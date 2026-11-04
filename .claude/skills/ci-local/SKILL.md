---
name: ci-local
description: Levanta un runner de GitHub Actions para este repositorio con un Docker propio, aislado del de este equipo. Úsala cuando no queden minutos de Actions y haga falta CI real para fusionar.
---
# CI local

**Sin probar:** la imagen compila, pero este runner no ha atendido ningún job. Mira la primera ejecución.

La puerta de fusión exige comprobaciones reales. Sin minutos de Actions, este runner las da sin forzar nada.

**No uses un runner normal en este equipo.** El job `container` lanza `docker compose up` en un proyecto llamado `agentfarm`, el mismo que la instalación real: la reemplazaría. Aquí los jobs hablan con un Docker dentro de otro contenedor, sin acceso al socket del equipo ni a tu home. Ese contenedor es `--privileged`, así que úsalo solo con código de este repositorio.

```bash
bash .claude/skills/ci-local/run.sh up     # construye, registra y atiende jobs en segundo plano
gh variable set AGENTFARM_RUNNER -b agentfarm-local
gh api -X PUT repos/{owner}/{repo}/actions/permissions -F enabled=true -f allowed_actions=all
```

Los jobs se atienden de uno en uno, porque comparten puertos: una PR tarda unos 15 minutos. Pasa `MERGE_GATE_TIMEOUT=3600` a `merge-gate.sh`.

Al terminar:

```bash
gh variable delete AGENTFARM_RUNNER
bash .claude/skills/ci-local/run.sh down   # para, da de baja el runner y borra sus volúmenes
```

Sin la variable, los jobs vuelven a `ubuntu-24.04`. Con ella y sin runner, se quedan en cola para siempre.
