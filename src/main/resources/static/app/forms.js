import { E, api, button, lastRepo, rememberRepo } from "./lib.js";

export const withForms = (Base) =>
  class extends Base {
    // Built once, like the other drawers, so a code being typed survives polling.
    secondFactorForm() {
      const section = E("section");
      const problem = E("p", "", "error");
      problem.setAttribute("role", "alert");
      const state = E("p");
      const enrollment = E("div");
      const code = E("input");
      code.inputMode = "numeric";
      code.autocomplete = "one-time-code";
      const label = E("label", "Código de seis cifras");
      label.append(code);
      const actions = E("div", "", "actions");
      const run = async (path, body) => {
        try {
          const result = await api(path, body);
          problem.textContent = "";
          code.value = "";
          this.signature = "";
          return result;
        } catch (e) {
          problem.textContent = e.message;
          return null;
        }
      };
      const refresh = async () => {
        const status = await api("/me/2fa");
        const owner = this.data.me?.role === "owner";
        state.textContent =
          (status.active ? "Activo en tu cuenta." : "Sin activar en tu cuenta.") +
          (status.required_to_integrate ? " Hace falta para integrar." : " No hace falta para integrar.");
        actions.replaceChildren();
        if (status.active) {
          enrollment.replaceChildren();
          actions.append(
            button("Desactivar", async () => {
              if (await run("/me/2fa/disable", { code: code.value.trim() })) await refresh();
            }, "quiet"),
          );
          if (owner)
            actions.append(
              button(status.required_to_integrate ? "Dejar de exigir para integrar" : "Exigir para integrar", async () => {
                if (await run("/governance/two-factor-to-integrate/" + !status.required_to_integrate, {})) await refresh();
              }, "quiet"),
            );
        } else {
          actions.append(
            button("Generar clave", async () => {
              const created = await run("/me/2fa/setup", {});
              if (!created) return;
              const uri = E("a", "Abrir en la aplicación");
              uri.href = created.uri;
              enrollment.replaceChildren(
                E("p", "Añade esta clave a tu aplicación de autenticación y escribe el código que te muestre. Solo se enseña ahora."),
                E("pre", created.secret),
                uri,
              );
            }, "quiet"),
            button("Confirmar código", async () => {
              if (await run("/me/2fa/confirm", { code: code.value.trim() })) await refresh();
            }, true),
          );
        }
      };
      this.refreshSecondFactor = refresh;
      section.append(
        E("p", "Pide un código además de la contraseña. Si pierdes el teléfono, se restablece con ./agentfarm 2fa-reset desde el equipo del panel.", "muted"),
        state,
        problem,
        enrollment,
        label,
        actions,
      );
      return section;
    }
    // Adopting is an ordinary task: its proposal, with agentfarm.yaml, waits for approval as any other.
    adoptionForm() {
      const section = E("section");
      const problem = E("p", "", "error");
      problem.setAttribute("role", "alert");
      const repo = E("input");
      repo.placeholder = "owner/repositorio";
      const label = E("label", "Repositorio");
      label.append(repo);
      section.append(
        E(
          "p",
          "Napoleon analizará el repositorio y te propondrá sus comandos de prueba, sus reglas de calidad y hasta tres mejoras. Desde entonces, AgentFarm vigilará su CI, sus alertas de Dependabot, los comentarios de sus PR y hará una revisión semanal: todo llega como propuesta para que decidas.",
        ),
        problem,
        label,
        button(
          "Analizar repositorio",
          async () => {
            const repository = repo.value.trim();
            if (!/^[\w.-]+\/[\w.-]+$/.test(repository)) {
              problem.textContent = "Indica el repositorio como owner/repositorio.";
              return;
            }
            try {
              await api("/work", {
                request_id: crypto.randomUUID(),
                repository,
                title: "Adoptar " + repository,
                request: "Adopta este repositorio para que los agentes lo mantengan.",
                recipe: { id: "adopt-repo", version: 2 },
              });
              await api("/automation/triggers", {
                repository,
                kinds: ["labeled_issue", "failing_ci", "dependabot", "weekly", "pr_feedback"],
                max_new: 2,
                enabled: true,
              });
              problem.textContent = "";
              repo.value = "";
              this.adoptDrawer.close();
              this.signature = "";
              await this.poll();
            } catch (e) {
              problem.textContent = e.message;
            }
          },
          true,
        ),
      );
      return section;
    }
    requestForm() {
      const section = E("section");
      const problem = E("p", "", "error");
      problem.setAttribute("role", "alert");
      section.append(
        E("p", "Napoleon investigará y te propondrá un alcance antes de cambiar código.", "muted"),
        problem,
      );
      const repo = E("input");
      repo.value = lastRepo();
      repo.placeholder = "owner/repositorio";
      repo.required = true;
      const title = E("input");
      title.placeholder = "Qué quieres mejorar";
      const request = E("textarea");
      request.placeholder = "Describe el objetivo que quieres conseguir.";
      for (const [name, input] of [
        ["Repositorio", repo],
        ["Título", title],
        ["Petición", request],
      ]) {
        const l = E("label", name);
        l.append(input);
        section.append(l);
      }
      const recipe = E("select"),
        guidance = E("details"),
        catalogState = E("small");
      const recipeLabel = E("label", "Receta opcional");
      recipeLabel.append(recipe);
      const manual = E("option", "Petición libre");
      manual.value = "";
      recipe.append(manual);
      recipe.setAttribute("aria-label", "Receta opcional");
      guidance.append(E("summary", "Qué propone la receta"));
      guidance.hidden = true;
      let recipes = [];
      recipe.onchange = () => {
        const chosen = recipes.find(
          (r) => r.id + "@" + r.version === recipe.value,
        );
        guidance.hidden = !chosen;
        guidance.replaceChildren(E("summary", "Qué propone la receta"));
        if (chosen) {
          guidance.append(E("p", chosen.purpose));
          for (const step of chosen.research_steps) guidance.append(E("p", step));
          guidance.append(
            E(
              "small",
              "La receta guía la investigación. El alcance y la integración requieren aprobación.",
            ),
          );
        }
      };
      api("/recipes")
        .then((rows) => {
          recipes = rows;
          for (const r of rows) {
            const option = E("option", r.title + " · v" + r.version);
            option.value = r.id + "@" + r.version;
            recipe.append(option);
          }
        })
        .catch(() => {
          catalogState.textContent =
            "Recetas no disponibles. Puedes enviar una petición libre.";
        });
      section.append(recipeLabel, catalogState, guidance);
      const id = crypto.randomUUID();
      section.append(
        button(
          "Crear issue e investigar",
          async () => {
            if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo.value.trim())) {
              problem.textContent = "Indica el repositorio como owner/nombre.";
              return;
            }
            if (!title.value.trim() || !request.value.trim()) {
              problem.textContent = "Escribe un título y una petición.";
              return;
            }
            try {
              await api("/work", {
                request_id: id,
                repository: repo.value.trim(),
                title: title.value.trim(),
                request: request.value.trim(),
                ...(recipe.value
                  ? {
                      recipe: ((r) => ({ id: r.id, version: r.version }))(
                        recipes.find(
                          (r) => r.id + "@" + r.version === recipe.value,
                        ),
                      ),
                    }
                  : {}),
              });
              this.selectedRepo = repo.value.trim();
              rememberRepo(this.selectedRepo);
              this.form.replaceWith((this.form = this.requestForm()));
              this.newDrawer.close();
              this.signature = "";
              await this.poll();
            } catch (e) {
              problem.textContent = e.message;
            }
          },
          true,
        ),
        E("span", " "),
        button(
          "Pedir ideas de mejora",
          () => {
            title.value = "Proponer mejoras del proyecto";
            request.value =
              "Analiza el estado actual y propone mejoras priorizadas y viables, con fuentes cuando sean necesarias. No implementes nada; esperaré para seleccionar y aprobar el alcance.";
          },
          "quiet",
        ),
      );
      return section;
    }
  };
