package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Runs a candidate's setup and test commands in a disposable container. The container sees an
 * exported copy of the commit tree only: no .git directory, no home directory and no provider or
 * GitHub credentials. Setup may use the network; by default tests run with it disconnected.
 */
public final class TestSandbox {
  public static final String DEFAULT_IMAGE = "buildpack-deps:noble";
  private static final String IMAGE = "[a-z0-9][a-z0-9._/:@-]{0,254}";

  /** Container limits declared by the project profile or the executor defaults. */
  public record Policy(String image, String network, int cpus, int memoryMb, int timeoutMinutes) {
    public Policy {
      if (image == null || !image.matches(IMAGE) || image.contains(".."))
        throw new IllegalArgumentException("Invalid container image");
      if (!Set.of("setup", "none", "always").contains(network))
        throw new IllegalArgumentException("Container network must be setup, none or always");
      if (cpus < 1 || cpus > 16) throw new IllegalArgumentException("Container cpus must be 1-16");
      if (memoryMb < 256 || memoryMb > 32768)
        throw new IllegalArgumentException("Container memory_mb must be 256-32768");
      if (timeoutMinutes < 1 || timeoutMinutes > 60)
        throw new IllegalArgumentException("Container timeout_minutes must be 1-60");
    }

    public static Policy defaults(String image) {
      return new Policy(image, "setup", 2, 4096, 10);
    }

    /** Reads the policy recorded in an approved proposal. */
    public static Policy fromSummary(com.fasterxml.jackson.databind.JsonNode n) {
      return new Policy(
          n.path("image").asText(),
          n.path("network").asText(),
          n.path("cpus").asInt(),
          n.path("memory_mb").asInt(),
          n.path("timeout_minutes").asInt());
    }

    public Map<String, Object> summary() {
      return obj(
          "mode",
          "container",
          "image",
          image,
          "network",
          network,
          "cpus",
          cpus,
          "memory_mb",
          memoryMb,
          "timeout_minutes",
          timeoutMinutes);
    }
  }

  public record Output(int exit, String text) {}

  /** Executes one host command; tests replace it to observe the docker invocations. */
  @FunctionalInterface
  public interface Runner {
    Output run(List<String> argv, Duration timeout) throws Exception;
  }

  private final Runner runner;
  private final String docker;
  private final String user;

  public TestSandbox(Runner runner, String docker, String user) {
    this.runner = runner;
    this.docker = docker;
    if (!user.matches("[0-9]+:[0-9]+")) throw new IllegalArgumentException("uid:gid required");
    this.user = user;
  }

  /**
   * Exports {@code sha} from {@code repository}, runs setup and the given test commands, and
   * always removes the container and the exported tree. Returns one result per test, in order.
   */
  public List<Map<String, Object>> run(
      Path repository, String sha, Policy policy, String setup, Map<String, String> tests)
      throws Exception {
    if (!sha.matches("[a-f0-9]{40}")) throw new IllegalArgumentException("Full SHA required");
    Path tree = Files.createTempDirectory("agentfarm-sandbox-");
    String name = "agentfarm-" + sha.substring(0, 12) + "-" + UUID.randomUUID().toString().substring(0, 8);
    Duration limit = Duration.ofMinutes(policy.timeoutMinutes());
    try {
      Path archive = tree.resolveSibling(tree.getFileName() + ".tar");
      try {
        check(
            runner.run(
                List.of("git", "-C", repository.toString(), "archive", "--format=tar", "-o", archive.toString(), sha),
                Duration.ofMinutes(2)),
            "git archive");
        check(
            runner.run(List.of("tar", "-xf", archive.toString(), "-C", tree.toString()), Duration.ofMinutes(2)),
            "tar");
      } finally {
        Files.deleteIfExists(archive);
      }
      check(runner.run(createArgs(name, tree, policy), Duration.ofMinutes(10)), "docker create");
      check(runner.run(List.of(docker, "start", name), Duration.ofMinutes(1)), "docker start");
      var results = new ArrayList<Map<String, Object>>();
      if (!setup.isBlank()) {
        // Package managers need root inside the container; hand the tree back afterwards.
        var out = runner.run(exec(name, "0:0", setup), limit);
        runner.run(exec(name, "0:0", "chown -R " + user + " /workspace /tmp"), Duration.ofMinutes(2));
        if (out.exit() != 0) {
          for (var test : tests.entrySet())
            results.add(result(test.getKey(), "failed", test.getValue(), "setup failed: " + out.text()));
          return results;
        }
      }
      if (policy.network().equals("setup"))
        check(
            runner.run(List.of(docker, "network", "disconnect", "-f", "bridge", name), Duration.ofMinutes(1)),
            "docker network disconnect");
      for (var test : tests.entrySet()) {
        String command = test.getValue();
        if (command.startsWith("N/A:") && command.length() > 12) {
          results.add(obj("kind", test.getKey(), "status", "not_applicable", "reason", command));
          continue;
        }
        Output out;
        try {
          out = runner.run(exec(name, user, command), limit);
        } catch (Exception e) {
          out = new Output(124, String.valueOf(e.getMessage()));
        }
        results.add(
            result(test.getKey(), out.exit() == 0 ? "passed" : "failed", command, out.text()));
      }
      return results;
    } finally {
      cleanup(name, tree);
    }
  }

