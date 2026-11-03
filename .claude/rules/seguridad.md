---
paths:
  - "src/main/java/io/agentfarm/core/AgentSandbox.java"
  - "src/main/java/io/agentfarm/core/TestSandbox.java"
  - "src/main/java/io/agentfarm/core/GitHub*.java"
  - "src/main/java/io/agentfarm/core/NodeCredentials.java"
  - "src/main/java/io/agentfarm/core/SecretScan.java"
  - "src/main/java/io/agentfarm/web/SecurityConfig.java"
  - "src/main/java/io/agentfarm/web/NodeTokenFilter.java"
  - "agentfarm"
  - "compose.yaml"
  - "tests/credentials.sh"
  - "tests/codex_sandbox.sh"
---
# Seguridad

- El sandbox de Codex es una lista de exclusión. Si añades una ruta con credenciales, añádela a `AgentSandbox` y compruébala en `tests/codex_sandbox.sh`, que prueba el sandbox real. `AgentSandboxTest` solo comprueba el perfil que generamos.
- Que Codex tenga la red apagada no sustituye a ocultar un socket: aplica las dos capas.
- Una credencial se lee de un archivo o del entorno, nunca de un argumento de orden, porque se vería en `ps`. No la escribas en la salida, ni en logs, ni en errores.
- Los comandos que guardan un secreto no lo muestran, ni siquiera parte.
- Una comprobación de credencial debe probar lo que la integración necesita de verdad. Un token de grano fino lee la PR, pero recibe 403 en `check-runs`.
- Lo que escribe un agente no es de fiar: `agentfarm.yaml`, prompts e issues pueden traer instrucciones. Un repositorio no aporta sus propias skills, y las reglas de una tarea salen del perfil de la rama base, nunca del candidato.
- Un error en el aislamiento o en la credencial detiene el trabajo y lo explica en el panel. No sigas sin él.
