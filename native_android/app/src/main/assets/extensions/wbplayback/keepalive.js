// WBrowser background playback. When the app is minimised or the screen locks, the browser tells
// pages they are hidden; players like YouTube, Netflix, Twitch or Hotstar then pause themselves.
// This keeps the page believing it is still visible and focused so playback carries on
// (the same idea as Brave's "background video playback"). Runs in the page world.
(function () {
  var code = '(' + function () {
    try {
      var def = function (o, k, v) {
        try { Object.defineProperty(o, k, { configurable: true, get: function () { return v; } }); } catch (e) {}
      };
      def(Document.prototype, 'hidden', false);
      def(Document.prototype, 'webkitHidden', false);
      def(Document.prototype, 'visibilityState', 'visible');
      def(Document.prototype, 'webkitVisibilityState', 'visible');
      try { Document.prototype.hasFocus = function () { return true; }; } catch (e) {}

      var stop = function (e) { e.stopImmediatePropagation(); };
      ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'freeze'].forEach(function (t) {
        document.addEventListener(t, stop, true);
        window.addEventListener(t, stop, true);
      });
      // only swallow focus loss of the window/document itself, never of form fields
      var stopWin = function (e) { if (e.target === window || e.target === document) e.stopImmediatePropagation(); };
      window.addEventListener('blur', stopWin, true);
      document.addEventListener('blur', stopWin, true);
    } catch (e) {}
  } + ')();';

  var s = document.createElement('script');
  s.textContent = code;
  (document.head || document.documentElement).appendChild(s);
  s.remove();
})();