  private void cleanup(String name, Path tree) {
    // Files written as root (setup, or a test that escalated inside the container) must be
    // handed back before the host user can delete the exported tree.
    for (var argv :
        List.of(exec(name, "0:0", "chown -R " + user + " /workspace /tmp"), List.of(docker, "rm", "-f", name)))
      try {
        runner.run(argv, Duration.ofMinutes(2));
      } catch (Exception ignored) {
      }
    try {
      deleteTree(tree);
    } catch (Exception ignored) {
      // A leftover temporary tree never affects the checkout or the evidence.
    }
  }

  List<String> createArgs(String name, Path tree, Policy p) {
    return List.of(
        docker,
        "create",
        "--name",
        name,
        "--label",
        "agentfarm.sandbox=1",
        "--network",
        p.network().equals("none") ? "none" : "bridge",
        "--cpus",
        String.valueOf(p.cpus()),
        "--memory",
        p.memoryMb() + "m",
        "--pids-limit",
        "1024",
        "--cap-drop",
        "ALL",
        "--cap-add",
        "CHOWN",
        "--cap-add",
        "DAC_OVERRIDE",
        "--cap-add",
        "FOWNER",
        "--cap-add",
        "SETUID",
        "--cap-add",
        "SETGID",
        "--security-opt",
        "no-new-privileges",
        "--tmpfs",
        "/tmp:rw,exec,size=1g",
        "--env",
        "HOME=/tmp",
        // JVM tools read user.home from /etc/passwd, not HOME; setup (root) and tests (host
        // uid, usually absent from the image) must share one dependency cache.
        "--env",
        "JAVA_TOOL_OPTIONS=-Duser.home=/tmp",
        "--env",
        "CI=true",
        "--volume",
        tree.toAbsolutePath() + ":/workspace",
        "--workdir",
        "/workspace",
        "--entrypoint",
        "sleep",
        p.image(),
        "infinity");
  }

  private List<String> exec(String name, String as, String command) {
    return List.of(docker, "exec", "--user", as, name, "bash", "-lc", command);
  }

  private static Map<String, Object> result(String kind, String status, String command, String out) {
    return obj("kind", kind, "status", status, "command", command, "output", out);
  }

  private static void check(Output out, String step) {
    if (out.exit() != 0)
      throw new IllegalStateException(
          step + " failed: " + out.text().substring(0, Math.min(out.text().length(), 1800)));
  }

  private static void deleteTree(Path root) throws Exception {
    if (!Files.exists(root)) return;
    try (var paths = Files.walk(root)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
    }
  }
}
