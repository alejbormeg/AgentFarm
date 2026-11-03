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
Los informes se etiquetan como declarados; un passed de Boxer no verifica por sí
solo ningún criterio. Los campos de credenciales conocidos se ocultan
en la exportación; no debe enviarse ningún secreto como evidencia.

Al abrir la tarea se ve qué criterio está pendiente, validado manualmente,
rechazado o exceptuado. Una decisión necesita una explicación de al menos veinte
caracteres y el entorno utilizado. Registrar validación manual significa que
el operador declara haber realizado esa comprobación; no certifica una prueba
automática ni una observación independiente del servidor.

Un criterio definido como test no se puede sustituir por una validación manual.
Se cierra solo cuando el verificador lo comprueba (ver abajo). Si no lo consigue,
queda pendiente o necesita una excepción explícita, justificada y visible, y el
estado resultante es aceptado con excepciones. Un rechazo humano siempre prevalece.

## Verificador
`EvidenceVerifier` corre en el panel cada 30 segundos, con la credencial de GitHub
del panel y sin depender del ejecutor. Para cada tarea en revisión lee del commit
exacto los check-runs, los estados y los archivos cambiados, y guarda el resultado
ligado al `basis`: si cambia el SHA, la versión o el informe, deja de valer.

Un criterio de test queda **comprobado** si la CI pasó entera y, además, el informe
muestra para cada comprobación que el comando aprobado, sin alterar, pasó en el
contenedor desechable. Las pruebas en el host no cuentan. La CI no pasa si el
candidato cambia otro workflow distinto de `.github/workflows/agentfarm.yml`.

**N/A:** un criterio de test no puede remitir a un comando `N/A:`, y un candidato que
cambia código con un tipo de prueba en `N/A:` no pasa. Solo se admite N/A cuando los
cambios son documentación (`docs/`, `.md`, `.txt`, licencias, memoria del proyecto).

Comprueba que los comandos aprobados pasaron, no que las pruebas sean buenas. Sin
credencial de GitHub en el panel, los criterios de test quedan sin comprobar.

Una nueva decisión de rechazo revoca la aceptación anterior del mismo criterio.
El historial se conserva. Reintentar la misma petición no duplica decisiones.
Cambiar SHA, versión aprobada o informe revisado invalida las decisiones; el
servidor vuelve a comprobar la cobertura al solicitar merge y antes de publicar
la integración. Las comprobaciones GitHub previas siguen siendo obligatorias.

Al recibir una propuesta, el panel avisa de los criterios de test que quizá no se puedan acreditar:
los que piden algo que las pruebas no suelen demostrar, como la identidad de un binario o el usuario
del proceso, y los que se cierran con un comando que siempre pasa. Es un aviso y no bloquea, porque
una frase puede dar una falsa alarma. Decide antes de aprobar: corrige la propuesta, o conviértelo en un
criterio `manual`. Un criterio de test sobre un comando `N/A:` sí se rechaza.

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
Herramientas y dependencias registradas automáticamente; reproducción del bug en base/candidato;
artefactos durables con integridad y enlaces desde la PR; vistas previas y capturas;
construcción y contraste del As-is desde el commit integrado y To-be comprobado;
validación visual escritorio/móvil. No se declara terminada la fase 2.
