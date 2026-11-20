// Data layer for the Admin App on Firebase. api(path, opts) mimics the old REST API so the views stay the same,
// but everything runs in the browser against Firestore; firestore.rules is what enforces who can read or delete.
import { initializeApp } from 'https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js';
import { getAuth, connectAuthEmulator, signInWithEmailAndPassword, signOut, onAuthStateChanged, EmailAuthProvider, reauthenticateWithCredential, updatePassword, sendPasswordResetEmail, sendSignInLinkToEmail, isSignInWithEmailLink, signInWithEmailLink }
  from 'https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js';
import { getFirestore, connectFirestoreEmulator, collection, collectionGroup, query, where, orderBy, limit, startAfter, getDocs, getDoc, doc, updateDoc, setDoc, writeBatch, addDoc, getCountFromServer, onSnapshot }
  from 'https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js';
import config from './firebase-config.js';

const PERMS = {
  superadmin: ['aggregate', 'user_list', 'user_detail', 'export', 'manage_users', 'manage_admins', 'audit', 'delete_data', 'server_info', 'manage_config'],
  analyst: ['aggregate', 'user_list', 'user_detail', 'export', 'server_info'],
};
// The owner is the one permanent superadmin (see firestore.rules isBoss()). Same email, verified, is superadmin everywhere.
const OWNER = 'mybrowser.server@gmail.com';
const isOwnerUser = (u) => !!u && (u.email || '').toLowerCase() === OWNER && u.emailVerified === true;
const MAX_QUERY = 10000;  // Firestore rejects any query limit above 10,000
const MAX_DOCS = 30000;   // dashboards aggregate in the browser; this keeps queries bounded

let app, auth, db, me = null;
// On localhost with an empty config the page talks to the local Firebase emulators (never to production).
const LOCAL = ['127.0.0.1', 'localhost'].includes(location.hostname) && !config.apiKey;
export const configured = () => LOCAL || (!!config.apiKey && !!config.projectId);

const err = (m) => Object.assign(new Error(m), {});
const ts = (m) => (typeof m === 'number' ? m : 0);

/** Resolves to the current admin ({email,name,role,permissions}) or null. Rejects non-admin accounts. */
export function initFirebase() {
  if (!configured()) return Promise.resolve(null);
  if (app) return Promise.resolve(me);   // already initialised (e.g. boot() after a successful login)
  app = initializeApp(LOCAL ? { apiKey: 'fake-key', projectId: 'demo-wbrowser', appId: 'local', authDomain: 'localhost' } : config);
  auth = getAuth(app); db = getFirestore(app);
  if (LOCAL) { connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true }); connectFirestoreEmulator(db, '127.0.0.1', 8080); }
  return new Promise((resolve) => {
    const off = onAuthStateChanged(auth, async (u) => {
      off();
      resolve(u ? await loadMe(u).catch(() => null) : null);
    });
  });
}

async function loadMe(u, force = false) {
  const t = await u.getIdTokenResult(force);
  const owner = isOwnerUser(u);
  let role = owner ? 'superadmin' : t.claims.role;
  if (role === 'superadmin' && !owner) role = 'analyst';      // there is exactly one superadmin
  if (!PERMS[role]) { await signOut(auth); throw err('forbidden'); }
  me = { email: u.email, name: u.displayName || u.email, role, permissions: PERMS[role], uid: u.uid, owner };
  // self-heal: if the Admins-list record was deleted, put it back (access never depended on it)
  getDoc(doc(db, 'admins', u.uid)).then((s) => { if (!s.exists()) return setDoc(doc(db, 'admins', u.uid), { email: u.email, name: me.name, role, updatedAt: Date.now() }); }).catch(() => {});
  return me;
}

const audit = (action, target, detail = '') =>
  addDoc(collection(db, 'audit'), { at: Date.now(), by: me.email, action, target: String(target || ''), detail: String(detail).slice(0, 300) }).catch(() => {});

