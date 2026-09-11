import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import worker, { INSERT, MAX_BYTES, RETENTION_SECONDS } from "./worker.mjs";

const schema = readFileSync(new URL("./migrations/0001_reports.sql", import.meta.url), "utf8");
const uploadToken = "u".repeat(64);
const readToken = "r".repeat(64);

function fixture(t) {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(schema);
  t.after(() => sqlite.close());
  // Only the D1 transport shape is adapted; every SQL statement runs in real SQLite.
  const DB = {
    prepare(sql) {
      return { bind(...args) {
        const statement = sqlite.prepare(sql);
        return {
          run: () => ({ meta: { changes: Number(statement.run(...args).changes) } }),
          all: () => ({ results: statement.all(...args) }),
          first: () => statement.get(...args) ?? null,
        };
      } };
    },
    batch(statements) {
      sqlite.exec("BEGIN IMMEDIATE");
      try {
        const results = statements.map(statement => statement.run());
        sqlite.exec("COMMIT");
        return results;
      } catch (error) {
        sqlite.exec("ROLLBACK");
        throw error;
      }
    },
  };
  return { sqlite, env: { DB, UPLOAD_TOKEN: uploadToken, READ_TOKEN: readToken } };
}

function request(method, path = "/reports", token = uploadToken, body, headers = {}) {
  return new Request(`https://example.test${path}`, {
    method, headers: { Authorization: `Bearer ${token}`, "Content-Type": "text/plain", ...headers },
    ...(body === undefined ? {} : { body, duplex: "half" }),
  });
}

test("fails closed and separates upload and read capabilities", async t => {
  const { env } = fixture(t);
  for (const settings of [{}, { ...env, READ_TOKEN: "" }, { ...env, READ_TOKEN: uploadToken }]) {
    assert.equal((await worker.fetch(request("POST", "/reports", uploadToken, "x"), settings)).status, 503);
  }
  for (const [method, token] of [["GET", uploadToken], ["POST", readToken], ["GET", "wrong"]]) {
    assert.equal((await worker.fetch(request(method, "/reports", token), env)).status, 401);
  }
});

test("stores opaque UTF-8 text and downloads only with reader authorization", async t => {
  const { env } = fixture(t);
  const body = '{"diagnostic":"canción"}';
  const created = await worker.fetch(request("POST", "/reports", uploadToken, body), env);
  assert.equal(created.status, 201);
  const report = await created.json();
  assert.deepEqual(Object.keys(report).sort(), ["expires_at", "id"]);
  assert.match(report.id, /^[0-9a-f-]{36}$/);
  const downloaded = await worker.fetch(request("GET", `/reports/${report.id}`, readToken), env);
  assert.equal(await downloaded.text(), body);
  assert.equal(downloaded.headers.get("Cache-Control"), "no-store");
  assert.equal(downloaded.headers.get("Access-Control-Allow-Origin"), null);
  assert.match(downloaded.headers.get("Content-Disposition"), /^attachment;/);
  const listed = await (await worker.fetch(request("GET", "/reports", readToken), env)).json();
  assert.equal(listed.reports.length, 1);
  assert.equal(listed.reports[0].body, undefined);
  assert.equal(listed.reports[0].expires_at - listed.reports[0].created_at, RETENTION_SECONDS);
});

test("rejects unsupported types, malformed UTF-8 and empty reports", async t => {
  const { env } = fixture(t);
  for (const [body, headers, status] of [
    ["x", { "Content-Type": "application/json" }, 415],
    ["x", { "Content-Type": "text/plain; charset=latin1" }, 415],
    ["x", { "Content-Encoding": "gzip" }, 415],
    [new Uint8Array([0xc3, 0x28]), {}, 400],
    ["", {}, 400],
  ]) {
    assert.equal((await worker.fetch(request("POST", "/reports", uploadToken, body, headers), env)).status, status);
  }
});

test("bounds streamed bytes even when Content-Length lies and accepts exact limit", async t => {
  const { env } = fixture(t);
  let cancelled = false;
  const stream = new ReadableStream({
    start(controller) { controller.enqueue(new Uint8Array(MAX_BYTES + 1)); },
    cancel() { cancelled = true; },
  });
  const oversized = await worker.fetch(request("POST", "/reports", uploadToken, stream, { "Content-Length": "1" }), env);
  assert.equal(oversized.status, 413);
  assert.equal(cancelled, true);
  assert.equal((await worker.fetch(request("POST", "/reports", uploadToken, "x".repeat(MAX_BYTES)), env)).status, 201);
});

test("atomic SQL insertion caps concurrent uploads at 100 rows", async t => {
  const { env, sqlite } = fixture(t);
  const results = await Promise.all(Array.from({ length: 105 }, () =>
    worker.fetch(request("POST", "/reports", uploadToken, "report"), env)));
  assert.equal(results.filter(result => result.status === 201).length, 100);
  assert.equal(results.filter(result => result.status === 429).length, 5);
  assert.equal(sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 100);
});

test("expiry filters both read routes and scheduled cleanup removes expired rows", async t => {
  const { env, sqlite } = fixture(t);
  const now = Math.floor(Date.now() / 1000);
  const id = crypto.randomUUID();
  sqlite.prepare(INSERT).run(id, now - RETENTION_SECONDS, now, "expired");
  assert.equal((await worker.fetch(request("GET", `/reports/${id}`, readToken), env)).status, 404);
  assert.deepEqual(await (await worker.fetch(request("GET", "/reports", readToken), env)).json(), { reports: [] });
  await worker.scheduled({}, env);
  assert.equal(sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 0);
});

test("upload reclaims expired capacity; database failures disclose no report content", async t => {
  const { env, sqlite } = fixture(t);
  for (let i = 0; i < 100; i++) sqlite.prepare(INSERT).run(crypto.randomUUID(), 0, RETENTION_SECONDS, "old");
  assert.equal((await worker.fetch(request("POST", "/reports", uploadToken, "fresh"), env)).status, 201);
  assert.equal(sqlite.prepare("SELECT count(*) AS n FROM reports").get().n, 1);
  sqlite.exec("DROP TABLE reports");
  const failed = await worker.fetch(request("POST", "/reports", uploadToken, "private report"), env);
  assert.equal(failed.status, 503);
  assert.deepEqual(await failed.json(), { error: "unavailable" });
});
