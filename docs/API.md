# API local de gobierno

Todas las rutas requieren autenticación, salvo `GET /api/health`. El panel usa esta misma API.
Además de las cuentas de personas (propietario, revisor, observador) hay dos credenciales
`Bearer` revocables: la del ejecutor (`afn_…`, solo sus rutas de trabajo) y la de un cliente MCP
(`afr_…`, creada con `./agentfarm mcp-token`), que solo lee tareas y crea peticiones.

GET /api/csrf entrega el token CSRF y el nombre de su cabecera. Un cliente de
la API debe mantener la cookie de sesión y enviar ese token en los POST.
La autenticación admite la sesión de formulario o HTTP Basic. No colocar
contraseñas en argumentos compartidos, logs o Git.

| Ruta | Uso |
| --- | --- |
| GET /api/overview | Admisión, nodos, trabajadores, tareas y cuentas. |
| GET /api/nodes, /api/workers | Registros y estados declarados con caducidad. |
| POST /api/nodes | Registrar id y role (napoleon/snowball). |
| POST /api/workers | Registrar id y node. |
| POST /api/heartbeat/{kind}/{id} | Declarar state: active, idle, busy o error. kind: node/worker. |
| POST /api/entities/{kind}/{id}/enabled/{boolean} | Habilitar/deshabilitar admisión. |
| GET /api/tasks, /api/runs | Tareas e informes. |
| POST /api/tasks | Encolar TaskSpec JSON. |
| POST /api/simulate | Simular una tarea, sin proveedor. |
| POST /api/assign | project, id, worker, account, run_id y amounts por ventana. |
| POST /api/tasks/{project}/{id}/progress | worker y progress 0..100. |
| POST /api/tasks/{project}/{id}/cancel | Cancelar ready o solicitar para assigned/running. |
| POST /api/governance/paused/{boolean} | Pausa/reanudación global. |
| GET /api/work?limit=&offset= | Resúmenes de tareas, de la más reciente a la más antigua, sin propuesta, evidencia ni petición. `limit` hasta 1000; 0 o ausente devuelve todas. |
| GET /api/work/{id} | Una tarea entera: propuesta, evidencia y petición incluidas. |
| GET /api/audit | Últimas 200 acciones de gobierno. |
| GET /api/requesters | Propietario: credenciales de clientes MCP, sin secreto, con su último uso. |
| POST /api/requesters/{id}/revoke | Propietario: revocar una credencial de cliente MCP. |
| GET /api/budget/{account} | Estado del presupuesto. |
| POST /api/budget/{operation} | configure, open-window, observe, reserve, settle. |

Los contratos del presupuesto y sus unidades siguen en BUDGET.md. Nunca enviar
un porcentaje de suscripción como si fueran USD. Una asignación no ejecuta
un modelo; un reporte de progreso no verifica implementación. No hay endpoint
para fusionar cambios ni para aceptar evidencias todavía.

La infraestructura de identidad y comunicación remota se implementará después.
La consola actual no debe exponerse fuera del host local.
