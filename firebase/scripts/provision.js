'use strict';
// One-shot project setup using a service-account key (GOOGLE_APPLICATION_CREDENTIALS):
//   - enables Anonymous + Email/Password sign-in
//   - registers the Web app (admin site) and the Android app, writes their configs
//   - registers the local debug SHA-1 (needed only for Google sign-in)
// Safe to re-run: existing apps are reused.
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { GoogleAuth } = require('google-auth-library');

const PROJECT = process.argv[2] || JSON.parse(fs.readFileSync(path.join(__dirname, '..', '.firebaserc'), 'utf8')).projects.default;
const PACKAGE = 'dev.wbrowser.nativeapp';
const root = path.join(__dirname, '..');

(async () => {
  const client = await new GoogleAuth({ scopes: ['https://www.googleapis.com/auth/cloud-platform'] }).getClient();
  const call = async (method, url, data) => (await client.request({ method, url, data })).data;
  const FB = `https://firebase.googleapis.com/v1beta1`;
  const wait = async (op) => {
    for (let i = 0; i < 40 && !op.done; i++) { await new Promise((r) => setTimeout(r, 2000)); op = await call('GET', `${FB}/${op.name}`); }
    if (!op.done || op.error) throw new Error('operation failed: ' + JSON.stringify(op.error || 'timeout'));
    return op.response;
  };

  const db = await call('GET', `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/(default)`);
  console.log('Firestore:', db.locationId, db.type);

  await call('PATCH', `https://identitytoolkit.googleapis.com/admin/v2/projects/${PROJECT}/config?updateMask=signIn.anonymous,signIn.email`,
    { signIn: { anonymous: { enabled: true }, email: { enabled: true, passwordRequired: true } } });
  console.log('Auth: Anonymous + Email/Password enabled');

  // ---- web app (admin site) ----
  let web = (await call('GET', `${FB}/projects/${PROJECT}/webApps`)).apps?.[0];
  if (!web) web = await wait(await call('POST', `${FB}/projects/${PROJECT}/webApps`, { displayName: 'WBrowser Admin' }));
  const wc = await call('GET', `${FB}/projects/${PROJECT}/webApps/${web.appId}/config`);
  fs.writeFileSync(path.join(root, 'public', 'admin', 'firebase-config.js'),
    `// Firebase WEB config (public identifiers, not secrets: access is enforced by firestore.rules + sign-in).\nexport default ${JSON.stringify({ apiKey: wc.apiKey, authDomain: wc.authDomain, projectId: wc.projectId, appId: wc.appId }, null, 2)};\n`);
  console.log('Web app:', web.appId, '-> public/admin/firebase-config.js');

  // ---- android app ----
  let and = (await call('GET', `${FB}/projects/${PROJECT}/androidApps`)).apps?.find((a) => a.packageName === PACKAGE);
  if (!and) and = await wait(await call('POST', `${FB}/projects/${PROJECT}/androidApps`, { displayName: 'WBrowser', packageName: PACKAGE }));
  const ac = await call('GET', `${FB}/projects/${PROJECT}/androidApps/${and.appId}/config`);
  const target = path.join(root, '..', 'native_android', 'app', 'google-services.json');
  fs.writeFileSync(target, Buffer.from(ac.configFileContents, 'base64'));
  console.log('Android app:', and.appId, '-> native_android/app/google-services.json');

  // ---- debug SHA-1 (for Google sign-in) ----
  try {
    const ks = path.join(process.env.USERPROFILE || process.env.HOME, '.android', 'debug.keystore');
    const keytool = path.join(process.env.JAVA_HOME || '', 'bin', 'keytool');
    const out = execFileSync(keytool, ['-list', '-v', '-keystore', ks, '-alias', 'androiddebugkey', '-storepass', 'android', '-keypass', 'android'], { encoding: 'utf8' });
    const sha = /SHA1:\s*([0-9A-F:]+)/i.exec(out)[1].replace(/:/g, '').toLowerCase();
    await call('POST', `${FB}/projects/${PROJECT}/androidApps/${and.appId}/sha`, { shaHash: sha, certType: 'SHA_1' }).catch((e) => {
      if (!/ALREADY_EXISTS|already exists/i.test(JSON.stringify(e.response?.data || e.message))) throw e;
    });
    console.log('Debug SHA-1 registered');
    // re-download so the file includes the OAuth client created for that SHA-1
    const ac2 = await call('GET', `${FB}/projects/${PROJECT}/androidApps/${and.appId}/config`);
    fs.writeFileSync(target, Buffer.from(ac2.configFileContents, 'base64'));
  } catch (e) { console.log('SHA-1 step skipped:', (e.message || '').split('\n')[0]); }
})().catch((e) => { console.error('FAILED:', e.response?.status, JSON.stringify(e.response?.data?.error || e.message).slice(0, 400)); process.exit(1); });
