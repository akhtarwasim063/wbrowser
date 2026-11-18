'use strict';
// Creates the Google Drive refresh token for the server. Run once on your PC:
//   GDRIVE_CLIENT_ID=... GDRIVE_CLIENT_SECRET=... node scripts/drive-auth.js
// It opens a loopback listener, you sign in with the Google account whose Drive should hold the backup, and it
// prints GDRIVE_REFRESH_TOKEN. Nothing is stored by this script; put the values in the server's environment.
const http = require('node:http');
const { OAuth2Client } = require('google-auth-library');

const { GDRIVE_CLIENT_ID: id, GDRIVE_CLIENT_SECRET: secret } = process.env;
if (!id || !secret) { console.error('Set GDRIVE_CLIENT_ID and GDRIVE_CLIENT_SECRET first (see tracking_backend/README.md).'); process.exit(1); }

const PORT = 53682;
const redirect = `http://127.0.0.1:${PORT}/callback`;
const client = new OAuth2Client(id, secret, redirect);
const url = client.generateAuthUrl({
  access_type: 'offline', prompt: 'consent',
  scope: ['https://www.googleapis.com/auth/drive.file'], // only files created by this app
});

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, redirect);
  if (u.pathname !== '/callback') { res.writeHead(404).end(); return; }
  const code = u.searchParams.get('code');
  if (!code) { res.end('No code received: ' + (u.searchParams.get('error') || 'unknown')); return; }
  try {
    const { tokens } = await client.getToken(code);
    res.end('Done. You can close this tab and return to the terminal.');
    if (!tokens.refresh_token) console.error('\nNo refresh token returned. Remove the app at https://myaccount.google.com/permissions and run again.');
    else console.log('\nAdd these to the server environment (keep them secret):\n\nGDRIVE_CLIENT_ID=' + id + '\nGDRIVE_CLIENT_SECRET=' + secret + '\nGDRIVE_REFRESH_TOKEN=' + tokens.refresh_token + '\nDRIVE_BACKUP_KEY=<choose a long random passphrase and keep it safe>\n');
  } catch (e) { res.end('Failed: ' + e.message); console.error(e.message); }
  server.close();
});
server.listen(PORT, '127.0.0.1', () => console.log('Open this URL in your browser and approve access:\n\n' + url + '\n'));
