/* Accounts: sign in with GitHub so your games are kept in a private repository on your own
   account (game-codex-saves) and show up on every device you sign in on.

   Layout of the repository:
     index.json                         which games exist, when each last changed, deleted games
     games/<id>/project.json            the game
     games/<id>/media/<file>            its pictures, videos, sounds and models
   The newest copy of each game wins. Files never change once written (each has a unique name),
   so only new ones are uploaded. */
window.Codex = window.Codex || {};

Codex.Account = (function () {
  const { h, icon, ICONS, toast, dialog, ask, menu, Store } = Codex;
  const API = "https://api.github.com";
  let app = null;
  let state = "off"; // off | ok | busy | error
  let lastError = "";
  let timer = 0;
  let running = null;
  let again = false;

  const settings = () => app.settings();
  const account = () => settings().account || null;
  const repoName = () => (Codex.CONFIG && Codex.CONFIG.savesRepo) || "game-codex-saves";
  const repoPath = () => "/repos/" + account().login + "/" + repoName();

  // ---------- GitHub ----------
  function gh(path, opts) {
    opts = opts || {};
    const headers = Object.assign({ Accept: "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28" }, opts.headers || {});
    let body = opts.body;
    if (body && typeof body !== "string") { body = JSON.stringify(body); headers["Content-Type"] = "application/json"; }
    return Store.http(path.startsWith("http") ? path : API + path, { method: opts.method || "GET", headers, body, auth: true, timeout: opts.timeout });
  }

  function enc(str) { return btoa(unescape(encodeURIComponent(str))); }
  function dec(b64) { return decodeURIComponent(escape(atob(String(b64).replace(/\s/g, "")))); }

  function fail(res, what) {
    const msg = (res.json() || {}).message || "";
    if (res.status === 401) return new Error("GitHub signed you out (the token expired or was revoked). Sign in again.");
    if (res.status === 403 && /rate limit/i.test(msg)) return new Error("GitHub's hourly limit was reached. Syncing will try again later.");
    if (res.status === 0) return new Error("Couldn't reach GitHub. Check your internet connection.");
    return new Error(what + " (GitHub said " + res.status + (msg ? ": " + msg : "") + ").");
  }

  async function ensureRepo() {
    const r = await gh(repoPath());
    if (r.status === 200) return;
    if (r.status !== 404) throw fail(r, "Couldn't open your saves");
    const c = await gh("/user/repos", { method: "POST", body: { name: repoName(), private: true, auto_init: true, description: "Games saved by Game Codex. Keep this private." } });
    if (c.status !== 201) throw fail(c, "Couldn't create the game-codex-saves repository");
    await new Promise((res) => setTimeout(res, 1500)); // GitHub needs a moment before files can be added
  }

  async function getIndex() {
    const r = await gh(repoPath() + "/contents/index.json");
    if (r.status === 404) return { data: { format: "game-codex-saves", version: 1, games: {} }, sha: null };
    if (r.status !== 200) throw fail(r, "Couldn't read your saves");
    const j = r.json();
    let data;
    try { data = JSON.parse(dec(j.content)); } catch (e) { data = null; }
    if (!data || !data.games) data = { format: "game-codex-saves", version: 1, games: {} };
    return { data, sha: j.sha };
  }

  async function putFile(path, text, sha, message) {
    const r = await gh(repoPath() + "/contents/" + path, { method: "PUT", body: { message, content: enc(text), sha: sha || undefined } });
    if (r.status === 200 || r.status === 201) return r.json().content.sha;
    if ((r.status === 409 || r.status === 422) && !sha) {
      // the file exists but we didn't know its sha: look it up and try once more
      const m = await gh(repoPath() + "/contents/" + path);
      if (m.status === 200) return putFile(path, text, m.json().sha, message);
    }
    const e = fail(r, "Couldn't save " + path);
    e.status = r.status;
    throw e;
  }

  async function listDir(path) {
    const r = await gh(repoPath() + "/contents/" + path);
    if (r.status === 404) return [];
    if (r.status !== 200) throw fail(r, "Couldn't list " + path);
    const j = r.json();
    return Array.isArray(j) ? j : [];
  }

  async function deleteFile(path, sha, message) {
    const r = await gh(repoPath() + "/contents/" + path, { method: "DELETE", body: { message, sha } });
    if (r.status !== 200 && r.status !== 404) throw fail(r, "Couldn't remove " + path);
  }

  async function readProject(id) {
    const r = await gh(repoPath() + "/contents/games/" + id + "/project.json");
    if (r.status !== 200) throw fail(r, "Couldn't download a game");
    const j = r.json();
    let text;
    if (j.content && j.encoding === "base64") text = dec(j.content);
    else {
      const b = await gh(repoPath() + "/git/blobs/" + j.sha);
      if (b.status !== 200) throw fail(b, "Couldn't download a game");
      text = dec(b.json().content);
    }
    return { project: JSON.parse(text), sha: j.sha };
  }

  // ---------- syncing ----------
  async function push(p, idx) {
    const remote = await listDir("games/" + p.id + "/media");
    const have = new Set(remote.map((f) => f.name));
    const needed = app.mediaOf(p);
    for (const mid of needed) {
      if (have.has(mid)) continue;
      const res = await Store.request({ type: "gh-media-up", url: API + repoPath() + "/contents/games/" + p.id + "/media/" + mid, mediaId: mid, message: "Add a file to " + (p.name || "a game") }, 30 * 60 * 1000);
      if (res.status === 413 || res.tooBig) toast("A file in " + (p.name || "a game") + " is over GitHub's 100 MB limit, so it stays on this device only.");
      else if (res.status && res.status >= 300 && res.status !== 422 && !res.missing) throw fail({ status: res.status, json: () => { try { return JSON.parse(res.body || "{}"); } catch (e) { return {}; } } }, "Couldn't upload a file");
    }
    const wanted = new Set(needed);
    for (const f of remote) if (!wanted.has(f.name)) await deleteFile("games/" + p.id + "/media/" + f.name, f.sha, "Remove a file");
    const entry = idx.games[p.id] || {};
    let sha;
    try {
      sha = await putFile("games/" + p.id + "/project.json", JSON.stringify(p), entry.sha, "Save " + (p.name || "a game"));
    } catch (e) {
      if (e.status === 409 || e.status === 422) sha = await putFile("games/" + p.id + "/project.json", JSON.stringify(p), null, "Save " + (p.name || "a game"));
      else throw e;
    }
    idx.games[p.id] = { name: p.name || "", updated: p.updated || Date.now(), sha };
  }

  async function pull(id, idx) {
    const { project, sha } = await readProject(id);
    const needed = app.mediaOf(project);
    const m = needed.length ? await Store.request({ type: "media-exists", ids: needed }) : { missing: [] };
    for (const mid of m.missing || []) {
      await Store.request({ type: "gh-media-down", url: API + repoPath() + "/contents/games/" + id + "/media/" + mid, mediaId: mid }, 30 * 60 * 1000);
    }
    project.media = needed;
    idx.games[id] = Object.assign({}, idx.games[id], { sha, updated: project.updated || idx.games[id].updated });
    app.putProject(project);
  }

  async function removeRemote(id) {
    for (const f of await listDir("games/" + id + "/media")) await deleteFile("games/" + id + "/media/" + f.name, f.sha, "Remove a deleted game's file");
    for (const f of await listDir("games/" + id)) if (f.type === "file") await deleteFile("games/" + id + "/" + f.name, f.sha, "Remove a deleted game");
  }

  async function syncOnce() {
    await ensureRepo();
    const index = await getIndex();
    const idx = index.data;
    const s = settings();
    const deleted = Object.assign({}, s.syncDeleted || {});
    let changedIndex = false;

    for (const id of Object.keys(deleted)) {
      const r = idx.games[id];
      if (r && !r.deleted) await removeRemote(id);
      idx.games[id] = { name: (r && r.name) || "", deleted: deleted[id] };
      changedIndex = true;
    }
    for (const p of app.projects().slice()) {
      const r = idx.games[p.id];
      const local = p.updated || 0;
      if (!r) { await push(p, idx); changedIndex = true; }
      else if (r.deleted) {
        if (r.deleted >= local) app.removeProject(p.id, false);
        else { await push(p, idx); changedIndex = true; }
      } else if (local > (r.updated || 0)) { await push(p, idx); changedIndex = true; }
      else if ((r.updated || 0) > local) await pull(p.id, idx);
    }
    for (const id of Object.keys(idx.games)) {
      const r = idx.games[id];
      if (r.deleted || deleted[id] || app.projects().some((p) => p.id === id)) continue;
      await pull(id, idx);
    }
    if (changedIndex) {
      try { await putFile("index.json", JSON.stringify(idx, null, 1), index.sha, "Update the game list"); }
      catch (e) { if (e.status === 409 || e.status === 422) { again = true; return; } throw e; }
    }
    s.syncDeleted = {};
    s.lastSync = Date.now();
    app.saveSettings();
  }

  function setState(st, err) {
    state = st;
    lastError = err || "";
    app.renderTopbar();
    const el = document.getElementById("sync-status");
    if (el) el.textContent = statusText();
  }

  function statusText() {
    if (state === "busy") return "Syncing…";
    if (state === "error") return lastError;
    const t = settings().lastSync;
    return t ? "Last synced " + new Date(t).toLocaleString([], { dateStyle: "medium", timeStyle: "short" }) : "Not synced yet";
  }

  function sync() {
    if (!account() || !Store.native) return Promise.resolve();
    if (running) { again = true; return running; }
    clearTimeout(timer);
    setState("busy");
    running = (async () => {
      let tries = 0;
      do { again = false; await syncOnce(); tries++; } while (again && tries < 3);
    })().then(() => setState("ok"), (e) => {
      setState("error", e.message);
      if (/signed you out/.test(e.message)) { signOut(false); toast(e.message); }
    }).then(() => { running = null; });
    return running;
  }

  function schedule(ms) {
    if (!account()) return;
    clearTimeout(timer);
    timer = setTimeout(sync, ms == null ? 15000 : ms);
  }

  // ---------- signing in ----------
  async function finishSignIn(token) {
    await Store.request({ type: "token-set", token });
    const r = await gh("/user");
    if (r.status !== 200) {
      await Store.request({ type: "token-clear" });
      throw new Error(r.status === 401 ? "GitHub didn't accept that token." : "Couldn't reach GitHub (" + r.status + ").");
    }
    const u = r.json();
    settings().account = { login: u.login, name: u.name || "", avatar: u.avatar_url ? u.avatar_url + "&s=96" : "" };
    settings().lastSync = 0;
    app.saveSettings();
    app.render();
    toast("Signed in as " + u.login + ". Syncing your games…");
    sync();
  }

  function signInDialog() {
    if (!Store.native) { toast("Signing in works in the desktop and tablet apps."); return; }
    const clientId = Codex.CONFIG && Codex.CONFIG.githubClientId;
    const body = h("div");
    const d = dialog({ title: "Sign in with GitHub", class: "small", body, onClose: () => { cancelled = true; } });
    let cancelled = false;

    function tokenView() {
      body.innerHTML = "";
      const input = h("input", { type: "password", placeholder: "ghp_…", "aria-label": "Personal access token", autocomplete: "off", spellcheck: "false" });
      const status = h("p", { class: "muted", "aria-live": "polite" });
      const go = async () => {
        if (!input.value.trim()) return;
        status.textContent = "Checking…";
        try { await finishSignIn(input.value.trim()); d.close(); }
        catch (e) { status.textContent = e.message; }
      };
      input.addEventListener("keydown", (e) => { if (e.key === "Enter") go(); });
      body.append(
        h("p", null, "Your games will be saved to a private repository called " + repoName() + " on your GitHub account."),
        h("ol", { class: "steps" },
          h("li", null, "Open ", h("a", { href: "https://github.com/settings/tokens/new?scopes=repo&description=Game%20Codex", target: "_blank", rel: "noopener" }, "github.com/settings/tokens/new"), " (signed in)."),
          h("li", null, "Keep the ", h("strong", null, "repo"), " box ticked, choose how long it lasts, and press Generate token."),
          h("li", null, "Copy the token and paste it here.")),
        input, status,
        h("div", { class: "row-btns", style: { marginTop: "8px" } },
          clientId ? h("button", { type: "button", class: "btn ghost", onclick: deviceView }, "Use a sign-in code instead") : null,
          h("span", { style: { flex: 1 } }),
          h("button", { type: "button", class: "btn primary", onclick: go }, "Sign in")));
      requestAnimationFrame(() => input.focus());
    }

    async function deviceView() {
      body.innerHTML = "";
      const status = h("p", { class: "muted", "aria-live": "polite" }, "Getting a code from GitHub…");
      body.append(status);
      try {
        const r = await Store.http("https://github.com/login/device/code", { method: "POST", headers: { Accept: "application/json", "Content-Type": "application/x-www-form-urlencoded" }, body: "client_id=" + encodeURIComponent(clientId) + "&scope=repo" });
        const j = r.json() || {};
        if (!j.user_code) throw new Error(j.error_description || "GitHub didn't give a code (" + r.status + ").");
        body.innerHTML = "";
        const copy = h("button", { type: "button", class: "btn ghost sm", onclick: () => { navigator.clipboard && navigator.clipboard.writeText(j.user_code).then(() => toast("Code copied"), () => {}); } }, icon(ICONS.copy), "Copy code");
        body.append(
          h("p", null, "Open GitHub, sign in if asked, and enter this code:"),
          h("div", { class: "code-box" }, j.user_code),
          h("div", { class: "row-btns" },
            h("a", { class: "btn primary", href: j.verification_uri || "https://github.com/login/device", target: "_blank", rel: "noopener" }, icon(ICONS.github), "Open github.com/login/device"), copy),
          status,
          h("p", { class: "muted" }, "Game Codex will ask for access to your repositories so it can keep your games in a private one. ", h("button", { type: "button", class: "btn ghost sm", onclick: tokenView }, "Use a token instead")));
        status.textContent = "Waiting for you to approve on GitHub…";
        let wait = (j.interval || 5) * 1000;
        const end = Date.now() + (j.expires_in || 900) * 1000;
        while (!cancelled && Date.now() < end) {
          await new Promise((res) => setTimeout(res, wait));
          if (cancelled) return;
          const t = await Store.http("https://github.com/login/oauth/access_token", { method: "POST", headers: { Accept: "application/json", "Content-Type": "application/x-www-form-urlencoded" }, body: "client_id=" + encodeURIComponent(clientId) + "&device_code=" + encodeURIComponent(j.device_code) + "&grant_type=urn:ietf:params:oauth:grant-type:device_code" });
          const tj = t.json() || {};
          if (tj.access_token) { status.textContent = "Signed in. Setting up…"; await finishSignIn(tj.access_token); d.close(); return; }
          if (tj.error === "slow_down") wait += 5000;
          else if (tj.error === "access_denied") { status.textContent = "Sign-in was cancelled on GitHub."; return; }
          else if (tj.error === "expired_token") break;
          else if (tj.error && tj.error !== "authorization_pending") { status.textContent = tj.error_description || tj.error; return; }
        }
        if (!cancelled) status.textContent = "The code expired. Close this and try again.";
      } catch (e) { status.textContent = e.message; }
    }

    clientId ? deviceView() : tokenView();
  }

  async function signOut(quiet) {
    clearTimeout(timer);
    if (Store.native) { try { await Store.request({ type: "token-clear" }); } catch (e) { /* ignore */ } }
    const s = settings();
    delete s.account;
    s.syncDeleted = {};
    s.lastSync = 0;
    app.saveSettings();
    state = "off";
    if (!quiet) { app.render(); toast("Signed out. Your games stay on this device."); }
  }

  // ---------- UI ----------
  function open(anchor) {
    const a = account();
    if (!a) { signInDialog(); return; }
    menu(anchor, [
      h("div", { class: "menu-head" }, a.avatar ? h("img", { class: "avatar", src: a.avatar, alt: "" }) : icon(ICONS.user),
        h("div", null, h("strong", null, a.name || a.login), h("small", null, statusText()))),
      "-",
      { label: "Sync now", icon: ICONS.refresh, onclick: () => sync().then(() => toast(state === "error" ? lastError : "Your games are synced")) },
      { label: "Open my saves on GitHub", icon: ICONS.github, onclick: () => openUrl("https://github.com/" + a.login + "/" + repoName()) },
      "-",
      { label: "Sign out", icon: ICONS.logout, onclick: () => ask({ title: "Sign out?", text: "Your games stay on this device and in your GitHub account. They just stop syncing until you sign in again.", confirm: "Sign out" }).then((y) => y && signOut(false)) },
    ]);
  }

  function openUrl(url) {
    const a = h("a", { href: url, target: "_blank", rel: "noopener" });
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  function settingsPanel(closeSettings) {
    const a = account();
    if (!a) {
      return h("div", null,
        h("p", null, "Sign in with GitHub to keep your games in a private repository on your account, so they're safe and show up on your computer and your tablet."),
        h("button", { type: "button", class: "btn primary", onclick: () => { closeSettings(); signInDialog(); } }, icon(ICONS.github), "Sign in with GitHub"));
    }
    return h("div", null,
      h("div", { class: "account-card" }, a.avatar ? h("img", { class: "avatar", src: a.avatar, alt: "" }) : icon(ICONS.user),
        h("div", null, h("strong", null, a.name || a.login), h("span", { class: "muted" }, "github.com/" + a.login + "/" + repoName() + " (private)"),
          h("div", { class: "muted", id: "sync-status", "aria-live": "polite" }, statusText()))),
      h("div", { class: "row-btns" },
        h("button", { type: "button", class: "btn ghost", onclick: () => sync() }, icon(ICONS.refresh), "Sync now"),
        h("button", { type: "button", class: "btn ghost", onclick: () => { closeSettings(); signOut(false); } }, icon(ICONS.logout), "Sign out")));
  }

  function noteDeleted(id) {
    if (!account()) return;
    const s = settings();
    s.syncDeleted = Object.assign({}, s.syncDeleted, { [id]: Date.now() });
    app.saveSettings();
    schedule(3000);
  }

  function init(appApi, signedIn) {
    app = appApi;
    if (account() && !signedIn && Store.native) { delete settings().account; app.saveSettings(); }
    Store.onSave(() => schedule(15000));
    if (account()) { state = "ok"; setTimeout(sync, 2500); }
    // pick up changes made on your other devices
    setInterval(() => { if (account() && state !== "busy") sync(); }, 5 * 60 * 1000);
  }

  return { init, open, sync, signOut, settingsPanel, noteDeleted, status: () => state, signInDialog };
})();
