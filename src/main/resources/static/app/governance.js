import { E, api, button, link, when } from "./lib.js";

export const withGovernance = (Base) =>
  class extends Base {
    async startGitHubApp() {
      try {
        const { action, manifest } = await api("/github-app/start", {});
        const form = E("form");
        form.method = "post";
        form.action = action;
        const field = E("input");
        field.type = "hidden";
        field.name = "manifest";
        field.value = manifest;
        form.append(field);
        document.body.append(form);
        form.submit();
      } catch (e) {
        this.error.textContent = e.message;
      }
    }
    connection(name, ok, detail, control) {
      const row = E("div", "", "setting");
      const text = E("div");
      text.append(E("strong", name), E("p", detail, "small"));
      const side = E("div", "", "acts");
      side.append(E("span", ok ? "Conectado" : "Sin conectar", "pill " + (ok ? "ok" : "warn")));
      if (control) side.append(control);
      row.append(text, side);
      return row;
    }
    viewGovernance(r, o) {
      const owner = this.data.me?.role === "owner";
      const head = E("div", "", "head");
      head.append(E("h1", "Gobierno"), E("p", "Cada propuesta necesita tu aprobación. Cada integración queda vinculada al commit revisado."));
      this.live.append(head);
      const sections = E("nav", "", "subnav");
      sections.setAttribute("aria-label", "Secciones de Gobierno");
      for (const [id, label] of [["control", "Control"], ["muro", "Mandamientos"], ["skills", "Skills"], ["automatizacion", "Automatización"], ["decisiones", "Decisiones"]]) {
        const a = E("a", label);
        a.href = "#" + id;
        sections.append(a);
      }
      this.live.append(sections);

      const grid = E("div", "", "cols");
      const control = E("section", "", "panel");
      control.id = "control";
      control.append(E("h2", "Control de ejecución"));
      const actions = E("div", "", "actions");
      if (owner) actions.append(button(o.paused ? "Reanudar cola" : "Pausar agentes", () => this.action("/governance/paused/" + !o.paused, {}), o.paused ? "" : "danger"));
      actions.append(
        button("Segundo factor", async () => {
          await this.refreshSecondFactor();
          this.secondFactorDrawer.showModal();
        }),
      );
      control.append(E("p", o.paused ? "La cola está pausada." : "La cola está en marcha.", "small"), actions);
      if (owner) {
        const github = this.data.githubApp;
        const configured = !!this.data.automation?.github_configured;
        control.append(
          E("h3", "Conexiones"),
          this.connection("Ejecutor", !!r.fresh, r.fresh ? "Da señal ahora mismo." : "El trabajo no avanza hasta que se conecte.", link("Conectar ejecutor", "/setup.html")),
          this.connection(
            "GitHub",
            configured,
            github?.mode === "app" ? "GitHub App" + (github.slug ? " · " + github.slug : "") : (configured ? "Con un token personal." : "Sin él no se verifica ni se integra.") + (github && !github.webhook ? " La App se creará sin webhook: el panel no tiene dirección pública. Si lo despliegas, configura AGENTFARM_PANEL_URL con https." : ""),
            button("Conectar GitHub", () => this.startGitHubApp()),
          ),
        );
      }
      const wall = E("section", "", "barn");
      wall.id = "muro";
      wall.append(E("h2", "Los Siete Mandamientos"), E("p", "Cada cambio los cumple antes de poder integrarse. Están en el código del panel: ningún agente puede repintarlos."));
      const rules = E("ol");
      for (const c of this.commandmentList || []) rules.append(E("li", c.text));
      wall.append(rules);
      grid.append(control, wall);
      this.live.append(grid);

      const skills = E("section", "", "panel");
      skills.id = "skills";
      skills.append(E("h2", "Skills de los agentes"), E("p", "Cada turno recibe las que corresponden a su rol y a la tecnología del repositorio, y la evidencia registra su versión.", "small"));
      const catalog = E("ul", "", "skills");
      for (const c of this.skillList || []) catalog.append(E("li", c.title + " · " + c.id + " v" + c.version));
      skills.append(catalog);
      this.live.append(skills);
      this.automation(this.data.automation);
      const audit = E("section", "", "panel");
      audit.id = "decisiones";
      this.live.append(audit);
      audit.append(E("h2", "Últimas decisiones"));
      if (!this.data.extra?.length) audit.append(E("p", "Sin decisiones registradas.", "muted"));
      else {
        const scroll = E("div", "", "table-scroll"),
          grid2 = E("table"),
          headRow = E("tr");
        ["Fecha", "Decisión", "Sobre"].forEach((x) => headRow.append(E("th", x)));
        grid2.append(headRow);
        for (const row of this.data.extra.slice(0, 30)) {
          const tr = E("tr");
          tr.append(E("td", when(row.at)), E("td", row.action), E("td", row.subject));
          grid2.append(tr);
        }
        scroll.append(grid2);
        audit.append(scroll);
      }
    }
    automation(a) {
      if (!a) return;
      const owner = this.data.me?.role === "owner";
      const section = E("section", "", "panel");
      section.id = "automatizacion";
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
              (!t.enabled ? "desactivado" : t.waiting_for ? "empezará al integrar su adopción" : t.kinds.map((k) => kinds[k] || k).join(", ")) +
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
      sentryOrg.setAttribute("aria-label", "Organización de Sentry");
      sentryProject.setAttribute("aria-label", "Proyecto de Sentry");
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
  };
