'use strict';
// Privacy-minimising URL handling. Mirrored on-device (Sanitizer.kt) and re-applied here (never trust the client).

const SEARCH_HOSTS = [
  /(^|\.)google\.[a-z.]+$/, /(^|\.)bing\.com$/, /(^|\.)duckduckgo\.com$/, /(^|\.)startpage\.com$/,
  /(^|\.)ecosia\.org$/, /(^|\.)search\.yahoo\.com$/, /(^|\.)search\.brave\.com$/, /(^|\.)yandex\.[a-z.]+$/,
];
const SENSITIVE_PARAM = /(token|code|secret|passw|pwd|auth|session|sess|sid|key|signature|sig|otp|pin|jwt|bearer|credential|ticket|csrf|nonce|state)/i;

const isSearchHost = (h) => SEARCH_HOSTS.some((r) => r.test(h));

/** Returns {url, domain} or null when the URL must not be stored. */
function sanitizeUrl(raw) {
  if (typeof raw !== 'string' || raw.length === 0 || raw.length > 8192) return null;
  let u;
  try { u = new URL(raw); } catch { return null; }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
  if (!u.hostname) return null;
  u.hash = '';
  u.username = '';
  u.password = '';
  const host = u.hostname.toLowerCase();
  if (isSearchHost(host)) {
    // The query lives in the separate, consent-gated "searches" record; never leak it through the URL.
    u.search = '';
  } else {
    for (const k of [...u.searchParams.keys()]) if (SENSITIVE_PARAM.test(k)) u.searchParams.delete(k);
  }
  const url = u.toString().slice(0, 2048);
  return { url, domain: host.replace(/^www\./, '') };
}

const clampStr = (s, n) => (typeof s === 'string' ? s.replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, n) : '');

module.exports = { sanitizeUrl, clampStr, isSearchHost };
