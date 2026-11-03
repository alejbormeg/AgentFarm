const style = "/farm-console.css?v=12";
const names = {
  queued: "En cola",
  approved: "Lista para implementar",
  proposed: "Esperando tu aprobación",
  review: "Resultado disponible",
  integrated: "Integrada",
  blocked: "Bloqueada",
  cancelled: "Cancelada",
  integrating: "Integrando",
};
const E = (tag, text, cls) => {
  const e = document.createElement(tag);
  if (text != null) e.textContent = text;
  if (cls) e.className = cls;
  return e;
};
const json = (x) => {
  try {
    return JSON.parse(x || "{}");
  } catch {
    return {};
  }
};
const pill = (text, cls = "") => E("span", text, "pill " + cls);
const svgIcon = (label, body) => {
  const e = E("span");
  e.innerHTML = `<svg class="hat" viewBox="0 0 64 64" role="img" aria-label="${label}">${body}</svg>`;
  return e;
};
// Animal Farm's cast, also served from /icons for the shell's logo and favicon.
const hat = () =>
  svgIcon(
    "Napoleon, un cerdo con bicornio",
    '<path d="M15 27 8 19l13 3zM49 27l7-8-13 3z" fill="#e5909f"/><ellipse cx="32" cy="42" rx="21" ry="19" fill="#f4b3bd"/><ellipse cx="32" cy="49" rx="10" ry="7.5" fill="#e5909f"/><ellipse cx="28" cy="49" rx="2" ry="3" fill="#7c3a48"/><ellipse cx="36" cy="49" rx="2" ry="3" fill="#7c3a48"/><circle cx="22.5" cy="37" r="3" fill="#142d4e"/><circle cx="41.5" cy="37" r="3" fill="#142d4e"/><path d="M2 25C11 23 19 7 32 5c13 2 21 18 30 20-9 7-51 7-60 0z" fill="#142d4e"/><path d="M4 25c12 6 44 6 56 0" fill="none" stroke="#e3ab3e" stroke-width="3" stroke-linecap="round"/><circle cx="32" cy="17" r="6.5" fill="#fff"/><circle cx="32" cy="17" r="4.2" fill="#285bc5"/><circle cx="32" cy="17" r="2" fill="#d23c3c"/>',
  );
const horse = () =>
  svgIcon(
    "Boxer, un caballo de tiro",
    '<path d="M29 12C42 12 54 24 55 44l1 16H40l-3-13-7 1c-2-6-1-12 1-16z" fill="#9a6235"/><path d="M26 4l5 9c13 0 23 12 25 31l1 16h-7l-1-16C48 28 40 18 30 16z" fill="#3a2414"/><path d="M31 13c6 3 6 11 3 18-2 4-6 7-8 12-1 3-4 5-8 5l-7-1c-4-1-6-5-4-9l9-18c2-5 6-8 8-13l1 6z" fill="#9a6235"/><path d="M26 4l-1 7c-3 1-5 3-6 5l9 1 3-4z" fill="#3a2414"/><path d="M20 17c2 7 0 15-6 23l-3-1c4-8 5-15 5-21z" fill="#fff"/><circle cx="25" cy="22" r="2.8" fill="#1b1209"/><ellipse cx="9.5" cy="41" rx="1.8" ry="2.4" fill="#3a2414"/><path d="M8 34l22 1M30 14v21" fill="none" stroke="#e3ab3e" stroke-width="2.6" stroke-linecap="round"/>',
  );
const lens = () =>
  svgIcon(
    "Lupa del revisor",
    '<circle cx="26" cy="26" r="19" fill="#e8eef8" stroke="#142d4e" stroke-width="6"/><path d="M41 41l16 16" stroke="#142d4e" stroke-width="8" stroke-linecap="round"/><path d="M17 26l7 7 12-13" fill="none" stroke="#2f8a4e" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>',
  );
const clover = () =>
  svgIcon(
    "Trébol de Clover",
    '<path d="M32 30c1 13 6 22 15 30" fill="none" stroke="#256b38" stroke-width="4.5" stroke-linecap="round"/><g fill="#3f9b52"><path d="M32 28C22 28 8 24 8 13 8 6 15 3 20 7c-1-5 5-9 10-6 9 5 5 18 2 27z"/><path d="M32 28C22 28 8 24 8 13 8 6 15 3 20 7c-1-5 5-9 10-6 9 5 5 18 2 27z" transform="rotate(90 32 28)"/><path d="M32 28C22 28 8 24 8 13 8 6 15 3 20 7c-1-5 5-9 10-6 9 5 5 18 2 27z" transform="rotate(180 32 28)"/><path d="M32 28C22 28 8 24 8 13 8 6 15 3 20 7c-1-5 5-9 10-6 9 5 5 18 2 27z" transform="rotate(270 32 28)"/></g><g stroke="#c9ebd0" stroke-width="2.2" stroke-linecap="round"><path d="M32 28 18 14M32 28l14-14M32 28 18 42M32 28l14 14"/></g>',
  );
// Board columns; `you` marks the ones waiting on the owner. Stages only change through evidence
// and approvals, so cards open the task instead of being dragged.
const columns = [
  { title: "Investigación", states: ["queued"] },
  { title: "Espera tu aprobación", states: ["proposed"], you: true },
  { title: "Implementación", states: ["approved"] },
  { title: "Espera tu revisión", states: ["review", "integrating"], you: true },
  { title: "Bloqueadas", states: ["blocked"], stop: true },
  { title: "Cerradas", states: ["integrated", "cancelled"] },
];
const waiting = (w) => ["proposed", "review", "blocked"].includes(w.state);
const whyYou = (w) =>
  w.state === "proposed"
    ? "Revisa y aprueba el alcance."
    : w.state === "review"
      ? "Revisa las pruebas y la PR antes de integrar."
      : w.error || "Necesita que decidas cómo seguir.";
