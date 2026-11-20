// YouTube is loaded as its DESKTOP site (see Tabs.kt) so it keeps playing in the background. The desktop layout is a little wider than
// a phone, which made the page overflow sideways. A viewport meta asks the browser to lay the page out 480 px wide and fit that to the
// screen, so the whole page is visible without horizontal scrolling (pinch still zooms). Top frame only.
(function () {
  if (window !== window.top) return;
  var CONTENT = 'width=480, user-scalable=yes, minimum-scale=0.3, maximum-scale=5';
  var meta = null;
  function apply() {
    var head = document.head || document.documentElement;
    if (!head) return;
    meta = document.querySelector('meta[name="viewport"]');
    if (!meta) { meta = document.createElement('meta'); meta.setAttribute('name', 'viewport'); head.appendChild(meta); }
    if (meta.getAttribute('content') !== CONTENT) meta.setAttribute('content', CONTENT);
  }
  apply();
  document.addEventListener('DOMContentLoaded', apply);
  window.addEventListener('load', apply);
  // the page may rewrite the tag while it boots: keep ours for the first half minute
  var n = 0, t = setInterval(function () { apply(); if (++n > 20) clearInterval(t); }, 1500);
})();
