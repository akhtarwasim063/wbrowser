# WBrowser

Flutter browser with ad/tracker blocking, Brave-style shield toggle, history and Firebase sync.

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

## Run
    flutter run                      # Android device/emulator
    flutter build apk --debug

## Activity tracking + Admin App
The native Android app (`native_android/`) reports browsing activity (automatic background sync with an offline queue)
to **Firebase** (Auth + Firestore). Admins use the separate Admin App: a web page on Firebase Hosting
(`firebase/public/admin`) and an Android wrapper (`admin_android/`). Setup is in `firebase/README.md`; legal/compliance
notes are in `docs/RESEARCH_AND_COMPLIANCE.md`. The older Node/SQLite backend in `tracking_backend/` is kept as an
alternative. The section below is for the separate Flutter app in `lib/`.

## Firebase history sync (Flutter app only, one-time)
1. Create a Firebase project; enable **Authentication -> Email/Password** and **Firestore**.
2. `dart pub global activate flutterfire_cli`, then `flutterfire configure` in this folder.
   This overwrites `lib/firebase_options.dart`. Without it the app runs with local history only.
3. Firestore rules (each user can only touch their own history):

       rules_version = '2';
       service cloud.firestore {
         match /databases/{db}/documents {
           match /users/{uid}/history/{doc} {
             allow read, write: if request.auth != null && request.auth.uid == uid;
           }
         }
       }

Data model: `users/{uid}/history/{id}` = `{url, title, visitedAt, deleted}`.
Deleting a row sets `deleted: true` so other devices drop it too.

## Layout
- `lib/blocker/filter_parser.dart`  ABP filter list -> WebView content-blocker rules + cosmetic CSS
- `lib/blocker/blocker_service.dart` downloads EasyList / EasyPrivacy / uBlock filters, caches, rebuilds
- `lib/blocker/youtube_script.dart`  YouTube ad script (fragile, will need upkeep)
- `lib/history/`                    SQLite store + Firestore sync
- `lib/ui/`                         browser, history, settings pages
