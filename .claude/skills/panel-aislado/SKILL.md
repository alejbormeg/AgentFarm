---
name: panel-aislado
description: Arranca un panel de AgentFarm desechable, con base de datos propia y otro puerto, para probar la interfaz o la API sin tocar la instalación real. Úsala para ver un cambio en un navegador o reproducir un fallo.
---
# Panel aislado

Nunca pruebes contra `localhost:8080`. Arranca una copia desechable:

```bash
./mvnw -B -ntp -q -DskipTests package
tmp=$(mktemp -d)
AGENTFARM_PASSWORD="$(openssl rand -hex 16 | tee "$tmp/password")" AGENTFARM_DB="$tmp/agentfarm.db" PORT=18095 \
  java -jar target/agentfarm.jar > "$tmp/panel.log" 2>&1 &
```

Espera a `curl -fsS http://127.0.0.1:18095/api/health`. Al terminar, para el proceso y borra `$tmp`.

Para tener datos en todas las vistas sin cuentas reales, usa `./agentfarm demo` (puerto 8090), o `bash tests/e2e/run.sh` con `E2E_KEEP=1`, que deja su directorio de trabajo para revisarlo.

Para recorrer las vistas en un navegador sin interfaz:

```bash
CHROME=google-chrome node tests/ui/smoke.mjs http://127.0.0.1:18095 "$(cat "$tmp/password")"
```

El smoke necesita Chrome. Si este equipo no lo tiene, la CI lo ejecuta en el job `e2e`, y puedes mirar el panel desde el navegador del propietario. Di siempre en la PR cuál de las dos cosas has hecho.
