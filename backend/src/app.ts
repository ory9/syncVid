import { timingSafeEqual } from "node:crypto";
import { mkdirSync } from "node:fs";
import { rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import express, { type NextFunction, type Request, type Response } from "express";
import multer from "multer";
import { z } from "zod";
import { JobManager, toResponse } from "./jobs.js";

const LANG = z.string().regex(/^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$/, "invalid language tag");
const Fields = z.object({
  sourceLanguage: LANG,
  targetLanguage: LANG,
  task: z.literal("transcribe_align_translate"),
  durationMs: z.coerce.number().int().positive().max(6 * 3600_000).optional()
});

export interface AppOptions { jobs: JobManager; apiKey?: string; maxUploadBytes?: number }

function keyMatches(given: string | undefined, expected: string) {
  if (!given) return false;
  const a = Buffer.from(given), b = Buffer.from(expected);
  return a.length === b.length && timingSafeEqual(a, b);
}

export function createApp({ jobs, apiKey, maxUploadBytes = 100 * 1024 * 1024 }: AppOptions) {
  const dir = join(tmpdir(), "synclyrics-uploads");
  mkdirSync(dir, { recursive: true });
  const upload = multer({ dest: dir, limits: { fileSize: maxUploadBytes, files: 1 } });
  const app = express();
  app.disable("x-powered-by");

  app.get("/healthz", (_req, res) => { res.json({ ok: true }); });

  app.use("/v1", (req: Request, res: Response, next: NextFunction) => {
    if (apiKey && !keyMatches(req.header("x-api-key"), apiKey)) { res.status(401).json({ error: "unauthorized" }); return; }
    next();
  });

  app.post("/v1/jobs", upload.single("audio"), async (req, res) => {
    const file = req.file;
    const cleanup = () => (file ? rm(file.path, { force: true }) : Promise.resolve());
    if (!file) { res.status(400).json({ error: "audio file is required (field: audio)" }); return; }
    const parsed = Fields.safeParse(req.body);
    if (!parsed.success) { await cleanup(); res.status(400).json({ error: parsed.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`).join("; ") }); return; }
    if (!/^(audio|video)\//.test(file.mimetype)) { await cleanup(); res.status(415).json({ error: "audio/* upload expected" }); return; }

    const key = req.header("idempotency-key");
    const existing = key ? jobs.findByKey(key) : undefined;
    if (existing) { await cleanup(); res.status(200).json({ jobId: existing.jobId, status: existing.status }); return; }

    const job = jobs.submit({ path: file.path, mimeType: file.mimetype, ...parsed.data }, key);
    res.status(202).json({ jobId: job.jobId, status: job.status });
  });

  app.get("/v1/jobs/:id", (req, res) => {
    const job = jobs.get(req.params.id);
    if (!job) { res.status(404).json({ error: "job not found (expired or unknown)" }); return; }
    res.json(toResponse(job));
  });

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  app.use((err: unknown, _req: Request, res: Response, _next: NextFunction) => {
    if (err instanceof multer.MulterError) { res.status(err.code === "LIMIT_FILE_SIZE" ? 413 : 400).json({ error: err.message }); return; }
    res.status(500).json({ error: "internal error" });
  });
  return app;
}
