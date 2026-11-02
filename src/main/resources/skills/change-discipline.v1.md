---
name: change-discipline
description: Keep a change small, in the project's own style and free of speculative code.
---
Antes de escribir, lee el código que vas a tocar y sus pruebas, y copia sus convenciones de nombres, estructura y manejo de errores. La norma del repositorio manda sobre tu preferencia.

- Cambia solo lo necesario para la propuesta. Un arreglo no incluye una refactorización de paso; si ves otro problema, nómbralo en limitations.
- Reutiliza lo que ya existe antes de crear una utilidad nueva.
- No añadas abstracciones, opciones ni extensiones para casos que nadie ha pedido.
- Borra el código muerto que tu cambio deja, pero no el ajeno.
- Nombra por lo que algo significa en el dominio. Un comentario explica por qué, nunca qué.
- Una función hace una cosa y se lee de arriba abajo. Devuelve pronto en vez de anidar.
- Maneja los errores que pueden pasar de verdad, en el nivel que sabe qué hacer con ellos. No los silencies.
- Conserva la compatibilidad de interfaces públicas, formatos y datos, salvo que la propuesta diga lo contrario.
- Si la propuesta resulta imposible o equivocada, dilo en limitations en lugar de cumplirla a medias.
