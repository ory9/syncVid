# SyncLyrics backend

Implements `docs/API_CONTRACT.md`: upload audio → transcribe → word timestamps → sentence translation → poll result.

```
cd backend && npm install
ALLOW_NO_AUTH=1 npm run dev                  # mock provider, offline, fake words (for wiring the app)
API_KEY=changeme PROVIDER=openai OPENAI_API_KEY=sk-... npm run dev   # real providers
npm run typecheck && npm run lint && npm test && npm run build
```

Android emulator reaches it at `http://10.0.2.2:8080` (default). For a device/release build set
`synclyrics.backendUrl` (https) and `synclyrics.apiKey` in `~/.gradle/gradle.properties`.

## Env
| var | meaning |
|---|---|
| `API_KEY` | required `x-api-key` header value (or `ALLOW_NO_AUTH=1` for local dev only) |
| `PROVIDER` | `mock` (default) or `openai` |
| `OPENAI_API_KEY`, `OPENAI_BASE_URL` | any OpenAI-compatible endpoint |
| `TRANSCRIBE_MODEL`, `TRANSLATE_MODEL` | defaults `whisper-1`, `gpt-4o-mini` |
| `WHISPER_LANGS` | language codes passed to the recognizer; others are auto-detected |

## What it does
- Cleans provider timings to the app's invariants (sorted, no overlaps, `end > start`, clamped to `durationMs`), warns on low confidence.
- Groups words into lines (pause > 0.8 s, sentence punctuation, 12-word cap), translates per line.
- Idempotency-Key, upload size limit, constant-time key check, uploaded audio deleted after processing (success or failure), job TTL 1 h.

## Limits you must know
- **Luganda quality**: Whisper does not officially support Luganda; expect poor output. Provider code is isolated in `src/providers.ts` — implement `Transcriber` for whichever service you validate on your own audio. Singing is worse than speech; always review timings in the app.
- Word confidence is derived from the segment's `avg_logprob`, a rough proxy.
- Job store is in memory (single instance). Use a DB/queue to scale or survive restarts.
- Run behind HTTPS.
