# Instalación portable — incremento de fase 1
Este incremento es candidato de desarrollo. La fase 1 sigue abierta: faltan
aislamiento de credenciales del trabajador, publicación de versiones y piloto
de instalación. No acredita los objetivos de las fases 2–4.

## Panel
Requiere Docker Compose v2. Desde el checkout: `./agentfarm up`.
Abre http://localhost:8080, usuario admin; obtén la contraseña mediante
`./agentfarm password`. El panel escucha en localhost y persiste SQLite.
Alternativa: Java 21+ y `java -jar target/agentfarm.jar` después de
`./mvnw package`. Las versiones publicadas deberán proporcionar ese JAR.
Nunca publiques el panel en Internet sin TLS y una política de acceso.

En Gobierno → Conectar ejecutor se seleccionan un nodo y una cuenta registrados.
Si el panel aún no los tiene, **Preparar este equipo** crea el nodo `napoleon-local`, sus dos
trabajadores y la cuenta `codex-subscription` con parada al 2 % semanal restante.
La conexión comprueba después heartbeats y autenticación declarada por el ejecutor;
no inicia inferencia ni equivale a verificar aislamiento.

## Ejecutor Linux / WSL Ubuntu 24.04
Necesita Java 21+, Git, gh y Codex autenticados en la cuenta que ejecutará el servicio.
Usa el inicio de sesión oficial de cada herramienta; nunca pegues tokens en Git.
Compilar el paquete: `bash runtime/package.sh`. Extrae
`target/agentfarm-executor-linux.tar.gz` en una ubicación permanente. El destino
no necesita Maven ni una copia del código fuente.

Crea un directorio privado de estado y guarda la credencial de nodo entregada
una sola vez por el asistente en `STATE/node-token`, permiso 600.
Crea fuera de Git un archivo privado `executor.env`:
```text
AGENTFARM_NODE=mi-napoleon
AGENTFARM_ACCOUNT=mi-cuenta
AGENTFARM_URL=http://localhost:8080
AGENTFARM_STATE=/ruta/privada/agentfarm
AGENTFARM_IMPLEMENTER=mi-boxer
AGENTFARM_REVIEWER=mi-revisor
```
Los IDs deben coincidir con los registros del panel. `./executor` permite probar
en primer plano con esas variables. `./install-executor.sh /ruta/executor.env`
instala el servicio systemd del usuario. Consulta/parada:
`systemctl --user status|stop agentfarm-executor`.
No actives el observador o piloto históricos junto a él. WSL debe permanecer vivo;
el servicio de usuario no configura arranque de Windows ni linger.

El ejecutor usa un token revocable de nodo y conserva CSRF. Su API no permite
aprobar, crear credenciales, cambiar políticas ni reclamar órdenes de integración.
Configura la credencial en **Gobierno → Conectar GitHub**, o con `./agentfarm github-token` o `github-app` ([qué credencial usar](TEAM.md)).
La integración se procesa en el panel con `AGENTFARM_GITHUB_TOKEN_FILE`,
un archivo privado de credenciales GitHub leído por Spring, accesible solo al
operador del panel. Nunca lo montes en un directorio del trabajador.
Sin ese archivo, las órdenes de integración no se procesarán.

**Qué leen los agentes:** el modo de solo lectura de Codex alcanza todo el disco. El
ejecutor le aplica un perfil de permisos que oculta `~/.config`, `~/.ssh`, `~/.aws`,
`~/.docker`, `~/.codex/auth.json`, el estado del ejecutor, las copias, los demás
workspaces, `/mnt`, los sockets de contenedores y el directorio de sesión de tu usuario, y
deja la red apagada. Antes de aceptar trabajo, y cada diez minutos, el ejecutor comprueba con el
sandbox real que esas rutas no se alcanzan: si falla o no puede saberlo, no acepta tareas y el
panel lo avisa. `bash tests/codex_sandbox.sh` hace una comprobación más completa, útil al
actualizar Codex. Es una lista de exclusión: el resto de tus archivos, como otros
proyectos en tu home, sigue siendo legible. Requiere Codex CLI 0.166 o posterior.
Ejecutar el ejecutor con un usuario propio sigue siendo más seguro: consulta el
[modelo de amenazas](SECURITY.md).

Claude Code no corre en ese sandbox. Recibe las mismas rutas como reglas de bloqueo de sus
herramientas, ignora la configuración del repositorio y no tiene `Bash`. Esas reglas siguen siendo
más débiles que un aislamiento del sistema, y el panel lo avisa mientras un rol lo use. Se han probado
con una cuenta real en Claude Code 2.1.318, también por enlaces simbólicos: repite
`bash tests/claude_rules.sh` al actualizarlo, porque gasta muy poca cuota y es lo único que lo comprueba.

