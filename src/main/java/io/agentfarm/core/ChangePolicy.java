package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/**
 * The one scope policy for a candidate: both sides of every change, read from Git without renames
 * and NUL-delimited, so moving a file out of scope shows up as a deletion outside it. The CLI's
 * scope check, the executor before pushing and the panel when verifying all ask this class. Git
 * itself is read by {@code io.agentfarm.adapters.GitChanges}.
 */
public final class ChangePolicy {
  /** A regular file, executable or not. Anything else is a symlink or a submodule. */
  static final Set<String> REGULAR = Set.of("100644", "100755");

  private static final String ABSENT = "000000";

  /**
   * What no candidate of an adopted project may touch: Git's own metadata and submodules, the
   * platform's state and the project's workflows. The platform's own workflow is exempt.
   */
  public static final List<String> PROFILE_PROTECTED =
      List.of(".git/", ".gitmodules", ".agentfarm/", ".github/workflows/");

  /** One path that changed; a mode is null when GitHub does not report it. */
  public record Change(String path, String status, String oldMode, String newMode) {
    public static Change of(String path, String status) {
      return new Change(path, status, null, null);
    }
  }

  private ChangePolicy() {}

  /**
   * A literal relative POSIX path that every disk can hold: no globs, no `..`, no reserved names.
   */
  public static void portablePath(String value, boolean directory) {
    if (value == null || value.isEmpty())
      throw new IllegalArgumentException("Nonempty literal path required");
    String path = directory && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    if (path.isEmpty()
        || path.chars().anyMatch(c -> c < 32 || c == 127)
        || path.matches(".*[\\\\:*?\\[\\]<>|\"].*"))
      throw new IllegalArgumentException("Literal relative POSIX path required");
    for (String part : path.split("/", -1)) {
      String stem = part.split("\\.", -1)[0].toLowerCase(Locale.ROOT);
      if (part.isEmpty()
          || part.equals(".")
          || part.equals("..")
          || part.endsWith(".")
          || part.endsWith(" ")
          || stem.matches("con|prn|aux|nul|com[1-9]|lpt[1-9]"))
        throw new IllegalArgumentException("Unsupported portable path");
    }
  }

  /** Parses `git diff --raw -z --no-renames`: ":old new oldsha newsha status\0path\0". */
  public static List<Change> parseRaw(byte[] raw) {
    String text = utf8(raw);
    var changes = new ArrayList<Change>();
    String[] fields = text.split("\u0000", -1);
    for (int i = 0; i + 1 < fields.length; i += 2) {
      String meta = fields[i];
      if (meta.isEmpty()) break;
      if (!meta.startsWith(":")) throw new IllegalArgumentException("Unexpected git diff output");
      String[] m = meta.substring(1).split(" ");
      if (m.length < 5) throw new IllegalArgumentException("Unexpected git diff output");
      String status = m[4].substring(0, 1);
      if (status.equals("R") || status.equals("C"))
        throw new IllegalArgumentException("Renames must be read with --no-renames");
      changes.add(
          new Change(
              fields[i + 1],
              switch (status) {
                case "A" -> "added";
                case "D" -> "deleted";
                default -> "modified";
              },
              m[0],
              m[1]));
    }
    return changes;
  }

  /** One entry of GitHub's compare files: `previous` is set for a rename. */
  public record GitHubFile(String filename, String status, String previous) {}

  /**
   * GitHub's compare files. A rename names both sides, and its origin counts as a deletion. GitHub
   * reports no modes, so symlinks are only caught by Git before the push.
   */
  public static List<Change> fromGitHub(List<GitHubFile> files) {
    var changes = new ArrayList<Change>();
    for (GitHubFile f : files) {
      if (f.status().equals("renamed") && f.previous() != null && !f.previous().isEmpty())
        changes.add(Change.of(f.previous(), "deleted"));
      changes.add(
          Change.of(
              f.filename(),
              switch (f.status()) {
                case "added", "renamed", "copied" -> "added";
                case "removed" -> "deleted";
                default -> "modified";
              }));
    }
    return changes;
  }

  /** Every path named by a change, both sides of a rename included. */
  public static List<String> paths(List<Change> changes) {
    return changes.stream().map(Change::path).distinct().toList();
  }

  /**
   * What breaks the scope: a path outside `allowed` on either side, a non-portable name, a symlink
   * or submodule on either side, a protected path, or a name that collides with another in the
   * candidate when case is ignored. `exempt` are paths the platform itself writes.
   */
  public static List<Map<String, Object>> violations(
      Collection<String> allowed,
      List<Change> changes,
      Collection<String> candidateTree,
      Collection<String> protectedPaths,
      Collection<String> exempt) {
    Map<String, Integer> folded = new HashMap<>();
    if (candidateTree != null) candidateTree.forEach(p -> folded.merge(fold(p), 1, Integer::sum));
    var violations = new ArrayList<Map<String, Object>>();
    for (Change c : changes) {
      String path = c.path();
      if (path.isBlank() || exempt.contains(path)) continue;
      try {
        portablePath(path, false);
      } catch (IllegalArgumentException e) {
        violations.add(obj("path", path, "reason", "unsupported_path"));
      }
      if (protectedPaths.stream().anyMatch(p -> matches(fold(path), fold(p))))
        violations.add(obj("path", path, "reason", "protected_path"));
      if (!ProjectProfile.permitted(allowed, path))
        violations.add(
            obj(
                "path",
                path,
                "reason",
                c.status().equals("deleted")
                    ? "deleted_outside_allowed_paths"
                    : "outside_allowed_paths"));
      if (special(c.oldMode()) || special(c.newMode()))
        violations.add(obj("path", path, "reason", "symlink_or_submodule"));
      if (!c.status().equals("deleted") && folded.getOrDefault(fold(path), 0) > 1)
        violations.add(obj("path", path, "reason", "case_collision"));
    }
    return violations;
  }

  /** One line per violation, for an error message. */
  public static String describe(List<Map<String, Object>> violations) {
    return String.join(
        ", ", violations.stream().map(v -> v.get("path") + " (" + v.get("reason") + ")").toList());
  }

  private static boolean special(String mode) {
    return mode != null && !mode.equals(ABSENT) && !REGULAR.contains(mode);
  }

  static String fold(String path) {
    return path.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
  }

  static boolean matches(String path, String scope) {
    return scope.endsWith("/") ? path.startsWith(scope) : path.equals(scope);
  }

  public static String utf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("Non-UTF-8 Git paths unsupported");
    }
  }
}
