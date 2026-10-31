package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Structured, commit-bound project context. Never executable policy or proof. */
public final class ProjectMemory {
  public static final String FILE = "agentfarm-memory.json";
  public static final int MAX_BYTES = 65536;

  private ProjectMemory() {}

  private static void fields(JsonNode n, Set<String> allowed) {
    if (n == null || !n.isObject()) throw new IllegalArgumentException("Memory object required");
    for (var it = n.fieldNames(); it.hasNext(); ) {
      if (!allowed.contains(it.next())) throw new IllegalArgumentException("Unknown memory field");
    }
  }

  private static void origin(String repository, String sha) {
    if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || !sha.matches("[a-f0-9]{40}"))
      throw new IllegalArgumentException("Memory repository and full source SHA required");
  }

  public static JsonNode absent(String repository, String sha) {
    origin(repository, sha);
    return MAPPER.valueToTree(
        obj("repository", repository, "base_sha", sha, "present", false, "hash", "absent"));
  }

  public static JsonNode snapshot(String repository, String sha, String raw) {
    origin(repository, sha);
    if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IllegalArgumentException("Project memory exceeds 64 KiB");
    JsonNode doc;
    try {
      doc =
          MAPPER
              .reader()
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .readTree(raw);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid project memory JSON");
    }
    fields(doc, Set.of("version", "repository", "entries"));
    if (number(doc, "version") != 1 || !text(doc, "repository").equals(repository))
      throw new IllegalArgumentException("Unsupported memory version or repository mismatch");
    var entries = doc.path("entries");
    if (!entries.isArray() || entries.size() > 100)
      throw new IllegalArgumentException("Memory entries must be an array, maximum 100");
    var ids = new HashSet<String>();
    for (var entry : entries) {
      fields(entry, Set.of("id", "kind", "summary", "rationale", "references"));
      var id = text(entry, "id");
      if (!id.matches("[A-Za-z0-9_-]{1,60}")
          || !ids.add(id)
          || !Set.of("decision", "constraint", "rejection").contains(text(entry, "kind"))
          || text(entry, "summary").length() > 240
          || text(entry, "rationale").length() > 2000)
        throw new IllegalArgumentException("Invalid or duplicate memory entry");
      var refs = entry.path("references");
      if (!refs.isMissingNode()) {
        if (!refs.isArray() || refs.size() > 10)
          throw new IllegalArgumentException("Invalid memory references");
        for (var ref : refs) {
          if (!ref.isTextual()
              || ref.asText().length() > 1000
              || !ref.asText().startsWith("https://"))
            throw new IllegalArgumentException("Memory references require HTTPS URLs");
        }
      }
    }
    return MAPPER.valueToTree(
        obj(
            "repository",
            repository,
            "base_sha",
            sha,
            "present",
            true,
            "hash",
            sha256(canonical(doc)),
            "document",
            doc));
  }

  public static Object validateFile(java.nio.file.Path file, String repository)
      throws java.io.IOException {
    if (!java.nio.file.Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        || java.nio.file.Files.size(file) > MAX_BYTES)
      throw new IllegalArgumentException("Memory must be a regular file up to 64 KiB");
    var result = snapshot(repository, "0".repeat(40), java.nio.file.Files.readString(file));
    return obj(
        "validation",
        "schema_only",
        "repository",
        repository,
        "hash",
        result.path("hash").asText(),
        "entries",
        result.path("document").path("entries").size());
  }

  public static void validate(JsonNode saved, String repository) {
    if (!saved.path("present").isBoolean())
      throw new IllegalArgumentException("Memory presence required");
    var expected =
        saved.path("present").asBoolean()
            ? snapshot(repository, text(saved, "base_sha"), encode(saved.path("document")))
            : absent(repository, text(saved, "base_sha"));
    if (!expected.equals(saved))
      throw new IllegalArgumentException("Memory snapshot or hash mismatch");
  }

  public static void requireUnchanged(JsonNode approved, JsonNode current) {
    validate(current, text(current, "repository"));
    if (approved == null || approved.isMissingNode()) {
      if (current.path("present").asBoolean())
        throw new IllegalArgumentException(
            "Project memory appeared; research and approve a new proposal");
      return;
    }
    validate(approved, text(current, "repository"));
    if (!approved.path("hash").equals(current.path("hash")))
      throw new IllegalArgumentException(
          "Project memory changed; research and approve a new proposal");
  }
}
