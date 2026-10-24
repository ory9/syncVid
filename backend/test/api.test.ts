import { describe, expect, it } from "vitest";
import { createApp } from "../src/app.js";
import { JobManager } from "../src/jobs.js";
import { MockTranscriber, MockTranslator } from "../src/providers.js";
import type { Transcriber } from "../src/types.js";
import { existsSync } from "node:fs";
import type { Server } from "node:http";

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
