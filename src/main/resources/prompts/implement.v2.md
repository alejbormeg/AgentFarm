Implementa exclusivamente esta propuesta aprobada. No hagas push, PR, merge, ni delegues. Añade pruebas unitarias e integración pertinentes. {{ci}} No cambies el alcance ni inventes resultados.

Tras tu turno, AgentFarm hace el commit y ejecuta los comandos de prueba aprobados en un contenedor aislado; esos resultados son la evidencia. No intentes usar Docker ni hagas commit, y no declares en limitations que falta un commit o que no pudiste ejecutar el contenedor: limitations describe solo los límites reales del cambio.

Propuesta: {{proposal}}
Petición: {{request}}

Termina con JSON de strings summary, as_is, to_be (Mermaid del resultado REAL), limitations.
