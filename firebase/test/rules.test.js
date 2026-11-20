'use strict';
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const { initializeTestEnvironment, assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const { doc, setDoc, getDoc, deleteDoc, updateDoc, collection, getDocs, collectionGroup, writeBatch } = require('firebase/firestore');

let env;
test.before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-wbrowser',
    firestore: { rules: fs.readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8'), host: '127.0.0.1', port: 8080 },
  });
});
test.after(async () => { await env.cleanup(); });
test.beforeEach(async () => { await env.clearFirestore(); });

const profile = (o = {}) => ({ name: 'Asha', userId: 'WB-ABCDEFGH', status: 'active', activityConsent: true, searchConsent: false, createdAt: 1, ...o });
const visit = (o = {}) => ({ url: 'https://a.com/x', domain: 'a.com', title: 'A', visitedAt: Date.now(), durationMs: 1000, sessionId: 's1', ...o });
// the owner is the only superadmin (firestore.rules isBoss): verified owner email
const ROOT = { role: 'superadmin', email: 'mybrowser.server@gmail.com', email_verified: true };
const db = (uid, claims) => (claims ? env.authenticatedContext(uid, claims) : env.authenticatedContext(uid)).firestore();
const seed = (fn) => env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore()));

test('user creates own profile only, with consent and active status', async () => {
  await assertSucceeds(setDoc(doc(db('u1'), 'users/u1'), profile()));
  await assertFails(setDoc(doc(db('u1'), 'users/u2'), profile()));
  await assertFails(setDoc(doc(db('u3'), 'users/u3'), profile({ activityConsent: false })));
  await assertFails(setDoc(doc(db('u4'), 'users/u4'), profile({ status: 'suspended' })));
  await assertFails(setDoc(doc(env.unauthenticatedContext().firestore(), 'users/u5'), profile()));
});

test('user cannot reactivate themselves; superadmin can suspend', async () => {
  await seed((d) => setDoc(doc(d, 'users/u1'), profile({ status: 'suspended' })));
  await assertFails(updateDoc(doc(db('u1'), 'users/u1'), { status: 'active' }));
  await seed((d) => setDoc(doc(d, 'users/u1'), profile()));
  await assertFails(updateDoc(doc(db('u1'), 'users/u1'), { name: '' }));   // empty name is rejected
  await assertSucceeds(updateDoc(doc(db('root', ROOT), 'users/u1'), { status: 'suspended' }));
  await assertFails(updateDoc(doc(db('ana', { role: 'analyst' }), 'users/u1'), { status: 'suspended' }));
});

test('visits: valid upload ok, bad shapes rejected, suspended/no-consent blocked, others blocked', async () => {
  await seed((d) => setDoc(doc(d, 'users/u1'), profile()));
  await assertSucceeds(setDoc(doc(db('u1'), 'users/u1/visits/v1'), visit()));
  await assertSucceeds(setDoc(doc(db('u1'), 'users/u1/visits/v1'), visit({ durationMs: 5000 }))); // checkpoint update
  await assertFails(setDoc(doc(db('u1'), 'users/u1/visits/v2'), visit({ url: 'javascript:alert(1)' })));
  await assertFails(setDoc(doc(db('u1'), 'users/u1/visits/v3'), visit({ durationMs: -1 })));
  await assertFails(setDoc(doc(db('u1'), 'users/u1/visits/v4'), visit({ durationMs: 999999999 })));
  await assertFails(setDoc(doc(db('u1'), 'users/u1/visits/v5'), { ...visit(), extra: 'x' }));
  await assertFails(setDoc(doc(db('u2'), 'users/u1/visits/v6'), visit()));          // another user
  await seed((d) => setDoc(doc(d, 'users/u1'), profile({ status: 'suspended' })));
  await assertFails(setDoc(doc(db('u1'), 'users/u1/visits/v7'), visit()));           // suspended
  await seed((d) => setDoc(doc(d, 'users/u1'), profile({ activityConsent: false })));
  await assertFails(setDoc(doc(db('u1'), 'users/u1/visits/v8'), visit()));           // consent withdrawn
});

test('searches only with search consent; withdrawing consent blocks new ones', async () => {
  const s = { query: 'hello', engine: 'google', at: Date.now() };
  await seed((d) => setDoc(doc(d, 'users/u1'), profile({ searchConsent: false })));
  await assertFails(setDoc(doc(db('u1'), 'users/u1/searches/s1'), s));
  await seed((d) => setDoc(doc(d, 'users/u1'), profile({ searchConsent: true })));
  await assertSucceeds(setDoc(doc(db('u1'), 'users/u1/searches/s1'), s));
  await assertFails(setDoc(doc(db('u1'), 'users/u1/searches/s2'), { ...s, query: '' }));
});

