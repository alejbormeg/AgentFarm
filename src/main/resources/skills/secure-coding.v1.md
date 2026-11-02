---
name: secure-coding
description: Avoid the common vulnerabilities in code that handles input, data or credentials.
---
- Trata como no fiable todo lo que venga de fuera: peticiones, archivos, variables, respuestas de otros servicios y salida de modelos.
- Valida en la frontera, con listas de lo permitido.
- Usa consultas parametrizadas y las API que separan datos de órdenes. No construyas SQL, HTML ni comandos de shell concatenando texto.
- Comprueba la autorización en el servidor en cada operación, no solo en la interfaz.
- Normaliza las rutas y rechaza las que salgan del directorio previsto. Limita a qué destinos puede llamar el servidor con URL recibidas.
- Nunca escribas secretos en el código, las pruebas, los registros ni los mensajes de error. Se leen del entorno o de un gestor de secretos.
- Usa las bibliotecas criptográficas estándar y funciones de contraseña lentas. No inventes criptografía.
- Limita tamaños, tiempos y reintentos de lo que controla un usuario.
- Añade una dependencia solo si compensa su riesgo. Fija la versión y usa el archivo de bloqueo del proyecto.
