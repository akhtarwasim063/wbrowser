'use strict';
process.env.ADMIN_JWT_SECRET = 'test-secret-test-secret-test-secret-123456';
process.env.LOGIN_RATE_LIMIT = '1000';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { open } = require('../src/db');
const { createUserApi } = require('../src/userApi');
const { createAdminApi, createAdmin } = require('../src/adminApi');
const { sanitizeUrl } = require('../src/sanitize');

const db = open(':memory:');
createAdmin(db, { email: 'root@x.io', name: 'Root', password: 'Sup3rSecretPass', role: 'superadmin' });
createAdmin(db, { email: 'ana@x.io', name: 'Ana', password: 'Sup3rSecretPass', role: 'analyst' });
createAdmin(db, { email: 'vie@x.io', name: 'Vie', password: 'Sup3rSecretPass', role: 'viewer' });

let U, A;
test.before(async () => {
  const mk = (app) => new Promise((r) => { const s = app.listen(0, '127.0.0.1', () => r(s)); });
  const g = async () => ({ email: 'g@example.com', name: 'Gee', sub: 'google-sub-1' });
  U = await mk(createUserApi(db, { googleVerifier: g }));
  A = await mk(createAdminApi(db));
});
test.after(() => { U.close(); A.close(); });

const b64 = (n) => crypto.randomBytes(n).toString('base64url');
const newId = () => 'WB-' + Array.from({ length: 16 }, () => '0123456789ABCDEFGHJKMNPQRSTVWXYZ'[crypto.randomInt(32)]).join('');
const uurl = (p) => `http://127.0.0.1:${U.address().port}${p}`;
const aurl = (p) => `http://127.0.0.1:${A.address().port}${p}`;
const post = (url, body, headers = {}) => fetch(url, { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(body) });

async function newUser(search = false) {
  const userId = newId(), secret = b64(32);
  const r = await post(uurl('/api/v1/register'), { userId, secret, name: 'Asha', consent: { activity: true, search }, device: { model: 'Pixel', os: '15', appVersion: '1.0' } });
  assert.equal(r.status, 201);
  return { userId, secret, h: { authorization: `Bearer ${userId}.${secret}` } };
}
async function login(email, password = 'Sup3rSecretPass') {
  const r = await post(aurl('/admin/api/auth/login'), { email, password }, { 'x-requested-with': 'wbrowser-admin' });
  const cookie = (r.headers.get('set-cookie') || '').split(';')[0];
  return { r, h: { cookie, 'x-requested-with': 'wbrowser-admin' } };
}
const now = Date.now();
const ev = (id, url, ms, off = 0) => ({ type: 'visit', id, sessionId: 'sess1', url, title: 'T ' + id, startedAt: now - off, durationMs: ms });

test('sanitizer strips fragments, creds, sensitive params and search queries', () => {
  assert.equal(sanitizeUrl('https://u:p@www.Example.com/a?x=1&access_token=abc&code=9#frag').url, 'https://www.example.com/a?x=1');
  assert.equal(sanitizeUrl('https://www.Example.com/').domain, 'example.com');
  assert.equal(sanitizeUrl('https://www.google.com/search?q=secret+thing').url, 'https://www.google.com/search');
  assert.equal(sanitizeUrl('https://www.google.com/search?q=x').domain, 'google.com');
  assert.equal(sanitizeUrl('javascript:alert(1)'), null);
  assert.equal(sanitizeUrl('file:///etc/passwd'), null);
});

test('register is idempotent, rejects hijack and missing consent', async () => {
  const u = await newUser();
  const again = await post(uurl('/api/v1/register'), { userId: u.userId, secret: u.secret, name: 'Asha', consent: { activity: true } });
  assert.equal(again.status, 200);
  const hijack = await post(uurl('/api/v1/register'), { userId: u.userId, secret: b64(32), name: 'Evil', consent: { activity: true } });
  assert.equal(hijack.status, 409);
  const noConsent = await post(uurl('/api/v1/register'), { userId: newId(), secret: b64(32), name: 'N', consent: { activity: false } });
  assert.equal(noConsent.status, 400);
});

