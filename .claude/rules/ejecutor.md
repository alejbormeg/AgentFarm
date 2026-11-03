---
paths:
  - "runtime/**"
  - "tests/e2e/**"
---
# Ejecutor

- `runtime/FarmDaemon.java` no pasa por `./mvnw verify`. Lo compila `runtime/verify.sh`, y su comportamiento solo lo ejercita `tests/e2e/run.sh`, que empaqueta el ejecutor y corre el jar que se distribuye. Todos los fallos del piloto sobre forja estaban aquí.
- Un `Error` en el bucle principal debe terminar el proceso para que systemd lo reinicie. Un hilo que no sea daemon lo mantendría vivo, informando de que está sano sin coger órdenes.
- Lleva la lógica nueva a una clase de `core` con pruebas de módulo y deja en `FarmDaemon` solo la llamada.
- Una clase nueva de `core` que use el ejecutor hay que añadirla a la lista de `runtime/package.sh`. `bash runtime/package.sh` detecta las que faltan, y sin ellas el ejecutor instalado no arranca.
- El snapshot que envía el ejecutor pasa por una lista de campos en `TelemetryService.ingest`: un campo nuevo que no esté en ella no llega al panel.
- Un cambio del ejecutor no llega al equipo con solo reiniciar el panel. Panel y ejecutor deben correr el mismo commit: usa `desplegar-local`.
- Los fallos transitorios del panel o de GitHub se esperan con `Backoff`. No salgas ni llenes el registro.
- Reintentar una tarea no debe duplicar trabajo: comprueba `resumable` y `startsOver` antes de cambiar cómo se reanuda un turno.
