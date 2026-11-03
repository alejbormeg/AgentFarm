---
paths:
  - "src/**/*.java"
  - "runtime/*.java"
  - "pom.xml"
---
# Java 21 y 25

- El proyecto compila para **Java 21** (`java.version` en `pom.xml`, `--release 21` en `runtime/`) y la CI lo ejecuta también con **25**. Escribe Java 21: nada de lo que llegó después, como las variables `_`, `Stream.gather`, `ScopedValue`, los constructores flexibles o los archivos compactos. Sin `--enable-preview`.
- En 21 puedes usar records, `switch` con patrones, bloques de texto, `List.getLast()` y hilos virtuales. Sigue el estilo del archivo que tocas: `core` ya usa records y `Map.of`.
- Desde Java 23, `javac` solo procesa anotaciones si se configura de forma explícita. `pom.xml` declara Lombok y Mateu en `annotationProcessorPaths`: un procesador nuevo debe ir allí.
- Java 24 y 25 endurecen el uso de `sun.misc.Unsafe` y de JNI con avisos en el arranque, y eliminan el Security Manager. No los uses tú, y mira esos avisos al actualizar una dependencia nativa, como `sqlite-jdbc`.
- Este equipo solo tiene JDK 21: lo propio de Java 25 solo se comprueba en la CI.

## Errores que ya hemos cometido

- **`store.tx` deshace la transacción si lanzas una excepción.** Si un contador de fallos debe persistir, devuelve un resultado y lanza fuera, como en `TwoFactor`. Fuera de un `return`, a veces hay que dar el tipo: `boolean ok = store.tx(...)`.
- **Un controlador nuevo necesita su manejador de errores.** Sin `@ExceptionHandler` o una entrada en `WorkflowErrors`, un `IllegalArgumentException` llega como 500 sin mensaje.
- **Un dominio nuevo bajo `/api/**` es solo del propietario** por la última regla de `SecurityConfig`. Declara antes los roles que necesite.
- **Una lista blanca te puede dejar fuera:** `TelemetryService.ingest` descarta cualquier campo que no esté en su lista.
- **`GitHubReader.get` valida la ruta:** una prueba con una lambda como doble no lo ve. Si añades una lectura, pruébala con el cliente real contra `tests/e2e/fake_github_api.py`.
- **Un `AGENTFARM_PASSWORD` de menos de 16 caracteres** impide arrancar el panel.
- **Una prueba de reapertura debe abrir el mismo archivo.** Mira que falle sin el arreglo: una vez pasó porque reabría otra base.

## Para escribir pruebas

- Con `@TempDir` para las bases, y `/dev/shm` si la prueba hace miles de commits: el `fsync` puede tardar más que la propia prueba.
- `WebTest` usa el servidor real con `HttpClient`. Comparte contexto con otras pruebas: deshaz lo global en `finally`, y usa cuentas propias del test.
- Las pruebas de `FarmDaemon` van en `runtime/RuntimeChecks.java`, o mejor en una clase de `core` con su prueba.
