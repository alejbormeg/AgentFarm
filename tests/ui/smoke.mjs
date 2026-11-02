#!/usr/bin/env node
// Drives the real panel in headless Chrome: unit tests can't see a view that renders blank.
// Usage: CHROME=/path/to/chrome node tests/ui/smoke.mjs <panel-url> <admin-password> [observer-password]
import { spawn } from "node:child_process";
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
socket.onmessage = (m) => {
  const d = JSON.parse(m.data);
  if (d.id && pending.has(d.id)) {
    pending.get(d.id)(d.result || d);
    pending.delete(d.id);
  } else if (d.method === "Runtime.exceptionThrown") {
    exceptions.push(d.params.exceptionDetails.exception?.description || d.params.exceptionDetails.text);
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

const views = {
  overview: "Espera tu decisión",
  tasks: "Molinos · iniciativas",
  agents: "Nodos dirigentes",
  budgets: "Cuota del proveedor",
  governance: "Los Siete Mandamientos",
};

try {
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
  check(await until(() => visible("CI sin leer")), "the task says CI has not been read");

  // Agentes re-fetches itself; each render must not add another poll.
  await go("/overview");
  check(await until(() => visible("La cosecha de esta semana")), "the inbox shows the week");
  check(await visible("Adoptar repositorio"), "an owner can adopt a repository");
  await go("/agents");
  await until(() => visible("Nodos dirigentes"));
  await sleep(11000);
  posts = [];
  await sleep(16000);
  const polls = posts.filter((p) => p.includes("agent-status")).length;
  check(polls >= 2 && polls <= 5, `agents polls about every 5 s (${polls} in 16 s)`);
  await go("/tasks");
  await sleep(1000);
  posts = [];
  await sleep(6000);
  check(!posts.some((p) => p.includes("agent-status")), "polling stops after leaving agents");

  if (observerPassword) {
    await login("oscar", observerPassword);
    exceptions.length = 0;
    await go("/governance");
    check(await until(() => visible("Los Siete Mandamientos")), "an observer can read governance");
    check(!(await visible("Pausar agentes")), "an observer sees no pause button");
    check(!(await visible("Nueva tarea")), "an observer cannot start a task");
    check(!(await visible("Adoptar repositorio")), "an observer cannot adopt a repository");
    check(exceptions.length === 0, "observer view throws nothing");
  }
} finally {
  socket.close();
  chrome.kill();
  await sleep(300);
  rmSync(profile, { recursive: true, force: true, maxRetries: 5 });
}
if (failures.length) {
  console.error(`UI smoke FAILED: ${failures.length} check(s)`);
  process.exit(1);
}
console.log("UI smoke OK");
process.exit(0);
