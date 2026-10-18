/* Store kit: everything a Steam store page or a Roblox experience page asks for, made from the game.
   Neither store lets other apps publish for you, so this builds a folder to upload by hand on
   Steamworks (partner.steamgames.com) or the Roblox Creator Dashboard (create.roblox.com).
   Sizes from Steamworks' "Graphical Assets" page and Roblox's thumbnail and icon guides (Oct 2026). */
window.Codex = window.Codex || {};

Codex.StoreKit = (function () {
  const { h, icon, ICONS, toast, dialog, Store } = Codex;

  const STEAM = [
    { file: "header_capsule.png", name: "Header capsule", w: 920, h: 430, logo: true },
    { file: "small_capsule.png", name: "Small capsule", w: 462, h: 174, logo: true },
    { file: "main_capsule.png", name: "Main capsule", w: 1232, h: 706, logo: true },
    { file: "vertical_capsule.png", name: "Vertical capsule", w: 748, h: 896, logo: true },
    { file: "page_background.png", name: "Page background (optional)", w: 1438, h: 810, logo: false, dim: true },
    { file: "library_capsule.png", name: "Library capsule", w: 600, h: 900, logo: true },
    { file: "library_header.png", name: "Library header", w: 920, h: 430, logo: true },
    { file: "library_hero.png", name: "Library hero (art only)", w: 3840, h: 1240, logo: false },
    { file: "library_logo.png", name: "Library logo (see-through)", w: 1280, h: 720, logoOnly: true },
  ];
  const ROBLOX = [
    { file: "icon.png", name: "Experience icon", w: 512, h: 512, logo: true },
  ];
  const LIMITS = { steamShort: 300, robloxName: 50, robloxDesc: 1000 };

  // ---------- pictures ----------
  function loadImage(id) {
    return new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = () => reject(new Error("Couldn't load a picture for the store kit."));
      img.src = Store.mediaUrl(id);
    });
  }

  // The art to build capsules from: the chosen key art, else the cover, else the first image in the game.
  function imagesIn(p) {
    const keyArt = [], art = [], renders = [];
    Codex.SECTIONS.forEach((s) => (p.entries[s.id] || []).forEach((e) => (e.art || []).forEach((a) => {
      const label = (e.name || s.one) + (a.caption ? ": " + a.caption : "");
      if (!a.kind || a.kind === "image" || a.kind === "sketch") (s.id === "media" && /Key art|Screenshot/.test(e.type || "") ? keyArt : art).push({ id: a.id, label });
      if (a.kind === "model" && a.poster) renders.push({ id: a.poster, label: (e.name || s.one) + " (3D model view)" });
    })));
    return (p.cover ? [{ id: p.cover, label: "Cover art" }] : []).concat(keyArt, art, renders);
  }

  function screenshotsIn(p) {
    const list = [];
    (p.entries.media || []).forEach((e) => {
      if (e.store === "No") return;
      if (!/Screenshot|Key art|Gameplay/.test(e.type || "") && e.store !== "Yes") return;
      (e.art || []).forEach((a) => { if (!a.kind || a.kind === "image" || a.kind === "sketch") list.push({ id: a.id, label: e.name || "Screenshot" }); });
    });
    return list;
  }

  function videosIn(p) {
    const list = [];
    (p.entries.media || []).forEach((e) => {
      if (e.store === "No") return;
      (e.art || []).forEach((a) => { if (a.kind === "video") list.push({ id: a.id, name: e.name || "Trailer", type: e.type || "Video" }); });
    });
    return list;
  }

  // Cover-fit crop with the focus slightly above center (faces and titles usually sit high).
  function drawCover(c, img, W, H) {
    const s = Math.max(W / img.width, H / img.height);
    const w = img.width * s, hh = img.height * s;
    c.drawImage(img, (W - w) / 2, Math.min(0, (H - hh) * 0.4), w, hh);
  }

  function fallbackArt(c, W, H, p) {
    const pal = p.palette && p.palette.length ? p.palette : ["#24345e", "#ff7f6e", "#f5c04a"];
    const g = c.createLinearGradient(0, 0, W, H);
    g.addColorStop(0, pal[0]); g.addColorStop(1, pal[1] || pal[0]);
    c.fillStyle = g;
    c.fillRect(0, 0, W, H);
  }

  // The game's name set as a logo: big, condensed, with a soft shadow so it reads on any art.
  function drawTitle(c, W, H, text, opts) {
    const area = opts.logoOnly ? { x: W * 0.06, y: H * 0.1, w: W * 0.88, h: H * 0.8 } : { x: W * 0.07, y: H * (W > H ? 0.5 : 0.62), w: W * 0.86, h: H * (W > H ? 0.42 : 0.3) };
    const words = String(text || "Untitled game").trim().split(/\s+/);
    let size = Math.round(area.h * 0.7);
    let lines;
    const fit = () => {
      c.font = "800 " + size + "px 'Bricolage Grotesque', sans-serif";
      if ("fontStretch" in c) c.fontStretch = "semi-condensed";
      lines = [];
      let line = "";
      words.forEach((w) => {
        const t = line ? line + " " + w : w;
        if (c.measureText(t).width > area.w && line) { lines.push(line); line = w; } else line = t;
      });
      lines.push(line);
      return lines.length * size * 0.95 <= area.h && lines.every((l) => c.measureText(l).width <= area.w);
    };
    while (!fit() && size > 10) size = Math.floor(size * 0.92);
    c.textAlign = "center";
    c.textBaseline = "alphabetic";
    const total = lines.length * size * 0.95;
    let y = area.y + (area.h - total) / 2 + size * 0.8;
    c.shadowColor = "rgba(0,0,0,.55)";
    c.shadowBlur = Math.round(size * 0.18);
    c.shadowOffsetY = Math.round(size * 0.04);
    c.fillStyle = "#ffffff";
    lines.forEach((l) => { c.fillText(l, area.x + area.w / 2, y); y += size * 0.95; });
    c.shadowColor = "transparent";
  }

  async function render(spec, art, logo, p, titleOnArt) {
    const cv = document.createElement("canvas");
    cv.width = spec.w;
    cv.height = spec.h;
    const c = cv.getContext("2d");
    if (spec.logoOnly) {
      if (logo) { const s = Math.min(spec.w / logo.width, spec.h / logo.height) * 0.9; c.drawImage(logo, (spec.w - logo.width * s) / 2, (spec.h - logo.height * s) / 2, logo.width * s, logo.height * s); }
      else drawTitle(c, spec.w, spec.h, p.name, { logoOnly: true });
      return cv;
    }
    if (art) drawCover(c, art, spec.w, spec.h); else fallbackArt(c, spec.w, spec.h, p);
    if (spec.dim) { c.fillStyle = "rgba(10,14,24,.55)"; c.fillRect(0, 0, spec.w, spec.h); }
    if (spec.logo) {
      if (logo) {
        const maxW = spec.w * 0.8, maxH = spec.h * (spec.w > spec.h ? 0.42 : 0.3);
        const s = Math.min(maxW / logo.width, maxH / logo.height);
        const lw = logo.width * s, lh = logo.height * s;
        c.drawImage(logo, (spec.w - lw) / 2, spec.h * (spec.w > spec.h ? 0.92 : 0.88) - lh, lw, lh);
      } else if (titleOnArt) {
        const g = c.createLinearGradient(0, spec.h * 0.4, 0, spec.h);
        g.addColorStop(0, "rgba(0,0,0,0)"); g.addColorStop(1, "rgba(0,0,0,.55)");
        c.fillStyle = g;
        c.fillRect(0, 0, spec.w, spec.h);
        drawTitle(c, spec.w, spec.h, p.name, {});
      }
    }
    return cv;
  }

  async function screenshot(id) {
    const img = await loadImage(id);
    const W = Math.max(1920, Math.round(img.width / 16) * 16), H = Math.round(W * 9 / 16);
    const cv = document.createElement("canvas");
    cv.width = W; cv.height = H;
    drawCover(cv.getContext("2d"), img, W, H);
    return cv;
  }

  // ---------- text ----------
  function plain(s) { return String(s || "").replace(/\s+/g, " ").trim(); }
  function clip(s, n) { s = plain(s); return s.length <= n ? s : s.slice(0, n - 1).replace(/\s+\S*$/, "") + "…"; }

  function defaults(p) {
    const mech = (p.entries.mechanics || []).filter((m) => m.status !== "Cut");
    const chars = (p.entries.characters || []).filter((c) => /Protagonist|Antagonist|Boss|Companion/.test(c.role || ""));
    const levels = (p.entries.levels || []).slice().sort((a, b) => (a.order || 99) - (b.order || 99));
    const storyline = (p.entries.story || []).find((s) => s.kind === "Storyline");
    const bb = [];
    if (p.pitch) bb.push(plain(p.pitch), "");
    if (storyline && storyline.summary) bb.push("[h2]The story[/h2]", plain(storyline.summary), "");
    if (mech.length) { bb.push("[h2]Features[/h2]", "[list]"); mech.slice(0, 8).forEach((m) => bb.push("[*][b]" + (m.name || "Untitled") + "[/b]" + (m.description ? ": " + clip(m.description, 160) : ""))); bb.push("[/list]", ""); }
    if (chars.length) { bb.push("[h2]Characters[/h2]", "[list]"); chars.slice(0, 6).forEach((c) => bb.push("[*][b]" + (c.name || "Untitled") + "[/b]" + (c.kind ? ", " + c.kind : "") + (c.goal ? ". " + clip(c.goal, 120) : ""))); bb.push("[/list]", ""); }
    if (levels.length) { bb.push("[h2]Places to explore[/h2]", "[list]"); levels.slice(0, 6).forEach((l) => bb.push("[*]" + (l.name || "Untitled") + (l.biome ? ": " + plain(l.biome) : ""))); bb.push("[/list]"); }
    const robloxDesc = [plain(p.pitch), mech.length ? "\n\nFeatures:\n" + mech.slice(0, 6).map((m) => "• " + (m.name || "")).join("\n") : ""].join("");
    return {
      steam: { short: clip(p.pitch || "", LIMITS.steamShort), about: bb.join("\n").trim(), tags: (p.genre || []).concat(p.platforms && p.platforms.includes("VR") ? ["VR"] : []).join(", ") },
      roblox: { name: clip(p.name || "", LIMITS.robloxName), desc: clip(robloxDesc, LIMITS.robloxDesc), genre: (p.genre || [])[0] || "" },
      // covers usually have the title painted on already
      titleOnArt: !p.cover, keyArt: null, logo: null,
    };
  }

  // ---------- dialog ----------
  function open(p, app) {
    p.storeKit = Object.assign(defaults(p), p.storeKit || {});
    const kit = p.storeKit;
    const save = () => Store.saveProject(p);
    const images = imagesIn(p);
    let store = "steam";

    const body = h("div");
    const d = dialog({ title: "Store kit for " + (p.name || "this game"), class: "full", body,
      actions: [h("button", { type: "button", class: "btn ghost", onclick: () => d.close() }, "Close"),
        h("button", { type: "button", class: "btn primary", onclick: () => build(p, kit, store).then(() => {}).catch((e) => toast(e.message)) }, icon(ICONS.download), "Save the kit")] });

    function textField(label, obj, key, limit, area, rows) {
      const id = Codex.uid("k");
      const input = area ? Codex.autosize(h("textarea", { id, rows: rows || 4 })) : h("input", { id });
      input.value = obj[key] || "";
      const counter = limit ? h("div", { class: "counter" }) : null;
      const upd = () => { if (counter) { counter.textContent = input.value.length + " / " + limit; counter.classList.toggle("over", input.value.length > limit); } };
      input.addEventListener("input", () => { obj[key] = input.value; upd(); save(); });
      upd();
      return h("div", { class: "field wide" }, h("label", { for: id }, label), input, counter);
    }

    function picker(label, key, allowNone, help) {
      const sel = h("select", { "aria-label": label }, h("option", { value: "" }, allowNone || "Made from the game's colors"), images.map((im) => h("option", { value: im.id }, im.label)));
      sel.value = kit[key] || (allowNone ? "" : (images[0] && images[0].id) || "");
      sel.addEventListener("change", () => { kit[key] = sel.value || (allowNone ? null : ""); save(); drawPreviews(); });
      return h("div", { class: "field" }, h("span", { class: "label" }, label), sel, help ? h("small", { class: "muted" }, help) : null);
    }

    const previews = h("div", { class: "kit-grid", "aria-live": "polite" });
    async function drawPreviews() {
      previews.innerHTML = "";
      try { await document.fonts.load("800 60px 'Bricolage Grotesque'"); } catch (e) { /* use the fallback font */ }
      const art = await artFor(kit, images).catch(() => null);
      const logo = kit.logo ? await loadImage(kit.logo).catch(() => null) : null;
      const specs = store === "steam" ? STEAM : ROBLOX;
      for (const s of specs) {
        const cv = await render(s, art, logo, p, kit.titleOnArt);
        cv.setAttribute("aria-label", s.name + " preview");
        if (s.logoOnly) cv.classList.add("see-through");
        previews.appendChild(h("div", { class: "kit-asset" }, cv, h("strong", null, s.name), h("small", null, s.w + " × " + s.h + " px")));
      }
      const shots = screenshotsIn(p);
      previews.appendChild(h("div", { class: "kit-asset" }, h("strong", null, store === "steam" ? "Screenshots" : "Thumbnails"),
        h("small", null, shots.length + " found in Media (type Screenshot, Key art or Gameplay clip). " + (store === "steam" ? "Steam needs at least 5, 1920 × 1080 or larger." : "Roblox shows up to 10, ideally 1920 × 1080.")),
        h("small", null, videosIn(p).length + " videos will go in the trailers folder.")));
    }

    function draw() {
      body.innerHTML = "";
      const tabs = h("div", { class: "chips kit-tabs", role: "radiogroup", "aria-label": "Store" },
        [["steam", "Steam"], ["roblox", "Roblox"]].map(([v, l]) => h("button", { type: "button", class: "chip", role: "radio", "aria-checked": String(store === v), onclick: () => { store = v; draw(); } }, l)));
      const titleBox = h("input", { type: "checkbox", id: "kit-title", checked: kit.titleOnArt });
      titleBox.addEventListener("change", () => { kit.titleOnArt = titleBox.checked; save(); drawPreviews(); });
      body.append(tabs,
        h("p", { class: "muted" }, store === "steam"
          ? "Steam doesn't let other apps publish games, so this makes everything for your store page to upload on Steamworks (you'll need a Steamworks account and the Steam Direct fee)."
          : "Roblox experiences are published from Roblox Studio. This makes the icon, thumbnails and page text to add on the Creator Dashboard."),
        h("div", { class: "fields" },
          picker("Key art", "keyArt", null, "Used for every capsule. Wide, detailed art works best."),
          picker("Logo", "logo", "Use the game's name", "A see-through PNG of your logo, if you have one."),
          h("div", { class: "field" }, h("span", { class: "label" }, "Title"), h("label", { class: "switch-row", for: "kit-title", style: { marginTop: "6px" } }, titleBox, "Put the game's name on the art"))),
        h("h3", { style: { margin: "18px 0 10px" } }, "Pictures"), previews,
        h("h3", { style: { margin: "22px 0 10px" } }, "Store page text"),
        store === "steam"
          ? h("div", { class: "fields" },
            textField("Short description", kit.steam, "short", LIMITS.steamShort, true, 3),
            textField("About this game (Steam formatting: [h2], [list], [b])", kit.steam, "about", 0, true, 10),
            textField("Tags (comma separated)", kit.steam, "tags", 0, false))
          : h("div", { class: "fields" },
            textField("Experience name", kit.roblox, "name", LIMITS.robloxName, false),
            textField("Description", kit.roblox, "desc", LIMITS.robloxDesc, true, 8),
            textField("Genre", kit.roblox, "genre", 0, false)));
      drawPreviews();
    }
    draw();
  }

  function artFor(kit, images) {
    const id = kit.keyArt === "" ? null : kit.keyArt || (images[0] && images[0].id);
    return id ? loadImage(id) : Promise.resolve(null);
  }

  async function build(p, kit, store) {
    toast("Making the store kit…");
    try { await document.fonts.load("800 60px 'Bricolage Grotesque'"); } catch (e) { /* fallback font */ }
    const images = imagesIn(p);
    const art = await artFor(kit, images).catch(() => null);
    const logo = kit.logo ? await loadImage(kit.logo).catch(() => null) : null;
    const files = [];
    const dir = store === "steam" ? "steam/" : "roblox/";
    for (const s of store === "steam" ? STEAM : ROBLOX) {
      const cv = await render(s, art, logo, p, kit.titleOnArt);
      files.push({ path: dir + s.file, data: cv.toDataURL("image/png") });
    }
    const shots = screenshotsIn(p);
    for (let i = 0; i < shots.length && i < (store === "steam" ? 20 : 10); i++) {
      const cv = await screenshot(shots[i].id).catch(() => null);
      if (cv) files.push({ path: dir + (store === "steam" ? "screenshots/screenshot_" : "thumbnails/thumbnail_") + String(i + 1).padStart(2, "0") + ".jpg", data: cv.toDataURL("image/jpeg", 0.92) });
    }
    if (store === "roblox" && !shots.length && art) {
      const cv = await render({ w: 1920, h: 1080, logo: true }, art, logo, p, kit.titleOnArt);
      files.push({ path: dir + "thumbnails/thumbnail_01.jpg", data: cv.toDataURL("image/jpeg", 0.92) });
    }
    const vids = videosIn(p);
    const media = vids.map((v, i) => ({ id: v.id, path: "trailers/" + String(i + 1).padStart(2, "0") + "-" + v.name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") + v.id.slice(v.id.lastIndexOf(".")) }));
    files.push({ path: dir + "store-page.txt", data: storeText(p, kit, store) });
    files.push({ path: "README.txt", data: readme(p, store, shots.length, vids.length) });
    const base = (p.name || "game").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "game";
    Store.exportKit(base + "-" + store + "-store-kit", files, media);
  }

  function storeText(p, kit, store) {
    if (store === "steam") {
      return ["GAME NAME", p.name || "", "", "SHORT DESCRIPTION (" + kit.steam.short.length + "/300)", kit.steam.short, "", "ABOUT THIS GAME", kit.steam.about, "", "TAGS", kit.steam.tags, ""].join("\n");
    }
    return ["EXPERIENCE NAME (" + kit.roblox.name.length + "/50)", kit.roblox.name, "", "DESCRIPTION (" + kit.roblox.desc.length + "/1000)", kit.roblox.desc, "", "GENRE", kit.roblox.genre, ""].join("\n");
  }

  function readme(p, store, shots, vids) {
    if (store === "steam") {
      return [
        "Steam store kit for " + (p.name || "your game") + ", made with Game Codex.",
        "",
        "Upload on Steamworks: partner.steamgames.com → your app → Edit Store Page.",
        "  Graphical Assets tab: the capsules, page background and library images in steam/.",
        "  Screenshots tab: steam/screenshots/ (" + shots + " here; Steam needs at least 5).",
        "  Trailers tab: trailers/ (" + vids + " here).",
        "  Description tab: copy the text from steam/store-page.txt.",
        "",
        "Steam's rules: capsules show artwork and the game's logo only (no review quotes or awards);",
        "the library hero is artwork only; the library logo is a see-through PNG.",
        "Publishing on Steam needs a Steamworks account, the Steam Direct fee and Valve's review.",
      ].join("\n");
    }
    return [
      "Roblox store kit for " + (p.name || "your game") + ", made with Game Codex.",
      "",
      "Publish the experience from Roblox Studio first, then on create.roblox.com → Creations → your experience:",
      "  Places → Start Place → Basic Info: name and description from roblox/store-page.txt.",
      "  Icon: roblox/icon.png (512 × 512).",
      "  Thumbnails: roblox/thumbnails/ (up to 10 images or videos, 16:9).",
      "  Videos: trailers/ (" + vids + " here). Roblox limits video uploads to 3 a month.",
    ].join("\n");
  }

  return { open, STEAM, ROBLOX, defaults };
})();
