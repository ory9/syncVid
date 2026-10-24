# SyncLyrics Android Starter

A Kotlin + Jetpack Compose starter for importing a video, previewing it, and displaying bilingual transcript words synchronized to playback time. It includes a practical architecture and an export pipeline plan.

## What this starter does
- Opens a video using Android's Storage Access Framework.
- Plays the selected video using AndroidX Media3 ExoPlayer.
- Displays original and translated text together.
- Highlights each word based on its `startMs` / `endMs` timestamps.
- Imports a transcript from JSON (the `docs/API_CONTRACT.md` shape, or a bare `segments` array; optional root `audioOffsetMs`).
- Auto-scrolls to the current line, flags low-confidence words (underlined) and timing issues.
- **Exports an MP4** with the bilingual line and active-word highlight burned in (Media3 Transformer, H.264/AAC), saved to `Movies/SyncLyrics` and shareable.
- Includes a timestamped sample transcript and a basic transcript data model.

## What is intentionally not faked
Speech recognition, translation, forced alignment, and MP4 text burn-in are production integrations, not magic local functions. This starter defines clean seams for those services and documents how to implement them. Export burns in whatever timings you give it; it does not make them accurate.

## Requirements
- Android Studio (current stable)
- JDK 17
- Android SDK 35
- Internet access for Gradle dependency resolution

## Run
1. Open this folder in Android Studio.
2. Let Gradle sync.
3. Run on an Android 8.0+ device or emulator.
4. Tap **Choose video** and select a local video.
5. The included sample words are illustrative; replace them with a real transcript for your video.

## Project layout
- `app/src/main/java/com/example/synclyrics/MainActivity.kt` — Compose UI and player.
- `app/src/main/java/com/example/synclyrics/Transcript.kt` — word/sentence model and timing lookup.
- `docs/ARCHITECTURE.md` — full implementation plan.
- `docs/ALIGNMENT.md` — transcript timing approach.
- `app/.../Transcript.kt`, `TranscriptJson.kt` — model, lookup, validation, JSON import.
- `app/.../LyricsOverlay.kt`, `VideoExporter.kt` — burn-in renderer and export job.
- `docs/EXPORT.md` — MP4 rendering plan.
- `docs/API_CONTRACT.md` — suggested backend contract.

## Important notes
- Word timestamps must use the same media timeline as ExoPlayer (`positionMs`).
- The sample transcript does not represent a verified transcription or translation.
- For music, manually review alignment. Automatic speech recognition and forced alignment can be poor on singing, long held syllables, backing vocals, and noisy recordings.
- For real production, persist transcript edits and job state in Room/WorkManager and handle lifecycle/process death.

## Get an APK without Android Studio
Push this folder to a GitHub repo. The `Build` workflow runs the unit tests and builds a debug APK; download it from the run's **Artifacts** (`synclyrics-debug-apk`) and install it on a phone (allow "install unknown apps"). To bake in your deployed backend, set repo variable `BACKEND_URL` (https) and secret `BACKEND_API_KEY` before the run. If the build fails, the log shows the compile error.

## Backend (generate transcript)
`backend/` is a Node/Express/TypeScript service implementing `docs/API_CONTRACT.md`. The app's **Generate** button extracts audio, uploads it, polls, and loads the result. See `backend/README.md` (mock provider works offline; real quality depends on the provider you plug in).

## Export notes
- Uses Media3 `Transformer` + a bitmap overlay (no FFmpeg binary). Output is re-encoded H.264/AAC; HDR, trimming and speed edits are not handled.
- Overlay time = source timeline, the same clock as the preview.
- API 26–28 saves to app-specific storage (no storage permission requested); API 29+ saves to `Movies/SyncLyrics`.
- Verify on a real device with your own videos (portrait, rotated, long) before relying on it.
- No Gradle wrapper is included; Android Studio will create one (AGP 8.9.2 needs Gradle 8.11.1+).

## Building in GitHub Codespaces
Needs JDK 17, Gradle 8.11.1+ (e.g. `sdk install gradle 8.11.1`) and the Android SDK (`sdkmanager "platforms;android-35" "build-tools;35.0.0"`, set `ANDROID_HOME`).
`gradle.properties` is memory-tuned (1 worker, 3 GB heap, in-process Kotlin compiler) to limit memory spikes in Gradle workers. If a build still fails, inspect the first `What went wrong` and `Caused by` entries in the Gradle output:
```bash
gradle --stop
gradle assembleDebug --no-daemon --stacktrace 2>&1 | tee build.log
grep -n -E "What went wrong|Caused by|OutOfMemory|aapt2" build.log | head
```
