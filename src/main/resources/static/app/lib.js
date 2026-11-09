export const names = {
  queued: "En cola",
  approved: "Lista para implementar",
  proposed: "Esperando tu aprobación",
  review: "Resultado disponible",
  integrated: "Integrada",
  blocked: "Bloqueada",
  cancelled: "Cancelada",
  integrating: "Integrando",
};
export const E = (tag, text, cls) => {
  const e = document.createElement(tag);
  if (text != null) e.textContent = text;
  if (cls) e.className = cls;
  return e;
};
export const json = (x) => {
  try {
    return JSON.parse(x || "{}");
  } catch {
    return {};
  }
};
export const pill = (text, cls = "") => E("span", text, "pill " + cls);
// Board columns; `you` marks the ones waiting on the owner. Stages only change through evidence
// and approvals, so cards open the task instead of being dragged.
export const columns = [
  { title: "Investigación", states: ["queued"] },
  { title: "Espera tu aprobación", states: ["proposed"], you: true },
  { title: "Implementación", states: ["approved"] },
  { title: "Espera tu revisión", states: ["review", "integrating"], you: true },
  { title: "Bloqueadas", states: ["blocked"], stop: true },
  { title: "Cerradas", states: ["integrated", "cancelled"] },
];
export const waiting = (w) => ["proposed", "review", "blocked"].includes(w.state);
export const whyYou = (w) =>
  w.state === "proposed"
    ? "Revisa y aprueba el alcance."
    : w.state === "review"
      ? "Revisa las pruebas y la PR antes de integrar."
      : w.error || "Necesita que decidas cómo seguir.";
export const activity = {
  researching: "Napoleon investigando",
  implementing: "Boxer implementando",
  testing: "Ejecutando tests",
  reviewing: "Revisión independiente",
  integrating: "Integrando PR",
};
export const periodNames = { weekly: "semanal" };
export const period = (x) => periodNames[x] || x;
// Budget admission codes from BudgetService, in the operator's words.
export const reasonLabel = (reason) => {
  const [code, detail] = reason.split(":");
  const label =
    {
      missing_active_window: "Falta una ventana vigente",
      missing_or_stale_usage: "Falta una lectura vigente de la cuota",
      budget_exhausted: "Cuota agotada",
      insufficient_budget: "Saldo insuficiente",
      unreconciled_usage: "Hay consumo pendiente de conciliación",
      reservation_crossed_reset: "Una reserva cruzó un reinicio",
      human_pause: "Pausa del operador",
    }[code] || reason;
  return detail && label !== reason ? label + " (" + period(detail) + ")" : label;
};
// A clock time alone would make yesterday's signal look current.
export const when = (date) => {
  const d = new Date(date);
  return d.toDateString() === new Date().toDateString()
    ? d.toLocaleTimeString("es-ES")
    : d.toLocaleString("es-ES");
};
// How long a card has sat in its stage. Tasks older than the column have no recorded start.
export const stageAge = (since) => {
  const seconds = Math.max(0, Date.now() / 1000 - since);
  if (seconds < 60) return "Hace menos de 1 min";
  if (seconds < 3600) return "Hace " + Math.floor(seconds / 60) + " min";
  if (seconds < 86400) return "Hace " + Math.floor(seconds / 3600) + " h";
  return "Hace " + Math.floor(seconds / 86400) + " d";
};
// Closed work only grows; the board keeps its newest cards in view.
export const closedShown = 5;
// Items that need the owner first, then work in progress, then the rest.
export const attention = {
  proposed: 0,
  review: 0,
  blocked: 1,
  integrating: 2,
  approved: 2,
  queued: 3,
  integrated: 4,
  cancelled: 5,
};
export const byAttention = (a, b) =>
  (attention[a.state] ?? 3) - (attention[b.state] ?? 3) ||
  Number(b.created || 0) - Number(a.created || 0);
export const byRecentlyClosed = (a, b) =>
  Number(b.state_since || b.created || 0) - Number(a.state_since || a.created || 0);
// Fresh snapshots from every executor slot; older servers only send one snapshot.
export const liveSnapshots = (r) =>
  r.executors?.length
    ? r.executors.filter((x) => x.fresh).map((x) => x.snapshot)
    : r.fresh
      ? [r.snapshot || {}]
      : [];
