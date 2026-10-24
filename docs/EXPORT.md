# MP4 export implementation

The starter now ships a Media3 Transformer export (`VideoExporter`, `LyricsOverlay`); the notes below describe the alternative ASS/FFmpeg route and what a production export must cover. A production export must render frames with text at the frame's presentation timestamp and encode a new MP4.

## Recommended first implementation: FFmpeg-based renderer
Use a maintained Android-compatible FFmpeg integration, or render on a backend. Confirm current licensing and codec support before choosing a distribution. Do not bundle an unreviewed FFmpeg binary.

### Rendering concept
For each output frame at time `t`:
1. Draw video frame.
2. Find the active word for `t`.
3. Draw a translucent bottom panel.
4. Draw original transcript line with the active word highlighted.
5. Draw translated sentence underneath.
6. Encode frame and original audio to MP4.

A practical FFmpeg route is to generate timed ASS subtitles with per-word karaoke tags and burn them into the video. ASS supports styling and timed karaoke effects, but exact word timings need careful escaping and testing. For a highlight that changes fill word-by-word, create one timed subtitle event per word or generate karaoke tags from word timestamps.

## Export presets
- Social portrait: 1080x1920, 30 fps (only if input can be sensibly cropped/padded).
- Preserve source: original dimensions and frame rate where supported.
- Small file: H.264 video + AAC audio, configurable quality.

## Production requirements
- Render progress and cancellation.
- Keep audio/video sync; handle variable frame rate and rotation metadata.
- Preserve or intentionally transform aspect ratio.
- Avoid text under notches or platform overlays.
- Font fallback for all target scripts.
- Escape ASS special characters and line breaks.
- Write to app cache first; validate output, then copy to MediaStore.
- Test with long videos, low storage, interrupted jobs, and non-ASCII text.
- Show a preview of the export style before encoding.