// ---------- owner recovery: sign in with an emailed link (works even if the Auth user was deleted) ----------
const LINK_KEY = 'wba_link_email';
export async function sendSignInLink(email) {
  const e = String(email || '').trim().toLowerCase();
  if (e !== OWNER) return;                       // only the owner has this path; the caller always shows the same message
  await sendSignInLinkToEmail(auth, e, { url: location.origin + location.pathname, handleCodeInApp: true });
  try { localStorage.setItem(LINK_KEY, e); } catch { /* ignore */ }
}
export const hasEmailLink = () => !!auth && isSignInWithEmailLink(auth, location.href);
export const storedLinkEmail = () => { try { return localStorage.getItem(LINK_KEY) || ''; } catch { return ''; } };
export async function finishEmailLink(email) {
  const e = String(email || '').trim().toLowerCase();
  if (e !== OWNER) throw err('invalid_credentials');
  await signInWithEmailLink(auth, e, location.href);
  try { localStorage.removeItem(LINK_KEY); } catch { /* ignore */ }
  history.replaceState(null, '', location.pathname + '#/');
  return loadMe(auth.currentUser, true);
}

// ---------- caches ----------
const cache = new Map();
async function cached(key, fn, ttl = 6000) {
  const c = cache.get(key);
  if (c && Date.now() - c.t < ttl) return c.v;
  const v = await fn(); cache.set(key, { t: Date.now(), v }); return v;
}
const bust = () => cache.clear();
export const refreshData = () => cache.clear();
// Presence (online/offline) changes far more often than history, so it gets its own, un-cached cache key.
export const bustUsers = () => cache.delete('users');

const users = () => cached('users', async () => (await getDocs(collection(db, 'users'))).docs.map((d) => ({ uid: d.id, ...d.data() })));

/**
 * Live presence: the phone app writes `online` + `lastSeen` on the user document the instant it is
 * foregrounded/backgrounded (see Cloud.setOnline in the app), so a Firestore realtime listener here
 * reflects it within about a second - no polling, no manual refresh. Returns an unsubscribe function.
 */
export function watchPresence(cb) {
  if (!configured() || !me) return () => {};
  return onSnapshot(collection(db, 'users'), (snap) => {
    const map = {};
    snap.forEach((d) => { const u = d.data(); map[d.id] = { online: u.online === true, lastSeen: ts(u.lastSeen) }; });
    cb(map);
  }, () => {});
}
// Per-user queries (default single-field indexes): no collection-group index is needed.
const rangeDocs = (group, field, from, to) => cached(`${group}:${from}:${to}`, async () => {
  const us = await users(); const out = [];
  await Promise.all(us.map(async (u) => {
    const snap = await getDocs(query(collection(db, 'users', u.uid, group), where(field, '>=', from), where(field, '<=', to), orderBy(field, 'desc'), limit(Math.min(MAX_QUERY, Math.ceil(MAX_DOCS / Math.max(us.length, 1))))));
    snap.docs.forEach((d) => out.push({ id: d.id, uid: u.uid, ...d.data() }));
  }));
  return out;
});

const day = (ms, tz) => new Date(ms + tz * 60000).toISOString().slice(0, 10);
const domainMap = (visits) => {
  const m = new Map();
  for (const v of visits) {
    const e = m.get(v.domain) || { domain: v.domain, visits: 0, users: new Set(), totalMs: 0, lastVisit: 0 };
    e.visits++; e.users.add(v.uid); e.totalMs += v.durationMs || 0; e.lastVisit = Math.max(e.lastVisit, v.visitedAt); m.set(v.domain, e);
  }
  return [...m.values()].map((e) => ({ domain: e.domain, visits: e.visits, users: e.users.size, totalMs: e.totalMs, avgMs: e.totalMs / e.visits, lastVisit: e.lastVisit }));
};
const dailyVisits = (visits, tz) => {
  const m = new Map();
  for (const v of visits) {
    const k = day(v.visitedAt, tz); const e = m.get(k) || { day: k, visits: 0, users: new Set(), ms: 0 };
    e.visits++; e.users.add(v.uid); e.ms += v.durationMs || 0; m.set(k, e);
  }
  return [...m.values()].map((e) => ({ day: e.day, visits: e.visits, users: e.users.size, ms: e.ms })).sort((a, b) => a.day.localeCompare(b.day));
};
const sessionStats = (rows) => ({ n: rows.length, totalMs: rows.reduce((s, r) => s + (r.durationMs || 0), 0), avgMs: rows.length ? rows.reduce((s, r) => s + (r.durationMs || 0), 0) / rows.length : 0 });

