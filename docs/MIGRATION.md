# Migración a Java, Spring Boot y Mateu

## Stack

Java 21, Spring Boot 4.1.2, Mateu 3.0-beta.25 (MVC y Vaadin) y SQLite JDBC 3.51.1.0.
Desarrollo y pruebas en Ubuntu 24.04 LTS WSL. Docker Compose como instalación
principal y JAR como alternativa. Mateu está en beta: se fija su versión.

| Antes | Ahora |
| --- | --- |
| coordinator.py | CoordinatorService y consola coordinator. |
| scope_check.py | ScopeService y consola scope. |
| budget.py | BudgetService y consola budget. |
| unittest | JUnit: presupuesto, coordinador, alcance, importación y HTTP. |
| Sin interfaz | Mateu, API autenticada y auditoría. |

Los ficheros Python se retiran de la distribución. Se conservan los ejemplos
JSON y las especificaciones. No se ejecuta Python a través de un puente.

## Transferir los datos

1. Detener los procesos anteriores y hacer una copia consistente de state.db
   y budget.db. Si siguen abiertos, usar sqlite3 .backup; no copiar solo el
   fichero principal mientras existan escrituras en WAL.
2. Compilar con ./mvnw verify o descargar el JAR de CI.
3. Importar a un destino nuevo:
   java -jar agentfarm.jar import --db data/agentfarm.db /copia/state.db
4. Repetir para /copia/budget.db.
5. Comprobar tareas, informes y reservas antes de admitir trabajo nuevo.
6. Conservar las copias para volver a la versión histórica.

El origen se abre en modo de solo lectura; cada importación es transaccional.
Se comprueban las columnas esperadas. Se preservan especificaciones, hashes,
estados, consumos y reservas pendientes. Una fila idéntica es idempotente; un
conflicto detiene y revierte toda la importación. No se liberan reservas por migrar.

Los fixtures legacy-coordinator.sql y legacy-budget.sql se generaron con la
versión Python anterior. Las pruebas comprueban una especificación Unicode y
una reserva pendiente, incluyendo idempotencia y ausencia de reembolso.

## Gobierno implementado

Registrar nodos/Boxer, habilitar/deshabilitar, caducidad de heartbeat, encolar,
asignar con reserva, progreso declarado y solicitud de cancelación.
La asignación necesita nodo y Boxer habilitados/recientes y Boxer idle.

Reserva y asignación comparten transacción. Se mantiene un trabajo
asignado/en curso, dos cambios pendientes de revisión y cinco ejecuciones/día.
La pausa bloquea simulación, asignación y reservas nuevas.

La cancelación de una tarea lista conserva su registro como cancelled. Para
assigned/running se guarda la petición, sin afirmar que el proceso terminó
ni liquidar automáticamente su coste. El progreso 100% no acredita criterios
ni autoriza integración.

No hay ejecución real de modelos, consenso distribuido, alta disponibilidad,
identidad máquina a máquina ni finalización validada con aprobación de commit.
El panel no muestra actividad inventada por defecto.

Fuentes:
[Mateu MVC](https://mateu.io/java-create-your-project/springboot-mvc/),
[limitaciones](https://mateu.io/reference/known-issues/),
[Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html).
