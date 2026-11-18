// WBrowser Shield: backup YouTube ad removal. Runs next to uBlock Origin.
// The page-world code is injected so it can wrap JSON.parse / fetch before YouTube's player runs.
(function () {
  var code = '(' + function () {
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
  } + ')();';

  var s = document.createElement('script');
  s.textContent = code;
  (document.head || document.documentElement).appendChild(s);
  s.remove();

  // Content-script side: skip whatever ad still plays, hide ad containers.
  function tick() {
    var skip = document.querySelector('.ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern');
    if (skip) skip.click();
    var player = document.querySelector('.html5-video-player.ad-showing');
    var v = document.querySelector('video');
    if (player && v && isFinite(v.duration)) { v.muted = true; v.currentTime = v.duration; }
    var close = document.querySelector('.ytp-ad-overlay-close-button');
    if (close) close.click();
  }
  setInterval(tick, 400);

  var st = document.createElement('style');
  st.textContent = '#player-ads,#masthead-ad,ytd-ad-slot-renderer,ytd-promoted-sparkles-web-renderer,' +
    'ytd-display-ad-renderer,.ytp-ad-overlay-container,.ytp-ad-module,ytd-banner-promo-renderer,' +
    'ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,ytd-in-feed-ad-layout-renderer,' +
    'ytd-ad-slot-renderer,ytm-promoted-video-renderer{display:none!important}';
  (document.head || document.documentElement).appendChild(st);
})();
