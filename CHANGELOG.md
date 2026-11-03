# Historial de AgentFarm

Registro de incrementos y autorizaciones del promotor. Lo que funciona hoy se
resume en el [README](README.md); la arquitectura y los contratos, en el
[plan técnico](docs/PLAN.md).

## Sin publicar — guía de pilotos

- `docs/PILOTS.md`: cuándo repetir un piloto real, cómo recorrerlo y qué medir.

## Sin publicar — adopción probada en un repositorio real

- Un `agentfarm.yaml` que el parser rechaza deja la PR en borrador, en vez de romper las tareas siguientes.
- Las reglas de una investigación salen del perfil de la rama base, nunca del candidato: un perfil inválido ya no bloquea su corrección.
- Corregir una adopción la rehace desde la rama base y reemplaza su rama.
- El agente recibe el formato real del perfil al adoptar un repositorio.

## Sin publicar — modo demo y página pública

- `./agentfarm demo`: la granja completa con agentes y GitHub simulados, en un minuto y sin cuentas.
- README con el nuevo enfoque, capturas y versión en inglés.
- `site/`: página estática lista para publicar. No está publicada.

## Sin publicar — vocabulario de Orwell y lenguaje llano

- Molinos (iniciativas), muros (hitos), cosecha (lo integrado), reunión del domingo (revisión
  semanal), Benjamin (revisor) y «Squealer dice» (lo que declara el agente). Glosario en
  [Animal Farm](docs/ANIMAL_FARM.md). La API no cambia.
- Fuera términos internos como «Admisión», «candidato» o «no acredita», y variables de entorno en la interfaz.
- La Bandeja muestra primero la semana y las decisiones; el estado de los agentes pasa al final.

## Sin publicar — adoptar repositorios y aprobar por lotes

- **Adoptar repositorio:** una tarea analiza el repo, propone `agentfarm.yaml` y deja activa la vigilancia.
- **Esta semana** y **Listas para integrar** en la Bandeja: integrar a la vez los cambios de bajo
  riesgo, cada uno por su commit exacto. Mensaje semanal por webhook.
- Primera vez: la Bandeja indica los pasos y **Preparar este equipo** crea nodo, trabajadores y cuenta
  sin escribir JSON.

## Sin publicar — skills de los agentes

- Cada turno recibe las skills de su rol y de la tecnología del repositorio, escritas en el prompt.
  La evidencia guarda versión y hash, y el panel las muestra. Ver [Skills](docs/SKILLS.md).
- `skills` en `agentfarm.yaml` nombra skills del catálogo o instaladas por el operador.
- `tests/eval/` compara tareas de referencia con y sin skills; aún sin ejecutar con agente real.

## Sin publicar — Los Siete Mandamientos

- Cada candidato se juzga contra siete reglas visibles en Gobierno y en la tarea; una incumplida
  bloquea la integración. Ver [Mandamientos](docs/COMMANDMENTS.md).
- Con la receta `bugfix`, las pruebas nuevas deben fallar sobre el commit base.
- `quality` en `agentfarm.yaml`: comprobaciones y métricas que no pueden empeorar.
- Búsqueda de credenciales antes del push y, de nuevo, desde el panel.
- Cambiar `agentfarm.yaml` o la CI del proyecto bloquea el candidato.

## Sin publicar — verificador automático de criterios

- Las pruebas superadas cierran sus criterios sin excepción manual: el panel lee la CI del
  commit con su propia credencial y exige que los comandos aprobados pasaran en el contenedor.
- `N/A:` ya no cuenta como superado cuando el candidato cambia código, ni puede sostener un
  criterio de test. El prompt de investigación pasa a `research.v3`.

## Sin publicar — Bandeja en blanco y prueba de navegador

- Corregido: la Bandeja salía en blanco por un icono borrado en la PR #31. Si una vista
  falla al dibujarse, ahora lo dice y lo reintenta en vez de quedarse vacía.
- `tests/ui/smoke.mjs` recorre el panel en Chrome dentro del e2e de la CI: las cinco vistas,
  una tarea por enlace directo, el refresco de Agentes y el rol de observador.

## Sin publicar — lavado de cara del panel (Vaadin)

- Navegación por tareas: Bandeja, Tareas, Agentes (une Nodos y Boxer; las direcciones
  antiguas redirigen), Presupuesto y Gobierno.
- Tablero por etapas con búsqueda; la tarea y **Nueva tarea** se abren en un panel
  lateral. Las tarjetas no se arrastran: las etapas cambian por evidencia y aprobación.
