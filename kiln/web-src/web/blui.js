// Blender-style widgets for the 3D viewer: drag-to-edit number fields, collapsible panels,
// header menus and the toolbar's tool icons (original drawings, 24×24 grid).
const el = (t, c, x) => { const e = document.createElement(t); if (c) e.className = c; if (x != null) e.textContent = x; return e; };

const TOOL = {
  selectBox: '<path d="M4 4h3M10 4h3M16 4h4v3M20 10v3M4 7v3M4 13v3M4 19h3M10 19h2" stroke-dasharray="0"/><path d="M13 12l7 2.6-3 1.1 2.8 3.4-1.6 1.3-2.8-3.4-1.4 2.9z" fill="currentColor" stroke="none"/>',
  cursor: '<circle cx="12" cy="12" r="6.5" stroke-dasharray="3.4 2.4"/><path d="M12 2.5v5M12 16.5v5M2.5 12h5M16.5 12h5"/>',
  move: '<path d="M12 3v18M3 12h18"/><path d="M9.5 5.5L12 3l2.5 2.5M9.5 18.5L12 21l2.5-2.5M5.5 9.5L3 12l2.5 2.5M18.5 9.5L21 12l-2.5 2.5"/>',
  rotate: '<path d="M19.5 9.5A8 8 0 0 0 5 7.5"/><path d="M4.5 14.5A8 8 0 0 0 19 16.5"/><path d="M5 3.5v4h4M19 20.5v-4h-4"/><circle cx="12" cy="12" r="2.2" fill="currentColor" stroke="none"/>',
  scale: '<rect x="3.5" y="10.5" width="10" height="10" rx="1" fill="currentColor" fill-opacity=".25"/><path d="M10.5 3.5h10v10"/><path d="M20.5 3.5l-7 7M15 3.5h5.5V9"/>',
  transform: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7v10M7 12h10"/><path d="M10.3 8.7L12 7l1.7 1.7M8.7 13.7L7 12l1.7-1.7"/><rect x="14.8" y="14.8" width="3" height="3" fill="currentColor" stroke="none"/>',
  annotate: '<path d="M3.5 18.5c3-4 5 1.5 8-1.5s1-6 4.5-6.5"/><path d="M14 13.5l6.5-6.5a1.6 1.6 0 0 0-2.3-2.3L11.7 11.2l-.6 2.9z"/>',
  measure: '<path d="M4 20V5.5L18.5 20z"/><path d="M4 9h2M4 12.5h2M4 16h2M8 20v-2M11.5 20v-2"/><path d="M7.5 16.5V12l4.5 4.5z" fill="currentColor" fill-opacity=".3" stroke="none"/>',
  addCube: '<path d="M12 8.5l7 3.5v7.5l-7 3.5-7-3.5V12z"/><path d="M5 12l7 3.5 7-3.5M12 15.5V23"/><path d="M5 2v5M2.5 4.5h5"/>',
  extrude: '<path d="M4 15l8 4 8-4-8-4z" fill="currentColor" fill-opacity=".25"/><path d="M4 15l8 4 8-4M12 11V3M9 6l3-3 3 3"/>',
  inset: '<rect x="3.5" y="3.5" width="17" height="17" rx="1"/><rect x="8" y="8" width="8" height="8" rx=".5" fill="currentColor" fill-opacity=".3"/><path d="M3.5 3.5L8 8M20.5 3.5L16 8M3.5 20.5L8 16M20.5 20.5L16 16"/>',
  draw: '<path d="M4 20l1-4.5L15.5 5a2.1 2.1 0 0 1 3 3L8 18.5z"/><path d="M13.5 7l3 3"/>',
  erase: '<path d="M8.5 20H20M4.5 15.5l9.8-9.8a2 2 0 0 1 2.8 0l2.2 2.2a2 2 0 0 1 0 2.8L11 19H8z"/><path d="M9.5 10.5l5 5"/>',
  // header: select modes and shading
  vert: '<path d="M6 8l6-4 6 4v8l-6 4-6-4z" stroke-opacity=".45"/><circle cx="12" cy="12" r="3" fill="currentColor" stroke="none"/>',
  edge: '<path d="M6 8l6-4 6 4v8l-6 4-6-4z" stroke-opacity=".45"/><path d="M6 8l6 4 6-4M12 12v8" stroke-opacity=".45"/><path d="M12 12v8" stroke-width="3"/>',
  face: '<path d="M6 8l6-4 6 4v8l-6 4-6-4z" stroke-opacity=".45"/><path d="M12 12l6-4v8l-6 4z" fill="currentColor" stroke="none"/>',
  shWire: '<circle cx="12" cy="12" r="8"/><ellipse cx="12" cy="12" rx="3.5" ry="8"/><path d="M4 12h16"/>',
  shSolid: '<circle cx="12" cy="12" r="8" fill="currentColor" fill-opacity=".85"/>',
  shMaterial: '<circle cx="12" cy="12" r="8"/><path d="M12 4a8 8 0 0 1 0 16z" fill="currentColor"/><circle cx="9" cy="9" r="1.6" fill="currentColor" stroke="none"/>',
  shRender: '<circle cx="12" cy="12" r="8" fill="currentColor" fill-opacity=".35"/><path d="M7.5 9.5a5 5 0 0 1 4-3.2"/>',
  grid: '<path d="M4 9h16M4 15h16M9 4v16M15 4v16"/>',
  sidebar: '<rect x="3.5" y="4.5" width="17" height="15" rx="1.5"/><path d="M14.5 4.5v15"/>',
  zoom: '<circle cx="10.5" cy="10.5" r="6"/><path d="M15 15l5.5 5.5M8 10.5h5M10.5 8v5"/>',
  pan: '<path d="M8 13V6.5a1.5 1.5 0 0 1 3 0V11V5a1.5 1.5 0 0 1 3 0v6V6.5a1.5 1.5 0 0 1 3 0V14c0 4-2.5 7-6 7-2.5 0-4-1-5.5-3.5L3.8 14a1.5 1.5 0 0 1 2.4-1.8z"/>',
  camera: '<path d="M4 8.5h11v9H4zM15 11.5l5-3v9l-5-3"/>',
  timeline: '<rect x="3.5" y="13.5" width="17" height="6" rx="1"/><path d="M8 13.5v6M12 9.5l2 2-2 2-2-2zM6 4.5h12"/>',
  confirm: '<path d="M4.5 12.5l4.5 4.5L19.5 6.5"/>',
  cancel: '<path d="M6 6l12 12M18 6L6 18"/>',
};
export function ticon(name, size = 18) {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('width', size); s.setAttribute('height', size);
  s.setAttribute('fill', 'none'); s.setAttribute('stroke', 'currentColor'); s.setAttribute('stroke-width', '1.6');
  s.setAttribute('stroke-linecap', 'round'); s.setAttribute('stroke-linejoin', 'round'); s.setAttribute('aria-hidden', 'true');
  s.innerHTML = TOOL[name] || ''; return s;
}
export const TOOL_ICONS = Object.keys(TOOL);

