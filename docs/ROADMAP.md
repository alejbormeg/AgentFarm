# Hoja de ruta: de ejecutor gobernado a equipo autónomo

Objetivo: que un equipo de agentes mantenga y mejore un producto de forma
continua, con el humano decidiendo alcance e integración y con autonomía
creciente donde las evidencias lo justifiquen. Cada fase se entrega en PR
propias y no amplía permisos sin aprobación explícita del promotor.

## Fase 0 — Correcciones rápidas del panel (hecha)

Ver [CHANGELOG](../CHANGELOG.md#sin-publicar--auditoría-fase-0).

## Fase 1 — Seguro y paralelo

1.5 parcial: `TestSandbox`, `Prompts` y la puerta de CI ya viven en el módulo con pruebas; queda trasladar el resto de `FarmDaemon` (estado estático por tarea).

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
| 2.1 | Adaptador Claude Code (CLI/SDK headless) sobre el contrato `AgentAdapter`, con lectura de cuota y cancelación. | Una tarea completa con Claude Code bajo las mismas reglas de admisión. |
| 2.2 | Revisión cruzada: implementa un motor, revisa otro (rol Benjamin). | La evidencia registra motor y modelo de cada rol. |
| 2.3 | Política de selección por receta y coste, sin fallback de pago implícito. | Elegir motor queda auditado y explicado en la tarea. |

## Fase 3 — Proactivo

| # | Entrega | Criterio de salida |
| --- | --- | --- |
| 3.1 | Disparadores con política: issue con etiqueta `agentfarm`, CI rojo en la rama principal, alertas Dependabot/CVE, cron semanal de mejoras. Cada uno crea una propuesta, nunca una implementación. | Un CI rojo genera una propuesta enlazada al run fallido. |
| 3.2 | Bucle de revisión: comentarios de la PR y checks fallidos generan una nueva versión del candidato dentro del presupuesto. | Un comentario de revisión produce un commit nuevo y nueva aprobación por SHA. |
| 3.3 | Niveles de autonomía por receta (p. ej. dependencias *patch* con CI verde pueden autoaprobarse); por defecto todo requiere al humano. | La política es versionada, visible y auditada; desactivarla bloquea al instante. |
| 3.4 | Sobre la [memoria de proyecto](PROJECT_MEMORY.md) existente: el revisor propone lecciones nuevas (tests inestables, rechazos repetidos) como PR de memoria que el humano aprueba. | Un rechazo con motivo genera una propuesta de memoria enlazada. |

## Fase 4 — Medible

| # | Entrega | Criterio de salida |
| --- | --- | --- |
| 4.1 | Bandeja de decisiones como pantalla principal, con visor de diff y aprobar/corregir/rechazar. | Se puede aprobar una PR sin salir del panel, viendo el diff. |
| 4.2 | Métricas: propuestas aprobadas, PR integradas sin corrección, cuota por PR integrada, tiempo de ciclo, regresiones posteriores. | Panel con series semanales calculadas desde la auditoría. |
| 4.3 | Notificaciones (web push, Telegram o correo) cuando algo espera tu decisión. | Aviso en menos de un minuto tras pasar a *proposed* o *review*. |
| 4.4 | Herramientas MCP opcionales por proyecto (Sentry, base de datos de pruebas, navegador). | Declaradas en `agentfarm.yaml` y visibles en la propuesta. |

## Fuera de alcance por ahora

Consenso distribuido entre varios nodos Napoleon/Snowball, despliegue y
fusión sin aprobación humana de alcance nuevo.
