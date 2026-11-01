# AgentFarm desde Claude Code o Claude Desktop (MCP)

`agentfarm.jar mcp` es un servidor [Model Context Protocol](https://modelcontextprotocol.io)
por stdio que habla con tu panel con la credencial del propietario.

| Herramienta | Qué hace |
| --- | --- |
| `agentfarm_inbox` | Tareas abiertas y si esperan tu decisión |
| `agentfarm_work_item` | Propuesta, criterios, sandbox, SHA, PR y hallazgos de revisión |
| `agentfarm_create_request` | Crea una petición (opcionalmente con receta); AgentFarm investiga antes de proponer |
| `agentfarm_metrics` | Resultados por semana |
| `agentfarm_status` | Pausa, ejecutores conectados y cuota |

No hay herramientas para aprobar, implementar ni integrar: un modelo no debe
aprobar trabajo de agentes. Eso sigue en el panel.

## Claude Code

```bash
./agentfarm password > ~/.config/agentfarm-password && chmod 600 ~/.config/agentfarm-password
claude mcp add agentfarm -- java -jar /ruta/AgentFarm/target/agentfarm.jar mcp --panel http://localhost:8080 --password-file ~/.config/agentfarm-password
```

## Claude Desktop (`claude_desktop_config.json`)

```json
{
  "mcpServers": {
    "agentfarm": {
      "command": "java",
      "args": ["-jar", "/ruta/agentfarm.jar", "mcp", "--panel", "http://localhost:8080",
               "--password-file", "/ruta/agentfarm-password"]
    }
  }
}
```

El fichero de contraseña debe tener permiso 600 y nunca estar en Git.

## Salud
`GET /api/health` es público y solo devuelve `{"status":"UP"}` (o 503). Con la
sesión del propietario añade ejecutores frescos, tareas que esperan tu decisión,
comandos en cola y pausa. El healthcheck de Docker lo usa.