// ---------- subcollection helpers ----------
async function wipeSub(uid, name, onlyIds) {
  let n = 0;
  for (;;) {
    const snap = await getDocs(query(collection(db, 'users', uid, name), limit(400)));
    if (snap.empty) break;
    const b = writeBatch(db); snap.docs.forEach((d) => b.delete(d.ref)); await b.commit(); n += snap.size;
    if (snap.size < 400) break;
  }
  return n;
}
const csvCell = (v) => { let s = v == null ? '' : String(v); if (/^[=+\-@\t\r]/.test(s)) s = "'" + s; return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s; };

export async function csvDownload(uid, qs) {
  const p = new URLSearchParams(qs); const from = Number(p.get('from')), to = Number(p.get('to'));
  const lines = ['visited_at_iso,url,domain,title,duration_seconds'];
  let last = null;
  for (let page = 0; page < 20; page++) {            // up to 100,000 rows, 5,000 per request
    const snap = await getDocs(query(collection(db, 'users', uid, 'visits'), where('visitedAt', '>=', from), where('visitedAt', '<=', to), orderBy('visitedAt', 'desc'), ...(last ? [startAfter(last)] : []), limit(5000)));
    snap.docs.forEach((d) => { const v = d.data(); lines.push([new Date(v.visitedAt).toISOString(), v.url, v.domain, v.title, Math.round((v.durationMs || 0) / 1000)].map(csvCell).join(',')); });
    if (snap.size < 5000) break;
    last = snap.docs[snap.docs.length - 1];
  }
  await audit('export_history', uid);
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([lines.join('\n')], { type: 'text/csv' })); a.download = `history-${uid}.csv`; a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 5000);
}

