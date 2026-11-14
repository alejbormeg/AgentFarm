# Modelo de amenazas

Qué protege AgentFarm, frente a quién y qué riesgos quedan. Lo ha escrito quien desarrolla el producto,
así que **no sustituye a una revisión independiente**, y el repositorio no debe abrirse sin ella
([#77](https://github.com/alejbormeg/AgentFarm/issues/77)).

## Qué hay que proteger

| Activo | Dónde está | Si se pierde |
| --- | --- | --- |
| Credencial de GitHub del panel | Volumen del panel | Fusionar y escribir en los repositorios a su alcance |
| Sesión de `gh` y de Codex | Home del usuario del ejecutor. Con una GitHub App en el panel, el ejecutor no necesita sesión de `gh`: recibe por orden un token de una hora limitado a ese repositorio. Solo probado con dobles | Publicar como esa persona y gastar su cuota |
| Token del nodo | `AGENTFARM_STATE/node-token` | Reclamar trabajo e informar como ese nodo, sin poder fusionar |
| Contraseña del panel y claves de segundo factor | Volumen del panel y sus copias | Aprobar e integrar como esa persona |
| Credencial de un cliente MCP (`afr_…`) | Archivo privado del usuario, legible por el modelo de ese cliente | Leer tareas y crear peticiones; nada más |
| Docker del ejecutor | Socket del equipo | Equivale a root en el equipo |
| Código de otros proyectos | Home del usuario del ejecutor | Filtrarlo a través de lo que lea un agente |

## Amenazas

La hipótesis central es que **un agente puede estar comprometido**. Lee repositorios, issues y páginas web
que cualquiera puede manipular, y esa inyección de instrucciones **solo se puede limitar, no eliminar**.

| # | Amenaza | Qué la limita hoy | Lo que queda |
| --- | --- | --- | --- |
| T1 | Instrucciones inyectadas desde el repositorio, una issue, una revisión de PR o una web | Propuesta aprobada por una persona, revisión independiente, búsqueda web solo al investigar. Solo entran issues etiquetadas por quien tiene permiso de triaje y revisiones de `OWNER`, `MEMBER` o `COLLABORATOR`. El texto externo llega al agente entre marcas con un código nuevo en cada turno, como datos y no como instrucciones, y un disparador no propone lecciones de memoria | El agente puede escribir código malicioso que parezca correcto. Solo lo paran la revisión y las pruebas. Las marcas ayudan al modelo, pero no lo obligan |
| T2 | Un agente lee credenciales | `AgentSandbox`: perfil de Codex con red apagada, comprobado por el ejecutor antes de reclamar trabajo | Es una lista de exclusión: el resto de la home sigue legible ([#72](https://github.com/alejbormeg/AgentFarm/issues/72)). Con Claude Code solo hay reglas sin probar ([#75](https://github.com/alejbormeg/AgentFarm/issues/75)) |
| T3 | Un agente saca datos fuera | Red apagada en los turnos | Lo que lee llega al proveedor del modelo. La búsqueda web de la investigación puede filtrar datos en sus consultas |
| T4 | El código de una tarea ataca durante sus pruebas | Contenedor efímero sin credenciales, sin `.git`, con límites de CPU, memoria y tiempo | Una fuga del contenedor. La fase `setup` tiene red |
| T5 | Un repositorio trae su propia configuración | Las reglas salen de la rama base. Un repositorio no aporta skills y Claude Code ignora sus ajustes | El `AGENTS.md` o `CLAUDE.md` del repositorio llega al agente como instrucciones |
| T6 | Se integra algo distinto de lo revisado | Integración por SHA con CI en verde en ese commit y los Siete Mandamientos | Quien tenga la credencial de GitHub puede fusionar fuera del panel. El panel lo marca, pero no lo impide |
| T7 | Alguien entra en el panel | Solo en `127.0.0.1` por defecto, CSRF, roles, segundo factor opcional y espera tras 5 contraseñas fallidas, de 1 a 15 minutos, por cuenta y dirección. Política de contenido estricta: solo se carga lo que sirve el panel. Cookie de sesión `HttpOnly` y `SameSite=Lax` (la vuelta desde GitHub al conectar la App es una navegación desde otro sitio; con `Strict` se perdía la sesión), y `Secure` con `AGENTFARM_PANEL_URL` en https. Ningún GET cambia nada sin un `state` de un solo uso | Sin segundo factor, la contraseña lo abre todo. Exponerlo en una red es decisión del operador |
| T8 | Se compromete el ejecutor | El nodo no puede fusionar, y sus credenciales se revocan una a una | Corre con la identidad del operador y con acceso a Docker. **Es el mayor riesgo actual** |
| T9 | Un secreto llega a un registro o a la evidencia | `TelemetryService.clean` y `SecretScan` | Solo detectan formatos conocidos |
| T10 | Se gasta la cuota | Clover detiene el trabajo con la cuota desconocida o al llegar al límite | Las lecturas son periódicas: puede pasarse un poco del límite |

## Docker en el ejecutor

Las pruebas corren en contenedores, así que el ejecutor necesita Docker. Quien alcanza el socket normal puede
montar `/` en un contenedor y es root en el equipo ([#73](https://github.com/alejbormeg/AgentFarm/issues/73)).

| Opción | A favor | En contra |
| --- | --- | --- |
| Socket normal (hoy) | Funciona sin configuración | Un ejecutor comprometido es root |
| Proxy que filtra el socket | No cambia el demonio | Filtrar rutas no basta: habría que validar cada `docker create` (`Privileged`, `Binds`, `CapAdd`, `PidMode`…), y un descuido en ese código da root |
| **Docker sin root** | El demonio corre como un usuario sin privilegios: escapar no da más que ese usuario. Lo mantiene Docker | Requiere `uidmap` y adaptar los permisos del workspace, y hay que comprobar los límites de CPU y memoria |

**Decisión: Docker sin root, con el [usuario propio](https://github.com/alejbormeg/AgentFarm/issues/72).** Lo
importante es que un fallo no dé más privilegios que ese usuario, y un proxy propio concentraría el riesgo en
código nuestro. **Aún no está implementado ni probado**: `TestSandbox` cambia el dueño de los archivos con el
UID del equipo, y sin root ese UID es otro dentro del contenedor.

## Lo que puede hacer el token de GitHub del ejecutor

Con una GitHub App, el ejecutor pide al panel un token de una hora por orden, y el panel lo
limita de dos maneras: **al repositorio de la orden** y **a los permisos que esa orden necesita**.
GitHub, si no se le pide un subconjunto, da al token todo lo que tiene la App.

| Orden | Permisos del token |
|---|---|
| `review_existing` | solo lectura: metadata, contents, pull requests, issues |
| `research` | lo mismo, y escritura en issues: crea la issue de la tarea si aún no existe |
| `implement` | además escritura en contents, pull requests e issues (anota el resultado en la issue de la tarea), y en workflows (el flujo de verificación de los repositorios sin CI) |
| `merge` | ninguno: integrar lo hace el panel con su propia credencial |

El panel rechaza el token si GitHub lo devuelve con más repositorios o más permisos de los pedidos,
o sin decir sus permisos. Conceder menos de lo pedido no es un error.

**Límite que esto no resuelve:** un token con `contents:write` puede, por la API de GitHub, fusionar
o empujar a la rama principal. Para que un ejecutor comprometido no pueda saltarse la decisión del
panel hay que protegerla en GitHub: regla de rama sobre `main` que exija PR y revisión, **sin
permitir saltarla** a la App. Esa configuración y su prueba con una App real en un repositorio
desechable siguen pendientes (#76, #83): hasta entonces no se da por verificada.

## Secretos en lo que mandan agentes y ejecutores

`core/Redaction` es la única política de redacción. La usan el ejecutor (errores, salidas de
pruebas, logs), la telemetría y el panel. Oculta tokens de GitHub (`ghp_`, `gho_`, `ghu_`, `ghs_`,
`ghr_`, `github_pat_`), credenciales de nodo (`afn_`), claves de OpenAI y Anthropic (`sk-`,
`sk-proj-`, `sk-ant-`), AWS, Google, Slack, cabeceras `Bearer`, claves privadas PEM de varias
líneas y asignaciones de campos secretos (`password=`, `api_key:`…).

El panel la aplica **al guardar** la propuesta, la evidencia y el error que informa un agente, y
otra vez **al devolverlos**, para cubrir filas anteriores. El listado de tareas solo lleva
resúmenes; la propuesta y la evidencia completas se piden tarea a tarea (`GET /api/work/{id}`), y
las ven todas las personas con cuenta en el panel, ya redactadas. Los nodos no las leen. La
redacción reconoce formatos conocidos: un secreto sin formato reconocible puede pasar, así que no
sustituye al escaneo del diff antes del push.

## Estado del panel en disco

La base de datos (con los secretos TOTP), su WAL y SHM, la contraseña inicial, la clave de la
GitHub App y las copias de seguridad solo los puede leer la cuenta que ejecuta el panel. Al
arrancar, el panel deja el directorio de la base en `700` y los archivos en `600` aunque el
umask sea `022`, y se niega a arrancar si no puede hacerlo (por ejemplo, si otra cuenta es su
propietaria) o si la base es un enlace simbólico. Si la base está directamente en tu directorio
personal o en el de trabajo, ese directorio no se toca: solo se protegen los archivos.

En sistemas de archivos sin permisos POSIX (una carpeta de Windows montada en WSL, por ejemplo) el
panel no puede aplicarlos. Guarda allí el estado solo si las ACL de la carpeta dejan leerla
únicamente a tu cuenta, o, mejor, usa el volumen de Docker o una ruta del sistema de archivos de
Linux.

## Si crees que algo se ha comprometido

1. Pausa los agentes en **Gobierno**.
2. Revoca el token del nodo, la credencial de GitHub del panel, y las sesiones de `gh` y de Codex.
3. Cambia la contraseña del panel y restablece el segundo factor con `./agentfarm 2fa-reset <usuario>`.
4. Revisa la auditoría del panel y lo publicado en GitHub.
