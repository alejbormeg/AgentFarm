package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentfarm.core.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubAccessTest {
  final ObjectMapper mapper = new ObjectMapper();

  @Test
  void reportsTheMissingCheckRunsPermissionAndExplainsIt() {
    var result =
        GitHubAccess.check(
            "fine-grained-token",
            "o/r",
            (repo, path) -> {
              if (path.contains("check-runs")) throw new IllegalStateException("GitHub HTTP 403 for " + path);
              return mapper.createObjectNode();
            });
    assertEquals("failed", result.get("state"));
    assertTrue(result.toString().contains("check_runs"));
    assertTrue(result.get("advice").toString().contains("checks=read"));
  }

  @Test
  void aClassicTokenWorksButIsFlaggedAsBroad() {
    var result = GitHubAccess.check("classic-token", "o/r", (repo, path) -> mapper.createObjectNode());
    assertEquals("passed", result.get("state"));
    assertTrue(result.get("advice").toString().contains("todos tus repositorios"));
    assertNull(GitHubAccess.check("github-app", "o/r", (repo, path) -> mapper.createObjectNode()).get("advice"));
  }

  @Test
  void credentialKindComesFromThePrefixWithoutExposingTheToken(@TempDir Path dir) throws Exception {
    for (var c :
        new String[][] {
          {"ghp_secret", "classic-token"}, {"github_pat_secret", "fine-grained-token"}, {"other", "token"}
        }) {
      Path file = dir.resolve("t");
      Files.writeString(file, c[0] + "\n");
      assertEquals(c[1], GitHubCredentials.file(file.toString()).kind());
    }
    assertEquals("none", GitHubCredentials.file(dir.resolve("missing").toString()).kind());
  }

  @Test
  void versionsDifferOnlyBetweenTwoKnownCommits() {
    assertTrue(BuildInfo.mismatch("aaa", "bbb"));
    assertFalse(BuildInfo.mismatch("aaa", "aaa"));
    assertFalse(BuildInfo.mismatch("unknown", "bbb"));
    assertFalse(BuildInfo.mismatch("aaa", "unknown"));
    assertFalse(BuildInfo.mismatch("aaa", null));
  }
}
