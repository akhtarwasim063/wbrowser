# Working from any computer, recovering the owner account, and cleaning a work PC

Everything needed to build, run and deploy WBrowser is in this repository and in the owner's logins. Nothing has to stay on one particular computer.

## 1. What lives where

| Thing | Where it lives | Needed on a new computer |
|---|---|---|
| Code (apps, website, rules, scripts) | this GitHub repository | `git clone` |
| Website + Firestore rules (deploy) | Firebase project `wbrowser-62d56` | Firebase CLI login as the owner |
| Data (users, visits, sessions) | Cloud Firestore | nothing, it is in the cloud |
| Admin sign-ins | Firebase Authentication | nothing, it is in the cloud |
| GitHub push access | a personal access token you create | create a new one per push, never save it |
| Service-account key (only for `set-role.js` / `restore-owner.js`) | **not** in the repo (git-ignored) | optional, create from Firebase Console > Project settings > Service accounts if ever needed |

No password, token or key is stored in the repository.

## 2. Setting up a new (personal) computer

1. Install: Git, Node.js (LTS), Android Studio (it bundles the JDK and Android SDK).
2. `git clone https://github.com/akhtarwasim063/wbrowser.git` and check out the branch you want
   (`development` for new work, `production` for the stable code).
3. **Website / rules deploy**
   ```
   cd firebase
   npm install
   npx firebase login            # sign in as the project owner in the browser
   npm run deploy                # builds the legal pages, then deploys Firestore rules + Hosting
   ```
   Hosting only: `npx firebase deploy --only hosting --project wbrowser-62d56`.
4. **Android apps**: in Android Studio use File > Open on `native_android/` (the browser) or `admin_android/` (the admin app),
   never on the repo root. Let Gradle sync, connect a phone with USB debugging, press Run.
   More detail: [ANDROID_STUDIO_SETUP.md](ANDROID_STUDIO_SETUP.md).
5. **GitHub**: commit normally. To push, create a fine-grained or classic personal access token for this one repository,
   push, then delete the token on GitHub.

## 3. The owner account (always superadmin) and how to get back in

`mybrowser.server@gmail.com` is the one permanent superadmin. Other accounts can only be analysts (read-only).
Full explanation: [../firebase/README.md](../firebase/README.md#owner-account-the-one-permanent-superadmin-and-how-to-get-back-in).

**If the owner sign-in was deleted from Firebase Authentication, or you forgot the password:**

1. Open `https://wbrowser-62d56.web.app/admin/` on any computer or phone.
2. Type `mybrowser.server@gmail.com` and tap **Locked out? Email me a sign-in link**.
3. Open the email on the **same browser** and tap the link. The sign-in is re-created (verified) and the account is superadmin again, with all data intact.
4. Open *My account > Owner recovery* and set a password (12+ characters). Now the Android admin app can sign in with email and password too.

Notes:
- The normal "Forgot password" email only works while the sign-in still exists. After a deletion, always use the sign-in link first.
- Firebase's free plan sends sign-in **links**, not numeric OTP codes (codes would need Cloud Functions on the paid plan).
- One-time setting: Firebase Console > Authentication > Sign-in method > Email/Password > "Email link (passwordless sign-in)" must stay **on**.
- From a computer that has the service-account key: `node firebase/scripts/restore-owner.js` re-creates the sign-in, role and record.
- Deleting the sign-in or the `admins` record **cannot** lock the owner out: access is decided by the verified email in
  `firestore.rules` (`isBoss()`), the website and the Android app, not by those records.
- If the owner address ever changes, update it in four places: `firebase/firestore.rules`, `firebase/public/admin/fb.js`,
  `firebase/scripts/set-role.js` + `restore-owner.js`, and `AdminRepo.kt` (`OWNER`).

## 4. Removing everything from a work / shared PC

Do this **after** your work is pushed to GitHub (`git status` clean and `git push` done), otherwise unpushed commits are lost.

1. **Firebase CLI logins** (these hold access tokens):
   ```
   npx firebase login:list                    # shows every signed-in account
   npx firebase logout                        # the main account
   npx firebase logout <other-account-email>  # each extra account
   ```
   If logout fails, delete `%USERPROFILE%\.config\configstore\firebase-tools.json`.
2. **Browser**: sign out of the owner Gmail and the Firebase Console, then clear the site data (or delete that browser profile).
3. **GitHub**: delete any personal access token you used (GitHub > Settings > Developer settings > Personal access tokens).
   Check nothing was stored: `cmdkey /list` (Windows Credential Manager) should show no `git:https://github.com` entry,
   and `git config --global --list` should not contain your personal name or email.
4. **Phone**: Settings > Developer options > *Revoke USB debugging authorizations*, so the old PC is no longer trusted.
   Then `adb kill-server` on the PC.
5. **Project files**: delete the project folder (for example `D:\TempWork\WBroswer`). Also delete
   `%USERPROFILE%\.gradle` (Gradle cache) if this PC will not build Android apps any more.
6. **Assistant notes** (if an AI coding assistant was used on this PC): delete its saved notes and chat history for the project,
   for example `%USERPROFILE%\.claude\projects\<project>\`. Anything that was pasted into a chat (such as a token) stays in that history until deleted,
   so revoke the token as in step 3.
7. **Android emulator images / SDK** hold no account data. Remove them only to free disk space.

After this the only places holding access are: the owner's Gmail, Firebase Authentication (in the cloud), and GitHub.

## 5. Quick safety checklist
- [ ] Latest code pushed (`git log origin/<branch>` shows your last commit)
- [ ] Website and rules deployed (`npm run deploy` finished without errors)
- [ ] Owner can sign in with the emailed link on a different computer
- [ ] All Firebase CLI accounts logged out on the work PC
- [ ] GitHub tokens revoked
