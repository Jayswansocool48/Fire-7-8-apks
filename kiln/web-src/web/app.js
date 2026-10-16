// Kiln — profiles, onboarding, asset board, per-asset canvas, library, settings and the assistant drawer.
import { icon } from './icons.js';
import { sfx } from './sound.js';
import { createAssistant } from './assistant.js';

/* ---------- helpers ---------- */
const $ = s => document.querySelector(s);
const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
};
const uid = () => Math.random().toString(36).slice(2, 9).padEnd(7, '0');
const api = (p, o) => fetch(p, o).then(r => r.json());
const row = (...kids) => { const r = el('div', 'row'); r.append(...kids); return r; };
const ibtn = (name, label, { cls, title, sfx: s, size } = {}) => {
  const b = el('button', cls);
  b.append(icon(name, size || 17));
  if (label) b.append(el('span', 0, label));
  if (title) { b.title = title; b.setAttribute('aria-label', title); }
  if (s) b.dataset.sfx = s;
  return b;
};
// Running inside the Android app (Fire HD 8 tablet)? Its WebView adds "KilnAndroid" to the user agent.
const ANDROID = /KilnAndroid/.test(navigator.userAgent);
const typingIn = n => !!n && (/TEXTAREA|INPUT|SELECT/.test(n.tagName) || n.isContentEditable);

const ST = ['planned', 'modeled', 'done'];
const LB = { planned: 'Planned', modeled: 'Modeled', done: 'Complete' };
const STATUS_SFX = { planned: 'tick', modeled: 'modeled', done: 'complete' };
const W = 4000, H = 3000;
const COLORS = ['#fff3b0', '#ffd6a5', '#caffbf', '#9bf6ff', '#bdb2ff', '#ffc6ff', '#fdffb6', '#e5e5e5'];
const USES = [
  ['3D game assets', ['Props', 'Weapons', 'Tools', 'Furniture']],
  ['Characters & creatures', ['Characters', 'Creatures']],
  ['Environments & levels', ['Environment', 'Buildings', 'Vehicles']],
  ['Textures & materials', ['Materials']],
  ['Animation', ['Animations']],
  ['Concept art & 2D', ['Concepts']],
  ['World building & story', ['Lore']],
  ['Prototyping', ['Prototypes']],
];
const ACC = ['#2f80ed', '#0f9f8f', '#d9468f', '#e07b12', '#7c3aed', '#3f8f3a'];   // extra accents; null = Kiln indigo
const OLD_DEFAULT_ACCENT = '#ff7a3d';
const FONTS = [
  ['System UI', 'system-ui,sans-serif'], ['Serif', 'Georgia,"Times New Roman",serif'],
  ['Elegant', '"Palatino Linotype",Palatino,serif'], ['Monospace', 'ui-monospace,Menlo,Consolas,monospace'],
  ['Handwriting', '"Segoe Script","Comic Sans MS","Bradley Hand",cursive'], ['Display', 'Impact,"Arial Black",sans-serif'],
  ['Rounded', '"Trebuchet MS","Varela Round",sans-serif'],
];
const LIB_DIRS = ['textures', 'images', 'models', 'fonts', 'blender', 'games'];
const KIND_ICON = { Models: 'cube', Textures: 'texture', Images: 'image', Fonts: 'font', Blender: 'blender', Games: 'gamepad', Other: 'file' };

let PR, S, view = 'board', statusFilter = 'all', saveT, renderId = 0, libCount = 0;
let PROFILES = { profiles: [], last: '' };
const collapsed = new Set();

/* ---------- storage ---------- */
const base = () => `/api/${PR.id}`;
const FU = p => `/files/${PR.id}/${p}`;
function setSaveState(txt, err) {
  const s = $('#savestate'); if (!s) return;
  s.textContent = txt; s.classList.toggle('err', !!err);
}
async function flush() {
  clearTimeout(saveT);
  if (!PR || !S) return;
  setSaveState('Saving…');
  try {
    const r = await fetch(base() + '/state', { method: 'POST', body: JSON.stringify(S) });
    if (!r.ok) throw new Error(r.status);
    setSaveState('All changes saved');
  } catch (e) {
    setSaveState('Not saved. Is Kiln still running?', true);
    sfx.play('error');
  }
}
const save = () => { clearTimeout(saveT); setSaveState('Unsaved changes'); saveT = setTimeout(flush, 300); };
const saveP = () => fetch('/api/profiles', { method: 'POST', body: JSON.stringify(PROFILES) });
// Last-chance save when the window closes inside the debounce window.
const beacon = () => { if (saveT && PR && S) navigator.sendBeacon(base() + '/state', JSON.stringify(S)); };
addEventListener('pagehide', beacon);
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') beacon(); }); // Android: app sent to the background
const up = async (f, dir, name) => (await api(`${base()}/upload?path=${encodeURIComponent(dir + '/' + (name || f.name))}${name ? '&overwrite=1' : ''}`, { method: 'POST', body: f })).url;

const isModel = n => /\.(glb|gltf)$/i.test(n);
const isImg = n => /\.(png|jpe?g|webp|bmp|gif|svg)$/i.test(n);
const esc = s => String(s).replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c])).replace(/\n/g, '<br>');
const clean = h => {
  const t = document.createElement('template'); t.innerHTML = h;
  t.content.querySelectorAll('script,iframe,object,embed,style,link,meta,form').forEach(n => n.remove());
  t.content.querySelectorAll('*').forEach(n => [...n.attributes].forEach(a => { if (/^on/i.test(a.name) || /javascript:/i.test(a.value)) n.removeAttribute(a.name); }));
  return t.innerHTML;
};
const regFont = f => new FontFace(f.name, `url("${f.url}")`).load().then(x => document.fonts.add(x)).catch(() => {});
const avatar = p => { const a = el('div', 'av', (p.name[0] || '?').toUpperCase()); a.style.background = p.accent || 'linear-gradient(135deg,#5b4cf5,#8b7fff)'; a.style.color = p.accent ? inkFor(p.accent) : '#fff'; return a; };
const allAssets = () => S.categories.flatMap(c => c.assets);

/* ---------- global sound hooks ---------- */
document.addEventListener('pointerdown', e => {
  if (e.button !== 0) return;
  const b = e.target.closest('button,.mi,.pc,.addcard,[data-sfx]');
  if (!b || b.disabled) return;
  sfx.play(b.dataset.sfx || 'click');
}, true);

/* ---------- toasts, dialogs, menus ---------- */
function toast(msg, kind = '') {
  const t = el('div', 'toast ' + kind);
  t.append(icon(kind === 'err' ? 'info' : kind === 'ok' ? 'check' : 'sparkle', 16), el('span', 0, msg));
  $('#toasts').append(t);
  setTimeout(() => { t.classList.add('out'); setTimeout(() => t.remove(), 300); }, 2600);
}
function modal(build, wide) {
  const o = el('div', 'ov'), c = el('div', 'dlg' + (wide ? ' wide' : ''));
  o.append(c); document.body.append(o);
  const close = () => { o.remove(); removeEventListener('keydown', esc_, true); };
  const esc_ = e => { if (e.key === 'Escape') { e.stopPropagation(); close(); } };
  addEventListener('keydown', esc_, true);
  o.addEventListener('pointerdown', e => { if (e.target === o) close(); });
  build(c, close);
  return close;
}
const ask = (title, val = '', ph = '') => new Promise(res => modal((c, cl) => {
  const i = el('input'); i.value = val; i.placeholder = ph;
  const ok = el('button', 'pri', 'OK'), no = el('button', 'ghost', 'Cancel');
  const r = row(no, ok); r.classList.add('end');
  c.append(el('h3', 0, title), i, r);
  ok.onclick = () => { cl(); res(i.value.trim() || null); };
  no.onclick = () => { cl(); res(null); };
  i.onkeydown = e => { if (e.key === 'Enter') ok.click(); };
  setTimeout(() => { i.focus(); i.select(); });
}));
const sure = (msg, { yes = 'Delete', sound = 'delete', detail } = {}) => new Promise(res => modal((c, cl) => {
  const ok = el('button', 'pri', yes), no = el('button', 'ghost', 'Cancel');
  ok.dataset.sfx = sound;
  if (sound === 'delete') { ok.className = 'danger'; }
  const r = row(no, ok); r.classList.add('end');
  c.append(el('h3', 0, msg)); if (detail) c.append(el('p', 0, detail)); c.append(r);
  ok.onclick = () => { cl(); res(true); };
  no.onclick = () => { cl(); res(false); };
  setTimeout(() => ok.focus());
}));
const choose = (title, options, cur, okLabel = 'Move') => new Promise(res => modal((c, cl) => {
  const s = el('select'); options.forEach(o => s.append(new Option(o, o))); if (cur) s.value = cur;
  const ok = el('button', 'pri', okLabel), no = el('button', 'ghost', 'Cancel');
  const r = row(no, ok); r.classList.add('end');
  c.append(el('h3', 0, title), s, r);
  ok.onclick = () => { cl(); res(s.value); };
  no.onclick = () => { cl(); res(null); };
}));
// items: [label, fn, iconName?, cls?] | {colors: fn} | '-'
function menu(x, y, items) {
  document.querySelector('.cm')?.remove();
  const m = el('div', 'cm');
  items.forEach(i => {
    if (i === '-') return m.append(el('hr'));
    if (i.colors) {
      const r = el('div', 'sw');
      COLORS.forEach(c => { const b = el('i'); b.style.background = c; b.onclick = () => { m.remove(); sfx.play('tick'); i.colors(c); }; r.append(b); });
      return m.append(r);
    }
    const b = el('div', 'mi' + (i[3] ? ' ' + i[3] : ''));
    if (i[2]) b.append(icon(i[2], 16));
    b.append(el('span', 0, i[0]));
    b.onclick = () => { m.remove(); i[1](); };
    m.append(b);
  });
  document.body.append(m);
  const r = m.getBoundingClientRect();
  m.style.left = Math.min(x, innerWidth - r.width - 8) + 'px';
  m.style.top = Math.min(y, innerHeight - r.height - 8) + 'px';
  const away = e => { if (!m.contains(e.target)) { m.remove(); removeEventListener('pointerdown', away, true); } };
  setTimeout(() => addEventListener('pointerdown', away, true));
}

