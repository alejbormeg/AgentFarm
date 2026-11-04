package io.agentfarm.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * The profile only protects anything if Codex applies it, and an update can change that. This runs
 * the real sandbox, without inference, and looks at every hidden path. It never reads their
 * content.
 */
public final class SandboxProbe {
  private static final String PASSED = "agentfarm-sandbox-passed";
  private static final String SCRIPT =
      "checkout=$1; shift; "
          + "[ -r \"$checkout\" ] || { echo \"SIN ACCESO $checkout\"; exit 1; }; "
          + "exposed=0; "
          + "for path in \"$@\"; do "
          + "if [ -S \"$path\" ] || [ -r \"$path\" ]; then echo \"EXPUESTO $path\"; exposed=1; fi; "
          + "done; "
          + "[ \"$exposed\" = 0 ] && echo "
          + PASSED;

  private SandboxProbe() {}

  @SuppressWarnings("unchecked")
  public static List<String> command(String codex, Map<String, Object> config, Path checkout) {
    var permissions =
        (Map<String, Object>)
            ((Map<String, Object>) config.get("permissions")).get(AgentSandbox.PROFILE);
    var filesystem = (Map<String, Object>) permissions.get("filesystem");
    var network = (Map<String, Object>) permissions.get("network");
    var entries = new StringJoiner(",");
    filesystem.forEach(
        (path, access) -> entries.add(Json.encode(path) + "=" + Json.encode(access)));
    var argv =
        new ArrayList<>(
            List.of(
                codex,
                "sandbox",
                "-c",
                "default_permissions=\"" + AgentSandbox.PROFILE + "\"",
                "-c",
                "permissions." + AgentSandbox.PROFILE + ".filesystem={" + entries + "}",
                "-c",
                "permissions."
                    + AgentSandbox.PROFILE
                    + ".network.enabled="
                    + network.get("enabled"),
                "--",
                "sh",
                "-c",
                SCRIPT,
                "agentfarm-sandbox-probe",
                checkout.toString()));
    argv.addAll(AgentSandbox.hiddenPaths(config));
    return argv;
  }

  /**
   * What went wrong, or empty if the sandbox hid everything. Not being able to tell is a failure.
   */
  public static Optional<String> check(List<String> command, Path checkout, long timeoutSeconds)
      throws InterruptedException {
    Process process;
    try {
      process =
          new ProcessBuilder(command)
              .directory(checkout.toFile())
              .redirectErrorStream(true)
              .start();
    } catch (IOException e) {
      return Optional.of("no se pudo ejecutar Codex: " + e.getMessage());
    }
    try {
      process.getOutputStream().close();
    } catch (IOException ignored) {
    }
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      return Optional.of("la comprobación superó " + timeoutSeconds + " segundos");
    }
    String output;
    try {
      output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      return Optional.of("no se pudo leer el resultado: " + e.getMessage());
    }
    if (process.exitValue() == 0 && output.lines().anyMatch(PASSED::equals))
      return Optional.empty();
    String detail = output.lines().limit(5).reduce((a, b) -> a + "; " + b).orElse("sin salida");
    return Optional.of(detail.length() > 400 ? detail.substring(0, 400) : detail);
  }
}
