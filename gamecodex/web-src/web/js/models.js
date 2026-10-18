/* 3D model viewer (glb, gltf, obj, stl, fbx) built on three.js r147, which ships with the app
   in vendor/three and loads only when a model is first opened. */
window.Codex = window.Codex || {};

Codex.Models = (function () {
  const { h, icon, ICONS } = Codex;
  const BASE = "vendor/three/";
  let ready = null;

  function script(src) {
    return new Promise((resolve, reject) => {
      const s = document.createElement("script");
      s.src = BASE + src;
      s.onload = resolve;
      s.onerror = () => reject(new Error("Couldn't load the 3D viewer."));
      document.head.appendChild(s);
    });
  }

  function load() {
    if (!ready) {
      ready = script("three.min.js")
        .then(() => script("fflate.min.js"))
        .then(() => Promise.all(["OrbitControls.js", "GLTFLoader.js", "OBJLoader.js", "STLLoader.js", "NURBSUtils.js"].map(script)))
        .then(() => script("NURBSCurve.js"))
        .then(() => script("FBXLoader.js"))
        .catch((e) => { ready = null; throw e; });
    }
    return ready;
  }

  function ext(id) { return String(id).split(".").pop().toLowerCase(); }

  // XMLHttpRequest rather than fetch(): the desktop window serves the app from local files,
  // and WebKit lets XHR read those but not fetch().
  function bytes(url) {
    return new Promise((resolve, reject) => {
      const x = new XMLHttpRequest();
      x.open("GET", url);
      x.responseType = "arraybuffer";
      x.onload = () => (x.response && x.response.byteLength ? resolve(x.response) : reject(new Error("the file is empty or missing")));
      x.onerror = () => reject(new Error("the file couldn't be read"));
      x.send();
    });
  }

  function loadModel(url, type) {
    const T = window.THREE;
    const fail = (e) => { throw new Error("This model couldn't be opened" + (e && e.message ? " (" + e.message + ")." : ".")); };
    return bytes(url).then((buf) => new Promise((resolve, reject) => {
      try {
        if (type === "glb" || type === "gltf") new T.GLTFLoader().parse(buf, "", (g) => resolve(g.scene), reject);
        else if (type === "obj") resolve(new T.OBJLoader().parse(new TextDecoder().decode(buf)));
        else if (type === "fbx") resolve(new T.FBXLoader().parse(buf, ""));
        else if (type === "stl") {
          const geo = new T.STLLoader().parse(buf);
          geo.computeVertexNormals();
          resolve(new T.Mesh(geo, new T.MeshStandardMaterial({ color: 0xb8c2d6, roughness: 0.55, metalness: 0.1 })));
        } else reject(new Error("Game Codex can't open ." + type + " models"));
      } catch (e) { reject(e); }
    })).then((obj) => {
      // OBJ files without materials come in flat white; give them a soft clay look.
      obj.traverse((o) => { if (o.isMesh && o.material && o.material.type === "MeshPhongMaterial" && !o.material.map) o.material = new T.MeshStandardMaterial({ color: 0xb8c2d6, roughness: 0.6 }); });
      // Show both sides of every face, so models exported with flipped faces don't have holes.
      obj.traverse((o) => { if (o.isMesh) (Array.isArray(o.material) ? o.material : [o.material]).forEach((m) => { if (m) m.side = T.DoubleSide; }); });
      return obj;
    }, fail);
  }

  // Lights, a floor shadow-catcher feel, and the camera framed on the model.
  function stage(canvas, w, hgt) {
    const T = window.THREE;
    const renderer = new T.WebGLRenderer({ canvas, antialias: true, alpha: true, preserveDrawingBuffer: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.setSize(w, hgt, false);
    renderer.outputEncoding = T.sRGBEncoding;
    const scene = new T.Scene();
    scene.add(new T.HemisphereLight(0xdfe8ff, 0x2a2f3a, 0.65));
    const key = new T.DirectionalLight(0xffffff, 0.85);
    key.position.set(3, 5, 4);
    scene.add(key);
    const rim = new T.DirectionalLight(0x9fb8ff, 0.45);
    rim.position.set(-4, 2, -3);
    scene.add(rim);
    const camera = new T.PerspectiveCamera(40, w / hgt, 0.01, 10000);
    return { renderer, scene, camera };
  }

  function frame(object, camera) {
    const T = window.THREE;
    const box = new T.Box3().setFromObject(object);
    const size = box.getSize(new T.Vector3());
    const center = box.getCenter(new T.Vector3());
    object.position.sub(center);
    const radius = Math.max(size.x, size.y, size.z) * 0.5 || 1;
    const dist = radius / Math.sin((camera.fov * Math.PI) / 360) * 1.15;
    camera.position.set(dist * 0.65, dist * 0.45, dist * 0.85);
    camera.near = dist / 100;
    camera.far = dist * 100;
    camera.updateProjectionMatrix();
    camera.lookAt(0, 0, 0);
    return { radius, dist };
  }

  function setWire(object, on) {
    object.traverse((o) => {
      if (!o.material) return;
      (Array.isArray(o.material) ? o.material : [o.material]).forEach((m) => { m.wireframe = on; });
    });
  }

  // An interactive viewer for a dialog. Returns {el, dispose, snapshot}.
  function viewer(item) {
    const canvas = h("canvas", { "aria-label": "3D view of " + (item.caption || item.name || "the model") + ". Drag to turn it, pinch or scroll to zoom." });
    const msg = h("div", { class: "viewer-msg" }, "Loading the model…");
    const wire = h("button", { type: "button", class: "btn sm", "aria-pressed": "false" }, "Wireframe");
    const spin = h("button", { type: "button", class: "btn sm", "aria-pressed": "true" }, "Turntable");
    const reset = h("button", { type: "button", class: "btn sm" }, "Reset view");
    const el = h("div", { class: "viewer" }, canvas, msg, h("div", { class: "viewer-bar" }, spin, wire, reset));
    let alive = true, raf = 0, ctx = null, controls = null, model = null, start = null;

    load().then(() => loadModel(Codex.Store.mediaUrl(item.id), ext(item.id))).then((obj) => {
      if (!alive) return;
      const T = window.THREE;
      const w = el.clientWidth || 800, hh = el.clientHeight || 500;
      ctx = stage(canvas, w, hh);
      model = obj;
      ctx.scene.add(obj);
      frame(obj, ctx.camera);
      start = ctx.camera.position.clone();
      controls = new T.OrbitControls(ctx.camera, canvas);
      controls.enableDamping = true;
      controls.autoRotate = true;
      controls.autoRotateSpeed = 1.6;
      msg.remove();
      const resize = () => {
        const W = el.clientWidth, H = el.clientHeight;
        if (!W || !H) return;
        ctx.renderer.setSize(W, H, false);
        ctx.camera.aspect = W / H;
        ctx.camera.updateProjectionMatrix();
      };
      resize();
      window.addEventListener("resize", resize);
      el._resize = resize;
      const tick = () => { if (!alive) return; controls.update(); ctx.renderer.render(ctx.scene, ctx.camera); raf = requestAnimationFrame(tick); };
      tick();
    }).catch((e) => { msg.textContent = e.message + " Try exporting it from Blender as .glb."; });

    spin.addEventListener("click", () => { if (!controls) return; controls.autoRotate = !controls.autoRotate; spin.setAttribute("aria-pressed", String(controls.autoRotate)); });
    wire.addEventListener("click", () => { if (!model) return; const on = wire.getAttribute("aria-pressed") !== "true"; setWire(model, on); wire.setAttribute("aria-pressed", String(on)); });
    reset.addEventListener("click", () => { if (!controls) return; ctx.camera.position.copy(start); controls.target.set(0, 0, 0); controls.update(); });

    return {
      el,
      snapshot() {
        if (!ctx) return null;
        ctx.renderer.render(ctx.scene, ctx.camera);
        return Codex.Store.saveMedia(squareShot(canvas));
      },
      dispose() {
        alive = false;
        cancelAnimationFrame(raf);
        if (el._resize) window.removeEventListener("resize", el._resize);
        if (controls) controls.dispose();
        if (ctx) { ctx.renderer.dispose(); ctx.renderer.forceContextLoss && ctx.renderer.forceContextLoss(); }
      },
    };
  }

  // Crop the middle of a render to a square picture for the art strip.
  function squareShot(canvas) {
    const s = Math.min(canvas.width, canvas.height);
    const out = document.createElement("canvas");
    out.width = out.height = 640;
    const c = out.getContext("2d");
    const g = c.createRadialGradient(320, 220, 40, 320, 320, 460);
    g.addColorStop(0, "#2c3850"); g.addColorStop(1, "#121823");
    c.fillStyle = g;
    c.fillRect(0, 0, 640, 640);
    c.drawImage(canvas, (canvas.width - s) / 2, (canvas.height - s) / 2, s, s, 0, 0, 640, 640);
    return out.toDataURL("image/jpeg", 0.88);
  }

  // Render a picture of a newly added model so it has a thumbnail. Resolves to a media id or null.
  function makePoster(item) {
    return load().then(() => loadModel(Codex.Store.mediaUrl(item.id), ext(item.id))).then((obj) => {
      const canvas = document.createElement("canvas");
      canvas.width = canvas.height = 640;
      const ctx = stage(canvas, 640, 640);
      ctx.renderer.setPixelRatio(1);
      ctx.renderer.setSize(640, 640, false);
      ctx.scene.add(obj);
      frame(obj, ctx.camera);
      ctx.renderer.render(ctx.scene, ctx.camera);
      const id = Codex.Store.saveMedia(squareShot(canvas));
      ctx.renderer.dispose();
      ctx.renderer.forceContextLoss && ctx.renderer.forceContextLoss();
      return id;
    });
  }

  return { viewer, makePoster, load };
})();
