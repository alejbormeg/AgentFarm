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

## Adoptar un repositorio
**Adoptar repositorio** crea una tarea con la receta `adopt-repo` y activa los disparadores de
issues etiquetadas, CI roto, Dependabot, revisión semanal y comentarios de PR. Napoleon analiza
el repositorio y propone `agentfarm.yaml` con sus comandos de prueba y sus reglas de calidad, además
de hasta tres mejoras. Es una tarea normal: la propuesta espera tu aprobación y la PR, tu integración.
Es la única receta que puede añadir `agentfarm.yaml`; ninguna puede modificarlo.
El perfil propuesto pasa por el mismo parser que usa el ejecutor: si no carga, la PR queda en borrador.
Pedir correcciones rehace la adopción desde la rama base y reemplaza su rama.

## Aprobar por lotes y resumen semanal
**Esta semana**, en la Bandeja, resume lo integrado, lo que está en curso, lo que espera y lo
bloqueado. **Listas para integrar** agrupa los cambios de bajo riesgo: recetas `dependency-update`,
`test-improvement` y `project-memory` con todas las comprobaciones superadas y sin excepciones.

El propietario las integra de una vez. Cada una se aprueba por su commit exacto y pasa las
mismas puertas que una fusión individual. Si una ya no cumple, se rechaza esa y siguen las demás.
Una tarea con cualquier otra receta, o sin receta, se integra una a una.

Con un webhook configurado llega también un mensaje semanal, excepto si la semana no tuvo actividad.
`GET /api/work/digest` · `POST /api/work/batch/merge` (solo propietario, hasta 20 tareas).

## Bandeja, resultados y avisos
- **Bandeja:** lo que espera tu decisión aparece primero. En tareas
  en revisión, **Ver cambios del candidato** muestra el diff `base...sha` leído
  de GitHub (requiere el token del panel), sin salir del panel.
- **Resultados (8 semanas):** propuestas, tasa de aprobación (incluidas las de
  política), correcciones pedidas, PR integradas, ciclo mediano y consumo por
  cuenta Clover. `GET /api/work/metrics?weeks=N`. Solo cuenta eventos registrados.
- **Avisos:**
  - Navegador: botón *Avisarme en este navegador* (mientras el panel esté abierto).
  - Webhook: `AGENTFARM_NOTIFY_URL` (https, o http en localhost) con
    `AGENTFARM_NOTIFY_FORMAT=json|slack|ntfy` y `AGENTFARM_PANEL_URL` para el enlace.
    Se envía cuando una tarea pasa a *propuesta*, *revisión* o *bloqueada*: solo
    título y estado, nunca propuesta, código ni registros. Hasta 5 intentos.

## Señales de producción (fase 7)
- **Sentry** (`sentry`): con `AGENTFARM_SENTRY_TOKEN_FILE` (token de solo lectura;
  `AGENTFARM_SENTRY_URL` para instancias propias, solo https), cada error nuevo sin
  resolver de las últimas 24 h del proyecto configurado abre una propuesta
  `bugfix` con el enlace, una sola vez por issue de Sentry. Configuración:
  `{"repository","kinds":["sentry"],"sentry":{"organization","project"}}`.
- **Herramientas MCP por proyecto**: `agentfarm.yaml` puede declarar
  `mcp: [sentry, postgres-test]`, **solo nombres**. Los comandos los define el
  operador del ejecutor en `STATE/mcp-servers.json`:
  `{"sentry": {"command": "npx", "args": ["-y", "@sentry/mcp-server"], "env": ["SENTRY_TOKEN"]}}`
  (`env` lista nombres de variables del ejecutor, nunca valores). Se habilitan en
  investigación y revisión, no en implementación; un nombre no definido bloquea
  la tarea. La propuesta muestra qué servidores se usarán.
