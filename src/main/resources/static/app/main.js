import { E, api, button, liveSnapshots, stageAge, signalAge, waiting, when } from "./lib.js";
import { FarmMap } from "./map.js";
import { withForms } from "./forms.js";
import { withGovernance } from "./governance.js";
import { withTask } from "./task.js";
import { withViews } from "./views.js";

const ROUTES = [
  ["overview", "Bandeja", '<path d="M3 13h5l2 3h4l2-3h5"/><path d="M5 5h14l2 8v6H3v-6z"/>'],
  ["tasks", "Tareas", '<path d="M4 4h5v16H4zM10 4h5v10h-5zM16 4h4v13h-4z"/>'],
  ["agents", "Agentes", '<circle cx="9" cy="8" r="3.5"/><path d="M3 20c0-4 3-6.5 6-6.5s6 2.5 6 6.5M16 5a3.5 3.5 0 0 1 0 7M18 14c2 1 3.5 3 3.5 6"/>'],
  ["budgets", "Presupuesto", '<path d="M4 17a8.5 8.5 0 1 1 16 0"/><path d="M12 14l4-5"/>'],
  ["governance", "Gobierno", '<path d="M4 10l8-6 8 6v10H4zM9 20v-6h6v6"/>'],
];
const THEME_NAMES = { auto: "Tema: como el sistema", light: "Tema: La ventana", dark: "Tema: Atardecer" };

