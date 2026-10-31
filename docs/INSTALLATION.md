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
Registra antes el nodo y sus dos trabajadores en Nodos/Boxer, y configura en
Clover el presupuesto de suscripción con parada al 2 % semanal restante.
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
La integración se procesa en el panel con `AGENTFARM_GITHUB_TOKEN_FILE`,
un archivo privado de credenciales GitHub leído por Spring, accesible solo al
operador del panel. Nunca lo montes en un directorio del trabajador.
Sin ese archivo, las órdenes de integración no se procesarán.

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
```bash
java -jar agentfarm.jar backup --db /data/agentfarm.db --output /backups/farm-2026-10-31.db
java -jar agentfarm.jar restore --snapshot /backups/farm-2026-10-31.db --db /data/restored.db
```
Backup usa VACUUM INTO e integrity_check; incluye transacciones confirmadas del
WAL sin copiarlo en bruto. La restauración exige un destino nuevo. Detén el panel
antes de cambiar AGENTFARM_DB al archivo restaurado. Conserva el original.
La base contiene historial y hashes de credenciales: protege también el backup.
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
