/// Best-effort YouTube ad removal, injected at document start.
/// Strips ad fields from player JSON and skips any ad that still shows.
/// YouTube changes this regularly, so expect to maintain it.
const youtubeAdScript = r'''
(function () {
  if (!/(^|\.)youtube\.com$/.test(location.hostname)) return;
  var keys = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
  function prune(o) {
    if (o && typeof o === 'object') {
      for (var i = 0; i < keys.length; i++) {
        if (keys[i] in o) { try { delete o[keys[i]]; } catch (e) {} }
      }
    }
    return o;
  }
  var _parse = JSON.parse;
  JSON.parse = function () { return prune(_parse.apply(this, arguments)); };
  if (window.Response && Response.prototype.json) {
    var _json = Response.prototype.json;
    Response.prototype.json = function () { return _json.apply(this, arguments).then(prune); };
  }
  var pr;
  try {
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      configurable: true,
      get: function () { return pr; },
      set: function (v) { pr = prune(v); }
    });
  } catch (e) {}
  setInterval(function () {
    var skip = document.querySelector('.ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern');
    if (skip) skip.click();
    var player = document.querySelector('.html5-video-player.ad-showing');
    var v = document.querySelector('video');
    if (player && v && isFinite(v.duration)) { v.muted = true; v.currentTime = v.duration; }
    var close = document.querySelector('.ytp-ad-overlay-close-button');
    if (close) close.click();
  }, 400);
  var st = document.createElement('style');
  st.textContent = '#player-ads,#masthead-ad,ytd-ad-slot-renderer,ytd-promoted-sparkles-web-renderer,' +
    'ytd-display-ad-renderer,.ytp-ad-overlay-container,.ytp-ad-module,ytd-banner-promo-renderer,' +
    'ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer{display:none!important}';
  document.documentElement.appendChild(st);
})();
''';
