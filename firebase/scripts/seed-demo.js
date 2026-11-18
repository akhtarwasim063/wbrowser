'use strict';
// LOCAL EMULATOR ONLY: fills Firestore with demo users and 14 days of activity so the dashboards have data.
//   set FIRESTORE_EMULATOR_HOST=127.0.0.1:8080& set GCLOUD_PROJECT=demo-wbrowser& node scripts/seed-demo.js
if (!process.env.FIRESTORE_EMULATOR_HOST) { console.error('Refusing to run: FIRESTORE_EMULATOR_HOST is not set (this script must never touch a real project).'); process.exit(1); }
const admin = require('firebase-admin');
admin.initializeApp();
const db = admin.firestore();
const people = [['Priya Sharma', 'priya@example.com'], ['Rahul Verma', null], ['Ananya Iyer', 'ananya@example.com'], ['Karan Mehta', null], ['Sara Khan', 'sara@example.com']];
const sites = [['youtube.com', 'YouTube', 9], ['wikipedia.org', 'Wikipedia', 5], ['github.com', 'GitHub', 6], ['news.ycombinator.com', 'Hacker News', 3], ['reddit.com', 'Reddit', 5], ['amazon.in', 'Amazon.in', 4], ['stackoverflow.com', 'Stack Overflow', 4], ['bbc.com', 'BBC News', 2]];
const rnd = (a, b) => Math.floor(a + Math.random() * (b - a));
const pick = () => { let t = Math.random() * sites.reduce((s, x) => s + x[2], 0); for (const x of sites) { t -= x[2]; if (t <= 0) return x; } return sites[0]; };
(async () => {
  const now = Date.now();
  for (const [i, [name, email]] of people.entries()) {
    const uid = `demo-user-${i + 1}`; const ref = db.doc(`users/${uid}`);
    await ref.set({ name, userId: `WB-DEMO${String(i + 1).padStart(12, '0')}`, status: i === 3 ? 'suspended' : 'active', activityConsent: true, searchConsent: i % 2 === 0,
      createdAt: now - rnd(3, 13) * 86400000, lastSeen: now - rnd(0, i === 4 ? 4 * 86400000 : 3600000), ...(email ? { email, googleName: name } : {}),
      device: { model: ['OnePlus CPH2805', 'Samsung SM-S918B', 'Google Pixel 8', 'Xiaomi 13', 'Realme RMX3771'][i], os: String(13 + (i % 3)), appVersion: '1.0.0' } });
    const batch = db.batch();
    for (let d = 0; d < 14; d++) {
      const visits = rnd(2, 14); const start = now - d * 86400000;
      for (let v = 0; v < visits; v++) {
        const [domain, title] = pick(); const hour = [9, 10, 11, 13, 14, 19, 20, 21, 22][rnd(0, 9)];
        const at = new Date(start); at.setHours(hour, rnd(0, 59), rnd(0, 59), 0);
        batch.set(ref.collection('visits').doc(`d${d}v${v}`), { url: `https://www.${domain}/${['', 'home', 'watch', 'wiki/Firebase', 'trending'][rnd(0, 5)]}`, domain, title: `${title}`, visitedAt: at.getTime(), durationMs: rnd(5, 420) * 1000, sessionId: `s${d}` });
      }
      batch.set(ref.collection('sessions').doc(`s${d}`), { startedAt: start - 3600000, endedAt: start, durationMs: rnd(2, 55) * 60000 });
      if (i % 2 === 0 && d < 6) batch.set(ref.collection('searches').doc(`q${d}`), { query: ['best android browser', 'weather today', 'firebase pricing', 'python tutorial', 'cheap flights'][rnd(0, 5)], engine: 'google', at: start - rnd(1, 9) * 3600000 });
    }
    await batch.commit();
  }
  console.log('seeded', people.length, 'demo users');
})();
