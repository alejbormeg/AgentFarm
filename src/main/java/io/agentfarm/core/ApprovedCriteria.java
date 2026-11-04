package io.agentfarm.core;

import static io.agentfarm.core.Json.*;
import static io.agentfarm.core.Store.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.util.*;

/**
 * Criteria a person approved survive a correction. An agent answering a correction can only add to
 * them, even reusing an id; only a person retires one, with a reason that stays on the proposal.
 */
public final class ApprovedCriteria {
  private static final int MIN_REASON = 20;

  private ApprovedCriteria() {}

  /** The latest approved criteria, or null when no version of this item has been approved yet. */
  private static JsonNode approved(Connection c, Object item) throws Exception {
    var scope =
        one(c, "SELECT criteria FROM acceptance_scopes WHERE item=? ORDER BY version DESC LIMIT 1", item);
    return scope == null ? null : parse(scope.get("criteria").toString());
  }

  private static String normal(JsonNode criterion) {
    return criterion.path("description").asText().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  /** Pure decision: approved first and untouched, then what the agent adds under free ids. */
  public static ArrayNode merge(JsonNode approved, JsonNode proposed, Set<String> retired) {
    ArrayNode out = MAPPER.createArrayNode();
    Set<String> ids = new HashSet<>(retired), texts = new HashSet<>();
    int highest = 0;
    for (var criterion : approved)
      if (!retired.contains(criterion.path("id").asText())) {
        out.add(criterion);
        ids.add(criterion.path("id").asText());
        texts.add(normal(criterion));
      }
    for (String id : ids) highest = Math.max(highest, number(id));
    for (var criterion : proposed) {
      if (!texts.add(normal(criterion))) continue;
      String id = criterion.path("id").asText();
      if (ids.contains(id)) {
        id = "AC-" + ++highest;
        criterion = ((ObjectNode) criterion.deepCopy()).put("id", id);
      } else highest = Math.max(highest, number(id));
      ids.add(id);
      out.add(criterion);
    }
    return out;
  }

  private static int number(String id) {
    return id.matches("AC-[0-9]{1,4}") ? Integer.parseInt(id.substring(3)) : 0;
  }

  /** On a new proposal. Fields an agent might send here are the platform's: its values are dropped. */
  static void apply(Connection c, Object item, JsonNode previous, ObjectNode proposal) throws Exception {
    proposal.remove(List.of("kept_criteria", "retired_criteria"));
    var retired = previous.path("retired_criteria");
    if (retired.isArray() && !retired.isEmpty()) proposal.set("retired_criteria", retired);
    var approved = approved(c, item);
    if (approved == null) return;
    Set<String> gone = new HashSet<>();
    retired.forEach(r -> gone.add(r.path("id").asText()));
    proposal.set("criteria", merge(approved, proposal.path("criteria"), gone));
    ArrayNode kept = MAPPER.createArrayNode();
    for (var criterion : approved)
      if (!gone.contains(criterion.path("id").asText())) kept.add(criterion.path("id").asText());
    proposal.set("kept_criteria", kept);
  }

  /** On a correction: a person, with a reason, retires criteria that were approved. */
  static void retire(Connection c, Map<String, Object> item, JsonNode ids, String reason, String actor, long at)
      throws Exception {
    if (actor.startsWith("node:") || actor.startsWith("policy:"))
      throw new IllegalArgumentException("Only a person can retire a criterion");
    if (reason.trim().length() < MIN_REASON)
      throw new IllegalArgumentException("Explain in at least 20 characters why a criterion is retired");
    var approved = approved(c, item.get("id"));
    var proposal = (ObjectNode) parse(item.get("proposal").toString());
    ArrayNode retired =
        proposal.path("retired_criteria").isArray()
            ? (ArrayNode) proposal.get("retired_criteria")
            : MAPPER.createArrayNode();
    for (var id : ids) {
      JsonNode found = null;
      if (approved != null)
        for (var criterion : approved)
          if (criterion.path("id").asText().equals(id.asText())) found = criterion;
      if (found == null) throw new IllegalArgumentException("Only an approved criterion can be retired");
      retired.add(
          MAPPER.valueToTree(
              obj(
                  "id", id.asText(),
                  "description", found.path("description").asText(),
                  "reason", reason.trim(),
                  "actor", actor,
                  "at", at)));
    }
    proposal.set("retired_criteria", retired);
    exec(c, "UPDATE work_items SET proposal=? WHERE id=?", encode(proposal), item.get("id"));
  }
}
