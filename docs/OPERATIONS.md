# Operación en WSL y trazabilidad GitHub

El panel y el ejecutor WSL son procesos separados. El panel no monta el
socket Docker ni las credenciales GitHub. El ejecutor usa Codex app-server y
GitHub CLI autenticados en la cuenta Linux.

## Uso desde el panel

1. Pulsa **Nueva tarea** en cualquier vista, indica `owner/repository`,
   título y objetivo. **Crear issue e investigar** encola una orden persistente.
   Hasta que GitHub confirme la creación, se muestra explícitamente pendiente.
2. Napoleon inspecciona el checkout y usa búsqueda web cuando es necesaria. Es el único turno con búsqueda web: los de implementación y revisión la tienen apagada.
   La issue contiene fuentes, alcance, criterios, diagramas y comandos de tests.
3. Revisa el alcance y los comandos (incluida instalación de dependencias).
   **Implementar propuesta vN** aprueba esa versión. **Pedir correcciones**
   permite reformular una propuesta sin autorizar implementación.
4. Boxer trabaja en `agentfarm/<issue>-<titulo>`. Se ejecutan unitarias e
   integración, después una revisión Codex separada en modo de solo lectura.
   El ejecutor escribe un workflow con los comandos aprobados y publica la PR.
   Si hay fallos, la PR queda en borrador.
5. Revisa enlaces y evidencias. **Aprobar e integrar este commit** está al principio de la tarea, muestra el SHA y exige
   confirmación explícita. El panel vuelve a consultar GitHub: SHA, draft,
   checks esperados, estados CI y revisiones con cambios solicitados. Solo
   registra integración después de la respuesta positiva de GitHub.

Los comandos de test no son una prueba matemática de calidad. Se conservan su
salida, resultado y SHA para revisión humana. Una excepción N/A requiere motivo
visible en la propuesta aprobada. Una PR integrada no implica despliegue.

## Instalación del ejecutor local

Requisitos en WSL Ubuntu 24.04: Java 21, Git, Codex CLI y GitHub CLI.
Autenticar Codex con la cuenta de suscripción y GitHub mediante:

```bash
gh auth login --hostname github.com --git-protocol https --web
gh auth refresh --hostname github.com --scopes workflow
gh auth setup-git
bash runtime/install-service.sh /ruta/absoluta/executor.env
systemctl --user status agentfarm-executor
```

[Instalación](INSTALLATION.md) explica `executor.env` y la credencial del nodo.
El servicio instalado arranca con el gestor de usuario de WSL; WSL debe estar
encendida. El panel conserva Compose como instalación principal y JAR como
alternativa. Este ejecutor sigue siendo local, con un único operador y un único
nodo; no representa autenticación distribuida ni aislamiento multiusuario.

## Señales y presupuesto

Telemetría/heartbeats cada 2 segundos; sin señal durante 12 segundos el estado
caduca. El PID de app-server en reposo no se anuncia como inferencia activa.
La cuota se consulta sin inferencia cada 30 segundos en reposo y cada 5 en una
orden. Una lectura con más de 60 segundos no se muestra como saldo actual.

Clover conserva cuota observada y reservas separadas. Las ventanas nuevas
del proveedor se concilian sin borrar el historial y sin reservas pendientes.
La liquidación por contador acumulativo libera una reserva sin sumar otra vez
el consumo ya observado. La parada al 2% usa comprobaciones discretas: una
operación en curso puede saltar el umbral entre lecturas. No hay recarga ni
fallback API.

## Recuperación

Cada petición tiene ID estable; cada orden tiene propietario y heartbeat.
La pérdida de una orden durante más de 120 segundos la bloquea para revisión.
No se relanza automáticamente un turno de inferencia. Reintentar conserva
issue, rama, candidato y evidencias disponibles; las PR se reconcilian por rama.
Las reservas se registran en un diario local y solo se liberan con observación
vigente de su ventana, una vez terminado el proceso.

Los cambios de aprobación, cancelación y publicación se auditan. El botón
Reintentar no aprueba fusiones. Una integración fallida vuelve a revisión y
requiere otro clic sobre el SHA. Los errores de GitHub se muestran en la tarea.

