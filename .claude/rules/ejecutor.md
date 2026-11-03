---
paths:
  - "runtime/**"
  - "tests/e2e/**"
---
# Ejecutor

- `runtime/FarmDaemon.java` no pasa por `./mvnw verify`. Lo compila `runtime/verify.sh`, y su comportamiento solo lo ejercita `tests/e2e/run.sh`. Todos los fallos del piloto sobre forja estaban aquí.
- Lleva la lógica nueva a una clase de `core` con pruebas de módulo y deja en `FarmDaemon` solo la llamada.
- Un cambio del ejecutor no llega al equipo con solo reiniciar el panel. Panel y ejecutor deben correr el mismo commit: usa `desplegar-local`.
- Los fallos transitorios del panel o de GitHub se esperan con `Backoff`. No salgas ni llenes el registro.
- Reintentar una tarea no debe duplicar trabajo: comprueba `resumable` y `startsOver` antes de cambiar cómo se reanuda un turno.
