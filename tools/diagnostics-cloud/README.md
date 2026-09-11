# Private diagnostics storage — backend slice only

**Not deployed. No Android client or automatic upload is included.** This isolated Worker
uses the existing `spoty-diagnostics` D1 database. It does not use R2 or modify Prode.
The app must sanitize and preview reports and obtain explicit consent before sending them.
This service treats text as opaque; it does not claim to remove credentials or personal data.

## Contract

All requests use HTTPS and `Authorization: Bearer <token>`. Never put tokens in URLs.

| Endpoint | Capability | Result |
| --- | --- | --- |
| `POST /reports` | `UPLOAD_TOKEN` only | 201 JSON `{id, expires_at}`; expiry is Unix seconds |
| `GET /reports` | `READ_TOKEN` only | JSON metadata list, newest first, no bodies |
| `GET /reports/{uuid}` | `READ_TOKEN` only | UTF-8 attachment; 404 if missing or expired |

Uploads accept only `text/plain` or `text/plain; charset=utf-8`, including JSON serialized
as plain text. Bodies must be valid UTF-8, nonempty and at most 256 KiB of received bytes.
Compressed bodies are rejected. Streaming limits do not trust Content-Length.
Errors: 400 invalid text, 401 unauthorized, 404 unknown route/report, 413 too large,
415 unsupported format, 429 storage full, 503 missing configuration or database failure.
Responses are non-cacheable; no CORS access or public report URLs are provided.

## Deployment preparation (operator action, not performed by tests)

Use an installed, authenticated Wrangler CLI from this directory; verify the account and
database identifiers in `wrangler.toml` before any remote command.

1. Generate two independent high-entropy secrets, each at least 32 characters
   (for example, generate 32 random bytes and hex-encode them).
2. Set them interactively: `wrangler secret put UPLOAD_TOKEN` and
   `wrangler secret put READ_TOKEN`. Never commit them, embed them in an APK or paste them in logs.
3. Apply schema: `wrangler d1 migrations apply spoty-diagnostics --remote`.
4. Deploy only after authorization: `wrangler deploy`.
5. Smoke-test role separation, expiry, and download using synthetic non-sensitive text.

Missing, short or identical secrets disable HTTP access. Rotate capabilities with secret put.
The read capability stays with the operator; secure upload credential provisioning is a
pending client-design decision. An upload token can consume capacity but cannot read reports.

## Retention, bounds and proof

At most 100 rows (up to 25 MiB of payload) are accepted. Expired rows are removed in the
same transactional D1 batch as insertion; the INSERT itself checks capacity atomically.
Expired reports are immediately excluded from reads; hourly cleanup deletes stored rows.
Physical deletion can lag if scheduled execution fails; D1 backup retention is separate.
Worker observability is disabled and code never logs bodies or authorization headers.
The 10 ms CPU setting and bounded storage are not a guarantee against account quota exhaustion;
stolen upload capabilities and request floods require operator revocation/access controls.

Run from repository root: `node --test tools/diagnostics-cloud/*.test.mjs` (Node 24).
Tests execute the actual schema and SQL with `node:sqlite`, adapting only D1 method shapes.
They do not prove deployed D1 scheduling, edge CPU use or distributed concurrency behavior.
Reference: [D1 transactional batches](https://developers.cloudflare.com/d1/worker-api/d1-database/).
