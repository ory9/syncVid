# Realistic transcript alignment

## Recommended pipeline
1. **Transcribe in the source language.** Ask the speech service for segment timestamps if supported. Preserve punctuation and confidence where available.
2. **Normalize carefully.** Keep a display string and a matching/alignment token form. Do not strip apostrophes or diacritics from the display text.
3. **Align the corrected transcript to audio.** Forced alignment takes known text plus audio and estimates word boundaries. It is usually more repeatable than trying to infer word times from translated text.
4. **Map timestamps to the video timeline.** Audio may start after the video begins or be trimmed. Store `audioOffsetMs` and apply it consistently.
5. **Review.** Show waveform and word start/end times. Let users tap a word to replay a short context window, nudge start/end, split, merge, or re-align a sentence.
6. **Export using the same timestamps.** Renderer time and preview time must refer to the same media timeline.

## Speech vs. music
- Ordinary speech: automatic word timestamps can be a useful first pass.
- Singing: use a lyric-aware transcription model if available, then forced alignment against the known lyrics and manual review.
- Backing vocals, instrumental gaps, long vowels, melisma, and repeated choruses often cause poor word boundaries.
- A recognizer may hallucinate words over music. Flag low confidence instead of silently accepting it.
- Translation is normally sentence-level. Do not try to align each translated word one-to-one with the source; word order and grammar differ.

## Timestamp schema
All times are integer milliseconds relative to the video timeline.
```json
{
  "id": "word-001",
  "text": "Mukama",
  "startMs": 0,
  "endMs": 700,
  "confidence": 0.93
}
```
Use half-open intervals `[startMs, endMs)`. At playback position `t`, a word is active when `startMs <= t && t < endMs`. At pauses, no word may be active.

## Timing quality checks
- `startMs >= 0`
- `endMs > startMs`
- Word intervals should be sorted.
- Detect overlaps and gaps; do not assume every gap is an error.
- Flag implausibly short/long word durations for review.
- Include tests for seeking backward, seeking forward, pausing, and playback speed changes.
