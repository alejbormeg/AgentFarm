# Historial de AgentFarm

Registro de incrementos y autorizaciones del promotor. Lo que funciona hoy se
resume en el [README](README.md); la arquitectura y los contratos, en el
[plan técnico](docs/PLAN.md).

## Sin publicar — fase 5: MCP, salud y segunda auditoría

- Segunda auditoría crítica ([AUDIT-2026-11](docs/AUDIT-2026-11.md)) y fases 5–7 en la hoja de ruta.
- `agentfarm.jar mcp`: servidor MCP por stdio para Claude Code y Claude Desktop
  (bandeja, tareas, peticiones, métricas y estado; sin aprobar ni integrar). [Guía](docs/MCP.md)
- `GET /api/health` público (UP/DOWN) con detalles para el propietario; healthcheck de Docker.
- El estado del nodo ya no parpadea con varias ranuras: un latido ocupado se mantiene 12 s.

## Fase 4: bandeja, resultados y avisos (#22)

- Visor de diff del candidato en la bandeja (`GET /api/work/{id}/diff`).
- Resultados por semana desde la auditoría (`GET /api/work/metrics`) en Panorama.
- Avisos: notificaciones del navegador y webhook (json, Slack o ntfy) con outbox.

## Fase 3: automatización gobernada (#21)

- Disparadores por repositorio (issue etiquetada, CI roto, Dependabot, revisión
  semanal, comentarios y checks de PR) que abren propuestas una sola vez y con límite.
- Autonomía por receta: `auto_implement` para cambios de bajo riesgo verificados
  por tests en contenedor; la integración sigue siendo humana.
- Lecciones de memoria propuestas a partir de tus correcciones (opcional).
- Sección Automatización en Gobierno y API `/api/automation`. [Guía](docs/AUTOMATION.md)

## Fase 2: Claude Code y revisión cruzada (#20)

- Adaptador Claude Code (`claude -p` JSON) con herramientas cerradas por rol y
  sin Bash; proveedor configurable por rol, sin fallback automático.
- Coste medido en USD contra una cuenta Clover `api`: reserva por turno y
  liquidación con el coste real; credenciales de nodo con `api_account` opcional.
- Evidencias y propuestas registran el proveedor de cada rol.
- Las ranuras reservan su parte del margen semanal (`AGENTFARM_SLOTS`) en vez
  de todo el margen restante, que impedía admitir trabajo en paralelo.

## Fase 1: paralelo, CI del proyecto, prompts y migraciones (#19)

- Varias tareas a la vez: límite global (`agentfarm.max-parallel`) y por
  repositorio; ranuras de ejecutor `<nodo>-s<N>` con estado propio y señal propia.
- El ejecutor respeta el CI existente del repositorio y exige que todas sus
  comprobaciones pasen; solo añade su workflow a repositorios sin CI.
- Prompts como plantillas versionadas con hash registrado en propuesta y evidencia.
- `schema_migrations`: migraciones ordenadas, transaccionales y protección
  frente a bases de datos de versiones más recientes; índices para reclamaciones y auditoría.

## Fase 1.1: pruebas en contenedor (#18)

- `setup` y pruebas se ejecutan en un contenedor Docker desechable con una copia
  del árbol del commit: sin `.git`, sin credenciales de GitHub ni Codex, sin red
  durante las pruebas por defecto, con límites de CPU, memoria y tiempo.
- El perfil admite `environment: container` y el bloque `container`
  (imagen, red, CPU, memoria, tiempo). `local` exige `-Dagentfarm.sandbox=host`.
- La política del sandbox se guarda en la propuesta, se muestra antes de aprobar
  y se registra en la evidencia de cada prueba.
- El perfil de ejemplo de AgentFarm usa `maven:3.9.11-eclipse-temurin-21`.

## Auditoría, fase 0 (#17)

