'use strict';
const crypto = require('node:crypto');
const path = require('node:path');

const prod = process.env.NODE_ENV === 'production';
const int = (v, d) => (v === undefined || v === '' ? d : parseInt(v, 10));

const cfg = {
  prod,
  // Public listener: ONLY the user-app ingestion API lives here.
  port: int(process.env.PORT, 4000),
  // Admin listener: ONLY the admin API + admin web app. Bind to localhost / VPN, never expose with the user API.
  adminPort: int(process.env.ADMIN_PORT, 4001),
  adminBind: process.env.ADMIN_BIND || '127.0.0.1',
  dbPath: process.env.DB_PATH || path.join(__dirname, '..', 'data', 'wbrowser.db'),
  // Used ONLY to sign admin sessions. Never shared with / known to the user app.
  adminJwtSecret: process.env.ADMIN_JWT_SECRET || '',
  adminSessionHours: int(process.env.ADMIN_SESSION_HOURS, 8),
  cookieSecure: process.env.COOKIE_SECURE ? process.env.COOKIE_SECURE === 'true' : prod,
  googleWebClientId: process.env.GOOGLE_WEB_CLIENT_ID || '',
  loginRateLimit: int(process.env.LOGIN_RATE_LIMIT, 10), // per IP per 15 min (account lockout is separate)
  driveBackupMinutes: Math.max(1, int(process.env.DRIVE_BACKUP_MINUTES, 15)),
  retentionDays: int(process.env.RETENTION_DAYS, 180),
  consentVersion: 1,
  trustProxy: process.env.TRUST_PROXY || false,
};

if (!cfg.adminJwtSecret) {
  if (prod) throw new Error('ADMIN_JWT_SECRET is required in production (use 48+ random bytes).');
  cfg.adminJwtSecret = crypto.randomBytes(48).toString('hex'); // dev: sessions reset on restart
  console.warn('[config] ADMIN_JWT_SECRET not set; using an ephemeral dev secret.');
}
if (prod && cfg.adminJwtSecret.length < 32) throw new Error('ADMIN_JWT_SECRET too short.');
module.exports = cfg;