/* An icon-only button with a Blender-style tooltip ("Move  G"). */
export function tbtn(icon, label, key, onClick) {
  const b = el('button', 'bl-ib'); b.type = 'button'; b.append(ticon(icon));
  b.title = key ? `${label}  (${key})` : label; b.setAttribute('aria-label', label);
  if (onClick) b.onclick = onClick; return b;
}

/* Number field: drag left/right to change, click to type, arrows to nudge. */
export function num(label, get, set, o = {}) {
  const step = o.step ?? 0.1, dec = o.dec ?? 3, unit = o.unit || '', fmt = v => (+v).toFixed(dec).replace(/\.?0+$/, m => m.includes('.') ? '' : m) + unit;
  const box = el('div', 'bl-num' + (o.cls ? ' ' + o.cls : '')), l = el('span', 'k', label), v = el('span', 'v');
  const dn = el('button', 'nudge', '‹'), up = el('button', 'nudge', '›'); dn.type = up.type = 'button';
  dn.tabIndex = up.tabIndex = -1; dn.setAttribute('aria-label', label + ' down'); up.setAttribute('aria-label', label + ' up');
  box.append(dn, l, v, up); box.tabIndex = 0; box.setAttribute('role', 'spinbutton'); box.setAttribute('aria-label', label);
  const clamp = x => Math.min(o.max ?? Infinity, Math.max(o.min ?? -Infinity, x));
  const show = () => { const x = get(); v.textContent = fmt(x); box.setAttribute('aria-valuenow', x); };
  const put = x => { if (!Number.isFinite(x)) return; set(clamp(x)); show(); };
  dn.onclick = e => { e.stopPropagation(); o.begin && o.begin(); put(get() - step); o.end && o.end(); };
  up.onclick = e => { e.stopPropagation(); o.begin && o.begin(); put(get() + step); o.end && o.end(); };
  let drag = null;
  box.addEventListener('pointerdown', e => {
    if (e.target === dn || e.target === up || box.classList.contains('typing')) return;
    drag = { x: e.clientX, v0: get(), moved: false }; box.setPointerCapture(e.pointerId); e.preventDefault();
  });
  box.addEventListener('pointermove', e => {
    if (!drag) return; const dx = e.clientX - drag.x;
    if (!drag.moved && Math.abs(dx) > 3) { drag.moved = true; o.begin && o.begin(); box.classList.add('drag'); }
    if (drag.moved) put(drag.v0 + Math.round(dx / 4) * step * (e.shiftKey ? 0.1 : 1) * (e.ctrlKey ? 10 : 1));
  });
  box.addEventListener('pointerup', () => {
    if (!drag) return; const moved = drag.moved; drag = null; box.classList.remove('drag');
    if (moved) { o.end && o.end(); return; }
    type();
  });
  function type() {
    box.classList.add('typing'); const i = el('input'); i.value = (+get()).toFixed(dec).replace(/\.?0+$/, ''); i.inputMode = 'decimal';
    v.replaceWith(i); i.focus(); i.select(); let done = false;
    const finish = ok => { if (done) return; done = true; if (ok) { const x = evalNum(i.value); if (x != null) { o.begin && o.begin(); put(x); o.end && o.end(); } }
      i.replaceWith(v); box.classList.remove('typing'); show(); };
    i.onkeydown = e => { e.stopPropagation(); if (e.key === 'Enter') finish(true); else if (e.key === 'Escape') finish(false); };
    i.onblur = () => finish(true);
  }
  box.onkeydown = e => { if (e.key === 'Enter') { e.preventDefault(); type(); } else if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') { e.preventDefault(); dn.click(); } else if (e.key === 'ArrowRight' || e.key === 'ArrowUp') { e.preventDefault(); up.click(); } };
  show(); box.update = show; return box;
}
// "1.5", "-2", "90", "1/2", "2*3" — simple arithmetic only, like Blender's number fields
export function evalNum(s) {
  s = String(s).trim().replace(/,/g, '.').replace(/[a-z°]+$/i, '');
  if (!/^[-+*/(). 0-9]+$/.test(s) || !s) return null;
  try { const x = Function('"use strict";return (' + s + ')')(); return Number.isFinite(x) ? x : null; } catch { return null; }
}

/* A column of three fields (X / Y / Z) under one heading. */
export function vec3(title, get, set, o = {}) {
  const w = el('div', 'bl-vec'); if (title) w.append(el('div', 'bl-vh', title));
  const axes = ['X', 'Y', 'Z'], fields = axes.map((a, i) => num(a, () => get()[i], x => { const v = get().slice(); v[i] = x; set(v); }, { ...o, cls: 'ax' + i }));
  const col = el('div', 'bl-col'); col.append(...fields); w.append(col);
  w.update = () => fields.forEach(f => f.update()); return w;
}

/* Collapsible panel with a ▾ header, like Blender's sidebar panels. */
export function panel(title, open = true) {
  const p = el('section', 'bl-panel' + (open ? '' : ' closed')), h = el('button', 'bl-ph'); h.type = 'button';
  h.append(el('span', 'tri'), el('span', 0, title)); h.setAttribute('aria-expanded', open);
  const body = el('div', 'bl-pb'); h.onclick = () => { p.classList.toggle('closed'); h.setAttribute('aria-expanded', !p.classList.contains('closed')); };
  p.append(h, body); return { el: p, body };
}

/* Header dropdown menu: items are [label, shortcut, fn] or '-' for a separator. */
let openMenu = null;
export function menu(label, items) {
  const b = el('button', 'bl-menu', label); b.type = 'button'; b.setAttribute('aria-haspopup', 'menu');
  b.onclick = e => {
    e.stopPropagation(); const was = openMenu && openMenu.owner === b; closeMenus(); if (was) return;
    const m = el('div', 'bl-pop'); m.setAttribute('role', 'menu'); m.owner = b;
    (typeof items === 'function' ? items() : items).forEach(it => {
      if (it === '-') return m.append(el('hr'));
      const [t, k, fn, dis] = it, r = el('button', 'bl-mi'); r.type = 'button'; r.setAttribute('role', 'menuitem');
      r.append(el('span', 0, t), el('kbd', 0, k || '')); r.disabled = !!dis; r.onclick = ev => { ev.stopPropagation(); closeMenus(); fn(); }; m.append(r);
    });
    const host = b.closest('.vh') || document.body, hb = host.getBoundingClientRect(), bb = b.getBoundingClientRect();
    m.style.left = (bb.left - hb.left) + 'px'; m.style.top = (bb.bottom - hb.top + 2) + 'px'; host.append(m); openMenu = m;
    const first = m.querySelector('button:not([disabled])'); first && first.focus();
    m.onkeydown = ev => { const bs = [...m.querySelectorAll('button:not([disabled])')], i = bs.indexOf(document.activeElement);
      if (ev.key === 'ArrowDown') { ev.preventDefault(); bs[(i + 1) % bs.length].focus(); } else if (ev.key === 'ArrowUp') { ev.preventDefault(); bs[(i - 1 + bs.length) % bs.length].focus(); }
      else if (ev.key === 'Escape') { ev.stopPropagation(); closeMenus(); b.focus(); } };
  };
  return b;
}
export function closeMenus() { if (openMenu) { openMenu.remove(); openMenu = null; } }
document.addEventListener('pointerdown', e => { if (openMenu && !openMenu.contains(e.target)) closeMenus(); });

export function check(label, get, set) {
  const l = el('label', 'bl-check'), i = el('input'); i.type = 'checkbox'; i.checked = !!get();
  i.onchange = () => set(i.checked); l.append(i, el('span', 0, label)); l.update = () => i.checked = !!get(); return l;
}
export function choice(label, opts, get, set) {
  const l = el('label', 'bl-field'), s = el('select'); opts.forEach(([v, t]) => s.append(new Option(t, v))); s.value = get();
  s.onchange = () => set(s.value); l.append(el('span', 'k', label), s); l.update = () => s.value = get(); l.select = s; return l;
}
export function colour(label, get, set, begin) {
  const l = el('label', 'bl-field'), i = el('input'); i.type = 'color'; i.value = get();
  if (begin) i.onclick = begin; i.oninput = () => set(i.value); l.append(el('span', 'k', label), i); l.update = () => i.value = get(); return l;
}
export function button(label, fn, cls) { const b = el('button', 'bl-btn' + (cls ? ' ' + cls : ''), label); b.type = 'button'; b.onclick = fn; return b; }
