#!/usr/bin/env node
// Drives the real panel in headless Chrome: unit tests can't see a view that renders blank.
// Usage: CHROME=/path/to/chrome node tests/ui/smoke.mjs <panel-url> <admin-password> [observer-password]
import { spawn } from "node:child_process";
import { createHmac } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const [panel, password, observerPassword] = process.argv.slice(2);
if (!panel || !password) throw Error("Usage: smoke.mjs <panel-url> <admin-password> [observer-password]");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const failures = [];
const check = (ok, message) => {
  console.log((ok ? "ok   " : "FAIL ") + message);
  if (!ok) failures.push(message);
};

const profile = mkdtempSync(join(tmpdir(), "agentfarm-ui-"));
const chrome = spawn(
  process.env.CHROME || "google-chrome",
  ["--headless=new", "--no-sandbox", "--disable-gpu", "--remote-debugging-port=0", "--user-data-dir=" + profile, "about:blank"],
  { stdio: "ignore" },
);
chrome.on("error", (e) => {
  console.error("Cannot start Chrome: " + e.message);
  process.exit(2);
});

let port;
for (let i = 0; i < 60 && !port; i++) {
  await sleep(500);
  try {
    port = readFileSync(join(profile, "DevToolsActivePort"), "utf8").split("\n")[0];
  } catch {}
}
if (!port) throw Error("Chrome did not open its debugging port");
const page = (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).find((t) => t.type === "page");
const socket = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r) => (socket.onopen = r));
let nextId = 0;
const pending = new Map();
const exceptions = [];
let posts = [];
const policyBlocks = [];
socket.onmessage = (m) => {
  const d = JSON.parse(m.data);
  if (d.id && pending.has(d.id)) {
    pending.get(d.id)(d.result || d);
    pending.delete(d.id);
  } else if (d.method === "Runtime.exceptionThrown") {
    exceptions.push(d.params.exceptionDetails.exception?.description || d.params.exceptionDetails.text);
  } else if (d.method === "Log.entryAdded" && /Content Security Policy/.test(d.params.entry.text)) {
    policyBlocks.push(d.params.entry.text.slice(0, 160));
  } else if (d.method === "Network.requestWillBeSent" && d.params.request.method === "POST") {
    posts.push(new URL(d.params.request.url).pathname);
  }
};
const send = (method, params = {}) =>
  new Promise((r) => {
    const id = ++nextId;
    pending.set(id, r);
    socket.send(JSON.stringify({ id, method, params }));
  });
const js = async (expression) => {
  const r = await send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
  return r.result?.value;
};
await send("Page.enable");
await send("Network.enable");
await send("Runtime.enable");
await send("Log.enable");
await send("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });

// Every element, across shadow roots; only what is on screen counts as text.
const all = `(function w(r,o=[]){for(const e of r.querySelectorAll('*')){o.push(e);if(e.shadowRoot)w(e.shadowRoot,o)}return o})(document)`;
const visibleText = () =>
  js(`${all}.filter(e=>!/^(STYLE|SCRIPT)$/.test(e.tagName)&&e.getBoundingClientRect().width>0)
    .map(e=>[...e.childNodes].filter(n=>n.nodeType==3).map(n=>n.textContent.trim()).join(' ')).join(' ').replace(/\\s+/g,' ')`);
const visible = (text) => visibleText().then((t) => t.includes(text));
const until = async (condition, ms = 12000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (await condition()) return true;
    await sleep(250);
  }
  return false;
};
const go = async (path) => {
  await send("Page.navigate", { url: panel + path });
  await sleep(500);
};
const login = async (user, secret) => {
  await send("Network.clearBrowserCookies");
  await go("/login");
  check(await until(() => visible("Entra para gobernar")), "login page is in Spanish");
  await js(`document.querySelector('[name=username]').value=${JSON.stringify(user)};
    document.querySelector('[name=password]').value=${JSON.stringify(secret)};document.querySelector('form').submit()`);
  await until(() => js(`location.pathname!=='/login'`));
};
const shownErrors = () =>
  js(`${all}.filter(e=>e.tagName==='P'&&e.classList.contains('error')&&e.getBoundingClientRect().width>0).map(e=>e.textContent).filter(Boolean)`);

