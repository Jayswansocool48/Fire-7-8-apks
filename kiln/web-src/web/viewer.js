// Kiln 3D viewer, laid out like Blender's 3D Viewport:
// header (mode, select modes, View/Select/Add/Object menus, shading) · toolbar on the left ·
// sidebar on the right (N) with Item / Tool / View tabs · navigation gizmo · Adjust Last Operation panel.
// Grab/rotate/scale, extrude and inset follow the mouse or finger until you confirm, like Blender's modal tools.
import * as T from 'three';
import {GLTFLoader} from 'three/addons/loaders/GLTFLoader.js';
import {OrbitControls} from 'three/addons/controls/OrbitControls.js';
import {TransformControls} from 'three/addons/controls/TransformControls.js';
import {RoomEnvironment} from 'three/addons/environments/RoomEnvironment.js';
import {PRIMS as EP, extrude, extrudeDir, inset, del as delSel, subdivide, merge, flip, faceSel, fnorm, cen, edgesOf, clone, sized} from './edit.js';
import {ticon, tbtn, num, vec3, panel, menu, closeMenus, check, choice, colour, button} from './blui.js';
import {icon} from './icons.js';
import {sfx} from './sound.js';

const el = (t, c, x) => { const e = document.createElement(t); if (c) e.className = c; if (x != null) e.textContent = x; return e; };
const row = (...k) => { const r = el('div', 'row'); r.append(...k); return r; };
const ib = (ic, l) => { const b = el('button'); b.type = 'button'; b.append(icon(ic, 15), el('span', 0, l)); return b; };
const getT = o => ({p: o.position.toArray(), r: [o.rotation.x, o.rotation.y, o.rotation.z], s: o.scale.toArray()});
const setT = (o, t) => { o.position.fromArray(t.p); o.rotation.set(t.r[0], t.r[1], t.r[2]); o.scale.fromArray(t.s); };
const DEG = 180 / Math.PI;
// Blender is Z-up and three.js is Y-up. Everything you see, type or lock to (X/Y/Z) uses Blender's axes:
// Blender (x, y, z) = three.js (x, -z, y).
const AX = {x: new T.Vector3(1, 0, 0), y: new T.Vector3(0, 0, -1), z: new T.Vector3(0, 1, 0)};
const AXI = {x: 0, y: 2, z: 1}; // three.js component for each Blender axis
const toB = v => [v[0], -v[2], v[1]], fromB = v => [v[0], v[2], -v[1]], swapB = v => [v[0], v[2], v[1]];
const BASIS = new T.Matrix4().set(1, 0, 0, 0, 0, 0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1), BASIS_I = BASIS.clone().invert();
// Rotation shown as Blender XYZ Euler (degrees) <-> three.js quaternion
const rotToB = q => { const m = BASIS.clone().multiply(new T.Matrix4().makeRotationFromQuaternion(q)).multiply(BASIS_I), e = new T.Euler().setFromRotationMatrix(m, 'ZYX'); return [e.x, e.y, e.z].map(a => a * 180 / Math.PI); };
const rotFromB = d => { const e = new T.Euler(...d.map(a => a * Math.PI / 180), 'ZYX'), m = BASIS_I.clone().multiply(new T.Matrix4().makeRotationFromEuler(e)).multiply(BASIS); return new T.Quaternion().setFromRotationMatrix(m); };
const AXCOL = {x: '#ff3352', y: '#8bdc00', z: '#2890ff'};
const PRIM_NAMES = {plane: 'Plane', cube: 'Cube', cylinder: 'Cylinder', cone: 'Cone', sphere: 'UV Sphere', torus: 'Torus'};
const segd = (x, y, p, q) => { const dx = q[0] - p[0], dy = q[1] - p[1], l = dx * dx + dy * dy || 1, t = Math.max(0, Math.min(1, ((x - p[0]) * dx + (y - p[1]) * dy) / l)); return Math.hypot(x - (p[0] + t * dx), y - (p[1] + t * dy)); };

function buildGeo(em) {
  const pos = [], nor = [], fmap = [];
  em.f.forEach((f, fi) => { const n = fnorm(em, f); for (let i = 1; i < f.length - 1; i++) { for (const k of [0, i, i + 1]) { const v = em.v[f[k]]; pos.push(v[0], v[1], v[2]); nor.push(n[0], n[1], n[2]); } fmap.push(fi); } });
  const g = new T.BufferGeometry(); g.setAttribute('position', new T.Float32BufferAttribute(pos, 3)); g.setAttribute('normal', new T.Float32BufferAttribute(nor, 3));
  g.userData.fmap = fmap; if (pos.length) { g.computeBoundingSphere(); g.computeBoundingBox(); } return g;
}

/* ---- selection outline (constant pixel width, drawn only outside the object's silhouette) ---- */
const OUTLINE_VS = `attribute vec3 onormal; uniform float width; uniform vec2 res;
void main(){ vec4 c = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  vec3 n = normalize(normalMatrix * onormal); vec4 nc = projectionMatrix * vec4(n, 0.0);
  vec2 d = nc.xy; float l = length(d); if (l > 1e-5) c.xy += d / l * width / res * 2.0 * c.w; gl_Position = c; }`;
const OUTLINE_FS = `uniform vec3 color; void main(){ gl_FragColor = vec4(color, 1.0); }`;
function smoothNormals(geo) {
  // outlines need one normal per position (flat-shaded meshes have split normals, which would crack the outline)
  const p = geo.attributes.position, acc = new Map(), key = i => `${p.getX(i).toFixed(4)},${p.getY(i).toFixed(4)},${p.getZ(i).toFixed(4)}`;
  const idx = geo.index ? geo.index.array : null, tri = idx ? idx.length : p.count, v = [new T.Vector3(), new T.Vector3(), new T.Vector3()], e1 = new T.Vector3(), e2 = new T.Vector3();
  for (let t = 0; t < tri; t += 3) {
    const ii = [0, 1, 2].map(k => idx ? idx[t + k] : t + k); ii.forEach((i, k) => v[k].fromBufferAttribute(p, i));
    const n = e1.subVectors(v[1], v[0]).cross(e2.subVectors(v[2], v[0])).clone();
    ii.forEach(i => { const k = key(i); acc.set(k, (acc.get(k) || new T.Vector3()).add(n)); });
  }
  const out = new Float32Array(p.count * 3);
  for (let i = 0; i < p.count; i++) { const n = (acc.get(key(i)) || new T.Vector3(0, 1, 0)).clone().normalize(); out.set([n.x, n.y, n.z], i * 3); }
  return new T.BufferAttribute(out, 3);
}