## Interfaces

- `GET/POST /api/work`: peticiones y estado.
- `POST /api/work/{id}/actions/{implement|correct|retry|cancel|merge}`.
- `POST /api/work/import-candidate`: candidato existente, aprobado por operador,
  con base, SHA, issue y estrategia de pruebas; vuelve a probarse y revisarse.
- `POST /api/work/agent/claim` y `/agent/commands/{id}`: órdenes y resultados.
- `GET /api/runtime`: señal y cuota con frescura calculada en servidor.
- `POST /api/budget/rollover` y `/settle-observed`: conciliación de suscripción.
- `GET /api/budget/{account}/reservations`: historial de reservas.

Estas API requieren la sesión/credencial del operador; las mutaciones requieren
CSRF. El nombre del agente identifica al proceso, no constituye una identidad
criptográfica independiente. Los antiguos endpoints de simulación siguen
disponibles como compatibilidad, separados del nuevo flujo de ejecución.

## Dependencias visuales

Mermaid 11.12.0 se sirve localmente, con `securityLevel: strict` y licencia MIT. Las propuestas nuevas (`research.v7`) traen sus diagramas como grafos tipados que el panel dibuja con iconos y que se descargan como `.drawio`; Mermaid se conserva para las propuestas anteriores y para el cuerpo de las issues y las PR.
No se envían los diagramas a un servicio externo. La interfaz son módulos propios servidos por el panel, sin recursos externos.

## Empezar con issues abiertas

Tras adoptar un repositorio, **Traer issues** (barra superior) lista sus issues abiertas. Marca las
que quieras y pulsa «Empezar con N»: Napoleon investiga cada una y te propone un plan; hasta que lo
apruebes no se implementa nada. Las que ya tienen tarea salen marcadas y no se pueden repetir; las
que abre AgentFarm para seguir sus tareas no se ofrecen. El disparador `labeled_issue` (etiqueta
`agentfarm`) sigue funcionando y comparte la misma clave, así que nunca abre dos tareas por la
misma issue. Al integrar la PR de una tarea nacida de una issue, esta se cierra con `Closes #N`.

## Autonomía hasta la PR

Por defecto el plan de Napoleon espera tu aprobación. Para que una tarea trabaje sola hasta la PR
márcala al crearla («Que trabajen de forma autónoma hasta la PR» en Nueva tarea o en Traer issues)
o activa la autonomía de un repositorio en **Gobierno → Autonomía hasta la PR**. Entonces:

- El plan se aprueba solo si las pruebas corren en el contenedor, el plan trae criterios, el panel
  no está pausado y no se ha llegado al tope diario del repositorio (10 por defecto). Los criterios
  manuales se te enseñan en la PR.
- El alcance es el del perfil aprobado del repositorio (`allowed_paths`): la autonomía no lo amplía.
- Si el resultado no pasa (pruebas, revisión de Benjamin o mandamientos), se hace **una** ronda de
  corrección con lo que falló y, si sigue mal, la tarea espera en «Resultado disponible».
- **Integrar sigue siendo tuyo.** Nunca se autoaprueba una adopción ni la memoria del proyecto.

Elegir una issue de una persona sin rol en el repositorio es tu decisión: su texto guiará al agente,
y por eso la confirmación lo avisa. Cada aprobación automática queda en la auditoría como
`policy:autopilot`.

## Modelos por agente

En **Agentes → Proveedores**, cada rol (Napoleon, Boxer, Benjamin) de cada nodo tiene un campo
«Modelo». Se sugieren los que el ejecutor ve en su suscripción (Codex los pide a su app-server; con
Antigravity, `agy models`; Claude no puede listarlos y se sugieren los actuales); también puedes
escribir otro identificador (minúsculas, números, puntos y guiones). Se guarda en el panel, viaja con
la orden y se aplica en el siguiente turno; vacío es el modelo por defecto del proveedor. El
modelo que se usó queda en la evidencia de la tarea. Elegir un modelo no cambia lo que cuesta
la suscripción: un modelo más potente consume más cuota.
