// YouTube behaves like the YouTube app instead of a web page (youtube.com, top frame only):
//   1) DOCKED PLAYER   In portrait, when you scroll down the watch page the player (with its controls) stays pinned under the top bar
//                      and the list scrolls underneath, like the YouTube app.
//   2) SWIPE UP        Swipe up on the player in portrait = full screen. Swipe down in full screen = leave it.
//   3) PINCH           In full screen, pinch out = fill the screen (crop), pinch in = fit the whole video.
// Rotating the phone, the toolbars and the app-level behaviour are handled natively in MainActivity.
// Runs as an extension content script: it only touches the DOM (no inline scripts, which YouTube's CSP blocks).
(function () {
  if (window !== window.top) return;

  // Debug switch (off in releases): reports to tools/bgtest/server.js (see HANDOFF section 4)
  var WB_DEBUG = false;
  var dbgBox = null, tmsg = '';
  var dbg = function (m) {
    if (!WB_DEBUG) return;
    try {
      if (!dbgBox) { dbgBox = document.createElement('div'); dbgBox.style.cssText = 'position:fixed;left:0;bottom:0;z-index:2147483647;background:rgba(0,0,0,.75);color:#0f0;font:10px monospace;padding:2px 4px;pointer-events:none;max-width:100%'; document.documentElement.appendChild(dbgBox); }
      dbgBox.textContent = m + ' || ' + tmsg;
    } catch (e) { /* ignore */ }
  };
  var player = function () { return document.getElementById('movie_player'); };
  var video = function () { var p = player(); return (p && p.querySelector('video')) || document.querySelector('video'); };
  var watching = function () { return location.pathname === '/watch'; };
  var portrait = function () { return window.innerHeight >= window.innerWidth; };
  var fsEl = function () { return document.fullscreenElement || document.webkitFullscreenElement; };
  var put = function (el, props) { for (var k in props) el.style.setProperty(k, props[k], 'important'); };

  // ------------------------------------------------------------------ 1) docked player
  // The whole player block (video AND controls) is pinned under YouTube's top bar and a spacer of the same height keeps the page below from
  // jumping. Pinning only the <video> does not work: it sits in a lower stacking layer, behind the top bar and the list.
  var docked = false, boxEl = null, saved = [], spacer = null, ticking = false;
  function topBar() { var m = document.getElementById('masthead-container') || document.querySelector('ytd-masthead'); return m ? Math.max(0, m.getBoundingClientRect().bottom) : 0; }
  function remember(el) { saved.push([el, el.getAttribute('style')]); }

  // the outermost wrapper that is exactly as tall as the player (YouTube nests several; #player itself can be 0 px tall)
  function findBox() {
    var p = player();
    if (!p) return null;
    var h = p.getBoundingClientRect().height, box = p;
    while (box.parentElement && box.parentElement.tagName.toLowerCase() !== 'ytd-watch-flexy' && Math.abs(box.parentElement.getBoundingClientRect().height - h) < 3) box = box.parentElement;
    return box;
  }

  function dock(box) {
    var r = box.getBoundingClientRect(), top = topBar();
    if (r.height < 40) return;
    saved = []; boxEl = box;
    spacer = document.createElement('div');
    spacer.id = 'wb-dock-spacer';
    spacer.style.setProperty('height', r.height + 'px');
    spacer.style.setProperty('width', '100%');
    box.parentNode.insertBefore(spacer, box);
    // layers above the player must not cover it: lift every ancestor that makes its own layer
    for (var n = box.parentElement; n && n.tagName.toLowerCase() !== 'ytd-app'; n = n.parentElement) {
      if (getComputedStyle(n).zIndex !== 'auto') { remember(n); n.style.setProperty('z-index', '2100', 'important'); }
    }
    remember(box);
    put(box, { position: 'fixed', top: top + 'px', left: '0', width: '100vw', height: r.height + 'px', 'z-index': '2100', background: '#000', transform: 'none' });
    docked = true;
  }
  function undock() {
    docked = false;
    for (var i = saved.length - 1; i >= 0; i--) { var el = saved[i][0], old = saved[i][1]; if (old === null) el.removeAttribute('style'); else el.setAttribute('style', old); }
    saved = [];
    if (spacer && spacer.parentNode) spacer.parentNode.removeChild(spacer);
    boxEl = null; spacer = null;
  }
  function evalDock() {
    ticking = false;
    var p = player();
    // only on a watch page, in portrait, not in full screen and not in the tiny picture-in-picture window (keepalive.js lays that out)
    if (!watching() || fsEl() || !p || !portrait() || window.innerHeight < 300) { if (docked) undock(); return; }
    var bar = topBar();
    if (!docked) {
      var box = findBox();
      if (box && box.getBoundingClientRect().bottom < bar + 8) dock(box);
    } else if (spacer && spacer.getBoundingClientRect().top >= bar - 4) undock();       // scrolled back up to where the player belongs
    dbg('dock=' + docked + ' bar=' + Math.round(bar) + ' box=' + (boxEl ? boxEl.tagName + '#' + boxEl.id : '-') + ' sp=' + (spacer ? Math.round(spacer.getBoundingClientRect().top) : '-'));
  }
  window.addEventListener('scroll', function () { if (!ticking) { ticking = true; requestAnimationFrame(evalDock); } }, { passive: true, capture: true });
  window.addEventListener('yt-navigate-finish', function () { if (docked) undock(); }, true);

  // ------------------------------------------------------------------ 2) swipe up / down
  function toggleFullscreen() {
    var b = document.querySelector('.ytp-fullscreen-button');
    if (b) { b.click(); return; }
    var p = player();
    if (fsEl()) { if (document.exitFullscreen) document.exitFullscreen(); }
    else if (p && p.requestFullscreen) p.requestFullscreen();
  }
  var start = null, armed = 0;       // armed: -1 swipe up / 1 swipe down decided while moving, acted on at touchend (full screen needs the touchend as the user's gesture)
  document.addEventListener('touchstart', function (e) {
    start = null; armed = 0;
    if (e.touches.length !== 1) return;
    var t = e.touches[0], p = player();
    tmsg = 'ts ' + (e.target.tagName||'') + ' in=' + (p && p.contains(e.target)) + ' fs=' + !!fsEl() + ' w=' + watching() + ' p=' + portrait();
    dbg('');
    if (!p || !p.contains(e.target)) return;
    if (e.target.closest && e.target.closest('.ytp-chrome-bottom, .ytp-progress-bar, .ytp-settings-menu, .ytp-popup')) return;   // seeking is not a swipe
    if (!fsEl() && !(watching() && portrait())) return;
    start = { x: t.clientX, y: t.clientY };
  }, { passive: true, capture: true });
  // decide while the finger moves: once the page starts scrolling the browser may never send a proper touchend
  document.addEventListener('touchmove', function (e) {
    if (e.touches.length !== 1) return;
    var c = e.touches[0];
    if (!start && !armed) return;
    var sx = start ? start.x : armed.x, sy = start ? start.y : armed.y, dx = c.clientX - sx, dy = c.clientY - sy;
    if (Math.abs(dy) < 10 || Math.abs(dx) > Math.abs(dy) * 0.6) return;
    if (e.cancelable) e.preventDefault();               // a vertical drag on the player is not a page scroll (and the browser then still sends touchend)
    if (Math.abs(dy) >= 45 && ((dy < 0 && !fsEl()) || (dy > 0 && fsEl()))) { armed = { x: sx, y: sy, dir: dy < 0 ? -1 : 1 }; start = null; tmsg += ' mv ' + Math.round(dy); dbg(''); }
  }, { passive: false, capture: true });
  document.addEventListener('touchend', function (e) {
    if (armed && armed.dir) { armed = 0; tmsg += ' FS'; dbg(''); toggleFullscreen(); return; }
    armed = 0;
    if (!start || !e.changedTouches.length) return;
    var c = e.changedTouches[0], dx = c.clientX - start.x, dy = c.clientY - start.y;
    start = null;
    tmsg += ' te dx=' + Math.round(dx) + ' dy=' + Math.round(dy); dbg('');
    if (Math.abs(dy) < 80 || Math.abs(dx) > Math.abs(dy) * 0.6) return;
    if (dy < 0 && !fsEl()) toggleFullscreen();          // swipe up on the player: enter full screen
    else if (dy > 0 && fsEl()) toggleFullscreen();      // swipe down in full screen: leave it
  }, { passive: true, capture: true });

  // ------------------------------------------------------------------ 3) pinch in full screen: fit <-> fill
  var pinch = 0;
  var spread = function (t) { var dx = t[0].clientX - t[1].clientX, dy = t[0].clientY - t[1].clientY; return Math.sqrt(dx * dx + dy * dy); };
  function fill(on) { var v = video(); if (v) v.style.setProperty('object-fit', on ? 'cover' : 'contain', 'important'); }
  document.addEventListener('touchstart', function (e) { pinch = (fsEl() && e.touches.length === 2) ? spread(e.touches) : 0; start = pinch ? null : start; }, { passive: true, capture: true });
  document.addEventListener('touchmove', function (e) {
    if (!pinch || e.touches.length !== 2) return;
    var d = spread(e.touches), ratio = d / pinch;
    if (ratio > 1.18) { fill(true); pinch = d; } else if (ratio < 0.85) { fill(false); pinch = d; }
  }, { passive: true, capture: true });
  document.addEventListener('touchend', function () { pinch = 0; }, { passive: true, capture: true });
  document.addEventListener('fullscreenchange', function () {
    if (!fsEl()) { var v = video(); if (v) v.style.removeProperty('object-fit'); }     // every new full screen starts as "fit"
    if (docked) undock();
  }, true);
})();
