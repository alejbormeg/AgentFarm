package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Maintained guidance, never commands or acceptance approvals. Keep released versions. */
public final class RecipeCatalog {
  private RecipeCatalog() {}

  private static final JsonNode CATALOG = load();

  private static JsonNode load() {
    try (var stream = RecipeCatalog.class.getResourceAsStream("/recipes.json")) {
      if (stream == null) throw new IllegalStateException("Recipe catalog missing");
      return MAPPER.readTree(stream);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot load recipe catalog", e);
    }
  }

  public static JsonNode snapshot(String id, long version) {
    for (var recipe : CATALOG) {
      if (recipe.path("id").asText().equals(id) && recipe.path("version").asLong() == version) {
        var copy = (com.fasterxml.jackson.databind.node.ObjectNode) recipe.deepCopy();
        copy.put("hash", sha256(canonical(recipe)));
        return copy;
      }
    }
    throw new IllegalArgumentException("Unknown recipe version; reload the catalog");
  }

  public static Object list() {
    // Old versions stay readable by id; only the newest of each recipe is offered for new tasks.
    var newest = new LinkedHashMap<String, JsonNode>();
    for (var recipe : CATALOG)
      newest.merge(
          text(recipe, "id"),
          recipe,
          (a, b) -> number(a, "version") > number(b, "version") ? a : b);
    var result = new ArrayList<JsonNode>();
    for (var recipe : newest.values()) result.add(snapshot(text(recipe, "id"), number(recipe, "version")));
    return result;
  }
}