/* ---------- assistant ---------- */
const assistant = createAssistant({
  toast, modal, choose,
  getProfile: () => PR, getState: () => (PR ? S : null),
  onChange: () => { if (PR) drawRail(); },
  addAssets: (catName, names) => {
    const c = S.categories.find(x => x.name === catName); if (!c) return;
    names.forEach(n => c.assets.push(mk(n.split(/:|\s[\u2013\u2014-]\s/)[0].trim().slice(0, 80) || n.slice(0, 80))));
    save(); if (!document.querySelector('.ws')) render();
  },
});

/* ---------- profiles / onboarding ---------- */
const gate = () => { const g = el('div', 'gate'); document.body.append(g); return g; };
function picker() {
  const g = gate(); g.append(el('h1', 0, "Who's working today?"));
  const w = el('div', 'pk');
  PROFILES.profiles.forEach((p, i) => {
    const c = el('div', 'pc'); c.style.animationDelay = i * 40 + 'ms';
    c.append(avatar(p), el('b', 0, p.name), el('small', 0, (p.uses && p.uses[0]) || 'Game developer'));
    c.onclick = () => { g.remove(); enter(p, true); };
    const x = el('span', 'px'); x.append(icon('close', 15)); x.title = 'Remove profile';
    x.onclick = async e => {
      e.stopPropagation();
      if (await sure(`Remove "${p.name}"?`, { yes: 'Remove', detail: `Their files stay in ~/Kiln/profiles/${p.id}` })) {
        PROFILES.profiles = PROFILES.profiles.filter(q => q !== p); await saveP();
        g.remove(); PROFILES.profiles.length ? picker() : onboard();
      }
    };
    c.append(x); w.append(c);
  });
  const add = el('div', 'pc'); const plus = el('div', 'av'); plus.append(icon('plus', 26)); plus.style.background = 'var(--p3)'; plus.style.color = 'var(--t)';
  add.append(plus, el('b', 0, 'New profile'), el('small', 0, 'Set up a workspace'));
  add.onclick = () => { g.remove(); onboard(); };
  w.append(add); g.append(w);
}
const chips = (opts, sel, multi, on) => {
  const w = el('div', 'chips');
  opts.forEach(o => {
    const b = el('button', 'chip' + (sel.includes(o) ? ' on' : ''), o); b.dataset.sfx = 'toggle';
    b.onclick = () => {
      if (multi) sel.includes(o) ? sel.splice(sel.indexOf(o), 1) : sel.push(o);
      else { sel.length = 0; sel.push(o); }
      w.querySelectorAll('.chip').forEach(c => c.classList.toggle('on', sel.includes(c.textContent)));
      on && on();
    };
    w.append(b);
  });
  return w;
};
function onboard() {
  const g = gate(), card = el('div', 'wiz'); g.append(card);
  const d = { name: '', color: null, uses: [], level: ['Beginner'], tool: ['Blender'], engine: ['Unity'], cats: [] };
  const first = !PROFILES.profiles.length;
  let step = 0;
  applyAccent(null);
  const steps = [
    () => {
      card.append(el('h2', 0, first ? 'Welcome to Kiln' : 'New profile'), el('p', 0, "Let's set up your workspace. What should we call you?"));
      const i = el('input'); i.placeholder = 'Your name'; i.value = d.name; i.oninput = () => d.name = i.value;
      i.onkeydown = e => { if (e.key === 'Enter') card.querySelector('.nav2 .pri').click(); };
      card.append(i, el('p', 0, 'Pick an accent colour'));
      const s = el('div', 'sws');
      [null, ...ACC].forEach(c => {
        const b = el('i', c === d.color ? 'on' : ''); b.style.background = c || 'linear-gradient(135deg,#5b4cf5,#8b7fff)'; b.title = c || 'Kiln indigo';
        b.onclick = () => { sfx.play('tick'); d.color = c; applyAccent(c); s.querySelectorAll('i').forEach(x => x.classList.toggle('on', x === b)); };
        s.append(b);
      });
      card.append(s); setTimeout(() => i.focus());
    },
    () => card.append(el('h2', 0, 'What are you making?'), el('p', 0, 'Pick all that apply. This sets up your starting categories.'), chips(USES.map(u => u[0]), d.uses, true)),
    () => card.append(el('h2', 0, 'Your workflow'), el('p', 0, 'Experience with 3D'), chips(['Beginner', 'Intermediate', 'Pro'], d.level, false),
      el('p', 0, 'Main 3D tool'), chips(['Blender', 'Maya', '3ds Max', 'ZBrush', 'Other'], d.tool, false),
      el('p', 0, 'Game engine'), chips(['Unity', 'Unreal', 'Godot', 'Other / none'], d.engine, false)),
    () => {
      const sug = [...new Set(USES.filter(u => d.uses.includes(u[0])).flatMap(u => u[1]))];
      if (!d.cats.length) d.cats = sug.length ? [...sug] : ['Props', 'Characters', 'Environment'];
      card.append(el('h2', 0, 'Starting categories'), el('p', 0, "Untick anything you don't want — you can add more later. Untick all for a blank slate."));
      card.append(chips([...new Set([...sug, ...d.cats])], d.cats, true));
    },
  ];
  const draw = () => {
    card.textContent = '';
    card.append(el('div', 'step', `Step ${step + 1} of ${steps.length}`));
    steps[step]();
    const dots = el('div', 'dots'); steps.forEach((_, i) => dots.append(el('i', i <= step ? 'on' : '')));
    const back = el('button', 'ghost', step ? 'Back' : (first ? '' : 'Cancel'));
    const nx = el('button', 'pri', step === steps.length - 1 ? 'Open my board' : 'Next');
    if (step === steps.length - 1) nx.dataset.sfx = 'kiln';
    back.style.visibility = step || !first ? 'visible' : 'hidden';
    back.onclick = () => {
      if (step) { step--; draw(); return; }
      g.remove(); applyAccent(PR && PR.accent); picker();
    };
    nx.onclick = async () => {
      if (step === 0 && !d.name.trim()) { sfx.play('error'); card.querySelector('input').focus(); return; }
      if (step < steps.length - 1) { step++; draw(); return; }
      const p = { id: uid() + uid(), name: d.name.trim(), accent: d.color, uses: d.uses, level: d.level[0], tool: d.tool[0], engine: d.engine[0], created: Date.now() };
      PROFILES.profiles.push(p); PR = p; await saveP();
      S = { categories: d.cats.map(n => ({ name: n, assets: [] })) }; await flush();
      g.remove(); enter(p);
    };
    const n = el('div', 'nav2'); n.append(back, dots, nx); card.append(n);
  };
  draw();
}
async function enter(p, chosen) {
  PR = p; PROFILES.last = p.id; saveP();
  if (p.accent === OLD_DEFAULT_ACCENT) p.accent = null;          // AssetForge's orange default becomes Kiln's theme accent
  S = await api(base() + '/state');
  if (!S.categories) S = { categories: [] };
  (S.fonts = S.fonts || []).forEach(regFont);
  applyAccent(p.accent);
  $('#app').classList.remove('hidden');
  view = 'board'; statusFilter = 'all'; $('#q').value = '';
  setSaveState('');
  drawRail(); render();
  api(base() + '/files').then(fs => { libCount = fs.length; drawRail(); }).catch(() => {});
  if (chosen) sfx.play('kiln');
}

