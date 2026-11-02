---
name: software-design
description: Choose the simplest design that holds, and make the acceptance criteria checkable.
---
Elige el diseño más simple que resuelva la petición y encaje con la arquitectura existente. Un patrón se usa cuando resuelve un problema presente de este código, no por costumbre.

- Separa lo que cambia por motivos distintos: reglas de negocio, entrada y salida, persistencia.
- Las dependencias apuntan hacia las reglas de negocio, no al revés. Prefiere composición a herencia.
- Define límites pequeños y explícitos. Da nombre a los conceptos del dominio.
- Considera al menos una alternativa y explica en summary por qué la descartas.
- Si tocas datos o interfaces públicas, propone compatibilidad, transición y cómo volver atrás.
- Divide lo grande en cambios que se puedan revisar y entregar por separado.
- Cada criterio de aceptación describe un comportamiento comprobable. Prefiere method "test". Usa "manual" solo si una prueba automática no puede comprobarlo, y di por qué.
- Propón comandos de prueba que existan en el repositorio o que el cambio vaya a crear. Nómbralos solo después de leer cómo se prueban hoy.
- Señala en risks lo que no has podido comprobar, sin rellenar con riesgos genéricos.
