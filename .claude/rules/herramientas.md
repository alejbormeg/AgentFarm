# Trabajar con las herramientas

Errores reales de sesiones anteriores. Cada uno costó una ejecución de CI o un correo de fallo.

## Shell

- **Publica solo si la prueba pasó.** En `prueba; git push` o `prueba | tail; git push` el fallo no detiene nada: `| tail` y `| grep` esconden el código de salida. Usa `comprobar.sh && git push`, o mira `PIPESTATUS`.
- **Comprueba una variable antes de usarla.** Si `$(gh ...)` falla por un 502, queda vacía y acabas escribiendo un enlace `#` en un documento.
- **`git checkout` lleva contigo los cambios sin confirmar.** Si un paso anterior falló, puedes estar en la rama equivocada: mira `git status -sb`.
- **`git branch -f` y una cadena con `&&`:** si falla a medias tras un `git stash`, el trabajo se queda guardado y las pruebas siguientes validan el código base. Comprueba el stash.
- **No uses `pkill -f` con un patrón que aparezca en tu orden:** mata tu propia shell. Guarda el PID o usa `timeout`.
- **Un `sed -i` o un script de sustitución puede no aplicar nada.** Usa aserciones al sustituir, y no sigas si fallan.

## GitHub

- `gh` falla a menudo con 502 o 504. Comprueba el estado real antes de repetir algo que crea cosas, como una issue.
- `gh pr edit` falla en este repositorio por los Projects clásicos. Usa `gh api -X PATCH repos/{owner}/{repo}/pulls/<n> -F body=@archivo`.
- No uses `--amend` con push forzado. Añade un commit normal: la fusión por squash los une.
- `CHANGELOG.md` siempre se edita en el mismo punto, así que varias PR a la vez entran en conflicto, y cada resolución repite la CI. Con PR en paralelo, deja sus líneas para la última.
- Esta sesión no puede vincular PR al monitor de la app. Si no llega el aviso, lee la CI una vez por pausa, sin sondear.

## Dobles

Un doble solo demuestra lo que hace el doble. Antes de dar algo por probado, mira qué cliente real hay por medio y si valida algo que el doble se salta.
