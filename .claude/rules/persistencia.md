---
paths:
  - "src/main/java/io/agentfarm/core/SchemaMigrations.java"
  - "src/main/java/io/agentfarm/core/Store.java"
  - "src/main/java/io/agentfarm/core/LegacyImporter.java"
  - "src/main/java/io/agentfarm/core/Backups.java"
---
# Persistencia

- Cada cambio de esquema es una `Migration` nueva al final de `SchemaMigrations.ALL`, con su prueba en `SchemaMigrationsTest`. No edites una migración ya publicada.
- El esquema base de `Store` y de los servicios se ejecuta en cada arranque sobre una base ya migrada. Nombra siempre las columnas de un `INSERT`: añadir una columna a `governance` impidió reiniciar el panel, y solo lo detectó el job `container`.
- Conserva los datos de SQLite. Una base de una versión anterior se actualiza sin perder nada, y una de una versión más nueva se rechaza.
- Una fila antigua no tiene el dato que una columna nueva registra: deja `NULL` y que la interfaz lo diga, en vez de inventarlo.
- Antes de desplegar una migración sobre la instalación real, haz `./agentfarm backup`.
