'use strict';
// Break-glass: (re)creates the owner's sign-in and makes it the one permanent superadmin. Run on YOUR PC only.
//   set GOOGLE_APPLICATION_CREDENTIALS=C:\path\serviceAccountKey.json
//   node scripts/restore-owner.js
// Safe to run any time: it repairs a deleted Auth user, a missing role claim and a missing admins record.
// The owner can always sign in again WITHOUT this script too: on the admin page tap "Locked out? Email me a
// sign-in link", open the link, and the same email is superadmin again (firestore.rules isBoss()).
const admin = require('firebase-admin');

const OWNER = 'mybrowser.server@gmail.com';   // keep in sync with firestore.rules, public/admin/fb.js and the Android app

(async () => {
  admin.initializeApp();
  const auth = admin.auth(), db = admin.firestore();
  let user;
  try { user = await auth.getUserByEmail(OWNER); console.log('Owner sign-in exists:', user.uid); }
  catch {
    // no password on purpose: sign in with an emailed link or the "forgot password" link, then set a password
    user = await auth.createUser({ email: OWNER, emailVerified: true, displayName: 'Owner' });
    console.log('Owner sign-in re-created:', user.uid);
  }
  if (!user.emailVerified) await auth.updateUser(user.uid, { emailVerified: true });
  await auth.setCustomUserClaims(user.uid, { role: 'superadmin' });
  await db.doc('admins/' + user.uid).set({ email: OWNER, name: user.displayName || 'Owner', role: 'superadmin', updatedAt: Date.now() });
  await auth.revokeRefreshTokens(user.uid);
  console.log(OWNER + ' is the permanent superadmin. Sign in again (use "Locked out? Email me a sign-in link" if you have no password).');
})().catch((e) => { console.error(e.message); process.exit(1); });
