package io.agentfarm.core;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The one redaction policy for what agents, executors and providers send: errors, logs, proposals,
 * evidence and telemetry. It hides known credential formats and assignments of secret fields. It
 * also works on encoded JSON: replacements never contain quotes or backslashes, and no pattern
 * consumes a backslash, so a valid JSON text stays valid.
 */
public final class Redaction {
  private static final String SEP = "(?:\\\\n|\\\\r|\\n|\\r|[ \\t])*";

  private record Rule(Pattern pattern, String replacement) {}

  private static final List<Rule> RULES =
      List.of(
          new Rule(
              Pattern.compile(
                  "-----BEGIN[A-Z ]*PRIVATE KEY-----(?:"
                      + SEP
                      + "(?!-----)[A-Za-z0-9+/=:,.-]+)*(?:"
                      + SEP
                      + "-----END[A-Z ]*PRIVATE KEY-----)?"),
              "[clave privada oculta]"),
          new Rule(Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*"), "Bearer [oculto]"),
          new Rule(
              Pattern.compile("(?:github_pat_[A-Za-z0-9_]{8,}|gh[pousr]_[A-Za-z0-9_]{8,})"),
              "[credencial oculta]"),
          new Rule(Pattern.compile("afn_[A-Za-z0-9_-]{16,}"), "[credencial oculta]"),
          new Rule(Pattern.compile("sk-(?:ant-|proj-)?[A-Za-z0-9_-]{12,}"), "[clave oculta]"),
          new Rule(Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), "[clave oculta]"),
          new Rule(Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"), "[clave oculta]"),
          new Rule(Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}"), "[credencial oculta]"),
          new Rule(
              Pattern.compile(
                  "(?i)((?:\\\\?[\"'])?(?:access_token|refresh_token|id_token|client_secret|password"
                      + "|passwd|api_key|apikey|secret_key|private_key|authorization)(?:\\\\?[\"'])?"
                      + "\\s*[:=]\\s*(?:\\\\?[\"'])?)(?!\\[)[^\\s\"',}\\\\]+"),
              "$1[oculto]"));

  private Redaction() {}

  /** Hides credentials; keeps everything else as it is. Null stays null. */
  public static String secrets(String value) {
    if (value == null) return null;
    String out = value;
    for (Rule rule : RULES) out = rule.pattern().matcher(out).replaceAll(rule.replacement());
    return out;
  }

  /** For text shown to people: also drops terminal escapes and control characters. */
  public static String text(String value) {
    if (value == null) return null;
    return secrets(
        value.replaceAll("\\x1B\\[[0-9;]*[A-Za-z]", "").replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", ""));
  }
}
