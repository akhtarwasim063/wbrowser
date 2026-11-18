'use strict';
// Google Drive persistence for the SQLite database.
//
// Drive cannot be a live database or run the API (no queries, transactions, or concurrent writers, and strict
// rate limits), so the Node server stays the API and Drive holds an ENCRYPTED copy of the database:
//   - every DRIVE_BACKUP_MINUTES (and on shutdown) the DB is snapshotted, encrypted, uploaded
//   - if the server starts with no local DB (new host, wiped disk) the latest copy is downloaded and restored
// Access uses the narrow `drive.file` scope (only files this app created) with an OAuth refresh token.
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { OAuth2Client } = require('google-auth-library');

const MAGIC = Buffer.from('WBK1');
const API = 'https://www.googleapis.com/drive/v3';
const UPLOAD = 'https://www.googleapis.com/upload/drive/v3';

function encrypt(plain, passphrase) {
  const salt = crypto.randomBytes(16), iv = crypto.randomBytes(12);
  const key = crypto.scryptSync(passphrase, salt, 32);
  const c = crypto.createCipheriv('aes-256-gcm', key, iv);
  const body = Buffer.concat([c.update(plain), c.final()]);
  return Buffer.concat([MAGIC, salt, iv, c.getAuthTag(), body]);
}

function decrypt(blob, passphrase) {
  if (blob.length < 48 || !blob.subarray(0, 4).equals(MAGIC)) throw new Error('Not a WBrowser backup');
  const salt = blob.subarray(4, 20), iv = blob.subarray(20, 32), tag = blob.subarray(32, 48);
  const d = crypto.createDecipheriv('aes-256-gcm', crypto.scryptSync(passphrase, salt, 32), iv);
  d.setAuthTag(tag);
  try { return Buffer.concat([d.update(blob.subarray(48)), d.final()]); }
  catch { throw new Error('Backup decryption failed (wrong DRIVE_BACKUP_KEY or corrupted file)'); }
}

class DriveStore {
  constructor({ clientId, clientSecret, refreshToken, folderId, backupKey, fileName = 'wbrowser-backup.enc', getToken, fetchImpl }) {
    if (!backupKey || backupKey.length < 12) throw new Error('DRIVE_BACKUP_KEY (12+ chars) is required to encrypt backups');
    this.folderId = folderId || null;
    this.key = backupKey;
    this.fileName = fileName;
    this.f = fetchImpl || fetch;
    if (getToken) this.getToken = getToken;
    else {
      const oauth = new OAuth2Client(clientId, clientSecret);
      oauth.setCredentials({ refresh_token: refreshToken });
      this.getToken = async () => (await oauth.getAccessToken()).token;
    }
    this.status = { lastBackupAt: null, lastError: null, fileId: null, bytes: null };
  }

  static fromEnv(env = process.env) {
    if (!env.GDRIVE_REFRESH_TOKEN) return null;
    return new DriveStore({
      clientId: env.GDRIVE_CLIENT_ID, clientSecret: env.GDRIVE_CLIENT_SECRET, refreshToken: env.GDRIVE_REFRESH_TOKEN,
      folderId: env.GDRIVE_FOLDER_ID, backupKey: env.DRIVE_BACKUP_KEY,
    });
  }

  async req(url, opts = {}) {
    const r = await this.f(url, { ...opts, headers: { ...(opts.headers || {}), authorization: `Bearer ${await this.getToken()}` } });
    if (!r.ok) throw new Error(`Drive ${r.status}: ${(await r.text()).slice(0, 200)}`);
    return r;
  }

  async findFile() {
    const q = `name='${this.fileName}' and trashed=false` + (this.folderId ? ` and '${this.folderId}' in parents` : '');
    const r = await this.req(`${API}/files?q=${encodeURIComponent(q)}&fields=files(id,modifiedTime)&orderBy=modifiedTime desc&pageSize=1&spaces=drive`);
    return (await r.json()).files[0] || null;
  }

  async upload(buf) {
    const existing = await this.findFile();
    if (existing) {
      await this.req(`${UPLOAD}/files/${existing.id}?uploadType=media`, { method: 'PATCH', headers: { 'content-type': 'application/octet-stream' }, body: buf });
      return existing.id;
    }
    const boundary = 'wb' + crypto.randomBytes(12).toString('hex');
    const meta = { name: this.fileName, ...(this.folderId ? { parents: [this.folderId] } : {}) };
    const body = Buffer.concat([
      Buffer.from(`--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(meta)}\r\n--${boundary}\r\nContent-Type: application/octet-stream\r\n\r\n`),
      buf, Buffer.from(`\r\n--${boundary}--`),
    ]);
    const r = await this.req(`${UPLOAD}/files?uploadType=multipart&fields=id`, { method: 'POST', headers: { 'content-type': `multipart/related; boundary=${boundary}` }, body });
    return (await r.json()).id;
  }

  /** Snapshot (consistent even while the server writes), encrypt, upload. */
  async backup(db) {
    const tmp = path.join(os.tmpdir(), `wb-snap-${process.pid}-${Date.now()}.db`);
    try {
      db.exec(`VACUUM INTO '${tmp.replace(/'/g, "''")}'`);
      const blob = encrypt(fs.readFileSync(tmp), this.key);
      this.status.fileId = await this.upload(blob);
      this.status.lastBackupAt = Date.now(); this.status.lastError = null; this.status.bytes = blob.length;
    } catch (e) {
      this.status.lastError = e.message;
      throw e;
    } finally { fs.rmSync(tmp, { force: true }); }
  }

  /** Writes the latest backup to destPath. Returns false when Drive has none. */
  async restoreTo(destPath) {
    const f = await this.findFile();
    if (!f) return false;
    const r = await this.req(`${API}/files/${f.id}?alt=media`);
    const plain = decrypt(Buffer.from(await r.arrayBuffer()), this.key);
    fs.mkdirSync(path.dirname(destPath), { recursive: true });
    fs.writeFileSync(destPath, plain);
    return true;
  }
}

module.exports = { DriveStore, encrypt, decrypt };