- Bandeja con lo que espera tu decisión y por qué; los avisos dicen cómo resolverse.
- Página de acceso propia en español; códigos de presupuesto traducidos; fechas en
  señales que no son de hoy; confirmación al permitir autonomía.
- Corregido: el error de una acción desaparecía en el siguiente refresco (2 s).
- Agentes y Presupuesto usan componentes nativos de Mateu (`StatusList` y `Meter`) que se
  refrescan cada 5 s. Deshabilitar un agente desde Mateu exige ser propietario en el servidor.
- Las tarjetas muestran cuánto llevan en su etapa (migración 4, `work_items.state_since`; las
  tareas anteriores no tienen ese dato). «Cerradas» enseña las 5 últimas y puede desplegarse.
- Revisores y observadores ya no ven controles que el servidor les rechazaría.
- Corregido: fusionar sin publicador de integración mostraba solo «Conflict».

## Sin publicar — copia y recuperación con Docker Compose

- `./agentfarm backup [destino]` funciona con el panel en Compose: crea la copia en
  el contenedor, la extrae y verifica SHA-256 e `integrity_check`. Sin el servicio en
  marcha, usa el JAR. Antes solo llamaba al JAR y, en el piloto, ejecutarlo a mano
  dentro del contenedor dejó un archivo vacío.
- `./agentfarm restore <copia>` valida con el panel en marcha, conserva la base
  anterior en el volumen y la recupera si el panel no arranca con la copia.

## Sin publicar — reentrega de reclamaciones perdidas

- Si la respuesta a `claim` se pierde (el panel tardó más de 10 s durante el piloto),
  el ejecutor recibe el mismo comando al volver a reclamar mientras no haya
  reportado nada; tras el primer reporte nunca se reentrega. Antes, el comando
  quedaba huérfano y se bloqueaba a los 120 s.

## Segundo tramo del piloto real (forja PR #4) (#28)

- El sandbox descarga la imagen si falta, con reintentos ante fallos de red y
  descarga anónima si el ayudante de credenciales configurado no existe
  (p. ej. `docker-credential-desktop.exe` heredado de Docker Desktop).
- La plantilla de CI ejecuta las jobs dentro de la misma imagen del sandbox: en
  el piloto, el `apt-get` del setup funcionaba como root en el contenedor y fallaba
  sin root en GitHub Actions.
- `implement.v2`: AgentFarm hace el commit y ejecuta las pruebas; el implementador
  no debe declarar lo contrario en `limitations`.
- `review.v2`: el workflow de la plantilla es política de la plataforma, no
  alcance del implementador; la evidencia válida es la del contenedor.

## Primer piloto real (forja) (#27)

- Prompt de investigación `research.v2` con la forma exacta de `checks`; el
  ejecutor normaliza variantes inofensivas (objeto o texto) antes de enviar la
  propuesta. En el piloto, Codex devolvió `checks: {"unit": "comando"}` y el
  panel rechazó una investigación ya pagada.
- Las lecturas de GitHub se reintentan (3 veces, con espera) ante fallos de red
  pasajeros; las escrituras no, para no duplicar issues ni PR.

## Fase 7: señales de producción (#26)

- Disparador Sentry: errores nuevos sin resolver abren propuestas `bugfix`, una vez cada uno.
- Herramientas MCP por proyecto: el perfil solo nombra servidores definidos por el
  operador del ejecutor; disponibles en investigación y revisión (Codex y Claude Code).
- Mateu 3.0-beta.32.

## Fase 6: equipo pequeño (#25)

- Roles propietario, revisor y observador (`AGENTFARM_USERS_FILE`, `user-hash`);
  los revisores deciden propuestas, solo los propietarios integran y configuran.
- GitHub App con token de instalación cacheado como alternativa al token personal.
- Coste esperado por receta (mediana y p90 observados) junto al botón de aprobar.
  [Guía](docs/TEAM.md)

## 5.3: tarea de punta a punta en CI (#24)

- `tests/e2e/run.sh`: panel y ejecutor reales con dobles de Codex app-server y
  `gh` sobre un repositorio Git local; la tarea recorre investigación, aprobación,
  implementación, pruebas en contenedor, revisión y PR. Job `e2e` en CI.
- Corrección encontrada por la prueba: el ejecutor publica una lectura de cuota
  (que abre la ventana Clover) y su señal antes de la primera reclamación; antes,
  la primera tarea de una cuenta nueva quedaba bloqueada por admisión.

## Fase 5: MCP, salud y segunda auditoría (#23)

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
