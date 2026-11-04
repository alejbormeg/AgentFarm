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
    assertEquals(
        "failed", ProfileCheck.validate("setup: x", "o/r", "u", "i", List.of()).get("status"));
    assertEquals(
        "failed", ProfileCheck.validate(PROFILE, "o/otro", "u", "i", List.of()).get("status"));
    var changed = ProfileCheck.validate(PROFILE, "o/r", "otro", "i", List.of());
    assertEquals("failed", changed.get("status"));
    assertTrue(changed.get("error").toString().contains("aprobados"));
  }

  // Both found by adopting with a real agent: docs/PILOTS.md.
  @Test
  void anNaReasonIsNotACommandSoItsWordingMayDiffer() {
    String profile =
        """
        version: 1
        repository: o/r
        setup: ""
        tests:
          unit: "N/A: solo hay un README"
          integration: "N/A: no hay componentes"
        allowed_paths: [src/, README.md]
        environment: container
        review: human-and-independent
        secret_refs: {}
        """;
    assertEquals(
        "passed",
        ProfileCheck.validate(
                profile, "o/r", "N/A: adopción sin código", "N/A: sin integración", List.of())
            .get("status"));
    assertEquals(
        "failed",
        ProfileCheck.validate(profile, "o/r", "pytest", "N/A: sin integración", List.of())
            .get("status"),
        "N/A cannot replace an approved command");
  }

  @Test
  void aGateNameMayUseUnderscoresAndTheErrorNamesIt() {
    String template =
        """
        version: 1
        repository: o/r
        setup: ""
        tests: {unit: u, integration: i}
        allowed_paths: [src/]
        environment: container
        review: human-and-independent
        secret_refs: {}
        quality:
          metrics:
            NAME: {command: "echo 1", better: lower}
        """;
    assertEquals(
        "passed",
        ProfileCheck.validate(
                template.replace("NAME", "undetected_mutations_percent"),
                "o/r",
                "u",
                "i",
                List.of())
            .get("status"));
    var refused =
        ProfileCheck.validate(template.replace("NAME", "No-Vale"), "o/r", "u", "i", List.of());
    assertEquals("failed", refused.get("status"));
    assertTrue(refused.get("error").toString().contains("No-Vale"), refused.toString());
  }

  @Test
  void adoptingARepositoryWithoutCodeIsNotUntestedCode() {
    var adoption = List.of("agentfarm.yaml", ".github/workflows/agentfarm.yml");
    assertFalse(
        io.agentfarm.core.TestPolicy.untestedCode(adoption, "not_applicable", "not_applicable"));
    assertTrue(
        io.agentfarm.core.TestPolicy.untestedCode(
            List.of("agentfarm.yaml", "shop.py"), "not_applicable", "not_applicable"),
        "the profile does not excuse real code");
  }
}
