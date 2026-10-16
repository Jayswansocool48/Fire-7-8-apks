/* Game Codex: the main screen, projects, lists, idea generators and settings. */
(function () {
  const { h, icon, ICONS, uid, toast, dialog, ask, Store, SECTIONS, sectionById } = Codex;

  const loaded = Store.init();
  const data = { projects: loaded.projects, settings: Object.assign({ theme: "system" }, loaded.settings) };
  const view = { project: null, section: "overview", entry: null, search: "", filter: "", listScroll: 0, navOpen: false };
  let info = { version: Store.version, install: Store.native ? "flatpak" : "browser" };
  const appEl = document.getElementById("app");

  // ---------- data ----------
  function project() { return data.projects.find((p) => p.id === view.project) || null; }

  function newProject(fields) {
    const p = Object.assign({ id: uid("p"), name: "Untitled game", genre: [], platforms: [], pitch: "", audience: "", artStyle: "", palette: ["#2b2d42", "#ef8354", "#f6d55c", "#3caea3"], notes: "", media: [], created: Date.now(), entries: {} }, fields);
    SECTIONS.forEach((s) => { p.entries[s.id] = p.entries[s.id] || []; });
    data.projects.push(p);
    Store.saveProject(p, true);
    return p;
  }

  function changed(opts) {
    const p = project();
    if (!p) return;
    Store.saveProject(p);
    if (opts && opts.list) { renderList(); renderNav(); }
  }

  function saveSettings() { Store.saveSettings(data.settings); }

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
    // the copy gets its own picture files so deleting one doesn't break the other
    copy.art = (e.art || []).map((a) => {
      const url = Store.mediaUrl(a.id);
      return url.startsWith("data:") ? { ...a, id: addMedia(url) } : { ...a, id: copyMedia(a.id) };
    });
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
    if (!(await ask({ title: "Delete " + (e.name || "this " + s.one) + "?", text: "Its notes and pictures are removed for good. Links to it from other entries are removed too.", confirm: "Delete", danger: true }))) return;
    const ids = (e.art || []).map((a) => a.id);
    removeMediaIds(ids);
    list.splice(list.indexOf(e), 1);
    // drop links that pointed at it
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
    p.media = p.media.filter((m) => !ids.includes(m));
    Store.deleteMedia(ids);
  }

  function addArt(entry, dataUrl, kind, caption) {
    entry.art = entry.art || [];
    entry.art.push({ id: addMedia(dataUrl), kind, caption: caption || "" });
    changed({ list: true });
  }
  function removeArt(entry, index) {
    const [a] = entry.art.splice(index, 1);
    removeMediaIds([a.id]);
    changed({ list: true });
  }
  function replaceArt(entry, index, dataUrl) {
    const old = entry.art[index].id;
    entry.art[index] = { ...entry.art[index], id: addMedia(dataUrl), kind: "sketch" };
    removeMediaIds([old]);
    changed({ list: true });
  }

  // ---------- navigation ----------
  function go(section, entryId, opts) {
    const sectionChanged = view.section !== section;
    view.section = section;
    view.entry = entryId || null;
    if (sectionChanged) { view.search = ""; view.filter = ""; }
    view.navOpen = false;
    render();
    if (opts && opts.focusName) requestAnimationFrame(() => { const n = document.querySelector(".sheet-name"); if (n) n.focus(); });
    else if (entryId) requestAnimationFrame(() => { const c = document.querySelector('.card[aria-current="true"]'); if (c) c.scrollIntoView({ block: "nearest" }); });
  }

  function openProject(id) {
    view.project = id;
    data.settings.lastProject = id;
    saveSettings();
    go("overview");
  }

  // ---------- rendering ----------
  function applyTheme() {
    const t = data.settings.theme;
    if (t === "system") document.documentElement.removeAttribute("data-theme");
    else document.documentElement.setAttribute("data-theme", t);
  }

  function render() {
    applyTheme();
    const p = project();
    appEl.innerHTML = "";
    if (!p) { appEl.appendChild(welcome()); return; }
    appEl.className = "shell" + (view.navOpen ? " nav-open" : "");
    appEl.appendChild(h("nav", { class: "nav", id: "nav", "aria-label": "Game sections" }));
    const main = h("main", { class: "main", id: "main" });
    appEl.appendChild(main);
    renderNav();
    if (view.section === "overview") main.appendChild(overview(p));
    else {
      const s = sectionById(view.section);
      main.classList.add("split", "tone-" + s.color);
      if (view.entry) main.classList.add("has-entry");
      main.appendChild(h("section", { class: "list-pane", id: "list", "aria-label": s.name }));
      main.appendChild(h("div", { class: "editor-pane", id: "editor" }));
      renderList();
      renderEditor();
    }
  }

  function renderNav() {
    const nav = document.getElementById("nav");
    const p = project();
    if (!nav || !p) return;
    nav.innerHTML = "";
    nav.appendChild(h("button", { type: "button", class: "project-switch", "aria-haspopup": "dialog", onclick: projectsDialog },
      h("span", { class: "logo", "aria-hidden": "true" }, logoMark()),
      h("span", { class: "project-name" }, h("small", null, "Game"), h("strong", null, p.name || "Untitled game")),
      icon(ICONS.chevron, "chev")));

    const item = (id, label, count, tone, d, key) => h("a", {
      href: "#" + id, class: "nav-item" + (tone ? " tone-" + tone : ""), "aria-current": view.section === id ? "page" : null,
      title: key ? label + " (Alt+" + key + ")" : label,
      onclick: (e) => { e.preventDefault(); go(id); },
    }, icon(d), h("span", null, label), count != null ? h("span", { class: "count" }, count) : null);

    nav.appendChild(h("div", { class: "nav-list" },
      item("overview", "Overview", null, null, ICONS.home, 1),
      SECTIONS.map((s, i) => item(s.id, s.name, p.entries[s.id].length, s.color, s.icon, i + 2))));

    nav.appendChild(h("div", { class: "nav-foot" },
      h("button", { type: "button", class: "nav-item", onclick: () => ideasDialog() }, icon(ICONS.dice), h("span", null, "Idea generators")),
      h("button", { type: "button", class: "nav-item", onclick: settingsDialog }, icon(ICONS.gear), h("span", null, "Settings"))));
  }

  function logoMark() {
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", "0 0 64 64");
    svg.innerHTML = '<rect x="6" y="8" width="52" height="48" rx="10" fill="currentColor" opacity=".18"/><path d="M14 18c6-2 12-2 18 2v28c-6-4-12-4-18-2z" fill="currentColor"/><path d="M50 18c-6-2-12-2-18 2v28c6-4 12-4 18-2z" fill="currentColor" opacity=".7"/>';
    return svg;
  }

  function entryThumb(s, e) {
    if (e.art && e.art[0]) return h("img", { class: "thumb", src: Store.mediaUrl(e.art[0].id), alt: "", loading: "lazy" });
    return h("span", { class: "thumb initial", "aria-hidden": "true" }, (e.name || "?").trim().charAt(0).toUpperCase() || "?");
  }

  function filterKey(s) {
    return { characters: "role", props: "type", story: "kind", mechanics: "category", levels: null }[s.id];
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
    return JSON.stringify([e.name, e.kind, e.role, e.type, e.summary, e.description, e.traits, e.tags, e.notes, e.biome, e.category, e.look, e.backstory]).toLowerCase().includes(q);
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
      h("button", { type: "button", class: "icon-btn menu-btn", "aria-label": "Show sections", "aria-controls": "nav", onclick: () => { view.navOpen = !view.navOpen; appEl.classList.toggle("nav-open", view.navOpen); } }, icon(ICONS.menu)),
      h("h1", null, s.name),
      h("div", { class: "list-actions" },
        h("button", { type: "button", class: "btn ghost sm", title: "Random " + s.one, onclick: () => ideasDialog(s.id) }, icon(ICONS.dice), "Roll"),
        h("button", { type: "button", class: "btn primary sm", title: "New " + s.one + " (Ctrl+N)", onclick: () => createEntry(s.id) }, icon(ICONS.plus), "New"))));
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
          h("button", { type: "button", class: "btn primary", onclick: () => createEntry(s.id) }, icon(ICONS.plus), "New " + s.one),
          h("button", { type: "button", class: "btn ghost", onclick: () => ideasDialog(s.id) }, icon(ICONS.dice), "Roll one"))));
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
        e.traits && e.traits.length ? h("span", { class: "card-tags" }, e.traits.slice(0, 3).join(", ")) : null))));
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
    name.addEventListener("input", () => { p.name = name.value; changed(); const n = document.querySelector(".project-name strong"); if (n) n.textContent = p.name || "Untitled game"; });
    const pitch = Codex.autosize(h("textarea", { class: "ov-pitch", rows: 2, placeholder: "Your game in one or two sentences. What does the player do, and why is it fun?", "aria-label": "Pitch" }));
    pitch.value = p.pitch || "";
    pitch.addEventListener("input", () => { p.pitch = pitch.value; changed(); });

    wrap.appendChild(h("header", { class: "ov-head" },
      h("button", { type: "button", class: "icon-btn menu-btn", "aria-label": "Show sections", "aria-controls": "nav", onclick: () => { view.navOpen = !view.navOpen; appEl.classList.toggle("nav-open", view.navOpen); } }, icon(ICONS.menu)),
      cover(p), h("div", { class: "ov-title" }, name, pitch,
        h("button", { type: "button", class: "btn ghost sm", onclick: () => ideasDialog("game") }, icon(ICONS.dice), "Roll a game idea"))));

    // counts per section: the codex at a glance
    wrap.appendChild(h("section", { class: "shelf", "aria-label": "Sections" }, SECTIONS.map((s) => {
      const list = sortedEntries(p, s);
      const recent = list.slice().sort((a, b) => (b.created || 0) - (a.created || 0)).slice(0, 3);
      return h("div", { class: "spine tone-" + s.color },
        h("button", { type: "button", class: "spine-head", onclick: () => go(s.id) }, icon(s.icon), h("span", { class: "spine-n" }, list.length), h("span", null, s.name)),
        h("ul", null, recent.length ? recent.map((e) => h("li", null, h("button", { type: "button", onclick: () => go(s.id, e.id) }, e.name || "Untitled"))) : h("li", { class: "muted" }, "None yet")),
        h("button", { type: "button", class: "spine-add", "aria-label": "New " + s.one, onclick: () => { view.section = s.id; createEntry(s.id); } }, icon(ICONS.plus), "New " + s.one));
    })));

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
    wrap.appendChild(details);

    wrap.appendChild(h("footer", { class: "sheet-foot" },
      h("button", { type: "button", class: "btn ghost", onclick: () => exportDialog(p) }, icon(ICONS.download), "Export"),
      h("button", { type: "button", class: "btn ghost danger-text", onclick: () => deleteProject(p) }, icon(ICONS.trash), "Delete this game")));
    return wrap;
  }

  function cover(p) {
    const file = h("input", { type: "file", accept: "image/*", hidden: true });
    file.addEventListener("change", async () => {
      const f = file.files[0];
      if (!f) return;
      try {
        const url = await Codex.readImage(f, 1600);
        if (p.cover) removeMediaIds([p.cover]);
        p.cover = addMedia(url);
        changed();
        render();
      } catch (err) { toast(err.message); }
    });
    const btn = h("button", { type: "button", class: "cover" + (p.cover ? "" : " none"), "aria-label": p.cover ? "Change cover image" : "Add a cover image", onclick: () => file.click() },
      p.cover ? h("img", { src: Store.mediaUrl(p.cover), alt: "Cover art for " + (p.name || "this game") }) : [icon(ICONS.image), h("span", null, "Cover art")]);
    return h("div", { class: "cover-wrap" }, btn, file,
      p.cover ? null : h("button", { type: "button", class: "btn ghost sm", onclick: () => Codex.Sketch.open({ title: "Cover for " + (p.name || "your game"), onSave: (d) => { p.cover = addMedia(d); changed(); render(); } }) }, icon(ICONS.pencil), "Sketch it"));
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
    if (!(await ask({ title: "Delete " + (p.name || "this game") + "?", text: "Every character, prop, story entry, mechanic, level and picture in it is removed for good. Export it first if you want a copy.", confirm: "Delete game", danger: true }))) return;
    Store.deleteMedia(p.media || []);
    Store.deleteProject(p.id);
    data.projects = data.projects.filter((x) => x.id !== p.id);
    view.project = data.projects[0] ? data.projects[0].id : null;
    data.settings.lastProject = view.project;
    saveSettings();
    view.section = "overview";
    render();
    toast("Deleted " + (p.name || "the game"));
  }

  // ---------- welcome & projects ----------
  function welcome() {
    appEl.className = "welcome-shell";
    const name = h("input", { id: "new-name", placeholder: "Working title", "aria-label": "Game name", autofocus: true });
    const create = () => { const p = newProject({ name: name.value.trim() || "Untitled game" }); openProject(p.id); };
    name.addEventListener("keydown", (e) => { if (e.key === "Enter") create(); });
    return h("main", { class: "welcome" },
      h("div", { class: "welcome-mark", "aria-hidden": "true" }, logoMark()),
      h("h1", null, "Game Codex"),
      h("p", { class: "lead" }, "One place for everything in your game: characters, props, story, quests, dialogue, mechanics and levels, with sketches and art right next to them."),
      h("div", { class: "welcome-form" }, name, h("button", { type: "button", class: "btn primary", onclick: create }, "Start a game")),
      h("button", { type: "button", class: "btn ghost", onclick: () => { const p = Codex.sampleGame(newProject); openProject(p.id); } }, "Open a sample game"),
      data.projects.length ? h("button", { type: "button", class: "btn ghost", onclick: projectsDialog }, "Open one of my games") : null,
      h("button", { type: "button", class: "btn ghost", onclick: () => Store.importProject() }, icon(ICONS.upload), "Import a game"));
  }

  function projectsDialog() {
    const list = h("ul", { class: "project-list" }, data.projects.slice().sort((a, b) => (b.updated || 0) - (a.updated || 0)).map((p) => h("li", null,
      h("button", { type: "button", class: "project-row", "aria-current": p.id === view.project ? "true" : null, onclick: () => { d.close(); openProject(p.id); } },
        p.cover ? h("img", { src: Store.mediaUrl(p.cover), alt: "" }) : h("span", { class: "thumb initial", "aria-hidden": "true" }, (p.name || "?").charAt(0).toUpperCase()),
        h("span", null, h("strong", null, p.name || "Untitled game"), h("small", null, SECTIONS.reduce((n, s) => n + p.entries[s.id].length, 0) + " entries" + (p.genre.length ? ", " + p.genre.slice(0, 2).join(" ") : "")))))));
    const name = h("input", { placeholder: "New game name", "aria-label": "New game name" });
    const create = () => { d.close(); const p = newProject({ name: name.value.trim() || "Untitled game" }); openProject(p.id); };
    name.addEventListener("keydown", (e) => { if (e.key === "Enter") create(); });
    const d = dialog({
      title: "Your games",
      body: [list, h("div", { class: "welcome-form" }, name, h("button", { type: "button", class: "btn primary", onclick: create }, icon(ICONS.plus), "New game"))],
      actions: [h("button", { type: "button", class: "btn ghost", onclick: () => { d.close(); Store.importProject(); } }, icon(ICONS.upload), "Import a game")],
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
      if (!project()) return;
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
      history.forEach((r, i) => results.appendChild(h("li", { class: "idea" + (i === 0 ? " fresh" : "") + (r.section ? " tone-" + sectionById(r.section).color : "") },
        h("div", null, h("strong", null, r.title), h("p", null, r.text)),
        project() ? h("button", { type: "button", class: "btn " + (i === 0 ? "primary" : "ghost") + " sm", onclick: () => use(r) }, r.section ? "Add to " + sectionById(r.section).name.toLowerCase() : "Add to pitch") : null)));
    }

    const rollBtn = h("button", { type: "button", class: "btn primary roll", onclick: roll }, icon(ICONS.dice), "Roll again");
    const d = dialog({
      title: "Idea generators",
      class: "wide ideas-dialog",
      body: [h("p", { class: "muted" }, "Random starting points made on your computer. Roll until something sparks, then add it and make it yours."), tabs, h("div", { class: "roll-row" }, rollBtn, styleBox), results],
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
          h("strong", null, "Game Codex file"), h("small", null, "Everything, including pictures. Import it on another computer or keep it as a backup.")),
        h("button", { type: "button", class: "option", onclick: () => { d.close(); Store.exportMarkdown(Codex.toMarkdown(p), fileBase(p)); } },
          h("strong", null, "Design document (Markdown)"), h("small", null, "A readable game design doc to share or paste into GitHub, Notion or Google Docs."))),
    });
  }

  // ---------- settings ----------
  function settingsDialog() {
    const theme = h("div", { class: "chips", role: "radiogroup", "aria-label": "Theme" }, [["system", "Match my system"], ["dark", "Dark"], ["light", "Light"]].map(([v, l]) =>
      h("button", { type: "button", class: "chip", role: "radio", "aria-checked": String(data.settings.theme === v), onclick: (e) => {
        data.settings.theme = v; saveSettings(); applyTheme();
        theme.querySelectorAll(".chip").forEach((c) => c.setAttribute("aria-checked", String(c === e.currentTarget)));
      } }, l)));

    const updateStatus = h("p", { class: "muted", id: "update-status", "aria-live": "polite" }, "");
    const updateBtn = h("button", { type: "button", class: "btn ghost", onclick: () => {
      if (!Store.native) { updateStatus.textContent = "Updates are for the desktop app. This browser copy always runs the files it was opened from."; return; }
      updateStatus.textContent = "Checking…";
      Store.post({ type: "check-update" });
    } }, "Check for updates");
    const installBtn = h("button", { type: "button", class: "btn primary", hidden: true, onclick: () => { installBtn.disabled = true; Store.post({ type: "install-update" }); } }, "Install update");
    const restartBtn = h("button", { type: "button", class: "btn primary", hidden: true, onclick: () => { Store.flush(data.projects); Store.post({ type: "restart" }); } }, "Restart Game Codex");
    updateUi = (m) => {
      if (!document.contains(updateStatus)) return;
      installBtn.hidden = true;
      if (m.status === "current") updateStatus.textContent = "You have the newest version (" + m.current + ").";
      else if (m.status === "available") {
        updateStatus.textContent = "Version " + m.latest + " is available (you have " + m.current + "). " + (m.store ? "Update it from the COSMIC Store." : m.local ? "Run ./install.sh again in the game-codex folder to update." : m.canInstall ? "" : "Download it from the GitHub releases page.");
        installBtn.hidden = !m.canInstall;
        installBtn.disabled = false;
      } else if (m.status === "downloading") updateStatus.textContent = "Downloading " + m.latest + "…";
      else if (m.status === "installing") updateStatus.textContent = m.text;
      else if (m.status === "installed") { updateStatus.textContent = "Version " + m.latest + " is installed. Restart to use it."; restartBtn.hidden = false; }
      else updateStatus.textContent = m.text;
    };

    const d = dialog({
      title: "Settings",
      class: "wide settings",
      body: [
        h("section", null, h("h3", null, "Look"), theme),
        h("section", null, h("h3", null, "Updates"),
          h("p", null, "Game Codex " + (info.version || "dev") + (info.install === "flatpak" ? ", installed as a Flatpak" : info.install === "browser" ? ", running in a browser" : "")),
          h("div", { class: "row-btns" }, updateBtn, installBtn, restartBtn), updateStatus),
        h("section", null, h("h3", null, "Your data"),
          h("p", null, "Your games are saved only on this computer" + (Store.native ? ", in the app's own folder" : ", in this browser") + ". Nothing is uploaded and there's no account."),
          h("div", { class: "row-btns" },
            project() ? h("button", { type: "button", class: "btn ghost", onclick: () => { d.close(); exportDialog(project()); } }, icon(ICONS.download), "Export this game") : null,
            h("button", { type: "button", class: "btn ghost", onclick: () => { d.close(); Store.importProject(); } }, icon(ICONS.upload), "Import a game"),
            h("button", { type: "button", class: "btn ghost danger-text", onclick: async () => {
              d.close();
              if (await ask({ title: "Delete all your data?", text: "Every game, sketch, image and setting in Game Codex is erased from this computer. This can't be undone. The app stays installed.", confirm: "Delete everything", danger: true })) {
                Store.deleteAllData();
                data.projects = [];
                data.settings = { theme: "system" };
                view.project = null;
                render();
                toast("All your Game Codex data was deleted");
              }
            } }, "Delete all my data"))),
        h("section", null, h("h3", null, "Privacy and terms"), privacyText()),
        h("section", { class: "danger-zone" }, h("h3", null, "Uninstall"),
          h("p", null, "Removes Game Codex from this computer along with all of your games, sketches and settings. Export anything you want to keep first."),
          h("button", { type: "button", class: "btn danger", onclick: async () => {
            d.close();
            if (!(await ask({ title: "Uninstall Game Codex?", text: "The app and all of your games, sketches and settings will be removed from this computer. Export first if you want to keep anything.", confirm: "Uninstall", danger: true }))) return;
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
        h("p", null, "Game Codex doesn't collect, send or sell any personal data. Everything you make stays in files on your computer. There are no accounts, ads, analytics or tracking."),
        h("p", null, "The only time it goes online is when you press Check for updates: it asks GitHub for the newest release. GitHub sees your IP address, as with any website (see GitHub's privacy statement)."),
        h("p", null, "Cookies: the app sets none. Children: the app collects no data from anyone, so it's safe for any age.")),
      h("details", null, h("summary", null, "Terms of use"),
        h("p", null, "Game Codex is free. What you make in it belongs to you. The app is provided as it is, without warranty; keep backups of important work with Export."),
        h("p", null, "Refunds: there's nothing to buy, so there's nothing to refund. There are no hidden fees or in-app purchases.")),
      h("details", null, h("summary", null, "Deleting your data"),
        h("p", null, "Use Delete all my data above, or Uninstall to remove the app too. Because nothing is stored anywhere else, that removes it completely.")),
      h("p", { class: "muted" }, "Made by Jayswansocool48. Questions or problems: github.com/Jayswansocool48/game-codex/issues"));
  }

  let updateUi = () => {};

  // ---------- messages from the desktop window ----------
  Store.on("info", (m) => { info = Object.assign(info, m); });
  Store.on("update", (m) => updateUi(m));
  Store.on("uninstall", (m) => {
    if (m.status === "ok") {
      document.body.innerHTML = "";
      document.body.appendChild(h("main", { class: "welcome" }, h("h1", null, "Game Codex was uninstalled"), h("p", { class: "lead" }, "Your data is being removed. This window closes in a moment.")));
      setTimeout(() => Store.post({ type: "uninstall-finish" }), 1500);
    } else toast(m.text);
  });
  Store.on("imported", (m) => {
    if (m.error) { toast(m.error); return; }
    const p = m.project;
    if (data.projects.some((x) => x.id === p.id)) { p.id = uid("p"); p.name = (p.name || "Untitled game") + " (imported)"; }
    SECTIONS.forEach((s) => { p.entries = p.entries || {}; p.entries[s.id] = p.entries[s.id] || []; });
    p.media = p.media || [];
    p.genre = p.genre || [];
    p.platforms = p.platforms || [];
    p.palette = p.palette || [];
    data.projects.push(p);
    Store.saveProject(p, true);
    openProject(p.id);
    toast("Imported " + (p.name || "the game"));
  });
  Store.on("exported", (m) => toast(m.error ? m.error : "Saved " + m.file));
  Store.on("data-deleted", () => {});

  // ---------- keyboard ----------
  document.addEventListener("keydown", (e) => {
    if (document.querySelector(".backdrop") || document.body.classList.contains("sketching") || !project()) return;
    const mod = e.ctrlKey || e.metaKey;
    const typing = /INPUT|TEXTAREA|SELECT/.test(e.target.tagName);
    if (mod && e.key.toLowerCase() === "n" && view.section !== "overview") { e.preventDefault(); createEntry(view.section); }
    else if ((mod && e.key.toLowerCase() === "f") || (e.key === "/" && !typing)) { const s = document.getElementById("search"); if (s) { e.preventDefault(); s.focus(); } }
    else if (e.altKey && /^[1-6]$/.test(e.key)) { e.preventDefault(); go(e.key === "1" ? "overview" : SECTIONS[+e.key - 2].id); }
    else if (e.key === "Escape" && view.navOpen) { view.navOpen = false; appEl.classList.remove("nav-open"); }
  });
  window.addEventListener("beforeunload", () => Store.flush(data.projects));

  const api = { createEntry, duplicateEntry, deleteEntry, addArt, removeArt, replaceArt, currentProject: project, render };
  Codex.app = api;

  // start
  const last = data.settings.lastProject;
  view.project = data.projects.some((p) => p.id === last) ? last : data.projects[0] ? data.projects[0].id : null;
  render();
})();
