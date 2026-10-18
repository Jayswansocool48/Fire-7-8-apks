// Drives Game Codex inside the Android emulator over the WebView's DevTools socket (debug build only).
// Checks the storage bridge, files and video byte ranges, the 3D viewer, GitHub access rules, Back,
// and takes screenshots of the main screens in landscape and portrait.
import { _android as android } from 'playwright';
import { writeFileSync } from 'node:fs';
import { execSync } from 'node:child_process';

const PKG = 'io.github.jayswansocool48.gamecodex.debug';
const OUT = process.env.OUT || 'screens';
const out = [];
const step = async (name, fn) => { try { await fn(); out.push('ok   ' + name); } catch (e) { out.push('FAIL ' + name + ' — ' + String(e.message || e).split('\n')[0]); } };
const adb = (cmd) => execSync('adb ' + cmd, { encoding: 'utf8' });
const shot = (name) => { try { execSync(`adb exec-out screencap -p > ${OUT}/${name}.png`); } catch (e) { /* keep going */ } };

const [device] = await android.devices();
const webview = await device.webView({ pkg: PKG }, { timeout: 60000 });
const page = await webview.page();
const errors = [];
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', (e) => errors.push(e.message));
const wait = (ms) => page.waitForTimeout(ms);

await step('the page loads with the Android bridge', async () => {
  await page.waitForFunction(() => window.Codex && Codex.app && document.querySelector('.topbar'), null, { timeout: 30000 });
  const p = await page.evaluate(() => [Codex.Store.native, Codex.Store.platform]);
  if (!p[0] || p[1] !== 'android') throw new Error(JSON.stringify(p));
});
shot('01-library-empty');

await step('a sample game opens and is saved to the tablet', async () => {
  await page.click('text=Open a sample game');
  await page.waitForSelector('.ov-name', { timeout: 10000 });
  await wait(1500);
  const files = adb(`shell run-as ${PKG} ls files/game-codex/projects`);
  if (!/\.json/.test(files)) throw new Error('no project file: ' + files);
});
shot('02-overview');

await step('a sketch is saved as a file and served back with byte ranges', async () => {
  const r = await page.evaluate(async () => {
    const c = document.createElement('canvas'); c.width = 300; c.height = 200;
    const x = c.getContext('2d'); x.fillStyle = '#ff7f6e'; x.fillRect(0, 0, 300, 200);
    const p = Codex.app.currentProject();
    Codex.app.addArt(p.entries.characters[0], c.toDataURL('image/png'), 'sketch', 'smoke');
    await new Promise((res) => setTimeout(res, 800));
    const id = p.entries.characters[0].art.slice(-1)[0].id;
    const url = 'https://appassets.androidplatform.net/media/' + id; // from the tablet's storage, not the page's cache
    const full = await fetch(url);
    const part = await fetch(url, { headers: { Range: 'bytes=0-9' } });
    return { url, full: full.status, part: part.status, len: (await part.arrayBuffer()).byteLength, range: part.headers.get('content-range') };
  });
  if (r.full !== 200 || r.part !== 206 || r.len !== 10) throw new Error(JSON.stringify(r));
});

await step('the 3D viewer renders a model from the tablet storage', async () => {
  adb('push gamecodex/tests/sword.obj /data/local/tmp/sword.obj');
  adb(`shell run-as ${PKG} cp /data/local/tmp/sword.obj files/game-codex/media/msmokesword01.obj`);
  const poster = await page.evaluate(() => Codex.Models.makePoster({ id: 'msmokesword01.obj' }));
  if (!poster) throw new Error('no poster');
});

await step('the network bridge reaches GitHub and nothing else', async () => {
  const r = await page.evaluate(async () => {
    const ok = await Codex.Store.http('https://api.github.com/zen');
    const bad = await Codex.Store.http('https://example.com/');
    return [ok.status, bad.status];
  });
  if (r[1] !== 0 || !(r[0] === 200 || r[0] === 403)) throw new Error(JSON.stringify(r));
});

await step('character sheet, media and library screens', async () => {
  await page.click('.tab:has-text("Characters")'); await wait(400);
  await page.click('.card:has-text("Wren")'); await wait(800);
  shot('03-character');
  await page.click('.tab[title="Media"]'); await wait(400);
  await page.click('.card'); await wait(600);
  shot('04-media');
  await page.click('.topbar .mark'); await wait(800);
  shot('05-library');
});

await step('Back closes a dialog first', async () => {
  await page.click('.tb-end .icon-btn[aria-label="Settings"]'); await wait(600);
  shot('06-settings');
  adb('shell input keyevent KEYCODE_BACK'); await wait(800);
  if (await page.$('.backdrop')) throw new Error('settings still open');
});

await step('portrait layout', async () => {
  adb('shell wm size 800x1280'); await wait(2500);
  await page.click('.game-cover'); await wait(800);
  shot('07-portrait-overview');
  await page.click('.tab[title="Characters"]'); await wait(500);
  await page.click('.card:has-text("Wren")'); await wait(700);
  shot('08-portrait-character');
  adb('shell wm size 1280x800'); await wait(1500);
});

await step('the update check reads the APK repository', async () => {
  const r = await page.evaluate(() => new Promise((res) => {
    Codex.Store.on('update', (m) => res(m));
    Codex.Store.post({ type: 'check-update' });
    setTimeout(() => res({ status: 'timeout' }), 30000);
  }));
  // this commit's GameCodex.json isn't published until the APK build finishes, so "available" or "current" both pass
  if (!['current', 'available'].includes(r.status)) throw new Error(JSON.stringify(r));
  out.push('     update check said: ' + r.status + (r.text ? ' (' + r.text + ')' : ''));
});

await step('no errors in the console', async () => {
  const real = errors.filter((e) => !/favicon/.test(e));
  if (real.length) throw new Error(real.slice(0, 3).join(' | '));
});

const passed = out.filter((l) => l.startsWith('ok')).length;
const total = out.filter((l) => /^(ok|FAIL)/.test(l)).length;
out.push(`${passed}/${total} passed`);
console.log(out.join('\n'));
writeFileSync(OUT + '/RESULTS.txt', out.join('\n') + '\n');
await device.close();
process.exit(passed === total ? 0 : 1);
