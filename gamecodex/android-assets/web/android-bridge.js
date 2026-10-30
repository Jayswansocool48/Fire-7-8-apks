/* Connects Game Codex to the tablet. Loaded before the app's own scripts.
   The app talks to its host through window.webkit.messageHandlers.codex (src/lib/native.ts in the
   game-codex repo); this points that at the Android side (window.AndroidCodex) and hands over the
   saved games. The app itself handles the Back button (window.__gameCodexBack). */
(function () {
  var A = window.AndroidCodex;
  if (!A) return;

  var boot = {};
  try { boot = JSON.parse(A.boot()); } catch (e) { boot = { projects: [], settings: {} }; }
  boot.platform = "android";
  window.__GAME_CODEX__ = boot;
  window.webkit = window.webkit || {};
  window.webkit.messageHandlers = { codex: { postMessage: function (s) { A.post(String(s)); } } };

  // Drawing with a finger: stop the browser treating strokes as scroll or fling gestures.
  // Without this, the first tap after a quick stroke (on a tool or Save) is swallowed.
  ["touchstart", "touchmove"].forEach(function (type) {
    document.addEventListener(type, function (e) {
      if (e.target && e.target.closest && e.target.closest(".sketch-paper")) e.preventDefault();
    }, { passive: false });
  });
})();
