export const MAX_BYTES = 256 * 1024;
export const RETENTION_SECONDS = 7 * 24 * 60 * 60;
export const PURGE = "DELETE FROM reports WHERE expires_at <= ?";
export const INSERT = `INSERT INTO reports (id, created_at, expires_at, body)
  SELECT ?, ?, ?, ? WHERE (SELECT COUNT(*) FROM reports) < 100`;
const encoder = new TextEncoder();
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function response(value, status = 200, headers = {}) {
  return new Response(value, { status, headers: {
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Content-Security-Policy": "default-src 'none'",
    ...headers,
  } });
}

function json(value, status = 200) {
  return response(JSON.stringify(value), status, { "Content-Type": "application/json" });
}

async function authorized(request, secret) {
  const header = request.headers.get("Authorization") ?? "";
  if (!header.startsWith("Bearer ") || header.length > 512) return false;
  // WebCrypto verifies the MAC; no request-controlled secret comparison in JavaScript.
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
  const mac = await crypto.subtle.sign("HMAC", key, encoder.encode(secret));
  return crypto.subtle.verify("HMAC", key, mac, encoder.encode(header.slice(7)));
}

async function readText(request) {
  const contentType = request.headers.get("Content-Type") ?? "";
  if (!/^text\/plain(?:\s*;\s*charset=utf-8)?$/i.test(contentType)) throw 415;
  if (request.headers.has("Content-Encoding")) throw 415;
  const declared = request.headers.get("Content-Length");
  if (declared !== null && (!/^\d+$/.test(declared) || Number(declared) > MAX_BYTES)) throw 413;
  if (!request.body) throw 400;
  const reader = request.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let size = 0;
  let text = "";
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BYTES) throw 413;
      text += decoder.decode(value, { stream: true });
    }
    text += decoder.decode();
    if (size === 0) throw 400;
    return text;
  } catch (error) {
    await reader.cancel().catch(() => {});
    throw error === 413 ? 413 : 400;
  } finally {
    reader.releaseLock();
  }
}

export default {
  async fetch(request, env) {
    const secrets = [env.UPLOAD_TOKEN, env.READ_TOKEN];
    if (!env.DB || secrets.some(token => typeof token !== "string" || token.length < 32) ||
        secrets[0] === secrets[1]) return json({ error: "unavailable" }, 503);
    const path = new URL(request.url).pathname;
    const isUpload = request.method === "POST" && path === "/reports";
    const isRead = request.method === "GET" && (path === "/reports" || uuid.test(path.slice(9)) && path.startsWith("/reports/"));
    if (!isUpload && !isRead) return json({ error: "not_found" }, 404);
    try {
      if (!await authorized(request, isUpload ? env.UPLOAD_TOKEN : env.READ_TOKEN)) {
        return json({ error: "unauthorized" }, 401);
      }
      const now = Math.floor(Date.now() / 1000);
      if (isUpload) {
        const body = await readText(request);
        const id = crypto.randomUUID();
        const expiresAt = now + RETENTION_SECONDS;
        // D1 batch is transactional; capacity is checked inside the INSERT, never in JS.
        const results = await env.DB.batch([
          env.DB.prepare(PURGE).bind(now),
          env.DB.prepare(INSERT).bind(id, now, expiresAt, body),
        ]);
        if (results[1].meta.changes !== 1) return json({ error: "capacity" }, 429);
        return json({ id, expires_at: expiresAt }, 201);
      }
      if (path === "/reports") {
        const { results } = await env.DB.prepare(
          "SELECT id, created_at, expires_at FROM reports WHERE expires_at > ? ORDER BY created_at DESC LIMIT 100",
        ).bind(now).all();
        return json({ reports: results });
      }
      const report = await env.DB.prepare(
        "SELECT body FROM reports WHERE id = ? AND expires_at > ?",
      ).bind(path.slice(9), now).first();
      if (!report) return json({ error: "not_found" }, 404);
      return response(report.body, 200, {
        "Content-Type": "text/plain; charset=utf-8",
        "Content-Disposition": `attachment; filename="diagnostics-${path.slice(9)}.txt"`,
      });
    } catch (error) {
      const status = [400, 413, 415].includes(error) ? error : 503;
      return json({ error: status === 503 ? "unavailable" : "invalid_report" }, status);
    }
  },
  async scheduled(_event, env) {
    await env.DB.prepare(PURGE).bind(Math.floor(Date.now() / 1000)).run();
  },
};
