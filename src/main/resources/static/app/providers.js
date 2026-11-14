import { E, api, button } from "./lib.js";

const NAMES = { codex: "Codex (ChatGPT)", "claude-code": "Claude Code", antigravity: "Antigravity (Gemini)" };
const ROLES = { research: "Napoleon · investiga", implement: "Boxer · implementa", review: "Benjamin · revisa" };
// What the owner does on the node's machine: credentials never pass through the panel.
const STEPS = {
  codex: ["En el equipo del nodo: codex login (sesión de tu suscripción).", "Comprueba con: codex login status."],
  "claude-code": [
    "En Presupuesto crea una cuenta de cuota de modo api (USD): es el tope de gasto de Claude.",
    "En el equipo del nodo guarda tu clave de Anthropic en ~/.local/state/agentfarm/anthropic-api-key (chmod 600).",
    "Añade AGENTFARM_CLAUDE_ACCOUNT=<tu cuenta> a ~/.config/agentfarm/executor.env y reinicia: systemctl --user restart agentfarm-executor.",
  ],
  antigravity: [
    "En el equipo del nodo ejecuta agy una vez e inicia sesión con tu cuenta de Google.",
    "En Presupuesto crea una cuenta de cuota de modo api (USD): es el tope de gasto, estimado por tokens; la cuota real de tu plan de Google no se puede leer.",
    "Añade AGENTFARM_ANTIGRAVITY_ACCOUNT=<tu cuenta> a ~/.config/agentfarm/executor.env y reinicia: systemctl --user restart agentfarm-executor.",
  ],
};

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
        E("p", "Las claves y los inicios de sesión viven en el equipo de cada nodo: el panel nunca los ve. Aquí eliges qué proveedor usa cada agente y ves si está listo.", "muted"),
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
        const selects = {};
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
        }
        box.append(
          button(
            "Guardar para " + node.id,
            async () => {
              problem.textContent = "";
              try {
                const roles = {};
                for (const role of Object.keys(selects)) roles[role] = selects[role].value;
                await api("/executors/" + encodeURIComponent(node.id) + "/providers", { roles });
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
        for (const [provider, steps] of Object.entries(STEPS)) {
          const info = seen?.providers?.[provider];
          guide.append(E("h4", NAMES[provider] + (seen ? (info?.ready ? " · listo" : info?.installed ? " · instalado, falta configurar" : " · no instalado") : "")));
          const list = E("ol");
          for (const step of steps) list.append(E("li", step));
          guide.append(list);
        }
        box.append(guide);
        root.append(box);
      }
      root.append(problem);
    }
  };
