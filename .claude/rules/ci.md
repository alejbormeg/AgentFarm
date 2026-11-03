---
paths:
  - ".github/**"
  - "runtime/mvn-retry.sh"
  - "tests/**"
---
# CI

- Cada PR corre una vez: `pull_request`, y `push` solo en `main`. Un push nuevo cancela la ejecución anterior de esa PR.
- Las comprobaciones obligatorias son `java (21)`, `java (25)`, `e2e` y `container`. Si renombras un job, actualiza `required` en `merge-gate.sh`, o la puerta no fusionará nada.
- La primera llamada a Maven de cada job pasa por `runtime/mvn-retry.sh`, que solo repite cortes de descarga. No añadas reintentos a las pruebas: un fallo debe verse.
- Cada script nuevo se añade a un job y a `comprobar.sh`.
- Todo job lleva `timeout-minutes`: uno colgado consume minutos y no avisa.
- Si un fallo es de infraestructura, demuéstralo con el registro antes de relanzar. Consulta la skill `diagnosticar-ci`.
