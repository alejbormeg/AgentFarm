# Hoja de ruta: de ejecutor gobernado a equipo autónomo

Objetivo: que un equipo de agentes mantenga y mejore un producto de forma
continua, con el humano decidiendo alcance e integración y con autonomía
creciente donde las evidencias lo justifiquen. Cada fase se entrega en PR
propias y no amplía permisos sin aprobación explícita del promotor.

## Fase 0 — Correcciones rápidas del panel (hecha)

Ver [CHANGELOG](../CHANGELOG.md#sin-publicar--auditoría-fase-0).

## Fase 1 — Seguro y paralelo

1.5 parcial: `TestSandbox`, `Prompts`, la puerta de CI y `ExecutorWork` ya viven en el módulo con pruebas, incluidas las decisiones de investigación, implementación e integración, y el estado de cada orden está en `FarmDaemon.Task`. Queda lo que lanza procesos y llama a GitHub, que solo ejercita el e2e.

| # | Entrega | Criterio de salida |
| --- | --- | --- |
| 1.1 ✅ | Tests y `setup` en un contenedor efímero por tarea: sin `~/.config/gh` ni credenciales de Codex, red limitada, CPU/memoria/tiempo acotados. El push lo hace el ejecutor fuera del contenedor. | Un test que lee `~/.config/gh` o abre red no permitida falla; los tests de proyecto pasan igual que en el host. |
| 1.2 ✅ | Planificador con N ranuras: lease y heartbeat por comando, límite por repositorio y por cuenta; varias tareas a la vez en worktrees separados. | Dos tareas de repos distintos avanzan en paralelo; nunca hay doble claim tras caída del ejecutor. |
| 1.3 ✅ | Usar el CI declarado en `agentfarm.yaml` del proyecto; dejar de sobrescribir `.github/workflows/agentfarm.yml`. Plantilla solo si el proyecto no tiene CI. | Una PR en un repo con CI propio no añade workflows. |
| 1.4 ✅ | Prompts como ficheros versionados (`prompts/*.md`) con hash guardado en la evidencia, igual que las recetas. | La evidencia identifica la versión de prompt usada. |
| 1.5 (parcial) | `FarmDaemon` como módulo Maven con pruebas; estado por tarea en vez de estáticos globales. | Cobertura de los caminos research/implement/merge con dobles de Codex y `gh`. |
| 1.6 ✅ | Migraciones de esquema versionadas en lugar de `CREATE TABLE IF NOT EXISTS` dispersos. | Base de datos de la versión anterior se actualiza sin pérdida. |

## Fase 2 — Varios motores y revisión cruzada

| # | Entrega | Criterio de salida |
| --- | --- | --- |
| 2.1 ✅ | Adaptador Claude Code (CLI/SDK headless) sobre el contrato `AgentAdapter`, con lectura de cuota y cancelación. | Una tarea completa con Claude Code bajo las mismas reglas de admisión. |
| 2.2 ✅ | Revisión cruzada: implementa un motor, revisa otro (rol Benjamin). | La evidencia registra motor y modelo de cada rol. |
| 2.3 ✅ | Política de selección por receta y coste, sin fallback de pago implícito. | Elegir motor queda auditado y explicado en la tarea. |

## Fase 3 — Proactivo

| # | Entrega | Criterio de salida |
| --- | --- | --- |
| 3.1 ✅ | Disparadores con política: issue con etiqueta `agentfarm`, CI rojo en la rama principal, alertas Dependabot/CVE, cron semanal de mejoras. Cada uno crea una propuesta, nunca una implementación. | Un CI rojo genera una propuesta enlazada al run fallido. |
| 3.2 ✅ | Bucle de revisión: comentarios de la PR y checks fallidos generan una nueva versión del candidato dentro del presupuesto. | Un comentario de revisión produce un commit nuevo y nueva aprobación por SHA. |
| 3.3 ✅ | Niveles de autonomía por receta (p. ej. dependencias *patch* con CI verde pueden autoaprobarse); por defecto todo requiere al humano. | La política es versionada, visible y auditada; desactivarla bloquea al instante. |
| 3.4 ✅ | Sobre la [memoria de proyecto](PROJECT_MEMORY.md) existente: el revisor propone lecciones nuevas (tests inestables, rechazos repetidos) como PR de memoria que el humano aprueba. | Un rechazo con motivo genera una propuesta de memoria enlazada. |

## Fase 4 — Medible (hecha salvo 4.4, movida a 7.1)

| # | Entrega | Criterio de salida |
| --- | --- | --- |
| 4.1 ✅ | Bandeja de decisiones como pantalla principal, con visor de diff y aprobar/corregir/rechazar. | Se puede aprobar una PR sin salir del panel, viendo el diff. |
| 4.2 ✅ | Métricas: propuestas aprobadas, PR integradas sin corrección, cuota por PR integrada, tiempo de ciclo, regresiones posteriores. | Panel con series semanales calculadas desde la auditoría. |
| 4.3 ✅ | Notificaciones (web push, Telegram o correo) cuando algo espera tu decisión. | Aviso en menos de un minuto tras pasar a *proposed* o *review*. |
| 4.4 | Herramientas MCP opcionales por proyecto (Sentry, base de datos de pruebas, navegador). | Declaradas en `agentfarm.yaml` y visibles en la propuesta. |

## Fase 5 — Operable desde donde trabajas (auditoría de noviembre)

Origen: [segunda auditoría](AUDIT-2026-11.md).

| # | Entrega | Criterio de salida |
| --- | --- | --- |
| 5.1 ✅ | Servidor MCP de AgentFarm (stdio): bandeja, peticiones, propuestas y métricas desde Claude Code o Claude Desktop; sin aprobar ni integrar. | Un cliente MCP lista la bandeja y crea una petición con la credencial del propietario. |
| 5.2 ✅ | `GET /api/health` sin secretos (panel, base de datos, ejecutores frescos, cola) y latido de nodo agregado entre ranuras. | El healthcheck de Compose usa `/api/health`; el nodo no parpadea con dos ranuras. |
| 5.3 ✅ | Ejecución de punta a punta en CI con Codex app-server y `gh` simulados. | Una tarea recorre research → implement → review → PR en CI sin red ni cuota. |

## Fase 6 — Equipo pequeño

| # | Entrega | Criterio de salida |
| --- | --- | --- |
| 6.1 ✅ | Roles propietario, revisor y observador; 2FA opcional. | Un revisor aprueba propuestas pero no integra. |
| 6.2 ✅ | GitHub App con permisos mínimos por repositorio. | El panel funciona sin token personal. |
| 6.3 ✅ | Coste esperado por receta antes de aprobar, desde el histórico. | La propuesta muestra el rango de coste observado. |

## Fase 7 — Señales de producción

| # | Entrega | Criterio de salida |
| --- | --- | --- |
| 7.1 ✅ | Herramientas MCP por proyecto declaradas en `agentfarm.yaml` (antigua 4.4). | Visibles en la propuesta y limitadas por rol. |
| 7.2 ✅ | Disparadores desde Sentry/Grafana. | Un error nuevo abre una propuesta `bugfix` enlazada. |
| 7.3 ✅ | Interfaz fuera de Mateu. | Sin avisos de deprecación; mismas vistas. |

## Fase 8 — Mantener repos para desarrolladores

Objetivo: dejar de mantener un repositorio, sin perder el control de lo que se integra.

| # | Entrega | Criterio de salida |
| --- | --- | --- |
| 8.1 ✅ | Prueba de navegador en la CI. | Una vista en blanco rompe la CI. |
| 8.2 ✅ | [Verificador](EVIDENCE.md#verificador): las pruebas superadas cierran sus criterios. | Un criterio de test se cierra sin excepción manual. |
| 8.3 ✅ | [Siete Mandamientos](COMMANDMENTS.md) como puertas. | Uno incumplido bloquea la integración. |
| 8.4 (parcial) | [Skills](SKILLS.md) por rol y tecnología. | Comparadas con un agente real sin diferencias en el resultado; faltan tareas más difíciles. |
| 8.5 (parcial) | Adoptar un repo, resumen semanal y aprobación por lotes. | Adopción e integración desde el panel probadas sobre forja; falta el resumen semanal y los lotes con un repo real. |
| 8.6 ✅ | Vocabulario y lenguaje llano. | Falta validarlo con usuarios. |
| 8.7 (parcial) | Modo demo y página pública. | Falta publicarla y abrir el repositorio. |
| 8.8 | Vía para quien tiene ideas y no programa: entrevista, repo nuevo y vistas previas. | Después de validar lo anterior. |

## Fuera de alcance por ahora

Consenso distribuido entre varios nodos Napoleon/Snowball, despliegue y
fusión sin aprobación humana de alcance nuevo.