/* ---------- theme & accent ---------- */
const THEMES = [['system', 'System'], ['light', 'Light'], ['dark', 'Dark']];
const getTheme = () => { try { return localStorage.getItem('kiln.theme') || 'system'; } catch (e) { return 'system'; } };
const isDark = () => document.documentElement.dataset.theme === 'dark' || (!document.documentElement.dataset.theme && matchMedia('(prefers-color-scheme: dark)').matches);
function setTheme(t) {
  try { localStorage.setItem('kiln.theme', t); } catch (e) { /* theme just won't be remembered */ }
  if (t === 'system') delete document.documentElement.dataset.theme; else document.documentElement.dataset.theme = t;
  drawThemeBtn(); applyAccent(PR && PR.accent);
}
function drawThemeBtn() {
  const b = $('#themebtn'); b.textContent = ''; b.append(icon(isDark() ? 'moon' : 'sun', 18));
  b.title = `Theme: ${THEMES.find(x => x[0] === getTheme())[1]} (click to switch)`; b.setAttribute('aria-label', b.title);
}
$('#themebtn').onclick = () => { const order = ['system', 'light', 'dark']; setTheme(order[(order.indexOf(getTheme()) + 1) % 3]); sfx.play('toggle'); };
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => { drawThemeBtn(); applyAccent(PR && PR.accent); });
drawThemeBtn();
// Picks white or dark text for the accent colour, whichever reads better.
const inkFor = hex => { const n = parseInt(hex.slice(1), 16), [r, g, b] = [n >> 16, (n >> 8) & 255, n & 255].map(v => { v /= 255; return v <= .03928 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4; }); return .2126 * r + .7152 * g + .0722 * b > .4 ? '#14112e' : '#ffffff'; };
function applyAccent(col) {
  const st = document.documentElement.style;
  if (!col) { ['--a', '--on-a', '--a-soft', '--shaped'].forEach(v => st.removeProperty(v)); return; }
  st.setProperty('--a', col); st.setProperty('--shaped', col); st.setProperty('--on-a', inkFor(col));
  st.setProperty('--a-soft', `color-mix(in srgb, ${col} ${isDark() ? 22 : 12}%, ${isDark() ? '#1b1b2c' : '#ffffff'})`);
}

/* ---------- rail ---------- */
const VIEWS = [['board', 'Board', 'board'], ['library', 'Library', 'library'], ['completed', 'Fired', 'flame']];
function drawRail() {
  const n = $('#nav'); n.textContent = '';
  const all = allAssets(), done = all.filter(a => a.status === 'done').length;
  const counts = { board: all.length, library: libCount, completed: done };
  VIEWS.forEach(([k, label, ic]) => {
    const b = el('button', 'rbtn' + (view === k ? ' on' : ''));
    b.append(icon(ic, 20), el('span', 0, label));
    if (counts[k]) b.append(el('span', 'badge', String(counts[k])));
    b.title = { board: 'Asset board', library: 'Library: models, textures, fonts and more', completed: 'Completed assets' }[k];
    b.onclick = () => { if (view === k) return; view = k; statusFilter = 'all'; render(); };
    n.append(b);
  });
  const t = $('#railtools'); t.textContent = '';
  const ai = el('button', 'rbtn' + (assistant.isOpen() ? ' on' : '')); ai.append(icon('sparkle', 20), el('span', 0, 'Assistant'));
  ai.title = 'Assistant (Ctrl+J)'; ai.onclick = () => assistant.toggle();
  const st = el('button', 'rbtn'); st.append(icon('settings', 20), el('span', 0, 'Settings')); st.onclick = () => settings();
  const me = el('button', 'avbtn'); me.append(avatar(PR)); me.title = `${PR.name}: switch profile`;
  me.onclick = e => menu(e.clientX + 8, e.clientY - 120, [
    [PR.name, () => settings(), 'switch', 'cur'], '-',
    ['Switch profile', async () => { await flush(); assistant.close(); $('#app').classList.add('hidden'); picker(); }, 'switch'],
    ['Settings', () => settings(), 'settings'],
  ]);
  t.append(ai, st, me);
}

/* ---------- settings ---------- */
function settings(section) {
  modal((c, cl) => {
    c.append(el('h3', 0, 'Settings'));
    const setRow = (ic, title, sub, ...ctl) => { const s = el('div', 'set'), t = el('div', 't'); t.append(el('b', 0, title)); if (sub) t.append(el('small', 0, sub)); s.append(icon(ic, 20), t, ...ctl); c.append(s); return s; };
    // appearance
    const seg = el('div', 'seg3');
    THEMES.forEach(([k, l]) => { const b = el('button', getTheme() === k ? 'on' : '', l); b.dataset.sfx = 'toggle'; b.onclick = () => { setTheme(k); seg.querySelectorAll('button').forEach(x => x.classList.toggle('on', x === b)); }; seg.append(b); });
    setRow('sun', 'Theme', 'System follows your COSMIC light/dark setting', seg);
    const sws = el('div', 'sws');
    [null, ...ACC].forEach(col => {
      const b = el('i', (col || null) === (PR.accent || null) ? 'on' : ''); b.style.background = col || 'linear-gradient(135deg,#5b4cf5,#8b7fff)'; b.style.width = b.style.height = '24px';
      b.title = col ? col : 'Kiln indigo (default)';
      b.onclick = () => { sfx.play('tick'); PR.accent = col; applyAccent(col); sws.querySelectorAll('i').forEach(x => x.classList.toggle('on', x === b)); saveP(); drawRail(); };
      sws.append(b);
    });
    setRow('palette', 'Accent colour', null, sws);
    // assistant
    const aiRow = setRow('sparkle', 'Assistant', assistant.summary());
    const aiBtn = ibtn('settings', 'Set up'); aiBtn.onclick = () => { cl(); assistant.settings(); }; aiRow.append(aiBtn);
    // sound
    const snd = PROFILES.sound = Object.assign({ on: true, vol: 0.6 }, PROFILES.sound);
    const sw = el('label', 'switch'), cb = el('input'); cb.type = 'checkbox'; cb.checked = snd.on; cb.setAttribute('aria-label', 'Sound effects'); sw.append(cb, el('i'));
    cb.onchange = () => { snd.on = cb.checked; sfx.configure(snd); saveP(); sfx.play('toggle'); };
    setRow(snd.on ? 'volume' : 'mute', 'Sound effects', 'Clicks, pops and a kiln chime when an asset is complete', sw);
    const vol = el('input'); vol.type = 'range'; vol.min = 0; vol.max = 1; vol.step = .05; vol.value = snd.vol; vol.setAttribute('aria-label', 'Volume');
    vol.oninput = () => { snd.vol = +vol.value; sfx.configure(snd); };
    vol.onchange = () => { saveP(); sfx.play('complete'); };
    setRow('volume', 'Volume', 'Release the slider to hear it', vol);
    // profile & data
    const rn = ibtn('pencil', 'Rename'); const pr = setRow('switch', 'Profile name', PR.name, rn);
    rn.onclick = async () => { const n = await ask('Profile name', PR.name); if (n) { PR.name = n; pr.querySelector('small').textContent = n; saveP(); drawRail(); } };
    setRow('library', 'Your data', `Saved on this computer in ~/Kiln/profiles/${PR.id}`);
    const un = ibtn('trash', 'Uninstall', { cls: 'danger' }); un.onclick = () => { cl(); uninstallApp(); };
    setRow('trash', 'Uninstall Kiln', 'Remove the app, its icon and shortcuts from this computer', un);
    const done = el('button', 'pri', 'Done'); done.onclick = cl;
    const r = row(done); r.classList.add('end'); c.append(r);
  }, true);
}

function uninstallApp() {
  modal((c, cl) => {
    c.append(el('h3', 0, 'Uninstall Kiln?'),
      el('p', 0, ANDROID ? 'Android will ask you to confirm removing the app.' : 'This removes the app, its Applications icon and Desktop shortcut. If Kiln was installed from the .deb, your computer will ask for your password.'));
    const lab = el('label', 'set'), cb = el('input'); cb.type = 'checkbox';
    const t = el('div', 't'); t.append(el('b', 0, 'Also delete all my boards, files and assistant settings'), el('small', 0, ANDROID ? 'Everything Kiln stored on this tablet, for every profile. This cannot be undone.' : 'Everything in ~/Kiln, for every profile. This cannot be undone.'));
    lab.append(cb, t); lab.style.cursor = 'pointer'; c.append(lab);
    const ok = ibtn('trash', 'Uninstall', { cls: 'danger', sfx: 'delete' }), no = el('button', 'ghost', 'Cancel');
    const r = row(no, ok); r.classList.add('end'); c.append(r);
    no.onclick = cl;
    ok.onclick = async () => {
      ok.disabled = no.disabled = true; ok.lastChild.textContent = 'Uninstalling…';
      if (!cb.checked) await flush();
      let res = { ok: false, message: 'Kiln stopped responding.' };
      try { res = await api('/api/uninstall' + (cb.checked ? '?data=1' : ''), { method: 'POST' }); } catch (e) { /* server may already be gone */ }
      cl();
      if (!res.ok) { sfx.play('error'); return toast(res.message || 'Uninstall was cancelled.', 'err'); }
      clearTimeout(saveT); PR = null;                            // nothing more to save
      document.body.textContent = '';
      const g = gate(), b = el('div', 'empty');
      const big = el('div', 'big'); big.append(icon('flame', 30));
      if (ANDROID) { b.append(big, el('h3', 0, 'Confirm in Android’s prompt'), el('p', 0, cb.checked ? 'Your boards were deleted. Tap OK in the prompt to remove the app.' : 'Tap OK in the prompt to remove the app.')); g.append(b); return; }
      b.append(big, el('h3', 0, 'Kiln has been uninstalled'),
        el('p', 0, cb.checked ? 'The app and all your boards were removed.' : 'Your boards are still in ~/Kiln if you install it again.'),
        el('p', 0, 'You can close this window.'));
      g.append(b);
      setTimeout(() => window.close(), 2500);
    };
  });
}

