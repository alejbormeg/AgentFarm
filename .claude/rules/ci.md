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
- Todo job lleva `timeout-minutes`: uno colgado consume minutos y no avisa.
- Si un fallo es de infraestructura, demuéstralo con el registro antes de relanzar. Consulta la skill `diagnosticar-ci`.
