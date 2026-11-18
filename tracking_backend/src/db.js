'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');

function open(file) {
  if (file !== ':memory:') fs.mkdirSync(path.dirname(file), { recursive: true });
  const db = new DatabaseSync(file);
  db.exec('PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000; PRAGMA synchronous=NORMAL;');
  db.exec(`
CREATE TABLE IF NOT EXISTS users(
  id TEXT PRIMARY KEY,                -- WB-XXXXXXXXXXXXXXXX, generated on device
  secret_hash TEXT NOT NULL,          -- sha256(device secret); the secret itself is never stored
  name TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  last_seen INTEGER,
  status TEXT NOT NULL DEFAULT 'active',      -- active | suspended
  activity_consent INTEGER NOT NULL DEFAULT 0,
  search_consent INTEGER NOT NULL DEFAULT 0,
  consent_version INTEGER NOT NULL DEFAULT 1,
  google_sub TEXT, email TEXT, google_name TEXT,  -- only from verified Google ID token; token is discarded
  device_model TEXT, os_version TEXT, app_version TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS users_google ON users(google_sub) WHERE google_sub IS NOT NULL;

CREATE TABLE IF NOT EXISTS consent_log(
  id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL, purpose TEXT NOT NULL,
  granted INTEGER NOT NULL, version INTEGER NOT NULL, at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS consent_user ON consent_log(user_id);

CREATE TABLE IF NOT EXISTS sessions(
  user_id TEXT NOT NULL, id TEXT NOT NULL, started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL,
  duration_ms INTEGER NOT NULL, PRIMARY KEY(user_id,id)
);
CREATE INDEX IF NOT EXISTS sessions_time ON sessions(started_at);

CREATE TABLE IF NOT EXISTS visits(
  user_id TEXT NOT NULL, id TEXT NOT NULL, session_id TEXT, url TEXT NOT NULL, domain TEXT NOT NULL,
  title TEXT, visited_at INTEGER NOT NULL, duration_ms INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(user_id,id)
);
CREATE INDEX IF NOT EXISTS visits_time ON visits(visited_at);
CREATE INDEX IF NOT EXISTS visits_user_time ON visits(user_id, visited_at);
CREATE INDEX IF NOT EXISTS visits_domain ON visits(domain, visited_at);

CREATE TABLE IF NOT EXISTS searches(
  user_id TEXT NOT NULL, id TEXT NOT NULL, query TEXT NOT NULL, engine TEXT, searched_at INTEGER NOT NULL,
  PRIMARY KEY(user_id,id)
);
CREATE INDEX IF NOT EXISTS searches_user_time ON searches(user_id, searched_at);

-- Admin identities live in their own table; nothing here is reachable from the user API.
CREATE TABLE IF NOT EXISTS admin_users(
  id INTEGER PRIMARY KEY AUTOINCREMENT, email TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
  password_hash TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('superadmin','analyst','viewer')),
  active INTEGER NOT NULL DEFAULT 1, token_version INTEGER NOT NULL DEFAULT 0,
  failed_logins INTEGER NOT NULL DEFAULT 0, locked_until INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL, last_login INTEGER
);
CREATE TABLE IF NOT EXISTS audit_log(
  id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, admin_id INTEGER, admin_email TEXT,
  action TEXT NOT NULL, target TEXT, detail TEXT, ip TEXT
);
CREATE INDEX IF NOT EXISTS audit_time ON audit_log(at);
`);
  return db;
}

/** Runs fn inside a transaction. */
function tx(db, fn) {
  db.exec('BEGIN IMMEDIATE');
  try { const r = fn(); db.exec('COMMIT'); return r; } catch (e) { db.exec('ROLLBACK'); throw e; }
}

/** Removes every trace of a user (right to erasure). */
function eraseUser(db, id) {
  tx(db, () => {
    for (const t of ['visits', 'searches', 'sessions', 'consent_log']) db.prepare(`DELETE FROM ${t} WHERE user_id=?`).run(id);
    db.prepare('DELETE FROM users WHERE id=?').run(id);
  });
}

function purgeOld(db, days) {
  const cut = Date.now() - days * 86400000;
  db.prepare('DELETE FROM visits WHERE visited_at<?').run(cut);
  db.prepare('DELETE FROM searches WHERE searched_at<?').run(cut);
  db.prepare('DELETE FROM sessions WHERE started_at<?').run(cut);
  db.prepare('DELETE FROM audit_log WHERE at<?').run(Date.now() - 365 * 86400000);
}

module.exports = { open, tx, eraseUser, purgeOld };
