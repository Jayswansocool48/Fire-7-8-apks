// Drives Game Codex inside the Android emulator over the WebView's DevTools socket (debug build only).
// Checks the storage bridge, files and video byte ranges, the 3D studio, GitHub access rules, Back,
// and takes screenshots of the main screens in landscape and portrait.
// The page keeps no globals, so this uses the screen itself and the bridge's own messages.
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
const nav = (label) => page.click(`nav[aria-label=Main] >> text=${label}`);

// Sends a message the way the page does and waits for the tablet's answer.
const ask = (msg, timeout = 30000) => page.evaluate(({ msg, timeout }) => new Promise((resolve) => {
  const rid = 't' + Math.random().toString(36).slice(2);
  const orig = window.__gameCodexNative;
  window.__gameCodexNative = (m) => {
    if (m.type === 'reply' && m.rid === rid) { window.__gameCodexNative = orig; resolve(m); } else orig(m);
  };
  window.webkit.messageHandlers.codex.postMessage(JSON.stringify({ ...msg, rid }));
  setTimeout(() => resolve({ timeout: true }), timeout);
}), { msg, timeout });

await step('the page loads with the Android bridge', async () => {
  await page.waitForSelector('text=Your library is empty', { timeout: 30000 });
  const platform = await page.evaluate(() => document.documentElement.dataset.platform);
  if (platform !== 'android') throw new Error('platform: ' + platform);
});
shot('01-library-empty');

await step('a sample game opens and is saved to the tablet', async () => {
  await page.click('text=Open a sample game');
  await page.waitForSelector('input[aria-label="Game name"]', { timeout: 10000 });
  await wait(1500);
  const files = adb(`shell run-as ${PKG} ls files/game-codex/projects`);
  if (!/\.json/.test(files)) throw new Error('no project file: ' + files);
});
shot('02-overview');

await step('a file is saved and served back with byte ranges', async () => {
  const id = 'msmoke0000001.png';
  const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
  await page.evaluate(({ id, png }) => window.webkit.messageHandlers.codex.postMessage(JSON.stringify({ type: 'save-media', id, data: png })), { id, png });
  await wait(800);
  const r = await page.evaluate(async (id) => {
    const url = 'https://appassets.androidplatform.net/media/' + id;
    const full = await fetch(url);
    const part = await fetch(url, { headers: { Range: 'bytes=0-9' } });
    return { full: full.status, part: part.status, len: (await part.arrayBuffer()).byteLength };
  }, id);
  if (r.full !== 200 || r.part !== 206 || r.len !== 10) throw new Error(JSON.stringify(r));
});

await step('the network bridge reaches GitHub and nothing else', async () => {
  const ok = await ask({ type: 'http', url: 'https://api.github.com/zen' });
  const bad = await ask({ type: 'http', url: 'https://example.com/' });
  if (bad.status !== 0 || !(ok.status === 200 || ok.status === 403)) throw new Error(JSON.stringify([ok.status, bad.status]));
});

await step('the assistant settings are saved on the tablet', async () => {
  const a = await ask({ type: 'ai-set', settings: { provider: 'ollama', ollama_url: 'http://192.168.1.20:11434' } });
  const b = await ask({ type: 'ai-get' });
  if (a.settings?.provider !== 'ollama' || b.settings?.ollama_url !== 'http://192.168.1.20:11434' || b.settings?.ready !== true) throw new Error(JSON.stringify(b));
});

await step('the 3D studio opens and Back closes it', async () => {
  await nav('Props'); await wait(500);
  await page.click('[data-card]:has-text("Everlantern")'); await wait(700);
  await page.click('text=Model in 3D'); await wait(6000);
  shot('09-studio');
  if (!(await page.$('canvas.bl-canvas'))) throw new Error('studio did not open');
  adb('shell input keyevent KEYCODE_BACK'); await wait(1200);
  if (await page.$('[aria-label="Close the 3D studio"]')) throw new Error('Back did not close it');
});

await step('character sheet, media and library screens', async () => {
  await nav('Characters'); await wait(500);
  await page.click('[data-card]:has-text("Wren")'); await wait(900);
  shot('03-character');
  await nav('Media'); await wait(500);
  await page.click('[data-card]'); await wait(700);
  shot('04-media');
  await nav('Library'); await wait(900);
  shot('05-library');
});

await step('Back closes a dialog first, then steps back', async () => {
  await page.click('button[aria-label=Settings]'); await wait(700);
  shot('06-settings');
  adb('shell input keyevent KEYCODE_BACK'); await wait(900);
  if (await page.$('[role=dialog]')) throw new Error('settings still open');
});

await step('portrait layout', async () => {
  adb('shell wm size 800x1280'); await wait(2500);
  await page.click('[aria-label^="Open Lantern Keeper"]'); await wait(1000);
  shot('07-portrait-overview');
  await page.click('[aria-label="Open the menu"]'); await wait(700);
  await nav('Characters'); await wait(600);
  await page.click('[data-card]:has-text("Wren")'); await wait(800);
  shot('08-portrait-character');
  adb('shell input keyevent KEYCODE_BACK'); await wait(800);
  if (!(await page.$('[data-card]'))) throw new Error('Back did not return to the list');
  adb('shell wm size 1280x800'); await wait(1500);
});

await step('the update check reads the APK repository', async () => {
  const r = await ask({ type: 'check-update' });
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