// ---------- the REST-shaped facade ----------
export async function api(path, opts = {}) {
  const method = opts.method || 'GET';
  const [pathname, qs = ''] = path.split('?');
  const q = new URLSearchParams(qs);
  const to = Number(q.get('to')) || Date.now(), from = Number(q.get('from')) || to - 7 * 86400000, tz = parseInt(q.get('tz'), 10) || 0;
  const page = Math.max(parseInt(q.get('page'), 10) || 1, 1), lim = Math.min(Math.max(parseInt(q.get('limit'), 10) || 50, 1), 500);
  const search = (q.get('q') || '').toLowerCase();
  const seg = pathname.split('/').filter(Boolean);

  // ----- auth -----
  if (pathname === '/auth/login') {
    try { const c = await signInWithEmailAndPassword(auth, opts.body.email, opts.body.password); const m = await loadMe(c.user, true); return { email: m.email, name: m.name, role: m.role }; }
    catch (e) { throw err(e.message === 'forbidden' ? 'forbidden' : (e.code === 'auth/too-many-requests' ? 'too_many_attempts' : 'invalid_credentials')); }
  }
  if (pathname === '/auth/reset') {   // always answers ok, so it never reveals which emails are admins
    try { await sendPasswordResetEmail(auth, String(opts.body?.email || '').trim()); } catch (e) { if (e.code === 'auth/too-many-requests') throw err('too_many_attempts'); }
    return { ok: true };
  }
  if (pathname === '/auth/logout') { await signOut(auth); me = null; bust(); return { ok: true }; }
  if (pathname === '/auth/me') { if (!me) throw err('unauthorized'); return me; }
  if (pathname === '/auth/change-password') {
    try {
      await reauthenticateWithCredential(auth.currentUser, EmailAuthProvider.credential(me.email, opts.body.current));
      if (!opts.body.next || opts.body.next.length < 12) throw err('weak_password');
      await updatePassword(auth.currentUser, opts.body.next); return { ok: true };
    } catch (e) { throw err(e.message === 'weak_password' ? 'weak_password' : 'invalid_credentials'); }
  }
  if (!me) throw err('unauthorized');
  if (pathname === '/auth/set-password') {         // owner only: used right after an email-link sign-in (no current password exists)
    if (!me.owner) throw err('forbidden');
    if (!opts.body.next || opts.body.next.length < 12) throw err('weak_password');
    try { await updatePassword(auth.currentUser, opts.body.next); return { ok: true }; }
    catch (e) { throw err(e.code === 'auth/requires-recent-login' ? 'recent_login' : 'failed'); }
  }

  const view0 = (u) => ({ id: u.uid, code: u.userId, name: u.name, email: u.email || null, last_seen: ts(u.lastSeen) || null, online: u.online === true, status: u.status });
  // ----- aggregates -----
  if (pathname === '/overview') {
    const [visits, sess, us] = await Promise.all([rangeDocs('visits', 'visitedAt', from, to), rangeDocs('sessions', 'startedAt', from, to), users()]);
    const sites = domainMap(visits).sort((a, b) => b.visits - a.visits);
    return {
      range: { from, to },
      totals: { visits: visits.length, activeUsers: new Set(visits.map((v) => v.uid)).size, domains: sites.length, totalMs: visits.reduce((s, v) => s + (v.durationMs || 0), 0) },
      sessions: sessionStats(sess),
      users: { total: us.length, newUsers: us.filter((u) => ts(u.createdAt) >= from && ts(u.createdAt) <= to).length, searchConsent: us.filter((u) => u.searchConsent).length },
      daily: dailyVisits(visits, tz), topSites: sites.slice(0, 10),
      hourly: Array.from({ length: 24 }, (_, h) => ({ hour: h, visits: visits.filter((v) => new Date(v.visitedAt + tz * 60000).getUTCHours() === h).length })),
      recentUsers: [...us].sort((a, b) => ts(b.lastSeen) - ts(a.lastSeen)).slice(0, 5).map(view0),
    };
  }
  if (pathname === '/websites') {
    const visits = await rangeDocs('visits', 'visitedAt', from, to);
    let rows = domainMap(visits).filter((r) => r.domain.toLowerCase().includes(search));
    rows.sort((a, b) => (q.get('sort') === 'time' ? b.totalMs - a.totalMs : b.visits - a.visits));
    return { rows: rows.slice(0, lim) };
  }
  // ----- one website: every page opened on it, who opened it and when -----
  if (seg[0] === 'websites' && seg[1]) {
    const domain = decodeURIComponent(seg[1]).toLowerCase();
    const [visits, us] = await Promise.all([rangeDocs('visits', 'visitedAt', from, to), users()]);
    const names = new Map(us.map((u) => [u.uid, u.name]));
    const mine = visits.filter((v) => v.domain === domain);
    const pages = new Map(), byUser = new Map();
    for (const v of mine) {
      const p = pages.get(v.url) || { url: v.url, title: v.title || '', visits: 0, totalMs: 0, lastVisit: 0, users: new Set() };
      p.visits++; p.totalMs += v.durationMs || 0; p.users.add(v.uid);
      if (v.visitedAt >= p.lastVisit) { p.lastVisit = v.visitedAt; if (v.title) p.title = v.title; }
      pages.set(v.url, p);
      const b = byUser.get(v.uid) || { uid: v.uid, name: names.get(v.uid) || 'Unknown', visits: 0, totalMs: 0, lastVisit: 0 };
      b.visits++; b.totalMs += v.durationMs || 0; b.lastVisit = Math.max(b.lastVisit, v.visitedAt); byUser.set(v.uid, b);
    }
    return {
      domain, totals: { visits: mine.length, pages: pages.size, users: byUser.size, totalMs: mine.reduce((t, v) => t + (v.durationMs || 0), 0) },
      pages: [...pages.values()].map((p) => ({ ...p, users: p.users.size })).sort((a, b) => b.visits - a.visits || b.lastVisit - a.lastVisit),
      timeline: [...mine].sort((a, b) => b.visitedAt - a.visitedAt).slice(0, 1000).map((v) => ({ id: v.id, uid: v.uid, user: names.get(v.uid) || 'Unknown', url: v.url, title: v.title || '', visited_at: v.visitedAt, duration_ms: v.durationMs })),
      byUser: [...byUser.values()].sort((a, b) => b.visits - a.visits),
    };
  }
  if (pathname === '/sessions') {
    const sess = await rangeDocs('sessions', 'startedAt', from, to); const m = new Map();
    for (const s of sess) { const k = day(s.startedAt, tz); const e = m.get(k) || { day: k, sessions: 0, totalMs: 0 }; e.sessions++; e.totalMs += s.durationMs || 0; m.set(k, e); }
    return { daily: [...m.values()].map((e) => ({ ...e, avgMs: e.totalMs / e.sessions })).sort((a, b) => a.day.localeCompare(b.day)) };
  }

  // ----- users -----
  const view = (u) => ({ id: u.uid, code: u.userId, name: u.name, email: u.email || null, google_name: u.googleName || null, created_at: ts(u.createdAt), last_seen: ts(u.lastSeen) || null,
    online: u.online === true, status: u.status, search_consent: u.searchConsent ? 1 : 0, activity_consent: u.activityConsent ? 1 : 0, device_model: u.device?.model, os_version: u.device?.os, app_version: u.device?.appVersion,
    manufacturer: u.device?.manufacturer, sdk: u.device?.sdk, locale: u.locale, timezone: u.timezone, name_source: u.nameSource });

  if (pathname === '/users' && method === 'GET') {
    if (!me.permissions.includes('user_list')) throw err('forbidden');
    const [us, visits, sess] = await Promise.all([users(), rangeDocs('visits', 'visitedAt', from, to), rangeDocs('sessions', 'startedAt', from, to)]);
    const vBy = new Map(), sBy = new Map();
    for (const v of visits) { const e = vBy.get(v.uid) || { n: 0, ms: 0 }; e.n++; e.ms += v.durationMs || 0; vBy.set(v.uid, e); }
    for (const s of sess) sBy.set(s.uid, (sBy.get(s.uid) || 0) + (s.durationMs || 0));
    const rows = us.filter((u) => !search || [u.name, u.userId, u.email].some((x) => (x || '').toLowerCase().includes(search)))
      .sort((a, b) => ts(b.lastSeen) - ts(a.lastSeen))
      .map((u) => ({ ...view(u), visits: vBy.get(u.uid)?.n || 0, totalMs: vBy.get(u.uid)?.ms || 0, sessionMs: sBy.get(u.uid) || 0 }));
    return { rows: rows.slice((page - 1) * lim, page * lim), total: rows.length };
  }

  if (seg[0] === 'users' && seg[1]) {
    const uid = decodeURIComponent(seg[1]); const sub = seg[2]; const subId = seg[3];
    if (!me.permissions.includes('user_detail') && method === 'GET') throw err('forbidden');
    const userSnap = await getDoc(doc(db, 'users', uid));
    if (!userSnap.exists()) throw err('not_found');
    const u = { uid, ...userSnap.data() };
    const inRange = (col, field) => getDocs(query(collection(db, 'users', uid, col), where(field, '>=', from), where(field, '<=', to), orderBy(field, 'desc'), limit(5000)));

    if (!sub && method === 'GET') {
      const [vs, ss] = await Promise.all([inRange('visits', 'visitedAt'), inRange('sessions', 'startedAt')]);
      const visits = vs.docs.map((d) => ({ uid, ...d.data() })); const sites = domainMap(visits);
      await audit('view_user', uid);
      return { user: view(u), stats: { visits: visits.length, domains: sites.length, totalMs: visits.reduce((s, v) => s + (v.durationMs || 0), 0) },
        sessions: sessionStats(ss.docs.map((d) => d.data())), topSites: sites.sort((a, b) => b.totalMs - a.totalMs).slice(0, 10),
        daily: dailyVisits(visits, tz).map((d) => ({ day: d.day, visits: d.visits, ms: d.ms })),
        consents: [{ purpose: 'activity', granted: u.activityConsent ? 1 : 0, version: 1, at: ts(u.createdAt) }, { purpose: 'search', granted: u.searchConsent ? 1 : 0, version: 1, at: ts(u.createdAt) }] };
    }
    if (sub === 'history' && method === 'GET') {
      const all = (await inRange('visits', 'visitedAt')).docs.map((d) => ({ id: d.id, ...d.data() }))
        .filter((v) => !search || v.url.toLowerCase().includes(search) || (v.title || '').toLowerCase().includes(search));
      await audit('view_history', uid);
      return { rows: all.slice((page - 1) * lim, page * lim).map((v) => ({ id: v.id, url: v.url, domain: v.domain, title: v.title, visited_at: v.visitedAt, duration_ms: v.durationMs })), total: all.length };
    }
    if (sub === 'sessions' && method === 'GET') {
      const rows = (await inRange('sessions', 'startedAt')).docs.map((d) => ({ id: d.id, started_at: d.data().startedAt, ended_at: d.data().endedAt, duration_ms: d.data().durationMs }));
      await audit('view_sessions', uid); return { rows: rows.slice(0, lim) };
    }
    if (sub === 'searches' && method === 'GET') {
      const rows = (await inRange('searches', 'at')).docs.map((d) => ({ id: d.id, query: d.data().query, engine: d.data().engine, searched_at: d.data().at }));
      await audit('view_searches', uid); return { rows: rows.slice(0, lim), consent: !!u.searchConsent };
    }
    if (method === 'DELETE') {
      if (!me.permissions.includes('delete_data')) throw err('forbidden');
      let deleted = 0;
      if (!sub) {
        if ((await getDoc(doc(db, 'admins', uid))).exists()) throw err('protected_admin');   // admin accounts can never be erased
        for (const c of ['visits', 'searches', 'sessions']) await wipeSub(uid, c); const b = writeBatch(db); b.delete(doc(db, 'users', uid)); await b.commit(); await audit('erase_user', uid); }
      else if (subId) { const b = writeBatch(db); b.delete(doc(db, 'users', uid, sub === 'history' ? 'visits' : sub, subId)); await b.commit(); deleted = 1; await audit(sub === 'history' ? 'delete_visit' : 'delete_search', uid, subId); }
      else if ((sub === 'history' || sub === 'searches') && Array.isArray(opts.body?.ids)) {   // multi-select delete
        const col = sub === 'history' ? 'visits' : 'searches'; const ids = opts.body.ids.map(String);
        for (let i = 0; i < ids.length; i += 400) { const b = writeBatch(db); ids.slice(i, i + 400).forEach((id) => b.delete(doc(db, 'users', uid, col, id))); await b.commit(); }
        deleted = ids.length; await audit(sub === 'history' ? 'delete_visits' : 'delete_searches', uid, `${deleted} entries`);
      }
      else if (sub === 'history') { deleted = await wipeSub(uid, 'visits'); await wipeSub(uid, 'sessions'); await audit('clear_user_history', uid, `${deleted} visits`); }
      else if (sub === 'searches') { deleted = await wipeSub(uid, 'searches'); await audit('clear_user_searches', uid, `${deleted} searches`); }
      bust(); return { ok: true, deleted };
    }
    if (method === 'PATCH' && !sub) {
      if (!me.permissions.includes('manage_users')) throw err('forbidden');
      await updateDoc(doc(db, 'users', uid), { status: opts.body.status }); await audit('set_user_status', uid, opts.body.status); bust(); return { ok: true };
    }
  }

  // ----- clear everything (superadmin) -----
  if (pathname === '/data' && method === 'DELETE') {
    if (!me.permissions.includes('delete_data') || opts.body?.confirm !== 'DELETE') throw err('forbidden');
    const scope = opts.body.scope; const out = {};
    for (const u of await users()) {
      if (scope === 'history' || scope === 'all') { out.visits = (out.visits || 0) + await wipeSub(u.uid, 'visits'); out.sessions = (out.sessions || 0) + await wipeSub(u.uid, 'sessions'); }
      if (scope === 'searches' || scope === 'all') out.searches = (out.searches || 0) + await wipeSub(u.uid, 'searches');
    }
    await audit('clear_all_data', scope, JSON.stringify(out)); bust(); return { ok: true, deleted: out };
  }

  // ----- remote settings read by the phone app (config/app) -----
  if (pathname === '/config') {
    if (!me.permissions.includes('manage_config')) throw err('forbidden');
    const ref = doc(db, 'config', 'app');
    if (method === 'GET') {
      const d = (await getDoc(ref)).data() || {};
      return { trackingEnabled: d.trackingEnabled !== false, searchAllowed: d.searchAllowed !== false, uploadIntervalSeconds: d.uploadIntervalSeconds ?? 15, retentionDays: d.retentionDays ?? 180, announcement: d.announcement ?? '', updatedAt: d.updatedAt || null, updatedBy: d.updatedBy || '' };
    }
    const b = opts.body;
    const data = { trackingEnabled: !!b.trackingEnabled, searchAllowed: !!b.searchAllowed, uploadIntervalSeconds: Math.min(600, Math.max(5, parseInt(b.uploadIntervalSeconds, 10) || 15)),
      retentionDays: Math.min(3650, Math.max(1, parseInt(b.retentionDays, 10) || 180)), announcement: String(b.announcement || '').slice(0, 200), updatedAt: Date.now(), updatedBy: me.email };
    await setDoc(ref, data); await audit('update_config', 'config/app', JSON.stringify(data).slice(0, 280)); bust(); return { ok: true, ...data };
  }
  // delete everything older than N days for every user (real deletes in Firestore)
  if (pathname === '/purge' && method === 'POST') {
    if (!me.permissions.includes('delete_data')) throw err('forbidden');
    const days = Math.max(1, parseInt(opts.body?.days, 10) || 0); if (!days) throw err('invalid_request');
    const cutoff = Date.now() - days * 86400000; const out = { visits: 0, sessions: 0, searches: 0 };
    for (const u of await users()) {
      for (const [col, field, key] of [['visits', 'visitedAt', 'visits'], ['sessions', 'startedAt', 'sessions'], ['searches', 'at', 'searches']]) {
        for (;;) {
          const snap = await getDocs(query(collection(db, 'users', u.uid, col), where(field, '<', cutoff), limit(400)));
          if (snap.empty) break;
          const b = writeBatch(db); snap.docs.forEach((d) => b.delete(d.ref)); await b.commit(); out[key] += snap.size;
          if (snap.size < 400) break;
        }
      }
    }
    await audit('purge_older_than', String(days) + ' days', JSON.stringify(out)); bust(); return { ok: true, deleted: out };
  }

  // ----- admin accounts, audit, server -----
  if (pathname === '/admins') {
    if (!me.permissions.includes('manage_admins')) throw err('forbidden');
    return { rows: (await getDocs(collection(db, 'admins'))).docs.map((d, i) => ({ id: i, ...d.data(), active: 1, created_at: d.data().updatedAt, last_login: null })) };
  }
  if (pathname === '/audit') {
    if (!me.permissions.includes('audit')) throw err('forbidden');
    const s = await getDocs(query(collection(db, 'audit'), orderBy('at', 'desc'), limit(200)));
    return { rows: s.docs.map((d) => ({ at: d.data().at, admin_email: d.data().by, action: d.data().action, target: d.data().target, detail: d.data().detail, ip: '' })) };
  }
  if (pathname === '/server-info') {
    const n = async (c) => (await getCountFromServer(collection(db, c))).data().count;
    const c = async (name) => { let t = 0; for (const u of await users()) t += (await getCountFromServer(collection(db, 'users', u.uid, name))).data().count; return t; };
    const [u, v, s, se, a] = await Promise.all([n('users'), c('visits'), c('searches'), c('sessions'), n('admins')]);
    return { firebase: true, projectId: config.projectId, host: location.origin, counts: { users: u, visits: v, searches: s, sessions: se, admins: a } };
  }
  throw err('not_found');
}
