package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class InsightsTest {
  @TempDir Path dir;
  Store store;
  WorkflowService work;

  @BeforeEach
  void setup() {
    store = new Store(dir.resolve("db").toString());
    work = new WorkflowService(store, Clock.systemUTC(), 2, 1);
  }

  void propose(String id) {
    work.create(MAPPER.valueToTree(obj("request_id", id, "repository", "o/" + id, "title", "Título " + id, "request", "R")), "owner");
    var claim = (Map<?, ?>) work.claim("a-" + id);
    work.report(
        ((Map<?, ?>) claim.get("command")).get("id").toString(),
        MAPPER.valueToTree(
            obj("state", "proposed", "proposal",
                obj("summary", "s", "acceptance", "a", "as_is", "x", "to_be", "y", "unit_test", "u", "integration_test", "i",
                    "base_sha", "b".repeat(40), "criteria", List.of(obj("id", "AC-1", "description", "d", "method", "manual"))))),
        "a-" + id);
  }

  @Test
  void metricsCountRecordedEventsOnly() {
    propose("metric-01");
    propose("metric-02");
    work.action("metric-01", "implement", MAPPER.valueToTree(obj("version", 1)), "owner");
    work.action("metric-02", "correct", MAPPER.valueToTree(obj("feedback", "más tests")), "owner");
    var totals = MAPPER.valueToTree(new InsightsService(store, Clock.systemUTC()).metrics(4)).path("totals");
    assertEquals(2, totals.path("proposals").asInt());
    assertEquals(0.5, totals.path("approval_rate").asDouble());
    assertEquals(1, totals.path("corrections").asInt());
    assertTrue(totals.path("median_cycle_hours").isNull(), "nothing integrated yet");
    assertThrows(IllegalArgumentException.class, () -> new InsightsService(store, Clock.systemUTC()).metrics(0));
  }

  @Test
  void expectedCostComesFromSettledRunsPerRecipeAndKind() {
    work.create(
        MAPPER.valueToTree(obj("request_id", "cost-0001", "repository", "o/r", "title", "T", "request", "R", "recipe", obj("id", "bugfix", "version", 1))),
        "owner");
    store.tx(
        c -> {
          String command = Store.one(c, "SELECT id FROM work_commands WHERE item='cost-0001'").get("id").toString();
          Store.exec(c, "INSERT INTO budget_accounts VALUES('sub','subscription','{}')");
          Store.exec(c, "INSERT INTO budget_runs VALUES('sub',?,'{}','settled','{\"weekly\":120}')", "work-" + command);
          Store.exec(c, "INSERT INTO budget_runs VALUES('sub','work-unknown','{}','settled','{\"weekly\":999}')");
          Store.exec(c, "INSERT INTO budget_runs VALUES('sub','work-x','{}','reserved',NULL)");
          return null;
        });
    var costs = MAPPER.valueToTree(new InsightsService(store, Clock.systemUTC()).costs());
    var bugfix = costs.path("bugfix/research/subscription");
    assertEquals(1, bugfix.path("runs").asInt());
    assertEquals(120, bugfix.path("median").asLong());
    assertEquals("basis_points", bugfix.path("unit").asText());
    assertEquals(1, costs.size(), "unknown commands and open reservations are ignored");
  }

  @Test
  void attentionStatesAreDeliveredOnceWithoutContent() {
    propose("notify-01");
    var sent = new ArrayList<String>();
    var service =
        new NotificationService(store, "https://hooks.example/x", "slack", "http://localhost:8080",
            (URI uri, String type, Map<String, String> headers, String body) -> {
              sent.add(body);
              return 200;
            });
    assertEquals(1, service.deliver());
    assertEquals(0, service.deliver(), "delivered once");
    assertTrue(sent.get(0).contains("Propuesta lista para aprobar: Título notify-01"));
    assertFalse(sent.get(0).contains("acceptance"), "no proposal content");
    var failing =
        new NotificationService(store, "https://hooks.example/x", "json", "p", (u, t, h, b) -> 500);
    propose("notify-02");
    for (int i = 0; i < 7; i++) failing.deliver();
    long attempts =
        store.tx(c -> Store.num(Store.one(c, "SELECT attempts FROM notifications WHERE item='notify-02'"), "attempts"));
    assertEquals(5L, attempts, "bounded retries");
    assertThrows(IllegalArgumentException.class, () -> new NotificationService(store, "http://evil.example/x", "json", "p", (u, t, h, b) -> 200));
    assertThrows(IllegalArgumentException.class, () -> new NotificationService(store, "", "sms", "p", (u, t, h, b) -> 200));
  }
}
