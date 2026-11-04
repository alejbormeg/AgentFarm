package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Declarative project instructions. Loading never executes commands. */
public record ProjectProfile(
    String repository,
    String setup,
    Map<String, String> tests,
    List<String> allowedPaths,
    String environment,
    String review,
    Map<String, String> secretRefs,
    TestSandbox.Policy container,
    List<String> mcp,
    Map<String, Object> quality,
    List<String> skills,
    String hash) {
  /** What an adopting agent must follow; a test keeps it accepted by {@link #parse}. */
  public static final String FORMAT =
      """
      version: 1
      repository: owner/name
      setup: comando de instalación, o cadena vacía
      tests:
        unit: comando
        integration: comando
      allowed_paths: [src/, tests/, README.md]
      environment: container
      container: {image: buildpack-deps:noble, network: setup, cpus: 2, memory_mb: 4096, timeout_minutes: 20}
      review: human-and-independent
      secret_refs: {}
      quality:
        checks: {nombre: comando que termina en 0}
        metrics: {nombre: {command: comando que imprime un número, better: lower}}
      # Los nombres usan minúsculas, cifras, - o _ y tienen como máximo 40 caracteres.
      # Los comandos de tests deben ser los de unit_test e integration_test de la propuesta.
      """;

  public static ProjectProfile load(Path file) throws Exception {
    if (Files.size(file) > 65536) throw new IllegalArgumentException("Profile exceeds 64 KiB");
    return parse(Files.readString(file));
  }

  public static ProjectProfile parse(String source) {
    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setCodePointLimit(65536);
    options.setNestingDepthLimit(20);
    Object loaded;
    try {
      loaded = new Yaml(new SafeConstructor(options)).load(source);
    } catch (org.yaml.snakeyaml.error.YAMLException e) {
      throw new IllegalArgumentException("Invalid YAML profile");
    }
    if (!(loaded instanceof Map<?, ?> map))
      throw new IllegalArgumentException("Profile must be a mapping");
    var n = MAPPER.valueToTree(map);
    for (var k : n.properties())
      if (!Set.of(
              "version",
              "repository",
              "setup",
              "tests",
              "allowed_paths",
              "environment",
              "review",
              "secret_refs",
              "container",
              "mcp",
              "quality",
              "skills")
          .contains(k.getKey()))
        throw new IllegalArgumentException("Unknown profile field: " + k.getKey());
    if (!n.path("version").isIntegralNumber() || n.path("version").asInt() != 1)
      throw new IllegalArgumentException("Profile version must be 1");
    if (n.has("setup") && !n.path("setup").isTextual())
      throw new IllegalArgumentException("Setup must be text");
    String repo = text(n, "repository");
    if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
      throw new IllegalArgumentException("Invalid repository");
    var tests = new LinkedHashMap<String, String>();
    if (!n.path("tests").isObject() || n.path("tests").size() != 2)
      throw new IllegalArgumentException("Unit and integration tests required");
    for (String k : List.of("unit", "integration")) tests.put(k, text(n.path("tests"), k));
    if (!n.path("allowed_paths").isArray() || n.path("allowed_paths").isEmpty())
      throw new IllegalArgumentException("Allowed paths required");
    var paths = new ArrayList<String>();
    for (var p : n.path("allowed_paths")) {
      String v = p.asText();
      if (!p.isTextual()
          || v.isBlank()
          || v.startsWith("/")
          || v.contains("\\")
          || Arrays.asList(v.split("/")).contains("..")
          || v.equals(".")
          || v.equals(".git")
          || v.startsWith(".git/")
          || v.contains(":")
          || Arrays.asList(v.split("/")).contains("."))
        throw new IllegalArgumentException("Unsafe allowed path");
      paths.add(v);
    }
    String environment = text(n, "environment"), review = text(n, "review");
    if (!Set.of("local", "container").contains(environment)
        || !Set.of("independent", "human-and-independent").contains(review))
      throw new IllegalArgumentException("Unsupported environment or review policy");
    TestSandbox.Policy container = null;
    if (n.has("container")) {
      var c = n.path("container");
      if (!c.isObject()) throw new IllegalArgumentException("Container must be a mapping");
      for (var k : c.properties())
        if (!Set.of("image", "network", "cpus", "memory_mb", "timeout_minutes")
            .contains(k.getKey()))
          throw new IllegalArgumentException("Unknown container field: " + k.getKey());
      for (String k : List.of("cpus", "memory_mb", "timeout_minutes"))
        if (c.has(k) && !c.path(k).isIntegralNumber())
          throw new IllegalArgumentException("Container " + k + " must be an integer");
      var d = TestSandbox.Policy.defaults(TestSandbox.DEFAULT_IMAGE);
      container =
          new TestSandbox.Policy(
              c.path("image").asText(d.image()),
              c.path("network").asText(d.network()),
              c.path("cpus").asInt(d.cpus()),
              c.path("memory_mb").asInt(d.memoryMb()),
              c.path("timeout_minutes").asInt(d.timeoutMinutes()));
    }
    if (container != null && !environment.equals("container"))
      throw new IllegalArgumentException("Container settings require environment: container");
    // MCP servers are referenced by name only; the executor's operator defines their commands.
    var mcp = new ArrayList<String>();
    if (n.has("mcp")) {
      if (!n.path("mcp").isArray() || n.path("mcp").size() > 8)
        throw new IllegalArgumentException("mcp must be a list of at most 8 server names");
      for (var m : n.path("mcp")) {
        if (!m.isTextual()
            || !m.asText().matches("[a-z0-9][a-z0-9-]{0,39}")
            || mcp.contains(m.asText()))
          throw new IllegalArgumentException("Invalid MCP server name");
        mcp.add(m.asText());
      }
    }
    var quality = quality(n.path("quality"));
    // Names only: the catalog and the operator decide what a name means.
    var skills = new ArrayList<String>();
    if (n.has("skills")) {
      if (!n.path("skills").isArray() || n.path("skills").size() > 16)
        throw new IllegalArgumentException("skills must be a list of at most 16 names");
      for (var name : n.path("skills")) {
        if (!name.isTextual() || !name.asText().matches("[a-z0-9][a-z0-9-]{0,39}"))
          throw new IllegalArgumentException("Invalid skill name");
        skills.add(name.asText());
      }
    }
    var refs = new LinkedHashMap<String, String>();
    if (n.has("secret_refs")) {
      if (!n.path("secret_refs").isObject())
        throw new IllegalArgumentException("Secret references must be a mapping");
      for (var entry : n.path("secret_refs").properties()) {
        if (!entry.getValue().isTextual()
            || !entry.getValue().asText().matches("env:[A-Z_][A-Z0-9_]*"))
          throw new IllegalArgumentException("Use env:NAME secret references, never secret values");
        refs.put(entry.getKey(), entry.getValue().asText());
      }
    }
    return new ProjectProfile(
        repo,
        n.path("setup").asText(""),
        Map.copyOf(tests),
        List.copyOf(paths),
        environment,
        review,
        Map.copyOf(refs),
        container,
        List.copyOf(mcp),
        quality,
        List.copyOf(skills),
        sha256(canonical(n)));
  }

  /**
   * Extra gates: {@code checks} must exit 0; {@code metrics} print a number that may not get worse
   * than on the base commit. Higher is better unless a metric says {@code better: lower}.
   */
  private static Map<String, Object> quality(com.fasterxml.jackson.databind.JsonNode q) {
    if (q.isMissingNode()) return Map.of();
    if (!q.isObject()) throw new IllegalArgumentException("quality must be a mapping");
    for (var k : q.properties())
      if (!Set.of("checks", "metrics").contains(k.getKey()))
        throw new IllegalArgumentException("Unknown quality field: " + k.getKey());
    var checks = new LinkedHashMap<String, String>();
    for (var c : q.path("checks").properties()) {
      qualityName(c.getKey());
      checks.put(c.getKey(), text(q.path("checks"), c.getKey()));
    }
    var metrics = new LinkedHashMap<String, Object>();
    for (var m : q.path("metrics").properties()) {
      qualityName(m.getKey());
      var value = m.getValue();
      String command = value.isTextual() ? value.asText() : text(value, "command");
      String better = value.path("better").asText("higher");
      if (!Set.of("higher", "lower").contains(better))
        throw new IllegalArgumentException("Metric better must be higher or lower");
      metrics.put(m.getKey(), obj("command", command, "better", better));
    }
    if (checks.size() + metrics.size() > 8)
      throw new IllegalArgumentException("At most 8 quality gates");
    return obj("checks", checks, "metrics", metrics);
  }

  private static void qualityName(String name) {
    if (!name.matches("[a-z0-9][a-z0-9_-]{0,39}"))
      throw new IllegalArgumentException("Invalid quality gate name: " + name);
  }

  public void requireSupportedExecution() {
    if (!secretRefs.isEmpty())
      throw new IllegalArgumentException(
          "Secret injection is not implemented; remove references or wait for an isolated adapter");
  }

  /** Sandbox for this project's tests; {@code null} means the profile asks for the host. */
  public TestSandbox.Policy sandbox(String defaultImage) {
    if (environment.equals("local")) return null;
    return container != null ? container : TestSandbox.Policy.defaults(defaultImage);
  }

  public Map<String, Object> summary() {
    return obj(
        "repository",
        repository,
        "setup",
        setup,
        "tests",
        tests,
        "allowed_paths",
        allowedPaths,
        "environment",
        environment,
        "review",
        review,
        "secret_refs",
        secretRefs,
        "container",
        container == null ? Map.of() : container.summary(),
        "mcp",
        mcp,
        "quality",
        quality,
        "skills",
        skills,
        "hash",
        hash);
  }

  public boolean permits(String path) {
    return permitted(allowedPaths, path);
  }

  public static boolean permitted(Collection<String> allowed, String path) {
    return allowed.stream().anyMatch(p -> p.endsWith("/") ? path.startsWith(p) : path.equals(p));
  }
}