// RFC 6238, independently of the panel's own code.
const totp = (secret, step) => {
  let bits = "";
  for (const c of secret) bits += "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const hash = createHmac("sha1", key).update(counter).digest();
  const offset = hash[hash.length - 1] & 15;
  return String((hash.readUInt32BE(offset) & 0x7fffffff) % 1000000).padStart(6, "0");
};
const clickButton = (text) =>
  js(`(()=>{const b=${all}.find(e=>e.tagName==='BUTTON'&&e.textContent.trim()===${JSON.stringify(text)}&&e.getBoundingClientRect().width>0);if(!b)return false;b.click();return true})()`);

const views = {
  overview: "Espera tu decisión",
  tasks: "Molinos · iniciativas",
  agents: "Nodos dirigentes",
  budgets: "Cuota del proveedor",
  governance: "Los Siete Mandamientos",
};

// The notices need a state only the end of the e2e reaches: an outdated executor, one with a broken
// sandbox, two tasks on the same files and a corrected proposal.
const checkNotices = async () => {
  await login("admin", password);
  await go("/overview");
  check(await until(() => visible("Ejecutor con otra versión que el panel: e2e-node-s9")), "the inbox warns about an outdated executor");
  check(
    await until(() => visible("El ejecutor e2e-node-s9 no acepta trabajo: el aislamiento de los agentes no funciona")),
    "the inbox warns about a broken sandbox",
  );
  check(await until(() => visible("Claude Code solo tiene reglas de bloqueo.")), "the inbox warns that Claude Code has no system sandbox");
  await go("/overview?tarea=e2e-request-0001");
  check(await until(() => visible("Squealer dice · unitarias: Superadas")), "test results are in plain words");
  check(await until(() => visible("Se conservan de la versión aprobada")), "a correction shows which approved criteria it kept");
  await js(`${all}.find(e=>e.tagName==='SUMMARY'&&e.textContent.startsWith('Squealer dice · unitarias')).click()`);
  check(await until(() => visible("Registro completo")), "the full record stays available");
  check(
    await js(`${all}.filter(e=>e.tagName==='PRE'&&e.textContent.includes('"kind"')).every(e=>e.closest('details:not([open])'))`),
    "the evidence does not show raw JSON until asked",
  );
  await go("/overview?tarea=e2e-request-overlap");
  check(await until(() => visible("Toca los mismos archivos que")), "a task warns about another one on the same files");
  check(await until(() => visible("AC-3 quizá no se pueda acreditar")), "a doubtful criterion is flagged before approving");
  check(
    await js(`(()=>{const t=${all}.find(e=>e.tagName==='ARTICLE'&&e.textContent.includes('quizá no se pueda acreditar'));if(!t)return false;const d=t.querySelector(':scope > .decision'),l=t.querySelector(':scope > .links');return !!d&&!!l&&d.children.length>0&&!!(d.compareDocumentPosition(l)&Node.DOCUMENT_POSITION_FOLLOWING)})()`),
    "the decision comes before the evidence",
  );
  await go("/overview?tarea=e2e-request-noci");
  check(await until(() => visible("GitHub no ha lanzado ninguna comprobación")), "a task explains why its CI does not start");
  await go("/overview?tarea=e2e-request-adopt");
  check(await until(() => visible("Cambios respecto a la versión anterior")), "a corrected proposal lists what changed");
  await js(`${all}.find(e=>e.tagName==='SUMMARY'&&e.textContent.startsWith('Cambios respecto')).click()`);
  check(await until(() => visible("Antes:")), "opening the changes shows the earlier value");
  await send("Emulation.setEmulatedMedia", { features: [{ name: "prefers-color-scheme", value: "dark" }] });
  await go("/overview");
  await until(() => visible("Bandeja"));
  check(await js(`document.documentElement.dataset.theme==='dark'`), "a dark system gets Atardecer");
  const luminance = (rgb) => {
    const [r, g, b] = rgb.match(/\d+/g).map(Number).map((v) => ((v /= 255) <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  const colors = await js(`(()=>{const s=document.querySelector('.head h1'),b=document.querySelector('.sheet');return [getComputedStyle(s).color,getComputedStyle(b).backgroundColor]})()`);
  const [a, b] = colors.map(luminance).sort((x, y) => y - x);
  check((a + 0.05) / (b + 0.05) >= 4.5, "the panel stays readable on a dark system");
  check(await js(`[...document.querySelectorAll('.scene')].filter(e=>e.getBoundingClientRect().width>0).every(e=>e.src.includes('atardecer'))`), "the farm is at dusk in the dark theme");
  await send("Emulation.setEmulatedMedia", { features: [] });
  const errors = await shownErrors();
  check(errors.length === 0, `the notices show no error ${errors.join(" | ")}`);
  check(exceptions.length === 0, `the notices throw nothing ${exceptions.slice(0, 2).join(" | ")}`);
};

try {
  if (process.env.SMOKE_NOTICES) {
    await checkNotices();
  } else {
    await send("Network.clearBrowserCookies");
    for (let i = 0; i < 6; i++) {
      await go("/login");
      await js(`document.querySelector('[name=username]').value='alguien-que-prueba';
        document.querySelector('[name=password]').value='no-es-esta';document.querySelector('form').submit()`);
      await sleep(600);
    }
    check(await until(() => visible("Demasiados intentos")), "the login page says when guessing is blocked");
    await login("admin", password);
    for (const [view, marker] of Object.entries(views)) {
      exceptions.length = 0;
      await go("/" + view);
      check(await until(() => visible(marker)), `${view} shows "${marker}"`);
      check(exceptions.length === 0, `${view} throws nothing ${exceptions.slice(0, 2).join(" | ")}`);
      const errors = await shownErrors();
      check(errors.length === 0, `${view} shows no error ${errors.join(" | ")}`);
    }

    // A card opens its task in the drawer, by deep link too, and closing returns the address.
    await go("/overview");
    const opened = await until(() =>
      js(`(()=>{const c=${all}.find(e=>e.tagName==='BUTTON'&&e.dataset.task);if(!c)return false;c.click();return true})()`),
    );
    check(opened, "the inbox lists a task card");
    check(await until(() => js(`location.search.includes('tarea=')`)), "opening a card sets ?tarea=");
    check(await until(() => js(`${all}.some(e=>e.tagName==='DIALOG'&&e.open)`)), "the task opens in the drawer");
    const link = await js("location.pathname+location.search");
    await go(link);
    check(await until(() => js(`${all}.some(e=>e.tagName==='DIALOG'&&e.open)`)), "the deep link reopens the task");
    check(await until(() => visible("Mandamientos")), "the task shows its commandments");
    // The panel reads CI every 30 seconds.
    check(await until(() => visible("La CI del commit pasó entera."), 45000), "the task reports the CI the panel read");

    await go("/overview");
    check(await until(() => visible("La cosecha")), "the inbox shows the harvest");
    check(await js(`[...document.querySelectorAll('.farm .scene')].some(e=>e.complete&&e.naturalWidth===420&&e.getBoundingClientRect().width===840)`), "the farm is drawn at a whole-pixel scale");
    check(await visible("Adoptar repositorio"), "an owner can adopt a repository");
    await go("/governance");
    check(await until(() => visible("Conectar GitHub")), "an owner can connect GitHub");

    // Moving between views keeps the app alive instead of reloading it.
    await js(`window.__sameApp=true;document.querySelector('.nav a[href="/agents"]').click()`);
    check(await until(() => visible("Nodos dirigentes")), "the navigation opens a view");
    check(await js(`window.__sameApp===true`), "the navigation does not reload the page");
    await js(`history.back()`);
    check(await until(() => visible("Los Siete Mandamientos")), "the back button returns to the view");

    // Every width, both themes, and the policy of what may load.
    const noScroll = () => js(`document.documentElement.scrollWidth<=innerWidth+1`);
    for (const [width, height] of [[1440, 900], [768, 1024], [390, 844]]) {
      await send("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile: false });
      for (const view of Object.keys(views)) {
        await go("/" + view);
        await until(() => visible(views[view]));
        check(await noScroll(), `${view} has no horizontal scroll at ${width} px`);
      }
    }
    await send("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
    check(policyBlocks.length === 0, `nothing breaks the content policy ${policyBlocks.slice(0, 2).join(" | ")}`);

    if (observerPassword) {
      await login("oscar", observerPassword);
      exceptions.length = 0;
      await go("/governance");
      check(await until(() => visible("Los Siete Mandamientos")), "an observer can read governance");
    check(await visible("Segundo factor"), "an observer can protect their own account");
      check(!(await visible("Pausar agentes")), "an observer sees no pause button");
      check(!(await visible("Nueva tarea")), "an observer cannot start a task");
      check(!(await visible("Adoptar repositorio")), "an observer cannot adopt a repository");
    check(!(await visible("Conectar GitHub")), "an observer cannot connect GitHub");
      check(exceptions.length === 0, "observer view throws nothing");

    // The second factor, from the drawer to the next sign-in.
    await clickButton("Segundo factor");
    check(await until(() => visible("Sin activar en tu cuenta.")), "the second factor starts inactive");
    await clickButton("Generar clave");
    check(await until(() => visible("Solo se enseña ahora")), "enrolling shows the key once");
    const secret = await js(`${all}.find(e=>e.tagName==='PRE'&&/^[A-Z2-7]{32}$/.test(e.textContent)).textContent`);
    const step = Math.floor(Date.now() / 30000);
    const typeCode = (code) =>
      js(`(()=>{const i=${all}.find(e=>e.tagName==='INPUT'&&e.autocomplete==='one-time-code'&&e.getBoundingClientRect().width>0);i.value=${JSON.stringify(code)};return true})()`);
    await typeCode(totp(secret, step));
    await clickButton("Confirmar código");
    check(await until(() => visible("Activo en tu cuenta.")), "a code from the app activates it");
    await login("oscar", observerPassword);
    check(await until(() => visible("Escribe el código de seis cifras")), "the next sign-in asks for the code");
    await go("/governance");
    check(await until(() => js(`location.pathname==='/login/2fa'`)), "the panel stays closed without the code");
    await js(`document.querySelector('[name=code]').value=${JSON.stringify(totp(secret, step + 1))};document.querySelector('form').submit()`);
    await until(() => js(`location.pathname!=='/login/2fa'`));
    await go("/governance");
    check(await until(() => visible("Los Siete Mandamientos")), "the right code opens the panel");
    }
  }
} finally {
  socket.close();
  await new Promise((done) => {
    chrome.once("exit", done);
    chrome.kill("SIGKILL");
    setTimeout(done, 5000);
  });
  // Chrome's helpers can outlive it and keep writing to the profile: a leftover directory in /tmp
  // must not turn a run whose checks all passed into a failure.
  try {
    rmSync(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
  } catch (e) {
    console.warn(`Could not remove the Chrome profile ${profile}: ${e.code}`);
  }
}
if (failures.length) {
  console.error(`UI smoke FAILED: ${failures.length} check(s)`);
  process.exit(1);
}
console.log("UI smoke OK");
process.exit(0);
