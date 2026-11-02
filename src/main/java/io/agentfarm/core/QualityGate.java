package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import java.util.*;
import java.util.regex.Pattern;

/** A quality metric may not get worse: compares what a command printed on base and candidate. */
public final class QualityGate {
  private static final Pattern LAST_NUMBER = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*%?\\s*$");

  private QualityGate() {}

  /** The number at the end of the command's last non-blank line. */
  public static OptionalDouble metric(String output) {
    var lines = output == null ? new String[0] : output.strip().split("\n");
    if (lines.length == 0) return OptionalDouble.empty();
    var m = LAST_NUMBER.matcher(lines[lines.length - 1]);
    return m.find() ? OptionalDouble.of(Double.parseDouble(m.group(1))) : OptionalDouble.empty();
  }

  public static Map<String, Object> compare(
      String name, String better, String baseOutput, String candidateOutput) {
    var base = metric(baseOutput);
    var candidate = metric(candidateOutput);
    // A metric that stops being measurable can't be claimed as unchanged.
    if (candidate.isEmpty())
      return obj("name", name, "status", "failed", "reason", "El candidato no da un número");
    if (base.isEmpty())
      return obj(
          "name",
          name,
          "status",
          "passed",
          "candidate",
          candidate.getAsDouble(),
          "reason",
          "Primera medición: la base no da un número");
    boolean worse =
        better.equals("lower")
            ? candidate.getAsDouble() > base.getAsDouble()
            : candidate.getAsDouble() < base.getAsDouble();
    return obj(
        "name",
        name,
        "status",
        worse ? "failed" : "passed",
        "base",
        base.getAsDouble(),
        "candidate",
        candidate.getAsDouble());
  }
}
