# HANDOFF: living context for the browser work (UI, playback, YouTube)

> **Read this first. Update it after EVERY change.** It exists so that if a session, a PC or an assistant is lost, a new one can open this file and know
> what was being done, what is finished, what is left, how to do it and which rules apply. Keep it truthful and short per entry.

Last updated: 2026-11-20 (file created; nothing implemented yet)

---------------------------------------------------------------------------------------------------

## 0. How to use / update this file (the protocol)

**Starting a session (new person or assistant):**
1. `git status -sb` and `git branch --show-current`; `git log --oneline -5`. Work only on the right branch (section 3).
2. Read sections 1 to 4, then the workstream section for the branch you are on (section 6), then the Status board (section 9).
3. Continue with the first unchecked task of that workstream. Do not start a second task before the first one is written down in the log.

**After every implemented change (mandatory, takes one minute):**
1. Tick the task `[x]` (or leave `[~]` if half done, and say exactly what is left).
2. Add ONE line to the Change log (section 10): date, branch, task id, what changed, files touched, how it was tested on the phone, commit id.
3. Update "Known issues" / "Open questions" if anything new appeared.
4. Update the "Last updated" line and the Status board counts.
5. Commit the code and this file in the same commit (`git add -A`), message ends with the co-author line (section 2).

**When a NEW feature or idea comes up:**
- Browser feature: add it as a new task id under the right workstream (U = UI, P = playback, Y = YouTube, F = other browser features) using the template in section 7.1.
- Admin feature (admin app and admin website): add it under workstream A using the template in section 7.2. Admin is separate from the browser: different folders, different branch.
- Anything that changes data stored in Firebase touches BOTH sides: see 7.3.

---------------------------------------------------------------------------------------------------

## 1. Project at a glance

WBrowser = an Android browser (GeckoView, with uBlock Origin) that reports browsing activity to Firebase, plus an admin app and an admin website.

