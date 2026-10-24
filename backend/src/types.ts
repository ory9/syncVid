export interface RawWord { text: string; startMs: number; endMs: number; confidence?: number }
export interface Word { text: string; startMs: number; endMs: number; confidence?: number }
export interface Segment { id: string; original: string; translation: string; words: Word[] }
export type JobStatus = "queued" | "processing" | "completed" | "failed";

export interface Transcriber {
  /** Returns words with timings on the audio's own timeline (ms). */
  transcribe(input: { path: string; mimeType: string; language: string }): Promise<RawWord[]>;
}
export interface Translator {
  /** One translation per input line, same order and length. */
  translate(lines: string[], source: string, target: string): Promise<string[]>;
}
