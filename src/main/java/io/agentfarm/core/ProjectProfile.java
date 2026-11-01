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
    String hash) {
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
              "container")
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
        sha256(canonical(n)));
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
        "hash",
        hash);
  }

  public boolean permits(String path) {
    return allowedPaths.stream()
        .anyMatch(p -> p.endsWith("/") ? path.startsWith(p) : path.equals(p));
  }
}
