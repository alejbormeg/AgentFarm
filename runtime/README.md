# Ejecutor actual
Ver [operación y trazabilidad GitHub](../docs/OPERATIONS.md).
El servicio activo es agentfarm-executor; el observador y el piloto descritos
a continuación se conservan como referencia histórica y no deben arrancarse
simultáneamente con el nuevo ejecutor.

# Piloto local Codex/WSL para Forja

Ejecutor Java separado del panel, conectado al protocolo oficial `codex app-server`
por stdio y a la API local autenticada. Instalación específica del equipo del
promotor; no es todavía un instalador general ni un ejecutor distribuido.

- Ubuntu 24.04, usuario alex. Codex CLI 0.166.0 autenticado con ChatGPT.
- Proyecto: /home/alex/proyectos/forja, rama agentfarm/epoch-0-contracts.
- La copia inicial es una instantánea de main leída con el conector GitHub.
  Su raíz local NO es el commit remoto. Para publicar habrá que trasladar el
  diff sobre el HEAD remoto mediante una PR, conservando el historial remoto.
- Java ejecuta un Boxer de implementación (workspace-write) y uno de revisión
  (read-only), secuencialmente. Apruebaciones interactivas denegadas.
- Credenciales, prompts locales, cuota y resultados viven en
  ~/.local/state/agentfarm, fuera de Git. Nunca añadirlos al repositorio.
- Política solicitada para este piloto: parar al 2% restante semanal (98% usado),
  sustituyendo el anterior ejemplo del 50%. Cuenta codex-subscription única;
  se rechaza reiniciar si ya existe, hasta reconciliar estado y reservas.
- Lectura de cuota y gobierno cada 5 segundos entre llamadas; cada petición
  tiene timeout. Se interrumpe el turno y se destruyen procesos ante cuota,
  datos desconocidos, reset de ventana, pausa, deshabilitación o cancelación.
  Las lecturas son discretas: no se garantiza terminar exactamente en 2%.
- No compra créditos, aplica resets ni cambia a API. No publica, despliega o
  fusiona. Se detiene también al terminar el incremento, por error o por tiempo.
- El progreso 0 solo marca el inicio. No estima porcentajes de trabajo.
  Un candidato pasa a review sin marcar implementación verificada.
- Máximo 45 minutos/turno y 90 minutos de servicio. Sin reinicio automático.

Comprobar o parar el servicio dentro de WSL:

```bash
systemctl --user status agentfarm-forja
systemctl --user stop agentfarm-forja
cat ~/.local/state/agentfarm/status.txt
cat ~/.local/state/agentfarm/quota.json
```

La pausa del panel interrumpe el piloto. Reanudar el panel no reinicia procesos:
requiere reconciliar la ejecución y su reserva, sin duplicar llamadas.

Validación: pruebas JUnit del dominio/API y caso de propietario/SHA/candidato;
`java -cp "$JACKSON_CLASSPATH" runtime/CodexRunner.java guard-test` verifica
cuota desconocida, umbral 98%, otra ventana agotada y admisión por debajo.
`quota` consulta la cuenta sin iniciar inferencia. El classpath requiere Jackson 2
(jackson-databind/core/annotations) del build Maven.

Endpoint añadido: POST /api/tasks/{project}/{id}/candidate con worker y SHA
completo. Exige propietario de tarea activa, persiste SHA y pasa a revisión.
El SHA es declarado: no valida alcance, calidad, pruebas ni autoriza merge.

## Operaciones observables en Mateu

Panorama integra un componente web dentro de la shell Mateu, servido por
Spring Boot y protegido por la misma sesión del operador. Consulta /api/runtime
y /api/overview cada dos segundos, sin el bucle de acciones Mateu que disparaba
la protección de peticiones repetidas. Permite ver fases, tarea/worker,
fuente, CLI/modelo/sesión, PID cuando existe, rama y archivos cambiados.

RuntimeObserver.java es un proceso Java separado de solo lectura. Consulta
el grupo de procesos del supervisor y exclusivamente los rollouts de hoy con
originator agentfarm y cwd exacto /home/alex/proyectos/forja. No lee auth.json,
no ejecuta inferencia y no reinicia workers. Solo envía mensajes del asistente,
llamadas/salidas de herramientas y eventos de ciclo de turno; excluye mensajes
de usuario/desarrollador, contexto interno y reasoning. Oculta contraseña
conocida, claves comunes y Bearer. La consola es de lectura, no una terminal
interactiva.

Los eventos se deduplican por archivo/línea, con retención de 600 y lectura de
los últimos 200. Límites por lote/texto; la API exige propietario y CSRF.
La señal caduca a los 12 segundos: una captura antigua no se muestra como
ejecución viva. Cuota conserva su fecha de observación y se marca caducada
cuando el supervisor deja de actualizarla. Un observador vivo no significa un
turno Codex activo: se exige además proceso y estado running.

Servicio de este piloto:

    systemctl --user status agentfarm-observer
    systemctl --user stop agentfarm-observer

Solo persiste mientras vive esta sesión WSL; todavía no hay instalador genérico,
reanudación ni asociación multi-proyecto/multi-ejecución. La detección de los
dos roles corresponde a las dos sesiones secuenciales del piloto actual.
No se reinterpreta este observador como identidad criptográfica de un nodo remoto.

## Registros vivos en todas las vistas

Nodos, Boxer, Tareas, Clover y Gobierno usan agentfarm-registry dentro de Mateu.
Los GET de lectura se renuevan cada dos segundos; no se reconstruyen formularios,
no hay POST periódico y no se utilizan acciones Mateu recursivas. Cada vista
muestra la última consulta y un error explícito si falla o supera ocho segundos.
Clover consulta el saldo real de cada cuenta y conserva la fecha de su lectura
de consumo: refrescar el panel no hace vigente una observación antigua.

Los timers paran al abandonar la vista y se reactivan al volver. El mismo ciclo
se corrige en Panorama, conservando su shadow root en reconexiones. Los botones
Actualizar datos solo consultan registros. Los formularios nativos mantienen
sus campos durante ese refresco. Consultar saldo y aplicar una conciliación
devuelven el modelo actualizado sin borrar los campos existentes.

Verificado en navegador: las cinco rutas; pausa y reanudación externas reflejadas
en vivo; auditoría nueva sin navegación; borrador de nodo conservado durante la
pausa; borrador JSON conservado al consultar saldo; límite vigente 98% cargado;
vuelta a Nodos sin error de reconexión. Las pruebas se hacen sin ejecutar agentes
ni registrar presupuestos nuevos. Se restaura la admisión habilitada.

Compatibilidad con el renderizador Element de Mateu beta: el registro usa un ID
estable, observa cambios de vista y descarta respuestas que pertenezcan a la
pestaña anterior. Solo conserva una instancia propia en su contenedor; esto
también elimina definiciones antiguas retenidas por la caché del cliente.
La ruta actual, validada contra las cinco vistas conocidas, determina qué
registro presentar. Se ha repetido Nodos → Boxer → Tareas → Clover → Gobierno
→ Nodos → Clover, verificando una sola instancia y la vista correcta en cada paso.
