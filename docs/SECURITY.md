# Modelo de amenazas

Qué protege AgentFarm, frente a quién y qué riesgos quedan. Lo ha escrito quien desarrolla el producto,
así que **no sustituye a una revisión independiente**, y el repositorio no debe abrirse sin ella
([#77](https://github.com/alejbormeg/AgentFarm/issues/77)).

## Qué hay que proteger

| Activo | Dónde está | Si se pierde |
| --- | --- | --- |
| Credencial de GitHub del panel | Volumen del panel | Fusionar y escribir en los repositorios a su alcance |
| Sesión de `gh` y de Codex | Home del usuario del ejecutor. Con una GitHub App en el panel, el ejecutor no necesita sesión de `gh`: recibe por orden un token de una hora limitado a ese repositorio. Solo probado con dobles | Publicar como esa persona y gastar su cuota |
| Token del nodo | `AGENTFARM_STATE/node-token` | Reclamar trabajo e informar como ese nodo, sin poder fusionar |
| Contraseña del panel y claves de segundo factor | Volumen del panel y sus copias | Aprobar e integrar como esa persona |
| Docker del ejecutor | Socket del equipo | Equivale a root en el equipo |
| Código de otros proyectos | Home del usuario del ejecutor | Filtrarlo a través de lo que lea un agente |

## Amenazas

La hipótesis central es que **un agente puede estar comprometido**. Lee repositorios, issues y páginas web
que cualquiera puede manipular, y esa inyección de instrucciones **solo se puede limitar, no eliminar**.

| # | Amenaza | Qué la limita hoy | Lo que queda |
| --- | --- | --- | --- |
| T1 | Instrucciones inyectadas desde el repositorio, una issue o una web | Propuesta aprobada por una persona, revisión independiente, búsqueda web solo al investigar | El agente puede escribir código malicioso que parezca correcto. Solo lo paran la revisión y las pruebas |
| T2 | Un agente lee credenciales | `AgentSandbox`: perfil de Codex con red apagada, comprobado por el ejecutor antes de reclamar trabajo | Es una lista de exclusión: el resto de la home sigue legible ([#72](https://github.com/alejbormeg/AgentFarm/issues/72)). Con Claude Code solo hay reglas sin probar ([#75](https://github.com/alejbormeg/AgentFarm/issues/75)) |
| T3 | Un agente saca datos fuera | Red apagada en los turnos | Lo que lee llega al proveedor del modelo. La búsqueda web de la investigación puede filtrar datos en sus consultas |
| T4 | El código de una tarea ataca durante sus pruebas | Contenedor efímero sin credenciales, sin `.git`, con límites de CPU, memoria y tiempo | Una fuga del contenedor. La fase `setup` tiene red |
| T5 | Un repositorio trae su propia configuración | Las reglas salen de la rama base. Un repositorio no aporta skills y Claude Code ignora sus ajustes | El `AGENTS.md` o `CLAUDE.md` del repositorio llega al agente como instrucciones |
| T6 | Se integra algo distinto de lo revisado | Integración por SHA con CI en verde en ese commit y los Siete Mandamientos | Quien tenga la credencial de GitHub puede fusionar fuera del panel. El panel lo marca, pero no lo impide |
| T7 | Alguien entra en el panel | Solo en `127.0.0.1` por defecto, CSRF, roles, segundo factor opcional y espera tras 5 contraseñas fallidas, de 1 a 15 minutos, por cuenta y dirección. Política de contenido estricta: solo se carga lo que sirve el panel. Con `AGENTFARM_PANEL_URL` en https, la cookie es `Secure` | Sin segundo factor, la contraseña lo abre todo. Exponerlo en una red es decisión del operador |
| T8 | Se compromete el ejecutor | El nodo no puede fusionar, y sus credenciales se revocan una a una | Corre con la identidad del operador y con acceso a Docker. **Es el mayor riesgo actual** |
| T9 | Un secreto llega a un registro o a la evidencia | `TelemetryService.clean` y `SecretScan` | Solo detectan formatos conocidos |
| T10 | Se gasta la cuota | Clover detiene el trabajo con la cuota desconocida o al llegar al límite | Las lecturas son periódicas: puede pasarse un poco del límite |

## Docker en el ejecutor

Las pruebas corren en contenedores, así que el ejecutor necesita Docker. Quien alcanza el socket normal puede
montar `/` en un contenedor y es root en el equipo ([#73](https://github.com/alejbormeg/AgentFarm/issues/73)).

| Opción | A favor | En contra |
| --- | --- | --- |
| Socket normal (hoy) | Funciona sin configuración | Un ejecutor comprometido es root |
| Proxy que filtra el socket | No cambia el demonio | Filtrar rutas no basta: habría que validar cada `docker create` (`Privileged`, `Binds`, `CapAdd`, `PidMode`…), y un descuido en ese código da root |
| **Docker sin root** | El demonio corre como un usuario sin privilegios: escapar no da más que ese usuario. Lo mantiene Docker | Requiere `uidmap` y adaptar los permisos del workspace, y hay que comprobar los límites de CPU y memoria |

**Decisión: Docker sin root, con el [usuario propio](https://github.com/alejbormeg/AgentFarm/issues/72).** Lo
importante es que un fallo no dé más privilegios que ese usuario, y un proxy propio concentraría el riesgo en
código nuestro. **Aún no está implementado ni probado**: `TestSandbox` cambia el dueño de los archivos con el
UID del equipo, y sin root ese UID es otro dentro del contenedor.

## Si crees que algo se ha comprometido

1. Pausa los agentes en **Gobierno**.
2. Revoca el token del nodo, la credencial de GitHub del panel, y las sesiones de `gh` y de Codex.
3. Cambia la contraseña del panel y restablece el segundo factor con `./agentfarm 2fa-reset <usuario>`.
4. Revisa la auditoría del panel y lo publicado en GitHub.
