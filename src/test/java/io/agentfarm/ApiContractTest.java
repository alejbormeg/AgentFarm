package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentfarm.core.*;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Keys and types of every JSON answer the panel gives, over a realistic farm. The new interface and
 * the hexagonal refactor both lean on this: neither may change the API without it showing here.
 * Values are never stored, so the golden file can't hold a secret or go stale with the clock.
 *
 * <p>After a deliberate change: {@code ./mvnw test -Dtest=ApiContractTest -Dcontract.update=true}
 * and read the diff of {@code src/test/resources/api-contract.json} before committing it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiContractTest {
  private static final Path GOLDEN = Path.of("src/test/resources/api-contract.json");
  private static final ObjectMapper PRETTY =
      new ObjectMapper()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Path DIR;

  static {
    try {
      DIR = Files.createTempDirectory("agentfarm-contract-");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("agentfarm.db", () -> DIR.resolve("farm.db").toString());
  }

  @LocalServerPort int port;
  @Autowired WorkflowService work;
  @Autowired Store store;
  @Autowired Clock clock;

  private final HttpClient client =
      HttpClient.newBuilder()
          .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final Map<String, JsonNode> seen = new TreeMap<>();
  private String initiative;

  private String auth() throws Exception {
    String credentials = "admin:" + Files.readString(DIR.resolve("admin-password.txt")).trim();
    return "Basic "
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", auth())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    JsonNode csrf = parse(get("/api/csrf").body());
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", auth())
            .header("Content-Type", "application/json")
            .header(csrf.path("header").asText(), csrf.path("token").asText())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** Records a read under its route, with ids replaced so the golden file reads as the route. */
  private void read(String route, String path) throws Exception {
    record("GET " + route, get(path));
  }

  /** Records a write the same way, and fails right here if the seed itself stops working. */
  private JsonNode write(String route, String path, Object body) throws Exception {
    var answer = post(path, MAPPER.writeValueAsString(body));
    assertEquals(200, answer.statusCode(), route + ": " + answer.body());
    record("POST " + route, answer);
    return parse(answer.body());
  }

  private void record(String key, HttpResponse<String> answer) {
    ObjectNode entry = NODES.objectNode();
    entry.put("status", answer.statusCode());
    String body = answer.body();
    if (body != null && !body.isBlank() && body.stripLeading().matches("(?s)[\\[{\"].*"))
      entry.set("body", shape(parse(body)));
    JsonNode previous = seen.get(key);
    seen.put(key, previous == null ? entry : merge(previous, entry));
  }

  private static JsonNode n(Object... values) {
    return MAPPER.valueToTree(obj(values));
  }

  private static String commandId(Object claim) {
    return MAPPER.valueToTree(claim).path("command").path("id").asText();
  }

  private static Map<String, Object> proposal(boolean recipeFields) {
    var proposal =
        new LinkedHashMap<String, Object>(
            obj(
                "base_sha", "b".repeat(40),
                "summary", "Corrige el cupón caducado",
                "acceptance", "Un cupón caducado no descuenta nada",
                "as_is", "flowchart LR\nA-->B",
                "to_be", "flowchart LR\nA-->C",
                "unit_test", "./unit",
                "integration_test", "./integration",
                "criteria", List.of(obj("id", "AC-1", "description", "d", "method", "manual"))));
    if (recipeFields) proposal.put("risk", "low");
    return proposal;
  }

  /** A farm with one task in each stage the panel treats differently. */
  @BeforeAll
  void seed() throws Exception {
    write(
        "/api/budget/configure",
        "/api/budget/configure",
        obj("account", "demo", "mode", "subscription", "shares", obj("weekly", 9800)));
    long now = Instant.now(clock).getEpochSecond();
    write(
        "/api/budget/{operation}",
        "/api/budget/open-window",
        obj(
            "account",
            "demo",
            "period",
            "weekly",
            "start",
            now - 86400,
            "end",
            now + 6 * 86400,
            "total",
            10000,
            "used",
            1000,
            "observed_at",
            now));
    write(
        "/api/budget/{operation}",
        "/api/budget/reserve",
        obj("account", "demo", "run_id", "work-still-open", "amounts", obj("weekly", 50)));
    write("/api/nodes", "/api/nodes", obj("id", "napoleon-demo", "role", "napoleon"));
    write("/api/workers", "/api/workers", obj("id", "demo-boxer", "node", "napoleon-demo"));
    write("/api/workers", "/api/workers", obj("id", "demo-reviewer", "node", "napoleon-demo"));
    write("/api/executors", "/api/executors", obj("node", "napoleon-demo", "account", "demo"));
    write(
        "/api/runtime/observations",
        "/api/runtime/observations",
        obj(
            "snapshot",
            obj(
                "node",
                "napoleon-demo",
                "executor",
                "napoleon-demo",
                "quota",
                obj("weekly_used_percent", 40, "observed_at", Instant.now(clock).toString())),
            "events",
            List.of(
                obj(
                    "external_id", "contract-event-1",
                    "at", Instant.now(clock).toString(),
                    "worker", "demo-boxer",
                    "kind", "output",
                    "text", "Pruebas en marcha"))));

    // Waiting on the owner's approval.
    write(
        "/api/work",
        "/api/work",
        obj(
            "request_id", "contract-proposed",
            "repository", "demo/tienda",
            "title", "Añadir métricas",
            "request", "Añade métricas de latencia"));
    var claim =
        write(
            "/api/work/agent/claim",
            "/api/work/agent/claim",
            obj("agent", "contract-agent", "integration", false));
    write(
        "/api/work/agent/commands/{id}",
        "/api/work/agent/commands/" + commandId(claim),
        obj("agent", "contract-agent", "state", "proposed", "proposal", proposal(false)));

    // Waiting on the owner's integration, with its evidence and every criterion decided.
    String sha = "c".repeat(40);
    work.create(
        MAPPER.valueToTree(
            obj(
                "request_id", "contract-review",
                "repository", "demo/tienda",
                "title", "Corregir el cupón",
                "request", "El cupón caducado se sigue aplicando",
                "recipe", obj("id", "bugfix", "version", 1))),
        "owner");
    String research = commandId(work.claim("worker"));
    write(
        "/api/budget/{operation}",
        "/api/budget/reserve",
        obj("account", "demo", "run_id", "work-" + research, "amounts", obj("weekly", 100)));
    write(
        "/api/budget/{operation}",
        "/api/budget/settle",
        obj("account", "demo", "run_id", "work-" + research, "actual", obj("weekly", 80)));
    work.report(research, n("state", "proposed", "proposal", proposal(true)), "worker");
    write(
        "/api/work/{id}/actions/{action}",
        "/api/work/contract-review/actions/implement",
        obj("version", 1));
    work.report(
        commandId(work.claim("worker")),
        n(
            "state",
            "review",
            "sha",
            sha,
            "pr",
            3,
            "branch",
            "agentfarm/3-cupon",
            "evidence",
            obj(
                "sha",
                sha,
                "passed",
                true,
                "unit",
                obj("status", "passed", "exit_code", 0, "command", "./unit"),
                "integration",
                obj("status", "passed", "exit_code", 0, "command", "./integration"))),
        "worker");
    EvidenceFixtures.accept(store, clock, "contract-review");
    // What the verifier leaves once GitHub answers; without credentials it never runs here.
    String basis = parse(get("/api/evidence/contract-review").body()).path("basis").asText();
    store.tx(c ->
        Store.exec(
            c,
            "INSERT OR REPLACE INTO evidence_verifications(item,basis,sha,state,reason,detail,at)"
                + " VALUES(?,?,?,?,?,?,?)",
            "contract-review",
            basis,
            sha,
            "success",
            "La CI del commit pasó entera.",
            encode(
                obj(
                    "checks",
                    List.of(
                        obj(
                            "name",
                            "AgentFarm unit",
                            "status",
                            "completed",
                            "conclusion",
                            "success")),
                    "files",
                    List.of("demo/a.txt"),
                    "added",
                    List.of("demo/a.txt"),
                    "secrets",
                    List.of())),
            now));

    // Low risk with every gate passed: ready to integrate in a batch.
    candidate("contract-ready", "test-improvement", "d".repeat(40));
    // Integrated, so the digest and the metrics have something to count.
    candidate("contract-integrated", "dependency-update", "e".repeat(40));
    store.tx(c -> {
      Store.exec(c, "UPDATE work_items SET state='integrated' WHERE id='contract-integrated'");
      Store.exec(
          c,
          "INSERT INTO audit(at,actor,action,subject) VALUES(?,?,?,?)",
          Instant.now(clock).toString(),
          "control-plane",
          "work.integrated",
          "contract-integrated");
      return null;
    });

    // Stopped, with the reason the panel shows.
    work.create(
        n(
            "request_id", "contract-blocked",
            "repository", "demo/otra",
            "title", "Adoptar otra",
            "request", "Adopta el repositorio"),
        "owner");
    work.report(
        commandId(work.claim("worker")),
        n("state", "blocked", "error", "Ejecutor desconectado"),
        "worker");

    // Cancelled by a person.
    work.create(
        n(
            "request_id", "contract-cancelled",
            "repository", "demo/tercera",
            "title", "Cancelada",
            "request", "No hace falta"),
        "owner");
    write("/api/work/{id}/actions/{action}", "/api/work/contract-cancelled/actions/cancel", obj());

    var created =
        write(
            "/api/initiatives",
            "/api/initiatives",
            obj(
                "request_id", "contract-molino-1",
                "title", "Migrar a la API v2",
                "goal", "Pasar la tienda a la API v2",
                "review_limit", 2,
                "milestones", List.of(obj("id", "M1", "title", "Cliente nuevo"))));
    initiative = created.path("id").asText();
    write(
        "/api/initiatives/{id}/tasks",
        "/api/initiatives/" + initiative + "/tasks",
        obj(
            "version",
            created.path("version").asInt(),
            "item",
            "contract-proposed",
            "milestone",
            "M1",
            "depends_on",
            List.of()));
    write("/api/governance/paused/{paused}", "/api/governance/paused/false", obj());
    write(
        "/api/automation/triggers",
        "/api/automation/triggers",
        obj(
            "repository",
            "demo/tienda",
            "kinds",
            List.of("failing_ci"),
            "max_new",
            2,
            "enabled",
            true));
    write(
        "/api/automation/autonomy",
        "/api/automation/autonomy",
        obj("recipe", "dependency-update", "level", "auto_implement"));
    write(
        "/api/tasks",
        "/api/tasks",
        obj(
            "id", "CONTRACT-001",
            "project", "demo",
            "title", "Tarea del coordinador",
            "acceptance", List.of("Se simula sin tocar ningún repositorio"),
            "allowed_paths", List.of("docs/")));
    write("/api/simulate", "/api/simulate", obj());
  }

  private void candidate(String id, String recipe, String sha) {
    work.create(
        MAPPER.valueToTree(
            obj(
                "request_id",
                id,
                "repository",
                "demo/tienda",
                "title",
                id,
                "request",
                "Hazlo",
                "recipe",
                obj("id", recipe, "version", 1))),
        "owner");
    work.report(
        commandId(work.claim("worker")),
        n("state", "proposed", "proposal", proposal(true)),
        "worker");
    work.action(id, "implement", n("version", 1), "owner");
    work.report(
        commandId(work.claim("worker")),
        n("state", "review", "sha", sha, "pr", 4, "evidence", obj("sha", sha, "passed", true)),
        "worker");
    EvidenceFixtures.accept(store, clock, id);
  }

  @Test
  void theApiKeepsItsKeysAndTypes() throws Exception {
    for (String route :
        List.of(
            "/api/audit",
            "/api/automation",
            "/api/budget/demo",
            "/api/budget/demo/reservations",
            "/api/csrf",
            "/api/evidence",
            "/api/evidence/commandments",
            "/api/executors",
            "/api/github-app",
            "/api/health",
            "/api/initiatives",
            "/api/installation",
            "/api/me",
            "/api/me/2fa",
            "/api/nodes",
            "/api/overview",
            "/api/providers",
            "/api/recipes",
            "/api/runs",
            "/api/runtime",
            "/api/skills",
            "/api/tasks",
            "/api/version",
            "/api/work",
            "/api/work/costs",
            "/api/work/digest",
            "/api/work/metrics",
            "/api/workers")) read(route, route);
    read("/api/initiatives/{id}", "/api/initiatives/" + initiative);
    read("/api/work/{id}/diff", "/api/work/contract-review/diff");
    for (String task :
        List.of(
            "contract-proposed",
            "contract-review",
            "contract-ready",
            "contract-integrated",
            "contract-blocked",
            "contract-cancelled")) {
      read("/api/evidence/{id}", "/api/evidence/" + task);
    }

    ObjectNode actual = NODES.objectNode();
    seen.forEach(actual::set);
    String rendered = PRETTY.writeValueAsString(PRETTY.treeToValue(actual, Object.class)) + "\n";
    if (Boolean.getBoolean("contract.update")) {
      Files.writeString(GOLDEN, rendered);
      return;
    }
    assertTrue(
        Files.exists(GOLDEN),
        "Falta " + GOLDEN + ". Genéralo con -Dcontract.update=true y revisa el resultado.");
    assertEquals(
        Files.readString(GOLDEN),
        rendered,
        "La API ha cambiado. Si es a propósito, regenera con -Dcontract.update=true y revisa el"
            + " diff de "
            + GOLDEN
            + "; la interfaz y el ejecutor dependen de estas claves.");
  }

  @Test
  void theShapeOfAnAnswerIgnoresValuesButNotKeysOrTypes() {
    assertEquals(
        shape(parse("{\"a\":1,\"b\":\"x\",\"c\":[{\"d\":true}]}")),
        shape(parse("{\"a\":2,\"b\":\"y\",\"c\":[{\"d\":false},{\"d\":true}]}")));
    assertNotEquals(shape(parse("{\"a\":1}")), shape(parse("{\"b\":1}")));
    assertNotEquals(shape(parse("{\"a\":1}")), shape(parse("{\"a\":\"1\"}")));
    assertEquals(
        parse("[{\"a\":\"null|number\",\"b\":\"string\"}]"),
        shape(parse("[{\"a\":1},{\"a\":null,\"b\":\"x\"}]")),
        "array items are merged, so one element with an extra key is still seen");
  }

  /** The type of each value, with array items merged into one shape. */
  static JsonNode shape(JsonNode node) {
    if (node.isObject()) {
      // A week is a value, not a key of the contract: it changes with the calendar.
      var entries = new TreeMap<String, JsonNode>();
      fields(node)
          .forEach((key, value) -> {
            String name = key.matches("\\d{4}-W\\d{2}") ? "{week}" : key;
            JsonNode known = entries.get(name);
            entries.put(name, known == null ? shape(value) : merge(known, shape(value)));
          });
      ObjectNode out = NODES.objectNode();
      entries.forEach(out::set);
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = NODES.arrayNode();
      JsonNode merged = null;
      for (JsonNode item : node) merged = merged == null ? shape(item) : merge(merged, shape(item));
      if (merged != null) out.add(merged);
      return out;
    }
    if (node.isNull()) return NODES.textNode("null");
    if (node.isNumber()) return NODES.textNode("number");
    if (node.isBoolean()) return NODES.textNode("boolean");
    return NODES.textNode("string");
  }

  private static Map<String, JsonNode> fields(JsonNode node) {
    var out = new LinkedHashMap<String, JsonNode>();
    node.fieldNames().forEachRemaining(name -> out.put(name, node.get(name)));
    return out;
  }

  /** Two shapes as one: a key in only one side stays, and differing types become a union. */
  static JsonNode merge(JsonNode a, JsonNode b) {
    if (a.equals(b)) return a;
    if (a.isObject() && b.isObject()) {
      ObjectNode out = NODES.objectNode();
      var names = new TreeSet<String>();
      names.addAll(fields(a).keySet());
      names.addAll(fields(b).keySet());
      for (String name : names) {
        JsonNode left = a.get(name);
        JsonNode right = b.get(name);
        out.set(name, left == null ? right : right == null ? left : merge(left, right));
      }
      return out;
    }
    if (a.isArray() && b.isArray()) {
      if (a.isEmpty()) return b;
      if (b.isEmpty()) return a;
      return NODES.arrayNode().add(merge(a.get(0), b.get(0)));
    }
    if (a.isNumber() && b.isNumber()) return a; // Status codes of a route seen more than once.
    if (a.isTextual() && b.isTextual()) {
      var types = new TreeSet<String>(List.of(a.asText().split("\\|")));
      types.addAll(List.of(b.asText().split("\\|")));
      return NODES.textNode(String.join("|", new ArrayList<>(types)));
    }
    // A value that is an object or list in one answer and a plain type in another, usually null.
    JsonNode structured = a.isTextual() ? b : a;
    JsonNode plain = a.isTextual() ? a : b;
    ObjectNode union = NODES.objectNode();
    union.set("$or", NODES.arrayNode().add(plain).add(structured));
    return union;
  }
}