test('read access: owner own data, analyst/superadmin everything, other users nothing', async () => {
  await seed(async (d) => {
    await setDoc(doc(d, 'users/u1'), profile());
    await setDoc(doc(d, 'users/u1/visits/v1'), visit());
    await setDoc(doc(d, 'users/u2'), profile({ userId: 'WB-OTHER' }));
  });
  await assertSucceeds(getDoc(doc(db('u1'), 'users/u1/visits/v1')));
  await assertFails(getDoc(doc(db('u2'), 'users/u1/visits/v1')));
  await assertFails(getDocs(collection(db('u2'), 'users')));
  await assertFails(getDocs(collectionGroup(db('u2'), 'visits')));
  await assertSucceeds(getDocs(collectionGroup(db('ana', { role: 'analyst' }), 'visits')));
  await assertSucceeds(getDocs(collection(db('ana', { role: 'analyst' }), 'users')));
  await assertFails(getDocs(collectionGroup(env.unauthenticatedContext().firestore(), 'visits')));
});

test('delete: analyst cannot, superadmin and owner can', async () => {
  await seed(async (d) => { await setDoc(doc(d, 'users/u1'), profile()); await setDoc(doc(d, 'users/u1/visits/v1'), visit()); await setDoc(doc(d, 'users/u1/visits/v2'), visit()); });
  await assertFails(deleteDoc(doc(db('ana', { role: 'analyst' }), 'users/u1/visits/v1')));
  await assertFails(deleteDoc(doc(db('u2'), 'users/u1/visits/v1')));
  await assertSucceeds(deleteDoc(doc(db('root', ROOT), 'users/u1/visits/v1')));
  await assertSucceeds(deleteDoc(doc(db('u1'), 'users/u1/visits/v2')));
  await assertSucceeds(deleteDoc(doc(db('u1'), 'users/u1')));
});

test('batched upload of 100 visits is within rule-call limits', async () => {
  await seed((d) => setDoc(doc(d, 'users/u1'), profile()));
  const d = db('u1'); const b = writeBatch(d);
  for (let i = 0; i < 100; i++) b.set(doc(d, `users/u1/visits/b${i}`), visit());
  await assertSucceeds(b.commit());
});

test('admins and audit: nobody can self-promote; audit append-only', async () => {
  await assertFails(setDoc(doc(db('u1'), 'admins/u1'), { role: 'superadmin' }));
  await assertFails(setDoc(doc(db('root', ROOT), 'admins/x'), { role: 'superadmin' }));
  const ana = db('ana', { role: 'analyst', email: 'ana@x.io' });
  await assertSucceeds(setDoc(doc(ana, 'audit/a1'), { by: 'ana@x.io', action: 'view_user', at: 1 }));
  await assertFails(setDoc(doc(ana, 'audit/a2'), { by: 'someone@else.io', action: 'x', at: 1 }));
  await assertFails(updateDoc(doc(ana, 'audit/a1'), { action: 'edited' }));
  await assertFails(getDoc(doc(ana, 'audit/a1')));
  await assertSucceeds(getDoc(doc(db('root', ROOT), 'audit/a1')));
});

const cfgDoc = (o = {}) => ({ trackingEnabled: true, searchAllowed: true, uploadIntervalSeconds: 15, retentionDays: 180, announcement: '', updatedAt: 1, updatedBy: 'root@x.io', ...o });

test('config/app: any signed-in app reads, only superadmin writes valid values', async () => {
  await seed((d) => setDoc(doc(d, 'config/app'), cfgDoc()));
  await assertSucceeds(getDoc(doc(db('u1'), 'config/app')));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'config/app')));
  const root = db('root', ROOT);
  await assertSucceeds(setDoc(doc(root, 'config/app'), cfgDoc({ retentionDays: 90, announcement: 'Hello' })));
  await assertFails(setDoc(doc(db('ana', { role: 'analyst' }), 'config/app'), cfgDoc()));
  await assertFails(setDoc(doc(db('u1'), 'config/app'), cfgDoc()));
  await assertFails(setDoc(doc(root, 'config/app'), cfgDoc({ uploadIntervalSeconds: 1 })));
  await assertFails(setDoc(doc(root, 'config/app'), cfgDoc({ retentionDays: 0 })));
  await assertFails(setDoc(doc(root, 'config/app'), { ...cfgDoc(), extra: 1 }));
  await assertFails(setDoc(doc(root, 'config/other'), cfgDoc()));
  await assertFails(deleteDoc(doc(root, 'config/app')));
});

