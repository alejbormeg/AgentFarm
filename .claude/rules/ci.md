---
paths:
  - ".github/**"
  - "runtime/mvn-retry.sh"
  - "tests/**"
---
# CI

- Cada PR corre una vez: `pull_request`, y `push` solo en `main`. Un push nuevo cancela la ejecución anterior de esa PR.
- Java 25 corre solo al fusionar en `main`. Lo que solo cambia `docs/`, `site/` o `*.md` de la raíz no corre: mantén esa lista igual en `java.yml` y `merge-gate.sh`. Un prompt es Markdown, pero no documentación.
- Las comprobaciones obligatorias son `java (21)`, `e2e` y `container`. Si renombras un job, actualiza `required` en `merge-gate.sh`, o la puerta no fusionará nada.
- La primera llamada a Maven de cada job pasa por `runtime/mvn-retry.sh`, que solo repite cortes de descarga. No añadas reintentos a las pruebas: un fallo debe verse.
- Cada script nuevo se añade a un job y a `comprobar.sh`.
- `tests/security_scan.sh` falla con una vulnerabilidad alta o crítica que tenga arreglo, o con un secreto en el historial. Actualiza la dependencia. Una coincidencia falsa de gitleaks va en `.gitleaksignore`, comprobando antes que lo es: nunca copies su valor.
- Acciones e imágenes base van fijadas por SHA y digest. Al actualizarlas, cambia el valor y el comentario de la versión.
- Todo job lleva `timeout-minutes`: uno colgado consume minutos y no avisa.
- Un artefacto ocupa espacio hasta que caduca, y ese espacio no vuelve al renovarse el ciclo. Sube uno solo si algo lo descarga y siempre con `retention-days`: 294 sin uso llegaron a 13,8 GB frente a los 500 MB del plan. `tests/pre_push.sh` exige la caducidad.
- Si un fallo es de infraestructura, demuéstralo con el registro antes de relanzar. Consulta la skill `diagnosticar-ci`.
- `comprobar.sh` da por bueno el commit que `validar-local.sh` ya pasó, porque esta corre todo lo que aquella corre, en una copia limpia.
- Sin minutos, Actions está desactivado y `validar-local.sh` hace lo que `java.yml`: mantén ambos iguales. `merge-gate.sh --local` solo fusiona el commit que ese script validó, y deja de funcionar cuando Actions vuelve a estar activo.
- `tests/claude_rules.sh` y `tests/codex_sandbox.sh` necesitan una cuenta real, así que no van en la CI. Se repiten al actualizar esas herramientas.
