package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import io.agentfarm.core.SchemaMigrations.Migration;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaMigrationsTest {
  @TempDir Path dir;

  @Test
  void migrationsRunOnceInOrderAndRefuseNewerDatabases() {
    var store = new Store(dir.resolve("db").toString());
    new SchemaMigrations(
        store, new WorkflowService(store, Clock.systemUTC()), new TelemetryService(store, Clock.systemUTC()),
        new NodeCredentials(store, Clock.systemUTC()));
    int latest = SchemaMigrations.ALL.getLast().version();
    assertEquals(latest, SchemaMigrations.version(store));
    assertEquals(latest, SchemaMigrations.apply(store, SchemaMigrations.ALL), "idempotent");
    var newer = new java.util.ArrayList<>(SchemaMigrations.ALL);
    newer.add(new Migration(latest + 1, "future", List.of("CREATE TABLE future(x)")));
    SchemaMigrations.apply(store, newer);
    assertThrows(IllegalStateException.class, () -> SchemaMigrations.apply(store, SchemaMigrations.ALL));
  }

  @Test
  void failedMigrationLeavesNoTrace() {
    var store = new Store(dir.resolve("db2").toString());
    var broken =
        List.of(
            new Migration(1, "baseline", List.of()),
            new Migration(2, "broken", List.of("CREATE TABLE ok(x)", "NOT SQL")));
    assertThrows(RuntimeException.class, () -> SchemaMigrations.apply(store, broken));
    assertEquals(0, SchemaMigrations.version(store));
  }
}
