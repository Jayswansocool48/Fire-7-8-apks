/* The Production page: every character, prop and level as something to
   build, from Planned to Modeled to Complete. Complete ones glow like pottery out of a kiln. */
window.Codex = window.Codex || {};

Codex.Production = (function () {
  const { h, icon, ICONS, menu } = Codex;
  const state = { stage: "", search: "" };

  function thumb(e) {
    const S = Codex.Store;
    const a = (e.art || []).find((x) => x.kind === "image" || x.kind === "sketch" || (x.kind === "model" && x.poster));
    if (a) return h("img", { src: S.mediaUrl(a.kind === "model" ? a.poster : a.id), alt: "", loading: "lazy" });
    return h("span", { class: "prod-initial", "aria-hidden": "true" }, (e.name || "?").trim().charAt(0).toUpperCase() || "?");
  }

  function meta(s, e) {
    const bits = [];
    const sub = Codex.Editor.subline(s, e);
    if (sub) bits.push(sub);
    if (e.group) bits.push(e.group);
    return bits.join(" · ");
  }

  function facts(e) {
    const out = [];
    if ((e.poly || "").trim()) out.push(h("span", null, icon(ICONS.mesh), e.poly.trim()));
    const t = e.tasks || [];
    if (t.length) out.push(h("span", { title: "Production tasks done" }, icon(ICONS.check), t.filter((x) => x.done).length + "/" + t.length));
    if ((e.art || []).some((a) => a.kind === "model") || (e.studio && (e.studio.props || []).length)) out.push(h("span", null, icon(ICONS.cube), "3D"));
    return out;
  }

  function matches(e) {
    const q = state.search.trim().toLowerCase();
    if (state.stage && Codex.stageOf(e) !== state.stage) return false;
    return !q || [e.name, e.group, e.type, e.role, e.biome, e.poly, e.notes].join(" ").toLowerCase().includes(q);
  }

  function card(ctx, s, e) {
    const st = Codex.stageOf(e);
    const pill = h("button", {
      type: "button", class: "stage-pill s-" + st.toLowerCase(), "aria-haspopup": "menu",
      "aria-label": (e.name || "Untitled") + ": " + st + ". Change stage",
      onclick: (ev) => menu(ev.currentTarget, Codex.STAGES.map((x) => ({
        label: x.id + (x.id === st ? "  ✓" : ""), onclick: () => { ctx.app.setStage(e, x.id); ctx.rerender(); },
      }))),
    }, h("i", { class: "dot" }), st, icon(ICONS.chevron));
    return h("li", { class: "prod-card s-" + st.toLowerCase() },
      h("button", { type: "button", class: "prod-open", onclick: () => ctx.go(s.id, e.id), "aria-label": "Open " + (e.name || "Untitled " + s.one) },
        h("span", { class: "prod-thumb" }, thumb(e)),
        h("span", { class: "prod-info" },
          h("strong", null, e.name || "Untitled " + s.one),
          meta(s, e) ? h("small", null, meta(s, e)) : null,
          h("span", { class: "prod-facts" }, facts(e)))),
      pill);
  }

  function render(ctx) {
    const p = ctx.project;
    const c = ctx.app.productionCounts(p);
    const wrap = h("div", { class: "production tone-fire" });

    const kiln = Codex.Store.can("kiln") ? h("button", { type: "button", class: "btn ghost sm", onclick: () => Codex.Kiln.open(p) }, icon(ICONS.flame), "Import from Kiln") : null;
    wrap.appendChild(h("header", { class: "prod-head" },
      h("div", null,
        h("h1", null, "Production"),
        h("p", { class: "muted" }, "Everything you have to build: characters, props and levels, from planned to complete.")),
      h("div", { class: "row-btns" }, kiln)));

    if (!c.total) {
      wrap.appendChild(h("div", { class: "prod-empty" },
        h("p", null, "Nothing to build yet. Characters, props and levels you add show up here, each with its production stage, polycount, Blender file and tasks."),
        h("div", { class: "row-btns" },
          Codex.PRODUCTION_SECTIONS.map((id) => { const s = Codex.sectionById(id); return h("button", { type: "button", class: "btn ghost tone-" + s.color, onclick: () => ctx.app.createEntry(id) }, icon(ICONS.plus), "New " + s.one); }),
          kiln)));
      return wrap;
    }

    const pct = (n) => (n / c.total) * 100;
    const bar = h("div", { class: "heat", role: "img", "aria-label": c.Planned + " planned, " + c.Modeled + " modeled, " + c.Complete + " complete" },
      h("i", { class: "s-planned", style: { width: pct(c.Planned) + "%" } }),
      h("i", { class: "s-modeled", style: { width: pct(c.Modeled) + "%" } }),
      h("i", { class: "s-complete", style: { width: pct(c.Complete) + "%" } }));
    const chip = (id, label, n) => h("button", {
      type: "button", class: "chip stage-chip" + (id ? " s-" + id.toLowerCase() : ""), "aria-pressed": String(state.stage === id),
      onclick: () => { state.stage = state.stage === id ? "" : id; ctx.rerender(); },
    }, id ? h("i", { class: "dot" }) : null, label, h("b", null, n));
    const search = h("input", { type: "search", id: "prod-search", value: state.search, placeholder: "Search what you're building", "aria-label": "Search production" });
    search.addEventListener("input", () => { state.search = search.value; draw(); });

    wrap.appendChild(h("section", { class: "prod-summary", "aria-label": "Progress" },
      h("div", { class: "prod-sum" },
        h("strong", null, c.Complete + " of " + c.total + " complete"),
        h("span", { class: "muted" }, c.Complete === c.total ? "Everything is out of the kiln." : c.Modeled + " modeled, " + c.Planned + " planned")),
      bar,
      h("div", { class: "prod-tools" },
        h("div", { class: "chips", role: "group", "aria-label": "Show" },
          chip("", "All", c.total), Codex.STAGES.map((x) => chip(x.id, x.id, c[x.id]))),
        h("div", { class: "search" }, icon(ICONS.search), search))));

    const groups = h("div", { class: "prod-groups" });
    wrap.appendChild(groups);
    function draw() {
      groups.innerHTML = "";
      let any = false;
      Codex.PRODUCTION_SECTIONS.forEach((id) => {
        const s = Codex.sectionById(id);
        const all = p.entries[id] || [];
        const shown = all.filter(matches);
        if (!shown.length) return;
        any = true;
        const done = all.filter((e) => Codex.stageOf(e) === "Complete").length;
        groups.appendChild(h("section", { class: "prod-group tone-" + s.color, "aria-label": s.name },
          h("header", null,
            h("button", { type: "button", class: "prod-gtitle", onclick: () => ctx.go(id) }, icon(s.icon), h("h2", null, s.name)),
            h("span", { class: "count", title: done + " of " + all.length + " complete" }, done + "/" + all.length),
            h("span", { class: "line" }),
            h("button", { type: "button", class: "btn ghost sm", onclick: () => ctx.app.createEntry(id) }, icon(ICONS.plus), "New " + s.one)),
          h("ul", { class: "prod-grid", role: "list" }, shown.map((e) => card(ctx, s, e)))));
      });
      if (!any) groups.appendChild(h("p", { class: "empty" }, "Nothing matches.", " ",
        h("button", { type: "button", class: "btn ghost sm", onclick: () => { state.search = ""; state.stage = ""; ctx.rerender(); } }, "Show everything")));
    }
    draw();
    return wrap;
  }

  return { render };
})();
