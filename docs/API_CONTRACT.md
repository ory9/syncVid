# Suggested backend API contract

The backend is an adapter layer; choose the actual transcription, alignment, and translation providers after evaluating source-language quality, cost, privacy, and latency.

## POST /v1/jobs
Multipart fields:
- `audio`: extracted audio file
- `sourceLanguage`: BCP-47 or provider language identifier, e.g. `lg`
- `targetLanguage`: e.g. `en`
- `task`: `transcribe_align_translate`

Response:
```json
{
  "jobId": "job_123",
  "status": "queued"
}
```

## GET /v1/jobs/{jobId}
Response:
```json
{
  "jobId": "job_123",
  "status": "completed",
  "sourceLanguage": "lg",
  "targetLanguage": "en",
  "segments": [
    {
      "id": "segment-1",
      "original": "Mukama obeéranga nange",
      "translation": "Lord, you have always been with me",
      "words": [
        {"text": "Mukama", "startMs": 0, "endMs": 700, "confidence": 0.91},
        {"text": "obeéranga", "startMs": 700, "endMs": 1450, "confidence": 0.72},
        {"text": "nange", "startMs": 1450, "endMs": 1900, "confidence": 0.94}
      ]
    }
  ]
}
```

## Design notes
- Authenticate users and use signed upload URLs for large files.
- Keep provider API keys on the backend, never in the APK.
- Use idempotency keys, job status polling or push updates, retry rules, and upload limits.
- Set a retention policy and delete uploaded media when processing is complete unless the user opts in to storage.
- Validate all returned timestamps against media duration.
- Return confidence and warnings for weak transcription/alignment.
