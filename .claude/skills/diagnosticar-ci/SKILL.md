---
name: diagnosticar-ci
description: Encuentra por qué ha fallado un job de GitHub Actions de AgentFarm y distingue un error real de un fallo de infraestructura. Úsala cuando una PR o main estén en rojo, o lleguen correos de fallos de CI.
---
# Diagnosticar la CI

`gh run view --log-failed` a veces no devuelve nada. Lee el job directamente:

```bash
h=$(gh pr view <pr> --json headRefOid --jq .headRefOid)
gh api "repos/{owner}/{repo}/commits/$h/check-runs" --jq '.check_runs[]|select(.conclusion=="failure")|"\(.id) \(.name)"'
gh api repos/{owner}/{repo}/actions/jobs/<id> --jq '.steps[]|select(.conclusion=="failure")|.name'
gh api repos/{owner}/{repo}/actions/jobs/<id>/logs > /tmp/job.log
grep -aE 'E2E FAILED|FAIL |Tests run:.*(Failures|Errors): [1-9]|Caused by:|wget: Failed|Executor jar is missing|AssertionError' /tmp/job.log
```

## Síntomas conocidos

| En el registro | Causa habitual | Qué hacer |
| --- | --- | --- |
| `wget: Failed to fetch ... apache-maven` | Descarga de Maven cortada | Infraestructura. `runtime/mvn-retry.sh` lo reintenta; si persiste, relanza solo ese job |
| `Executor jar is missing classes` | Clase nueva de `core` que usa el ejecutor | Añádela a `runtime/package.sh` |
| `table X has N columns but M values` al reiniciar | `INSERT` sin columnas en el esquema base | Nombra las columnas. Consulta `.claude/rules/persistencia.md` |
| `Out of approved profile scope` | La tarea del doble escribe fuera de `allowed_paths` | Revisa `fake_codex.py` y el perfil de esa fase del e2e |
| `quota_budget_window_mismatch` | El doble cambia la ventana de cuota entre reinicios | `E2E_QUOTA_RESETS` debe mantenerse fija |
| `FAIL ...` en el smoke con `UI smoke FAILED` | Texto distinto o espera corta | Compara con el panel. El panel lee la CI cada 30 s |
| `ENOTEMPTY` tras comprobaciones en verde | Limpieza del perfil de Chrome | Debe solo avisar: es un fallo de la prueba |
| `Tests run` con error solo en `java (25)` | Incompatibilidad real con Java 25 | Consulta `.claude/rules/java.md`. Hasta hoy no ha pasado ninguna vez |

## Antes de relanzar

Relanza solo si el registro demuestra que es infraestructura, y dilo en la PR. Un rojo sin explicar se investiga: repetir hasta que salga verde esconde el fallo. Reproduce los errores reales en local, añade la prueba y publica una corrección.