**Usuario propio:** para que un agente no alcance tu home aunque falle el perfil, instala el ejecutor con
un usuario dedicado, con su propia sesión de `gh` y de Codex:

```bash
bash runtime/package.sh
sudo bash runtime/install-dedicated-user.sh target/agentfarm-executor-linux.tar.gz
```

El instalador crea el usuario, no copia ninguna de tus credenciales, comprueba que no pueda leer tu home y
te indica los pasos que faltan. Ese usuario no tiene Docker, así que las pruebas en contenedor necesitan
[Docker sin root](SECURITY.md#docker-en-el-ejecutor). **Esta vía aún no se ha recorrido entera en un equipo real.**

**Límite importante:** gh y Codex siguen ejecutándose con la identidad Linux
del servicio. Las pruebas ya se aíslan en contenedor (ver más abajo), pero el
sandbox de escritura de Codex sobre el checkout no es una frontera de seguridad. Se necesita una identidad/entorno de trabajador separado y
credenciales GitHub limitadas antes de afirmar separación completa de privilegios.

## Perfil de repositorio y diagnóstico
Copia `examples/agentfarm.yaml` a la raíz del repositorio y adapta repositorio,
comandos y rutas. Revísalo mediante:
```bash
java -jar agentfarm.jar profile --file /repo/agentfarm.yaml
java -jar agentfarm.jar doctor --repo /repo --panel http://localhost:8080
java -jar agentfarm.jar doctor --repo /repo --run-tests true
```
El diagnóstico no ejecuta comandos del perfil salvo autorización explícita
`--run-tests true`. No muestra salidas de herramientas ni valores de credenciales.
La cuota figura desconocida: autenticarse no acredita saldo disponible.
La lectura real pertenece al ejecutor y caduca según la política Clover.
La configuración se liga por hash a la propuesta; cambiarla exige otra aprobación.
Los archivos cambiados deben estar dentro de allowed_paths.
`secret_refs` se puede describir, pero su ejecución se bloquea expresamente.

## Pruebas en contenedor
El ejecutor ejecuta `setup` y los comandos de prueba en un contenedor Docker
desechable por candidato, nunca en el checkout del host:

- El contenedor recibe una copia del árbol del commit (`git archive`): sin `.git`,
  sin hooks, sin `~/.config/gh`, sin credenciales de Codex ni variables de token.
- `--cap-drop ALL` (salvo los permisos mínimos de propietario para gestores de
  paquetes), `no-new-privileges`, límites de CPU, memoria, procesos y tiempo.
- `setup` corre como root del contenedor con red; después se desconecta la red
  (`network: setup`, por defecto) y las pruebas corren con tu uid, sin red.
  `network: none` no da red en ningún momento; `always` la mantiene en las pruebas.
- El contenedor y la copia se eliminan siempre, también ante fallo o cancelación.
- La política forma parte de la propuesta que apruebas y queda en la evidencia.

```yaml
environment: container
container:
  image: maven:3.9.11-eclipse-temurin-21   # por defecto buildpack-deps:noble
  network: setup                          # setup | none | always
  cpus: 2                                 # 1-16
  memory_mb: 4096                         # 256-32768
  timeout_minutes: 20                     # 1-60
```

Sin perfil, las tareas usan `buildpack-deps:noble` (configurable con
`-Dagentfarm.sandbox-image=`). Un perfil con `environment: local` solo se ejecuta
si el operador arranca el ejecutor con `-Dagentfarm.sandbox=host`; si no, la
tarea se bloquea con el motivo. Codex sigue usando su propio sandbox
`workspace-write`/`read-only` en el checkout del host.

Límite: Docker con el uid del ejecutor equivale a privilegios de root en el host;
el contenedor protege frente al código del candidato, no frente al operador.

## Copia y recuperación

### Con Docker Compose
```bash
./agentfarm backup                       # ~/agentfarm-backups/agentfarm-<fecha UTC>.db
./agentfarm backup /backups/farm.db      # también admite un directorio existente
./agentfarm restore /backups/farm.db     # pide confirmación; sin terminal, --yes
```
Con el servicio en marcha, `backup` crea la copia dentro del contenedor, la extrae
por un flujo (`/tmp` es un tmpfs que `docker compose cp` no puede leer) y comprueba
SHA-256 e `integrity_check` antes de darle su nombre final, con permisos 0600 y sin
sobrescribir nada. Si el host no tiene `sqlite3` ni `python3`, vale la comprobación
del contenedor junto con el SHA-256, y la salida lo indica. El tmpfs admite 128 MB:
con una base mayor la copia falla sin efectos, sin dejar nada a medias.

`restore` valida la copia junto al panel en marcha: una copia dañada se rechaza sin
interrumpirlo. Después detiene el panel, deja la base actual en el volumen como
`/data/agentfarm.db.pre-restore-<fecha>` y lo arranca con la copia. Si no arranca
(p. ej. la copia es de una versión más reciente), recupera la base anterior y
termina con error. Funciona también con el servicio detenido, y lo deja en marcha.
En una instalación nueva, ejecuta antes `./agentfarm up`. La contraseña inicial
está en el volumen y `restore` no la cambia. Detén el ejecutor antes de restaurar.
Borra la base anterior cuando ya no la necesites:
`docker compose exec agentfarm rm /data/agentfarm.db.pre-restore-<fecha>`.

### Con el JAR
Sin el servicio en marcha, `./agentfarm backup [destino]` usa el JAR sobre
`AGENTFARM_DB` (por defecto `./data/agentfarm.db`) y avisa de ello. Si usas Compose
y lo tienes detenido, arráncalo primero: esa base no es la del volumen. Con opciones
explícitas, `./agentfarm backup|restore` siempre llama al JAR:
```bash
java -jar agentfarm.jar backup --db /data/agentfarm.db --output /backups/farm-2026-10-31.db
java -jar agentfarm.jar restore --snapshot /backups/farm-2026-10-31.db --db /data/restored.db
```
La restauración con el JAR exige un destino nuevo. Detén el panel antes de cambiar
AGENTFARM_DB al archivo restaurado. Conserva el original.

### En ambos casos
Backup usa VACUUM INTO e integrity_check; incluye transacciones confirmadas del
WAL sin copiarlo en bruto. La base contiene historial y hashes de credenciales: protege también el backup.
No incluye credenciales externas, checkouts, recibos ni artefactos locales.
Copia esos directorios por separado, con el ejecutor detenido, y conserva las
mismas ubicaciones durante la recuperación. La retención automática no está
implementada. No reinicies trabajo bloqueado sin reconciliar recibos, reservas,
PR remota y SHA; el sistema no repite una inferencia tras expirar una reclamación.

## Validación pendiente para cerrar fase 1
Publicar JAR/imagen/paquete de ejecutor; probar desde una máquina limpia;
separar de verdad la identidad y permisos GitHub del trabajador; completar
reconciliación/retención; revisar licencias de distribución; medir instalación
y primera tarea con cinco usuarios (al menos cuatro en menos de 15 minutos).

## Varias tareas en paralelo
El panel admite varios comandos reclamados a la vez: como máximo
`agentfarm.max-parallel` (2 por defecto, `AGENTFARM_MAX_PARALLEL`) y
`agentfarm.max-per-repository` por repositorio (1). Cada ejecutor reclama un
único comando. Para trabajar en paralelo instala varias ranuras del mismo nodo:

```bash
./install-executor.sh /ruta/executor.env 1
./install-executor.sh /ruta/executor.env 2
systemctl --user status agentfarm-executor-s1 agentfarm-executor-s2
```

Cada ranura reclama como `<nodo>-s<N>` con la credencial del nodo, usa su propio
directorio de trabajo, recibos y diario de reservas, y publica su señal por
separado; el panel muestra cuántos ejecutores están conectados. Todas comparten
la cuenta de cuota, así que Clover sigue limitando la admisión conjunta.

## CI del proyecto
Si el commit base ya tiene workflows en `.github/workflows/`, AgentFarm no añade
el suyo: la evidencia exige que **todas** las comprobaciones de GitHub del SHA
terminen bien (`ci_checks: ["*"]`). Solo los repositorios sin CI reciben
`.github/workflows/agentfarm.yml` con los comandos aprobados. Un candidato que
modifica los workflows propios del proyecto queda como PR en borrador
(`workflow_changes` en la evidencia): ese cambio debe revisarlo una persona,
porque podría debilitar la misma comprobación que lo valida.

## Prompts versionados
Las instrucciones de investigación, implementación y revisión viven en
`src/main/resources/prompts/*.vN.md`. La propuesta y la evidencia guardan el id
y el hash SHA-256 de la plantilla usada. Cambiar un prompt es crear `vN+1` y
actualizar `Prompts.CURRENT`, de modo que los resultados previos siguen trazables.

## Migraciones de esquema
`schema_migrations` registra las versiones aplicadas. Cada versión nueva se
aplica una vez y en una transacción; un panel antiguo se niega a abrir una base
de datos escrita por una versión más reciente. Haz `backup` antes de actualizar.
