// WBrowser Shield: backup YouTube ad removal. Runs next to uBlock Origin.
// v2: no inline <script> (YouTube's CSP blocked the old version, so it never ran). The page's own JSON.parse / fetch / player-response
// are wrapped through window.wrappedJSObject + exportFunction, and a small DOM sweep skips ads and hides ad slots.
(function () {
  var W = window.wrappedJSObject;
  if (!W) return;

  var KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
  function prune(o) {
    try {
      if (o && typeof o === 'object') {
        var u = (typeof XPCNativeWrapper !== 'undefined' && XPCNativeWrapper.unwrap) ? XPCNativeWrapper.unwrap(o) : o;
        for (var i = 0; i < KEYS.length; i++) if (KEYS[i] in u) { try { delete u[KEYS[i]]; } catch (e) { /* ignore */ } }
      }
    } catch (e) { /* never break the page */ }
    return o;
  }

  // ads are described inside the JSON YouTube loads: remove those parts before the player sees them
  try {
    var parse = W.JSON.parse;
    W.JSON.parse = exportFunction(function (text, reviver) { return prune(parse.call(W.JSON, text, reviver)); }, W);
  } catch (e) { /* ignore */ }
  try {
    var proto = W.Response.prototype, json = proto.json;
    proto.json = exportFunction(function () {
      return json.call(this).then(exportFunction(function (r) { return prune(r); }, W));
    }, W);
  } catch (e) { /* ignore */ }
  try {
    var pr;
    Object.defineProperty(W, 'ytInitialPlayerResponse', {
      configurable: true,
      get: exportFunction(function () { return pr; }, W),
      set: exportFunction(function (v) { pr = prune(v); }, W),
    });
  } catch (e) { /* ignore */ }

  // whatever still gets through: skip it, and hide the slots that are only ads
  var HIDE = ['.ytp-ad-overlay-container', '.ytp-ad-text-overlay', 'ytd-ad-slot-renderer', 'ytd-in-feed-ad-layout-renderer', 'ytd-display-ad-renderer',
    'ytd-promoted-sparkles-web-renderer', 'ytd-promoted-video-renderer', '#masthead-ad', 'ytd-banner-promo-renderer', 'ytd-statement-banner-renderer'].join(',');
  var mutedByUs = false;
  function sweep() {
    try {
      var p = document.getElementById('movie_player');
      var v = p && p.querySelector('video');
      if (p && p.classList.contains('ad-showing')) {
        var skip = p.querySelector('.ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-ad-skip-button-container button');
        if (skip) skip.click();
        if (v && isFinite(v.duration) && v.duration > 0) {
          if (!v.muted) { v.muted = true; mutedByUs = true; }
          v.currentTime = v.duration;               // jump to the end of the ad
        }
      } else if (v && mutedByUs) { v.muted = false; mutedByUs = false; }
      var slots = document.querySelectorAll(HIDE);
      for (var i = 0; i < slots.length; i++) slots[i].style.setProperty('display', 'none', 'important');
    } catch (e) { /* ignore */ }
  }
  setInterval(sweep, 600);
})();
