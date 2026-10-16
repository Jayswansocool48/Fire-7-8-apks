// Kiln icon set — original line icons drawn for this app (24×24 grid, 1.75 stroke).
// Usage: icon('board') returns an <svg> element; iconHTML('board') returns markup.
const P = {
  // navigation
  board: '<rect x="3" y="3" width="7.5" height="9" rx="1.5"/><rect x="13.5" y="3" width="7.5" height="5" rx="1.5"/><rect x="3" y="15" width="7.5" height="6" rx="1.5"/><rect x="13.5" y="11" width="7.5" height="10" rx="1.5"/>',
  library: '<path d="M3 7.5V18a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V9.5a2 2 0 0 0-2-2h-7L10 5H5a2 2 0 0 0-2 2.5z"/><path d="M3 11h18"/>',
  trophy: '<path d="M8 4h8v5a4 4 0 0 1-8 0z"/><path d="M8 6H5v1.5A3.5 3.5 0 0 0 8.5 11M16 6h3v1.5A3.5 3.5 0 0 1 15.5 11"/><path d="M12 13v4M8.5 20h7M10 17h4v3h-4z"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="M12 2.5v2.2M12 19.3v2.2M4.6 4.6l1.6 1.6M17.8 17.8l1.6 1.6M2.5 12h2.2M19.3 12h2.2M4.6 19.4l1.6-1.6M17.8 6.2l1.6-1.6"/><circle cx="12" cy="12" r="7"/>',
  switch: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20a6.5 6.5 0 0 1 13 0"/><path d="M17 4.5l3 3-3 3M20 7.5h-5.5"/>',
  // generic actions
  plus: '<path d="M12 5v14M5 12h14"/>',
  search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="M15.5 15.5L21 21"/>',
  more: '<circle cx="5" cy="12" r="1.3" fill="currentColor"/><circle cx="12" cy="12" r="1.3" fill="currentColor"/><circle cx="19" cy="12" r="1.3" fill="currentColor"/>',
  close: '<path d="M6 6l12 12M18 6L6 18"/>',
  check: '<path d="M4.5 12.5l4.5 4.5L19.5 6.5"/>',
  undo: '<path d="M9 14L4 9l5-5"/><path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11"/>',
  redo: '<path d="M15 14l5-5-5-5"/><path d="M20 9H9.5a5.5 5.5 0 0 0 0 11H13"/>',
  fit: '<path d="M4 9V5a1 1 0 0 1 1-1h4M15 4h4a1 1 0 0 1 1 1v4M20 15v4a1 1 0 0 1-1 1h-4M9 20H5a1 1 0 0 1-1-1v-4"/><rect x="8.5" y="8.5" width="7" height="7" rx="1"/>',
  trash: '<path d="M4 7h16M9.5 7V4.5h5V7"/><path d="M6 7l1 13h10l1-13"/><path d="M10 11v5M14 11v5"/>',
  pencil: '<path d="M4 20l1-4.5L15.5 5a2.1 2.1 0 0 1 3 3L8 18.5z"/><path d="M13.5 7l3 3"/>',
  upload: '<path d="M12 15V4M7.5 8.5L12 4l4.5 4.5"/><path d="M4 15v3.5A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5V15"/>',
  download: '<path d="M12 4v11M7.5 10.5L12 15l4.5-4.5"/><path d="M4 15v3.5A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5V15"/>',
  up: '<path d="M12 19V5M6 11l6-6 6 6"/>',
  chevron: '<path d="M6 9l6 6 6-6"/>',
  folderMove: '<path d="M3 7.5V18a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V9.5a2 2 0 0 0-2-2h-7L10 5H5a2 2 0 0 0-2 2.5z"/><path d="M10 14h6M13.5 11.5L16 14l-2.5 2.5"/>',
  info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5.5M12 7.8v.2"/>',
  arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
  back: '<path d="M19 12H5M11 6l-6 6 6 6"/>',
  external: '<path d="M14 4h6v6M20 4l-9 9"/><path d="M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/>',
  copy: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5.5A1.5 1.5 0 0 0 14.5 4h-9A1.5 1.5 0 0 0 4 5.5v9A1.5 1.5 0 0 0 5.5 16H8"/>',
  lock: '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
  unlock: '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 7.5-1.9"/>',
  front: '<rect x="8" y="8" width="12" height="12" rx="2" fill="currentColor" fill-opacity=".25"/><path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"/>',
  backward: '<rect x="4" y="4" width="12" height="12" rx="2" fill="currentColor" fill-opacity=".25"/><path d="M8 16v3a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1V9a1 1 0 0 0-1-1h-3"/>',
  volume: '<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z"/><path d="M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11"/>',
  mute: '<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z"/><path d="M16 9.5l5 5M21 9.5l-5 5"/>',
  sparkle: '<path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z"/><path d="M18.5 16.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z"/>',
  flame: '<path d="M12 21c-3.9 0-6.5-2.6-6.5-6.2 0-3.4 2.6-5.3 3.6-8.3.6 1.7 1.6 2.7 2.7 3.2.3-2.6 1.6-4.9 3.7-6.7-.2 3 1.1 4.6 2.2 6.1 1 1.4 1.8 3 1.8 5.4 0 3.8-2.8 6.5-7.5 6.5z"/><path d="M12 21c-1.6 0-2.8-1.2-2.8-2.8 0-1.7 1.4-2.6 2-4.2 1.4 1.2 3.6 2.4 3.6 4.4 0 1.5-1.2 2.6-2.8 2.6z"/>',
  anvil: '<path d="M7 6h14v3.5h-3a3 3 0 0 0-2.5 3h-5A3 3 0 0 0 8 9.5H7z"/><path d="M7 6C4.5 6 3 7 2.5 8c1.2.9 2.8 1.5 4.5 1.5"/><path d="M10.5 12.5V15h5v-2.5M7.5 19.5l3-4.5h5l3 4.5z"/>',
  // board items
  note: '<path d="M5 4h14a1 1 0 0 1 1 1v10l-5 5H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z"/><path d="M20 15h-4a1 1 0 0 0-1 1v4"/><path d="M8 9h8M8 12.5h5"/>',
  text: '<path d="M5 6.5V5h14v1.5M12 5v14M9 19h6"/>',
  todo: '<rect x="3.5" y="4" width="6" height="6" rx="1.2"/><path d="M5 7l1.2 1.2L8.5 6M13 7h7.5"/><rect x="3.5" y="14" width="6" height="6" rx="1.2"/><path d="M13 17h7.5"/>',
  link: '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1"/><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>',
  palette: '<path d="M12 3.5a8.5 8.5 0 1 0 0 17c1.3 0 1.9-.9 1.9-1.8 0-1.4-1.3-1.7-1.3-3 0-1 .8-1.7 1.8-1.7h2.1A4 4 0 0 0 20.5 10C20 6.2 16.4 3.5 12 3.5z"/><circle cx="7.5" cy="11" r="1.2" fill="currentColor"/><circle cx="10" cy="7.3" r="1.2" fill="currentColor"/><circle cx="14.5" cy="7.3" r="1.2" fill="currentColor"/>',
  frame: '<path d="M7 3v18M17 3v18M3 7h18M3 17h18"/>',
  image: '<rect x="3.5" y="4.5" width="17" height="15" rx="2"/><circle cx="9" cy="10" r="1.8"/><path d="M20.5 16l-5-5-8.5 8.5"/>',
  texture: '<rect x="3.5" y="3.5" width="17" height="17" rx="2"/><path d="M3.5 9.2h17M3.5 14.8h17M9.2 3.5v17M14.8 3.5v17"/><path d="M3.5 3.5h5.7v5.7H3.5zM14.8 3.5h5.7v5.7h-5.7zM9.2 9.2h5.6v5.6H9.2zM3.5 14.8h5.7v5.7H3.5zM14.8 14.8h5.7v5.7h-5.7z" fill="currentColor" fill-opacity=".28" stroke="none"/>',
  cube: '<path d="M12 2.8l8 4.6v9.2l-8 4.6-8-4.6V7.4z"/><path d="M4 7.4l8 4.6 8-4.6M12 12v9.2"/>',
  font: '<path d="M3.5 19L8.5 5h1l5 14M5.3 14h7.4"/><path d="M20.5 19v-5.5a2.5 2.5 0 0 0-4.6-1.4M20.5 15.2c-3.2-.3-4.8.6-4.8 2.1 0 1 .8 1.7 1.9 1.7 1.6 0 2.9-1.3 2.9-3.8z"/>',
  blender: '<circle cx="14" cy="13.5" r="6"/><circle cx="14" cy="13.5" r="2.2"/><path d="M3 9.5h8M6 5.5l6.5 4.2"/>',
  gamepad: '<path d="M7 7.5h10a4.5 4.5 0 0 1 4.4 5.5l-.8 3.6a2.2 2.2 0 0 1-3.8 1L14.5 15h-5l-2.3 2.6a2.2 2.2 0 0 1-3.8-1L2.6 13A4.5 4.5 0 0 1 7 7.5z"/><path d="M7.5 10v3.5M5.8 11.8h3.5"/><circle cx="15.5" cy="11" r=".9" fill="currentColor"/><circle cx="17.8" cy="13" r=".9" fill="currentColor"/>',
  file: '<path d="M6 3h8l4.5 4.5V20a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z"/><path d="M14 3v4.5h4.5"/>',
  sketch: '<path d="M3.5 17c2.2-3.6 4-5.4 5.4-5.4 2.2 0 1 4.4 3.2 4.4 1.8 0 3-4.3 5-4.3 1.2 0 2 .8 3.4 2.3"/><path d="M15 4.5l3 3L10.5 15 7 16l1-3.5z"/>',
  eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>',
  // text formatting
  bold: '<path d="M7 4.5h6a3.75 3.75 0 0 1 0 7.5H7zM7 12h7a3.75 3.75 0 0 1 0 7.5H7z"/>',
  italic: '<path d="M10 4.5h8M6 19.5h8M14.5 4.5l-5 15"/>',
  underline: '<path d="M7 4v7a5 5 0 0 0 10 0V4M5 20.5h14"/>',
  strike: '<path d="M16.5 7.2C15.9 5.5 14.2 4.5 12 4.5c-2.7 0-4.5 1.4-4.5 3.4 0 1.5 1 2.5 3 3.1M4 12h16M8 16.5c.5 1.9 2.2 3 4.3 3 2.7 0 4.6-1.4 4.6-3.5 0-.8-.3-1.5-.9-2"/>',
  alignLeft: '<path d="M4 6h16M4 10h10M4 14h16M4 18h10"/>',
  alignCenter: '<path d="M4 6h16M7 10h10M4 14h16M7 18h10"/>',
  alignRight: '<path d="M4 6h16M10 10h10M4 14h16M10 18h10"/>',
  list: '<circle cx="5" cy="6.5" r="1.2" fill="currentColor"/><circle cx="5" cy="12" r="1.2" fill="currentColor"/><circle cx="5" cy="17.5" r="1.2" fill="currentColor"/><path d="M9.5 6.5H20M9.5 12H20M9.5 17.5H20"/>',
  eraser: '<path d="M8.5 20H20M4.6 14.4l9.2-9.2a2 2 0 0 1 2.8 0l3.2 3.2a2 2 0 0 1 0 2.8L12.4 18.6a2 2 0 0 1-1.4.6H8.6a2 2 0 0 1-1.4-.6l-2.6-2.6a1.2 1.2 0 0 1 0-1.6z"/><path d="M9.5 9.5l5 5"/>',
  // 3D viewer tools
  cursor: '<path d="M5 3.5l13.5 6.2-5.7 1.9-2.4 5.9z"/><path d="M12.8 11.6l5.2 5.2"/>',
  move: '<path d="M12 3v18M3 12h18M12 3l-2.5 2.5M12 3l2.5 2.5M12 21l-2.5-2.5M12 21l2.5-2.5M3 12l2.5-2.5M3 12l2.5 2.5M21 12l-2.5-2.5M21 12l-2.5 2.5"/>',
  rotate: '<path d="M20 12a8 8 0 1 1-2.3-5.7"/><path d="M20 4v4.5h-4.5"/>',
  scale: '<path d="M14 4h6v6M20 4l-7 7"/><rect x="4" y="13" width="7" height="7" rx="1"/>',
  brush: '<path d="M20 4c-3.7 2.3-7 5.6-9.4 9l1.4 1.4c3.4-2.4 6.7-5.7 9-9.4z"/><path d="M10.6 13c-2.1-.1-3.6 1.2-3.9 3.4-.2 1.7-1.2 2.8-3.2 3.1 2.6 1.2 6.2.9 7.9-1 1-1.1 1-2.6.6-4z"/>',
  boxSelect: '<path d="M4 7V5a1 1 0 0 1 1-1h2M11 4h2M17 4h2a1 1 0 0 1 1 1v2M20 11v2M20 17v2a1 1 0 0 1-1 1h-2M13 20h-2M7 20H5a1 1 0 0 1-1-1v-2M4 13v-2"/>',
  mesh: '<path d="M12 3l7.8 4.5v9L12 21l-7.8-4.5v-9z"/><path d="M12 3v18M4.2 7.5l15.6 9M19.8 7.5l-15.6 9"/>',
  film: '<rect x="3.5" y="4" width="17" height="16" rx="2"/><path d="M7.5 4v16M16.5 4v16M3.5 8h4M3.5 12h4M3.5 16h4M16.5 8h4M16.5 12h4M16.5 16h4"/>',
  play: '<path d="M7 4.5v15l12-7.5z" fill="currentColor" fill-opacity=".2"/>',
  pause: '<rect x="6" y="4.5" width="4" height="15" rx="1"/><rect x="14" y="4.5" width="4" height="15" rx="1"/>',
  stop: '<rect x="5.5" y="5.5" width="13" height="13" rx="2"/>',
  keyframe: '<path d="M12 3.5l8.5 8.5-8.5 8.5L3.5 12z" fill="currentColor" fill-opacity=".25"/>',
  layers: '<path d="M12 3.5l9 4.8-9 4.8-9-4.8z"/><path d="M3 12.5l9 4.8 9-4.8M3 16.5l9 4.8 9-4.8"/>',
  moon: '<path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4"/>',
  wire: '<path d="M12 3l8 4.5v9L12 21l-8-4.5v-9z" stroke-dasharray="2.2 2.2"/><path d="M12 12V21M4 7.5l8 4.5 8-4.5"/>',
  solid: '<path d="M12 3l8 4.5v9L12 21l-8-4.5v-9z" fill="currentColor" fill-opacity=".35"/>',
  material: '<circle cx="12" cy="12" r="8.5"/><path d="M8 8.5a5 5 0 0 1 4-2" /><circle cx="12" cy="12" r="8.5" fill="currentColor" fill-opacity=".15" stroke="none"/>',
  rendered: '<circle cx="12" cy="12" r="8.5" fill="currentColor" fill-opacity=".35"/><path d="M8 8.5a5 5 0 0 1 4-2"/>',
};

const NS = 'http://www.w3.org/2000/svg';
export const iconHTML = (name, size = 18) =>
  `<svg class="ico" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${P[name] || P.file}</svg>`;

export function icon(name, size = 18) {
  const t = document.createElement('template');
  t.innerHTML = iconHTML(name, size);
  return t.content.firstChild;
}

export const ICON_NAMES = Object.keys(P);
