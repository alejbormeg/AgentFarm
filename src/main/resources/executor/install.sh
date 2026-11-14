#!/usr/bin/env bash
# Installs the AgentFarm executor from the panel that served this script, as a systemd user
# service. Usage: bash install.sh [--panel URL] [--node NAME] [--code afc_…|--token-file node-token] [--replace]
# With --node several executors can share a machine: each gets its own unit, configuration and state.
# The node credential comes from --token-file or is typed at the terminal: never as an argument.
set -euo pipefail
panel=@PANEL_URL@
token_file=""
code=""
name=""
replace=0
die() { echo "$*" >&2; exit 1; }
usage() { echo "Uso: bash install.sh [--panel URL] [--node NOMBRE] [--code afc_…|--token-file node-token] [--replace]"; }
while (($#)); do
  case "$1" in
    --panel) panel="${2:-}"; shift 2 || die "Falta la dirección tras --panel." ;;
    --token-file) token_file="${2:-}"; shift 2 || die "Falta el archivo tras --token-file." ;;
    --node) name="${2:-}"; shift 2 || die "Falta el nombre tras --node." ;;
    --code) code="${2:-}"; shift 2 || die "Falta el código tras --code." ;;
    --replace) replace=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done
panel="${panel%/}"
[[ -z "$name" || "$name" =~ ^[A-Za-z0-9_-]{1,80}$ ]] || die "Nombre de nodo no válido: $name"
# Where this executor lives. A named one has its own files, so it never collides with another.
config="${XDG_CONFIG_HOME:-$HOME/.config}"
env_file="$config/agentfarm/executor${name:+-$name}.env"
unit="$config/systemd/user/agentfarm-executor${name:+-$name}.service"
unit_name="agentfarm-executor${name:+-$name}"
# Before anything is spent: a code is single-use, so a refusal here must not burn it.
if ((replace == 0)) && [[ -e "$env_file" || -e "$unit" ]]; then
  die "Ya hay un ejecutor instalado con ese nombre en este equipo ($env_file o $unit). Para sustituirlo, vuelve a ejecutar con --replace."
fi
[[ "$panel" =~ ^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$ ]] || die "Dirección del panel no válida: $panel"

echo "Comprobando este equipo..."
missing=0
need() { command -v "$1" >/dev/null || { echo "  Falta $1: $2" >&2; missing=1; }; }
need java "instala Java 21 o posterior, por ejemplo: sudo apt install openjdk-21-jre-headless"
need git "sudo apt install git"
need curl "sudo apt install curl"
need tar "sudo apt install tar"
need sha256sum "sudo apt install coreutils"
need gh "instala GitHub CLI: https://cli.github.com"
command -v codex >/dev/null || command -v agy >/dev/null || command -v claude >/dev/null ||
  { echo "  Falta un agente: instala Codex (codex login), Antigravity (agy) o Claude Code (claude)." >&2; missing=1; }
need docker "las pruebas de cada tarea corren en contenedores; en WSL activa la integración de Docker Desktop"
((missing == 0)) || die "Instala lo que falta y vuelve a ejecutar este instalador."
java_major="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')"
[[ "${java_major%%.*}" =~ ^[0-9]+$ && "${java_major%%.*}" -ge 21 ]] ||
  die "Hace falta Java 21 o posterior (este equipo tiene ${java_major:-una versión desconocida})."
docker info >/dev/null 2>&1 || die "Docker no responde. En WSL, abre Docker Desktop y activa la integración con esta distribución."
if command -v codex >/dev/null; then
  codex login status >/dev/null 2>&1 || die "Codex no tiene sesión. Entra con: codex login"
fi
systemd=1
systemctl --user show-environment >/dev/null 2>&1 || systemd=0

