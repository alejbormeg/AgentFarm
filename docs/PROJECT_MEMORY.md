# Memoria de proyecto versionada — tercer incremento de fase 4

El archivo `agentfarm-memory.json` de la raíz conserva decisiones aceptadas,
restricciones y motivos de rechazo. Git registra las versiones y la PR explica
por qué cambian. Este incremento incluye la memoria inicial de AgentFarm,
que solo entra en vigor para el ejecutor cuando se integra en su rama base.
No crea ni modifica una memoria en Forja.

## Proponer cambios

1. En Tareas selecciona la receta **Actualizar la memoria del proyecto**.
2. Describe la decisión, restricción o rechazo y sus referencias.
3. Napoleon investigará la memoria vigente y propondrá alcance y comprobaciones.
4. Aprueba el alcance; Boxer modificará el archivo en la rama de la tarea,
   ejecutará las comprobaciones aplicables y publicará una PR.
5. Revisa y fusiona el SHA explícitamente. Hasta esa integración, la propuesta
   de memoria de la rama candidata no sustituye a la memoria de la rama base.

Las peticiones y las recetas no cambian automáticamente este archivo.
Para correcciones documentales se justifican los tipos de pruebas no aplicables.
No guardes credenciales, conversaciones completas ni logs privados en Git.

## Formato

```json
{
  "version": 1,
  "repository": "owner/repo",
  "entries": [
    {
      "id": "ADR-001",
      "kind": "decision",
      "summary": "Conservar la interfaz pública.",
      "rationale": "Los consumidores necesitan una transición compatible.",
      "references": ["https://github.com/owner/repo/issues/1"]
    }
  ]
}
```

Tipos: decision (decisión), constraint (restricción) y rejection (motivo de
rechazo). references es opcional. Los identificadores son únicos y estables;
las versiones anteriores y entradas retiradas se consultan en el historial Git.
Los campos desconocidos, claves duplicadas, JSON adicional, repositorio distinto,
tipos inválidos, enlaces no HTTPS y documentos de más de 64 KiB se rechazan.
Hay como máximo 100 entradas; resumen hasta 240 caracteres, motivo hasta 2000
y diez referencias por entrada. No se ejecutan ni consultan esas URLs.

Validar un archivo local:

```bash
java -jar target/agentfarm.jar memory --file agentfarm-memory.json --repository alejbormeg/AgentFarm
```

Devuelve validación del esquema, hash y número de entradas. No acredita que
el archivo esté integrado ni que sus afirmaciones sean correctas.
Hay un ejemplo portable en examples/project-memory.json.

## Qué se registra y comprueba

El ejecutor hace fetch y consulta el commit de la rama base remota configurada
como default en GitHub. Lee el blob Git desde ese SHA; no usa el archivo sucio
del checkout ni la copia de una rama candidata. Rechaza symlinks y blobs grandes
antes de cargar su contenido.

La propuesta conserva project_memory: repository, base_sha, present, hash y
document cuando existe. La ausencia también queda registrada. El servidor
valida formato y hash al recibirla, y la aprobación congela el snapshot junto
al resto de la propuesta. La issue y los contextos de trabajo lo incluyen;
el panel lo muestra desplegable con enlace al commit. El expediente existente
conserva la propuesta aprobada. La memoria del candidato se valida antes de
publicar: si el esquema es inválido, las evidencias marcan fallo y la PR
queda en borrador aunque las otras pruebas hayan pasado. Validar el esquema
no convierte las decisiones del candidato en memoria aceptada.

Antes de implementar o revisar un candidato y antes de publicar, se compara
con la memoria de la rama base observada. Si aparece, desaparece o cambia,
la orden queda bloqueada: usa **Solicitar corrección** para investigar de nuevo
y aprobar la versión actualizada. Un reintento de implementación con el mismo
snapshot obsoleto seguirá bloqueándose. Un commit ajeno que conserve la misma
memoria no la invalida por sí solo; siguen vigentes las comprobaciones de base
y alcance del flujo existente.

Las tareas históricas sin snapshot continúan si su repositorio no tiene archivo.
Si aparece uno, deben investigarse de nuevo. Panel y ejecutor deben actualizarse
juntos para recibir estas comprobaciones.

## Límites y evidencia

La memoria es contexto del repositorio, no una instrucción de sistema,
autorización de ejecución ni evidencia de que algo funciona. No sustituye
la petición, el alcance aprobado, Clover o las políticas del operador.
La lectura remota es una observación del ejecutor con enlace verificable,
no una firma independiente del servidor ni una prueba de protección de rama.
Cambios posteriores a la publicación siguen requiriendo revisión humana;
este incremento no añade un webhook que invalide PR por cambios de memoria.

Pruebas: esquema, hashes y ausencia, alteraciones, congelación al aprobar,
lectura de blobs en repositorio Git temporal, cambios sucios/candidatos,
symlinks, tamaño y cambios que exigen nueva propuesta, sin inferencia ni red.
No acredita pilotos de memoria ni la revisión visual escritorio/móvil.
Siguen pendientes sugerencias por señales, roles de equipo y pilotos,
además de los pendientes de fases anteriores.

## Corrección operativa incluida

La validación prolongada encontró contención nativa en aperturas y cierres
SQLite que dejaba los hilos HTTP esperando. Acotar Tomcat en la entrega anterior
no bastaba para recuperar la disponibilidad. Store serializa ahora por ruta de
base de datos el ciclo completo de cada transacción, incluida apertura y cierre,
también entre instancias Store del mismo archivo. BEGIN IMMEDIATE ya imponía
exclusión de transacciones; la exclusión cubre ahora todo el ciclo nativo.
No se atribuye el fallo a una causa confirmada del proveedor JDBC.

Se comprueban 4.000 incrementos concurrentes y rollback, 192 peticiones HTTP
autenticadas en Spring Boot y una ráfaga real de 400 consultas autenticadas
a tareas, runtime, iniciativas y evidencias. La prueba anterior de login se
conserva pero no se considera suficiente para acreditar acceso a SQLite.
