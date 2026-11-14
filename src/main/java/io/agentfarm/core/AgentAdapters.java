package io.agentfarm.core;

import java.util.*;

/** Stable IDs; never selects another provider as fallback. */
public final class AgentAdapters {
  private static final Map<String, AgentAdapter> ADAPTERS =
      Map.of(
          "codex",
          new CodexAdapter(),
          "claude-code",
          new ClaudeCodeAdapter(),
          "antigravity",
          new io.agentfarm.adapters.AntigravityAdapter());

  public static AgentAdapter get(String id) {
    var adapter = ADAPTERS.get(id);
    if (adapter == null) throw new IllegalArgumentException("Unknown agent provider");
    return adapter;
  }

  public static List<Map<String, Object>> catalog() {
    return List.of(
        get("codex").descriptor(),
        get("claude-code").descriptor(),
        get("antigravity").descriptor());
  }
}
