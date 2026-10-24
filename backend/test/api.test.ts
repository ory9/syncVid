import { describe, expect, it } from "vitest";
import { existsSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { Server } from "node:http";
import { createApp } from "../src/app.js";
import { JobManager } from "../src/jobs.js";
import { MockTranscriber, MockTranslator, OpenAITranscriber, OpenAITranslator } from "../src/providers.js";
import type { Transcriber } from "../src/types.js";

async function withServer<T>(jobs: JobManager, apiKey: string | undefined, fn: (base: string) => Promise<T>) {
  const server: Server = createApp({ jobs, apiKey, maxUploadBytes: 1024 }).listen(0);
  await new Promise((r) => server.once("listening", r));
  const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  try { return await fn(base); } finally { server.close(); }
}
const form = (extra: Record<string, string> = {}, bytes = 10) => {
  const f = new FormData();
  f.set("audio", new Blob([new Uint8Array(bytes)], { type: "audio/mp4" }), "a.m4a");
  f.set("sourceLanguage", "lg"); f.set("targetLanguage", "en"); f.set("task", "transcribe_align_translate");
  for (const [k, v] of Object.entries(extra)) f.set(k, v);
  return f;
};
const poll = async (base: string, id: string, headers: Record<string, string> = {}) => {
  for (let i = 0; i < 50; i++) {
    const j = await (await fetch(`${base}/v1/jobs/${id}`, { headers })).json() as { status: string };
    if (j.status === "completed" || j.status === "failed") return j as Record<string, unknown>;
    await new Promise((r) => setTimeout(r, 20));
  }
  throw new Error("timeout");
};
const mk = (t: Transcriber = new MockTranscriber()) => new JobManager(t, new MockTranslator());

describe("OpenAI providers", () => {
  it("sends bearer auth headers to OpenAI-compatible endpoints", async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = [];
    const originalFetch = globalThis.fetch;
    const tmp = mkdtempSync(join(tmpdir(), "synclyrics-"));
    const audioPath = join(tmp, "sample.m4a");
    writeFileSync(audioPath, new Uint8Array([1, 2, 3, 4]));

    globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(input), init });
      if (String(input).includes("/audio/transcriptions")) {
        return new Response(JSON.stringify({ words: [{ word: "hello", start: 0, end: 1 }] }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      return new Response(JSON.stringify({ choices: [{ message: { content: JSON.stringify({ translations: ["hola"] }) } }] }), { status: 200, headers: { "Content-Type": "application/json" } });
    };

    try {
      const transcriber = new OpenAITranscriber("sk-test");
      const words = await transcriber.transcribe({ path: audioPath, mimeType: "audio/m4a", language: "en" });
      const translator = new OpenAITranslator("sk-test");
      const translations = await translator.translate(["hello"], "en", "es");
      expect(words[0]?.text).toBe("hello");
      expect(translations).toEqual(["hola"]);
      const firstAuth = new Headers(calls[0]?.init?.headers ?? {}).get("Authorization");
      const secondAuth = new Headers(calls[1]?.init?.headers ?? {}).get("Authorization");
      expect(firstAuth).toBe("Bearer " + "sk-" + "test");
      expect(secondAuth).toBe("Bearer " + "sk-" + "test");
    } finally {
      globalThis.fetch = originalFetch;
    }
  });
});

describe("API", () => {
  it("runs a job end to end in the API_CONTRACT shape", async () => {
    await withServer(mk(), undefined, async (base) => {
      const res = await fetch(`${base}/v1/jobs`, { method: "POST", body: form() });
      expect(res.status).toBe(202);
      const { jobId } = (await res.json()) as { jobId: string };
      const job = await poll(base, jobId) as { status: string; segments: { original: string; translation: string; words: { startMs: number; endMs: number }[] }[] };
      expect(job.status).toBe("completed");
      expect(job.segments.length).toBeGreaterThan(0);
      expect(job.segments[0]!.translation).toMatch(/mock translation/);
      for (const s of job.segments) for (const w of s.words) expect(w.endMs).toBeGreaterThan(w.startMs);
    });
  });
  it("requires the API key", async () => {
    await withServer(mk(), "secret", async (base) => {
      expect((await fetch(`${base}/v1/jobs/x`)).status).toBe(401);
      expect((await fetch(`${base}/v1/jobs/x`, { headers: { "x-api-key": "secret" } })).status).toBe(404);
    });
  });
  it("validates input and enforces upload limit", async () => {
    await withServer(mk(), undefined, async (base) => {
      expect((await fetch(`${base}/v1/jobs`, { method: "POST", body: form({ sourceLanguage: "!!" }) })).status).toBe(400);
      expect((await fetch(`${base}/v1/jobs`, { method: "POST", body: form({}, 5000) })).status).toBe(413);
    });
  });
  it("is idempotent per Idempotency-Key", async () => {
    await withServer(mk(), undefined, async (base) => {
      const a = await (await fetch(`${base}/v1/jobs`, { method: "POST", headers: { "Idempotency-Key": "k1" }, body: form() })).json() as { jobId: string };
      const b = await (await fetch(`${base}/v1/jobs`, { method: "POST", headers: { "Idempotency-Key": "k1" }, body: form() })).json() as { jobId: string };
      expect(b.jobId).toBe(a.jobId);
    });
  });
  it("reports provider failure and still deletes the upload", async () => {
    let seen = "";
    const failing: Transcriber = { async transcribe({ path }) { seen = path; throw new Error("boom"); } };
    await withServer(mk(failing), undefined, async (base) => {
      const { jobId } = await (await fetch(`${base}/v1/jobs`, { method: "POST", body: form() })).json() as { jobId: string };
      const job = await poll(base, jobId);
      expect(job.status).toBe("failed"); expect(job.error).toBe("boom");
      expect(existsSync(seen)).toBe(false);
    });
  });
});
