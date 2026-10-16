/* Saving and loading.
   In the desktop app, everything goes through the window's Python side, which writes
   to ~/.local/share/game-codex (inside the Flatpak: ~/.var/app/<app-id>/data/game-codex).
   Opened in a plain browser (for development), it falls back to localStorage. */
window.Codex = window.Codex || {};

Codex.Store = (function () {
  const handler = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.codex;
  const native = !!handler;
  const boot = window.__GAME_CODEX__ || {};
  const listeners = {};
  const pending = {};
  const mediaCache = {};
  const LS = "game-codex:";

  function post(msg) {
    if (native) handler.postMessage(JSON.stringify(msg));
  }

  // Python calls this with {type: ..., ...}
  window.__gameCodexNative = function (msg) {
    (listeners[msg.type] || []).forEach((fn) => {
      try { fn(msg); } catch (err) { console.error(err); }
    });
  };

  function on(type, fn) { (listeners[type] = listeners[type] || []).push(fn); }

  function lsGet(key, fallback) {
    try { const v = localStorage.getItem(LS + key); return v ? JSON.parse(v) : fallback; } catch (e) { return fallback; }
  }
  function lsSet(key, value) {
    try { localStorage.setItem(LS + key, JSON.stringify(value)); return true; } catch (e) { return false; }
  }
  function lsRemove(key) { try { localStorage.removeItem(LS + key); } catch (e) { /* ignore */ } }

  function init() {
    if (native) return { projects: boot.projects || [], settings: boot.settings || {}, native };
    const ids = lsGet("projects", []);
    return { projects: ids.map((id) => lsGet("project:" + id, null)).filter(Boolean), settings: lsGet("settings", {}), native };
  }

  function writeProject(project) {
    project.updated = Date.now();
    if (native) { post({ type: "save-project", project }); return; }
    const ids = lsGet("projects", []);
    if (!ids.includes(project.id)) { ids.push(project.id); lsSet("projects", ids); }
    if (!lsSet("project:" + project.id, project)) Codex.toast && Codex.toast("Browser storage is full. Remove some images or use the desktop app.");
  }

  // Saves wait a moment so typing doesn't write the file on every key.
  function saveProject(project, now) {
    clearTimeout(pending[project.id]);
    if (now) { delete pending[project.id]; writeProject(project); return; }
    pending[project.id] = setTimeout(() => { delete pending[project.id]; writeProject(project); }, 600);
  }

  function flush(projects) {
    Object.keys(pending).forEach((id) => {
      clearTimeout(pending[id]);
      delete pending[id];
      const p = projects.find((x) => x.id === id);
      if (p) writeProject(p);
    });
  }

  function deleteProject(id) {
    clearTimeout(pending[id]);
    delete pending[id];
    if (native) { post({ type: "delete-project", id }); return; }
    const p = lsGet("project:" + id, null);
    if (p) (p.media || []).forEach((m) => lsRemove("media:" + m));
    lsSet("projects", lsGet("projects", []).filter((x) => x !== id));
    lsRemove("project:" + id);
  }

  function saveSettings(settings) {
    if (native) post({ type: "save-settings", settings });
    else lsSet("settings", settings);
  }

  function newMediaId(dataUrl) {
    const ext = /^data:image\/jpeg/.test(dataUrl) ? "jpg" : "png";
    return "m" + Date.now().toString(36) + Math.random().toString(36).slice(2, 8) + "." + ext;
  }

  function saveMedia(dataUrl) {
    const id = newMediaId(dataUrl);
    mediaCache[id] = dataUrl;
    if (native) post({ type: "save-media", id, data: dataUrl });
    else if (!lsSet("media:" + id, dataUrl)) Codex.toast && Codex.toast("Browser storage is full, so this image won't be kept after you close the page.");
    return id;
  }

  function mediaUrl(id) {
    if (mediaCache[id]) return mediaCache[id];
    if (native) return (boot.mediaBase || "") + encodeURIComponent(id);
    const v = lsGet("media:" + id, null);
    if (v) mediaCache[id] = v;
    return v || "";
  }

  function deleteMedia(ids) {
    if (!ids.length) return;
    ids.forEach((id) => delete mediaCache[id]);
    if (native) post({ type: "delete-media", ids });
    else ids.forEach((id) => lsRemove("media:" + id));
  }

  function download(name, blob) {
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = name;
    document.body.appendChild(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
  }

  // Export: a .gamecodex file (a zip with project.json, design.md and the images).
  function exportProject(project, markdown, fileBase) {
    if (native) { post({ type: "export", project, markdown, name: fileBase }); return; }
    const media = {};
    (project.media || []).forEach((id) => { const u = mediaUrl(id); if (u) media[id] = u; });
    download(fileBase + ".gamecodex.json", new Blob([JSON.stringify({ format: "game-codex", version: 1, project, media, markdown })], { type: "application/json" }));
  }

  function exportMarkdown(markdown, fileBase) {
    if (native) { post({ type: "export-markdown", markdown, name: fileBase }); return; }
    download(fileBase + ".md", new Blob([markdown], { type: "text/markdown" }));
  }

  function importProject() {
    if (native) { post({ type: "import" }); return; }
    const input = document.createElement("input");
    input.type = "file";
    input.accept = ".json,application/json";
    input.onchange = () => {
      const file = input.files[0];
      if (!file) return;
      file.text().then((text) => {
        let data;
        try { data = JSON.parse(text); } catch (e) { data = null; }
        if (!data || data.format !== "game-codex" || !data.project) {
          window.__gameCodexNative({ type: "imported", error: "That file isn't a Game Codex export." });
          return;
        }
        Object.entries(data.media || {}).forEach(([id, url]) => { mediaCache[id] = url; lsSet("media:" + id, url); });
        window.__gameCodexNative({ type: "imported", project: data.project });
      });
    };
    input.click();
  }

  function deleteAllData() {
    if (native) { post({ type: "delete-data" }); return; }
    try {
      Object.keys(localStorage).filter((k) => k.startsWith(LS)).forEach((k) => localStorage.removeItem(k));
    } catch (e) { /* ignore */ }
  }

  return { native, post, on, init, saveProject, flush, deleteProject, saveSettings, saveMedia, mediaUrl, deleteMedia, exportProject, exportMarkdown, importProject, deleteAllData, version: boot.version || "dev" };
})();
