import { E, api, button } from "./lib.js";

// Add an executor machine from the panel: a one-use command, and the credentials already issued.
// A drawer built once, so the 2 s poll never erases a command being copied.
export const withNodes = (Base) =>
  class extends Base {
    nodeForm() {
      const root = E("div", "", "node-form");
      this.nodeRoot = root;
      return root;
    }
    async openNodes() {
      this.nodeDrawer.showModal();
      await this.drawNodes();
    }
    async drawNodes(command) {
      const root = this.nodeRoot;
      const problem = E("p", "", "error");
      problem.setAttribute("role", "alert");
      const name = E("input");
      name.value = "napoleon-" + (Math.floor(Math.random() * 90) + 10);
      name.maxLength = 60;
      name.setAttribute("aria-label", "Nombre del nodo");
      const label = E("label", "Nombre del nuevo nodo");
      label.append(name);
      const make = button(
        "Generar comando",
        async () => {
          problem.textContent = "";
          try {
            await this.drawNodes(await api("/executors/enroll", { node: name.value.trim() }));
          } catch (e) {
            problem.textContent = e.message;
          }
        },
        true,
      );
      root.replaceChildren(
        E("p", "En el equipo nuevo hace falta Java 21, Git, Docker, GitHub CLI y Codex. Pega el comando en su terminal: instala el ejecutor y lo conecta a este panel.", "muted"),
        label,
        make,
        problem,
      );
      if (command) {
        const box = E("div", "", "command-box");
        const pre = E("pre", command.command);
        box.append(
          E("p", "Código de un solo uso para «" + command.node + "»: caduca en " + Math.round(command.expires_in / 60) + " minutos y no vale una segunda vez."),
          pre,
          button("Copiar comando", async () => {
            try {
              await navigator.clipboard.writeText(command.command);
              problem.textContent = "";
            } catch {
              problem.textContent = "No se pudo copiar: selecciona el texto a mano.";
            }
          }),
        );
        root.append(box);
      }
      root.append(E("h3", "Credenciales de nodos"));
      let items = [];
      try {
        items = await api("/executors");
      } catch (e) {
        problem.textContent = e.message;
      }
      const list = E("ul", "", "credentials");
      if (!items.length) list.append(E("li", "Ninguna todavía.", "empty"));
      for (const item of items) {
        const li = E("li", item.node + " · " + item.account + " · " + (item.revoked ? "revocada" : "activa") + " ");
        if (!item.revoked)
          li.append(
            button(
              "Revocar",
              async () => {
                if (!confirm("¿Revocar la credencial de " + item.node + "? El ejecutor dejará de autenticarse.")) return;
                await api("/executors/" + encodeURIComponent(item.id) + "/revoke", {});
                await this.drawNodes();
              },
              "quiet",
            ),
          );
        list.append(li);
      }
      root.append(list);
    }
  };
