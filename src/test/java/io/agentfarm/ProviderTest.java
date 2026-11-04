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
    return n(
        "state",
        "available",
        "mode",
        "subscription",
        "account",
        "account-a",
        "windows",
        List.of(obj("period", "weekly", "end", 2000000)));
  }

  String state(
      com.fasterxml.jackson.databind.JsonNode req,
      com.fasterxml.jackson.databind.JsonNode b,
      com.fasterxml.jackson.databind.JsonNode r) {
    return ProviderAdmission.check(req, b, r, now).get("state").toString();
  }

  @Test
  void authenticationCannotSilentlySwitchToApiBilling() {
    assertDoesNotThrow(() ->
        AgentAdapters.get("codex").validateAuthentication(n("account", obj("type", "chatgpt"))));
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
    assertEquals(
        1_234_568,
        AgentAdapters.get("claude-code")
            .normalizeQuota(
                n(
                    "type",
                    "result",
                    "subtype",
                    "success",
                    "is_error",
                    false,
                    "result",
                    "ok",
                    "total_cost_usd",
                    1.2345671),
                now)
            .path("used")
            .asLong());
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
  void aReservationConsumesFreeAdmissionWithoutConsumingObservedQuota(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
    var service =
        new BudgetService(
            new Store(dir.resolve("db").toString()), Clock.fixed(now, ZoneOffset.UTC));
    service.execute(
        "configure",
        n("account", "account-a", "mode", "subscription", "shares", obj("weekly", 9800)),
        "owner");
    service.execute(
        "open-window",
        n(
            "account",
            "account-a",
            "period",
            "weekly",
            "start",
            900000,
            "end",
            2000000,
            "total",
            10000,
            "used",
            7000,
            "observed_at",
            now.getEpochSecond(),
            "source",
            "provider",
            "max_age",
            60),
        "owner");
    assertEquals(
        "admissible", state(request(), MAPPER.valueToTree(service.status("account-a")), runtime()));
    service.execute(
        "reserve",
        n("account", "account-a", "run_id", "active-run", "amounts", obj("weekly", 2800)),
        "worker");
    var after = MAPPER.valueToTree(service.status("account-a"));
    assertEquals("blocked", state(request(), after, runtime()));
    assertEquals(7000, after.path("windows").get(0).path("used").asInt());
    assertEquals(2800, after.path("windows").get(0).path("reserved").asInt());
    assertEquals(
        "blocked",
        state(
            request(),
            n(
                "state",
                "available",
                "mode",
                "subscription",
                "account",
                "account-a",
                "windows",
                List.of(obj("period", "weekly", "end", 1999999))),
            runtime()));
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
  void claudeCodeIsAdmittedOnlyAgainstAnAvailableApiAccount() {
    var claude = n("provider", "claude-code", "profile", "reinforced", "account", "api-a");
    assertEquals(
        "admissible",
        state(claude, n("state", "available", "mode", "api", "account", "api-a"), runtime()));
    assertEquals(
        "blocked",
        state(claude, n("state", "paused", "mode", "api", "account", "api-a"), runtime()));
    assertEquals(
        "blocked",
        state(
            claude,
            n("state", "available", "mode", "subscription", "account", "api-a"),
            runtime()));
    assertEquals(
        "blocked",
        state(
            n(
                "provider",
                "claude-code",
                "profile",
                "balanced",
                "account",
                "api-a",
                "additional_payment",
                true),
            n("state", "available", "mode", "api", "account", "api-a"),
            runtime()));
  }

  @Test
  void claudeCodeCommandAndResultAreStrict() {
    var review = ClaudeCodeAdapter.command("claude", "review", "");
    assertTrue(review.containsAll(List.of("-p", "--output-format", "json", "Read,Glob,Grep")));
    assertEquals("Bash", review.get(review.indexOf("--disallowedTools") + 1));
    assertEquals("acceptEdits", ClaudeCodeAdapter.command("claude", "implement", "").get(5));
    assertThrows(
        IllegalArgumentException.class, () -> ClaudeCodeAdapter.command("claude", "merge", ""));
    assertEquals(
        "user",
        review.get(review.indexOf("--setting-sources") + 1),
        "a repository's settings are not trusted");
    var rules =
        Json.parse(
            ClaudeCodeAdapter.command(
                    "claude", "implement", "", List.of(), null, List.of("/home/a/.ssh"))
                .stream()
                .filter(a -> a.startsWith("{"))
                .findFirst()
                .orElseThrow());
    assertEquals(
        "[\"Read(//home/a/.ssh)\",\"Read(//home/a/.ssh/**)\",\"Edit(//home/a/.ssh)\",\"Edit(//home/a/.ssh/**)\"]",
        rules.path("permissions").path("deny").toString());
    assertThrows(
        IllegalArgumentException.class,
        () -> ClaudeCodeAdapter.command("claude", "review", "x; rm -rf /"));
    var ok =
        ClaudeCodeAdapter.parse(
            "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"{}\","
                + "\"session_id\":\"s1\",\"total_cost_usd\":0.5,\"modelUsage\":{\"claude-x\":{}}}");
    assertEquals(500_000, ok.costMicros());
    assertEquals("claude-x", ok.model());
    for (String bad :
        List.of(
            "not json",
            "{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true,\"total_cost_usd\":1}",
            "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"x\"}"))
      assertThrows(IllegalArgumentException.class, () -> ClaudeCodeAdapter.parse(bad), bad);
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
