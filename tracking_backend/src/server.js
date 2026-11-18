'use strict';
const fs = require('node:fs');
const cfg = require('./config');
const { open, purgeOld } = require('./db');
const { DriveStore } = require('./driveStore');
const { createUserApi } = require('./userApi');
const { createAdminApi, createAdmin } = require('./adminApi');

async function start() {
  // Optional Google Drive persistence (encrypted backup + auto-restore on an empty host).
  let drive = null;
  try { drive = DriveStore.fromEnv(); } catch (e) { console.error('[drive] disabled:', e.message); }
  if (drive && cfg.dbPath !== ':memory:' && !fs.existsSync(cfg.dbPath)) {
    try { console.log(await drive.restoreTo(cfg.dbPath) ? '[drive] database restored from Google Drive' : '[drive] no backup on Drive yet'); }
    catch (e) { console.error('[drive] restore failed:', e.message); if (cfg.prod) process.exit(1); }
  }

  const db = open(cfg.dbPath);

  if (!db.prepare('SELECT 1 FROM admin_users LIMIT 1').get()) {
    const { ADMIN_BOOTSTRAP_EMAIL: e, ADMIN_BOOTSTRAP_PASSWORD: p } = process.env;
    if (e && p) { createAdmin(db, { email: e, name: 'Administrator', password: p }); console.log('[admin] bootstrap superadmin created:', e); }
    else console.warn('[admin] no admin exists. Run: npm run create-admin');
  }

  const pub = createUserApi(db).listen(cfg.port, () => console.log(`[user-api]  http://0.0.0.0:${cfg.port}`));
  const adm = createAdminApi(db, { drive, driveMinutes: cfg.driveBackupMinutes }).listen(cfg.adminPort, cfg.adminBind, () => console.log(`[admin-app] http://${cfg.adminBind}:${cfg.adminPort}/admin/`));

  const retention = () => { try { purgeOld(db, cfg.retentionDays); } catch (e) { console.error('[retention]', e); } };
  retention();
  const timer = setInterval(retention, 6 * 3600_000); timer.unref();

  const backup = async () => { try { await drive.backup(db); console.log('[drive] backup uploaded'); } catch (e) { console.error('[drive] backup failed:', e.message); } };
  if (drive) {
    console.log(`[drive] enabled, backing up every ${cfg.driveBackupMinutes} min`);
    setTimeout(backup, 10_000).unref();
    setInterval(backup, cfg.driveBackupMinutes * 60_000).unref();
  }

  const stop = async () => { if (drive) await backup(); pub.close(); adm.close(); db.close(); process.exit(0); };
  process.on('SIGINT', stop); process.on('SIGTERM', stop);
}

if (require.main === module) start();
module.exports = { start };
