import { E, api, button } from "./lib.js";

const ROLE_NAMES = { research: "Napoleon · investiga", implement: "Boxer · implementa", review: "Benjamin · revisa" };
const SOURCES = { upload: "Subida por ti", github: "GitHub", anthropic: "Anthropic", openai: "OpenAI", gemini: "Google Gemini" };

// The owner's instructions for one repository: a system message per agent and extra skills.
// A drawer built once and refilled on open, so the 2 s poll never erases what is being typed.
export const withProfile = (Base) =>
  class extends Base {
    profileForm() {
      const root = E("div", "", "profile");
      this.profileRoot = root;
      return root;
    }
    async openProfile(repo) {
      this.profileRepo = repo;
      this.profileDrawer.showModal();
      this.profileRoot.replaceChildren(E("p", "Cargando…", "muted"));
      try {
        this.drawProfile(await api("/repos/" + repo + "/agents"));
      } catch (e) {
        this.profileRoot.replaceChildren(E("p", e.message, "error"));
      }
    }
    drawProfile(profile) {
      const repo = this.profileRepo;
      const root = this.profileRoot;
      const problem = E("p", "", "error");
      problem.setAttribute("role", "alert");
      const done = E("p", "", "done");
      const skills = (profile.skills || []).map((s) => ({ ...s }));
      const fields = {};
      root.replaceChildren(
        E("h3", repo),
        E("p", "Lo que escribas aquí se añade a las instrucciones de cada agente en este repositorio. No amplía permisos: manda siempre el alcance que aprobaste.", "muted"),
      );
      for (const role of ["research", "implement", "review"]) {
        const r = profile.roles[role];
        const area = E("textarea");
        area.rows = 5;
        area.maxLength = 8000;
        area.value = r.system;
        fields[role] = area;
        const label = E("label", ROLE_NAMES[role] + (r.custom ? " · personalizado" : " · por defecto"));
        label.append(area);
        const restore = button("Restaurar el de por defecto", () => (area.value = r.default), "quiet");
        root.append(label, restore);
      }
      root.append(E("h3", "Skills de este repositorio"));
      const list = E("div", "", "skill-list");
      const paint = () => {
        list.replaceChildren();
        if (!skills.length) list.append(E("p", "Ninguna todavía. Sube un archivo .md o busca una en GitHub o en los marketplaces.", "empty"));
        skills.forEach((s, i) => {
          const row = E("div", "", "skill-row");
          const on = E("input");
          on.type = "checkbox";
          on.checked = s.enabled !== false;
          on.addEventListener("change", () => (s.enabled = on.checked));
          const name = E("label", s.title || s.id);
          name.prepend(on);
          const roles = E("span", "", "skill-roles");
          for (const role of ["research", "implement", "review"]) {
            const box = E("input");
            box.type = "checkbox";
            box.checked = s.roles.includes(role);
            box.addEventListener("change", () => (s.roles = ["research", "implement", "review"].filter((x) => (x === role ? box.checked : s.roles.includes(x)))));
            const l = E("label", ROLE_NAMES[role].split(" · ")[0]);
            l.prepend(box);
            roles.append(l);
          }
          const view = E("details");
          view.append(E("summary", "Ver el texto · " + (SOURCES[s.source] || s.source) + (s.origin ? " · " + s.origin : "")), E("pre", s.text));
          row.append(name, roles, view, button("Quitar", () => (skills.splice(i, 1), paint()), "quiet"));
          list.append(row);
        });
      };
      paint();
      const file = E("input");
      file.type = "file";
      file.accept = ".md,.txt,text/markdown,text/plain";
      file.addEventListener("change", async () => {
        const f = file.files[0];
        if (!f) return;
        const id = f.name.replace(/\.(md|txt)$/i, "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 40) || "skill";
        skills.push({ id, title: id, roles: ["research", "implement", "review"], enabled: true, source: "upload", origin: f.name, text: await f.text() });
        file.value = "";
        paint();
      });
      const upload = E("label", "Subir una skill (.md)");
      upload.append(file);
      root.append(list, upload);
      // Skills found online: official catalogues, any GitHub repository, or a search. The panel
      // fetches them; the text is shown whole and added only when the owner says so.
      const finder = E("section", "", "skill-finder");
      finder.append(E("h4", "Buscar skills online"));
      finder.append(E("p", "Anthropic y OpenAI publican las suyas en GitHub; Google usa el mismo formato SKILL.md, así que se buscan como cualquier repositorio. Nada se añade sin que lo veas entero y lo aceptes.", "muted"));
      const look = E("div", "", "finder-results");
      const note = E("p", "", "muted");
      const show = (...nodes) => look.replaceChildren(...nodes);
      const failed = (e) => (note.textContent = e.message);
      const uniqueId = (id) => {
        let candidate = id,
          n = 2;
        while (skills.some((x) => x.id === candidate)) candidate = id.slice(0, 36) + "-" + n++;
        return candidate;
      };
      const preview = async (repoName, path) => {
        note.textContent = "";
        try {
          const found = await api("/skill-sources/skill?repo=" + encodeURIComponent(repoName) + "&path=" + encodeURIComponent(path));
          const box = E("div", "", "skill-preview");
          const details = E("details");
          details.open = true;
          details.append(E("summary", found.title + " · " + found.bytes + " bytes · " + found.origin), E("pre", found.text));
          box.append(
            details,
            E("p", "Fijada por su hash " + found.hash.slice(0, 12) + ". Es texto: no se ejecuta nada.", "muted"),
            button(
              "Añadir a este repositorio",
              () => {
                skills.push({ id: uniqueId(found.id), title: found.title, roles: ["research", "implement", "review"], enabled: true, source: found.source, origin: found.origin, text: found.text });
                paint();
                show(E("p", "Añadida a la lista de arriba. Pulsa Guardar para que la usen los agentes.", "done"));
              },
              true,
            ),
          );
          show(box);
        } catch (e) {
          failed(e);
        }
      };
      const browse = async (repoName, path) => {
        note.textContent = "";
        try {
          const entries = await api("/skill-sources/browse?repo=" + encodeURIComponent(repoName) + "&path=" + encodeURIComponent(path));
          const head = E("p", repoName + (path ? " / " + path : ""), "muted");
          const rows = [head];
          if (path)
            rows.push(button("Subir", () => browse(repoName, path.split("/").slice(0, -1).join("/")), "quiet"));
          const items = entries.filter((x) => x.type === "dir" || x.name === "SKILL.md");
          if (!items.length) rows.push(E("p", "Aquí no hay carpetas ni SKILL.md.", "empty"));
          for (const x of items)
            rows.push(button(x.type === "dir" ? "📁 " + x.name : "Ver " + x.name, () => (x.type === "dir" ? browse(repoName, x.path) : preview(repoName, x.path)), "quiet"));
          show(...rows);
        } catch (e) {
          failed(e);
        }
      };
      const official = E("div", "", "finder-official");
      for (const o of [{ name: "Anthropic", repo: "anthropics/skills", path: "skills" }, { name: "OpenAI", repo: "openai/skills", path: "" }])
        official.append(button(o.name, () => browse(o.repo, o.path)));
      const query = E("input");
      query.placeholder = "propietario/repositorio, o palabras para buscar";
      query.setAttribute("aria-label", "Repositorio o búsqueda de skills");
      official.append(
        query,
        button("Buscar", async () => {
          note.textContent = "";
          const text = query.value.trim();
          if (/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(text)) return browse(text, "");
          try {
            const repos = await api("/skill-sources/search?q=" + encodeURIComponent(text));
            if (!repos.length) return show(E("p", "Sin resultados.", "empty"));
            show(...repos.map((r) => button(r.repo + " ★" + r.stars + (r.description ? " · " + r.description : ""), () => browse(r.repo, ""), "quiet")));
          } catch (e) {
            failed(e);
          }
        }),
      );
      finder.append(official, note, look);
      const actions = E("div", "", "actions");
      actions.append(
        button(
          "Guardar",
          async () => {
            problem.textContent = done.textContent = "";
            try {
              const roles = {};
              for (const role of Object.keys(fields)) roles[role] = { system: fields[role].value };
              this.drawProfile(await api("/repos/" + repo + "/agents", { roles, skills }));
              this.profileRoot.append(E("p", "Guardado. Las próximas tareas de este repositorio usan estas instrucciones.", "done"));
            } catch (e) {
              problem.textContent = e.message;
            }
          },
          true,
        ),
        button(
          "Restaurar todo",
          async () => {
            try {
              this.drawProfile(await api("/repos/" + repo + "/agents/reset", {}));
            } catch (e) {
              problem.textContent = e.message;
            }
          },
          "quiet",
        ),
      );
      const exports = E("p", "", "exports");
      exports.append(E("small", "Descarga el mismo perfil para usarlo fuera de AgentFarm: "));
      for (const [format, label] of [["claude", "Claude (CLAUDE.md)"], ["codex", "ChatGPT/Codex (AGENTS.md)"], ["gemini", "Gemini / Antigravity (GEMINI.md)"]]) {
        const a = E("a", label);
        a.href = "/api/repos/" + repo + "/agents/export?format=" + format;
        a.download = "";
        exports.append(a, document.createTextNode(" "));
      }
      root.append(problem, done, actions, exports);
    }
  };
