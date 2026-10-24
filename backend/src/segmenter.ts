import type { RawWord, Segment, Word } from "./types.js";

export const MIN_WORD_MS = 40;

/**
 * Makes provider output safe for the client's invariants (start >= 0, end > start, sorted,
 * no overlaps) and clamps to the media duration when known. Returns cleaned words + warnings.
 */
export function normalizeWords(raw: RawWord[], durationMs?: number): { words: Word[]; warnings: string[] } {
  const warnings: string[] = [];
  const sorted = raw
    .filter((w) => w.text.trim().length > 0 && Number.isFinite(w.startMs) && Number.isFinite(w.endMs))
    .map((w) => ({ ...w, text: w.text.trim(), startMs: Math.max(0, Math.round(w.startMs)), endMs: Math.round(w.endMs) }))
    .sort((a, b) => a.startMs - b.startMs);
  if (sorted.length !== raw.length) warnings.push(`Dropped ${raw.length - sorted.length} invalid/blank word(s)`);

  const out: Word[] = [];
  let clamped = 0, dropped = 0, fixed = 0;
  for (const w of sorted) {
    let { startMs, endMs } = w;
    const prev = out[out.length - 1];
    if (prev && startMs < prev.endMs) { // overlap: push start forward
      startMs = prev.endMs; fixed++;
    }
    if (durationMs !== undefined) {
      if (startMs >= durationMs) { dropped++; continue; }
      if (endMs > durationMs) { endMs = durationMs; clamped++; }
    }
    if (endMs - startMs < MIN_WORD_MS) {
      endMs = startMs + MIN_WORD_MS; fixed++;
      if (durationMs !== undefined && endMs > durationMs) { dropped++; continue; }
    }
    out.push({ text: w.text, startMs, endMs, ...(w.confidence !== undefined ? { confidence: round(w.confidence) } : {}) });
  }
  if (fixed) warnings.push(`Adjusted ${fixed} word timing(s) (overlap / too short)`);
  if (clamped) warnings.push(`Clamped ${clamped} word(s) to media duration`);
  if (dropped) warnings.push(`Dropped ${dropped} word(s) beyond media duration`);
  const low = out.filter((w) => w.confidence !== undefined && w.confidence < 0.6).length;
  if (low) warnings.push(`${low} low-confidence word(s); review recommended`);
  return { words: out, warnings };
}

export interface SegmentOptions { maxGapMs: number; maxWords: number }
const SENTENCE_END = /[.!?…。]$/;

/** Groups words into lines: new line on a long pause, sentence-ending punctuation, or word cap. */
export function groupIntoSegments(words: Word[], opts: SegmentOptions = { maxGapMs: 800, maxWords: 12 }): Segment[] {
  const segments: Segment[] = [];
  let cur: Word[] = [];
  const flush = () => {
    if (!cur.length) return;
    segments.push({ id: `segment-${segments.length + 1}`, original: cur.map((w) => w.text).join(" "), translation: "", words: cur });
    cur = [];
  };
  for (const w of words) {
    const prev = cur[cur.length - 1];
    if (prev && (w.startMs - prev.endMs > opts.maxGapMs || cur.length >= opts.maxWords)) flush();
    cur.push(w);
    if (SENTENCE_END.test(w.text)) flush();
  }
  flush();
  return segments;
}

const round = (n: number) => Math.round(Math.min(1, Math.max(0, n)) * 1000) / 1000;
