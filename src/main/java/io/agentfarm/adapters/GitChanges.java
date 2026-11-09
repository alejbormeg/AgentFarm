package io.agentfarm.adapters;

import io.agentfarm.core.ChangePolicy;
import io.agentfarm.core.SecretScan;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Reads what a candidate changes straight from Git, for the policies in the core: both sides of
 * every change, every path of a commit, and the whole diff for the secret scan. Never through a
 * helper that keeps only the tail of a command's output. The executor ships this class.
 */
public final class GitChanges {
  private GitChanges() {}

  /** Both sides of every change between two commits, renames split into deletion and addition. */
  public static List<ChangePolicy.Change> changes(Path repo, String base, String candidate) {
    for (String ref : List.of(base, candidate))
      if (ref == null || !ref.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"))
        throw new IllegalArgumentException("Full immutable commit SHA required");
    return ChangePolicy.parseRaw(
        git(
            repo,
            "-c",
            "core.quotePath=false",
            "diff",
            "--raw",
            "-z",
            "--no-renames",
            "--no-ext-diff",
            "--no-textconv",
            "--ignore-submodules=none",
            base,
            candidate,
            "--"));
  }

  /** The most diff a secret scan reads; past it the scan is incomplete, never clean. */
  public static final long SCAN_LIMIT = 50_000_000;

  /**
   * Scans everything a candidate adds, binary files read as text, before anything is pushed. A diff
   * Git cannot produce in time or that is too large is reported as incomplete.
   */
  public static SecretScan.Coverage secretScan(Path repo, String base, String candidate) {
    for (String ref : List.of(base, candidate))
      if (ref == null || !ref.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"))
        throw new IllegalArgumentException("Full immutable commit SHA required");
    byte[] diff;
    try {
      diff =
          git(
              repo,
              "-c",
              "core.quotePath=false",
              "diff",
              "--text",
              "--no-color",
              "--no-ext-diff",
              "--no-textconv",
              "--unified=0",
              base,
              candidate,
              "--");
    } catch (IllegalArgumentException e) {
      return SecretScan.Coverage.of(List.of(), List.of("Git no pudo dar el diff entero"));
    }
    try {
      return SecretScan.scan(new java.io.ByteArrayInputStream(diff), SCAN_LIMIT);
    } catch (java.io.IOException e) {
      return SecretScan.Coverage.of(List.of(), List.of("no se pudo leer el diff"));
    }
  }

  /** Every path in a commit, to find names that collide when case is ignored. */
  public static List<String> tree(Path repo, String commit) {
    if (commit == null || !commit.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"))
      throw new IllegalArgumentException("Full immutable commit SHA required");
    var paths = new ArrayList<String>();
    for (String p :
        ChangePolicy.utf8(git(repo, "ls-tree", "-r", "-z", "--name-only", "--full-tree", commit))
            .split("\u0000")) if (!p.isEmpty()) paths.add(p);
    return paths;
  }

  private static byte[] git(Path repo, String... args) {
    Path output = null;
    try {
      output = Files.createTempFile("agentfarm-git-", ".out");
      var command =
          new ArrayList<>(
              List.of("git", "--no-pager", "--no-replace-objects", "-C", repo.toString()));
      command.addAll(List.of(args));
      var pb =
          new ProcessBuilder(command)
              .redirectOutput(output.toFile())
              .redirectError(ProcessBuilder.Redirect.DISCARD);
      pb.environment().put("GIT_TERMINAL_PROMPT", "0");
      pb.environment().put("GIT_NO_LAZY_FETCH", "1");
      Process process = pb.start();
      process.getOutputStream().close();
      if (!process.waitFor(120, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalArgumentException("Git command timed out");
      }
      if (process.exitValue() != 0 || Files.size(output) > SCAN_LIMIT)
        throw new IllegalArgumentException("Git could not read the candidate");
      return Files.readAllBytes(output);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Git read interrupted", e);
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("Git unavailable or unreadable repository", e);
    } finally {
      if (output != null)
        try {
          Files.deleteIfExists(output);
        } catch (Exception ignored) {
        }
    }
  }
}
