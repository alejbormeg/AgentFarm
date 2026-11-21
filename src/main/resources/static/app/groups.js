import { E, api, button } from "./lib.js";

// Repositories that take part in one change: an integrator (forja) and the ones it integrates.
// A drawer built once and refilled on open, so the 2 s poll never erases what is being typed.
// Declared here by the owner only: nothing written in a repository can widen a group.
const parse = (text) =>
  text
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const [repo, ...role] = line.split(/\s+[—-]\s+|\s{2,}|\t/);
      return { repo: repo.trim(), role: role.join(" ").trim() };
    });

export const withGroups = (Base) =>
  class extends Base {
    groupsForm() {
      const root = E("div", "", "groups");
      this.groupsRoot = root;
      return root;
    }
    async openGroups(integrator) {
      this.groupsDrawer.showModal();
      await this.drawGroups(integrator);
    }
    async drawGroups(editing) {
      const root = this.groupsRoot;
      const problem = E("p", "", "error");
      problem.setAttribute("role", "alert");
      const done = E("p", "", "done");
      done.setAttribute("role", "status");
      let groups;
      try {
        groups = (await api("/repo-groups")).groups || [];
      } catch (e) {
        root.replaceChildren(E("p", e.message, "error"));
        return;
      }
      const known = [...new Set((this.data?.work || []).map((w) => w.repo).filter(Boolean))].sort();
      root.replaceChildren(
        E("p", "Un repositorio que integra otros (por ejemplo forja) y los repositorios que integra. Napoleon solo puede proponer partes de un cambio en estos repositorios. No da ningún permiso: cada repositorio sigue con su propio perfil, su propio token y tu aprobación. Cada miembro tiene que estar adoptado para trabajar en él.", "muted"),
      );
      if (!groups.length) root.append(E("p", "Todavía no hay grupos.", "empty"));
      for (const g of groups) {
        const box = E("section", "", "group");
        box.append(E("h3", g.integrator));
        if (!g.integrator_known) box.append(E("p", "El panel aún no conoce este repositorio: adóptalo para poder trabajar en él.", "note-line"));
        const list = E("ul");
        for (const m of g.members)
          list.append(E("li", m.repo + (m.role ? " — " + m.role : "") + (m.known ? "" : " · sin adoptar en el panel")));
        box.append(list);
        box.append(
          button("Editar", () => this.drawGroups(g.integrator)),
          button("Quitar grupo", async () => {
            if (!confirm("Se quita el grupo de " + g.integrator + ". Los repositorios y sus tareas no cambian. ¿Continuar?")) return;
            try {
              await api("/repo-groups/" + g.integrator + "/delete", {});
              await this.drawGroups();
            } catch (e) {
              problem.textContent = e.message;
            }
          }),
        );
        root.append(box);
      }
      const current = groups.find((g) => g.integrator === editing);
      const form = E("section", "", "group-form");
      form.append(E("h3", current ? "Editar el grupo de " + current.integrator : "Nuevo grupo"));
      const integrator = E("input");
      integrator.setAttribute("list", "group-known-repos");
      integrator.placeholder = "owner/repositorio integrador";
      integrator.setAttribute("aria-label", "Repositorio integrador");
      integrator.value = current?.integrator || "";
      if (current) integrator.readOnly = true;
      const options = E("datalist");
      options.id = "group-known-repos";
      for (const r of known) options.append(Object.assign(E("option"), { value: r }));
      const members = E("textarea");
      members.rows = 6;
      members.placeholder = "Un repositorio por línea: owner/repo — para qué sirve\nowner/api — implementa el contrato";
      members.setAttribute("aria-label", "Repositorios miembros");
      members.value = (current?.members || []).map((m) => m.repo + (m.role ? " — " + m.role : "")).join("\n");
      form.append(
        integrator,
        options,
        members,
        button("Guardar grupo", async () => {
          problem.textContent = "";
          done.textContent = "";
          try {
            await api("/repo-groups/" + integrator.value.trim(), { members: parse(members.value) });
            await this.drawGroups();
            this.groupsRoot.append(Object.assign(E("p", "Grupo guardado.", "done"), { role: "status" }));
          } catch (e) {
            problem.textContent = e.message;
          }
        }),
      );
      root.append(form, problem, done);
    }
  };
