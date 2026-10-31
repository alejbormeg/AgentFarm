# API local de gobierno

Todas las rutas requieren autenticación del operador. No existen todavía
credenciales por nodo ni permisos distintos para un trabajador externo.
El panel Mateu utiliza los mismos servicios de dominio.

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
| GET /api/audit | Últimas 200 acciones de gobierno. |
| GET /api/budget/{account} | Estado del presupuesto. |
| POST /api/budget/{operation} | configure, open-window, observe, reserve, settle. |

Los contratos del presupuesto y sus unidades siguen en BUDGET.md. Nunca enviar
un porcentaje de suscripción como si fueran USD. Una asignación no ejecuta
un modelo; un reporte de progreso no verifica implementación. No hay endpoint
para fusionar cambios ni para aceptar evidencias todavía.

La infraestructura de identidad y comunicación remota se implementará después.
La consola actual no debe exponerse fuera del host local.
