# Fase 2 — expediente y decisiones de aceptación
Primer incremento de #8, iniciado por instrucción expresa del promotor después
de fusionar PR #11. #7 conserva sus pendientes de fase 1; iniciar este incremento
no acredita que se haya superado el piloto de instalación.

## Implementado
La propuesta incluye criterios identificados:
```json
{"criteria":[
  {"id":"AC-1","description":"La operación conserva los datos esperados","method":"test","checks":["unit","integration"]},
  {"id":"AC-2","description":"El usuario identifica el bloqueo en la interfaz","method":"manual"}
]}
```
IDs únicos AC-1…AC-9999; máximo 50 criterios. Se registra una instantánea
inmutable de la propuesta al pulsar Implementar. Los criterios heredados sin
array se representan como una validación manual AC-1 con el texto original.

El expediente exportable conserva repositorio, issue/PR/rama, base, candidato,
versión de propuesta, hash de alcance, informes del ejecutor y decisiones humanas.
Los informes se etiquetan como declarados; un passed de Boxer no verifica
automáticamente ningún criterio. Los campos de credenciales conocidos se ocultan
en la exportación; no debe enviarse ningún secreto como evidencia.

En Tareas/Panorama se ve qué criterio está pendiente, validado manualmente,
rechazado o exceptuado. Una decisión necesita una explicación de al menos veinte
caracteres y el entorno utilizado. Registrar validación manual significa que
el operador declara haber realizado esa comprobación; no certifica una prueba
automática ni una observación independiente del servidor.

Para un criterio definido como test, este incremento **no permite** sustituirlo
silenciosamente por una validación manual. Mientras no exista el verificador
separado, solo puede permanecer pendiente o tener una excepción explícita,
justificada y visible. El estado resultante será aceptado con excepciones.

Una nueva decisión de rechazo revoca la aceptación anterior del mismo criterio.
El historial se conserva. Reintentar la misma petición no duplica decisiones.
Cambiar SHA, versión aprobada o informe revisado invalida las decisiones; el
servidor vuelve a comprobar la cobertura al solicitar merge y antes de publicar
la integración. Las comprobaciones GitHub previas siguen siendo obligatorias.

## Compatibilidad y API
No se conceden aceptaciones a tareas históricas. En un candidato histórico en
revisión, el operador debe importar expresamente su alcance y después revisarlo.
Una tarea ya integrada conserva su historial; no se reescribe como pendiente.

API autenticada del operador, con CSRF en mutaciones:
- GET /api/evidence: bandeja de candidatos en revisión.
- GET /api/evidence/{item}: descarga JSON.
- POST /api/evidence/{item}/import-scope: versión y SHA vigentes.
- POST /api/evidence/{item}/decisions: request_id, version, sha, basis,
  criterion, kind (manual/exception/reject), reason, environment.

basis lo proporciona el expediente y liga la decisión a los datos revisados.
Un basis antiguo es rechazado. Las credenciales de nodo no pueden leer expedientes
ni registrar decisiones. Las decisiones no autorizan por sí solas la fusión:
sigue existiendo una acción humana separada sobre el commit exacto.

La UI distingue la base investigada, el To-be aprobado y el resultado declarado.
El expediente conserva la propuesta original y el informe del candidato. Estos
diagramas siguen necesitando comprobación; no son una prueba de arquitectura.

## Pendientes de la fase 2
Verificador automático en entorno separado con credenciales acotadas; herramientas
y dependencias registradas automáticamente; reproducción del bug en base/candidato;
artefactos durables con integridad y enlaces desde la PR; vistas previas y capturas;
construcción y contraste del As-is desde el commit integrado y To-be comprobado;
validación visual escritorio/móvil. No se declara terminada la fase 2.
