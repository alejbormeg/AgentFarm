---
paths:
  - "src/main/java/io/agentfarm/ui/**"
  - "src/main/resources/static/**"
  - "tests/ui/**"
---
# Interfaz

- `DESIGN.md` manda. Toda la interfaz sale de un componente web compartido, `static/farm-console.js`, dentro de la shell Mateu.
- Los datos se refrescan por sondeo: un formulario o un borrador debe sobrevivir al refresco, también en un cajón.
- El vocabulario de Orwell nombra cosas y las acciones usan palabras llanas (`docs/ANIMAL_FARM.md`). Un aviso aparece solo si hay algo que hacer y dice cómo resolverlo.
- Comprueba cada cambio visible en un navegador con `panel-aislado`. Una prueba unitaria no ve una vista en blanco.
- Añade cada aviso nuevo a `tests/ui/smoke.mjs` y comprueba que el texto que busca es el mismo que muestra el panel. Un texto distinto ya rompió la CI.
- No inventes porcentajes de avance ni muestres como vigente un dato caducado.
