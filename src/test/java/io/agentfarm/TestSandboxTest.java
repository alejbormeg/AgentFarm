package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.TestSandbox;
import io.agentfarm.core.TestSandbox.Output;
import io.agentfarm.core.TestSandbox.Policy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TestSandboxTest {
  static final String SHA = "a".repeat(40);
  @TempDir Path repo;

  /** Records docker invocations; git and tar run for real against a fake export. */
  static class Fake implements TestSandbox.Runner {
    final List<List<String>> calls = new ArrayList<>();
    final Map<String, Output> answers = new HashMap<>();

    @Override
    public Output run(List<String> argv, Duration timeout) throws Exception {
      calls.add(argv);
      if (argv.get(0).equals("git")) {
        Files.write(Path.of(argv.get(argv.size() - 2)), new byte[0]);
        return new Output(0, "");
      }
      if (argv.get(0).equals("tar")) return new Output(0, "");
      String command = argv.get(argv.size() - 1);
      return answers.getOrDefault(command, new Output(0, "ok"));
    }

    List<String> docker() {
      return calls.stream()
          .filter(c -> c.get(0).equals("docker"))
          .map(c -> c.get(1) + (c.get(1).equals("exec") ? " " + c.get(3) + " " + c.get(c.size() - 1) : ""))
          .toList();
    }
  }

  Map<String, String> tests(String unit, String integration) {
    var t = new LinkedHashMap<String, String>();
    t.put("unit", unit);
    t.put("integration", integration);
    return t;
  }

  @Test
  void setupAsRootThenTestsOfflineAsHostUserAndAlwaysCleansUp() throws Exception {
    var fake = new Fake();
    fake.answers.put("make it", new Output(2, "boom"));
    var results =
        new TestSandbox(fake, "docker", "1000:1000")
            .run(repo, SHA, Policy.defaults("img:1"), "apt-get install -y x", tests("make test", "make it"));
    assertEquals(
        List.of(
            "create",
            "start",
            "exec 0:0 apt-get install -y x",
            "exec 0:0 chown -R 1000:1000 /workspace /tmp",
            "network",
            "exec 1000:1000 make test",
            "exec 1000:1000 make it",
            "exec 0:0 chown -R 1000:1000 /workspace /tmp",
            "rm"),
        fake.docker());
    assertEquals("passed", results.get(0).get("status"));
    assertEquals("failed", results.get(1).get("status"));
    assertEquals("boom", results.get(1).get("output"));
    var create = fake.calls.stream().filter(c -> c.contains("create")).findFirst().orElseThrow();
    assertTrue(create.containsAll(List.of("--cap-drop", "ALL", "no-new-privileges", "HOME=/tmp")));
    assertTrue(create.stream().noneMatch(a -> a.contains(".config") || a.contains("docker.sock")));
    assertTrue(
        create.stream().filter(a -> a.endsWith(":/workspace")).allMatch(a -> !a.startsWith(repo.toString())),
        "the checkout itself (with .git) is never mounted");
  }

  @Test
  void failedSetupFailsEveryTestWithoutRunningThem() throws Exception {
    var fake = new Fake();
    fake.answers.put("bad setup", new Output(1, "no package"));
    var results =
        new TestSandbox(fake, "docker", "1000:1000")
            .run(repo, SHA, Policy.defaults("img:1"), "bad setup", tests("make test", "N/A: no integration layer"));
    assertTrue(results.stream().allMatch(r -> r.get("status").equals("failed")));
    assertTrue(fake.docker().stream().noneMatch(c -> c.contains("make test")));
    assertEquals("rm", fake.docker().getLast());
  }

  @Test
  void noNetworkPolicyNeverAttachesTheBridgeAndNotApplicableIsKept() throws Exception {
    var fake = new Fake();
    var results =
        new TestSandbox(fake, "docker", "1000:1000")
            .run(repo, SHA, new Policy("img:1", "none", 1, 512, 1), "", tests("make test", "N/A: no integration layer"));
    var create = fake.calls.stream().filter(c -> c.contains("create")).findFirst().orElseThrow();
    assertEquals("none", create.get(create.indexOf("--network") + 1));
    assertFalse(fake.docker().contains("network"));
    assertEquals("not_applicable", results.get(1).get("status"));
  }

  @Test
  void rejectsUnsafeInputs() {
    var sandbox = new TestSandbox(new Fake(), "docker", "1000:1000");
    assertThrows(
        IllegalArgumentException.class,
        () -> sandbox.run(repo, "HEAD", Policy.defaults("img:1"), "", tests("a", "b")));
    assertThrows(IllegalArgumentException.class, () -> new TestSandbox(new Fake(), "docker", "root"));
    assertThrows(IllegalArgumentException.class, () -> Policy.defaults("--privileged"));
    assertThrows(IllegalArgumentException.class, () -> new Policy("img:1", "host", 1, 512, 1));
  }

  // Real Docker: the container sees the commit tree only, offline, without host credentials.
  @Test
  void dockerIsolationEndToEnd() throws Exception {
    Assumptions.assumeTrue(run(List.of("docker", "info"), Duration.ofSeconds(20)).exit() == 0, "Docker unavailable");
    Assumptions.assumeTrue(
        run(List.of("docker", "pull", "-q", "bash:5.2"), Duration.ofMinutes(3)).exit() == 0, "Image unavailable");
    git("init", "-q");
    Files.writeString(repo.resolve("hello.txt"), "tree content");
    git("add", ".");
    git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init");
    String sha = git("rev-parse", "HEAD");
    var uid = Files.readAttributes(repo, "unix:uid,gid");
    var results =
        new TestSandbox(TestSandboxTest::run, "docker", uid.get("uid") + ":" + uid.get("gid"))
            .run(
                repo,
                sha,
                new Policy("bash:5.2", "setup", 1, 256, 2),
                "echo setup > /workspace/from-setup",
                tests(
                    "test \"$(cat hello.txt)\" = 'tree content' && test -f from-setup && test ! -e .git"
                        + " && test ! -e \"$HOME/.config/gh\" && test -z \"$GH_TOKEN\"",
                    "! wget -q -T 3 -O /dev/null http://example.com"));
    assertEquals("passed", results.get(0).get("status"), results.get(0).toString());
    assertEquals("passed", results.get(1).get("status"), results.get(1).toString());
    assertEquals("", run(List.of("docker", "ps", "-aq", "--filter", "label=agentfarm.sandbox=1", "--filter", "ancestor=bash:5.2"), Duration.ofSeconds(20)).text());
  }

  String git(String... args) throws Exception {
    var cmd = new ArrayList<>(List.of("git", "-C", repo.toString()));
    cmd.addAll(List.of(args));
    var out = run(cmd, Duration.ofSeconds(20));
    if (out.exit() != 0) throw new AssertionError(out.text());
    return out.text();
  }

  static Output run(List<String> argv, Duration timeout) throws Exception {
    var pb = new ProcessBuilder(argv).redirectErrorStream(true);
    pb.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
    var p = pb.start();
    p.getOutputStream().close();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
      p.destroyForcibly();
      return new Output(124, out);
    }
    return new Output(p.exitValue(), out.trim());
  }
}
