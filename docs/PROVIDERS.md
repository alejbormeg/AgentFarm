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
como no implementadas. Claude Code figura como no integrado y cuota desconocida:
no se instala ni ejecuta, no se simula su consumo y no se selecciona como fallback.

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
telemetría, cuota reciente, umbral y capacidades. GET /api/providers muestra el
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
