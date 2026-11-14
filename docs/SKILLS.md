# Skills de los agentes

Una skill es una guía especializada: cómo diseñar pruebas, cómo revisar código o cómo se
escribe Python mantenible. El catálogo está en `src/main/resources/skills/`.

## Cómo se aplican

AgentFarm las elige por **rol** y por **tecnología** y las escribe en el prompt del turno. No
depende de que el modelo decida usarlas, y funciona igual con Codex y con Claude Code.

| Skill | Investigar | Implementar | Revisar | Tecnología |
| --- | --- | --- | --- | --- |
| `software-design` | ✓ | | | Todas |
| `change-discipline` | | ✓ | | Todas |
| `test-design` | ✓ | ✓ | ✓ | Todas |
| `secure-coding` | | ✓ | ✓ | Todas |
| `code-review` | | | ✓ | Todas |
| `java-spring`, `python`, `node-typescript`, `go` | | ✓ | ✓ | Según el repositorio |
| `web-ui` | | ✓ | ✓ | Si hay archivos de interfaz |

La tecnología se detecta por archivos conocidos (`pom.xml`, `pyproject.toml`, `package.json`,
`go.mod`, `.html`, `.tsx`…), sin ejecutar nada del repositorio. La evidencia de cada candidato
guarda identificador, versión y hash de las skills de cada turno, y el panel las muestra al abrir
la tarea. Como las recetas, las versiones publicadas no se editan: se añade una nueva.

## Añadir las tuyas

Un repositorio no puede traer sus propias skills, porque sus archivos los escriben los mismos
agentes a los que obligan. Solo puede nombrarlas en `agentfarm.yaml`:

```yaml
skills: [python, house-style]
```

Cada nombre es del catálogo o una skill instalada por el operador del ejecutor en
`AGENTFARM_STATE/skills/<nombre>/SKILL.md` (máximo 32 KiB). Un nombre desconocido es un error.
El contenido de esas skills es texto: no se ejecuta nada.

## Instrucciones y skills por repositorio (panel)

En **Agentes → Instrucciones por repositorio**, el propietario elige para cada repositorio el system
message de Napoleon, Boxer y Benjamin (hay uno por defecto, editable y restaurable) y sube skills
`.md` propias, activables por rol. Se guardan en el panel, no en el repositorio, por la misma razón
de arriba: los archivos del repositorio los escriben los agentes. Llegan al ejecutor con la orden,
se escriben en el prompt del turno con la nota de que la petición, el alcance aprobado y las reglas
de AgentFarm prevalecen, y la evidencia registra su hash. No conceden permisos. El mismo perfil se
descarga como `CLAUDE.md`, `AGENTS.md` (ChatGPT/Codex) o `GEMINI.md`.

## Comprobar si ayudan

Usar una skill no garantiza nada: lo que cuenta es el resultado. `tests/eval/run.sh` ejecuta tres
tareas de referencia con y sin skills sobre un proyecto de ejemplo, con el GitHub simulado pero
con el agente real, y por eso **gasta cuota**:

```bash
EVAL_CODEX=codex bash tests/eval/run.sh on
EVAL_CODEX=codex bash tests/eval/run.sh off
python3 tests/eval/compare.py
```

La comparación muestra pruebas, revisión, una puntuación de mutación (cuántos fallos introducidos
detectan las pruebas), tamaño del cambio y hallazgos. Tres tareas son una señal, no una estadística:
conviene repetirlas antes de sacar conclusiones. En cualquier ejecutor, `-Dagentfarm.skills=off`
desactiva las skills y la evidencia lo registra.

### Resultado con Codex real (3 de noviembre de 2026)

Dos rondas de las tres tareas, con y sin skills: 12 tareas en total, unos 14 minutos y un 1 % de cuota semanal por ronda.

| Medida | Con skills | Sin skills |
| --- | --- | --- |
| Llegan a revisión con pruebas en verde | 6 de 6 | 6 de 6 |
| Revisión sin bloqueos | 6 de 6 | 6 de 6 |
| Mutación por tarea (cupón, impuestos, pruebas) | 66,7 / 100 / 100 | 66,7 / 100 / 100 |
| Criterios por propuesta, de media | 5,7 | 4,7 |
| Longitud de los hallazgos de la revisión, de media | 516 caracteres | 397 caracteres |

**No muestra que las skills mejoren el resultado, ni que sobren.** Con skills las propuestas son algo más detalladas, pero ambas variantes resuelven todo y sacan la misma mutación: estas tareas son demasiado fáciles para distinguirlas. Por eso no se retira ninguna. Para decidirlo hacen falta tareas más difíciles, en un proyecto más grande. Más caracteres no significan mejor revisión.
