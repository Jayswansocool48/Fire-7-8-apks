/* Connects Game Codex to the tablet. Loaded before the app's own scripts.
   The app already knows how to talk to the desktop window through
   window.webkit.messageHandlers.codex; this points that at the Android side instead. */
(function () {
  var A = window.AndroidCodex;
  if (!A) return;

  var boot = {};
  try { boot = JSON.parse(A.boot()); } catch (e) { boot = { projects: [], settings: {} }; }
  window.__GAME_CODEX__ = boot;
  window.webkit = window.webkit || {};
  window.webkit.messageHandlers = { codex: { postMessage: function (s) { A.post(String(s)); } } };

  // Tablet layout and touch sizes, after the app's own stylesheet.
  var css = document.createElement("link");
  css.rel = "stylesheet";
  css.href = "android.css";
  document.addEventListener("DOMContentLoaded", function () { document.head.appendChild(css); });

  // Tell Settings what kind of install this is once the app has started.
  document.addEventListener("DOMContentLoaded", function () {
    document.documentElement.classList.add("android");
    if (window.__gameCodexNative) window.__gameCodexNative({ type: "info", install: "android", version: boot.version || "dev" });
  });

  // Drawing with a finger: stop the browser treating strokes as scroll/fling gestures.
  // Without this, the first tap after a quick stroke (on a tool or Save) is swallowed.
  ["touchstart", "touchmove"].forEach(function (type) {
    document.addEventListener(type, function (e) {
      if (e.target && e.target.closest && e.target.closest(".sketch-paper")) e.preventDefault();
    }, { passive: false });
  });

  function escape(target) {
    target.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true, cancelable: true }));
  }
  function visible(el) { return el && el.offsetParent !== null; }

  // The tablet's Back button: close whatever is on top. Returns false when there's
  // nothing left to close, and the app goes to the background.
  window.__gameCodexBack = function () {
    if (document.querySelector(".backdrop")) { escape(document.activeElement || document.body); return true; }
    var sketch = document.querySelector(".sketch:not([hidden])");
    if (sketch) { escape(sketch); return true; }
    var app = document.getElementById("app");
    if (app && app.classList.contains("nav-open")) { escape(document.body); return true; }
    var back = document.querySelector(".main.has-entry .back-btn");
    if (visible(back)) { back.click(); return true; }
    var overview = document.querySelector('.nav-item[href="#overview"]');
    if (overview && overview.getAttribute("aria-current") !== "page") { overview.click(); return true; }
    return false;
  };
})();
