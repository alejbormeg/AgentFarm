package io.agentfarm.core;

import static io.agentfarm.core.Json.obj;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Codex's read-only mode can read the whole disk, including the operator's credentials. This
 * profile hides what agents never need. It is a deny list, so other user files stay readable.
 */
public final class AgentSandbox {
  public static final String PROFILE = "agentfarm";
  private static final List<String> SECRETS =
      List.of(
          ".ssh", ".aws", ".azure", ".gnupg", ".kube", ".docker", ".config", ".netrc",
          ".git-credentials", ".npmrc", ".pypirc", ".claude", ".codex/auth.json",
          "agentfarm-backups");

  private AgentSandbox() {}

  /** Paths are real ones: the sandbox cannot mount over a symlink that leaves the home. */
  public static Map<String, Object> filesystem(
      Path home, Path state, Path mounts, Path checkout, boolean write) throws IOException {
    var denied = new TreeSet<Path>();
    for (String secret : SECRETS) denied.add(home.resolve(secret));
    Path workspaces = checkout.getParent();
    if (Files.isDirectory(state))
      try (var entries = Files.list(state)) {
        entries.filter(e -> !e.equals(workspaces)).forEach(denied::add);
      }
    if (workspaces != null && workspaces.startsWith(state))
      try (var entries = Files.list(workspaces)) {
        entries.filter(e -> !e.equals(checkout)).forEach(denied::add);
      }
    boolean hideMounts = Files.isDirectory(mounts);
    var filesystem = new LinkedHashMap<String, Object>();
    filesystem.put(":root", "read");
    var real = new TreeSet<Path>();
    for (Path path : denied) if (Files.exists(path)) real.add(path.toRealPath());
    for (Path path : real)
      if (!hideMounts || !path.startsWith(mounts)) filesystem.put(path.toString(), "none");
    if (hideMounts) filesystem.put(mounts.toString(), "none");
    if (write) {
      filesystem.put(checkout.toString(), "write");
      filesystem.put("/tmp", "write");
    }
    return filesystem;
  }

  /** The config entries a Codex thread needs to apply the profile. */
  public static Map<String, Object> codexConfig(Map<String, Object> filesystem) {
    return obj(
        "default_permissions", PROFILE, "permissions", obj(PROFILE, obj("filesystem", filesystem)));
  }
}