| Folder | What it is | Tech |
|---|---|---|
| `native_android/` | **The browser app** `dev.wbrowser.nativeapp` (this file's main subject) | Kotlin, Android Views + XML, GeckoView 160, Firebase Auth + Firestore |
| `admin_android/` | Admin app `dev.wbrowser.admin`, fully native, talks to Firebase directly | Kotlin, Jetpack Compose, Material 3 |
| `firebase/` | Firestore rules, Hosting, the admin website (`public/admin`), privacy/terms pages, PC scripts | Firebase, plain JS |
| `docs/` | Setup and cleanup docs, research | Markdown |

- Firebase project: `wbrowser-62d56`. Admin website: `https://wbrowser-62d56.web.app/admin/`.
- The one permanent superadmin is the owner `mybrowser.server@gmail.com`; recovery is described in `docs/SETUP_AND_CLEANUP.md`.
- Admin design language ("water crystal glass": gradient, drifting light orbs, translucent glass cards, gradient pills) lives in
  `admin_android/.../ui/Theme.kt` + `Glass.kt` and `firebase/public/admin/style.css` (block "v3"). The browser UI work (U tasks) should reuse the same tokens.

---------------------------------------------------------------------------------------------------

## 2. Rules (do not break these)

**GitHub (strict, personal account only)**
- Everything here is personal work. Use ONLY the personal GitHub account of the owner. Never an employer/office identity.
- Commit with a one-off identity, nothing saved in any config:
  `git -c user.name="SAM" -c user.email="102154560+akhtarwasim063@users.noreply.github.com" commit ...`
- Push only when the owner asks, using a token the owner gives for that push:
  `git -c credential.helper= -c "http.extraheader=Authorization: Basic <base64 user:token>" push ...` (remote URL stays plain).
  Never write a token to any file, URL, config, memory or commit. Never `git config --global`. No `gh` login, no SSH keys, no Credential Manager entries.
- After every push verify nothing is left (`git config --local --list`, `cmdkey /list`, `git remote -v`) and remind the owner to revoke the token.
- Never force-push unless asked. End commit messages with: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` (use the line the session gives).
- Full local rules file: `D:\TempWork\GITHUB_RULES.md` (not in the repo).

**Branching**
- `main` = default branch, `production` = stable, `development` = integration, `feature/*` = one branch per piece of work, always created from `development`.
- Flow: feature branch -> pull request into `development` -> test -> `development` -> `production` -> `main`. Details: `README.md` "Branching strategy".

**Testing / running**
- Run and install ONLY on the owner's real phone, never start or use the emulator (`adb -s <serial> ...`, the serial comes from `adb devices`).
- After any code change to the browser or the admin app: build, install and launch BOTH apps on the phone, and check logcat for crashes.
- Background playback, lock screen and PiP cannot be judged on an emulator. Always test on the phone.
- Things that need the owner's login (admin screens with data) are checked by the owner on the phone.

**Never**
- Commit secrets (service-account keys, tokens, `.env`). The Firebase web config in `firebase-config.js` / BuildConfig is public by design.
- Edit a file in a way that fights Android Studio: if Studio has the file open it may overwrite it with an old copy. Reload from disk / close the file when in doubt.
- Deploy rules or hosting without the owner asking. Owner recovery logic (`isBoss()`, `OWNER` constants) must stay in sync (see `docs/SETUP_AND_CLEANUP.md`).

**Shell and tooling quirks on this PC (Windows, Git Bash)**
- Long heredocs with quotes can fail. For big file edits write a script/text file with the Write tool and run it with `node`, or use the Edit tool.
- Some files use CRLF line endings. Scripts that match multi-line text must normalise `\r\n` first.
- Template literals with `${...}` inside `node -e "..."` break in bash; put such code in a file.
- Android Studio / Gradle can wipe `build/outputs`. If `adb install` says the APK is missing, run `./gradlew assembleDebug` again right before installing.
- Web CSP is strict: no inline `<script>`, no `style="..."` attributes written as text (setting `el.style.x` from JS is fine).

---------------------------------------------------------------------------------------------------

## 3. Branches for this work

All created from `development` at `dfe3ccb` (2026-11-20).

| Branch | Workstream |
|---|---|
| `feature/browser-ui` | U: browser look and feel |
| `feature/browser-playback` | P: foreground and background playback, notification, PiP |
| `feature/browser-youtube` | Y: YouTube behaviour and ad shield |
| `feature/browser-features` | F: other new browser features (not started) |
| `feature/browser-account` | account screens (not in the current round) |

The three active streams touch some of the same files (`MainActivity.kt`, `Tabs.kt`). To avoid painful merges:
1. Merge order into `development`: **playback -> youtube -> ui**. After each merge, `git merge development` into the next branch and rebuild.
2. To test the combination on the phone, create a throw-away branch (`git checkout -b test/browser-combined development`, merge the three, build, install) and delete it after.
3. Keep commits small and one task per commit, so conflicts stay small.
4. This file is marked `merge=union` in `.gitattributes`, so two branches adding log lines do not conflict. After a merge, read the file once and remove any duplicated lines.

---------------------------------------------------------------------------------------------------

## 4. Build, run, debug (browser app)

```
cd native_android
./gradlew assembleDebug                       # APK: app/build/outputs/apk/debug/app-debug.apk
adb devices                                   # find the phone's serial
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell monkey -p dev.wbrowser.nativeapp -c android.intent.category.LAUNCHER 1
adb -s <serial> logcat -c ; adb -s <serial> logcat -s WBMedia AndroidRuntime:E     # media/PiP logs use the tag WBMedia
```
Admin app: same from `admin_android/` (package `dev.wbrowser.admin`). Build facts: AGP 9.5.1, Gradle 9.6, compileSdk 37.2, minSdk 26.

---------------------------------------------------------------------------------------------------

## 5. How the browser app works today (facts, verified in code on 2026-11-20)

Package `dev.wbrowser.nativeapp`, folder `native_android/app/src/main/`.

| File | Responsibility |
|---|---|
| `MainActivity.kt` (~980 lines) | The whole browser screen: address bar, bottom bar, tab switcher, menu, find, theater mode, PiP, background-playback glue |
| `Tabs.kt` | `BrowserTab` (one GeckoSession per tab): navigation, desktop mode, media session delegate (`isPlayingMedia`, `toggleMediaPlayback`, `pauseMediaPlayback`, `resumeMediaPlayback`), fullscreen video flag |
| `MediaPlaybackService.kt` | Foreground service (type mediaPlayback), notification with Play/Pause + Stop, wake lock, audio focus. `MediaBridge` connects notification buttons to the playing tab |
| `App.kt` | `Engine`: process-wide GeckoRuntime, settings, installs uBlock Origin from AMO, installs the two built-in extensions below |
| `assets/extensions/wbplayback/keepalive.js` | Makes pages believe they are still visible/focused (overrides `document.hidden`, `visibilityState`, swallows `visibilitychange`/`pagehide`/blur) so players do not pause in the background |
| `assets/extensions/wbshield/youtube.js` | Backup YouTube ad removal: prunes `adPlacements`, `playerAds`, `adSlots`, `adBreakHeartbeatParams` from `JSON.parse`, `Response.json` and `ytInitialPlayerResponse`. Runs next to uBlock Origin. Switch: Settings "YouTube ad shield" (`youtube_shield`) |
| `assets/newtab.html` | New-tab page |
| `Prefs.kt`, `SettingsActivity.kt`, `res/xml/preferences.xml` | Settings screen and keys (theme, text size, search engine, shields, privacy, ...) |
| `AccountActivity.kt`, `Account.kt`, `GoogleLink.kt`, `NameDialog.kt`, `PrivacyActivity.kt` | Account (name, optional Google sign-in) and privacy settings |
| `HistoryActivity.kt`, `HistoryDb.kt` | Local history and bookmarks (SQLite, tables `history` and `bookmarks`; `isBookmarked()` etc.) |
| `Cloud.kt`, `SyncWorker.kt`, `Tracker.kt`, `ActivityDb.kt`, `Sanitizer.kt`, `Remote.kt` | Activity tracking to Firebase: offline queue, sync worker, on-device privacy minimisation, remote config |
| `res/layout/*`, `res/values*/colors.xml`, `themes.xml` | Views UI. Colors today: `wb_primary #4F46E5`, light surfaces `#F8F9FD/#EEF0F8`, plus night variants |

Background/PiP flow today (all in `MainActivity.kt`):
- `onUserLeaveHint()` -> if a tab is playing media or in fullscreen video: `enterMiniPlayer()` (PiP, fixed 16:9, no actions).
- `onPause()` -> if playing and pref `bg_playback` (default true): `keepPageActive()` + `resumeAfterBackgrounding()` (retries at 250/700/1400/2500 ms).
- `onMediaChanged()` -> starts/refreshes `MediaPlaybackService` (title only) while playing; stops it when nothing plays.
- Setting `bg_playback` exists; PiP has no play/pause action; notification has no seek/artwork/metadata beyond the title.

Known weak points to fix (from reading the code, to be confirmed on the phone): retry-timer based resume is fragile; notification is not a real media session
(no lock-screen controls, artwork, seek, next/previous); no audio-focus-based pause/resume policy for calls; PiP aspect ratio is fixed; no PiP controls;
battery-optimisation guidance for aggressive OEMs is missing; UI still uses the older flat theme (not the new glass design).

---------------------------------------------------------------------------------------------------

## 6. Workstreams (tasks are PROPOSED unless marked confirmed; ask the owner if unsure)

Legend: `[ ]` todo, `[~]` in progress / partly done, `[x]` done and tested on the phone.

### 6.U  UI  (branch `feature/browser-ui`)
Goal: the browser looks and feels like the new admin app: crystal/glass, compact, smooth, with working light and dark.
- [ ] U1 Design tokens: add the admin palette (light + dark) to `res/values/colors.xml`, `res/values-night/colors.xml`, `themes.xml`.
- [ ] U2 Glass surfaces for address bar, bottom bar, menu sheet and shield sheet (translucent fill, bright rim stroke, soft shadow; real blur only where supported).
- [ ] U3 New-tab page (`assets/newtab.html`): crystal background, search box, quick links, light/dark aware.
- [ ] U4 Tab switcher: tidy cards, previews, open/close animations.
- [ ] U5 Settings, Account, History, Privacy screens restyled to match (same cards, spacing, headers).
- [ ] U6 Motion: press feedback/ripples, sheet transitions, toolbar hide/show easing (keep 60 fps).
- [ ] U7 Theme switch (Auto/Light/Dark) verified everywhere, status/navigation bar icons follow the theme, edge-to-edge correct.
Acceptance: screenshots on the phone in light and dark; no text unreadable on glass; no jank when scrolling a page.

### 6.P  Playback: foreground and background  (branch `feature/browser-playback`)
Goal: audio/video keeps playing when the app is in the background or the screen is off, with proper controls, and resumes correctly.
- [ ] P1 Baseline on the phone: write the test matrix result (YouTube, another video site, audio-only site) for: Home button, lock screen, PiP, switching tab, incoming call. Record what fails.
- [ ] P2 Real media notification: a MediaSession with MediaStyle notification: title/artist/artwork from Gecko `MediaSession.Metadata`, Play/Pause, Seek, Previous/Next when the page offers them, lock-screen controls, Stop.
- [ ] P3 Audio focus policy: pause on focus loss (call/other app), resume on gain, duck for short interruptions, pause on headphones unplugged (`ACTION_AUDIO_BECOMING_NOISY`), headset/Bluetooth buttons.
- [ ] P4 Foreground service hardening: Android 14+ foreground-service rules and timeouts, safe restart, stop when idle for a while, wake lock only while playing, Wi-Fi lock for streams.
- [ ] P5 Replace timer-based resume with an event-based approach (keepalive.js + session active/focused + media session callbacks); remove the 250/700/1400/2500 ms retries once proven unnecessary.
- [ ] P6 PiP: aspect ratio from the real video size, auto-enter on Android 12+ (`setAutoEnterEnabled`), play/pause (and close) actions in the PiP window, `sourceRectHint`, correct chrome hiding/restoring.
- [ ] P7 Aggressive battery managers (ColorOS/MIUI etc.): optional prompt to exclude the app from battery optimisation, with a short explanation screen.
- [ ] P8 Settings: clear toggles ("Play in background", "Auto mini-player", "Keep playing when screen is off") wired to `Prefs`.
Acceptance: YouTube plays with screen locked for 10+ minutes; notification controls work from the lock screen; a phone call pauses and playback resumes after; PiP controls work.

### 6.Y  YouTube  (branch `feature/browser-youtube`)
Goal: YouTube pages are ad-free as far as possible, background/PiP friendly and comfortable to use. (Depends on P for background behaviour.)
- [ ] Y1 Baseline on the phone: note which ads still appear (pre-roll, mid-roll, banner/overlay, Shorts, skippable button) with uBlock on/off and shield on/off.
- [ ] Y2 Ad shield v2 in `wbshield/youtube.js`: also cover `fetch`/XHR bodies for `/youtubei/v1/player` and `/next`, `ytInitialData` ad slots, auto-click skip buttons, jump to end of an `.ad-showing` video, hide ad containers with CSS. Keep it behind the `youtube_shield` setting.
- [ ] Y3 Background play for YouTube verified end to end together with P2/P5 (title and artwork in the notification come from YouTube's metadata).
- [ ] Y4 Playback comfort: remember position, autoplay behaviour, fullscreen/PiP transitions, handle `m.youtube.com`, `music.youtube.com`, `youtube.com/shorts`.
- [ ] Y5 Quick access: YouTube shortcut on the new-tab page and an optional "YouTube mode" (mobile site default, immersive fullscreen).
- [ ] Y6 Resilience: a way to ship selector/script fixes without a full app release (for example via `Remote.kt` remote config), because YouTube changes often.
Acceptance: no video ads in a 20-minute session on the phone, background and PiP keep playing, the setting really turns the shield off.

### 6.F  Other browser features  (branch `feature/browser-features`, not started)
Candidates (ask the owner): a fuller bookmarks experience (bookmarks already exist in `HistoryDb`; check what the History screen shows), reader mode, downloads manager. Find-in-page and desktop mode already exist.

### 6.A  Admin app + admin website  (separate from the browser; branch per task from `development`)
Current state: admin app has login, Overview, Websites (+detail), Sessions, Users (+detail with long-press multi-select delete), Admins, Audit, Data and storage,
Firebase settings, My account, Settings (theme, default range, auto refresh, web console link); the website has the same pages and the same look; all
confirmations are bottom sheets; custom date range and auto refresh exist in both. Owner recovery is live. New admin tasks go here (template 7.2).

---------------------------------------------------------------------------------------------------

## 7. Templates: adding something new

### 7.1 A new browser task
```
- [ ] <U|P|Y|F><n> <one-line goal>
      Why: <problem it solves>   Where: <files / classes>   Settings key: <Prefs key if any>
      Test on phone: <exact steps>   Depends on: <task ids>
```
Steps: add the task here -> create/choose the branch -> implement small -> build, install, launch both apps on the phone -> tick the task -> add a log line (section 10).
A new setting: add the preference to `res/xml/preferences.xml`, read it through `Prefs` with a default, use a clear title and summary, mention the key in the task.
New JS for pages: `assets/extensions/<ext>/` (+ `manifest.json`), installed in `App.kt` `ensureBuiltIn()`.

### 7.2 A new admin feature (admin app AND website, kept in parity)
1. Data: does it need new Firestore data or rules? If yes, read 7.3 first.
2. Admin app (`admin_android`): add the query/action to `data/AdminRepo.kt` (+ model in `Models.kt`), a screen in `ui/` (use `DataScreen`, `AdminCard`, `GlassButton`, `StatGrid`, bottom-sheet dialogs), a `Route` in `AdminViewModel.kt`, a link in Settings or a tab, and the route in `Shell.kt`.
3. Admin website (`firebase/public/admin`): add the same endpoint to `fb.js` (`api()` facade), a view in `app.js` (add it to `routes`), styles in `style.css` (use the existing glass tokens). No inline script/style (CSP).
4. Rules/tests: update `firebase/firestore.rules` and `firebase/test/rules.test.js` (needs Java + emulator to run: `npm test` in `firebase/`).
5. Check on the phone (admin app) and in a browser (website). Deploy only when the owner asks: `cd firebase && npm run deploy` (rules + hosting) or `npx firebase deploy --only hosting`.

### 7.3 A change to data shared by the browser and the admin
The browser WRITES (`Cloud.kt`, `Sanitizer.kt`, `Tracker.kt`), the rules DECIDE, the admin READS (`AdminRepo.kt` and `fb.js`). Change all three together, in this order:
rules + tests -> browser writer -> admin app reader -> admin website reader -> docs/this file. The Sanitizer rules exist on device and are enforced again by the rules.
Document the field in `firebase/README.md` (data model) and in the log here. Roll out the rules BEFORE shipping a browser build that writes the new field.

---------------------------------------------------------------------------------------------------

## 8. Known issues and open questions (keep short)
- Open: which of F (bookmarks / reader mode / downloads) does the owner want first? (not blocking the current round)
- Open: should the UI work copy the exact admin glass look, or a lighter version for performance on older phones? (decide in U2)
- Note: `firebase/test/rules.test.js` could not be run on the work PC (no Java); run it where Java is available before relying on the rule changes.
- Note: some PC cleanup instructions are in `docs/SETUP_AND_CLEANUP.md`.

---------------------------------------------------------------------------------------------------

## 9. Status board

| Stream | Branch | Tasks | Done | Now working on |
|---|---|---|---|---|
| UI | `feature/browser-ui` | 7 | 0 | nothing started |
| Playback | `feature/browser-playback` | 8 | 0 | nothing started |
| YouTube | `feature/browser-youtube` | 6 | 0 | nothing started |
| Admin | (per task) | n/a | n/a | idle |

---------------------------------------------------------------------------------------------------

## 10. Change log (append only; newest at the bottom)

Format: `YYYY-MM-DD | branch | task | what changed (files) | tested how | commit`

2026-11-20 | development | setup | created this HANDOFF.md and the three work branches (ui, playback, youtube) from `dfe3ccb` | n/a (docs only) | see git log