test('events: auth required, idempotent, durations updated, searches consent-gated', async () => {
  const noauth = await post(uurl('/api/v1/events'), { events: [] });
  assert.equal(noauth.status, 401);
  const bad = await post(uurl('/api/v1/events'), { events: [ev('v1', 'https://a.com/', 1)] }, { authorization: 'Bearer WB-0000000000000000.' + b64(32) });
  assert.equal(bad.status, 401);

  const u = await newUser(false);
  const batch = { events: [
    ev('visit001', 'https://www.news.com/a?token=zzz#x', 1000, 5000),
    { type: 'search', id: 'search01', query: 'private query', engine: 'google', at: now },
    { type: 'session', id: 'sessAAA1', startedAt: now - 60000, endedAt: now },
    ev('bad0001', 'chrome://settings', 5),
  ] };
  let r = await (await post(uurl('/api/v1/events'), batch, u.h)).json();
  assert.deepEqual([r.accepted, r.rejected], [2, 2]); // search dropped (no consent), chrome:// dropped
  // retry same batch with longer duration: still no duplicates, duration max wins
  batch.events[0].durationMs = 9000;
  await post(uurl('/api/v1/events'), batch, u.h);
  const rows = db.prepare('SELECT * FROM visits WHERE user_id=?').all(u.userId);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].duration_ms, 9000);
  assert.equal(rows[0].url, 'https://www.news.com/a');
  assert.equal(rows[0].domain, 'news.com');
  assert.equal(db.prepare('SELECT COUNT(*) n FROM searches WHERE user_id=?').get(u.userId).n, 0);

  // grant consent -> searches accepted; withdraw -> erased
  await post(uurl('/api/v1/consent'), { search: true }, u.h);
  r = await (await post(uurl('/api/v1/events'), { events: [batch.events[1]] }, u.h)).json();
  assert.equal(r.accepted, 1);
  await post(uurl('/api/v1/consent'), { search: false }, u.h);
  assert.equal(db.prepare('SELECT COUNT(*) n FROM searches WHERE user_id=?').get(u.userId).n, 0);
});

test('google link stores only sub/email/name; export and erase work', async () => {
  const u = await newUser();
  const r = await post(uurl('/api/v1/link-google'), { idToken: 'x'.repeat(40) }, u.h);
  assert.equal(r.status, 200);
  const row = db.prepare('SELECT * FROM users WHERE id=?').get(u.userId);
  assert.equal(row.email, 'g@example.com');
  const second = await newUser();
  assert.equal((await post(uurl('/api/v1/link-google'), { idToken: 'x'.repeat(40) }, second.h)).status, 409);
  const exp = await (await fetch(uurl('/api/v1/me/export'), { headers: u.h })).json();
  assert.equal(exp.profile.id, u.userId);
  assert.equal(exp.profile.secret_hash, undefined);
  assert.equal((await fetch(uurl('/api/v1/me'), { method: 'DELETE', headers: u.h })).status, 200);
  assert.equal(db.prepare('SELECT COUNT(*) n FROM users WHERE id=?').get(u.userId).n, 0);
});

test('user API exposes no admin routes; admin API rejects user credentials', async () => {
  assert.equal((await fetch(uurl('/admin/api/users'))).status, 404);
  const u = await newUser();
  assert.equal((await fetch(aurl('/admin/api/users'), { headers: u.h })).status, 401);
});

test('admin auth: bad password, csrf header, lockout', async () => {
  const bad = await login('ana@x.io', 'wrong-password');
  assert.equal(bad.r.status, 401);
  const noCsrf = await post(aurl('/admin/api/auth/login'), { email: 'ana@x.io', password: 'Sup3rSecretPass' });
  assert.equal(noCsrf.status, 403);
  const ok = await login('ana@x.io');
  assert.equal(ok.r.status, 200);
  assert.match(ok.r.headers.get('set-cookie'), /HttpOnly/i);
  assert.match(ok.r.headers.get('set-cookie'), /SameSite=Strict/i);
});

test('RBAC: viewer aggregates only, analyst read-only, superadmin manages', async () => {
  const u = await newUser();
  await post(uurl('/api/v1/events'), { events: [ev('rb000001', 'https://rbac.com/p', 3000)] }, u.h);
  const vie = await login('vie@x.io'), ana = await login('ana@x.io'), root = await login('root@x.io');

  assert.equal((await fetch(aurl('/admin/api/overview'), { headers: vie.h })).status, 200);
  assert.equal((await fetch(aurl('/admin/api/users'), { headers: vie.h })).status, 403);
  assert.equal((await fetch(aurl(`/admin/api/users/${u.userId}/history`), { headers: vie.h })).status, 403);

  const hist = await (await fetch(aurl(`/admin/api/users/${u.userId}/history?from=${now - 1e6}&to=${now + 1e6}`), { headers: ana.h })).json();
  assert.equal(hist.rows[0].domain, 'rbac.com');
  assert.equal((await fetch(aurl(`/admin/api/users/${u.userId}`), { method: 'DELETE', headers: ana.h })).status, 403);
  assert.equal((await fetch(aurl('/admin/api/admins'), { headers: ana.h })).status, 403);
  assert.equal((await fetch(aurl('/admin/api/audit'), { headers: ana.h })).status, 403);

  const audit = await (await fetch(aurl('/admin/api/audit'), { headers: root.h })).json();
  assert.ok(audit.rows.some((a) => a.action === 'view_history' && a.target === u.userId && a.admin_email === 'ana@x.io'));

  const ov = await (await fetch(aurl(`/admin/api/overview?from=${now - 1e6}&to=${now + 1e6}`), { headers: root.h })).json();
  assert.ok(ov.totals.visits >= 1);
  assert.ok(ov.topSites.some((s) => s.domain === 'rbac.com'));

  const csv = await (await fetch(aurl(`/admin/api/users/${u.userId}/history.csv?from=${now - 1e6}&to=${now + 1e6}`), { headers: ana.h })).text();
  assert.match(csv, /rbac\.com/);

  assert.equal((await fetch(aurl(`/admin/api/users/${u.userId}`), { method: 'PATCH', headers: { ...root.h, 'content-type': 'application/json' }, body: JSON.stringify({ status: 'suspended' }) })).status, 200);
  assert.equal((await post(uurl('/api/v1/events'), { events: [ev('rb000002', 'https://rbac.com/q', 1)] }, u.h)).status, 403);
  assert.equal((await fetch(aurl(`/admin/api/users/${u.userId}`), { method: 'DELETE', headers: root.h })).status, 200);
});

