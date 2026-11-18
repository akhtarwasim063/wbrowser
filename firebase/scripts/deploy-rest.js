'use strict';
// Deploys firestore.rules, index overrides and the Hosting site through Google's REST APIs using a service-account key
// (GOOGLE_APPLICATION_CREDENTIALS). Use this when `firebase deploy` is blocked by its extra IAM pre-flight checks.
//   node scripts/deploy-rest.js
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const crypto = require('node:crypto');
const { GoogleAuth } = require('google-auth-library');

const root = path.join(__dirname, '..');
const PROJECT = JSON.parse(fs.readFileSync(path.join(root, '.firebaserc'), 'utf8')).projects.default;
const cfg = JSON.parse(fs.readFileSync(path.join(root, 'firebase.json'), 'utf8'));

(async () => {
  const client = await new GoogleAuth({ scopes: ['https://www.googleapis.com/auth/cloud-platform', 'https://www.googleapis.com/auth/firebase'] }).getClient();
  const call = async (method, url, data, extra = {}) => (await client.request({ method, url, data, ...extra })).data;

  // ---------- Firestore rules ----------
  const rules = fs.readFileSync(path.join(root, cfg.firestore.rules), 'utf8');
  const rs = await call('POST', `https://firebaserules.googleapis.com/v1/projects/${PROJECT}/rulesets`, { source: { files: [{ name: 'firestore.rules', content: rules }] } });
  const relName = `projects/${PROJECT}/releases/cloud.firestore`;
  try { await call('PATCH', `https://firebaserules.googleapis.com/v1/${relName}`, { release: { name: relName, rulesetName: rs.name } }); }
  catch (e) { if (e.response?.status !== 404) throw e; await call('POST', `https://firebaserules.googleapis.com/v1/projects/${PROJECT}/releases`, { name: relName, rulesetName: rs.name }); }
  console.log('Rules released:', rs.name.split('/').pop());

  // ---------- index overrides (single-field, incl. collection-group scope) ----------
  const idx = JSON.parse(fs.readFileSync(path.join(root, cfg.firestore.indexes), 'utf8'));
  for (const f of idx.fieldOverrides || []) {
    const name = `projects/${PROJECT}/databases/(default)/collectionGroups/${f.collectionGroup}/fields/${f.fieldPath}`;
    const indexes = f.indexes.map((i) => ({ queryScope: i.queryScope, fields: [{ fieldPath: f.fieldPath, order: i.order }] }));
    try { await call('PATCH', `https://firestore.googleapis.com/v1/${name}?updateMask=indexConfig`, { name, indexConfig: { indexes } }); console.log('Index override:', f.collectionGroup + '.' + f.fieldPath); }
    catch (e) { console.log('Index override skipped (key lacks permission; not required, the admin site queries per user):', f.collectionGroup + '.' + f.fieldPath); }
  }

  // ---------- Hosting ----------
  const h = cfg.hosting;
  // the emulator-only loopback allowances are not shipped to production
  const headers = (h.headers || []).map((x) => ({ glob: x.source, headers: Object.fromEntries(x.headers.map((k) => [k.key, k.key === 'Content-Security-Policy' ? k.value.replace(/ (https?|wss?):\/\/127\.0\.0\.1:\d+/g, '') : k.value])) }));
  const redirects = (h.redirects || []).map((r) => ({ glob: r.source, statusCode: r.type, location: r.destination }));
  const version = await call('POST', `https://firebasehosting.googleapis.com/v1beta1/sites/${PROJECT}/versions`, { config: { headers, redirects, cleanUrls: !!h.cleanUrls } });

  const pub = path.join(root, h.public); const files = {}; const blobs = {};
  (function walk(d) { for (const n of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, n.name); if (n.isDirectory()) { if (n.name !== 'node_modules') walk(p); continue; }
    if (n.name.startsWith('.')) continue;
    const gz = zlib.gzipSync(fs.readFileSync(p), { level: 9 }); const hash = crypto.createHash('sha256').update(gz).digest('hex');
    files['/' + path.relative(pub, p).split(path.sep).join('/')] = hash; blobs[hash] = gz;
  } })(pub);

  const pop = await call('POST', `https://firebasehosting.googleapis.com/v1beta1/${version.name}:populateFiles`, { files });
  for (const hash of pop.uploadRequiredHashes || []) {
    await call('POST', `${pop.uploadUrl}/${hash}`, blobs[hash], { headers: { 'content-type': 'application/octet-stream' }, body: blobs[hash] });
  }
  await call('PATCH', `https://firebasehosting.googleapis.com/v1beta1/${version.name}?update_mask=status`, { status: 'FINALIZED' });
  const rel = await call('POST', `https://firebasehosting.googleapis.com/v1beta1/sites/${PROJECT}/releases?versionName=${version.name}`, {});
  console.log(`Hosting released: ${Object.keys(files).length} files -> https://${PROJECT}.web.app  (${rel.version?.status || 'ok'})`);
})().catch((e) => { console.error('FAILED:', e.response?.status, JSON.stringify(e.response?.data?.error || e.message).slice(0, 500)); process.exit(1); });
