const style = "/farm-console.css?v=1";
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
const hat = () => {
  const e = E("span");
  e.innerHTML =
    '<svg class="hat" viewBox="0 0 64 44" role="img" aria-label="Bicornio de Napoleon"><path d="M3 34Q13 20 22 24Q25 5 32 5Q39 5 42 24Q51 20 61 34L54 39H10Z" fill="#203650" stroke="#101e30" stroke-width="2"/><path d="M10 34Q32 28 54 34" fill="none" stroke="#d6ad59" stroke-width="3"/><circle cx="32" cy="23" r="5" fill="#e9d9b7"/><circle cx="32" cy="23" r="3" fill="#3568a5"/></svg>';
  return e;
};
const svgIcon = (label, body, viewBox = "0 0 64 44") => {
  const e = E("span");
  e.innerHTML = `<svg class="hat" viewBox="${viewBox}" role="img" aria-label="${label}">${body}</svg>`;
  return e;
};
const horse = (label = "Caballo Boxer") =>
  svgIcon(
    label,
    '<path d="M18 40V27Q14 22 17 14L24 6L27 9L30 4L33 9Q46 11 48 24L56 30L52 36L44 31V40H38V31H26V40Z" fill="#7a5233" stroke="#3d2817" stroke-width="2" stroke-linejoin="round"/><path d="M27 9Q22 16 24 24" fill="none" stroke="#3d2817" stroke-width="3"/><circle cx="36" cy="16" r="2" fill="#f6f7f9"/>',
  );
const clover = () =>
  svgIcon(
    "Trébol Clover",
    '<g fill="#3f8f4f" stroke="#1f5a2c" stroke-width="2"><circle cx="32" cy="13" r="8"/><circle cx="22" cy="23" r="8"/><circle cx="42" cy="23" r="8"/></g><path d="M32 24Q33 34 40 41" fill="none" stroke="#1f5a2c" stroke-width="3" stroke-linecap="round"/>',
  );
