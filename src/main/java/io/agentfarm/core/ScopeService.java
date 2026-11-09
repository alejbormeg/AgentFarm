package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class ScopeService {
  public static final List<String> PROTECTED =
      List.of("AGENTS.md", ".github/", ".agentfarm/", ".git/");

  /** A literal, portable, relative path; see {@link ChangePolicy#portablePath}. */
  public static void scopedPath(String value, boolean directory) {
    ChangePolicy.portablePath(value, directory);
  }

  private record GitResult(int code, byte[] bytes) {}

  private GitResult git(Path repo, Set<Integer> accepted, String... args) {
    Path output = null;
    try {
      output = Files.createTempFile("agentfarm-git-", ".out");
      List<String> command =
          new ArrayList<>(
              List.of("git", "--no-pager", "--no-replace-objects", "-C", repo.toString()));
      command.addAll(List.of(args));
      ProcessBuilder pb =
          new ProcessBuilder(command)
              .redirectInput(ProcessBuilder.Redirect.PIPE)
              .redirectOutput(output.toFile())
              .redirectError(ProcessBuilder.Redirect.DISCARD);
      pb.environment().put("GIT_TERMINAL_PROMPT", "0");
      pb.environment().put("GIT_NO_LAZY_FETCH", "1");
      Process process = pb.start();
      process.getOutputStream().close();
      if (!process.waitFor(15, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalArgumentException("Git command timed out");
      }
      if (!accepted.contains(process.exitValue()) || Files.size(output) > 50_000_000)
        throw new IllegalArgumentException("Git could not read requested repository or commits");
      return new GitResult(process.exitValue(), Files.readAllBytes(output));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Scope check interrupted", e);
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

  private String commit(Path repo, String sha) {
    if (sha == null || !sha.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"))
      throw new IllegalArgumentException("Full immutable commit SHA required");
    String resolved =
        new String(
                git(repo, Set.of(0), "rev-parse", "--verify", sha + "^{commit}").bytes(),
                StandardCharsets.UTF_8)
            .trim();
    if (!resolved.equals(sha.toLowerCase(Locale.ROOT)))
      throw new IllegalArgumentException("SHA must directly identify a commit");
    return resolved;
  }

  private Map<String, List<String>> snapshot(Path repo, String sha) {
    byte[] bytes = git(repo, Set.of(0), "ls-tree", "-r", "-z", "--full-tree", sha).bytes();
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("Non-UTF-8 Git paths unsupported");
    }
    Map<String, List<String>> result = new TreeMap<>();
    for (String record : text.split("\u0000"))
      if (!record.isEmpty()) {
        int tab = record.indexOf('\t');
        String[] metadata = record.substring(0, tab).split(" ");
        result.put(record.substring(tab + 1), List.of(metadata));
      }
    return result;
  }

  public Map<String, Object> check(Path repo, JsonNode spec, String base, String candidate) {
    CoordinatorService.validate(spec);
    List<String> allowed = new ArrayList<>();
    spec.get("allowed_paths")
        .forEach(v -> {
          scopedPath(v.asText(), true);
          allowed.add(v.asText());
        });
    base = commit(repo, base);
    candidate = commit(repo, candidate);
    if (git(repo, Set.of(0, 1), "merge-base", "--is-ancestor", base, candidate).code() != 0)
      throw new IllegalArgumentException("Candidate must descend from base");
    var before = snapshot(repo, base);
    var after = snapshot(repo, candidate);
    Set<String> paths = new TreeSet<>(before.keySet());
    paths.addAll(after.keySet());
    List<Map<String, Object>> changes = new ArrayList<>();
    List<ChangePolicy.Change> policyChanges = new ArrayList<>();
    for (String path : paths) {
      var old = before.get(path);
      var next = after.get(path);
      if (Objects.equals(old, next)) continue;
      String status = old == null ? "added" : next == null ? "deleted" : "modified";
      changes.add(obj("path", path, "status", status, "before", old, "after", next));
      policyChanges.add(
          new ChangePolicy.Change(
              path,
              status,
              old == null ? null : old.getFirst(),
              next == null ? null : next.getFirst()));
    }
    List<Map<String, Object>> violations =
        ChangePolicy.violations(allowed, policyChanges, after.keySet(), PROTECTED, Set.of());
    return obj(
        "mode",
        "scope_validation",
        "policy_version",
        "scope-v1",
        "protected_paths",
        PROTECTED,
        "spec_hash",
        sha256(canonical(spec)),
        "base_sha",
        base,
        "candidate_sha",
        candidate,
        "state",
        !changes.isEmpty() && violations.isEmpty() ? "passed" : "blocked",
        "reason",
        changes.isEmpty()
            ? "no_changes"
            : violations.isEmpty() ? "within_scope" : "scope_violation",
        "changes",
        changes,
        "violations",
        violations,
        "implementation_verified",
        false,
        "human_approved",
        false);
  }
}
