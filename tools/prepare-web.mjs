// Rewrites a web app so it runs on older Android WebViews (Android 7 era).
//
//   node tools/prepare-web.mjs <assets-root-in> <assets-root-out>
//
// Each root contains a "web/" folder. For every index.html it:
//   • lowers inline JavaScript to Chrome 58 syntax with esbuild (?. ?? ??= spread, etc.)
//   • adds small polyfills for newer built-ins (.at(), .flat(), Promise.finally …)
//   • lowers CSS with Lightning CSS (inset, :is(), …) and adds fallbacks for
//     clamp()/min()/max(), which older engines drop entirely
//   • verifies the result parses as ES2017, so the build fails instead of the app.
import { cpSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { transformSync } from "esbuild";
import { transform as transformCss } from "lightningcss";
import { parse as parseJs } from "acorn";

const [inRoot, outRoot] = process.argv.slice(2);
if (!inRoot || !outRoot) {
  console.error("usage: prepare-web.mjs <in> <out>");
  process.exit(2);
}
rmSync(outRoot, { recursive: true, force: true });
cpSync(inRoot, outRoot, { recursive: true });

const htmlPath = join(outRoot, "web", "index.html");
let html = readFileSync(htmlPath, "utf8");

const POLYFILLS = `(function(){
function def(o,n,f){if(o&&!o[n])try{Object.defineProperty(o,n,{value:f,writable:true,configurable:true});}catch(e){}}
function at(i){i=Math.trunc(i)||0;if(i<0)i+=this.length;return i<0||i>=this.length?undefined:this[i];}
def(Array.prototype,"at",at);def(String.prototype,"at",at);
def(Array.prototype,"flat",function(d){d=d===undefined?1:Math.trunc(d);var out=[];(function f(a,dd){for(var k=0;k<a.length;k++){if(!(k in a))continue;var v=a[k];if(Array.isArray(v)&&dd>0)f(v,dd-1);else out.push(v);}})(this,d);return out;});
def(Array.prototype,"flatMap",function(fn,t){return Array.prototype.map.call(this,fn,t).flat();});
def(String.prototype,"trimStart",String.prototype.trimLeft);def(String.prototype,"trimEnd",String.prototype.trimRight);
def(String.prototype,"replaceAll",function(s,r){if(s instanceof RegExp)return this.replace(s,r);return this.split(s).join(typeof r==="function"?r(s):r);});
def(Promise.prototype,"finally",function(f){var C=this.constructor||Promise;return this.then(function(v){return C.resolve(f()).then(function(){return v;});},function(e){return C.resolve(f()).then(function(){throw e;});});});
def(Promise,"allSettled",function(ps){return Promise.all(Array.from(ps,function(p){return Promise.resolve(p).then(function(v){return{status:"fulfilled",value:v};},function(e){return{status:"rejected",reason:e};});}));});
def(Object,"fromEntries",function(it){var o={};Array.from(it,function(p){o[p[0]]=p[1];});return o;});
if(typeof window!=="undefined"&&!window.globalThis)window.globalThis=window;
if(typeof Node!=="undefined"&&!("isConnected" in Node.prototype))try{Object.defineProperty(Node.prototype,"isConnected",{get:function(){return(this.ownerDocument||document).documentElement.contains(this);},configurable:true});}catch(e){}
if(typeof window!=="undefined"&&!window.queueMicrotask)window.queueMicrotask=function(f){Promise.resolve().then(f);};
})();`;

// ---- JavaScript ------------------------------------------------------------
function lowerScript(whole, attrs, code) {
  if (/\bsrc\s*=/i.test(attrs)) return whole;
  const type = (attrs.match(/\btype\s*=\s*["']?([^"'\s>]+)/i) || [])[1];
  if (type && !/^(text|application)\/(javascript|ecmascript)$/i.test(type)) return whole; // e.g. text/markdown
  const out = transformSync(code, {
    loader: "js",
    target: "chrome58",
    charset: "utf8",
    legalComments: "inline",
  }).code;
  parseJs(out, { ecmaVersion: 2017, sourceType: "script", allowHashBang: true });
  scripts++;
  return `<script${attrs}>${out}</script>`;
}
function splitArgs(s) {
  const out = [];
  let depth = 0, cur = "";
  for (const ch of s) {
    if (ch === "(") depth++;
    if (ch === ")") depth--;
    if (ch === "," && depth === 0) { out.push(cur.trim()); cur = ""; } else cur += ch;
  }
  if (cur.trim()) out.push(cur.trim());
  return out;
}

// Replace clamp()/min()/max() with one plain value an old engine understands.
function mathFallback(value) {
  let changed = true;
  while (changed) {
    changed = false;
    value = value.replace(/\b(clamp|min|max)\(([^()]*(?:\([^()]*\)[^()]*)*)\)/i, (m, fn, inner) => {
      changed = true;
      const args = splitArgs(inner);
      if (fn.toLowerCase() === "clamp") return args[1] ?? args[0];
      const relative = args.find(a => /%|vw|vh|vmin|vmax|em\b|rem\b/.test(a));
      return relative ?? args[0];
    });
  }
  return value;
}

function addMathFallbacks(css) {
  return css.replace(/(^|[{;]\s*)([a-zA-Z-]+)\s*:\s*([^;{}]*\b(?:clamp|min|max)\([^;{}]*)(;|(?=\}))/g,
    (m, lead, prop, value, end) => {
      const fb = mathFallback(value);
      return `${lead}${prop}:${fb};${prop}:${value}${end}`;
    });
}

function lowerStyle(whole, attrs = "", css) {
  let lowered = css;
  try {
    lowered = transformCss({
      filename: "inline.css",
      code: Buffer.from(css),
      minify: false,
      targets: { chrome: 58 << 16, android: 58 << 16 },
      errorRecovery: true,
    }).code.toString();
  } catch (e) {
    console.warn("Lightning CSS skipped a block:", e.message);
  }
  styles++;
  return `<style${attrs}>${addMathFallbacks(lowered)}</style>`;
}

// Walk the page: script blocks get JS lowering; only the markup between them gets CSS
// lowering, so <style> text inside JavaScript strings is never touched.
let scripts = 0;
let styles = 0;
const scriptRe = /<script(\s[^>]*)?>([\s\S]*?)<\/script>/gi;
let out = "";
let last = 0;
let firstScript = true;
for (const m of html.matchAll(scriptRe)) {
  out += html.slice(last, m.index).replace(/<style(\s[^>]*)?>([\s\S]*?)<\/style>/gi, lowerStyle);
  if (firstScript) {
    out += `<script>${POLYFILLS}</script>\n`;
    firstScript = false;
  }
  out += lowerScript(m[0], m[1] || "", m[2]);
  last = m.index + m[0].length;
}
out += html.slice(last).replace(/<style(\s[^>]*)?>([\s\S]*?)<\/style>/gi, lowerStyle);
html = out;
console.log(`Lowered ${scripts} inline script(s) and ${styles} style block(s).`);

writeFileSync(htmlPath, html);
console.log(`Wrote ${htmlPath} (${Math.round(html.length / 1024)} KB)`);
