# AgentFarm

Plano de control local para equipos de agentes, con **Java 21, Spring Boot 4 y Mateu**.
Boxer ejecutará trabajo; Napoleon dirigirá un nodo; Napoleon y Snowball contrastarán
propuestas cuando se implemente la coordinación entre nodos. El humano gobierna la granja.

## Incremento en desarrollo — fase 1

[Guía de conexión, diagnóstico y recuperación](docs/INSTALLATION.md).
Incluye perfiles agentfarm.yaml ligados a la propuesta, doctor, backup/restore,
paquete Linux del ejecutor y credenciales de nodo revocables.
La separación de identidad del trabajador, las versiones públicas y el piloto
de instalación siguen pendientes; no se declara la fase 1 completada.

## Aceptación por criterio — fase 2 en desarrollo
[Expedientes y decisiones](docs/EVIDENCE.md): criterios ligados a versión/SHA,
informes declarados, validaciones humanas y excepciones visibles. Integrar exige
cubrir todos los criterios y volver a comprobar el commit. El verificador
automático separado sigue pendiente.

## Proveedores y recursos — fase 3 en desarrollo
[Contrato y admisión](docs/PROVIDERS.md): cuota Codex normalizada, comprobación
explícita de proveedor/cuenta y capacidades, sin fallback ni pago adicional.
Claude Code y optimización de perfiles siguen pendientes.

## Instalar y abrir

Necesitas Docker con Compose v2. En Windows, usa Docker Desktop con integración
para la distribución Ubuntu 24.04 LTS de WSL.

Desde Ubuntu:

```bash
git clone https://github.com/alejbormeg/AgentFarm.git
cd AgentFarm
./agentfarm up
./agentfarm password
```

Abre **http://localhost:8080**, usuario **admin**, con la contraseña del último
comando. El primer arranque compila la aplicación y descarga las imágenes.
Los siguientes reutilizan las capas. Solo se publica el puerto de loopback.

```bash
./agentfarm status
./agentfarm logs
./agentfarm stop
```

El volumen `farm-data` conserva base de datos y contraseña. Detener el contenedor
no borra los datos. No uses `docker compose down -v` salvo que quieras eliminarlos.

**Alternativa sin Docker:** descarga el JAR de la PR o compílalo con
`./mvnw verify`, instala Java 21 y ejecuta:

```bash
java -jar target/agentfarm.jar
```

El JAR escucha en `127.0.0.1:8080` y guarda sus datos en `./data`. La primera
contraseña está en `data/admin-password.txt`; nunca se imprime en el log.
No necesitas Node, Python ni un servidor SQL. Maven Wrapper sirve para desarrollo.
Más detalles: [instalación](docs/INSTALL.md).

## Panel Mateu

- **Panorama:** inventario y admisión, con actualización periódica.
- **Nodos:** Napoleon/Snowball registrados, habilitación y heartbeat.
- **Boxer:** trabajadores, nodo, disponibilidad y heartbeat.
- **Tareas:** cola, asignación, estado, progreso declarado y cancelación.
- **Clover:** presupuesto API o cuota de suscripción, ventanas y conciliación.
- **Gobierno:** pausa/reanudación de admisión y auditoría de acciones.

Un registro nuevo tiene estado desconocido. El heartbeat caduca tras 45 segundos.
El progreso se identifica como declarado y puede estar vacío. La asignación reserva
presupuesto y queda en `assigned`; no declara que un modelo haya empezado a trabajar.

**Todavía no hay ejecución real de proveedores, lectura automática de cuotas,
cancelación de procesos ni consenso distribuido.** El panel gobierna el estado
persistente y la admisión. La simulación no aporta evidencias de implementación.
No hay fusión automática ni fallback de pago.

## Presupuesto

La política confirmada es **como máximo 50% de la cuota semanal de Codex**, compartida
por los trabajadores de una cuenta. La mensualidad de 20 USD no se convierte en
saldo de API. Sin observación vigente y cota justificable se pausa la admisión.

[Contrato de presupuesto](docs/BUDGET.md) · [Roles Animal Farm](docs/ANIMAL_FARM.md)

## Consola Java

```bash
java -jar target/agentfarm.jar coordinator --db state.db enqueue examples/task.json
java -jar target/agentfarm.jar coordinator --db state.db simulate
java -jar target/agentfarm.jar coordinator --db state.db status
java -jar target/agentfarm.jar coordinator --db state.db report
java -jar target/agentfarm.jar budget --db budget.db configure examples/budget-subscription.json
java -jar target/agentfarm.jar budget --db budget.db status codex-subscription
java -jar target/agentfarm.jar scope --repo /ruta/al/proyecto --spec examples/task.json --base SHA_BASE_COMPLETO --candidate SHA_CANDIDATO_COMPLETO
```

`scope` valida árboles Git confirmados sin modificar el checkout. Salidas: 0 si
pasa, 1 si se bloquea y 2 si hay error. No equivale a aprobación humana ni valida comportamiento.

## Migrar datos anteriores

Detén el proceso anterior y realiza copias consistentes de sus SQLite.

```bash
java -jar target/agentfarm.jar import --db data/agentfarm.db /ruta/copia/state.db
java -jar target/agentfarm.jar import --db data/agentfarm.db /ruta/copia/budget.db
```

Se preservan especificaciones, hashes, estados, consumos y reservas. Los conflictos
se rechazan y revierten. [Guía de migración](docs/MIGRATION.md).

## Desarrollo y pruebas en WSL

```bash
./mvnw verify
./mvnw spring-boot:run
docker compose up --build --wait -d
```

Pruebas Java de invariantes, importación SQLite, concurrencia, autenticación y gobierno.
[Plan técnico](docs/PLAN.md).

## Operación en vivo y tareas GitHub

[Guía del panel y ejecutor WSL](docs/OPERATIONS.md): idea → issue → propuesta → aprobación → rama → tests → revisión → PR → integración explícita por SHA.

[Iniciativas, hitos y dependencias](docs/INITIATIVES.md): agrupa tareas entre
repositorios, limita cambios pendientes de revisión y consulta por qué una tarea
está esperando. La agrupación conserva las aprobaciones individuales.
