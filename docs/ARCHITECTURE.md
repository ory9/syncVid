# Architecture and implementation plan

## User flow
1. User imports a local video.
2. App creates a project and extracts or uploads audio for transcription.
3. Speech service returns a transcript; alignment service assigns word start/end times.
4. Translation service produces sentence-level translation.
5. User reviews transcript and timing in a waveform/timeline editor.
6. Player highlights the active word and scrolls the active line into view.
7. Export renderer burns the bilingual transcript and current-word highlight into an MP4.
8. Exported file is saved to MediaStore and shared using Android Sharesheet.

## Modules
- `player`: Media3 playback and position clock.
- `transcript`: immutable word/sentence models, active-word lookup, editor.
- `ingest`: document picker, metadata, audio extraction.
- `jobs`: WorkManager jobs with progress and retry state.
- `speech`: transcription API adapter.
- `alignment`: forced-alignment adapter plus manual corrections.
- `translation`: translation API adapter.
- `render`: export job and render configuration.
- `storage`: Room entities, project repository, MediaStore writer.

## Suggested milestone plan
### M1 — Interactive prototype (2–4 days)
- Import video, playback, sample transcript, active-word highlighting.
- Verify timestamps follow seek and playback.
- Replace sample transcript with editable JSON.

### M2 — Real transcription and translation (4–8 days)
- Define backend contract.
- Upload audio, receive transcript and sentence translation.
- Handle long-running jobs and errors.

### M3 — Timing review (4–7 days)
- Timeline with word start/end fields.
- Tap a word to seek; nudge timing ±50/100 ms.
- Split/merge words and replay short context.
- Persist edits locally.

### M4 — MP4 export (5–10 days)
- Render at source resolution or chosen output preset.
- Burn original line and translation line into a safe-area panel.
- Highlight current word based on render timestamp.
- Encode, verify, save, share.

### M5 — Reliability and release (1–2 weeks)
- Lifecycle tests, low-storage handling, cancellation, retries.
- Test portrait/landscape, variable frame rate, long videos, rotation, Bluetooth audio.
- Privacy disclosures, upload consent, copyright reminders, accessibility.

## Data flow
Video URI -> audio extraction -> transcription -> alignment -> translation -> human review -> render.

Keep transcript text independent of video rendering. Never use translated word positions as a substitute for original audio timing; align the original transcript first.
