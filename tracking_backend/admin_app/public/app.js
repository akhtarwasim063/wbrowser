'use strict';
// WBrowser Admin App. Plain JS, no inline code (strict CSP). All dynamic text goes through textContent.
(() => {
  const API = '/admin/api';
  const root = document.getElementById('app');
  let me = null;
  const filter = { preset: '7', from: '', to: '' }; // shared date filter

  // ---------- helpers ----------
  const h = (tag, attrs, ...kids) => {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (k === 'class') e.className = v;
      else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
      else if (v !== false && v != null) e.setAttribute(k, v === true ? '' : v);
    }
    for (const kid of kids.flat()) if (kid != null && kid !== false) e.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
    return e;
  };
  const svgNS = 'http://www.w3.org/2000/svg';
  const s = (tag, attrs, ...kids) => {
    const e = document.createElementNS(svgNS, tag);
    for (const [k, v] of Object.entries(attrs || {})) e.setAttribute(k, v);
    for (const kid of kids) if (kid != null) e.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
    return e;
  };
  const dur = (ms) => {
    const t = Math.round((ms || 0) / 1000);
    if (t < 60) return `${t}s`;
    if (t < 3600) return `${Math.floor(t / 60)}m ${t % 60}s`;
    return `${Math.floor(t / 3600)}h ${Math.floor((t % 3600) / 60)}m`;
  };
  // Only http(s) targets become links (URLs are untrusted data). Opens in a new tab, no referrer / opener.
  const extLink = (href, text) => {
    if (!/^https?:\/\//i.test(href)) return text;
    return h('a', { href, target: '_blank', rel: 'noopener noreferrer', title: 'Open in new tab' }, text);
  };
  const siteLink = (domain) => extLink('https://' + domain, domain);
  const bytes = (n) => (n == null ? '—' : n < 1024 ? `${n} B` : n < 1048576 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1048576).toFixed(1)} MB`);
  const fmt = (n) => Number(n || 0).toLocaleString();
  const dt = (ms) => (ms ? new Date(ms).toLocaleString() : '—');

  async function api(path, opts = {}) {
    const r = await fetch(API + path, {
      method: opts.method || 'GET',
      credentials: 'same-origin',
      headers: { 'content-type': 'application/json', 'x-requested-with': 'wbrowser-admin' },
      body: opts.body ? JSON.stringify(opts.body) : undefined,
    });
    if (r.status === 401 && path !== '/auth/login') { me = null; render(); throw new Error('Session expired'); }
    const type = r.headers.get('content-type') || '';
    const data = type.includes('json') ? await r.json() : await r.text();
    if (!r.ok) throw new Error((data && data.error) || `HTTP ${r.status}`);
    return data;
  }

  function range() {
    const to = filter.preset === 'custom' && filter.to ? new Date(filter.to + 'T23:59:59.999').getTime() : Date.now();
    let from;
    if (filter.preset === 'custom' && filter.from) from = new Date(filter.from + 'T00:00:00').getTime();
    else if (filter.preset === 'today') { const d = new Date(); d.setHours(0, 0, 0, 0); from = d.getTime(); }
    else from = Date.now() - parseInt(filter.preset, 10) * 86400000;
    return `from=${from}&to=${to}&tz=${-new Date().getTimezoneOffset()}`;
  }
  const rangeLabel = () => (filter.preset === 'custom' ? `${filter.from || '…'} → ${filter.to || 'now'}` : filter.preset === 'today' ? 'Today' : `Last ${filter.preset} days`);

  function dateBar(onChange) {
    const sel = h('select', { onchange: (e) => { filter.preset = e.target.value; onChange(); } },
      [['today', 'Today'], ['7', 'Last 7 days'], ['30', 'Last 30 days'], ['90', 'Last 90 days'], ['custom', 'Custom…']]
        .map(([v, l]) => h('option', { value: v, selected: filter.preset === v }, l)));
    const f = h('input', { type: 'date', value: filter.from, onchange: (e) => { filter.from = e.target.value; onChange(); } });
    const t = h('input', { type: 'date', value: filter.to, onchange: (e) => { filter.to = e.target.value; onChange(); } });
    if (filter.preset !== 'custom') { f.hidden = true; t.hidden = true; }
    return h('div', { class: 'row' }, sel, f, t);
  }

  function barChart(rows, key, label, cls = 'b') {
    const W = 560, H = 160, P = 22;
    if (!rows.length) return h('p', { class: 'mut' }, 'No data in this range.');
    const max = Math.max(1, ...rows.map((r) => r[key] || 0));
    const bw = Math.max(2, Math.min(40, (W - P) / rows.length - 4));
    const svg = s('svg', { viewBox: `0 0 ${W} ${H}`, width: '100%', role: 'img', 'aria-label': label });
    rows.forEach((r, i) => {
      const bh = Math.round(((r[key] || 0) / max) * (H - P - 14));
      const x = P + i * ((W - P) / rows.length);
      svg.append(s('rect', { class: cls, x, y: H - P - bh, width: bw, height: bh, rx: 2 }, s('title', {}, `${r.day}: ${fmt(r[key])}`)));
      if (rows.length <= 14 || i % Math.ceil(rows.length / 10) === 0) svg.append(s('text', { x: x + bw / 2, y: H - 6, 'text-anchor': 'middle' }, r.day.slice(5)));
    });
    svg.append(s('text', { x: 0, y: 10 }, fmt(max)));
    return svg;
  }

  function table(cols, rows, onRow) {
    return h('table', {},
      h('thead', {}, h('tr', {}, cols.map((c) => h('th', { class: c.num ? 'num' : '' }, c.label)))),
      h('tbody', {}, rows.length ? rows.map((r) => h('tr', onRow ? { class: 'click', onclick: () => onRow(r) } : {},
        cols.map((c) => h('td', { class: (c.num ? 'num ' : '') + (c.cls || ''), 'data-label': c.label || '' }, c.cell(r))))) :h('tr', {}, h('td', { colspan: cols.length, class: 'mut' }, 'Nothing to show.'))));
  }
  const kpi = (v, l) => h('div', { class: 'kpi' }, h('b', {}, v), h('span', {}, l));
  const pager = (page, total, limit, go) => h('div', { class: 'pager' },
    h('span', { class: 'mut' }, `${fmt(total)} total`),
    h('button', { disabled: page <= 1, onclick: () => go(page - 1) }, 'Prev'),
    h('span', {}, `Page ${page}`),
    h('button', { disabled: page * limit >= total, onclick: () => go(page + 1) }, 'Next'));
  const can = (p) => me && me.permissions.includes(p);
  const errBox = (e) => h('p', { class: 'err' }, e.message);

  // ---------- views ----------
  async function viewOverview(box) {
    const load = async () => {
      box.replaceChildren(h('p', { class: 'mut' }, 'Loading…'));
      try {
        const d = await api(`/overview?${range()}`);
        box.replaceChildren(
          h('div', { class: 'grid' },
            kpi(fmt(d.users.total), 'Registered users'), kpi(fmt(d.totals.activeUsers), `Active users (${rangeLabel()})`),
            kpi(fmt(d.totals.visits), 'Page visits'), kpi(fmt(d.totals.domains), 'Websites'),
            kpi(dur(d.totals.totalMs), 'Total browsing time'), kpi(dur(d.sessions.avgMs), 'Avg session duration'),
            kpi(fmt(d.sessions.n), 'Sessions'), kpi(fmt(d.users.newUsers), 'New users')),
          h('div', { class: 'cols' },
            h('div', { class: 'card' }, h('h2', {}, 'Visits per day'), barChart(d.daily, 'visits', 'Visits per day')),
            h('div', { class: 'card' }, h('h2', {}, 'Active users per day'), barChart(d.daily, 'users', 'Active users per day', 'b2'))),
          h('div', { class: 'card' }, h('h2', {}, 'Top websites'),
            table([{ label: 'Website', cell: (r) => siteLink(r.domain) }, { label: 'Visits', num: 1, cell: (r) => fmt(r.visits) },
              { label: 'Users', num: 1, cell: (r) => fmt(r.users) }, { label: 'Time', num: 1, cell: (r) => dur(r.totalMs) }], d.topSites)));
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
          { label: 'Website', cell: (r) => siteLink(r.domain) }, { label: 'Visits', num: 1, cell: (r) => fmt(r.visits) },
          { label: 'Unique users', num: 1, cell: (r) => fmt(r.users) }, { label: 'Total time', num: 1, cell: (r) => dur(r.totalMs) },
          { label: 'Avg per visit', num: 1, cell: (r) => dur(r.avgMs) }, { label: 'Last visit', cell: (r) => dt(r.lastVisit) }], d.rows)));
      } catch (e) { box.replaceChildren(errBox(e)); }
    };
    const search = h('input', { placeholder: 'Filter websites…', oninput: (e) => { q = e.target.value; load(); } });
    const sorter = h('select', { onchange: (e) => { sort = e.target.value; load(); } },
      h('option', { value: 'visits' }, 'Sort by visits'), h('option', { value: 'time' }, 'Sort by time spent'));
    return [h('div', {}, dateBar(load), h('div', { class: 'row' }, search, sorter)), box, load];
  }

  async function viewSessions(box) {
    const load = async () => {
      try {
        const d = await api(`/sessions?${range()}`);
        box.replaceChildren(
          h('div', { class: 'card' }, h('h2', {}, 'Sessions per day'), barChart(d.daily, 'sessions', 'Sessions per day')),
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
          { label: 'Name', cell: (r) => h('div', {}, r.name, h('div', { class: 'sub' }, r.email || r.google_name || '')) },
          { label: 'User ID', cell: (r) => r.id }, { label: 'Registered', cell: (r) => dt(r.created_at) },
          { label: 'Last seen', cell: (r) => dt(r.last_seen) }, { label: 'Visits', num: 1, cell: (r) => fmt(r.visits) },
          { label: 'Browsing time', num: 1, cell: (r) => dur(r.totalMs) }, { label: 'Session time', num: 1, cell: (r) => dur(r.sessionMs) },
          { label: 'Status', cell: (r) => h('span', { class: 'pill ' + (r.status === 'active' ? 'ok' : 'bad') }, r.status) }],
          d.rows, (r) => { location.hash = '#/users/' + r.id; }), pager(page, d.total, 25, (p) => { page = p; load(); })));
      } catch (e) { box.replaceChildren(errBox(e)); }
    };
    const search = h('input', { placeholder: 'Search name, ID or email…', oninput: (e) => { q = e.target.value; page = 1; load(); } });
    return [h('div', {}, dateBar(load), h('div', { class: 'row' }, search)), box, load];
  }

  async function viewUser(box, id) {
    let tab = 'history', q = '', page = 1;
    const head = h('div', {}), body = h('div', {});
    const loadHead = async () => {
      try {
        const d = await api(`/users/${encodeURIComponent(id)}?${range()}`);
        const u = d.user;
        const actions = h('div', { class: 'row' });
        if (can('manage_users')) {
          actions.append(
            h('button', { onclick: async () => { await api(`/users/${id}`, { method: 'PATCH', body: { status: u.status === 'active' ? 'suspended' : 'active' } }); loadHead(); } }, u.status === 'active' ? 'Suspend uploads' : 'Reactivate'),
            h('button', { class: 'bad', onclick: async () => { if (confirm(`Permanently erase ${u.name} and all their data?`)) { await api(`/users/${id}`, { method: 'DELETE' }); location.hash = '#/users'; } } }, 'Erase user data'));
        }
        if (can('delete_data')) {
          const wipe = (what, path, msg) => h('button', { class: 'bad', onclick: async () => {
            if (!confirm(msg)) return;
            try { const r = await api(path, { method: 'DELETE' }); alert(`Deleted ${r.deleted} ${what}.`); loadHead(); loadBody(); } catch (er) { alert(er.message); }
          } }, `Clear ${what}`);
          actions.append(wipe('history', `/users/${encodeURIComponent(id)}/history`, `Delete ALL browsing history and sessions of ${u.name}?`),
            wipe('searches', `/users/${encodeURIComponent(id)}/searches`, `Delete ALL search queries of ${u.name}?`));
        }
        if (can('export')) actions.append(h('a', { href: `${API}/users/${encodeURIComponent(id)}/history.csv?${range()}` }, h('button', {}, 'Export CSV')));
        head.replaceChildren(
          h('div', { class: 'card' }, h('h2', {}, u.name), h('p', { class: 'mut' },
            `${u.id} · ${u.email || 'no Google account'} · registered ${dt(u.created_at)} · last seen ${dt(u.last_seen)} · ${u.device_model || 'unknown device'} (Android ${u.os_version || '?'}, app ${u.app_version || '?'})`),
            h('p', {}, h('span', { class: 'pill ' + (u.activity_consent ? 'ok' : 'bad') }, u.activity_consent ? 'activity consent' : 'activity consent withdrawn'), ' ',
              h('span', { class: 'pill ' + (u.search_consent ? 'ok' : '') }, u.search_consent ? 'search-query consent' : 'no search-query consent')), actions),
          h('div', { class: 'grid' }, kpi(fmt(d.stats.visits), 'Visits'), kpi(fmt(d.stats.domains), 'Websites'), kpi(dur(d.stats.totalMs), 'Browsing time'),
            kpi(fmt(d.sessions.n), 'Sessions'), kpi(dur(d.sessions.avgMs), 'Avg session')),
          h('div', { class: 'cols' }, h('div', { class: 'card' }, h('h2', {}, 'Visits per day'), barChart(d.daily, 'visits', 'Visits per day')),
            h('div', { class: 'card' }, h('h2', {}, 'Top sites by time'), table([{ label: 'Website', cell: (r) => siteLink(r.domain) }, { label: 'Visits', num: 1, cell: (r) => fmt(r.visits) }, { label: 'Time', num: 1, cell: (r) => dur(r.totalMs) }], d.topSites))));
      } catch (e) { head.replaceChildren(errBox(e)); }
    };
    const loadBody = async () => {
      try {
        const base = `/users/${encodeURIComponent(id)}`;
        if (tab === 'history') {
          const d = await api(`${base}/history?${range()}&q=${encodeURIComponent(q)}&page=${page}&limit=50`);
          body.replaceChildren(h('div', { class: 'card' }, table([{ label: 'Time', cell: (r) => dt(r.visited_at) },
            { label: 'Page', cls: 'url', cell: (r) => h('div', {}, extLink(r.url, r.title || r.domain), h('div', { class: 'sub' }, r.url)) },
            { label: 'Time spent', num: 1, cell: (r) => dur(r.duration_ms) },
            ...(can('delete_data') ? [{ label: '', cell: (r) => h('button', { class: 'bad', title: 'Delete this entry', onclick: async () => {
              if (!confirm('Delete this history entry?')) return;
              try { await api(`${base}/history/${encodeURIComponent(r.id)}`, { method: 'DELETE' }); loadBody(); loadHead(); } catch (er) { alert(er.message); }
            } }, 'Delete') }] : [])], d.rows), pager(page, d.total, 50, (p) => { page = p; loadBody(); })));
        } else if (tab === 'sessions') {
          const d = await api(`${base}/sessions?${range()}&limit=100`);
          body.replaceChildren(h('div', { class: 'card' }, table([{ label: 'Started', cell: (r) => dt(r.started_at) }, { label: 'Ended', cell: (r) => dt(r.ended_at) }, { label: 'Duration', num: 1, cell: (r) => dur(r.duration_ms) }], d.rows)));
        } else {
          const d = await api(`${base}/searches?${range()}&limit=100`);
          body.replaceChildren(h('div', { class: 'card' }, d.consent ? null : h('p', { class: 'mut' }, 'This user has not consented to search-query collection.'),
            table([{ label: 'Time', cell: (r) => dt(r.searched_at) },
              { label: 'Query', cell: (r) => extLink('https://www.google.com/search?q=' + encodeURIComponent(r.query), r.query) },
              { label: 'Engine', cell: (r) => r.engine || '' },
              ...(can('delete_data') ? [{ label: '', cell: (r) => h('button', { class: 'bad', title: 'Delete this search', onclick: async () => {
                if (!confirm('Delete this search query?')) return;
                try { await api(`${base}/searches/${encodeURIComponent(r.id)}`, { method: 'DELETE' }); loadBody(); } catch (er) { alert(er.message); }
              } }, 'Delete') }] : [])], d.rows)));
        }
      } catch (e) { body.replaceChildren(errBox(e)); }
    };
    const reload = () => { page = 1; loadHead(); loadBody(); };
    const tabs = h('div', { class: 'row' }, ['history', 'sessions', 'searches'].map((t) =>
      h('button', { class: t === tab ? 'pri' : '', onclick: (e) => { tab = t; tabs.querySelectorAll('button').forEach((b) => b.classList.remove('pri')); e.target.classList.add('pri'); page = 1; loadBody(); } }, t[0].toUpperCase() + t.slice(1))));
    const search = h('input', { placeholder: 'Filter history by URL or title…', oninput: (e) => { q = e.target.value; page = 1; if (tab === 'history') loadBody(); } });
    box.replaceChildren(head, tabs, h('div', { class: 'row' }, search), body);
    return [dateBar(reload), box, reload, true];
  }

  async function viewAdmins(box) {
    const load = async () => {
      try {
        const d = await api('/admins');
        const form = h('form', { class: 'card', onsubmit: async (e) => {
          e.preventDefault();
          const f = e.target;
          try { await api('/admins', { method: 'POST', body: { email: f.email.value, name: f.name.value, role: f.role.value, password: f.password.value } }); load(); }
          catch (er) { alert('Could not create admin: ' + er.message + ' (password needs 12+ chars with upper, lower and a digit)'); }
        } }, h('h2', {}, 'Add admin'), h('div', { class: 'row' },
          h('input', { name: 'name', placeholder: 'Name', required: true }), h('input', { name: 'email', type: 'email', placeholder: 'Email', required: true }),
          h('select', { name: 'role' }, ['viewer', 'analyst', 'superadmin'].map((r) => h('option', { value: r }, r))),
          h('input', { name: 'password', type: 'password', placeholder: 'Initial password', autocomplete: 'new-password', required: true }), h('button', { class: 'pri' }, 'Create')));
        box.replaceChildren(h('div', { class: 'card' }, table([{ label: 'Name', cell: (r) => r.name }, { label: 'Email', cell: (r) => r.email },
          { label: 'Role', cell: (r) => h('select', { onchange: async (e) => { try { await api(`/admins/${r.id}`, { method: 'PATCH', body: { role: e.target.value } }); } catch (er) { alert(er.message); } load(); } },
            ['viewer', 'analyst', 'superadmin'].map((x) => h('option', { value: x, selected: x === r.role }, x))) },
          { label: 'Last login', cell: (r) => dt(r.last_login) },
          { label: '', cell: (r) => h('span', {}, h('button', { onclick: async () => { try { await api(`/admins/${r.id}`, { method: 'PATCH', body: { active: !r.active } }); } catch (er) { alert(er.message); } load(); } }, r.active ? 'Disable' : 'Enable'), ' ',
            h('button', { onclick: async () => { const p = prompt('New password (12+ chars, upper/lower/digit):'); if (p) { try { await api(`/admins/${r.id}`, { method: 'PATCH', body: { password: p } }); alert('Password reset. All sessions revoked.'); } catch (er) { alert(er.message); } } } }, 'Reset password')) }], d.rows)), form);
      } catch (e) { box.replaceChildren(errBox(e)); }
    };
    return [h('div', {}), box, load];
  }

  async function viewServer(box) {
    const load = async () => {
      try {
        const d = await api('/server-info');
        const row = (k, v) => h('tr', {}, h('th', {}, k), h('td', {}, v));
        const danger = (scope, label) => h('button', { class: 'bad', onclick: async () => {
          const w = prompt(`This permanently deletes ${label} for ALL users. Type DELETE to confirm:`);
          if (w !== 'DELETE') return;
          try { const r = await api('/data', { method: 'DELETE', body: { scope, confirm: 'DELETE' } }); alert('Deleted: ' + JSON.stringify(r.deleted)); load(); } catch (er) { alert(er.message); }
        } }, `Clear ${label}`);
        box.replaceChildren(
          h('div', { class: 'card' }, h('h2', {}, 'Where data is stored'), h('table', {}, h('tbody', {},
            row('Database', d.storage.engine), row('Location', d.storage.location), row('Size', bytes(d.storage.sizeBytes)),
            row('Firebase', d.firebase ? 'Yes' : 'Not used (own server + SQLite)'),
            row('Google Drive backup', !d.drive.enabled ? 'Not configured' : h('span', {},
              `every ${d.drive.everyMinutes} min · last: ${d.drive.lastBackupAt ? dt(d.drive.lastBackupAt) : 'not yet'}${d.drive.lastError ? ' · ERROR: ' + d.drive.lastError : ''} `,
              can('manage_server') ? h('button', { onclick: async (e) => { e.target.disabled = true; try { await api('/drive/backup', { method: 'POST' }); } catch (er) { alert('Backup failed: ' + er.message); } load(); } }, 'Back up now') : null)),
            row('User API', `port ${d.userApi.port} (public, used by the phone app)`), row('Admin', `port ${d.adminApi.port} bound to ${d.adminApi.bind}`),
            row('Retention', `${d.retentionDays} days (older data auto-deleted)`), row('Google sign-in', d.googleSignIn ? 'configured' : 'not configured'),
            row('Mode', d.production ? 'production' : 'development'), row('Node', d.node), row('Uptime', dur(d.uptimeSeconds * 1000))))),
          h('div', { class: 'card' }, h('h2', {}, 'Stored records'), h('div', { class: 'grid' },
            kpi(fmt(d.counts.users), 'Users'), kpi(fmt(d.counts.visits), 'Visits'), kpi(fmt(d.counts.searches), 'Searches'),
            kpi(fmt(d.counts.sessions), 'Sessions'), kpi(fmt(d.counts.admins), 'Admins'))),
          h('div', { class: 'card' }, h('h2', {}, 'Danger zone: all users'), h('p', { class: 'mut' }, 'User accounts stay registered; only collected data is removed. Actions are audit-logged.'),
            h('div', { class: 'row' }, danger('history', 'browsing history + sessions'), danger('searches', 'search queries'), danger('all', 'ALL collected data'))));
      } catch (e) { box.replaceChildren(errBox(e)); }
    };
    return [h('div', {}), box, load];
  }

  async function viewAudit(box) {
    const load = async () => {
      try {
        const d = await api('/audit?limit=200');
        box.replaceChildren(h('div', { class: 'card' }, table([{ label: 'When', cell: (r) => dt(r.at) }, { label: 'Admin', cell: (r) => r.admin_email || '—' },
          { label: 'Action', cell: (r) => r.action }, { label: 'Target', cell: (r) => r.target || '' }, { label: 'Detail', cell: (r) => r.detail || '' }, { label: 'IP', cell: (r) => r.ip || '' }], d.rows)));
      } catch (e) { box.replaceChildren(errBox(e)); }
    };
    return [h('div', {}), box, load];
  }

  async function viewAccount(box) {
    const form = h('form', { class: 'card', onsubmit: async (e) => {
      e.preventDefault(); const f = e.target;
      try { await api('/auth/change-password', { method: 'POST', body: { current: f.current.value, next: f.next.value } }); f.reset(); alert('Password changed.'); }
      catch (er) { alert('Failed: ' + er.message + ' (new password needs 12+ chars with upper, lower and a digit)'); }
    } }, h('h2', {}, 'Change password'),
      h('div', { class: 'row' }, h('input', { name: 'current', type: 'password', placeholder: 'Current password', autocomplete: 'current-password', required: true }),
        h('input', { name: 'next', type: 'password', placeholder: 'New password', autocomplete: 'new-password', required: true }), h('button', { class: 'pri' }, 'Change')));
    box.replaceChildren(form);
    return [h('div', {}), box, null];
  }

  // ---------- shell / routing ----------
  const routes = [
    ['#/', 'Overview', viewOverview, 'aggregate'], ['#/websites', 'Websites', viewWebsites, 'aggregate'], ['#/sessions', 'Sessions', viewSessions, 'aggregate'],
    ['#/users', 'Users', viewUsers, 'user_list'], ['#/admins', 'Admins', viewAdmins, 'manage_admins'], ['#/server', 'Server and data', viewServer, 'server_info'], ['#/audit', 'Audit log', viewAudit, 'audit'], ['#/account', 'My account', viewAccount, 'aggregate'],
  ];

  function loginView(msg) {
    const f = h('form', { class: 'card', onsubmit: async (e) => {
      e.preventDefault();
      try { await api('/auth/login', { method: 'POST', body: { email: f.email.value, password: f.password.value } }); await boot(); }
      catch (er) { render(({ invalid_credentials: 'Invalid email or password.', account_locked: 'Account temporarily locked. Try again in 15 minutes.', too_many_attempts: 'Too many attempts. Try later.' })[er.message] || er.message); }
    } }, h('h1', {}, 'WBrowser Admin'), h('input', { name: 'email', type: 'email', placeholder: 'Email', autocomplete: 'username', required: true }),
      h('input', { name: 'password', type: 'password', placeholder: 'Password', autocomplete: 'current-password', required: true }),
      msg ? h('div', { class: 'err' }, msg) : null, h('button', { class: 'pri' }, 'Sign in'));
    return h('div', { class: 'login' }, f);
  }

  async function render(msg) {
    if (!me) { root.replaceChildren(loginView(typeof msg === 'string' ? msg : '')); return; }
    const hash = location.hash || '#/';
    const uid = /^#\/users\/(.+)$/.exec(hash);
    const route = uid ? ['#/users', 'User', null, 'user_detail'] : (routes.find((r) => r[0] === hash) || routes[0]);
    const nav = h('nav', {}, h('div', { class: 'brand' }, 'WBrowser Admin'),
      routes.filter((r) => can(r[3])).map((r) => h('a', { href: r[0], class: r[0] === route[0] ? 'on' : '' }, r[1])), h('div', { class: 'sp' }),
      h('div', { class: 'who' }, `${me.name} · ${me.role}`),
      h('a', { href: '#', onclick: async (e) => { e.preventDefault(); await api('/auth/logout', { method: 'POST' }); me = null; render(); } }, 'Sign out'));
    const box = h('div', {});
    const main = h('main', {}, h('h1', {}, uid ? 'User activity' : route[1]), h('p', { class: 'mut' }, uid ? '' : (can(route[3]) ? '' : 'Not permitted.')), box);
    root.replaceChildren(h('div', { class: 'shell' }, nav, main));
    if (!can(route[3])) return;
    const [bar, content, load] = uid ? await viewUser(box, decodeURIComponent(uid[1])) : await route[2](h('div', {}));
    if (uid) { main.replaceChildren(h('h1', {}, 'User activity'), h('p', {}, h('a', { href: '#/users' }, '← All users')), bar, box); }
    else main.replaceChildren(h('h1', {}, route[1]), bar, content);
    if (load) load();
  }

  async function boot() {
    try { me = await api('/auth/me'); } catch { me = null; }
    render();
  }
  window.addEventListener('hashchange', () => { if (me) render(); });
  boot();
})();
