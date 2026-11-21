import { E, api, button } from "./lib.js";
import { NAMES, fallbacks, nodeFiles, steps } from "./provider-steps.js";

const ROLES = { research: "Napoleon · investiga", implement: "Boxer · implementa", review: "Benjamin · revisa" };

// Which provider each role uses on each node. A drawer built once, refilled on open, so the poll
// never erases a choice being made.
export const withProviders = (Base) =>
  class extends Base {
    providerForm() {
      const root = E("div", "", "providers");
      this.providerRoot = root;
      return root;
    }
    async openProviders() {
      this.providerDrawer.showModal();
      await this.drawProviders();
    }
    async drawProviders() {
      const root = this.providerRoot;
      root.replaceChildren(E("p", "Cargando…", "muted"));
      const problem = E("p", "", "error");
      problem.setAttribute("role", "alert");
      let runtime, nodes;
      try {
        [runtime, nodes] = await Promise.all([api("/runtime"), api("/overview").then((o) => o.nodes || [])]);
      } catch (e) {
        root.replaceChildren(E("p", e.message, "error"));
        return;
      }
      root.replaceChildren(
        E("p", "Las claves y los inicios de sesión viven en el equipo de cada nodo: el panel nunca los ve. Aquí eliges qué proveedor y qué modelo usa cada agente (dentro de tu suscripción) y ves si está listo. Si dejas el modelo vacío se usa el del proveedor.", "muted"),
      );
      if (!nodes.length) root.append(E("p", "Añade un nodo primero.", "empty"));
      for (const node of nodes) {
        const seen = (runtime.executors || []).find((x) => x.snapshot?.node === node.id && x.fresh)?.snapshot;
        const box = E("section", "", "provider-node");
        box.append(E("h3", node.id));
        box.append(E("p", seen ? "Con señal. Ahora: " + ["research", "implement", "review"].map((r) => ROLES[r].split(" · ")[0] + " → " + (NAMES[seen.roles?.[r]] || "Codex (ChatGPT)")).join(", ") : "Sin señal reciente: no se puede comprobar qué hay instalado.", "muted"));
        if (seen?.isolation_notice) box.append(E("p", seen.isolation_notice, "note-line"));
        let chosen = {};
        try {
          chosen = (await api("/executors/" + encodeURIComponent(node.id) + "/providers")).roles || {};
        } catch (e) {
          problem.textContent = e.message;
        }
        for (const f of fallbacks(chosen, seen?.roles))
          box.append(E("p", ROLES[f.role].split(" · ")[0] + ": elegiste " + NAMES[f.chosen] + ", pero no está listo en este nodo, así que sigue con " + (NAMES[f.running] || f.running) + ". Conéctalo abajo.", "note-line"));
        const selects = {};
        const models = {};
        for (const role of Object.keys(ROLES)) {
          const select = E("select");
          select.setAttribute("aria-label", ROLES[role] + " en " + node.id);
          select.append(Object.assign(E("option", "Como está configurado en el equipo"), { value: "" }));
          for (const provider of Object.keys(NAMES)) {
            const info = seen?.providers?.[provider];
            const ready = !seen || info?.ready;
            const option = E("option", NAMES[provider] + (seen && !ready ? " · falta configurar" : ""));
            option.value = provider;
            option.disabled = !!seen && !ready;
            select.append(option);
          }
          select.value = chosen[role] || "";
          selects[role] = select;
          const label = E("label", ROLES[role]);
          label.append(select);
          box.append(label);
          // The model of this role, from what the provider offers on that machine (or any id typed).
          const model = E("input");
          model.placeholder = "Modelo por defecto del proveedor";
          model.setAttribute("aria-label", "Modelo de " + ROLES[role].split(" · ")[0] + " en " + node.id);
          model.maxLength = 64;
          model.value = chosen.models?.[role] || "";
          const list = E("datalist");
          list.id = "models-" + node.id + "-" + role;
          model.setAttribute("list", list.id);
          const offer = () => {
            const provider = select.value || seen?.roles?.[role] || "codex";
            const options = provider === "claude-code" ? CLAUDE_SUGGESTED : seen?.models?.[provider] || [];
            list.replaceChildren(...options.map((o) => Object.assign(E("option", o.name || o.id), { value: o.id })));
          };
          select.addEventListener("change", offer);
          offer();
          models[role] = model;
          const modelLabel = E("label", "Modelo de " + ROLES[role].split(" · ")[0]);
          modelLabel.append(model, list);
          box.append(modelLabel);
        }
        box.append(
          button(
            "Guardar para " + node.id,
            async () => {
              problem.textContent = "";
              try {
                const roles = {};
                const chosenModels = {};
                for (const role of Object.keys(selects)) {
                  roles[role] = selects[role].value;
                  chosenModels[role] = models[role].value.trim();
                }
                await api("/executors/" + encodeURIComponent(node.id) + "/providers", { roles, models: chosenModels });
                box.append(E("p", "Guardado. Se aplica a la próxima tarea que coja este nodo.", "done"));
              } catch (e) {
                problem.textContent = e.message;
              }
            },
            true,
          ),
        );
        const guide = E("details");
        guide.dataset.key = "providers-guide-" + node.id;
        guide.append(E("summary", "Cómo conectar una cuenta"));
        const files = nodeFiles(seen?.state_dir);
        for (const provider of Object.keys(NAMES)) {
          const info = seen?.providers?.[provider];
          guide.append(E("h4", NAMES[provider] + (seen ? (info?.ready ? " · listo" : info?.installed ? " · instalado, falta configurar" : " · no instalado") : "")));
          const list = E("ol");
          for (const step of steps(provider, files)) list.append(E("li", step));
          guide.append(list);
        }
        box.append(guide);
        root.append(box);
      }
      root.append(problem);
    }
  };
