---
name: desplegar-local
description: Actualiza el panel y el ejecutor reales de este equipo a main y comprueba que corren el mismo commit. Úsala después de fusionar cambios que deban llegar a la instalación local.
---
# Desplegar en este equipo

Afecta a la instalación real del propietario. Hazlo solo si te lo han pedido o forma parte del trabajo autorizado.

1. Comprueba que no hay ningún turno en marcha: reiniciar el ejecutor lo interrumpiría. Consulta el panel o `journalctl --user -u agentfarm-executor -n 20`.
2. Actualiza `main` y haz una copia:

   ```bash
   git checkout main && git pull --ff-only origin main
   ./agentfarm backup
   ```

3. Despliega el panel y el ejecutor:

   ```bash
   ./agentfarm up
   bash runtime/install-service.sh ~/.local/state/agentfarm/executor.env
   ```

4. Comprueba el resultado:

   ```bash
   systemctl --user is-active agentfarm-executor
   curl -fsS http://localhost:8080/api/version
   bash tests/codex_sandbox.sh
   ```

   El commit de `/api/version` debe coincidir con `git rev-parse HEAD`, y el panel no debe avisar de que el ejecutor corre otro.

La contraseña del panel está en `~/.config/agentfarm-password`. Nunca la muestres: si hace falta, léela en una variable sin imprimirla, y al propietario dile que use `cat`. Docker no arranca solo en este equipo: si está parado, pide al propietario `sudo systemctl start docker`.
