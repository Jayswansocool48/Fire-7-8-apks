// Drives Kiln inside the Android emulator over the WebView's DevTools socket (debug build only).
// Checks: the page and its /api load, a profile can be made, the 3D viewer opens and its tools work by touch.
import { _android as android } from 'playwright';
import { writeFileSync } from 'node:fs';
const out = [], step = async (name, fn) => { try { await fn(); out.push('ok   ' + name); } catch (e) { out.push('FAIL ' + name + ' — ' + (e.message || e).split('\n')[0]); } };
const [device] = await android.devices();
const webview = await device.webView({ pkg: 'io.github.jayswansocool48.kiln' }, { timeout: 60000 });
const page = await webview.page(), ctx = page.context();
const errors = []; page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); }); page.on('pageerror', e => errors.push(e.message));
await step('page loads from the built-in server', async () => {
  await page.waitForFunction(() => document.readyState === 'complete', null, { timeout: 30000 });
  const p = await page.evaluate(() => fetch('/api/profiles').then(r => r.status));
  if (p !== 200) throw new Error('profiles status ' + p);
});
await step('user agent marks the Android app', async () => { if (!/KilnAndroid/.test(await page.evaluate(() => navigator.userAgent))) throw new Error('no KilnAndroid'); });
await step('state and file upload round-trip', async () => {
  const r = await page.evaluate(async () => {
    const id = 'smoketest';
    await fetch(`/api/${id}/state`, { method: 'POST', body: JSON.stringify({ hello: 1 }) });
    const s = await fetch(`/api/${id}/state`).then(r => r.json());
    const u = await fetch(`/api/${id}/upload?path=textures/a b+c.txt`, { method: 'POST', body: 'hi' }).then(r => r.json());
    const f = await fetch(u.url).then(r => r.text()); const list = await fetch(`/api/${id}/files`).then(r => r.json());
    return { s, u, f, list };
  });
  if (r.s.hello !== 1 || r.f !== 'hi' || !r.list.some(x => x.kind === 'Textures')) throw new Error(JSON.stringify(r));
});
await step('requests without the app cookie are refused', async () => {
  const st = await page.evaluate(() => fetch('/api/profiles', { credentials: 'omit' }).then(r => r.status));
  if (st !== 403) throw new Error('status ' + st);
});
await step('3D viewer opens with WebGL and the Blender UI', async () => {
  await page.evaluate(async () => {
    const host = document.createElement('div'); host.className = 'vh'; host.id = 'smokevh';
    const vm = document.createElement('div'); vm.className = 'vm'; vm.append(document.createElement('header'), host); document.body.append(vm);
    const m = await import('/viewer.js');
    await m.mountViewer(host, null, { data: {}, save() {}, up: async () => '', files: async () => [], fu: async () => '', key: 'smoke', uses: [], setModel() {} });
    window.V = host.__viewer;
  });
  await page.waitForSelector('.bl-tools', { timeout: 20000 });
});
await step('Add Cube, then extrude a face by touch', async () => {
  await page.evaluate(async () => { await window.V.addPrim('cube'); window.V.enterEdit(); const ed = window.V.ed; window.V.setEMode('f'); ed.sel = new Set(ed.em.f[4]); window.V.setEMode('f'); window.V.setTool('extrude'); });
  const before = await page.evaluate(() => window.V.ed.em.f.length);
  const b = await page.evaluate(() => { const r = document.querySelector('.bl-canvas').getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; });
  const cdp = await ctx.newCDPSession(page), touch = (type, x, y) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: type === 'touchEnd' ? [] : [{ x, y }] });
  await touch('touchStart', b.x, b.y); for (let i = 1; i <= 6; i++) await touch('touchMove', b.x, b.y - i * 20); await touch('touchEnd');
  await page.waitForTimeout(300);
  const after = await page.evaluate(() => window.V.ed.em.f.length);
  if (!(after > before)) throw new Error(`faces ${before} -> ${after}`);
});
await step('no errors in the console', async () => { if (errors.length) throw new Error(errors.slice(0, 3).join(' | ')); });
const passed = out.filter(l => l.startsWith('ok')).length;
out.push(`${passed}/${out.length} passed`); console.log(out.join('\n')); writeFileSync(process.env.OUT + '/RESULTS.txt', out.join('\n') + '\n');
await device.close(); process.exit(passed === out.length - 1 ? 0 : 1);
