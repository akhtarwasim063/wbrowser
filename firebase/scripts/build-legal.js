'use strict';
// Renders the Privacy Policy and Terms of Service (single source: tracking_backend/legal/*.md) into static pages
// for Firebase Hosting: https://<project>.web.app/privacy and /terms (use these URLs on Google's OAuth branding page).
const fs = require('node:fs');
const path = require('node:path');
const { render } = require('../../tracking_backend/src/legal');

const src = path.join(__dirname, '..', '..', 'tracking_backend', 'legal');
for (const [dir, file] of [['privacy', 'PRIVACY_POLICY.md'], ['terms', 'TERMS_OF_SERVICE.md']]) {
  const out = path.join(__dirname, '..', 'public', dir);
  fs.mkdirSync(out, { recursive: true });
  fs.writeFileSync(path.join(out, 'index.html'), render(fs.readFileSync(path.join(src, file), 'utf8')));
  console.log('wrote', path.join('public', dir, 'index.html'));
}
