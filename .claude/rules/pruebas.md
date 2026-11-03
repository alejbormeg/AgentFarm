---
paths:
  - "src/test/**"
  - "tests/**"
---
# Pruebas

- Una prueba nueva debe fallar sin el cambio. Compruébalo al menos una vez.
- Un doble (`fake_codex.py`, `fake_gh.py`, `fake_github_api.py`) debe rechazar lo mismo que el servicio real. Si el real te sorprende, corrige primero el doble hasta que reproduzca el fallo.
- Sin red real ni cuota en la CI. Lo que no se pueda probar sin ellas va a `docs/PILOTS.md` y se dice en la PR.
- Si `tests/e2e/run.sh` tiene un conflicto, resuélvelo leyendo el flujo: las secciones dependen del estado de las anteriores. Un orden invertido ya dejó el e2e en rojo.
- Un test inestable es un error: busca la causa. Repetir hasta que salga verde esconde el fallo.
- Los scripts llevan `set -euo pipefail`, usan un directorio temporal y un puerto propio, y limpian con `trap`. Nunca tocan el puerto 8080 ni el volumen `agentfarm_farm-data`.
