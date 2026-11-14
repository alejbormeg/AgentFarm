import { integration } from "./decide.js";
import {
  E,
  activity,
  advancedForm,
  api,
  byAttention,
  byRecentlyClosed,
  button,
  closedShown,
  columns,
  link,
  liveSnapshots,
  names,
  period,
  pill,
  reasonLabel,
  renewal,
  running,
  signalAge,
  stageAge,
  NODE_FRESH_SECONDS,
  waiting,
  wall,
  when,
} from "./lib.js";

const RUN_STATES = { reserved: "En curso", settled: "Cerrada", overrun: "Excedida", reconciled: "Conciliada", unknown: "Sin confirmar" };

const percent = (x) => x.toLocaleString("es-ES", { maximumFractionDigits: 1 }) + " %";
const hours = (x) => x.toLocaleString("es-ES", { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + " h";
const plural = (n, one, many) => n + " " + (n === 1 ? one : many);

export const withViews = (Base) =>
  class extends Base {
    // ---- Bandeja: what waits on the owner, and the farm that shows the rest at a glance. ----
    decisionRow(w) {
      const d = this.data.dossiers?.find((x) => x.item === w.id);
      const live = running(this.data.runtime, w.id);
      const role = this.data.me?.role;
      const row = E("article", "", "row");
      const icon = E("img", null, "sprite-inline");
      icon.src = "/art/caja.png";
      icon.alt = "";
      const text = E("div", "", "row-text");
      const title = this.taskLink(w, "title");
      title.textContent = w.title;
      const meta = E("p", "", "meta");
      const parts = [w.repo];
      if (w.pr) parts.push("PR #" + w.pr);
      else if (w.issue) parts.push("Issue #" + w.issue);
      if (w.recipe?.title) parts.push(w.recipe.title);
      meta.append(parts.join(" · "));
      if (w.sha) {
        meta.append(" · ");
        meta.append(E("code", w.sha.slice(0, 7)));
      }
      if (w.state_since) {
        const age = E("span", " · " + stageAge(w.state_since).toLowerCase());
        age.dataset.since = w.state_since;
        age.dataset.prefix = " · ";
        meta.append(age);
      }
      text.append(title, meta);
      if (w.state === "blocked") text.append(E("p", w.error || "Necesita que decidas cómo seguir.", "warn-line"));
      for (const other of d?.overlaps || []) text.append(E("p", "Toca un archivo de «" + other.title + "» (PR #" + other.pr + ").", "warn-line"));
      const decision = integration({
        state: w.state,
        pr: w.pr,
        sha: w.sha,
        ready: d?.ready,
        blockers: d?.blockers,
        role,
        githubConfigured: this.data.automation ? this.data.automation.github_configured : undefined,
        twoFactor: this.data.twoFactor,
      });
      if (decision.show && !decision.ok) {
        for (const reason of decision.reasons) {
          const line = E("p", "No se puede integrar aún: " + reason.text, "note-line");
          if (reason.to) line.append(" ", button(reason.label, () => this.go(reason.to), "quiet"));
          text.append(line);
        }
      } else if (w.state === "review" && d && !d.ready && d.blockers?.[0])
        text.append(E("p", "Aún no: " + d.blockers[0].replace(": evidence missing", ": sin evidencia todavía"), "note-line"));
      if (live) text.append(E("p", activity[live.run_state] || names[w.state], "note-line"));
      const actions = E("div", "", "acts");
      if (w.state === "proposed") {
        actions.append(button("Leer", () => this.showTask(w.id)));
        if (role !== "observer")
          actions.append(button("Aprobar", () => this.action("/work/" + w.id + "/actions/implement", { version: w.version }), true));
      } else if (w.state === "review") {
        actions.append(button("Ver", () => this.showTask(w.id)));
        if (w.pr) {
          const pull = link("PR #" + w.pr, "https://github.com/" + w.repo + "/pull/" + w.pr);
          pull.className = "quiet";
          actions.append(pull);
        }
        if (decision.show) {
          const integrate = button(
            "Integrar",
            () =>
              confirm("¿Integrar la PR #" + w.pr + " con el commit " + w.sha.slice(0, 12) + "?") &&
              this.action("/work/" + w.id + "/actions/merge", { sha: w.sha }),
            true,
          );
          if (!decision.ok) {
            integrate.disabled = true;
            integrate.title = decision.reasons.map((r) => r.text).join(" ");
          }
          actions.append(integrate);
        }
      } else if (w.state === "blocked" && role === "owner") {
        actions.append(button("Ver", () => this.showTask(w.id)), button("Reintentar", () => this.action("/work/" + w.id + "/actions/retry", {}), true));
      } else {
        actions.append(button("Ver", () => this.showTask(w.id)));
      }
      row.append(icon, text, wall(w.state === "proposed" || w.state === "blocked" ? (d?.commandments?.some((c) => c.status !== "pending" && c.status !== "not_evaluated") ? d : null) : d), actions);
      return row;
    }
    // Three counts at the top of the Bandeja. Each differs in shape and in words, not only in colour.
    statusStrip(waitingCount, movingCount, doneCount) {
      const strip = E("nav", "", "status-strip");
      strip.setAttribute("aria-label", "Resumen de tareas");
      for (const [cls, glyph, count, label, target] of [
        ["attention", "●", waitingCount, waitingCount === 1 ? "espera tu atención" : "esperan tu atención", "grupo-atencion"],
        ["progress", "◐", movingCount, "en proceso", "grupo-proceso"],
        ["finished", "✓", doneCount, doneCount === 1 ? "terminada" : "terminadas", "grupo-terminadas"],
      ]) {
        const tile = E("a", "", "status-tile " + cls + (count ? "" : " none"));
        tile.href = "#" + target;
        tile.addEventListener("click", (event) => {
          event.preventDefault();
          document.getElementById(target)?.scrollIntoView({ behavior: "smooth", block: "start" });
        });
        tile.append(E("span", glyph, "status-glyph"), E("strong", String(count)), E("span", label));
        strip.append(tile);
      }
      return strip;
    }
    finishedRow(w) {
      const row = E("article", "", "row finished-row");
      const mark = E("span", w.state === "integrated" ? "✓" : "–", "done-mark");
      mark.setAttribute("aria-hidden", "true");
      const text = E("div", "", "row-text");
      const title = this.taskLink(w, "title");
      title.textContent = w.title;
      const meta = E("p", "", "meta");
      meta.append([w.repo, w.pr ? "PR #" + w.pr : w.issue ? "Issue #" + w.issue : null].filter(Boolean).join(" · "));
      if (w.state_since) {
        const age = E("span", " · " + stageAge(w.state_since).toLowerCase());
        age.dataset.since = w.state_since;
        age.dataset.prefix = " · ";
        meta.append(age);
      }
      text.append(title, meta);
      const actions = E("div", "", "acts");
      actions.append(pill(w.state === "integrated" ? "Integrada" : "Cancelada", w.state === "integrated" ? "ok" : ""), button("Ver", () => this.showTask(w.id)));
      row.append(mark, text, actions);
      return row;
    }
    batchButton(ready) {
      return button(
        ready.length === 1 ? "Integrar la lista" : "Integrar las " + ready.length + " listas",
        async () => {
          const items = ready.map((w) => ({ id: w.id, sha: w.sha }));
          const list = ready.map((w) => "· " + w.title + " · " + w.sha.slice(0, 7)).join("\n");
          if (!confirm("Se integrarán " + items.length + " cambios, cada uno con el commit indicado:\n" + list + "\n¿Continuar?")) return;
          try {
            const { results } = await api("/work/batch/merge", { items });
            const refused = results.filter((r) => r.state === "refused");
            this.error.textContent = refused.length ? refused.length + " no se integraron: " + refused[0].error : "";
            this.signature = "";
            await this.poll();
          } catch (e) {
            this.error.textContent = e.message;
          }
        },
        true,
      );
    }
    harvest(m) {
      const box = E("section", "", "stat");
      box.append(E("h2", "La cosecha"));
      if (!m) {
        box.append(E("p", "Sin datos todavía.", "muted"));
        return box;
      }
      box.append(this.big(String(m.totals.integrated), (m.totals.integrated === 1 ? "PR integrada" : "PR integradas") + " en 8 semanas"));
      const weeks = Object.keys(m.weeks || {}).sort().slice(-8);
      const top = Math.max(1, ...weeks.map((k) => m.weeks[k].integrated));
      const bars = E("div", "", "bars");
      bars.setAttribute("role", "img");
      bars.setAttribute("aria-label", "PR integradas por semana: " + weeks.map((k) => m.weeks[k].integrated).join(", "));
      weeks.forEach((k, i) => {
        const b = E("span", "", i === weeks.length - 1 ? "now" : "");
        b.style.height = Math.max(2, (m.weeks[k].integrated / top) * 100) + "%";
        b.title = k + ": " + plural(m.weeks[k].integrated, "integrada", "integradas");
        bars.append(b);
      });
      const axis = E("div", "", "axis");
      if (weeks.length) axis.append(E("span", "S" + weeks[0].split("W")[1]), E("span", "S" + weeks.at(-1).split("W")[1]));
      box.append(bars, axis);
      return box;
    }
    quotaCard(r) {
      const q = r.snapshot?.quota || {};
      const box = E("section", "", "stat");
      box.append(E("h2", "Cuota de Clover"));
      if (!r.quota_fresh) {
        box.append(E("p", "Cuota actual desconocida. Sin una lectura reciente de la cuota no se admite trabajo nuevo.", "note-line"));
        if (q.observed_at) box.append(E("small", "Última lectura: " + when(q.observed_at)));
        return box;
      }
      box.append(this.big(percent(100 - q.weekly_used_percent), "restante"));
      const bar = E("div", "", "meter"),
        used = E("i");
      used.style.width = Math.min(100, Math.max(0, 100 - q.weekly_used_percent)) + "%";
      bar.append(used);
      bar.setAttribute("role", "img");
      bar.setAttribute("aria-label", percent(100 - q.weekly_used_percent) + " de cuota restante");
      box.append(bar, E("p", (q.resets_at ? "Se renueva el " + renewal(q.resets_at) + ". " : "") + "Se detiene al 2 % restante.", "small"));
      return box;
    }
    quality(m) {
      if (!m) return "";
      const t = m.totals;
      const box = E("section", "", "stat");
      box.append(E("h2", "Calidad"));
      const facts = E("dl", "", "facts");
      for (const [label, value] of [
        ["Propuestas", t.proposals],
        ["Aprobadas", t.approval_rate == null ? "—" : Math.round(t.approval_rate * 100) + " %"],
        ["Correcciones", t.corrections],
        ["Ciclo mediano", t.median_cycle_hours == null ? "—" : hours(t.median_cycle_hours)],
      ])
        facts.append(E("dt", label), E("dd", String(value)));
      box.append(facts);
      return box;
    }
    big(value, note) {
      const p = E("p", value, "big");
      p.append(E("small", " " + note));
      return p;
    }
    updateFarm(r, decisions) {
      this.farm.update({
        runtime: r,
        decisions,
        integrated: this.data.metrics?.totals?.integrated || 0,
        quotaFresh: r.quota_fresh,
        usedPercent: r.snapshot?.quota?.weekly_used_percent,
        initiative: (this.data.initiatives || []).find((i) => !i.complete && !i.paused),
      });
    }
    // First run: the steps that remain, ticked from the real state, instead of an empty page.
    firstSteps(allWork, r) {
      if (allWork.length || this.data.me?.role !== "owner") return null;
      const box = E("section", "", "first");
      box.append(E("h2", "Empieza por aquí"));
      const steps = E("ol", "", "steps");
      const step = (done, text, control) => {
        const li = E("li", "", done ? "done" : "");
        li.append(E("span", text));
        if (control && !done) li.append(control);
        steps.append(li);
      };
      const setup = link("Conectar", "/setup.html");
      step(!!r.fresh, r.fresh ? "Ejecutor conectado" : "Conecta un ejecutor", setup);
      step(!!this.data.automation?.github_configured, "Conecta GitHub", button("Conectar", () => this.startGitHubApp()));
      step(false, "Adopta tu primer repositorio", button("Adoptar", () => this.adoptDrawer.showModal(), true));
      box.append(steps);
      return box;
    }
    viewInbox(r, work, allWork) {
      const m = this.data.metrics,
        s = r.snapshot || {},
        busy = liveSnapshots(r).some((x) => !!x.codex_pid);
      const yours = work.filter(waiting).sort(byAttention);
      const ready = (this.data.digest?.ready || []).filter((x) => work.some((w) => w.id === x.id));
      const head = E("div", "", "head");
      head.append(
        E("h1", "Bandeja"),
        E(
          "p",
          yours.length
            ? plural(yours.length, "decisión te espera", "decisiones te esperan") + (ready.length ? ". " + plural(ready.length, "se puede integrar ya", "se pueden integrar ya") + "." : ".")
            : "Nada espera tu decisión ahora mismo.",
        ),
      );
      this.updateFarm(r, yours.length);
      const up = E("div", "", "up"),
        rail = E("div", "", "rail");
      rail.append(this.harvest(m), this.quotaCard(r), this.quality(m));
      up.append(this.farm.root, rail);
      this.live.append(head);
      const first = this.firstSteps(allWork, r);
      if (first) this.live.append(first);
      this.live.append(up);

      const moving = work.filter((w) => ["queued", "approved", "integrating"].includes(w.state)).sort(byAttention);
      const finished = work.filter((w) => ["integrated", "cancelled"].includes(w.state)).sort(byRecentlyClosed);
      this.live.append(this.statusStrip(yours.length, moving.length || (busy ? 1 : 0), finished.length));

      const list = E("section", "", "list attention");
      list.id = "grupo-atencion";
      const bar = E("div", "", "list-head");
      bar.append(E("h2", "Esperan tu atención"));
      if (ready.length && this.data.me?.role === "owner") bar.append(this.batchButton(ready));
      list.append(bar);
      if (!yours.length) list.append(E("p", "Nada espera tu decisión ahora mismo.", "empty"));
      for (const w of yours) list.append(this.decisionRow(w));
      this.live.append(list);

      const flow = E("section", "", "list progress");
      flow.id = "grupo-proceso";
      const h = E("div", "", "list-head");
      h.append(E("h2", "En proceso"));
      flow.append(h);
      if (!moving.length) flow.append(E("p", busy ? s.task_title || "Hay una ejecución en curso." : "Ningún agente está trabajando ahora.", "empty"));
      for (const w of moving) flow.append(this.decisionRow(w));
      this.live.append(flow);

      const done = E("section", "", "list finished");
      done.id = "grupo-terminadas";
      const dh = E("div", "", "list-head");
      dh.append(E("h2", "Terminadas"));
      done.append(dh);
      if (!finished.length) done.append(E("p", "Todavía no hay tareas terminadas.", "empty"));
      for (const w of finished.slice(0, closedShown)) done.append(this.finishedRow(w));
      if (finished.length > closedShown) done.append(E("p", "Hay " + (finished.length - closedShown) + " más en Tareas → Cerradas.", "empty"));
      this.live.append(done);
      this.console(r.events || []);
      const detail = E("details", "", "fold");
      detail.dataset.key = "technical";
      const diagnostics = E("pre");
      diagnostics.textContent = !s.observed_at
        ? "El ejecutor todavía no ha enviado ninguna señal. Conéctalo desde Gobierno → Conectar ejecutor."
        : JSON.stringify(
            { entorno: s.host, estado: s.run_state, ultima_señal: s.observed_at, session: s.session, modelo: s.model, proceso: s.codex_pid, problema: s.github_error },
            null,
            2,
          );
      detail.append(E("summary", "Conexión y diagnóstico"), diagnostics);
      this.live.append(detail);
    }

    // ---- Tareas: stages with work are wide; an empty stage folds into a labelled strip. ----
    taskCard(w) {
      const live = running(this.data.runtime, w.id);
      const d = this.data.dossiers?.find((x) => x.item === w.id);
      const card = this.taskLink(w, "card" + (w.state === "blocked" ? " stop" : ""));
      card.append(E("strong", w.title), E("small", w.repo + (w.pr ? " · PR #" + w.pr : w.issue ? " · #" + w.issue : "")));
      if (w.sha) card.append(E("code", w.sha.slice(0, 7)));
      const foot = E("span", "", "card-foot");
      if (["integrated", "cancelled", "integrating"].includes(w.state) || live)
        foot.append(pill(live ? activity[live.run_state] || names[w.state] : names[w.state], w.state === "integrated" ? "ok" : ""));
      else foot.append(wall(d?.commandments?.some((c) => c.status === "ok" || c.status === "failed") ? d : null));
      if (w.state_since) {
        const age = E("small", stageAge(w.state_since), "age");
        age.dataset.since = w.state_since;
        foot.append(age);
      }
      card.append(foot);
      return card;
    }
    board(work, query) {
      if (!work.length) {
        this.live.append(E("p", query ? "Ninguna tarea coincide con la búsqueda." : "Sin tareas todavía. Crea una con «Nueva tarea» para abrir su issue y empezar.", "empty"));
        return;
      }
      const board = E("div", "", "board");
      for (const c of columns) {
        const closed = c.states.includes("integrated");
        const rows = work.filter((w) => c.states.includes(w.state)).sort(closed ? byRecentlyClosed : byAttention);
        const col = E("section", "", "column" + (rows.length ? "" : " vacant") + (c.stop ? " stop" : ""));
        col.setAttribute("aria-label", c.title + ": " + rows.length);
        const head = E("header");
        head.append(E("h2", c.title), E("span", String(rows.length), "count"));
        col.append(head);
        const limit = closed && !query && !this.allClosed ? closedShown : rows.length;
        for (const w of rows.slice(0, limit)) col.append(this.taskCard(w));
        if (closed && !query && rows.length > closedShown)
          col.append(
            button(this.allClosed ? "Ver solo las últimas " + closedShown : "Ver las " + rows.length, () => {
              this.allClosed = !this.allClosed;
              this.signature = "";
              this.render();
            }, "quiet"),
          );
        board.append(col);
      }
      this.live.append(board);
    }
    viewTasks(work) {
      const query = (this.query || "").trim().toLowerCase();
      const open = work.filter(waiting).length;
      const head = E("div", "", "head");
      head.append(E("h1", "Tareas"), E("p", plural(open, "espera tu decisión", "esperan tu decisión") + ". Las etapas cambian con evidencias, no arrastrando tarjetas."));
      this.live.append(head);
      this.board(
        work.filter((w) => !query || [w.title, w.repo, w.issue && "#" + w.issue].some((x) => String(x || "").toLowerCase().includes(query))),
        query,
      );
      const mills = E("section", "", "panel");
      mills.append(E("h2", "Molinos · iniciativas: " + (this.data.initiatives?.length || 0)));
      this.initiativeList(mills);
      this.live.append(mills);
      if (this.data.overview.tasks?.length) {
        const legacy = E("details", "", "fold");
        legacy.dataset.key = "legacy-tasks";
        legacy.append(E("summary", "Tareas del piloto anterior"));
        for (const t of this.data.overview.tasks) legacy.append(E("p", t.id + " · " + t.state + " · " + (t.candidate_sha || "Sin commit")));
        this.live.append(legacy);
      }
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
        const card = E("article", "", "mill"),
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
    // ---- Agentes: one card per animal, with what it is doing now. ----
    agentCard(a, kind, r) {
      const enabled = Number(a.enabled) !== 0;
      const now = Date.now() / 1000;
      const alive = r.fresh && a.heartbeat != null && now - a.heartbeat >= 0 && now - a.heartbeat <= NODE_FRESH_SECONDS;
      const state = !enabled ? "Deshabilitado" : !alive ? "Sin señal" : a.reported_state === "busy" ? "Trabajando" : "Disponible";
      const card = E("article", "", "agent" + (state === "Trabajando" ? " busy" : ""));
      const who = kind === "node" ? "napoleon" : /review/.test(a.id) ? "benjamin" : "boxer";
      const face = E("img", null, "sprite-inline big");
      face.src = "/art/" + who + ".png";
      face.alt = "";
      const text = E("div");
      const age = E("small", signalAge(a.heartbeat), "signal");
      if (a.heartbeat) age.dataset.signal = a.heartbeat;
      text.append(E("h3", a.id), E("small", { napoleon: "Coordina la cola", boxer: "Implementa", benjamin: "Revisa sin poder tocar nada" }[who]), age);
      const live = liveSnapshots(r).find((x) => x.task_title && x.run_state !== "idle" && (x.worker === a.id || x.node === a.node || x.node === a.id) && state === "Trabajando");
      if (live) text.append(E("p", live.task_title, "note-line"));
      const side = E("div", "", "agent-side");
      side.append(pill(state, state === "Disponible" ? "ok" : state === "Trabajando" ? "info" : state === "Sin señal" ? "warn" : ""));
      if (this.data.me?.role === "owner") {
        const toggle = button(enabled ? "Deshabilitar" : "Habilitar", () => this.action("/entities/" + kind + "/" + encodeURIComponent(a.id) + "/enabled/" + !enabled, {}));
        if (kind === "node")
          toggle.title = "Deshabilitado, el nodo no recibe órdenes, tokens de GitHub ni presupuesto nuevos, pero puede cerrar lo que ya tiene. Para cortarle todo el acceso, revoca su token.";
        side.append(toggle);
      }
      card.append(face, text, side);
      return card;
    }
    agentSection(title, rows, kind, r) {
      const section = E("section");
      section.append(E("h2", title));
      if (!rows.length) section.append(E("p", "Ninguno registrado todavía.", "empty"));
      const grid = E("div", "", "agents");
      for (const a of rows) grid.append(this.agentCard(a, kind, r));
      section.append(grid);
      return section;
    }
    viewAgents(r, o) {
      const head = E("div", "", "head");
      head.append(E("h1", "Agentes"), E("p", r.fresh ? "El establo: quién hay y qué hace ahora." : "El ejecutor no da señal: nadie puede coger trabajo."));
      this.updateFarm(r, this.data.work.filter(waiting).length);
      const up = E("div", "", "up"),
        recent = E("section", "", "stat recent");
      recent.append(E("h2", "Actividad reciente"));
      const events = (r.events || []).slice(-8).reverse();
      if (!events.length) recent.append(E("p", "Sin actividad registrada todavía.", "small"));
      for (const ev of events) {
        const line = E("p", "", "event");
        line.append(E("small", when(ev.at) + " · " + ev.worker), E("span", ev.text));
        recent.append(line);
      }
      up.append(this.farm.root, recent);
      const repos = [...new Set(this.data.work.map((w) => w.repo).filter(Boolean))].sort();
      const profiles = E("section");
      profiles.append(E("h2", "Instrucciones por repositorio"), E("p", "Elige cómo trabajan Napoleon, Boxer y Benjamin en cada repositorio: su system message y las skills que quieres que usen.", "small"));
      if (this.data.me?.role === "owner")
        for (const repo of repos) profiles.append(button("Configurar " + repo, () => this.openProfile(repo)));
      else profiles.append(E("p", "Solo el propietario puede configurarlo.", "empty"));
      if (!repos.length) profiles.append(E("p", "Adopta un repositorio para configurarlo.", "empty"));
      this.live.append(head, up, profiles, this.agentSection("Nodos dirigentes", o.nodes, "node", r), this.agentSection("Trabajadores Boxer", o.workers, "worker", r));
      if (this.data.me?.role === "owner")
        for (const kind of ["nodes", "workers"]) {
          const form = advancedForm(kind);
          form.classList.add("fold");
          form.dataset.key = "register-" + kind;
          this.live.append(form);
        }
    }

    // ---- Presupuesto: the quota, what each account has spent and reserved, and the engines. ----
    windowBar(w, unit) {
      const divisor = unit === "usd_micros" ? 1e6 : 100,
        symbol = unit === "usd_micros" ? " USD" : " %";
      const fmt = (x) => (x / divisor).toLocaleString("es-ES", { maximumFractionDigits: 2 }) + symbol;
      const row = E("div", "", "window");
      const title = E("p", "", "window-title");
      title.append(E("strong", period(w.period)[0].toUpperCase() + period(w.period).slice(1)), E("small", w.end ? " · se renueva el " + renewal(w.end) : ""));
      const bar = E("div", "", "meter stacked");
      for (const [cls, value] of [["used", w.used], ["reserved", w.reserved]]) {
        const part = E("i", "", cls);
        part.style.width = Math.min(100, (value / Math.max(1, w.cap)) * 100) + "%";
        bar.append(part);
      }
      bar.setAttribute("role", "img");
      bar.setAttribute("aria-label", "Consumido " + fmt(w.used) + ", reservado " + fmt(w.reserved) + ", margen " + fmt(w.remaining));
      const legend = E("p", "", "legend");
      legend.append(E("span", "Consumido " + fmt(w.used), "k-used"), E("span", "Reservado " + fmt(w.reserved), "k-reserved"), E("span", "Margen " + fmt(w.remaining), "k-free"), E("span", "Tope " + fmt(w.cap)));
      row.append(title, bar, legend);
      return row;
    }
    viewBudgets(r) {
      const q = r.snapshot?.quota || {};
      const head = E("div", "", "head");
      head.append(E("h1", "Presupuesto"), E("p", "Sin cambio automático de proveedor ni pagos adicionales."));
      this.live.append(head);
      const top = E("div", "", "cols");
      const provider = E("section", "", "panel");
      provider.append(E("h2", "Cuota del proveedor"));
      if (r.quota_fresh) {
        provider.append(this.big(percent(100 - q.weekly_used_percent), "restante esta semana"));
        const bar = E("div", "", "meter"),
          left = E("i");
        left.style.width = Math.min(100, Math.max(0, 100 - q.weekly_used_percent)) + "%";
        bar.append(left);
        provider.append(bar, E("p", (q.resets_at ? "Se renueva el " + renewal(q.resets_at) + ". " : "") + "Última lectura: " + when(q.observed_at) + ".", "small"));
      } else provider.append(E("p", "Cuota actual desconocida. " + (q.observed_at ? "Última lectura: " + when(q.observed_at) + "." : "Sin observación."), "note-line"));
      const engines = E("section", "", "panel");
      engines.append(E("h2", "Motores y capacidades"));
      for (const p of this.data.providers?.providers || []) {
        const row = E("div", "", "setting");
        const text = E("div");
        text.append(
          E("strong", p.id),
          E("p", "Cuota: " + ({ unknown: "desconocida", "api-cost-observed": "coste API en USD por turno (cuenta Clover api)" }[p.quota] || "suscripción observada"), "small"),
        );
        row.append(text, pill(p.state === "implemented" ? "Ejecutor integrado" : "Solo contrato", p.state === "implemented" ? "ok" : ""));
        engines.append(row);
      }
      engines.append(E("p", "Económico y equilibrado usan la misma política actual; revisión reforzada espera al verificador separado.", "small"));
      top.append(provider, engines);
      this.live.append(top);
      const clover = E("section", "", "panel");
      clover.append(E("h2", "Clover · lo reservado y lo que queda"));
      for (const a of this.data.budgets || []) {
        const box = E("div", "", "account");
        box.append(E("h3", a.account));
        if (!a.windows.length) box.append(E("p", "Sin ventana abierta todavía: se abre con la primera lectura de la cuota.", "muted"));
        for (const w of a.windows) box.append(this.windowBar(w, a.unit));
        for (const reason of a.reasons) box.append(E("p", reasonLabel(reason), "warn-line"));
        const open = (a.reservations || []).filter((x) => x.state === "reserved");
        if (open.length) box.append(E("p", plural(open.length, "reserva abierta", "reservas abiertas"), "small"));
        clover.append(box);
      }
      if (!this.data.budgets?.length) clover.append(E("p", "Sin cuentas todavía. Se crean al conectar el ejecutor.", "empty"));
      this.live.append(clover);
      const costs = Object.entries(this.data.metrics?.cost || {});
      const runs = (this.data.budgets || []).flatMap((a) => (a.reservations || []).map((x) => ({ ...x, account: a.account, unit: a.unit })));
      if (costs.length || runs.length) {
        const spend = E("section", "", "panel");
        spend.append(E("h2", "Consumo · 8 semanas"));
        const facts = E("dl", "", "facts wide");
        for (const [account, c] of costs)
          facts.append(
            E("dt", account),
            E(
              "dd",
              (c.attribution === "account"
                ? (c.observed + c.total ? "Hasta " + (c.observed + c.total) / 100 + " %" : "Menos de 1 %") + " de la cuenta"
                : c.unit === "usd_micros"
                  ? (c.total / 1e6).toFixed(2) + " USD"
                  : c.total / 100 + " %") +
                " · " +
                plural(c.runs, "ejecución", "ejecuciones"),
            ),
          );
        spend.append(facts);
        if (runs.length) {
          const scroll = E("div", "", "table-scroll"),
            table = E("table"),
            headRow = E("tr");
          ["Ejecución", "Cuenta", "Estado", "Reservado"].forEach((x) => headRow.append(E("th", x)));
          table.append(headRow);
          const shown = (x) => {
            try {
              return Object.entries(JSON.parse(x.amounts))
                .map(([p, v]) => period(p) + " " + (x.unit === "usd_micros" ? (v / 1e6).toFixed(2) + " USD" : v / 100 + " %"))
                .join(", ");
            } catch {
              return x.amounts;
            }
          };
          for (const x of runs.slice(-8).reverse()) {
            const tr = E("tr");
            const run = E("td", x.run_id.replace(/^work-/, "").slice(0, 8));
            run.title = x.run_id;
            tr.append(run, E("td", x.account), E("td", RUN_STATES[x.state] || x.state), E("td", shown(x)));
            table.append(tr);
          }
          scroll.append(table);
          spend.append(E("h3", "Últimas ejecuciones"), scroll);
        }
        this.live.append(spend);
      }
      if (this.data.me?.role === "owner") {
        const form = advancedForm("budgets");
        form.classList.add("fold");
        form.dataset.key = "budget-advanced";
        this.live.append(form);
      }
    }
  };
