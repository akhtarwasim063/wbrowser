'use strict';
// Usage: node scripts/create-admin.js <email> "<name>" <role: superadmin|analyst|viewer>
// The password is read from the ADMIN_PASSWORD env var or prompted (hidden) so it never lands in shell history.
const readline = require('node:readline');
const cfg = require('../src/config');
const { open } = require('../src/db');
const { createAdmin } = require('../src/adminApi');

async function askHidden(prompt) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: true });
  rl._writeToOutput = (s) => { if (s.includes(prompt)) rl.output.write(s); };
  return new Promise((resolve) => rl.question(prompt, (a) => { rl.close(); process.stdout.write('\n'); resolve(a); }));
}

(async () => {
  const [email, name = 'Administrator', role = 'superadmin'] = process.argv.slice(2);
  if (!email) { console.error('Usage: create-admin <email> "<name>" <superadmin|analyst|viewer>'); process.exit(1); }
  const password = process.env.ADMIN_PASSWORD || await askHidden('Password (12+ chars, upper/lower/digit): ');
  const db = open(cfg.dbPath);
  try { createAdmin(db, { email, name, password, role }); console.log(`Created ${role} ${email}`); }
  catch (e) { console.error(e.message); process.exit(1); }
})();
