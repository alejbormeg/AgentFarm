import { E, activity, api, button, expectedCost, json, link, names, pill, rawRecord, running, testEvidence, testStatus, whyYou, wall, journey } from "./lib.js";

export const withTask = (Base) =>
  class extends Base {
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
          ci === "failed" || ci === "unavailable" || d.verification?.detail?.stalled ? "warning" : "muted",
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
    taskDetail(w) {
      const task = E("article", "", "task"),
        row = E("div", "", "task-state");
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
      const dossier = this.data.dossiers?.find((d) => d.item === w.id);
      task.append(this.taskDecision(w, dossier, json(w.proposal)));
      task.append(journey(w, dossier, json(w.evidence)));
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
      if (p.kept_criteria?.length)
        task.append(
          E("p", "Se conservan de la versión aprobada: " + p.kept_criteria.join(", ") + ". Un agente no puede quitarlos.", "muted"),
        );
      for (const gone of p.retired_criteria || [])
        task.append(
          E("p", gone.id + " retirado por " + gone.actor + ": «" + gone.description + "». Motivo: " + gone.reason, "warning"),
        );
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
            "Squealer dice · unitarias: " + testStatus(ev.unit?.status),
          ),
          ...testEvidence(p.unit_test, ev.unit, "unit-raw-" + w.id),
        );
        detail.append(t);
      }
      if (ev.integration || p.integration_test) {
        const t = E("details");
        t.dataset.key = "integration-" + w.id;
        t.append(
          E(
            "summary",
            "Squealer dice · integración: " + testStatus(ev.integration?.status),
          ),
          ...testEvidence(p.integration_test, ev.integration, "integration-raw-" + w.id),
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
          E("p", ev.review.findings || "Sin comentarios."),
          rawRecord(ev.review, "review-raw-" + w.id),
        );
        detail.append(t);
      }
      if (w.sha) detail.append(E("p", "Commit: " + w.sha));
      task.append(detail);
      return task;
    }
    // What the person can decide, and what stops them: the first thing the task shows.
    taskDecision(w, dossier, p) {
      const actions = E("div", "", "decision");
      if (w.state === "proposed") {
        const estimate = expectedCost(this.data.costs, w);
        if (estimate) actions.append(E("p", estimate, "muted"));
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
        if (dossier?.ready)
          actions.append(
            E("p", "Se fusionará la PR #" + w.pr + " con el commit " + w.sha + ". GitHub y las pruebas deben confirmarlo."),
            button(
              "Aprobar e integrar este commit",
              () =>
                confirm("¿Integrar la PR #" + w.pr + " con el commit " + w.sha.slice(0, 12) + "?") &&
                this.action("/work/" + w.id + "/actions/merge", { sha: w.sha }),
              true,
            ),
          );
        else {
          actions.append(E("h4", "Aún no se puede integrar"));
          const missing = E("ul", "", "blockers");
          for (const blocker of dossier?.blockers || ["Faltan criterios o mandamientos por cubrir."])
            missing.append(E("li", blocker.replace(": evidence missing", ": sin evidencia todavía")));
          actions.append(missing);
        }
      }
      const canAct = this.data.me?.role !== "observer";
      if (canAct && ["proposed", "review", "blocked"].includes(w.state)) {
        const correct = E("details");
        correct.dataset.key = "correct-" + w.id;
        correct.append(E("summary", "Pedir correcciones"));
        const feedback = E("textarea");
        feedback.placeholder = "Qué debe corregirse";
        feedback.setAttribute("aria-label", "Qué debe corregirse");
        // Once a version was approved its criteria stay unless a person retires them, with the reason above.
        const approved = w.state === "proposed" ? (p.criteria || []).filter((c) => p.kept_criteria?.includes(c.id)) : p.criteria || [];
        const retire = [];
        const retirable = E("details");
        retirable.dataset.key = "retire-" + w.id;
        retirable.append(E("summary", "Retirar un criterio aprobado"), E("p", "El motivo es el texto de la corrección, con al menos 20 caracteres, y queda visible.", "muted"));
        for (const c of approved) {
          const box = E("input");
          box.type = "checkbox";
          box.dataset.key = "retire-" + w.id + "-" + c.id;
          retire.push([c.id, box]);
          const label = E("label");
          label.append(box, " " + c.id + " · " + c.description);
          retirable.append(label);
        }
        correct.append(
          feedback,
          ...(approved.length && w.state !== "blocked" ? [retirable] : []),
          button("Investigar correcciones", () => {
            const ids = retire.filter(([, box]) => box.checked).map(([id]) => id);
            return this.action("/work/" + w.id + "/actions/correct", {
              feedback: feedback.value,
              ...(ids.length ? { retire: ids } : {}),
            });
          }),
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
      return actions;
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
  };
