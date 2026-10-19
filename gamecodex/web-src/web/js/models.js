/* 3D models: thumbnails, and the full-screen 3D studio (a Blender-style editor) for
   viewing, texturing, painting, animating and modeling. The studio and three.js r160 are one
   script, vendor/studio/studio.js, loaded the first time it's needed (built from studio/).

   Files the studio makes (painted textures, uploaded textures and backgrounds, exported .glb
   models) are saved as this game's media. Its scene settings live on the model's art item
   (art.v3d) or, for a blank modeling scene, on the entry (entry.studio). Inside those, a saved
   file is written as "media:<id>", which Codex.app.mediaOf() counts so exports and GitHub sync
   carry the files along. */
window.Codex = window.Codex || {};

Codex.Models = (function () {
  const { h, icon, ICONS, toast } = Codex;
  const MEDIA_REF = /^media:([A-Za-z0-9_-]{1,64}\.[a-z0-9]+)(\?.*)?$/;
  let ready = null;

  function load() {
    if (!ready) {
      ready = new Promise((resolve, reject) => {
        const s = document.createElement("script");
        s.src = "vendor/studio/studio.js";
        s.onload = () => (Codex.Studio ? resolve(Codex.Studio) : reject(new Error("The 3D studio didn't start.")));
        s.onerror = () => reject(new Error("Couldn't load the 3D studio."));
        document.head.appendChild(s);
      }).catch((e) => { ready = null; throw e; });
    }
    return ready;
  }

  function ext(id) { return String(id).split(".").pop().toLowerCase(); }
  function refId(ref) { const m = MEDIA_REF.exec(String(ref || "")); return m ? m[1] : null; }

  // XMLHttpRequest rather than fetch(): the desktop window serves the app from local files,
  // and WebKit lets XHR read those but not fetch().
  function bytes(url) {
    return new Promise((resolve, reject) => {
      const x = new XMLHttpRequest();
      x.open("GET", url);
      x.responseType = "arraybuffer";
      x.onload = () => (x.response && x.response.byteLength ? resolve(x.response) : reject(new Error("the file is empty or missing")));
      x.onerror = () => reject(new Error("the file couldn't be read"));
      x.send();
    });
  }

  // A saved file reference ("media:<id>") as a URL the page can load; anything else is passed through.
  function url(ref) {
    const id = refId(ref);
    return id ? Codex.Store.mediaUrl(id) : ref;
  }

  function parseRef(ref) {
    const id = refId(ref) || String(ref);
    return Promise.all([load(), bytes(url(ref))]).then(([S, buf]) => S.parse(buf, ext(id)))
      .catch((e) => { throw new Error("This model couldn't be opened (" + e.message + "). Try exporting it from Blender as .glb."); });
  }

  // Render a picture of a model so it has a thumbnail. Resolves to a media id.
  function makePoster(item) {
    return Promise.all([load(), bytes(Codex.Store.mediaUrl(item.id))]).then(([S, buf]) => S.poster(buf, ext(item.id))).then((d) => Codex.Store.saveMedia(d));
  }

  function blobToDataUrl(blob) {
    return new Promise((resolve, reject) => { const r = new FileReader(); r.onload = () => resolve(r.result); r.onerror = () => reject(new Error("Couldn't read the file")); r.readAsDataURL(blob); });
  }

  const IMAGE = /^(png|jpg|webp|gif)$/;
  function fileExt(blob, name) {
    const n = String(name || blob.name || "");
    let e = n.includes(".") ? n.split(".").pop().toLowerCase() : "";
    if (!e) e = { "image/png": "png", "image/jpeg": "jpg", "image/webp": "webp", "model/gltf-binary": "glb" }[blob.type] || "";
    return e === "jpeg" ? "jpg" : e;
  }

  /* Opens the studio over the whole window.
     ctx: the editor's context ({project, entry, changed(), rerender(), app}).
     index: the model's place in entry.art, or null for the entry's blank modeling scene. */
  function open(ctx, index) {
    const { project, entry } = ctx;
    const art = index == null ? null : entry.art[index];
    const data = art ? (art.v3d = art.v3d || {}) : (entry.studio = entry.studio || {});
    const title = entry.name || "Untitled";
    const sub = art ? (art.caption || art.name || "3D model") : "3D modeling scene";
    const S = Codex.Store;

    const host = h("div", { class: "vh" });
    const bar = h("header", { class: "studio-bar" });
    const el = h("div", { class: "studio", role: "dialog", "aria-modal": "true", "aria-label": "3D studio: " + title }, bar, host);
    let dispose = () => {}, closed = false;

    const close = () => {
      if (closed) return;
      closed = true;
      dispose();
      el.remove();
      document.body.classList.remove("studio-open");
      ctx.changed({ list: true });
      Codex.sfx.play("close");
      // new models made in the studio get their thumbnails now
      postersFor(ctx).then(() => ctx.rerender());
      if (Codex.Assistant) Codex.Assistant.refresh();
    };

    const snap = h("button", { type: "button", class: "btn ghost sm", onclick: () => {
      const d = Codex.Studio && Codex.Studio.snapshot(host);
      if (!d) return;
      ctx.app.setPoster(entry, art, S.saveMedia(d));
      toast("Saved this view as the model's picture");
    } }, icon(ICONS.image), "Use this view as the picture");
    const more = h("button", { type: "button", class: "icon-btn", "aria-label": "More", "aria-haspopup": "menu", onclick: (e) => Codex.menu(e.currentTarget, [
      { label: "Show it first in the art", icon: ICONS.up, onclick: () => { const i = entry.art.indexOf(art); if (i > 0) { entry.art.unshift(entry.art.splice(i, 1)[0]); ctx.changed({ list: true }); toast("It's first now"); } } },
      { label: "Delete this model", icon: ICONS.trash, danger: true, onclick: async () => {
        if (!(await Codex.ask({ title: "Delete this 3D model?", text: "The model, its painted textures and its scene settings are removed from this game for good.", confirm: "Delete", danger: true }))) return;
        const i = entry.art.indexOf(art);
        close();
        if (i >= 0) { ctx.app.removeArt(entry, i); ctx.rerender(); }
      } },
    ]) }, icon(ICONS.more));
    bar.append(...[
      h("button", { type: "button", class: "icon-btn", "aria-label": "Close the 3D studio", onclick: close }, icon(ICONS.x)),
      h("div", { class: "studio-title" }, h("strong", null, title), h("small", null, sub)),
      Codex.Assistant ? h("button", { type: "button", class: "btn ghost sm", title: "Assistant (Ctrl+J)", onclick: () => Codex.Assistant.toggle() }, icon(ICONS.spark), "Assistant") : null,
      art ? snap : null, art ? more : null,
      h("button", { type: "button", class: "btn primary sm", onclick: close }, "Done"),
    ].filter(Boolean)); // append() would print a skipped button as the word "null"

    document.body.appendChild(el);
    document.body.classList.add("studio-open");
    if (Codex.Assistant) Codex.Assistant.refresh();

    const key = entry.id + "_" + (art ? art.id.split(".")[0] : "scene");
    const opts = {
      data,
      key,
      uses: [],
      save: () => ctx.changed(),
      url,
      load: parseRef,
      fu: (id) => "media:" + id,
      // other pictures and models in this game, for the Texture and Scene tabs
      files: async () => {
        const out = [];
        Codex.SECTIONS.forEach((s) => (project.entries[s.id] || []).forEach((e) => (e.art || []).forEach((a) => {
          if (a === art) return;
          const name = (e.name || "Untitled") + (a.caption ? ": " + a.caption : "");
          if (a.kind === "image" || a.kind === "sketch") out.push({ path: a.id, name, kind: "Textures" });
          else if (a.kind === "model") out.push({ path: a.id, name, kind: "Models" });
        })));
        return out;
      },
      // save a file the studio made; returns its reference for the scene data
      up: async (blob, dir, name) => {
        const e = fileExt(blob, name);
        if (dir === "models" ? e !== "glb" : !IMAGE.test(e)) {
          toast("Game Codex keeps PNG, JPEG, WebP and GIF pictures and .glb models. Convert the file and try again.");
          throw new Error("unsupported file type");
        }
        const id = S.saveFile(await blobToDataUrl(blob), e);
        project.media.push(id);
        if (dir === "models") {
          entry.art.push({ id, kind: "model", name: (entry.name || "model") + ".glb", caption: "Made in the 3D studio" });
        } else if (name) {
          // one file per name (a model's painted texture): the old one goes when the new one is saved
          const named = data.files = data.files || {};
          const old = refId(named[name]);
          named[name] = "media:" + id;
          if (old) ctx.app.forgetMedia([old]);
        }
        ctx.changed({ list: dir === "models" });
        return "media:" + id;
      },
      // "Save meshes as the main model": put that model first in the art strip
      setModel: (ref) => {
        const id = refId(ref);
        const i = entry.art.findIndex((a) => a.id === id);
        if (i > 0) entry.art.unshift(entry.art.splice(i, 1)[0]);
        ctx.changed({ list: true });
      },
    };

    load().then((Studio) => {
      if (closed) return;
      Codex.sfx.play("open");
      return Studio.mount(host, art ? "media:" + art.id : null, opts).then((d) => { if (closed) d(); else dispose = d; });
    }).catch((e) => { host.appendChild(h("p", { class: "studio-error" }, e.message)); });
    return { close };
  }

  // Thumbnails for models that don't have one yet (new exports, imports).
  async function postersFor(ctx) {
    const todo = (ctx.entry.art || []).filter((a) => a.kind === "model" && !a.poster);
    for (const a of todo) {
      try { ctx.app.setPoster(ctx.entry, a, await makePoster(a)); } catch (e) { /* the studio still opens it */ }
    }
  }

  return { load, open, makePoster, postersFor, url, refId, MEDIA_REF };
})();
