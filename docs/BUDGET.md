# Clover: presupuesto de admisión

Decisión del promotor: AgentFarm podrá usar **como máximo el 50% de la cuota
semanal de Codex**. La mensualidad declarada de 20 USD es el precio de su
suscripción; no se convierte en saldo de API ni en 10 USD semanales.

`BudgetService` implementa un registro local persistente de presupuestos y reservas.
Todavía no hay un trabajador real ni un lector automático de cuotas conectado.
Este incremento permite probar la decisión de admisión sin gastar dinero.

## API y suscripción son cuentas diferentes

| Modalidad | Unidad | Ejemplo |
| --- | --- | --- |
| `subscription` | Puntos básicos de cada cuota (10000 = 100%) | Tope semanal de 5000 = 50%. |
| `api` | Millonésimas de USD (1000000 = 1 USD) | Presupuesto mensual de 20000000 = 20 USD. |

El precio de una suscripción nunca entra en la aritmética de cuota. El uso real
depende del plan, modelo y tarea. Los límites y sus reinicios deben proceder
del proveedor, no de dividir la mensualidad entre cuatro semanas.
Fuente: [OpenAI Docs: pricing y límites](https://learn.chatgpt.com/docs/pricing).

La política del usuario está en [budget-subscription.json](../examples/budget-subscription.json).
La intención «trabajad hasta el 50% semanal» se representa mediante
`shares.weekly = 5000`. Esto todavía no es un intérprete de lenguaje natural.

El ejemplo [budget-api.json](../examples/budget-api.json) muestra otra cuenta,
sin asociarla a la suscripción del usuario. Para API, el mismo coste se carga
a todos los periodos configurados: un límite semanal nunca elimina el mensual.

## Regla de admisión

Para cada ventana activa:

`tope = total_de_la_ventana × porcentaje_permitido / 100`

`disponible = máximo(0, tope − consumo_contabilizado − reservas_pendientes)`

La tarea solo recibe una reserva si cabe en **todas** las ventanas y las
observaciones están vigentes. Con una cuota semanal normalizada a 10000, tope
5000, consumo observado de 2000 y reservas de 1000, quedan 2000 puntos (20% de
la cuota semanal completa). El tope incluye el consumo observado de toda la
cuenta, también el uso personal externo a AgentFarm. Es una decisión conservadora
para reservar margen al usuario, no una cuota adicional al consumo personal.

Napoleon y Snowball deben utilizar el mismo registro por cuenta. No se asigna
un 50% independiente a cada nodo o Boxer. El SQLite de este incremento se
comparte entre procesos de un único host; la coordinación entre hosts queda
pendiente y no debe simularse copiando el archivo o usando un SQLite por nodo.

## Ciclo y persistencia

1. `configure` registra cuenta, modalidad y porcentajes. La configuración
   existente es inmutable en esta versión; repetirla es idempotente. No borrar
   el registro ni crear alias de la misma cuenta para eludir consumo acumulado.
2. `open-window` registra un periodo con `start`/`end` Unix UTC, total y una
   observación opcional. Debe usar el reinicio real del proveedor, que no tiene
   por qué coincidir con el lunes o con el primer día del mes. No se permiten
   ventanas solapadas del mismo periodo ni reemplazar una ventana vigente.
3. `observe` recibe consumo acumulado y su instante de medición. No permite
   retroceder tiempo, disminuir consumo o aportar fechas futuras. La observación
   inicial identifica su fuente (`manual` o `provider`); esa etiqueta declara
   procedencia, no verifica autenticidad. La lectura automática no está implementada.
4. `reserve` bloquea capacidad atómicamente (`BEGIN IMMEDIATE`) antes de admitir
   trabajo. Requiere un ID único y cotas en cada ventana. Una repetición devuelve
   `duplicate`: **no autoriza ejecutar de nuevo**. Otro contenido con ese ID falla.
5. `settle` registra consumo real y libera solo la parte no utilizada de la
   reserva. Es idempotente; una liquidación no se puede reescribir. Una cancelación
   solo liquida cero si se ha verificado que no consumió proveedor.
6. `status` informa modalidad, unidad, topes, consumo, reservas, disponible,
   reinicios y motivos de pausa.

Las operaciones financieras del registro se realizan en transacciones. Las
reservas sobreviven a un reinicio y no caducan automáticamente. La caducidad
de una ventana no convierte un trabajo pendiente en gratuito.

## Cuándo se pausa

- Sin observación o con observación caducada (300 segundos por defecto).
- Sin alguna ventana activa requerida, o al alcanzar su tope.
- Si la reserva pedida supera el disponible de cualquier ventana.
- Con un coste final desconocido: se conserva la reserva hasta reconciliarlo.
- Si el consumo real supera la reserva: se registra el gasto y se bloquea la cuenta.
- Si un trabajo cruza un reinicio: requiere reconciliar la atribución por periodo;
  no puede aprovechar el reinicio para consumir dos veces el mismo margen.

Los casos `overrun` y `crossed_reset` quedan bloqueados para intervención humana;
esta versión no incluye un comando que los desbloquee. El coste `unknown` sí
puede reconciliarse con `settle` cuando se conozca el consumo. No hay recarga,
compra de créditos, cambio de cuenta ni fallback automático a API de pago.

Las observaciones nunca reducen consumo ya contabilizado. Se toma el máximo
del contador local y el consumo acumulado recibido, manteniendo todas las
reservas abiertas; cada liquidación añade consumo real. Si una observación ya
incluía trabajo que aún estaba pendiente de liquidar, puede contarse dos veces.
Ese exceso conservador puede detener trabajo antes del tope. La correlación
exacta con registros del proveedor sigue pendiente.

## Uso de la herramienta

```bash
java -jar agentfarm.jar budget --db budget.db configure examples/budget-subscription.json
java -jar agentfarm.jar budget --db budget.db status codex-subscription
```

Sin una ventana y una observación vigentes, `status` devuelve `paused`.

Los demás comandos reciben un objeto JSON en un archivo:

| Comando | Campos del objeto |
| --- | --- |
| `open-window archivo.json` | `account`, `period`, `start`, `end`, `total`; opcionales `used`, `observed_at`, `max_age`, `source`. |
| `observe archivo.json` | `account`, `period`, `start`, `used`, `observed_at`. |
| `reserve archivo.json` | `account`, `run_id`, `amounts` (un entero positivo por periodo). |
| `settle archivo.json` | `account`, `run_id`, `actual` (un entero no negativo por periodo, o `null` si es desconocido). |

Ejemplo de reserva en la cuenta de suscripción:

```json
{"account":"codex-subscription","run_id":"boxer-001-task-001","amounts":{"weekly":200}}
```

200 puntos básicos representan una cota de 2% de la cuota semanal. No es una
tarifa universal por tarea ni una estimación obtenida de Codex. El adaptador
futuro debe aportar una cota justificable. Si existen otras ventanas aplicables,
por ejemplo cinco horas, deben configurarse y reservarse también. El ejemplo
semanal no detecta ni configura automáticamente límites de la cuenta real.

Salidas CLI: 0 para una operación completada o capacidad disponible; 1 para
pausa, duplicado, coste desconocido, exceso o cruce de reinicio; 2 para entrada
inválida o error del registro. La CLI no permite sustituir el reloj del host.

## Qué garantía aporta esta versión

Impide sobrerreservas locales cuando todos los participantes usan el mismo
registro. No impone un límite duro al proveedor, no detiene un turno ya iniciado
y no conoce consumo externo posterior a la última observación. Las cotas se
reciben de un llamador de confianza; un trabajador no debe poder modificarlas
ni escribir directamente en la base de datos.

Antes de ejecutar modelos faltan: lectura verificada de cuotas/costes y reinicios,
control de acceso al registro, integración obligatoria con el trabajador,
cancelación y límites de consumo por petición. Con suscripciones cuyo consumo
por turno no tenga cota verificable, el corte exacto al 50% no puede garantizarse:
se necesitarán margen preventivo y medición entre turnos. Sin datos fiables se
mantendrá la pausa. La cuenta real del usuario no se ha consultado ni configurado.
