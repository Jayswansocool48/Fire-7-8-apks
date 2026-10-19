/* Game Codex: the library, the top bar, game screens, import/export, settings and updates. */
(function () {
  const { h, icon, ICONS, uid, toast, dialog, ask, menu, Store, SECTIONS, sectionById } = Codex;

  const loaded = Store.init();
  const data = { projects: loaded.projects, settings: Object.assign({ theme: "system", autoUpdate: true, favorites: [], opened: {}, libSort: "opened", sound: { on: true, vol: 0.6 } }, loaded.settings) };
  Codex.sfx.configure(data.settings.sound || {});
  SECTIONS.forEach((s) => data.projects.forEach((p) => { p.entries = p.entries || {}; p.entries[s.id] = p.entries[s.id] || []; }));
  const view = { page: "library", project: null, section: "overview", entry: null, search: "", filter: "", libSearch: "" };
  let info = { version: Store.version, install: Store.platform };
  let update = null; // {latest, canInstall, text} once a newer version is found
  const appEl = document.getElementById("app");

  // ---------- data ----------
  function project() { return data.projects.find((p) => p.id === view.project) || null; }

  // Each new game starts with its own palette, which also colors its box art until it has a cover.
  const PALETTES = [
    ["#3b1f5e", "#ff6b9d", "#ffd166", "#2ec4b6"], ["#0f4c5c", "#e36414", "#fb8b24", "#9a031e"], ["#1d3557", "#e63946", "#f1faee", "#a8dadc"],
    ["#283618", "#dda15e", "#fefae0", "#bc6c25"], ["#2b2d42", "#ef233c", "#edf2f4", "#8d99ae"], ["#003049", "#f77f00", "#fcbf49", "#d62828"],
    ["#10002b", "#7b2cbf", "#e0aaff", "#3c096c"], ["#06402b", "#2dc653", "#e2f9b8", "#155d27"], ["#3d0c11", "#d4a373", "#faedcd", "#9c6644"],
    ["#0b2545", "#13c4a3", "#eef4ed", "#8da9c4"],
  ];
  function newProject(fields) {
    const used = data.projects.map((x) => (x.palette || [])[0]);
    const free = PALETTES.filter((pal) => !used.includes(pal[0]));
    const pal = (free.length ? free : PALETTES)[Math.floor(Math.random() * (free.length || PALETTES.length))].slice();
    const p = Object.assign({ id: uid("p"), name: "Untitled game", genre: [], platforms: [], pitch: "", audience: "", artStyle: "", palette: pal, notes: "", media: [], created: Date.now(), entries: {} }, fields);
    SECTIONS.forEach((s) => { p.entries[s.id] = p.entries[s.id] || []; });
    data.projects.push(p);
    Store.saveProject(p, true);
    return p;
  }

  function changed(opts) {
    const p = project();
    if (!p) return;
    Store.saveProject(p);
    if (opts && opts.list) { renderList(); renderTopbar(); }
  }

  function saveSettings() { Store.saveSettings(data.settings); }

  // Files a 3D scene uses (painted textures, uploaded textures and backdrops) are written inside its
  // settings as "media:<id>" (see models.js). These find and rewrite them.
  const MEDIA_REF = /^media:([A-Za-z0-9_-]{1,64}\.[a-z0-9]+)(\?.*)?$/;
  function sceneMedia(obj, ids) {
    if (typeof obj === "string") { const m = MEDIA_REF.exec(obj); if (m) ids.add(m[1]); }
    else if (obj && typeof obj === "object") Object.values(obj).forEach((v) => sceneMedia(v, ids));
    return ids;
  }
  function remapScene(obj, fn) {
    if (typeof obj === "string") { const m = MEDIA_REF.exec(obj); return m ? "media:" + fn(m[1]) + (m[2] || "") : obj; }
    if (Array.isArray(obj)) return obj.map((v) => remapScene(v, fn));
    if (obj && typeof obj === "object") { const out = {}; Object.keys(obj).forEach((k) => { out[k] = remapScene(obj[k], fn); }); return out; }
    return obj;
  }
  function entryMedia(e, ids) {
    ids = ids || new Set();
    (e.art || []).forEach((a) => { if (a.id) ids.add(a.id); if (a.poster) ids.add(a.poster); if (a.v3d) sceneMedia(a.v3d, ids); });
    if (e.studio) sceneMedia(e.studio, ids);
    return ids;
  }

  // Every file a game uses: entry art, model posters, 3D scene files and the cover.
  function mediaOf(p) {
    const ids = new Set();
    if (p.cover) ids.add(p.cover);
    SECTIONS.forEach((s) => (p.entries[s.id] || []).forEach((e) => entryMedia(e, ids)));
    return [...ids];
  }

  function createEntry(sectionId, fields, opts) {
    const p = project();
    const s = sectionById(sectionId);
    const e = Object.assign({ id: uid("e"), name: "", created: Date.now(), art: [] }, fields || {});
    if (sectionId === "story" && !e.kind) e.kind = (view.section === "story" && view.filter) || "Storyline";
    if ((sectionId === "levels" || (sectionId === "story" && e.kind === "Chapter")) && e.order == null) {
      e.order = p.entries[sectionId].filter((x) => sectionId === "levels" || x.kind === "Chapter").length + 1;
    }
    if (e.stats === undefined) {
      const sf = s.fields.find((f) => f.type === "stats");
      if (sf) e.stats = sf.defaults.map((n) => [n, 0]);
    }
    p.entries[sectionId].push(e);
    changed();
    if (!(opts && opts.silent)) go(sectionId, e.id, { focusName: !e.name });
    return e;
  }

  function duplicateEntry(sectionId, id) {
    const p = project();
    const e = p.entries[sectionId].find((x) => x.id === id);
    const copy = JSON.parse(JSON.stringify(e));
    copy.id = uid("e");
    copy.name = (e.name || "Untitled") + " (copy)";
    copy.created = Date.now();
    // the copy gets its own files so deleting one doesn't break the other
    const dup = (mid) => {
      if (!mid) return mid;
      const url = Store.mediaUrl(mid);
      return url.startsWith("data:") ? addMedia(url) : copyMedia(mid);
    };
    const copied = {};
    const dupOnce = (mid) => (copied[mid] = copied[mid] || dup(mid));
    copy.art = (e.art || []).map((a) => Object.assign({}, a, { id: dupOnce(a.id), poster: a.poster ? dupOnce(a.poster) : undefined, v3d: a.v3d ? remapScene(a.v3d, dupOnce) : undefined }));
    if (e.studio) copy.studio = remapScene(e.studio, dupOnce);
    p.entries[sectionId].splice(p.entries[sectionId].indexOf(e) + 1, 0, copy);
    changed();
    go(sectionId, copy.id);
    toast("Duplicated " + (e.name || "it"));
  }

  async function deleteEntry(sectionId, id) {
    const p = project();
    const s = sectionById(sectionId);
    const list = p.entries[sectionId];
    const e = list.find((x) => x.id === id);
    if (!(await ask({ title: "Delete " + (e.name || "this " + s.one) + "?", text: "Its notes and files are removed for good. Links to it from other entries are removed too.", confirm: "Delete", danger: true }))) return;
    const ids = [...entryMedia(e)];
    list.splice(list.indexOf(e), 1);
    const still = new Set(mediaOf(p));
    removeMediaIds(ids.filter((m) => !still.has(m)));
    SECTIONS.forEach((sec) => (p.entries[sec.id] || []).forEach((x) => sec.fields.forEach((f) => {
      if (f.type === "links" && f.to === sectionId && x[f.key]) x[f.key] = x[f.key].filter((l) => l.id !== id);
    })));
    Store.saveProject(p, true);
    view.entry = null;
    render();
    toast("Deleted " + (e.name || "the " + s.one));
  }

  function addMedia(dataUrl) {
    const id = Store.saveMedia(dataUrl);
    project().media.push(id);
    return id;
  }

  function copyMedia(id) {
    const newId = uid("m") + id.slice(id.lastIndexOf("."));
    Store.post({ type: "copy-media", from: id, to: newId });
    project().media.push(newId);
    return newId;
  }

  function removeMediaIds(ids) {
    const p = project();
    ids = ids.filter(Boolean);
    p.media = (p.media || []).filter((m) => !ids.includes(m));
    Store.deleteMedia(ids);
  }

  function addArt(entry, dataUrl, kind, caption) {
    entry.art = entry.art || [];
    entry.art.push({ id: addMedia(dataUrl), kind, caption: caption || "" });
    changed({ list: true });
  }
  function addFile(entry, f) {
    entry.art = entry.art || [];
    const item = { id: f.id, kind: f.kind, name: f.name, size: f.size, caption: (f.name || "").replace(/\.[^.]+$/, "") };
    entry.art.push(item);
    project().media.push(f.id);
    changed({ list: true });
    return item;
  }
  function setPoster(entry, item, posterId) {
    const old = item.poster;
    item.poster = posterId;
    project().media.push(posterId);
    if (old) removeMediaIds([old]);
    changed({ list: true });
  }
  function removeArt(entry, index) {
    const [a] = entry.art.splice(index, 1);
    const still = new Set(mediaOf(project()));
    removeMediaIds([a.id, a.poster, ...sceneMedia(a.v3d, new Set())].filter((m) => m && !still.has(m)));
    changed({ list: true });
  }
  // A file the 3D studio replaced (an older copy of a painted texture).
  function forgetMedia(ids) {
    const still = new Set(mediaOf(project()));
    removeMediaIds(ids.filter((m) => !still.has(m)));
  }

  // ---------- production stage ----------
  function setStage(entry, stage) {
    if (Codex.stageOf(entry) === stage) return;
    entry.prod = stage;
    changed({ list: true });
    Codex.sfx.play(stage === "Complete" ? "complete" : stage === "Modeled" ? "modeled" : "tick");
    if (stage === "Complete") toast((entry.name || "It") + " is complete");
    renderTopbar();
  }
  function productionCounts(p) {
    const c = { Planned: 0, Modeled: 0, Complete: 0, total: 0 };
    Codex.PRODUCTION_SECTIONS.forEach((id) => (p.entries[id] || []).forEach((e) => { c[Codex.stageOf(e)]++; c.total++; }));
    return c;
  }
  function replaceArt(entry, index, dataUrl) {
    const old = entry.art[index].id;
    entry.art[index] = Object.assign({}, entry.art[index], { id: addMedia(dataUrl), kind: "sketch" });
    removeMediaIds([old]);
    changed({ list: true });
  }

  // How far along the design is: the checklist on the overview and the badge in the library.
  function checklist(p) {
    const n = (id) => (p.entries[id] || []).length;
    const story = p.entries.story || [];
    return [
      ["A pitch", !!(p.pitch || "").trim()],
      ["A genre", (p.genre || []).length > 0],
      ["Cover art", !!p.cover],
      ["An art style", !!(p.artStyle || "").trim()],
      ["A main character", (p.entries.characters || []).some((c) => c.role === "Protagonist") || n("characters") > 0],
      ["Three characters", n("characters") >= 3],
      ["A prop", n("props") > 0],
      ["A storyline", story.some((s) => s.kind === "Storyline")],
      ["A quest", story.some((s) => s.kind === "Quest")],
      ["A core mechanic", n("mechanics") > 0],
      ["A first level", n("levels") > 0],
      ["A teaser or trailer", (p.entries.media || []).some((m) => /Teaser|Trailer/.test(m.type || "") || (m.art || []).some((a) => a.kind === "video"))],
    ];
  }
  function progress(p) {
    const c = checklist(p);
    return Math.round((c.filter((x) => x[1]).length / c.length) * 100);
  }
  function entryCount(p) { return SECTIONS.reduce((n, s) => n + (p.entries[s.id] || []).length, 0); }

  // ---------- navigation ----------
  function go(section, entryId, opts) {
    const sectionChanged = view.section !== section;
    view.page = "game";
    view.section = section;
    view.entry = entryId || null;
    if (sectionChanged) { view.search = ""; view.filter = ""; }
    render();
    if (opts && opts.focusName) requestAnimationFrame(() => { const n = document.querySelector(".sheet-name"); if (n) n.focus(); });
    else if (entryId) requestAnimationFrame(() => { const c = document.querySelector('.card[aria-current="true"]'); if (c) c.scrollIntoView({ block: "nearest" }); });
  }

  function openProject(id) {
    view.project = id;
    data.settings.lastProject = id;
    data.settings.opened = Object.assign({}, data.settings.opened, { [id]: Date.now() });
    saveSettings();
    go("overview");
  }

  function showLibrary() {
    view.page = "library";
    render();
  }

  // ---------- rendering ----------
  function applyTheme() {
    const t = data.settings.theme;
    if (t === "system") document.documentElement.removeAttribute("data-theme");
    else document.documentElement.setAttribute("data-theme", t);
  }

  function render() {
    applyTheme();
    if (view.page === "game" && !project()) view.page = "library";
    appEl.innerHTML = "";
    appEl.appendChild(h("header", { class: "topbar", id: "topbar" }));
    if (update && !data.settings.hideUpdate) appEl.appendChild(updateBanner());
    const pageEl = h("main", { class: "page", id: "main" });
    appEl.appendChild(pageEl);
    renderTopbar();
    if (Codex.Assistant) Codex.Assistant.refresh();
    if (view.page === "library") { pageEl.append(...library()); return; }
    const p = project();
    if (view.section === "overview") pageEl.appendChild(overview(p));
    else if (view.section === "production") pageEl.appendChild(Codex.Production.render({ project: p, app: api, go, changed, rerender: render }));
    else {
      const s = sectionById(view.section);
      const split = h("div", { class: "split tone-" + s.color + (view.entry ? " has-entry" : "") },
        h("section", { class: "list-pane", id: "list", "aria-label": s.name }),
        h("div", { class: "editor-pane", id: "editor" }));
      pageEl.appendChild(split);
      renderList();
      renderEditor();
    }
  }

  function markSvg(size) {
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", "0 0 64 64");
    svg.setAttribute("aria-hidden", "true");
    if (size) { svg.setAttribute("width", size); svg.setAttribute("height", size); }
    svg.innerHTML = Codex.LOGO_SVG;
    return svg;
  }

  function renderTopbar() {
    const bar = document.getElementById("topbar");
    if (!bar) return;
    bar.innerHTML = "";
    const p = project();
    bar.appendChild(h("button", { type: "button", class: "mark", title: "Library", "aria-label": "Library", onclick: showLibrary }, markSvg()));
    bar.appendChild(h("button", { type: "button", class: "tb-link tb-hide-narrow", "aria-current": view.page === "library" ? "page" : null, onclick: showLibrary }, "Library"));
    if (p && view.page === "game") {
      bar.appendChild(h("span", { class: "tb-sep tb-hide-narrow", "aria-hidden": "true" }));
      bar.appendChild(h("button", { type: "button", class: "tb-game", "aria-haspopup": "menu", title: "Game menu", onclick: (e) => gameMenu(e.currentTarget, p) },
        h("span", null, p.name || "Untitled game"), icon(ICONS.chevron)));
      const tab = (id, label, tone, d, count) => h("button", {
        type: "button", class: "tab" + (tone ? " tone-" + tone : ""), "aria-current": view.section === id ? "page" : null, title: label,
        onclick: () => go(id),
      }, icon(d), h("span", null, label), count != null ? h("span", { class: "count" }, count) : null);
      const pc = productionCounts(p);
      bar.appendChild(h("nav", { class: "tabs", "aria-label": "Sections" },
        tab("overview", "Overview", null, ICONS.home),
        tab("production", "Production", "fire", ICONS.flame, pc.total ? pc.Complete + "/" + pc.total : null),
        SECTIONS.map((s) => tab(s.id, s.name, s.color, s.icon, p.entries[s.id].length))));
    } else bar.appendChild(h("span", { class: "tb-spacer" }));
    const account = data.settings.account;
    const sync = Codex.Account ? Codex.Account.status() : "off";
    bar.appendChild(h("div", { class: "tb-end" },
      h("button", { type: "button", class: "icon-btn" + (Codex.Assistant && Codex.Assistant.isOpen() ? " on" : ""), id: "ai-btn", title: "Assistant (Ctrl+J)", "aria-label": "Assistant", "aria-pressed": String(!!(Codex.Assistant && Codex.Assistant.isOpen())), onclick: () => Codex.Assistant.toggle() }, icon(ICONS.spark)),
      h("button", { type: "button", class: "icon-btn", title: "Idea generators", "aria-label": "Idea generators", onclick: () => ideasDialog() }, icon(ICONS.dice)),
      h("button", { type: "button", class: "icon-btn avatar-btn", title: account ? "Signed in as " + account.login : "Sign in to keep your games", "aria-label": "Account", onclick: (e) => Codex.Account.open(e.currentTarget) },
        account && account.avatar ? h("img", { class: "avatar", src: account.avatar, alt: "" }) : icon(ICONS.user),
        account ? h("span", { class: "sync-dot " + sync, "aria-hidden": "true" }) : null),
      h("button", { type: "button", class: "icon-btn", title: "Settings", "aria-label": "Settings", onclick: settingsDialog }, icon(ICONS.gear))));
  }

  function gameMenu(anchor, p) {
    const fav = (data.settings.favorites || []).includes(p.id);
    menu(anchor, [
      { label: "Back to library", icon: ICONS.grid, onclick: showLibrary },
      { label: fav ? "Remove from favorites" : "Add to favorites", icon: ICONS.star, onclick: () => toggleFav(p.id) },
      "-",
      { label: "Export…", icon: ICONS.download, onclick: () => exportDialog(p) },
      { label: "Store kit for Steam or Roblox…", icon: ICONS.store, onclick: () => Codex.StoreKit.open(p, api) },
      { label: "Update from a .gamecodex file…", icon: ICONS.upload, onclick: () => Store.importProject(p.id) },
      Store.can("kiln") ? { label: "Import assets from Kiln…", icon: ICONS.flame, onclick: () => Codex.Kiln.open(p) } : null,
      "-",
      { label: "Delete this game", icon: ICONS.trash, danger: true, onclick: () => deleteProject(p) },
    ]);
  }

  function toggleFav(id) {
    const f = data.settings.favorites = (data.settings.favorites || []).slice();
    const i = f.indexOf(id);
    if (i < 0) f.push(id); else f.splice(i, 1);
    saveSettings();
    render();
  }

  // ---------- library ----------
  // Box art: the game's cover, or one made from its name and palette.
  function coverArt(p) {
    if (p.cover) return h("img", { src: Store.mediaUrl(p.cover), alt: "", loading: "lazy" });
    const pal = (p.palette && p.palette.length ? p.palette : ["#24345e", "#ff7f6e", "#f5c04a"]);
    const el = h("span", { class: "gen-cover", "aria-hidden": "true" }, h("b", null, p.name || "Untitled game"), (p.genre || []).length ? h("small", null, p.genre.slice(0, 2).join(" · ")) : null);
    el.style.setProperty("--c1", pal[0]);
    el.style.setProperty("--c2", pal[1] || pal[0]);
    el.style.setProperty("--c3", shade(pal[0], -0.45));
    return el;
  }

  function shade(hex, amt) {
    const n = parseInt((hex || "#333333").slice(1), 16);
    const f = (c) => Math.max(0, Math.min(255, Math.round(c + (amt < 0 ? c * amt : (255 - c) * amt))));
    return "#" + [n >> 16 & 255, n >> 8 & 255, n & 255].map(f).map((c) => c.toString(16).padStart(2, "0")).join("");
  }

  function sortedGames() {
    const opened = data.settings.opened || {};
    const list = data.projects.slice();
    const by = data.settings.libSort;
    if (by === "name") list.sort((a, b) => (a.name || "").localeCompare(b.name || ""));
    else if (by === "updated") list.sort((a, b) => (b.updated || 0) - (a.updated || 0));
    else if (by === "progress") list.sort((a, b) => progress(b) - progress(a));
    else if (by === "size") list.sort((a, b) => entryCount(b) - entryCount(a));
    else list.sort((a, b) => (opened[b.id] || b.updated || 0) - (opened[a.id] || a.updated || 0));
    return list;
  }

  function library() {
    if (!data.projects.length) return [h("div", { class: "lib-main" }, libraryEmpty())];
    const q = view.libSearch.toLowerCase();
    const games = sortedGames().filter((p) => !q || (p.name || "").toLowerCase().includes(q) || (p.genre || []).join(" ").toLowerCase().includes(q));
    const favs = data.settings.favorites || [];
    const az = data.projects.slice().sort((a, b) => (a.name || "").localeCompare(b.name || ""));

    const search = h("input", { type: "search", id: "lib-search", value: view.libSearch, placeholder: "Search games", "aria-label": "Search games" });
    search.addEventListener("input", () => {
      view.libSearch = search.value;
      const grid = document.getElementById("lib-grid");
      if (grid) { grid.replaceWith(libGrid(sortedGames().filter((p) => !search.value || (p.name || "").toLowerCase().includes(search.value.toLowerCase())))); }
    });
    const row = (p) => h("li", null, h("button", { type: "button", class: "lib-row", onclick: () => openProject(p.id) },
      p.cover ? h("img", { class: "mini", src: Store.mediaUrl(p.cover), alt: "" }) : h("span", { class: "mini", style: { background: "linear-gradient(160deg, " + ((p.palette || [])[1] || "#556") + ", " + ((p.palette || [])[0] || "#334") + ")" } }),
      h("span", null, p.name || "Untitled game")));
    const side = h("aside", { class: "lib-side", "aria-label": "Your games" },
      h("div", { class: "search" }, icon(ICONS.search), search),
      h("div", { class: "lib-groups" },
        favs.length ? [h("div", { class: "lib-group-title" }, icon(ICONS.star), "Favorites", h("span", { class: "n" }, "(" + favs.filter((id) => data.projects.some((p) => p.id === id)).length + ")")),
          h("ul", { class: "rows" }, az.filter((p) => favs.includes(p.id)).map(row))] : null,
        h("div", { class: "lib-group-title" }, "All games", h("span", { class: "n" }, "(" + az.length + ")")),
        h("ul", { class: "rows" }, az.map(row))),
      h("div", { class: "lib-side-foot" },
        h("button", { type: "button", class: "btn ghost sm", onclick: () => newGameDialog() }, icon(ICONS.plus), "New game"),
        h("button", { type: "button", class: "btn ghost sm", onclick: () => Store.importProject(null) }, icon(ICONS.upload), "Import a .gamecodex file"),
        Store.can("kiln") ? h("button", { type: "button", class: "btn ghost sm", onclick: () => Codex.Kiln.open(null) }, icon(ICONS.flame), "Import from Kiln") : null));

    const sort = h("select", { "aria-label": "Sort by" },
      [["opened", "Recently opened"], ["updated", "Recently edited"], ["name", "Name"], ["progress", "% of design planned"], ["size", "Most entries"]].map(([v, l]) => h("option", { value: v }, l)));
    sort.value = data.settings.libSort;
    sort.addEventListener("change", () => { data.settings.libSort = sort.value; saveSettings(); render(); });

    const main = h("section", { class: "lib-main", "aria-label": "Library" },
      h("header", { class: "lib-head" },
        h("h1", null, "All games", h("span", { class: "n" }, "(" + data.projects.length + ")")),
        h("label", { class: "sort" }, "Sort by", sort),
        h("span", { class: "grow" }),
        h("button", { type: "button", class: "btn primary", onclick: () => newGameDialog() }, icon(ICONS.plus), "New game")),
      libGrid(games));
    return [side, main];
  }

  function libGrid(games) {
    const favs = data.settings.favorites || [];
    const grid = h("div", { class: "lib-grid", id: "lib-grid" });
    games.forEach((p) => {
      const pct = progress(p);
      const fav = favs.includes(p.id);
      grid.appendChild(h("div", { class: "game-card" },
        h("button", { type: "button", class: "game-cover", "aria-label": "Open " + (p.name || "Untitled game") + ", " + pct + "% planned", onclick: () => openProject(p.id) },
          coverArt(p),
          h("span", { class: "badge" + (pct === 100 ? " done" : "") }, pct === 100 ? icon(ICONS.check) : null, pct + "% planned")),
        h("button", { type: "button", class: "fav", "aria-pressed": String(fav), "aria-label": (fav ? "Remove " : "Add ") + (p.name || "this game") + (fav ? " from" : " to") + " favorites", onclick: () => toggleFav(p.id) }, icon(ICONS.star)),
        h("div", { class: "game-title" }, p.name || "Untitled game"),
        h("div", { class: "game-meta" }, entryCount(p) + " entries" + ((p.genre || []).length ? " · " + p.genre.slice(0, 2).join(", ") : ""))));
    });
    grid.appendChild(h("div", { class: "game-card new-card" },
      h("button", { type: "button", class: "game-cover", onclick: () => newGameDialog() }, h("span", null, icon(ICONS.plus), "New game"))));
    return grid;
  }

  function libraryEmpty() {
    const name = h("input", { id: "new-name", placeholder: "Working title", "aria-label": "Game name", autofocus: true });
    const create = () => { const p = newProject({ name: name.value.trim() || "Untitled game" }); openProject(p.id); };
    name.addEventListener("keydown", (e) => { if (e.key === "Enter") create(); });
    const signedIn = !!data.settings.account;
    return h("div", { class: "lib-empty" },
      h("div", { class: "mark-big", "aria-hidden": "true" }, markSvg(96)),
      h("h1", null, "Your library is empty"),
      h("p", null, "Every game you design lives here: characters, props, story, quests, mechanics, levels, teasers and 3D models."),
      h("div", { class: "welcome-form" }, name, h("button", { type: "button", class: "btn primary", onclick: create }, "Start a game")),
      h("div", { class: "row-btns" },
        h("button", { type: "button", class: "btn ghost", onclick: () => { const p = Codex.sampleGame(newProject); openProject(p.id); } }, "Open a sample game"),
        h("button", { type: "button", class: "btn ghost", onclick: () => Store.importProject(null) }, icon(ICONS.upload), "Import a game"),
        Store.can("kiln") ? h("button", { type: "button", class: "btn ghost", onclick: () => Codex.Kiln.open(null) }, icon(ICONS.flame), "Import from Kiln") : null,
        signedIn ? null : h("button", { type: "button", class: "btn ghost", onclick: (e) => Codex.Account.open(e.currentTarget) }, icon(ICONS.github), "Sign in to get your games")));
  }

  function newGameDialog() {
    const name = h("input", { placeholder: "Working title", "aria-label": "Game name", autofocus: true });
    const create = () => { d.close(); const p = newProject({ name: name.value.trim() || "Untitled game" }); openProject(p.id); };
    name.addEventListener("keydown", (e) => { if (e.key === "Enter") create(); });
    const d = dialog({
      title: "New game", class: "small",
      body: [name, h("p", { class: "muted" }, "You can change the name any time.")],
      actions: [
        h("button", { type: "button", class: "btn ghost", onclick: () => { d.close(); const p = Codex.sampleGame(newProject); openProject(p.id); } }, "Sample game instead"),
        h("button", { type: "button", class: "btn primary", onclick: create }, "Create game")],
    });
  }

  // ---------- list and editor ----------
  function entryThumb(s, e) {
    const a = (e.art || [])[0];
    const src = a ? (a.kind === "model" ? a.poster : a.kind === "video" || a.kind === "audio" ? null : a.id) : null;
    if (src) return h("img", { class: "thumb", src: Store.mediaUrl(src), alt: "", loading: "lazy" });
    if (a && (a.kind === "video" || a.kind === "audio")) return h("span", { class: "thumb initial", "aria-hidden": "true" }, icon(a.kind === "video" ? ICONS.video : ICONS.music));
    return h("span", { class: "thumb initial", "aria-hidden": "true" }, (e.name || "?").trim().charAt(0).toUpperCase() || "?");
  }

  function filterKey(s) {
    return { characters: "role", props: "type", story: "kind", mechanics: "category", levels: null, media: "type" }[s.id];
  }

  function sortedEntries(p, s) {
    let list = p.entries[s.id].slice();
    if (s.id === "levels") list.sort((a, b) => (a.order || 999) - (b.order || 999));
    if (s.id === "story") {
      const rank = { Storyline: 0, Chapter: 1, Quest: 2, Dialogue: 3 };
      list.sort((a, b) => (rank[a.kind] - rank[b.kind]) || ((a.order || 999) - (b.order || 999)));
    }
    return list;
  }

  function matches(e, q) {
    if (!q) return true;
    q = q.toLowerCase();
    return JSON.stringify([e.name, e.kind, e.role, e.type, e.summary, e.description, e.traits, e.tags, e.notes, e.biome, e.category, e.look, e.backstory, e.group, e.prod]).toLowerCase().includes(q);
  }

  function renderList() {
    const el = document.getElementById("list");
    const p = project();
    if (!el || !p) return;
    const s = sectionById(view.section);
    const scroll = el.querySelector(".cards") ? el.querySelector(".cards").scrollTop : 0;
    const searchHadFocus = document.activeElement && document.activeElement.id === "search";
    el.innerHTML = "";
    const all = sortedEntries(p, s);
    const fk = filterKey(s);
    const filters = fk ? [...new Set(all.map((e) => e[fk]).filter(Boolean))] : [];
    if (s.id === "story") SECTIONS[2].kinds.forEach((k) => { if (!filters.includes(k)) filters.push(k); });
    const shown = all.filter((e) => matches(e, view.search) && (!view.filter || e[fk] === view.filter));

    const search = h("input", { id: "search", type: "search", value: view.search, placeholder: "Search " + s.name.toLowerCase(), "aria-label": "Search " + s.name.toLowerCase() });
    search.addEventListener("input", () => { view.search = search.value; renderList(); });
    search.addEventListener("keydown", (e) => {
      if (e.key === "ArrowDown") { e.preventDefault(); const c = el.querySelector(".card"); if (c) c.focus(); }
    });

    el.appendChild(h("header", { class: "list-head" },
      h("h1", null, s.name),
      h("div", { class: "list-actions" },
        s.id === "media" ? null : h("button", { type: "button", class: "btn ghost sm", title: "Random " + s.one, onclick: () => ideasDialog(s.id) }, icon(ICONS.dice), "Roll"),
        h("button", { type: "button", class: "btn tone sm", title: "New " + s.one + " (Ctrl+N)", onclick: () => createEntry(s.id) }, icon(ICONS.plus), "New"))));
    el.appendChild(h("div", { class: "search" }, icon(ICONS.search), search));
    if (filters.length > 1 || (s.id === "story" && filters.length)) {
      el.appendChild(h("div", { class: "filters", role: "group", "aria-label": "Filter" },
        h("button", { type: "button", class: "chip", "aria-pressed": String(!view.filter), onclick: () => { view.filter = ""; renderList(); } }, "All"),
        filters.map((f) => h("button", { type: "button", class: "chip", "aria-pressed": String(view.filter === f), onclick: () => { view.filter = view.filter === f ? "" : f; renderList(); } }, f))));
    }

    const ul = h("ul", { class: "cards", role: "list" });
    if (!all.length) {
      ul.appendChild(h("li", { class: "empty" },
        h("p", null, s.empty),
        h("div", { class: "row-btns" },
          h("button", { type: "button", class: "btn tone", onclick: () => createEntry(s.id) }, icon(ICONS.plus), "New " + s.one),
          s.id === "media" ? null : h("button", { type: "button", class: "btn ghost", onclick: () => ideasDialog(s.id) }, icon(ICONS.dice), "Roll one"))));
    } else if (!shown.length) {
      ul.appendChild(h("li", { class: "empty" }, h("p", null, "Nothing matches “" + (view.search || view.filter) + "”."),
        h("button", { type: "button", class: "btn ghost", onclick: () => { view.search = ""; view.filter = ""; renderList(); } }, "Clear search")));
    }
    let lastKind = null;
    shown.forEach((e) => {
      if (s.id === "story" && !view.filter && e.kind !== lastKind) { lastKind = e.kind; ul.appendChild(h("li", { class: "group", role: "presentation" }, e.kind + "s")); }
      const sub = Codex.Editor.subline(s, e);
      ul.appendChild(h("li", null, h("button", {
        type: "button", class: "card", "aria-current": view.entry === e.id ? "true" : null, dataset: { id: e.id },
        onclick: () => go(s.id, e.id),
        onkeydown: (ev) => {
          const cards = [...ul.querySelectorAll(".card")];
          const i = cards.indexOf(ev.currentTarget);
          if (ev.key === "ArrowDown" && cards[i + 1]) { ev.preventDefault(); cards[i + 1].focus(); }
          if (ev.key === "ArrowUp") { ev.preventDefault(); (cards[i - 1] || search).focus(); }
        },
      }, entryThumb(s, e), h("span", { class: "card-text" }, h("strong", null, e.name || "Untitled " + s.one), sub ? h("small", null, sub) : null,
        e.traits && e.traits.length ? h("span", { class: "card-tags" }, e.traits.slice(0, 3).join(", ")) : null),
        Codex.inProduction(s.id) ? h("span", { class: "stage-dot s-" + Codex.stageOf(e).toLowerCase(), title: Codex.stageOf(e), "aria-label": Codex.stageOf(e) }) : null)));
    });
    el.appendChild(ul);
    ul.scrollTop = scroll;
    if (searchHadFocus) { search.focus(); search.setSelectionRange(search.value.length, search.value.length); }
  }

  function renderEditor() {
    const el = document.getElementById("editor");
    const p = project();
    if (!el || !p) return;
    const s = sectionById(view.section);
    const e = p.entries[s.id].find((x) => x.id === view.entry);
    el.innerHTML = "";
    if (!e) {
      el.appendChild(h("div", { class: "editor-empty" },
        h("p", null, p.entries[s.id].length ? "Pick a " + s.one + " from the list to see its sheet." : s.empty)));
      return;
    }
    const scroll = el.scrollTop;
    const ctx = {
      project: p, section: s, entry: e, app: api,
      changed, go,
      rerender: () => { const y = el.scrollTop; renderEditor(); renderList(); el.scrollTop = y; },
    };
    el.appendChild(h("button", { type: "button", class: "btn ghost sm back-btn", onclick: () => go(s.id) }, "Back to " + s.name.toLowerCase()));
    el.appendChild(Codex.Editor.render(ctx));
    el.scrollTop = scroll;
  }

  // ---------- overview ----------
  function overview(p) {
    const wrap = h("div", { class: "overview" });
    const name = h("input", { class: "ov-name", value: p.name, "aria-label": "Game name", spellcheck: "false" });
    name.addEventListener("input", () => { p.name = name.value; changed(); const n = document.querySelector(".tb-game span"); if (n) n.textContent = p.name || "Untitled game"; });
    const pitch = Codex.autosize(h("textarea", { class: "ov-pitch", rows: 2, placeholder: "Your game in one or two sentences. What does the player do, and why is it fun?", "aria-label": "Pitch" }));
    pitch.value = p.pitch || "";
    pitch.addEventListener("input", () => { p.pitch = pitch.value; changed(); });
    const pct = progress(p);

    const hero = h("header", { class: "ov-hero" },
      p.cover ? h("div", { class: "ov-hero-bg", style: { backgroundImage: "url(\"" + Store.mediaUrl(p.cover) + "\")" } }) : null,
      cover(p),
      h("div", { class: "ov-title" }, name, pitch,
        h("div", { class: "progress", title: "Your design checklist below" }, h("strong", null, pct + "%"), h("span", { class: "progress-bar" }, h("i", { style: { width: pct + "%" } })), "planned"),
        (() => {
          const c = productionCounts(p), made = c.total ? Math.round((c.Complete / c.total) * 100) : 0;
          return c.total ? h("button", { type: "button", class: "progress made", title: c.Complete + " of " + c.total + " characters, props and levels complete", onclick: () => go("production") },
            h("strong", null, made + "%"), h("span", { class: "progress-bar" }, h("i", { style: { width: made + "%" } })), "made") : null;
        })(),
        h("div", { class: "row-btns" },
          h("button", { type: "button", class: "btn ghost sm", onclick: () => ideasDialog("game") }, icon(ICONS.dice), "Roll a game idea"),
          h("button", { type: "button", class: "btn ghost sm", onclick: () => Codex.StoreKit.open(p, api) }, icon(ICONS.store), "Store kit"))));
    wrap.appendChild(hero);

    const body = h("div", { class: "ov-body" });
    body.appendChild(h("section", { class: "shelf", "aria-label": "Sections" }, SECTIONS.map((s) => {
      const list = sortedEntries(p, s);
      const recent = list.slice().sort((a, b) => (b.created || 0) - (a.created || 0)).slice(0, 3);
      return h("div", { class: "spine tone-" + s.color },
        h("button", { type: "button", class: "spine-head", onclick: () => go(s.id) }, icon(s.icon), h("span", { class: "spine-n" }, list.length), h("span", null, s.name)),
        h("ul", null, recent.length ? recent.map((e) => h("li", null, h("button", { type: "button", onclick: () => go(s.id, e.id) }, e.name || "Untitled"))) : h("li", { class: "muted" }, "None yet")),
        h("button", { type: "button", class: "spine-add", "aria-label": "New " + s.one, onclick: () => { view.section = s.id; createEntry(s.id); } }, icon(ICONS.plus), "New " + s.one));
    })));

    body.appendChild(h("section", { class: "ov-section" }, h("h2", null, "Design checklist"),
      h("ul", { class: "todo" }, checklist(p).map(([label, done]) => h("li", { class: done ? "done" : "" }, icon(done ? ICONS.check : ICONS.plus), label, h("span", { class: "sr-only", style: { position: "absolute", left: "-9999px" } }, done ? " (done)" : " (to do)"))))));

    const details = h("section", { class: "fields ov-fields", "aria-label": "Game details" });
    const chipsField = (label, key, options) => h("div", { class: "field wide" }, h("span", { class: "label" }, label),
      h("div", { class: "chips", role: "group", "aria-label": label }, options.map((o) => h("button", {
        type: "button", class: "chip", "aria-pressed": String(p[key].includes(o)),
        onclick: (e) => { const i = p[key].indexOf(o); i < 0 ? p[key].push(o) : p[key].splice(i, 1); e.currentTarget.setAttribute("aria-pressed", String(i < 0)); changed(); },
      }, o))));
    details.appendChild(chipsField("Genre", "genre", Codex.GENRES));
    details.appendChild(chipsField("Platforms", "platforms", Codex.PLATFORMS));
    const text = (label, key, placeholder, area) => {
      const id = uid("f");
      const input = area ? Codex.autosize(h("textarea", { id, rows: 3, placeholder })) : h("input", { id, placeholder });
      input.value = p[key] || "";
      input.addEventListener("input", () => { p[key] = input.value; changed(); });
      return h("div", { class: "field" + (area ? " wide" : "") }, h("label", { for: id }, label), input);
    };
    details.appendChild(text("Who it's for", "audience", "Cozy players, speedrunners, ages 10+…"));
    details.appendChild(text("Art style", "artStyle", "Pixel art, low-poly, hand-drawn…"));
    details.appendChild(palette(p));
    details.appendChild(text("Inspirations", "inspirations", "Games, films and books it borrows from", true));
    details.appendChild(text("Notes", "notes", "Anything else", true));
    body.appendChild(h("section", { class: "ov-section" }, h("h2", null, "About the game"), details));

    body.appendChild(h("div", { class: "ov-actions" },
      h("button", { type: "button", class: "btn ghost", onclick: () => exportDialog(p) }, icon(ICONS.download), "Export"),
      h("button", { type: "button", class: "btn ghost", onclick: () => Store.importProject(p.id) }, icon(ICONS.upload), "Update from a .gamecodex file"),
      h("button", { type: "button", class: "btn ghost danger-text", onclick: () => deleteProject(p) }, icon(ICONS.trash), "Delete this game")));
    wrap.appendChild(body);
    return wrap;
  }

  function cover(p) {
    const file = h("input", { type: "file", accept: "image/*", hidden: true });
    file.addEventListener("change", async () => {
      const f = file.files[0];
      if (!f) return;
      try {
        const url = await Codex.readImage(f, 2000);
        if (p.cover) removeMediaIds([p.cover]);
        p.cover = addMedia(url);
        changed();
        render();
      } catch (err) { toast(err.message); }
    });
    const btn = h("button", { type: "button", class: "cover", "aria-label": p.cover ? "Change cover art" : "Add cover art", onclick: () => file.click() },
      coverArt(p), h("span", { class: "cover-hint" }, p.cover ? "Change cover" : "Add cover art"));
    return h("div", { class: "cover-wrap" }, btn, file,
      h("button", { type: "button", class: "btn ghost sm", onclick: () => Codex.Sketch.open({ title: "Cover for " + (p.name || "your game"), onSave: (d) => { if (p.cover) removeMediaIds([p.cover]); p.cover = addMedia(d); changed(); render(); } }) }, icon(ICONS.pencil), "Sketch a cover"));
  }

  function palette(p) {
    const wrap = h("div", { class: "field" }, h("span", { class: "label" }, "Color palette"));
    const row = h("div", { class: "palette" });
    function draw() {
      row.innerHTML = "";
      p.palette.forEach((c, i) => {
        const input = h("input", { type: "color", value: c, "aria-label": "Palette color " + (i + 1) });
        input.addEventListener("input", () => { p.palette[i] = input.value; changed(); });
        row.appendChild(h("span", { class: "pal" }, input,
          h("button", { type: "button", class: "icon-btn sm", "aria-label": "Remove color " + (i + 1), onclick: () => { p.palette.splice(i, 1); changed(); draw(); } }, icon(ICONS.x))));
      });
      if (p.palette.length < 8) row.appendChild(h("button", { type: "button", class: "icon-btn", "aria-label": "Add a color", onclick: () => { p.palette.push("#888888"); changed(); draw(); } }, icon(ICONS.plus)));
    }
    draw();
    wrap.appendChild(row);
    return wrap;
  }

  async function deleteProject(p) {
    if (!(await ask({ title: "Delete " + (p.name || "this game") + "?", text: "Everything in it is removed for good" + (data.settings.account ? ", on this device and in your GitHub account" : "") + ". Export it first if you want a copy.", confirm: "Delete game", danger: true }))) return;
    removeProject(p.id, true);
    view.page = "library";
    render();
    toast("Deleted " + (p.name || "the game"));
  }

  // Also used by sync when a game was deleted on another device (fromUser false).
  function removeProject(id, fromUser) {
    const p = data.projects.find((x) => x.id === id);
    if (!p) return;
    Store.deleteMedia(mediaOf(p).concat(p.media || []));
    Store.deleteProject(id);
    data.projects = data.projects.filter((x) => x.id !== id);
    data.settings.favorites = (data.settings.favorites || []).filter((x) => x !== id);
    if (fromUser && Codex.Account) Codex.Account.noteDeleted(id);
    saveSettings();
    if (view.project === id) { view.project = null; view.page = "library"; }
    render();
  }

  // Put a game from sync or an import in place. Keeps its updated time.
  function putProject(p, opts) {
    SECTIONS.forEach((s) => { p.entries = p.entries || {}; p.entries[s.id] = p.entries[s.id] || []; });
    ["media", "genre", "platforms", "palette"].forEach((k) => { p[k] = p[k] || []; });
    const i = data.projects.findIndex((x) => x.id === p.id);
    if (i >= 0) {
      const old = data.projects[i];
      const keep = new Set(mediaOf(p));
      Store.deleteMedia(mediaOf(old).filter((m) => !keep.has(m)));
      data.projects[i] = p;
    } else data.projects.push(p);
    Store.saveProject(p, true, { keepTime: !(opts && opts.touch) });
    if (view.page === "library" || view.project === p.id) render();
  }

  // ---------- import, and updating a game from a file ----------
  Store.on("imported", async (m) => {
    if (m.error) { toast(m.error); return; }
    const incoming = m.project;
    const target = m.target && data.projects.find((x) => x.id === m.target);
    const same = !target && data.projects.find((x) => x.id === incoming.id);
    const existing = target || same;
    if (existing) {
      const choice = await chooseUpdate(existing, incoming, !!target);
      if (choice === "cancel") { Store.deleteMedia(mediaOf(incoming)); return; }
      if (choice === "update") {
        incoming.id = existing.id;
        incoming.created = existing.created || incoming.created;
        putProject(incoming, { touch: true });
        openProject(existing.id);
        toast("Updated " + (existing.name || "the game") + " from the file");
        return;
      }
      incoming.id = uid("p");
      incoming.name = (incoming.name || "Untitled game") + " (copy)";
    }
    putProject(incoming, { touch: true });
    openProject(incoming.id);
    toast("Imported " + (incoming.name || "the game"));
  });

  function chooseUpdate(existing, incoming, explicit) {
    return new Promise((resolve) => {
      let result = "cancel";
      const when = (t) => (t ? new Date(t).toLocaleString([], { dateStyle: "medium", timeStyle: "short" }) : "unknown");
      const older = (incoming.updated || 0) < (existing.updated || 0);
      const d = dialog({
        title: explicit ? "Update " + (existing.name || "this game") + "?" : (existing.name || "This game") + " is already in your library",
        class: "small",
        body: h("div", { class: "stack" },
          h("p", null, "The file has “" + (incoming.name || "Untitled game") + "” with " + entryCount(incoming) + " entries, saved " + when(incoming.updated) + ". Your copy has " + entryCount(existing) + " entries, saved " + when(existing.updated) + "."),
          older ? h("p", { class: "danger-text", style: { color: "var(--danger)", margin: 0 } }, "The file is older than your copy.") : null,
          h("button", { type: "button", class: "option", onclick: () => { result = "update"; d.close(); } }, h("strong", null, "Update my game with the file"), h("small", null, "Replaces your copy with what's in the file.")),
          h("button", { type: "button", class: "option", onclick: () => { result = "copy"; d.close(); } }, h("strong", null, "Keep both"), h("small", null, "Adds the file as a separate game."))),
        actions: [h("button", { type: "button", class: "btn ghost", onclick: () => d.close() }, "Cancel")],
        onClose: () => resolve(result),
      });
    });
  }

  // ---------- idea generators ----------
  function ideasDialog(startWith) {
    const gens = Codex.Generators.list;
    let current = gens.find((g) => g.id === startWith) || gens.find((g) => g.section === startWith) || gens[0];
    let style = data.settings.genStyle || "fantasy";
    const history = [];
    const results = h("ul", { class: "ideas", "aria-live": "polite" });
    const tabs = h("div", { class: "chips", role: "group", "aria-label": "What to roll" });
    const styleSel = h("select", { "aria-label": "Style" }, Codex.Generators.STYLES.map(([v, l]) => h("option", { value: v }, l)));
    styleSel.value = style;
    styleSel.addEventListener("change", () => { style = styleSel.value; data.settings.genStyle = style; saveSettings(); roll(); });
    const styleBox = h("label", { class: "inline" }, "Style ", styleSel);

    function drawTabs() {
      tabs.innerHTML = "";
      gens.forEach((g) => tabs.appendChild(h("button", { type: "button", class: "chip" + (g.section ? " tone-" + sectionById(g.section).color : ""), "aria-pressed": String(g === current), onclick: () => { current = g; drawTabs(); roll(); } }, g.label)));
      styleBox.hidden = !current.styles;
    }

    function roll() {
      const r = current.run(current.styles ? style : undefined);
      history.unshift(r);
      if (history.length > 8) history.pop();
      drawResults();
    }

    function use(r) {
      if (!project() || view.page !== "game") {
        d.close();
        const p = newProject({ name: r.section ? "Untitled game" : "New game", pitch: r.section ? "" : r.text });
        openProject(p.id);
        if (r.section) createEntry(r.section, JSON.parse(JSON.stringify(r.fields)));
        toast("Started a new game with it");
        return;
      }
      if (!r.section) {
        const p = project();
        p.pitch = p.pitch ? p.pitch + "\n" + r.text : r.text;
        changed();
        d.close();
        go("overview");
        toast("Added to your pitch");
        return;
      }
      d.close();
      const f = JSON.parse(JSON.stringify(r.fields));
      createEntry(r.section, f);
      toast("Added " + (f.name || "it") + " to " + sectionById(r.section).name.toLowerCase());
    }

    function drawResults() {
      results.innerHTML = "";
      const inGame = project() && view.page === "game";
      history.forEach((r, i) => results.appendChild(h("li", { class: "idea" + (i === 0 ? " fresh" : "") + (r.section ? " tone-" + sectionById(r.section).color : "") },
        h("div", null, h("strong", null, r.title), h("p", null, r.text)),
        h("button", { type: "button", class: "btn " + (i === 0 ? "primary" : "ghost") + " sm", onclick: () => use(r) },
          !inGame ? "Start a game with it" : r.section ? "Add to " + sectionById(r.section).name.toLowerCase() : "Add to pitch"))));
    }

    const rollBtn = h("button", { type: "button", class: "btn primary roll", onclick: roll }, icon(ICONS.dice), "Roll again");
    const d = dialog({
      title: "Idea generators",
      class: "wide ideas-dialog",
      body: [h("p", { class: "muted" }, "Random starting points made on your device. Roll until something sparks, then add it and make it yours."), tabs, h("div", { class: "roll-row" }, rollBtn, styleBox), results],
    });
    d.el.addEventListener("keydown", (e) => { if (e.key === "r" && e.target.tagName !== "INPUT" && e.target.tagName !== "SELECT") { e.preventDefault(); roll(); } });
    drawTabs();
    roll();
    requestAnimationFrame(() => rollBtn.focus());
  }

  // ---------- export ----------
  function fileBase(p) { return (p.name || "game").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "game"; }

  function exportDialog(p) {
    const d = dialog({
      title: "Export " + (p.name || "this game"),
      class: "small",
      body: h("div", { class: "stack" },
        h("button", { type: "button", class: "option", onclick: () => { d.close(); Store.exportProject(p, Codex.toMarkdown(p, { images: true }), fileBase(p)); } },
          h("strong", null, "Game Codex file (.gamecodex)"), h("small", null, "Everything, including pictures, videos and models. Open it on another device, keep it as a backup, or use it to update a game.")),
        h("button", { type: "button", class: "option", onclick: () => { d.close(); Store.exportMarkdown(Codex.toMarkdown(p), fileBase(p)); } },
          h("strong", null, "Design document (Markdown)"), h("small", null, "A readable game design doc to share or paste into GitHub, Notion or Google Docs.")),
        h("button", { type: "button", class: "option", onclick: () => { d.close(); Codex.StoreKit.open(p, api); } },
          h("strong", null, "Store kit for Steam or Roblox"), h("small", null, "Store page text and every image size the store asks for, ready to upload."))),
    });
  }

  // ---------- updates ----------
  function updateBanner() {
    const u = update;
    const btn = u.canInstall
      ? h("button", { type: "button", class: "btn primary sm", onclick: (e) => { e.currentTarget.disabled = true; e.currentTarget.textContent = "Downloading…"; Store.post({ type: "install-update" }); } }, "Update now")
      : null;
    return h("div", { class: "banner", role: "status" },
      icon(ICONS.download),
      h("span", { class: "grow" }, h("strong", null, "Game Codex " + u.latest + " is available. "), u.canInstall ? "Your games are kept." : (u.text || "")),
      btn,
      h("button", { type: "button", class: "btn ghost sm", onclick: () => { update = null; render(); } }, "Later"));
  }

  function checkForUpdates(quiet) {
    if (!Store.native) return;
    updateQuiet = quiet;
    data.settings.lastUpdateCheck = Date.now();
    saveSettings();
    Store.post({ type: "check-update" });
  }
  let updateQuiet = false;
  let updateUi = () => {};

  Store.on("update", (m) => {
    if (m.status === "available") { update = m; if (updateQuiet) render(); }
    if (m.status === "installed") {
      update = null;
      render();
      if (m.restart !== false) toast("Version " + m.latest + " is installed. Restarting…");
      setTimeout(() => { Store.flush(data.projects); Store.post({ type: "restart" }); }, 1500);
    }
    if (m.status === "error" && !updateQuiet && !document.getElementById("update-status")) toast(m.text);
    if (m.status === "error" && document.querySelector(".banner .btn.primary")) { toast(m.text); render(); }
    updateUi(m);
  });

  // ---------- settings ----------
  function settingsDialog() {
    const theme = h("div", { class: "chips", role: "radiogroup", "aria-label": "Theme" }, [["system", "Match my system"], ["dark", "Dark"], ["light", "Light"]].map(([v, l]) =>
      h("button", { type: "button", class: "chip", role: "radio", "aria-checked": String(data.settings.theme === v), onclick: (e) => {
        data.settings.theme = v; saveSettings(); applyTheme();
        theme.querySelectorAll(".chip").forEach((c) => c.setAttribute("aria-checked", String(c === e.currentTarget)));
      } }, l)));

    const updateStatus = h("p", { class: "muted", id: "update-status", "aria-live": "polite" }, "");
    const updateBtn = h("button", { type: "button", class: "btn ghost", onclick: () => {
      if (!Store.native) { updateStatus.textContent = "Updates are for the desktop and tablet apps."; return; }
      updateStatus.textContent = "Checking…";
      checkForUpdates(false);
    } }, icon(ICONS.refresh), "Check for updates");
    const installBtn = h("button", { type: "button", class: "btn primary", hidden: true, onclick: () => { installBtn.disabled = true; Store.post({ type: "install-update" }); } }, "Install update");
    const auto = h("input", { type: "checkbox", id: "auto-update", checked: data.settings.autoUpdate !== false });
    auto.addEventListener("change", () => { data.settings.autoUpdate = auto.checked; saveSettings(); });
    updateUi = (m) => {
      if (!document.contains(updateStatus)) return;
      installBtn.hidden = true;
      if (m.status === "current") updateStatus.textContent = "You have the newest version (" + m.current + ").";
      else if (m.status === "available") {
        updateStatus.textContent = "Version " + m.latest + " is available (you have " + m.current + "). " + (m.canInstall ? "" : m.text || "");
        installBtn.hidden = !m.canInstall;
        installBtn.disabled = false;
      } else if (m.status === "downloading") updateStatus.textContent = "Downloading " + m.latest + "…";
      else if (m.status === "installing") updateStatus.textContent = m.text;
      else if (m.status === "installed") updateStatus.textContent = "Version " + m.latest + " is installed. Restarting…";
      else updateStatus.textContent = m.text;
    };
    const where = info.install === "android" ? "this tablet" : Store.native ? "this computer" : "this browser";
    const snd = data.settings.sound = Object.assign({ on: true, vol: 0.6 }, data.settings.sound);
    const sndOn = h("input", { type: "checkbox", id: "sound-on", checked: snd.on });
    sndOn.addEventListener("change", () => { snd.on = sndOn.checked; Codex.sfx.configure(snd); saveSettings(); Codex.sfx.play("toggle"); });
    const sndVol = h("input", { type: "range", min: 0, max: 1, step: 0.05, value: snd.vol, "aria-label": "Sound volume" });
    sndVol.addEventListener("input", () => { snd.vol = +sndVol.value; Codex.sfx.configure(snd); });
    sndVol.addEventListener("change", () => { saveSettings(); Codex.sfx.play("complete"); });

    const d = dialog({
      title: "Settings",
      class: "wide settings",
      body: [
        h("section", null, h("h3", null, "Look"), theme),
        h("section", null, h("h3", null, "Sound"),
          h("label", { class: "switch-row", for: "sound-on" }, sndOn, "Sound effects: clicks in the 3D studio, a kiln chime when something is complete"),
          h("label", { class: "inline" }, "Volume", sndVol)),
        h("section", null, h("h3", null, "Assistant"), Codex.Assistant.settingsPanel(() => d.close())),
        h("section", null, h("h3", null, "Account"), Codex.Account.settingsPanel(() => d.close())),
        h("section", null, h("h3", null, "Updates"),
          h("p", null, "Game Codex " + (info.version || "dev") + (info.install === "flatpak" ? " (Flatpak)" : info.install === "android" ? " for Android" : info.install === "browser" ? ", running in a browser" : "")),
          h("div", { class: "row-btns" }, updateBtn, installBtn), updateStatus,
          Store.native ? h("label", { class: "switch-row", for: "auto-update" }, auto, "Check for updates when Game Codex starts") : null),
        h("section", null, h("h3", null, "Your data"),
          h("p", null, "Your games are saved on " + where + (data.settings.account ? " and in a private repository on your GitHub account" : "") + ". No ads, no tracking."),
          h("div", { class: "row-btns" },
            project() && view.page === "game" ? h("button", { type: "button", class: "btn ghost", onclick: () => { d.close(); exportDialog(project()); } }, icon(ICONS.download), "Export this game") : null,
            h("button", { type: "button", class: "btn ghost", onclick: () => { d.close(); Store.importProject(null); } }, icon(ICONS.upload), "Import a game"),
            h("button", { type: "button", class: "btn ghost danger-text", onclick: async () => {
              d.close();
              if (await ask({ title: "Delete all your data on " + where + "?", text: "Every game, file and setting in Game Codex is erased from " + where + ". This can't be undone. The app stays installed" + (data.settings.account ? ", you're signed out, and the copies in your GitHub account are kept" : "") + ".", confirm: "Delete everything", danger: true })) {
                if (Codex.Account) Codex.Account.signOut(true);
                Store.deleteAllData();
                data.projects = [];
                data.settings = { theme: "system", autoUpdate: true, favorites: [], opened: {}, libSort: "opened" };
                view.project = null;
                view.page = "library";
                render();
                toast("All your Game Codex data on " + where + " was deleted");
              }
            } }, "Delete all my data"))),
        h("section", null, h("h3", null, "Privacy and terms"), privacyText()),
        h("section", { class: "danger-zone" }, h("h3", null, "Uninstall"),
          h("p", null, "Removes Game Codex from " + where + " along with all of its games, files and settings there. Games saved to your GitHub account stay there until you delete the game-codex-saves repository."),
          h("button", { type: "button", class: "btn danger", onclick: async () => {
            d.close();
            if (!(await ask({ title: "Uninstall Game Codex?", text: "The app and everything it saved on " + where + " will be removed. Export first or sign in with GitHub if you want to keep your games.", confirm: "Uninstall", danger: true }))) return;
            if (!Store.native) { Store.deleteAllData(); data.projects = []; view.project = null; render(); toast("Data deleted. To remove this browser copy, delete its folder."); return; }
            Store.flush(data.projects);
            Store.post({ type: "uninstall" });
          } }, "Uninstall Game Codex")),
      ],
    });
  }

  function privacyText() {
    return h("div", { class: "legal" },
      h("details", null, h("summary", null, "Privacy policy"),
        h("p", null, "Game Codex doesn't collect, send or sell personal data. There are no ads, analytics or tracking. Everything you make is saved in the app's own folder on your device."),
        h("p", null, "If you sign in with GitHub, your games and their files are copied to a private repository called game-codex-saves on your own GitHub account, so you can open them on your other devices. Game Codex talks only to GitHub for this, using a sign-in token kept on your device. Sign out to stop syncing; delete the repository on GitHub to remove the copies there."),
        h("p", null, "Checking for updates asks GitHub for the newest release. GitHub sees your IP address, as with any website (see GitHub's privacy statement). The app sets no cookies and collects nothing from anyone, so it's safe for any age."),
        h("p", null, "The assistant is off until you choose one. With Ollama it runs on your own computer and nothing leaves it. With Claude or ChatGPT, what you type, and the game details you choose to share, go to Anthropic or OpenAI under their terms, using your own API key; Game Codex keeps that key only on this computer and never sends it anywhere else.")),
      h("details", null, h("summary", null, "Terms of use"),
        h("p", null, "Game Codex is free. What you make in it belongs to you. The app is provided as it is, without warranty; keep backups of important work with Export or by signing in."),
        h("p", null, "Refunds: there's nothing to buy, so there's nothing to refund. There are no hidden fees or in-app purchases. Store kits help you prepare store pages; publishing on Steam or Roblox follows their own rules and fees."),
        h("p", null, "The assistant's answers come from an AI model and can be wrong. Check anything important. Using Claude or ChatGPT is billed by Anthropic or OpenAI to your own account, and their own terms (including their minimum age) apply.")),
      h("details", null, h("summary", null, "Deleting your data"),
        h("p", null, "Use Delete all my data above, or Uninstall to remove the app too. If you signed in, also delete the game-codex-saves repository in your GitHub settings.")),
      h("p", { class: "muted" }, "Made by Jayswansocool48. Questions or problems: github.com/Jayswansocool48/game-codex/issues"));
  }

  // ---------- messages from the native side ----------
  Store.on("info", (m) => {
    info = Object.assign(info, m);
    const day = 12 * 3600 * 1000;
    if (data.settings.autoUpdate !== false && Date.now() - (data.settings.lastUpdateCheck || 0) > day) checkForUpdates(true);
  });
  Store.on("uninstall", (m) => {
    if (m.status === "ok") {
      document.body.innerHTML = "";
      document.body.appendChild(h("main", { class: "lib-empty" }, h("h1", null, "Game Codex was uninstalled"), h("p", null, "Your data is being removed. This window closes in a moment.")));
      setTimeout(() => Store.post({ type: "uninstall-finish" }), 1500);
    } else toast(m.text);
  });
  Store.on("exported", (m) => toast(m.error ? m.error : "Saved " + m.file));

  // ---------- keyboard ----------
  document.addEventListener("keydown", (e) => {
    if (document.querySelector(".backdrop, .menu") || document.body.classList.contains("sketching") || document.body.classList.contains("studio-open")) return;
    if (e.target.closest && e.target.closest(".assistant")) return;
    const mod = e.ctrlKey || e.metaKey;
    const typing = /INPUT|TEXTAREA|SELECT/.test(e.target.tagName);
    if (view.page !== "game") {
      if ((mod && e.key.toLowerCase() === "f") || (e.key === "/" && !typing)) { const s = document.getElementById("lib-search"); if (s) { e.preventDefault(); s.focus(); } }
      else if (mod && e.key.toLowerCase() === "n") { e.preventDefault(); newGameDialog(); }
      return;
    }
    if (mod && e.key.toLowerCase() === "n" && view.section !== "overview") { e.preventDefault(); createEntry(view.section); }
    else if ((mod && e.key.toLowerCase() === "f") || (e.key === "/" && !typing)) { const s = document.getElementById("search"); if (s) { e.preventDefault(); s.focus(); } }
    else if (e.altKey && /^[1-7]$/.test(e.key)) { e.preventDefault(); go(e.key === "1" ? "overview" : SECTIONS[+e.key - 2].id); }
    else if (e.altKey && e.key.toLowerCase() === "l") { e.preventDefault(); showLibrary(); }
  });
  window.addEventListener("beforeunload", () => Store.flush(data.projects));

  // Thumbnails for 3D models that don't have one yet (after an import). One at a time, in the background.
  async function makePosters(p) {
    const todo = [];
    SECTIONS.forEach((s) => (p.entries[s.id] || []).forEach((e) => (e.art || []).forEach((a) => { if (a.kind === "model" && !a.poster) todo.push([e, a]); })));
    let made = 0;
    for (const [e, a] of todo) {
      try {
        const id = await Codex.Models.makePoster(a);
        a.poster = id;
        p.media.push(id);
        made++;
      } catch (err) { /* it still opens in the studio */ }
    }
    if (made) { Store.saveProject(p); if (view.project === p.id) render(); }
  }

  const api = {
    createEntry, duplicateEntry, deleteEntry, addArt, addFile, setPoster, removeArt, replaceArt, forgetMedia, setStage, productionCounts,
    currentProject: project, render, renderTopbar, putProject, removeProject, mediaOf, makePosters, openProject, go, changed,
    current: () => ({ page: view.page, section: view.section, entry: view.entry }),
    projects: () => data.projects, settings: () => data.settings, saveSettings, isOpen: () => view.page,
  };
  Codex.app = api;

  // start: the library, or straight back into the game that was open last time
  view.page = "library";
  if (Codex.Account) Codex.Account.init(api, loaded.signedIn);
  render();
})();
