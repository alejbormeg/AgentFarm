package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;

/** Pure admission policy. It neither launches agents nor reserves/spends quota. */
public final class ProviderAdmission {
  public static Map<String, Object> check(
      JsonNode request, JsonNode budget, JsonNode runtime, Instant now) {
    String provider = text(request, "provider"),
        profile = text(request, "profile"),
        account = text(request, "account");
    var adapter = AgentAdapters.get(provider).descriptor();
    if (!Set.of("economic", "balanced", "reinforced").contains(profile))
      throw new IllegalArgumentException("Unknown resource profile");
    List<String> reasons = new ArrayList<>();
    if (!adapter.get("state").equals("implemented")) reasons.add("provider_not_implemented");
    if (request.path("additional_payment").asBoolean())
      reasons.add("additional_payment_not_supported");
    if (profile.equals("reinforced")
        && !Boolean.TRUE.equals(adapter.get("independent_verification")))
      reasons.add("independent_verifier_unavailable");
    if (!budget.path("account").asText().equals(account)) reasons.add("budget_account_mismatch");
    if (!budget.path("mode").asText().equals("subscription"))
      reasons.add("resource_unit_not_supported_by_adapter");
    if (!budget.path("state").asText().equals("available"))
      reasons.add("clover_budget_not_available");
    var snapshot = runtime.path("snapshot");
    var quota = snapshot.path("quota");
    if (!snapshot.path("account").asText().equals(account)
        || !snapshot.path("provider").asText().equals(provider)
        || !quota.path("provider").asText().equals(provider))
      reasons.add("quota_account_or_provider_mismatch");
    if (!quota.path("unit").asText().equals("subscription_percent")
        || !quota.path("source").asText().equals("provider"))
      reasons.add("provider_quota_unit_or_source_unknown");
    boolean matchingWindow = false;
    for (var window : budget.path("windows"))
      if (window.path("period").asText().equals("weekly")
          && window.path("end").isIntegralNumber()
          && window.path("end").asLong() == quota.path("resets_at").asLong()) matchingWindow = true;
    if (!matchingWindow) reasons.add("quota_budget_window_mismatch");
    boolean fresh = false;
    try {
      long age =
          Duration.between(Instant.parse(quota.path("observed_at").asText()), now).getSeconds();
      fresh = age >= 0 && age <= 60;
    } catch (Exception ignored) {
    }
    if (!runtime.path("fresh").asBoolean() || !runtime.path("quota_fresh").asBoolean() || !fresh)
      reasons.add("provider_quota_unknown");
    if (!quota.path("weekly_used_percent").isNumber()
        || !Double.isFinite(quota.path("weekly_used_percent").asDouble())
        || quota.path("weekly_used_percent").asDouble() < 0
        || quota.path("weekly_used_percent").asDouble() >= 98
        || !quota.path("allowed").asBoolean()
        || !quota.path("resets_at").isIntegralNumber()
        || quota.path("resets_at").asLong() <= now.getEpochSecond())
      reasons.add("quota_stop_or_invalid_reading");
    return obj(
        "state",
        reasons.isEmpty() ? "admissible" : "blocked",
        "provider",
        provider,
        "profile",
        profile,
        "account",
        account,
        "reasons",
        reasons,
        "unit",
        "subscription_percent",
        "stop_remaining_percent",
        2,
        "additional_payment",
        false,
        "reserved",
        false,
        "optimization_active",
        false);
  }
}
