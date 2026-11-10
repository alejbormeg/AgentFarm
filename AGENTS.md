# AgentFarm: reglas de desarrollo

Panel (Spring Boot) y ejecutor (`runtime/FarmDaemon.java`) para que un equipo de agentes mantenga
repositorios y un humano decida qué se integra. Lee [PRODUCT.md](PRODUCT.md) antes de decidir algo de producto,
[DESIGN.md](DESIGN.md) antes de tocar la interfaz y la especificación de la tarea antes de empezar.

## Reglas que no se negocian

- No amplíes alcance ni cambies contratos sin aprobación.
- Una aprobación se vincula al commit exacto. No publiques ni fusiones sin autorización explícita del propietario; aun autorizado, solo con todas las comprobaciones en verde sobre el head actual. Mientras Actions esté desactivado por falta de minutos, el propietario ha decidido que `validar-local.sh` sobre ese mismo head las sustituye.
- Nunca incorpores ni muestres secretos, sesiones ni logs privados. Eso incluye la salida de una orden: no imprimas tokens ni la contraseña del panel.
- Distingue capacidad implementada, simulada y planificada. Probado con dobles no es probado en real, y hay que decirlo.
- Una issue se cierra solo si se cumple su aceptación. Lo que no puedas acreditar se queda abierto, con un comentario de lo que falta.
- Un fallo se corrige en el producto y con su prueba, no reescribiendo el criterio que lo detectó.
- Prueba las invariantes de coordinación antes de añadir proveedores.

## Mapa

| Ruta | Qué es |
| --- | --- |
| `src/main/java/io/agentfarm/core` | Dominio: flujo, evidencias, presupuesto, sandbox, GitHub, migraciones |
| `src/main/java/io/agentfarm/web` | API y seguridad |
| `src/main/resources/static/app` | Interfaz: módulos ES sin compilación |
| `tools/pixelart/farm.py` | Genera los dibujos de la granja (`static/art`) |
| `src/main/resources/{prompts,skills,recipes.json}` | Instrucciones de los agentes, versionadas |
| `runtime/` | Ejecutor. `FarmDaemon.java` se compila aparte y usa las clases de `core` |
| `agentfarm`, `compose.yaml`, `Dockerfile` | Instalación y operación |
| `tests/` | e2e, navegador y operación. Las pruebas unitarias están en `src/test` |
| `docs/ROADMAP.md`, `CHANGELOG.md` | Qué falta y qué se hizo |
| `docs/SECURITY.md` | Modelo de amenazas: actualízalo si cambia una defensa |
| `docs/QUALITY.md` | Qué comprueba `verify` y cómo regenerar el contrato de la API |

`docs/PLAN.md` y `runtime/README.md` tienen partes históricas: si contradicen al código, manda el código.
Lo pendiente vive en las issues de GitHub, no en listas de la documentación.

## Comprobar un cambio

| Si tocas | Ejecuta |
| --- | --- |
| Cualquier cosa de Java | `./mvnw -B -ntp verify` |
| `runtime/` o una clase de `core` que use el ejecutor | además `bash runtime/verify.sh`, `bash runtime/package.sh` y `bash tests/e2e/run.sh` |
| Interfaz | además `E2E_CHROME=google-chrome bash tests/e2e/run.sh`, que lanza el smoke de `tests/ui/smoke.mjs`, y mírala en un navegador |
| `agentfarm`, credenciales | además `bash tests/credentials.sh` y `bash tests/ops.sh` |
| `comprobar.sh` o el hook de `.claude/hooks/` | además `bash tests/pre_push.sh` |
| `compose.yaml`, `Dockerfile`, copias | además `bash tests/compose_backup.sh`, en un proyecto de Compose desechable |
| `.claude/skills/resolver-issue/merge-gate.sh` | además `bash tests/merge_gate.sh` |
| `tests/pilot/run.py` | además `python3 -I tests/pilot/test_run.py` |
| Aislamiento de los agentes | además `bash tests/codex_sandbox.sh`, sin inferencia, contra el Codex instalado |
| `runtime/install-dedicated-user.sh` | además `bash tests/dedicated_user.sh`, en un contenedor |
| `src/main/resources/executor/install.sh`, `runtime/package.sh` o `runtime/install-executor.sh` | además `bash runtime/package.sh` y `bash tests/executor_install.sh` |

`tests/e2e/run.sh` necesita Docker y la imagen `bash:5.2`; con `E2E_SANDBOX=host` corre sin contenedor. `tests/eval/run.sh` y los
pilotos reales de `docs/PILOTS.md` gastan cuota: mírala antes y no los lances sin que te lo pidan, salvo que la tarea lo exija.

La CI ejecuta todo esto en `.github/workflows/java.yml`: con Java 21 en cada PR, y también con 25 al fusionar. Un cambio que solo toca `docs/`, `site/` o `*.md` de la raíz no la usa.

**Antes de publicar,** ejecuta `bash .claude/skills/resolver-issue/comprobar.sh`. Elige qué correr según lo que cambia la rama y registra que ese commit pasó. Casi todos los rojos de la CI venían de publicar algo que nunca se había ejecutado en local.

## Entregar

- Rama desde `main`, PR en español y squash con título `[#issue] Qué cambia`.
- Una PR por issue, con la prueba que falla sin el cambio.
- Cada cambio visible añade una línea a `CHANGELOG.md`. Si completa una fila de `docs/ROADMAP.md`, actualízala.
- `core.filemode` está a `false` en este clon: un script nuevo no queda ejecutable al añadirlo. Usa `git add --chmod=+x`, o invócalo con `bash`.

## Estilo

Español en la interfaz, la documentación, las issues y los commits; inglés en el código y sus comentarios. Copia el estilo del
archivo que tocas. Sin dependencias nuevas salvo que no haya alternativa razonable.
