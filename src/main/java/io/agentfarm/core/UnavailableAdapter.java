package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;

/** Explicit unavailable implementation. It cannot run or infer quota. */
public final class UnavailableAdapter implements AgentAdapter {
  private final String provider;

  public UnavailableAdapter(String provider) {
    this.provider = provider;
  }

  public String id() {
    return provider;
  }

  public Map<String, Object> descriptor() {
    return obj(
        "id",
        id(),
        "state",
        "unavailable",
        "reason",
        "Execution and reliable quota integration not implemented",
        "authentication",
        "not-integrated",
        "execution",
        "unavailable",
        "events",
        false,
        "interrupt",
        false,
        "resume",
        false,
        "quota",
        "unknown",
        "artifacts",
        "unavailable",
        "independent_verification",
        false,
        "additional_payment",
        false);
  }

  public void validateAuthentication(JsonNode response) {
    throw new IllegalArgumentException("Provider authentication integration unavailable");
  }

  public JsonNode normalizeQuota(JsonNode response, Instant now) {
    throw new IllegalArgumentException("Provider quota integration unavailable");
  }
}
