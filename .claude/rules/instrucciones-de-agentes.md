---
paths:
  - "src/main/resources/prompts/**"
  - "src/main/resources/skills/**"
  - "src/main/resources/recipes.json"
  - "src/main/java/io/agentfarm/core/Prompts.java"
  - "src/main/java/io/agentfarm/core/RecipeCatalog.java"
  - "src/main/java/io/agentfarm/core/SkillCatalog.java"
---
# Prompts, recetas y skills de los agentes

- Una versión publicada no se edita, porque la evidencia guarda su hash. Añade `nombre.vN+1.md`, o una entrada nueva en `recipes.json`, y actualiza `Prompts.CURRENT` y `PromptsTest`.
- Si cambias los huecos `{{...}}` de un prompt, actualiza también las llamadas a `Prompts.render`: un valor que falta o sobra es un error, y varias están en `FarmDaemon`.
- `tests/e2e/fake_codex.py` no razona: un prompt peor pasa el e2e igual. Di en la PR que no se ha validado con un agente real, salvo que lo hayas hecho.
- Pide a los agentes solo lo que la plataforma puede acreditar. Un criterio imposible bloquea la revisión.
- Estas skills son del producto y las leen los agentes de los usuarios. No las confundas con las de `.claude/skills/`, que son para desarrollar este repositorio.