/* ---------- overview: heat bar + status filter ---------- */
function counts() {
  const c = { planned: 0, modeled: 0, done: 0 };
  allAssets().forEach(a => c[a.status] = (c[a.status] || 0) + 1);
  return c;
}
function drawOverview(c) {
  const o = $('#overview'); o.textContent = '';
  const total = allAssets().length;
  if (view !== 'board' || !total) return;
  const card = el('div', 'heatcard'), sum = el('div', 'sum');
  sum.append(el('b', 0, `${c.done} of ${total} complete`), el('span', 0, c.done === total ? 'Everything is fired.' : `${c.modeled} modeled, ${c.planned} planned`));
  const bar = el('div', 'heatbar'); bar.setAttribute('role', 'img'); bar.setAttribute('aria-label', `${c.planned} planned, ${c.modeled} modeled, ${c.done} complete`);
  ST.forEach(s => { const i = el('i', s); i.style.width = (c[s] / total * 100) + '%'; bar.append(i); });
  sum.append(bar);
  const f = el('div', 'filters');
  [['all', 'All', total], ...ST.map(k => [k, LB[k], c[k]])].forEach(([k, label, n]) => {
    const b = el('button', `fchip${statusFilter === k ? ' on' : ''}`); b.dataset.sfx = 'toggle';
    if (k !== 'all') b.append(el('i', 'dot ' + k));
    b.append(el('span', 0, label), el('b', 0, String(n)));
    b.setAttribute('aria-pressed', statusFilter === k);
    b.onclick = () => { statusFilter = statusFilter === k ? 'all' : k; render(); };
    f.append(b);
  });
  card.append(sum, f); o.append(card);
}

/* ---------- board views ---------- */
const mk = n => ({ id: uid(), name: n, status: 'planned', sketch: '', final: '', model: '', notes: '', poly: '', blender: '', board: [] });
function setStatus(a, s) {
  if (a.status === s) return;
  a.status = s; save(); sfx.play(STATUS_SFX[s]);
  if (s === 'done') toast(`${a.name} is complete`, 'ok');
}
function card(a, cat) {
  const e = el('article', 'card' + (a.status === 'done' ? ' fired' : '')), pv = el('div', 'pv'), src = a.final || a.sketch;
  e.tabIndex = 0; e.setAttribute('aria-label', `${a.name}, ${LB[a.status]}`);
  e.onkeydown = ev => { if (ev.key === 'Enter') openWS(a, cat); };
  if (src) { const i = new Image(); i.loading = 'lazy'; i.decoding = 'async'; i.src = src; i.alt = ''; pv.append(i); }
  else { const ph = el('div', 'ph'); ph.append(icon('sketch', 26), el('span', 0, 'No sketch yet')); pv.append(ph); }
  const pill = el('span', 'pill'); pill.append(el('i', 'dot ' + a.status), el('span', 0, LB[a.status])); pv.append(pill);
  const inf = el('div', 'info'), meta = el('div', 'meta');
  const items = (a.board || []).length;
  meta.append(el('span', 0, cat));
  if (items) { const m = el('span'); m.append(icon('layers', 12), String(items)); meta.append(m); }
  if (a.model) { const m = el('span'); m.append(icon('cube', 12), '3D'); meta.append(m); }
  inf.append(el('h3', 0, a.name), meta); e.append(pv, inf);
  e.onclick = () => openWS(a, cat);
  e.oncontextmenu = ev => {
    ev.preventDefault();
    const c = S.categories.find(x => x.assets.includes(a));
    menu(ev.clientX, ev.clientY, [
      ['Open', () => openWS(a, cat), 'arrow'],
      ['Rename', async () => { const n = await ask('Rename asset', a.name); if (n) { a.name = n; save(); render(); } }, 'pencil'],
      '-',
      ...ST.map(s => [LB[s], () => { setStatus(a, s); render(); }, s === 'done' ? 'check' : s === 'modeled' ? 'cube' : 'sketch', a.status === s ? 'cur' : '']),
      '-',
      ['Move to category…', async () => {
        const to = await choose(`Move "${a.name}" to`, S.categories.map(x => x.name), c && c.name);
        const dest = S.categories.find(x => x.name === to);
        if (dest && dest !== c) { c.assets = c.assets.filter(x => x !== a); dest.assets.push(a); save(); render(); sfx.play('place'); }
      }, 'folderMove'],
      ['Duplicate', () => { const d = JSON.parse(JSON.stringify(a)); d.id = uid(); d.name = a.name + ' copy'; c.assets.splice(c.assets.indexOf(a) + 1, 0, d); save(); render(); sfx.play('pop'); }, 'copy'],
      ['Delete', () => delAsset(a), 'trash', 'danger'],
    ]);
  };
  return e;
}
async function delAsset(a, after) {
  if (!await sure(`Delete "${a.name}"?`, { detail: 'Its board, notes and 3D scene settings will be removed. Library files stay.' })) return false;
  const c = S.categories.find(c => c.assets.includes(a));
  if (c) c.assets = c.assets.filter(x => x !== a);
  save(); after && after(); render();
  return true;
}
function render() {
  const id = ++renderId, v = $('#view');
  v.textContent = '';
  const counts_ = counts(); drawRail(); drawOverview(counts_);
  $('#title').textContent = { board: 'Board', library: 'Library', completed: 'Fired' }[view];
  $('#addcat').classList.toggle('hidden', view === 'library');
  $('#q').placeholder = view === 'library' ? 'Search files' : 'Search assets';
  const all = allAssets(), plural = (n, w, ws) => `${n} ${n === 1 ? w : ws}`;
  $('#sub').textContent = view === 'library' ? 'Models, textures, images, fonts and builds for this profile'
    : view === 'completed' ? `${plural(counts_.done, 'asset', 'assets')} out of the kiln`
    : `${plural(all.length, 'asset', 'assets')} in ${plural(S.categories.length, 'category', 'categories')}`;
  if (view === 'library') return lib(v, id);

  if (!S.categories.length) return v.append(emptyState('board', `Welcome, ${PR.name}!`, 'Your board is empty. Start with a category like Props or Characters.', 'New category', addCategory));
  if (view === 'completed' && !counts_.done) return v.append(emptyState('flame', 'Nothing fired yet', 'Assets you mark Complete show up here.'));

  const want = view === 'completed' ? 'done' : statusFilter;
  S.categories.forEach((c, ci) => {
    const items = c.assets.filter(a => want === 'all' || a.status === want);
    if (want !== 'all' && !items.length) return;
    const sec = el('section', 'cat'), h = el('div', 'ch'), g = el('div', 'grid');
    const done = c.assets.filter(a => a.status === 'done').length;
    const tog = ibtn('chevron', '', { cls: 'tog', title: 'Collapse', sfx: 'tick', size: 16 });
    const h2 = el('h2', 0, c.name), line = el('div', 'line');
    const cnt = el('span', 'count', `${done}/${c.assets.length}`); cnt.title = `${done} of ${c.assets.length} complete`;
    h.append(tog, h2, cnt, line);
    const toggle = () => { collapsed.has(c.name) ? collapsed.delete(c.name) : collapsed.add(c.name); sec.classList.toggle('closed'); g.classList.toggle('hidden'); };
    tog.onclick = toggle; h2.onclick = () => { sfx.play('tick'); toggle(); };
    if (view === 'board') {
      const ad = ibtn('plus', 'Asset', { cls: 'ghost', title: `New asset in ${c.name}` }); ad.onclick = () => addAsset(c);
      const mo = ibtn('more', '', { cls: 'ghost icon', title: 'Category options' });
      mo.onclick = e => menu(e.clientX, e.clientY, [
        ['New asset', () => addAsset(c), 'plus'],
        ['Rename category', async () => {
          const n = await ask('Rename category', c.name);
          if (!n || n === c.name) return;
          if (S.categories.some(x => x !== c && x.name.toLowerCase() === n.toLowerCase())) { sfx.play('error'); return toast(`There's already a "${n}" category`, 'err'); }
          if (collapsed.delete(c.name)) collapsed.add(n);
          c.name = n; save(); render();
        }, 'pencil'],
        ['Move up', () => { if (ci > 0) { [S.categories[ci - 1], S.categories[ci]] = [c, S.categories[ci - 1]]; save(); render(); } }, 'up'],
        '-',
        ['Delete category', async () => {
          if (await sure(`Delete "${c.name}"?`, { detail: `This removes ${c.assets.length} asset${c.assets.length === 1 ? '' : 's'} and their boards. Library files stay.` })) {
            S.categories = S.categories.filter(x => x !== c); save(); render();
          }
        }, 'trash', 'danger'],
      ]);
      h.append(ad, mo);
    }
    if (collapsed.has(c.name)) { sec.classList.add('closed'); g.classList.add('hidden'); }
    items.forEach(a => { const k = card(a, c.name); k.dataset.n = (a.name + ' ' + c.name).toLowerCase(); g.append(k); });
    if (view === 'board' && want === 'all') {
      if (!c.assets.length) {                                   // compact row for an empty category
        const ec = el('button', 'emptycat'); ec.append(icon('plus', 18), el('span', 0, `Add the first asset to ${c.name}`)); ec.onclick = () => addAsset(c);
        g.classList.add('solo'); g.append(ec);
      } else {
        const ac = el('button', 'addcard'); ac.append(icon('plus', 22), el('span', 0, 'New asset')); ac.onclick = () => addAsset(c);
        g.append(ac);
      }
    }
    sec.append(h, g); v.append(sec);
  });
  filter();
}
function emptyState(ic, title, text, action, fn) {
  const e = el('div', 'empty'), b = el('div', 'big'); b.append(icon(ic, 30));
  e.append(b, el('h3', 0, title), el('p', 0, text));
  if (action) { const btn = ibtn('plus', action, { cls: 'pri' }); btn.onclick = fn; e.append(btn); }
  return e;
}
async function addAsset(c) {
  const n = await ask(`New asset in ${c.name}`, '', 'e.g. Rusty lantern');
  if (!n) return;
  c.assets.push(mk(n)); save(); render(); sfx.play('pop');
}
async function addCategory() {
  const n = await ask('New category', '', 'e.g. Weapons');
  if (!n) return;
  if (S.categories.some(c => c.name.toLowerCase() === n.toLowerCase())) { sfx.play('error'); return toast(`There's already a "${n}" category`, 'err'); }
  S.categories.push({ name: n, assets: [] }); save(); render(); sfx.play('pop');
}
function filter() {
  const q = $('#q').value.trim().toLowerCase();
  if (view === 'library') {
    document.querySelectorAll('#view .lib section').forEach(s => {
      let any = false;
      s.querySelectorAll('.tile').forEach(t => { const m = t.dataset.n.includes(q); t.style.display = m ? '' : 'none'; any ||= m; });
      s.style.display = any ? '' : 'none';
    });
    return;
  }
  document.querySelectorAll('#view .cat').forEach(s => {
    let any = false;
    s.querySelectorAll('.card').forEach(k => { const m = k.dataset.n.includes(q); k.style.display = m ? '' : 'none'; any ||= m; });
    const add = s.querySelector('.addcard'); if (add) add.style.display = q ? 'none' : '';
    const g = s.querySelector('.grid');
    if (q) g.classList.toggle('hidden', !any); else g.classList.toggle('hidden', s.classList.contains('closed'));
    s.style.display = any || !q ? '' : 'none';
  });
}
let qt;
$('#q').oninput = () => { clearTimeout(qt); qt = setTimeout(filter, 80); };
$('#searchbox').prepend(icon('search', 16));
const ac = $('#addcat'); ac.append(icon('plus', 16), el('span', 0, 'New category')); ac.onclick = addCategory;
addEventListener('keydown', e => {
  if (document.querySelector('.ws,.vm,.ov,.gate') || typingIn(document.activeElement)) return;
  if ((e.ctrlKey || e.metaKey) && e.key === 'f') { e.preventDefault(); $('#q').focus(); }
});

