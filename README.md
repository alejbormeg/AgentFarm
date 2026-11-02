# AgentFarm

Plano de control local para un equipo de agentes de desarrollo. Tú describes
una mejora; los agentes investigan, proponen, implementan, prueban, revisan y
abren una PR. **Nada se integra sin tu aprobación del commit exacto.**

Java 21, Spring Boot 4 y Mateu. Un solo proceso, SQLite, sin Node ni servidor SQL.

## Qué hace hoy

| Funciona | Detalle |
| --- | --- |
| Flujo idea → PR | Issue, propuesta versionada con criterios, tu aprobación, rama `agentfarm/<issue>-…`, tests, revisión independiente de solo lectura y PR. [Operación](docs/OPERATIONS.md) |
| Integración gobernada | Aprobar e integrar exige el SHA revisado, CI verde y cobertura de todos los criterios de aceptación. [Evidencias](docs/EVIDENCE.md) |
| Presupuesto (Clover) | Reserva de cuota de suscripción Codex por ventana; parada al umbral configurado; sin pago adicional ni cambio de proveedor. [Presupuesto](docs/BUDGET.md) |
| Los Siete Mandamientos | Pruebas en entorno limpio, prueba de regresión en cada bug, revisión independiente, alcance, métricas que no empeoran y sin secretos. [Mandamientos](docs/COMMANDMENTS.md) |
| Alcance Git | `scope` comprueba que un commit solo toca las rutas permitidas. |
| Iniciativas y recetas | Agrupar tareas con dependencias y límite de cambios en revisión; recetas versionadas para bugs, dependencias, pruebas y migraciones. [Iniciativas](docs/INITIATIVES.md) · [Recetas](docs/RECIPES.md) |
| Memoria de proyecto | Decisiones, restricciones y rechazos versionados en Git; cada propuesta guarda el snapshot del commit base. [Memoria](docs/PROJECT_MEMORY.md) |
| Operación | Pausa global, auditoría de decisiones, heartbeats, doctor, backup/restore. [Instalación del ejecutor](docs/INSTALLATION.md) |

Las pruebas de cada candidato se ejecutan en un contenedor desechable, sin
credenciales ni `.git` y sin red por defecto. [Detalles](docs/INSTALLATION.md#pruebas-en-contenedor)

Varias tareas avanzan a la vez con ranuras de ejecutor, dentro de límites
globales y por repositorio. [Paralelo](docs/INSTALLATION.md#varias-tareas-en-paralelo)

Claude Code puede investigar, implementar o revisar (p. ej. revisión cruzada de
lo que implementa Codex), medido en USD contra una cuenta Clover. [Proveedores](docs/PROVIDERS.md#claude-code-fase-2)

Disparadores (issue etiquetada, CI roto, Dependabot, revisión semanal, feedback de
PR) abren propuestas solos, y la autonomía por receta puede aprobar cambios de
bajo riesgo verificados por tests. Integrar siempre es tuyo. [Automatización](docs/AUTOMATION.md)

Consulta la bandeja y crea peticiones desde Claude Code o Claude Desktop con el
servidor MCP incluido. [MCP](docs/MCP.md)

Equipos pequeños: roles propietario, revisor y observador, GitHub App y coste
esperado antes de aprobar. [Equipo](docs/TEAM.md)

Señales de producción: errores nuevos de Sentry abren propuestas, y cada proyecto
puede dar a sus agentes herramientas MCP aprobadas por el operador. [Automatización](docs/AUTOMATION.md)

**Todavía no:** coordinación entre varios nodos y 2FA. Ver la [segunda auditoría](docs/AUDIT-2026-11.md).
La hoja de ruta está en [docs/ROADMAP.md](docs/ROADMAP.md).

## Empezar en 3 pasos

Necesitas Docker con Compose v2 (en Windows, desde la WSL Ubuntu 24.04).

```bash
git clone https://github.com/alejbormeg/AgentFarm.git && cd AgentFarm
./agentfarm up
./agentfarm password
```

1. Abre **http://localhost:8080** con el usuario **admin** y la contraseña anterior.
2. Conecta el ejecutor WSL (Codex CLI y GitHub CLI autenticados): **Gobierno → Conectar ejecutor**
   o sigue [la guía del ejecutor](docs/OPERATIONS.md#instalación-del-ejecutor-local).
3. Pulsa **Nueva tarea**, indica `owner/repositorio` y describe el objetivo.
   Revisa la propuesta y pulsa **Implementar propuesta**; cuando la PR esté lista, **Aprobar e integrar**.

Otros comandos: `./agentfarm status`, `./agentfarm logs`, `./agentfarm stop`.
Copia y recuperación de la base del volumen: `./agentfarm backup [destino]` y
`./agentfarm restore <copia>`. [Detalles](docs/INSTALLATION.md#copia-y-recuperación)
El volumen `farm-data` conserva base de datos y contraseña; no uses
`docker compose down -v` salvo que quieras borrarlos.

**Sin Docker:** `./mvnw verify` y `java -jar target/agentfarm.jar`. Escucha en
`127.0.0.1:8080`, guarda datos en `./data` y la contraseña en `data/admin-password.txt`.
Más detalles en [instalación](docs/INSTALL.md).

## El panel

- **Bandeja:** lo que espera tu decisión primero, trabajo en curso, resultados y cuota.
- **Tareas:** tablero por etapas, búsqueda e iniciativas. Cada tarjeta abre la tarea con sus evidencias.
- **Agentes:** nodos dirigentes y trabajadores Boxer, heartbeat y habilitación.
- **Presupuesto:** cuota del proveedor, reservas Clover y conciliación.
- **Gobierno:** pausa de la granja, automatización y auditoría.

El progreso que informa un agente se muestra como *declarado*; una señal sin
heartbeat en 12 segundos pasa a desconocida. Ver [roles Animal Farm](docs/ANIMAL_FARM.md).

## Consola

```bash
java -jar target/agentfarm.jar scope --repo /ruta/proyecto --spec examples/task.json --base SHA_BASE --candidate SHA_CANDIDATO
java -jar target/agentfarm.jar budget --db budget.db status codex-subscription
java -jar target/agentfarm.jar coordinator --db state.db status
```

`scope` devuelve 0 si pasa, 1 si bloquea y 2 si hay error. Importar datos de
versiones anteriores: [migración](docs/MIGRATION.md).

## Desarrollo

```bash
./mvnw verify
./mvnw spring-boot:run
E2E_SANDBOX=host bash tests/e2e/run.sh   # tarea completa con Codex y GitHub simulados
                                         # con E2E_CHROME=<chrome> y Node 22, también en navegador
bash tests/compose_backup.sh             # backup/restore con Compose, en un proyecto aparte
```

Reglas para agentes y colaboradores en [AGENTS.md](AGENTS.md). Arquitectura y
contratos en el [plan técnico](docs/PLAN.md); historial en [CHANGELOG](CHANGELOG.md).
Licencia Apache 2.0.
