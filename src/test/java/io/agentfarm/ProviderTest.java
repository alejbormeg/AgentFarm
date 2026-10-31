package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProviderTest {
  final Instant now = Instant.ofEpochSecond(1000000);

  com.fasterxml.jackson.databind.JsonNode n(Object... v) {
    return MAPPER.valueToTree(obj(v));
  }

  com.fasterxml.jackson.databind.JsonNode raw(double used) {
    return n(
        "rateLimits",
        obj(
            "limitId",
            "codex",
            "secondary",
            obj("usedPercent", used, "windowDurationMins", 10080, "resetsAt", 2000000)));
  }

  com.fasterxml.jackson.databind.JsonNode quota() {
    return AgentAdapters.get("codex").normalizeQuota(raw(70), now);
  }

  com.fasterxml.jackson.databind.JsonNode runtime() {
    return n(
        "fresh",
        true,
        "quota_fresh",
        true,
        "snapshot",
        obj("account", "account-a", "provider", "codex", "quota", quota()));
  }

  com.fasterxml.jackson.databind.JsonNode request() {
    return n(
        "provider",
        "codex",
        "profile",
        "balanced",
        "account",
        "account-a",
        "additional_payment",
        false);
  }

  com.fasterxml.jackson.databind.JsonNode budget() {
    return n("state", "available", "mode", "subscription", "account", "account-a");
  }

  String state(
      com.fasterxml.jackson.databind.JsonNode req,
      com.fasterxml.jackson.databind.JsonNode b,
      com.fasterxml.jackson.databind.JsonNode r) {
    return ProviderAdmission.check(req, b, r, now).get("state").toString();
  }

  @Test
  void authenticationCannotSilentlySwitchToApiBilling() {
    assertDoesNotThrow(
        () ->
            AgentAdapters.get("codex")
                .validateAuthentication(n("account", obj("type", "chatgpt"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentAdapters.get("codex").validateAuthentication(n("account", obj("type", "apiKey"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentAdapters.get("codex").validateAuthentication(n("account", null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentAdapters.get("claude-code")
                .validateAuthentication(n("account", obj("type", "chatgpt"))));
  }

  @Test
  void normalizedQuotaPreservesUnitsAndStopPolicy() {
    assertEquals("subscription_percent", quota().path("unit").asText());
    assertEquals("provider", quota().path("source").asText());
    assertEquals(70, quota().path("weekly_used_percent").asDouble());
    assertFalse(
        AgentAdapters.get("codex").normalizeQuota(raw(98), now).path("allowed").asBoolean());
    var paused = raw(70);
    ((com.fasterxml.jackson.databind.node.ObjectNode) paused).put("ordinaryUsageAllowed", false);
    assertFalse(AgentAdapters.get("codex").normalizeQuota(paused, now).path("allowed").asBoolean());
  }

  @Test
  void wrongBucketsAndMalformedReadingsAreUnknown() {
    for (var invalid :
        List.of(
            n(),
            raw(-1),
            raw(101),
            n(
                "rateLimitsByLimitId",
                obj("other", raw(10).path("rateLimits")),
                "rateLimits",
                raw(10).path("rateLimits")),
            n(
                "rateLimits",
                obj(
                    "secondary",
                    obj("usedPercent", 30, "windowDurationMins", 10080, "resetsAt", 999999)))))
      assertThrows(
          IllegalArgumentException.class,
          () -> AgentAdapters.get("codex").normalizeQuota(invalid, now));
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentAdapters.get("claude-code").normalizeQuota(raw(10), now));
    assertThrows(IllegalArgumentException.class, () -> AgentAdapters.get("unknown"));
  }

  @Test
  void knownFreshAccountCanBeAdmittedWithoutSpending() {
    var result = ProviderAdmission.check(request(), budget(), runtime(), now);
    assertEquals("admissible", result.get("state"));
    assertEquals(false, result.get("reserved"));
    assertEquals(false, result.get("optimization_active"));
    assertEquals(false, result.get("additional_payment"));
    var economic = request().deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) economic).put("profile", "economic");
    assertEquals("admissible", state(economic, budget(), runtime()));
  }

  @Test
  void noImplicitFallbackOrAdditionalPayment() {
    for (var req :
        List.of(
            n("provider", "claude-code", "profile", "balanced", "account", "account-a"),
            n("provider", "codex", "profile", "reinforced", "account", "account-a"),
            n(
                "provider",
                "codex",
                "profile",
                "balanced",
                "account",
                "account-a",
                "additional_payment",
                true))) assertEquals("blocked", state(req, budget(), runtime()));
    assertEquals(
        "blocked",
        state(
            request(), n("state", "available", "mode", "api", "account", "account-a"), runtime()));
  }

  @Test
  void staleUnknownAndMismatchedReadingsBlockAdmission() {
    var stale = runtime();
    ((com.fasterxml.jackson.databind.node.ObjectNode) stale.path("snapshot").path("quota"))
        .put("observed_at", now.minusSeconds(61).toString());
    assertEquals("blocked", state(request(), budget(), stale));
    var mismatch = runtime();
    ((com.fasterxml.jackson.databind.node.ObjectNode) mismatch.path("snapshot"))
        .put("account", "other-account");
    assertEquals("blocked", state(request(), budget(), mismatch));
    var stopped = runtime();
    ((com.fasterxml.jackson.databind.node.ObjectNode) stopped.path("snapshot").path("quota"))
        .put("weekly_used_percent", 98);
    assertEquals("blocked", state(request(), budget(), stopped));
    var offline = runtime();
    ((com.fasterxml.jackson.databind.node.ObjectNode) offline).put("fresh", false);
    assertEquals("blocked", state(request(), budget(), offline));
    assertEquals(
        "blocked",
        state(
            request(),
            n("state", "paused", "mode", "subscription", "account", "account-a"),
            runtime()));
  }
}
