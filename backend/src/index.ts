import { createApp } from "./app.js";
import { JobManager } from "./jobs.js";
import { MockTranscriber, MockTranslator, OpenAITranscriber, OpenAITranslator } from "./providers.js";

const provider = process.env.PROVIDER ?? "mock";
const openaiKey = process.env.OPENAI_API_KEY;
if (provider === "openai" && !openaiKey) throw new Error("PROVIDER=openai requires OPENAI_API_KEY");

const apiKey = process.env.API_KEY;
if (!apiKey && process.env.ALLOW_NO_AUTH !== "1") throw new Error("Set API_KEY (or ALLOW_NO_AUTH=1 for local development only)");

const jobs = provider === "openai"
  ? new JobManager(new OpenAITranscriber(openaiKey!), new OpenAITranslator(openaiKey!))
  : new JobManager(new MockTranscriber(), new MockTranslator());
setInterval(() => jobs.sweep(), 60_000).unref();

const port = Number(process.env.PORT ?? 8080);
createApp({ jobs, apiKey }).listen(port, () => console.log(`SyncLyrics backend on :${port} (provider=${provider})`));
