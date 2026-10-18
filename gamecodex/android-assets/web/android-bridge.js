/* Connects Game Codex to the tablet. Loaded before the app's own scripts.
   The app already talks to its desktop window through window.webkit.messageHandlers.codex;
   this points that at the Android side (window.AndroidCodex) instead. */
(function () {
  var A = window.AndroidCodex;
  if (!A) return;

  var boot = {};
  try { boot = JSON.parse(A.boot()); } catch (e) { boot = { projects: [], settings: {} }; }
  boot.platform = "android";
  window.__GAME_CODEX__ = boot;
  window.webkit = window.webkit || {};
  window.webkit.messageHandlers = { codex: { postMessage: function (s) { A.post(String(s)); } } };

  // Tablet touch sizes, after the app's own stylesheet.
  var css = document.createElement("link");
  css.rel = "stylesheet";
  css.href = "android.css";
  document.addEventListener("DOMContentLoaded", function () {
    document.head.appendChild(css);
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

  // The tablet's Back button: close whatever is on top, then step back towards the library.
  // Returns false on the library, and the app goes to the background.
  window.__gameCodexBack = function () {
    if (document.querySelector(".menu")) { escape(document.activeElement || document.body); return true; }
    if (document.querySelector(".backdrop")) { escape(document.activeElement || document.body); return true; }
    var sketch = document.querySelector(".sketch:not([hidden])");
    if (sketch) { escape(sketch); return true; }
    var back = document.querySelector(".split.has-entry .back-btn");
    if (visible(back)) { back.click(); return true; }
    var tabs = document.querySelectorAll(".tabs .tab");
    if (tabs.length) {
      if (tabs[0].getAttribute("aria-current") !== "page") { tabs[0].click(); return true; }
      var mark = document.querySelector(".topbar .mark");
      if (mark) { mark.click(); return true; }
    }
    return false;
  };
})();
