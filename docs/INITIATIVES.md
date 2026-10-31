# Iniciativas y límites de revisión — primer incremento de fase 4

Una iniciativa agrupa tareas existentes de uno o varios repositorios, las asigna
a hitos y expresa qué cambios deben integrarse antes de comenzar otros.
Se administra desde **Tareas → Iniciativas**. No crea una aprobación colectiva:
cada tarea mantiene su issue, propuesta, alcance aprobado, evidencias y fusión por SHA.

## Uso

1. Crea una iniciativa con objetivo, hitos (uno por línea) y límite de revisión entre 1 y 10.
2. Vincula las tareas existentes a un hito. Para una dependencia, vincula primero la predecesora.
3. Añade dependencias antes de que una orden esté ejecutándose o exista un candidato en revisión.
4. Aprueba cada propuesta normalmente. El planificador reclamará solo órdenes elegibles.
5. Integra cada candidato explícitamente. Un hito solo se completa cuando todas sus tareas están integradas.

Una dependencia exige estado **integrated** de la predecesora. Terminar Codex,
pasar tests, abrir una PR o aprobarla no libera la dependencia. Una predecesora
cancelada mantiene el bloqueo hasta que el operador revise el plan.
La investigación también espera para no proponer sobre una base pendiente de integrar.

El límite cuenta tareas en desarrollo, revisión o integración: la plaza se reserva
al reclamar implementación o revisión de un candidato existente. No impide investigar
otras tareas independientes ni integrar cambios. El límite es por iniciativa;
no sustituye los controles globales, de proveedor, cuota o aprobación.
Las tareas sin iniciativa conservan su comportamiento anterior.

La pausa de iniciativa afecta a próximas órdenes, incluida integración. No interrumpe
una orden ya reclamada: para eso siguen disponibles los controles de tarea y pausa global.
La UI muestra las causas concretas de espera y el número de plazas ocupadas.
Los hitos vacíos y las tareas canceladas no se declaran completos.

## Persistencia y API

SQLite conserva iniciativas, hitos, asociaciones y dependencias. La creación usa
request_id y hash del contenido: repetir la misma petición no crea otro recurso;
reutilizar el identificador con otro contenido falla. Vincular una relación idéntica
es idempotente. Los cambios exigen la versión vigente de la iniciativa y quedan auditados.
Repetir una pausa con una versión antigua devuelve conflicto; recarga y consulta el estado.

- GET /api/initiatives y GET /api/initiatives/{id}
- POST /api/initiatives:
  request_id, title, goal, review_limit, milestones: [{id, title}]
- POST /api/initiatives/{id}/tasks:
  version, item, milestone, depends_on: [identificadores de tareas]
- POST /api/initiatives/{id}/pause:
  version, paused

Todas las rutas requieren operador autenticado; las mutaciones conservan CSRF.
Las credenciales de ejecutor no acceden a este gobierno.
Una tarea pertenece como máximo a una iniciativa y cada predecesora debe pertenecer
a esa misma iniciativa. Se rechazan autorreferencias, ciclos y cambios durante ejecución
o después de entrar en revisión. Puede incorporarse una tarea histórica en revisión
o integrada sin dependencias, conservando su estado. No se reasignan tareas entre iniciativas.

La comprobación de dependencias y plazas comparte la transacción SQLite que reclama
la orden. Se salta trabajo bloqueado para que no impida ejecutar órdenes independientes.
Se mantienen la exclusión de ejecución actual y la recuperación de órdenes existentes.

## Alcance y validación

Implementado: agrupación entre repositorios, hitos, dependencias, pausa de nuevas órdenes,
contrapresión de revisión, persistencia, auditoría y panel con sondeo existente de dos segundos.
Los contadores de iniciativa se calculan con los estados reales, sin porcentaje inferido.

Las pruebas automatizadas usan SQLite aislada y HTTP real contra Spring Boot.
Cubren integración simulada confirmada, límites, ciclos, recuperación, repetición,
CSRF y separación de credenciales. No fusionan repositorios externos.
La validación visual de escritorio y móvil sigue pendiente.

Pendiente de #10: recetas mantenidas, sugerencias basadas en señales, memoria de proyecto
mediante PR, usuarios con roles diferenciados y pilotos medidos. También continúan los
pendientes de #7, #8 y #9, incluido verificador separado y segundo proveedor ejecutable.
Este incremento no acredita los criterios de salida de ninguna fase.
