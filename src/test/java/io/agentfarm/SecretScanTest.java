package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.adapters.GitChanges;
import io.agentfarm.core.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A secret scan reads the whole candidate before any push, and never calls a partial read clean.
 */
class SecretScanTest {
  @TempDir Path repo;
  static final String TOKEN = "ghp_" + "Z".repeat(36);

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

  String base() throws Exception {
    git("init", "-q");
    git("config", "user.email", "t@example.com");
    git("config", "user.name", "t");
    git("config", "core.hooksPath", repo.resolve("no-hooks").toString());
    return commit();
  }

  static String filler(int lines) {
    return "harmless line of text\n".repeat(lines);
  }

  @Test
  void aSecretAnywhereInALargeDiffIsFoundBeforeThePush() throws Exception {
    String base = base();
    // About 340,000 characters, more than the 150,000 the runtime keeps of a command's output.
    Files.writeString(repo.resolve("a.txt"), "key=" + TOKEN + "\n" + filler(5000));
    Files.writeString(repo.resolve("m.txt"), filler(5000) + "mid=" + TOKEN + "\n" + filler(5000));
    Files.writeString(repo.resolve("z.txt"), filler(5000) + "end=" + TOKEN + "\n");
    String candidate = commit();
    assertTrue(git("diff", base, candidate).length() > 340_000);
    var scan = GitChanges.secretScan(repo, base, candidate);
    assertEquals("found", scan.state());
    assertEquals(
        List.of("a.txt (token de GitHub)", "m.txt (token de GitHub)", "z.txt (token de GitHub)"),
        scan.findings());
    assertNotNull(scan.refusal());
    assertFalse(scan.refusal().contains(TOKEN), "a finding never carries the value");
  }

  @Test
  void aSecretInsideABinaryFileIsFoundToo() throws Exception {
    String base = base();
    byte[] bytes = ("\u0000\u0001PNG\n+" + TOKEN + "\n").getBytes(StandardCharsets.UTF_8);
    Files.write(repo.resolve("image.bin"), bytes);
    String candidate = commit();
    assertTrue(git("diff", base, candidate).contains("Binary files"), "Git hides it by default");
    assertEquals("found", GitChanges.secretScan(repo, base, candidate).state());
  }

  @Test
  void aCleanCandidateIsCleanOnlyWhenEverythingWasRead() throws Exception {
    String base = base();
    Files.writeString(repo.resolve("ok.txt"), filler(20000));
    String candidate = commit();
    var scan = GitChanges.secretScan(repo, base, candidate);
    assertEquals("clean", scan.state());
    assertNull(scan.refusal());
  }

  @Test
  void whatCannotBeReadIsIncompleteNeverClean() throws Exception {
    byte[] diff = ("+++ b/a.txt\n+" + filler(100)).getBytes(StandardCharsets.UTF_8);
    var cut = SecretScan.scan(new ByteArrayInputStream(diff), 200);
    assertEquals("incomplete", cut.state());
    assertTrue(cut.refusal().startsWith("No se pudo analizar"));
    var binary =
        SecretScan.scan(
            new ByteArrayInputStream(
                "Binary files a/x.png and b/x.png differ\n".getBytes(StandardCharsets.UTF_8)),
            1000);
    assertEquals("incomplete", binary.state());
    assertEquals(List.of("binario sin analizar: a/x.png and b/x.png"), binary.unscanned());
    var both =
        SecretScan.scan(
            new ByteArrayInputStream(
                ("+++ b/k\n+" + TOKEN + "\n" + filler(100)).getBytes(StandardCharsets.UTF_8)),
            100);
    assertEquals("found", both.state(), "what was read still counts");
  }

  @Test
  void onlyFullCommitShasReachGit() {
    assertThrows(
        IllegalArgumentException.class, () -> GitChanges.secretScan(repo, "HEAD~1", "HEAD"));
  }
}
