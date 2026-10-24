import { readFile } from "node:fs/promises";
import { basename } from "node:path";
import type { RawWord, Transcriber, Translator } from "./types.js";

/** Deterministic offline provider for local development and tests. Not real recognition. */
export class MockTranscriber implements Transcriber {
  async transcribe(): Promise<RawWord[]> {
    const text = ["Mukama", "obeéranga", "nange", "nomponya", "Ejoogo", "ly'omubi."];
    let t = 0;
    return text.map((w, i) => {
      const start = t + (i === 4 ? 300 : 0);
      t = start + 700;
      return { text: w, startMs: start, endMs: t, confidence: i === 1 ? 0.5 : 0.9 };
    });
  }
}
export class MockTranslator implements Translator {
  async translate(lines: string[]): Promise<string[]> { return lines.map((l) => `[mock translation] ${l}`); }
}

const OPENAI = process.env.OPENAI_BASE_URL ?? "https://api.openai.com/v1";

/**
 * OpenAI Whisper-compatible transcription (verbose_json with word timestamps).
 * Whisper does not officially support every language (e.g. Luganda): `languages` lists codes it
 * may be told about; others are auto-detected. Evaluate quality on your own audio, and swap this
 * class for another provider if results are poor.
 */
export class OpenAITranscriber implements Transcriber {
  constructor(
    private apiKey: string,
    private model = process.env.TRANSCRIBE_MODEL ?? "whisper-1",
    private languages = new Set((process.env.WHISPER_LANGS ?? "en,sw,fr,es,pt,ar,hi").split(","))
  ) {}

  async transcribe({ path, mimeType, language }: { path: string; mimeType: string; language: string }): Promise<RawWord[]> {
    const form = new FormData();
    form.set("file", new Blob([await readFile(path)], { type: mimeType }), basename(path));
    form.set("model", this.model);
    form.set("response_format", "verbose_json");
    form.append("timestamp_granularities[]", "word");
    form.append("timestamp_granularities[]", "segment");
    if (this.languages.has(language)) form.set("language", language);

    const res = await fetch(`${OPENAI}/audio/transcriptions`, { method: "POST", headers: { Authorization: `Bearer ${this.apiKey}` }, body: form });
    if (!res.ok) throw new Error(`Transcription provider error ${res.status}`);
    const data = (await res.json()) as {
      words?: { word: string; start: number; end: number }[];
      segments?: { start: number; end: number; avg_logprob?: number }[];
    };
    const segs = data.segments ?? [];
    return (data.words ?? []).map((w) => {
      const seg = segs.find((s) => w.start >= s.start - 0.01 && w.start < s.end + 0.01);
      const confidence = seg?.avg_logprob !== undefined ? Math.exp(seg.avg_logprob) : undefined;
      return { text: w.word, startMs: w.start * 1000, endMs: w.end * 1000, ...(confidence !== undefined ? { confidence } : {}) };
    });
  }
}

/** Sentence-level translation through an OpenAI-compatible chat endpoint. */
export class OpenAITranslator implements Translator {
  constructor(private apiKey: string, private model = process.env.TRANSLATE_MODEL ?? "gpt-4o-mini") {}

  async translate(lines: string[], source: string, target: string): Promise<string[]> {
    if (!lines.length) return [];
    const res = await fetch(`${OPENAI}/chat/completions`, {
      method: "POST",
      headers: { Authorization: `Bearer ${this.apiKey}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        model: this.model,
        response_format: { type: "json_object" },
        messages: [
          { role: "system", content: `Translate each line from ${source} to ${target}. Keep meaning and register; these are sung/spoken lines, translate per line, not word for word. Reply as JSON: {"translations": [string, ...]} with exactly ${lines.length} items in order.` },
          { role: "user", content: JSON.stringify(lines) }
        ]
      })
    });
    if (!res.ok) throw new Error(`Translation provider error ${res.status}`);
    const data = (await res.json()) as { choices?: { message?: { content?: string } }[] };
    const parsed = JSON.parse(data.choices?.[0]?.message?.content ?? "{}") as { translations?: unknown };
    const t = parsed.translations;
    if (!Array.isArray(t) || t.length !== lines.length || t.some((x) => typeof x !== "string")) {
      throw new Error("Translation provider returned an unexpected shape");
    }
    return t as string[];
  }
}