export async function mountViewer(host, url, o) {
  const D = o.data, save = () => o.save();
  D.tx = Object.assign({src: '', rep: 1, rot: 0, tint: '#ffffff', rough: .6, metal: 0}, D.tx);
  D.bg = Object.assign({t: 'default', c: '#393939', c2: '#2b2b2b', img: ''}, D.bg); D.props = D.props || [];
  D.model = Object.assign({p: [0, 0, 0], r: [0, 0, 0], s: [1, 1, 1]}, D.model); D.anim = Object.assign({dur: 4, loop: true, keys: {}}, D.anim); D.paint = D.paint || {};
  D.brush = Object.assign({color: '#ff4d4d', size: 60, op: 1, hard: .5, erase: false}, D.brush); if (D.grid === undefined) D.grid = true; D.gridSize = D.gridSize || 24;
  D.annot = D.annot || []; D.rulers = D.rulers || []; D.cursor = D.cursor || [0, 0, 0];
  D.view = Object.assign({lens: 50, sidebar: true, toolbar: true, tab: 'Item', snap: false, space: 'world', annotColor: '#4cc3ff',
    timeline: (o.uses || []).includes('Animation')}, D.view);
  host.classList.add('bl');
  const bgEl = el('div', 'bgl'); host.append(bgEl);
  const status = el('div', 'bl-status');
  let r; const cleanup = [];
  try {
    r = new T.WebGLRenderer({antialias: true, alpha: true, stencil: true, powerPreference: 'high-performance'});
    const touchy = matchMedia('(pointer: coarse)').matches;
    r.setPixelRatio(Math.min(devicePixelRatio, touchy ? 1.5 : 2)); r.shadowMap.enabled = true; r.shadowMap.type = T.PCFSoftShadowMap; r.setClearColor(0, 0);
    const cvs = r.domElement; cvs.className = 'bl-canvas'; host.append(cvs);
    const rb = el('div', 'rb'); rb.style.display = 'none';
    const labels = el('div', 'bl-labels'), cursorEl = el('div', 'bl-cursor');
    host.append(rb, labels, cursorEl, status);

    /* ---- scene ---- */
    const sc = new T.Scene();
    let persp = new T.PerspectiveCamera(39.6, 1, .01, 3000), ortho = new T.OrthographicCamera(-1, 1, 1, -1, -1000, 1000), cam = persp, isOrtho = false, viewName = 'User';
    const ctl = new OrbitControls(cam, cvs);
    ctl.mouseButtons = {LEFT: T.MOUSE.ROTATE, MIDDLE: T.MOUSE.ROTATE, RIGHT: null};
    ctl.touches = {ONE: T.TOUCH.ROTATE, TWO: T.TOUCH.DOLLY_PAN};
    const env = new T.PMREMGenerator(r).fromScene(new RoomEnvironment(), .04).texture;
    const hemi = new T.HemisphereLight(0xffffff, 0x444455, 1.4), sun = new T.DirectionalLight(0xffffff, 2.5);
    const ground = new T.Mesh(new T.PlaneGeometry(1, 1), new T.ShadowMaterial({opacity: .4})); ground.rotation.x = -Math.PI / 2; ground.receiveShadow = true; sc.add(hemi, sun, sun.target, ground);
    let shadeBtns = [], time = 0;
    let dirty = true, alive = true, cur = 'solid', hdri = null, grid = null, axes = null, selObj = null, tool = 'select', mode = 'object', ed = null, busy = false, M = null, lastOp = null;
    const props = [];
    const loader = new GLTFLoader(), gltf = url ? await loader.loadAsync(url) : {scene: new T.Group(), animations: []}, root = gltf.scene, holder = new T.Group(); holder.add(root); sc.add(holder);
    let size = new T.Vector3(2, 2, 2);
    if (url) { const box = new T.Box3().setFromObject(root), ctr = box.getCenter(new T.Vector3()); size = box.getSize(new T.Vector3()); root.position.sub(ctr); root.position.y += size.y / 2; }
    setT(holder, D.model); const rad = Math.max(size.x, size.y, size.z) || 1;
    const modelName = url ? decodeURIComponent(url.split('/').pop().split('?')[0]).replace(/\.(glb|gltf)$/i, '') : 'Model';
    persp.near = rad / 500; persp.far = rad * 800; persp.position.set(rad * 1.9, rad * 1.3, rad * 1.9); ctl.target.set(0, size.y / 2, 0); ctl.update();
    sun.position.set(rad * 2, rad * 3, rad * 1.5); sun.target.position.set(0, size.y / 2, 0); sun.shadow.mapSize.set(2048, 2048);
    Object.assign(sun.shadow.camera, {left: -rad * 3, right: rad * 3, top: rad * 3, bottom: -rad * 3, near: .1, far: rad * 12}); sun.shadow.camera.updateProjectionMatrix();
    const makeGrid = () => {
      if (grid) { sc.remove(grid); grid.geometry.dispose(); sc.remove(axes); }
      const gs = D.gridSize, div = Math.min(400, Math.max(10, Math.round(gs * 2)));
      grid = new T.GridHelper(1, div, 0x545454, 0x464646); grid.scale.setScalar(rad * gs); grid.visible = D.grid; sc.add(grid);
      // Blender's red X and green Y floor lines (Y is up here, so the green line runs along Z)
      const L = rad * gs / 2, ag = new T.BufferGeometry().setFromPoints([new T.Vector3(-L, 0, 0), new T.Vector3(L, 0, 0), new T.Vector3(0, 0, -L), new T.Vector3(0, 0, L)]);
      ag.setAttribute('color', new T.Float32BufferAttribute([.85, .2, .32, .85, .2, .32, .55, .86, 0, .55, .86, 0], 3));
      axes = new T.LineSegments(ag, new T.LineBasicMaterial({vertexColors: true, transparent: true, opacity: .8})); axes.visible = D.grid; axes.position.y = .0005; sc.add(axes);
      ground.scale.setScalar(rad * gs); dirty = true;
    };
    makeGrid();
    const meshes = []; let tris = 0;
    root.traverse(m => { if (m.isMesh) { m.userData.m = m.material; m.castShadow = m.receiveShadow = true; meshes.push(m); tris += (m.geometry.index ? m.geometry.index.count : m.geometry.attributes.position.count) / 3; } });
    const ov = new T.MeshStandardMaterial(), mats = {wire: new T.MeshBasicMaterial({color: 0x000000, wireframe: true}), solid: new T.MeshStandardMaterial({color: 0xbfbfbf, roughness: .75})};

    /* ---- shading ---- */
    const own = m => m.userData.pc ? m.userData.pc.mat : (D.tx.src && ov.map ? ov : m.userData.m);
    function setBg() {
      const b = D.bg; let css;
      if (b.t === 'color') css = b.c; else if (b.t === 'gradient') css = `linear-gradient(${b.c2},${b.c})`;
      else if (b.t === 'image' && b.img) css = `url("${b.img}") center/cover no-repeat`; else css = cur === 'wire' ? '#303030' : 'radial-gradient(ellipse at 50% 40%, #444 0%, #393939 55%, #303030 100%)';
      bgEl.style.background = css; sc.background = b.t === 'hdri' && hdri ? hdri : null; dirty = true;
    }
    function shade(m) {
      cur = m; const orig = m === 'material' || m === 'rendered', pick = (x, w) => m === 'wire' ? mats.wire : m === 'solid' ? mats.solid : w;
      meshes.forEach(x => x.material = pick(x, own(x))); props.forEach(P => P.ms.forEach(x => x.material = pick(x, x.userData.m)));
      sc.environment = orig ? (D.bg.t === 'hdri' && hdri ? hdri : env) : null; hemi.visible = m === 'solid'; sun.visible = m === 'solid' || m === 'rendered';
      sun.castShadow = m === 'rendered'; ground.visible = m === 'rendered'; grid.visible = axes.visible = D.grid;
      r.toneMapping = m === 'rendered' ? T.ACESFilmicToneMapping : T.NoToneMapping; sc.traverse(x => { if (x.material && !x.userData.keepMat) x.material.needsUpdate = true; });
      shadeBtns.forEach(b => b.classList.toggle('on', b.dataset.m === m)); setBg();
    }
    async function loadTex() {
      if (ov.map) { ov.map.dispose(); ov.map = null; }
      if (D.tx.src) { try { const t = await new T.TextureLoader().loadAsync(D.tx.src); t.colorSpace = T.SRGBColorSpace; t.flipY = false; t.wrapS = t.wrapT = T.RepeatWrapping; t.anisotropy = r.capabilities.getMaxAnisotropy(); ov.map = t; } catch (e) { say('Texture failed to load'); } }
      if (D.tx.src && (cur === 'solid' || cur === 'wire')) cur = 'material'; updTex();
    }
    function updTex() {
      if (ov.map) { ov.map.repeat.set(D.tx.rep, D.tx.rep); ov.map.center.set(.5, .5); ov.map.rotation = D.tx.rot * Math.PI / 180; }
      ov.color.set(D.tx.tint); ov.roughness = D.tx.rough; ov.metalness = D.tx.metal; ov.needsUpdate = true;
      meshes.forEach(x => { if (x.userData.pc) { x.userData.pc.mat.roughness = D.tx.rough; x.userData.pc.mat.metalness = D.tx.metal; } }); shade(cur); save();
    }
    async function loadHdri() { hdri = null; if (D.bg.t === 'hdri' && D.bg.img) { try { const t = await new T.TextureLoader().loadAsync(D.bg.img); t.mapping = T.EquirectangularReflectionMapping; t.colorSpace = T.SRGBColorSpace; hdri = t; } catch (e) { /* keep default */ } } shade(cur); }

    /* ---- undo / redo ---- */
    const H = [], F = []; let uBtn, rBtn;
    const updHist = () => { if (uBtn) { uBtn.disabled = !H.length; rBtn.disabled = !F.length; } };
    const sceneJ = () => JSON.stringify({m: D.model, p: D.props, k: D.anim.keys, a: D.annot, u: D.rulers, c: D.cursor});
    const pushScene = () => { H.push({t: 's', j: sceneJ()}); if (H.length > 80) H.shift(); F.length = 0; updHist(); };
    async function applyScene(j) {
      busy = true; const s = JSON.parse(j), ei = ed ? props.indexOf(ed.P) : -1, em0 = ed && ed.mode; endModal(false); leaveEdit(); detachAll(); selObj = null; lastOp = null; showRedo();
      props.forEach(x => { sc.remove(x.g); if (x.p.k === 'mesh') x.g.geometry.dispose(); }); props.length = 0;
      Object.assign(D.model, s.m); setT(holder, D.model); D.props = s.p; D.anim.keys = s.k; D.annot = s.a || []; D.rulers = s.u || []; D.cursor = s.c || [0, 0, 0];
      for (const p of D.props) try { await mkProp(p); } catch (e) { /* skip broken props */ }
      shade(cur); choose(null); markers(); drawAnnot(); drawRulers();
      if (ei >= 0 && props[ei] && props[ei].p.k === 'mesh') { choose(props[ei].g); enterEdit(); em0 && setEMode(em0); }
      save(); busy = false;
    }
    const swap = (from, to) => async () => {
      if (busy || !from.length) return; const e = from.pop();
      if (e.t === 's') { to.push({t: 's', j: sceneJ()}); await applyScene(e.j); }
      else { const m = meshes[e.i], pc = m && m.userData.pc; if (pc && e.img) { to.push({t: 'p', i: e.i, img: pc.g.getImageData(0, 0, 1024, 1024)}); pc.g.putImageData(e.img, 0, 0); pc.tex.needsUpdate = true; persist(m); dirty = true; } }
      updHist(); refreshPanels();
    };
    const undo = swap(H, F), redo = swap(F, H);

    /* ---- gizmos (Move, Rotate, Scale; Transform shows move + rotate) ---- */
    const tcT = new TransformControls(cam, cvs), tcR = new TransformControls(cam, cvs), tcS = new TransformControls(cam, cvs), TCS = [tcT, tcR, tcS], pivot = new T.Object3D();
    tcT.setMode('translate'); tcR.setMode('rotate'); tcS.setMode('scale'); sc.add(tcT, tcR, tcS, pivot);
    const detachAll = () => TCS.forEach(t => t.detach());
    const commit = () => { Object.assign(D.model, getT(holder)); props.forEach(x => x.p.t = getT(x.g)); save(); };
    TCS.forEach(tc => {
      tc.addEventListener('dragging-changed', e => {
        ctl.enabled = !e.value; TCS.forEach(t => { if (t !== tc) t.enabled = !e.value; }); // only one handle at a time
        if (ed && tc.object === pivot) {
          if (e.value) { pushScene(); pivot.updateMatrixWorld(true); ed.g.updateMatrixWorld(true); ed.st = {inv0: pivot.matrixWorld.clone().invert(), o: [...ed.sel].map(i => ({i, w: new T.Vector3(...ed.em.v[i]).applyMatrix4(ed.g.matrixWorld)}))}; }
          else { ed.st = null; refreshEdit(); save(); }
          return;
        }
        if (e.value) pushScene(); else { commit(); refreshPanels(); }
      });
      tc.addEventListener('objectChange', () => {
        if (ed && ed.st && tc.object === pivot) {
          pivot.updateMatrixWorld(true); const Mx = pivot.matrixWorld.clone().multiply(ed.st.inv0), inv = ed.g.matrixWorld.clone().invert();
          ed.st.o.forEach(q => { const v = q.w.clone().applyMatrix4(Mx).applyMatrix4(inv); ed.em.v[q.i] = [v.x, v.y, v.z]; }); refreshEdit(true);
        }
        dirty = true; itemLive();
      });
      tc.addEventListener('change', () => dirty = true);
    });
    tcR.size = 1.35; // Transform tool: rotation rings sit outside the move arrows
    // three.js colours its up axis green; Blender's up axis is Z (blue), so swap green and blue on the handles to match the gizmo and fields.
    TCS.forEach(tc => tc.traverse(o => { const m = o.material; if (!m || !m.color || m.userData.blSwap) return; m.userData.blSwap = true;
      [m.color, m._color].forEach(c => { if (!c || !c.isColor) return; const {r, g, b} = c; if (g > .5 && r < .5 && b < .5) c.setRGB(r, b, g); else if (b > .5 && r < .5 && g < .5) c.setRGB(r, b, g); }); }));
    function attachGizmos() {
      detachAll(); const target = ed ? (ed.sel.size ? pivot : null) : selObj; if (!target || M || mode === 'paint') return;
      if (tool === 'move' || tool === 'transform') tcT.attach(target);
      if (tool === 'rotate' || tool === 'transform') tcR.attach(target);
      if (tool === 'scale') tcS.attach(target);
      TCS.forEach(t => { t.setSpace(D.view.space); t.setTranslationSnap(D.view.snap ? (D.view.snapMove || .1) : null); t.setRotationSnap(D.view.snap ? (D.view.snapRot || 15) / DEG : null); t.setScaleSnap(D.view.snap ? .1 : null); });
    }
    const selectables = () => [holder, ...props.map(x => x.g)];
    const nameOf = n => { if (!n) return ''; const P = props.find(x => x.g === n); return P ? (P.p.n || PRIM_NAMES[P.p.k] || (P.p.k === 'glb' ? 'Model' : 'Object')) : modelName; };

    /* ---- outline ---- */
    const outMats = {mask: new T.MeshBasicMaterial({colorWrite: false, depthWrite: false, depthTest: false, stencilWrite: true, stencilRef: 1, stencilFunc: T.AlwaysStencilFunc, stencilZPass: T.ReplaceStencilOp}),
      line: new T.ShaderMaterial({vertexShader: OUTLINE_VS, fragmentShader: OUTLINE_FS, depthWrite: false,
        uniforms: {color: {value: new T.Color('#ffa028')}, width: {value: 2}, res: {value: new T.Vector2(1, 1)}},
        stencilWrite: true, stencilRef: 1, stencilFunc: T.NotEqualStencilFunc, stencilZPass: T.KeepStencilOp, stencilWriteMask: 0})};
    let outlined = [];
    function clearOutline() { outlined.forEach(x => { x.parent && x.parent.remove(x); x.userData.ownGeo && x.geometry.dispose(); }); outlined = []; dirty = true; }
    function setOutline() {
      clearOutline(); if (!selObj || ed) return;
      selObj.traverse(m => {
        if (!m.isMesh || m.isSkinnedMesh || m.userData.outline || !m.geometry.attributes.position) return;
        if (!m.geometry.attributes.onormal) m.geometry.setAttribute('onormal', smoothNormals(m.geometry));
        const a = new T.Mesh(m.geometry, outMats.mask), b = new T.Mesh(m.geometry, outMats.line);
        a.renderOrder = 900; b.renderOrder = 901; a.userData.outline = b.userData.outline = true; a.userData.keepMat = b.userData.keepMat = true; a.raycast = b.raycast = () => {};
        m.add(a, b); outlined.push(a, b);
      });
      dirty = true;
    }

    function choose(n) {
      selObj = n; if (!ed) { setOutline(); attachGizmos(); }
      refreshPanels(); info(); dirty = true;
    }

    /* ---- props: model props, editable meshes ---- */
    const PRIM = {cube: () => new T.BoxGeometry(1, 1, 1), sphere: () => new T.SphereGeometry(.5, 32, 16), cylinder: () => new T.CylinderGeometry(.5, .5, 1, 32), cone: () => new T.ConeGeometry(.5, 1, 32), plane: () => new T.PlaneGeometry(1, 1).rotateX(-Math.PI / 2), torus: () => new T.TorusGeometry(.4, .15, 16, 48)};
    async function mkProp(p) {
      let g;
      if (p.k === 'glb') g = (await loader.loadAsync(p.src)).scene;
      else if (p.k === 'mesh') g = new T.Mesh(buildGeo(p.geo), new T.MeshStandardMaterial({color: p.c || '#bfbfbf', roughness: .7, side: T.DoubleSide}));
      else g = new T.Mesh(PRIM[p.k](), new T.MeshStandardMaterial({color: p.c || '#bfbfbf', roughness: .7, side: T.DoubleSide}));
      setT(g, p.t); const ms = []; g.traverse(x => { if (x.isMesh) { x.userData.m = x.material; x.castShadow = x.receiveShadow = true; ms.push(x); } });
      sc.add(g); props.push({p, g, ms}); return g;
    }
    const primArgs = {cylinder: [['Vertices', 'n', 12, 3, 64]], cone: [['Vertices', 'n', 12, 3, 64]], sphere: [['Segments', 's', 12, 3, 64], ['Rings', 'R', 8, 3, 32]], torus: [['Major Segments', 's', 12, 3, 64], ['Minor Segments', 't', 8, 3, 32]]};
    const primGeo = (k, a) => sized(EP[k](...(primArgs[k] || []).map(([, key]) => a[key])), a.size);
    async function addPrim(k, at) {
      pushScene(); const a = {size: Math.round(rad * .5 * 10) / 10 || 1, loc: toB(at || D.cursor), rot: [0, 0, 0]}; (primArgs[k] || []).forEach(([, key, def]) => a[key] = def);
      const p = {id: Math.random().toString(36).slice(2, 8), k: 'mesh', n: PRIM_NAMES[k], prim: k, geo: primGeo(k, a), c: '#bfbfbf', t: {p: a.loc.slice(), r: [0, 0, 0], s: [1, 1, 1]}};
      D.props.push(p); const g = await mkProp(p); save(); shade(cur); choose(g); sfx.play('pop');
      setLastOp('Add ' + PRIM_NAMES[k], a, [
        ['Size', 'size', {step: .1, min: .001, unit: ' m'}], ...(primArgs[k] || []).map(([l, key, , mn, mx]) => [l, key, {step: 1, dec: 0, min: mn, max: mx}]),
        ['Location', 'loc', {vec: true, step: .1, unit: ' m'}], ['Rotation', 'rot', {vec: true, step: 5, dec: 1, unit: '°'}]],
      () => { const P = props.find(x => x.p === p); if (!P) return; p.geo = primGeo(k, a); p.t.p = fromB(a.loc); { const e = new T.Euler().setFromQuaternion(rotFromB(a.rot)); p.t.r = [e.x, e.y, e.z]; }
        P.g.geometry.dispose(); P.g.geometry = buildGeo(p.geo); setT(P.g, p.t); if (selObj === P.g) setOutline(); save(); refreshPanels(); dirty = true; });
    }
    async function addModelProp(src) { pushScene(); const p = {id: Math.random().toString(36).slice(2, 8), k: 'glb', n: decodeURIComponent(src.split('/').pop()).replace(/\.(glb|gltf).*$/i, ''), src, t: {p: D.cursor.slice(), r: [0, 0, 0], s: [1, 1, 1]}}; D.props.push(p); const g = await mkProp(p); save(); shade(cur); choose(g); sfx.play('pop'); }
    function removeSel() {
      const i = props.findIndex(x => x.g === selObj); if (i < 0) { say("The main model can't be deleted here"); return; }
      pushScene(); const P = props[i]; detachAll(); clearOutline(); sc.remove(P.g); props.splice(i, 1); D.props = D.props.filter(q => q !== P.p); delete D.anim.keys[P.p.id];
      selObj = null; choose(null); save(); markers(); sfx.play('delete');
    }
    async function duplicateSel(thenGrab = true) {
      const x = props.find(q => q.g === selObj); if (!x) { say('Select a prop or mesh to duplicate'); return; }
      pushScene(); const p = JSON.parse(JSON.stringify(x.p)); p.id = Math.random().toString(36).slice(2, 8); D.props.push(p);
      const g = await mkProp(p); save(); shade(cur); choose(g); sfx.play('pop'); if (thenGrab) startModal('grab', {noUndo: true});
    }
    for (const p of D.props) try { await mkProp(p); } catch (e) { /* skip broken props */ }

    /* ---- Edit Mode ---- */
    const edMat = {pt: new T.PointsMaterial({size: 7, sizeAttenuation: false, vertexColors: true, depthTest: false, transparent: true}),
      ln: new T.LineBasicMaterial({vertexColors: true, depthTest: false, transparent: true, opacity: .85}),
      fc: new T.MeshBasicMaterial({color: 0xffa028, transparent: true, opacity: .28, depthTest: false, side: T.DoubleSide})};
    function enterEdit() {
      const P = props.find(x => x.g === selObj);
      if (!P || P.p.k !== 'mesh') { say('Select an editable mesh first (Add ▸ Mesh in the header)'); syncMode(); return false; }
      if (mode === 'paint') leavePaint();
      clearOutline(); ed = {P, g: P.g, em: P.p.geo, mode: D.view.emode || 'v', sel: new Set(), ov: new T.Group(), st: null}; ed.g.add(ed.ov);
      mode = 'edit'; detachAll(); refreshEdit(); setTool(['move', 'rotate', 'scale', 'transform', 'select'].includes(tool) ? tool : 'select'); syncMode(); return true;
    }
    function leaveEdit() {
      if (!ed) return; endModal(true); const {g, ov} = ed; ov.children.forEach(c => c.geometry.dispose()); g.remove(ov); ed = null; mode = 'object'; detachAll();
      if (['extrude', 'inset'].includes(tool)) tool = 'select'; if (selObj) choose(selObj); dirty = true; syncMode(); buildTools(); save();
    }
    function refreshEdit(skipPivot) {
      const {g, em, sel, ov} = ed; g.geometry.dispose(); g.geometry = buildGeo(em); ed.P.p.geo = em;
      while (ov.children.length) { const c = ov.children[0]; ov.remove(c); c.geometry.dispose(); }
      const selC = [1, .63, .16], vC = [0, 0, 0], eC = [0, 0, 0];
      const pp = [], pc = []; if (ed.mode === 'v') em.v.forEach((v, i) => { pp.push(v[0], v[1], v[2]); pc.push(...(sel.has(i) ? selC : vC)); });
      const P1 = new T.BufferGeometry(); P1.setAttribute('position', new T.Float32BufferAttribute(pp, 3)); P1.setAttribute('color', new T.Float32BufferAttribute(pc, 3));
      const lp = [], lc = []; edgesOf(em).forEach(([a, b]) => { const s = sel.has(a) && sel.has(b); lp.push(...em.v[a], ...em.v[b]); for (let k = 0; k < 2; k++) lc.push(...(s ? selC : eC)); });
      const L = new T.BufferGeometry(); L.setAttribute('position', new T.Float32BufferAttribute(lp, 3)); L.setAttribute('color', new T.Float32BufferAttribute(lc, 3));
      const fp = []; faceSel(em, sel).forEach(fi => { const f = em.f[fi]; for (let i = 1; i < f.length - 1; i++) fp.push(...em.v[f[0]], ...em.v[f[i]], ...em.v[f[i + 1]]); });
      const FG = new T.BufferGeometry(); FG.setAttribute('position', new T.Float32BufferAttribute(fp, 3));
      [new T.Points(P1, edMat.pt), new T.LineSegments(L, edMat.ln), new T.Mesh(FG, edMat.fc)].forEach(x => { x.renderOrder = 10; ov.add(x); });
      if (!skipPivot) { g.updateMatrixWorld(true); if (sel.size) { const c = cen(em, [...sel]); pivot.position.set(c[0], c[1], c[2]).applyMatrix4(g.matrixWorld); pivot.quaternion.identity(); pivot.scale.set(1, 1, 1); } attachGizmos(); }
      itemLive(); info(); dirty = true;
    }
    function op(name, fn) { if (!ed) return; sfx.play('click'); pushScene(); const s = fn(ed.em, ed.sel); if (s) ed.sel = s; refreshEdit(); save(); return name; }
    const setEMode = m => { if (!ed) return; ed.mode = m; D.view.emode = m; selBtns.forEach(b => b.classList.toggle('on', b.dataset.k === m)); refreshEdit(); };
    function subdivOp() {
      if (!ed) return; pushScene(); const base = clone(ed.em), sel0 = new Set(ed.sel), a = {cuts: 1};
      const run = () => { const em = clone(base); let s = new Set(sel0); for (let i = 0; i < a.cuts; i++) s = subdivide(em, s) || s; ed.em = em; ed.sel = s; refreshEdit(); save(); };
      run(); sfx.play('click'); setLastOp('Subdivide', a, [['Number of Cuts', 'cuts', {step: 1, dec: 0, min: 1, max: 4}]], run);
    }
    const A_ = {
      extrude: () => startModal('extrude'), inset: () => startModal('inset'), subdiv: subdivOp,
      del: () => op('Delete', (em, s) => delSel(em, s, ed.mode)), merge: () => op('Merge', (em, s) => merge(em, s)), flip: () => op('Flip Normals', (em, s) => flip(em, s)),
      all: () => { if (ed) { ed.sel = new Set(ed.em.v.map((_, i) => i)); refreshEdit(); } }, none: () => { if (ed) { ed.sel = new Set(); refreshEdit(); } },
      invert: () => { if (ed) { ed.sel = new Set(ed.em.v.map((_, i) => i).filter(i => !ed.sel.has(i))); refreshEdit(); } },
    };
    const proj = i => { const v = new T.Vector3(...ed.em.v[i]).applyMatrix4(ed.g.matrixWorld).project(cam), b = cvs.getBoundingClientRect(); return [(v.x * .5 + .5) * b.width, (-v.y * .5 + .5) * b.height, v.z]; };
    function editPick(e) {
      const b = cvs.getBoundingClientRect(), x = e.clientX - b.left, y = e.clientY - b.top, em = ed.em; ed.g.updateMatrixWorld(true); let hit = null;
      const reach = e.pointerType === 'mouse' ? 1 : 2.2;
      if (ed.mode === 'v') { let bd = 14 * reach; em.v.forEach((_, i) => { const p = proj(i); if (p[2] > 1) return; const d = Math.hypot(p[0] - x, p[1] - y); if (d < bd) { bd = d; hit = [i]; } }); }
      else if (ed.mode === 'e') { let bd = 10 * reach; edgesOf(em).forEach(([a, c]) => { const d = segd(x, y, proj(a), proj(c)); if (d < bd) { bd = d; hit = [a, c]; } }); }
      else { ndc.set(x / b.width * 2 - 1, -(y / b.height) * 2 + 1); rc.setFromCamera(ndc, cam); const h = rc.intersectObject(ed.g, false)[0]; if (h && h.faceIndex != null) { const fi = ed.g.geometry.userData.fmap[h.faceIndex]; if (fi != null) hit = em.f[fi]; } }
      if (!e.shiftKey) ed.sel = new Set();
      if (hit) { const all = hit.every(i => ed.sel.has(i)); hit.forEach(i => all && e.shiftKey ? ed.sel.delete(i) : ed.sel.add(i)); }
      refreshEdit();
    }
    function boxSelect(x0, y0, x1, y1, add) {
      const em = ed.em, b = cvs.getBoundingClientRect(), inBox = (px, py) => px >= Math.min(x0, x1) && px <= Math.max(x0, x1) && py >= Math.min(y0, y1) && py <= Math.max(y0, y1);
      const ins = i => { const p = proj(i); return p[2] <= 1 && inBox(p[0], p[1]); }; ed.g.updateMatrixWorld(true); if (!add) ed.sel = new Set();
      if (ed.mode === 'f') em.f.forEach(f => { const v = new T.Vector3(...cen(em, f)).applyMatrix4(ed.g.matrixWorld).project(cam); if (inBox((v.x * .5 + .5) * b.width, (-v.y * .5 + .5) * b.height)) f.forEach(i => ed.sel.add(i)); });
      else if (ed.mode === 'e') edgesOf(em).forEach(([a, c]) => { if (ins(a) && ins(c)) { ed.sel.add(a); ed.sel.add(c); } });
      else em.v.forEach((_, i) => { if (ins(i)) ed.sel.add(i); });
      refreshEdit();
    }
    async function exportGlb(bake) {
      leaveEdit(); const ms = props.filter(x => x.p.k === 'mesh'); if (!ms.length) { say('Add a mesh first (Add ▸ Mesh)'); return; }
      try {
        const {GLTFExporter} = await import('three/addons/exporters/GLTFExporter.js'), grp = new T.Group();
        ms.forEach(x => { const c = x.g.clone(); c.children.filter(k => k.userData.outline).forEach(k => c.remove(k)); grp.add(c); });
        const buf = await new GLTFExporter().parseAsync(grp, {binary: true}), u = await o.up(new Blob([buf], {type: 'model/gltf-binary'}), 'models', `${o.key}_${Date.now().toString(36)}.glb`);
        if (bake) { pushScene(); ms.forEach(x => sc.remove(x.g)); const keep = props.filter(x => x.p.k !== 'mesh'); props.splice(0, props.length, ...keep); D.props = D.props.filter(p => p.k !== 'mesh'); o.setModel && o.setModel(u); save(); choose(null); say('Saved as the asset model. Close and reopen the viewer to see it.'); sfx.play('complete'); }
        else { say('Exported to Library ▸ Models'); sfx.play('chime'); }
      } catch (e) { say('Export failed: ' + e.message); sfx.play('error'); }
    }

    /* ---- Texture Paint ---- */
    const rc = new T.Raycaster(), ndc = new T.Vector2();
    function initPC(m) {
      if (m.userData.pc) return m.userData.pc; const c = document.createElement('canvas'); c.width = c.height = 1024; const g = c.getContext('2d'), om = m.userData.m;
      g.fillStyle = om && om.color ? '#' + om.color.getHexString() : '#ffffff'; g.fillRect(0, 0, 1024, 1024);
      const img = D.tx.src && ov.map ? ov.map.image : om && om.map ? om.map.image : null; if (img) try { g.drawImage(img, 0, 0, 1024, 1024); } catch (e) { /* cross-origin image */ }
      const bs = document.createElement('canvas'); bs.width = bs.height = 1024; bs.getContext('2d').drawImage(c, 0, 0);
      const tex = new T.CanvasTexture(c); tex.flipY = false; tex.colorSpace = T.SRGBColorSpace; tex.anisotropy = 8; const mat = new T.MeshStandardMaterial({map: tex, roughness: D.tx.rough, metalness: D.tx.metal});
      return m.userData.pc = {c, g, bs, tex, mat};
    }
    function stamp(pc, x, y) {
      const g = pc.g, B = D.brush, s = B.size;
      if (B.erase || tool === 'erase') { g.save(); g.beginPath(); g.arc(x, y, s / 2, 0, 7); g.clip(); g.drawImage(pc.bs, 0, 0); g.restore(); return; }
      const h = parseInt(B.color.slice(1), 16), rgb = `${h >> 16},${(h >> 8) & 255},${h & 255}`, gr = g.createRadialGradient(x, y, 0, x, y, s / 2);
      gr.addColorStop(0, `rgba(${rgb},${B.op})`); gr.addColorStop(Math.min(.98, B.hard), `rgba(${rgb},${B.op})`); gr.addColorStop(1, `rgba(${rgb},0)`); g.fillStyle = gr; g.beginPath(); g.arc(x, y, s / 2, 0, 7); g.fill();
    }
    let stroke = null, pt;
    function paintAt(e) {
      setRay(e); const h = rc.intersectObjects(meshes, false)[0]; if (!h || !h.uv) { stroke.last = null; return; }
      const m = h.object, pc = initPC(m), x = h.uv.x * 1024, y = h.uv.y * 1024;
      if (!stroke.pushed) { stroke.pushed = true; H.push({t: 'p', i: meshes.indexOf(m), img: pc.g.getImageData(0, 0, 1024, 1024)}); F.length = 0; const ps = H.filter(q => q.t === 'p' && q.img); if (ps.length > 12) ps[0].img = null; updHist(); }
      if (m.material !== pc.mat) { if (cur === 'wire' || cur === 'solid') cur = 'material'; shade(cur); }
      const L = stroke.last; if (L && L.m === m) { const d = Math.hypot(x - L.x, y - L.y), n = Math.max(1, Math.ceil(d / (D.brush.size * .2))); for (let i = 1; i <= n; i++) stamp(pc, L.x + (x - L.x) * i / n, L.y + (y - L.y) * i / n); } else stamp(pc, x, y);
      stroke.last = {m, x, y}; stroke.m = m; pc.tex.needsUpdate = true; dirty = true;
    }
    const persist = m => { clearTimeout(pt); pt = setTimeout(() => { const pc = m.userData.pc, i = meshes.indexOf(m); if (!pc) return; pc.c.toBlob(async b => { D.paint[i] = (await o.up(b, 'textures/painted', `${o.key}_${i}.png`)) + '?v=' + Date.now(); save(); }, 'image/png'); }, 500); };
    function enterPaint() { if (!meshes.length) { say('Texture Paint works on the imported model; this scene has none'); syncMode(); return; } if (ed) leaveEdit(); mode = 'paint'; detachAll(); clearOutline(); setTool('draw'); if (cur === 'wire' || cur === 'solid') shade('material'); syncMode(); }
    function leavePaint() { mode = 'object'; setTool('select'); if (selObj) choose(selObj); syncMode(); }
    if (D.tx.src) await loadTex();
    for (const [i, u] of Object.entries(D.paint)) { const m = meshes[+i]; if (!m) continue; const pc = initPC(m); await new Promise(res => { const im = new Image(); im.onload = () => { pc.g.clearRect(0, 0, 1024, 1024); pc.g.drawImage(im, 0, 0, 1024, 1024); pc.tex.needsUpdate = true; res(); }; im.onerror = res; im.src = u; }); }

    /* ---- picking helpers ---- */
    function setRay(e) { const b = cvs.getBoundingClientRect(); ndc.set((e.clientX - b.left) / b.width * 2 - 1, -(e.clientY - b.top) / b.height * 2 + 1); rc.setFromCamera(ndc, cam); }
    function surfacePoint(e, plane) {
      setRay(e); const hits = rc.intersectObjects([...meshes, ...props.flatMap(x => x.ms)], false).filter(h => !h.object.userData.outline);
      if (hits[0]) return hits[0].point.clone();
      const p = new T.Vector3();
      if (plane) return rc.ray.intersectPlane(plane, p) ? p : null;
      // Floor first; when looking at it side-on (Front/Right views) use the plane facing the view through the 3D cursor, like Blender.
      if (Math.abs(rc.ray.direction.y) > .15 && rc.ray.intersectPlane(new T.Plane(new T.Vector3(0, 1, 0), 0), p)) return p;
      const n = cam.getWorldDirection(new T.Vector3()).negate(); return rc.ray.intersectPlane(new T.Plane().setFromNormalAndCoplanarPoint(n, new T.Vector3(...D.cursor)), p) ? p : null;
    }
    const toScreen = v => { const p = v.clone().project(cam), b = cvs.getBoundingClientRect(); return {x: (p.x * .5 + .5) * b.width, y: (-p.y * .5 + .5) * b.height, z: p.z}; };
    const worldPerPixel = at => { const b = cvs.getBoundingClientRect(); if (isOrtho) return (cam.top - cam.bottom) / cam.zoom / b.height; const d = cam.position.distanceTo(at); return 2 * d * Math.tan(cam.fov / DEG / 2) / b.height; };

    /* ---- annotations and rulers ---- */
    const annotGroup = new T.Group(); sc.add(annotGroup);
    function drawAnnot() {
      annotGroup.children.slice().forEach(c => { annotGroup.remove(c); c.geometry.dispose(); c.material.dispose(); });
      D.annot.forEach(s => { if (s.pts.length < 2) return; const g = new T.BufferGeometry().setFromPoints(s.pts.map(p => new T.Vector3(...p)));
        const l = new T.Line(g, new T.LineBasicMaterial({color: s.c, depthTest: false, transparent: true})); l.renderOrder = 950; l.userData.keepMat = true; annotGroup.add(l); });
      dirty = true;
    }
    const rulerGroup = new T.Group(); sc.add(rulerGroup);
    function drawRulers() {
      rulerGroup.children.slice().forEach(c => { rulerGroup.remove(c); c.geometry.dispose(); c.material.dispose(); });
      D.rulers.forEach(u => { const g = new T.BufferGeometry().setFromPoints([new T.Vector3(...u.a), new T.Vector3(...u.b)]);
        const l = new T.Line(g, new T.LineDashedMaterial({color: 0xffffff, dashSize: rad * .03, gapSize: rad * .02, depthTest: false, transparent: true})); l.computeLineDistances(); l.renderOrder = 950; l.userData.keepMat = true; rulerGroup.add(l); });
      dirty = true;
    }
    function placeOverlays() {
      // 3D cursor and ruler labels are HTML so they stay crisp and readable on any screen
      const c = toScreen(new T.Vector3(...D.cursor)); cursorEl.style.display = c.z < 1 ? '' : 'none'; cursorEl.style.transform = `translate(${c.x}px,${c.y}px)`;
      labels.textContent = '';
      D.rulers.forEach(u => { const a = new T.Vector3(...u.a), b = new T.Vector3(...u.b), m = toScreen(a.clone().add(b).multiplyScalar(.5)); if (m.z > 1) return;
        const t = el('span', 'bl-rlab', fmtLen(a.distanceTo(b))); t.style.transform = `translate(${m.x}px,${m.y}px)`; labels.append(t); });
    }
    const fmtLen = d => d >= 1 ? d.toFixed(3).replace(/\.?0+$/, '') + ' m' : (d * 100).toFixed(1).replace(/\.0$/, '') + ' cm';

    /* ---- modal operators (G/R/S, extrude, inset) ---- */
    const modalBar = el('div', 'bl-modal hidden');
    function startModal(kind, opt = {}) {
      if (M) return; if (mode === 'paint') return;
      if (ed) { if (!ed.sel.size) { say('Select something first (A selects all)'); return; } if (kind === 'inset' && !faceSel(ed.em, ed.sel).length) { say('Inset works on faces: switch to Face select (3) and pick some'); return; } }
      else if (!selObj) { say('Select an object first'); return; } else if (kind === 'extrude' || kind === 'inset') return;
      if (!opt.noUndo) pushScene();
      const pushed = !opt.noUndo;
      detachAll(); ctl.enabled = false; closeMenus();
      M = {kind, axis: null, ref: opt.ref || null, cur: opt.ref || null, num: '', touch: !!opt.touch, val: null, pushed};
      if (ed) {
        ed.g.updateMatrixWorld(true); M.em0 = clone(ed.em); M.sel0 = new Set(ed.sel); M.mw = ed.g.matrixWorld.clone(); M.inv = M.mw.clone().invert();
        const c = cen(ed.em, [...ed.sel]); M.pivot = new T.Vector3(...c).applyMatrix4(M.mw);
        if (kind === 'extrude') { const d = extrudeDir(ed.em, ed.sel); M.dir = new T.Vector3(...d).transformDirection(M.mw); M.a = {offset: 0}; }
        if (kind === 'inset') M.a = {thickness: 0, depth: 0, individual: false};
      } else { M.obj = selObj; M.t0 = getT(selObj); M.q0 = selObj.quaternion.clone(); M.pivot = selObj.getWorldPosition(new T.Vector3()); }
      if (!M.ref && lastPointer) { M.ref = {...lastPointer}; M.cur = {...lastPointer}; }
      modalBar.classList.remove('hidden'); host.classList.add('modal'); modalUpdate(); sfx.play('tick');
    }
    function modalValue() {
      const k = M.kind, typed = M.num !== '' && M.num !== '-' ? parseFloat(M.num) : null;
      if (k === 'extrude') {
        if (typed != null) return typed; if (!M.ref || !M.cur) return 0;
        return axisParam(M.cur, M.pivot, M.dir) - axisParam(M.ref, M.pivot, M.dir);
      }
      if (k === 'inset') {
        if (typed != null) return Math.max(0, typed); if (!M.ref || !M.cur) return 0;
        const p = toScreen(M.pivot), d0 = Math.hypot(M.ref.x - p.x, M.ref.y - p.y), d1 = Math.hypot(M.cur.x - p.x, M.cur.y - p.y);
        return Math.max(0, (d0 - d1) * worldPerPixel(M.pivot));
      }
      if (k === 'grab') {
        if (typed != null) return (M.axis ? AX[M.axis] : AX.x).clone().multiplyScalar(typed);
        if (!M.ref || !M.cur) return new T.Vector3();
        if (M.axis) { const ax = AX[M.axis]; return ax.clone().multiplyScalar(axisParam(M.cur, M.pivot, ax) - axisParam(M.ref, M.pivot, ax)); }
        const n = cam.getWorldDirection(new T.Vector3()), pl = new T.Plane().setFromNormalAndCoplanarPoint(n, M.pivot), a = rayPlane(M.ref, pl), b = rayPlane(M.cur, pl);
        return a && b ? b.sub(a) : new T.Vector3();
      }
      if (k === 'rotate') {
        if (typed != null) return typed / DEG; if (!M.ref || !M.cur) return 0;
        const p = toScreen(M.pivot); return Math.atan2(M.cur.y - p.y, M.cur.x - p.x) - Math.atan2(M.ref.y - p.y, M.ref.x - p.x);
      }
      if (k === 'scale') {
        if (typed != null) return typed; if (!M.ref || !M.cur) return 1;
        const p = toScreen(M.pivot), d0 = Math.max(4, Math.hypot(M.ref.x - p.x, M.ref.y - p.y)); return Math.hypot(M.cur.x - p.x, M.cur.y - p.y) / d0;
      }
    }
    function rayAt(pt) { const b = cvs.getBoundingClientRect(); ndc.set(pt.x / b.width * 2 - 1, -pt.y / b.height * 2 + 1); rc.setFromCamera(ndc, cam); return rc.ray; }
    function rayPlane(pt, pl) { const v = new T.Vector3(); return rayAt(pt).intersectPlane(pl, v) ? v : null; }
    function axisParam(pt, origin, dir) {
      // closest point on the line origin + t·dir to the mouse ray; returns t
      const ray = rayAt(pt), w0 = origin.clone().sub(ray.origin), a = dir.dot(dir), b = dir.dot(ray.direction), c = ray.direction.dot(ray.direction), d = dir.dot(w0), e = ray.direction.dot(w0);
      const den = a * c - b * b; return Math.abs(den) < 1e-9 ? 0 : (b * e - c * d) / den;
    }
    function rotAxis() {
      const toCam = cam.position.clone().sub(M.pivot).normalize();
      if (!M.axis) return isOrtho ? cam.getWorldDirection(new T.Vector3()).negate() : toCam;
      const a = AX[M.axis].clone(); return a.dot(toCam) < 0 ? a.negate() : a;
    }
    function modalApply() {
      const v = modalValue(); M.val = v;
      if (M.kind === 'extrude') { M.a.offset = v; runExtrude(M.em0, M.sel0, M.a); }
      else if (M.kind === 'inset') { M.a.thickness = v; runInset(M.em0, M.sel0, M.a); }
      else if (ed) {
        const xf = modalMatrix(v), em = clone(M.em0);
        M.sel0.forEach(i => { const w = new T.Vector3(...em.v[i]).applyMatrix4(M.mw).applyMatrix4(xf).applyMatrix4(M.inv); em.v[i] = [w.x, w.y, w.z]; });
        ed.em = em; ed.sel = new Set(M.sel0); refreshEdit(true);
      } else {
        setT(M.obj, M.t0); M.obj.quaternion.copy(M.q0);
        if (M.kind === 'grab') M.obj.position.add(v);
        else if (M.kind === 'rotate') M.obj.quaternion.premultiply(new T.Quaternion().setFromAxisAngle(rotAxis(), -v));
        else M.obj.scale.multiply(scaleVec(v));
        itemLive();
      }
      dirty = true;
    }
    const scaleVec = f => M.axis ? new T.Vector3(1, 1, 1).setComponent(AXI[M.axis], f) : new T.Vector3(f, f, f);
    function modalMatrix(v) {
      const P = new T.Matrix4().makeTranslation(M.pivot.x, M.pivot.y, M.pivot.z), Pi = new T.Matrix4().makeTranslation(-M.pivot.x, -M.pivot.y, -M.pivot.z);
      if (M.kind === 'grab') return new T.Matrix4().makeTranslation(v.x, v.y, v.z);
      if (M.kind === 'rotate') return P.multiply(new T.Matrix4().makeRotationAxis(rotAxis(), -v)).multiply(Pi);
      const s = scaleVec(v); return P.multiply(new T.Matrix4().makeScale(s.x, s.y, s.z)).multiply(Pi);
    }
    function runExtrude(em0, sel0, a) { const em = clone(em0), s = extrude(em, new Set(sel0), a.offset); ed.em = em; ed.sel = s; refreshEdit(true); }
    function runInset(em0, sel0, a) { const em = clone(em0), s = inset(em, new Set(sel0), a.thickness, a.depth, a.individual); ed.em = em; ed.sel = s; refreshEdit(true); }
    function modalUpdate() {
      modalApply(); const v = M.val, ax = M.axis ? ` along ${M.axis.toUpperCase()}` : '';
      const vb = M.kind === 'grab' ? toB(v.toArray()) : null, txt = M.kind === 'grab' ? `D: ${fmtN(v.length())} m (${fmtN(vb[0])}, ${fmtN(vb[1])}, ${fmtN(vb[2])})${ax}`
        : M.kind === 'rotate' ? `Rot: ${fmtN(v * DEG, 2)}°${ax}` : M.kind === 'scale' ? `Scale: ${fmtN(v)}${ax}`
        : M.kind === 'extrude' ? `Extrude: ${fmtN(v)} m along Normal` : `Thickness: ${fmtN(v)} m`;
      modalTxt.textContent = (M.num ? `[${M.num}] ` : '') + txt;
      modalAxes.forEach(b => b.classList.toggle('on', b.dataset.a === M.axis)); modalAxes.forEach(b => b.hidden = M.kind === 'extrude' || M.kind === 'inset');
      status.textContent = M.touch || touchy ? 'Drag anywhere to adjust · tap ✓ to confirm' : 'Confirm: click / Enter · Cancel: right-click / Esc · X Y Z: axis · type a number';
    }
    const fmtN = (x, d = 3) => (+x).toFixed(d).replace(/\.?0+$/, '').replace(/^-0$/, '0');
    function endModal(ok) {
      if (!M) return; const m = M; M = null; modalBar.classList.add('hidden'); host.classList.remove('modal'); ctl.enabled = true; status.textContent = '';
      if (!ok) {
        if (ed && m.em0) { ed.em = m.em0; ed.sel = m.sel0; ed.P.p.geo = ed.em; refreshEdit(); } else if (m.obj) { setT(m.obj, m.t0); itemLive(); }
        if (m.pushed) { H.pop(); updHist(); } sfx.play('close'); dirty = true; attachGizmos(); return;
      }
      sfx.play('click');
      if (ed) {
        ed.P.p.geo = ed.em; refreshEdit();
        if (m.kind === 'extrude') setLastOp('Extrude Region', m.a, [['Offset', 'offset', {step: .05, unit: ' m'}]], () => { runExtrude(m.em0, m.sel0, m.a); refreshEdit(); save(); });
        else if (m.kind === 'inset') setLastOp('Inset Faces', m.a, [['Thickness', 'thickness', {step: .01, min: 0, unit: ' m'}], ['Depth', 'depth', {step: .01, unit: ' m'}], ['Individual', 'individual', {bool: true}]], () => { runInset(m.em0, m.sel0, m.a); refreshEdit(); save(); });
        else lastXform(m);
      } else { commit(); lastXform(m); }
      save(); attachGizmos(); refreshPanels();
    }
    function lastXform(m) {
      // Adjust Last Operation for Move / Rotate / Resize, re-applied from the starting state
      const v = m.val, a = m.kind === 'grab' ? {move: toB(v.toArray())} : m.kind === 'rotate' ? {angle: v * DEG, axis: m.axis || 'view'} : {scale: m.axis ? [1, 1, 1].map((x, i) => 'xyz'[i] === m.axis ? v : 1) : [v, v, v]};
      const reapply = () => {
        const val = m.kind === 'grab' ? new T.Vector3(...fromB(a.move)) : m.kind === 'rotate' ? a.angle / DEG : null;
        const prev = M; M = m; M.axis = m.kind === 'rotate' && a.axis !== 'view' ? a.axis : m.axis;
        if (ed && m.em0) { const xf = m.kind === 'scale' ? (() => { const P = new T.Matrix4().makeTranslation(m.pivot.x, m.pivot.y, m.pivot.z), Pi = new T.Matrix4().makeTranslation(-m.pivot.x, -m.pivot.y, -m.pivot.z); return P.multiply(new T.Matrix4().makeScale(...swapB(a.scale))).multiply(Pi); })() : modalMatrix(val), em = clone(m.em0);
          m.sel0.forEach(i => { const w = new T.Vector3(...em.v[i]).applyMatrix4(m.mw).applyMatrix4(xf).applyMatrix4(m.inv); em.v[i] = [w.x, w.y, w.z]; }); ed.em = em; ed.P.p.geo = em; refreshEdit(); }
        else if (m.obj) { setT(m.obj, m.t0); m.obj.quaternion.copy(m.q0); if (m.kind === 'grab') m.obj.position.add(val); else if (m.kind === 'rotate') m.obj.quaternion.premultiply(new T.Quaternion().setFromAxisAngle(rotAxis(), -val)); else m.obj.scale.multiply(new T.Vector3(...swapB(a.scale))); commit(); }
        M = prev; save(); refreshPanels(); dirty = true;
      };
      if (m.kind === 'grab') setLastOp('Move', a, [['Move', 'move', {vec: true, step: .05, unit: ' m'}]], reapply);
      else if (m.kind === 'rotate') setLastOp('Rotate', a, [['Angle', 'angle', {step: 5, dec: 2, unit: '°'}], ['Axis', 'axis', {opts: [['view', 'View'], ['x', 'X'], ['y', 'Y'], ['z', 'Z']]}]], reapply);
      else setLastOp('Resize', a, [['Scale', 'scale', {vec: true, step: .05}]], reapply);
    }

    /* ---- Adjust Last Operation panel (bottom left) ---- */
    const redoEl = el('div', 'bl-redo hidden');
    function setLastOp(title, a, fields, apply) { lastOp = {title, a, fields, apply}; showRedo(); }
    function showRedo() {
      redoEl.textContent = ''; if (!lastOp) { redoEl.classList.add('hidden'); return; }
      const {el: p, body} = panel(lastOp.title, !D.view.redoClosed); p.querySelector('.bl-ph').addEventListener('click', () => D.view.redoClosed = p.classList.contains('closed'));
      lastOp.fields.forEach(([label, key, f]) => {
        const go = () => lastOp.apply();
        if (f.vec) body.append(vec3(label, () => lastOp.a[key], v => { lastOp.a[key] = v; go(); }, f));
        else if (f.bool) body.append(check(label, () => lastOp.a[key], v => { lastOp.a[key] = v; go(); }));
        else if (f.opts) body.append(choice(label, f.opts, () => lastOp.a[key], v => { lastOp.a[key] = v; go(); }));
        else body.append(num(label, () => lastOp.a[key], v => { lastOp.a[key] = v; go(); }, f));
      });
      redoEl.append(p); redoEl.classList.remove('hidden');
    }

    /* ---- pointer handling ---- */
    let dn = null, box = null, annot = null, ruler = null, lastPointer = null; const pointers = new Set();
    const local = e => { const b = cvs.getBoundingClientRect(); return {x: e.clientX - b.left, y: e.clientY - b.top}; };
    const navKey = e => e.button === 1 || e.altKey;
    cvs.addEventListener('contextmenu', e => e.preventDefault());
    cvs.addEventListener('pointerdown', e => {
      pointers.add(e.pointerId); closeMenus();
      if (pointers.size > 1) { // second finger: hand over to orbit/zoom
        if (box) { box = null; rb.style.display = 'none'; } if (annot) { annot = null; } if (stroke) { stroke = null; } ctl.enabled = !M; return;
      }
      if (M) {
        if (e.button === 2) { endModal(false); return; }
        if (e.pointerType === 'mouse') { if (e.button === 0) endModal(true); return; }
        M.touch = true; M.ref = local(e); M.cur = local(e); M.pid = e.pointerId; cvs.setPointerCapture(e.pointerId); modalUpdate(); return;
      }
      if (e.button === 2) return;
      if (navKey(e) || TCS.some(t => t.dragging)) return;
      dn = {x: e.clientX, y: e.clientY, t: performance.now()}; const p = local(e);
      if (mode === 'paint' && (tool === 'draw' || tool === 'erase')) { ctl.enabled = false; stroke = {last: null, m: null}; paintAt(e); return; }
      if (tool === 'annotate') { ctl.enabled = false; const q = surfacePoint(e, viewPlane()); annot = {c: D.view.annotColor, pts: q ? [q.toArray()] : []}; return; }
      if (tool === 'measure') { ctl.enabled = false; const q = surfacePoint(e); if (q) { ruler = {a: q.toArray(), b: q.toArray()}; } return; }
      if (ed && (tool === 'extrude' || tool === 'inset')) { ctl.enabled = false; startModal(tool, {ref: p, touch: e.pointerType !== 'mouse'}); if (M) { M.drag = true; cvs.setPointerCapture(e.pointerId); } return; }
      if (ed && tool === 'select') { ctl.enabled = false; box = p; }
    });
    cvs.addEventListener('pointermove', e => {
      lastPointer = local(e);
      if (M) { if (M.touch && M.pid !== undefined && e.pointerId !== M.pid) return; if (!M.ref) M.ref = local(e); M.cur = local(e); modalUpdate(); return; }
      if (pointers.size > 1) return;
      if (stroke) paintAt(e);
      if (annot) { const q = surfacePoint(e, viewPlane()); if (q) { annot.pts.push(q.toArray()); if (annot.pts.length > 1) { D.annot.push(annot); drawAnnot(); D.annot.pop(); } } }
      if (ruler) { const q = surfacePoint(e); if (q) { ruler.b = q.toArray(); D.rulers.push(ruler); drawRulers(); placeOverlays(); D.rulers.pop(); } }
      if (box && dn && Math.hypot(e.clientX - dn.x, e.clientY - dn.y) > 4) { const p = local(e); rb.style.display = 'block'; Object.assign(rb.style, {left: Math.min(p.x, box.x) + 'px', top: Math.min(p.y, box.y) + 'px', width: Math.abs(p.x - box.x) + 'px', height: Math.abs(p.y - box.y) + 'px'}); }
    });
    const up = e => {
      pointers.delete(e.pointerId);
      if (M) { if (M.drag && e.pointerType !== 'mouse') endModal(true); else if (M.drag && e.button === 0) endModal(true); return; }
      ctl.enabled = true;
      if (stroke) { const m = stroke.m; stroke = null; m && persist(m); return; }
      if (annot) { if (annot.pts.length > 1) { pushScene(); D.annot.push(annot); save(); } annot = null; drawAnnot(); return; }
      if (ruler) { pushScene(); D.rulers = [ruler]; ruler = null; drawRulers(); placeOverlays(); save(); refreshPanels(); return; }
      if (box) { const p = local(e), moved = dn && Math.hypot(e.clientX - dn.x, e.clientY - dn.y) > 4; rb.style.display = 'none';
        if (moved) { boxSelect(box.x, box.y, p.x, p.y, e.shiftKey); box = null; dn = null; return; } box = null; }
      if (!dn || TCS.some(t => t.dragging || t.axis) || Math.hypot(e.clientX - dn.x, e.clientY - dn.y) > 6 || e.button === 2) { dn = null; return; }
      dn = null;
      if (tool === 'cursor') { const q = surfacePoint(e); if (q) { D.cursor = q.toArray(); save(); refreshPanels(); dirty = true; sfx.play('tick'); } return; }
      if (tool === 'add' && !ed) { const q = surfacePoint(e); if (q) addPrim(D.view.addKind || 'cube', q.toArray()); return; }
      if (ed) { editPick(e); return; }
      if (mode === 'paint') return;
      setRay(e); const h = rc.intersectObjects(selectables(), true).filter(x => !x.object.userData.outline)[0];
      if (!h) { choose(null); return; } let n = h.object; const s = selectables(); while (n && !s.includes(n)) n = n.parent; choose(n); sfx.play('tick');
    };
    cvs.addEventListener('pointerup', up); cvs.addEventListener('pointercancel', e => { pointers.delete(e.pointerId); stroke = annot = ruler = box = null; rb.style.display = 'none'; ctl.enabled = !M; });
    const viewPlane = () => new T.Plane().setFromNormalAndCoplanarPoint(cam.getWorldDirection(new T.Vector3()), ctl.target);

    /* ---- keyboard (Blender keymap) ---- */
    const kd = e => {
      if (!host.isConnected || /INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName)) return;
      const k = e.key.toLowerCase(), code = e.code;
      if (M) {
        if (k === 'escape') { e.preventDefault(); endModal(false); }
        else if (k === 'enter' || code === 'NumpadEnter') { e.preventDefault(); endModal(true); }
        else if ('xyz'.includes(k) && k.length === 1 && M.kind !== 'extrude' && M.kind !== 'inset') { M.axis = M.axis === k ? null : k; modalUpdate(); }
        else if (/^[0-9.]$/.test(e.key) || (e.key === '-' && !M.num)) { M.num += e.key; modalUpdate(); }
        else if (k === 'backspace') { M.num = M.num.slice(0, -1); modalUpdate(); }
        else if (k === 'g' || k === 'r' || k === 's') { /* switching operator mid-way isn't supported; ignore */ }
        e.stopPropagation(); return;
      }
      if (e.ctrlKey || e.metaKey) {
        if (k === 'z') { e.preventDefault(); e.shiftKey ? (redo(), sfx.play('redo')) : (undo(), sfx.play('undo')); }
        else if (k === 'y') { e.preventDefault(); redo(); sfx.play('redo'); }
        else if (k === 'i' && ed) { e.preventDefault(); A_.invert(); }
        return;
      }
      if (k === 'tab') { e.preventDefault(); sfx.play('toggle'); ed ? leaveEdit() : enterEdit(); return; }
      if (code === 'Numpad1' || code === 'Numpad3' || code === 'Numpad7') { e.preventDefault(); viewAxis(code === 'Numpad1' ? (e.ctrlKey ? 'back' : 'front') : code === 'Numpad3' ? 'right' : 'top'); return; }
      if (code === 'Numpad5') { e.preventDefault(); setOrtho(!isOrtho); return; }
      if (code === 'NumpadDecimal' || code === 'NumpadPeriod') { e.preventDefault(); frameSel(); return; }
      if (k === 'home') { e.preventDefault(); frameAll(); return; }
      if (k === ' ') { e.preventDefault(); togglePlay(); return; }
      if (k === 'n') { toggleSidebar(); return; }
      if (k === 't') { toggleToolbar(); return; }
      if (e.shiftKey && k === 'a') { e.preventDefault(); addMenuBtn.click(); return; }
      if (e.shiftKey && k === 'd') { e.preventDefault(); if (!ed) duplicateSel(); return; }
      if (k === 'g') startModal('grab'); else if (k === 'r') startModal('rotate'); else if (k === 's') startModal('scale');
      else if (k === 'escape') { ed ? A_.none() : choose(null); }
      else if (k === 'b') setTool('select');
      else if (ed) {
        if (k === '1') setEMode('v'); else if (k === '2') setEMode('e'); else if (k === '3') setEMode('f');
        else if (k === 'a') e.altKey ? A_.none() : A_.all(); else if (k === 'e') A_.extrude(); else if (k === 'i') A_.inset();
        else if (k === 'x' || k === 'delete') A_.del(); else if (k === 'm') A_.merge();
      }
      else if (k === 'x' || k === 'delete') removeSel();
      else if (k === 'a') { if (e.altKey) choose(null); else if (!selObj) choose(holder); }
    };
    document.addEventListener('keydown', kd, true); cleanup.push(() => document.removeEventListener('keydown', kd, true));

    /* ---- camera helpers (views, ortho, framing) ---- */
    function setOrtho(on) {
      if (on === isOrtho) return; const b = cvs.getBoundingClientRect(), dist = cam.position.distanceTo(ctl.target);
      if (on) { const h = 2 * dist * Math.tan(persp.fov / DEG / 2), a = (b.width || 1) / (b.height || 1); Object.assign(ortho, {left: -h * a / 2, right: h * a / 2, top: h / 2, bottom: -h / 2, near: -rad * 400, far: rad * 400, zoom: 1}); ortho.position.copy(persp.position); ortho.quaternion.copy(persp.quaternion); ortho.updateProjectionMatrix(); cam = ortho; }
      else { const h = (ortho.top - ortho.bottom) / ortho.zoom, d = h / 2 / Math.tan(persp.fov / DEG / 2), dir = ortho.position.clone().sub(ctl.target).normalize(); persp.position.copy(ctl.target).addScaledVector(dir, d); cam = persp; if (viewName !== 'User') viewName = 'User'; }
      isOrtho = on; ctl.object = cam; TCS.forEach(t => t.camera = cam); ctl.update(); info(); dirty = true;
    }
    const VIEWS = {front: [0, 0, 1], back: [0, 0, -1], right: [1, 0, 0], left: [-1, 0, 0], top: [0, 1, 0], bottom: [0, -1, 0]};
    function viewAxis(n) {
      const d = cam.position.distanceTo(ctl.target), v = new T.Vector3(...VIEWS[n]); setOrtho(true);
      cam.position.copy(ctl.target).addScaledVector(v, d); cam.up.set(0, 1, 0); if (n === 'top' || n === 'bottom') cam.up.set(0, 0, n === 'top' ? -1 : 1);
      cam.lookAt(ctl.target); ctl.update(); cam.up.set(0, 1, 0); viewName = n[0].toUpperCase() + n.slice(1); info(); dirty = true;
    }
    function frameBox(box3) {
      if (box3.isEmpty()) return; const c = box3.getCenter(new T.Vector3()), r0 = box3.getSize(new T.Vector3()).length() / 2 || rad, dir = cam.position.clone().sub(ctl.target).normalize();
      ctl.target.copy(c); if (isOrtho) { cam.zoom = (cam.top - cam.bottom) / (r0 * 2.4); cam.updateProjectionMatrix(); cam.position.copy(c).addScaledVector(dir, rad * 50); }
      else cam.position.copy(c).addScaledVector(dir, r0 / Math.sin(cam.fov / DEG / 2) * 1.1); ctl.update(); dirty = true;
    }
    function frameSel() {
      if (ed && ed.sel.size) { ed.g.updateMatrixWorld(true); const b = new T.Box3(); ed.sel.forEach(i => b.expandByPoint(new T.Vector3(...ed.em.v[i]).applyMatrix4(ed.g.matrixWorld))); if (b.getSize(new T.Vector3()).length() < 1e-6) b.expandByScalar(rad * .1); frameBox(b); }
      else if (selObj) frameBox(new T.Box3().setFromObject(selObj)); else frameAll();
    }
    function frameAll() { const b = new T.Box3(); selectables().forEach(s => b.expandByObject(s)); if (b.isEmpty()) b.setFromCenterAndSize(new T.Vector3(), new T.Vector3(rad, rad, rad)); frameBox(b); }
    function setLens(mm) { D.view.lens = mm; const b = cvs.getBoundingClientRect(), a = (b.width || 1) / (b.height || 1), hf = 2 * Math.atan(18 / mm); persp.fov = a >= 1 ? 2 * Math.atan(Math.tan(hf / 2) / a) * DEG : hf * DEG; persp.updateProjectionMatrix(); dirty = true; }

    /* ================= UI ================= */
    // header
    const hdr = el('div', 'bl-header'), modeSel = el('select', 'bl-mode'); modeSel.setAttribute('aria-label', 'Interaction mode');
    [['object', 'Object Mode'], ['edit', 'Edit Mode'], ['paint', 'Texture Paint']].forEach(([v, t]) => modeSel.append(new Option(t, v)));
    modeSel.onchange = () => { const m = modeSel.value; sfx.play('toggle'); if (m === 'edit') { if (!ed) enterEdit(); } else { if (ed) leaveEdit(); if (m === 'paint') enterPaint(); else if (mode === 'paint') leavePaint(); } syncMode(); };
    const selGroup = el('div', 'bl-seg'), selBtns = [['v', 'vert', 'Vertex select', '1'], ['e', 'edge', 'Edge select', '2'], ['f', 'face', 'Face select', '3']].map(([k, ic, l, key]) => { const b = tbtn(ic, l, key, () => setEMode(k)); b.dataset.k = k; selGroup.append(b); return b; });
    const viewMenu = menu('View', () => [['Sidebar', 'N', toggleSidebar], ['Toolbar', 'T', toggleToolbar], ['Timeline', '', toggleTimeline], '-', ['Frame Selected', 'Numpad .', frameSel], ['Frame All', 'Home', frameAll], '-',
      ['Front', 'Numpad 1', () => viewAxis('front')], ['Back', 'Ctrl Numpad 1', () => viewAxis('back')], ['Right', 'Numpad 3', () => viewAxis('right')], ['Left', '', () => viewAxis('left')], ['Top', 'Numpad 7', () => viewAxis('top')], ['Bottom', '', () => viewAxis('bottom')], '-',
      [isOrtho ? 'Perspective' : 'Orthographic', 'Numpad 5', () => setOrtho(!isOrtho)]]);
    const selMenu = menu('Select', () => ed ? [['All', 'A', A_.all], ['None', 'Alt A', A_.none], ['Invert', 'Ctrl I', A_.invert], '-', ['Box Select', 'B', () => setTool('select')]]
      : [['All', 'A', () => choose(holder)], ['None', 'Alt A', () => choose(null)], ['Main Model', '', () => choose(holder)], ...props.map(x => [nameOf(x.g), '', () => choose(x.g)])]);
    const addItems = () => [...Object.keys(EP).map(k => [PRIM_NAMES[k], '', () => { if (ed) leaveEdit(); addPrim(k); }]), '-', ['Model from Library…', '', () => { showTab('Scene'); }]];
    const addMenuBtn = menu('Add', addItems);
    const objMenu = menu('Object', () => ed ? [['Extrude Region', 'E', A_.extrude], ['Inset Faces', 'I', A_.inset], ['Subdivide', '', A_.subdiv], ['Merge at Center', 'M', A_.merge], ['Flip Normals', '', A_.flip], '-', ['Delete', 'X', A_.del]]
      : [['Move', 'G', () => startModal('grab')], ['Rotate', 'R', () => startModal('rotate')], ['Scale', 'S', () => startModal('scale')], '-', ['Duplicate', 'Shift D', () => duplicateSel()], ['Delete', 'X', removeSel], '-',
        ['Export .glb to Library', '', () => exportGlb(false)], ['Save Meshes as Asset Model', '', () => exportGlb(true)]]);
    const spacer = el('div', 'grow');
    uBtn = tbtn('undo', 'Undo', 'Ctrl Z', () => { undo(); sfx.play('undo'); }); uBtn.firstChild.replaceWith(icon('undo', 16));
    rBtn = tbtn('redo', 'Redo', 'Ctrl Shift Z', () => { redo(); sfx.play('redo'); }); rBtn.firstChild.replaceWith(icon('redo', 16));
    [uBtn, rBtn].forEach(b => b.dataset.sfx = 'none'); updHist();
    const gridBtn = tbtn('grid', 'Floor grid', '', () => { D.grid = !D.grid; grid.visible = axes.visible = D.grid; gridBtn.classList.toggle('on', D.grid); save(); dirty = true; refreshPanels(); }); gridBtn.classList.toggle('on', D.grid);
    const shadeGroup = el('div', 'bl-seg'); shadeBtns = [['wire', 'shWire', 'Wireframe'], ['solid', 'shSolid', 'Solid'], ['material', 'shMaterial', 'Material Preview'], ['rendered', 'shRender', 'Rendered']].map(([m, ic, l]) => { const b = tbtn(ic, l, 'Z', () => shade(m)); b.dataset.m = m; b.dataset.sfx = 'toggle'; shadeGroup.append(b); return b; });
    const tlBtn = tbtn('timeline', 'Timeline (animation)', '', toggleTimeline), sbBtn = tbtn('sidebar', 'Sidebar', 'N', toggleSidebar);
    hdr.append(modeSel, selGroup, viewMenu, selMenu, addMenuBtn, objMenu, spacer, uBtn, rBtn, gridBtn, shadeGroup, tlBtn, sbBtn);
    // overlay text
    const infoEl = el('div', 'bl-info');
    function info() {
      const frame = Math.floor(time * 24) + 1, nm = ed ? nameOf(ed.g) : nameOf(selObj);
      infoEl.textContent = ''; infoEl.append(el('div', 0, `${viewName} ${isOrtho ? 'Orthographic' : 'Perspective'}`), el('div', 0, `(${frame}) Scene${nm ? ' | ' + nm : ''}`));
      if (ed) infoEl.append(el('div', 'dim', `Vertices ${ed.sel.size}/${ed.em.v.length} · Faces ${faceSel(ed.em, ed.sel).length}/${ed.em.f.length}`));
      else if (!url && !props.length) infoEl.append(el('div', 'dim', 'Empty scene: Add ▸ Cube, then Tab to edit it'));
      else if (url && !selObj) infoEl.append(el('div', 'dim', `${meshes.length} meshes · ${Math.round(tris).toLocaleString()} tris`));
    }
    let sayT; function say(t) { clearTimeout(sayT); status.textContent = t; status.classList.add('show'); sayT = setTimeout(() => { if (!M) { status.textContent = ''; status.classList.remove('show'); } }, 4000); }

    // toolbar
    const tbar = el('div', 'bl-tools');
    const TOOLS = {
      object: [['select', 'selectBox', 'Select Box', 'B'], ['cursor', 'cursor', 'Cursor', ''], '-', ['move', 'move', 'Move', 'G'], ['rotate', 'rotate', 'Rotate', 'R'], ['scale', 'scale', 'Scale', 'S'], ['transform', 'transform', 'Transform', ''], '-', ['annotate', 'annotate', 'Annotate', ''], ['measure', 'measure', 'Measure', ''], '-', ['add', 'addCube', 'Add Cube (tap to place)', '']],
      edit: [['select', 'selectBox', 'Select Box', 'B'], ['cursor', 'cursor', 'Cursor', ''], '-', ['move', 'move', 'Move', 'G'], ['rotate', 'rotate', 'Rotate', 'R'], ['scale', 'scale', 'Scale', 'S'], ['transform', 'transform', 'Transform', ''], '-', ['annotate', 'annotate', 'Annotate', ''], ['measure', 'measure', 'Measure', ''], '-', ['extrude', 'extrude', 'Extrude Region (drag)', 'E'], ['inset', 'inset', 'Inset Faces (drag)', 'I']],
      paint: [['draw', 'draw', 'Draw', ''], ['erase', 'erase', 'Erase', ''], '-', ['annotate', 'annotate', 'Annotate', '']],
    };
    function buildTools() {
      tbar.textContent = ''; let grp = el('div', 'grp'); tbar.append(grp);
      TOOLS[mode].forEach(t => { if (t === '-') { grp = el('div', 'grp'); tbar.append(grp); return; } const [k, ic, l, key] = t, b = tbtn(ic, l, key, () => setTool(k)); b.dataset.t = k; b.dataset.sfx = 'tick'; b.classList.toggle('on', tool === k); grp.append(b); });
    }
    function setTool(t) {
      if (M) endModal(true); tool = t; tbar.querySelectorAll('button[data-t]').forEach(b => b.classList.toggle('on', b.dataset.t === t));
      const consume = ['annotate', 'measure'].includes(t) || mode === 'paint' || (ed && ['select', 'extrude', 'inset'].includes(t));
      ctl.touches.ONE = consume ? null : T.TOUCH.ROTATE; // one finger orbits unless the tool needs it; two fingers always pan/zoom
      attachGizmos(); refreshPanels(); dirty = true;
    }

    // navigation gizmo + zoom/pan/camera buttons (drag them, like Blender's)
    const nav = el('div', 'bl-nav'), navC = el('canvas'); navC.width = navC.height = 160; navC.setAttribute('aria-label', 'Navigation gizmo: drag to orbit, tap an axis to look along it'); nav.append(navC);
    const navBtns = el('div', 'bl-navbtns'), zoomB = tbtn('zoom', 'Zoom (drag up/down)', ''), panB = tbtn('pan', 'Pan (drag)', ''), camB = tbtn('camera', 'Toggle perspective / orthographic', 'Numpad 5', () => setOrtho(!isOrtho)), fitB = tbtn('fit', 'Frame selected', 'Numpad .', frameSel);
    fitB.firstChild.replaceWith(icon('fit', 18)); navBtns.append(zoomB, panB, camB, fitB); nav.append(navBtns);
    let navHit = [];
    function drawNav() {
      const g = navC.getContext('2d'), c = 80, R = 56; g.clearRect(0, 0, 160, 160);
      const q = cam.quaternion.clone().invert(), list = [['X', 1, 0, 0], ['Y', 0, 0, -1], ['Z', 0, 1, 0], ['-X', -1, 0, 0], ['-Y', 0, 0, 1], ['-Z', 0, -1, 0]].map(([n, x, y, z]) => { const v = new T.Vector3(x, y, z).applyQuaternion(q); return {n, v, x: c + v.x * R, y: c - v.y * R}; });
      list.sort((a, b) => a.v.z - b.v.z); navHit = list;
      const colOf = n => AXCOL[n.replace('-', '').toLowerCase()];
      list.forEach(a => { const neg = a.n[0] === '-', col = colOf(a.n); if (!neg) { g.strokeStyle = col; g.lineWidth = 4; g.beginPath(); g.moveTo(c, c); g.lineTo(a.x, a.y); g.stroke(); }
        g.beginPath(); g.arc(a.x, a.y, neg ? 13 : 16, 0, 7); g.fillStyle = neg ? col + '66' : col; g.fill(); if (neg) { g.strokeStyle = col; g.lineWidth = 2; g.stroke(); }
        if (!neg) { g.fillStyle = '#000'; g.font = 'bold 18px sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(a.n, a.x, a.y + 1); } });
    }
    function orbitBy(dx, dy) { const off = cam.position.clone().sub(ctl.target), s = new T.Spherical().setFromVector3(off); s.theta -= dx * .012; s.phi = Math.max(.01, Math.min(Math.PI - .01, s.phi - dy * .012)); off.setFromSpherical(s); cam.position.copy(ctl.target).add(off); cam.lookAt(ctl.target); viewName = 'User'; ctl.update(); info(); dirty = true; }
    function dragOn(elm, fn, tap) {
      elm.addEventListener('pointerdown', e => { e.preventDefault(); e.stopPropagation(); elm.setPointerCapture(e.pointerId); let lx = e.clientX, ly = e.clientY, moved = 0; const sx = e.clientX, sy = e.clientY;
        const mv = ev => { fn(ev.clientX - lx, ev.clientY - ly); moved = Math.max(moved, Math.hypot(ev.clientX - sx, ev.clientY - sy)); lx = ev.clientX; ly = ev.clientY; };
        const upf = ev => { elm.removeEventListener('pointermove', mv); elm.removeEventListener('pointerup', upf); if (moved < 4 && tap) tap(ev); };
        elm.addEventListener('pointermove', mv); elm.addEventListener('pointerup', upf); });
    }
    dragOn(navC, orbitBy, e => { const b = navC.getBoundingClientRect(), x = (e.clientX - b.left) * 160 / b.width, y = (e.clientY - b.top) * 160 / b.height;
      const hit = [...navHit].reverse().find(a => Math.hypot(a.x - x, a.y - y) < 18); if (!hit) return;
      viewAxis({X: 'right', '-X': 'left', Z: 'top', '-Z': 'bottom', '-Y': 'front', Y: 'back'}[hit.n]); sfx.play('tick'); });
    dragOn(zoomB, (dx, dy) => { if (isOrtho) { cam.zoom = Math.max(.01, cam.zoom * Math.pow(1.01, -dy)); cam.updateProjectionMatrix(); } else { const off = cam.position.clone().sub(ctl.target).multiplyScalar(Math.pow(1.01, dy)); cam.position.copy(ctl.target).add(off); } ctl.update(); dirty = true; });
    dragOn(panB, (dx, dy) => { const wpp = worldPerPixel(ctl.target), right = new T.Vector3().setFromMatrixColumn(cam.matrix, 0), upv = new T.Vector3().setFromMatrixColumn(cam.matrix, 1), d = right.multiplyScalar(-dx * wpp).add(upv.multiplyScalar(dy * wpp)); cam.position.add(d); ctl.target.add(d); ctl.update(); dirty = true; });

    // modal bar (touch: axis lock, confirm, cancel)
    const modalTxt = el('span', 'txt'), modalAxes = ['x', 'y', 'z'].map(a => { const b = el('button', 'ax ax-' + a, a.toUpperCase()); b.type = 'button'; b.dataset.a = a; b.title = `Lock to ${a.toUpperCase()} axis`; b.onclick = () => { if (M) { M.axis = M.axis === a ? null : a; modalUpdate(); } }; return b; });
    const okB = tbtn('confirm', 'Confirm', 'Enter', () => endModal(true)), noB = tbtn('cancel', 'Cancel', 'Esc', () => endModal(false)); okB.classList.add('ok');
    modalBar.append(modalTxt, ...modalAxes, noB, okB);

    // sidebar (N panel)
    const side = el('div', 'bl-side'), sideBody = el('div', 'bl-sbody'), sideTabs = el('div', 'bl-stabs'); side.append(sideBody, sideTabs);
    const TABS = ['Item', 'Tool', 'View', 'Material', 'Scene'];
    TABS.forEach(n => { const b = el('button', 0, n); b.type = 'button'; b.onclick = () => showTab(n); b.dataset.tab = n; sideTabs.append(b); });
    function showTab(n) { D.view.tab = n; if (!D.view.sidebar) toggleSidebar(); sideTabs.querySelectorAll('button').forEach(b => b.classList.toggle('on', b.dataset.tab === n)); refreshPanels(); }
    let live = [];
    function itemLive() { live.forEach(f => f.update && f.update()); info(); }
    function refreshPanels() {
      if (!sideBody) return; sideBody.textContent = ''; live = []; const tab = D.view.tab;
      sideTabs.querySelectorAll('button').forEach(b => b.classList.toggle('on', b.dataset.tab === tab));
      const add = (p, ...ws) => { p.body.append(...ws); live.push(...ws); };
      if (tab === 'Item') {
        if (ed) {
          const p = panel('Transform'); const glob = D.view.medianGlobal;
          const med = () => { if (!ed || !ed.sel.size) return [0, 0, 0]; const c = cen(ed.em, [...ed.sel]); if (!glob) return toB(c); const v = new T.Vector3(...c).applyMatrix4(ed.g.matrixWorld); return toB(v.toArray()); };
          let base = null;
          const setMed = v => { if (!ed || !ed.sel.size) return; const now = med(); const d = new T.Vector3(...fromB([v[0] - now[0], v[1] - now[1], v[2] - now[2]])); if (glob) d.applyMatrix3(new T.Matrix3().setFromMatrix4(ed.g.matrixWorld.clone().invert()));
            ed.sel.forEach(i => { const q = ed.em.v[i]; ed.em.v[i] = [q[0] + d.x, q[1] + d.y, q[2] + d.z]; }); refreshEdit(); save(); };
          add(p, vec3(ed.sel.size === 1 ? 'Vertex' : 'Median', med, setMed, {step: .05, unit: ' m', begin: () => { if (!base) { pushScene(); base = 1; } }, end: () => { base = null; }}));
          const seg = el('div', 'bl-seg2'); [['Local', false], ['Global', true]].forEach(([l, g]) => { const b = button(l, () => { D.view.medianGlobal = g; refreshPanels(); }); b.classList.toggle('on', !!glob === g); seg.append(b); }); p.body.append(seg);
          if (!ed.sel.size) p.body.append(el('p', 'bl-hint', 'Nothing selected. Tap a vertex, edge or face, or press A.'));
          sideBody.append(p.el);
        } else if (selObj) {
          const P = props.find(x => x.g === selObj), o3 = selObj, p = panel('Transform');
          let pushed = false; const begin = () => { if (!pushed) { pushScene(); pushed = true; } }, end = () => { pushed = false; commit(); };
          const f = {begin, end};
          if (P) { const nm = el('input', 'bl-name'); nm.value = nameOf(o3); nm.setAttribute('aria-label', 'Object name'); nm.onchange = () => { P.p.n = nm.value.trim() || PRIM_NAMES[P.p.prim] || 'Object'; save(); info(); }; p.body.append(nm); }
          add(p, vec3('Location', () => toB(o3.position.toArray()), v => { o3.position.fromArray(fromB(v)); commit(); dirty = true; }, {...f, step: .05, unit: ' m'}));
          add(p, vec3('Rotation', () => rotToB(o3.quaternion), v => { o3.quaternion.copy(rotFromB(v)); commit(); dirty = true; }, {...f, step: 5, dec: 1, unit: '°'}));
          add(p, vec3('Scale', () => swapB(o3.scale.toArray()), v => { o3.scale.fromArray(swapB(v)); commit(); dirty = true; }, {...f, step: .05}));
          const dims = () => { const s = o3.scale.clone(); o3.scale.set(1, 1, 1); o3.updateMatrixWorld(true); const b = new T.Box3().setFromObject(o3); o3.scale.copy(s); o3.updateMatrixWorld(true); const z = b.getSize(new T.Vector3()); return swapB([z.x * s.x, z.y * s.y, z.z * s.z]); };
          add(p, vec3('Dimensions', dims, v => { const d = swapB(dims()), w = swapB(v), s = o3.scale.toArray(); o3.scale.fromArray(s.map((x, i) => d[i] > 1e-9 ? x * w[i] / d[i] : x)); commit(); dirty = true; }, {...f, step: .05, unit: ' m', min: 0}));
          sideBody.append(p.el);
          if (P && P.p.k !== 'glb') { const c = panel('Colour'); c.body.append(colour('Base colour', () => P.p.c || '#bfbfbf', v => { P.p.c = v; P.ms[0].userData.m.color.set(v); dirty = true; save(); }, pushScene)); sideBody.append(c.el); }
        } else { const p = panel('Transform'); p.body.append(el('p', 'bl-hint', 'Nothing selected. Tap an object to see its location, rotation and scale.')); sideBody.append(p.el); }
      }
      if (tab === 'Tool') {
        const names = {select: 'Select Box', cursor: 'Cursor', move: 'Move', rotate: 'Rotate', scale: 'Scale', transform: 'Transform', annotate: 'Annotate', measure: 'Measure', add: 'Add Cube', extrude: 'Extrude Region', inset: 'Inset Faces', draw: 'Draw', erase: 'Erase'};
        const p = panel('Active Tool'); p.body.append(el('div', 'bl-toolname', names[tool] || tool)); sideBody.append(p.el);
        if (['move', 'rotate', 'scale', 'transform'].includes(tool)) {
          p.body.append(choice('Orientation', [['world', 'Global'], ['local', 'Local']], () => D.view.space, v => { D.view.space = v; attachGizmos(); save(); }));
          p.body.append(check('Snap', () => D.view.snap, v => { D.view.snap = v; attachGizmos(); save(); refreshPanels(); }));
          if (D.view.snap) { p.body.append(num('Move step', () => D.view.snapMove || .1, v => { D.view.snapMove = v; attachGizmos(); save(); }, {step: .05, min: .001, unit: ' m'}), num('Rotate step', () => D.view.snapRot || 15, v => { D.view.snapRot = v; attachGizmos(); save(); }, {step: 5, min: 1, dec: 0, unit: '°'})); }
          p.body.append(el('p', 'bl-hint', 'Drag the handles, or press G, R or S to move, rotate or scale with the mouse.'));
        } else if (tool === 'cursor') {
          p.body.append(vec3('Location', () => toB(D.cursor), v => { D.cursor = fromB(v); save(); dirty = true; }, {step: .1, unit: ' m'}), button('Reset to world origin', () => { D.cursor = [0, 0, 0]; save(); refreshPanels(); dirty = true; }));
          p.body.append(el('p', 'bl-hint', 'Tap a surface to place the 3D cursor. New objects are added there.'));
        } else if (tool === 'annotate') {
          p.body.append(colour('Colour', () => D.view.annotColor, v => { D.view.annotColor = v; save(); }), button('Clear annotations', () => { pushScene(); D.annot = []; drawAnnot(); save(); }));
          p.body.append(el('p', 'bl-hint', 'Draw on the model or in the air to leave notes.'));
        } else if (tool === 'measure') {
          const u = D.rulers[0]; p.body.append(el('div', 'bl-big', u ? fmtLen(new T.Vector3(...u.a).distanceTo(new T.Vector3(...u.b))) : '—'), button('Clear ruler', () => { pushScene(); D.rulers = []; drawRulers(); placeOverlays(); save(); refreshPanels(); }));
          p.body.append(el('p', 'bl-hint', 'Drag from one point to another to measure.'));
        } else if (tool === 'add') {
          p.body.append(choice('Shape', Object.keys(EP).map(k => [k, PRIM_NAMES[k]]), () => D.view.addKind || 'cube', v => { D.view.addKind = v; save(); }));
          p.body.append(el('p', 'bl-hint', 'Tap the floor or a surface to place it.'));
        } else if (tool === 'extrude' || tool === 'inset') {
          p.body.append(el('p', 'bl-hint', tool === 'extrude' ? 'Select faces, then drag anywhere to pull them out. Release to finish; change the amount in Adjust Last Operation.' : 'Select faces, then drag towards their middle to inset. Change thickness and depth afterwards in Adjust Last Operation.'));
        } else if (tool === 'draw' || tool === 'erase') {
          const b = panel('Brush'); [colour('Colour', () => D.brush.color, v => { D.brush.color = v; save(); }), num('Radius', () => D.brush.size, v => { D.brush.size = v; save(); }, {step: 2, min: 2, max: 400, dec: 0, unit: ' px'}),
            num('Strength', () => D.brush.op, v => { D.brush.op = v; save(); }, {step: .05, min: .05, max: 1, dec: 2}), num('Hardness', () => D.brush.hard, v => { D.brush.hard = v; save(); }, {step: .05, min: 0, max: .95, dec: 2})].forEach(w => b.body.append(w));
          b.body.append(button('Fill with colour', () => { meshes.forEach(m => { const pc = initPC(m); pc.g.fillStyle = D.brush.color; pc.g.fillRect(0, 0, 1024, 1024); pc.tex.needsUpdate = true; persist(m); }); shade('material'); }), button('Clear paint', () => { meshes.forEach(m => { if (m.userData.pc) { m.userData.pc.tex.dispose(); m.userData.pc = null; } }); D.paint = {}; save(); shade(cur); }));
          sideBody.append(b.el); p.body.append(el('p', 'bl-hint', 'Drag on the model to paint. Two fingers (or middle mouse) move the view.'));
        } else p.body.append(el('p', 'bl-hint', ed ? 'Tap to select, drag to box select. Hold Shift to add.' : 'Tap an object to select it. Drag to orbit, two fingers to pan and zoom.'));
      }
      if (tab === 'View') {
        const p = panel('View'); p.body.append(num('Focal Length', () => D.view.lens, v => { setLens(v); save(); }, {step: 5, min: 4, max: 300, dec: 0, unit: ' mm'}),
          num('Clip Start', () => persp.near, v => { persp.near = v; persp.updateProjectionMatrix(); dirty = true; }, {step: .01, min: .0001, unit: ' m'}),
          num('Clip End', () => persp.far, v => { persp.far = v; persp.updateProjectionMatrix(); dirty = true; }, {step: 10, min: 1, unit: ' m'}));
        const vw = el('div', 'bl-grid3'); ['front', 'back', 'right', 'left', 'top', 'bottom'].forEach(n => vw.append(button(n[0].toUpperCase() + n.slice(1), () => viewAxis(n))));
        p.body.append(vw, button(isOrtho ? 'Switch to Perspective' : 'Switch to Orthographic', () => { setOrtho(!isOrtho); refreshPanels(); }), button('Frame Selected', frameSel), button('Frame All', frameAll)); sideBody.append(p.el);
        const g = panel('Overlays'); g.body.append(check('Floor grid', () => D.grid, v => { D.grid = v; grid.visible = axes.visible = v; gridBtn.classList.toggle('on', v); dirty = true; save(); }),
          num('Grid size', () => D.gridSize, v => { D.gridSize = v; makeGrid(); save(); }, {step: 2, min: 4, max: 100, dec: 0})); sideBody.append(g.el);
      }
      if (tab === 'Material') {
        const p = panel('Texture'), ts = el('select'); ts.setAttribute('aria-label', 'Texture');
        const fill = async () => { const fs = (await o.files()).filter(f => f.kind === 'Textures'); ts.textContent = ''; ts.append(new Option('Original materials', '')); fs.forEach(f => ts.append(new Option(f.path.split('/').pop(), o.fu(f.path)))); if (D.tx.src && ![...ts.options].some(x => x.value === D.tx.src)) ts.append(new Option(D.tx.src.split('/').pop(), D.tx.src)); ts.value = D.tx.src; };
        fill(); ts.onchange = () => { D.tx.src = ts.value; loadTex(); };
        const uf = el('input'); uf.type = 'file'; uf.accept = 'image/*'; uf.hidden = true; uf.onchange = async () => { D.tx.src = await o.up(uf.files[0], 'textures'); await fill(); loadTex(); };
        p.body.append(ts, button('Upload texture…', () => uf.click()), uf,
          num('Tiling', () => D.tx.rep, v => { D.tx.rep = v; updTex(); }, {step: .25, min: .25, max: 20, dec: 2}), num('Rotation', () => D.tx.rot, v => { D.tx.rot = v; updTex(); }, {step: 5, min: 0, max: 360, dec: 0, unit: '°'}),
          num('Roughness', () => D.tx.rough, v => { D.tx.rough = v; updTex(); }, {step: .05, min: 0, max: 1, dec: 2}), num('Metallic', () => D.tx.metal, v => { D.tx.metal = v; updTex(); }, {step: .05, min: 0, max: 1, dec: 2}),
          colour('Tint', () => D.tx.tint, v => { D.tx.tint = v; updTex(); }), el('p', 'bl-hint', 'For imported models with UVs. Shows in Material Preview and Rendered.'));
        sideBody.append(p.el);
      }
      if (tab === 'Scene') {
        const w = panel('World'), bs = choice('Background', [['default', 'Default'], ['color', 'Solid colour'], ['gradient', 'Gradient'], ['image', 'Image backdrop'], ['hdri', '360° environment image']], () => D.bg.t, v => { D.bg.t = v; save(); loadHdri(); });
        const bf = el('input'); bf.type = 'file'; bf.accept = 'image/*'; bf.hidden = true; bf.onchange = async () => { D.bg.img = await o.up(bf.files[0], 'images'); if (['default', 'color', 'gradient'].includes(D.bg.t)) D.bg.t = 'image'; save(); loadHdri(); refreshPanels(); };
        w.body.append(bs, colour('Colour', () => D.bg.c, v => { D.bg.c = v; save(); setBg(); }), colour('Gradient top', () => D.bg.c2, v => { D.bg.c2 = v; save(); setBg(); }), button('Upload background image…', () => bf.click()), bf); sideBody.append(w.el);
        const m = panel('Add Model from Library'), ms = el('select'); ms.setAttribute('aria-label', 'Model');
        o.files().then(fs => { fs.filter(f => /\.(glb|gltf)$/i.test(f.path)).forEach(f => ms.append(new Option(f.path.split('/').pop(), o.fu(f.path)))); if (!ms.options.length) ms.append(new Option('No models in the library', '')); });
        m.body.append(ms, button('Add at 3D cursor', () => ms.value && addModelProp(ms.value))); sideBody.append(m.el);
        const x = panel('Export'); x.body.append(button('Export meshes to Library (.glb)', () => exportGlb(false)), button('Save meshes as the asset model', () => exportGlb(true)), el('p', 'bl-hint', 'Saving as the asset model bakes your meshes into the model and removes them from the scene (Undo brings them back).')); sideBody.append(x.el);
      }
    }
    function toggleSidebar() { D.view.sidebar = !D.view.sidebar; side.classList.toggle('hidden', !D.view.sidebar); sbBtn.classList.toggle('on', D.view.sidebar); host.classList.toggle('no-side', !D.view.sidebar); save(); }
    function toggleToolbar() { D.view.toolbar = !D.view.toolbar; tbar.classList.toggle('hidden', !D.view.toolbar); save(); }
    function toggleTimeline() { D.view.timeline = !D.view.timeline; dock.classList.toggle('hidden', !D.view.timeline); tlBtn.classList.toggle('on', D.view.timeline); host.classList.toggle('with-tl', D.view.timeline); save(); }
    function syncMode() { modeSel.value = mode; selGroup.classList.toggle('hidden', !ed); host.dataset.mode = mode; if (ed) selBtns.forEach(b => b.classList.toggle('on', b.dataset.k === ed.mode)); buildTools(); setTool(TOOLS[mode].some(t => t[0] === tool) ? tool : TOOLS[mode][0][0]); info(); }

    /* ---- animation timeline ---- */
    const dock = el('div', 'dock bl-timeline');
    let playing = false, speed = 1, mixer = null, clipAct = null; const A = D.anim;
    const pb = el('button'), sb = el('button'), sl = el('input'), tl = el('div', 'tl'), tlab = el('span', 0, '0.00s'), dur = el('input'), lp = el('input'), sp = el('select');
    sl.type = 'range'; sl.min = 0; sl.max = A.dur; sl.step = .01; sl.value = 0; sl.setAttribute('aria-label', 'Time'); dur.type = 'number'; dur.min = .5; dur.step = .5; dur.value = A.dur; dur.setAttribute('aria-label', 'Length in seconds');
    lp.type = 'checkbox'; lp.checked = A.loop; [.25, .5, 1, 2].forEach(v => sp.append(new Option(v + 'x', v))); sp.value = 1; sp.setAttribute('aria-label', 'Playback speed');
    const setPB = on => { pb.textContent = ''; pb.append(icon(on ? 'pause' : 'play', 15)); pb.title = on ? 'Pause (Space)' : 'Play (Space)'; }; setPB(false); sb.append(icon('stop', 15)); sb.title = 'Stop';
    tl.append(sl); const objs = () => ({model: holder, ...Object.fromEntries(props.map(x => [x.p.id, x.g]))});
    const idOf = () => { const x = props.find(q => q.g === selObj); return x ? x.p.id : 'model'; };
    function evalAt(ks, t) {
      ks = [...ks].sort((a, b) => a.t - b.t); if (t <= ks[0].t) return ks[0]; const l = ks[ks.length - 1]; if (t >= l.t) return l; let i = 0; while (ks[i + 1].t < t) i++;
      const a = ks[i], b = ks[i + 1], f = (t - a.t) / (b.t - a.t), lp_ = (x, y) => x.map((v, j) => v + (y[j] - v) * f);
      const q = new T.Quaternion().setFromEuler(new T.Euler(a.r[0], a.r[1], a.r[2])).slerp(new T.Quaternion().setFromEuler(new T.Euler(b.r[0], b.r[1], b.r[2])), f), e = new T.Euler().setFromQuaternion(q);
      return {p: lp_(a.p, b.p), s: lp_(a.s, b.s), r: [e.x, e.y, e.z]};
    }
    const applyAnim = t => { const O = objs(); for (const [id, ks] of Object.entries(A.keys)) if (ks.length && O[id]) setT(O[id], evalAt(ks, t)); dirty = true; };
    function markers() { tl.querySelectorAll('.mk').forEach(m => m.remove()); (A.keys[idOf()] || []).forEach(k => { const m = el('i', 'mk'); m.style.left = (k.t / A.dur * 100) + '%'; m.title = k.t.toFixed(2) + 's'; m.onclick = () => { time = k.t; sl.value = time; applyAnim(time); lbl(); }; tl.append(m); }); }
    const lbl = () => { tlab.textContent = `${time.toFixed(2)}s / ${A.dur}s`; info(); };
    function togglePlay() { if (!playing && time >= A.dur) time = 0; playing = !playing; setPB(playing); }
    sl.oninput = () => { time = +sl.value; playing = false; setPB(false); applyAnim(time); lbl(); };
    pb.onclick = togglePlay; sb.onclick = () => { playing = false; setPB(false); time = 0; sl.value = 0; applyAnim(0); lbl(); };
    dur.onchange = () => { A.dur = Math.max(.5, +dur.value || 4); sl.max = A.dur; save(); markers(); lbl(); }; lp.onchange = () => { A.loop = lp.checked; save(); }; sp.onchange = () => speed = +sp.value;
    const lpl = el('label', 'bl-check'); lpl.append(lp, el('span', 0, 'Loop'));
    dock.append(row(pb, sb, tl, tlab, el('span', 0, 'Length'), dur, lpl, sp));
    const kb = ib('keyframe', 'Insert Keyframe'), kd2 = ib('trash', 'Delete Keyframe'), kc = ib('close', 'Clear Keys'); kb.dataset.sfx = 'key';
    kb.onclick = () => { pushScene(); const id = idOf(), ob = objs()[id], ks = A.keys[id] = (A.keys[id] || []).filter(k => Math.abs(k.t - time) > .02); ks.push({t: +time.toFixed(2), ...getT(ob)}); save(); markers(); };
    kd2.onclick = () => { pushScene(); const id = idOf(); A.keys[id] = (A.keys[id] || []).filter(k => Math.abs(k.t - time) > .1); save(); markers(); }; kc.onclick = () => { pushScene(); delete A.keys[idOf()]; save(); markers(); };
    const cs = el('select'), cb = ib('play', 'Clip'); cs.setAttribute('aria-label', 'Animation clip');
    gltf.animations.forEach((c, i) => cs.append(new Option(c.name || 'Clip ' + (i + 1), i))); if (!gltf.animations.length) { cs.append(new Option('No clips in model', '')); cs.disabled = cb.disabled = true; } else mixer = new T.AnimationMixer(root);
    cb.onclick = () => { if (clipAct) { mixer.stopAllAction(); clipAct = null; cb.replaceChildren(icon('play', 15), el('span', 0, 'Clip')); } else { clipAct = mixer.clipAction(gltf.animations[+cs.value]); clipAct.reset().play(); cb.replaceChildren(icon('pause', 15), el('span', 0, 'Clip')); } };
    dock.append(row(kb, kd2, kc, el('span', 'hint', 'Pose the selected object, scrub the time, insert a keyframe.'), cs, cb));

    host.append(hdr, infoEl, tbar, nav, side, redoEl, modalBar, dock);
    side.classList.toggle('hidden', !D.view.sidebar); sbBtn.classList.toggle('on', D.view.sidebar); host.classList.toggle('no-side', !D.view.sidebar);
    tbar.classList.toggle('hidden', !D.view.toolbar); dock.classList.toggle('hidden', !D.view.timeline); tlBtn.classList.toggle('on', D.view.timeline); host.classList.toggle('with-tl', D.view.timeline);
    if (Object.keys(A.keys).length) applyAnim(0); lbl(); markers(); cvs.addEventListener('pointerup', () => setTimeout(markers, 0));

    /* ---- go ---- */
    await loadHdri(); shade(D.tx.src || Object.keys(D.paint).length || props.length ? 'material' : 'solid'); drawAnnot(); drawRulers();
    syncMode(); setTool('select'); showTab(D.view.tab); info();
    const clock = new T.Clock();
    const loop = () => {
      if (!alive) return; const dt = clock.getDelta();
      if (playing) { time += dt * speed; if (time >= A.dur) { if (A.loop) time %= A.dur; else { time = A.dur; playing = false; setPB(false); } } applyAnim(time); sl.value = time; lbl(); }
      if (clipAct) { mixer.update(dt * speed); dirty = true; }
      if (dirty) { dirty = false; const b = cvs.getBoundingClientRect(); outMats.line.uniforms.res.value.set(b.width || 1, b.height || 1); outMats.line.uniforms.width.value = 2; r.render(sc, cam); drawNav(); placeOverlays(); }
      requestAnimationFrame(loop);
    };
    loop();
    const ro = new ResizeObserver(() => {
      const w = host.clientWidth, h = host.clientHeight; if (!w || !h) return; r.setSize(w, h, false); persp.aspect = w / h; setLens(D.view.lens);
      if (isOrtho) { const hh = ortho.top - ortho.bottom; ortho.left = -hh * w / h / 2; ortho.right = hh * w / h / 2; ortho.updateProjectionMatrix(); } dirty = true;
    });
    ro.observe(host);
    ctl.addEventListener('change', () => { dirty = true; if (viewName !== 'User' && !M) { viewName = 'User'; info(); } });
    // test hook: lets automated checks drive the viewer without a mouse
    host.__viewer = {get mode() { return mode; }, get tool() { return tool; }, get ed() { return ed; }, get selObj() { return selObj; }, props, startModal, endModal, setTool, enterEdit, leaveEdit, addPrim, choose, setEMode, A_,
      modal: () => M, setModalPointer: p => { if (M) { if (!M.ref) M.ref = p; M.cur = p; modalUpdate(); } }, outlined: () => outlined.length, lastOp: () => lastOp, D, cam: () => cam, toScreen, viewAxis, setOrtho};
    return () => { alive = false; ro.disconnect(); cleanup.forEach(f => f()); TCS.forEach(t => t.dispose()); ctl.dispose(); r.dispose(); host.textContent = ''; host.classList.remove('bl', 'no-side', 'with-tl', 'modal'); };
  } catch (e) {
    console.error(e); status.textContent = "Couldn't load the 3D viewer/model: " + e.message; status.classList.add('show');
    return () => { cleanup.forEach(f => f()); r && r.dispose(); };
  }
}
