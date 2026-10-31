# AgentFarm

Equipo de agentes de desarrollo con autonomía acotada, especificaciones versionadas y control humano verificable. Forja será el primer caso de aplicación; el núcleo no depende de Forja.

La identidad de AgentFarm se inspira en *Animal Farm*, de George Orwell:

| Rol | Nombre en AgentFarm | Responsabilidad prevista |
| --- | --- | --- |
| Trabajador | **Boxer** | Ejecutar tareas acotadas. Máxima: «Trabajaré más duro». |
| Dirección de un único nodo | **Napoleon** | Planificar y coordinar sus Boxer dentro de las políticas aprobadas. |
| Dirección de varios nodos | **Napoleon y Snowball** | Contrastar propuestas y alcanzar acuerdos entre dirigentes pares. |

Los roles son independientes del proveedor de IA y de la máquina que los aloje.
La dirección, los trabajadores reales y los acuerdos entre nodos están
**planificados**; el simulador actual no los ejecuta. El humano conserva la
aprobación final. Véase el [modelo de roles y topologías](docs/ANIMAL_FARM.md).

**Estado: simulador de coordinación, control de alcance y registro local de presupuesto.** No ejecuta modelos, modifica proyectos, abre PR ni despliega infraestructura. La arquitectura objetivo está en [docs/PLAN.md](docs/PLAN.md).

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

## Validar el alcance de un cambio

Requiere Git en el PATH. El comando lee dos commits existentes sin modificar el
repositorio ni la base de datos del simulador:

```bash
python3 scope_check.py --repo /ruta/al/proyecto --spec examples/task.json --base SHA_BASE_COMPLETO --candidate SHA_CANDIDATO_COMPLETO
```

Sustituye ambos SHA por identificadores completos de commits; el candidato debe
descender de la base. La especificación usa el formato del simulador:
`allowed_paths: ["docs/"]` permite archivos bajo esa carpeta, mientras que
`["docs/a.md"]` solo permite ese archivo. Las rutas son literales, sin glob.

El control detecta altas, bajas, renombrados (baja + alta) y cambios de modo.
Bloquea cambios fuera de alcance, enlaces simbólicos, submódulos y las rutas
protegidas `AGENTS.md`, `.github/`, `.agentfarm/` y `.git/`, aunque se permitan
en la tarea. El informe JSON incluye ambos commits, hash de especificación,
versión de política y motivos de bloqueo.

Código de salida: **0** para cambios dentro de alcance, **1** para bloqueo o
ausencia de cambios y **2** para entradas inválidas o fallos de Git.
`passed` solo acredita alcance: no valida comportamiento ni representa aprobación
humana. Los cambios locales, ignorados y no registrados quedan fuera del informe.
No sustituye al aislamiento. Consulta la [especificación M1-001](docs/tasks/M1-001.md).

La suite se ejecuta en CI con Linux/Windows y Python 3.11/3.13.

## Clover: control de presupuesto

La política de ejemplo es **como máximo el 50% de la cuota semanal de Codex**.
El precio mensual de la suscripción no se convierte en saldo de API. Para API
se configuran presupuestos monetarios independientes.

```bash
python3 budget.py --db budget.db configure examples/budget-subscription.json
python3 budget.py --db budget.db status codex-subscription
```

El registro reserva capacidad de forma atómica para todos los Boxer de la
cuenta. Se pausa si falta saldo o una lectura vigente, el coste es desconocido
o un trabajo cruza un reinicio. Sin ventanas observadas, el ejemplo permanece
en pausa. Todavía no consulta la cuenta real ni limita turnos en el proveedor.
Consulta [unidades, reservas y límites de esta versión](docs/BUDGET.md).

## Qué viene después

1. Aprobar especificaciones y modelo de amenazas.
2. Implementar aislamiento y un adaptador real de Codex, después Claude.
3. Validación del diff, pruebas y revisión independiente.
4. GitHub y aprobación humana vinculada al commit exacto.
5. Vistas As-Is, To-Be y transición con evidencias.

No hay fusión automática. El proyecto no se atribuye resultados todavía no medidos.
