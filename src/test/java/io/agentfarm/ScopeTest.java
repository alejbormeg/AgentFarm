package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ScopeTest {
  @TempDir Path repo;
  String base;
  ScopeService service = new ScopeService();

  JsonNode spec() {
    return Json.parse(
        "{\"id\":\"T1\",\"project\":\"demo\",\"title\":\"Test\",\"acceptance\":[\"Evidence\"],\"allowed_paths\":[\"docs/\"]}");
  }

  String git(String... args) throws Exception {
    List<String> cmd = new ArrayList<>(List.of("git", "-C", repo.toString()));
    cmd.addAll(List.of(args));
    var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
    pb.environment()
        .put(
            "GIT_CONFIG_GLOBAL",
            System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
    var p = pb.start();
    p.getOutputStream().close();
    String out =
        new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0)
      throw new AssertionError(out);
    return out.trim();
  }

  void write(String path, String content) throws Exception {
    Path file = repo.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  String commit() throws Exception {
    git("commit", "-m", "fixture");
    return git("rev-parse", "HEAD");
  }

  String changed() throws Exception {
    git("add", "--all");
    return commit();
  }

  JsonNode check(String candidate) {
    return Json.MAPPER.valueToTree(service.check(repo, spec(), base, candidate));
  }

  @BeforeEach
  void setup() throws Exception {
    git("init", "-b", "main");
    git("config", "user.name", "Scope tests");
    git("config", "user.email", "scope@example.invalid");
    git("config", "commit.gpgsign", "false");
    git("config", "core.hooksPath", repo.resolve("no-hooks").toString());
    write("docs/a.md", "initial\n");
    write("src/app.txt", "initial\n");
    git("add", ".");
    base = commit();
  }

  @Test
  void recordsAddModifyDeleteAndIdentity() throws Exception {
    write("docs/a.md", "changed\n");
    write("docs/new.md", "new\n");
    String sha = changed();
    var r = check(sha);
    assertEquals("passed", r.get("state").asText());
    assertEquals(base, r.get("base_sha").asText());
    assertEquals(sha, r.get("candidate_sha").asText());
    assertFalse(r.get("human_approved").asBoolean());
    Files.delete(repo.resolve("docs/a.md"));
    assertTrue(check(changed()).toString().contains("deleted"));
  }

  @Test
  void renameChecksOrigin() throws Exception {
    git("mv", "src/app.txt", "docs/app.txt");
    assertEquals("blocked", check(commit()).get("state").asText());
  }

  @Test
  void renameChecksDestination() throws Exception {
    git("mv", "docs/a.md", "src/a.md");
    assertEquals("blocked", check(commit()).get("state").asText());
  }

  @Test
  void directoryPrefixDoesNotAllowSibling() throws Exception {
    write("docs-secret.txt", "outside");
    assertEquals("blocked", check(changed()).get("state").asText());
  }

  @Test
  void fileScopeIsExact() throws Exception {
    var s = (com.fasterxml.jackson.databind.node.ObjectNode) spec();
    s.set("allowed_paths", Json.MAPPER.valueToTree(List.of("docs/a.md")));
    write("docs/a.md.backup", "outside");
    assertEquals("blocked", service.check(repo, s, base, changed()).get("state"));
  }

  @Test
  void protectedPathsOverrideExplicitScope() throws Exception {
    write("AGENTS.md", "protected");
    var s = (com.fasterxml.jackson.databind.node.ObjectNode) spec();
    s.set("allowed_paths", Json.MAPPER.valueToTree(List.of("AGENTS.md")));
    assertTrue(Json.encode(service.check(repo, s, base, changed())).contains("protected_path"));
  }

  @Test
  void protectionIgnoresCase() throws Exception {
    write(".GitHub/workflows/task.yml", "protected");
    assertTrue(check(changed()).toString().contains("protected_path"));
  }

  @Test
  void symlinkIsBlockedWithoutFollowing() throws Exception {
    String oid = git("hash-object", "-w", "docs/a.md");
    git("update-index", "--add", "--cacheinfo", "120000," + oid + ",docs/link");
    assertTrue(check(commit()).toString().contains("symlink_or_submodule"));
  }

  @Test
  void submoduleIsBlocked() throws Exception {
    git("update-index", "--add", "--cacheinfo", "160000," + base + ",docs/vendor");
    assertTrue(check(commit()).toString().contains("symlink_or_submodule"));
  }

  @Test
  void modeOnlyChangeIsVisible() throws Exception {
    git("update-index", "--chmod=+x", "docs/a.md");
    assertEquals("passed", check(commit()).get("state").asText());
  }

  @Test
  void noChangeIsBlocked() {
    assertEquals("no_changes", check(base).get("reason").asText());
  }

  @Test
  void rejectsBranchesMissingCommitsAndBlobIds() throws Exception {
    String blob = git("rev-parse", "HEAD:docs/a.md");
    for (String value :
        List.of("HEAD", "main", base.substring(0, 12), "--all", "0".repeat(40), blob))
      assertThrows(IllegalArgumentException.class, () -> check(value));
  }

  @Test
  void rejectsUnrelatedCommit() throws Exception {
    String orphan = git("commit-tree", git("rev-parse", "HEAD^{tree}"), "-m", "orphan");
    assertThrows(IllegalArgumentException.class, () -> check(orphan));
  }

  @Test
  void rejectsUnsafeScopes() {
    for (String scope :
        List.of(
            "/",
            "./",
            "docs//",
            "docs/../src",
            "C:/private",
            "\\private",
            "docs/*",
            "docs/[ab]",
            "docs/\n",
            ".github./",
            "docs /",
            "docs/NUL",
            "docs/com1.txt"))
      assertThrows(IllegalArgumentException.class, () -> ScopeService.scopedPath(scope, true));
  }

  @Test
  void checkDoesNotModifyCheckoutAndDoesNotClaimUncommittedChanges() throws Exception {
    write("docs/a.md", "commit");
    String sha = changed();
    write("src/app.txt", "uncommitted");
    write("untracked.txt", "fixture");
    String before = git("status", "--porcelain=v1", "-uall");
    assertEquals("passed", check(sha).get("state").asText());
    assertEquals(before, git("status", "--porcelain=v1", "-uall"));
  }

  @Test
  void changedCaseCollisionIsBlocked() throws Exception {
    String oid = git("hash-object", "-w", "docs/a.md");
    git("update-index", "--add", "--cacheinfo", "100644," + oid + ",docs/A.md");
    assertTrue(check(commit()).toString().contains("case_collision"));
  }

  @Test
  void committedNonportablePathIsBlocked() throws Exception {
    String oid = git("hash-object", "-w", "docs/a.md");
    git(
        "-c",
        "core.protectNTFS=false",
        "update-index",
        "--add",
        "--cacheinfo",
        "100644," + oid + ",docs/file.");
    assertTrue(check(commit()).toString().contains("unsupported_path"));
  }
}
