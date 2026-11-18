'use strict';
// Creates an admin sign-in and gives it a role. Run on YOUR PC only; the service-account key never goes in the apps.
//   set GOOGLE_APPLICATION_CREDENTIALS=C:\path\serviceAccountKey.json
//   node scripts/set-role.js admin@example.com superadmin "Your Name"      (password is prompted)
//   node scripts/set-role.js viewer@example.com analyst
//   node scripts/set-role.js someone@example.com none          (removes admin access)
const readline = require('node:readline');
const admin = require('firebase-admin');

async function askHidden(prompt) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: true });
  rl._writeToOutput = (s) => { if (s.includes(prompt)) rl.output.write(s); };
  return new Promise((r) => rl.question(prompt, (a) => { rl.close(); process.stdout.write('\n'); r(a); }));
}

(async () => {
  const [email, role, name = 'Admin'] = process.argv.slice(2);
  if (!email || !['superadmin', 'analyst', 'none'].includes(role)) {
    console.error('Usage: node scripts/set-role.js <email> <superadmin|analyst|none> ["Name"]'); process.exit(1);
  }
  admin.initializeApp();
  const auth = admin.auth(), db = admin.firestore();
  let user;
  try { user = await auth.getUserByEmail(email); }
  catch {
    const password = process.env.ADMIN_PASSWORD || await askHidden('New password (12+ chars): ');
    if (password.length < 12) { console.error('Password too short'); process.exit(1); }
    user = await auth.createUser({ email, password, displayName: name, emailVerified: true });
  }
  // never leave the project without a superadmin
  if (role !== 'superadmin') {
    const current = (await db.doc(`admins/${user.uid}`).get()).data();
    if (current && current.role === 'superadmin') {
      const supers = await db.collection('admins').where('role', '==', 'superadmin').get();
      if (supers.size <= 1) { console.error('Refusing: this is the only superadmin. Create another superadmin first.'); process.exit(1); }
    }
  }
  if (role === 'none') {
    await auth.setCustomUserClaims(user.uid, { role: null }); await db.doc(`admins/${user.uid}`).delete();
  } else {
    await auth.setCustomUserClaims(user.uid, { role });
    await db.doc(`admins/${user.uid}`).set({ email, name: user.displayName || name, role, updatedAt: Date.now() });
  }
  await auth.revokeRefreshTokens(user.uid);
  console.log(`${email} is now: ${role}. They must sign in again.`);
})().catch((e) => { console.error(e.message); process.exit(1); });