test('kill switches in config/app are enforced by the rules, not only by the app', async () => {
  await seed(async (d) => { await setDoc(doc(d, 'users/u1'), profile({ searchConsent: true })); await setDoc(doc(d, 'config/app'), cfgDoc({ trackingEnabled: false })); });
  await assertFails(setDoc(doc(db('u1'), 'users/u1/visits/v1'), visit()));
  await seed((d) => setDoc(doc(d, 'config/app'), cfgDoc({ searchAllowed: false })));
  await assertSucceeds(setDoc(doc(db('u1'), 'users/u1/visits/v1'), visit()));
  await assertFails(setDoc(doc(db('u1'), 'users/u1/searches/s1'), { query: 'x', engine: 'g', at: Date.now() }));
  await seed((d) => setDoc(doc(d, 'config/app'), cfgDoc()));
  await assertSucceeds(setDoc(doc(db('u1'), 'users/u1/searches/s1'), { query: 'x', engine: 'g', at: Date.now() }));
  const u1 = db('u1'); const b = writeBatch(u1); for (let i = 0; i < 100; i++) b.set(doc(u1, `users/u1/visits/b${i}`), visit());
  await assertSucceeds(b.commit());   // 100 writes with the extra config lookup stay within rule-call limits
});

test('admin accounts are protected: a superadmin cannot delete an admin uid; normal users can be deleted', async () => {
  await seed(async (d) => {
    await setDoc(doc(d, 'admins/adm1'), { role: 'superadmin', email: 'a@x.io' });
    await setDoc(doc(d, 'users/adm1'), profile({ name: 'Admin on phone' }));
    await setDoc(doc(d, 'users/u9'), profile());
  });
  const root = db('root', ROOT);
  await assertFails(deleteDoc(doc(root, 'users/adm1')));
  await assertSucceeds(deleteDoc(doc(root, 'users/u9')));
  await assertFails(deleteDoc(doc(root, 'admins/adm1')));          // admins/* is never writable from apps
  await assertFails(setDoc(doc(root, 'admins/adm1'), { role: 'analyst' }));
});

test('admin records: an admin can re-create only their own record; nobody can edit or delete it', async () => {
  const ana = db('ana', { role: 'analyst', email: 'ana@x.io' });
  await assertSucceeds(setDoc(doc(ana, 'admins/ana'), { email: 'ana@x.io', name: 'Ana', role: 'analyst', updatedAt: 1 }));
  await assertFails(setDoc(doc(ana, 'admins/ana'), { email: 'ana@x.io', name: 'Ana', role: 'superadmin', updatedAt: 2 }));     // update refused
  await assertFails(deleteDoc(doc(ana, 'admins/ana')));
  await assertFails(setDoc(doc(db('ana2', { role: 'analyst', email: 'ana2@x.io' }), 'admins/ana2'), { email: 'ana2@x.io', role: 'superadmin' })); // role must match claim
  await assertFails(setDoc(doc(db('ana3', { role: 'analyst', email: 'ana3@x.io' }), 'admins/ana3'), { email: 'other@x.io', role: 'analyst' }));  // email must match
  await assertFails(setDoc(doc(db('ana4', { role: 'analyst', email: 'ana4@x.io' }), 'admins/someone-else'), { email: 'ana4@x.io', role: 'analyst' }));
  await assertFails(setDoc(doc(db('plain'), 'admins/plain'), { email: 'p@x.io', role: 'superadmin' }));                        // non-admin
  await assertSucceeds(getDoc(doc(ana, 'admins/ana')));
});

test('owner is the only superadmin: needs the verified owner email, not just a claim', async () => {
  await seed(async (d) => { await setDoc(doc(d, 'users/u1'), profile()); await setDoc(doc(d, 'users/u1/visits/v1'), visit()); });
  // owner with NO claim at all (e.g. the Auth user was deleted and re-created by an email link) still has full power
  const owner = db('boss', { email: 'mybrowser.server@gmail.com', email_verified: true });
  await assertSucceeds(updateDoc(doc(owner, 'users/u1'), { status: 'suspended' }));
  await assertSucceeds(deleteDoc(doc(owner, 'users/u1/visits/v1')));
  // the same address WITHOUT a verified email gets nothing (nobody can register it as a password account)
  const fake = db('fake', { email: 'mybrowser.server@gmail.com', email_verified: false });
  await assertFails(getDocs(collection(fake, 'users')));
  // another account with a superadmin claim is only an analyst: reads yes, deletes and suspends no
  const other = db('other', { role: 'superadmin', email: 'someone@else.io', email_verified: true });
  await assertSucceeds(getDocs(collection(other, 'users')));
  await assertFails(updateDoc(doc(other, 'users/u1'), { status: 'active' }));
  await assertFails(getDoc(doc(other, 'audit/a1')));
  // an admins record can only claim superadmin for the owner
  await assertSucceeds(setDoc(doc(owner, 'admins/boss'), { email: 'mybrowser.server@gmail.com', role: 'superadmin', updatedAt: 1 }));
  await assertFails(setDoc(doc(other, 'admins/other'), { email: 'someone@else.io', role: 'superadmin', updatedAt: 1 }));
});
