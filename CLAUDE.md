@AGENTS.md

## Claude Code

- Las reglas de `.claude/rules/` se cargan solas según el archivo que toques. Son los errores que ya hemos cometido: léelas antes de discutirlas.
- Para trabajar una issue usa la skill `resolver-issue`; para actualizar el panel y el ejecutor de este equipo, `desplegar-local`; para probar en un navegador, `panel-aislado`; si la CI está en rojo, `diagnosticar-ci`.
- Un hook bloquea `git push` hasta que `comprobar.sh` pase sobre ese commit. No lo esquives: si te estorba, cuéntale al propietario por qué.
- El panel de `localhost:8080` y el ejecutor `agentfarm-executor` son la instalación real del propietario, con datos reales y cuota de Codex. No pruebes contra ellos: usa `panel-aislado` o `tests/e2e/run.sh`.
- Fusiona solo si el propietario lo ha autorizado, y siempre con `.claude/skills/resolver-issue/merge-gate.sh`.
