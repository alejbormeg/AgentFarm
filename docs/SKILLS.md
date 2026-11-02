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

Todavía no se ha ejecutado esta comparación con un agente real.