export const running = (r, id) =>
  liveSnapshots(r).find((x) => x.task_id === id && x.run_state !== "idle");
export const TEST_STATUS = { passed: "Superadas", failed: "Fallidas", not_applicable: "No aplican" };
export const testStatus = (status) => (status ? TEST_STATUS[status] || status : "Planificadas");
// The record as stored stays one click away, for whoever needs the rest of its fields.
export const rawRecord = (record, key) => {
  const raw = E("details");
  raw.dataset.key = key;
  raw.append(E("summary", "Registro completo"), E("pre", JSON.stringify(record, null, 2)));
  return raw;
};
export const testEvidence = (planned, result, key) => {
  if (!result) return [E("p", "Comando previsto", "muted"), E("pre", planned)];
  const parts = [E("p", testStatus(result.status), result.status === "failed" ? "warning" : "")];
  if (result.command) parts.push(E("p", "Comando", "muted"), E("pre", result.command));
  if (result.output) parts.push(E("p", "Salida", "muted"), E("pre", result.output));
  parts.push(rawRecord(result, key));
  return parts;
};
// Expected implementation cost from settled runs of the same recipe (no estimate without data).
export const expectedCost = (costs, w) => {
  const recipe = w.recipe?.id || "libre";
  const found = Object.values(costs || {}).filter(
    (c) => c.recipe === recipe && c.kind === "implement",
  );
  if (!found.length) return "";
  if (found.every((c) => c.median == null))
    return "Sin coste por ejecución: la cuota de suscripción es de toda la cuenta y se lee en porcentajes enteros.";
  return (
    "Coste observado al implementar " +
    (recipe === "libre" ? "peticiones libres" : "la receta " + recipe) +
    ": " +
    found
      .filter((c) => c.median != null)
      .map((c) =>
        c.unit === "usd_micros"
          ? "mediana " + (c.median / 1e6).toFixed(2) + " USD, p90 " + (c.p90 / 1e6).toFixed(2) + " USD"
          : "mediana " + c.median / 100 + " %, p90 " + c.p90 / 100 + " % de la cuota semanal",
      )
      .join(" · ") +
    " (" +
    found.reduce((n, c) => n + c.runs, 0) +
    " ejecuciones)."
  );
};
export const lastRepoKey = "agentfarm.lastRepository";
export const lastRepo = () => {
  try {
    return localStorage.getItem(lastRepoKey) || "";
  } catch {
    return "";
  }
};
export const rememberRepo = (value) => {
  try {
    localStorage.setItem(lastRepoKey, value);
  } catch {}
};
export async function api(path, body) {
  const options = {
    credentials: "same-origin",
    cache: "no-store",
    signal: AbortSignal.timeout(12000),
  };
  if (body !== undefined) {
    const token = await api("/csrf");
    options.method = "POST";
    options.headers = {
      "Content-Type": "application/json",
      [token.header]: token.token,
    };
    options.body = JSON.stringify(body);
  }
  const r = await fetch("/api" + path, options);
  if (!r.ok) {
    let error;
    try {
      error = (await r.json()).error;
    } catch {}
    throw Error(
      error || "No se pudo completar la operación (HTTP " + r.status + ").",
    );
  }
  return r.json();
}
export function link(label, url) {
  const e = E("a", label);
  e.href = url;
  e.target = "_blank";
  e.rel = "noopener noreferrer";
  return e;
}
// A button whose action may be asynchronous: while it runs the button is disabled and busy, so a
// double click never sends twice, and it comes back when the action ends, well or badly.
export function button(label, fn, kind = "") {
  const b = E("button", label, kind === true ? "primary" : kind);
  b.type = "button";
  b.onclick = async (event) => {
    if (b.getAttribute("aria-busy") === "true") return;
    const result = fn(event);
    if (!result || typeof result.then !== "function") return result;
    b.setAttribute("aria-busy", "true");
    b.disabled = true;
    b.classList.add("busy");
    try {
      return await result;
    } finally {
      b.removeAttribute("aria-busy");
      b.disabled = false;
      b.classList.remove("busy");
    }
  };
  return b;
}
// Two steps for what can't be undone: the first click says what will happen, the second does it.
export function confirmButton(label, explanation, confirmLabel, fn) {
  const wrap = E("span", "", "confirm");
  const note = E("p", "", "note-line");
  note.setAttribute("role", "status");
  const first = button(label, () => {
    note.textContent = explanation;
    wrap.replaceChildren(first, note, second, keep);
    second.focus();
  });
  const second = button(confirmLabel, fn, "danger");
  const keep = button("No, mantenerla", () => {
    note.textContent = "";
    wrap.replaceChildren(first);
    first.focus();
  }, "quiet");
  wrap.append(first);
  return wrap;
}
export function advancedForm(kind) {
  const d = E("details");
  d.append(
    E(
      "summary",
      {
        budgets: "Ajustes avanzados de presupuesto",
        nodes: "Registrar nodo dirigente",
        workers: "Registrar trabajador Boxer",
      }[kind],
    ),
  );
  const values = {};
  const fields =
    kind === "budgets"
      ? ["account", "operation", "data"]
      : kind === "nodes"
        ? ["id", "role"]
        : ["id", "node"];
  for (const field of fields) {
    const label = E(
      "label",
      {
        account: "Cuenta",
        operation: "Operación",
        data: "Datos JSON",
        id: "Identificador",
        role: "Rol (napoleon o snowball)",
        node: "Nodo",
      }[field],
    );
    const input = E(field === "data" ? "textarea" : "input");
    input.value =
      {
        account: "codex-subscription",
        operation: "status",
        data: "{}",
        role: "napoleon",
        node: "napoleon-wsl",
      }[field] || "";
    values[field] = input;
    label.append(input);
    d.append(label);
  }
  const output = E("pre");
  d.append(
    button("Aplicar", async () => {
      try {
        let result;
        if (kind === "budgets")
          result = await api("/budget/" + values.operation.value, {
            ...JSON.parse(values.data.value),
            account: values.account.value,
          });
        else
          result = await api(
            "/" + kind,
            Object.fromEntries(
              Object.entries(values).map(([k, v]) => [k, v.value]),
            ),
          );
        output.textContent = JSON.stringify(result, null, 2);
      } catch (e) {
        output.textContent = e.message;
      }
    }),
    output,
  );
  return d;
}

