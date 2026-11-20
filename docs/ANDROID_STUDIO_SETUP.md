# Opening and running the apps in Android Studio

This repo holds **two separate Gradle projects**. Do not open the repo root. Open each app folder in its own Android Studio window:

| App | Open this folder | Module name |
|---|---|---|
| WBrowser | `native_android/` | `WBrowserNative.app` |
| Admin app | `admin_android/` | `WBrowserAdmin.app` |

## Before the first run
1. **JDK**: Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK = JetBrains Runtime 17 or newer.
2. **Android SDK**: `local.properties` is created by Android Studio (`sdk.dir=...`). It is not committed.
3. **Firebase (WBrowser only)**: put your `google-services.json` in `native_android/app/` (it is not committed). See `firebase/README.md`.
4. **Admin app only**: set `ADMIN_URL=https://<project>.web.app/admin/` in `admin_android/gradle.properties`, or pass `-PADMIN_URL=...` to Gradle.
5. Connect a phone with USB debugging, or create an emulator in Device Manager.

## After cloning: sync, then run
1. File > Open the app folder (see table above).
2. Wait for **Gradle sync** to finish (first sync downloads GeckoView and Firebase, so it takes a while).
   If it did not start: File > Sync Project with Gradle Files.
3. The toolbar should now show an **app** run configuration. Pick a device and press Run.

## If the toolbar shows "Add Configuration..." (Run is disabled)
Android Studio normally creates the `app` configuration after a successful sync. If it did not, create it by hand.

### Option A: in the IDE
1. Make sure Gradle sync finished without errors (a failed sync is the usual cause).
2. Run > Edit Configurations.
3. Click **+** and choose **Android App**.
4. Name: `app`. Module: choose `<project>.app` (see the table above).
5. Launch Options: Launch = *Default Activity*.
6. OK. Select a device and press Run.

### Option B: copy a ready-made file
The configurations are saved in this repo under `docs/android-studio/`:

| Copy this file | To this path |
|---|---|
| `docs/android-studio/native_android-app.xml` | `native_android/.idea/runConfigurations/app.xml` |
| `docs/android-studio/admin_android-app.xml` | `admin_android/.idea/runConfigurations/app.xml` |

Create the `runConfigurations` folder if it does not exist. Close and reopen the project, then sync Gradle.
`.idea/` is git-ignored on purpose (local IDE settings), which is why the files are kept here.

## Still disabled?
- Build tab shows a red error: the sync failed. Fix that error first (wrong Gradle JDK, missing `google-services.json`, no internet).
- No device in the device dropdown: connect a phone or create an emulator.
- Try File > Invalidate Caches > Invalidate and Restart.