class FarmApp extends withGovernance(withViews(withTask(withForms(HTMLElement)))) {
  connectedCallback() {
    if (this.content) return;
    this.farm = new FarmMap();
    this.shell();
    addEventListener("popstate", () => {
      this.signature = "";
      this.poll();
    });
    this.poll();
    this.timer = setInterval(() => this.poll(), 2000);
  }
  route() {
    return location.pathname.split("/").filter(Boolean).at(-1) || "overview";
  }
  go(path) {
    history.pushState({}, "", path);
    this.signature = "";
    this.markNav();
    this.poll();
  }
  markNav() {
    const route = this.route();
    for (const [id, a] of Object.entries(this.navLinks)) {
      a.classList.toggle("on", id === route);
      if (id === route) a.setAttribute("aria-current", "page");
      else a.removeAttribute("aria-current");
    }
  }
  shell() {
    const side = E("aside", "", "side");
    const brand = E("a", "", "brand");
    brand.href = "/overview";
    const logo = E("img", null, "sprite-inline");
    logo.src = "/art/napoleon.png";
    logo.alt = "";
    brand.append(logo, E("span", "AgentFarm"));
    this.repositorySelect = E("select");
    this.repositorySelect.setAttribute("aria-label", "Filtrar repositorio");
    this.repositorySelect.onchange = () => {
      this.selectedRepo = this.repositorySelect.value;
      this.signature = "";
      this.render();
    };
    const nav = E("nav", "", "nav");
    nav.setAttribute("aria-label", "Secciones");
    this.navLinks = {};
    this.badge = E("b");
    this.badge.hidden = true;
    for (const [id, name, icon] of ROUTES) {
      const a = E("a");
      a.href = "/" + id;
      a.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true">' + icon + "</svg>";
      a.append(E("span", name));
      if (id === "overview") a.append(this.badge);
      a.onclick = (e) => {
        if (e.metaKey || e.ctrlKey || e.shiftKey || e.button) return;
        e.preventDefault();
        if (this.route() !== id) this.go("/" + id);
      };
      this.navLinks[id] = a;
      nav.append(a);
    }
    this.pauseSlot = E("div");
    const notify = E("div");
    this.notifySlot = notify;
    const theme = button(THEME_NAMES[window.agentfarmTheme.choice()], () => {
      theme.textContent = THEME_NAMES[window.agentfarmTheme.cycle()];
    }, "quiet");
    this.who = E("small");
    const foot = E("div", "", "foot");
    foot.append(this.pauseSlot, notify, theme, this.who);
    side.append(brand, this.repositorySelect, nav, foot);

    const main = E("div", "", "main");
    const top = E("header", "", "top");
    this.search = E("input");
    this.search.type = "search";
    this.search.placeholder = "Buscar por título, repositorio o issue";
    this.search.setAttribute("aria-label", "Buscar tareas");
    this.search.oninput = () => {
      this.query = this.search.value;
      this.signature = "";
      this.render();
    };
    this.demoChip = E("span", "", "demo");
    this.demoChip.hidden = true;
    this.adopt = button("Adoptar repositorio", () => this.adoptDrawer.showModal());
    this.newTask = button("Nueva tarea", () => this.newDrawer.showModal(), true);
    top.append(this.search, this.demoChip, E("span", "", "spacer"), this.adopt, this.newTask);

    const sheet = E("main", "", "sheet");
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
    this.live = E("div", "", "live");
    this.content = sheet;
    sheet.append(problem, this.strip, this.live);
    main.append(top, sheet);

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
      [...this.live.querySelectorAll("[data-task]")].find((c) => c.dataset.task === id)?.focus();
    });
    const linked = new URLSearchParams(location.search).get("tarea");
    if (linked) this.pendingTask = linked;
    this.classList.add("app");
    this.append(side, main, this.newDrawer, this.adoptDrawer, this.secondFactorDrawer, this.taskDrawer);
    this.markNav();
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
  async poll() {
    if (this.loading || !this.isConnected) return;
    this.loading = true;
    try {
      const [runtime, overview, work] = await Promise.all([api("/runtime"), api("/overview"), api("/work")]);
      const route = this.route();
      this.view = route;
      this.me ||= await api("/me").catch(() => ({ role: "observer" }));
      const usesTasks = ["overview", "tasks"].includes(route) || this.openTask || this.pendingTask;
      const dossiers = usesTasks || route === "governance" ? await api("/evidence") : [];
      const initiatives = usesTasks ? await api("/initiatives") : [];
      const costs = usesTasks ? await api("/work/costs").catch(() => ({})) : {};
      const metrics = ["overview", "agents", "budgets"].includes(route) ? await api("/work/metrics").catch(() => null) : null;
      const digest = route === "overview" ? await api("/work/digest").catch(() => null) : null;
      const automation = ["overview", "governance"].includes(route) ? await api("/automation").catch(() => null) : null;
      const extra = route === "governance" ? await api("/audit") : null;
      const githubApp = route === "governance" && this.me.role === "owner" ? await api("/github-app").catch(() => null) : null;
      if (route === "governance") {
        this.commandmentList ||= await api("/evidence/commandments");
        this.skillList ||= await api("/skills");
      }
      let providers = null,
        budgets = null;
      if (route === "budgets") {
        providers = await api("/providers").catch(() => null);
        budgets = await Promise.all(
          overview.budget_accounts.map(async (a) => ({
            ...(await api("/budget/" + encodeURIComponent(a.account))),
            reservations: await api("/budget/" + encodeURIComponent(a.account) + "/reservations").catch(() => []),
          })),
        );
      }
      this.data = { runtime, overview, work, extra, dossiers, initiatives, automation, metrics, digest, costs, githubApp, providers, budgets, me: this.me };
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
      this.pollFailed = true;
      this.error.textContent = e.message + " Los datos visibles pueden estar desactualizados.";
    } finally {
      this.loading = false;
    }
  }
  chrome(allWork, o) {
    const me = this.data.me;
    const owner = me?.role === "owner";
    const repos = [...new Set(allWork.map((w) => w.repo))];
    this.selectedRepo ??= "";
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
    this.newTask.hidden = me?.role === "observer";
    this.adopt.hidden = !owner;
    this.search.hidden = !["overview", "tasks"].includes(this.view);
    const decisions = allWork.filter(waiting).length;
    this.badge.textContent = decisions;
    this.badge.hidden = !decisions;
    document.title = (decisions ? "(" + decisions + ") " : "") + "AgentFarm";
    this.who.textContent = me ? me.user + " · " + ({ owner: "propietario", reviewer: "revisor", observer: "observador" }[me.role] || me.role) : "";
    this.demoChip.hidden = !me?.demo;
    this.demoChip.textContent = "Demo: agentes y GitHub simulados";
    this.demoChip.title = "Modo demo: los agentes y GitHub son simulados. Nada sale de este equipo y nada se integra.";
    if (this.pauseState !== o.paused + ":" + owner) {
      this.pauseState = o.paused + ":" + owner;
      this.pauseSlot.replaceChildren(
        ...(owner ? [button(o.paused ? "Reanudar cola" : "Pausar agentes", () => this.action("/governance/paused/" + !o.paused, {}), o.paused ? "" : "danger")] : []),
      );
    }
    if ("Notification" in window && Notification.permission === "default" && !this.notifySlot.children.length)
      this.notifySlot.append(
        button("Avisarme en este navegador", async () => {
          await Notification.requestPermission();
          this.notifySlot.replaceChildren();
        }, "quiet"),
      );
  }
  render() {
    const { runtime: r, overview: o, work: allWork } = this.data;
    const s = r.snapshot || {},
      q = s.quota || {},
      busy = liveSnapshots(r).some((x) => !!x.codex_pid);
    this.chrome(allWork, o);
    const work = allWork.filter((w) => !this.selectedRepo || w.repo === this.selectedRepo);
    const now = Date.now() / 1000;
    const alive = (a) => a.heartbeat != null && now - a.heartbeat <= 12;
    const signature = JSON.stringify([
      this.view,
      s.run_state,
      s.task_id,
      liveSnapshots(r).map((x) => [x.executor, x.run_state, x.task_id, x.task_title]),
      r.fresh,
      busy,
      r.quota_fresh,
      q,
      work,
      o.nodes.map((n) => [n.id, n.enabled, n.reported_state, alive(n)]),
      o.workers.map((w) => [w.id, w.enabled, w.reported_state, alive(w)]),
      o.paused,
      this.data.extra,
      this.data.dossiers,
      this.data.digest,
      this.data.metrics,
      this.data.initiatives,
      this.data.budgets,
      this.data.providers,
      this.data.automation,
      this.data.githubApp,
      this.query,
      (r.events || []).at(-1)?.id,
      this.openTask,
      r.outdated_executors,
      r.unisolated_executors,
      r.isolation_notices,
    ]);
    this.live.querySelectorAll("[data-since]").forEach((e) => {
      const text = stageAge(e.dataset.since);
      e.textContent = e.dataset.prefix ? e.dataset.prefix + text.toLowerCase() : text;
    });
    this.live.querySelectorAll("[data-signal]").forEach((e) => {
      e.textContent = signalAge(Number(e.dataset.signal));
    });
    if (signature === this.signature) return;
    this.signature = signature;
    this.status(r, o);
    const roots = [this.live, this.taskBody];
    const all = (selector) => roots.flatMap((x) => [...x.querySelectorAll(selector)]);
    const drawerScroll = this.taskBody.parentElement?.scrollTop || 0;
    const pageScroll = this.content.scrollTop;
    const focused = document.activeElement;
    const focusOwner = focused?.closest?.("details[data-key]");
    const focusState = focusOwner
      ? {
          key: focusOwner.dataset.key,
          index: [...focusOwner.querySelectorAll("textarea,input,select")].indexOf(focused),
          start: focused.selectionStart,
          end: focused.selectionEnd,
        }
      : null;
    const drafts = all("details[data-key]").map((d) => [d.dataset.key, [...d.querySelectorAll("textarea,input,select")].map((i) => i.value)]);
    const opened = all("details[open]")
      .map((e) => e.dataset.key)
      .filter(Boolean);
    this.live.replaceChildren();
    this.taskBody.replaceChildren();
    if (this.view === "overview") this.viewInbox(r, work, allWork);
    else if (this.view === "tasks") this.viewTasks(work);
    else if (this.view === "agents") this.viewAgents(r, o);
    else if (this.view === "budgets") this.viewBudgets(r);
    else if (this.view === "governance") this.viewGovernance(r, o);
    const shown = this.openTask && allWork.find((w) => w.id === this.openTask);
    if (shown) {
      this.taskTitle.textContent = shown.title;
      this.taskBody.append(this.taskDetail(shown));
    } else if (this.openTask && this.taskDrawer.open) this.taskDrawer.close();
    for (const d of all("details")) {
      if (opened.includes(d.dataset.key)) d.open = true;
      const saved = drafts.find((x) => x[0] === d.dataset.key);
      if (saved) [...d.querySelectorAll("textarea,input,select")].forEach((input, i) => (input.value = saved[1][i] || ""));
      if (focusState && focusState.key === d.dataset.key && focusState.index >= 0) {
        const input = [...d.querySelectorAll("textarea,input,select")][focusState.index];
        input?.focus({ preventScroll: true });
        input?.setSelectionRange?.(focusState.start, focusState.end);
      }
    }
    if (this.taskBody.parentElement) this.taskBody.parentElement.scrollTop = drawerScroll;
    this.content.scrollTop = pageScroll;
  }
  // Only what needs saying right now, each with the way to resolve it.
  status(r, o) {
    const q = r.snapshot?.quota || {};
    const owner = this.data.me?.role === "owner";
    const items = [];
    const notice = (text, kind, control) => {
      const row = E("div", "", "notice " + kind);
      row.append(E("span", text));
      if (control) row.append(control);
      items.push(row);
    };
    if (!r.fresh) {
      const connect = E("a", "Conectar ejecutor");
      connect.href = "/setup.html";
      notice("El ejecutor no da señal: el trabajo no avanzará hasta que se conecte.", "bad", owner ? connect : null);
    }
    if (o.paused)
      notice("La cola está pausada: no se admite trabajo nuevo.", "warn", owner ? button("Reanudar cola", () => this.action("/governance/paused/false", {})) : null);
    if (["overview", "budgets"].includes(this.view)) {
      if (!r.quota_fresh) notice("Cuota desconocida: sin una lectura reciente no se admite trabajo nuevo.", "warn");
      else if (q.allowed === false) notice("Codex no admite trabajo ahora mismo.", "warn");
    }
    if (this.view === "overview") {
      if (r.outdated_executors?.length) notice("Ejecutor con otra versión que el panel: " + r.outdated_executors.join(", ") + ". Actualízalo.", "warn");
      for (const x of r.unisolated_executors || [])
        notice("El ejecutor " + x.executor + " no acepta trabajo: el aislamiento de los agentes no funciona (" + x.problem + "). Ejecuta tests/codex_sandbox.sh y revisa la versión de Codex.", "bad");
      for (const x of r.isolation_notices || []) notice(x.executor + ": " + x.notice + " Revisa docs/PROVIDERS.md antes de darle un repositorio.", "warn");
    }
    this.strip.replaceChildren(...items);
  }
  console(events) {
    const details = E("details", "", "fold");
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
}
customElements.define("farm-app", FarmApp);
