package com.example.synclyrics

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: Float) : ExportState
    data class Done(val savedTo: String, val shareUri: Uri) : ExportState
    data class Failed(val message: String) : ExportState
}

data class VideoInfo(val width: Int, val height: Int, val durationMs: Long)

/** Reads display dimensions (rotation applied) and duration. Returns null if unreadable. */
suspend fun readVideoInfo(context: Context, uri: Uri): VideoInfo? = withContext(Dispatchers.IO) {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(context, uri)
        fun meta(k: Int) = r.extractMetadata(k)?.toIntOrNull()
        var w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: return@withContext null
        var h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: return@withContext null
        val rot = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
        if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        VideoInfo(w, h, dur)
    } catch (e: Exception) {
        null
    } finally {
        runCatching { r.release() }
    }
}

/**
 * Burns the transcript into an MP4 using Media3 Transformer (H.264/AAC, no bundled FFmpeg).
 * Must be created and used on the main thread (Transformer requires a Looper thread).
 * Writes to app cache first, then copies to the Movies collection.
 */
@UnstableApi
class VideoExporter(private val context: Context) {
    private var transformer: Transformer? = null
    private var tempFile: File? = null
    @Volatile private var cancelled = false

    /** Suspends until done. Throws CancellationException if the caller is cancelled (export is cancelled too). */
    suspend fun export(
        source: Uri,
        segments: List<TranscriptSegment>,
        info: VideoInfo,
        onState: (ExportState) -> Unit
    ) {
        cancelled = false
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // drop stale exports
        val out = File(dir, "synclyrics_${System.currentTimeMillis()}.mp4")
        tempFile = out

        val overlay = LyricsOverlay(segments, info.width, info.height)
        val effects: List<Effect> = listOf(OverlayEffect(listOf(overlay)))
        val item = EditedMediaItem.Builder(MediaItem.fromUri(source))
            .setEffects(Effects(emptyList(), effects))
            .build()

        var failure: String? = null
        var finished = false
        val t = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { finished = true }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    failure = exportException.message ?: "Export failed (code ${exportException.errorCode})"
                    finished = true
                }
            })
            .build()
        transformer = t
        onState(ExportState.Running(0f))
        t.start(item, out.absolutePath)

        val holder = ProgressHolder()
        try {
            while (!finished && !cancelled) {
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onState(ExportState.Running(holder.progress / 100f))
                }
                delay(200)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            t.cancel(); out.delete(); transformer = null
            throw e
        }
        transformer = null

        if (cancelled) { out.delete(); onState(ExportState.Idle); return }
        failure?.let { out.delete(); onState(ExportState.Failed(it)); return }
        if (!out.exists() || out.length() == 0L) { onState(ExportState.Failed("Export produced an empty file")); return }

        try {
            val where = withContext(Dispatchers.IO) { saveToMovies(out) }
            val share = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
            onState(ExportState.Done(where, share))
        } catch (e: Exception) {
            onState(ExportState.Failed("Could not save video: ${e.message}"))
        }
    }

    /** Transformer.cancel() fires no listener callback, so we flag it and let export() unwind. */
    fun cancel() { cancelled = true; transformer?.cancel() }

    private fun saveToMovies(file: File): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/SyncLyrics")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return "Movies/SyncLyrics/${file.name}"
        }
        // API 26-28: no scoped storage and we avoid requesting WRITE_EXTERNAL_STORAGE; use app-specific storage.
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val dest = File(dir, file.name)
        file.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }
}
