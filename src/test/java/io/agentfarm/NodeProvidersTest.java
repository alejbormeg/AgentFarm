package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.adapters.NodeProviders;
import io.agentfarm.core.*;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class NodeProvidersTest {
  @TempDir Path dir;

  NodeProviders providers() {
    var store = new Store(dir.resolve("db").toString());
    var clock = Clock.systemUTC();
    var budget = new BudgetService(store, clock);
    var control = new ControlService(store, clock, budget);
    control.registerNode("napoleon-1", "napoleon", "owner");
    return new NodeProviders(store, clock);
  }

  @Test
  void theOwnersChoiceIsKeptPerRoleAndEmptyMeansTheExecutorsOwn() {
    var providers = providers();
    assertTrue(providers.roles("napoleon-1").isEmpty());
    var saved =
        providers.save(
            "napoleon-1",
            parse("{\"roles\":{\"review\":\"antigravity\",\"research\":\"\"}}"),
            "owner");
    assertEquals("antigravity", saved.path("review").asText());
    assertFalse(saved.has("research"), "no choice for a role means the executor's setting stands");
    assertEquals("antigravity", providers.roles("napoleon-1").path("review").asText());
  }

  @Test
  void unknownProvidersNodesAndNonOwnersAreRefused() {
    var providers = providers();
    assertThrows(
        IllegalArgumentException.class,
        () -> providers.save("napoleon-1", parse("{\"roles\":{\"review\":\"skynet\"}}"), "owner"));
    assertThrows(
        IllegalArgumentException.class,
        () -> providers.save("ghost", parse("{\"roles\":{\"review\":\"codex\"}}"), "owner"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            providers.save(
                "napoleon-1", parse("{\"roles\":{\"review\":\"codex\"}}"), "node:napoleon-1"));
    assertThrows(IllegalArgumentException.class, () -> providers.roles("bad id"));
  }
}
