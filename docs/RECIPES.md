# Recetas mantenidas — segundo incremento de fase 4

En **Nueva tarea**, selecciona una receta opcional y describe
el problema concreto. También puedes seguir enviando peticiones libres.

| Receta | Investigación guiada | Evidencia prevista |
| --- | --- | --- |
| Reproducir y corregir un bug | Reproducción en la base, causa y corrección acotada | Antes/después, test de regresión o excepción justificada |
| Actualizar una dependencia | Versión objetivo, notas oficiales, compatibilidad y reversión | Versiones, instalación reproducible y pruebas de consumidores |
| Mejorar las pruebas | Riesgo concreto, casos positivos, negativos y de frontera | Qué regresión detecta cada caso y resultados por SHA |
| Actualizar la memoria del proyecto | Decisiones, restricciones y rechazos con justificación | Diff y validación del archivo versionado mediante PR |
| Preparar una migración | Interfaces, datos, compatibilidad, transición y riesgos | As-is/To-be del incremento y recuperación ensayada con datos de prueba |

Son guías de investigación y evidencia; no son scripts ejecutables ni pruebas
ya superadas. No fijan versiones de dependencias, comandos de tests ni cifras
de cobertura. Napoleon debe concretar los comandos y criterios adecuados al
repositorio. Si una comprobación no aplica o la reproducción no es posible,
la propuesta debe explicarlo y conservar una excepción visible.

## Versiones y trazabilidad

El catálogo se mantiene en `src/main/resources/recipes.json` y cambia mediante PR.
Cada entrada tiene identificador y versión. Conserva las versiones publicadas:
una modificación de instrucciones debe crear una versión nueva, sin editar la anterior.

Al crear una tarea, Spring Boot resuelve la selección contra su catálogo y guarda
una copia completa en SQLite (`work_recipes`) con hash SHA-256 canónico. Ignora
instrucciones enviadas por el cliente: solo acepta la selección id/version.
La petición original permanece intacta en `work_items.request`.
El ejecutor recibe la copia guardada, la incluye en issue y contextos de trabajo
y la UI permite consultar instrucciones, evidencia prevista, versión y hash.

Leer tareas y reintentar una petición ya registrada usa esa copia, aunque la versión
ya no aparezca en el catálogo activo. Reutilizar request_id con otra receta, quitar
la receta o añadirla a una petición libre registrada devuelve conflicto. La creación
de tarea, receta y orden de investigación es una sola transacción.
Las tareas históricas mantienen su forma anterior y no reciben recetas implícitas.

## API

- GET /api/recipes: catálogo de operador autenticado.
- POST /api/work: admite el campo opcional
  `"recipe": {"id": "bugfix", "version": 1}`.
- GET /api/work y reclamación de orden: incluyen `recipe` solo cuando la tarea
  tiene una copia guardada.

Se conserva CSRF para crear trabajo y el ejecutor no puede consultar el catálogo
de gobierno. La copia de su tarea llega al reclamar la orden con su credencial acotada.
Actualiza panel y ejecutor a la misma versión antes de admitir tareas con recetas.

## Gobierno y límites

Seleccionar una receta encola investigación y creación de issue, no implementación.
El alcance propuesto sigue requiriendo aprobación; ningún criterio se verifica por
usar una receta. Se mantienen cuota, dependencias, límites de revisión y fusión humana
del SHA exacto. Las instrucciones de la receta no amplían un alcance aprobado ni
autorizan despliegues o migraciones de datos reales.

Validación automatizada: catálogo/hash, copia inmutable, original intacto, reinicio,
reintentos, conflicto de identidad, versión desconocida sin recursos parciales,
compatibilidad libre, HTTP/CSRF y contexto del ejecutor sin inferencias.
No acredita resultados de las cuatro recetas en repositorios piloto. Permanecen
pendientes la validación visual, memoria versionada, señales de mejora, roles y pilotos.

## Fiabilidad del panel observada durante la entrega

El contenedor existente agotó su límite de 128 procesos/hilos: Tomcat intentó
crear el hilo HTTP 107 y los healthchecks dejaron de poder hacer fork.
Se limita el pool HTTP a 32 hilos, con cuatro mínimos y cola de aceptación
de 64. El límite de procesos de Compose permanece en 128. CI añade una ráfaga
de peticiones de lectura para comprobar disponibilidad bajo concurrencia.
Este control no acredita capacidad para un número arbitrario de usuarios.
