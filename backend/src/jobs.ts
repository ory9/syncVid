import { randomUUID } from "node:crypto";
import { rm } from "node:fs/promises";
import { groupIntoSegments, normalizeWords } from "./segmenter.js";
import type { JobStatus, Segment, Transcriber, Translator } from "./types.js";

export interface Job {
  jobId: string;
  status: JobStatus;
  sourceLanguage: string;
  targetLanguage: string;
  segments?: Segment[];
  warnings: string[];
  error?: string;
  createdAt: number;
}
export interface JobInput { path: string; mimeType: string; sourceLanguage: string; targetLanguage: string; durationMs?: number }

/** In-memory store + bounded-concurrency runner. Swap for a DB/queue before running multiple instances. */
export class JobManager {
  private jobs = new Map<string, Job>();
  private idem = new Map<string, string>();
  private queue: (() => Promise<void>)[] = [];
  private running = 0;

  constructor(
    private transcriber: Transcriber,
    private translator: Translator,
    private opts = { concurrency: 2, ttlMs: 60 * 60 * 1000 }
  ) {}

  get(id: string) { return this.jobs.get(id); }
  findByKey(key: string) { const id = this.idem.get(key); return id ? this.jobs.get(id) : undefined; }

  submit(input: JobInput, idempotencyKey?: string): Job {
    const job: Job = { jobId: `job_${randomUUID()}`, status: "queued", sourceLanguage: input.sourceLanguage, targetLanguage: input.targetLanguage, warnings: [], createdAt: Date.now() };
    this.jobs.set(job.jobId, job);
    if (idempotencyKey) this.idem.set(idempotencyKey, job.jobId);
    this.queue.push(() => this.run(job, input));
    this.pump();
    return job;
  }

  sweep(now = Date.now()) {
    for (const [id, j] of this.jobs) if (now - j.createdAt > this.opts.ttlMs && j.status !== "queued" && j.status !== "processing") this.jobs.delete(id);
    for (const [k, id] of this.idem) if (!this.jobs.has(id)) this.idem.delete(k);
  }

  private pump() {
    while (this.running < this.opts.concurrency && this.queue.length) {
      const task = this.queue.shift()!;
      this.running++;
      void task().finally(() => { this.running--; this.pump(); });
    }
  }

  private async run(job: Job, input: JobInput) {
    job.status = "processing";
    try {
      const raw = await this.transcriber.transcribe({ path: input.path, mimeType: input.mimeType, language: input.sourceLanguage });
      const { words, warnings } = normalizeWords(raw, input.durationMs);
      job.warnings.push(...warnings);
      if (!words.length) throw new Error("No speech detected");
      const segments = groupIntoSegments(words);
      const translations = await this.translator.translate(segments.map((s) => s.original), input.sourceLanguage, input.targetLanguage);
      segments.forEach((s, i) => { s.translation = translations[i] ?? ""; });
      job.segments = segments;
      job.status = "completed";
    } catch (e) {
      job.status = "failed";
      job.error = e instanceof Error ? e.message : "Processing failed";
    } finally {
      await rm(input.path, { force: true }); // retention: delete uploaded media once processed
    }
  }
}

export function toResponse(job: Job) {
  return {
    jobId: job.jobId, status: job.status,
    sourceLanguage: job.sourceLanguage, targetLanguage: job.targetLanguage,
    ...(job.segments ? { segments: job.segments } : {}),
    ...(job.warnings.length ? { warnings: job.warnings } : {}),
    ...(job.error ? { error: job.error } : {})
  };
}
