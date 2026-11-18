'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { open } = require('../src/db');
const { DriveStore, encrypt, decrypt } = require('../src/driveStore');

// Minimal in-memory imitation of the Drive v3 endpoints used by DriveStore.
function fakeDrive() {
  const files = new Map(); let n = 0; const calls = [];
  const res = (status, body) => ({ ok: status < 300, status, json: async () => body, text: async () => JSON.stringify(body), arrayBuffer: async () => Buffer.from(body).buffer.slice(Buffer.from(body).byteOffset, Buffer.from(body).byteOffset + Buffer.from(body).length) });
  const f = async (url, opts = {}) => {
    const u = new URL(url); const m = opts.method || 'GET';
    calls.push(`${m} ${u.pathname}`);
    assert.equal(opts.headers.authorization, 'Bearer tok');
    if (m === 'GET' && u.pathname === '/drive/v3/files') {
      const name = /name='([^']+)'/.exec(u.searchParams.get('q'))[1];
      return res(200, { files: [...files.entries()].filter(([, v]) => v.name === name).map(([id]) => ({ id })) });
    }
    if (m === 'POST' && u.pathname === '/upload/drive/v3/files') {
      const body = Buffer.from(opts.body); const text = body.toString('latin1');
      const name = JSON.parse(/\r\n\r\n(\{.*\})\r\n--/.exec(text)[1]).name;
      const start = text.indexOf('octet-stream\r\n\r\n') + 16, end = text.lastIndexOf('\r\n--');
      const id = 'f' + (++n); files.set(id, { name, data: body.subarray(start, end) });
      return res(200, { id });
    }
    if (m === 'PATCH') { const id = u.pathname.split('/').pop(); files.get(id).data = Buffer.from(opts.body); return res(200, { id }); }
    if (m === 'GET' && u.searchParams.get('alt') === 'media') return res(200, files.get(u.pathname.split('/').pop()).data);
    return res(404, {});
  };
  return { f, files, calls };
}

test('encrypt/decrypt roundtrip, tamper and wrong key are rejected', () => {
  const blob = encrypt(Buffer.from('secret-data'), 'passphrase-123456');
  assert.equal(decrypt(blob, 'passphrase-123456').toString(), 'secret-data');
  assert.throws(() => decrypt(blob, 'wrong-passphrase-1'), /decryption failed/);
  blob[blob.length - 1] ^= 1;
  assert.throws(() => decrypt(blob, 'passphrase-123456'), /decryption failed/);
  assert.equal(blob.includes('secret-data'), false);
});

test('backup uploads encrypted snapshot, updates same file, restore recreates the database', async () => {
  const d = fakeDrive();
  const store = new DriveStore({ backupKey: 'a-long-backup-passphrase', getToken: async () => 'tok', fetchImpl: d.f });
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'wbdrive-'));
  const src = open(path.join(tmp, 'a.db'));
  src.prepare("INSERT INTO users(id,secret_hash,name,created_at) VALUES('WB-AAAAAAAAAAAAAAAA','h','Drive User',1)").run();

  await store.backup(src);
  assert.equal(d.files.size, 1);
  const stored = [...d.files.values()][0].data;
  assert.equal(stored.includes('Drive User'), false, 'backup must be encrypted');

  src.prepare("INSERT INTO users(id,secret_hash,name,created_at) VALUES('WB-BBBBBBBBBBBBBBBB','h','Second',2)").run();
  await store.backup(src);
  assert.equal(d.files.size, 1, 'second backup updates the existing file');
  assert.ok(d.calls.includes('PATCH /upload/drive/v3/files/f1'));
  assert.ok(store.status.lastBackupAt);

  const dest = path.join(tmp, 'restored', 'b.db');
  assert.equal(await store.restoreTo(dest), true);
  const restored = open(dest);
  assert.deepEqual(restored.prepare('SELECT name FROM users ORDER BY created_at').all().map((r) => r.name), ['Drive User', 'Second']);

  const empty = new DriveStore({ backupKey: 'a-long-backup-passphrase', getToken: async () => 'tok', fetchImpl: fakeDrive().f });
  assert.equal(await empty.restoreTo(path.join(tmp, 'none.db')), false);
  assert.throws(() => new DriveStore({ backupKey: 'short', getToken: async () => 'x' }), /DRIVE_BACKUP_KEY/);
  src.close(); restored.close(); fs.rmSync(tmp, { recursive: true, force: true });
});
