/* Small DOM helpers shared by the rest of the app. */
window.Codex = window.Codex || {};

(function () {
  // h("div", {class: "x", onclick: fn}, child, "text", [more children])
  function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    if (attrs) {
      for (const [k, v] of Object.entries(attrs)) {
        if (v === undefined || v === null || v === false) continue;
        if (k.startsWith("on") && typeof v === "function") el.addEventListener(k.slice(2), v);
        else if (k === "class") el.className = v;
        else if (k === "html") el.innerHTML = v;
        else if (k === "value") el.value = v;
        else if (k === "checked") el.checked = !!v;
        else if (k === "dataset") Object.assign(el.dataset, v);
        else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
        else el.setAttribute(k, v === true ? "" : v);
      }
    }
    append(el, children);
    return el;
  }

  function append(el, children) {
    for (const c of children) {
      if (c === null || c === undefined || c === false) continue;
      if (Array.isArray(c)) append(el, c);
      else el.appendChild(c instanceof Node ? c : document.createTextNode(String(c)));
    }
  }

  function icon(d, cls) {
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("aria-hidden", "true");
    if (cls) svg.setAttribute("class", cls);
    const p = document.createElementNS(ns, "path");
    p.setAttribute("d", d);
    svg.appendChild(p);
    return svg;
  }

  const ICONS = {
    plus: "M12 5v14M5 12h14",
    dice: "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zm3.5 4a.5.5 0 1 0 0 1 .5.5 0 0 0 0-1zm7 0a.5.5 0 1 0 0 1 .5.5 0 0 0 0-1zm-3.5 3.5a.5.5 0 1 0 0 1 .5.5 0 0 0 0-1zm-3.5 3.5a.5.5 0 1 0 0 1 .5.5 0 0 0 0-1zm7 0a.5.5 0 1 0 0 1 .5.5 0 0 0 0-1z",
    gear: "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zm8 3-.1-1 2-1.6-2-3.4-2.4 1a7 7 0 0 0-1.7-1L15.5 3h-4l-.4 2.6a7 7 0 0 0-1.7 1l-2.4-1-2 3.4 2 1.6L7 12l.1 1-2 1.6 2 3.4 2.4-1a7 7 0 0 0 1.7 1l.4 2.6h4l.4-2.6a7 7 0 0 0 1.7-1l2.4 1 2-3.4-2-1.6.1-1z",
    home: "M4 11l8-7 8 7v9h-5v-6H9v6H4z",
    x: "M6 6l12 12M18 6L6 18",
    up: "M12 19V5M6 11l6-6 6 6",
    down: "M12 5v14M6 13l6 6 6-6",
    image: "M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15 9.5a1 1 0 1 0 0-.01",
    pencil: "M4 20l4-1 11-11-3-3L5 16l-1 4z",
    search: "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zm9 16-4-4",
    copy: "M8 8h11v12H8zM5 16V4h11",
    trash: "M5 7h14M10 11v6m4-6v6M7 7l1 13h8l1-13M9 7V4h6v3",
    play: "M7 5l12 7-12 7z",
    book: "M5 4h10a4 4 0 0 1 4 4v12H9a4 4 0 0 1-4-4V4zm4 0v12",
    chevron: "M9 6l6 6-6 6",
    check: "M5 12l5 5 9-10",
    folder: "M3 6h7l2 2h9v11H3z",
    download: "M12 4v11m-5-5 5 5 5-5M5 20h14",
    upload: "M12 20V9m-5 5 5-5 5 5M5 4h14",
    link: "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1",
    menu: "M4 7h16M4 12h16M4 17h16",
  };

  function uid(prefix) {
    return (prefix || "") + Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
  }

  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  // Grow a textarea to fit its text.
  function autosize(t) {
    const fit = () => { t.style.height = "auto"; t.style.height = t.scrollHeight + 2 + "px"; };
    t.addEventListener("input", fit);
    requestAnimationFrame(fit);
    return t;
  }

  let toastTimer;
  function toast(text) {
    let el = document.getElementById("toast");
    if (!el) {
      el = h("div", { id: "toast", role: "status", "aria-live": "polite" });
      document.body.appendChild(el);
    }
    el.textContent = text;
    el.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove("show"), 3200);
  }

  // A dialog that keeps focus inside it and closes with Esc. Returns {el, close}.
  function dialog(opts) {
    const prev = document.activeElement;
    const titleId = uid("dlg");
    const body = h("div", { class: "dialog-body" }, opts.body);
    const panel = h("div", { class: "dialog " + (opts.class || ""), role: opts.alert ? "alertdialog" : "dialog", "aria-modal": "true", "aria-labelledby": titleId },
      h("header", { class: "dialog-head" },
        h("h2", { id: titleId }, opts.title),
        h("button", { class: "icon-btn", type: "button", "aria-label": "Close", onclick: () => close() }, icon(ICONS.x))),
      body,
      opts.actions ? h("footer", { class: "dialog-actions" }, opts.actions) : null);
    const back = h("div", { class: "backdrop", onmousedown: (e) => { if (e.target === back) close(); } }, panel);
    let closed = false;
    function close(result) {
      if (closed) return;
      closed = true;
      back.remove();
      document.removeEventListener("keydown", onKey, true);
      if (opts.onClose) opts.onClose(result);
      if (prev && prev.focus && document.contains(prev)) prev.focus();
    }
    function onKey(e) {
      if (!document.contains(back)) return;
      if (e.key === "Escape") { e.stopPropagation(); e.preventDefault(); close(); }
      if (e.key === "Tab") {
        const f = [...panel.querySelectorAll("button, [href], input, select, textarea, [tabindex]:not([tabindex='-1'])")].filter((x) => !x.disabled && x.offsetParent !== null);
        if (!f.length) return;
        if (e.shiftKey && document.activeElement === f[0]) { e.preventDefault(); f[f.length - 1].focus(); }
        else if (!e.shiftKey && document.activeElement === f[f.length - 1]) { e.preventDefault(); f[0].focus(); }
      }
    }
    document.addEventListener("keydown", onKey, true);
    document.body.appendChild(back);
    requestAnimationFrame(() => {
      const first = panel.querySelector("[autofocus]") || panel.querySelector(".dialog-body input, .dialog-body select, .dialog-body textarea, .dialog-actions .primary, .dialog-body button") || panel.querySelector("button");
      if (first) first.focus();
    });
    return { el: panel, body, close };
  }

  // Yes/no question. Resolves true when confirmed.
  function ask(opts) {
    return new Promise((resolve) => {
      let ok = false;
      const yes = h("button", { type: "button", class: "btn " + (opts.danger ? "danger" : "primary"), onclick: () => { ok = true; d.close(); } }, opts.confirm || "OK");
      const no = h("button", { type: "button", class: "btn ghost", onclick: () => d.close() }, opts.cancel || "Cancel");
      const d = dialog({ title: opts.title, alert: true, class: "small", body: h("p", null, opts.text), actions: [no, yes], onClose: () => resolve(ok) });
      requestAnimationFrame(() => (opts.danger ? no : yes).focus());
    });
  }

  // Load a picked image file, shrink it so saves stay small, and return a data URL.
  function readImage(file, max) {
    max = max || 1600;
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onerror = () => reject(new Error("Couldn't read " + file.name));
      reader.onload = () => {
        const img = new Image();
        img.onerror = () => reject(new Error(file.name + " isn't an image this app can open."));
        img.onload = () => {
          const s = Math.min(1, max / Math.max(img.width, img.height));
          const c = document.createElement("canvas");
          c.width = Math.round(img.width * s);
          c.height = Math.round(img.height * s);
          c.getContext("2d").drawImage(img, 0, 0, c.width, c.height);
          const keepPng = /png|gif|webp|svg/.test(file.type);
          resolve(keepPng ? c.toDataURL("image/png") : c.toDataURL("image/jpeg", 0.88));
        };
        img.src = reader.result;
      };
      reader.readAsDataURL(file);
    });
  }

  Object.assign(Codex, { h, icon, ICONS, uid, esc, autosize, toast, dialog, ask, readImage });
})();
