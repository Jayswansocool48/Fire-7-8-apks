/* Import from Kiln: brings your Kiln (formerly AssetForge) boards into Game Codex. The desktop window
   finds and reads them (desktop/kiln_import.py) and copies their pictures and models; this asks which
   board to bring in, and where. Assets that were already imported into a game are skipped. */
window.Codex = window.Codex || {};

Codex.Kiln = (function () {
  const { h, icon, ICONS, toast, dialog, Store, SECTIONS } = Codex;

  function open(target) {
    if (!Store.can("kiln")) { toast("Importing from Kiln works in the desktop app."); return; }
    const body = h("div", { class: "stack kiln" }, h("p", { class: "muted" }, "Looking for your Kiln boards…"));
    const pick = h("button", { type: "button", class: "btn ghost", onclick: () => run(Store.request({ type: "kiln-pick" }, 30 * 60 * 1000)) }, icon(ICONS.folder), "Choose the Kiln folder…");
    const d = dialog({ title: "Import from Kiln", class: "small", body, actions: [pick, h("span", { class: "grow" }), h("button", { type: "button", class: "btn ghost", onclick: () => d.close() }, "Cancel")] });

    function run(req) {
      req.then((m) => list(m.roots || [])).catch((e) => { body.innerHTML = ""; body.appendChild(h("p", { class: "danger-text" }, e.message)); });
    }

    function list(roots) {
      body.innerHTML = "";
      const profiles = roots.flatMap((r) => r.profiles.map((p) => Object.assign({ dir: r.dir }, p)));
      if (!profiles.length) {
        body.appendChild(h("p", null, "No Kiln boards were found in your home folder. If you keep them somewhere else, choose the folder (it has a file called profiles.json in it)."));
        return;
      }
      body.appendChild(h("p", null, "Each asset becomes a character, prop or level with its production stage, art, 3D model and scene, notes and to-dos. Kiln's own files are left as they are."));
      profiles.forEach((p) => {
        const info = p.assets + " asset" + (p.assets === 1 ? "" : "s") + ", " + p.complete + " complete" + (p.categories.length ? " · " + p.categories.slice(0, 5).join(", ") + (p.categories.length > 5 ? "…" : "") : "");
        body.appendChild(h("div", { class: "kiln-row" },
          h("div", { class: "grow" }, h("strong", null, p.name), h("small", null, info)),
          target ? h("button", { type: "button", class: "btn primary sm", disabled: !p.assets, onclick: () => go(p, target) }, "Add to " + (target.name || "this game")) : null,
          h("button", { type: "button", class: "btn " + (target ? "ghost" : "primary") + " sm", disabled: !p.assets, onclick: () => go(p, null) }, "New game")));
      });
    }

    async function go(p, into) {
      body.innerHTML = "";
      body.appendChild(h("p", { class: "muted" }, "Copying " + p.assets + " assets and their files…"));
      d.el.querySelectorAll("footer button").forEach((b) => { b.disabled = true; });
      try {
        const m = await Store.request({ type: "kiln-import", dir: p.dir, profile: p.id }, 30 * 60 * 1000);
        d.close();
        finish(m.project, m.summary, into);
      } catch (e) {
        body.innerHTML = "";
        body.appendChild(h("p", { class: "danger-text" }, e.message));
        d.el.querySelectorAll("footer button").forEach((b) => { b.disabled = false; });
      }
    }

    run(Store.request({ type: "kiln-scan" }, 60000));
  }

  function finish(incoming, summary, into) {
    const app = Codex.app;
    let game = incoming, added = 0, skipped = 0;
    if (into) {
      const have = new Set();
      SECTIONS.forEach((s) => (into.entries[s.id] || []).forEach((e) => { if (e.kiln) have.add(e.kiln); }));
      SECTIONS.forEach((s) => (incoming.entries[s.id] || []).forEach((e) => {
        if (e.kiln && have.has(e.kiln)) { skipped++; return; }
        into.entries[s.id] = into.entries[s.id] || [];
        into.entries[s.id].push(e);
        added++;
      }));
      // files of assets that were skipped aren't needed
      const used = new Set(app.mediaOf(into));
      into.media = (into.media || []).concat(incoming.media.filter((x) => used.has(x)));
      Store.deleteMedia(incoming.media.filter((x) => !used.has(x)));
      Store.saveProject(into, true);
      game = into;
    } else {
      SECTIONS.forEach((s) => { added += (incoming.entries[s.id] || []).length; });
      app.putProject(incoming, { touch: true });
    }
    app.openProject(game.id);
    app.go("production");
    Codex.sfx.play("kiln");
    const parts = [["characters", "character"], ["props", "prop"], ["levels", "level"]].filter(([k]) => summary[k]).map(([k, one]) => summary[k] + " " + (summary[k] === 1 ? one : k));
    toast(added ? "Imported " + added + " from Kiln" + (parts.length && !skipped ? ": " + parts.join(", ") : "") + (skipped ? " (" + skipped + " were already here)" : "") : "Everything on that board was already here");
    // thumbnails for the 3D models, in the background
    app.makePosters(game);
  }

  return { open };
})();
