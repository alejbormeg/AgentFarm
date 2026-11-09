package io.agentfarm.core;

import java.io.*;
import java.nio.charset.*;
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

  /**
   * The outcome of a scan: {@code clean} only when every byte was read, {@code found} with the
   * places that look like a credential, {@code incomplete} when something could not be read.
   */
  public record Coverage(String state, List<String> findings, List<String> unscanned) {
    public static Coverage of(Collection<String> findings, Collection<String> unscanned) {
      return new Coverage(
          !findings.isEmpty() ? "found" : !unscanned.isEmpty() ? "incomplete" : "clean",
          List.copyOf(findings),
          List.copyOf(unscanned));
    }

    public boolean clean() {
      return state.equals("clean");
    }

    /** Why a push must not go ahead, or null when it may. */
    public String refusal() {
      return switch (state) {
        case "found" -> "Posible secreto en el candidato: " + String.join(", ", findings);
        case "incomplete" ->
            "No se pudo analizar el candidato entero en busca de secretos: "
                + String.join(", ", unscanned);
        default -> null;
      };
    }
  }

  /**
   * Scans a whole unified diff, never a truncated one. Past {@code maxBytes} the rest is not read
   * and the scan is incomplete; a binary file Git did not show as text is incomplete too.
   */
  public static Coverage scan(InputStream diff, long maxBytes) throws IOException {
    byte[] bytes = diff.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
    var unscanned = new LinkedHashSet<String>();
    boolean truncated = bytes.length > maxBytes;
    if (truncated) {
      bytes = Arrays.copyOf(bytes, (int) maxBytes);
      unscanned.add("el diff supera " + maxBytes / 1_000_000 + " MB");
    }
    String text =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString();
    for (String line : text.split("\n"))
      if (line.startsWith("Binary files ") && line.endsWith(" differ"))
        unscanned.add("binario sin analizar: " + line.substring(13, line.length() - 7));
    return Coverage.of(findings("", text), unscanned);
  }

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
