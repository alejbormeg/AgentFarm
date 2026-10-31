package io.agentfarm.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;

/**
 * Provider capabilities reflect AgentFarm's implementation, not vendor marketing. No inference on
 * lookup.
 */
public interface AgentAdapter {
  String id();

  Map<String, Object> descriptor();

  void validateAuthentication(JsonNode response);

  JsonNode normalizeQuota(JsonNode response, Instant observedAt);
}
