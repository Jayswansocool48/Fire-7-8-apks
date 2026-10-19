/* The assistant drawer: chat with Ollama (free, on this computer), Claude or ChatGPT about
   the game you're making. The desktop window does the talking (desktop/assistant.py) and keeps the API
   key; this page only sends what you type and, if you allow it, a summary of the open game. */
window.Codex = window.Codex || {};

Codex.Assistant = (function () {
  const { h, icon, ICONS, toast, dialog, Store, SECTIONS, sectionById } = Codex;
  const NAMES = { ollama: "Ollama", anthropic: "Claude", openai: "ChatGPT" };
  const KEY_URL = { anthropic: "https://console.anthropic.com/settings/keys", openai: "https://platform.openai.com/api-keys" };
  const SUGGESTED = { ollama: ["llama3.2", "qwen3", "gemma3", "mistral"], anthropic: ["claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5"], openai: [] };
  const available = () => Store.native && Store.can("assistant");

  let cfg = { provider: "", models: {}, keys: {}, ready: false, ollama_url: "http://localhost:11434" };
  let msgs = [], busy = null, share = true, open = false, panel = null, loaded = false;

  function load() {
    if (!available()) return Promise.resolve(cfg);
    return Store.request({ type: "ai-get" }, 15000).then((m) => { cfg = m.settings; loaded = true; if (open) draw(); return cfg; }).catch(() => cfg);
  }

  // ---------- what the assistant can see ----------
  function where() {
    const app = Codex.app, p = app && app.currentProject(), v = app ? app.current() : {};
    if (!p || v.page !== "game") return { project: null };
    const s = sectionById(v.section);
    const entry = s && v.entry ? (p.entries[s.id] || []).find((e) => e.id === v.entry) : null;
    return { project: p, section: v.section, s, entry };
  }

  function detail(s, e) {
    const bits = [];
    if (Codex.inProduction(s.id)) bits.push(Codex.stageOf(e));
    const sub = Codex.Editor.subline(s, e);
    if (sub) bits.push(sub);
    if (e.kind && s.id === "story") bits.unshift(e.kind);
    return (e.name || "Untitled") + (bits.length ? " [" + bits.join(", ") + "]" : "");
  }

  function fieldValue(p, f, v) {
    if (f.type === "links") return (v || []).map((l) => { const x = (p.entries[f.to] || []).find((e) => e.id === l.id); return x ? (x.name || "Untitled") + (l.note ? " (" + l.note + ")" : "") : null; }).filter(Boolean).join(", ");
    if (f.type === "checklist") return (v || []).map((o) => (o.done ? "[done] " : "") + o.text).join("; ");
    if (f.type === "beats") return (v || []).map((b, i) => (b.title || f.itemLabel + " " + (i + 1)) + (b.text ? ": " + b.text : "")).join(" | ");
    if (f.type === "pairs" || f.type === "stats") return (v || []).filter((x) => x[0] || x[1]).map((x) => x[0] + ": " + x[1]).join(", ");
    if (f.type === "dialogue") return (v || []).length + " lines";
    if (Array.isArray(v)) return v.join(", ");
    return v;
  }

  function context() {
    if (!share) return {};
    const { project: p, s, entry } = where();
    if (!p) return {};
    const ctx = {
      game: { name: p.name, pitch: p.pitch, genre: p.genre, platforms: p.platforms, audience: p.audience, artStyle: p.artStyle },
      sections: {},
    };
    SECTIONS.forEach((sec) => { ctx.sections[sec.name] = (p.entries[sec.id] || []).map((e) => detail(sec, e)); });
    if (entry) {
      const fields = { Name: entry.name };
      s.fields.forEach((f) => { if (!f.only || f.only.includes(entry.kind || "Storyline")) fields[f.label] = fieldValue(p, f, entry[f.key]); });
      fields.Notes = entry.notes;
      if (Codex.inProduction(s.id)) {
        fields["Production stage"] = Codex.stageOf(entry);
        Codex.PRODUCTION_FIELDS.forEach((f) => { fields[f.label] = fieldValue(p, f, entry[f.key]); });
      }
      ctx.entry = { section: s.one, fields };
    }
    return ctx;
  }

  function starters() {
    const { project: p, section, s, entry } = where();
    if (!p) return ["Give me five game ideas for a small 3D game", "What should a solo developer build first?", "How do I plan the art for a small game?"];
    const name = entry && (entry.name || "this " + s.one);
    if (entry && Codex.inProduction(s.id)) return ["Write a short art brief for " + name, "Break " + name + " into modeling and texturing tasks", "Suggest a polycount and texture budget for " + name, "What reference images should I collect for " + name + "?"];
    if (entry && s.id === "story") return ["Suggest three twists for " + name, "Write a short scene for " + name, "What could go wrong for the player in " + name + "?"];
    if (entry) return ["Give me ideas to make " + name + " more interesting", "What's missing from " + name + "?", "Describe " + name + " in two sentences for the store page"];
    if (section === "production") return ["Plan my art work for the next week", "Which assets should I build first, and why?", "Suggest a file naming convention for my assets"];
    if (s) return ["Brainstorm ten " + s.name.toLowerCase() + " for " + (p.name || "my game"), "What " + s.name.toLowerCase() + " am I missing?", "Give each of my " + s.name.toLowerCase() + " a one-line hook"];
    return ["Brainstorm ten props for " + (p.name || "my game"), "What's the weakest part of my design so far?", "Write a store page description for " + (p.name || "my game")];
  }

  // ---------- small, safe markdown: escape everything, then add lists, headings, bold, italics, code ----------
  function md(src) {
    const esc = (s) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
    const inline = (s) => esc(s).replace(/`([^`]+)`/g, "<code>$1</code>").replace(/\*\*([^*]+)\*\*/g, "<b>$1</b>").replace(/(^|[\s(])\*([^*\s][^*]*)\*/g, "$1<i>$2</i>");
    const out = []; let list = null, code = null;
    const close = () => { if (list) { out.push("</" + list + ">"); list = null; } };
    for (const line of src.split("\n")) {
      if (code !== null) { if (/^```/.test(line)) { out.push("<pre><code>" + esc(code) + "</code></pre>"); code = null; } else code += (code ? "\n" : "") + line; continue; }
      if (/^```/.test(line)) { close(); code = ""; continue; }
      let m;
      if ((m = line.match(/^\s*[-*•]\s+(.*)/))) { if (list !== "ul") { close(); out.push("<ul>"); list = "ul"; } out.push("<li>" + inline(m[1]) + "</li>"); continue; }
      if ((m = line.match(/^\s*\d+[.)]\s+(.*)/))) { if (list !== "ol") { close(); out.push("<ol>"); list = "ol"; } out.push("<li>" + inline(m[1]) + "</li>"); continue; }
      close();
      if ((m = line.match(/^#{1,4}\s+(.*)/))) out.push("<h4>" + inline(m[1]) + "</h4>");
      else if (line.trim()) out.push("<p>" + inline(line) + "</p>");
    }
    if (code !== null) out.push("<pre><code>" + esc(code) + "</code></pre>");
    close();
    return out.join("");
  }
  const listItems = (text) => text.split("\n").map((l) => l.match(/^\s*(?:[-*•]|\d+[.)])\s+(.*)/)).filter(Boolean).map((m) => m[1].replace(/\*\*/g, "").trim()).filter(Boolean);
  // "Rusty lantern – 300 tris" -> "Rusty lantern"
  const nameFrom = (item) => (item.split(/:|\s[–—-]\s/)[0].trim() || item).slice(0, 80);

  // ---------- putting replies into the game ----------
  function actions(m) {
    const { project: p, s, entry } = where();
    const items = listItems(m.content);
    const out = [h("button", { type: "button", class: "btn ghost sm", onclick: () => {
      (navigator.clipboard ? navigator.clipboard.writeText(m.content) : Promise.reject()).then(() => toast("Copied"), () => toast("Couldn't copy"));
    } }, icon(ICONS.copy), "Copy")];
    if (!p) return out;
    if (entry) {
      out.push(h("button", { type: "button", class: "btn ghost sm", onclick: () => {
        entry.notes = ((entry.notes || "").trim() ? entry.notes.trim() + "\n\n" : "") + m.content.trim();
        Codex.app.changed(); Codex.app.render(); toast("Added to the notes of " + (entry.name || "this " + s.one));
      } }, icon(ICONS.pencil), "Add to notes"));
      if (items.length && Codex.inProduction(s.id)) out.push(h("button", { type: "button", class: "btn ghost sm", onclick: () => {
        entry.tasks = (entry.tasks || []).concat(items.map((t) => ({ text: t, done: false })));
        Codex.app.changed({ list: true }); Codex.app.render(); toast("Added " + items.length + " production tasks");
      } }, icon(ICONS.check), "Add " + items.length + " as tasks"));
    }
    if (items.length) out.push(h("button", { type: "button", class: "btn ghost sm", onclick: (e) => {
      const targets = SECTIONS.filter((x) => x.id !== "media");
      Codex.menu(e.currentTarget, [h("p", { class: "menu-title" }, "Add " + items.length + " as new…")].concat(targets.map((x) => ({
        label: x.name, icon: x.icon, onclick: () => {
          items.forEach((it) => Codex.app.createEntry(x.id, { name: nameFrom(it), notes: it !== nameFrom(it) ? it : "" }, { silent: true }));
          Codex.app.render(); toast("Added " + items.length + " to " + x.name);
        },
      }))));
    } }, icon(ICONS.plus), "Add " + items.length + " to the game…"));
    return out;
  }

  // ---------- the drawer ----------
  function ensurePanel() {
    if (!panel) { panel = h("aside", { class: "assistant", id: "assistant", "aria-label": "Assistant" }); document.body.appendChild(panel); }
    return panel;
  }

  function draw() {
    const el = ensurePanel();
    el.innerHTML = "";
    const sub = available() ? (cfg.ready ? NAMES[cfg.provider] + ", " + (cfg.models[cfg.provider] || "default model") : "Not set up yet") : "Desktop app only";
    el.appendChild(h("header", { class: "ai-head" },
      h("span", { class: "ai-mark", "aria-hidden": "true" }, icon(ICONS.spark)),
      h("div", { class: "ai-who" }, h("strong", null, "Assistant"), h("small", null, cfg.ready ? h("i", { class: "ai-on", "aria-hidden": "true" }) : null, sub)),
      h("button", { type: "button", class: "icon-btn", "aria-label": "New chat", title: "New chat", onclick: () => { stop(); msgs = []; draw(); } }, icon(ICONS.plus)),
      available() ? h("button", { type: "button", class: "icon-btn", "aria-label": "Assistant settings", title: "Assistant settings", onclick: () => settings() }, icon(ICONS.gear)) : null,
      h("button", { type: "button", class: "icon-btn", "aria-label": "Close the assistant (Ctrl+J)", title: "Close (Ctrl+J)", onclick: close }, icon(ICONS.x))));

    const box = h("div", { class: "ai-msgs", "aria-live": "polite" });
    el.appendChild(box);
    if (!available()) {
      box.appendChild(h("div", { class: "ai-setup" }, h("strong", null, "The assistant runs in the desktop app"),
        h("p", null, "Open Game Codex on your computer to brainstorm with Ollama, Claude or ChatGPT.")));
    } else if (!cfg.ready) {
      box.appendChild(h("div", { class: "ai-setup" },
        h("strong", null, "Choose an assistant"),
        h("p", null, "It can brainstorm characters, props and quests, write art briefs and break your assets into tasks. Pick the AI it should use:"),
        [["ollama", "Ollama", Store.platform === "android" ? "Free, runs on your computer over Wi-Fi" : "Free, runs on your computer"], ["anthropic", "Claude", "Needs an Anthropic API key"], ["openai", "ChatGPT", "Needs an OpenAI API key"]].map(([k, n, d]) =>
          h("button", { type: "button", class: "option", onclick: () => settings(k) }, h("strong", null, n), h("small", null, d)))));
    } else if (!msgs.length) {
      const { project: p, s, entry } = where();
      box.appendChild(h("div", { class: "ai-starters" },
        h("p", { class: "ai-hello" }, h("strong", null, entry ? "Working on " + (entry.name || "this " + s.one) + "?" : p ? "What are you making?" : "Hi!"),
          entry ? "Ask about it, or try one of these." : "Ask anything, or try one of these."),
        starters().map((q) => h("button", { type: "button", class: "option", onclick: () => send(q) }, q))));
    } else msgs.forEach((m, i) => box.appendChild(bubble(m, i)));

    if (available() && cfg.ready) {
      const { project: p, entry } = where();
      const cb = h("input", { type: "checkbox", id: "ai-share", checked: share });
      cb.addEventListener("change", () => { share = cb.checked; });
      el.appendChild(h("label", { class: "ai-share", for: "ai-share", title: "Lets the assistant see your game's name, pitch, the names of its entries, and the entry you have open." }, cb,
        p ? "Share " + (entry ? "this entry and " : "") + "my game's details" : "Share my game's details"));
    }
    const ta = h("textarea", { rows: 1, placeholder: available() && cfg.ready ? "Ask the assistant" : "Set up the assistant first", disabled: !(available() && cfg.ready), "aria-label": "Message" });
    const fit = () => { ta.style.height = "auto"; ta.style.height = Math.min(160, ta.scrollHeight) + "px"; };
    const go = h("button", { type: "button", class: "btn primary ai-send", disabled: !(available() && cfg.ready), "aria-label": busy ? "Stop" : "Send", title: busy ? "Stop" : "Send (Enter)" }, icon(busy ? ICONS.stop : ICONS.send));
    ta.addEventListener("input", fit);
    ta.addEventListener("keydown", (e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); go.click(); } });
    go.addEventListener("click", () => { if (busy) return stop(); const q = ta.value.trim(); if (q) { ta.value = ""; fit(); send(q); } });
    el.appendChild(h("div", { class: "ai-compose" }, ta, go));
    box.scrollTop = box.scrollHeight;
    if (open && cfg.ready && !busy) requestAnimationFrame(() => ta.focus());
  }

  function bubble(m, i) {
    if (m.role === "user") return h("div", { class: "ai-msg user" }, m.content);
    if (m.role === "error") return h("div", { class: "ai-msg err", html: md(m.content) });
    const live = busy && i === msgs.length - 1;
    const body = h("div", { class: "ai-body" });
    if (!m.content && live) body.appendChild(h("span", { class: "ai-typing", "aria-label": "Thinking" }, h("i"), h("i"), h("i")));
    else body.innerHTML = md(m.content);
    return h("div", { class: "ai-msg bot" }, body, m.content && !live ? h("div", { class: "ai-acts" }, actions(m)) : null);
  }

  let chatSeq = 0;
  async function send(q) {
    if (busy || !cfg.ready) return;
    Codex.sfx.play("pop");
    msgs.push({ role: "user", content: q }, { role: "assistant", content: "" });
    const reply = msgs[msgs.length - 1];
    const chat = "c" + (++chatSeq) + Date.now().toString(36);
    busy = { chat };
    draw();
    let frame = 0;
    const redraw = () => {
      if (frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        const box = panel && panel.querySelector(".ai-msgs");
        if (!box) return;
        if (box.lastElementChild) box.lastElementChild.replaceWith(bubble(reply, msgs.length - 1));
        box.scrollTop = box.scrollHeight;
      });
    };
    busy.onChunk = (t) => { reply.content += t; redraw(); };
    try {
      const res = await Store.request({ type: "ai-chat", chat, context: context(), messages: msgs.filter((m) => m.content && m.role !== "error").map(({ role, content }) => ({ role, content })) }, 10 * 60 * 1000);
      if (res.stopped && !reply.content) msgs.pop();
      else if (reply.content) Codex.sfx.play("chime");
    } catch (e) {
      if (!reply.content) msgs.pop();
      msgs.push({ role: "error", content: e.message || "The assistant didn't answer." });
      Codex.sfx.play("error");
    }
    busy = null;
    draw();
  }
  function stop() { if (busy) Store.post({ type: "ai-stop", chat: busy.chat }); }
  Store.on("ai-chunk", (m) => { if (busy && m.chat === busy.chat && busy.onChunk) busy.onChunk(m.t || ""); });

  // ---------- settings ----------
  function settings(pick) {
    let p = pick || cfg.provider || "ollama";
    const models = Object.assign({}, cfg.models);
    const body = h("div", { class: "stack" });
    const status = h("p", { class: "ai-status", role: "status", hidden: true });
    let keyIn = null, urlIn = null, modelIn = null, list = null;
    const seg = h("div", { class: "chips", role: "radiogroup", "aria-label": "Assistant" }, [["ollama", "Ollama (free)"], ["anthropic", "Claude"], ["openai", "ChatGPT"]].map(([k, l]) =>
      h("button", { type: "button", class: "chip", role: "radio", "aria-checked": String(p === k), onclick: (e) => { p = k; seg.querySelectorAll(".chip").forEach((c) => c.setAttribute("aria-checked", String(c === e.currentTarget))); fill(); } }, l)));

    function fill() {
      body.innerHTML = "";
      keyIn = urlIn = null;
      if (p === "ollama") {
        body.appendChild(h("p", null, "Free and private: the AI runs on your own computer and nothing leaves it."));
        body.appendChild(Store.platform === "android"
          ? h("p", { class: "ai-note", html: "Ollama runs on your computer, not the tablet. On the computer, install it from <a href=\"https://ollama.com/download\">ollama.com</a>, run <code>ollama pull llama3.2</code> once, and start it with <code>OLLAMA_HOST=0.0.0.0 ollama serve</code> so the tablet can reach it. Then type the computer's address below, like <code>http://192.168.1.20:11434</code> (both on the same Wi-Fi)." })
          : h("p", { class: "ai-note", html: "First time? Install Ollama from <a href=\"https://ollama.com/download\">ollama.com</a>, then run <code>ollama pull llama3.2</code> once in a terminal. A computer with 8 GB of memory or more works best." }));
        urlIn = h("input", { id: "ai-url", value: cfg.ollama_url || "http://localhost:11434" });
        body.appendChild(h("div", { class: "field" }, h("label", { for: "ai-url" }, "Ollama address"), urlIn));
      } else {
        const n = NAMES[p], co = p === "anthropic" ? "Anthropic" : "OpenAI";
        body.appendChild(h("p", null, n + " runs on " + co + "'s servers and needs an API key, which is billed separately from a " + n + " subscription."));
        keyIn = h("input", { id: "ai-key", type: "password", autocomplete: "off", placeholder: cfg.keys[p] ? "Saved (" + cfg.keys[p] + "). Paste a new key to replace it." : (p === "anthropic" ? "sk-ant-…" : "sk-…") });
        body.appendChild(h("div", { class: "field" }, h("label", { for: "ai-key" }, co + " API key"), keyIn, h("a", { href: KEY_URL[p], class: "ai-link" }, "Get a key from " + co)));
        body.appendChild(h("p", { class: "ai-note warn" }, "What you type, and the game details you choose to share, are sent to " + co + ". The key stays on this computer, readable only by you."));
      }
      list = h("datalist", { id: "ai-models" }, SUGGESTED[p].map((m) => h("option", { value: m })));
      modelIn = h("input", { id: "ai-model", list: "ai-models", value: models[p] || "", placeholder: p === "openai" ? "Press Test to load your models" : SUGGESTED[p][0] || "" });
      body.appendChild(h("div", { class: "field" }, h("label", { for: "ai-model" }, "Model"), modelIn, list));
      status.hidden = true;
      body.appendChild(status);
    }
    fill();

    const payload = () => {
      models[p] = modelIn.value.trim();
      const d = { provider: p, models };
      if (urlIn) d.ollama_url = urlIn.value.trim();
      if (keyIn && keyIn.value.trim()) d.keys = { [p]: keyIn.value.trim() };
      return d;
    };
    const say = (text, warn) => { status.hidden = false; status.textContent = text; status.classList.toggle("warn", !!warn); };
    const test = h("button", { type: "button", class: "btn ghost", onclick: async () => {
      say("Connecting…");
      const d = payload();
      try {
        const r = await Store.request({ type: "ai-models", provider: p, key: d.keys && d.keys[p], url: d.ollama_url }, 60000);
        list.innerHTML = "";
        r.models.forEach((m) => list.appendChild(h("option", { value: m })));
        if (!modelIn.value && r.models.length) modelIn.value = r.models.includes(SUGGESTED[p][0]) ? SUGGESTED[p][0] : r.models[0];
        say(r.models.length ? "Connected. " + r.models.length + " model" + (r.models.length === 1 ? "" : "s") + " available." : p === "ollama" ? "Connected, but no models are installed. Run: ollama pull llama3.2" : "Connected.");
        Codex.sfx.play("chime");
      } catch (e) { say(e.message, true); Codex.sfx.play("error"); }
    } }, icon(ICONS.check), "Test");
    const save = h("button", { type: "button", class: "btn primary", onclick: async () => {
      const d = payload();
      if (p !== "ollama" && !(d.keys || cfg.keys[p])) { say("Paste your API key first.", true); return; }
      if (p === "openai" && !d.models.openai) { say("Pick a model. Press Test to load the models your key can use.", true); return; }
      try {
        cfg = (await Store.request({ type: "ai-set", settings: d })).settings;
        dlg.close();
        toast("The assistant now uses " + NAMES[p]);
        if (!open) show(); else draw();
        refreshButtons();
      } catch (e) { say(e.message, true); }
    } }, "Save");
    const off = cfg.provider ? h("button", { type: "button", class: "btn ghost danger-text", onclick: async () => {
      cfg = (await Store.request({ type: "ai-set", settings: { provider: "" } })).settings;
      dlg.close(); draw(); toast("The assistant is off");
    } }, "Turn off") : null;
    const dlg = dialog({ title: "Assistant", class: "small ai-settings", body: [seg, body], actions: [off, h("span", { class: "grow" }), test, save] });
  }

  // a small block for the Settings dialog
  function settingsPanel(closeSettings) {
    if (!available()) return h("p", { class: "muted" }, "The assistant works in the desktop app, with Ollama (free, on your computer), Claude or ChatGPT.");
    const text = h("p", null, cfg.ready ? "Using " + NAMES[cfg.provider] + " (" + (cfg.models[cfg.provider] || "default model") + "). Press Ctrl+J or the sparkle at the top to open it." : "Off. Use Ollama for free, or Claude or ChatGPT with your own API key.");
    if (!loaded) load().then(() => { text.textContent = cfg.ready ? "Using " + NAMES[cfg.provider] + " (" + (cfg.models[cfg.provider] || "default model") + ")." : text.textContent; });
    return h("div", null, text, h("div", { class: "row-btns" }, h("button", { type: "button", class: "btn ghost", onclick: () => { closeSettings(); settings(); } }, icon(ICONS.spark), cfg.ready ? "Change" : "Set up the assistant")));
  }

  function refreshButtons() {
    const b = document.getElementById("ai-btn");
    if (b) { b.classList.toggle("on", open); b.setAttribute("aria-pressed", String(open)); }
  }
  function show() {
    open = true;
    ensurePanel().hidden = false;
    document.body.classList.add("ai-open");
    if (!loaded) load(); else draw();
    draw();
    refreshButtons();
  }
  function close() {
    open = false;
    stop();
    if (panel) panel.hidden = true;
    document.body.classList.remove("ai-open");
    refreshButtons();
  }
  function toggle() { if (open) close(); else show(); }
  // when you move around the game, the quick prompts and the share line follow
  function refresh() { if (open && !busy) draw(); }

  document.addEventListener("keydown", (e) => {
    if ((e.ctrlKey || e.metaKey) && !e.shiftKey && e.key.toLowerCase() === "j" && !document.querySelector(".backdrop")) { e.preventDefault(); toggle(); }
  }, true);

  return { toggle, show, close, refresh, settings, settingsPanel, isOpen: () => open, load };
})();
