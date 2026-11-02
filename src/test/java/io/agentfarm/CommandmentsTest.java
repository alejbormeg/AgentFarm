package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CommandmentsTest {
  JsonNode n(Object... v) {
    return MAPPER.valueToTree(obj(v));
  }

  JsonNode files(String... names) {
    return n("files", List.of(names), "added", List.of(), "secrets", List.of());
  }

  Map<String, String> statuses(List<Map<String, Object>> list) {
    var out = new HashMap<String, String>();
    list.forEach(c -> out.put(c.get("id").toString(), c.get("status").toString()));
    return out;
  }

  JsonNode healthy() {
    return n(
        "review",
        obj("passed", true),
        "providers",
        obj("implement", "codex", "review", "claude-code"),
        "regression",
        obj("status", "proved"),
        "quality",
        obj("checks", List.of(obj("kind", "lint", "status", "passed")), "metrics", List.of()));
  }

  @Test
  void aHealthyCandidateFulfilsThemAll() {
    var result =
        statuses(
            Commandments.assess(
                n("allowed_paths", List.of("src/")),
                healthy(),
                "success",
                "ok",
                files("src/App.java"),
                ""));
    assertEquals(7, result.size());
    result.forEach((id, status) -> assertEquals("ok", status, id));
  }

  @Test
  void withoutCiReadNothingClaimsToBeFulfilled() {
    var result = statuses(Commandments.assess(n(), n(), null, null, n(), ""));
    assertEquals("pending", result.get("CM-1"));
    assertEquals("not_evaluated", result.get("CM-2"));
    assertEquals("not_evaluated", result.get("CM-3"));
    assertEquals("not_evaluated", result.get("CM-4"));
    assertEquals("not_evaluated", result.get("CM-5"));
    assertEquals("pending", result.get("CM-6"));
    assertEquals("pending", result.get("CM-7"));
  }

  @Test
  void eachViolationBreaksItsOwnCommandment() {
    assertEquals(
        "failed",
        statuses(Commandments.assess(n(), n("untested_code", true), "success", "", files("a"), ""))
            .get("CM-1"));
    assertEquals(
        "failed",
        statuses(
                Commandments.assess(
                    n(), n("regression", obj("status", "not_proved", "reason", "ya pasaban")), "success", "", files("a"), ""))
            .get("CM-2"));
    assertEquals(
        "failed",
        statuses(Commandments.assess(n(), n("review", obj("passed", false)), "success", "", files("a"), ""))
            .get("CM-3"));
    assertEquals(
        "failed",
        statuses(
                Commandments.assess(
                    n("allowed_paths", List.of("src/")), n(), "success", "", files("docs/x.md"), ""))
            .get("CM-4"));
    assertEquals(
        "failed",
        statuses(
                Commandments.assess(
                    n(),
                    n("quality", obj("metrics", List.of(obj("name", "coverage", "status", "failed", "base", 90.0, "candidate", 80.0)))),
                    "success",
                    "",
                    files("a"),
                    ""))
            .get("CM-5"));
    assertEquals(
        "failed",
        statuses(
                Commandments.assess(
                    n(), n(), "failed", "", n("files", List.of("a"), "secrets", List.of("a (clave de API)")), ""))
            .get("CM-6"));
    assertEquals(
        "failed",
        statuses(Commandments.assess(n(), n(), "success", "", files("agentfarm.yaml"), "")).get("CM-7"));
    assertEquals(
        "failed",
        statuses(Commandments.assess(n(), n(), "failed", "", files(".github/workflows/ci.yml"), "")).get("CM-7"));
  }

  @Test
  void onlyAdoptingARepoMayAddItsRules() {
    var added = n("files", List.of("agentfarm.yaml"), "added", List.of("agentfarm.yaml"), "secrets", List.of());
    assertEquals(
        "ok", statuses(Commandments.assess(n(), n(), "success", "", added, "adopt-repo")).get("CM-7"));
    assertEquals(
        "failed", statuses(Commandments.assess(n(), n(), "success", "", added, "bugfix")).get("CM-7"));
    assertEquals(
        "failed",
        statuses(Commandments.assess(n(), n(), "success", "", files("agentfarm.yaml"), "adopt-repo")).get("CM-7"),
        "adopting adds the rules; it does not rewrite existing ones");
    assertEquals(
        "ok",
        statuses(Commandments.assess(n(), n(), "success", "", files(".github/workflows/agentfarm.yml"), "")).get("CM-7"),
        "the platform's own workflow is not a project rule");
  }

  @Test
  void secretScanOnlyReadsAddedLinesAndNeverKeepsTheValue() {
    String token = "ghp_" + "a".repeat(36);
    var found = SecretScan.findings("", "+++ b/config.env\n+TOKEN=" + token + "\n");
    assertEquals(List.of("config.env (token de GitHub)"), found);
    assertFalse(found.toString().contains(token));
    assertTrue(SecretScan.findings("x", "-TOKEN=" + token + "\n TOKEN=" + token).isEmpty());
    assertFalse(SecretScan.findings("key.pem", "+-----BEGIN RSA PRIVATE KEY-----").isEmpty());
    assertFalse(SecretScan.findings("a", "+" + "AKIA" + "ABCDEFGHIJKLMNOP").isEmpty());
    assertTrue(SecretScan.findings("a", "+sk-short\n+just some words").isEmpty());
  }

  @Test
  void aMetricMayNotGetWorse() {
    assertEquals(90.5, QualityGate.metric("building\nTOTAL 90.5%\n").getAsDouble());
    assertTrue(QualityGate.metric("no number here").isEmpty());
    assertEquals("passed", QualityGate.compare("c", "higher", "80", "80").get("status"));
    assertEquals("passed", QualityGate.compare("c", "higher", "80", "81").get("status"));
    assertEquals("failed", QualityGate.compare("c", "higher", "80", "79.9").get("status"));
    assertEquals("failed", QualityGate.compare("w", "lower", "3", "4").get("status"));
    assertEquals("passed", QualityGate.compare("w", "lower", "3", "2").get("status"));
    assertEquals("failed", QualityGate.compare("c", "higher", "80", "oops").get("status"), "unmeasurable");
    assertEquals("passed", QualityGate.compare("c", "higher", "oops", "70").get("status"), "first reading");
  }

  @Test
  void testPolicyRecognisesCommonTestFiles() {
    for (String path :
        List.of(
            "src/test/java/AppTest.java",
            "tests/test_app.py",
            "app_test.go",
            "src/app.test.ts",
            "src/app.spec.js",
            "lib/__tests__/x.js"))
      assertTrue(TestPolicy.isTest(path), path);
    for (String path : List.of("src/main/App.java", "latest/notes.py", "contest.py"))
      assertFalse(TestPolicy.isTest(path), path);
  }

  @Test
  void profilesDeclareBoundedQualityGates() {
    String base =
        "version: 1\nrepository: o/r\ntests:\n  unit: u\n  integration: i\nallowed_paths: [src/]\n"
            + "environment: container\nreview: independent\n";
    var profile =
        ProjectProfile.parse(
            base
                + "quality:\n  checks:\n    lint: ruff check .\n  metrics:\n    coverage: cov\n"
                + "    warnings: {command: warn, better: lower}\n");
    var q = MAPPER.valueToTree(profile.quality());
    assertEquals("ruff check .", q.path("checks").path("lint").asText());
    assertEquals("higher", q.path("metrics").path("coverage").path("better").asText());
    assertEquals("lower", q.path("metrics").path("warnings").path("better").asText());
    assertTrue(ProjectProfile.parse(base).quality().isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectProfile.parse(base + "quality:\n  gates:\n    a: b\n"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectProfile.parse(base + "quality:\n  metrics:\n    a: {command: x, better: more}\n"));
  }
}
