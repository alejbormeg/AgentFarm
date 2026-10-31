# AgentFarm

Equipo de agentes de desarrollo con autonomía acotada, especificaciones versionadas y control humano verificable. Forja será el primer caso de aplicación; el núcleo no depende de Forja.

**Estado: prototipo local de coordinación en simulación.** No ejecuta modelos, modifica proyectos, abre PR ni despliega infraestructura. No se ha instalado en el portátil del usuario. La arquitectura objetivo está en [docs/PLAN.md](docs/PLAN.md).

## Primera ejecución (Python 3.11+, sin dependencias)

```bash
python3 -m unittest discover -s tests -v
python3 coordinator.py --db state.db enqueue examples/task.json
python3 coordinator.py --db state.db simulate
python3 coordinator.py --db state.db status
python3 coordinator.py --db state.db report > report.json
```

El resultado `review` significa **simulación pendiente de revisión**, nunca código implementado. No contiene evidencias de implementación. Repetir una tarea idéntica no la duplica; reutilizar su ID con otro contenido se rechaza. Una tarea en ejecución interrumpida requiere reconciliación manual; no se vuelve a ejecutar a ciegas.

Variables opcionales: `ACP_MAX_PENDING=2`, `ACP_MAX_DAILY_RUNS=5`. Son límites de admisión de trabajo simulado, no un control de facturación de proveedores.

## Qué viene después

1. Aprobar especificaciones y modelo de amenazas.
2. Implementar aislamiento y un adaptador real de Codex, después Claude.
3. Validación del diff, pruebas y revisión independiente.
4. GitHub y aprobación humana vinculada al commit exacto.
5. Vistas As-Is, To-Be y transición con evidencias.

No hay fusión automática. El proyecto no se atribuye resultados todavía no medidos.
