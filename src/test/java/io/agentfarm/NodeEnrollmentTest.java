package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.adapters.NodeEnrollment;
import io.agentfarm.core.*;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class NodeEnrollmentTest {
  @TempDir Path dir;
  Store store;
  NodeCredentials credentials;

  /** A clock the test moves. */
  static final class Moving extends Clock {
    Instant now = Instant.parse("2026-11-14T10:00:00Z");

    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  Moving clock = new Moving();

  NodeEnrollment enrollment() {
    store = new Store(dir.resolve("db").toString());
    var budget = new BudgetService(store, clock);
    new ControlService(store, clock, budget);
    budget.execute(
        "configure",
        parse("{\"account\":\"sub\",\"mode\":\"subscription\",\"shares\":{\"weekly\":9800}}"),
        "owner");
    credentials = new NodeCredentials(store, clock);
    new SchemaMigrations(
        store, new WorkflowService(store, clock), new TelemetryService(store, clock), credentials);
    return new NodeEnrollment(store, clock, credentials);
  }

  @Test
  void aCodeCreatesTheNodeAndWorksOnce() {
    var enrollment = enrollment();
    var created = enrollment.create("napoleon-2", "", "owner");
    String code = created.get("code").toString();
    assertTrue(code.startsWith("afc_"));
    assertEquals("sub", created.get("account"));
    var issued = MAPPER.valueToTree(enrollment.redeem(code));
    assertTrue(issued.path("token").asText().startsWith("afn_"));
    assertEquals("napoleon-2", issued.path("node").asText());
    assertNotNull(credentials.authenticate(issued.path("token").asText()), "the credential works");
    assertThrows(
        IllegalArgumentException.class, () -> enrollment.redeem(code), "a second use is refused");
  }

  @Test
  void aCodeExpires() {
    var enrollment = enrollment();
    String code = enrollment.create("napoleon-3", "sub", "owner").get("code").toString();
    clock.now = clock.now.plusSeconds(NodeEnrollment.TTL_SECONDS + 1);
    assertThrows(IllegalArgumentException.class, () -> enrollment.redeem(code));
  }

  @Test
  void onlyTheOwnerAsksAndGuessesAreRefused() {
    var enrollment = enrollment();
    assertThrows(IllegalArgumentException.class, () -> enrollment.create("n", "sub", "node:x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> enrollment.create("napoleon-s2", "sub", "owner"),
        "slot-like ids are reserved");
    assertThrows(IllegalArgumentException.class, () -> enrollment.create("ok", "missing", "owner"));
    for (String bad :
        new String[] {null, "", "afc_short", "afn_" + "a".repeat(32), "afc_" + "a".repeat(32)})
      assertThrows(
          IllegalArgumentException.class, () -> enrollment.redeem(bad), String.valueOf(bad));
  }

  @Test
  void theNodeAndItsWorkersExistAfterwardsAndRepeatingIsHarmless() {
    var enrollment = enrollment();
    enrollment.create("napoleon-4", "sub", "owner");
    enrollment.create("napoleon-4", "sub", "owner");
    var rows =
        store.tx(
            c -> Store.rows(c, "SELECT id FROM farm_workers WHERE node='napoleon-4' ORDER BY id"));
    assertEquals(2, rows.size());
  }
}