/* ---------- library ---------- */
async function lib(v, id) {
  const fs = await api(base() + '/files');
  if (id !== renderId) return;                               // a newer render started meanwhile
  libCount = fs.length; drawRail();
  const w = el('div', 'lib'), bar = el('div', 'libbar');
  const sel = el('select'); LIB_DIRS.forEach(d => sel.append(new Option(d[0].toUpperCase() + d.slice(1), d)));
  const inp = el('input'); inp.type = 'file'; inp.multiple = true; inp.hidden = true;
  const ub = ibtn('upload', 'Upload files', { cls: 'pri' }); ub.onclick = () => inp.click();
  const doUpload = async files => {
    if (!files.length) return;
    for (const f of files) await up(f, isModel(f.name) ? 'models' : sel.value);
    sfx.play('upload'); toast(`Uploaded ${files.length} file${files.length === 1 ? '' : 's'}`, 'ok'); render();
  };
  inp.onchange = () => doUpload([...inp.files]);
  bar.append(el('span', 0, 'Upload to'), sel, ub, inp, el('span', 'hint', 'or drop files anywhere here'));
  w.append(bar); v.append(w);
  v.ondragover = e => { if (view !== 'library') return; e.preventDefault(); w.classList.add('drag'); };
  v.ondragleave = e => { if (!v.contains(e.relatedTarget)) w.classList.remove('drag'); };
  v.ondrop = e => { if (view !== 'library') return; e.preventDefault(); w.classList.remove('drag'); doUpload([...e.dataTransfer.files]); };

  for (const k of ['Models', 'Textures', 'Images', 'Fonts', 'Blender', 'Games', 'Other']) {
    const l = fs.filter(f => f.kind === k);
    if (!l.length) continue;
    const sec = el('section'), h = el('h2'); h.append(icon(KIND_ICON[k], 15), k, el('small', 0, `${l.length}`)); sec.append(h);
    const g = el('div', 'grid');
    l.forEach(f => {
      const t = el('article', 'card tile'), pv = el('div', 'pv'), n = f.path.split('/').pop();
      t.dataset.n = n.toLowerCase();
      if (isImg(n)) { const i = new Image(); i.loading = 'lazy'; i.decoding = 'async'; i.src = FU(f.path); i.alt = ''; pv.append(i); }
      else { const kd = el('div', 'ph kind'); kd.append(icon(KIND_ICON[k], 30)); if (k === 'Fonts' && S.fonts.some(q => q.url === FU(f.path))) kd.append(el('span', 0, 'In font list')); pv.append(kd); }
      const acts = el('div', 'acts');
      if (k === 'Images' || k === 'Textures') {
        const to = k === 'Images' ? 'textures' : 'images';
        const mv = ibtn('folderMove', '', { title: `Move to ${to}`, size: 15 });
        mv.onclick = async e => {
          e.stopPropagation();
          const r = await api(`${base()}/move?path=${encodeURIComponent(f.path)}&to=${to}`, { method: 'POST' });
          if (r.url) { S = JSON.parse(JSON.stringify(S).split(FU(f.path)).join(r.url)); save(); sfx.play('place'); render(); }
        };
        acts.append(mv);
      }
      const x = ibtn('trash', '', { cls: 'del', title: 'Delete file', size: 15 });
      x.onclick = async e => {
        e.stopPropagation();
        if (await sure(`Delete ${n}?`, { detail: 'Boards using this file will lose it.' })) {
          await fetch(`${base()}/delete?path=${encodeURIComponent(f.path)}`, { method: 'POST' }); render();
        }
      };
      acts.append(x); pv.append(acts);
      const inf = el('div', 'info'); inf.append(el('h3', 0, n)); t.append(pv, inf); t.title = f.path;
      if (isModel(n)) t.onclick = () => showViewer(FU(f.path), n);
      if (k === 'Fonts') t.onclick = () => {
        const name = n.replace(/\.[^.]+$/, ''), url = FU(f.path);
        if (!S.fonts.some(q => q.url === url)) { S.fonts.push({ name, url }); regFont({ name, url }); save(); sfx.play('chime'); toast(`${name} added to your font list`, 'ok'); render(); }
      };
      g.append(t);
    });
    sec.append(g); w.append(sec);
  }
  if (!fs.length) w.append(emptyState('library', 'Your library is empty', 'Upload textures, models, fonts and game builds above, or drop files onto an asset board.'));
  filter();
}

