// Kiln UI sounds — synthesized live with the Web Audio API.
// No audio files ship with the app, so there is nothing to license and nothing to download.
let ctx = null, master = null, noiseBuf = null;
const cfg = { on: true, vol: 0.6 };
let lastPlay = {};

function audio() {
  if (!ctx) {
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return null;
    ctx = new AC();
    master = ctx.createGain();
    master.gain.value = cfg.vol * 0.5;
    const comp = ctx.createDynamicsCompressor();
    comp.threshold.value = -14; comp.ratio.value = 4;
    master.connect(comp).connect(ctx.destination);
    noiseBuf = ctx.createBuffer(1, ctx.sampleRate, ctx.sampleRate);
    const d = noiseBuf.getChannelData(0);
    for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
  }
  if (ctx.state === 'suspended') ctx.resume();
  return ctx;
}

// One oscillator voice with a fast attack and exponential decay.
function tone({ type = 'sine', f = 440, f2 = null, t = 0, dur = 0.12, gain = 0.3, attack = 0.004 }) {
  const c = ctx, s = c.currentTime + t, o = c.createOscillator(), g = c.createGain();
  o.type = type; o.frequency.setValueAtTime(f, s);
  if (f2) o.frequency.exponentialRampToValueAtTime(f2, s + dur);
  g.gain.setValueAtTime(0.0001, s);
  g.gain.exponentialRampToValueAtTime(gain, s + attack);
  g.gain.exponentialRampToValueAtTime(0.0001, s + dur);
  o.connect(g).connect(master); o.start(s); o.stop(s + dur + 0.02);
}

// Filtered noise burst (whooshes, crunches, sparks).
function noise({ t = 0, dur = 0.2, gain = 0.2, type = 'bandpass', f = 1200, f2 = null, q = 1 }) {
  const c = ctx, s = c.currentTime + t, src = c.createBufferSource(), fl = c.createBiquadFilter(), g = c.createGain();
  src.buffer = noiseBuf; fl.type = type; fl.Q.value = q; fl.frequency.setValueAtTime(f, s);
  if (f2) fl.frequency.exponentialRampToValueAtTime(f2, s + dur);
  g.gain.setValueAtTime(0.0001, s);
  g.gain.exponentialRampToValueAtTime(gain, s + 0.01);
  g.gain.exponentialRampToValueAtTime(0.0001, s + dur);
  src.connect(fl).connect(g).connect(master); src.start(s, Math.random() * 0.5); src.stop(s + dur + 0.02);
}

// Metallic strike: inharmonic partials like a hammer on an anvil.
function anvil(t = 0, base = 620, gain = 0.22, dur = 1.1) {
  noise({ t, dur: 0.05, gain: gain * 1.4, type: 'highpass', f: 2500 });
  [1, 2.32, 3.88, 5.41, 6.9].forEach((r, i) => tone({ f: base * r, t, dur: dur / (1 + i * 0.45), gain: gain / (1 + i * 0.8), attack: 0.002 }));
}

const SOUNDS = {
  click:   () => tone({ type: 'triangle', f: 1400, f2: 900, dur: 0.045, gain: 0.12 }),
  tick:    () => tone({ type: 'sine', f: 2000, dur: 0.025, gain: 0.07 }),
  toggle:  () => { tone({ type: 'square', f: 900, dur: 0.03, gain: 0.04 }); tone({ type: 'sine', f: 1500, t: 0.035, dur: 0.04, gain: 0.08 }); },
  pop:     () => { tone({ type: 'sine', f: 380, f2: 980, dur: 0.09, gain: 0.25 }); tone({ type: 'triangle', f: 1960, t: 0.03, dur: 0.06, gain: 0.05 }); },
  place:   () => { tone({ type: 'triangle', f: 210, f2: 90, dur: 0.1, gain: 0.3 }); noise({ dur: 0.05, gain: 0.05, f: 600 }); },
  open:    () => { noise({ dur: 0.28, gain: 0.08, f: 400, f2: 3200, q: 0.8 }); tone({ f: 523, t: 0.05, dur: 0.18, gain: 0.1 }); tone({ f: 784, t: 0.1, dur: 0.22, gain: 0.09 }); },
  close:   () => { noise({ dur: 0.22, gain: 0.07, f: 2800, f2: 350, q: 0.8 }); tone({ f: 659, f2: 440, dur: 0.16, gain: 0.08 }); },
  delete:  () => { tone({ type: 'sawtooth', f: 320, f2: 70, dur: 0.22, gain: 0.08 }); noise({ dur: 0.18, gain: 0.12, type: 'lowpass', f: 1800, f2: 200 }); },
  undo:    () => tone({ type: 'sine', f: 880, f2: 520, dur: 0.09, gain: 0.12 }),
  redo:    () => tone({ type: 'sine', f: 520, f2: 880, dur: 0.09, gain: 0.12 }),
  error:   () => { tone({ type: 'square', f: 196, dur: 0.12, gain: 0.06 }); tone({ type: 'square', f: 165, t: 0.13, dur: 0.18, gain: 0.06 }); },
  chime:   () => { [784, 1047, 1319].forEach((f, i) => tone({ f, t: i * 0.06, dur: 0.5, gain: 0.08 })); },
  upload:  () => { tone({ f: 660, f2: 990, dur: 0.12, gain: 0.1 }); tone({ f: 1320, t: 0.1, dur: 0.3, gain: 0.07 }); },
  modeled: () => { anvil(0, 520, 0.12, 0.6); },
  complete:() => { anvil(0, 620, 0.2, 1.2); [1047, 1319, 1568].forEach((f, i) => tone({ f, t: 0.18 + i * 0.07, dur: 0.6, gain: 0.06 })); },
  key:     () => { tone({ type: 'triangle', f: 1760, dur: 0.06, gain: 0.08 }); tone({ f: 2637, t: 0.03, dur: 0.08, gain: 0.04 }); },
  kiln:    () => { noise({ dur: 0.7, gain: 0.07, type: 'lowpass', f: 300, f2: 2400, q: 0.7 }); anvil(0.35, 560, 0.16, 1.4); },
};

export const sfx = {
  configure(o = {}) {
    if (typeof o.on === 'boolean') cfg.on = o.on;
    if (typeof o.vol === 'number') cfg.vol = Math.max(0, Math.min(1, o.vol));
    if (master) master.gain.value = cfg.vol * 0.5;
  },
  get settings() { return { ...cfg }; },
  play(name) {
    if (!cfg.on || !cfg.vol || !SOUNDS[name]) return;
    const now = performance.now();
    if (now - (lastPlay[name] || 0) < 35) return;          // avoid stacking on rapid repeats
    lastPlay[name] = now;
    try { if (audio()) SOUNDS[name](); } catch (e) { /* audio is optional */ }
  },
  names: Object.keys(SOUNDS),
};