- Panel: pictogramas SVG para Boxer, Clover y Snowball (sin depender de fuentes emoji).
- Panel: las tareas que esperan tu decisión aparecen primero.
- Panel: un único título por vista; el componente muestra un subtítulo.
- Panel: el To-be muestra el diseño propuesto hasta que exista resultado declarado.
- Panel: fuentes de la investigación como enlaces; diagnóstico legible sin señal.
- Panel: el repositorio por defecto es el último usado, no uno fijo.
- Panel: la fase del ejecutor (investigar, implementar, probar, revisar) se refleja
  en el mapa de agentes con cualquier identificador de nodo.
- Panel: indicación visual de que la barra de pestañas se desplaza en móvil.
- API: `claim` devuelve el comando ya reclamado.
- Eliminados `agentfarm-monitor.js` y `agentfarm-registry.js`, que no se cargaban.

## Incrementos anteriores

### Incremento operativo — issue #5
Implementa la primera ejecución local gobernada: peticiones persistentes con
issue, propuesta versionada, aprobación, rama aislada, pruebas, revisión y PR.
La integración requiere confirmación humana de SHA y comprobaciones GitHub.
El servicio systemd de WSL informa heartbeats y consulta cuota sin inferencia.
Detalles y límites actuales en [OPERATIONS.md](docs/OPERATIONS.md).
Los hitos anteriores de este documento describen la línea base de migración;
no acreditan consenso multinodo ni servicios Forja desplegados.


### Hoja de ruta aprobada — operación gobernada
La evolución vigente se sigue en issues #7 (instalación fiable), #8 (aceptación
por evidencias), #9 (adaptadores y Clover) y #10 (equipos y mejora continua).
Cada fase exige los criterios de salida de la anterior. La política del piloto
vigente detiene al 2 % semanal restante, sustituyendo el ejemplo histórico del
50 %. La señal operativa caduca tras 12 segundos, y la cuota tras 60.
El incremento de fase 1 separa credenciales HTTP de nodo y operador y traslada
la integración al panel; no acredita separación de identidad Linux.
[Estado del incremento y límites](docs/INSTALLATION.md).


### Inicio autorizado de fase 2
Tras fusionar PR #11, el promotor pide comenzar fase 2 expresamente.
Se conservan abiertos los pendientes de #7. El primer incremento de #8
incorpora [expedientes y decisiones por criterio](docs/EVIDENCE.md); no acredita
verificación automática separada ni los criterios de salida de la fase 2.


### Inicio autorizado de fase 3
El promotor aprueba fusionar PR #12 y pide continuar con la siguiente fase.
#7 y #8 permanecen abiertas. El primer incremento de #9 introduce
[contrato de adaptadores y admisión](docs/PROVIDERS.md), sin declarar disponibles
Claude Code, optimización de costes o un planificador multinodo.

### Inicio autorizado de fase 4
El promotor pide fusionar PR #13 y continuar con la siguiente fase.
#7, #8 y #9 permanecen abiertas: esta autorización adelanta un incremento,
sin declarar superados sus criterios de salida. El primer incremento de #10
añade [iniciativas, hitos y dependencias](docs/INITIATIVES.md), con límite de cambios
en desarrollo o revisión y sin ampliar las aprobaciones de alcance o integración.
Recetas, memoria versionada, roles y pilotos permanecen pendientes.

### Segundo incremento de fase 4
Tras fusionar PR #14, el promotor pide continuar. El plan aprobado termina
en fase 4; se entrega su siguiente incremento: [recetas versionadas](docs/RECIPES.md)
para bugs, dependencias, pruebas y migraciones. Seleccionar una receta inicia
investigación; no aprueba desarrollo ni verifica criterios. Se conservan abiertos
los pendientes de #7–#10 y los criterios externos de salida.

### Tercer incremento de fase 4
Tras aprobar y fusionar PR #15, el promotor pide continuar. Se añade
[memoria de proyecto versionada](docs/PROJECT_MEMORY.md): decisiones, restricciones
y rechazos en Git, snapshot del commit base dentro de cada propuesta y receta
para modificarla mediante PR. No se declara una fase 5 ni se cierran #7–#10.