if [[ -n "$code" ]]; then
  # A one-use code from "Añadir nodo" in the panel, valid for minutes: it is exchanged for the
  # node credential here, so the long-lived token never appears in a command line.
  [[ "$code" =~ ^afc_[A-Za-z0-9_-]{32}$ ]] || die "Eso no parece un código de enrolamiento (afc_…)."
  redeemed="$(printf '{"code":"%s"}' "$code" | curl -fsS --max-time 15 -H 'Content-Type: application/json' -d @- "$panel/executor/redeem")" ||
    die "El panel no aceptó el código: caduca a los 15 minutos y solo vale una vez. Genera otro en Agentes → Añadir nodo."
  token="$(grep -oE '"token" *: *"afn_[A-Za-z0-9_-]{43}"' <<<"$redeemed" | head -1 | sed -E 's/.*"(afn_[^"]*)"$/\1/')"
  unset redeemed code
elif [[ -n "$token_file" ]]; then
  [[ -r "$token_file" ]] || die "No se puede leer $token_file."
  token="$(tr -d '[:space:]' <"$token_file")"
else
  [[ -r /dev/tty ]] || die "Sin terminal, pasa la credencial con --token-file."
  read -rsp "Credencial del nodo (afn_…, se crea en Conectar ejecutor): " token </dev/tty
  echo >/dev/tty
fi
[[ "$token" =~ ^afn_[A-Za-z0-9_-]{43}$ ]] || die "Eso no parece una credencial de nodo (afn_…)."

tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
# The credential goes to curl from a private file, never as an argument other processes can list.
(umask 077 && printf 'Authorization: Bearer %s\n' "$token" >"$tmp/authorization")
identity="$(curl -fsS --max-time 15 -H @"$tmp/authorization" "$panel/api/executors/me")" ||
  die "El panel ($panel) no reconoce esa credencial: créala de nuevo en Conectar ejecutor."
field() { grep -oE "\"$1\" *: *\"[A-Za-z0-9._-]*\"" <<<"$identity" | head -1 | sed -E 's/.*"([^"]*)"$/\1/'; }
node="$(field node)" account="$(field account)" implementer="$(field implementer)" reviewer="$(field reviewer)"
[[ -n "$node" && -n "$account" && -n "$implementer" && -n "$reviewer" ]] ||
  die "El panel no devolvió la identidad del nodo."
[[ -z "$name" || "$name" == "$node" ]] || die "Este comando es para el nodo $name y el panel devolvió $node."

echo "Descargando el ejecutor de $panel..."
curl -fsS --max-time 300 -o "$tmp/executor.tar.gz" "$panel/executor/agentfarm-executor-linux.tar.gz" ||
  die "El panel no sirve el paquete del ejecutor. Si lo arrancaste sin Docker, compílalo con bash runtime/package.sh y reinícialo."
expected="$(curl -fsS --max-time 15 "$panel/executor/agentfarm-executor-linux.tar.gz.sha256" | cut -d' ' -f1)"
actual="$(sha256sum "$tmp/executor.tar.gz" | cut -d' ' -f1)"
[[ "$expected" =~ ^[0-9a-f]{64}$ && "$actual" == "$expected" ]] || die "El paquete descargado no coincide con su SHA-256."

data="${XDG_DATA_HOME:-$HOME/.local/share}/agentfarm"
release="$data/executor.$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$release"
tar -xzf "$tmp/executor.tar.gz" -C "$release"
root="$release/agentfarm-executor"
[[ -x "$root/executor" && -f "$root/executor.jar" ]] || die "El paquete no trae el ejecutor."

state="${XDG_STATE_HOME:-$HOME/.local/state}/agentfarm${name:+-$name}"
install -d -m 700 "$state" "$(dirname "$env_file")"
(umask 077 && printf '%s\n' "$token" >"$state/node-token")
unset token
(umask 077 && cat >"$env_file" <<EOF
AGENTFARM_NODE=$node
AGENTFARM_ACCOUNT=$account
AGENTFARM_URL=$panel
AGENTFARM_STATE=$state
AGENTFARM_IMPLEMENTER=$implementer
AGENTFARM_REVIEWER=$reviewer
EOF
)
echo "Ejecutor del nodo $node (cuenta $account) en $root"
echo "Configuración: $env_file · credencial: $state/node-token (permiso 600)"

if ((systemd == 0)); then
  cat <<TEXT
Este equipo no tiene servicios systemd de usuario, así que el ejecutor no arrancará solo.
En WSL, añade a /etc/wsl.conf:
  [boot]
  systemd=true
y ejecuta "wsl --shutdown" desde Windows. Después repite este instalador con --replace.
Mientras tanto, puedes arrancarlo en primer plano:
  set -a; . "$env_file"; set +a; "$root/executor"
TEXT
  exit 0
fi
"$root/install-executor.sh" "$env_file" "" "$name"
echo "Servicio en marcha: systemctl --user status $unit_name"
echo "Vuelve al panel y pulsa Comprobar conexión en Conectar ejecutor."
