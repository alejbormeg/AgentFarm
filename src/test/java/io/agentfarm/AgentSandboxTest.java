package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.AgentSandbox;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSandboxTest {
  @TempDir Path dir;

  Path mkdirs(Path path) throws Exception {
    return Files.createDirectories(path);
  }

  @Test
  void hidesCredentialsStateAndOtherWorkspacesButNotTheCheckout() throws Exception {
    Path root = dir.toRealPath(), home = mkdirs(root.resolve("home"));
    mkdirs(home.resolve(".config/gh"));
    Files.createDirectories(home.resolve(".codex"));
    Files.writeString(home.resolve(".codex/auth.json"), "{}");
    Files.writeString(home.resolve(".codex/config.toml"), "");
    Path state = mkdirs(home.resolve(".local/state/agentfarm"));
    Files.writeString(state.resolve("node-token"), "x");
    Path checkout = mkdirs(state.resolve("workspaces/mine"));
    Path other = mkdirs(state.resolve("workspaces/other"));
    var fs = AgentSandbox.filesystem(home, state, root.resolve("mnt"), checkout, false, java.util.List.of());
    assertEquals("read", fs.get(":root"));
    for (Path hidden :
        new Path[] {home.resolve(".config"), home.resolve(".codex/auth.json"), state.resolve("node-token"), other})
      assertEquals("none", fs.get(hidden.toString()), hidden.toString());
    assertFalse(fs.containsKey(checkout.toString()), "reading needs no grant: root is readable");
    assertFalse(fs.containsKey(state.resolve("workspaces").toString()), "the sandbox fails to nest under a denied parent");
    assertFalse(fs.containsKey(home.resolve(".codex/config.toml").toString()));
    assertFalse(fs.containsKey(home.resolve(".ssh").toString()), "a missing path cannot be mounted over");
    assertFalse(fs.containsKey("/tmp"), "read-only role");
  }

  @Test
  void writingIsLimitedToTheCheckoutAndTemporaryFiles() throws Exception {
    Path root = dir.toRealPath(), home = mkdirs(root.resolve("home")), state = home.resolve("state");
    Path checkout = mkdirs(root.resolve("work/mine"));
    var fs = AgentSandbox.filesystem(home, state, root.resolve("mnt"), checkout, true, java.util.List.of());
    assertEquals("write", fs.get(checkout.toString()));
    assertEquals("write", fs.get("/tmp"));
  }

  @Test
  void followsSymlinksAndHidesMountedDisksAsAWhole() throws Exception {
    Path root = dir.toRealPath(), home = mkdirs(root.resolve("home")), mnt = mkdirs(root.resolve("mnt"));
    Path windows = mkdirs(mnt.resolve("c/Users/me/.aws"));
    Files.createSymbolicLink(home.resolve(".aws"), windows);
    Path secrets = mkdirs(root.resolve("vault"));
    Files.createSymbolicLink(home.resolve(".gnupg"), secrets);
    var fs = AgentSandbox.filesystem(home, home.resolve("state"), mnt, mkdirs(root.resolve("work/mine")), false, java.util.List.of());
    assertEquals("none", fs.get(mnt.toString()));
    assertEquals("none", fs.get(secrets.toString()));
    assertFalse(fs.containsKey(home.resolve(".aws").toString()));
    assertFalse(fs.containsKey(windows.toString()), "already inside the hidden mount");
  }

  @Test
  void hidesContainerSocketsThatExistAndFollowsTheirLinks() throws Exception {
    Path root = dir.toRealPath(), home = mkdirs(root.resolve("home")), run = mkdirs(root.resolve("run"));
    Path socket = Files.createFile(run.resolve("docker.sock"));
    Path varRun = Files.createSymbolicLink(root.resolve("var-docker.sock"), socket);
    var fs =
        AgentSandbox.filesystem(
            home,
            home.resolve("state"),
            root.resolve("mnt"),
            mkdirs(root.resolve("work/mine")),
            true,
            java.util.List.of(socket, varRun, run.resolve("podman.sock")));
    assertEquals("none", fs.get(socket.toString()));
    assertFalse(fs.containsKey(varRun.toString()), "the sandbox hides the target, not the link");
    assertFalse(fs.containsKey(run.resolve("podman.sock").toString()), "a missing path cannot be mounted over");
  }

  @Test
  void addsTheSessionDirectoryAndACustomDockerHost() {
    var plain = AgentSandbox.hostSockets(java.util.Map.of(), "1000");
    assertTrue(plain.containsAll(AgentSandbox.CONTAINER_SOCKETS));
    assertTrue(plain.contains(Path.of("/run/user/1000")));
    var custom =
        AgentSandbox.hostSockets(
            java.util.Map.of("XDG_RUNTIME_DIR", "/custom/run", "DOCKER_HOST", "unix:///srv/docker.sock"), "1000");
    assertTrue(custom.contains(Path.of("/custom/run")));
    assertTrue(custom.contains(Path.of("/srv/docker.sock")));
    assertFalse(
        AgentSandbox.hostSockets(java.util.Map.of("DOCKER_HOST", "tcp://x:2375"), "1000").stream()
            .anyMatch(p -> p.toString().contains("x:2375")));
  }

  @Test
  void theNetworkIsOffExplicitly() {
    var config = io.agentfarm.core.Json.MAPPER.valueToTree(AgentSandbox.codexConfig(java.util.Map.of(":root", "read")));
    assertFalse(config.path("permissions").path("agentfarm").path("network").path("enabled").asBoolean(true));
  }
}