/* ---------- asset workspace (bounded Milanote-style board) ---------- */
function openWS(a, cat) {
  sfx.play('open');
  const ws = el('div', 'ws'); document.body.append(ws);
  const hd = el('header');
  const back = ibtn('back', '', { cls: 'ghost icon', title: 'Back to board (Esc)' });
  const ttl = el('div', 'ttl'); ttl.append(el('h2', 0, a.name), el('small', 0, cat));
  const hist = el('div', 'grp');
  const bU = ibtn('undo', '', { title: 'Undo (Ctrl+Z)', sfx: 'undo' }), bY = ibtn('redo', '', { title: 'Redo (Ctrl+Y)', sfx: 'redo' }), bF = ibtn('fit', '', { title: 'Fit board to window' });
  hist.append(bU, bY, bF);
  const seg = el('div', 'grp seg');
  const drawSeg = () => seg.querySelectorAll('button').forEach(b => b.classList.toggle('on', b.dataset.s === a.status));
  ST.forEach(s => {
    const b = el('button'); b.dataset.s = s; b.dataset.sfx = 'none';
    b.append(el('i', 'dot ' + s), el('span', 0, LB[s]));
    b.onclick = () => { setStatus(a, s); drawSeg(); };
    seg.append(b);
  });
  drawSeg();
  const done = el('button', 'pri', 'Done'); done.dataset.sfx = 'none';
  const aiB = ibtn('sparkle', 'Assistant', { cls: 'ghost', title: 'Assistant (Ctrl+J)' }); aiB.onclick = () => assistant.toggle();
  hd.append(back, ttl, hist, seg, aiB, done);
  document.body.classList.add('ws-open');

  // text formatting bar
  const fb = el('div', 'fbar off'), fsel = el('select'), fsz = el('input');
  let savedR = null;
  fsz.type = 'number'; fsz.min = 8; fsz.max = 200; fsz.title = 'Font size';
  const applyStyle = (t, it) => { t.style.fontFamily = it.ff || ''; t.style.fontSize = (it.fs || (it.t === 'text' ? 24 : 15)) + 'px'; t.style.textAlign = it.al || 'left'; };
  const fontOpts = () => {
    fsel.textContent = '';
    [...FONTS, ...(S.fonts || []).map(f => [f.name, `"${f.name}",sans-serif`])].forEach(([n, v]) => fsel.append(new Option(n, v)));
    fsel.append(new Option('＋ Add font file…', '__add'));
  };
  fontOpts();
  const curS = () => sel_ && (sel_.it.t === 'note' || sel_.it.t === 'text') ? sel_ : null;
  const syncBar = () => { const s = curS(); fb.classList.toggle('off', !s); if (s) { fsel.value = s.it.ff || FONTS[0][1]; fsz.value = s.it.fs || (s.it.t === 'text' ? 24 : 15); } };
  const styleIt = s => { applyStyle(s.d.querySelector('.rt'), s.it); save(); };
  fsel.onchange = () => {
    const s = curS();
    if (fsel.value === '__add') {
      const f = el('input'); f.type = 'file'; f.accept = '.ttf,.otf,.woff,.woff2';
      f.onchange = async () => {
        const file = f.files[0]; if (!file) return;
        const url = await up(file, 'fonts'), name = file.name.replace(/\.[^.]+$/, '');
        (S.fonts = S.fonts || []).push({ name, url }); regFont({ name, url }); fontOpts();
        if (s) { s.it.ff = `"${name}",sans-serif`; styleIt(s); }
        syncBar(); save(); sfx.play('chime');
      };
      f.click(); syncBar(); return;
    }
    if (s) { snap(); s.it.ff = fsel.value; styleIt(s); }
  };
  fsz.oninput = () => { const s = curS(); if (s && fsz.value) { s.it.fs = Math.max(8, Math.min(200, +fsz.value)); styleIt(s); } };
  const cmd = (c, v) => {
    const s = curS(); if (!s) return;
    const t = s.d.querySelector('.rt');
    document.execCommand('styleWithCSS', false, true);
    if (document.activeElement !== t) {
      t.focus(); const g = getSelection();
      if (savedR && t.contains(savedR.commonAncestorContainer) && !savedR.collapsed) { g.removeAllRanges(); g.addRange(savedR); }
      else document.execCommand('selectAll');
    }
    document.execCommand(c, false, v); s.it.html = t.innerHTML; save();
  };
  const selChange = () => {
    const g = getSelection();
    if (g.rangeCount) { const n = g.anchorNode && (g.anchorNode.nodeType === 1 ? g.anchorNode : g.anchorNode.parentElement); if (n && n.closest && n.closest('.rt')) savedR = g.getRangeAt(0).cloneRange(); }
  };
  document.addEventListener('selectionchange', selChange);
  const fbtn = (ic, title, f) => { const b = ibtn(ic, '', { title, sfx: 'tick', size: 16 }); b.onmousedown = e => e.preventDefault(); b.onclick = f; fb.append(b); return b; };
  const sep = () => fb.append(el('i', 'sep'));
  fb.append(el('span', 'lbl', 'Text'), fsel, fsz); sep();
  fbtn('bold', 'Bold (Ctrl+B)', () => cmd('bold')); fbtn('italic', 'Italic (Ctrl+I)', () => cmd('italic'));
  fbtn('underline', 'Underline (Ctrl+U)', () => cmd('underline')); fbtn('strike', 'Strikethrough', () => cmd('strikeThrough')); sep();
  const tci = el('input'), hci = el('input'); tci.type = hci.type = 'color'; tci.value = '#d13438'; hci.value = '#ffe066';
  tci.title = 'Text colour'; hci.title = 'Highlight'; tci.oninput = () => cmd('foreColor', tci.value); hci.oninput = () => cmd('hiliteColor', hci.value);
  fb.append(tci, hci); sep();
  [['alignLeft', 'left'], ['alignCenter', 'center'], ['alignRight', 'right']].forEach(([ic, al]) => fbtn(ic, 'Align ' + al, () => { const s = curS(); if (s) { snap(); s.it.al = al; styleIt(s); } }));
  fbtn('list', 'Bullet list', () => cmd('insertUnorderedList')); fbtn('eraser', 'Clear formatting', () => cmd('removeFormat'));

  const body = el('div', 'wb'), tools = el('div', 'lt'), cv = el('div', 'cv'), inner = el('div', 'cvi'), zl = el('div', 'zl'), side = el('aside');
  cv.append(inner, zl); body.append(tools, cv, side); ws.append(hd, fb, body);
  if (PR.level === 'Beginner') { const tip = el('div', 'tip'); tip.append(icon('info', 14), 'Scroll to zoom, drag empty space to pan, right-click a card for more'); cv.append(tip); }

  // view transform
  const V = a.view || (a.view = { x: 0, y: 0, z: .8 });
  const apply = () => {
    const vw = cv.clientWidth, vh = cv.clientHeight; if (!vw) return;
    V.z = Math.min(3, Math.max(Math.max(.15, Math.min(vw / W, vh / H)), V.z));
    const w = W * V.z, h = H * V.z;
    V.x = w <= vw ? (vw - w) / 2 : Math.min(0, Math.max(vw - w, V.x));
    V.y = h <= vh ? (vh - h) / 2 : Math.min(0, Math.max(vh - h, V.y));
    inner.style.transform = `translate(${V.x}px,${V.y}px) scale(${V.z})`;
    zl.textContent = Math.round(V.z * 100) + '%';
  };
  const ro = new ResizeObserver(apply); ro.observe(cv);
  bF.onclick = () => { V.z = 0; apply(); save(); };
  cv.onwheel = e => {
    e.preventDefault();
    const r = cv.getBoundingClientRect(), mx = e.clientX - r.left, my = e.clientY - r.top, nz = Math.min(3, V.z * (e.deltaY < 0 ? 1.1 : 1 / 1.1));
    V.x = mx - (mx - V.x) * nz / V.z; V.y = my - (my - V.y) * nz / V.z; V.z = nz; apply(); save();
  };
  let sel_ = null, zmax = Math.max(1, ...a.board.map(b => b.z || 1));
  const select = (d, it) => { sel_ && sel_.d.classList.remove('sel'); sel_ = d ? { d, it } : null; d && d.classList.add('sel'); syncBar(); };
  cv.onpointerdown = e => {
    if (e.target !== cv && e.target !== inner) return;
    select(null); document.activeElement.blur(); cv.setPointerCapture(e.pointerId);
    const sx = e.clientX - V.x, sy = e.clientY - V.y;
    cv.onpointermove = m => { V.x = m.clientX - sx; V.y = m.clientY - sy; apply(); };
    cv.onpointerup = () => { cv.onpointermove = cv.onpointerup = null; save(); };
  };

  // undo / redo
  const hist_ = [], fut = [];
  const snap = () => { hist_.push(JSON.stringify(a.board)); if (hist_.length > 60) hist_.shift(); fut.length = 0; };
  const restore = s => { a.board = JSON.parse(s); inner.textContent = ''; select(null); a.board.forEach(put); save(); };
  const undo = () => { if (hist_.length) { fut.push(JSON.stringify(a.board)); restore(hist_.pop()); } };
  const redo = () => { if (fut.length) { hist_.push(JSON.stringify(a.board)); restore(fut.pop()); } };
  bU.onclick = undo; bY.onclick = redo;
  const del = (it, d) => { snap(); a.board = a.board.filter(b => b !== it); d.remove(); if (sel_ && sel_.it === it) select(null); save(); sfx.play('delete'); };
  const dup = it => {
    snap(); const c = JSON.parse(JSON.stringify(it));
    c.id = uid(); c.x = Math.min(W - c.w, it.x + 24); c.y = Math.min(H - 60, it.y + 24); c.z = ++zmax; c.lock = false;
    a.board.push(c); put(c); save(); sfx.play('pop');
  };
  const closeWS = () => {
    document.removeEventListener('keydown', key); document.removeEventListener('selectionchange', selChange);
    ro.disconnect(); ws.remove(); document.body.classList.remove('ws-open'); assistant.detach(); flush(); render(); sfx.play('close');
  };
  const key = e => {
    if (document.querySelector('.vm,.ov,.cm')) return;
    const typing = typingIn(document.activeElement), ctrl = e.ctrlKey || e.metaKey, k = e.key.toLowerCase();
    if (e.key === 'Escape') { if (typing) document.activeElement.blur(); else if (sel_) select(null); else closeWS(); return; }
    if (typing) return;                                       // never hijack keys while editing text
    if (ctrl && k === 'z') { e.preventDefault(); e.shiftKey ? (redo(), sfx.play('redo')) : (undo(), sfx.play('undo')); }
    else if (ctrl && k === 'y') { e.preventDefault(); redo(); sfx.play('redo'); }
    else if (ctrl && k === 'd' && sel_) { e.preventDefault(); dup(sel_.it); }
    else if ((e.key === 'Delete' || e.key === 'Backspace') && sel_ && !sel_.it.lock) del(sel_.it, sel_.d);
  };
  document.addEventListener('keydown', key);

  // adding items
  let cn = 0;
  const C = () => { const o = (cn++ % 8) * 28; return { x: Math.round((cv.clientWidth / 2 - V.x) / V.z - 110 + o), y: Math.round((cv.clientHeight / 2 - V.y) / V.z - 70 + o) }; };
  const add = it => {
    snap(); it.id = uid(); it.z = it.t === 'col' ? 0 : ++zmax;
    it.x = Math.max(0, Math.min(W - it.w, it.x)); it.y = Math.max(0, Math.min(H - 60, it.y));
    a.board.push(it); save(); const d = put(it); sfx.play('pop');
    if ((it.t === 'note' || it.t === 'text') && !it.html) d.querySelector('.rt').focus();
    return it;
  };
  // Lets the assistant read this asset and drop its replies onto the canvas.
  assistant.attach({
    asset: a, category: cat,
    addNote: html => add({ t: 'note', ...C(), w: 280, h: 220, c: COLORS[3], html: clean(html) }),
    addTodo: (title, items) => add({ t: 'todo', ...C(), w: 280, h: Math.min(420, 70 + items.length * 30), title, items: items.map(t => ({ t, d: false })) }),
  });
  const fin = el('input'); fin.type = 'file'; fin.multiple = true;
  const fi = (dir, acc) => { fin.accept = acc; fin.onchange = () => { addFiles([...fin.files], dir, C()); fin.value = ''; }; fin.click(); };
  async function addFiles(files, dir, at) {
    let n = 0;
    for (const f of files) {
      const m = isModel(f.name);
      if (!m && !isImg(f.name)) { toast(`${f.name} isn't an image or .glb/.gltf model`, 'err'); continue; }
      const src = await up(f, m ? 'models' : dir);
      if (m && !a.model) { a.model = src; side.refresh(); }
      add({ t: m ? 'model' : dir === 'textures' ? 'tex' : 'img', x: at.x + n * 24, y: at.y + n * 24, w: m ? 180 : 240, src }); n++;
    }
    if (n) sfx.play('upload');
  }
  const TOOLS = [
    ['note', 'Note', () => add({ t: 'note', ...C(), w: 200, h: 150, c: COLORS[0], text: '' })],
    ['text', 'Text', () => add({ t: 'text', ...C(), w: 260, h: 60, fs: 24, text: '' })],
    ['todo', 'To-do', () => add({ t: 'todo', ...C(), w: 230, h: 140, title: 'To-do', items: [{ t: '', d: false }] })],
    ['link', 'Link', () => add({ t: 'link', ...C(), w: 240, h: 100, url: '' })],
    ['palette', 'Colour', () => add({ t: 'color', ...C(), w: 120, h: 120, c: ACC[0] })],
    ['frame', 'Frame', () => add({ t: 'col', ...C(), w: 420, h: 300, title: 'Frame' })],
    '-',
    ['image', 'Image', () => fi('images', 'image/*')],
    ['texture', 'Texture', () => fi('textures', 'image/*')],
    ['cube', 'Model', () => fi('models', '.glb,.gltf')],
  ];
  TOOLS.forEach(t => {
    if (t === '-') return tools.append(el('hr'));
    const [ic, l, f] = t, b = el('button'); b.append(icon(ic, 20), l); b.title = 'Add ' + l.toLowerCase(); b.dataset.sfx = 'none'; b.onclick = f; tools.append(b);
  });
  cv.ondragover = e => { e.preventDefault(); cv.classList.add('drop'); };
  cv.ondragleave = () => cv.classList.remove('drop');
  cv.ondrop = e => {
    e.preventDefault(); cv.classList.remove('drop');
    const r = cv.getBoundingClientRect();
    addFiles([...e.dataTransfer.files], 'images', { x: (e.clientX - r.left - V.x) / V.z, y: (e.clientY - r.top - V.y) / V.z });
  };

  function put(it) {
    const d = el('div', 'it ' + it.t), gr = el('div', 'gr'), x = el('span', 'x');
    x.append(icon('close', 13)); x.title = 'Remove';
    d.style.cssText = `left:${it.x}px;top:${it.y}px;width:${it.w}px;z-index:${it.z || 0}` + (it.h ? `;height:${it.h}px` : '');
    if (it.lock) d.classList.add('lk');
    if (it.t === 'tex') {
      const b = el('span'); b.append(icon('cube', 12), 'Apply to model');
      b.onclick = () => {
        a.v3d = a.v3d || {};
        a.v3d.tx = Object.assign({ src: '', rep: 1, rot: 0, tint: '#ffffff', rough: .6, metal: 0 }, a.v3d.tx, { src: it.src });
        save(); b.lastChild.textContent = 'Applied'; sfx.play('chime');
        if (a.model) showViewer(a.model, a.name, a); else toast('Texture saved — add a model to see it in 3D');
      };
      gr.append(b);
    }
    if (it.lock) { const lk = el('span', 'lkd'); lk.append(icon('lock', 12)); lk.title = 'Locked'; gr.append(lk); }
    gr.append(x); d.append(gr);
    if (it.t === 'note' || it.t === 'text') {
      const t = el('div', 'rt'); t.contentEditable = 'true'; t.spellcheck = false;
      t.innerHTML = clean(it.html || esc(it.text || ''));
      if (it.t === 'note') d.style.background = it.c || COLORS[0];
      applyStyle(t, it); t.dataset.ph = it.t === 'text' ? 'Type something…' : 'Write a note…';
      t.onfocus = () => { snap(); select(d, it); };
      t.oninput = () => { it.html = t.innerHTML; save(); };
      t.onpaste = e => { e.preventDefault(); document.execCommand('insertText', false, e.clipboardData.getData('text/plain')); };
      t.ondrop = e => e.preventDefault();
      d.append(t);
    } else if (it.t === 'todo') {
      const box = el('div', 'tb2'), ti = el('input'); ti.value = it.title || ''; ti.style.fontWeight = '700';
      ti.oninput = () => { it.title = ti.value; save(); };
      const rows = () => {
        box.querySelectorAll('.tr').forEach(r => r.remove());
        it.items.forEach((r, i) => {
          const w = el('div', 'tr'), cb = el('input'), tx = el('input');
          cb.type = 'checkbox'; cb.checked = r.d; tx.type = 'text'; tx.value = r.t; tx.className = r.d ? 'dn' : ''; tx.placeholder = 'Task';
          cb.onchange = () => { r.d = cb.checked; tx.classList.toggle('dn', r.d); save(); sfx.play(r.d ? 'key' : 'tick'); };
          tx.oninput = () => { r.t = tx.value; save(); };
          tx.onkeydown = e => {
            const inputs = () => box.querySelectorAll('.tr input[type=text]');
            if (e.key === 'Enter') { it.items.splice(i + 1, 0, { t: '', d: false }); rows(); inputs()[i + 1].focus(); save(); }
            else if (e.key === 'Backspace' && !tx.value && it.items.length > 1) { e.preventDefault(); it.items.splice(i, 1); rows(); inputs()[Math.max(0, i - 1)].focus(); save(); }
          };
          w.append(cb, tx); box.insertBefore(w, addb);
        });
      };
      const addb = el('button', 0, '+ Add task'); addb.onclick = () => { it.items.push({ t: '', d: false }); rows(); save(); };
      box.append(ti, addb); rows(); d.append(box);
    } else if (it.t === 'link') {
      const b = el('div', 'lb'), i = el('input'), l = el('a');
      l.append(icon('external', 13), 'Open link'); i.placeholder = 'https://…'; i.value = it.url || ''; l.target = '_blank'; l.rel = 'noopener';
      const set = () => { l.href = /^https?:\/\//.test(i.value) ? i.value : 'https://' + i.value; l.style.display = i.value ? '' : 'none'; };
      set(); i.oninput = () => { it.url = i.value; set(); save(); };
      b.append(i, l); d.append(b);
    } else if (it.t === 'color') {
      const s = el('div', 'swc', it.c), i = el('input'); i.type = 'color'; i.value = it.c; s.style.background = it.c;
      i.oninput = () => { it.c = i.value; s.style.background = i.value; s.textContent = i.value; save(); };
      d.append(s, i);
    } else if (it.t === 'col') {
      const i = el('input'); i.value = it.title || ''; i.oninput = () => { it.title = i.value; save(); };
      gr.style.display = 'none'; d.prepend(i); d.append(gr);
    } else if (it.t === 'model') {
      const m = el('div', 'md'); m.append(icon('cube', 28), it.src.split('/').pop()); m.onclick = () => showViewer(it.src, a.name, a); d.append(m);
    } else {
      const i = new Image(); i.src = it.src; i.loading = 'lazy'; i.draggable = false; i.alt = ''; d.append(i);
    }
    const rz = el('i', 'rz'); d.append(rz);
    rz.onpointerdown = e => {
      e.stopPropagation(); rz.setPointerCapture(e.pointerId); snap();
      const sx = e.clientX, sy = e.clientY, w = it.w, h = it.h || d.offsetHeight, fl = it.h !== undefined;
      rz.onpointermove = m => {
        it.w = Math.round(Math.min(W - it.x, Math.max(60, w + (m.clientX - sx) / V.z))); d.style.width = it.w + 'px';
        if (fl) { it.h = Math.round(Math.min(H - it.y, Math.max(40, h + (m.clientY - sy) / V.z))); d.style.height = it.h + 'px'; }
      };
      rz.onpointerup = () => { rz.onpointermove = rz.onpointerup = null; save(); };
    };
    x.onclick = () => del(it, d);
    d.onpointerdown = e => {
      e.stopPropagation(); select(d, it);
      if (it.lock || e.button !== 0 || /TEXTAREA|SPAN|INPUT|BUTTON|^A$|svg|path/.test(e.target.tagName) || e.target.closest('.rt,.gr span')) return;
      d.setPointerCapture(e.pointerId); snap();
      const sx = e.clientX / V.z - it.x, sy = e.clientY / V.z - it.y, ox = it.x, oy = it.y;
      d.onpointermove = m => {
        it.x = Math.round(Math.max(0, Math.min(W - it.w, m.clientX / V.z - sx)));
        it.y = Math.round(Math.max(0, Math.min(H - (it.h || d.offsetHeight), m.clientY / V.z - sy)));
        d.style.left = it.x + 'px'; d.style.top = it.y + 'px';
      };
      d.onpointerup = () => { d.onpointermove = d.onpointerup = null; save(); if (Math.hypot(it.x - ox, it.y - oy) > 6) sfx.play('place'); };
    };
    d.oncontextmenu = e => {
      e.preventDefault(); select(d, it);
      const m = [
        ['Duplicate', () => dup(it), 'copy'],
        [it.lock ? 'Unlock position' : 'Lock position', () => { snap(); it.lock = !it.lock; save(); sfx.play('toggle'); d.replaceWith(put(it)); }, it.lock ? 'unlock' : 'lock'],
        ['Bring to front', () => { snap(); it.z = ++zmax; d.style.zIndex = it.z; save(); }, 'front'],
        ['Send to back', () => { snap(); it.z = 0; d.style.zIndex = 0; save(); }, 'backward'],
      ];
      if (it.t === 'note') m.push('-', { colors: c => { snap(); it.c = c; d.style.background = c; save(); } });
      m.push('-', ['Remove', () => del(it, d), 'trash', 'danger']);
      menu(e.clientX, e.clientY, m);
    };
    inner.append(d);
    return d;
  }
  a.board.forEach(put);

  // side panel
  const pane = (title, ic) => { const p = el('div', 'pane'), h = el('h4'); h.append(icon(ic, 13), title); p.append(h); side.append(p); return p; };
  const slot = (par, label, k) => {
    const s = el('div', 'slot'), f = el('input'); f.type = 'file'; f.accept = 'image/*'; f.hidden = true;
    const draw = () => {
      s.textContent = ''; s.classList.toggle('has', !!a[k]);
      if (a[k]) { const i = new Image(); i.src = a[k]; i.alt = ''; s.append(i, el('span', 'cap', label)); }
      else s.append(icon('upload', 20), el('span', 0, label));
    };
    draw();
    f.onchange = async () => { if (!f.files[0]) return; a[k] = await up(f.files[0], 'images'); save(); draw(); sfx.play('upload'); };
    s.onclick = () => f.click();
    s.title = a[k] ? `Replace ${label.toLowerCase()}` : `Upload ${label.toLowerCase()}`;
    par.append(s, f);
  };
  side.refresh = () => {
    side.textContent = '';
    const p1 = pane('Artwork', 'image'), slots = el('div', 'slots'); slot(slots, 'Sketch / concept', 'sketch'); slot(slots, 'Final render', 'final'); p1.append(slots);
    const p2 = pane('3D model', 'cube');
    const mf = el('input'); mf.type = 'file'; mf.accept = '.glb,.gltf'; mf.hidden = true;
    const mb = ibtn(a.model ? 'eye' : 'upload', a.model ? 'Open 3D viewer' : 'Upload .glb / .gltf', { cls: a.model ? 'pri' : '' });
    mb.onclick = () => a.model ? showViewer(a.model, a.name, a) : mf.click();
    mf.onchange = async () => { if (!mf.files[0]) return; a.model = await up(mf.files[0], 'models'); save(); side.refresh(); sfx.play('upload'); };
    p2.append(mb, mf);
    if (a.model) { const rm = ibtn('upload', 'Replace model'); rm.onclick = () => mf.click(); p2.append(rm); }
    const sm = ibtn('mesh', a.v3d && a.v3d.props && a.v3d.props.length ? 'Open modeling scene' : 'Start modeling (blank scene)');
    sm.onclick = () => showViewer(null, a.name, a); p2.append(sm);
    const p3 = pane('Details', 'info');
    const field = (l, k, ph) => { const lab = el('label'); lab.style.cssText = 'display:flex;flex-direction:column;gap:4px;font-size:12px;color:var(--m)'; const i = el('input'); i.value = a[k] || ''; i.placeholder = ph; i.oninput = () => { a[k] = i.value; save(); }; lab.append(l, i); p3.append(lab); };
    field('Polycount', 'poly', 'e.g. 4,200 tris'); field('Blender file path', 'blender', '/path/to/model.blend');
    const p4 = pane('Notes', 'note');
    const t = el('textarea'); t.rows = 6; t.value = a.notes || ''; t.placeholder = 'Reference links, feedback, ideas…'; t.oninput = () => { a.notes = t.value; save(); }; p4.append(t);
    const dl = ibtn('trash', 'Delete asset', { cls: 'danger' });
    dl.onclick = () => delAsset(a, () => { document.removeEventListener('keydown', key); document.removeEventListener('selectionchange', selChange); ro.disconnect(); ws.remove(); document.body.classList.remove('ws-open'); assistant.detach(); });
    side.append(dl);
  };
  side.refresh();
  back.onclick = closeWS; done.onclick = closeWS;
}

/* ---------- 3D viewer ---------- */
function showViewer(url, title, asset) {
  sfx.play('open');
  const vm = el('div', 'vm'), h = el('header'), host = el('div', 'vh');
  const back = ibtn('back', '', { cls: 'ghost icon', title: 'Close viewer' });
  const ttl = el('div', 'ttl'); ttl.append(el('h2', 0, title), el('small', 0, url ? '3D viewer' : 'Modeling scene'));
  const c = el('button', 'pri', 'Done'); c.dataset.sfx = 'none';
  h.append(back, ttl, c); vm.append(h, host); document.body.append(vm);
  let dispose = () => {}, closed = false;
  const close = () => { closed = true; dispose(); vm.remove(); sfx.play('close'); };
  c.onclick = close; back.onclick = close;
  import('/viewer.js')
    .then(m => m.mountViewer(host, url, {
      data: asset ? (asset.v3d || (asset.v3d = {})) : {}, save, up, files: () => api(base() + '/files'), fu: FU,
      key: asset ? asset.id : 'lib', uses: PR.uses || [], setModel: u => { if (asset) { asset.model = u; save(); } },
    }))
    .then(d => { closed ? d() : dispose = d; })
    .catch(e => { host.append(el('div', 'nfo', "Couldn't start the 3D viewer: " + e.message + ' — run ./setup.sh once.')); sfx.play('error'); });
}

/* Android Back button: close the top-most menu, dialog, 3D viewer or board, in that order. Returns false when there's nothing left to close. */
window.kilnBack = () => {
  const esc = () => dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  const pop = document.querySelector('.bl-pop'); if (pop) { pop.remove(); return true; }
  if (document.querySelector('.ov')) { esc(); return true; }
  const vm = document.querySelector('.vm'); if (vm) { const v = vm.querySelector('.vh')?.__viewer; if (v && v.modal()) v.endModal(false); else vm.querySelector('header .pri').click(); return true; }
  if (document.querySelector('.ws')) { document.activeElement?.blur?.(); document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); return true; }
  return false;
};

/* ---------- boot ---------- */
async function boot() {
  PROFILES = await api('/api/profiles');
  if (!PROFILES.profiles) PROFILES = { profiles: [], last: '' };
  sfx.configure(Object.assign({ on: true, vol: 0.6 }, PROFILES.sound));
  const p = PROFILES.profiles;
  if (!p.length) onboard();
  else if (p.length === 1) enter(p[0]);
  else picker();
}
Promise.all([boot(), new Promise(r => setTimeout(r, 1000))]).then(() => {
  const s = $('#splash'); s.classList.add('out'); setTimeout(() => s.remove(), 600);
});
