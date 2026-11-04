package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.AgentSandbox;
import io.agentfarm.core.SandboxProbe;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxProbeTest {
  @TempDir Path dir;

  /**
   * Stands in for Codex: runs what follows "--" unmasked, so a hidden path that exists stays
   * readable.
   */
  Path codex(String body) throws Exception {
    Path script = dir.resolve("codex");
    Files.writeString(script, "#!/bin/sh\n" + body + "\n");
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
    return script;
  }

  static final String UNMASKED = "while [ \"$1\" != -- ]; do shift; done; shift; exec \"$@\"";

  Map<String, Object> config(Path checkout, Path... hidden) {
    var filesystem = new LinkedHashMap<String, Object>();
    filesystem.put(":root", "read");
    for (Path path : hidden) filesystem.put(path.toString(), "none");
    filesystem.put(checkout.toString(), "write");
    return AgentSandbox.codexConfig(filesystem);
  }

  Optional<String> run(Path codex, Path checkout, Path... hidden) throws Exception {
    return SandboxProbe.check(
        SandboxProbe.command(codex.toString(), config(checkout, hidden), checkout), checkout, 10);
  }

  @Test
  void passesTheProfileToCodexWithTheNetworkOff() {
    var argv = SandboxProbe.command("codex", config(dir, Path.of("/x y")), dir);
    assertEquals(
        List.of("codex", "sandbox", "-c", "default_permissions=\"agentfarm\"", "-c"),
        argv.subList(0, 5));
    assertEquals(
        "permissions.agentfarm.filesystem={\":root\"=\"read\",\"/x y\"=\"none\",\""
            + dir
            + "\"=\"write\"}",
        argv.get(5));
    assertEquals("permissions.agentfarm.network.enabled=false", argv.get(7));
    assertEquals("/x y", argv.get(argv.size() - 1), "probes only the hidden paths");
    assertEquals(dir.toString(), argv.get(argv.size() - 2));
  }

  @Test
  void passesWhenNothingHiddenIsReachable() throws Exception {
    assertEquals(Optional.empty(), run(codex(UNMASKED), dir, dir.resolve("absent")));
  }

  @Test
  void failsWhenCodexLeavesAHiddenPathReadable() throws Exception {
    Path secret = Files.writeString(dir.resolve("secret"), "must-not-appear");
    var problem = run(codex(UNMASKED), dir, secret).orElseThrow();
    assertTrue(problem.contains("EXPUESTO " + secret), problem);
    assertFalse(problem.contains("must-not-appear"), "never reads the content");
  }

  @Test
  void failsWhenItCannotTell() throws Exception {
    assertTrue(run(codex("exit 0"), dir).isPresent(), "no confirmation is not a pass");
    assertTrue(
        run(codex("echo unknown subcommand; exit 2"), dir)
            .orElseThrow()
            .contains("unknown subcommand"));
    assertTrue(
        run(dir.resolve("missing"), dir).orElseThrow().startsWith("no se pudo ejecutar Codex"));
    var slow = SandboxProbe.command(codex("sleep 30").toString(), config(dir), dir);
    assertTrue(SandboxProbe.check(slow, dir, 1).orElseThrow().contains("superó"));
    assertTrue(
        SandboxProbe.check(
                SandboxProbe.command(codex(UNMASKED).toString(), config(dir), dir.resolve("gone")),
                dir,
                10)
            .orElseThrow()
            .contains("SIN ACCESO"));
  }
}
