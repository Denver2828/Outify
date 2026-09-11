CREATE TABLE reports (
    id TEXT PRIMARY KEY,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL CHECK (expires_at = created_at + 604800),
    body TEXT NOT NULL CHECK (length(CAST(body AS BLOB)) BETWEEN 1 AND 262144)
);
CREATE INDEX reports_expiry ON reports(expires_at);
CREATE INDEX reports_created ON reports(created_at DESC);
