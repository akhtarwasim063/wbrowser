// WBrowser Admin App (Firebase). Plain JS, no inline code (strict CSP). All dynamic text goes through textContent.
import { api as fbApi, initFirebase, configured, csvDownload, refreshData, watchPresence, bustUsers } from './fb.js';
(() => {
  const root = document.getElementById('app');
  let me = null;
  // small per-browser preferences (theme, default date range, auto refresh)
  const prefs = { get: (k, d) => { try { return localStorage.getItem('wba_' + k) ?? d; } catch { return d; } }, set: (k, v) => { try { localStorage.setItem('wba_' + k, v); } catch { /* ignore */ } } };
  const applyTheme = () => { const t = prefs.get('theme', 'auto'); if (t === 'auto') delete document.documentElement.dataset.theme; else document.documentElement.dataset.theme = t; };
  applyTheme();
  const filter = { preset: prefs.get('range', '7'), from: '', to: '' }; // shared date filter
  let currentLoad = null, refreshTimer = null;

  // ---------- live presence (online/offline) ----------
  // One Firestore listener for the whole session; any change re-runs whatever view is open so the
  // online dots update within about a second, with no manual refresh needed.
  let presenceUnsub = null;
  function startPresence() {
    if (presenceUnsub || !me) return;
    presenceUnsub = watchPresence(() => { bustUsers(); if (currentLoad) currentLoad(); });
  }
  function stopPresence() { presenceUnsub?.(); presenceUnsub = null; }

  // ---------- tiny DOM helpers ----------
  const h = (tag, attrs, ...kids) => {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (k === 'class') e.className = v;
      else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
      else if (v !== false && v != null) e.setAttribute(k, v === true ? '' : v);
    }
    for (const kid of kids.flat(Infinity)) if (kid != null && kid !== false) e.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
    return e;
  };
  const NS = 'http://www.w3.org/2000/svg';
  const s = (tag, attrs, ...kids) => {
    const e = document.createElementNS(NS, tag);
    for (const [k, v] of Object.entries(attrs || {})) e.setAttribute(k, v);
    for (const kid of kids) if (kid != null) e.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
    return e;
  };

  // ---------- icons (stroke icons, 24px grid) ----------
  const ICON = {
    grid: 'M3 3h7v9H3z|M14 3h7v5h-7z|M14 12h7v9h-7z|M3 16h7v5H3z',
    globe: 'M12 2a10 10 0 100 20 10 10 0 000-20z|M2 12h20|M12 2c3 3 3 17 0 20|M12 2c-3 3-3 17 0 20',
    clock: 'M12 2a10 10 0 100 20 10 10 0 000-20z|M12 6v6l4 2',
    users: 'M17 21v-2a4 4 0 00-4-4H7a4 4 0 00-4 4v2|M10 11a4 4 0 100-8 4 4 0 000 8z|M21 21v-2a4 4 0 00-3-3.9|M16 3.1a4 4 0 010 7.8',
    user: 'M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2|M12 11a4 4 0 100-8 4 4 0 000 8z',
    userplus: 'M16 21v-2a4 4 0 00-4-4H6a4 4 0 00-4 4v2|M9 11a4 4 0 100-8 4 4 0 000 8z|M19 8v6|M22 11h-6',
    activity: 'M22 12h-4l-3 9L9 3l-3 9H2',
    shield: 'M12 2l8 3v6c0 5-3.5 9-8 11-4.5-2-8-6-8-11V5z|M9 12l2 2 4-4',
    db: 'M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z|M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6|M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6',
    list: 'M8 6h13|M8 12h13|M8 18h13|M3 6h.01|M3 12h.01|M3 18h.01',
    layers: 'M12 2l10 5-10 5L2 7z|M2 17l10 5 10-5|M2 12l10 5 10-5',
    out: 'M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4|M16 17l5-5-5-5|M21 12H9',
    search: 'M11 19a8 8 0 100-16 8 8 0 000 16z|M21 21l-4.3-4.3',
    download: 'M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4|M7 10l5 5 5-5|M12 15V3',
    trash: 'M3 6h18|M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2|M19 6l-1 14a2 2 0 01-2 2H8a2 2 0 01-2-2L5 6',
    pause: 'M6 4h4v16H6z|M14 4h4v16h-4z',
    back: 'M19 12H5|M12 19l-7-7 7-7',
    check: 'M20 6L9 17l-5-5',
    lock: 'M5 11h14v10H5z|M8 11V7a4 4 0 018 0v4',
    refresh: 'M21 12a9 9 0 11-3-6.7|M21 3v6h-6',
    gear: 'M12 15a3 3 0 100-6 3 3 0 000 6z|M19.4 15a1.7 1.7 0 00.3 1.8l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-1.8-.3 1.7 1.7 0 00-1 1.5V21a2 2 0 11-4 0v-.1a1.7 1.7 0 00-1.1-1.5 1.7 1.7 0 00-1.8.3l-.1.1a2 2 0 11-2.8-2.8l.1-.1a1.7 1.7 0 00.3-1.8 1.7 1.7 0 00-1.5-1H3a2 2 0 110-4h.1a1.7 1.7 0 001.5-1.1 1.7 1.7 0 00-.3-1.8l-.1-.1a2 2 0 112.8-2.8l.1.1a1.7 1.7 0 001.8.3H9a1.7 1.7 0 001-1.5V3a2 2 0 114 0v.1a1.7 1.7 0 001 1.5 1.7 1.7 0 001.8-.3l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.8V9a1.7 1.7 0 001.5 1H21a2 2 0 110 4h-.1a1.7 1.7 0 00-1.5 1z',
    chev: 'M9 18l6-6-6-6',
  };
  const ico = (n) => { const e = s('svg', { class: 'ic', viewBox: '0 0 24 24', 'aria-hidden': 'true' }); for (const d of ICON[n].split('|')) e.append(s('path', { d })); return e; };
  const btn = (icon, text, attrs) => h('button', attrs, ico(icon), text);

  // ---------- formatting ----------
  const dur = (ms) => {
    const t = Math.round((ms || 0) / 1000);
    if (t < 60) return `${t}s`;
    if (t < 3600) return `${Math.floor(t / 60)}m ${t % 60}s`;
    return `${Math.floor(t / 3600)}h ${Math.floor((t % 3600) / 60)}m`;
  };
  const fmt = (n) => Number(n || 0).toLocaleString();
  const dt = (ms) => (ms ? new Date(ms).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }) : '—');
  const ago = (ms) => {
    if (!ms) return 'never';
    const d = Date.now() - ms;
    if (d < 60000) return 'just now';
    if (d < 3600000) return `${Math.floor(d / 60000)} min ago`;
    if (d < 86400000) return `${Math.floor(d / 3600000)} h ago`;
    return `${Math.floor(d / 86400000)} d ago`;
  };
  // Real-time: `online` is written by the phone the instant it is foregrounded/backgrounded (see
  // Cloud.setOnline), so a Firestore listener (watchPresence, below) keeps it live within ~1s.
  // The staleness fallback only covers an app build too old to send it, or an unclean process kill.
  const online = (u) => { if (!u) return false; if (u.online === true) return true; const ms = typeof u === 'object' ? u.last_seen : u; return !!ms && Date.now() - ms < 60000; };
  // Only http(s) targets become links (URLs are untrusted data). Opens in a new tab, no referrer / opener.
  const extLink = (href, text) => (/^https?:\/\//i.test(href) ? h('a', { href, target: '_blank', rel: 'noopener noreferrer', title: 'Open in new tab', onclick: (e) => e.stopPropagation() }, text) : text);
  const siteLink = (domain) => extLink('https://' + domain, domain);

  const initials = (n) => String(n || '?').trim().split(/\s+/).slice(0, 2).map((w) => w[0]).join('').toUpperCase() || '?';
  const tone = (str) => { let x = 0; for (const c of String(str)) x = (x * 31 + c.charCodeAt(0)) >>> 0; return 'c' + (x % 8); };
  const avatar = (name, extra = '') => h('div', { class: `av ${tone(name)} ${extra}` }, initials(name));

  const api = async (path, opts) => {
    try { return await fbApi(path, opts); }
    catch (e) { if (e.message === 'unauthorized' && path !== '/auth/login') { me = null; render(); throw new Error('Session expired'); } throw e; }
  };
  const can = (p) => me && me.permissions.includes(p);
  const errBox = (e) => h('p', { class: 'err' }, e.message);
  const loading = () => h('div', {}, h('div', { class: 'grid' }, [1, 2, 3, 4].map(() => h('div', { class: 'skeleton' }))), h('div', { class: 'skeleton' }));

  // ---------- date filter ----------
  function range() {
    const to = filter.preset === 'custom' && filter.to ? new Date(filter.to + 'T23:59:59.999').getTime() : Date.now();
    let from;
    if (filter.preset === 'custom' && filter.from) from = new Date(filter.from + 'T00:00:00').getTime();
    else if (filter.preset === 'today') { const d = new Date(); d.setHours(0, 0, 0, 0); from = d.getTime(); }
    else from = Date.now() - parseInt(filter.preset, 10) * 86400000;
    return `from=${from}&to=${to}&tz=${-new Date().getTimezoneOffset()}`;
  }
  const rangeLabel = () => (filter.preset === 'custom' ? `${filter.from || '…'} to ${filter.to || 'now'}` : filter.preset === 'today' ? 'today' : `last ${filter.preset} days`);

  function dateBar(onChange) {
    const seg = h('div', { class: 'seg' }, [['today', 'Today'], ['7', '7 days'], ['30', '30 days'], ['90', '90 days'], ['custom', 'Custom']].map(([v, l]) =>
      h('button', { class: filter.preset === v ? 'on' : '', onclick: () => { filter.preset = v; render(); } }, l)));
    const f = h('input', { type: 'date', value: filter.from, onchange: (e) => { filter.from = e.target.value; onChange(); } });
    const t = h('input', { type: 'date', value: filter.to, onchange: (e) => { filter.to = e.target.value; onChange(); } });
    return h('div', { class: 'row mb' }, seg, filter.preset === 'custom' ? [f, t] : null);
  }

  // ---------- charts ----------
  let gid = 0;
  function areaChart(rows, key, label, cls = '') {
    if (!rows.length) return h('div', { class: 'empty' }, 'No data in this range.');
    const W = 640, H = 150, L = 40, R = 10, T = 10, B = 24;
    const max = Math.max(1, ...rows.map((r) => r[key] || 0));
    const nice = Math.ceil(max / 4) * 4 || 4;
    const x = (i) => L + (rows.length === 1 ? (W - L - R) / 2 : (i * (W - L - R)) / (rows.length - 1));
    const y = (v) => T + (1 - v / nice) * (H - T - B);
    const id = `g${++gid}`;
    const svg = s('svg', { class: `chart ${cls}`, viewBox: `0 0 ${W} ${H}`, width: '100%', role: 'img', 'aria-label': label });
    const defs = s('defs', {}, s('linearGradient', { id, x1: 0, y1: 0, x2: 0, y2: 1 }, s('stop', { class: 's1', offset: '0%', 'stop-opacity': '0.35' }), s('stop', { class: 's1', offset: '100%', 'stop-opacity': '0' })));
    svg.append(defs);
    for (let i = 0; i <= 4; i++) {
      const v = (nice / 4) * i, yy = y(v);
      svg.append(s('line', { class: 'gl', x1: L, x2: W - R, y1: yy, y2: yy }), s('text', { x: L - 8, y: yy + 3, 'text-anchor': 'end' }, fmt(Math.round(v))));
    }
    const pts = rows.map((r, i) => [x(i), y(r[key] || 0)]);
    let d = `M${pts[0][0]},${pts[0][1]}`;
    for (let i = 1; i < pts.length; i++) { const [x0, y0] = pts[i - 1], [x1, y1] = pts[i], cx = (x0 + x1) / 2; d += ` C${cx},${y0} ${cx},${y1} ${x1},${y1}`; }
    svg.append(s('path', { d: `${d} L${pts[pts.length - 1][0]},${H - B} L${pts[0][0]},${H - B} Z`, fill: `url(#${id})`, class: 'ar' }));
    svg.append(s('path', { d, class: 'ln', fill: 'none', pathLength: '1' }));
    rows.forEach((r, i) => {
      svg.append(s('circle', { class: 'hit', cx: pts[i][0], cy: pts[i][1], r: 14 }, s('title', {}, `${r.day}: ${fmt(r[key])}`)),
        s('circle', { class: rows.length < 3 ? 'dotp show' : 'dotp', cx: pts[i][0], cy: pts[i][1], r: 4.5 }));
      if (rows.length <= 10 || i % Math.ceil(rows.length / 8) === 0) svg.append(s('text', { x: pts[i][0], y: H - 8, 'text-anchor': 'middle' }, r.day.slice(5)));
    });
    return svg;
  }

  function colChart(rows, key, label, xl) {
    const W = 640, H = 130, L = 34, B = 22, T = 8;
    const max = Math.max(1, ...rows.map((r) => r[key] || 0));
    const bw = (W - L) / rows.length;
    const svg = s('svg', { class: 'chart', viewBox: `0 0 ${W} ${H}`, width: '100%', role: 'img', 'aria-label': label });
    svg.append(s('line', { class: 'gl', x1: L, x2: W, y1: H - B, y2: H - B }), s('text', { x: L - 6, y: T + 6, 'text-anchor': 'end' }, fmt(max)));
    rows.forEach((r, i) => {
      const bh = Math.round(((r[key] || 0) / max) * (H - B - T));
      svg.append(s('rect', { class: 'cb', x: L + i * bw + 2, y: H - B - bh, width: Math.max(2, bw - 4), height: Math.max(bh, r[key] ? 2 : 0), rx: 3 }, s('title', {}, `${xl(r)}: ${fmt(r[key])}`)));
      if (i % 3 === 0) svg.append(s('text', { x: L + i * bw + bw / 2, y: H - 7, 'text-anchor': 'middle' }, xl(r)));
    });
    return svg;
  }

  function siteBars(sites) {
    if (!sites.length) return h('div', { class: 'empty' }, 'No websites yet.');
    const max = Math.max(1, ...sites.map((r) => r.visits));
    return h('div', { class: 'bars' }, sites.map((r) => {
      const fill = h('i');
      fill.style.width = `${Math.max(3, Math.round((r.visits / max) * 100))}%`;
      return h('div', { class: 'bar' }, h('div', { class: `av sq ${tone(r.domain)}` }, r.domain[0].toUpperCase()),
        h('div', { class: 'meta' }, siteLink(r.domain), h('div', { class: 'track' }, fill)),
        h('div', { class: 'val' }, `${fmt(r.visits)} visits`, h('small', {}, `${dur(r.totalMs)} · ${fmt(r.users)} user${r.users === 1 ? '' : 's'}`)));
    }));
  }

  // ---------- widgets ----------
  function table(cols, rows, onRow) {
    return h('table', {},
      h('thead', {}, h('tr', {}, cols.map((c) => h('th', { class: c.num ? 'num' : '' }, c.label)))),
      h('tbody', {}, rows.length ? rows.map((r) => h('tr', onRow ? { class: 'click', onclick: () => onRow(r) } : {},
        cols.map((c) => h('td', { class: (c.num ? 'num ' : '') + (c.main ? 'main ' : '') + (c.cls || ''), 'data-label': c.label || '' }, c.cell(r))))) : h('tr', {}, h('td', { colspan: cols.length, class: 'empty' }, 'Nothing to show.'))));
  }
  const kpi = (v, l, icon, t = '') => h('div', { class: `kpi ${t}` }, h('div', { class: 'badge' }, ico(icon)), h('b', {}, v), h('span', {}, l));
  const pager = (page, total, limit, go) => h('div', { class: 'pager' },
    h('span', { class: 'mut' }, `${fmt(total)} total`),
    h('button', { disabled: page <= 1, onclick: () => go(page - 1) }, 'Prev'), h('span', {}, `Page ${page}`),
    h('button', { disabled: page * limit >= total, onclick: () => go(page + 1) }, 'Next'));
  const card = (title, hint, ...body) => h('div', { class: 'card' }, h('h2', {}, title), hint ? h('p', { class: 'hint' }, hint) : h('div', { class: 'hint' }), body);
  const navRow = (href, icon, title, hint) => h('a', { class: 'listrow', href }, h('div', { class: 'badge' }, ico(icon)), h('div', {}, h('b', {}, title), h('div', { class: 'sub' }, hint)), h('span', { class: 'go' }, ico('chev')));
  const searchBox = (placeholder, on) => h('div', { class: 'search' }, ico('search'), h('input', { placeholder, oninput: (e) => on(e.target.value) }));


  // ---------- bottom-sheet dialogs (replace the browser's confirm / alert / prompt) ----------
  function dialogSheet({ icon, tone = '', title, text, field, actions }) {
    return new Promise((resolve) => {
      let done = false;
      const finish = (v) => { if (done) return; done = true; ov.classList.add('out'); setTimeout(() => ov.remove(), 190); resolve(v); };
      const input = field ? h('input', { placeholder: field, autocomplete: 'off', autocapitalize: 'off', oninput: () => sync() }) : null;
      const btns = actions.map((a) => h('button', { class: a.cls || '', onclick: () => finish(a.value(input)) }, a.label));
      const sync = () => actions.forEach((a, i) => { if (a.needs) btns[i].disabled = !a.needs(input); });
      const ov = h('div', { class: 'overlay', onclick: (e) => { if (e.target === ov) finish(actions.find((a) => a.cancel).value(input)); } },
        h('div', { class: 'sheet dlg' }, h('div', { class: 'dico ' + tone }, ico(icon)), h('h3', {}, title), h('p', {}, text), input, h('div', { class: 'row2' }, btns)));
      document.body.append(ov); sync(); input?.focus();
    });
  }
  const risky = (t) => /delete|erase|clear|permanently/i.test(t);
  const ask = (text, title) => dialogSheet({ icon: risky(text) ? 'trash' : 'check', tone: risky(text) ? 'bad' : '', title: title || (risky(text) ? 'Are you sure?' : 'Please confirm'), text,
    actions: [{ label: 'Cancel', value: () => false, cancel: true }, { label: risky(text) ? 'Delete' : 'Confirm', cls: risky(text) ? 'bad' : 'pri', value: () => true }] });
  const askType = (text) => dialogSheet({ icon: 'trash', tone: 'bad', title: 'Are you sure?', text, field: 'Type DELETE to confirm',
    actions: [{ label: 'Cancel', value: () => null, cancel: true }, { label: 'Delete', cls: 'bad', needs: (i) => i.value === 'DELETE', value: () => 'DELETE' }] });
  const notify = (text, title) => {
    const bad = /fail|error|invalid|expired|too many|not permitted/i.test(String(text));
    return dialogSheet({ icon: bad ? 'pause' : 'check', tone: bad ? 'bad' : 'ok', title: title || (bad ? 'Something went wrong' : 'Done'), text: String(text), actions: [{ label: 'OK', cls: 'pri', value: () => true, cancel: true }] });
  };

  // ---------- press and hold: select rows, then delete several at once ----------
  let sel = null, selBar = null;
  function endSelect() { sel = null; selBar?.remove(); selBar = null; document.querySelectorAll('.overlay').forEach((o) => o.remove()); document.querySelectorAll('.selecting').forEach((e) => e.classList.remove('selecting')); }
  function updateBar() {
    if (!selBar) return;
    selBar.querySelector('b').textContent = sel.size + ' selected';
    selBar.querySelector('.del').disabled = sel.size === 0;
  }
  function openSheet(title, items) {
    const close = () => ov.remove();
    const ov = h('div', { class: 'overlay', onclick: (e) => { if (e.target === ov) close(); } },
      h('div', { class: 'sheet' }, h('div', { class: 't' }, title),
        items.map(([icon, label, cls, fn]) => btn(icon, label, { class: cls || '', onclick: () => { close(); fn(); } })),
        h('button', { onclick: close }, 'Cancel')));
    document.body.append(ov);
  }
  function selectable(box, rows, kind, base, reload) {
    const trs = [...box.querySelectorAll('tbody tr')];
    const del = async (ids) => {
      if (!ids.length || !await ask(`Delete ${ids.length} ${ids.length === 1 ? 'entry' : 'entries'}?`)) return;
      try { await api(`${base}/${kind}`, { method: 'DELETE', body: { ids } }); endSelect(); reload(); } catch (er) { notify(er.message); }
    };
    const startSelect = (firstId) => {
      sel = new Set([firstId]);
      box.classList.add('selecting');
      trs.forEach((tr, i) => tr.classList.toggle('picked', rows[i] && sel.has(rows[i].id)));
      selBar?.remove();
      selBar = h('div', { class: 'selbar' }, h('b', {}, ''),
        h('button', { onclick: () => { sel = sel.size === rows.length ? new Set() : new Set(rows.map((r) => r.id)); trs.forEach((tr, i) => tr.classList.toggle('picked', rows[i] && sel.has(rows[i].id))); updateBar(); } }, 'All'),
        btn('trash', 'Delete', { class: 'bad del', onclick: () => del([...sel]) }),
        h('button', { onclick: () => { endSelect(); trs.forEach((tr) => tr.classList.remove('picked')); } }, 'Done'));
      document.body.append(selBar); updateBar();
    };
    trs.forEach((tr, i) => {
      const r = rows[i]; if (!r) return;
      tr.classList.add('sel-row');
      let timer = 0, sx = 0, sy = 0, fired = false;
      const fire = () => {
        fired = true; navigator.vibrate?.(15);
        if (sel) { sel.has(r.id) ? sel.delete(r.id) : sel.add(r.id); tr.classList.toggle('picked', sel.has(r.id)); updateBar(); return; }
        if (!can('delete_data')) return;
        openSheet(r.title || r.query || r.url || 'Entry', [['check', 'Select multiple', '', () => startSelect(r.id)], ['trash', 'Delete this one', 'bad', () => del([r.id])]]);
      };
      tr.addEventListener('pointerdown', (e) => { fired = false; sx = e.clientX; sy = e.clientY; clearTimeout(timer); timer = setTimeout(fire, 450); });
      tr.addEventListener('pointermove', (e) => { if (Math.abs(e.clientX - sx) > 8 || Math.abs(e.clientY - sy) > 8) clearTimeout(timer); });
      ['pointerup', 'pointerleave', 'pointercancel'].forEach((ev) => tr.addEventListener(ev, () => clearTimeout(timer)));
      tr.addEventListener('contextmenu', (e) => { e.preventDefault(); clearTimeout(timer); fire(); });
      tr.addEventListener('click', (e) => {
        if (fired) { fired = false; e.preventDefault(); e.stopPropagation(); return; }
        if (sel) { e.preventDefault(); e.stopPropagation(); sel.has(r.id) ? sel.delete(r.id) : sel.add(r.id); tr.classList.toggle('picked', sel.has(r.id)); updateBar(); }
      }, true);
    });
  }

  // ---------- views ----------
  async function viewOverview(box) {
    const load = async () => {
      try {
        const d = await api(`/overview?${range()}`);
        box.replaceChildren(
          h('div', { class: 'grid' },
            kpi(fmt(d.users.total), 'Registered users', 'users'), kpi(fmt(d.totals.activeUsers), `Active users, ${rangeLabel()}`, 'activity', 't-ok'),
            kpi(fmt(d.totals.visits), 'Page visits', 'globe', 't-cyan'), kpi(fmt(d.totals.domains), 'Websites visited', 'layers', 't-violet'),
            kpi(dur(d.totals.totalMs), 'Total browsing time', 'clock', 't-warn'), kpi(dur(d.sessions.avgMs), 'Avg session duration', 'clock', 't-ok'),
            kpi(fmt(d.sessions.n), 'Sessions', 'list', 't-cyan'), kpi(fmt(d.users.newUsers), 'New users', 'userplus', 't-violet')),
          h('div', { class: 'card list' }, h('h2', {}, 'Sessions'), h('div', { class: 'hint' }, `${fmt(d.sessions.n)} sessions, ${dur(d.sessions.avgMs)} on average`),
            navRow('#/sessions', 'clock', 'Session details', 'How often and how long the browser is used')),
          h('div', { class: 'cols' },
            card('Visits per day', 'Pages opened across all users', areaChart(d.daily, 'visits', 'Visits per day')),
            card('Active users per day', 'Distinct people browsing', areaChart(d.daily, 'users', 'Active users per day', 'cy'))),
          h('div', { class: 'cols' },
            card('Top websites', 'Most visited in this period', siteBars(d.topSites)),
            h('div', {}, card('Activity by hour', 'When people browse (your local time)', colChart(d.hourly, 'visits', 'Visits by hour', (r) => `${r.hour}h`)),
              card('Recently active', null, d.recentUsers.length ? h('div', { class: 'bars' }, d.recentUsers.map((u) => h('div', { class: 'who' },
                avatar(u.name), h('div', {}, h('b', {}, u.name), h('span', { class: 'sub' }, `${u.code || ''}`)),
                h('span', { class: 'pill ' + (online(u) ? 'ok' : '') }, online(u) ? 'online' : ago(u.last_seen))))) : h('div', { class: 'empty' }, 'No users yet.')))));
      } catch (e) { box.replaceChildren(errBox(e)); }
    };
    return [dateBar(load), box, load];
  }

  async function viewWebsites(box) {
    let q = '', sort = 'visits';
    const load = async () => {
      try {
        const d = await api(`/websites?${range()}&q=${encodeURIComponent(q)}&sort=${sort}&limit=200`);
        box.replaceChildren(h('div', { class: 'card' }, table([
          { label: 'Website', main: 1, cell: (r) => h('div', { class: 'who' }, h('div', { class: `av sq ${tone(r.domain)}` }, r.domain[0].toUpperCase()), siteLink(r.domain)) },
          { label: 'Visits', num: 1, cell: (r) => fmt(r.visits) }, { label: 'Users', num: 1, cell: (r) => fmt(r.users) },
          { label: 'Total time', num: 1, cell: (r) => dur(r.totalMs) }, { label: 'Avg / visit', num: 1, cell: (r) => dur(r.avgMs) },
          { label: 'Last visit', cell: (r) => ago(r.lastVisit) }], d.rows, (r) => { location.hash = '#/websites/' + encodeURIComponent(r.domain); })));
      } catch (e) { box.replaceChildren(errBox(e)); }
    };
    const sorter = h('div', { class: 'seg' }, [['visits', 'Most visits'], ['time', 'Most time']].map(([v, l]) =>
      h('button', { class: v === sort ? 'on' : '', onclick: (e) => { sort = v; sorter.querySelectorAll('button').forEach((b) => b.classList.remove('on')); e.target.classList.add('on'); load(); } }, l)));
    return [h('div', {}, dateBar(load), h('div', { class: 'row mb' }, searchBox('Filter websites…', (v) => { q = v; load(); }), sorter)), box, load];
  }

  async function viewSessions(box) {
    const load = async () => {
      try {
        const d = await api(`/sessions?${range()}`);
        box.replaceChildren(card('Sessions per day', 'Each time someone opens the browser', areaChart(d.daily, 'sessions', 'Sessions per day')),
          h('div', { class: 'card' }, table([{ label: 'Day', cell: (r) => r.day }, { label: 'Sessions', num: 1, cell: (r) => fmt(r.sessions) },
            { label: 'Avg duration', num: 1, cell: (r) => dur(r.avgMs) }, { label: 'Total time', num: 1, cell: (r) => dur(r.totalMs) }], d.daily.slice().reverse())));
      } catch (e) { box.replaceChildren(errBox(e)); }
    };
    return [dateBar(load), box, load];
  }

  async function viewUsers(box) {
    let q = '', page = 1;
    const load = async () => {
      try {
        const d = await api(`/users?${range()}&q=${encodeURIComponent(q)}&page=${page}&limit=25`);
        box.replaceChildren(h('div', { class: 'card' }, table([
          { label: 'User', main: 1, cell: (r) => h('div', { class: 'who' }, avatar(r.name), h('div', {}, h('b', {}, r.name), h('span', { class: 'sub' }, r.email || r.google_name || r.code || r.id))) },
          { label: 'User ID', cell: (r) => r.code || r.id },
          { label: 'Last seen', cell: (r) => h('span', {}, h('i', { class: 'dot ' + (online(r) ? 'on' : '') }), online(r) ? 'Online' : ago(r.last_seen)) },
          { label: 'Visits', num: 1, cell: (r) => fmt(r.visits) }, { label: 'Browsing', num: 1, cell: (r) => dur(r.totalMs) }, { label: 'Sessions', num: 1, cell: (r) => dur(r.sessionMs) },
          { label: 'Status', cell: (r) => h('span', { class: 'pill ' + (r.status === 'active' ? 'ok' : 'warn') }, r.status === 'active' ? 'Active' : 'Suspended') }],
          d.rows, (r) => { location.hash = '#/users/' + r.id; }), pager(page, d.total, 25, (p) => { page = p; load(); })));
      } catch (e) { box.replaceChildren(errBox(e)); }
    };
    return [h('div', {}, dateBar(load), h('div', { class: 'row mb' }, searchBox('Search name, ID or email…', (v) => { q = v; page = 1; load(); }))), box, load];
  }

  async function viewUser(box, id) {
    let tab = 'history', q = '', page = 1;
    const head = h('div', {}), body = h('div', {});
    const loadHead = async () => {
      try {
        const d = await api(`/users/${encodeURIComponent(id)}?${range()}`);
        const u = d.user;
        const actions = h('div', { class: 'actions' });
        if (can('manage_users')) {
          actions.append(
            btn('pause', u.status === 'active' ? 'Suspend uploads' : 'Reactivate', { onclick: async () => { await api(`/users/${id}`, { method: 'PATCH', body: { status: u.status === 'active' ? 'suspended' : 'active' } }); loadHead(); } }),
            btn('trash', 'Erase user', { class: 'bad', onclick: async () => { if (await ask(`Permanently erase ${u.name} and all their data?`)) { await api(`/users/${id}`, { method: 'DELETE' }); location.hash = '#/users'; } } }));
        }
        if (can('delete_data')) {
          const wipe = (what, path, msg) => btn('trash', `Clear ${what}`, { class: 'bad', onclick: async () => {
            if (!await ask(msg)) return;
            try { const r = await api(path, { method: 'DELETE' }); notify(`Deleted ${r.deleted} ${what}.`); loadHead(); loadBody(); } catch (er) { notify(er.message); }
          } });
          actions.append(wipe('history', `/users/${encodeURIComponent(id)}/history`, `Delete ALL browsing history and sessions of ${u.name}?`),
            wipe('searches', `/users/${encodeURIComponent(id)}/searches`, `Delete ALL search queries of ${u.name}?`));
        }
        if (can('export')) actions.append(btn('download', 'Export CSV', { onclick: async () => { try { await csvDownload(id, range()); } catch (er) { notify(er.message); } } }));
        head.replaceChildren(
          h('div', { class: 'card' }, h('div', { class: 'hero' }, avatar(u.name, 'lg'),
            h('div', { class: 'info' }, h('h2', {}, u.name), h('div', { class: 'sub' }, `${u.code || u.id}${u.email ? ' · ' + u.email : ''}`),
              h('div', { class: 'chips' },
                h('span', { class: 'pill ' + (online(u) ? 'ok' : '') }, online(u) ? 'Online now' : `Seen ${ago(u.last_seen)}`),
                h('span', { class: 'pill' }, `Registered ${dt(u.created_at)}`),
                h('span', { class: 'pill' }, `${u.manufacturer ? u.manufacturer + ' ' : ''}${u.device_model || 'Unknown device'} · Android ${u.os_version || '?'}${u.sdk ? ' (API ' + u.sdk + ')' : ''} · app ${u.app_version || '?'}`),
                u.locale ? h('span', { class: 'pill' }, `${u.locale}${u.timezone ? ' · ' + u.timezone : ''}`) : null,
                u.name_source ? h('span', { class: 'pill' }, 'Name from: ' + ({ typed: 'typed by user', 'device-name': 'phone name', google: 'Google account', guest: 'auto (guest)' }[u.name_source] || u.name_source)) : null,
                h('span', { class: 'pill ' + (u.activity_consent ? 'ok' : 'bad') }, u.activity_consent ? 'Activity sharing on' : 'Activity sharing off'),
                h('span', { class: 'pill ' + (u.search_consent ? 'ok' : '') }, u.search_consent ? 'Search sharing on' : 'Search sharing off'),
                u.status !== 'active' ? h('span', { class: 'pill warn' }, 'Suspended') : null))), actions),
          h('div', { class: 'grid' }, kpi(fmt(d.stats.visits), 'Visits', 'globe', 't-cyan'), kpi(fmt(d.stats.domains), 'Websites', 'layers', 't-violet'), kpi(dur(d.stats.totalMs), 'Browsing time', 'clock', 't-warn'),
            kpi(fmt(d.sessions.n), 'Sessions', 'list'), kpi(dur(d.sessions.avgMs), 'Avg session', 'clock', 't-ok')),
          h('div', { class: 'cols' }, card('Visits per day', null, areaChart(d.daily, 'visits', 'Visits per day')),
            card('Top sites', 'By visits', siteBars(d.topSites.map((t) => ({ ...t, users: 1 }))))));
      } catch (e) { head.replaceChildren(errBox(e)); }
    };
    const loadBody = async () => {
      try {
        const base = `/users/${encodeURIComponent(id)}`;
        if (tab === 'history') {
          const d = await api(`${base}/history?${range()}&q=${encodeURIComponent(q)}&page=${page}&limit=50`);
          const card = h('div', { class: 'card' }, table([
            { label: 'When', cell: (r) => dt(r.visited_at) },
            { label: 'Page', main: 1, cls: 'url', cell: (r) => h('div', { class: 'who' }, h('div', { class: `av sq ${tone(r.domain)}` }, r.domain[0].toUpperCase()), h('div', {}, extLink(r.url, r.title || r.domain), h('div', { class: 'sub' }, r.url))) },
            { label: 'Time spent', num: 1, cell: (r) => dur(r.duration_ms) }], d.rows), pager(page, d.total, 50, (p) => { page = p; loadBody(); }));
          body.replaceChildren(can('delete_data') ? h('p', { class: 'hintline' }, 'Tip: press and hold an entry to select or delete.') : null, card);
          selectable(card, d.rows, 'history', base, () => { loadBody(); loadHead(); });
        } else if (tab === 'sessions') {
          const d = await api(`${base}/sessions?${range()}&limit=100`);
          body.replaceChildren(h('div', { class: 'card' }, table([{ label: 'Started', cell: (r) => dt(r.started_at) }, { label: 'Ended', cell: (r) => dt(r.ended_at) }, { label: 'Duration', num: 1, cell: (r) => dur(r.duration_ms) }], d.rows)));
        } else {
          const d = await api(`${base}/searches?${range()}&limit=100`);
          const card = h('div', { class: 'card' }, d.consent ? null : h('p', { class: 'hint' }, 'This user has not enabled search-query sharing.'),
            table([{ label: 'When', cell: (r) => dt(r.searched_at) },
              { label: 'Query', main: 1, cell: (r) => extLink('https://www.google.com/search?q=' + encodeURIComponent(r.query), r.query) },
              { label: 'Engine', cell: (r) => r.engine || '' }], d.rows));
          body.replaceChildren(can('delete_data') ? h('p', { class: 'hintline' }, 'Tip: press and hold an entry to select or delete.') : null, card);
          selectable(card, d.rows, 'searches', base, () => { loadBody(); });
        }
      } catch (e) { body.replaceChildren(errBox(e)); }
    };
    const reload = () => { page = 1; loadHead(); loadBody(); };
    const tabs = h('div', { class: 'seg' }, ['history', 'sessions', 'searches'].map((t) =>
      h('button', { class: t === tab ? 'on' : '', onclick: (e) => { tab = t; tabs.querySelectorAll('button').forEach((b) => b.classList.remove('on')); e.target.classList.add('on'); page = 1; loadBody(); } }, t[0].toUpperCase() + t.slice(1))));
    box.replaceChildren(head, h('div', { class: 'row mb' }, tabs, searchBox('Filter history by URL or title…', (v) => { q = v; page = 1; if (tab === 'history') loadBody(); })), body);
    return [dateBar(reload), box, reload, true];
  }

  async function viewAdmins(box) {
    const load = async () => {
      try {
        const d = await api('/admins');
        box.replaceChildren(
          h('div', { class: 'card' }, table([{ label: 'Admin', main: 1, cell: (r) => h('div', { class: 'who' }, avatar(r.name || r.email), h('div', {}, h('b', {}, r.name), h('span', { class: 'sub' }, r.email))) },
            { label: 'Role', cell: (r) => h('span', { class: 'pill ' + (r.role === 'superadmin' ? 'ok' : '') }, r.role) }, { label: 'Updated', cell: (r) => dt(r.created_at) },
            { label: 'Protected', cell: () => h('span', { class: 'pill ok' }, ico('lock'), 'Cannot be deleted here') }], d.rows)),
          card('Add or change admins', 'Roles are Firebase sign-in claims, so they are managed from your PC with the Admin SDK, never from a browser:',
            h('p', {}, h('code', {}, 'node scripts/set-role.js person@example.com analyst "Name"')),
            h('p', { class: 'mut' }, 'Roles: superadmin (everything, can delete) and analyst (read-only). Use "none" to remove access. The person must sign in again.')));
      } catch (e) { box.replaceChildren(errBox(e)); }
    };
    return [h('div', {}), box, load];
  }

  async function viewServer(box) {
    const load = async () => {
      try {
        const d = await api('/server-info');
        const row = (k, v) => h('tr', {}, h('th', {}, k), h('td', {}, v));
        const danger = (scope, label) => btn('trash', `Clear ${label}`, { class: 'bad', onclick: async (e) => {
          const w = await askType(`This permanently deletes ${label} for ALL users. Type DELETE to confirm:`);
          if (w !== 'DELETE') return;
          e.currentTarget.disabled = true;
          try { const r = await api('/data', { method: 'DELETE', body: { scope, confirm: 'DELETE' } }); notify('Deleted: ' + JSON.stringify(r.deleted)); } catch (er) { notify(er.message); }
          load();
        } });
        box.replaceChildren(
          h('div', { class: 'grid' }, kpi(fmt(d.counts.users), 'Users', 'users'), kpi(fmt(d.counts.visits), 'Visits', 'globe', 't-cyan'), kpi(fmt(d.counts.searches), 'Searches', 'search', 't-violet'),
            kpi(fmt(d.counts.sessions), 'Sessions', 'list', 't-warn'), kpi(fmt(d.counts.admins), 'Admins', 'shield', 't-ok')),
          card('Where data is stored', null, h('table', {}, h('tbody', {},
            row('Database', 'Google Firebase: Cloud Firestore'), row('Project', d.projectId), row('Admin site', d.host),
            row('Public pages', h('span', {}, extLink(d.host + '/privacy', 'Privacy policy'), ' · ', extLink(d.host + '/terms', 'Terms of service'))),
            row('Access control', 'Firestore security rules + Firebase Authentication roles')))),
          can('delete_data') ? card('Danger zone: all users', 'User accounts stay registered; only collected data is removed. Actions are audit-logged.',
            h('div', { class: 'row' }, danger('history', 'browsing history + sessions'), danger('searches', 'search queries'), danger('all', 'ALL collected data'))) : null);
      } catch (e) { box.replaceChildren(errBox(e)); }
    };
    return [h('div', {}), box, load];
  }

  async function viewAudit(box) {
    const load = async () => {
      try {
        const d = await api('/audit?limit=200');
        box.replaceChildren(h('div', { class: 'card' }, table([{ label: 'When', cell: (r) => dt(r.at) }, { label: 'Admin', cell: (r) => r.admin_email || '—' },
          { label: 'Action', cell: (r) => h('span', { class: 'pill ' + (/delete|erase|clear/.test(r.action) ? 'bad' : '') }, r.action) }, { label: 'Target', cell: (r) => r.target || '' }, { label: 'Detail', cell: (r) => r.detail || '' }], d.rows)));
      } catch (e) { box.replaceChildren(errBox(e)); }
    };
    return [h('div', {}), box, load];
  }

  async function viewAccount(box) {
    const form = h('form', { class: 'card', onsubmit: async (e) => {
      e.preventDefault(); const f = e.target;
      try { await api('/auth/change-password', { method: 'POST', body: { current: f.current.value, next: f.next.value } }); f.reset(); notify('Password changed.'); }
      catch (er) { notify('Failed: ' + er.message + ' (new password needs 12+ characters)'); }
    } }, h('h2', {}, 'Change password'), h('p', { class: 'hint' }, `Signed in as ${me.email} (${me.role}).`),
      h('div', { class: 'row' }, h('input', { name: 'current', type: 'password', placeholder: 'Current password', autocomplete: 'current-password', required: true }),
        h('input', { name: 'next', type: 'password', placeholder: 'New password (12+ chars)', autocomplete: 'new-password', required: true }), h('button', { class: 'pri' }, 'Update password')));
    box.replaceChildren(form);
    return [h('div', {}), box, null];
  }


  async function viewSite(box, domain) {
    let tab = 'pages';
    const body = h('div', {}), head = h('div', {});
    let d;
    const draw = () => {
      if (!d) return;
      if (tab === 'pages') body.replaceChildren(h('div', { class: 'card' }, h('p', { class: 'hint' }, 'Every different page opened on this website, most visited first.'), table([
        { label: 'Page', main: 1, cls: 'url', cell: (r) => h('div', {}, extLink(r.url, r.title || r.url), h('div', { class: 'sub' }, r.url)) },
        { label: 'Visits', num: 1, cell: (r) => fmt(r.visits) }, { label: 'Users', num: 1, cell: (r) => fmt(r.users) },
        { label: 'Time', num: 1, cell: (r) => dur(r.totalMs) }, { label: 'Last opened', cell: (r) => ago(r.lastVisit) }], d.pages)));
      else if (tab === 'timeline') body.replaceChildren(h('div', { class: 'card' }, h('p', { class: 'hint' }, 'Everything opened on this website, newest first (up to 1,000).'), table([
        { label: 'When', cell: (r) => dt(r.visited_at) },
        { label: 'Page', main: 1, cls: 'url', cell: (r) => h('div', {}, extLink(r.url, r.title || r.url), h('div', { class: 'sub' }, r.url)) },
        { label: 'User', cell: (r) => h('a', { href: '#/users/' + r.uid }, r.user) },
        { label: 'Time spent', num: 1, cell: (r) => dur(r.duration_ms) }], d.timeline)));
      else body.replaceChildren(h('div', { class: 'card' }, table([
        { label: 'User', main: 1, cell: (r) => h('div', { class: 'who' }, avatar(r.name), h('a', { href: '#/users/' + r.uid }, r.name)) },
        { label: 'Visits', num: 1, cell: (r) => fmt(r.visits) }, { label: 'Time', num: 1, cell: (r) => dur(r.totalMs) }, { label: 'Last visit', cell: (r) => ago(r.lastVisit) }], d.byUser)));
    };
    const load = async () => {
      try {
        d = await api('/websites/' + encodeURIComponent(domain) + '?' + range());
        head.replaceChildren(h('div', { class: 'card' }, h('div', { class: 'hero' }, h('div', { class: 'av lg sq ' + tone(domain) }, domain[0].toUpperCase()),
          h('div', { class: 'info' }, h('h2', {}, domain), h('div', { class: 'chips' }, extLink('https://' + domain, 'Open ' + domain + ' \u2197'))))),
        h('div', { class: 'grid' }, kpi(fmt(d.totals.visits), 'Visits', 'globe', 't-cyan'), kpi(fmt(d.totals.pages), 'Different pages', 'list', 't-violet'),
          kpi(fmt(d.totals.users), 'Users', 'users'), kpi(dur(d.totals.totalMs), 'Time spent', 'clock', 't-warn')));
        draw();
      } catch (e) { body.replaceChildren(errBox(e)); }
    };
    const tabs = h('div', { class: 'seg' }, [['pages', 'Pages'], ['timeline', 'Timeline'], ['users', 'Users']].map(([v, l]) =>
      h('button', { class: v === tab ? 'on' : '', onclick: (e) => { tab = v; tabs.querySelectorAll('button').forEach((b) => b.classList.remove('on')); e.target.classList.add('on'); draw(); } }, l)));
    box.replaceChildren(head, h('div', { class: 'row mb' }, tabs), body);
    return [dateBar(load), box, load];
  }

  async function viewSettings(box) {
    const seg = (key, options, onSet) => h('div', { class: 'seg' }, options.map(([v, l]) => h('button', { class: prefs.get(key, options[0][0]) === v ? 'on' : '', onclick: (e) => {
      prefs.set(key, v); e.currentTarget.parentNode.querySelectorAll('button').forEach((b) => b.classList.remove('on')); e.currentTarget.classList.add('on'); if (onSet) onSet(v);
    } }, l)));
    const setting = (title, hint, control) => h('div', { class: 'setting' }, h('div', {}, h('b', {}, title), h('div', { class: 'sub' }, hint)), control);
    const link = navRow;
    box.replaceChildren(
      card('Appearance and behaviour', null,
        setting('Theme', 'Match your device or force light or dark', seg('theme', [['auto', 'Auto'], ['light', 'Light'], ['dark', 'Dark']], applyTheme)),
        setting('Default date range', 'Used when you open the console', seg('range', [['7', '7 days'], ['30', '30 days'], ['90', '90 days']], (v) => { filter.preset = v; })),
        setting('Auto refresh', 'Reload the current page automatically', seg('auto', [['0', 'Off'], ['30', '30 s'], ['60', '60 s']], () => scheduleRefresh()))),
      can('aggregate') ? h('div', { class: 'card list' }, h('h2', {}, 'Analytics'), h('div', { class: 'hint' }, 'More reports.'), link('#/sessions', 'clock', 'Sessions', 'How often and how long the browser is used')) : null,
      (can('manage_config') || can('manage_admins') || can('audit') || can('server_info'))
        ? h('div', { class: 'card list' }, h('h2', {}, 'Manage'), h('div', { class: 'hint' }, 'Control the phone app and see who has access.'),
            can('manage_config') ? link('#/config', 'gear', 'Firebase settings', 'Remote app settings and data retention') : null,
            can('manage_admins') ? link('#/admins', 'shield', 'Admins', 'Who can open this console') : null,
            can('audit') ? link('#/audit', 'list', 'Audit log', 'Every sensitive action by an admin') : null,
            can('server_info') ? link('#/server', 'db', 'Data and storage', 'Records stored, clear data') : null)
        : null,
      h('div', { class: 'card list' }, h('h2', {}, 'Account'), h('div', { class: 'hint' }),
        link('#/account', 'user', 'My account', 'Change your password')),
      card('Signed in', null, h('p', {}, h('b', {}, me.name || me.email), ' ', h('span', { class: 'pill' }, me.role)), h('div', { class: 'row' },
        btn('out', 'Sign out', { class: 'bad', onclick: async () => { await api('/auth/logout', { method: 'POST' }); me = null; render(); } }))));
    return [h('div', {}), box, null];
  }


  async function viewConfig(box) {
    const load = async () => {
      try {
        const c = await api('/config');
        const st = { ...c };
        const onoff = (val, set) => { const w = h('div', { class: 'seg' }); const mk = (v, l) => h('button', { type: 'button', class: val === v ? 'on' : '', onclick: (e) => { val = v; w.querySelectorAll('button').forEach((b) => b.classList.remove('on')); e.currentTarget.classList.add('on'); set(v); } }, l); w.append(mk(true, 'On'), mk(false, 'Off')); return w; };
        const num = (k, min, max) => h('input', { type: 'number', min, max, value: st[k], oninput: (e) => { st[k] = e.target.value; } });
        const row = (title, hint, control) => h('div', { class: 'setting' }, h('div', {}, h('b', {}, title), h('div', { class: 'sub' }, hint)), control);
        const ann = h('input', { type: 'text', maxlength: 200, value: st.announcement, placeholder: 'Shown inside the phone app (optional)', oninput: (e) => { st.announcement = e.target.value; } });
        const save = btn('check', 'Save settings', { class: 'pri', onclick: async (e) => { e.currentTarget.disabled = true; try { await api('/config', { method: 'PUT', body: st }); notify('Saved. Phones pick this up at their next sync.'); } catch (er) { notify(er.message); } load(); } });
        box.replaceChildren(
          card('Remote app settings', 'Saved in Firebase. Every phone reads these at its next sync, and the security rules enforce the first two switches even if an app ignored them.',
            row('Collect browsing activity', 'Off = every phone stops uploading immediately (kill switch)', onoff(st.trackingEnabled, (v) => { st.trackingEnabled = v; })),
            row('Allow search-query collection', 'Off = search queries are blocked for everyone', onoff(st.searchAllowed, (v) => { st.searchAllowed = v; })),
            row('Upload interval (seconds)', 'How quickly phones push new activity (5 to 600)', num('uploadIntervalSeconds', 5, 600)),
            row('Retention (days)', 'Used by "Delete old data" below (1 to 3650)', num('retentionDays', 1, 3650)),
            row('Announcement', 'A short message shown inside the phone app', ann),
            h('div', { class: 'row' }, save), c.updatedAt ? h('p', { class: 'hint' }, `Last changed ${dt(c.updatedAt)} by ${c.updatedBy}`) : null),
          card('Delete old data', 'Permanently deletes visits, sessions and searches older than the retention period for every user.',
            h('div', { class: 'row' }, btn('trash', 'Delete data older than the retention period', { class: 'bad', onclick: async (e) => {
              const d = parseInt(st.retentionDays, 10) || 180;
              if (await askType(`Delete everything older than ${d} days for ALL users? Type DELETE to confirm:`) !== 'DELETE') return;
              e.currentTarget.disabled = true;
              try { const r = await api('/purge', { method: 'POST', body: { days: d } }); notify('Deleted: ' + JSON.stringify(r.deleted)); } catch (er) { notify(er.message); }
              load();
            } }))),
          card('Accounts and deletion', null,
            h('p', {}, 'Admin (superadmin) accounts can never be erased from this console; the security rules refuse it.'),
            h('p', {}, 'Erasing a user, or clearing history or searches, deletes the records from Firebase immediately. When an erased user next opens the app, it also removes that person\u2019s Firebase sign-in, so nothing is left behind.')));
      } catch (e) { box.replaceChildren(errBox(e)); }
    };
    return [h('div', {}), box, load];
  }

  // ---------- shell / routing ----------
  const routes = [
    ['#/', 'Overview', 'Browsing activity across all users', 'grid', viewOverview, 'aggregate', 'Analytics'],
    ['#/websites', 'Websites', 'Tap a website to see every page opened on it', 'globe', viewWebsites, 'aggregate', 'Analytics'],
    ['#/sessions', 'Sessions', 'How often and how long the browser is used', 'clock', viewSessions, 'aggregate', 'Analytics'],
    ['#/users', 'Users', 'Everyone who installed the app', 'users', viewUsers, 'user_list', 'People'],
    ['#/config', 'Firebase settings', 'Control the phone app remotely and manage retention', 'db', viewConfig, 'manage_config', 'Manage'],
    ['#/admins', 'Admins', 'Who can open this console', 'shield', viewAdmins, 'manage_admins', 'Manage'],
    ['#/audit', 'Audit log', 'Every sensitive action taken by an admin', 'list', viewAudit, 'audit', 'Manage'],
    ['#/server', 'Data and storage', 'What is stored and where', 'db', viewServer, 'server_info', 'Manage'],
    ['#/account', 'My account', 'Your sign-in details', 'user', viewAccount, 'aggregate', 'Account'],
    ['#/settings', 'Settings', 'Appearance, refresh and more', 'gear', viewSettings, 'aggregate', 'Account'],
  ];
  // bottom bar on phones: main areas + Settings (which lists the rest)
  const TABS = [['#/', 'Overview', 'grid'], ['#/websites', 'Websites', 'globe'], ['#/users', 'Users', 'users'], ['#/settings', 'Settings', 'gear']];
  const SETTINGS_GROUP = ['#/settings', '#/admins', '#/server', '#/audit', '#/account', '#/config'];
  // which bottom tab is highlighted for a given page (Sessions is reached from Overview)
  const tabOf = (r) => (r === '#/sessions' ? '#/' : SETTINGS_GROUP.includes(r) ? '#/settings' : r);

  function loginView(msg) {
    const f = h('form', { onsubmit: async (e) => {
      e.preventDefault();
      try { await api('/auth/login', { method: 'POST', body: { email: f.email.value, password: f.password.value } }); await boot(); }
      catch (er) { render(({ invalid_credentials: 'Invalid email or password.', forbidden: 'This account is not an administrator.', too_many_attempts: 'Too many attempts. Try again later.' })[er.message] || er.message); }
    } }, h('h1', {}, 'Welcome back'), h('p', { class: 'subtitle' }, 'Sign in to the WBrowser admin console.'),
      h('div', {}, h('label', {}, 'Email'), h('input', { name: 'email', type: 'email', placeholder: 'you@company.com', autocomplete: 'username', required: true })),
      h('div', {}, h('label', {}, 'Password'), h('input', { name: 'password', type: 'password', placeholder: '••••••••••••', autocomplete: 'current-password', required: true })),
      msg ? h('div', { class: 'err' }, msg) : null, h('button', { class: 'pri' }, 'Sign in'),
      h('button', { type: 'button', class: 'link', onclick: async (e) => {
        const email = f.email.value.trim();
        if (!email) { f.email.focus(); notify('Type your admin email first, then tap "Forgot password?".'); return; }
        e.currentTarget.disabled = true;
        try { await api('/auth/reset', { method: 'POST', body: { email } }); notify('If that email belongs to an admin, a password reset link has been sent to it. Check your inbox (and spam).'); }
        catch (er) { notify(er.message === 'too_many_attempts' ? 'Too many requests. Try again later.' : er.message); }
        e.currentTarget.disabled = false;
      } }, 'Forgot password?'),
      h('p', { class: 'notice' }, 'Access is limited to authorised administrators. Activity is logged.'));
    return h('div', { class: 'auth' },
      h('div', { class: 'art' }, h('div', { class: 'brand' }, h('div', { class: 'logo' }, ico('shield')), h('span', {}, 'WBrowser Admin')),
        h('div', {}, h('h2', {}, 'Understand how your browser is used.'), h('p', {}, 'Live activity, website analytics and per-user history in one secure place.'),
          h('ul', {}, ['Real-time user activity and session length', 'Role-based access with a full audit trail', 'Delete any user’s data at any time'].map((t) => h('li', {}, ico('check'), t)))),
        h('p', { class: 'sub' }, '')),
      h('div', { class: 'pane' }, f));
  }

  function scheduleRefresh() {
    clearInterval(refreshTimer);
    const sec = parseInt(prefs.get('auto', '0'), 10);
    if (sec > 0) refreshTimer = setInterval(() => { if (me && currentLoad && !document.hidden) { refreshData(); currentLoad(); } }, sec * 1000);
  }

  // ---------- motion helpers (class "fx" on <html> is on only while a page is first drawn) ----------
  const html = document.documentElement;
  let fxTimer = null, shellEl = null, tabbarEl = null, tabbarFor = null, lastHash = null;
  const fxOn = () => { html.classList.add('fx'); clearTimeout(fxTimer); fxTimer = setTimeout(() => html.classList.remove('fx'), 5000); };
  const fxOffSoon = () => { clearTimeout(fxTimer); fxTimer = setTimeout(() => html.classList.remove('fx'), 1500); };

  function buildTabbar() {
    const tabs = TABS.filter((t) => can(routes.find((r) => r[0] === t[0])[5]));
    const el = h('div', { class: 'tabbar' }, h('i', { class: 'ind' }),
      tabs.map(([href, label, icon]) => h('a', { href }, ico(icon), h('span', {}, label))));
    el.style.setProperty('--n', tabs.length); el.style.setProperty('--i', -1);
    el._tabs = tabs.map((t) => t[0]);
    return el;
  }
  function setTab(el, page) {
    const idx = el._tabs.indexOf(tabOf(page));
    el.style.setProperty('--i', idx);
    el.classList.toggle('none', idx < 0);
    el.querySelectorAll('a').forEach((a, i) => a.classList.toggle('on', i === idx));
  }

  async function render(msg) {
    if (!me) { stopPresence(); clearInterval(refreshTimer); shellEl = tabbarEl = tabbarFor = lastHash = null; root.replaceChildren(loginView(typeof msg === 'string' ? msg : '')); return; }
    startPresence();
    const hash = location.hash || '#/';
    const hashChanged = hash !== lastHash; lastHash = hash;
    if (hashChanged) fxOn();
    const uid = /^#\/users\/(.+)$/.exec(hash);
    const site = /^#\/websites\/(.+)$/.exec(hash);
    const route = uid ? ['#/users', 'User activity', 'Profile, history, sessions and searches', 'users', null, 'user_detail']
      : site ? ['#/websites', 'Website activity', 'Every page opened on this website', 'globe', null, 'aggregate']
      : (routes.find((r) => r[0] === hash) || routes[0]);
    const groups = [...new Set(routes.filter((r) => can(r[5])).map((r) => r[6]))];
    const nav = h('nav', {}, h('div', { class: 'brand' }, h('div', { class: 'logo' }, ico('shield')), h('span', {}, 'WBrowser Admin')),
      groups.map((g) => [h('div', { class: 'navgroup' }, g), routes.filter((r) => r[6] === g && can(r[5])).map((r) =>
        h('a', { class: 'item' + (r[0] === route[0] ? ' on' : ''), href: r[0], title: r[1] }, ico(r[3]), h('span', {}, r[1])))]),
      h('div', { class: 'sp' }),
      h('div', { class: 'me' }, avatar(me.name || me.email), h('div', {}, h('b', {}, me.name || me.email), h('span', {}, me.role)),
        h('button', { class: 'out', title: 'Sign out', onclick: async () => { await api('/auth/logout', { method: 'POST' }); me = null; render(); } }, ico('out'))));
    if (tabbarFor !== me) { tabbarEl = buildTabbar(); tabbarFor = me; shellEl = null; }
    setTab(tabbarEl, route[0]);
    const box = h('div', {});
    const refreshBtn = btn('refresh', 'Refresh', { onclick: () => { fxOn(); refreshData(); if (currentLoad) Promise.resolve(currentLoad()).finally(fxOffSoon); else render(); } });
    const top = h('div', { class: 'top' }, h('div', {}, h('h1', {}, route[1]), h('p', { class: 'subtitle' }, route[2])), refreshBtn);
    const main = h('main', {}, top);
    const shell = h('div', { class: 'shell' }, nav, main);
    // only swap the shell; the bottom bar stays in the page so its indicator can slide between tabs
    if (shellEl && shellEl.parentNode === root) shellEl.replaceWith(shell); else root.replaceChildren(shell, tabbarEl);
    shellEl = shell;
    currentLoad = null;
    endSelect();
    if (!can(route[5])) { main.append(h('p', { class: 'err' }, 'Not permitted.')); return; }
    const [bar, content, load] = uid ? await viewUser(box, decodeURIComponent(uid[1]))
      : site ? await viewSite(box, decodeURIComponent(site[1])) : await route[4](h('div', {}));
    const back = (href, text) => h('a', { class: 'backlink', href }, ico('back'), text);
    if (uid) { top.replaceChildren(h('div', {}, back('#/users', 'All users'), h('h1', {}, 'User activity'), h('p', { class: 'subtitle' }, route[2])), refreshBtn); main.append(bar, box); }
    else if (site) { top.replaceChildren(h('div', {}, back('#/websites', 'All websites'), h('h1', {}, decodeURIComponent(site[1])), h('p', { class: 'subtitle' }, route[2])), refreshBtn); main.append(bar, box); }
    else main.append(bar, content);
    currentLoad = load || null; scheduleRefresh();
    if (!content.firstElementChild && !box.firstElementChild) content.replaceChildren(loading());
    if (load) Promise.resolve(load()).finally(fxOffSoon); else fxOffSoon();
    if (hashChanged) window.scrollTo(0, 0);
  }

  async function boot() {
    try { me = await initFirebase(); } catch { me = null; }
    render(configured() ? '' : 'Firebase is not configured yet: paste your web config into firebase-config.js and redeploy.');
  }
  window.addEventListener('hashchange', () => { if (me) render(); });
  setupPullToRefresh();
  setupMotion();
  boot();

  // ---------- pull-to-refresh: drag down from the very top of any page ----------
  function setupPullToRefresh() {
    const el = h('div', { class: 'ptr' }, ico('refresh'));
    document.body.appendChild(el);
    const THRESHOLD = 60;
    const atTop = () => (document.scrollingElement || html).scrollTop <= 2;
    let startY = 0, startX = 0, active = false, armed = false, busy = false;
    const reset = () => { el.classList.remove('show', 'go'); el.style.transform = ''; };
    document.addEventListener('touchstart', (e) => {
      armed = me != null && !busy && atTop() && e.touches.length === 1;
      active = false;
      if (armed) { startY = e.touches[0].clientY; startX = e.touches[0].clientX; }
    }, { passive: true });
    document.addEventListener('touchmove', (e) => {
      if (!armed) return;
      const dy = e.touches[0].clientY - startY, dx = Math.abs(e.touches[0].clientX - startX);
      if (dy <= 0 || !atTop() || (!active && dx > dy)) { armed = false; active = false; reset(); return; }
      active = true;
      const d = Math.min(dy * 0.55, 96);
      el.classList.add('show');
      el.classList.toggle('go', d >= THRESHOLD);
      el.style.transform = 'translate(-50%,' + d + 'px) rotate(' + (d * 3.2) + 'deg) scale(' + (0.7 + Math.min(d / THRESHOLD, 1) * 0.3) + ')';
    }, { passive: true });
    const release = async () => {
      const fire = active && el.classList.contains('go');
      armed = false; active = false;
      if (!fire) { reset(); return; }
      busy = true; fxOn();
      el.classList.add('spin'); el.style.transform = 'translate(-50%,64px)';
      refreshData(); bustUsers();
      const t0 = Date.now();
      try { if (currentLoad) await currentLoad(); else await render(); } catch { /* shown by the view */ }
      await new Promise((r) => setTimeout(r, Math.max(0, 650 - (Date.now() - t0))));
      el.classList.remove('spin'); reset(); busy = false; fxOffSoon();
    };
    document.addEventListener('touchend', release);
    document.addEventListener('touchcancel', () => { armed = false; active = false; if (!busy) reset(); });
  }

  // ---------- tap ripple + reveal on scroll + compact header on scroll ----------
  function setupMotion() {
    const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
    const phone = matchMedia('(max-width:760px)');
    // ripple on any tap target
    document.addEventListener('pointerdown', (e) => {
      if (reduce) return;
      const t = e.target.closest && e.target.closest('button,.tabbar a,.listrow,nav a.item,tr.click');
      if (!t || t.disabled || (t.matches('tr.click') && !phone.matches)) return;
      const r = t.getBoundingClientRect(), size = Math.max(r.width, r.height) * 2;
      const dot = h('span', { class: 'rip' });
      dot.style.width = dot.style.height = size + 'px';
      dot.style.left = (e.clientX - r.left - size / 2) + 'px';
      dot.style.top = (e.clientY - r.top - size / 2) + 'px';
      t.append(dot);
      dot.addEventListener('animationend', () => dot.remove());
    }, { passive: true });
    // header gets compact + shadow once the page is scrolled
    let ticking = false;
    addEventListener('scroll', () => {
      if (ticking) return; ticking = true;
      requestAnimationFrame(() => { html.classList.toggle('scrolled', scrollY > 6); ticking = false; });
    }, { passive: true });
    // cards / rows / tiles fade and rise into place (staggered) when they enter the screen
    if (reduce || !('IntersectionObserver' in window)) return;
    const io = new IntersectionObserver((entries) => {
      let n = 0;
      for (const en of entries) {
        if (!en.isIntersecting) continue;
        const el = en.target; io.unobserve(el);
        el.style.transitionDelay = Math.min(n++, 8) * 45 + 'ms';
        el.classList.add('in');
        setTimeout(() => { el.style.transitionDelay = ''; el.classList.remove('rv', 'in'); }, 900 + n * 45);
      }
    }, { threshold: 0.06 });
    const SEL = '.card,.kpi,.listrow,tbody tr,.bar,.skeleton';
    let queued = false;
    const scan = () => {
      queued = false;
      for (const el of root.querySelectorAll(SEL)) {
        if (el._seen) continue; el._seen = true;
        const below = el.getBoundingClientRect().top > innerHeight;
        if (below || html.classList.contains('fx')) { el.classList.add('rv'); io.observe(el); }
      }
    };
    new MutationObserver(() => { if (!queued) { queued = true; requestAnimationFrame(scan); } }).observe(root, { childList: true, subtree: true });
  }
})();
