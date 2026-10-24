package com.example.synclyrics

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Extracts the audio track to an AAC .m4a in cache. Call from the main thread (Transformer needs a Looper). */
@UnstableApi
suspend fun extractAudio(context: Context, source: Uri): File {
    val dir = File(context.cacheDir, "audio").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val out = File(dir, "audio_${System.currentTimeMillis()}.m4a")
    val item = EditedMediaItem.Builder(MediaItem.fromUri(source)).setRemoveVideo(true).build()
    return suspendCancellableCoroutine { cont ->
        val t = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cont.isActive) cont.resume(out)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    out.delete()
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            })
            .build()
        cont.invokeOnCancellation { t.cancel(); out.delete() }
        t.start(item, out.absolutePath)
    }
}

/**
 * Client for backend/ (see docs/API_CONTRACT.md). The backend holds the speech/translation provider keys;
 * [apiKey] here only identifies this app to your backend and is extractable from an APK, so prefer
 * per-user auth before a public release.
 */
class BackendClient(private val baseUrl: String, private val apiKey: String) {

    /** Uploads [audio], polls until done, returns the job JSON (feed to parseTranscriptJson). */
    suspend fun transcribe(
        audio: File, source: String, target: String, durationMs: Long?,
        onProgress: (String) -> Unit
    ): String {
        onProgress("Uploading audio…")
        val jobId = withContext(Dispatchers.IO) { submit(audio, source, target, durationMs) }
        val deadline = System.currentTimeMillis() + 15 * 60_000
        while (System.currentTimeMillis() < deadline) {
            val body = withContext(Dispatchers.IO) { get("/v1/jobs/$jobId") }
            val json = JSONObject(body)
            when (json.optString("status")) {
                "completed" -> return body
                "failed" -> throw IllegalStateException(json.optString("error", "Backend job failed"))
                else -> onProgress("Processing on server (${json.optString("status")})…")
            }
            delay(2_000)
        }
        throw IllegalStateException("Timed out waiting for the server")
    }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("x-api-key", apiKey)
        }

    private fun submit(audio: File, source: String, target: String, durationMs: Long?): String {
        val boundary = "----synclyrics${UUID.randomUUID()}"
        val c = open("/v1/jobs", "POST").apply {
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Idempotency-Key", UUID.randomUUID().toString())
        }
        try {
            c.outputStream.buffered().use { o ->
                fun field(name: String, value: String) =
                    o.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                field("sourceLanguage", source)
                field("targetLanguage", target)
                field("task", "transcribe_align_translate")
                durationMs?.let { field("durationMs", it.toString()) }
                o.write("--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"${audio.name}\"\r\nContent-Type: audio/mp4\r\n\r\n".toByteArray())
                audio.inputStream().use { it.copyTo(o) }
                o.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val body = read(c)
            return JSONObject(body).getString("jobId")
        } finally {
            c.disconnect()
        }
    }

    private fun get(path: String): String {
        val c = open(path, "GET")
        try { return read(c) } finally { c.disconnect() }
    }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
            throw IllegalStateException("Server error $code${if (msg.isNotBlank()) ": $msg" else ""}")
        }
        return text
    }
}
