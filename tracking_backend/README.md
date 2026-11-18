# WBrowser activity backend and Admin App

Two listeners in one process. They share only the database file.

| | User API | Admin API and Admin App |
|---|---|---|
| Port | `PORT` (4000), public | `ADMIN_PORT` (4001), bound to `ADMIN_BIND` (127.0.0.1) |
| Who | WBrowser Android app | Admins in a browser |
| Auth | Per-device secret (`Authorization: Bearer <userId>.<secret>`), only its SHA-256 is stored | Email and password (bcrypt) in `admin_users`, JWT in an HttpOnly, SameSite=Strict cookie signed with `ADMIN_JWT_SECRET` |
| Routes | `/api/v1/*` | `/admin/api/*`, UI at `/admin/` |

The user app has no admin URL, credential or code. Put the admin listener behind a VPN or an IP allow-list.

## Run

    cd tracking_backend
    npm install
    # first admin (password prompted, or ADMIN_PASSWORD env):
    npm run create-admin -- admin@example.com "Your Name" superadmin
    npm start
    # Admin App: http://127.0.0.1:4001/admin/

Production env: `NODE_ENV=production`, `ADMIN_JWT_SECRET` (48+ random bytes), `DB_PATH`, `GOOGLE_WEB_CLIENT_ID` (optional), `RETENTION_DAYS`, `TRUST_PROXY` (when behind a reverse proxy). Terminate TLS at a reverse proxy. Requires Node 22.13+ (uses built-in `node:sqlite`).

Android build:

    cd native_android
    gradlew assembleRelease -PTRACKING_API_URL=https://api.example.com -PGOOGLE_WEB_CLIENT_ID=<web-client-id>

Release builds refuse a non-https API URL. For an emulator, the default is `http://10.0.2.2:4000`.

## Roles

| Role | Can do |
|---|---|
| viewer | Aggregates only: overview, website analytics, session stats. No per-user URLs or queries. |
| analyst | viewer plus user list, per-user history, sessions, searches, CSV export. |
| superadmin | analyst plus suspend or erase users, manage admins, read the audit log. |

Every per-user read, export, erase and admin change is written to `audit_log`.

## User API

- `POST /api/v1/register` is idempotent. The device generates `userId` (`WB-` plus 16 chars) and the secret.
- `POST /api/v1/events` takes up to 200 events (`visit`, `search`, `session`) with stable IDs, so retries are safe. Searches are dropped unless consent is stored.
- `POST /api/v1/consent`, `POST /api/v1/profile`, `POST|DELETE /api/v1/link-google`
- `GET /api/v1/me/export`, `DELETE /api/v1/me`

## Tests

    npm test

Covers sanitising, idempotent ingest, consent gating, auth, RBAC, CSRF header, lockout and last-superadmin protection.

## Google Drive storage (optional)

Drive is not a server: it cannot run the API or act as a live database (no queries or transactions, strict rate limits).
So the Node server stays the API and Drive keeps an **encrypted copy of the database** (AES-256-GCM, key = `DRIVE_BACKUP_KEY`,
which never leaves your server). Every `DRIVE_BACKUP_MINUTES` (default 15) and on shutdown the server uploads a snapshot.
If the server starts with no database file (new host, wiped disk) it restores the latest copy automatically.
Scope is `drive.file`: the app can only see the one file it created.

### Get the token (one time, about 5 minutes)
1. https://console.cloud.google.com > create or pick a project > **APIs & Services > Library** > enable **Google Drive API**.
2. **APIs & Services > OAuth consent screen**: user type *External*, fill the app name and your email, add scope
   `.../auth/drive.file`. Under **Audience**, click **Publish app** (otherwise refresh tokens expire after 7 days;
   `drive.file` is a non-sensitive scope, so no Google review is needed).
3. **Credentials > Create credentials > OAuth client ID > Application type: Desktop app**. Copy the client ID and secret.
4. On your PC:

       cd tracking_backend
       set GDRIVE_CLIENT_ID=<id>& set GDRIVE_CLIENT_SECRET=<secret>& node scripts/drive-auth.js     (Windows cmd)

   Open the printed URL, sign in with the Google account whose Drive should hold the backup, approve. The terminal prints
   `GDRIVE_REFRESH_TOKEN=...`.
5. Start the server with these environment variables (never commit them):

       GDRIVE_CLIENT_ID, GDRIVE_CLIENT_SECRET, GDRIVE_REFRESH_TOKEN,
       DRIVE_BACKUP_KEY=<long random passphrase; keep a copy, without it backups cannot be decrypted>
       optional: GDRIVE_FOLDER_ID, DRIVE_BACKUP_MINUTES

The "Server and data" page in the Admin App shows the last backup time and has a **Back up now** button (superadmin).

## Admin Android app

`../admin_android/` is a separate app (`dev.wbrowser.admin`, "WBrowser Admin") that shows the Admin web app natively.
It shares no code or credentials with the browser app. Build and install:

    cd admin_android
    gradlew installDebug
    adb reverse tcp:4001 tcp:4001      (debug default: http://127.0.0.1:4001/admin/)

For a real server: `gradlew assembleRelease -PADMIN_URL=https://admin.example.com/admin/` (release builds require https
and block screenshots). The address can also be changed in the app on the error screen.
