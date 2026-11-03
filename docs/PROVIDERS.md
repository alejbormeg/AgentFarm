# Fase 3 — contrato de adaptadores y admisión

Inicio autorizado por el promotor tras fusionar PR #12. Las fases 1 y 2 conservan
sus pendientes. Este es el primer incremento de #9, no el cierre de la fase.

## Contrato implementado
AgentAdapter expone un ID estable, validación de autenticación, un descriptor de capacidades y la normalización
de cuota. La ejecución sigue en el servicio WSL: todavía no se ha extraído todo
su ciclo de vida a una interfaz intercambiable.

El catálogo declara capacidades de **AgentFarm**, no todas las del proveedor.
Codex está integrado mediante app-server: eventos, interrupción y lectura de cuota
de suscripción. La reanudación automática y la verificación independiente figuran
como no implementadas. Claude Code está integrado en modo medido (ver abajo);
nunca se selecciona como fallback.

El normalizador Codex se comparte entre dominio y ejecutor. Usa el bucket codex
de rateLimitsByLimitId cuando existe, o el formato legacy rateLimits cuando no.
No toma cuota de otro bucket. Exige una ventana semanal válida y bloquea ante
lecturas inválidas, límites alcanzados o 98 % consumido. Conserva fecha, fuente
y unidad de suscripción; no convierte la mensualidad en saldo monetario.

Protocolo contrastado con [OpenAI Docs, Codex App Server](https://learn.chatgpt.com/docs/app-server):
account/read identifica la autenticación y account/rateLimits/read devuelve
las ventanas de cuota. Esto no garantiza una lectura disponible para todas las
cuentas. El ejecutor requiere autenticación gestionada ChatGPT y elimina variables
de claves API del proceso hijo; no consume resets, compra créditos ni activa API.

## Admisión y perfiles
POST /api/providers/admission usa proveedor, perfil y cuenta explícitos.
Comprueba el presupuesto real de Clover, identidad de cuenta/proveedor en la
telemetría, ventana de presupuesto coincidente con el reset observado, cuota
reciente, umbral y capacidades. GET /api/providers muestra el
contrato. Ambos exigen autenticación; las mutaciones conservan CSRF. Un nodo
solo puede consultar admisión de la cuenta concedida en su credencial.

La consulta no reserva recursos ni ejecuta inferencia. El ejecutor la usa antes
de reservar trabajo nuevo. Una ejecución con reserva conserva los controles de
cuota, ventana, pausa, identidad e interrupción; no vuelve a exigir que su propio
margen reservado esté libre para otro trabajo.
Es una lectura de admisión, no una promesa de facturación exacta o una reserva
atómica nueva. La parada continúa basada en observaciones discretas.

AGENTFARM_RESOURCE_PROFILE en executor.env (requiere reiniciar el servicio):
- balanced: política actual, sin routing ni cambio automático de modelo.
- economic: misma política actual; optimización aún no implementada.
- reinforced: bloqueado mientras falte el verificador independiente.

Ningún perfil habilita pago adicional. additional_payment=true se bloquea;
las cuentas API no se ejecutan mediante este adaptador de suscripción.
Clover presenta USD de API y porcentaje de suscripción en sus unidades propias.

## Pendientes de #9
Adaptador ejecutable Claude Code y cuota fiable, extracción completa de inicio/
eventos/interrupción/reanudación/artefactos, límites por proyecto y tarea,
plazos y reintentos, planificación de varios ejecutores, perfiles realmente
optimizados y medición de recursos/atención humana por cambio aceptado.
El benchmark común entre proveedores y los criterios de salida siguen pendientes.

## Claude Code (fase 2)
El ejecutor puede usar Claude Code para cualquier rol, configurado por rol y
nunca como sustituto automático de Codex:

```text
AGENTFARM_PROVIDER_RESEARCH=codex
AGENTFARM_PROVIDER_IMPLEMENT=codex
AGENTFARM_PROVIDER_REVIEW=claude-code   # revisión cruzada: implementa Codex, revisa Claude
AGENTFARM_CLAUDE_ACCOUNT=claude-api     # cuenta Clover en modo api (USD)
AGENTFARM_CLAUDE_MODEL=                 # opcional
```

- Ejecución: `claude -p --output-format json` en el checkout, con el prompt por
  stdin. Herramientas cerradas por rol: investigación `Read,Glob,Grep,WebSearch,WebFetch`;
  implementación `Read,Glob,Grep,Edit,Write,MultiEdit` con `acceptEdits`; revisión
  `Read,Glob,Grep`. `Bash` siempre denegado: las pruebas corren en el sandbox.
- Aislamiento: recibe como reglas `Read` y `Edit` denegadas las rutas que el perfil de Codex oculta,
  y `--setting-sources user` ignora los ajustes del repositorio. **Sin probar con una cuenta real**, y
  sin aislamiento del sistema: un fallo de esas reglas deja las credenciales al alcance del agente.
- Credencial: `STATE/anthropic-api-key` (permiso 600), inyectada solo en el
  proceso de Claude Code; nunca en Git, en el panel ni en el sandbox de pruebas.
- Coste: la cuota de suscripción de Claude no es observable, así que solo se
  admite una cuenta Clover `api`. Antes de cada turno se reserva
  `agentfarm.claude-turn-micros` (3 USD por defecto) en cada periodo; al terminar se
  liquida con el `total_cost_usd` que informa Claude Code. Si el turno falla sin
  coste conocido, la reserva queda `unknown` y Clover bloquea más trabajo hasta
  que el propietario concilie.
- La credencial del nodo se emite con `api_account` opcional
  (`POST /api/executors {"node","account","api_account"}`); con ella el nodo solo
  puede leer, reservar y liquidar esa cuenta, nunca configurarla.
- La evidencia registra el proveedor de cada rol. El descriptor declara
  `independent_verification: true`, de modo que el perfil reforzado es admisible
  cuando la revisión la hace un proveedor distinto del implementador.
