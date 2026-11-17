import { E, api } from "./lib.js";

// Where this repository's CI runs (#241). Local CI is the owner's explicit choice, never automatic.
export function ciSection(repo) {
  const box = E("section", "", "ci-mode");
  box.append(E("h3", "Dónde corre la CI"));
  const status = E("p", "Cargando…", "muted");
  box.append(status);
  const problem = E("p", "", "error");
  problem.setAttribute("role", "alert");
  api("/repos/" + repo + "/ci")
    .then((mode) => {
      const toggle = E("input");
      toggle.type = "checkbox";
      toggle.checked = mode.local;
      toggle.disabled = !mode.available && !mode.local;
      const label = E("label", "CI local: el panel ejecuta las pruebas aprobadas en un nodo de la granja y publica el resultado con su App");
      label.prepend(toggle);
      const explain = () =>
        (status.textContent = toggle.checked
          ? "Se exige: " + mode.checks.join(" y ") + ", publicados por la App del panel para cada commit. No es una CI independiente: la ejecuta un equipo de la granja."
          : mode.available
            ? "La CI de GitHub decide. Elige CI local solo si GitHub no ejecuta tus workflows (sin minutos o con la facturación bloqueada)."
            : "La CI de GitHub decide. La CI local necesita la GitHub App del panel (Gobierno → Conectar GitHub).");
      explain();
      toggle.addEventListener("change", async () => {
        problem.textContent = "";
        try {
          mode = await api("/repos/" + repo + "/ci", { local: toggle.checked });
          toggle.checked = mode.local;
        } catch (e) {
          toggle.checked = !toggle.checked;
          problem.textContent = e.message;
        }
        explain();
      });
      box.insertBefore(label, status);
    })
    .catch((e) => (status.textContent = e.message));
  box.append(problem);
  return box;
}