const snowflake = () =>
  svgIcon(
    "Copo Snowball",
    '<g stroke="#3568a5" stroke-width="3" stroke-linecap="round"><path d="M32 4V40M16 13L48 31M16 31L48 13"/><path d="M27 8L32 12L37 8M27 36L32 32L37 36"/></g>',
  );
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
function button(label, fn, primary = false) {
  const b = E("button", label, primary ? "primary" : "");
  b.type = "button";
  b.onclick = fn;
  return b;
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
      let extra = null;
      if (route === "budgets")
        extra = await Promise.all(
          (overview.budget_accounts || []).map((a) =>
            api("/budget/" + encodeURIComponent(a.account)),
          ),
        );
      if (route === "governance") extra = await api("/audit");
      const dossiers = ["overview", "tasks", "governance"].includes(route)
        ? await api("/evidence")
        : [];
      const providers = route === "budgets" ? await api("/providers") : null;
      const automation =
        route === "governance" ? await api("/automation").catch(() => null) : null;
      const metrics =
        route === "overview" ? await api("/work/metrics").catch(() => null) : null;
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
        providers,
        initiatives,
        automation,
        metrics,
        costs,
        me: this.me,
      };
      this.notifyAttention(work);
      this.render();
      this.error.textContent = "";
    } catch (e) {
      if (this.error)
        this.error.textContent =
          e.message + " Los datos visibles pueden estar desactualizados.";
    } finally {
      this.loading = false;
    }
  }
  layout() {
    this.content.replaceChildren();
    const titles = {
      overview: "Tu granja, en directo",
      nodes: "Nodos dirigentes",
      workers: "Trabajadores Boxer",
      tasks: "Del objetivo a la PR",
      budgets: "Clover · presupuesto",
      governance: "Gobierno de la granja",
    };
    const header = E("header");
    header.append(
      E("p", titles[this.view] || titles.overview, "lead"),
      button("Actualizar", () => this.poll()),
    );
    this.error = E("p", "", "error");
    this.error.setAttribute("role", "status");
    this.strip = E("div", "", "strip");
    this.body = E("div");
    this.content.append(header, this.error, this.strip, this.body);
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
      header.insertBefore(this.repositorySelect, header.lastChild);
    }
    if (["overview", "tasks"].includes(this.view)) {
      this.form = this.requestForm();
      this.body.append(this.form);
    }
    this.live = E("div");
    this.body.append(this.live);
    if (["nodes", "workers", "budgets"].includes(this.view))
      this.body.append(this.advanced());
  }
  advanced() {
    const d = E("details");
    d.append(
      E(
        "summary",
        this.view === "budgets" ? "Conciliación avanzada" : "Registrar agente",
      ),
    );
    const values = {};
    const fields =
      this.view === "budgets"
        ? ["account", "operation", "data"]
        : this.view === "nodes"
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
          if (this.view === "budgets")
            result = await api("/budget/" + values.operation.value, {
              ...JSON.parse(values.data.value),
              account: values.account.value,
            });
          else
            result = await api(
              "/" + this.view,
              Object.fromEntries(
                Object.entries(values).map(([k, v]) => [k, v.value]),
              ),
            );
          output.textContent = JSON.stringify(result, null, 2);
          this.signature = "";
          await this.poll();
        } catch (e) {
          output.textContent = e.message;
        }
      }),
      output,
    );
    return d;
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
  requestForm() {
    const d = E("details");
    d.open = this.view === "tasks";
    d.append(E("summary", "Nueva idea o tarea"));
    const section = E("section");
    const repo = E("input");
    repo.value = lastRepo();
    repo.placeholder = "owner/repositorio";
    repo.required = true;
    const title = E("input");
    title.placeholder = "Qué quieres mejorar";
    const request = E("textarea");
    request.placeholder =
      "Describe el objetivo. Napoleon investigará y te propondrá un alcance antes de implementarlo.";
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
            this.error.textContent = "Indica el repositorio como owner/nombre.";
            return;
          }
          if (!title.value.trim() || !request.value.trim()) {
            this.error.textContent = "Escribe un título y una petición.";
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
            this.signature = "";
            await this.poll();
          } catch (e) {
            this.error.textContent = e.message;
          }
        },
        true,
      ),
    );
    d.append(section);
    return d;
  }
  render() {
    const { runtime: r, overview: o, work: allWork } = this.data,
      s = r.snapshot || {},
      fresh = r.fresh,
      q = s.quota || {},
      busy = liveSnapshots(r).some((x) => !!x.codex_pid);
    let work = allWork;
    if (this.repositorySelect && ["overview", "tasks"].includes(this.view)) {
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
    this.strip.replaceChildren(
      pill(
        fresh
          ? liveSnapshots(r).length > 1
            ? liveSnapshots(r).length + " ejecutores conectados"
            : "WSL conectado"
          : "WSL sin señal",
        fresh ? "ok" : "bad",
      ),
      pill(
        busy
          ? "Codex trabajando"
          : fresh && s.executor_available
            ? "Ejecutor disponible"
            : "Ejecutor no disponible",
        busy ? "ok" : "",
      ),
      pill(
        o.paused ? "Admisión pausada" : "Admisión habilitada",
        o.paused ? "warn" : "",
      ),
      pill(
        r.quota_fresh
          ? (100 - q.weekly_used_percent).toLocaleString("es-ES") +
              " % cuota semanal restante"
          : "Cuota actual desconocida",
        r.quota_fresh ? "" : "warn",
      ),
    );
    const me = this.data.me;
    if (me && me.role !== "owner")
      this.strip.append(
        pill(me.user + " · " + ({ reviewer: "revisor", observer: "observador" }[me.role] || me.role)),
      );
    if (r.quota_fresh && q.allowed === false)
      this.strip.append(pill("Codex no admite trabajo", "warn"));
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
      this.view === "nodes"
        ? o.nodes
        : o.nodes.map((n) => [n.id, n.enabled, n.reported_state]),
      this.view === "workers"
        ? o.workers
        : o.workers.map((w) => [w.id, w.enabled, w.reported_state]),
      o.paused,
      this.data.extra,
      this.data.dossiers,
      this.data.providers,
      this.data.initiatives,
      (r.events || []).at(-1)?.id,
    ]);
    if (signature === this.signature) return;
    this.signature = signature;
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
    const drafts = [...this.live.querySelectorAll("details[data-key]")].map(
      (d) => [
        d.dataset.key,
        [...d.querySelectorAll("textarea,input,select")].map((i) => i.value),
      ],
    );
    const opened = [...this.live.querySelectorAll("details[open]")]
      .map((e) => e.dataset.key)
      .filter(Boolean);
    this.live.replaceChildren();
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
        [
          "Revisor",
          () => horse("Caballo revisor"),
          phase("reviewing") ? "Revisando" : "Disponible",
        ],
      ]) {
        if (map.children.length) map.append(E("span", "→", "arrow"));
        const a = E("div", "", "agent");
        const avatar = E("span", "", "avatar");
        avatar.append(icon());
        a.append(
          avatar,
          E("strong", name),
          E("p", fresh ? state : "Sin señal", "muted"),
        );
        map.append(a);
      }
      this.live.append(map);
      const split = E("div", "", "split"),
        main = E("section"),
        side = E("aside");
      main.append(E("h2", "Trabajo y decisiones"));
      this.workList(main, work);
      side.append(
        E("h2", "Siguiente paso"),
        E(
          "p",
          o.paused
            ? "La granja está pausada. Reanuda para atender la cola."
            : work.some((w) => w.state === "proposed")
              ? "Revisa y aprueba el alcance de una propuesta."
              : work.some((w) => w.state === "review")
                ? "Revisa las pruebas y la PR antes de integrar."
                : busy
                  ? s.task_title || "Hay una ejecución en curso."
                  : "Escribe una idea o solicita sugerencias. Napoleon investigará antes de cambiar código.",
        ),
      );
      side.append(
        button(o.paused ? "Reanudar cola" : "Pausar agentes", () =>
          this.action("/governance/paused/" + !o.paused, {}),
        ),
      );
      const suggest = button("Investigar posibles mejoras", () => {
        this.form.open = true;
        const inputs = this.form.querySelectorAll("input");
        inputs[1].value = "Proponer mejoras del proyecto";
        this.form.querySelector("textarea").value =
          "Analiza el estado actual y propone mejoras priorizadas y viables, con fuentes cuando sean necesarias. No implementes nada; esperaré para seleccionar y aprobar el alcance.";
        this.form.scrollIntoView({ block: "start" });
      });
      side.append(E("p", ""), suggest);
      if ("Notification" in window && Notification.permission === "default")
        side.append(
          button("Avisarme en este navegador", async () => {
            await Notification.requestPermission();
            this.signature = "";
            this.render();
          }),
        );
      this.metricsBlock(side, this.data.metrics);
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
            : "La última lectura no acredita el saldo actual.",
        ),
        E(
          "small",
          q.observed_at
            ? "Última lectura: " +
                new Date(q.observed_at).toLocaleString("es-ES")
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
      split.append(main, side);
      this.live.append(split);
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
      this.initiativeList(this.live);
      this.workList(this.live, work);
      const legacy = E("details");
      legacy.append(E("summary", "Tareas del piloto anterior"));
      for (const t of o.tasks || [])
        legacy.append(
          E(
            "p",
            t.id +
              " · " +
              t.state +
              " · " +
              (t.candidate_sha || "Sin candidato"),
          ),
        );
      this.live.append(legacy);
    } else if (this.view === "nodes" || this.view === "workers") {
      const workers = this.view === "workers",
        rows = workers ? o.workers : o.nodes;
      const table = E("table");
      const head = E("tr");
      ["Agente", "Estado", "Última señal", "Gobierno"].forEach((x) =>
        head.append(E("th", x)),
      );
      table.append(head);
      for (const a of rows || []) {
        const tr = E("tr"),
          name = E("td");
        name.append(
          workers ? horse() : a.role === "snowball" ? snowflake() : hat(),
          document.createTextNode(a.id),
        );
        const age = Date.now() / 1000 - Number(a.heartbeat || 0);
        const alive = fresh && age >= 0 && age <= 12;
        const state =
          a.enabled === 0
            ? "Deshabilitado"
            : !alive
              ? "Sin señal"
              : a.reported_state === "busy"
                ? "Trabajando"
                : "Disponible";
        const actions = E("td");
        actions.append(
          button(a.enabled ? "Deshabilitar" : "Habilitar", () =>
            this.action(
              "/entities/" +
                (workers ? "worker" : "node") +
                "/" +
                encodeURIComponent(a.id) +
                "/enabled/" +
                !a.enabled,
              {},
            ),
          ),
        );
        tr.append(
          name,
          E("td", state),
          E(
            "td",
            a.heartbeat
              ? new Date(a.heartbeat * 1000).toLocaleTimeString("es-ES")
              : "Nunca",
          ),
          actions,
        );
        table.append(tr);
      }
      this.live.append(table);
    } else if (this.view === "budgets") {
      this.live.append(
        E("h2", "Cuota del proveedor"),
        E(
          "p",
          r.quota_fresh
            ? 100 - q.weekly_used_percent + " % semanal restante"
            : "Cuota actual desconocida",
        ),
        E(
          "small",
          q.observed_at
            ? "Última observación: " +
                new Date(q.observed_at).toLocaleString("es-ES")
            : "Sin observación",
        ),
      );
      const adapters = E("section");
      adapters.append(E("h2", "Motores y capacidades"));
      for (const provider of this.data.providers?.providers || [])
        adapters.append(
          E(
            "p",
            provider.id +
              " · " +
              (provider.state === "implemented"
                ? "Ejecutor integrado"
                : "Bloqueado: integración pendiente") +
              " · Cuota: " +
              ({
                unknown: "desconocida",
                "api-cost-observed": "coste API en USD por turno (cuenta Clover api)",
              }[provider.quota] || "suscripción observada"),
          ),
        );
      adapters.append(
        E(
          "small",
          "Sin cambio automático de proveedor ni pagos adicionales. Económico y equilibrado usan la misma política actual; revisión reforzada espera al verificador separado.",
        ),
      );
      this.live.append(adapters);
      const ledger = E("section");
      ledger.append(E("h2", "Reservas y margen autorizado"));
      this.live.append(ledger);
      for (const a of this.data.extra || []) {
        ledger.append(E("h3", a.account));
        for (const w of a.windows || [])
          ledger.append(
            E(
              "p",
              w.period +
                " · consumido " +
                (a.unit === "usd_micros"
                  ? (w.used / 1000000).toFixed(6) + " USD"
                  : w.used / 100 + " %") +
                " · reservado " +
                (a.unit === "usd_micros"
                  ? (w.reserved / 1000000).toFixed(6) + " USD"
                  : w.reserved / 100 + " %") +
                " · margen " +
                (a.unit === "usd_micros"
                  ? (w.remaining / 1000000).toFixed(6) + " USD"
                  : w.remaining / 100 + " %"),
            ),
          );
        if (a.reasons?.length)
          ledger.append(E("p", a.reasons.join(" · "), "error"));
      }
    } else {
      this.live.append(
        E("h2", "Control de ejecución"),
        link("Conectar ejecutor", "/setup.html"),
        button(o.paused ? "Reanudar cola" : "Pausar agentes", () =>
          this.action("/governance/paused/" + !o.paused, {}),
        ),
        E(
          "p",
          "Cada propuesta necesita tu aprobación. Cada integración queda vinculada al commit revisado.",
        ),
      );
      this.automation(this.data.automation);
      const audit = E("section");
      this.live.append(audit);
      audit.append(E("h2", "Últimas decisiones"));
      for (const row of (this.data.extra || []).slice(0, 30))
        audit.append(
          E(
            "p",
            new Date(row.at).toLocaleString("es-ES") +
              " · " +
              row.action +
              " · " +
              row.subject,
          ),
        );
    }
    for (const d of this.live.querySelectorAll("details")) {
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
  }
  coordinationReason(reason) {
    if (reason === "initiative_paused")
      return "Iniciativa en pausa para nuevas órdenes.";
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
    const section = E("section");
    section.append(E("h2", "Iniciativas · todos los repositorios"));
    const create = E("details");
    create.dataset.key = "new-initiative";
    create.append(E("summary", "Crear iniciativa"));
    const title = E("input");
    title.setAttribute("aria-label", "Nombre de la iniciativa");
    title.placeholder = "Nombre de la iniciativa";
    const goal = E("textarea");
    goal.setAttribute("aria-label", "Objetivo de la iniciativa");
    goal.placeholder = "Objetivo y resultado esperado";
    const milestones = E("textarea");
    milestones.setAttribute("aria-label", "Hitos, uno por línea");
    milestones.placeholder = "Hitos, uno por línea";
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
      button("Guardar iniciativa", async () => {
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
    section.append(create);
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
        E("summary", "Hitos, dependencias y gobierno"),
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
      details.append(
        button(
          initiative.paused ? "Reanudar iniciativa" : "Pausar nuevas órdenes",
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
      milestone.setAttribute("aria-label", "Hito de " + initiative.title);
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
      details.append(
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
      ["manual", "exception", "test"].includes(c.status),
    ).length;
    box.append(
      E(
        "h4",
        d.ready
          ? "Aceptación completa"
          : "Decisiones pendientes · " + covered + "/" + d.criteria.length,
      ),
    );
    if (d.scope_missing) {
      box.append(
        E(
          "p",
          "Candidato histórico sin criterios registrados. Revisa e importa su alcance antes de aceptarlo.",
        ),
        button("Importar alcance de esta propuesta", () =>
          this.action("/evidence/" + w.id + "/import-scope", {
            version: w.version,
            sha: w.sha,
          }),
        ),
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
              missing: "Sin evidencia",
              manual: "Validado manualmente",
              exception: "Excepción justificada",
              reject: "Rechazado",
              test: "Pruebas independientes",
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
      const kind = E("select");
      kind.setAttribute("aria-label", "Decisión " + c.id);
      for (const [value, label] of [
        ["", "Selecciona una decisión"],
        ...(c.method === "manual"
          ? [["manual", "Registrar validación manual"]]
          : []),
        ["exception", "Justificar una excepción"],
        ["reject", "Rechazar criterio"],
      ]) {
        const option = E("option", label);
        option.value = value;
        kind.append(option);
      }
      const reason = E("textarea");
      reason.setAttribute("aria-label", "Evidencia o justificación " + c.id);
      reason.placeholder =
        "Qué has comprobado, resultado y referencia de evidencia; o motivo concreto de la excepción.";
      const environment = E("input");
      environment.setAttribute("aria-label", "Entorno de comprobación " + c.id);
      environment.placeholder = "Entorno y versión de herramientas utilizados";
      detail.append(
        kind,
        reason,
        environment,
        button("Registrar decisión para " + c.id, () => {
          if (
            !kind.value ||
            reason.value.trim().length < 20 ||
            !environment.value.trim()
          ) {
            this.error.textContent =
              "Selecciona la decisión e indica evidencia o justificación y entorno.";
            return;
          }
          return this.action("/evidence/" + w.id + "/decisions", {
            request_id: crypto.randomUUID(),
            version: d.version,
            sha: d.sha,
            basis: d.basis,
            criterion: c.id,
            kind: kind.value,
            reason: reason.value,
            environment: environment.value,
          });
        }),
      );
      box.append(detail);
    }
    box.append(
      E(
        "small",
        "Los informes de Boxer son declaraciones. Este incremento permite validaciones humanas y excepciones; el verificador automático separado sigue pendiente.",
      ),
    );
    if (d.exceptions)
      box.append(E("p", "Excepciones aceptadas: " + d.exceptions, "warning"));
    parent.append(box);
  }
  diffViewer(w) {
    const d = E("details");
    d.dataset.key = "diff-" + w.id;
    d.append(E("summary", "Ver cambios del candidato " + w.sha.slice(0, 12)));
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
      ["PR integradas", t.integrated],
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
          });
    this.attention = ids;
  }
  automation(a) {
    if (!a) return;
    const section = E("section");
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
      const row = E("div", "", "row");
      row.append(
        E("span", label + " · " + (auto ? "implementa sin esperar si todos los criterios son tests" : "requiere tu aprobación")),
        button(auto ? "Volver a manual" : "Permitir autonomía", () =>
          this.action("/automation/autonomy", {
            recipe,
            level: auto ? "manual" : "auto_implement",
          }),
        ),
      );
      section.append(row);
    }
    const lessons = levels["project-memory"] === "propose_on_feedback";
    const memoryRow = E("div", "", "row");
    memoryRow.append(
      E("span", "Memoria · " + (lessons ? "tus correcciones abren una propuesta de lección" : "sin propuestas automáticas")),
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
        E("p", "Configura AGENTFARM_GITHUB_TOKEN_FILE en el panel para leer GitHub.", "error"),
      );
    const kinds = {
      labeled_issue: "Issues con etiqueta agentfarm",
      failing_ci: "CI roto en la rama principal",
      dependabot: "Alertas Dependabot",
      weekly: "Revisión semanal",
      pr_feedback: "Comentarios y checks de sus PR",
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
            (t.last_run ? " · última " + new Date(t.last_run * 1000).toLocaleString("es-ES") : "") +
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
    form.append(
      button("Guardar", () =>
        this.action("/automation/triggers", {
          repository: repo.value.trim(),
          kinds: Object.keys(boxes).filter((k) => boxes[k].checked),
          max_new: 2,
          enabled: true,
        }),
      ),
      button("Revisar ahora", () => this.action("/automation/triggers/run", {})),
    );
    section.append(form);
    this.live.append(section);
  }
  workList(parent, work) {
    if (!work.length) {
      parent.append(
        E(
          "p",
          "Sin tareas nuevas. Crea una idea para abrir su issue y empezar.",
          "empty",
        ),
      );
      return;
    }
    const ordered = [...work].sort(
      (a, b) =>
        (attention[a.state] ?? 3) - (attention[b.state] ?? 3) ||
        Number(b.created || 0) - Number(a.created || 0),
    );
    for (const w of ordered) {
      const task = E("article", "", "task"),
        row = E("div", "", "row");
      row.append(
        E("h3", w.title),
        pill(
          running(this.data.runtime, w.id)
            ? {
                researching: "Napoleon investigando",
                implementing: "Boxer implementando",
                testing: "Ejecutando tests",
                reviewing: "Revisión independiente",
                integrating: "Integrando PR",
              }[running(this.data.runtime, w.id).run_state] || names[w.state]
            : names[w.state] || w.state,
          w.state === "blocked" ? "bad" : w.state === "integrated" ? "ok" : "",
        ),
      );
      task.append(row);
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
            "Iniciativa: " +
              initiative.title +
              " · Hito: " +
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
      if (dossier) this.acceptance(task, w, dossier);
      if (w.sha)
        links.append(
          link("Expediente JSON", "/api/evidence/" + encodeURIComponent(w.id)),
        );
      const p = json(w.proposal),
        ev = json(w.evidence);
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
              ? "Esquema de memoria candidata válido · no acredita aceptación funcional."
              : "Memoria candidata inválida: " + validation.error,
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
            (w.state === "proposed" ? "Diseño To-be propuesto" : "Diseño To-be aprobado") +
              " · propuesta v" +
              w.version,
          ),
          E("pre", p.to_be),
        );
        detail.append(proposed);
      }
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
            "Unitarias declaradas · " + (ev.unit?.status || "Planificadas"),
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
            "Integración declarada · " +
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
            "Revisión declarada · " +
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
              "Integración bloqueada: completa la aceptación del candidato.",
              "error",
            ),
          );
      }
      if (["proposed", "review", "blocked"].includes(w.state)) {
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
      if (w.state === "blocked")
        actions.append(
          button("Reintentar operación", () =>
            this.action("/work/" + w.id + "/actions/retry", {}),
          ),
        );
      if (["queued", "approved"].includes(w.state))
        actions.append(
          button("Cancelar", () =>
            this.action("/work/" + w.id + "/actions/cancel", {}),
          ),
        );
      task.append(actions);
      parent.append(task);
    }
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
          new Date(ev.at).toLocaleTimeString("es-ES") +
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

// Compatibility with Mateu definitions cached by earlier panel versions.
customElements.define("agentfarm-monitor", class extends FarmConsole {});
customElements.define("agentfarm-registry", class extends FarmConsole {});
