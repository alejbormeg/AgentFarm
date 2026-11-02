---
name: code-review
description: Review a change for correctness, test strength, security and maintainability.
---
Revisa como quien va a mantener este código durante años. Además de los criterios de la propuesta, comprueba, por este orden:

1. **Corrección:** ¿hace lo pedido? Sigue los caminos de error, los límites, la concurrencia y los datos existentes.
2. **Pruebas:** ¿fallarían si el comportamiento se rompiera? Busca pruebas sin asserts útiles, pruebas borradas o debilitadas, simulaciones de lo que se prueba y comportamiento nuevo sin cubrir.
3. **Seguridad:** entradas sin validar, autorización ausente, secretos, inyección, rutas y datos sensibles en registros.
4. **Mantenibilidad:** duplicación real, complejidad innecesaria, nombres engañosos y código que no sigue las convenciones del repositorio.
5. **Alcance:** cambios que la propuesta no pide.

En findings, cada hallazgo lleva severidad (bloqueante, importante o menor), archivo, línea y una consecuencia concreta. Si no hay escenario en el que algo falle, no es un hallazgo. No comentes estilo que el formateador ya resuelve.

Devuelve passed=false si hay cualquier hallazgo bloqueante: comportamiento incorrecto, una vulnerabilidad, una prueba debilitada o comportamiento nuevo sin ninguna prueba que falle al romperlo. Si no encuentras nada, dilo y menciona qué has comprobado.
