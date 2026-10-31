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
      this.data = { runtime, overview, work, extra, dossiers, providers };
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
      E("h1", titles[this.view] || titles.overview),
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
    repo.value = "alejbormeg/forja";
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
    const id = crypto.randomUUID();
    section.append(
      button(
        "Crear issue e investigar",
        async () => {
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
            });
            this.selectedRepo = repo.value.trim();
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
      busy = fresh && !!s.codex_pid;
    let work = allWork;
    if (this.repositorySelect && ["overview", "tasks"].includes(this.view)) {
      const repos = [...new Set(allWork.map((w) => w.repo))];
      if (this.selectedRepo === undefined)
        this.selectedRepo = repos.includes("alejbormeg/forja")
          ? "alejbormeg/forja"
          : "";
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
      pill(fresh ? "WSL conectado" : "WSL sin señal", fresh ? "ok" : "bad"),
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
    if (r.quota_fresh && q.allowed === false)
      this.strip.append(pill("Codex no admite trabajo", "warn"));
    const signature = JSON.stringify([
      this.view,
      s.run_state,
      s.task_id,
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
      for (const [name, emoji, state] of [
        [
          "Napoleon",
          null,
          fresh
            ? s.active_worker === "napoleon-wsl"
              ? "Investigando"
              : "Coordina la cola"
            : "Sin señal",
        ],
        [
          "Boxer",
          "🐴",
          busy && s.active_worker === "boxer-forja"
            ? "Implementando"
            : "Disponible",
        ],
        [
          "Revisor",
          "🐴",
          busy && s.active_worker === "boxer-review"
            ? "Revisando"
            : "Disponible",
        ],
      ]) {
        if (map.children.length) map.append(E("span", "→", "arrow"));
        const a = E("div", "", "agent");
        const icon = E("span", "", "avatar");
        icon.append(emoji ? document.createTextNode(emoji) : hat());
        a.append(
          icon,
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
      side.append(
        E("h2", "🍀 Clover"),
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
      detail.append(
        E("summary", "Conexión y diagnóstico"),
        E(
          "pre",
          JSON.stringify(
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
          ),
        ),
      );
      this.live.append(detail);
    } else if (this.view === "tasks") {
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
          workers
            ? document.createTextNode("🐴 ")
            : a.role === "snowball"
              ? document.createTextNode("❄️ ")
              : hat(),
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
              (provider.quota === "unknown"
                ? "desconocida"
                : "suscripción observada"),
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
    for (const w of work) {
      const task = E("article", "", "task"),
        row = E("div", "", "row");
      row.append(
        E("h3", w.title),
        pill(
          this.data.runtime.fresh &&
            this.data.runtime.snapshot?.task_id === w.id &&
            this.data.runtime.snapshot?.run_state !== "idle"
            ? {
                researching: "Napoleon investigando",
                implementing: "Boxer implementando",
                testing: "Ejecutando tests",
                reviewing: "Revisión independiente",
                integrating: "Integrando PR",
              }[this.data.runtime.snapshot.run_state] || names[w.state]
            : names[w.state] || w.state,
          w.state === "blocked" ? "bad" : w.state === "integrated" ? "ok" : "",
        ),
      );
      task.append(row);
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
      if (p.sources) detail.append(E("p", "Fuentes: " + p.sources));
      const comparison = E("div", "", "compare");
      for (const [name, key] of [
        ["As-is · base investigada", "as_is"],
        ["To-be · resultado declarado", "to_be"],
      ]) {
        const col = E("section");
        col.append(E("h3", name));
        const diagram = E("div", "", "diagram");
        const source =
          key === "as_is" ? p.as_is : ev.sha === w.sha ? ev.to_be : null;
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
          E("summary", "Diseño To-be aprobado · propuesta v" + w.version),
          E("pre", p.to_be),
        );
        detail.append(proposed);
      }
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
      if (w.state === "proposed")
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
      if (w.state === "review") {
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
