// Kiln assistant drawer: chat with Ollama (local), Claude or ChatGPT about your assets.
// API keys stay in the local server (see ai.py); this page only sends messages and the context you allow.
import { icon } from './icons.js';
import { sfx } from './sound.js';

const NAMES = { ollama: 'Ollama', anthropic: 'Claude', openai: 'ChatGPT' };
const KEY_URL = { anthropic: 'https://console.anthropic.com/settings/keys', openai: 'https://platform.openai.com/api-keys' };
const SUGGESTED = { ollama: ['llama3.2', 'qwen3', 'gemma3', 'mistral'], anthropic: ['claude-sonnet-5-5', 'claude-haiku-5-5', 'claude-opus-5-5'], openai: [] };

const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
const ibtn = (name, label, cls, title) => { const b = el('button', cls); b.append(icon(name, 16)); if (label) b.append(el('span', 0, label)); if (title) { b.title = title; b.setAttribute('aria-label', title); } return b; };
const esc = s => s.replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const stripHtml = h => { const t = document.createElement('template'); t.innerHTML = h || ''; return t.content.textContent.trim(); };

// Small, safe markdown: escapes everything first, then adds headings, lists, bold, italics and code.
function md(src) {
  const inline = s => esc(s).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>').replace(/(^|[\s(])\*([^*\s][^*]*)\*/g, '$1<i>$2</i>');
  const out = []; let list = null, code = null;
  const close = () => { if (list) { out.push(`</${list}>`); list = null; } };
  for (const line of src.split('\n')) {
    if (code !== null) { if (/^```/.test(line)) { out.push(`<pre><code>${esc(code)}</code></pre>`); code = null; } else code += (code ? '\n' : '') + line; continue; }
    if (/^```/.test(line)) { close(); code = ''; continue; }
    let m;
    if ((m = line.match(/^\s*[-*•]\s+(.*)/))) { if (list !== 'ul') { close(); out.push('<ul>'); list = 'ul'; } out.push(`<li>${inline(m[1])}</li>`); continue; }
    if ((m = line.match(/^\s*\d+[.)]\s+(.*)/))) { if (list !== 'ol') { close(); out.push('<ol>'); list = 'ol'; } out.push(`<li>${inline(m[1])}</li>`); continue; }
    close();
    if ((m = line.match(/^#{1,4}\s+(.*)/))) out.push(`<h4>${inline(m[1])}</h4>`);
    else if (line.trim()) out.push(`<p>${inline(line)}</p>`);
  }
  if (code !== null) out.push(`<pre><code>${esc(code)}</code></pre>`);
  close();
  return out.join('');
}
const listItems = text => text.split('\n').map(l => l.match(/^\s*(?:[-*•]|\d+[.)])\s+(.*)/)).filter(Boolean).map(m => m[1].replace(/\*\*/g, '').trim()).filter(Boolean);

export function createAssistant(h) {
  // h: { toast, modal, choose, getProfile, getState, onChange, addAssets(category, names) }
  const panel = document.getElementById('assistant');
  let cfg = { provider: '', models: {}, keys: {}, ready: false, ollama_url: 'http://localhost:11434' };
  let msgs = [], busy = null, ws = null, includeCtx = true, open = false;
  const load = () => fetch('/api/ai').then(r => r.json()).then(c => { cfg = c; if (open) draw(); }).catch(() => {});
  load();

  function context() {
    const p = h.getProfile() || {}, ctx = { profile: { tool: p.tool, engine: p.engine, level: p.level } };
    if (!includeCtx) return ctx;
    if (ws) {
      const a = ws.asset, items = [];
      (a.board || []).forEach(it => {
        if (it.t === 'note' || it.t === 'text') { const t = stripHtml(it.html || it.text); if (t) items.push(t); }
        else if (it.t === 'todo') items.push(`${it.title || 'To-do'}: ${(it.items || []).map(x => (x.d ? '[done] ' : '') + x.t).filter(Boolean).join('; ')}`);
        else if (it.t === 'link' && it.url) items.push('Link: ' + it.url);
        else if (it.t === 'col' && it.title) items.push('Frame: ' + it.title);
      });
      ctx.asset = { name: a.name, category: ws.category, status: a.status, notes: a.notes, poly: a.poly, items };
    }
    const S = h.getState();
    if (S) ctx.board = S.categories.map(c => ({ name: c.name, assets: c.assets.map(x => ({ name: x.name, status: x.status })) }));
    return ctx;
  }

  function starters() {
    if (ws) {
      const n = ws.asset.name;
      return [`Write a short art brief for the ${n}`, `Break the ${n} into modeling and texturing tasks`, `Suggest a polycount and texture budget for the ${n}`, `What reference images should I collect for the ${n}?`];
    }
    const S = h.getState(), cat = S && S.categories.find(c => c.assets.length) || (S && S.categories[0]);
    return [
      'Brainstorm 10 props for a cozy medieval tavern',
      cat ? `What assets am I missing in ${cat.name}?` : 'What categories should a small 3D game start with?',
      'Plan my asset work for the next week',
      'Suggest a file naming convention for my assets',
    ];
  }

  function draw() {
    panel.textContent = '';
    const head = el('div', 'ah'), mark = el('div', 'mark'); mark.append(icon('sparkle', 18));
    const who = el('div', 'who'), sub = el('small');
    if (cfg.ready) { sub.append(el('i', 'dot done'), `${NAMES[cfg.provider]}, ${cfg.models[cfg.provider] || 'default model'}`); }
    else sub.textContent = 'Not set up yet';
    who.append(el('b', 0, 'Assistant'), sub);
    const nw = ibtn('plus', '', 'ghost icon', 'New chat'); nw.onclick = () => { stop(); msgs = []; draw(); };
    const st = ibtn('settings', '', 'ghost icon', 'Assistant settings'); st.onclick = () => settings();
    const cl = ibtn('close', '', 'ghost icon', 'Close assistant (Ctrl+J)'); cl.onclick = () => close();
    head.append(mark, who, nw, st, cl); panel.append(head);

    const box = el('div', 'msgs'); panel.append(box);
    if (!cfg.ready) {
      const s = el('div', 'setup');
      s.append(el('b', 0, 'Choose an assistant'), el('p', 0, 'Kiln can brainstorm assets, write briefs and break work into tasks. Pick the AI it should use:'));
      [['ollama', 'Ollama', 'Free, runs on your computer'], ['anthropic', 'Claude', 'Needs an Anthropic API key'], ['openai', 'ChatGPT', 'Needs an OpenAI API key']].forEach(([k, n, d]) => {
        const b = el('button'); b.style.cssText = 'width:100%;justify-content:flex-start;flex-direction:column;align-items:flex-start;gap:2px;padding:10px 12px;border-radius:12px';
        b.append(el('b', 0, n), Object.assign(el('small', 0, d), { style: 'color:var(--m)' }));
        b.onclick = () => settings(k); s.append(b);
      });
      box.append(s);
    } else if (!msgs.length) {
      const s = el('div', 'starters'), hi = el('div', 'hello');
      hi.append(el('b', 0, ws ? `Working on the ${ws.asset.name}?` : 'What are you making?'), ws ? 'Ask about this asset, or try one of these.' : 'Ask anything about your assets, or try one of these.');
      s.append(hi);
      starters().forEach(q => { const b = el('button', 0, q); b.onclick = () => send(q); s.append(b); });
      box.append(s);
    } else {
      msgs.forEach((m, i) => box.append(bubble(m, i)));
    }

    const ctx = el('div', 'ctx'), lab = el('label'), cb = el('input');
    cb.type = 'checkbox'; cb.checked = includeCtx; cb.onchange = () => { includeCtx = cb.checked; };
    lab.append(cb, ws ? `Share the ${ws.asset.name} and my board` : 'Share my board (asset names and status)');
    lab.title = 'Lets the assistant see your asset names, statuses, notes and the text on the open board.';
    ctx.append(lab);
    const comp = el('div', 'compose'), ta = el('textarea'), go = el('button', 'pri');
    ta.rows = 1; ta.placeholder = cfg.ready ? (ws ? `Ask about the ${ws.asset.name}` : 'Ask the assistant') : 'Set up the assistant first';
    ta.disabled = !cfg.ready; ta.setAttribute('aria-label', 'Message');
    const fit = () => { ta.style.height = 'auto'; ta.style.height = Math.min(160, ta.scrollHeight) + 'px'; };
    ta.oninput = fit;
    ta.onkeydown = e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); go.click(); } };
    const drawGo = () => { go.textContent = ''; go.append(icon(busy ? 'stop' : 'arrow', 18)); go.title = busy ? 'Stop' : 'Send (Enter)'; go.setAttribute('aria-label', go.title); go.disabled = !cfg.ready; };
    drawGo(); go.dataset.sfx = 'none';
    go.onclick = () => { if (busy) return stop(); const q = ta.value.trim(); if (q) { ta.value = ''; fit(); send(q); } };
    comp.append(ta, go);
    if (cfg.ready) panel.append(ctx);
    panel.append(comp);
    box.scrollTop = box.scrollHeight;
    if (cfg.ready && open) setTimeout(() => ta.focus());
  }

  function bubble(m, i) {
    if (m.role === 'user') return el('div', 'msg user', m.content);
    if (m.role === 'error') { const e = el('div', 'msg err'); e.innerHTML = md(m.content); return e; }
    const b = el('div', 'msg bot'), body = el('div', 'body');
    if (!m.content && busy && i === msgs.length - 1) { const t = el('div', 'typing'); t.append(el('i'), el('i'), el('i')); body.append(t); }
    else body.innerHTML = md(m.content);
    b.append(body);
    if (m.content && !(busy && i === msgs.length - 1)) {
      const acts = el('div', 'acts'), items = listItems(m.content);
      const cp = ibtn('copy', 'Copy'); cp.onclick = () => { navigator.clipboard.writeText(m.content).then(() => h.toast('Copied')).catch(() => h.toast('Copy failed', 'err')); };
      acts.append(cp);
      if (ws) {
        const n = ibtn('note', 'Add as note'); n.onclick = () => { ws.addNote(md(m.content)); h.toast(`Added a note to the ${ws.asset.name}`, 'ok'); };
        acts.append(n);
        if (items.length) { const t = ibtn('todo', `Add ${items.length} as to-do`); t.onclick = () => { ws.addTodo('To-do', items); h.toast(`Added ${items.length} tasks`, 'ok'); }; acts.append(t); }
      } else if (items.length && h.getState()) {
        const t = ibtn('plus', `Add ${items.length} as assets`); t.onclick = async () => {
          const S = h.getState(); if (!S.categories.length) return h.toast('Make a category first', 'err');
          const to = await h.choose(`Add ${items.length} assets to`, S.categories.map(c => c.name), undefined, 'Add');
          if (to) { h.addAssets(to, items); h.toast(`Added ${items.length} assets to ${to}`, 'ok'); }
        };
        acts.append(t);
      }
      b.append(acts);
    }
    return b;
  }

  async function send(q) {
    if (busy || !cfg.ready) return;
    sfx.play('pop');
    msgs.push({ role: 'user', content: q }, { role: 'assistant', content: '' });
    const reply = msgs[msgs.length - 1];
    busy = new AbortController(); draw();
    let frame = 0;
    const redraw = () => { if (frame) return; frame = requestAnimationFrame(() => { frame = 0; const box = panel.querySelector('.msgs'); if (!box) return; const last = box.lastElementChild; if (last) last.replaceWith(bubble(reply, msgs.length - 1)); box.scrollTop = box.scrollHeight; }); };
    try {
      const r = await fetch('/api/ai/chat', { method: 'POST', signal: busy.signal, body: JSON.stringify({ messages: msgs.filter(m => m.content && m.role !== 'error').map(({ role, content }) => ({ role, content })), context: context() }) });
      const rd = r.body.getReader(), dec = new TextDecoder(); let buf = '';
      for (;;) {
        const { value, done } = await rd.read(); if (done) break;
        buf += dec.decode(value, { stream: true });
        let nl; while ((nl = buf.indexOf('\n')) >= 0) {
          const line = buf.slice(0, nl).trim(); buf = buf.slice(nl + 1); if (!line) continue;
          const d = JSON.parse(line);
          if (d.t) { reply.content += d.t; redraw(); }
          if (d.error) { msgs.pop(); msgs.push({ role: 'error', content: d.error }); sfx.play('error'); }
        }
      }
      if (reply.content) sfx.play('chime');
    } catch (e) {
      if (e.name !== 'AbortError') { if (!reply.content) msgs.pop(); msgs.push({ role: 'error', content: "Couldn't reach Kiln's server. Is the app still running?" }); }
      else if (!reply.content) msgs.pop();
    }
    busy = null; draw();
  }
  function stop() { if (busy) busy.abort(); }

  /* ---------- settings ---------- */
  function settings(pick) {
    h.modal((c, cl) => {
      let p = pick || cfg.provider || 'ollama';
      const models = { ...cfg.models };
      c.append(el('h3', 0, 'Assistant'));
      const seg = el('div', 'seg3'); seg.style.alignSelf = 'flex-start';
      const body = el('div'); body.style.cssText = 'display:flex;flex-direction:column;gap:12px';
      [['ollama', 'Ollama (free)'], ['anthropic', 'Claude'], ['openai', 'ChatGPT']].forEach(([k, l]) => {
        const b = el('button', p === k ? 'on' : '', l); b.dataset.sfx = 'toggle';
        b.onclick = () => { p = k; seg.querySelectorAll('button').forEach(x => x.classList.toggle('on', x === b)); fill(); };
        seg.append(b);
      });
      c.append(seg, body);
      const status = el('div', 'note'); status.setAttribute('role', 'status');
      let keyIn, urlIn, modelIn, list;

      function fill() {
        body.textContent = '';
        if (p === 'ollama') {
          body.append(el('p', 0, 'Free and private: the AI runs on your own computer and nothing leaves it.'));
          const steps = el('div', 'note');
          if (/KilnAndroid/.test(navigator.userAgent)) steps.innerHTML = 'On this tablet, Ollama runs on your PC: install it from <a href="https://ollama.com/download" target="_blank" rel="noopener">ollama.com</a>, run <code>ollama pull llama3.2</code>, start it with <code>OLLAMA_HOST=0.0.0.0 ollama serve</code>, then type your PC’s address below, like <code>http://192.168.1.20:11434</code>.';
          else steps.innerHTML = 'First time? Install Ollama from <a href="https://ollama.com/download" target="_blank" rel="noopener">ollama.com</a>, then run <code>ollama pull llama3.2</code> once in a terminal. A computer with 8 GB of memory or more works best.';
          body.append(steps);
          const f = el('label', 'field', 'Ollama address'); urlIn = el('input'); urlIn.value = cfg.ollama_url || 'http://localhost:11434'; f.append(urlIn); body.append(f);
        } else {
          const n = NAMES[p], co = p === 'anthropic' ? 'Anthropic' : 'OpenAI';
          body.append(el('p', 0, `${n} runs on ${co}'s servers and needs an API key, which is billed separately from a ${n} subscription.`));
          const f = el('label', 'field'); f.append(`${co} API key`);
          keyIn = el('input'); keyIn.type = 'password'; keyIn.autocomplete = 'off'; keyIn.placeholder = cfg.keys[p] ? `Saved (${cfg.keys[p]}). Paste a new key to replace it.` : (p === 'anthropic' ? 'sk-ant-…' : 'sk-…');
          f.append(keyIn);
          const get = el('a', 0, `Get a key from ${co}`); get.href = KEY_URL[p]; get.target = '_blank'; get.rel = 'noopener'; get.style.cssText = 'color:var(--a);font-size:12.5px';
          body.append(f, get);
          const warn = el('div', 'note warn');
          warn.textContent = `Your messages, and the asset details you choose to share, are sent to ${co}. The key is stored only on this computer, in ~/Kiln/ai.json.`;
          body.append(warn);
        }
        const mf = el('label', 'field', 'Model'); modelIn = el('input'); list = el('datalist'); list.id = 'kiln-models';
        modelIn.setAttribute('list', 'kiln-models'); modelIn.value = models[p] || ''; modelIn.placeholder = p === 'openai' ? 'Press Test to load your models' : (SUGGESTED[p][0] || '');
        SUGGESTED[p].forEach(m => list.append(new Option(m, m)));
        mf.append(modelIn, list); body.append(mf);
        status.textContent = ''; status.style.display = 'none'; body.append(status);
      }
      fill();

      const payload = () => {
        models[p] = modelIn.value.trim();
        const d = { provider: p, models };
        if (urlIn && p === 'ollama') d.ollama_url = urlIn.value.trim();
        if (keyIn && p !== 'ollama' && keyIn.value.trim()) d.keys = { [p]: keyIn.value.trim() };
        return d;
      };
      const test = ibtn('check', 'Test'), save = el('button', 'pri', 'Save'), off = el('button', 'ghost', 'Turn off');
      test.onclick = async () => {
        status.style.display = ''; status.className = 'note'; status.textContent = 'Connecting…';
        const d = payload();
        const r = await fetch('/api/ai/models', { method: 'POST', body: JSON.stringify({ provider: p, key: d.keys && d.keys[p], url: d.ollama_url }) }).then(r => r.json()).catch(() => ({ error: "Couldn't reach Kiln's server." }));
        if (r.error) { status.className = 'note warn'; status.textContent = r.error; sfx.play('error'); return; }
        list.textContent = ''; r.models.forEach(m => list.append(new Option(m, m)));
        if (!modelIn.value && r.models.length) modelIn.value = r.models.includes(SUGGESTED[p][0]) ? SUGGESTED[p][0] : r.models[0];
        status.textContent = r.models.length ? `Connected. ${r.models.length} model${r.models.length === 1 ? '' : 's'} available.` : (p === 'ollama' ? 'Connected, but no models are installed. Run: ollama pull llama3.2' : 'Connected.');
        sfx.play('chime');
      };
      save.onclick = async () => {
        const d = payload();
        if (p !== 'ollama' && !(d.keys || cfg.keys[p])) { status.style.display = ''; status.className = 'note warn'; status.textContent = 'Paste your API key first.'; return sfx.play('error'); }
        if (!d.models[p] && p === 'openai') { status.style.display = ''; status.className = 'note warn'; status.textContent = 'Pick a model. Press Test to load the models your key can use.'; return sfx.play('error'); }
        cfg = await fetch('/api/ai', { method: 'POST', body: JSON.stringify(d) }).then(r => r.json());
        cl(); h.toast(`Assistant set to ${NAMES[p]}`, 'ok'); h.onChange(); if (!open) toggle(); else draw();
      };
      off.onclick = async () => { cfg = await fetch('/api/ai', { method: 'POST', body: JSON.stringify({ provider: '' }) }).then(r => r.json()); cl(); h.onChange(); if (open) draw(); };
      const r = el('div', 'row'); r.style.justifyContent = 'space-between';
      const l = el('div', 'row'); if (cfg.provider) l.append(off);
      const rr = el('div', 'row'); const cancel = el('button', 'ghost', 'Cancel'); cancel.onclick = cl; rr.append(cancel, test, save);
      r.append(l, rr); c.append(r);
    }, true);
  }

  function toggle() { open ? close() : show(); }
  function show() { open = true; panel.classList.remove('hidden'); document.body.classList.add('ai-open'); draw(); h.onChange(); }
  function close() { open = false; stop(); panel.classList.add('hidden'); document.body.classList.remove('ai-open'); h.onChange(); }
  document.addEventListener('keydown', e => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'j' && !document.querySelector('.gate')) { e.preventDefault(); toggle(); }
  });

  return {
    toggle, show, close, settings, isOpen: () => open,
    summary: () => cfg.ready ? `Using ${NAMES[cfg.provider]} (${cfg.models[cfg.provider] || 'default model'})` : 'Not set up. Use Ollama for free, or Claude or ChatGPT with an API key.',
    attach(ctx) { ws = ctx; if (open) draw(); },
    detach() { ws = null; if (open) draw(); },
  };
}