const activity = {
  researching: "Napoleon investigando",
  implementing: "Boxer implementando",
  testing: "Ejecutando tests",
  reviewing: "Revisión independiente",
  integrating: "Integrando PR",
};
const periodNames = { weekly: "semanal" };
const period = (x) => periodNames[x] || x;
// Budget admission codes from BudgetService, in the operator's words.
const reasonLabel = (reason) => {
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
const when = (date) => {
  const d = new Date(date);
  return d.toDateString() === new Date().toDateString()
    ? d.toLocaleTimeString("es-ES")
    : d.toLocaleString("es-ES");
};
// How long a card has sat in its stage. Tasks older than the column have no recorded start.
const stageAge = (since) => {
  const seconds = Math.max(0, Date.now() / 1000 - since);
  if (seconds < 60) return "Hace menos de 1 min";
  if (seconds < 3600) return "Hace " + Math.floor(seconds / 60) + " min";
  if (seconds < 86400) return "Hace " + Math.floor(seconds / 3600) + " h";
  return "Hace " + Math.floor(seconds / 86400) + " d";
};
// Closed work only grows; the board keeps its newest cards in view.
const closedShown = 5;
// Items that need the owner first, then work in progress, then the rest.
const attention = {
  proposed: 0,
  review: 0,
  blocked: 1,
  integrating: 2,
  approved: 2,
  queued: 3,
  integrated: 4,
  cancelled: 5,
};
const byAttention = (a, b) =>
  (attention[a.state] ?? 3) - (attention[b.state] ?? 3) ||
  Number(b.created || 0) - Number(a.created || 0);
const byRecentlyClosed = (a, b) =>
  Number(b.state_since || b.created || 0) - Number(a.state_since || a.created || 0);
// Fresh snapshots from every executor slot; older servers only send one snapshot.
const liveSnapshots = (r) =>
  r.executors?.length
    ? r.executors.filter((x) => x.fresh).map((x) => x.snapshot)
    : r.fresh
      ? [r.snapshot || {}]
      : [];
const running = (r, id) =>
  liveSnapshots(r).find((x) => x.task_id === id && x.run_state !== "idle");
// Expected implementation cost from settled runs of the same recipe (no estimate without data).
const expectedCost = (costs, w) => {
  const recipe = w.recipe?.id || "libre";
  const found = Object.values(costs || {}).filter(
    (c) => c.recipe === recipe && c.kind === "implement",
  );
  if (!found.length) return "";
  return (
    "Coste observado al implementar " +
    (recipe === "libre" ? "peticiones libres" : "la receta " + recipe) +
    ": " +
    found
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
const lastRepoKey = "agentfarm.lastRepository";
const lastRepo = () => {
  try {
    return localStorage.getItem(lastRepoKey) || "";
  } catch {
    return "";
  }
};
const rememberRepo = (value) => {
  try {
    localStorage.setItem(lastRepoKey, value);
  } catch {}
};
// The Mateu tab bar lives in nested shadow roots; on narrow screens it scrolls
// horizontally with no visual hint, so fade its edge to show more tabs exist.
const navHint = () => {
  const app = document
    .querySelector("mateu-ui")
    ?.shadowRoot?.querySelector("mateu-ux")
    ?.shadowRoot?.querySelector("mateu-app")?.shadowRoot;
  if (!app || app.getElementById("farm-nav-hint")) return;
  const css = document.createElement("style");
  css.id = "farm-nav-hint";
  css.textContent =
    "@media (max-width: 800px) { nav.mateu-tabs { scrollbar-width: none;" +
    " mask-image: linear-gradient(to right, #000 80%, transparent);" +
    " -webkit-mask-image: linear-gradient(to right, #000 80%, transparent); } }";
  app.append(css);
};
async function api(path, body) {
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
function link(label, url) {
  const e = E("a", label);
  e.href = url;
  e.target = "_blank";
  e.rel = "noopener noreferrer";
  return e;
}
function button(label, fn, kind = "") {
  const b = E("button", label, kind === true ? "primary" : kind);
  b.type = "button";
  b.onclick = fn;
  return b;
}
function advancedForm(kind) {
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
// Owner-only write forms placed below Mateu's read-only lists, which pick up the change on their
// next refresh.
class FarmAdvanced extends HTMLElement {
  async connectedCallback() {
    if (this.shadowRoot) return;
    const root = this.attachShadow({ mode: "open" });
    const css = E("link");
    css.rel = "stylesheet";
    css.href = style;
    root.append(css);
    const me = await api("/me").catch(() => null);
    if (me?.role === "owner") root.append(advancedForm(this.getAttribute("kind")));
  }
}
class FarmConsole extends HTMLElement {
  connectedCallback() {
    navHint();
    this.parentElement
      ?.querySelectorAll(
        ":scope > farm-console, :scope > agentfarm-monitor, :scope > agentfarm-registry",
      )
      .forEach((e) => {
        if (e !== this) e.remove();
      });
    if (!this.root) {
      this.root = this.attachShadow({ mode: "open" });
      const css = E("link");
      css.rel = "stylesheet";
      css.href = style;
      this.root.append(css);
      this.content = E("div");
      this.root.append(this.content);
      this.root.addEventListener("click", (e) => e.stopPropagation());
      this.root.addEventListener("change", (e) => e.stopPropagation());
    }
    this.poll();
    clearInterval(this.timer);
    this.timer = setInterval(() => this.poll(), 2000);
  }
  disconnectedCallback() {
    clearInterval(this.timer);
  }
  route() {
    return location.pathname.split("/").filter(Boolean).at(-1) || "overview";
  }
  async poll() {
    if (this.loading || !this.isConnected) return;
    this.loading = true;
    try {
      const [runtime, overview, work] = await Promise.all([
        api("/runtime"),
        api("/overview"),
        api("/work"),
      ]);
      let route = this.route();
      if (route !== this.view) {
        this.view = route;
        this.layout();
      }
      const extra = route === "governance" ? await api("/audit") : null;
      if (route === "governance") {
        this.commandmentList ||= await api("/evidence/commandments");
        this.skillList ||= await api("/skills");
      }
      const dossiers = ["overview", "tasks", "governance"].includes(route)
        ? await api("/evidence")
        : [];
      const automation =
        route === "governance" ? await api("/automation").catch(() => null) : null;
      const metrics =
        route === "overview" ? await api("/work/metrics").catch(() => null) : null;
      const digest = route === "overview" ? await api("/work/digest").catch(() => null) : null;
      const costs = ["overview", "tasks"].includes(route)
        ? await api("/work/costs").catch(() => ({}))
        : {};
      this.me ||= await api("/me").catch(() => ({ role: "owner" }));
      const initiatives = ["overview", "tasks"].includes(route)
        ? await api("/initiatives")
        : [];
      this.data = {
        runtime,
        overview,
        work,
        extra,
        dossiers,
        initiatives,
        automation,
        metrics,
        digest,
        costs,
        me: this.me,
      };
      this.notifyAttention(work);
      try {
        this.render();
      } catch (e) {
        // Render again next poll: a matching signature would blank the view and clear this error.
        this.signature = "";
        throw Error("No se pudo mostrar esta vista: " + e.message + ".");
      }
      if (this.pendingTask) {
        const id = this.pendingTask;
        this.pendingTask = null;
        if (work.some((w) => w.id === id)) this.showTask(id);
      }
      if (this.pollFailed) this.error.textContent = "";
      this.pollFailed = false;
    } catch (e) {
      if (this.error) {
        this.pollFailed = true;
        this.error.textContent =
          e.message + " Los datos visibles pueden estar desactualizados.";
      }
    } finally {
      this.loading = false;
    }
  }
  layout() {
    this.content.replaceChildren();
    const header = E("div", "", "toolbar");
    const problem = E("p", "", "error"),
      drawerProblem = E("p", "", "error");
    problem.setAttribute("role", "alert");
    drawerProblem.setAttribute("role", "alert");
    // Drawers sit in the top layer, so an action's error has to show inside them too.
    this.error = {
      set textContent(text) {
        problem.textContent = text;
        drawerProblem.textContent = text;
      },
    };
    this.strip = E("div", "", "strip");
    this.body = E("div");
    this.content.append(header, problem, this.strip, this.body);
    this.signature = "";
    if (["overview", "tasks"].includes(this.view)) {
      this.repoOptions = null;
      this.repositorySelect = E("select");
      this.repositorySelect.setAttribute("aria-label", "Filtrar repositorio");
      this.repositorySelect.onchange = () => {
        this.selectedRepo = this.repositorySelect.value;
        this.signature = "";
        this.render();
      };
      header.append(this.repositorySelect);
    } else this.repositorySelect = null;
    if (this.view === "tasks") {
      const search = E("input", "", "search");
      search.type = "search";
      search.placeholder = "Buscar por título, repositorio o issue";
      search.setAttribute("aria-label", "Buscar tareas");
      search.value = this.query || "";
      search.oninput = () => {
        this.query = search.value;
        this.signature = "";
        this.render();
      };
      header.append(search);
    }
    this.pending = E("a");
    this.pending.href = "/overview";
    this.newTask = button("Nueva tarea", () => this.newDrawer.showModal(), true);
    this.adopt = button("Adoptar repositorio", () => this.adoptDrawer.showModal(), "quiet");
    header.append(E("span", "", "spacer"), this.pending, this.adopt, this.newTask);
    this.live = E("div");
    this.body.append(this.live);
    this.form = this.requestForm();
    const fresh = this.drawer("Nueva tarea");
    this.newDrawer = fresh.dialog;
    fresh.body.append(this.form);
    const adoption = this.drawer("Adoptar repositorio");
    this.adoptDrawer = adoption.dialog;
    adoption.body.append(this.adoptionForm());
    const secondFactor = this.drawer("Segundo factor");
    this.secondFactorDrawer = secondFactor.dialog;
    secondFactor.body.append(this.secondFactorForm());
    const task = this.drawer("Tarea");
    this.taskDrawer = task.dialog;
    this.taskTitle = task.title;
    this.taskBody = E("div");
    task.body.append(drawerProblem, this.taskBody);
    this.taskDrawer.addEventListener("close", () => {
      const id = this.openTask;
      this.openTask = null;
      this.taskBody.replaceChildren();
      const url = new URL(location);
      url.searchParams.delete("tarea");
      history.replaceState(history.state, "", url);
      // The card that opened the drawer may have been re-rendered meanwhile.
      [...this.live.querySelectorAll("button.card")].find((c) => c.dataset.task === id)?.focus();
    });
    const linked = new URLSearchParams(location.search).get("tarea");
    if (linked) this.pendingTask = linked;
    this.content.append(this.newDrawer, this.adoptDrawer, this.secondFactorDrawer, this.taskDrawer);
  }
  drawer(name) {
    const dialog = E("dialog", "", "drawer"),
      head = E("div", "", "drawer-head"),
      title = E("h2", name),
      body = E("div", "", "drawer-body");
    title.id = "drawer-" + crypto.randomUUID();
    dialog.setAttribute("aria-labelledby", title.id);
    head.append(title, button("Cerrar", () => dialog.close(), "quiet"));
    dialog.append(head, body);
    // A press on the backdrop targets the dialog element itself.
    dialog.addEventListener("click", (e) => {
      if (e.target === dialog) dialog.close();
    });
    return { dialog, title, body };
  }
  showTask(id) {
    this.openTask = id;
    const url = new URL(location);
    url.searchParams.set("tarea", id);
    history.replaceState(history.state, "", url);
    this.signature = "";
    this.render();
    if (!this.taskDrawer.open) this.taskDrawer.showModal();
  }
  async action(path, body) {
    try {
      await api(path, body);
      this.signature = "";
      await this.poll();
    } catch (e) {
      this.error.textContent = e.message;
    }
  }
  // Built once, like the other drawers, so a code being typed survives polling.
  secondFactorForm() {
    const section = E("section");
    const problem = E("p", "", "error");
    problem.setAttribute("role", "alert");
    const state = E("p");
    const enrollment = E("div");
    const code = E("input");
    code.inputMode = "numeric";
    code.autocomplete = "one-time-code";
    const label = E("label", "Código de seis cifras");
    label.append(code);
    const actions = E("div", "", "actions");
    const run = async (path, body) => {
      try {
        const result = await api(path, body);
        problem.textContent = "";
        code.value = "";
        this.signature = "";
        return result;
      } catch (e) {
        problem.textContent = e.message;
        return null;
      }
    };
    const refresh = async () => {
      const status = await api("/me/2fa");
      const owner = this.data.me?.role === "owner";
      state.textContent =
        (status.active ? "Activo en tu cuenta." : "Sin activar en tu cuenta.") +
        (status.required_to_integrate ? " Hace falta para integrar." : " No hace falta para integrar.");
      actions.replaceChildren();
      if (status.active) {
        enrollment.replaceChildren();
        actions.append(
          button("Desactivar", async () => {
            if (await run("/me/2fa/disable", { code: code.value.trim() })) await refresh();
          }, "quiet"),
        );
        if (owner)
          actions.append(
            button(status.required_to_integrate ? "Dejar de exigir para integrar" : "Exigir para integrar", async () => {
              if (await run("/governance/two-factor-to-integrate/" + !status.required_to_integrate, {})) await refresh();
            }, "quiet"),
          );
      } else {
        actions.append(
          button("Generar clave", async () => {
            const created = await run("/me/2fa/setup", {});
            if (!created) return;
            const uri = E("a", "Abrir en la aplicación");
            uri.href = created.uri;
            enrollment.replaceChildren(
              E("p", "Añade esta clave a tu aplicación de autenticación y escribe el código que te muestre. Solo se enseña ahora."),
              E("pre", created.secret),
              uri,
            );
          }, "quiet"),
          button("Confirmar código", async () => {
            if (await run("/me/2fa/confirm", { code: code.value.trim() })) await refresh();
          }, true),
        );
      }
    };
    this.refreshSecondFactor = refresh;
    section.append(
      E("p", "Pide un código además de la contraseña. Si pierdes el teléfono, se restablece con ./agentfarm 2fa-reset desde el equipo del panel.", "muted"),
      state,
      problem,
      enrollment,
      label,
      actions,
    );
    return section;
  }
  // Adopting is an ordinary task: its proposal, with agentfarm.yaml, waits for approval as any other.
  adoptionForm() {
    const section = E("section");
    const problem = E("p", "", "error");
    problem.setAttribute("role", "alert");
    const repo = E("input");
    repo.placeholder = "owner/repositorio";
    const label = E("label", "Repositorio");
    label.append(repo);
    section.append(
      E(
        "p",
        "Napoleon analizará el repositorio y te propondrá sus comandos de prueba, sus reglas de calidad y hasta tres mejoras. Desde entonces, AgentFarm vigilará su CI, sus alertas de Dependabot, los comentarios de sus PR y hará una revisión semanal: todo llega como propuesta para que decidas.",
      ),
      problem,
      label,
      button(
        "Analizar repositorio",
        async () => {
          const repository = repo.value.trim();
          if (!/^[\w.-]+\/[\w.-]+$/.test(repository)) {
            problem.textContent = "Indica el repositorio como owner/repositorio.";
            return;
          }
          try {
            await api("/work", {
              request_id: crypto.randomUUID(),
              repository,
              title: "Adoptar " + repository,
              request: "Adopta este repositorio para que los agentes lo mantengan.",
              recipe: { id: "adopt-repo", version: 2 },
            });
            await api("/automation/triggers", {
              repository,
              kinds: ["labeled_issue", "failing_ci", "dependabot", "weekly", "pr_feedback"],
              max_new: 2,
              enabled: true,
            });
            problem.textContent = "";
            repo.value = "";
            this.adoptDrawer.close();
            this.signature = "";
            await this.poll();
          } catch (e) {
            problem.textContent = e.message;
          }
        },
        true,
      ),
    );
    return section;
  }
  // The week at a glance and what can be approved together. Each entry names its exact commit.
  weekBlock(main) {
    const d = this.data.digest;
    if (!d) return;
    main.append(E("h2", "La cosecha de esta semana"));
    const pills = E("div", "", "pills");
    pills.append(
      pill(d.integrated.length + " integradas", d.integrated.length ? "ok" : ""),
      pill(d.in_progress + " en curso"),
      pill(d.waiting + " esperan tu decisión"),
    );
    if (d.blocked.length) pills.append(pill(d.blocked.length + " bloqueadas", "bad"));
    main.append(pills);
    if (!d.ready.length || this.data.me?.role !== "owner") return;
    const box = E("div", "", "batch");
    box.append(
      E("h3", "Listas para integrar"),
      E("p", "Cambios de bajo riesgo con todas las comprobaciones superadas.", "muted"),
    );
    this.batch ||= new Set();
    for (const w of d.ready) {
      const row = E("label", "", "batch-row"),
        check = E("input");
      check.type = "checkbox";
      check.checked = this.batch.has(w.id) || !this.batchTouched;
      if (check.checked) this.batch.add(w.id);
      check.onchange = () => {
        this.batchTouched = true;
        check.checked ? this.batch.add(w.id) : this.batch.delete(w.id);
      };
      row.append(
        check,
        document.createTextNode(" " + w.title + " · " + w.repo + " · " + w.sha.slice(0, 8)),
      );
      box.append(row);
    }
    box.append(
      button(
        "Integrar las seleccionadas",
        async () => {
          const items = d.ready
            .filter((w) => this.batch.has(w.id))
            .map((w) => ({ id: w.id, sha: w.sha }));
          if (!items.length) {
            this.error.textContent = "Selecciona al menos una tarea.";
            return;
          }
          if (!confirm("Se integrarán " + items.length + " cambios, cada uno con el commit indicado. ¿Continuar?"))
            return;
          try {
            const { results } = await api("/work/batch/merge", { items });
            const refused = results.filter((r) => r.state === "refused");
            this.error.textContent = refused.length
              ? refused.length + " no se integraron: " + refused[0].error
              : "";
            this.batch.clear();
            this.signature = "";
            await this.poll();
          } catch (e) {
            this.error.textContent = e.message;
          }
        },
        true,
      ),
    );
    main.append(box);
  }
  // First run: what to do, in order, instead of an empty page.
  firstSteps(main, r, allWork) {
    if (allWork.length || this.data.me?.role !== "owner") return false;
    const box = E("section", "", "panel");
    box.append(E("h2", "Empieza por aquí"));
    const steps = E("ol", "", "commandments");
    const executor = E("li", "");
    executor.append(
      r.fresh
        ? document.createTextNode("Ejecutor conectado.")
        : link("Conecta un ejecutor", "/setup.html"),
    );
    const adopt = E("li", "");
    adopt.append(
      button("Adopta tu primer repositorio", () => this.adoptDrawer.showModal(), true),
    );
    steps.append(executor, adopt);
    box.append(steps);
    main.append(box);
    return true;
  }
  requestForm() {
    const section = E("section");
    const problem = E("p", "", "error");
    problem.setAttribute("role", "alert");
    section.append(
      E("p", "Napoleon investigará y te propondrá un alcance antes de cambiar código.", "muted"),
      problem,
    );
    const repo = E("input");
    repo.value = lastRepo();
    repo.placeholder = "owner/repositorio";
    repo.required = true;
    const title = E("input");
    title.placeholder = "Qué quieres mejorar";
    const request = E("textarea");
    request.placeholder = "Describe el objetivo que quieres conseguir.";
    for (const [name, input] of [
      ["Repositorio", repo],
      ["Título", title],
      ["Petición", request],
    ]) {
      const l = E("label", name);
      l.append(input);
      section.append(l);
    }
    const recipe = E("select"),
      guidance = E("details"),
      catalogState = E("small");
    const recipeLabel = E("label", "Receta opcional");
    recipeLabel.append(recipe);
    const manual = E("option", "Petición libre");
    manual.value = "";
    recipe.append(manual);
    recipe.setAttribute("aria-label", "Receta opcional");
    guidance.append(E("summary", "Qué propone la receta"));
    guidance.hidden = true;
    let recipes = [];
    recipe.onchange = () => {
      const chosen = recipes.find(
        (r) => r.id + "@" + r.version === recipe.value,
      );
      guidance.hidden = !chosen;
      guidance.replaceChildren(E("summary", "Qué propone la receta"));
      if (chosen) {
        guidance.append(E("p", chosen.purpose));
        for (const step of chosen.research_steps) guidance.append(E("p", step));
        guidance.append(
          E(
            "small",
            "La receta guía la investigación. El alcance y la integración requieren aprobación.",
          ),
        );
      }
    };
    api("/recipes")
      .then((rows) => {
        recipes = rows;
        for (const r of rows) {
          const option = E("option", r.title + " · v" + r.version);
          option.value = r.id + "@" + r.version;
          recipe.append(option);
        }
      })
      .catch(() => {
        catalogState.textContent =
          "Recetas no disponibles. Puedes enviar una petición libre.";
      });
    section.append(recipeLabel, catalogState, guidance);
    const id = crypto.randomUUID();
    section.append(
      button(
        "Crear issue e investigar",
        async () => {
          if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo.value.trim())) {
            problem.textContent = "Indica el repositorio como owner/nombre.";
            return;
          }
          if (!title.value.trim() || !request.value.trim()) {
            problem.textContent = "Escribe un título y una petición.";
            return;
          }
          try {
            await api("/work", {
              request_id: id,
              repository: repo.value.trim(),
              title: title.value.trim(),
              request: request.value.trim(),
              ...(recipe.value
                ? {
                    recipe: ((r) => ({ id: r.id, version: r.version }))(
                      recipes.find(
                        (r) => r.id + "@" + r.version === recipe.value,
                      ),
                    ),
                  }
                : {}),
            });
            this.selectedRepo = repo.value.trim();
            rememberRepo(this.selectedRepo);
            this.form.replaceWith((this.form = this.requestForm()));
            this.newDrawer.close();
            this.signature = "";
            await this.poll();
          } catch (e) {
            problem.textContent = e.message;
          }
        },
        true,
      ),
      E("span", " "),
      button(
        "Pedir ideas de mejora",
        () => {
          title.value = "Proponer mejoras del proyecto";
          request.value =
            "Analiza el estado actual y propone mejoras priorizadas y viables, con fuentes cuando sean necesarias. No implementes nada; esperaré para seleccionar y aprobar el alcance.";
        },
        "quiet",
      ),
    );
    return section;
  }
  render() {
    const { runtime: r, overview: o, work: allWork } = this.data,
      s = r.snapshot || {},
      fresh = r.fresh,
      q = s.quota || {},
      busy = liveSnapshots(r).some((x) => !!x.codex_pid);
    let work = allWork;
    if (this.repositorySelect) {
      const repos = [...new Set(allWork.map((w) => w.repo))];
      if (this.selectedRepo === undefined)
        this.selectedRepo = "";
      if (this.repoOptions !== repos.join("|")) {
        this.repoOptions = repos.join("|");
        this.repositorySelect.replaceChildren();
        for (const value of ["", ...repos]) {
          const option = E("option", value || "Todos los repositorios");
          option.value = value;
          this.repositorySelect.append(option);
        }
      }
      this.repositorySelect.value = this.selectedRepo;
      work = allWork.filter(
        (w) => !this.selectedRepo || w.repo === this.selectedRepo,
      );
    }
    const me = this.data.me;
    this.newTask.hidden = me?.role === "observer";
    this.adopt.hidden = me?.role !== "owner";
    const decisions = allWork.filter(waiting).length;
    this.pending.textContent =
      this.view !== "overview" && decisions
        ? decisions + (decisions === 1 ? " espera tu decisión" : " esperan tu decisión")
        : "";
    this.pending.hidden = !this.pending.textContent;
    document.title = (decisions ? "(" + decisions + ") " : "") + "AgentFarm";
    const signature = JSON.stringify([
      this.view,
      s.run_state,
      s.task_id,
      liveSnapshots(r).map((x) => [x.executor, x.run_state, x.task_id]),
      fresh,
      busy,
      r.quota_fresh,
      q,
      work,
      o.nodes.map((n) => [n.id, n.enabled, n.reported_state]),
      o.workers.map((w) => [w.id, w.enabled, w.reported_state]),
      o.paused,
      this.data.extra,
      this.data.dossiers,
      this.data.digest,
      this.data.initiatives,
      (r.events || []).at(-1)?.id,
      this.openTask,
    ]);
    this.live?.querySelectorAll("[data-since]").forEach((e) => {
      e.textContent = stageAge(e.dataset.since);
    });
    if (signature === this.signature) return;
    this.signature = signature;
    this.status(r, o, busy);
    const roots = [this.live, this.taskBody];
    const all = (selector) => roots.flatMap((x) => [...x.querySelectorAll(selector)]);
    const drawerScroll = this.taskBody.parentElement?.scrollTop || 0;
    const focused = this.root.activeElement;
    const focusOwner = focused?.closest("details[data-key]");
    const focusState = focusOwner
      ? {
          key: focusOwner.dataset.key,
          index: [
            ...focusOwner.querySelectorAll("textarea,input,select"),
          ].indexOf(focused),
          start: focused.selectionStart,
          end: focused.selectionEnd,
        }
      : null;
    const drafts = all("details[data-key]").map(
      (d) => [
        d.dataset.key,
        [...d.querySelectorAll("textarea,input,select")].map((i) => i.value),
      ],
    );
    const opened = all("details[open]")
      .map((e) => e.dataset.key)
      .filter(Boolean);
    this.live.replaceChildren();
    this.taskBody.replaceChildren();
    if (this.view === "overview") {
      const map = E("div", "", "map");
      const phases = liveSnapshots(r).map((x) => x.run_state || x.phase || "idle");
      const phase = (p) => phases.includes(p);
      for (const [name, icon, state] of [
        [
          "Napoleon",
          hat,
          phase("researching") ? "Investigando" : "Coordina la cola",
        ],
        [
          "Boxer",
          () => horse(),
          phase("implementing")
            ? "Implementando"
            : phase("testing")
              ? "Ejecutando tests"
              : "Disponible",
        ],
        ["Benjamin · revisor", lens, phase("reviewing") ? "Revisando" : "Disponible"],
      ]) {
        if (map.children.length) map.append(E("span", "→", "arrow"));
        const a = E("div", "", "agent"),
          text = E("div");
        text.append(E("strong", name), E("p", fresh ? state : "Sin señal", "muted"));
        a.append(icon(), text);
        map.append(a);
      }
      if (r.outdated_executors?.length)
        map.append(
          E(
            "p",
            "Ejecutor con otra versión que el panel: " + r.outdated_executors.join(", ") + ". Actualízalo.",
            "warning",
          ),
        );
      for (const x of r.unisolated_executors || [])
        map.append(
          E(
            "p",
            "El ejecutor " + x.executor + " no acepta trabajo: el aislamiento de los agentes no funciona (" + x.problem + "). Ejecuta tests/codex_sandbox.sh y revisa la versión de Codex.",
            "warning",
          ),
        );
      const split = E("div", "", "split"),
        main = E("section"),
        side = E("aside");
      if (!this.firstSteps(main, r, allWork)) this.weekBlock(main);
      const yours = work.filter(waiting).sort(byAttention);
      main.append(E("h2", "Espera tu decisión"));
      if (!yours.length)
        main.append(E("p", "Nada espera tu decisión ahora mismo.", "empty"));
      const inbox = E("div", "", "inbox");
      for (const w of yours) inbox.append(this.taskTile(w, true));
      main.append(inbox);
      const moving = work.filter((w) =>
        ["queued", "approved", "integrating"].includes(w.state),
      );
      main.append(E("h2", "En curso"));
      if (!moving.length)
        main.append(
          E(
            "p",
            busy ? s.task_title || "Hay una ejecución en curso." : "No hay trabajo en curso.",
            "empty",
          ),
        );
      const inProgress = E("div", "", "inbox");
      for (const w of moving.sort(byAttention)) inProgress.append(this.taskTile(w));
      main.append(inProgress);
      this.metricsBlock(side, this.data.metrics);
      if (me?.role === "owner")
        side.append(
          E("h2", "Control"),
          button(
            o.paused ? "Reanudar cola" : "Pausar agentes",
            () => this.action("/governance/paused/" + !o.paused, {}),
            o.paused ? "" : "danger",
          ),
        );
      side.append(
        (() => {
          const h = E("h2", "", "with-icon");
          h.append(clover(), document.createTextNode("Clover"));
          return h;
        })(),
        E(
          "p",
          r.quota_fresh
            ? "Parada al 2 % restante."
            : "Sin una lectura reciente de la cuota no se admite trabajo nuevo.",
        ),
        E(
          "small",
          q.observed_at
            ? "Última lectura: " + when(q.observed_at)
            : "Sin lectura",
        ),
      );
      if (r.quota_fresh) {
        const bar = E("div", "", "progress"),
          i = E("i");
        i.style.width =
          Math.max(0, Math.min(100, 100 - q.weekly_used_percent)) + "%";
        bar.append(i);
        side.append(bar);
      }
      if ("Notification" in window && Notification.permission === "default")
        side.append(
          E("p", ""),
          button(
            "Avisarme en este navegador",
            async () => {
              await Notification.requestPermission();
              this.signature = "";
              this.render();
            },
            "quiet",
          ),
        );
      split.append(main, side);
      this.live.append(split, map);
      this.console(r.events || []);
      const detail = E("details");
      detail.dataset.key = "technical";
      const diagnostics = E("pre");
      diagnostics.textContent = !s.observed_at
        ? "El ejecutor todavía no ha enviado ninguna señal. Conéctalo desde Gobierno → Conectar ejecutor."
        : JSON.stringify(
            {
              entorno: s.host,
              estado: s.run_state,
              ultima_señal: s.observed_at,
              session: s.session,
              modelo: s.model,
              proceso: s.codex_pid,
              problema: s.github_error,
            },
            null,
            2,
          );
      detail.append(E("summary", "Conexión y diagnóstico"), diagnostics);
      this.live.append(detail);
    } else if (this.view === "tasks") {
      const query = (this.query || "").trim().toLowerCase();
      this.board(
        this.live,
        work.filter(
          (w) =>
            !query ||
            [w.title, w.repo, w.issue && "#" + w.issue].some((x) =>
              String(x || "").toLowerCase().includes(query),
            ),
        ),
        query,
      );
      const initiatives = E("details");
      initiatives.dataset.key = "initiatives";
      initiatives.append(
        E("summary", "Molinos · iniciativas: " + (this.data.initiatives?.length || 0)),
      );
      this.initiativeList(initiatives);
      this.live.append(initiatives);
      if (o.tasks?.length) {
        const legacy = E("details");
        legacy.dataset.key = "legacy-tasks";
        legacy.append(E("summary", "Tareas del piloto anterior"));
        for (const t of o.tasks)
          legacy.append(
            E(
              "p",
              t.id +
                " · " +
                t.state +
                " · " +
                (t.candidate_sha || "Sin commit"),
            ),
          );
        this.live.append(legacy);
      }
    } else if (this.view === "governance") {
      const control = E("section", "", "panel");
      const controls = E("div", "", "actions");
      if (me?.role === "owner")
        controls.append(
          button(
            o.paused ? "Reanudar cola" : "Pausar agentes",
            () => this.action("/governance/paused/" + !o.paused, {}),
            o.paused ? "" : "danger",
          ),
          link("Conectar ejecutor", "/setup.html"),
        );
      controls.append(
        button("Segundo factor", async () => {
          await this.refreshSecondFactor();
          this.secondFactorDrawer.showModal();
        }, "quiet"),
      );
      control.append(
        E("h2", "Control de ejecución"),
        E(
          "p",
          "Cada propuesta necesita tu aprobación. Cada integración queda vinculada al commit revisado.",
        ),
        controls,
      );
      this.live.append(control);
      const wall = E("section", "", "panel");
      wall.append(
        E("h2", "Los Siete Mandamientos"),
        E(
          "p",
          "Cada cambio los cumple antes de poder integrarse. Están en el código del panel: ningún agente puede repintarlos.",
          "muted",
        ),
      );
      const rules = E("ol", "", "commandments");
      for (const c of this.commandmentList || []) rules.append(E("li", c.text));
      wall.append(rules);
      this.live.append(wall);
      const skills = E("section", "", "panel");
      skills.append(
        E("h2", "Skills de los agentes"),
        E(
          "p",
          "Cada turno recibe las que corresponden a su rol y a la tecnología del repositorio, y la evidencia registra su versión.",
          "muted",
        ),
      );
      const catalog = E("ul", "", "skills");
      for (const c of this.skillList || []) catalog.append(E("li", c.title + " · " + c.id + " v" + c.version));
      skills.append(catalog);
      this.live.append(skills);
      this.automation(this.data.automation);
      const audit = E("section", "", "panel");
      this.live.append(audit);
      audit.append(E("h2", "Últimas decisiones"));
      if (!this.data.extra?.length) audit.append(E("p", "Sin decisiones registradas.", "muted"));
      else {
        const scroll = E("div", "", "table-scroll"),
          grid = E("table"),
          head = E("tr");
        ["Fecha", "Decisión", "Sobre"].forEach((x) => head.append(E("th", x)));
        grid.append(head);
        for (const row of this.data.extra.slice(0, 30)) {
          const tr = E("tr");
          tr.append(E("td", when(row.at)), E("td", row.action), E("td", row.subject));
          grid.append(tr);
        }
        scroll.append(grid);
        audit.append(scroll);
      }
    }
    const shown = this.openTask && allWork.find((w) => w.id === this.openTask);
    if (shown) {
      this.taskTitle.textContent = shown.title;
      this.taskBody.append(this.taskDetail(shown));
    } else if (this.openTask && this.taskDrawer.open) this.taskDrawer.close();
    for (const d of all("details")) {
      if (opened.includes(d.dataset.key)) d.open = true;
      const saved = drafts.find((x) => x[0] === d.dataset.key);
      if (saved)
        [...d.querySelectorAll("textarea,input,select")].forEach(
          (input, i) => (input.value = saved[1][i] || ""),
        );
      if (
        focusState &&
        focusState.key === d.dataset.key &&
        focusState.index >= 0
      ) {
        const input = [...d.querySelectorAll("textarea,input,select")][
          focusState.index
        ];
        input?.focus({ preventScroll: true });
        input?.setSelectionRange?.(focusState.start, focusState.end);
      }
    }
    if (this.taskBody.parentElement) this.taskBody.parentElement.scrollTop = drawerScroll;
  }
  // Only what needs saying right now, each with the way to resolve it; Bandeja also shows what is fine.
  status(r, o, busy) {
    const s = r.snapshot || {},
      q = s.quota || {},
      fresh = r.fresh,
      owner = this.data.me?.role === "owner",
      items = [];
    const notice = (text, kind, control) => {
      const row = E("div", "", "notice " + kind);
      row.append(E("span", text));
      if (control) row.append(control);
      items.push(row);
    };
    if (!fresh)
      notice(
        "El ejecutor no da señal: el trabajo no avanzará hasta que se conecte.",
        "bad",
        owner ? link("Conectar ejecutor", "/setup.html") : null,
      );
    if (o.paused)
      notice(
        "La cola está pausada: no se admite trabajo nuevo.",
        "warn",
        owner ? button("Reanudar cola", () => this.action("/governance/paused/false", {})) : null,
      );
    if (this.view === "overview" || this.view === "budgets") {
      if (!r.quota_fresh)
        notice("Cuota desconocida: sin una lectura reciente no se admite trabajo nuevo.", "warn");
      else if (q.allowed === false) notice("Codex no admite trabajo ahora mismo.", "warn");
    }
    if (this.view === "overview") {
      const pills = E("div", "", "pills");
      const live = liveSnapshots(r).length;
      if (fresh)
        pills.append(pill(live > 1 ? live + " ejecutores conectados" : "WSL conectado", "ok"));
      if (fresh)
        pills.append(
          pill(
            busy ? "Codex trabajando" : s.executor_available ? "Ejecutor disponible" : "Ejecutor no disponible",
            busy || s.executor_available ? "ok" : "warn",
          ),
        );
      if (r.quota_fresh)
        pills.append(
          pill((100 - q.weekly_used_percent).toLocaleString("es-ES") + " % cuota semanal restante"),
        );
      const me = this.data.me;
      if (me && me.role !== "owner")
        pills.append(
          pill(me.user + " · " + ({ reviewer: "revisor", observer: "observador" }[me.role] || me.role)),
        );
      items.push(pills);
    }
    if (this.data.me?.demo)
      notice("Modo demo: los agentes y GitHub son simulados. Nada sale de este equipo y nada se integra.", "warn");
    this.strip.replaceChildren(...items);
  }
  // On the board the column already names the state, so a pill only adds what it does not say.
  taskTile(w, explain = false, inColumn = false) {
    const live = running(this.data.runtime, w.id);
    const card = button(
      "",
      () => this.showTask(w.id),
      "card" + (w.state === "blocked" ? " stop" : waiting(w) ? " you" : ""),
    );
    card.dataset.task = w.id;
    card.append(
      E("strong", w.title),
      E("small", w.repo + (w.issue ? " · #" + w.issue : "")),
    );
    if (explain) card.append(E("span", whyYou(w), "why"));
    if (w.state_since) {
      const age = E("small", stageAge(w.state_since), "age");
      age.dataset.since = w.state_since;
      card.append(age);
    }
    if (!inColumn || live || ["integrating", "integrated", "cancelled"].includes(w.state))
      card.append(
        pill(
          live ? activity[live.run_state] || names[w.state] : names[w.state] || w.state,
          w.state === "blocked" ? "bad" : w.state === "integrated" ? "ok" : "",
        ),
      );
    return card;
  }
  board(parent, work, query) {
    if (!work.length) {
      parent.append(
        E(
          "p",
          query
            ? "Ninguna tarea coincide con la búsqueda."
            : "Sin tareas todavía. Crea una con «Nueva tarea» para abrir su issue y empezar.",
          "empty",
        ),
      );
      return;
    }
    const board = E("div", "", "board");
    for (const c of columns) {
      const closed = c.states.includes("integrated"),
        rows = work
          .filter((w) => c.states.includes(w.state))
          .sort(closed ? byRecentlyClosed : byAttention);
      const col = E("section", "", "column" + (rows.length ? "" : " vacant")),
        head = E("header");
      head.append(E("h2", c.title), E("span", String(rows.length), "count"));
      col.append(head);
      if (!rows.length) col.append(E("p", "Vacía", "none"));
      const limit = closed && !query && !this.allClosed ? closedShown : rows.length;
      for (const w of rows.slice(0, limit)) col.append(this.taskTile(w, false, true));
      if (closed && !query && rows.length > closedShown)
        col.append(
          button(
            this.allClosed ? "Ver solo las últimas " + closedShown : "Ver las " + rows.length,
            () => {
              this.allClosed = !this.allClosed;
              this.signature = "";
              this.render();
            },
            "quiet",
          ),
        );
      board.append(col);
    }
    parent.append(board);
  }
  coordinationReason(reason) {
    if (reason === "initiative_paused")
      return "Molino en pausa para nuevas órdenes.";
    if (reason === "initiative_review_limit")
      return "Esperando espacio en la bandeja de revisión.";
    if (reason.startsWith("dependency_not_integrated:")) {
      const id = reason.split(":")[1],
        task = this.data.work.find((w) => w.id === id);
      return "Esperando integración: " + (task?.title || id);
    }
    return reason;
  }
  initiativeList(parent) {
    const owner = this.data.me?.role === "owner";
    const section = E("section");
    section.append(E("small", "Un molino es una iniciativa larga: se levanta piedra a piedra (tareas) y muro a muro (hitos), y abarca todos los repositorios."));
    const create = E("details");
    create.dataset.key = "new-initiative";
    create.append(E("summary", "Levantar un molino"));
    const title = E("input");
    title.setAttribute("aria-label", "Nombre del molino");
    title.placeholder = "Nombre del molino";
    const goal = E("textarea");
    goal.setAttribute("aria-label", "Objetivo del molino");
    goal.placeholder = "Objetivo y resultado esperado";
    const milestones = E("textarea");
    milestones.setAttribute("aria-label", "Muros (hitos), uno por línea");
    milestones.placeholder = "Muros (hitos), uno por línea";
    const limit = E("input");
    limit.type = "number";
    limit.min = "1";
    limit.max = "10";
    limit.value = "2";
    limit.setAttribute(
      "aria-label",
      "Límite de cambios pendientes de revisión",
    );
    for (const input of [title, goal, milestones, limit])
      input.oninput = () => {
        this.initiativeRequestId = crypto.randomUUID();
      };
    create.append(
      title,
      goal,
      milestones,
      E("p", "Máximo de cambios en desarrollo o pendientes de revisión:"),
      limit,
      button("Guardar el molino", async () => {
        this.initiativeRequestId ??= crypto.randomUUID();
        const titles = milestones.value
          .split("\n")
          .map((v) => v.trim())
          .filter(Boolean);
        try {
          await api("/initiatives", {
            request_id: this.initiativeRequestId,
            title: title.value.trim(),
            goal: goal.value.trim(),
            review_limit: Number(limit.value),
            milestones: titles.map((title, index) => ({
              id: "M" + (index + 1),
              title,
            })),
          });
          this.signature = "";
          await this.poll();
        } catch (e) {
          this.error.textContent = e.message;
        }
      }),
    );
    if (owner) section.append(create);
    if (!this.data.initiatives?.length)
      section.append(
        E(
          "p",
          "Agrupa tareas existentes. Cada una conserva su issue, propuesta y aprobación.",
          "empty",
        ),
      );
    for (const initiative of this.data.initiatives || []) {
      const card = E("article", "", "task"),
        details = E("details");
      details.dataset.key = "initiative-" + initiative.id;
      card.append(
        E("h3", initiative.title),
        pill(
          initiative.complete
            ? "Integrada"
            : initiative.paused
              ? "En pausa"
              : "Activa",
          initiative.complete ? "ok" : "",
        ),
        E(
          "p",
          initiative.integrated +
            "/" +
            initiative.total +
            " tareas integradas · revisión " +
            initiative.review_slots +
            "/" +
            initiative.review_limit,
        ),
      );
      details.append(
        E("summary", "Muros, dependencias y gobierno"),
        E("p", initiative.goal),
      );
      for (const milestone of initiative.milestones)
        details.append(
          E(
            "p",
            milestone.title +
              " · " +
              milestone.integrated +
              "/" +
              milestone.total +
              " integradas" +
              (milestone.complete ? " ✓" : ""),
          ),
        );
      if (owner) details.append(
        button(
          initiative.paused ? "Reanudar el molino" : "Pausar nuevas órdenes",
          () =>
            this.action("/initiatives/" + initiative.id + "/pause", {
              version: initiative.version,
              paused: !initiative.paused,
            }),
        ),
        E(
          "small",
          "La pausa afecta a próximas órdenes. Para interrumpir una ejecución usa los controles de tarea o la pausa global.",
        ),
      );
      const task = E("select"),
        milestone = E("select"),
        predecessor = E("select");
      task.setAttribute("aria-label", "Tarea de " + initiative.title);
      milestone.setAttribute("aria-label", "Muro de " + initiative.title);
      predecessor.setAttribute(
        "aria-label",
        "Predecesora de " + initiative.title,
      );
      const option = (select, value, label) => {
        const node = E("option", label);
        node.value = value;
        select.append(node);
      };
      option(task, "", "Selecciona una tarea");
      for (const work of this.data.work) {
        const owner = this.data.initiatives.find((i) =>
          i.tasks.some((t) => t.id === work.id),
        );
        if (!owner || owner.id === initiative.id)
          option(task, work.id, work.repo + " · " + work.title);
      }
      for (const m of initiative.milestones) option(milestone, m.id, m.title);
      option(predecessor, "", "Sin añadir dependencia");
      for (const work of initiative.tasks)
        option(predecessor, work.id, work.title);
      if (owner) details.append(
        task,
        milestone,
        predecessor,
        button("Vincular tarea o añadir dependencia", () => {
          if (!task.value) {
            this.error.textContent = "Selecciona una tarea.";
            return;
          }
          const existing = initiative.tasks.find((t) => t.id === task.value);
          const deps = [
            ...new Set([
              ...(existing?.depends_on || []),
              ...(predecessor.value ? [predecessor.value] : []),
            ]),
          ];
          return this.action("/initiatives/" + initiative.id + "/tasks", {
            version: initiative.version,
            item: task.value,
            milestone: milestone.value,
            depends_on: deps,
          });
        }),
      );
      for (const work of initiative.tasks) {
        const row = E("details");
        row.dataset.key = "initiative-task-" + work.id;
        row.append(
          E("summary", work.title + " · " + (names[work.state] || work.state)),
        );
        row.append(E("p", work.repo + " · " + work.id));
        if (work.issue)
          row.append(
            link(
              "Issue #" + work.issue,
              "https://github.com/" + work.repo + "/issues/" + work.issue,
            ),
          );
        for (const reason of work.blockers)
          row.append(E("p", this.coordinationReason(reason), "warning"));
        for (const id of work.depends_on) {
          const before = initiative.tasks.find((t) => t.id === id);
          row.append(E("p", "Depende de: " + (before?.title || id)));
          if (
            owner &&
            !["review", "integrating", "integrated", "cancelled"].includes(
              work.state,
            ) &&
            !work.in_progress
          )
            row.append(
              button("Quitar dependencia " + (before?.title || id), () =>
                this.action("/initiatives/" + initiative.id + "/tasks", {
                  version: initiative.version,
                  item: work.id,
                  milestone: work.milestone,
                  depends_on: work.depends_on.filter((d) => d !== id),
                }),
              ),
            );
        }
        details.append(row);
      }
      card.append(details);
      section.append(card);
    }
    parent.append(section);
  }
  acceptance(parent, w, d) {
    const box = E("section", "", "acceptance");
    const covered = d.criteria.filter((c) =>
      ["manual", "exception", "verified"].includes(c.status),
    ).length;
    box.append(
      E(
        "h4",
        d.ready
          ? "Aceptación completa"
          : "Criterios cubiertos · " + covered + " de " + d.criteria.length,
      ),
    );
    const ci = d.verification?.state;
    box.append(
      E(
        "p",
        ci === "missing" || !ci
          ? "CI sin leer: el panel la comprueba con su credencial de GitHub cada 30 segundos."
          : d.verification.reason,
        ci === "failed" || ci === "unavailable" ? "warning" : "muted",
      ),
    );
    for (const other of d.overlaps || [])
      box.append(
        E(
          "p",
          "Toca los mismos archivos que «" + other.title + "» (PR #" + other.pr + "): " + other.files.join(", "),
          "warning",
        ),
      );
    if (d.scope_missing) {
      box.append(
        E(
          "p",
          "Tarea antigua sin criterios registrados. Revisa e importa su alcance antes de aceptarla.",
        ),
        this.data.me?.role === "owner"
          ? button("Importar alcance de esta propuesta", () =>
              this.action("/evidence/" + w.id + "/import-scope", {
                version: w.version,
                sha: w.sha,
              }),
            )
          : "",
      );
    }
    for (const c of d.criteria) {
      const detail = E("details");
      detail.dataset.key = "criterion-" + w.id + "-" + c.id;
      detail.append(
        E(
          "summary",
          c.id +
            " · " +
            (c.description.length > 110
              ? c.description.slice(0, 110) + "…"
              : c.description) +
            " · " +
            ({
              missing: c.method === "test" ? "Sin comprobar" : "Falta tu validación",
              manual: "Validado manualmente",
              exception: "Excepción justificada",
              reject: "Rechazado",
              verified: "Comprobado",
            }[c.status] || c.status),
        ),
      );
      detail.append(E("p", c.description));
      detail.append(
        E(
          "p",
          c.method === "test"
            ? "Comprobaciones previstas: " + (c.checks || []).join(", ")
            : "Validación manual prevista.",
        ),
      );
      if (c.status === "verified")
        detail.append(
          E(
            "p",
            "Los comandos aprobados pasaron en un contenedor limpio y en la CI de este commit. Esto no mide si las pruebas son buenas.",
          ),
        );
      if (c.decision)
        detail.append(
          E(
            "p",
            c.decision.actor +
              " · " +
              new Date(c.decision.at * 1000).toLocaleString("es-ES") +
              " · " +
              c.decision.reason +
              " · Entorno: " +
              c.decision.environment,
          ),
        );
      if (this.data.me?.role === "owner")
        this.decisionForm(detail, w, d, c.id, [
          ...(c.method === "manual" ? [["manual", "Registrar validación manual"]] : []),
          ...(c.status === "verified" ? [] : [["exception", "Justificar una excepción"]]),
          ["reject", "Rechazar criterio"],
        ]);
      box.append(detail);
    }
    box.append(
      E(
        "small",
        "Los informes del agente son declaraciones. Un criterio de prueba se cierra solo cuando sus comandos aprobados pasan en el contenedor y en la CI del commit; si no, necesita tu excepción justificada.",
      ),
    );
    if (d.exceptions)
      box.append(E("p", "Excepciones aceptadas: " + d.exceptions, "warning"));
    parent.append(box);
  }
  // The panel's decision form: every signature names its evidence and environment.
  decisionForm(parent, w, d, id, kinds) {
    const kind = E("select");
    kind.setAttribute("aria-label", "Decisión " + id);
    for (const [value, label] of [["", "Selecciona una decisión"], ...kinds]) {
      const option = E("option", label);
      option.value = value;
      kind.append(option);
    }
    const reason = E("textarea");
    reason.setAttribute("aria-label", "Evidencia o justificación " + id);
    reason.placeholder =
      "Qué has comprobado, resultado y referencia de evidencia; o motivo concreto de la excepción.";
    const environment = E("input");
    environment.setAttribute("aria-label", "Entorno de comprobación " + id);
    environment.placeholder = "Entorno y versión de herramientas utilizados";
    parent.append(
      kind,
      reason,
      environment,
      button("Registrar decisión para " + id, () => {
        if (!kind.value || reason.value.trim().length < 20 || !environment.value.trim()) {
          this.error.textContent =
            "Selecciona la decisión e indica evidencia o justificación y entorno.";
          return;
        }
        return this.action("/evidence/" + w.id + "/decisions", {
          request_id: crypto.randomUUID(),
          version: d.version,
          sha: d.sha,
          basis: d.basis,
          criterion: id,
          kind: kind.value,
          reason: reason.value,
          environment: environment.value,
        });
      }),
    );
  }
  usedSkills(parent, w, used) {
    const roles = [
      ["research", "Napoleon"],
      ["implement", "Boxer"],
      ["review", "Benjamin"],
    ].filter(([role]) => used[role]?.length);
    if (!roles.length) return;
    const details = E("details");
    details.dataset.key = "skills-" + w.id;
    details.append(E("summary", "Skills usadas"));
    for (const [role, name] of roles)
      details.append(
        E("p", name + ": " + used[role].map((s) => s.id + (s.version ? " v" + s.version : "")).join(", ")),
      );
    parent.append(details);
  }
  commandments(parent, w, d) {
    if (!d.commandments?.length) return;
    const count = (status) => d.commandments.filter((c) => c.status === status).length;
    const details = E("details");
    details.dataset.key = "commandments-" + w.id;
    details.open = count("failed") > 0;
    details.append(
      E(
        "summary",
        "Mandamientos · " +
          count("ok") +
          " cumplidos" +
          (count("failed") ? " · " + count("failed") + " incumplidos" : "") +
          (count("pending") ? " · " + count("pending") + " pendientes" : ""),
      ),
    );
    const list = E("ol", "", "commandments");
    for (const c of d.commandments) {
      const row = E("li", "", "commandment " + c.status);
      row.append(
        E("strong", c.text),
        pill(
          {
            ok: "Cumplido",
            failed: "Incumplido",
            pending: "Pendiente",
            not_evaluated: "Sin evaluar",
            not_applicable: "No aplica",
            exception: "Excepción firmada",
          }[c.status] || c.status,
          c.status === "ok" ? "ok" : c.status === "failed" ? "bad" : "",
        ),
        E("small", c.detail || ""),
      );
      if (c.status === "failed" && ["CM-2", "CM-5"].includes(c.id) && this.data.me?.role === "owner")
        this.decisionForm(row, w, d, c.id, [["exception", "Firmar una excepción"]]);
      list.append(row);
    }
    details.append(list);
    parent.append(details);
  }
  diffViewer(w) {
    const d = E("details");
    d.dataset.key = "diff-" + w.id;
    d.append(E("summary", "Ver los cambios · " + w.sha.slice(0, 12)));
    const body = E("div", "", "diffs");
    d.append(body);
    this.diffs ||= {};
    const key = w.id + "@" + w.sha;
    const show = (result) => {
      body.replaceChildren();
      if (result.error) return body.append(E("p", result.error, "error"));
      const total = result.files.reduce((n, f) => n + f.additions + f.deletions, 0);
      body.append(E("p", result.files.length + " archivos · " + total + " líneas cambiadas", "muted"));
      for (const f of result.files) {
        const file = E("details");
        file.append(E("summary", f.file + " · " + f.status + " · +" + f.additions + " −" + f.deletions));
        const pre = E("pre", "", "patch");
        for (const line of (f.patch || "Sin diff textual (binario o demasiado grande)").split("\n"))
          pre.append(
            E(
              "span",
              line + "\n",
              line.startsWith("+") ? "add" : line.startsWith("-") ? "del" : line.startsWith("@@") ? "hunk" : "",
            ),
          );
        file.append(pre);
        body.append(file);
      }
      if (result.truncated) body.append(E("p", "Se muestran 100 archivos; el resto, en GitHub.", "muted"));
    };
    if (this.diffs[key]) show(this.diffs[key]);
    d.addEventListener("toggle", async () => {
      if (!d.open || this.diffs[key]) return;
      body.replaceChildren(E("p", "Cargando diff…", "muted"));
      try {
        this.diffs[key] = await api("/work/" + encodeURIComponent(w.id) + "/diff");
      } catch (e) {
        this.diffs[key] = { error: e.message };
      }
      show(this.diffs[key]);
    });
    return d;
  }
  metricsBlock(side, m) {
    if (!m) return;
    const t = m.totals;
    side.append(E("h2", "Resultados · 8 semanas"));
    const table = E("table");
    for (const [label, value] of [
      ["Propuestas", t.proposals],
      ["Aprobadas", t.approval_rate == null ? "—" : Math.round(t.approval_rate * 100) + " %"],
      ["Correcciones pedidas", t.corrections],
      ["Cosecha · PR integradas", t.integrated],
      ["Ciclo mediano", t.median_cycle_hours == null ? "—" : t.median_cycle_hours.toFixed(1) + " h"],
    ]) {
      const tr = E("tr");
      tr.append(E("th", label), E("td", String(value)));
      table.append(tr);
    }
    for (const [account, c] of Object.entries(m.cost || {})) {
      const tr = E("tr");
      tr.append(
        E("th", "Consumo " + account),
        E(
          "td",
          (c.unit === "usd_micros" ? (c.total / 1e6).toFixed(2) + " USD" : c.total / 100 + " %") +
            " · " + c.runs + " ejecuciones",
        ),
      );
      table.append(tr);
    }
    side.append(table);
  }
  notifyAttention(work) {
    const waiting = work.filter((w) => ["proposed", "review"].includes(w.state));
    const ids = new Set(waiting.map((w) => w.id + ":" + w.state + ":" + w.version));
    if (this.attention && "Notification" in window && Notification.permission === "granted")
      for (const w of waiting)
        if (!this.attention.has(w.id + ":" + w.state + ":" + w.version))
          new Notification("AgentFarm", {
            body: (w.state === "proposed" ? "Propuesta lista: " : "PR lista: ") + w.title,
            tag: "agentfarm-" + w.id,
            icon: "/icons/napoleon.svg",
          }).onclick = () => {
            window.focus();
            location.href = "/tasks?tarea=" + encodeURIComponent(w.id);
          };
    this.attention = ids;
  }
  automation(a) {
    if (!a) return;
    const owner = this.data.me?.role === "owner";
    const section = E("section", "", "panel");
    section.append(E("h2", "Automatización"));
    const levels = Object.fromEntries((a.autonomy || []).map((x) => [x.recipe, x.level]));
    section.append(
      E(
        "p",
        "Los disparadores solo abren propuestas. La autonomía por receta puede aprobar la implementación de cambios de bajo riesgo verificados por tests; integrar siempre exige tu aprobación del SHA.",
        "muted",
      ),
    );
    for (const [recipe, label] of [
      ["bugfix", "Corrección de bugs"],
      ["dependency-update", "Actualización de dependencias"],
      ["test-improvement", "Mejora de pruebas"],
    ]) {
      const auto = levels[recipe] === "auto_implement";
      const row = E("div", "", "setting");
      row.append(
        E("span", label + " · " + (auto ? "implementa sin esperar si todos los criterios son tests" : "requiere aprobación")),
      );
      if (owner) row.append(
        button(auto ? "Volver a manual" : "Permitir autonomía", () => {
          if (
            auto ||
            confirm(
              label +
                ": se implementará sin esperar tu aprobación si todos los criterios son tests. Integrar seguirá exigiendo tu aprobación del SHA. ¿Permitirlo?",
            )
          )
            return this.action("/automation/autonomy", {
              recipe,
              level: auto ? "manual" : "auto_implement",
            });
        }),
      );
      section.append(row);
    }
    const lessons = levels["project-memory"] === "propose_on_feedback";
    const memoryRow = E("div", "", "setting");
    memoryRow.append(
      E("span", "Memoria · " + (lessons ? "tus correcciones abren una propuesta de lección" : "sin propuestas automáticas")),
    );
    if (owner) memoryRow.append(
      button(lessons ? "Desactivar" : "Proponer lecciones", () =>
        this.action("/automation/autonomy", {
          recipe: "project-memory",
          level: lessons ? "manual" : "propose_on_feedback",
        }),
      ),
    );
    section.append(memoryRow);
    section.append(E("h3", "Disparadores por repositorio"));
    if (!a.github_configured)
      section.append(
        E("p", "Falta conectar el panel con GitHub, con una GitHub App o un token (ver docs/TEAM.md). Sin eso no se vigila ningún repositorio.", "notice warn"),
      );
    if (!a.sentry_configured)
      section.append(E("p", "Para vigilar errores de Sentry, el panel necesita su token (ver docs/AUTOMATION.md).", "muted"));
    const kinds = {
      labeled_issue: "Issues con etiqueta agentfarm",
      failing_ci: "CI roto en la rama principal",
      dependabot: "Alertas Dependabot",
      weekly: "Reunión del domingo (revisión semanal)",
      pr_feedback: "Comentarios y checks de sus PR",
      sentry: "Errores nuevos en Sentry",
    };
    for (const t of a.triggers || [])
      section.append(
        E(
          "p",
          t.repo +
            " · " +
            (t.enabled ? t.kinds.map((k) => kinds[k] || k).join(", ") : "desactivado") +
            " · máx. " +
            t.max_new +
            " por pasada" +
            (t.last_run ? " · última " + when(t.last_run * 1000) : "") +
            (t.last_error ? " · error: " + t.last_error : ""),
        ),
      );
    const form = E("details");
    form.dataset.key = "triggers";
    form.append(E("summary", "Configurar disparadores"));
    const repo = E("input");
    repo.placeholder = "owner/repositorio";
    repo.setAttribute("aria-label", "Repositorio");
    form.append(repo);
    const boxes = {};
    for (const [k, label] of Object.entries(kinds)) {
      const l = E("label", "");
      const box = E("input");
      box.type = "checkbox";
      boxes[k] = box;
      l.append(box, document.createTextNode(" " + label));
      form.append(l);
    }
    const sentryOrg = E("input"),
      sentryProject = E("input");
    sentryOrg.placeholder = "organización de Sentry";
    sentryProject.placeholder = "proyecto de Sentry";
    form.append(sentryOrg, sentryProject);
    form.append(
      button("Guardar", () =>
        this.action("/automation/triggers", {
          repository: repo.value.trim(),
          kinds: Object.keys(boxes).filter((k) => boxes[k].checked),
          max_new: 2,
          enabled: true,
          sentry: {
            organization: sentryOrg.value.trim(),
            project: sentryProject.value.trim(),
          },
        }),
      ),
      button("Revisar ahora", () => this.action("/automation/triggers/run", {})),
    );
    if (owner) section.append(form);
    this.live.append(section);
  }
  taskDetail(w) {
    const task = E("article", "", "task"),
      row = E("div", "", "row");
    row.append(
      pill(
        running(this.data.runtime, w.id)
          ? activity[running(this.data.runtime, w.id).run_state] || names[w.state]
          : names[w.state] || w.state,
        w.state === "blocked" ? "bad" : w.state === "integrated" ? "ok" : "",
      ),
    );
    task.append(row);
    if (["proposed", "review"].includes(w.state)) task.append(E("p", whyYou(w)));
    if (w.recipe) {
      const recipe = E("details");
      recipe.dataset.key = "recipe-" + w.id;
      recipe.append(E("summary", w.recipe.title + " · v" + w.recipe.version));
      recipe.append(E("p", w.recipe.purpose));
      for (const step of w.recipe.research_steps || [])
        recipe.append(E("p", step));
      recipe.append(E("strong", "Evidencia prevista"));
      for (const evidence of w.recipe.required_evidence || [])
        recipe.append(E("p", evidence));
      recipe.append(
        E(
          "small",
          "Versión conservada para esta tarea · SHA-256 " + w.recipe.hash,
        ),
      );
      task.append(recipe);
    }
    const links = E("div", "", "links");
    const base = "https://github.com/" + w.repo;
    links.append(link(w.repo, base));
    if (w.issue)
      links.append(link("Issue #" + w.issue, base + "/issues/" + w.issue));
    if (w.branch)
      links.append(
        link("Rama", base + "/tree/" + encodeURIComponent(w.branch)),
      );
    if (w.pr) {
      links.append(
        link("PR #" + w.pr, base + "/pull/" + w.pr),
        link("Pruebas", base + "/pull/" + w.pr + "/checks"),
        link("Diff", base + "/pull/" + w.pr + "/files"),
      );
    }
    task.append(links);
    if (w.pr && w.sha && ["review", "integrating"].includes(w.state))
      task.append(this.diffViewer(w));
    const initiative = this.data.initiatives?.find((i) =>
      i.tasks.some((t) => t.id === w.id),
    );
    if (initiative) {
      const coordinated = initiative.tasks.find((t) => t.id === w.id);
      task.append(
        E(
          "small",
          "Molino: " +
            initiative.title +
            " · Muro: " +
            initiative.milestones.find((m) => m.id === coordinated.milestone)
              ?.title,
        ),
      );
      for (const blocker of coordinated.blockers)
        task.append(E("p", this.coordinationReason(blocker), "warning"));
    }
    if (!w.issue)
      task.append(
        E("small", "Creación de issue pendiente de confirmación de GitHub."),
      );
    if (w.error) task.append(E("p", w.error, "error"));
    const dossier = this.data.dossiers?.find((d) => d.item === w.id);
    if (dossier) {
      this.commandments(task, w, dossier);
      this.acceptance(task, w, dossier);
    }
    this.usedSkills(task, w, json(w.evidence).skills || { research: json(w.proposal).skills });
    if (w.sha)
      links.append(
        link("Descargar expediente", "/api/evidence/" + encodeURIComponent(w.id)),
      );
    const p = json(w.proposal),
      ev = json(w.evidence);
    for (const doubt of p.doubtful_criteria || [])
      task.append(E("p", doubt.id + " quizá no se pueda acreditar. " + doubt.reason, "warning"));
    if (p.previous_changes?.length) {
      const changes = E("details");
      changes.dataset.key = "changes-" + w.id;
      changes.append(E("summary", "Cambios respecto a la versión anterior · " + p.previous_changes.length));
      const text = (value) => (typeof value === "string" ? value : JSON.stringify(value));
      for (const change of p.previous_changes) {
        changes.append(E("strong", change.field));
        changes.append(E("p", "Antes: " + text(change.before), "muted"));
        changes.append(E("p", "Ahora: " + text(change.after)));
      }
      task.append(changes);
    }
    if (p.project_memory) {
      const memory = p.project_memory,
        box = E("details");
      box.dataset.key = "memory-" + w.id;
      box.append(
        E(
          "summary",
          memory.present
            ? "Memoria de proyecto consultada"
            : "Sin archivo de memoria en la base consultada",
        ),
      );
      if (/^[a-f0-9]{40}$/.test(memory.base_sha))
        box.append(
          link(
            "Commit de origen · " + memory.base_sha.slice(0, 8),
            base + "/commit/" + memory.base_sha,
          ),
        );
      if (memory.present) {
        box.append(E("small", "SHA-256 " + memory.hash));
        for (const entry of memory.document.entries) {
          box.append(
            E(
              "h4",
              ({
                decision: "Decisión",
                constraint: "Restricción",
                rejection: "Motivo de rechazo",
              }[entry.kind] || entry.kind) +
                " · " +
                entry.id,
            ),
          );
          box.append(E("p", entry.summary), E("p", entry.rationale, "muted"));
          for (const ref of entry.references || [])
            box.append(link("Referencia", ref));
        }
        box.append(
          E(
            "small",
            "Contexto observado, no aprobación ni evidencia verificada. Los cambios se proponen con la receta «Actualizar la memoria del proyecto».",
          ),
        );
      }
      task.append(box);
    }
    if (ev.project_memory_validation) {
      const validation = ev.project_memory_validation;
      task.append(
        E(
          "small",
          validation.status === "passed"
            ? "La memoria del proyecto tiene un formato válido; eso no demuestra que funcione."
            : "Memoria del proyecto inválida: " + validation.error,
          validation.status === "passed" ? "muted" : "error",
        ),
      );
    }
    task.append(E("p", ev.summary || p.summary || w.request));
    const detail = E("details");
    detail.dataset.key = w.id;
    detail.append(E("summary", "Alcance, antes/después y evidencias"));
    if (p.acceptance) detail.append(E("p", p.acceptance));
    if (Array.isArray(p.criteria)) {
      const criteria = E("ul");
      for (const criterion of p.criteria)
        criteria.append(
          E(
            "li",
            criterion.id +
              ": " +
              criterion.description +
              " · " +
              (criterion.method === "test"
                ? "Pruebas: " + (criterion.checks || []).join(", ")
                : "Validación manual"),
          ),
        );
      detail.append(E("h4", "Criterios de la propuesta"), criteria);
    }
    const sources = (Array.isArray(p.sources) ? p.sources : [p.sources])
      .filter(Boolean)
      .map((x) =>
        typeof x === "string" ? { url: x } : { url: x.url, reason: x.reason || x.motivo },
      )
      .filter((x) => x.url);
    if (sources.length) {
      const list = E("ul");
      for (const x of sources) {
        const li = E("li");
        li.append(
          /^https?:\/\//.test(x.url)
            ? link(x.url, x.url)
            : document.createTextNode(x.url),
        );
        if (x.reason) li.append(document.createTextNode(" · " + x.reason));
        list.append(li);
      }
      detail.append(E("h4", "Fuentes"), list);
    }
    const comparison = E("div", "", "compare");
    const delivered = ev.sha && ev.sha === w.sha && ev.to_be;
    for (const [name, key] of [
      ["As-is · base investigada", "as_is"],
      [
        delivered ? "To-be · resultado declarado" : "To-be · propuesto",
        "to_be",
      ],
    ]) {
      const col = E("section");
      col.append(E("h3", name));
      const diagram = E("div", "", "diagram");
      const source =
        key === "as_is" ? p.as_is : delivered ? ev.to_be : p.to_be;
      diagram.append(E("pre", source || "Pendiente de investigación"));
      col.append(diagram);
      comparison.append(col);
      if (source) this.draw(diagram, source);
    }
    detail.append(comparison);
    if (p.base_sha)
      detail.append(
        link(
          "Commit base: " + p.base_sha.slice(0, 12),
          base + "/commit/" + p.base_sha,
        ),
      );
    if (p.to_be) {
      const proposed = E("details");
      proposed.dataset.key = "design-" + w.id;
      proposed.append(
        E(
          "summary",
          (w.state === "proposed" ? "Diseño propuesto" : "Diseño aprobado") +
            " · propuesta v" +
            w.version,
        ),
        E("pre", p.to_be),
      );
      detail.append(proposed);
    }
    if (p.mcp?.length)
      detail.append(
        E("p", "Herramientas MCP para investigar y revisar: " + p.mcp.join(", "), "muted"),
      );
    if (p.sandbox)
      detail.append(
        E(
          "p",
          p.sandbox.mode === "host"
            ? "Entorno de pruebas: host del ejecutor (requiere permiso del operador)"
            : "Entorno de pruebas: contenedor " +
                p.sandbox.image +
                " · red " +
                ({ setup: "solo en setup", none: "sin red", always: "siempre" }[
                  p.sandbox.network
                ] || p.sandbox.network) +
                " · " +
                p.sandbox.cpus +
                " CPU · " +
                p.sandbox.memory_mb +
                " MB · " +
                p.sandbox.timeout_minutes +
                " min",
          "muted",
        ),
      );
    if (ev.unit || p.unit_test) {
      const t = E("details");
      t.dataset.key = "unit-" + w.id;
      t.append(
        E(
          "summary",
          "Squealer dice · unitarias: " + (ev.unit?.status || "Planificadas"),
        ),
        E("pre", ev.unit ? JSON.stringify(ev.unit, null, 2) : p.unit_test),
      );
      detail.append(t);
    }
    if (ev.integration || p.integration_test) {
      const t = E("details");
      t.dataset.key = "integration-" + w.id;
      t.append(
        E(
          "summary",
          "Squealer dice · integración: " +
            (ev.integration?.status || "Planificadas"),
        ),
        E(
          "pre",
          ev.integration
            ? JSON.stringify(ev.integration, null, 2)
            : p.integration_test,
        ),
      );
      detail.append(t);
    }
    if (ev.review) {
      const t = E("details");
      t.dataset.key = "review-" + w.id;
      t.append(
        E(
          "summary",
          "Revisión de Benjamin · " +
            (ev.review.passed ? "Sin bloqueos" : "Con incidencias"),
        ),
        E("pre", JSON.stringify(ev.review, null, 2)),
      );
      detail.append(t);
    }
    if (w.sha) detail.append(E("p", "Commit: " + w.sha));
    task.append(detail);
    const actions = E("div", "", "actions");
    if (w.state === "proposed") {
      const estimate = expectedCost(this.data.costs, w);
      if (estimate) task.append(E("p", estimate, "muted"));
    }
    if (w.state === "proposed" && this.data.me?.role !== "observer")
      actions.append(
        button(
          "Implementar propuesta v" + w.version,
          () =>
            this.action("/work/" + w.id + "/actions/implement", {
              version: w.version,
            }),
          true,
        ),
      );
    if (w.state === "review" && this.data.me?.role === "owner") {
      const approve = E("details");
      approve.dataset.key = "approve-" + w.id;
      approve.append(
        E("summary", "Aprobar e integrar"),
        E(
          "p",
          "Se fusionará la PR #" +
            w.pr +
            " con el commit " +
            w.sha +
            ". GitHub y las pruebas deben confirmarlo.",
        ),
        button(
          "Confirmar integración de este commit",
          () =>
            this.action("/work/" + w.id + "/actions/merge", { sha: w.sha }),
          true,
        ),
      );
      if (dossier?.ready) actions.append(approve);
      else
        actions.append(
          E(
            "p",
            "Integración bloqueada: faltan criterios o mandamientos por cubrir.",
            "error",
          ),
        );
    }
    const canAct = this.data.me?.role !== "observer";
    if (canAct && ["proposed", "review", "blocked"].includes(w.state)) {
      const correct = E("details");
      correct.dataset.key = "correct-" + w.id;
      correct.append(E("summary", "Pedir correcciones"));
      const feedback = E("textarea");
      feedback.placeholder = "Qué debe corregirse";
      correct.append(
        feedback,
        button("Investigar correcciones", () =>
          this.action("/work/" + w.id + "/actions/correct", {
            feedback: feedback.value,
          }),
        ),
      );
      actions.append(correct);
    }
    if (canAct && w.state === "blocked")
      actions.append(
        button("Reintentar operación", () =>
          this.action("/work/" + w.id + "/actions/retry", {}),
        ),
      );
    if (canAct && ["queued", "approved"].includes(w.state))
      actions.append(
        button("Cancelar", () =>
          this.action("/work/" + w.id + "/actions/cancel", {}),
        ),
      );
    task.append(actions);
    return task;
  }
  async draw(target, source) {
    try {
      const { default: mermaid } = await import(
        "/vendor/mermaid/mermaid.esm.min.mjs"
      );
      mermaid.initialize({
        startOnLoad: false,
        securityLevel: "strict",
        theme: "neutral",
      });
      const { svg } = await mermaid.render(
        "diagram" + crypto.randomUUID().replaceAll("-", ""),
        source,
      );
      if (target.isConnected) target.innerHTML = svg;
    } catch {}
  }
  console(events) {
    const details = E("details");
    details.dataset.key = "console";
    details.append(
      E("summary", "Consola de actividad · " + events.length + " eventos"),
    );
    const box = E("div", "", "console");
    for (const ev of events.slice(-80)) {
      const line = E("details");
      line.dataset.key = "event-" + ev.id;
      line.append(
        E(
          "summary",
          when(ev.at) +
            " · " +
            ev.worker +
            " · " +
            ev.kind,
        ),
        E("pre", ev.text),
      );
      box.append(line);
    }
    details.append(box);
    this.live.append(details);
  }
}
customElements.define("farm-console", FarmConsole);
customElements.define("farm-advanced", FarmAdvanced);

// Compatibility with Mateu definitions cached by earlier panel versions.
customElements.define("agentfarm-monitor", class extends FarmConsole {});
customElements.define("agentfarm-registry", class extends FarmConsole {});