// The seven marks every candidate carries, in order. Each state differs in shape too, not only in colour.
const WALL_MARKS = ["I", "II", "III", "IV", "V", "VI", "VII"];
const WALL_STATES = {
  ok: "Cumplido",
  failed: "Incumplido",
  pending: "Pendiente",
  not_evaluated: "Sin evaluar",
  not_applicable: "No aplica",
  exception: "Excepción firmada",
};
export const wall = (dossier) => {
  const list = dossier?.commandments || [];
  const e = E("span", "", "wall" + (list.length ? "" : " none"));
  e.setAttribute("role", "img");
  const count = (s) => list.filter((c) => c.status === s).length;
  e.setAttribute(
    "aria-label",
    list.length
      ? "Mandamientos: " + count("ok") + " cumplidos" + (count("failed") ? ", " + count("failed") + " incumplidos" : "")
      : "Mandamientos: aún sin candidato",
  );
  WALL_MARKS.forEach((mark, i) => {
    const c = list[i];
    const m = E("i", mark, c ? "m-" + c.status : "");
    if (c) m.title = mark + ". " + c.text + " · " + (WALL_STATES[c.status] || c.status);
    e.append(m);
  });
  return e;
};
// Where a task is, from what is on record: a stage is done only when its evidence says so.
export const STAGE_TEXT = {
  done: "hecha",
  now: "en curso",
  you: "te toca",
  bad: "fallida",
  blocked: "bloqueada",
  na: "no aplica",
  cancelled: "cancelada",
  pending: "pendiente",
};
const testsStage = (ev) => {
  const statuses = [ev.unit?.status, ev.integration?.status];
  if (statuses.includes("failed")) return "bad";
  if (statuses.every((s) => s === "not_applicable")) return "na";
  // Done only when every required suite passed (or does not apply); one missing is not "all passed".
  if (statuses.every((s) => s === "passed" || s === "not_applicable")) return "done";
  return statuses.some(Boolean) ? "now" : "pending";
};
// Pure, so it can be tested without a browser: [{name, state, text}] for each stage.
export const journeySteps = (w, d, ev = {}) => {
  const state = w.state;
  const proposed = Number(w.version) > 0 || !!json(w.proposal).summary;
  const built = !!(w.sha || w.pr);
  const ci = d?.verification?.state;
  const steps = [
    ["Propuesta", proposed ? (state === "proposed" ? "you" : "done") : state === "queued" ? "now" : "pending"],
    ["Implementación", built ? "done" : state === "approved" ? "now" : "pending"],
    ["Pruebas", built ? testsStage(ev) : "pending"],
    ["Revisión", ev.review ? (ev.review.passed ? "done" : "bad") : "pending"],
    ["CI", ci === "success" ? "done" : ci === "failed" ? "bad" : ci === "pending" ? "now" : "pending"],
    [
      "Integración",
      state === "integrated" ? "done" : state === "integrating" ? "now" : state === "review" && d?.ready ? "you" : "pending",
    ],
  ];
  // A blocked task is stuck at its first unfinished stage; a cancelled one stops there for good.
  if (state === "blocked" || state === "cancelled") {
    const open = steps.filter(([, s]) => !["done", "na"].includes(s));
    open.forEach((step, i) => (step[1] = state === "cancelled" ? "cancelled" : i === 0 ? "blocked" : step[1] === "now" ? "pending" : step[1]));
  }
  return steps.map(([name, s]) => ({ name, state: s, text: STAGE_TEXT[s] }));
};
export const journey = (w, d, ev) => {
  const list = E("ol", "", "journey");
  list.setAttribute("aria-label", "Recorrido de la tarea");
  for (const step of journeySteps(w, d, ev)) {
    const item = E("li", step.name, step.state === "pending" ? "" : step.state);
    item.append(E("small", step.text));
    list.append(item);
  }
  return list;
};
const ago = (seconds) => {
  if (seconds < 60) return "hace " + Math.max(0, Math.floor(seconds)) + " s";
  if (seconds < 3600) return "hace " + Math.floor(seconds / 60) + " min";
  if (seconds < 86400) return "hace " + Math.floor(seconds / 3600) + " h";
  return "hace " + Math.floor(seconds / 86400) + " d";
};
export const signalAge = (epoch) => (epoch ? "Última señal " + ago(Date.now() / 1000 - epoch) : "Sin señal registrada");
export const renewal = (epoch) =>
  new Date(epoch * 1000).toLocaleString("es-ES", { weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
export const NODE_FRESH_SECONDS = 12;

// ---- Form state across a re-render: each field by a stable name, never by position. ----
const TEXT_TYPES = new Set(["text", "search", "url", "tel", "password", "email"]);
// Only these support a text selection; setSelectionRange throws on number, checkbox and others.
export const textual = (input) =>
  input?.tagName === "TEXTAREA" || (input?.tagName === "INPUT" && TEXT_TYPES.has((input.type || "text").toLowerCase()));
// Field → key inside its form: name, id, aria-label or its label's text; position only as a last resort.
export const fieldKeys = (root) => {
  const keys = new Map(),
    seen = new Map();
  for (const input of root.querySelectorAll("textarea,input,select")) {
    const base =
      input.name ||
      input.id ||
      input.getAttribute("aria-label") ||
      input.closest("label")?.textContent.trim() ||
      input.placeholder ||
      (input.type || input.tagName).toLowerCase();
    const n = seen.get(base) || 0;
    seen.set(base, n + 1);
    keys.set(input, n ? base + "#" + n : base);
  }
  return keys;
};
export const saveFields = (root) => {
  const saved = new Map();
  for (const [input, key] of fieldKeys(root))
    saved.set(key, input.type === "checkbox" || input.type === "radio" ? { checked: input.checked } : { value: input.value });
  return saved;
};
export const restoreFields = (root, saved) => {
  for (const [input, key] of fieldKeys(root)) {
    const s = saved.get(key);
    if (!s) continue;
    if ("checked" in s) input.checked = s.checked;
    else if (input.tagName === "SELECT") {
      if ([...input.options].some((o) => o.value === s.value)) input.value = s.value;
    } else input.value = s.value;
  }
};
