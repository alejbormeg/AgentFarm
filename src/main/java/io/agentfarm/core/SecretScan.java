package io.agentfarm.core;

import java.util.*;
import java.util.regex.Pattern;

/** Known credential formats in the lines a change adds. Findings never carry the value. */
public final class SecretScan {
  private static final Map<String, Pattern> KINDS =
      Map.of(
          "token de GitHub",
              Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})"),
          "clave de API", Pattern.compile("\\bsk-(?:ant-)?[A-Za-z0-9_-]{20,}"),
          "clave de AWS", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
          "clave de Google", Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"),
          "token de Slack", Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),
          "credencial de AgentFarm", Pattern.compile("\\bafn_[A-Za-z0-9_-]{40,}"),
          "clave privada", Pattern.compile("-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----"));

  private SecretScan() {}

  /** Scans a unified diff; {@code file} is used until a header names another one. */
  public static List<String> findings(String file, String diff) {
    var found = new LinkedHashSet<String>();
    String current = file;
    for (String line : diff.split("\n")) {
      if (line.startsWith("+++ ")) {
        String name = line.substring(4).trim();
        current = name.startsWith("b/") ? name.substring(2) : name;
      } else if (line.startsWith("+")) {
        for (var kind : KINDS.entrySet())
          if (kind.getValue().matcher(line).find()) found.add(current + " (" + kind.getKey() + ")");
      }
    }
    return List.copyOf(found);
  }
}
