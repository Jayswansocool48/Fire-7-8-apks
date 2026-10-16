/* Sketch pad for concept art and level layouts. Works with a mouse or a drawing tablet
   (pen pressure changes the line width). */
window.Codex = window.Codex || {};

Codex.Sketch = (function () {
  const W = 1200, H = 900;
  const SWATCHES = ["#1d1b26", "#ffffff", "#8a8698", "#e5484d", "#ff8a4c", "#f5c542", "#4cc38a", "#2bb3a3", "#3e8ef7", "#8e6cf0", "#e05fb5", "#8b5a3c"];
  const TOOLS = [
    ["pen", "Pen", "B", "M4 20l4-1 11-11-3-3L5 16l-1 4z"],
    ["eraser", "Eraser", "E", "M8 20h12M5 15l8-8 6 6-6 6H9l-4-4z"],
    ["line", "Line", "L", "M5 19L19 5"],
    ["rect", "Rectangle", "R", "M5 6h14v12H5z"],
    ["ellipse", "Ellipse", "O", "M12 6c4.4 0 8 2.7 8 6s-3.6 6-8 6-8-2.7-8-6 3.6-6 8-6z"],
    ["fill", "Fill", "G", "M6 12l6-6 6 6-6 6-6-6zm13 3c0 1.5-1 2.5-1.5 3.5-.5-1-1.5-2-1.5-3.5"],
  ];

  let el, canvas, ctx, overlay, octx, state;

  function svgIcon(d) {
    return `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${d}"/></svg>`;
  }

  function build() {
    el = document.createElement("div");
    el.className = "sketch";
    el.setAttribute("role", "dialog");
    el.setAttribute("aria-modal", "true");
    el.setAttribute("aria-labelledby", "sketch-title");
    el.hidden = true;
    el.innerHTML = `
      <div class="sketch-bar">
        <h2 id="sketch-title">Sketch</h2>
        <div class="sketch-tools" role="toolbar" aria-label="Drawing tools">
          ${TOOLS.map(([id, label, key, d]) => `<button type="button" class="tool" data-tool="${id}" aria-pressed="false" title="${label} (${key})" aria-label="${label}">${svgIcon(d)}</button>`).join("")}
        </div>
        <label class="sketch-size">Size <input type="range" min="1" max="80" value="6" data-size aria-label="Brush size"><output data-size-out>6</output></label>
        <label class="sketch-size">Opacity <input type="range" min="10" max="100" value="100" data-alpha aria-label="Opacity"></label>
        <div class="sketch-bar-end">
          <button type="button" class="btn ghost" data-undo title="Undo (Ctrl+Z)">Undo</button>
          <button type="button" class="btn ghost" data-redo title="Redo (Ctrl+Shift+Z)">Redo</button>
          <button type="button" class="btn ghost" data-clear>Clear</button>
          <button type="button" class="btn ghost" data-cancel>Cancel</button>
          <button type="button" class="btn primary" data-save title="Save (Ctrl+S)">Save sketch</button>
        </div>
      </div>
      <div class="sketch-body">
        <div class="sketch-side">
          <div class="swatches" role="group" aria-label="Colors">
            ${SWATCHES.map((c) => `<button type="button" class="swatch" data-color="${c}" style="--c:${c}" aria-label="Color ${c}"></button>`).join("")}
          </div>
          <label class="custom-color">Custom <input type="color" value="#1d1b26" data-color-input></label>
          <fieldset class="sketch-opts">
            <legend>Paper</legend>
            <label><input type="radio" name="sketch-bg" value="white" checked> White</label>
            <label><input type="radio" name="sketch-bg" value="clear"> See-through</label>
            <label><input type="checkbox" data-grid> Grid</label>
          </fieldset>
          <p class="sketch-help">Hold Shift for straight lines and squares. [ and ] change the size.</p>
        </div>
        <div class="sketch-stage">
          <div class="sketch-paper" data-paper>
            <canvas width="${W}" height="${H}" data-canvas aria-label="Drawing area"></canvas>
            <canvas width="${W}" height="${H}" data-overlay aria-hidden="true"></canvas>
          </div>
        </div>
      </div>`;
    document.body.appendChild(el);
    canvas = el.querySelector("[data-canvas]");
    overlay = el.querySelector("[data-overlay]");
    ctx = canvas.getContext("2d", { willReadFrequently: true });
    octx = overlay.getContext("2d");

    el.querySelectorAll("[data-tool]").forEach((b) => b.addEventListener("click", () => setTool(b.dataset.tool)));
    el.querySelectorAll("[data-color]").forEach((b) => b.addEventListener("click", () => setColor(b.dataset.color)));
    el.querySelector("[data-color-input]").addEventListener("input", (e) => setColor(e.target.value));
    const size = el.querySelector("[data-size]");
    size.addEventListener("input", () => setSize(+size.value));
    el.querySelector("[data-alpha]").addEventListener("input", (e) => { state.alpha = +e.target.value / 100; });
    el.querySelectorAll('input[name="sketch-bg"]').forEach((r) => r.addEventListener("change", () => setPaper(r.value)));
    el.querySelector("[data-grid]").addEventListener("change", (e) => el.querySelector("[data-paper]").classList.toggle("grid", e.target.checked));
    el.querySelector("[data-undo]").addEventListener("click", undo);
    el.querySelector("[data-redo]").addEventListener("click", redo);
    el.querySelector("[data-clear]").addEventListener("click", () => { snapshot(); ctx.clearRect(0, 0, W, H); state.dirty = true; });
    el.querySelector("[data-cancel]").addEventListener("click", cancel);
    el.querySelector("[data-save]").addEventListener("click", save);

    canvas.addEventListener("pointerdown", down);
    canvas.addEventListener("pointermove", move);
    canvas.addEventListener("pointerup", up);
    canvas.addEventListener("pointercancel", up);
    el.addEventListener("keydown", keys);
  }

  function setTool(t) {
    state.tool = t;
    el.querySelectorAll("[data-tool]").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.tool === t)));
    canvas.style.cursor = t === "fill" ? "cell" : "crosshair";
  }
  function setColor(c) {
    state.color = c;
    el.querySelector("[data-color-input]").value = c;
    el.querySelectorAll("[data-color]").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.color === c)));
    if (state.tool === "eraser") setTool("pen");
  }
  function setSize(n) {
    state.size = Math.max(1, Math.min(80, n));
    el.querySelector("[data-size]").value = state.size;
    el.querySelector("[data-size-out]").textContent = state.size;
  }
  function setPaper(v) {
    state.paper = v;
    el.querySelector("[data-paper]").classList.toggle("clear", v === "clear");
  }

  function point(e) {
    const r = canvas.getBoundingClientRect();
    const pressure = e.pointerType === "pen" ? Math.max(0.1, e.pressure || 0.5) : 1;
    return { x: (e.clientX - r.left) * (W / r.width), y: (e.clientY - r.top) * (H / r.height), p: pressure };
  }

  function snapshot() {
    state.undo.push(ctx.getImageData(0, 0, W, H));
    if (state.undo.length > 40) state.undo.shift();
    state.redo = [];
  }
  function undo() {
    if (!state.undo.length) return;
    state.redo.push(ctx.getImageData(0, 0, W, H));
    ctx.putImageData(state.undo.pop(), 0, 0);
    state.dirty = true;
  }
  function redo() {
    if (!state.redo.length) return;
    state.undo.push(ctx.getImageData(0, 0, W, H));
    ctx.putImageData(state.redo.pop(), 0, 0);
    state.dirty = true;
  }

  function stroke(c) {
    c.lineCap = "round";
    c.lineJoin = "round";
    c.strokeStyle = state.color;
    c.globalAlpha = state.alpha;
  }

  function down(e) {
    if (e.button !== 0 && e.pointerType === "mouse") return;
    canvas.setPointerCapture(e.pointerId);
    const p = point(e);
    if (state.tool === "fill") { snapshot(); fill(Math.floor(p.x), Math.floor(p.y)); state.dirty = true; return; }
    snapshot();
    state.drawing = true;
    state.start = p;
    state.last = p;
    state.points = [p];
    if (state.tool === "pen" || state.tool === "eraser") {
      // a dot for a single click
      ctx.save();
      ctx.globalCompositeOperation = state.tool === "eraser" ? "destination-out" : "source-over";
      ctx.globalAlpha = state.tool === "eraser" ? 1 : state.alpha;
      ctx.fillStyle = state.color;
      ctx.beginPath();
      ctx.arc(p.x, p.y, (state.size * p.p) / 2, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
    }
  }

  function constrain(a, b, square) {
    const dx = b.x - a.x, dy = b.y - a.y;
    if (square) {
      const s = Math.max(Math.abs(dx), Math.abs(dy));
      return { x: a.x + Math.sign(dx || 1) * s, y: a.y + Math.sign(dy || 1) * s };
    }
    const ang = Math.round(Math.atan2(dy, dx) / (Math.PI / 4)) * (Math.PI / 4);
    const len = Math.hypot(dx, dy);
    return { x: a.x + Math.cos(ang) * len, y: a.y + Math.sin(ang) * len };
  }

  function move(e) {
    if (!state.drawing) return;
    let p = point(e);
    const t = state.tool;
    if (t === "pen" || t === "eraser") {
      if (e.shiftKey) {
        // straight line preview from where the stroke started
        octx.clearRect(0, 0, W, H);
        stroke(octx);
        octx.lineWidth = state.size;
        const q = constrain(state.start, p, false);
        octx.beginPath(); octx.moveTo(state.start.x, state.start.y); octx.lineTo(q.x, q.y); octx.stroke();
        state.shiftEnd = q;
        return;
      }
      const l = state.last;
      const mid = { x: (l.x + p.x) / 2, y: (l.y + p.y) / 2 };
      ctx.save();
      ctx.globalCompositeOperation = t === "eraser" ? "destination-out" : "source-over";
      stroke(ctx);
      if (t === "eraser") ctx.globalAlpha = 1;
      ctx.lineWidth = state.size * ((l.p + p.p) / 2);
      ctx.beginPath();
      ctx.moveTo(state.lastMid ? state.lastMid.x : l.x, state.lastMid ? state.lastMid.y : l.y);
      ctx.quadraticCurveTo(l.x, l.y, mid.x, mid.y);
      ctx.stroke();
      ctx.restore();
      state.lastMid = mid;
      state.last = p;
      state.dirty = true;
      return;
    }
    if (e.shiftKey) p = constrain(state.start, p, t !== "line");
    octx.clearRect(0, 0, W, H);
    shape(octx, t, state.start, p);
    state.end = p;
  }

  function shape(c, t, a, b) {
    c.save();
    stroke(c);
    c.lineWidth = state.size;
    c.beginPath();
    if (t === "line") { c.moveTo(a.x, a.y); c.lineTo(b.x, b.y); }
    if (t === "rect") c.rect(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.abs(b.x - a.x), Math.abs(b.y - a.y));
    if (t === "ellipse") c.ellipse((a.x + b.x) / 2, (a.y + b.y) / 2, Math.abs(b.x - a.x) / 2, Math.abs(b.y - a.y) / 2, 0, 0, Math.PI * 2);
    c.stroke();
    c.restore();
  }

  function up() {
    if (!state.drawing) return;
    state.drawing = false;
    const t = state.tool;
    if ((t === "pen" || t === "eraser") && state.shiftEnd) {
      ctx.save();
      ctx.globalCompositeOperation = t === "eraser" ? "destination-out" : "source-over";
      shape(ctx, "line", state.start, state.shiftEnd);
      ctx.restore();
    } else if (state.end && (t === "line" || t === "rect" || t === "ellipse")) {
      shape(ctx, t, state.start, state.end);
    }
    octx.clearRect(0, 0, W, H);
    state.end = state.shiftEnd = state.lastMid = null;
    state.dirty = true;
  }

  function hexToRgba(hex, a) {
    const n = parseInt(hex.slice(1), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255, Math.round(a * 255)];
  }

  // Flood fill that treats close colors as the same, so anti-aliased edges don't leave gaps.
  function fill(x, y) {
    if (x < 0 || y < 0 || x >= W || y >= H) return;
    const img = ctx.getImageData(0, 0, W, H);
    const d = img.data;
    const i0 = (y * W + x) * 4;
    const target = [d[i0], d[i0 + 1], d[i0 + 2], d[i0 + 3]];
    const color = hexToRgba(state.color, state.alpha);
    const tol = 48;
    const same = (i) => Math.abs(d[i] - target[0]) + Math.abs(d[i + 1] - target[1]) + Math.abs(d[i + 2] - target[2]) + Math.abs(d[i + 3] - target[3]) <= tol;
    if (Math.abs(target[0] - color[0]) + Math.abs(target[1] - color[1]) + Math.abs(target[2] - color[2]) + Math.abs(target[3] - color[3]) < 4) return;
    const seen = new Uint8Array(W * H);
    const stack = [[x, y]];
    while (stack.length) {
      const [sx, sy] = stack.pop();
      let lx = sx;
      while (lx >= 0 && !seen[sy * W + lx] && same((sy * W + lx) * 4)) lx--;
      lx++;
      let up = false, dn = false;
      for (let cx = lx; cx < W; cx++) {
        const k = sy * W + cx;
        if (seen[k] || !same(k * 4)) break;
        seen[k] = 1;
        const i = k * 4;
        d[i] = color[0]; d[i + 1] = color[1]; d[i + 2] = color[2]; d[i + 3] = color[3];
        if (sy > 0) { const a = !seen[k - W] && same((k - W) * 4); if (a && !up) stack.push([cx, sy - 1]); up = a; }
        if (sy < H - 1) { const b = !seen[k + W] && same((k + W) * 4); if (b && !dn) stack.push([cx, sy + 1]); dn = b; }
      }
    }
    // Grow the fill one pixel under the soft edges of lines, so no pale ring is left.
    // The line stays on top: its pixel is blended over the fill color.
    const grow = [];
    for (let k = 0; k < W * H; k++) {
      if (seen[k]) continue;
      const kx = k % W;
      if ((kx > 0 && seen[k - 1] === 1) || (kx < W - 1 && seen[k + 1] === 1) || (k >= W && seen[k - W] === 1) || (k < W * (H - 1) && seen[k + W] === 1)) grow.push(k);
    }
    for (const k of grow) {
      const i = k * 4, a = d[i + 3] / 255, fa = color[3] / 255;
      const outA = a + fa * (1 - a);
      if (outA <= 0) continue;
      for (let c = 0; c < 3; c++) d[i + c] = Math.round((d[i + c] * a + color[c] * fa * (1 - a)) / outA);
      d[i + 3] = Math.round(outA * 255);
      seen[k] = 2;
    }
    ctx.putImageData(img, 0, 0);
  }

  function keys(e) {
    const mod = e.ctrlKey || e.metaKey;
    if (e.key === "Escape") { e.preventDefault(); cancel(); return; }
    if (mod && e.key.toLowerCase() === "s") { e.preventDefault(); save(); return; }
    if (mod && e.key.toLowerCase() === "z") { e.preventDefault(); e.shiftKey ? redo() : undo(); return; }
    if (mod && e.key.toLowerCase() === "y") { e.preventDefault(); redo(); return; }
    if (mod || e.target.tagName === "INPUT" && e.target.type !== "range" && e.target.type !== "radio" && e.target.type !== "checkbox") return;
    if (e.key === "[") setSize(state.size - (state.size > 10 ? 4 : 1));
    else if (e.key === "]") setSize(state.size + (state.size >= 10 ? 4 : 1));
    else {
      const t = TOOLS.find((x) => x[2].toLowerCase() === e.key.toLowerCase());
      if (t) setTool(t[0]);
    }
  }

  function output() {
    const out = document.createElement("canvas");
    out.width = W; out.height = H;
    const c = out.getContext("2d");
    if (state.paper === "white") { c.fillStyle = "#ffffff"; c.fillRect(0, 0, W, H); }
    c.drawImage(canvas, 0, 0);
    return out.toDataURL("image/png");
  }

  function save() {
    const data = output();
    const cb = state.onSave;
    close();
    cb && cb(data);
  }

  function cancel() {
    if (state.dirty && !window.confirm("Close the sketch without saving?")) return;
    close();
  }

  function close() {
    el.hidden = true;
    document.body.classList.remove("sketching");
    if (state.returnFocus && state.returnFocus.focus) state.returnFocus.focus();
  }

  function open(opts) {
    if (!el) build();
    state = { tool: "pen", color: "#1d1b26", size: 6, alpha: 1, paper: "white", undo: [], redo: [], dirty: false, onSave: opts.onSave, returnFocus: document.activeElement };
    el.querySelector("#sketch-title").textContent = opts.title || "Sketch";
    ctx.clearRect(0, 0, W, H);
    octx.clearRect(0, 0, W, H);
    setTool("pen"); setColor(state.color); setSize(6);
    el.querySelector("[data-alpha]").value = 100;
    el.querySelector('input[name="sketch-bg"][value="white"]').checked = true;
    setPaper("white");
    el.hidden = false;
    document.body.classList.add("sketching");
    if (opts.src) {
      const img = new Image();
      img.onload = () => {
        const s = Math.min(W / img.width, H / img.height);
        ctx.drawImage(img, (W - img.width * s) / 2, (H - img.height * s) / 2, img.width * s, img.height * s);
      };
      img.src = opts.src;
    }
    el.querySelector('[data-tool="pen"]').focus();
  }

  return { open };
})();
