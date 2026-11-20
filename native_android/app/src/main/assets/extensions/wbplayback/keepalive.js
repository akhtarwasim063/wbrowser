// WBrowser background playback + mini-player layout.
//
// 1) KEEP-ALIVE. When the app is minimised or the screen locks, the browser tells pages they are hidden and players
//    like YouTube, Netflix, Twitch or Hotstar pause themselves. We keep the page believing it is still visible and focused
//    (the same idea as Brave's "background video playback").
// 2) MINI-PLAYER (PiP) LAYOUT. Android's picture-in-picture window is just the app shrunk to a tiny window, so it would show
//    a squashed web page. When the window is that small we make the playing <video> fill it and hide everything else.
//
// IMPORTANT: this runs as an extension content script and talks to the page through window.wrappedJSObject / exportFunction.
// It must NOT inject inline <script> text: YouTube's Content-Security-Policy blocks that, which silently disabled the old version.
(function () {
  var W = window.wrappedJSObject;
  if (!W) return;

  // ---------------------------------------------------------------- 1) keep-alive
  // Debug switch (off in releases). Turn it on, rebuild, run tools/bgtest/server.js and `adb reverse tcp:8765 tcp:8765`: every page then reports
  // which events reach it right before it pauses itself. Used to find out why a site pauses in the background.
  var WB_DEBUG = false;
  if (WB_DEBUG) {
    var dbg = function (m) { try { navigator.sendBeacon('http://localhost:8765/log', 'WBPLAY ' + location.host + ' ' + m); } catch (e) { /* ignore */ } };
    ['visibilitychange', 'blur', 'focus', 'pagehide', 'pageshow', 'freeze', 'resume', 'resize', 'fullscreenchange'].forEach(function (t) { window.addEventListener(t, function () { dbg('event ' + t + ' hidden=' + document.hidden + ' focus=' + document.hasFocus() + ' h=' + window.innerHeight); }, true); });
    ['pause', 'play'].forEach(function (t) { document.addEventListener(t, function (e) { var v = e.target; if (v && v.tagName === 'VIDEO') dbg('video ' + t + ' t=' + v.currentTime.toFixed(1) + ' hidden=' + document.hidden + ' vs=' + document.visibilityState + ' focus=' + document.hasFocus()); }, true); });
  }

  function getter(proto, key, fn) {
    try { Object.defineProperty(proto, key, { configurable: true, get: exportFunction(fn, W) }); } catch (e) { /* ignore */ }
  }
  try {
    var DP = W.Document.prototype;
    getter(DP, 'hidden', function () { return false; });
    getter(DP, 'webkitHidden', function () { return false; });
    getter(DP, 'visibilityState', function () { return 'visible'; });
    getter(DP, 'webkitVisibilityState', function () { return 'visible'; });
    DP.hasFocus = exportFunction(function () { return true; }, W);
  } catch (e) { /* ignore */ }

  // Our listeners are registered at document_start, before any page script, so stopping propagation hides these events from the page.
  var stop = function (e) { e.stopImmediatePropagation(); };
  ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'freeze'].forEach(function (t) {
    document.addEventListener(t, stop, true);
    window.addEventListener(t, stop, true);
  });
  // only swallow focus loss of the window/document itself, never of form fields
  var stopWin = function (e) { if (e.target === window || e.target === document) e.stopImmediatePropagation(); };
  window.addEventListener('blur', stopWin, true);
  document.addEventListener('blur', stopWin, true);

  // ---------------------------------------------------------------- 2) mini-player layout (top frame only)
  if (window !== window.top) return;

  var PIP_MAX_HEIGHT = 270;          // the PiP window is ~110-240 css px high; a landscape phone is 350+
  var active = false, saved = [], applying = false, observer = null, retry = 0, tryTimer = 0;
  var VIDEO_STYLE = {
    visibility: 'visible', position: 'fixed', left: '0', top: '0', right: 'auto', bottom: 'auto', width: '100vw', height: '100vh',
    'max-width': 'none', 'max-height': 'none', 'min-width': '0', 'min-height': '0', margin: '0', padding: '0',
    'z-index': '2147483647', 'object-fit': 'contain', background: '#000', transform: 'none', opacity: '1', 'pointer-events': 'none',
  };
  var ANCESTOR_STYLE = {
    transform: 'none', filter: 'none', perspective: 'none', contain: 'none', 'will-change': 'auto', overflow: 'visible',
    'clip-path': 'none', opacity: '1', '-webkit-backdrop-filter': 'none', 'backdrop-filter': 'none', 'mix-blend-mode': 'normal',
  };

  function pickVideo() {
    var best = null, score = -1;
    var vs = document.getElementsByTagName('video');
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i];
      var playing = !v.paused && !v.ended && v.readyState > 1;
      var s = (playing ? 1e9 : 0) + (v.videoWidth || 0) * (v.videoHeight || 0) + (v.currentTime > 0 ? 1 : 0);
      if (s > score) { score = s; best = v; }
    }
    return best;
  }

  function put(el, props) {
    for (var k in props) el.style.setProperty(k, props[k], 'important');
  }
  function remember(el) { saved.push([el, el.getAttribute('style')]); }

  function reapply(video) {
    if (applying) return;
    applying = true;
    try { put(video, VIDEO_STYLE); } finally { applying = false; }
  }

  function enter() {
    if (active) return true;
    var v = pickVideo();
    if (!v) return false;
    active = true;
    remember(document.documentElement); remember(document.body);
    put(document.documentElement, { visibility: 'hidden', background: '#000', overflow: 'hidden' });
    put(document.body, { background: '#000', overflow: 'hidden' });
    // the page is hidden; only the video is made visible again, so nothing else shows. Ancestors must not trap a position:fixed child.
    for (var n = v.parentElement; n && n !== document.documentElement; n = n.parentElement) { remember(n); put(n, ANCESTOR_STYLE); }
    remember(v);
    reapply(v);
    // players keep rewriting the video's inline style while they resize: put ours back
    var intact = function () {
      var s = v.style;
      return s.getPropertyValue('position') === 'fixed' && s.getPropertyPriority('position') === 'important' &&
        s.getPropertyValue('width') === '100vw' && s.getPropertyPriority('width') === 'important' &&
        s.getPropertyValue('height') === '100vh' && s.getPropertyPriority('height') === 'important';
    };
    observer = new MutationObserver(function () { if (active && !intact()) reapply(v); });
    observer.observe(v, { attributes: true, attributeFilter: ['style', 'class'] });
    try { window.dispatchEvent(new Event('resize')); } catch (e) { /* ignore */ }
    return true;
  }

  function leave() {
    if (!active) return;
    active = false;
    if (observer) { observer.disconnect(); observer = null; }
    for (var i = saved.length - 1; i >= 0; i--) {
      var el = saved[i][0], old = saved[i][1];
      if (old === null) el.removeAttribute('style'); else el.setAttribute('style', old);
    }
    saved = [];
    try { window.dispatchEvent(new Event('resize')); } catch (e) { /* ignore */ }
  }

  function check() {
    var small = window.innerHeight < PIP_MAX_HEIGHT && window.innerWidth > window.innerHeight;
    clearTimeout(tryTimer);
    if (small && !active) {
      if (!enter() && retry++ < 8) tryTimer = setTimeout(check, 400);   // video may not be ready the instant the window shrinks
    } else if (!small) {
      retry = 0;
      if (active) leave();
    }
  }
  window.addEventListener('resize', check, true);
})();
