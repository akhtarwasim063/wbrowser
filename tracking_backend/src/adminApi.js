'use strict';
// ADMIN listener: admin API + admin web app. Separate port, separate identities (admin_users), separate JWT secret.
const path = require('node:path');
const express = require('express');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const cookieParser = require('cookie-parser');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const cfg = require('./config');
const fs = require('node:fs');
const { eraseUser, tx } = require('./db');

const COOKIE = 'wb_admin';
// Role-based access control. viewer = aggregates only (no per-user URLs/queries).
const PERMS = {
  superadmin: new Set(['aggregate', 'user_list', 'user_detail', 'export', 'manage_users', 'manage_admins', 'audit', 'delete_data', 'server_info', 'manage_server']),
  analyst: new Set(['aggregate', 'user_list', 'user_detail', 'export']),
  viewer: new Set(['aggregate']),
};
const ROLES = Object.keys(PERMS);
const DUMMY_HASH = bcrypt.hashSync('not-a-real-password', 12);

function validPassword(p) {
  return typeof p === 'string' && p.length >= 12 && p.length <= 200 && /[a-z]/.test(p) && /[A-Z]/.test(p) && /\d/.test(p);
}

function createAdminApi(db, { drive = null, driveMinutes = 15 } = {}) {
  const app = express();
  app.disable('x-powered-by');
  if (cfg.trustProxy) app.set('trust proxy', cfg.trustProxy);
  app.use(helmet({
    contentSecurityPolicy: { directives: { defaultSrc: ["'self'"], scriptSrc: ["'self'"], styleSrc: ["'self'"], imgSrc: ["'self'", 'data:'], frameAncestors: ["'none'"], formAction: ["'self'"], upgradeInsecureRequests: cfg.prod ? [] : null } },
    strictTransportSecurity: cfg.prod,
  }));
  app.use(express.json({ limit: '64kb' }));
  app.use(cookieParser());

  const audit = (req, action, target, detail) => {
    db.prepare('INSERT INTO audit_log(at,admin_id,admin_email,action,target,detail,ip) VALUES(?,?,?,?,?,?,?)')
      .run(Date.now(), req.admin ? req.admin.id : null, req.admin ? req.admin.email : (req.body && req.body.email) || null,
        action, target ? String(target).slice(0, 200) : null, detail ? String(detail).slice(0, 500) : null, req.ip);
  };

  // CSRF defence in depth on top of SameSite=Strict: mutating calls must carry a custom header
  // (cannot be sent cross-site without a CORS preflight, and we send no CORS headers).
  app.use('/admin/api', (req, res, next) => {
    if (['GET', 'HEAD', 'OPTIONS'].includes(req.method)) return next();
    if (req.get('x-requested-with') !== 'wbrowser-admin') return res.status(403).json({ error: 'csrf' });
    next();
  });

  const loginLimit = rateLimit({ windowMs: 15 * 60_000, limit: cfg.loginRateLimit, standardHeaders: true, legacyHeaders: false, message: { error: 'too_many_attempts' } });
  const apiLimit = rateLimit({ windowMs: 60_000, limit: 300, standardHeaders: true, legacyHeaders: false });
  app.use('/admin/api', apiLimit);

  const sign = (a) => jwt.sign({ sub: a.id, tv: a.token_version }, cfg.adminJwtSecret, { expiresIn: `${cfg.adminSessionHours}h`, issuer: 'wbrowser-admin', audience: 'wbrowser-admin' });
  const setCookie = (res, token) => res.cookie(COOKIE, token, {
    httpOnly: true, sameSite: 'strict', secure: cfg.cookieSecure, path: '/admin', maxAge: cfg.adminSessionHours * 3600_000,
  });

  app.post('/admin/api/auth/login', loginLimit, (req, res) => {
    const { email, password } = req.body || {};
    if (typeof email !== 'string' || typeof password !== 'string') return res.status(400).json({ error: 'invalid_request' });
    const a = db.prepare('SELECT * FROM admin_users WHERE email=?').get(email.trim().toLowerCase());
    const now = Date.now();
    if (a && a.locked_until > now) { bcrypt.compareSync(password, DUMMY_HASH); audit(req, 'login_locked', email); return res.status(423).json({ error: 'account_locked' }); }
    const ok = bcrypt.compareSync(password, a ? a.password_hash : DUMMY_HASH); // constant-ish time for unknown emails
    if (!a || !a.active || !ok) {
      if (a && !ok) {
        const f = a.failed_logins + 1;
        db.prepare('UPDATE admin_users SET failed_logins=?, locked_until=? WHERE id=?').run(f >= 5 ? 0 : f, f >= 5 ? now + 15 * 60_000 : 0, a.id);
      }
      audit(req, 'login_failed', email);
      return res.status(401).json({ error: 'invalid_credentials' });
    }
    db.prepare('UPDATE admin_users SET failed_logins=0, locked_until=0, last_login=? WHERE id=?').run(now, a.id);
    req.admin = a; audit(req, 'login', a.email);
    setCookie(res, sign(a));
    res.json({ email: a.email, name: a.name, role: a.role });
  });

  // ---- everything below requires a valid session ----
  const requireAuth = (req, res, next) => {
    const t = req.cookies[COOKIE];
    if (!t) return res.status(401).json({ error: 'unauthorized' });
    try {
      const p = jwt.verify(t, cfg.adminJwtSecret, { issuer: 'wbrowser-admin', audience: 'wbrowser-admin' });
      const a = db.prepare('SELECT * FROM admin_users WHERE id=?').get(p.sub);
      if (!a || !a.active || a.token_version !== p.tv) return res.status(401).json({ error: 'unauthorized' });
      req.admin = a; next();
    } catch { res.status(401).json({ error: 'unauthorized' }); }
  };
  const need = (perm) => (req, res, next) =>
    PERMS[req.admin.role].has(perm) ? next() : res.status(403).json({ error: 'forbidden' });

  app.post('/admin/api/auth/logout', (req, res) => { res.clearCookie(COOKIE, { path: '/admin' }); res.json({ ok: true }); });
  app.get('/admin/api/auth/me', requireAuth, (req, res) =>
    res.json({ email: req.admin.email, name: req.admin.name, role: req.admin.role, permissions: [...PERMS[req.admin.role]] }));

  app.post('/admin/api/auth/change-password', requireAuth, (req, res) => {
    const { current, next: nw } = req.body || {};
    if (!bcrypt.compareSync(String(current || ''), req.admin.password_hash)) return res.status(401).json({ error: 'invalid_credentials' });
    if (!validPassword(nw)) return res.status(400).json({ error: 'weak_password' });
    db.prepare('UPDATE admin_users SET password_hash=?, token_version=token_version+1 WHERE id=?').run(bcrypt.hashSync(nw, 12), req.admin.id);
    const a = db.prepare('SELECT * FROM admin_users WHERE id=?').get(req.admin.id);
    audit(req, 'change_password', a.email);
    setCookie(res, sign(a));
    res.json({ ok: true });
  });

  // ---- filters ----
  const range = (req) => {
    const to = Number(req.query.to) || Date.now();
    const from = Number(req.query.from) || to - 7 * 86400000;
    const tz = Math.max(-840, Math.min(840, parseInt(req.query.tz, 10) || 0)); // minutes east of UTC
    return { from, to, tz };
  };
  const paging = (req) => {
    const limit = Math.min(Math.max(parseInt(req.query.limit, 10) || 50, 1), 500);
    const page = Math.max(parseInt(req.query.page, 10) || 1, 1);
    return { limit, offset: (page - 1) * limit, page };
  };
  const like = (s) => `%${String(s).replace(/[\\%_]/g, (c) => '\\' + c)}%`;

  // ---- aggregates (all roles) ----
  app.get('/admin/api/overview', requireAuth, need('aggregate'), (req, res) => {
    const { from, to, tz } = range(req);
    const one = (sql, ...a) => db.prepare(sql).get(...a);
    const totals = one('SELECT COUNT(*) visits, COUNT(DISTINCT user_id) activeUsers, COUNT(DISTINCT domain) domains, COALESCE(SUM(duration_ms),0) totalMs FROM visits WHERE visited_at BETWEEN ? AND ?', from, to);
    const sess = one('SELECT COUNT(*) n, COALESCE(AVG(duration_ms),0) avgMs, COALESCE(SUM(duration_ms),0) totalMs FROM sessions WHERE started_at BETWEEN ? AND ?', from, to);
    const users = one("SELECT COUNT(*) total, SUM(CASE WHEN created_at BETWEEN ? AND ? THEN 1 ELSE 0 END) newUsers, SUM(CASE WHEN search_consent=1 THEN 1 ELSE 0 END) searchConsent FROM users", from, to);
    const daily = db.prepare(`SELECT date(visited_at/1000 + ?*60,'unixepoch') day, COUNT(*) visits, COUNT(DISTINCT user_id) users, SUM(duration_ms) ms
      FROM visits WHERE visited_at BETWEEN ? AND ? GROUP BY day ORDER BY day`).all(tz, from, to);
    const topSites = db.prepare(`SELECT domain, COUNT(*) visits, COUNT(DISTINCT user_id) users, SUM(duration_ms) totalMs
      FROM visits WHERE visited_at BETWEEN ? AND ? GROUP BY domain ORDER BY visits DESC LIMIT 10`).all(from, to);
    res.json({ range: { from, to }, totals, sessions: sess, users, daily, topSites });
  });

  app.get('/admin/api/websites', requireAuth, need('aggregate'), (req, res) => {
    const { from, to } = range(req); const { limit, offset } = paging(req);
    const search = req.query.q ? like(req.query.q) : '%';
    const rows = db.prepare(`SELECT domain, COUNT(*) visits, COUNT(DISTINCT user_id) users, SUM(duration_ms) totalMs, AVG(duration_ms) avgMs, MAX(visited_at) lastVisit
      FROM visits WHERE visited_at BETWEEN ? AND ? AND domain LIKE ? ESCAPE '\\' GROUP BY domain ORDER BY ${req.query.sort === 'time' ? 'totalMs' : 'visits'} DESC LIMIT ? OFFSET ?`)
      .all(from, to, search, limit, offset);
    res.json({ rows });
  });

  app.get('/admin/api/sessions', requireAuth, need('aggregate'), (req, res) => {
    const { from, to, tz } = range(req);
    const daily = db.prepare(`SELECT date(started_at/1000 + ?*60,'unixepoch') day, COUNT(*) sessions, AVG(duration_ms) avgMs, SUM(duration_ms) totalMs
      FROM sessions WHERE started_at BETWEEN ? AND ? GROUP BY day ORDER BY day`).all(tz, from, to);
    res.json({ daily });
  });

  // ---- per-user data (analyst, superadmin). Reads are audit-logged. ----
  app.get('/admin/api/users', requireAuth, need('user_list'), (req, res) => {
    const { from, to } = range(req); const { limit, offset } = paging(req);
    const s = req.query.q ? like(req.query.q) : '%';
    const rows = db.prepare(`SELECT u.id, u.name, u.email, u.google_name, u.created_at, u.last_seen, u.status, u.search_consent,
        (SELECT COUNT(*) FROM visits v WHERE v.user_id=u.id AND v.visited_at BETWEEN ? AND ?) visits,
        (SELECT COALESCE(SUM(duration_ms),0) FROM visits v WHERE v.user_id=u.id AND v.visited_at BETWEEN ? AND ?) totalMs,
        (SELECT COALESCE(SUM(duration_ms),0) FROM sessions x WHERE x.user_id=u.id AND x.started_at BETWEEN ? AND ?) sessionMs
      FROM users u WHERE (u.name LIKE ? ESCAPE '\\' OR u.id LIKE ? ESCAPE '\\' OR COALESCE(u.email,'') LIKE ? ESCAPE '\\')
      ORDER BY COALESCE(u.last_seen,0) DESC LIMIT ? OFFSET ?`).all(from, to, from, to, from, to, s, s, s, limit, offset);
    const total = db.prepare("SELECT COUNT(*) n FROM users u WHERE (u.name LIKE ? ESCAPE '\\' OR u.id LIKE ? ESCAPE '\\' OR COALESCE(u.email,'') LIKE ? ESCAPE '\\')").get(s, s, s).n;
    res.json({ rows, total });
  });

  const getUser = (req, res) => {
    const u = db.prepare('SELECT id,name,email,google_name,created_at,last_seen,status,activity_consent,search_consent,consent_version,device_model,os_version,app_version FROM users WHERE id=?').get(req.params.id);
    if (!u) res.status(404).json({ error: 'not_found' });
    return u;
  };

  app.get('/admin/api/users/:id', requireAuth, need('user_detail'), (req, res) => {
    const u = getUser(req, res); if (!u) return;
    const { from, to, tz } = range(req);
    audit(req, 'view_user', u.id);
    const stats = db.prepare('SELECT COUNT(*) visits, COUNT(DISTINCT domain) domains, COALESCE(SUM(duration_ms),0) totalMs FROM visits WHERE user_id=? AND visited_at BETWEEN ? AND ?').get(u.id, from, to);
    const sess = db.prepare('SELECT COUNT(*) n, COALESCE(AVG(duration_ms),0) avgMs, COALESCE(SUM(duration_ms),0) totalMs FROM sessions WHERE user_id=? AND started_at BETWEEN ? AND ?').get(u.id, from, to);
    const topSites = db.prepare('SELECT domain, COUNT(*) visits, SUM(duration_ms) totalMs FROM visits WHERE user_id=? AND visited_at BETWEEN ? AND ? GROUP BY domain ORDER BY totalMs DESC LIMIT 10').all(u.id, from, to);
    const daily = db.prepare(`SELECT date(visited_at/1000 + ?*60,'unixepoch') day, COUNT(*) visits, SUM(duration_ms) ms FROM visits WHERE user_id=? AND visited_at BETWEEN ? AND ? GROUP BY day ORDER BY day`).all(tz, u.id, from, to);
    const consents = db.prepare('SELECT purpose,granted,version,at FROM consent_log WHERE user_id=? ORDER BY at DESC LIMIT 20').all(u.id);
    res.json({ user: u, stats, sessions: sess, topSites, daily, consents });
  });

  app.get('/admin/api/users/:id/history', requireAuth, need('user_detail'), (req, res) => {
    const u = getUser(req, res); if (!u) return;
    const { from, to } = range(req); const { limit, offset } = paging(req);
    const s = req.query.q ? like(req.query.q) : '%';
    audit(req, 'view_history', u.id);
    const rows = db.prepare(`SELECT id,url,domain,title,visited_at,duration_ms FROM visits WHERE user_id=? AND visited_at BETWEEN ? AND ?
      AND (url LIKE ? ESCAPE '\\' OR COALESCE(title,'') LIKE ? ESCAPE '\\') ORDER BY visited_at DESC LIMIT ? OFFSET ?`).all(u.id, from, to, s, s, limit, offset);
    const total = db.prepare("SELECT COUNT(*) n FROM visits WHERE user_id=? AND visited_at BETWEEN ? AND ? AND (url LIKE ? ESCAPE '\\' OR COALESCE(title,'') LIKE ? ESCAPE '\\')").get(u.id, from, to, s, s).n;
    res.json({ rows, total });
  });

  app.get('/admin/api/users/:id/sessions', requireAuth, need('user_detail'), (req, res) => {
    const u = getUser(req, res); if (!u) return;
    const { from, to } = range(req); const { limit, offset } = paging(req);
    audit(req, 'view_sessions', u.id);
    const rows = db.prepare('SELECT id,started_at,ended_at,duration_ms FROM sessions WHERE user_id=? AND started_at BETWEEN ? AND ? ORDER BY started_at DESC LIMIT ? OFFSET ?').all(u.id, from, to, limit, offset);
    res.json({ rows });
  });

  app.get('/admin/api/users/:id/searches', requireAuth, need('user_detail'), (req, res) => {
    const u = getUser(req, res); if (!u) return;
    const { from, to } = range(req); const { limit, offset } = paging(req);
    audit(req, 'view_searches', u.id);
    const rows = db.prepare('SELECT id,query,engine,searched_at FROM searches WHERE user_id=? AND searched_at BETWEEN ? AND ? ORDER BY searched_at DESC LIMIT ? OFFSET ?').all(u.id, from, to, limit, offset);
    res.json({ rows, consent: !!u.search_consent });
  });

  const csvCell = (v) => {
    let s = v == null ? '' : String(v);
    if (/^[=+\-@\t\r]/.test(s)) s = "'" + s; // neutralise spreadsheet formula injection
    return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  };
  app.get('/admin/api/users/:id/history.csv', requireAuth, need('export'), (req, res) => {
    const u = getUser(req, res); if (!u) return;
    const { from, to } = range(req);
    audit(req, 'export_history', u.id);
    const rows = db.prepare('SELECT visited_at,url,domain,title,duration_ms FROM visits WHERE user_id=? AND visited_at BETWEEN ? AND ? ORDER BY visited_at DESC LIMIT 100000').all(u.id, from, to);
    res.type('text/csv').attachment(`history-${u.id}.csv`);
    res.send(['visited_at_iso,url,domain,title,duration_seconds',
      ...rows.map((r) => [new Date(r.visited_at).toISOString(), r.url, r.domain, r.title, Math.round(r.duration_ms / 1000)].map(csvCell).join(','))].join('\n'));
  });

  // ---- user administration (superadmin) ----
  app.patch('/admin/api/users/:id', requireAuth, need('manage_users'), (req, res) => {
    const u = getUser(req, res); if (!u) return;
    const st = (req.body || {}).status;
    if (!['active', 'suspended'].includes(st)) return res.status(400).json({ error: 'invalid_request' });
    db.prepare('UPDATE users SET status=? WHERE id=?').run(st, u.id);
    audit(req, 'set_user_status', u.id, st);
    res.json({ ok: true });
  });
  app.delete('/admin/api/users/:id', requireAuth, need('manage_users'), (req, res) => {
    const u = getUser(req, res); if (!u) return;
    eraseUser(db, u.id);
    audit(req, 'erase_user', u.id);
    res.json({ ok: true });
  });

  // ---- deleting collected data (superadmin only, audit-logged) ----
  app.delete('/admin/api/users/:id/history/:visitId', requireAuth, need('delete_data'), (req, res) => {
    const u = getUser(req, res); if (!u) return;
    const r = db.prepare('DELETE FROM visits WHERE user_id=? AND id=?').run(u.id, req.params.visitId);
    audit(req, 'delete_visit', u.id, req.params.visitId);
    res.json({ ok: true, deleted: Number(r.changes) });
  });
  app.delete('/admin/api/users/:id/history', requireAuth, need('delete_data'), (req, res) => {
    const u = getUser(req, res); if (!u) return;
    const r = tx(db, () => {
      const v = db.prepare('DELETE FROM visits WHERE user_id=?').run(u.id);
      db.prepare('DELETE FROM sessions WHERE user_id=?').run(u.id);
      return v;
    });
    audit(req, 'clear_user_history', u.id, `${r.changes} visits`);
    res.json({ ok: true, deleted: Number(r.changes) });
  });
  app.delete('/admin/api/users/:id/searches/:searchId', requireAuth, need('delete_data'), (req, res) => {
    const u = getUser(req, res); if (!u) return;
    const r = db.prepare('DELETE FROM searches WHERE user_id=? AND id=?').run(u.id, req.params.searchId);
    audit(req, 'delete_search', u.id, req.params.searchId);
    res.json({ ok: true, deleted: Number(r.changes) });
  });
  app.delete('/admin/api/users/:id/searches', requireAuth, need('delete_data'), (req, res) => {
    const u = getUser(req, res); if (!u) return;
    const r = db.prepare('DELETE FROM searches WHERE user_id=?').run(u.id);
    audit(req, 'clear_user_searches', u.id, `${r.changes} searches`);
    res.json({ ok: true, deleted: Number(r.changes) });
  });
  // Clear collected data for every user. Accounts stay registered. Requires the confirmation word.
  app.delete('/admin/api/data', requireAuth, need('delete_data'), (req, res) => {
    const { scope, confirm } = req.body || {};
    if (confirm !== 'DELETE' || !['history', 'searches', 'all'].includes(scope)) return res.status(400).json({ error: 'invalid_request' });
    const out = tx(db, () => {
      const o = {};
      if (scope === 'history' || scope === 'all') {
        o.visits = Number(db.prepare('DELETE FROM visits').run().changes);
        o.sessions = Number(db.prepare('DELETE FROM sessions').run().changes);
      }
      if (scope === 'searches' || scope === 'all') o.searches = Number(db.prepare('DELETE FROM searches').run().changes);
      return o;
    });
    audit(req, 'clear_all_data', scope, JSON.stringify(out));
    res.json({ ok: true, deleted: out });
  });

  // ---- where the data lives ----
  app.get('/admin/api/server-info', requireAuth, need('server_info'), (_req, res) => {
    const n = (t) => db.prepare(`SELECT COUNT(*) n FROM ${t}`).get().n;
    let size = null;
    try { if (cfg.dbPath !== ':memory:') size = fs.statSync(cfg.dbPath).size; } catch { /* ignore */ }
    res.json({
      storage: { engine: 'SQLite (Node built-in node:sqlite, WAL mode)', location: cfg.dbPath === ':memory:' ? 'memory' : cfg.dbPath, sizeBytes: size },
      firebase: false,
      drive: drive ? { enabled: true, everyMinutes: driveMinutes, ...drive.status } : { enabled: false },
      counts: { users: n('users'), visits: n('visits'), searches: n('searches'), sessions: n('sessions'), admins: n('admin_users') },
      userApi: { port: cfg.port }, adminApi: { port: cfg.adminPort, bind: cfg.adminBind },
      retentionDays: cfg.retentionDays, googleSignIn: !!cfg.googleWebClientId, production: cfg.prod,
      node: process.version, uptimeSeconds: Math.round(process.uptime()),
    });
  });

  app.post('/admin/api/drive/backup', requireAuth, need('manage_server'), async (req, res) => {
    if (!drive) return res.status(400).json({ error: 'drive_not_configured' });
    try { await drive.backup(db); audit(req, 'drive_backup', 'google-drive'); res.json({ ok: true, ...drive.status }); }
    catch (e) { audit(req, 'drive_backup_failed', 'google-drive', e.message); res.status(502).json({ error: 'drive_failed', message: e.message }); }
  });

  // ---- admin accounts (superadmin) ----
  const countSupers = () => db.prepare("SELECT COUNT(*) n FROM admin_users WHERE role='superadmin' AND active=1").get().n;
  app.get('/admin/api/admins', requireAuth, need('manage_admins'), (_req, res) =>
    res.json({ rows: db.prepare('SELECT id,email,name,role,active,created_at,last_login FROM admin_users ORDER BY id').all() }));
  app.post('/admin/api/admins', requireAuth, need('manage_admins'), (req, res) => {
    const { email, name, role, password } = req.body || {};
    const em = String(email || '').trim().toLowerCase();
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(em) || !String(name || '').trim() || !ROLES.includes(role) || !validPassword(password))
      return res.status(400).json({ error: 'invalid_request', hint: 'password needs 12+ chars with upper, lower and digit' });
    if (db.prepare('SELECT 1 FROM admin_users WHERE email=?').get(em)) return res.status(409).json({ error: 'exists' });
    db.prepare('INSERT INTO admin_users(email,name,password_hash,role,created_at) VALUES(?,?,?,?,?)')
      .run(em, String(name).trim().slice(0, 80), bcrypt.hashSync(password, 12), role, Date.now());
    audit(req, 'create_admin', em, role);
    res.status(201).json({ ok: true });
  });
  app.patch('/admin/api/admins/:id', requireAuth, need('manage_admins'), (req, res) => {
    const a = db.prepare('SELECT * FROM admin_users WHERE id=?').get(req.params.id);
    if (!a) return res.status(404).json({ error: 'not_found' });
    const { role, active, password } = req.body || {};
    const newRole = role === undefined ? a.role : role;
    const newActive = active === undefined ? a.active : (active ? 1 : 0);
    if (!ROLES.includes(newRole)) return res.status(400).json({ error: 'invalid_request' });
    if (a.role === 'superadmin' && a.active && (newRole !== 'superadmin' || !newActive) && countSupers() <= 1)
      return res.status(400).json({ error: 'last_superadmin' });
    if (password !== undefined && !validPassword(password)) return res.status(400).json({ error: 'weak_password' });
    db.prepare('UPDATE admin_users SET role=?, active=?, token_version=token_version+1, password_hash=? WHERE id=?')
      .run(newRole, newActive, password !== undefined ? bcrypt.hashSync(password, 12) : a.password_hash, a.id);
    audit(req, 'update_admin', a.email, JSON.stringify({ role: newRole, active: newActive, passwordReset: password !== undefined }));
    res.json({ ok: true });
  });

  app.get('/admin/api/audit', requireAuth, need('audit'), (req, res) => {
    const { limit, offset } = paging(req);
    res.json({ rows: db.prepare('SELECT * FROM audit_log ORDER BY id DESC LIMIT ? OFFSET ?').all(limit, offset) });
  });

  app.use('/admin/api', (_req, res) => res.status(404).json({ error: 'not_found' }));
  // Admin web app (static). Served only from this listener.
  app.use('/admin', express.static(path.join(__dirname, '..', 'admin_app', 'public'), { index: 'index.html', maxAge: 0 }));
  app.get('/', (_req, res) => res.redirect('/admin/'));
  // eslint-disable-next-line no-unused-vars
  app.use((err, _req, res, _next) => {
    if (err.type === 'entity.parse.failed') return res.status(400).json({ error: 'bad_json' });
    console.error('[admin-api]', err);
    res.status(500).json({ error: 'server_error' });
  });
  return app;
}

function createAdmin(db, { email, name, password, role = 'superadmin' }) {
  if (!validPassword(password)) throw new Error('Password needs 12+ chars with upper-case, lower-case and a digit.');
  db.prepare('INSERT INTO admin_users(email,name,password_hash,role,created_at) VALUES(?,?,?,?,?)')
    .run(email.trim().toLowerCase(), name, bcrypt.hashSync(password, 12), role, Date.now());
}

module.exports = { createAdminApi, createAdmin };
