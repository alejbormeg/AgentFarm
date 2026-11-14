import { E, api, button, link } from "./lib.js";

const STATE_NAMES = { queued: "en cola", proposed: "esperando tu aprobación", approved: "lista para implementar", review: "resultado disponible", integrated: "integrada", blocked: "bloqueada", cancelled: "cancelada", integrating: "integrando" };

// The open issues of an adopted repository, for the owner to choose which ones the agents start on.
// A drawer built once and refilled on open, so the 2 s poll never erases what is ticked.
export const withIssues = (Base) =>
  class extends Base {
    issuesForm() {
      const root = E("div", "", "issues");
      this.issuesRoot = root;
      return root;
    }
    async openIssues(repo) {
      this.issuesDrawer.showModal();
      await this.drawIssues(repo);
    }
    async drawIssues(wanted) {
      const root = this.issuesRoot;
      const repos = [...new Set((this.data?.work || []).map((w) => w.repo).filter(Boolean))].sort();
      const repo = wanted || this.issuesRepo || repos[0] || "";
      this.issuesRepo = repo;
      const problem = E("p", "", "error");
      problem.setAttribute("role", "alert");
      const done = E("p", "", "done");
      done.setAttribute("role", "status");
      root.replaceChildren(
        E("p", "Elige las issues con las que empiezan los agentes. Napoleon investiga cada una y te propone un plan; no se implementa nada hasta que lo apruebes. Investigar gasta cuota.", "muted"),
      );
      if (!repos.length) {
        root.append(E("p", "Adopta un repositorio primero.", "empty"));
        return;
      }
      const select = E("select");
      select.setAttribute("aria-label", "Repositorio");
      for (const r of repos) select.append(Object.assign(E("option", r), { value: r }));
      select.value = repo;
      select.addEventListener("change", () => this.drawIssues(select.value));
      const label = E("label", "Repositorio");
      label.append(select);
      root.append(label, problem, done);
      let issues;
      try {
        issues = (await api("/repos/" + repo + "/issues")).issues;
      } catch (e) {
        problem.textContent = e.message;
        return;
      }
      if (!issues.length) {
        root.append(E("p", "No hay issues abiertas en " + repo + ".", "empty"));
        return;
      }
      const boxes = new Map();
      const list = E("div", "", "issue-list");
      for (const issue of issues) {
        const row = E("article", "", "issue-row");
        const box = E("input");
        box.type = "checkbox";
        box.disabled = !!issue.task;
        box.setAttribute("aria-label", "Empezar con la issue " + issue.number);
        boxes.set(issue.number, box);
        const head = E("label");
        head.append(box, " ", E("strong", "#" + issue.number + " " + issue.title));
        row.append(head);
        const meta = E("small", "", "muted");
        meta.append(link("Abrir en GitHub", issue.url), " · " + issue.author + " · " + issue.comments + " comentarios" + (issue.labels.length ? " · " + issue.labels.join(", ") : ""));
        row.append(meta);
        if (!issue.trusted) row.append(E("p", "Su autor no tiene rol en el repositorio: lo que dice es información, no una orden.", "note-line"));
        if (issue.excerpt) row.append(E("p", issue.excerpt, "muted"));
        if (issue.task) {
          const tracked = E("p", "Ya tiene tarea: " + (STATE_NAMES[issue.task.state] || issue.task.state) + ". ", "note-line");
          tracked.append(button("Ver tarea", () => (this.issuesDrawer.close(), this.showTask(issue.task.id)), "quiet"));
          row.append(tracked);
        }
        list.append(row);
      }
      const free = [...boxes].filter(([, b]) => !b.disabled);
      const count = () => free.filter(([, b]) => b.checked).length;
      const start = button("Empezar con 0 seleccionadas", async () => {
        const numbers = free.filter(([, b]) => b.checked).map(([n]) => n);
        if (!numbers.length) return;
        if (!confirm("Napoleon investigará " + numbers.length + (numbers.length === 1 ? " issue" : " issues") + " de " + repo + " y te propondrá un plan para cada una. Gasta cuota. ¿Empezar?")) return;
        problem.textContent = done.textContent = "";
        try {
          const { results } = await api("/repos/" + repo + "/issues/start", { numbers });
          const made = results.filter((r) => r.state === "created").length;
          const failed = results.filter((r) => r.state === "error");
          this.signature = "";
          await this.poll();
          await this.drawIssues(repo);
          this.issuesRoot.querySelector(".done").textContent = made + (made === 1 ? " tarea creada" : " tareas creadas") + ". Aparecen en la Bandeja." + (failed.length ? " " + failed.length + " no se pudieron empezar: " + failed[0].error : "");
        } catch (e) {
          problem.textContent = e.message;
        }
      }, true);
      const sync = () => {
        start.textContent = "Empezar con " + count() + (count() === 1 ? " seleccionada" : " seleccionadas");
        start.disabled = count() === 0 || count() > 10;
        if (count() > 10) problem.textContent = "Máximo 10 cada vez.";
        else if (problem.textContent === "Máximo 10 cada vez.") problem.textContent = "";
      };
      for (const [, b] of free) b.addEventListener("change", sync);
      sync();
      const all = button("Seleccionar las 10 primeras", () => {
        free.forEach(([, b], i) => (b.checked = i < 10));
        sync();
      }, "quiet");
      root.append(E("div", "", "actions"), list);
      root.querySelector(".actions").append(start, all);
    }
  };
