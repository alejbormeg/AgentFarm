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
      for (const [format, label] of [["claude", "Claude (CLAUDE.md)"], ["codex", "ChatGPT/Codex (AGENTS.md)"], ["gemini", "Gemini (GEMINI.md)"]]) {
        const a = E("a", label);
        a.href = "/api/repos/" + repo + "/agents/export?format=" + format;
        a.download = "";
        exports.append(a, document.createTextNode(" "));
      }
      root.append(problem, done, actions, exports);
    }
  };
