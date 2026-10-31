package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class Diagnostics {
  public static Map<String, Object> run(Path repo, String panel, boolean runTests)
      throws Exception {
    var checks = new ArrayList<Map<String, Object>>();
    check(checks, "java", Runtime.version().feature() >= 21, "Java 21+ required");
    check(
        checks,
        "storage",
        Files.isDirectory(repo) && Files.isWritable(repo),
        "Project directory must be writable");
    for (String[] tool :
        List.of(
            new String[] {"git", "--version"},
            new String[] {"docker", "version", "--format", "{{.Server.Version}}"},
            new String[] {"codex", "login", "status"},
            new String[] {"gh", "auth", "status"})) probe(checks, tool[0], repo, tool);
    try {
      var request =
          HttpRequest.newBuilder(URI.create(panel + "/login"))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();
      int code =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(3))
              .build()
              .send(request, HttpResponse.BodyHandlers.discarding())
              .statusCode();
      check(checks, "panel", code == 200 || code == 302, "Panel login endpoint");
    } catch (Exception e) {
      check(checks, "panel", false, "Panel not reachable");
    }
    ProjectProfile profile = null;
    try {
      profile = ProjectProfile.load(repo.resolve("agentfarm.yaml"));
      check(checks, "profile", true, profile.hash());
      profile.requireSupportedExecution();
    } catch (Exception e) {
      profile = null;
      check(checks, "profile", false, e.getMessage());
    }
    checks.add(
        obj(
            "id",
            "quota",
            "state",
            "unknown",
            "detail",
            "Use the authenticated executor for a fresh provider reading; login does not establish"
                + " available quota"));
    if (runTests && profile != null) {
      profile.requireSupportedExecution();
      for (var test : profile.tests().entrySet()) {
        if (test.getValue().startsWith("N/A:") && test.getValue().length() > 12)
          checks.add(
              obj(
                  "id",
                  "test." + test.getKey(),
                  "state",
                  "not_applicable",
                  "detail",
                  test.getValue()));
        else
          probe(
              checks, "test." + test.getKey(), repo, new String[] {"bash", "-lc", test.getValue()});
      }
    } else
      checks.add(
          obj(
              "id",
              "tests",
              "state",
              "not_run",
              "detail",
              "Use --run-tests true only after reviewing the project profile"));
    return obj(
        "state",
        checks.stream().anyMatch(c -> c.get("state").equals("failed"))
            ? "blocked"
            : "ready_with_unknowns",
        "checks",
        checks);
  }

  private static void check(List<Map<String, Object>> all, String id, boolean pass, String detail) {
    all.add(obj("id", id, "state", pass ? "passed" : "failed", "detail", detail));
  }

  private static void probe(List<Map<String, Object>> all, String id, Path cwd, String[] command) {
    Process process = null;
    Path log = null;
    try {
      log = Files.createTempFile("agentfarm-doctor-", ".log");
      process =
          new ProcessBuilder(command)
              .directory(cwd.toFile())
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();
      if (!process.waitFor(id.startsWith("test.") ? 600 : 15, TimeUnit.SECONDS))
        throw new java.io.IOException("Command timed out");
      check(
          all,
          id,
          process.exitValue() == 0,
          process.exitValue() == 0
              ? "Command succeeded"
              : "Command failed; check tool authentication/setup locally");
    } catch (Exception e) {
      check(all, id, false, "Tool unavailable, failed or timed out");
    } finally {
      if (process != null && process.isAlive()) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
      if (log != null)
        try {
          Files.deleteIfExists(log);
        } catch (Exception ignored) {
        }
    }
  }
}
