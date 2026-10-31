package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static io.agentfarm.core.Store.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CoordinatorTest {
  @TempDir Path dir;
  Store store;
  CoordinatorService service;
  ControlService control;
  Clock clock = Clock.fixed(Instant.ofEpochSecond(110), ZoneOffset.UTC);

  com.fasterxml.jackson.databind.JsonNode spec(String id) {
    return MAPPER.valueToTree(
        obj(
            "id",
            id,
            "project",
            "demo",
            "title",
            "Test",
            "acceptance",
            List.of("Evidence"),
            "allowed_paths",
            List.of("docs/")));
  }

  @BeforeEach
  void setup() {
    store = new Store(dir.resolve("state.db").toString());
    service = new CoordinatorService(store, clock);
    control = new ControlService(store, clock, new BudgetService(store, clock));
  }

  @Test
  void idempotencyAndSpecConflict() {
    service.enqueue(spec("T1"), "test");
    assertEquals("already_enqueued", service.enqueue(spec("T1"), "test").get("state"));
    var changed = spec("T1").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed).put("title", "Other");
    assertThrows(IllegalArgumentException.class, () -> service.enqueue(changed, "test"));
    assertEquals(1, service.tasks().size());
  }

  @Test
  void capacityPreventsWork() {
    service.enqueue(spec("T1"), "test");
    service.enqueue(spec("T2"), "test");
    var result = service.simulate(1, 5, "test");
    assertEquals(List.of(), result.get("implementation_evidence"));
    assertEquals("simulation", result.get("mode"));
    assertEquals("review_capacity", service.simulate(1, 5, "test").get("reason"));
    assertEquals("ready", service.tasks().get(1).get("state"));
  }

  @Test
  void dailyLimit() {
    service.enqueue(spec("T1"), "test");
    service.enqueue(spec("T2"), "test");
    service.simulate(2, 1, "test");
    assertEquals("daily_limit", service.simulate(2, 1, "test").get("reason"));
  }

  @Test
  void crashedWorkIsNotRepeated() {
    service.enqueue(spec("T1"), "test");
    store.tx(
        c -> {
          exec(c, "UPDATE tasks SET state='running'");
          return null;
        });
    assertEquals(
        "running_task_requires_reconciliation", service.simulate(2, 5, "test").get("reason"));
    assertEquals(0, service.report().size());
  }

  @Test
  void rejectsEscapePaths() {
    for (String path : List.of("../private", "/etc", "docs/../../private", "C:\\private")) {
      var s = (com.fasterxml.jackson.databind.node.ObjectNode) spec("T1");
      s.set("allowed_paths", MAPPER.valueToTree(List.of(path)));
      assertThrows(IllegalArgumentException.class, () -> service.enqueue(s, "test"));
    }
  }

  @Test
  void idle() {
    assertEquals("idle", service.simulate(2, 5, "test").get("state"));
  }

  @Test
  void pauseStopsAdmissionAndIsAudited() {
    control.pause(true, "owner");
    assertEquals("human_pause", service.simulate(2, 5, "test").get("reason"));
    assertEquals("farm.pause", control.audit().getFirst().get("action"));
  }

  @Test
  void cancellationPreservesTaskHistory() {
    service.enqueue(spec("T1"), "test");
    control.cancel("demo", "T1", "owner");
    assertEquals("cancelled", service.tasks().getFirst().get("state"));
    assertEquals("idle", service.simulate(2, 5, "test").get("state"));
  }

  @Test
  void heartbeatIsUnknownUntilObservedAndExpires() {
    control.registerNode("napoleon-1", "napoleon", "owner");
    assertEquals("unknown", control.nodes().getFirst().get("status"));
    control.heartbeat("node", "napoleon-1", "active", "owner");
    assertEquals("active", control.nodes().getFirst().get("status"));
    var later =
        new ControlService(
            store,
            Clock.fixed(Instant.ofEpochSecond(156), ZoneOffset.UTC),
            new BudgetService(store, clock));
    assertEquals("stale", later.nodes().getFirst().get("status"));
  }

  @Test
  void workerNeedsRealNodeAndDisableOverridesHeartbeat() {
    assertThrows(
        IllegalArgumentException.class,
        () -> control.registerWorker("boxer-1", "missing", "owner"));
    control.registerNode("napoleon-1", "napoleon", "owner");
    control.registerWorker("boxer-1", "napoleon-1", "owner");
    control.heartbeat("node", "napoleon-1", "active", "owner");
    control.heartbeat("worker", "boxer-1", "idle", "owner");
    control.enable("node", "napoleon-1", false, "owner");
    assertEquals("node_unavailable", control.workers().getFirst().get("status"));
  }

  @Test
  void assignmentRequiresBudgetAndFreshIdleWorkerAndPauseBlocksIt() {
    control.registerNode("napoleon-1", "napoleon", "owner");
    control.registerWorker("boxer-1", "napoleon-1", "owner");
    service.enqueue(spec("T1"), "test");
    var request =
        MAPPER.valueToTree(
            obj(
                "project",
                "demo",
                "id",
                "T1",
                "worker",
                "boxer-1",
                "account",
                "codex",
                "run_id",
                "run-1",
                "amounts",
                obj("weekly", 100)));
    assertThrows(IllegalArgumentException.class, () -> control.assign(request, "owner"));
    control.heartbeat("node", "napoleon-1", "active", "owner");
    control.heartbeat("worker", "boxer-1", "idle", "owner");
    BudgetService b = new BudgetService(store, clock);
    b.execute(
        "configure",
        MAPPER.valueToTree(
            obj("account", "codex", "mode", "subscription", "shares", obj("weekly", 5000))),
        "owner");
    assertEquals("paused", control.assign(request, "owner").get("state"));
    assertEquals("ready", service.tasks().getFirst().get("state"));
    b.execute(
        "open-window",
        MAPPER.valueToTree(
            obj(
                "account",
                "codex",
                "period",
                "weekly",
                "start",
                100,
                "end",
                200,
                "total",
                10000,
                "used",
                0,
                "observed_at",
                100)),
        "owner");
    control.pause(true, "owner");
    assertEquals("paused", control.assign(request, "owner").get("state"));
    control.pause(false, "owner");
    assertEquals("assigned", control.assign(request, "owner").get("state"));
    assertEquals("assigned", service.tasks().getFirst().get("state"));
    control.progress("demo", "T1", "boxer-1", 25, "owner");
    assertEquals("running", service.tasks().getFirst().get("state"));
    control.cancel("demo", "T1", "owner");
    assertEquals(1, ((Number) service.tasks().getFirst().get("cancel_requested")).intValue());
  }

  @Test
  void canonicalHashMatchesPythonFixture() throws Exception {
    var legacy = parse(Files.readString(Path.of("src/test/resources/legacy-spec.json")));
    var result = service.enqueue(legacy, "test");
    String sql = Files.readString(Path.of("src/test/resources/legacy-coordinator.sql"));
    assertTrue(sql.contains((String) result.get("spec_hash")));
  }

  @Test
  void legacyFloatFormattingDoesNotChangeTaskIdentity() {
    String original =
        "{\"acceptance\": [\"Evidence\"], \"allowed_paths\": [\"docs/\"], \"extra\": 1e-07, \"id\":"
            + " \"float\", \"project\": \"demo\", \"title\": \"Test\"}";
    String hash = sha256(original);
    store.tx(
        c -> {
          exec(c, "INSERT INTO tasks VALUES(?,?,?,?,?)", "demo", "float", original, hash, "ready");
          return null;
        });
    assertEquals("already_enqueued", service.enqueue(parse(original), "test").get("state"));
    assertEquals(hash, service.tasks().getFirst().get("hash"));
  }
}
