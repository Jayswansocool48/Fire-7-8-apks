/* Saving, loading and everything that needs the native side (files, network, accounts).
   In the desktop and tablet apps, messages go to the window's native side, which writes to the
   app's own folder. Opened in a plain browser (for development), it falls back to localStorage
   and features that need the native side are switched off. */
window.Codex = window.Codex || {};

Codex.Store = (function () {
  const handler = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.codex;
  const native = !!handler;
  const boot = window.__GAME_CODEX__ || {};
  const listeners = {};
  const pending = {};
  const waiting = {};
  const mediaCache = {};
  const saveHooks = [];
  const LS = "game-codex:";
  let seq = 0;

  // Must match the native side's list.
  const MEDIA_ID = /^[A-Za-z0-9_-]{1,64}\.(png|jpg|webp|gif|mp4|webm|mov|m4v|mp3|ogg|wav|m4a|flac|glb|gltf|obj|stl|fbx)$/;
  const KIND = { png: "image", jpg: "image", webp: "image", gif: "image", mp4: "video", webm: "video", mov: "video", m4v: "video", mp3: "audio", ogg: "audio", wav: "audio", m4a: "audio", flac: "audio", glb: "model", gltf: "model", obj: "model", stl: "model", fbx: "model" };
  function kindOf(id) { return KIND[String(id).split(".").pop().toLowerCase()] || "image"; }

  function post(msg) {
    if (native) handler.postMessage(JSON.stringify(msg));
  }

  // Send a message and wait for its reply ({type: "reply", rid, ...}).
  function request(msg, timeoutMs) {
    if (!native) return Promise.reject(new Error("This needs the desktop or tablet app."));
    const rid = "r" + (++seq) + "-" + Date.now().toString(36);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { delete waiting[rid]; reject(new Error("No answer from the app.")); }, timeoutMs || 120000);
      waiting[rid] = (m) => { clearTimeout(timer); delete waiting[rid]; m.error ? reject(new Error(m.error)) : resolve(m); };
      post(Object.assign({}, msg, { rid }));
    });
  }

  // The native side calls this with {type: ..., ...}
  window.__gameCodexNative = function (msg) {
    if (msg.type === "reply" && waiting[msg.rid]) { waiting[msg.rid](msg); return; }
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
    if (native) return { projects: boot.projects || [], settings: boot.settings || {}, native, signedIn: !!boot.signedIn };
    const ids = lsGet("projects", []);
    return { projects: ids.map((id) => lsGet("project:" + id, null)).filter(Boolean), settings: lsGet("settings", {}), native, signedIn: false };
  }

  function writeProject(project, keepTime) {
    if (!keepTime) project.updated = Date.now();
    if (native) post({ type: "save-project", project });
    else {
      const ids = lsGet("projects", []);
      if (!ids.includes(project.id)) { ids.push(project.id); lsSet("projects", ids); }
      if (!lsSet("project:" + project.id, project)) Codex.toast && Codex.toast("Browser storage is full. Remove some files or use the desktop app.");
    }
    if (!keepTime) saveHooks.forEach((fn) => fn(project));
  }

  // Saves wait a moment so typing doesn't write the file on every key.
  // opts.keepTime saves without marking the game as changed (used when syncing).
  function saveProject(project, now, opts) {
    const keep = opts && opts.keepTime;
    clearTimeout(pending[project.id]);
    if (now || keep) { delete pending[project.id]; writeProject(project, keep); return; }
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

  function newMediaId(ext) {
    return "m" + Date.now().toString(36) + Math.random().toString(36).slice(2, 8) + "." + ext;
  }

  // Images made in the app (sketches, resized pictures) arrive as data URLs.
  function saveMedia(dataUrl) {
    return saveFile(dataUrl, /^data:image\/jpeg/.test(dataUrl) ? "jpg" : "png");
  }

  // Any file the page made (a painted texture, a .glb from the 3D studio), as a data URL. Returns its id.
  function saveFile(dataUrl, ext) {
    const id = newMediaId(ext);
    if (!MEDIA_ID.test(id)) throw new Error("Game Codex can't keep ." + ext + " files.");
    mediaCache[id] = dataUrl;
    if (native) post({ type: "save-media", id, data: dataUrl });
    else if (!lsSet("media:" + id, dataUrl)) Codex.toast && Codex.toast("Browser storage is full, so this file won't be kept after you close the page.");
    return id;
  }

  /* Pick video, sound or 3D model files. The native side copies them straight into the app's
     folder (they can be big, so they never pass through the page) and replies with
     [{id, name, size, kind}]. accept: "video" | "audio" | "model" | "media" */
  function pickFiles(accept, multiple) {
    if (native) return request({ type: "pick-files", accept, multiple: !!multiple }, 30 * 60 * 1000).then((m) => m.files || []);
    // Browser fallback for trying things out: small files only.
    return new Promise((resolve) => {
      const input = document.createElement("input");
      input.type = "file";
      input.multiple = !!multiple;
      input.accept = { video: "video/*", audio: "audio/*", model: ".glb,.gltf,.obj,.stl,.fbx", media: "video/*,audio/*,image/*" }[accept] || "";
      input.onchange = async () => {
        const out = [];
        for (const f of input.files) {
          const ext = (f.name.split(".").pop() || "").toLowerCase().replace("jpeg", "jpg");
          if (!KIND[ext]) { Codex.toast && Codex.toast(f.name + " isn't a file type Game Codex can keep."); continue; }
          if (f.size > 4 * 1024 * 1024) { Codex.toast && Codex.toast(f.name + " is too big for the browser version. Use the desktop or tablet app."); continue; }
          const url = await new Promise((r) => { const fr = new FileReader(); fr.onload = () => r(fr.result); fr.readAsDataURL(f); });
          const id = newMediaId(ext);
          mediaCache[id] = url;
          lsSet("media:" + id, url);
          out.push({ id, name: f.name, size: f.size, kind: KIND[ext] });
        }
        resolve(out);
      };
      input.click();
    });
  }

  function mediaUrl(id) {
    if (!id) return "";
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

  // Export: a .gamecodex file (a zip with project.json, design.md and the media files).
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

  /* Store kit: files is [{path, data}] where data is a data URL or plain text.
     The native side zips them together with the listed media files. */
  function exportKit(fileBase, files, mediaFiles) {
    if (native) { post({ type: "export-kit", name: fileBase, files, media: mediaFiles }); return; }
    files.forEach((f) => {
      const blob = f.data.startsWith("data:") ? dataUrlToBlob(f.data) : new Blob([f.data], { type: "text/plain" });
      download(f.path.replace(/\//g, "-"), blob);
    });
  }

  function dataUrlToBlob(url) {
    const [head, b64] = url.split(",");
    const bin = atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return new Blob([bytes], { type: head.slice(5).split(";")[0] });
  }

  // target: the id of a game to update in place, or null to import as a new game.
  function importProject(target) {
    if (native) { post({ type: "import", target: target || null }); return; }
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
        window.__gameCodexNative({ type: "imported", project: data.project, target: target || null });
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

  // ---------- network (through the native side; used for GitHub) ----------
  // auth: true adds the signed-in GitHub token (only ever sent to api.github.com).
  function http(url, opts) {
    opts = opts || {};
    return request({ type: "http", url, method: opts.method || "GET", headers: opts.headers || {}, body: opts.body == null ? null : String(opts.body), auth: !!opts.auth }, opts.timeout || 60000)
      .then((m) => ({ status: m.status, body: m.body || "", json() { try { return JSON.parse(this.body || "null"); } catch (e) { return null; } } }));
  }

  return {
    native, post, request, on, init, saveProject, flush, deleteProject, saveSettings, saveMedia, saveFile, pickFiles, mediaUrl, deleteMedia,
    exportProject, exportMarkdown, exportKit, importProject, deleteAllData, http, kindOf, MEDIA_ID,
    onSave: (fn) => saveHooks.push(fn),
    version: boot.version || "dev", platform: boot.platform || (native ? "desktop" : "browser"),
    // what the native side can do beyond saving (the tablet app may not have everything yet)
    can: (feature) => (boot.features || []).includes(feature),
  };
})();
