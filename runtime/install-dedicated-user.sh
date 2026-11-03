#!/usr/bin/env bash
# Prepares a dedicated Linux user for the executor, so an agent that escapes its profile still can't
# reach the operator's home. Run as root. It never reads, copies or moves the operator's credentials.
set -euo pipefail
package="${1:?Usage: sudo bash install-dedicated-user.sh /path/agentfarm-executor-linux.tar.gz [operator] [user]}"
operator="${2:-${SUDO_USER:-}}"
user="${3:-agentfarm}"
[[ "$(id -u)" == 0 ]] || { echo "Ejecútalo con sudo." >&2; exit 2; }
[[ -f "$package" ]] || { echo "No existe el paquete $package; créalo con: bash runtime/package.sh" >&2; exit 2; }
[[ "$user" =~ ^[a-z][a-z0-9_-]{0,30}$ ]] || { echo "Nombre de usuario no válido: $user" >&2; exit 2; }
[[ "$user" != root && "$user" != "$operator" ]] || { echo "Debe ser un usuario distinto del operador." >&2; exit 2; }

if ! id "$user" >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash "$user"
  echo "Usuario $user creado."
fi
for group in sudo docker adm; do
  if id -nG "$user" | tr ' ' '\n' | grep -qx "$group"; then
    echo "El usuario $user está en el grupo $group: sácalo, porque le daría privilegios del equipo." >&2
    exit 1
  fi
done
home_dir="$(getent passwd "$user" | cut -d: -f6)"
chmod 700 "$home_dir"

lib="$home_dir/.local/lib/agentfarm"
install -d -o "$user" -g "$user" -m 700 "$home_dir/.local" "$home_dir/.local/lib" "$lib" "$home_dir/.local/state" "$home_dir/.local/state/agentfarm"
dist="$(mktemp -d "$lib/executor.XXXXXX")"
tar -xzf "$package" -C "$dist"
chown -R "$user:$user" "$dist"
echo "Ejecutor extraído en $dist/agentfarm-executor."

# The service must survive the end of a session, and without a login there is no user manager.
if command -v loginctl >/dev/null && loginctl enable-linger "$user" 2>/dev/null; then
  echo "Sesión persistente activada."
else
  echo "AVISO: no se pudo activar la sesión persistente (loginctl enable-linger $user)." >&2
fi

failed=0
if [[ -n "$operator" ]]; then
  operator_home="$(getent passwd "$operator" | cut -d: -f6)"
  if [[ -n "$operator_home" ]] && su -s /bin/sh "$user" -c "test -r '$operator_home'"; then
    echo "AVISO: $user puede leer $operator_home. Ciérrala con: chmod 750 $operator_home" >&2
    failed=1
  else
    echo "Comprobado: $user no puede leer la home de $operator."
  fi
else
  echo "AVISO: sin operador indicado, no se ha comprobado el acceso a su home." >&2
fi

cat <<NEXT

Falta lo que solo puedes hacer tú, con credenciales propias de este usuario:
  sudo -iu $user
  gh auth login --hostname github.com --git-protocol https --web
  codex login
  # Guarda la credencial del nodo en ~/.local/state/agentfarm/node-token (permiso 600)
  # y crea ~/.local/state/agentfarm/executor.env, como explica docs/INSTALLATION.md.
  $dist/agentfarm-executor/install-executor.sh ~/.local/state/agentfarm/executor.env

Este usuario no tiene Docker: las pruebas en contenedor necesitan Docker sin root (consulta docs/SECURITY.md).
Hasta configurarlo, las tareas se bloquearán con el motivo.
NEXT
exit "$failed"