test('cannot demote or disable the last superadmin; token revocation on change', async () => {
  const root = await login('root@x.io');
  const me = await (await fetch(aurl('/admin/api/admins'), { headers: root.h })).json();
  const rootId = me.rows.find((a) => a.email === 'root@x.io').id;
  const r = await fetch(aurl(`/admin/api/admins/${rootId}`), { method: 'PATCH', headers: { ...root.h, 'content-type': 'application/json' }, body: JSON.stringify({ role: 'viewer' }) });
  assert.equal(r.status, 400);

  const viewer = me.rows.find((a) => a.email === 'vie@x.io').id;
  const vie = await login('vie@x.io');
  assert.equal((await fetch(aurl('/admin/api/overview'), { headers: vie.h })).status, 200);
  await fetch(aurl(`/admin/api/admins/${viewer}`), { method: 'PATCH', headers: { ...root.h, 'content-type': 'application/json' }, body: JSON.stringify({ active: false }) });
  assert.equal((await fetch(aurl('/admin/api/overview'), { headers: vie.h })).status, 401);
});

test('account lockout after repeated failures', async () => {
  createAdmin(db, { email: 'lock@x.io', name: 'L', password: 'Sup3rSecretPass', role: 'viewer' });
  for (let i = 0; i < 5; i++) await login('lock@x.io', 'nope-nope-nope');
  const r = await login('lock@x.io');
  assert.equal(r.r.status, 423);
});

test('superadmin can delete single/all history and searches; analyst and viewer cannot', async () => {
  const u = await newUser(true);
  await post(uurl('/api/v1/events'), { events: [
    ev('del00001', 'https://one.com/', 1000), ev('del00002', 'https://two.com/', 2000),
    { type: 'search', id: 'dels0001', query: 'alpha', engine: 'google', at: now },
    { type: 'search', id: 'dels0002', query: 'beta', engine: 'google', at: now },
    { type: 'session', id: 'delsess1', startedAt: now - 5000, endedAt: now },
  ] }, u.h);
  const root = await login('root@x.io'), ana = await login('ana@x.io');
  const del = (path, who, body) => fetch(aurl(path), { method: 'DELETE', headers: { ...who.h, 'content-type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
  const count = (t) => db.prepare(`SELECT COUNT(*) n FROM ${t} WHERE user_id=?`).get(u.userId).n;

  assert.equal((await del(`/admin/api/users/${u.userId}/history/del00001`, ana)).status, 403);
  assert.equal((await del(`/admin/api/users/${u.userId}/searches`, ana)).status, 403);
  assert.equal((await del('/admin/api/data', ana, { scope: 'all', confirm: 'DELETE' })).status, 403);
  assert.equal((await fetch(aurl('/admin/api/server-info'), { headers: ana.h })).status, 403);

  assert.equal((await (await del(`/admin/api/users/${u.userId}/history/del00001`, root)).json()).deleted, 1);
  assert.equal(count('visits'), 1);
  assert.equal((await (await del(`/admin/api/users/${u.userId}/searches/dels0001`, root)).json()).deleted, 1);
  assert.equal(count('searches'), 1);
  assert.equal((await del(`/admin/api/users/${u.userId}/history`, root)).status, 200);
  assert.equal(count('visits') + count('sessions'), 0);
  assert.equal((await del(`/admin/api/users/${u.userId}/searches`, root)).status, 200);
  assert.equal(count('searches'), 0);
  assert.equal(db.prepare('SELECT COUNT(*) n FROM users WHERE id=?').get(u.userId).n, 1); // account remains

  assert.equal((await del('/admin/api/data', root, { scope: 'all' })).status, 400); // needs confirmation
  assert.equal((await del('/admin/api/data', root, { scope: 'all', confirm: 'DELETE' })).status, 200);
  assert.equal(db.prepare('SELECT COUNT(*) n FROM visits').get().n, 0);

  const info = await (await fetch(aurl('/admin/api/server-info'), { headers: root.h })).json();
  assert.equal(info.firebase, false);
  assert.match(info.storage.engine, /SQLite/);
  const audit = await (await fetch(aurl('/admin/api/audit?limit=100'), { headers: root.h })).json();
  assert.ok(audit.rows.some((a) => a.action === 'clear_all_data'));
});

test('privacy policy and terms are served publicly', async () => {
  for (const [p, t] of [['/privacy', 'Privacy Policy'], ['/terms', 'Terms of Service']]) {
    const r = await fetch(uurl(p));
    assert.equal(r.status, 200);
    assert.match(r.headers.get('content-type'), /html/);
    assert.match(await r.text(), new RegExp(t));
  }
});
