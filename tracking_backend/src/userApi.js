'use strict';
// PUBLIC listener: used only by the WBrowser user app. Contains no admin functionality or credentials.
const crypto = require('node:crypto');
const express = require('express');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const { OAuth2Client } = require('google-auth-library');
const cfg = require('./config');
const { tx, eraseUser } = require('./db');
const { sanitizeUrl, clampStr } = require('./sanitize');

const ID_RE = /^WB-[0-9A-HJKMNP-TV-Z]{16}$/;
const SECRET_RE = /^[A-Za-z0-9_-]{43,86}$/;
const EVENT_ID_RE = /^[A-Za-z0-9_-]{6,64}$/;
const MAX_BATCH = 200;
const DAY = 86400000;

const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');
const safeEq = (a, b) => {
  const x = Buffer.from(a), y = Buffer.from(b);
  return x.length === y.length && crypto.timingSafeEqual(x, y);
};

function createUserApi(db, { googleVerifier } = {}) {
  const app = express();
  app.disable('x-powered-by');
  if (cfg.trustProxy) app.set('trust proxy', cfg.trustProxy);
  app.use(helmet());
  app.use(express.json({ limit: '512kb' }));

  const oauth = cfg.googleWebClientId ? new OAuth2Client(cfg.googleWebClientId) : null;
  const verifyGoogle = googleVerifier || (async (idToken) => {
    if (!oauth) throw Object.assign(new Error('google_not_configured'), { status: 503 });
    const t = await oauth.verifyIdToken({ idToken, audience: cfg.googleWebClientId });
    return t.getPayload();
  });

  const ipLimit = rateLimit({ windowMs: 60_000, limit: 120, standardHeaders: true, legacyHeaders: false });
  const registerLimit = rateLimit({ windowMs: 60 * 60_000, limit: 30, standardHeaders: true, legacyHeaders: false });
  app.use(ipLimit);

  const q = {
    userById: db.prepare('SELECT * FROM users WHERE id=?'),
    insertUser: db.prepare(`INSERT INTO users(id,secret_hash,name,created_at,last_seen,activity_consent,search_consent,consent_version,device_model,os_version,app_version)
      VALUES(?,?,?,?,?,?,?,?,?,?,?)`),
    consent: db.prepare('INSERT INTO consent_log(user_id,purpose,granted,version,at) VALUES(?,?,?,?,?)'),
    touch: db.prepare('UPDATE users SET last_seen=?, device_model=COALESCE(?,device_model), os_version=COALESCE(?,os_version), app_version=COALESCE(?,app_version) WHERE id=?'),
    upVisit: db.prepare(`INSERT INTO visits(user_id,id,session_id,url,domain,title,visited_at,duration_ms) VALUES(?,?,?,?,?,?,?,?)
      ON CONFLICT(user_id,id) DO UPDATE SET duration_ms=MAX(duration_ms,excluded.duration_ms), title=COALESCE(NULLIF(excluded.title,''),title)`),
    upSearch: db.prepare('INSERT OR IGNORE INTO searches(user_id,id,query,engine,searched_at) VALUES(?,?,?,?,?)'),
    upSession: db.prepare(`INSERT INTO sessions(user_id,id,started_at,ended_at,duration_ms) VALUES(?,?,?,?,?)
      ON CONFLICT(user_id,id) DO UPDATE SET ended_at=MAX(ended_at,excluded.ended_at), duration_ms=MAX(duration_ms,excluded.duration_ms)`),
  };

  const deviceInfo = (d = {}) => [clampStr(d.model, 80) || null, clampStr(d.os, 40) || null, clampStr(d.appVersion, 20) || null];

  // ---- registration (idempotent: safe to retry from the offline queue) ----
  app.post('/api/v1/register', registerLimit, (req, res) => {
    const b = req.body || {};
    const name = clampStr(b.name, 80);
    if (!ID_RE.test(b.userId || '') || !SECRET_RE.test(b.secret || '') || name.length < 1)
      return res.status(400).json({ error: 'invalid_request' });
    if (!b.consent || b.consent.activity !== true) return res.status(400).json({ error: 'activity_consent_required' });
    const hash = sha256(b.secret);
    const existing = q.userById.get(b.userId);
    if (existing) {
      if (!safeEq(existing.secret_hash, hash)) return res.status(409).json({ error: 'id_taken' });
      return res.json({ ok: true, userId: existing.id, existing: true });
    }
    const now = Date.now();
    const search = b.consent.search === true ? 1 : 0;
    tx(db, () => {
      q.insertUser.run(b.userId, hash, name, now, now, 1, search, cfg.consentVersion, ...deviceInfo(b.device));
      q.consent.run(b.userId, 'activity', 1, cfg.consentVersion, now);
      q.consent.run(b.userId, 'search', search, cfg.consentVersion, now);
    });
    res.status(201).json({ ok: true, userId: b.userId });
  });

  // ---- authenticated routes: Authorization: Bearer <userId>.<secret> ----
  const auth = (req, res, next) => {
    const m = /^Bearer (WB-[0-9A-Z]{16})\.([A-Za-z0-9_-]{43,86})$/.exec(req.get('authorization') || '');
    if (!m) return res.status(401).json({ error: 'unauthorized' });
    const u = q.userById.get(m[1]);
    if (!u || !safeEq(u.secret_hash, sha256(m[2]))) return res.status(401).json({ error: 'unauthorized' });
    if (u.status !== 'active') return res.status(403).json({ error: 'suspended' });
    req.user = u;
    next();
  };

  app.post('/api/v1/events', auth, (req, res) => {
    const events = req.body && req.body.events;
    if (!Array.isArray(events) || events.length === 0 || events.length > MAX_BATCH)
      return res.status(400).json({ error: 'invalid_batch' });
    const u = req.user;
    const now = Date.now();
    const okTime = (t) => Number.isFinite(t) && t > now - 120 * DAY && t < now + 5 * 60_000;
    let accepted = 0, rejected = 0;
    tx(db, () => {
      for (const e of events) {
        if (!e || !EVENT_ID_RE.test(e.id || '')) { rejected++; continue; }
        if (e.type === 'visit') {
          const s = sanitizeUrl(e.url);
          const at = Number(e.startedAt);
          if (!s || !okTime(at) || !u.activity_consent) { rejected++; continue; }
          const dur = Math.min(Math.max(Math.trunc(Number(e.durationMs) || 0), 0), DAY);
          const sess = EVENT_ID_RE.test(e.sessionId || '') ? e.sessionId : null;
          q.upVisit.run(u.id, e.id, sess, s.url, s.domain, clampStr(e.title, 300), Math.trunc(at), dur);
          accepted++;
        } else if (e.type === 'search') {
          const at = Number(e.at);
          const text = clampStr(e.query, 500);
          // Defence in depth: server also enforces the stored consent flag.
          if (!u.search_consent || !text || !okTime(at)) { rejected++; continue; }
          q.upSearch.run(u.id, e.id, text, clampStr(e.engine, 30) || null, Math.trunc(at));
          accepted++;
        } else if (e.type === 'session') {
          const s = Number(e.startedAt), en = Number(e.endedAt);
          if (!okTime(s) || !Number.isFinite(en) || en < s || en > now + 5 * 60_000 || !u.activity_consent) { rejected++; continue; }
          q.upSession.run(u.id, e.id, Math.trunc(s), Math.trunc(en), Math.min(Math.trunc(en - s), DAY));
          accepted++;
        } else rejected++;
      }
      const d = deviceInfo(req.body.device);
      q.touch.run(now, d[0], d[1], d[2], u.id);
    });
    res.json({ ok: true, accepted, rejected });
  });

  app.post('/api/v1/consent', auth, (req, res) => {
    const b = req.body || {};
    const now = Date.now();
    tx(db, () => {
      if (typeof b.search === 'boolean') {
        db.prepare('UPDATE users SET search_consent=? WHERE id=?').run(b.search ? 1 : 0, req.user.id);
        q.consent.run(req.user.id, 'search', b.search ? 1 : 0, cfg.consentVersion, now);
        // Withdrawal means previously collected search queries are erased too.
        if (!b.search) db.prepare('DELETE FROM searches WHERE user_id=?').run(req.user.id);
      }
      if (typeof b.activity === 'boolean') {
        db.prepare('UPDATE users SET activity_consent=? WHERE id=?').run(b.activity ? 1 : 0, req.user.id);
        q.consent.run(req.user.id, 'activity', b.activity ? 1 : 0, cfg.consentVersion, now);
      }
    });
    res.json({ ok: true });
  });

  app.post('/api/v1/profile', auth, (req, res) => {
    const name = clampStr((req.body || {}).name, 80);
    if (!name) return res.status(400).json({ error: 'invalid_request' });
    db.prepare('UPDATE users SET name=? WHERE id=?').run(name, req.user.id);
    res.json({ ok: true });
  });

  // ---- optional Google sign-in: client sends a Google ID token ONCE; we keep only sub/name/email ----
  app.post('/api/v1/link-google', auth, async (req, res) => {
    const idToken = (req.body || {}).idToken;
    if (typeof idToken !== 'string' || idToken.length < 20 || idToken.length > 4096)
      return res.status(400).json({ error: 'invalid_request' });
    try {
      const p = await verifyGoogle(idToken);
      if (!p || !p.sub) return res.status(401).json({ error: 'invalid_token' });
      const taken = db.prepare('SELECT id FROM users WHERE google_sub=? AND id<>?').get(p.sub, req.user.id);
      if (taken) return res.status(409).json({ error: 'google_account_already_linked' });
      db.prepare('UPDATE users SET google_sub=?, email=?, google_name=? WHERE id=?')
        .run(p.sub, clampStr(p.email, 200) || null, clampStr(p.name, 120) || null, req.user.id);
      res.json({ ok: true, email: p.email || null, name: p.name || null });
    } catch (e) {
      if (e.status === 503) return res.status(503).json({ error: 'google_not_configured' });
      res.status(401).json({ error: 'invalid_token' });
    }
  });

  app.delete('/api/v1/link-google', auth, (req, res) => {
    db.prepare('UPDATE users SET google_sub=NULL,email=NULL,google_name=NULL WHERE id=?').run(req.user.id);
    res.json({ ok: true });
  });

  // ---- data-principal rights (DPDP): access/portability + erasure ----
  app.get('/api/v1/me/export', auth, (req, res) => {
    const id = req.user.id;
    const { secret_hash, ...profile } = req.user;
    res.json({
      profile,
      consents: db.prepare('SELECT purpose,granted,version,at FROM consent_log WHERE user_id=? ORDER BY at').all(id),
      visits: db.prepare('SELECT id,url,domain,title,visited_at,duration_ms FROM visits WHERE user_id=? ORDER BY visited_at').all(id),
      searches: db.prepare('SELECT query,engine,searched_at FROM searches WHERE user_id=? ORDER BY searched_at').all(id),
      sessions: db.prepare('SELECT id,started_at,ended_at,duration_ms FROM sessions WHERE user_id=? ORDER BY started_at').all(id),
    });
  });

  app.delete('/api/v1/me', auth, (req, res) => {
    eraseUser(db, req.user.id);
    res.json({ ok: true });
  });

  require('./legal').mount(app);   // /privacy and /terms (public pages)
  app.get('/healthz', (_req, res) => res.json({ ok: true }));
  app.use((_req, res) => res.status(404).json({ error: 'not_found' }));
  // eslint-disable-next-line no-unused-vars
  app.use((err, _req, res, _next) => {
    if (err.type === 'entity.too.large') return res.status(413).json({ error: 'too_large' });
    if (err.type === 'entity.parse.failed') return res.status(400).json({ error: 'bad_json' });
    console.error('[user-api]', err);
    res.status(500).json({ error: 'server_error' });
  });
  return app;
}

module.exports = { createUserApi };
