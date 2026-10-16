/* The editor for one character, prop, story entry, mechanic or level. */
window.Codex = window.Codex || {};

Codex.Editor = (function () {
  const { h, icon, ICONS, uid, autosize } = Codex;

  // ctx: { project, section, entry, changed(), rerender(), go(sectionId, entryId), app }
  function render(ctx) {
    const { section, entry } = ctx;
    const kind = entry.kind;
    const wrap = h("article", { class: "sheet tone-" + section.color, "aria-label": entry.name || "Untitled " + section.one });

    const nameInput = h("input", { class: "sheet-name", value: entry.name || "", placeholder: "Name this " + section.one, "aria-label": "Name", spellcheck: "false" });
    nameInput.addEventListener("input", () => { entry.name = nameInput.value; ctx.changed({ list: true }); });

    const subtitle = subline(section, entry);
    wrap.appendChild(h("header", { class: "sheet-head" },
      h("p", { class: "sheet-kind" }, section.one === "story entry" ? (kind || "Story") : cap(section.one), subtitle ? " · " + subtitle : ""),
      nameInput));

    wrap.appendChild(artStrip(ctx));

    const grid = h("div", { class: "fields" });
    for (const f of section.fields) {
      if (f.only && !f.only.includes(kind || "Storyline")) continue;
      grid.appendChild(field(ctx, f));
    }
    grid.appendChild(field(ctx, { key: "notes", label: "Notes", type: "textarea", rows: 4, wide: true, placeholder: "Anything else" }));
    wrap.appendChild(grid);

    const backlinks = usedBy(ctx);
    if (backlinks) wrap.appendChild(backlinks);

    wrap.appendChild(h("footer", { class: "sheet-foot" },
      h("button", { type: "button", class: "btn ghost", onclick: () => ctx.app.duplicateEntry(section.id, entry.id) }, icon(ICONS.copy), "Duplicate"),
      h("button", { type: "button", class: "btn ghost danger-text", onclick: () => ctx.app.deleteEntry(section.id, entry.id) }, icon(ICONS.trash), "Delete " + section.one)));
    return wrap;
  }

  function cap(s) { return s.charAt(0).toUpperCase() + s.slice(1); }

  function subline(section, e) {
    if (section.id === "characters") return [e.role, e.kind].filter(Boolean).join(", ");
    if (section.id === "props") return [e.rarity, e.type].filter(Boolean).join(" ");
    if (section.id === "story") return e.kind === "Quest" ? e.questType && e.questType + " quest" : e.kind === "Chapter" && e.order ? "Chapter " + e.order : "";
    if (section.id === "mechanics") return [e.category, e.status].filter(Boolean).join(", ");
    if (section.id === "levels") return [e.order ? "Level " + e.order : "", e.biome].filter(Boolean).join(", ");
    return "";
  }

  // ---------- art ----------
  function artStrip(ctx) {
    const { entry } = ctx;
    entry.art = entry.art || [];
    const strip = h("section", { class: "art", "aria-label": "Art" });
    entry.art.forEach((a, i) => {
      const img = h("img", { src: Codex.Store.mediaUrl(a.id), alt: a.caption || (a.kind === "sketch" ? "Sketch" : "Image") + " of " + (entry.name || "this " + ctx.section.one), loading: "lazy" });
      strip.appendChild(h("button", { type: "button", class: "art-item" + (i === 0 ? " lead" : ""), title: a.caption || "Open", onclick: () => viewArt(ctx, i) },
        img, a.caption ? h("span", { class: "art-cap" }, a.caption) : null));
    });
    const file = h("input", { type: "file", accept: "image/*", multiple: true, hidden: true });
    file.addEventListener("change", async () => {
      for (const f of file.files) {
        try {
          const data = await Codex.readImage(f);
          ctx.app.addArt(entry, data, "image", f.name.replace(/\.[^.]+$/, ""));
        } catch (err) { Codex.toast(err.message); }
      }
      file.value = "";
      ctx.rerender();
    });
    const isLevel = ctx.section.id === "levels";
    strip.appendChild(h("div", { class: "art-add" + (entry.art.length ? "" : " empty") },
      h("button", { type: "button", class: "art-btn", onclick: () => Codex.Sketch.open({ title: (isLevel ? "Layout of " : "Sketch of ") + (entry.name || "untitled"), onSave: (data) => { ctx.app.addArt(entry, data, "sketch", isLevel ? "Layout" : ""); ctx.rerender(); } }) }, icon(ICONS.pencil), isLevel ? "Sketch the layout" : "New sketch"),
      h("button", { type: "button", class: "art-btn", onclick: () => file.click() }, icon(ICONS.image), "Add images"),
      file));
    return strip;
  }

  function viewArt(ctx, index) {
    const { entry } = ctx;
    const a = entry.art[index];
    const caption = h("input", { value: a.caption || "", placeholder: "Describe this image (also read out by screen readers)", "aria-label": "Caption" });
    caption.addEventListener("input", () => { a.caption = caption.value; ctx.changed(); });
    const d = Codex.dialog({
      title: a.caption || (entry.name || "Untitled") + ", " + (a.kind === "sketch" ? "sketch" : "image") + " " + (index + 1),
      class: "wide",
      body: [h("img", { class: "art-full", src: Codex.Store.mediaUrl(a.id), alt: a.caption || "" }), h("label", { class: "field wide" }, h("span", null, "Caption"), caption)],
      actions: [
        h("button", { type: "button", class: "btn ghost danger-text", onclick: async () => {
          d.close();
          if (await Codex.ask({ title: "Delete this " + (a.kind === "sketch" ? "sketch" : "image") + "?", text: "It's removed from this game for good.", confirm: "Delete", danger: true })) {
            ctx.app.removeArt(entry, index);
            ctx.rerender();
          }
        } }, "Delete"),
        index > 0 ? h("button", { type: "button", class: "btn ghost", onclick: () => { entry.art.unshift(entry.art.splice(index, 1)[0]); ctx.changed({ list: true }); d.close(); ctx.rerender(); } }, "Make it the main picture") : null,
        h("button", { type: "button", class: "btn ghost", onclick: () => {
          d.close();
          Codex.Sketch.open({ title: "Draw on " + (a.caption || "this picture"), src: Codex.Store.mediaUrl(a.id), onSave: (data) => { ctx.app.replaceArt(entry, index, data); ctx.rerender(); } });
        } }, "Draw on it"),
        h("button", { type: "button", class: "btn primary", onclick: () => { d.close(); ctx.rerender(); } }, "Done"),
      ],
    });
  }

  // ---------- fields ----------
  function field(ctx, f) {
    const { entry } = ctx;
    const id = uid("f");
    const wide = f.wide || ["textarea", "list", "checklist", "beats", "pairs", "stats", "links", "dialogue", "tags"].includes(f.type);
    const box = h("div", { class: "field" + (wide ? " wide" : "") + " t-" + f.type });
    const label = h("label", { for: id }, f.label);
    box.appendChild(label);
    const set = (v, opts) => { entry[f.key] = v; ctx.changed(opts); };
    const val = entry[f.key];

    switch (f.type) {
      case "text":
      case "number": {
        const input = h("input", { id, type: f.type === "number" ? "number" : "text", value: val == null ? "" : val, placeholder: f.placeholder || "" });
        if (f.type === "number") { input.min = 0; input.inputMode = "numeric"; }
        input.addEventListener("input", () => set(f.type === "number" ? (input.value === "" ? null : +input.value) : input.value, { list: f.type === "number" || f.key === "biome" }));
        box.appendChild(input);
        break;
      }
      case "textarea": {
        const t = autosize(h("textarea", { id, rows: f.rows || 3, placeholder: f.placeholder || "" }));
        t.value = val || "";
        t.addEventListener("input", () => set(t.value));
        box.appendChild(t);
        break;
      }
      case "select": {
        const s = h("select", { id }, h("option", { value: "" }, "Choose…"), f.options.map((o) => h("option", { value: o }, o)));
        s.value = val || (f.always ? f.options[0] : "");
        s.addEventListener("change", () => { set(s.value, { list: true }); if (f.key === "kind") ctx.rerender(); else ctx.refreshHead && ctx.refreshHead(); });
        box.appendChild(s);
        break;
      }
      case "tags": box.appendChild(tags(id, val || [], (v) => set(v, { list: true }), f.placeholder)); break;
      case "list": box.appendChild(list(id, val || [], (v, re) => { set(v); if (re) ctx.rerender(); }, f.placeholder)); break;
      case "checklist": box.appendChild(checklist(id, val || [], (v, re) => { set(v); if (re) ctx.rerender(); })); break;
      case "beats": box.appendChild(beats(id, val || [], (v, re) => { set(v); if (re) ctx.rerender(); }, f.itemLabel)); break;
      case "pairs": box.appendChild(pairs(id, val || [], (v, re) => { set(v); if (re) ctx.rerender(); }, f)); break;
      case "stats": box.appendChild(stats(id, val || (f.defaults || []).map((n) => [n, 0]), (v, re) => { set(v); if (re) ctx.rerender(); })); break;
      case "range": box.appendChild(pips(id, f.label, val || 0, f.max || 10, (v) => set(v, { list: true }))); break;
      case "links": box.appendChild(links(ctx, id, f)); break;
      case "dialogue": box.appendChild(dialogue(ctx, id, f)); break;
    }
    return box;
  }

  function tags(id, values, onChange, placeholder) {
    const wrap = h("div", { class: "tags" });
    const input = h("input", { id, placeholder: values.length ? "" : placeholder || "Type and press Enter" });
    function draw() {
      wrap.querySelectorAll(".tag").forEach((t) => t.remove());
      values.forEach((v, i) => {
        wrap.insertBefore(h("span", { class: "tag" }, v,
          h("button", { type: "button", "aria-label": "Remove " + v, onclick: () => { values.splice(i, 1); onChange(values.slice()); draw(); input.focus(); } }, icon(ICONS.x))), input);
      });
    }
    function commit() {
      const parts = input.value.split(",").map((s) => s.trim()).filter(Boolean);
      if (!parts.length) return;
      parts.forEach((p) => { if (!values.some((v) => v.toLowerCase() === p.toLowerCase())) values.push(p); });
      input.value = "";
      input.placeholder = "";
      onChange(values.slice());
      draw();
    }
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === ",") { e.preventDefault(); commit(); }
      else if (e.key === "Backspace" && !input.value && values.length) { values.pop(); onChange(values.slice()); draw(); }
    });
    input.addEventListener("blur", commit);
    wrap.appendChild(input);
    wrap.addEventListener("click", (e) => { if (e.target === wrap) input.focus(); });
    draw();
    return wrap;
  }

  function rowTools(arr, i, onChange, label) {
    return h("span", { class: "row-tools" },
      h("button", { type: "button", class: "icon-btn sm", "aria-label": "Move " + label + " up", disabled: i === 0, onclick: () => { arr.splice(i - 1, 0, arr.splice(i, 1)[0]); onChange(arr, true); } }, icon(ICONS.up)),
      h("button", { type: "button", class: "icon-btn sm", "aria-label": "Move " + label + " down", disabled: i === arr.length - 1, onclick: () => { arr.splice(i + 1, 0, arr.splice(i, 1)[0]); onChange(arr, true); } }, icon(ICONS.down)),
      h("button", { type: "button", class: "icon-btn sm", "aria-label": "Remove " + label, onclick: () => { arr.splice(i, 1); onChange(arr, true); } }, icon(ICONS.x)));
  }

  function addInput(id, placeholder, onAdd) {
    const input = h("input", { id, placeholder: placeholder || "Add…" });
    const add = () => { const v = input.value.trim(); if (v) { input.value = ""; onAdd(v); } };
    input.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); add(); } });
    return h("div", { class: "add-row" }, input, h("button", { type: "button", class: "btn ghost sm", onclick: add }, icon(ICONS.plus), "Add"));
  }

  function list(id, arr, onChange, placeholder) {
    arr = arr.slice();
    const ol = h("ol", { class: "rows" });
    arr.forEach((v, i) => {
      const input = h("input", { value: v, "aria-label": "Item " + (i + 1) });
      input.addEventListener("input", () => { arr[i] = input.value; onChange(arr); });
      ol.appendChild(h("li", null, input, rowTools(arr, i, onChange, "item " + (i + 1))));
    });
    return h("div", null, ol, addInput(id, placeholder, (v) => { arr.push(v); onChange(arr, true); focusAfter(id); }));
  }

  function focusAfter(id) {
    requestAnimationFrame(() => { const el = document.getElementById(id); if (el) el.focus(); });
  }

  function checklist(id, arr, onChange) {
    arr = arr.map((x) => ({ ...x }));
    const ul = h("ul", { class: "rows checks" });
    arr.forEach((o, i) => {
      const box = h("input", { type: "checkbox", checked: o.done, "aria-label": "Done: " + o.text });
      box.addEventListener("change", () => { o.done = box.checked; onChange(arr); });
      const input = h("input", { value: o.text, "aria-label": "Objective " + (i + 1), class: o.done ? "done" : "" });
      input.addEventListener("input", () => { o.text = input.value; onChange(arr); });
      box.addEventListener("change", () => input.classList.toggle("done", box.checked));
      ul.appendChild(h("li", null, box, input, rowTools(arr, i, onChange, "objective " + (i + 1))));
    });
    return h("div", null, ul, addInput(id, "Add an objective", (v) => { arr.push({ text: v, done: false }); onChange(arr, true); focusAfter(id); }));
  }

  function beats(id, arr, onChange, itemLabel) {
    arr = arr.map((x) => ({ ...x }));
    const ol = h("ol", { class: "beats" });
    arr.forEach((b, i) => {
      const t = h("input", { value: b.title || "", placeholder: itemLabel + " " + (i + 1), "aria-label": itemLabel + " " + (i + 1) + " title" });
      t.addEventListener("input", () => { b.title = t.value; onChange(arr); });
      const d = autosize(h("textarea", { rows: 2, placeholder: "What happens", "aria-label": itemLabel + " " + (i + 1) + " description" }));
      d.value = b.text || "";
      d.addEventListener("input", () => { b.text = d.value; onChange(arr); });
      ol.appendChild(h("li", null, h("div", { class: "beat-head" }, h("span", { class: "beat-n", "aria-hidden": "true" }, i + 1), t, rowTools(arr, i, onChange, itemLabel.toLowerCase() + " " + (i + 1))), d));
    });
    return h("div", null, ol, h("button", { id, type: "button", class: "btn ghost sm", onclick: () => { arr.push({ title: "", text: "" }); onChange(arr, true); } }, icon(ICONS.plus), "Add " + itemLabel.toLowerCase()));
  }

  function pairs(id, arr, onChange, f) {
    arr = arr.map((x) => x.slice());
    const table = h("div", { class: "pairs", role: "table", "aria-label": f.label },
      arr.length ? h("div", { class: "pair head", role: "row" }, h("span", { role: "columnheader" }, f.left), h("span", { role: "columnheader" }, f.right)) : null);
    arr.forEach((p, i) => {
      const a = h("input", { value: p[0], "aria-label": f.left + " " + (i + 1) });
      const b = h("input", { value: p[1], "aria-label": f.right + " " + (i + 1) });
      a.addEventListener("input", () => { p[0] = a.value; onChange(arr); });
      b.addEventListener("input", () => { p[1] = b.value; onChange(arr); });
      table.appendChild(h("div", { class: "pair", role: "row" }, a, b, rowTools(arr, i, onChange, "row " + (i + 1))));
    });
    const a = h("input", { id, placeholder: f.placeholder[0], "aria-label": "New " + f.left.toLowerCase() });
    const b = h("input", { placeholder: f.placeholder[1], "aria-label": "New " + f.right.toLowerCase() });
    const add = () => { if (!a.value.trim() && !b.value.trim()) return; arr.push([a.value.trim(), b.value.trim()]); onChange(arr, true); focusAfter(id); };
    [a, b].forEach((x) => x.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); add(); } }));
    return h("div", null, table, h("div", { class: "pair add" }, a, b, h("button", { type: "button", class: "btn ghost sm", onclick: add }, icon(ICONS.plus), "Add")));
  }

  // Stats shown as pips out of 10, like a character sheet.
  function stats(id, arr, onChange) {
    arr = arr.map((x) => x.slice());
    const wrap = h("div", { class: "stats" });
    arr.forEach((s, i) => {
      const n = h("input", { class: "stat-name", value: s[0], "aria-label": "Stat " + (i + 1) + " name" });
      n.addEventListener("input", () => { s[0] = n.value; onChange(arr); });
      wrap.appendChild(h("div", { class: "stat" }, n, pips(null, s[0] || "stat", s[1] || 0, 10, (v) => { s[1] = v; onChange(arr); }),
        h("button", { type: "button", class: "icon-btn sm", "aria-label": "Remove " + (s[0] || "stat"), onclick: () => { arr.splice(i, 1); onChange(arr, true); } }, icon(ICONS.x))));
    });
    // save the default stats the first time they're shown
    return h("div", null, wrap, addInput(id, "Add a stat (Mana, Luck…)", (v) => { arr.push([v, 0]); onChange(arr, true); focusAfter(id); }));
  }

  function pips(id, label, value, max, onChange) {
    const group = h("div", { class: "pips", role: "slider", tabindex: 0, "aria-label": label, "aria-valuemin": 0, "aria-valuemax": max, "aria-valuenow": value });
    if (id) group.id = id;
    const out = h("output", { class: "pip-val" }, value);
    function draw() {
      group.setAttribute("aria-valuenow", value);
      out.textContent = value;
      [...group.children].forEach((p, i) => { if (p.classList.contains("pip")) p.classList.toggle("on", i < value); });
    }
    for (let i = 1; i <= max; i++) {
      group.appendChild(h("span", { class: "pip", title: String(i), onclick: () => { value = value === i ? i - 1 : i; onChange(value); draw(); } }));
    }
    group.appendChild(out);
    group.addEventListener("keydown", (e) => {
      const d = { ArrowRight: 1, ArrowUp: 1, ArrowLeft: -1, ArrowDown: -1 }[e.key];
      if (e.key === "Home") value = 0; else if (e.key === "End") value = max; else if (d) value = Math.max(0, Math.min(max, value + d)); else return;
      e.preventDefault();
      onChange(value);
      draw();
    });
    draw();
    return group;
  }

  // ---------- links between entries ----------
  function links(ctx, id, f) {
    const { entry, project } = ctx;
    const target = Codex.sectionById(f.to);
    const pool = project.entries[f.to] || [];
    let arr = (entry[f.key] || []).filter((l) => pool.some((e) => e.id === l.id));
    const save = (re) => { entry[f.key] = arr; ctx.changed(); if (re) ctx.rerender(); };
    const wrap = h("div", { class: "links" });
    arr.forEach((l, i) => {
      const e = pool.find((x) => x.id === l.id);
      const thumb = e.art && e.art[0] ? h("img", { src: Codex.Store.mediaUrl(e.art[0].id), alt: "" }) : h("span", { class: "initial tone-" + target.color, "aria-hidden": "true" }, (e.name || "?").charAt(0).toUpperCase());
      const chip = h("div", { class: "link-chip" },
        h("button", { type: "button", class: "link-go", title: "Open " + (e.name || "Untitled"), onclick: () => ctx.go(f.to, e.id) }, thumb, h("span", null, e.name || "Untitled")),
        f.withNote ? (() => { const n = h("input", { class: "link-note", value: l.note || "", placeholder: f.notePlaceholder || "Note", "aria-label": "How they're connected to " + (e.name || "Untitled") }); n.addEventListener("input", () => { l.note = n.value; save(); }); return n; })() : null,
        h("button", { type: "button", class: "icon-btn sm", "aria-label": "Remove " + (e.name || "Untitled"), onclick: () => { arr.splice(i, 1); save(true); } }, icon(ICONS.x)));
      wrap.appendChild(chip);
    });
    if (!(f.single && arr.length)) {
      const choices = pool.filter((e) => e.id !== entry.id && !arr.some((l) => l.id === e.id));
      const s = h("select", { id, class: "link-add" },
        h("option", { value: "" }, choices.length ? "Link a " + target.one + "…" : "Link a " + target.one),
        choices.map((e) => h("option", { value: e.id }, e.name || "Untitled")),
        h("option", { value: "__new" }, "+ New " + target.one));
      s.addEventListener("change", () => {
        if (s.value === "__new") {
          const created = ctx.app.createEntry(f.to, {}, { silent: true });
          arr.push({ id: created.id });
          save();
          ctx.go(f.to, created.id);
          return;
        }
        if (s.value) { arr.push({ id: s.value }); save(true); focusAfter(id); }
      });
      wrap.appendChild(s);
    }
    return wrap;
  }

  // Shows which other entries link to this one.
  function usedBy(ctx) {
    const { project, section, entry } = ctx;
    const found = [];
    for (const s of Codex.SECTIONS) {
      for (const e of project.entries[s.id] || []) {
        if (e.id === entry.id) continue;
        for (const f of s.fields) {
          if (f.type === "links" && f.to === section.id && (e[f.key] || []).some((l) => l.id === entry.id)) { found.push([s, e, f]); break; }
          if (f.type === "dialogue" && e.dialogue && (e.dialogue.nodes || []).some((n) => n.speaker === entry.id)) { found.push([s, e, f]); break; }
        }
      }
    }
    if (!found.length) return null;
    return h("section", { class: "used-by" },
      h("h3", null, "Linked from"),
      h("ul", null, found.map(([s, e, f]) => h("li", null,
        h("button", { type: "button", class: "link-go plain tone-" + s.color, onclick: () => ctx.go(s.id, e.id) }, h("span", { class: "dot", "aria-hidden": "true" }), e.name || "Untitled"),
        h("span", { class: "muted" }, " " + f.label.toLowerCase())))));
  }

  // ---------- dialogue ----------
  function speakerName(ctx, s) {
    if (!s) return "Narrator";
    if (s === "player") return "Player";
    const c = (ctx.project.entries.characters || []).find((x) => x.id === s);
    return c ? c.name || "Untitled" : "Narrator";
  }

  function dialogue(ctx, id, f) {
    const { entry, project } = ctx;
    const d = entry.dialogue = entry.dialogue || { nodes: [] };
    const nodes = d.nodes;
    const chars = project.entries.characters || [];
    const save = (re) => { ctx.changed(); if (re) ctx.rerender(); };
    const wrap = h("div", { class: "dialogue" });
    nodes.forEach((n, i) => {
      const speaker = h("select", { "aria-label": "Line " + (i + 1) + " speaker" },
        h("option", { value: "" }, "Narrator"), h("option", { value: "player" }, "Player"),
        chars.map((c) => h("option", { value: c.id }, c.name || "Untitled")));
      speaker.value = n.speaker || "";
      speaker.addEventListener("change", () => { n.speaker = speaker.value; save(); });
      const line = autosize(h("textarea", { rows: 2, placeholder: "What they say", "aria-label": "Line " + (i + 1) + " text" }));
      line.value = n.text || "";
      line.addEventListener("input", () => { n.text = line.value; save(); });
      const targets = (sel, value) => {
        const s = h("select", { "aria-label": sel },
          h("option", { value: "" }, "Next line"), h("option", { value: "end" }, "End conversation"),
          nodes.map((m, j) => j === i ? null : h("option", { value: m.id }, "Line " + (j + 1) + ": " + (m.text || "").slice(0, 28))));
        s.value = value || "";
        return s;
      };
      n.choices = n.choices || [];
      const choices = h("ul", { class: "choices" }, n.choices.map((c, k) => {
        const t = h("input", { value: c.text || "", placeholder: "Player choice", "aria-label": "Line " + (i + 1) + " choice " + (k + 1) });
        t.addEventListener("input", () => { c.text = t.value; save(); });
        const to = targets("Choice " + (k + 1) + " goes to", c.to);
        to.addEventListener("change", () => { c.to = to.value; save(); });
        return h("li", null, h("span", { class: "arrow", "aria-hidden": "true" }, "↳"), t, to,
          h("button", { type: "button", class: "icon-btn sm", "aria-label": "Remove choice " + (k + 1), onclick: () => { n.choices.splice(k, 1); save(true); } }, icon(ICONS.x)));
      }));
      let goto = null;
      if (!n.choices.length) {
        goto = targets("After line " + (i + 1), n.next);
        goto.addEventListener("change", () => { n.next = goto.value; save(); });
      }
      wrap.appendChild(h("div", { class: "line", id: "line-" + n.id },
        h("div", { class: "line-head" }, h("span", { class: "beat-n", "aria-hidden": "true" }, i + 1), speaker, rowTools(nodes, i, (arr, re) => save(re), "line " + (i + 1))),
        line, choices,
        h("div", { class: "line-foot" },
          h("button", { type: "button", class: "btn ghost sm", onclick: () => { n.choices.push({ text: "", to: "" }); save(true); } }, icon(ICONS.plus), "Add a choice"),
          goto ? h("label", { class: "inline" }, "Then ", goto) : null)));
    });
    return h("div", null, wrap, h("div", { class: "row-btns" },
      h("button", { id, type: "button", class: "btn ghost sm", onclick: () => { nodes.push({ id: uid("n"), speaker: nodes.length ? nodes[nodes.length - 1].speaker : "", text: "", choices: [] }); save(true); } }, icon(ICONS.plus), "Add a line"),
      nodes.length ? h("button", { type: "button", class: "btn ghost sm", onclick: () => playDialogue(ctx) }, icon(ICONS.play), "Play it") : null));
  }

  function playDialogue(ctx) {
    const nodes = ctx.entry.dialogue.nodes;
    const box = h("div", { class: "play" });
    const dlg = Codex.dialog({ title: ctx.entry.name || "Conversation", body: box, class: "play-dialog" });
    function show(i) {
      box.innerHTML = "";
      const n = nodes[i];
      if (!n) { box.appendChild(h("p", { class: "muted" }, "End of conversation.")); box.appendChild(h("button", { type: "button", class: "btn ghost", onclick: () => show(0) }, "Play again")); box.querySelector("button").focus(); return; }
      box.appendChild(h("p", { class: "speaker" }, speakerName(ctx, n.speaker)));
      box.appendChild(h("p", { class: "said" }, n.text || "…"));
      const go = (to) => show(to === "end" ? -1 : to ? nodes.findIndex((m) => m.id === to) : i + 1);
      const btns = n.choices && n.choices.length
        ? n.choices.map((c) => h("button", { type: "button", class: "btn choice", onclick: () => go(c.to) }, c.text || "…"))
        : [h("button", { type: "button", class: "btn primary", onclick: () => go(n.next) }, "Continue")];
      box.appendChild(h("div", { class: "play-choices" }, btns));
      box.querySelector(".play-choices button").focus();
    }
    show(0);
    return dlg;
  }

  return { render, subline };
})();
