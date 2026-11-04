package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import org.junit.jupiter.api.Test;

class IntegrationGateTest {
  String sha = "a".repeat(40);

  com.fasterxml.jackson.databind.JsonNode remote() {
    return parse("{\"head\":{\"sha\":\"" + sha + "\"},\"state\":\"open\",\"draft\":false}");
  }

  com.fasterxml.jackson.databind.JsonNode evidence() {
    return MAPPER.valueToTree(
        obj("passed", true, "sha", sha, "ci_checks", java.util.List.of("unit")));
  }

  com.fasterxml.jackson.databind.JsonNode checks() {
    return parse(
        "{\"total_count\":1,\"check_runs\":[{\"name\":\"unit\",\"status\":\"completed\",\"conclusion\":\"success\"}]}");
  }

  @Test
  void onlyMatchingCandidateWithPassingChecksIsEligible() {
    assertDoesNotThrow(() ->
        IntegrationPublisher.validate(
            remote(), evidence(), checks(), parse("{\"total_count\":0}"), parse("[]"), sha));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            IntegrationPublisher.validate(
                remote(), evidence(), checks(), parse("{}"), parse("[]"), "b".repeat(40)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            IntegrationPublisher.validate(
                remote(), evidence(), parse("{\"total_count\":0}"), parse("{}"), parse("[]"), sha));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            IntegrationPublisher.validate(
                remote(),
                evidence(),
                parse(checks().toString().replace("success", "failure")),
                parse("{}"),
                parse("[]"),
                sha));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            IntegrationPublisher.validate(
                remote(),
                evidence(),
                checks(),
                parse("{}"),
                parse("[{\"state\":\"CHANGES_REQUESTED\",\"user\":{\"login\":\"reviewer\"}}]"),
                sha));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            IntegrationPublisher.validate(
                parse(remote().toString().replace("false", "true")),
                evidence(),
                checks(),
                parse("{}"),
                parse("[]"),
                sha));
  }

  @Test
  void projectCiWildcardRequiresEveryRunToPass() {
    var own =
        MAPPER.valueToTree(obj("passed", true, "sha", sha, "ci_checks", java.util.List.of("*")));
    var runs =
        "{\"total_count\":2,\"check_runs\":[{\"name\":\"build\",\"status\":\"completed\",\"conclusion\":\"success\"},"
            + "{\"name\":\"lint\",\"status\":\"completed\",\"conclusion\":\"%s\"}]}";
    assertDoesNotThrow(() ->
        IntegrationPublisher.validate(
            remote(), own, parse(runs.formatted("success")), parse("{}"), parse("[]"), sha));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            IntegrationPublisher.validate(
                remote(), own, parse(runs.formatted("failure")), parse("{}"), parse("[]"), sha));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            IntegrationPublisher.validate(
                remote(), own, parse("{\"total_count\":0}"), parse("{}"), parse("[]"), sha));
  }
}
