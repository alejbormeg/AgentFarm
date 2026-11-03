# Operación en WSL y trazabilidad GitHub

El panel Mateu y el ejecutor WSL son procesos separados. El panel no monta el
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
5. Revisa enlaces y evidencias. **Aprobar e integrar** muestra el SHA y exige
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

Mermaid 11.12.0 se sirve localmente, con `securityLevel: strict` y licencia MIT.
No se envían los diagramas a un servicio externo. La UI usa la shell Mateu y
un componente web servido por Spring; no requiere compilar Node al instalar.
