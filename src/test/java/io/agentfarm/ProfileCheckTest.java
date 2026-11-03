package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.ProfileCheck;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfileCheckTest {
  static final String PROFILE =
      """
      version: 1
      repository: o/r
      setup: ""
      tests: {unit: u, integration: i}
      allowed_paths: [src/, tests/]
      environment: container
      review: independent
      secret_refs: {}
      quality: {checks: {lint: "true"}}
      """;

  @Test
  void aValidProfileReportsItsGatesAndWarnsAboutUncoveredDirectories() {
    var result = ProfileCheck.validate(PROFILE, "o/r", "u", "i", List.of("src", "docs", ".github"));
    assertEquals("passed", result.get("status"));
    assertEquals(List.of("docs"), result.get("uncovered_directories"));
    assertNotNull(result.get("quality"));
  }

  @Test
  void rejectsWhatWouldBreakLaterTasks() {
    assertEquals("failed", ProfileCheck.validate("setup: x", "o/r", "u", "i", List.of()).get("status"));
    assertEquals("failed", ProfileCheck.validate(PROFILE, "o/otro", "u", "i", List.of()).get("status"));
    var changed = ProfileCheck.validate(PROFILE, "o/r", "otro", "i", List.of());
    assertEquals("failed", changed.get("status"));
    assertTrue(changed.get("error").toString().contains("aprobados"));
  }
}
