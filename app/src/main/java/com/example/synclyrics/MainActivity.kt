package com.example.synclyrics

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val Ink = Color(0xFF14151A)
private val Paper = Color(0xFFF8F4E8)
private val BlueInk = Color(0xFF3347A8)
private val Highlight = Color(0xFFFFD45C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = BlueInk)) {
                SyncLyricsScreen()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, UnstableApi::class)
@Composable
private fun SyncLyricsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Survive process death / config changes.
    var videoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var transcriptJson by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf("Choose a video to begin") }

    var positionMs by remember { mutableLongStateOf(0L) }
    var exportState by remember { mutableStateOf<ExportState>(ExportState.Idle) }
    var videoInfo by remember { mutableStateOf<VideoInfo?>(null) }
    var generating by remember { mutableStateOf(false) }

    val transcript: List<TranscriptSegment> = remember(transcriptJson) {
        transcriptJson?.let { runCatching { parseTranscriptJson(it) }.getOrNull() } ?: sampleTranscript()
    }
    val usingSample = transcriptJson == null
    val issues = remember(transcript, videoInfo) { validateTranscript(transcript, videoInfo?.durationMs) }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            videoUri = uri
            exportState = ExportState.Idle
            status = if (usingSample) "Video selected. Sample timestamps are illustrative; import a transcript JSON."
            else "Video selected."
        }
    }

    val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            }.mapCatching { text ->
                parseTranscriptJson(text) // validate before accepting
                text
            }.onSuccess {
                transcriptJson = it
                status = "Transcript imported."
            }.onFailure {
                status = "Import failed: ${it.message}"
            }
        }
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    positionMs = newPosition.positionMs.coerceAtLeast(0L)
                }
            })
        }
    }
    val exporter = remember { VideoExporter(context.applicationContext) }

    LaunchedEffect(videoUri) {
        val uri = videoUri
        if (uri == null) {
            player.clearMediaItems()
            videoInfo = null
            return@LaunchedEffect
        }
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = false
        videoInfo = readVideoInfo(context, uri)
        if (videoInfo == null) status = "Could not read video metadata; export may be unavailable."
    }

    // Position clock: poll only while playing; seeks while paused arrive via onPositionDiscontinuity.
    LaunchedEffect(player) {
        while (isActive) {
            if (player.isPlaying) positionMs = player.currentPosition.coerceAtLeast(0L)
            delay(33)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    DisposableEffect(player) {
        onDispose {
            exporter.cancel()
            player.release()
        }
    }

    val listState = rememberLazyListState()
    val currentSegment = currentSegmentIndex(transcript, positionMs)
    LaunchedEffect(currentSegment) {
        if (currentSegment >= 0) listState.animateScrollToItem(currentSegment)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Ink).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ClosedCaption, contentDescription = null, tint = Highlight)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("SyncLyrics", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Video · Lyrics · Translation", color = Color.LightGray, fontSize = 12.sp)
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth().height(220.dp).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (videoUri != null) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            PlayerView(viewContext).apply {
                                this.player = player
                                useController = true
                            }
                        },
                        update = { it.player = player }
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Your video appears here", color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { videoPicker.launch(arrayOf("video/*")) }) {
                            Icon(Icons.Default.FileUpload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Choose video")
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Translate, contentDescription = null, tint = BlueInk)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Original transcript", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (issues.isNotEmpty()) {
                        Text("${issues.size} timing issue(s) to review", fontSize = 11.sp, color = Color(0xFFB3261E))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = videoUri != null && !generating,
                    onClick = {
                        val uri = videoUri ?: return@TextButton
                        player.pause()
                        generating = true
                        scope.launch {
                            try {
                                status = "Extracting audio…"
                                val audio = extractAudio(context, uri)
                                try {
                                    val json = BackendClient(BuildConfig.BACKEND_URL, BuildConfig.BACKEND_API_KEY)
                                        .transcribe(audio, "lg", "en", videoInfo?.durationMs) { status = it }
                                    parseTranscriptJson(json) // validate before accepting
                                    transcriptJson = json
                                    status = "Transcript generated. Review timings before export."
                                } finally {
                                    audio.delete()
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                status = "Generate failed: ${e.message}"
                            } finally {
                                generating = false
                            }
                        }
                    }
                ) { Text(if (generating) "Working…" else "Generate", fontSize = 12.sp) }
                TextButton(onClick = { jsonPicker.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                    Text(if (usingSample) "Import JSON" else "Replace JSON", fontSize = 12.sp)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(transcript, key = { it.id }) { segment ->
                    val activeIndex = activeWordIndex(segment.words, positionMs)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                segment.words.forEachIndexed { index, word ->
                                    val active = index == activeIndex
                                    Text(
                                        text = word.text,
                                        color = if (active) Ink else BlueInk,
                                        fontSize = 20.sp,
                                        fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium,
                                        textDecoration = if (word.isLowConfidence) TextDecoration.Underline else null,
                                        modifier = Modifier
                                            .background(
                                                color = if (active) Highlight else Color.Transparent,
                                                shape = RoundedCornerShape(4.dp)
                                            )
                                            .clickable {
                                                player.seekTo(word.startMs)
                                                positionMs = word.startMs
                                            }
                                            .padding(horizontal = 2.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            if (segment.translation.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(segment.translation, color = Color.DarkGray, fontSize = 16.sp)
                            }
                        }
                    }
                }
            }

            Column(Modifier.fillMaxWidth().background(Color(0xFFE9E4D8)).padding(12.dp)) {
                val es = exportState
                if (es is ExportState.Running) {
                    LinearProgressIndicator(progress = { es.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when (es) {
                            is ExportState.Running -> "Exporting… ${(es.progress * 100).toInt()}%"
                            is ExportState.Done -> "Saved to ${es.savedTo}"
                            is ExportState.Failed -> "Export failed: ${es.message}"
                            ExportState.Idle -> status
                        },
                        fontSize = 12.sp, color = Color.DarkGray, modifier = Modifier.weight(1f)
                    )
                    when (es) {
                        is ExportState.Running -> OutlinedButton(onClick = { exporter.cancel() }) { Text("Cancel") }
                        is ExportState.Done -> {
                            OutlinedButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, es.shareUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(send, "Share video"))
                            }) { Text("Share") }
                        }
                        else -> OutlinedButton(
                            enabled = videoUri != null && videoInfo != null,
                            onClick = {
                                val uri = videoUri ?: return@OutlinedButton
                                val info = videoInfo ?: return@OutlinedButton
                                player.pause()
                                scope.launch {
                                    try {
                                        exporter.export(uri, transcript, info) { exportState = it }
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        exportState = ExportState.Idle
                                        throw e
                                    } catch (e: Exception) {
                                        exportState = ExportState.Failed(e.message ?: "unknown error")
                                    }
                                }
                            }
                        ) { Text("Export MP4") }
                    }
                }
            }
        }
    }
}
