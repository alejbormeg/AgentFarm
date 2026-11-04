# Pilotos reales

El e2e usa agentes y GitHub simulados. El primer piloto completo sobre un repositorio real encontró
en una mañana fallos que el e2e no veía, así que repite un piloto:

- antes de cada versión;
- después de cambiar prompts, recetas o el ejecutor;
- al actualizar Codex CLI, porque el aislamiento depende de sus perfiles de permisos.

## Preparación
1. Usa un repositorio privado desechable, con algo de código y pruebas. No uses un producto real.
2. Actualiza panel y ejecutor al mismo commit. El panel avisa si difieren.
3. Comprueba el entorno:

```bash
./agentfarm doctor
./agentfarm github-check owner/repo-de-pilotos
bash tests/codex_sandbox.sh
```

## Recorrido
Sigue el flujo solo desde el panel. **No fusiones con `gh` ni con la web**: saltarte el panel deja sin
probar justo el último tramo.

1. Adopta el repositorio y aprueba su propuesta.
2. Pide una corrección sobre el candidato.
3. Integra la adopción por SHA.
4. Lanza una tarea con cada receta que hayas cambiado, y otra sobre una rama que haya quedado desactualizada.
5. Integra al menos una de ellas por SHA.

## Probar agentes reales sin tocar GitHub
`tests/eval/run.sh` usa Codex real y GitHub simulado, así que no necesita credencial ni repositorios. Ejecútalo al
cambiar prompts o recetas: cada ronda de tres tareas tarda unos 7 minutos y gasta menos del 1 % de cuota semanal.

```bash
EVAL_CODEX=codex bash tests/eval/run.sh on
EVAL_CODEX=codex EVAL_NO_PROFILE=1 EVAL_TASKS=tests/eval/adopt.json EVAL_LABEL=adopt-code bash tests/eval/run.sh on
EVAL_CODEX=codex EVAL_NO_PROFILE=1 EVAL_TASKS=tests/eval/adopt.json EVAL_LABEL=adopt-empty EVAL_FIXTURE=tests/eval/fixture-empty bash tests/eval/run.sh on
```

Los resultados quedan en `tests/eval/results/`. La primera vez encontró tres fallos de adopción que el e2e no veía.

## Lanzarlo con una orden
`tests/pilot/run.py` recorre el flujo desde el panel y escribe las medidas en JSON. **Gasta cuota**: se detiene
al llegar a `--max-quota` (90 % semanal por defecto) y no lanza nada si no puede leerla. Termina con error si
una tarea se bloquea o llega a revisión sin pasar sus evidencias, para que un fallo no parezca un éxito.

```bash
python3 tests/pilot/run.py --repo owner/repo-de-pilotos --password-file ~/.config/agentfarm-password \
  --request "Corrige un fallo concreto" --merge
```

Aprueba las propuestas tal cual y **no decide por ti**: si una tarea se bloquea, lo informa y termina con error, y
nunca reescribe un criterio. Sin `--merge` se queda en la revisión. Una cuenta con segundo factor no sirve, porque
usa HTTP Basic. No mide las correcciones, que piden criterio humano: el paso 2 del recorrido sigue siendo manual.

## Qué medir
Anótalo en el `CHANGELOG.md` junto con la versión probada:

| Medida | Objetivo |
| --- | --- |
| Intervenciones humanas entre propuesta e integración, sin contar las aprobaciones | 0 |
| Tareas que llegan a integrarse sin bloquearse | Todas |
| Fallos que el e2e no detectó | 0; cada uno se convierte en una prueba |
| Cuota consumida por tarea integrada | Sin subidas inexplicadas |

Cada fallo se resuelve como corrección de producto, con su prueba. Reescribir un criterio a mano para
que la tarea pase cuenta como intervención, y hay que anotarlo.

## Credenciales
Revoca al terminar cualquier credencial creada para el piloto. Usa una GitHub App limitada al
repositorio de pilotos, y nunca pegues un secreto en un chat con un agente.
