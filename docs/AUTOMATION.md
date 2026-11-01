# Automatización gobernada

AgentFarm puede trabajar sin que escribas cada petición, pero sin saltarse tu
gobierno: los disparadores **solo abren propuestas**, la autonomía por receta
solo puede **aprobar la implementación** de cambios de bajo riesgo, e **integrar
siempre exige tu aprobación del SHA exacto**. La pausa de la granja detiene
disparadores, autoaprobaciones y reclamaciones.

Configuración en **Gobierno → Automatización** o por API (propietario + CSRF):
`GET /api/automation`, `POST /api/automation/triggers`,
`POST /api/automation/triggers/run`, `POST /api/automation/autonomy`.

## Disparadores por repositorio
Requieren `AGENTFARM_GITHUB_TOKEN_FILE` en el panel (lectura de issues, Actions y
alertas Dependabot). Se revisan cada 5 minutos (`agentfarm.trigger-interval-ms`).

| Disparador | Señal | Propuesta |
| --- | --- | --- |
| `labeled_issue` | Issue abierta con la etiqueta `agentfarm` | Investigar la issue (enlazada) |
| `failing_ci` | Última ejecución completada de la rama principal falla | Receta `bugfix` |
| `dependabot` | Alerta Dependabot abierta | Receta `dependency-update` |
| `weekly` | Una vez por semana ISO | Hasta tres mejoras priorizadas |
| `pr_feedback` | Cambios solicitados o checks fallidos en la PR de una tarea en revisión | Corrección: nueva versión de la propuesta |

Cada señal se procesa una sola vez (`trigger_events`) y cada repositorio abre como
máximo `max_new` propuestas por pasada (1-10). Las peticiones se auditan con el
actor `trigger:<tipo>`.

## Autonomía por receta
- `manual` (por defecto): toda propuesta espera tu aprobación.
- `auto_implement`, solo para `bugfix`, `dependency-update` y `test-improvement`:
  la propuesta se aprueba sola si **todos** sus criterios se verifican con tests y
  las pruebas corren en contenedor. Se audita como `policy:autonomy/<receta>`.
  Las migraciones y cualquier criterio manual siempre esperan.
- `propose_on_feedback`, solo para `project-memory`: cada corrección tuya con
  texto abre una propuesta para registrar la lección en la memoria del proyecto.

No existe nivel de autointegración.
