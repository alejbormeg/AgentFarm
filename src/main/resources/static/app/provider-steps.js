// What the owner does on a node's machine to connect each provider. Pure, so it is tested alone.

export const NAMES = { codex: "Codex (ChatGPT)", "claude-code": "Claude Code", antigravity: "Antigravity (Gemini)" };

// The installer names a node's files after it: agentfarm-NAME, executor-NAME.env and the unit
// agentfarm-executor-NAME. The executor reports its state directory, so the steps name the real ones.
export function nodeFiles(stateDir) {
  const dir = (stateDir || "~/.local/state/agentfarm").replace(/\/+$/, "");
  const base = dir.slice(dir.lastIndexOf("/") + 1);
  const suffix = base.startsWith("agentfarm-") ? base.slice("agentfarm".length) : "";
  return {
    state: dir,
    env: "~/.config/agentfarm/executor" + suffix + ".env",
    unit: "agentfarm-executor" + suffix,
  };
}

export function steps(provider, files) {
  const restart = "systemctl --user restart " + files.unit;
  switch (provider) {
    case "codex":
      return ["En el equipo del nodo: codex login (sesión de tu suscripción).", "Comprueba con: codex login status."];
    case "claude-code":
      return [
        "En Presupuesto crea una cuenta de cuota de modo api (USD): es el tope de gasto de Claude.",
        "En el equipo del nodo guarda tu clave de Anthropic en " + files.state + "/anthropic-api-key (chmod 600).",
        "Añade AGENTFARM_CLAUDE_ACCOUNT=<tu cuenta> a " + files.env + " y reinicia: " + restart + ".",
      ];
    case "antigravity":
      return [
        "En el equipo del nodo ejecuta agy una vez e inicia sesión con tu cuenta de Google.",
        "En Presupuesto crea una cuenta de cuota de modo api (USD): es el tope de gasto, estimado por tokens; la cuota real de tu plan de Google no se puede leer.",
        "Añade AGENTFARM_ANTIGRAVITY_ACCOUNT=<tu cuenta> a " + files.env + " y reinicia: " + restart + ".",
      ];
    default:
      return [];
  }
}

// Roles where the owner's choice is not what the node runs, because that provider is not ready there.
export function fallbacks(chosen, running) {
  return Object.entries(chosen || {})
    .filter(([role, provider]) => provider && running?.[role] && running[role] !== provider)
    .map(([role, provider]) => ({ role, chosen: provider, running: running[role] }));
}
