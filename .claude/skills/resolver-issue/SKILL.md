---
name: resolver-issue
description: Lleva una issue de AgentFarm de principio a fin. Rama, prueba que falla, cambio, comprobaciones, PR y fusión con la puerta estricta. Úsala para trabajar o cerrar una issue de este repositorio.
---
# Resolver una issue

1. Lee la issue con `gh issue view <n>` y su aceptación. Antes de tocar nada, separa lo que puedes acreditar tú de lo que necesita a una persona, una cuenta o cuota real.
2. Si parte de la aceptación no está a tu alcance, haz lo que sí lo esté y deja la issue abierta con un comentario que diga qué falta y quién debe hacerlo. Nunca la cierres reinterpretando su aceptación.
3. Crea la rama desde un `main` actualizado:

   ```bash
   git checkout main && git pull --ff-only origin main && git checkout -b <tipo>/<tema>
   ```

4. Escribe la prueba y mira cómo falla. Después haz el cambio.
5. Ejecuta las comprobaciones de la tabla de `AGENTS.md` según lo que hayas tocado.
6. Añade la línea de `CHANGELOG.md` y actualiza `docs/ROADMAP.md` si procede.
7. Abre la PR con título `[#n] Qué cambia` y `Closes #n` en el cuerpo solo si se cumple toda la aceptación; si no, `Refs #n`. Di qué se ha probado en real y qué con dobles.
8. Con autorización del propietario para fusionar, usa la puerta:

   ```bash
   bash .claude/skills/resolver-issue/merge-gate.sh <n> "[#issue] Título (#n)"
   ```

   Espera a las comprobaciones, exige las cuatro obligatorias en verde sobre el head actual y fusiona por ese SHA. **Si no fusiona, investiga la causa**: nunca fuerces la fusión ni la hagas a mano.
9. Comprueba que la issue se ha cerrado y vuelve a `main`. Si el cambio afecta al panel o al ejecutor, valora desplegar con `desplegar-local`.
