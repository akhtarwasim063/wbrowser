'use strict';
// Serves the Privacy Policy and Terms of Service (rendered from legal/*.md) on the public listener,
// so the app, Google's OAuth consent screen and users can link to https://<your-host>/privacy and /terms.
const fs = require('node:fs');
const path = require('node:path');

const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const inline = (s) => esc(s).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>').replace(/`(.+?)`/g, '<code>$1</code>');

function render(md) {
  const out = []; let list = false; let title = 'WBrowser';
  const close = () => { if (list) { out.push('</ul>'); list = false; } };
  for (const line of md.split(/\r?\n/)) {
    if (/^# /.test(line)) { title = line.slice(2); out.push(`<h1>${inline(title)}</h1>`); close(); }
    else if (/^## /.test(line)) { close(); out.push(`<h2>${inline(line.slice(3))}</h2>`); }
    else if (/^- /.test(line)) { if (!list) { out.push('<ul>'); list = true; } out.push(`<li>${inline(line.slice(2))}</li>`); }
    else if (line.trim() === '') close();
    else { close(); out.push(`<p>${inline(line)}</p>`); }
  }
  close();
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title><style>body{font:16px/1.6 system-ui,sans-serif;max-width:720px;margin:0 auto;padding:24px 16px;color:#14161f;background:#fff}
h1{font-size:26px}h2{font-size:19px;margin-top:28px}code{background:#f1f2f6;padding:1px 5px;border-radius:4px}
@media (prefers-color-scheme:dark){body{background:#0e1017;color:#eceef5}code{background:#272b38}}</style></head><body>${out.join('\n')}</body></html>`;
}

function mount(app) {
  const dir = path.join(__dirname, '..', 'legal');
  const pages = { '/privacy': 'PRIVACY_POLICY.md', '/terms': 'TERMS_OF_SERVICE.md' };
  for (const [route, file] of Object.entries(pages)) {
    app.get(route, (_req, res) => {
      res.type('html').set('Cache-Control', 'public, max-age=3600').send(render(fs.readFileSync(path.join(dir, file), 'utf8')));
    });
  }
}
module.exports = { mount, render };
