# Historial de AgentFarm

Registro de incrementos y autorizaciones del promotor. Lo que funciona hoy se
resume en el [README](README.md); la arquitectura y los contratos, en el
[plan técnico](docs/PLAN.md).

## Sin publicar — el ejecutor empaquetado vuelve a procesar órdenes

El jar de `f556753` no incluía `FarmDaemon$Task`: el ejecutor moría al coger su primera orden, pero seguía vivo y el panel lo daba por sano. El jar incluye ya todas sus clases, un fallo así termina el proceso para que systemd lo reinicie, y el e2e corre el paquete que se distribuye (#121).

## Sin publicar — adopción probada con un agente real

Codex 0.166 real y GitHub simulado, sobre el commit de esta PR. Antes de los arreglos, las dos adopciones necesitaban una corrección; después, ninguna.

| Repositorio | Antes | Después |
| --- | --- | --- |
| Con código | El perfil no cargaba: un nombre de métrica con `_` | 2 de 2 sin correcciones |
| Sin código | El motivo `N/A` debía coincidir letra a letra, y el perfil contaba como código sin probar | 1 de 1 sin correcciones |

- Un motivo `N/A:` no se compara como si fuera un comando, y `agentfarm.yaml` no cuenta como código sin probar.
- Los nombres de puertas de calidad admiten `_`, y el formato que recibe el agente explica la regla.
- Cada adopción cuesta unos 2 minutos y menos del 1 % de la cuota semanal. No incluye integrar desde el panel con GitHub real.

## Sin publicar — conectar GitHub con un clic

- **Gobierno → Conectar GitHub** crea la GitHub App con el flujo de manifiesto de GitHub y guarda sus credenciales sin copiar nada. Solo se ha probado contra un doble de GitHub.

## Sin publicar — ejecutor con más pruebas

- Las decisiones del ejecutor que no dependen de procesos pasan a `ExecutorWork`, con pruebas de módulo. Se retira su código de integración, que un ejecutor con credencial de nodo nunca recibía: integra el panel.

## Sin publicar — menos ruido en la CI

- Cada PR corre la CI una sola vez, y un push nuevo cancela la ejecución anterior. Antes, cada fallo llegaba dos veces.
- Una descarga de Maven cortada ya no pone en rojo un job, y `comprobar.sh` ejecuta en local lo que correría la CI según lo que cambia la rama.

## Sin publicar — github-check comprueba la escritura

- `./agentfarm github-check` avisa si la credencial no puede escribir en el repositorio, sin fusionar ni escribir nada.

## Sin publicar — guías en inglés

- Instalación, operación y evidencias tienen versión en inglés, enlazada desde `README.en.md`.
- `docs/OPERATIONS.md` deja de citar una contraseña en `panel-password` y un `install-service.sh` sin argumentos que ya no existen.
## Sin publicar — piloto con una orden

- `tests/pilot/run.py` recorre el flujo sobre un repositorio desechable e informa de las medidas de `docs/PILOTS.md`, con un límite de cuota. Solo se ha probado contra un panel simulado.

## Sin publicar — usuario propio para el ejecutor

- `runtime/install-dedicated-user.sh` crea un usuario sin privilegios para el ejecutor y comprueba que no pueda leer la home del operador. Solo se ha probado en un contenedor.

## Sin publicar — modelo de amenazas

- `docs/SECURITY.md` recoge qué se protege, los riesgos que quedan y por qué el camino para Docker es ejecutarlo sin root. Falta una revisión independiente.
## Sin publicar — segundo factor

- Cada persona puede activar un segundo factor (TOTP) en Gobierno, y un propietario puede exigirlo para integrar. `./agentfarm 2fa-reset <usuario>` lo restablece desde el equipo del panel. El servidor MCP acepta `--user`, porque una cuenta con segundo factor no puede usar HTTP Basic.
## Sin publicar — Claude Code con reglas de bloqueo

- Claude Code recibe las rutas ocultas como reglas de bloqueo de `Read` y `Edit`, e ignora los ajustes del repositorio, que podían concederle herramientas o ejecutar hooks. Estas reglas no se han probado con una cuenta real, y el panel lo avisa mientras un rol lo use.

## Sin publicar — avisos del panel comprobados en navegador

- El smoke comprueba los avisos de ejecutor con otra versión, de ejecutor sin aislamiento y de tareas sobre los mismos archivos, y los cambios entre versiones de una propuesta.
## Sin publicar — evidencias legibles

- El expediente muestra el resultado, el comando y la salida de cada prueba, y los comentarios de la revisión, en vez de JSON. El registro completo sigue a un clic.
## Sin publicar — avisos de criterios dudosos

- Al recibir una propuesta, el panel avisa de los criterios de test que piden algo que las pruebas quizá no puedan demostrar, como la identidad de un binario, o que se cierran con un comando que siempre pasa. Avisa sin rechazar, para no perder un turno de investigación.

## Sin publicar — el ejecutor comprueba el aislamiento

- Antes de aceptar trabajo, y cada diez minutos, el ejecutor comprueba con el sandbox real de Codex que las rutas ocultas no se alcanzan. Si falla o no puede saberlo, no acepta tareas y el panel lo avisa.
## Sin publicar — tests estables

- `StoreConcurrencyTest` agotaba su tiempo cuando el disco era lento: hacía 4000 commits con `fsync`. Ahora corre en memoria.
- El smoke de navegador fallaba al limpiar el perfil de Chrome, con todas sus comprobaciones superadas. La limpieza ya no puede romper la prueba.

## Sin publicar — búsqueda web solo al investigar

- Los turnos de implementación y revisión tienen la búsqueda web apagada: una página manipulada ya no puede influir en quien escribe o juzga el código.

## Sin publicar — los agentes no leen credenciales

- Los turnos de Codex ocultan las credenciales del usuario, el estado del ejecutor, las copias, los demás workspaces y los discos montados en `/mnt`.

## Sin publicar — lo aprendido en el piloto sobre forja

- `./agentfarm` sin argumentos muestra la ayuda en vez de lanzar un build.
- Instalar el ejecutor reinicia el servicio y conserva solo la copia actual, la anterior y las que usa otra unidad.
- Con el panel caído, el ejecutor espera con pausas crecientes, sin salir ni llenar el registro.
- El build de la imagen repite Maven solo si se cortó una descarga.
- Los agentes proponen solo criterios que la plataforma puede acreditar (`research.v4`), y el revisor recibe las comprobaciones de la plataforma y no bloquea por criterios manuales (`review.v3`).
- Al adoptar, las puertas del nuevo `agentfarm.yaml` se ejecutan y su fallo deja la PR en borrador; sus comandos de pruebas deben coincidir con los aprobados.
- `adopt-repo` v2: los comandos cubren todo el repositorio, las rutas permiten el trabajo futuro y un repositorio sin código usa `N/A: motivo`. Avisa de los directorios sin cubrir.
- Si la rama base avanzó, corregir una tarea rehace el candidato desde la base y reemplaza su rama, sobre la misma PR.
- Una PR fusionada fuera del panel cierra la tarea e indica que no pasó por sus puertas. Una PR con conflictos bloquea la integración, y el expediente avisa de otras tareas que tocan los mismos archivos.
- Cada versión de una propuesta muestra qué cambia respecto a la anterior.
- Los turnos de Codex ocultan las credenciales del usuario, el estado del ejecutor, las copias, los demás workspaces y los discos montados en `/mnt`.
- El e2e verifica la aceptación e integra por SHA desde el panel, contra un doble de GitHub que rechaza lo mismo que el real.
- `./agentfarm github-token` y `github-app` guardan la credencial sin mostrarla, y `github-check` prueba los permisos que necesita la integración.
- `GET /api/version` indica el commit del panel, que avisa si un ejecutor corre otro distinto, y `doctor` explica cómo arrancar Docker.
- `docs/PILOTS.md`: cuándo repetir un piloto real, cómo recorrerlo y qué medir.

## Sin publicar — Docker fuera del alcance de los agentes

- El perfil de los agentes oculta los sockets de contenedores y el directorio de sesión del usuario, y fija la red apagada. Antes, el socket de Docker solo estaba bloqueado porque no había red.
- `tests/codex_sandbox.sh` comprueba estos bloqueos contra el sandbox real de Codex.

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
