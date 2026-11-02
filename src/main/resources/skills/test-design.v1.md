---
name: test-design
description: Write unit and integration tests that fail when behaviour breaks.
---
Una prueba vale si falla cuando el comportamiento se rompe. Antes de darla por buena, piensa qué cambio del código la haría fallar; si ninguno razonable lo haría, no sirve.

- **Unitaria:** una regla o decisión, sin red, disco ni reloj real. Rápida y determinista.
- **Integración:** cruza una frontera real: base de datos, HTTP, sistema de archivos o proceso. Usa los componentes reales de esa frontera siempre que se pueda.
- Prueba el comportamiento observable, no la implementación: si una refactorización correcta rompe la prueba, está mal planteada.
- Cubre el caso normal, los errores y los límites (vacío, uno, máximo, duplicado, datos inválidos).
- Un bug se corrige con una prueba que falla antes del arreglo y pasa después.
- Cada prueba tiene un motivo para fallar y un nombre que describe ese comportamiento.
- Cada prueba comprueba algo concreto: nada de pruebas sin asserts, ni asserts triviales, ni comprobar que un mock devuelve lo que le pediste.
- Simula solo lo que no controlas. No simules la unidad que estás probando.
- Sin esperas fijas, orden entre pruebas, fechas actuales ni estado compartido.
- Nunca borres, ignores o debilites una prueba para que el conjunto pase. Si una prueba estaba mal, explica por qué en limitations.
