package io.agentfarm.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

public final class Json {
  public static final ObjectMapper MAPPER = new ObjectMapper();

  private Json() {}

  public static JsonNode parse(String text) {
    try {
      return MAPPER.readTree(text);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  public static String encode(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot encode JSON", e);
    }
  }

  public static Map<String, Object> obj(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }

  public static String text(JsonNode node, String key) {
    JsonNode value = node.get(key);
    if (value == null || !value.isTextual() || value.asText().isBlank())
      throw new IllegalArgumentException("Missing string: " + key);
    return value.asText();
  }

  public static long number(JsonNode node, String key) {
    JsonNode value = node.get(key);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.asLong() < 0
        || value.asLong() > 1_000_000_000_000L)
      throw new IllegalArgumentException(
          key + " must be a nonnegative integer at most 1000000000000");
    return value.asLong();
  }

  public static String canonical(JsonNode value) {
    if (value.isObject()) {
      TreeMap<String, JsonNode> sorted = new TreeMap<>();
      value.properties().forEach(e -> sorted.put(e.getKey(), e.getValue()));
      return sorted.entrySet().stream()
          .map(e -> encode(e.getKey()) + ": " + canonical(e.getValue()))
          .collect(Collectors.joining(", ", "{", "}"));
    }
    if (value.isArray()) {
      List<String> items = new ArrayList<>();
      value.forEach(v -> items.add(canonical(v)));
      return String.join(", ", items).transform(s -> "[" + s + "]");
    }
    return encode(value);
  }

  public static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
