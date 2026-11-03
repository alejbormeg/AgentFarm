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
    var fs = AgentSandbox.filesystem(home, state, root.resolve("mnt"), checkout, false);
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
    var fs = AgentSandbox.filesystem(home, state, root.resolve("mnt"), checkout, true);
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
    var fs = AgentSandbox.filesystem(home, home.resolve("state"), mnt, mkdirs(root.resolve("work/mine")), false);
    assertEquals("none", fs.get(mnt.toString()));
    assertEquals("none", fs.get(secrets.toString()));
    assertFalse(fs.containsKey(home.resolve(".aws").toString()));
    assertFalse(fs.containsKey(windows.toString()), "already inside the hidden mount");
  }
}
