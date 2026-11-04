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
          ".ssh",
          ".aws",
          ".azure",
          ".gnupg",
          ".kube",
          ".docker",
          ".config",
          ".netrc",
          ".git-credentials",
          ".npmrc",
          ".pypirc",
          ".claude",
          ".codex/auth.json",
          "agentfarm-backups");

  /**
   * Reaching any of these is root on the host, so hiding them must not depend on the network being
   * off.
   */
  public static final List<Path> CONTAINER_SOCKETS =
      List.of(
          Path.of("/run/docker.sock"),
          Path.of("/var/run/docker.sock"),
          Path.of("/run/containerd/containerd.sock"),
          Path.of("/run/podman/podman.sock"),
          Path.of("/var/lib/docker"));

  private AgentSandbox() {}

  /**
   * What this host adds to {@link #CONTAINER_SOCKETS}: session sockets and any custom Docker host.
   */
  public static List<Path> hostSockets(Map<String, String> env, String uid) {
    var sockets = new ArrayList<>(CONTAINER_SOCKETS);
    String runtime = env.getOrDefault("XDG_RUNTIME_DIR", "");
    sockets.add(Path.of(runtime.startsWith("/") ? runtime : "/run/user/" + uid));
    String host = env.getOrDefault("DOCKER_HOST", "");
    if (host.startsWith("unix:///")) sockets.add(Path.of(host.substring("unix://".length())));
    return sockets;
  }

  /** Paths are real ones: the sandbox cannot mount over a symlink that leaves the home. */
  public static Map<String, Object> filesystem(
      Path home, Path state, Path mounts, Path checkout, boolean write, List<Path> sockets)
      throws IOException {
    var denied = new TreeSet<>(sockets);
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

  /**
   * Only research needs the web. A manipulated page must not reach the turns that write the code or
   * judge it, so the others turn search off instead of inheriting the user's Codex setting.
   */
  public static String webSearch(String role) {
    return role.equals("research") ? "live" : "disabled";
  }

  /**
   * What a profile hides, for the checks and for providers that take rules instead of a profile.
   */
  @SuppressWarnings("unchecked")
  public static List<String> hiddenPaths(Map<String, Object> config) {
    var permissions =
        (Map<String, Object>) ((Map<String, Object>) config.get("permissions")).get(PROFILE);
    var hidden = new ArrayList<String>();
    ((Map<String, Object>) permissions.get("filesystem"))
        .forEach((path, access) -> {
          if (access.equals("none")) hidden.add(path);
        });
    return hidden;
  }

  /** The config entries a Codex thread needs to apply the profile. */
  public static Map<String, Object> codexConfig(Map<String, Object> filesystem) {
    return obj(
        "default_permissions",
        PROFILE,
        "permissions",
        obj(PROFILE, obj("filesystem", filesystem, "network", obj("enabled", false))));
  }
}
