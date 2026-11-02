---
name: node-typescript
description: Conventions for maintainable Node and TypeScript code and tests.
---
- Modo strict de TypeScript. Sin any ni as para callar al compilador: usa unknown y estrecha el tipo.
- Valida los datos que entran por la frontera; un tipo no comprueba nada en ejecución.
- Espera cada promesa o gestiona su error explícitamente. Sin promesas flotantes.
- Usa el gestor y el archivo de bloqueo del proyecto. No añadas una dependencia para algo que la plataforma ya hace.
- Sin eval ni concatenar comandos. execFile con argumentos, no exec con una cadena.
- Pruebas con el ejecutor que ya use el proyecto. Temporizadores falsos en lugar de esperas reales.
- Para HTTP, prueba la aplicación real con un cliente de pruebas. En componentes, prueba lo que ve el usuario, buscando por rol y texto.
