/* Turns a game into a readable Markdown design document. */
window.Codex = window.Codex || {};

Codex.toMarkdown = function (p, opts) {
  opts = opts || {};
  const out = [];
  const line = (s) => out.push(s == null ? "" : s);
  const find = (sectionId, id) => (p.entries[sectionId] || []).find((e) => e.id === id);
  const nameOf = (sectionId, id) => { const e = find(sectionId, id); return e ? e.name || "Untitled" : null; };
  const text = (s) => String(s || "").trim();

  line("# " + (p.name || "Untitled game"));
  line();
  if (text(p.pitch)) { text(p.pitch).split("\n").forEach((l) => line("> " + l)); line(); }
  const facts = [
    ["Genre", (p.genre || []).join(", ")],
    ["Platforms", (p.platforms || []).join(", ")],
    ["Who it's for", p.audience],
    ["Art style", p.artStyle],
    ["Color palette", (p.palette || []).map((c) => "`" + c + "`").join(" ")],
  ].filter(([, v]) => text(v));
  facts.forEach(([k, v]) => line("- **" + k + ":** " + v));
  if (facts.length) line();
  if (text(p.inspirations)) { line("**Inspirations:** " + text(p.inspirations)); line(); }
  if (text(p.notes)) { line(text(p.notes)); line(); }

  // table of contents
  const filled = Codex.SECTIONS.filter((s) => (p.entries[s.id] || []).length);
  if (filled.length) {
    line("## Contents");
    line();
    filled.forEach((s) => line("- [" + s.name + "](#" + s.name.toLowerCase().replace(/[^a-z0-9 ]/g, "").replace(/ +/g, "-") + ") (" + p.entries[s.id].length + ")"));
    line();
  }

  for (const s of filled) {
    line("## " + s.name);
    line();
    let list = p.entries[s.id].slice();
    if (s.id === "levels") list.sort((a, b) => (a.order || 999) - (b.order || 999));
    if (s.id === "story") {
      const rank = { Storyline: 0, Chapter: 1, Quest: 2, Dialogue: 3 };
      list.sort((a, b) => (rank[a.kind] - rank[b.kind]) || ((a.order || 999) - (b.order || 999)));
    }
    for (const e of list) {
      line("### " + (e.name || "Untitled " + s.one));
      line();
      const sub = Codex.Editor ? Codex.Editor.subline(s, e) : "";
      if (sub || (s.id === "story" && e.kind)) { line("*" + [s.id === "story" ? e.kind : "", sub].filter(Boolean).join(", ") + "*"); line(); }
      if (opts.images) (e.art || []).forEach((a) => line("![" + (a.caption || e.name || "") + "](media/" + a.id + ")"));
      if (opts.images && (e.art || []).length) line();
      if (Codex.inProduction && Codex.inProduction(s.id) && (e.prod || (e.poly || "").trim() || (e.blender || "").trim() || (e.tasks || []).length)) {
        const bits = [Codex.stageOf(e), (e.poly || "").trim(), (e.blender || "").trim() ? "`" + e.blender.trim() + "`" : ""].filter(Boolean);
        line("**Production:** " + bits.join(" · ")); line();
        if ((e.tasks || []).length) { line("**Production tasks**"); line(); e.tasks.forEach((x) => line("- [" + (x.done ? "x" : " ") + "] " + x.text)); line(); }
      }
      for (const f of s.fields) {
        if (f.key === "kind" || (f.only && !f.only.includes(e.kind || "Storyline"))) continue;
        const v = e[f.key];
        if (v == null || v === "" || (Array.isArray(v) && !v.length)) continue;
        switch (f.type) {
          case "text": case "number": case "select":
            if (["role", "kind", "type", "rarity", "category", "status", "order", "questType"].includes(f.key)) break;
            line("**" + f.label + ":** " + v); line(); break;
          case "range": if (v) { line("**" + f.label + ":** " + v + "/" + (f.max || 10)); line(); } break;
          case "textarea": line("**" + f.label + "**"); line(); line(text(v)); line(); break;
          case "tags": line("**" + f.label + ":** " + v.join(", ")); line(); break;
          case "list": line("**" + f.label + "**"); line(); v.filter(text).forEach((x) => line("- " + x)); line(); break;
          case "checklist": line("**" + f.label + "**"); line(); v.forEach((x) => line("- [" + (x.done ? "x" : " ") + "] " + x.text)); line(); break;
          case "beats": line("**" + f.label + "**"); line(); v.forEach((b, i) => line((i + 1) + ". **" + (b.title || f.itemLabel + " " + (i + 1)) + "**" + (text(b.text) ? ": " + text(b.text).replace(/\n+/g, " ") : ""))); line(); break;
          case "pairs":
            line("| " + f.left + " | " + f.right + " |"); line("| --- | --- |");
            v.forEach(([a, b]) => line("| " + (a || "") + " | " + (b || "") + " |")); line(); break;
          case "stats": {
            const set = v.filter(([, n]) => n);
            if (!set.length) break;
            line("| Stat | Value |"); line("| --- | --- |");
            set.forEach(([n, x]) => line("| " + n + " | " + "●".repeat(x) + "○".repeat(10 - x) + " " + x + " |")); line(); break;
          }
          case "links": {
            const names = v.map((l) => { const n = nameOf(f.to, l.id); return n ? n + (l.note ? " (" + l.note + ")" : "") : null; }).filter(Boolean);
            if (names.length) { line("**" + f.label + ":** " + names.join(", ")); line(); }
            break;
          }
          case "dialogue": {
            const nodes = (v.nodes || []);
            if (!nodes.length) break;
            line("**" + f.label + "**"); line();
            nodes.forEach((n, i) => {
              const who = n.speaker === "player" ? "Player" : n.speaker ? nameOf("characters", n.speaker) || "Narrator" : "Narrator";
              line((i + 1) + ". **" + who + ":** " + (text(n.text) || "…"));
              (n.choices || []).forEach((c) => {
                const j = nodes.findIndex((m) => m.id === c.to);
                line("   - *" + (c.text || "…") + "* → " + (c.to === "end" ? "end" : j >= 0 ? "line " + (j + 1) : "next line"));
              });
            });
            line();
            break;
          }
        }
      }
      if (text(e.notes)) { line("**Notes**"); line(); line(text(e.notes)); line(); }
    }
  }
  line("---");
  line("*Made with Game Codex*");
  return out.join("\n").replace(/\n{3,}/g, "\n\n");
};
