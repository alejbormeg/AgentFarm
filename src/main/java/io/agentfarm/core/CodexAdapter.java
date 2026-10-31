package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;

public final class CodexAdapter implements AgentAdapter {
  public String id() {
    return "codex";
  }

  public Map<String, Object> descriptor() {
    return obj(
        "id",
        id(),
        "state",
        "implemented",
        "authentication",
        "managed-chatgpt-cli",
        "execution",
        "app-server-stdio",
        "events",
        true,
        "interrupt",
        true,
        "resume",
        false,
        "quota",
        "observed-subscription-windows",
        "artifacts",
        "local-executor-reports",
        "independent_verification",
        false,
        "additional_payment",
        false);
  }

  public void validateAuthentication(JsonNode response) {
    if (!response.path("account").path("type").asText().equals("chatgpt"))
      throw new IllegalArgumentException(
          "Managed ChatGPT authentication required; API execution is not authorized");
  }

  public JsonNode normalizeQuota(JsonNode response, Instant now) {
    JsonNode bucket;
    var all = response.path("rateLimitsByLimitId");
    if (!all.isMissingNode() && !all.isNull()) {
      if (!all.isObject()) throw new IllegalArgumentException("Invalid quota buckets");
      bucket = all.path("codex");
    } else bucket = response.path("rateLimits");
    if (!bucket.isObject()
        || bucket.hasNonNull("limitId") && !bucket.path("limitId").asText().equals("codex"))
      throw new IllegalArgumentException("Codex quota unavailable");
    boolean allowed =
        !response.has("ordinaryUsageAllowed")
            || response.path("ordinaryUsageAllowed").isBoolean()
                && response.path("ordinaryUsageAllowed").asBoolean();
    if (bucket.hasNonNull("rateLimitReachedType")) allowed = false;
    JsonNode weekly = null;
    for (String key : List.of("primary", "secondary")) {
      var window = bucket.path(key);
      if (window.isMissingNode() || window.isNull()) continue;
      if (!window.isObject()
          || !window.path("usedPercent").isNumber()
          || !Double.isFinite(window.path("usedPercent").asDouble())
          || window.path("usedPercent").asDouble() < 0
          || window.path("usedPercent").asDouble() > 100
          || !window.path("resetsAt").isIntegralNumber()
          || window.path("resetsAt").asLong() <= now.getEpochSecond()
          || !window.path("windowDurationMins").isIntegralNumber()
          || window.path("windowDurationMins").asLong() <= 0)
        throw new IllegalArgumentException("Invalid provider quota window");
      if (window.path("usedPercent").asDouble() >= 98) allowed = false;
      if (window.path("windowDurationMins").asLong() == 10080) {
        if (weekly != null) throw new IllegalArgumentException("Ambiguous weekly quota");
        weekly = window;
      }
    }
    if (weekly == null) throw new IllegalArgumentException("Weekly Codex quota unknown");
    return MAPPER.valueToTree(
        obj(
            "provider",
            id(),
            "unit",
            "subscription_percent",
            "source",
            "provider",
            "weekly_used_percent",
            weekly.path("usedPercent").asDouble(),
            "stop_remaining_percent",
            2,
            "observed_at",
            now.toString(),
            "resets_at",
            weekly.path("resetsAt").asLong(),
            "allowed",
            allowed));
  }
}
