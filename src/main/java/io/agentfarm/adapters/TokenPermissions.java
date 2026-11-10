package io.agentfarm.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads what GitHub says a token can do, and compares it with what was asked. */
public final class TokenPermissions {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Map<String, Integer> LEVEL = Map.of("read", 1, "write", 2, "admin", 3);

  private TokenPermissions() {}

  /**
   * The permissions in an installation-token response that go beyond {@code requested}. An empty
   * list means the token has no more than was asked. A response without a permissions object, or
   * with a level that is not known, counts as exceeding: nothing is assumed.
   */
  public static List<String> excess(String response, Map<String, String> requested)
      throws java.io.IOException {
    JsonNode granted = MAPPER.readTree(response).path("permissions");
    var over = new ArrayList<String>();
    if (!granted.isObject()) {
      over.add("(la respuesta de GitHub no dice los permisos)");
      return over;
    }
    granted
        .properties()
        .forEach(e -> {
          int have = LEVEL.getOrDefault(e.getValue().asText(), 99);
          int allowed = LEVEL.getOrDefault(requested.getOrDefault(e.getKey(), ""), 0);
          if (have > allowed) over.add(e.getKey() + ":" + e.getValue().asText());
        });
    return over;
  }
}
