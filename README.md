# WBrowser

Android browser (GeckoView) with ad/tracker blocking, background playback, PiP mini-player, history and Firebase sync, plus an admin app and web dashboard.

## Repository ownership

This is a personal project. It is committed and pushed only with the personal GitHub account
(`akhtarwasim063`), never with any office/work identity, per the owner's machine-wide policy recorded in
`D:\TempWork\GITHUB_RULES.md` (not part of this repo; a local note for the owner's own future reference).

## Branching strategy

| Branch | Purpose |
|---|---|
| `production` | Stable, release-ready code only. Nothing is committed here directly. |
| `development` | Integration branch. All new work is merged here first and tested. |
| `feature/<name>` | One branch per new feature or fix, always created from `development`. |
| `main` | Default GitHub branch. Kept in sync with `production`. |

Workflow:
1. `git checkout development && git pull`
2. `git checkout -b feature/<name>`: write the code, commit.
3. Merge the feature into `development` (pull request), test it.
4. When `development` is stable, merge it into `production`, then fast-forward `main` to `production`.
5. Hotfixes: branch from `production` as `hotfix/<name>`, merge into `production` and back into `development`.

Current feature branch: `feature/background-playback-pip` (background playback, PiP mini-player).

## Project layout
| Folder | What it is | Tech |
|---|---|---|
| `native_android/` | The WBrowser app (`dev.wbrowser.nativeapp`) | Kotlin, Gradle, GeckoView, Firebase Auth + Firestore |
| `admin_android/` | Admin Android app (`dev.wbrowser.admin`), fully native, talks to Firebase directly (the web console in `firebase/public/admin` still works too) | Kotlin, Jetpack Compose, Firebase Auth + Firestore |
| `firebase/` | Backend: Firestore rules, Hosting, admin web page (`public/admin`), privacy/terms pages, helper scripts, `legal/` source | Firebase, Node.js |
| `docs/` | Research and compliance notes | Markdown |

## Run
    cd native_android && gradlew installDebug
    cd admin_android  && gradlew installDebug

Android Studio steps (which folder to open, run configuration): `docs/ANDROID_STUDIO_SETUP.md`. Firebase setup is in `firebase/README.md`. Compliance notes are in `docs/RESEARCH_AND_COMPLIANCE.md`.
The earlier Flutter prototype and the Node/SQLite backend were removed (still in git history before the cleanup commit).

## Accounts, new computers and cleanup
- Work from any computer, owner-account recovery (emailed sign-in link) and how to remove everything from a work PC: `docs/SETUP_AND_CLEANUP.md`.
- `mybrowser.server@gmail.com` is the one permanent superadmin. If its sign-in is ever deleted, open the web admin and tap **Locked out? Email me a sign-in link**.
