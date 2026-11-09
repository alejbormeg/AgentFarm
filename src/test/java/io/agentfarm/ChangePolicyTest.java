package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.adapters.GitChanges;
import io.agentfarm.core.*;
import io.agentfarm.core.ChangePolicy.Change;
import io.agentfarm.core.ChangePolicy.GitHubFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The single scope policy, over real Git where it matters: renames, modes and odd names. */
class ChangePolicyTest {
  @TempDir Path repo;
  String base;
  static final List<String> DOCS = List.of("docs/");

  String git(String... args) throws Exception {
    var command = new ArrayList<>(List.of("git", "-C", repo.toString()));
    command.addAll(List.of(args));
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), out);
    return out.trim();
  }

  String commit() throws Exception {
    git("add", "-A");
    git("commit", "-q", "--allow-empty", "-m", "c");
    return git("rev-parse", "HEAD");
  }

  void write(String path, String text) throws Exception {
    Path file = repo.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  List<Map<String, Object>> violations(String candidate) {
    return ChangePolicy.violations(
        DOCS,
        GitChanges.changes(repo, base, candidate),
        GitChanges.tree(repo, candidate),
        ChangePolicy.PROFILE_PROTECTED,
        Set.of(Commandments.PLATFORM_WORKFLOW));
  }

  List<String> reasons(String candidate) {
    return violations(candidate).stream().map(v -> v.get("path") + ":" + v.get("reason")).toList();
  }

  @BeforeEach
  void setUp() throws Exception {
    git("init", "-q");
    git("config", "user.email", "t@example.com");
    git("config", "user.name", "t");
    git("config", "core.hooksPath", repo.resolve("no-hooks").toString());
    write("private/guard.txt", "keep\n");
    write("docs/readme.md", "hi\n");
    base = commit();
  }

  @Test
  void movingAFileOutOfScopeIsADeletionOutsideIt() throws Exception {
    git("mv", "private/guard.txt", "docs/guard.txt");
    String candidate = commit();
    assertEquals(
        "docs/guard.txt", git("diff", "--name-only", base, candidate), "what the old check saw");
    assertEquals(List.of("private/guard.txt:deleted_outside_allowed_paths"), reasons(candidate));
  }

  @Test
  void movingAFileIntoForbiddenGroundIsRefusedToo() throws Exception {
    git("mv", "docs/readme.md", "private/readme.md");
    assertEquals(List.of("private/readme.md:outside_allowed_paths"), reasons(commit()));
  }

  @Test
  void aSymlinkOrSubmoduleInScopeIsRefused() throws Exception {
    Files.createSymbolicLink(repo.resolve("docs/link"), Path.of("../private/guard.txt"));
    assertEquals(List.of("docs/link:symlink_or_submodule"), reasons(commit()));
    git("update-index", "--add", "--cacheinfo", "160000," + base + ",docs/sub");
    git("commit", "-q", "-m", "sub");
    assertTrue(reasons(git("rev-parse", "HEAD")).contains("docs/sub:symlink_or_submodule"));
  }

  @Test
  void aFileTurnedIntoASymlinkIsRefused() throws Exception {
    Files.delete(repo.resolve("docs/readme.md"));
    Files.createSymbolicLink(repo.resolve("docs/readme.md"), Path.of("/etc/passwd"));
    assertEquals(List.of("docs/readme.md:symlink_or_submodule"), reasons(commit()));
  }

  @Test
  void protectedPathsAndCollidingNamesAreRefused() throws Exception {
    write(".gitmodules", "[submodule]\n");
    write(".github/workflows/ci.yml", "on: push\n");
    write(Commandments.PLATFORM_WORKFLOW, "on: push\n");
    write("docs/Readme.md", "clash\n");
    var found = reasons(commit());
    assertTrue(found.contains(".gitmodules:protected_path"), found.toString());
    assertTrue(found.contains(".github/workflows/ci.yml:protected_path"), found.toString());
    assertTrue(found.contains("docs/Readme.md:case_collision"), found.toString());
    assertTrue(
        found.stream().noneMatch(f -> f.startsWith(Commandments.PLATFORM_WORKFLOW)),
        "the platform's own workflow is exempt");
  }

  @Test
  void nonPortableNamesAreRefused() throws Exception {
    write("docs/con.txt", "x\n");
    write("docs/a:b.md", "x\n");
    var found = reasons(commit());
    assertTrue(found.contains("docs/con.txt:unsupported_path"), found.toString());
    assertTrue(found.contains("docs/a:b.md:unsupported_path"), found.toString());
  }

  @Test
  void legitimateUnicodeChangesPass() throws Exception {
    write("docs/guía/año ñandú 日本.md", "hola\n");
    git("mv", "docs/readme.md", "docs/léeme.md");
    String candidate = commit();
    assertEquals(List.of(), violations(candidate));
    assertTrue(
        ChangePolicy.paths(GitChanges.changes(repo, base, candidate))
            .contains("docs/guía/año ñandú 日本.md"),
        "names are read verbatim, without Git's quoting");
  }

  @Test
  void onlyFullCommitShasReachGit() {
    assertThrows(IllegalArgumentException.class, () -> GitChanges.changes(repo, "HEAD", base));
    assertThrows(
        IllegalArgumentException.class, () -> GitChanges.changes(repo, "--output=x", base));
    assertThrows(IllegalArgumentException.class, () -> GitChanges.tree(repo, "-r"));
  }

  @Test
  void gitHubRenamesNameBothSides() {
    var changes =
        ChangePolicy.fromGitHub(
            List.of(
                new GitHubFile("docs/guard.txt", "renamed", "private/guard.txt"),
                new GitHubFile("docs/new.md", "added", ""),
                new GitHubFile("docs/old.md", "removed", "")));
    assertEquals(
        List.of(
            new Change("private/guard.txt", "deleted", null, null),
            new Change("docs/guard.txt", "added", null, null),
            new Change("docs/new.md", "added", null, null),
            new Change("docs/old.md", "deleted", null, null)),
        changes);
    assertEquals(
        List.of("private/guard.txt:deleted_outside_allowed_paths"),
        ChangePolicy.violations(DOCS, changes, null, List.of(), Set.of()).stream()
            .map(v -> v.get("path") + ":" + v.get("reason"))
            .toList());
  }

  @Test
  void rawOutputWithRenamesIsRefused() {
    byte[] raw =
        ":100644 100644 aaaaaaa bbbbbbb R100\u0000a\u0000b\u0000".getBytes(StandardCharsets.UTF_8);
    assertThrows(IllegalArgumentException.class, () -> ChangePolicy.parseRaw(raw));
    assertThrows(
        IllegalArgumentException.class,
        () -> ChangePolicy.parseRaw(new byte[] {(byte) 0xff, 0}),
        "non-UTF-8 names are refused, not guessed");
  }
}
