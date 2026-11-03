# Pilotos reales

El e2e usa agentes y GitHub simulados. El primer piloto completo sobre un repositorio real encontró
en una mañana fallos que el e2e no veía, así que repite un piloto:

- antes de cada versión;
- después de cambiar prompts, recetas o el ejecutor;
- al actualizar Codex CLI, porque el aislamiento depende de sus perfiles de permisos.

## Preparación
1. Usa un repositorio privado desechable, con algo de código y pruebas. No uses un producto real.
2. Actualiza panel y ejecutor al mismo commit. El panel avisa si difieren.
3. Comprueba el entorno:

```bash
./agentfarm doctor
./agentfarm github-check owner/repo-de-pilotos
```

## Recorrido
Sigue el flujo solo desde el panel. **No fusiones con `gh` ni con la web**: saltarte el panel deja sin
probar justo el último tramo.

1. Adopta el repositorio y aprueba su propuesta.
2. Pide una corrección sobre el candidato.
3. Integra la adopción por SHA.
4. Lanza una tarea con cada receta que hayas cambiado, y otra sobre una rama que haya quedado desactualizada.
5. Integra al menos una de ellas por SHA.

## Qué medir
Anótalo en el `CHANGELOG.md` junto con la versión probada:

| Medida | Objetivo |
| --- | --- |
| Intervenciones humanas entre propuesta e integración, sin contar las aprobaciones | 0 |
| Tareas que llegan a integrarse sin bloquearse | Todas |
| Fallos que el e2e no detectó | 0; cada uno se convierte en una prueba |
| Cuota consumida por tarea integrada | Sin subidas inexplicadas |

Cada fallo se resuelve como corrección de producto, con su prueba. Reescribir un criterio a mano para
que la tarea pase cuenta como intervención, y hay que anotarlo.

## Credenciales
Revoca al terminar cualquier credencial creada para el piloto. Usa una GitHub App limitada al
repositorio de pilotos, y nunca pegues un secreto en un chat con un agente.
