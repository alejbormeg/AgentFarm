package io.agentfarm.core;

import static io.agentfarm.core.Json.obj;

import java.util.*;

/**
 * What the platform checks in the agentfarm.yaml a candidate adds, with the parser the executor
 * uses.
 */
public final class ProfileCheck {
  private ProfileCheck() {}

  /** The profile must load and keep the approved commands; narrow paths only raise a warning. */
  public static Map<String, Object> validate(
      String source,
      String repository,
      String unit,
      String integration,
      Collection<String> directories) {
    ProjectProfile profile;
    try {
      profile = ProjectProfile.parse(source);
      profile.requireSupportedExecution();
    } catch (IllegalArgumentException e) {
      return failed(e.getMessage());
    }
    if (!profile.repository().equals(repository)) return failed("Profile repository mismatch");
    if (!sameCommand(profile.tests().get("unit"), unit)
        || !sameCommand(profile.tests().get("integration"), integration))
      return failed("Los comandos de pruebas del perfil no coinciden con los aprobados");
    var uncovered =
        directories.stream().filter(d -> !d.startsWith(".") && !profile.permits(d + "/x")).toList();
    return obj(
        "status",
        "passed",
        "hash",
        profile.hash(),
        "quality",
        profile.quality(),
        "uncovered_directories",
        uncovered);
  }

  /** "N/A: motivo" is no command: two ways of writing the reason approve the same thing. */
  private static boolean sameCommand(String profile, String approved) {
    return profile.equals(approved) || (profile.startsWith("N/A:") && approved.startsWith("N/A:"));
  }

  private static Map<String, Object> failed(String error) {
    return obj("status", "failed", "error", error);
  }
}
