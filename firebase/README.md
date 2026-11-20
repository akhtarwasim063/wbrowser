# WBrowser on Firebase (no server of your own)

Phone app -> **Firebase Auth** (anonymous, optional Google link) -> **Cloud Firestore**. Admins use a static web page on
**Firebase Hosting** (also the public home of the Privacy Policy and Terms). Everything runs on the free **Spark** plan
and stays online when your PC is off. Who can read or delete what is enforced by `firestore.rules` and the `role` claim
on admin sign-ins.

    users/{uid}                      name, WB- user ID, consent flags, device, status
    users/{uid}/visits|searches|sessions/{id}
    admins/{uid}, audit/{id}

Roles: **superadmin** (read everything, delete, suspend) and **analyst** (read-only). The legal pages (privacy, terms) are built from `legal/` by `npm run build:legal`.

## One-time setup (about 15 minutes, in your own Google account)
1. https://console.firebase.google.com > **Add project** (any name; Analytics not needed).
2. **Build > Authentication > Get started > Sign-in method**: enable **Anonymous** and **Email/Password**; enable **Google**
   if you want the optional Google sign-in.
3. **Build > Firestore Database > Create database** (production mode, pick a region).
4. **Project settings > Your apps**:
   - add an **Android app** with package `dev.wbrowser.nativeapp` (add your debug SHA-1 for Google sign-in), download
     `google-services.json` into `native_android/app/`.
   - add a **Web app**, copy its config into `firebase/public/admin/firebase-config.js`.
5. Deploy rules, indexes and the admin site (from this `firebase/` folder):

       npm install
       npx firebase login
       npx firebase use --add          (pick your project)
       npm run deploy                  (builds the legal pages, deploys firestore + hosting)

   Your admin site is `https://<project>.web.app/admin/`, the policy pages are `/privacy` and `/terms`
   (use those URLs on Google's OAuth branding page if you add Google sign-in).
6. Create the first admin. Project settings > **Service accounts > Generate new private key** (keep the file private; it is
   git-ignored), then:

       set GOOGLE_APPLICATION_CREDENTIALS=C:\path\to\serviceAccountKey.json
       node scripts/set-role.js you@example.com superadmin "Your Name"       (password is prompted)
       node scripts/set-role.js reader@example.com analyst "Reader"

7. Build the apps. The browser app needs no flags (it reads `google-services.json`):

       cd native_android && gradlew installDebug
       cd admin_android  && gradlew installDebug

## Test locally without a Firebase project
    npx firebase emulators:start --only auth,firestore,hosting --project demo-wbrowser    (needs Java)
    set FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099& set FIRESTORE_EMULATOR_HOST=127.0.0.1:8080& set GCLOUD_PROJECT=demo-wbrowser
    node scripts/set-role.js admin@local.test superadmin
    cd ..\native_android && gradlew installDebug -PFIREBASE_EMULATOR_HOST=127.0.0.1
    adb reverse tcp:8080 tcp:8080 & adb reverse tcp:9099 tcp:9099 & adb reverse tcp:5000 tcp:5000
    admin site: http://127.0.0.1:5000/admin/        `npm test` runs the security-rules tests against the emulator.

## What changed versus the Node backend (be aware)
- URL cleaning (removing tokens, search queries) happens **on the phone only**. Rules check the shape of each record
  (https URL, sizes, ranges, search consent) but cannot rewrite it. The server-side re-sanitising is gone.
- Dashboards are computed in the admin's browser from Firestore reads (capped at 30,000 records per query). That is fine
  for small and medium use; Firestore bills per read on paid plans.
- The read-only "viewer" role is gone (rules cannot hide fields), and there is no automatic retention purge without Cloud
  Functions (which need the paid Blaze plan). Use the superadmin "Clear" buttons or add a scheduled function later.
- Admin accounts and roles are managed from your PC with `scripts/set-role.js`, not in the web page.
- Admin-action audit entries are written by the admin's browser, so they are a convenience log, not tamper-proof.

## Lost access to the admin console (recovery)
Admin access comes from the Firebase Auth **role claim** on the sign-in, not from the `admins/` record in Firestore.
Deleting that record never locks anyone out; the console re-creates it on the next login.

| Situation | Fix |
|---|---|
| Forgot the password | Admin login page > **Forgot password?** (type the email first). Firebase emails a reset link. |
| `admins/<uid>` record deleted | Nothing to do: it is re-created at the next login. |
| Sign-in account deleted in the Firebase console, or reset email unreachable | Run from your PC with a service-account key (Project settings > Service accounts): `node scripts/set-role.js you@example.com superadmin "Name"`. It re-creates the sign-in, sets the role and a new password. |
| Locked out of everything | Same script. Keep the key file somewhere safe and offline, not inside the project folder. |

Tips: keep a **second superadmin** as a backup (`set-role.js other@example.com superadmin`). The script refuses to demote or
remove the last remaining superadmin. Anyone who is an Owner of the Firebase project can still delete sign-ins in the console;
the recovery script is the way back.

## Owner account: the one permanent superadmin, and how to get back in
`mybrowser.server@gmail.com` is the project owner. It is **always the only superadmin**, whatever the Auth claim or the `admins` record says:
- `firestore.rules` (`isBoss()`), `public/admin/fb.js` and the Android app all treat that **verified** email as superadmin.
  A verified email is required, so nobody can register that address as a password account and use it.
- Any other account with a `superadmin` claim is only an analyst. `scripts/set-role.js` refuses to make anyone else superadmin,
  and refuses to demote or remove the owner.
- Deleting the owner's Auth user or `admins/{uid}` cannot lock the owner out, because access comes from the email, not from the record.

**If the account is deleted or you are locked out (no PC needed):**
1. Open `https://<project>.web.app/admin/`, type the owner email, tap **Locked out? Email me a sign-in link**.
2. Open the link in the same browser. This re-creates the sign-in (verified) and you are superadmin again, with all data intact.
3. Optional: *My account > Owner recovery* lets you set a password so the Android app can sign in with email + password too.
   (The "Forgot password" email only works while the Auth user exists, so after a deletion use the sign-in link first.)

**One-time setup:** Firebase Console > Authentication > Sign-in method > Email/Password > turn **on** "Email link (passwordless sign-in)".
Firebase's free plan has email *links* (no numeric OTP codes; those need Cloud Functions on the paid Blaze plan).

**From the PC (needs the service-account key):** `node scripts/restore-owner.js` re-creates the owner sign-in, claim and record.

If you ever change the owner address, update it in four places: `firestore.rules`, `public/admin/fb.js`,
`scripts/set-role.js` / `scripts/restore-owner.js`, and `AdminRepo.kt` (`OWNER`).
