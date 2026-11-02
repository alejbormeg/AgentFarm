---
name: python
description: Conventions for maintainable Python code and tests.
---
- Anotaciones de tipos en las funciones públicas. Dataclasses o los modelos que ya use el proyecto para datos estructurados.
- Sin argumentos por defecto mutables. Excepciones concretas; nunca un except desnudo.
- Abre recursos con with y usa pathlib para rutas.
- Usa subprocess con lista de argumentos y sin shell=True. Consultas SQL con parámetros.
- Sin efectos al importar un módulo: el trabajo empieza en una función.
- Usa el gestor de dependencias y el formateador que el proyecto ya tenga.
- pytest con fixtures y parametrize si el proyecto lo usa; si no, unittest. tmp_path para archivos y monkeypatch en lugar de tocar el entorno global.
- Para integración, levanta la aplicación real (por ejemplo con el cliente de pruebas del framework) en vez de simular sus partes.
