# AgentFarm desde Claude Code o Claude Desktop (MCP)

`agentfarm.jar mcp` es un servidor [Model Context Protocol](https://modelcontextprotocol.io)
por stdio que habla con tu panel con una credencial propia, que **solo lee tareas y crea
peticiones**.

| Herramienta | Qué hace |
| --- | --- |
| `agentfarm_inbox` | Tareas abiertas y si esperan tu decisión |
| `agentfarm_work_item` | Propuesta, criterios, sandbox, SHA, PR y hallazgos de revisión |
| `agentfarm_create_request` | Crea una petición (opcionalmente con receta); AgentFarm investiga antes de proponer |
| `agentfarm_metrics` | Resultados por semana |
| `agentfarm_status` | Pausa, ejecutores conectados y cuota |

## Por qué no tu contraseña

El modelo que usa el MCP puede leer cualquier archivo que lea su cliente, también el de la
credencial. Con la contraseña del propietario podría aprobar, integrar o pausar la granja por la
API, aunque el MCP no tenga herramientas para ello. Por eso el MCP usa una credencial que el panel
limita a leer tareas (`/api/work`, `/api/overview`, `/api/runtime`) y a crear peticiones
(`POST /api/work`): cualquier otra ruta responde 403. Las peticiones quedan en la auditoría como
`requester:<nombre>`.

Si le das una cuenta de propietario o de revisor, el MCP no arranca. Solo con
`--allow-full-account true` acepta una, y no es recomendable.

## Crear la credencial

Desde el directorio de AgentFarm, con el panel en marcha:

```bash
./agentfarm mcp-token
```

La guarda en `~/.config/agentfarm/mcp-token` con permiso 600, sin mostrarla. Puedes elegir otro
archivo: `./agentfarm mcp-token /ruta/archivo`. En **Gobierno → Conexiones** ves cada credencial con
su último uso y la revocas.

## Claude Code

```bash
claude mcp add agentfarm -- java -jar /ruta/AgentFarm/target/agentfarm.jar mcp --panel http://localhost:8080
```

Sin `--token-file`, usa `~/.config/agentfarm/mcp-token`.

## Claude Desktop (`claude_desktop_config.json`)

```json
{
  "mcpServers": {
    "agentfarm": {
      "command": "java",
      "args": ["-jar", "/ruta/agentfarm.jar", "mcp", "--panel", "http://localhost:8080",
               "--token-file", "/ruta/mcp-token"]
    }
  }
}
```

La credencial no lleva segundo factor ni caduca: revócala en Gobierno cuando deje de usarse. Si ya
habías copiado la contraseña del panel para el MCP, borra ese archivo y cámbiala.

## Salud
`GET /api/health` es público y solo devuelve `{"status":"UP"}` (o 503). Con la
sesión del propietario añade ejecutores frescos, tareas que esperan tu decisión,
comandos en cola y pausa. El healthcheck de Docker lo usa.

También añade `scheduler`: una fila por tarea programada (integración, verificación de
evidencias, notificaciones, disparadores y resumen semanal) con su último retraso, duración,
fallos y estado:

- `late`: arrancó más de 30 s después de lo previsto, o su última pasada (o la actual) dura más
  de 60 s.
- `failing`: su última pasada falló; `last_error` va sin secretos.
- `ok`: ninguno de los anteriores.

Cada tarea corre en su propio hilo, así que un GitHub lento o un webhook colgado solo retrasa a
su tarea. La verificación de evidencias dedica como mucho 20 s por pasada y la siguiente
retoma donde se quedó; las notificaciones, 30 s. Las escrituras en SQLite siguen pasando de una
en una.
