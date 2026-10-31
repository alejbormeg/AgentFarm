package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class MigrationTest {
  @TempDir Path dir;

  Path legacy(String name) throws Exception {
    Path source = dir.resolve(name + ".db");
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + source)) {
      String sql = Files.readString(Path.of("src/test/resources/legacy-" + name + ".sql"));
      for (String statement : sql.split(";"))
        if (!statement.isBlank())
          try (var s = c.createStatement()) {
            s.execute(statement);
          }
    }
    return source;
  }

  @Test
  void importsPythonTaskAndPreservesOriginalHashAndIdempotency() throws Exception {
    Path old = legacy("coordinator");
    Store store = new Store(dir.resolve("new.db").toString());
    LegacyImporter importer = new LegacyImporter(store);
    importer.importFile(old, "owner");
    var c = new CoordinatorService(store, Clock.systemUTC());
    var hash = c.tasks().getFirst().get("hash");
    assertNotNull(hash);
    var spec = Json.parse(Files.readString(Path.of("src/test/resources/legacy-spec.json")));
    assertEquals("already_enqueued", c.enqueue(spec, "owner").get("state"));
    assertEquals(0, importer.importFile(old, "owner").get("rows"));
  }

  @Test
  void importsOutstandingBudgetReservationsWithoutRefund() throws Exception {
    Path old = legacy("budget");
    Store store = new Store(dir.resolve("new.db").toString());
    new LegacyImporter(store).importFile(old, "owner");
    var b = new BudgetService(store, Clock.fixed(Instant.ofEpochSecond(110), ZoneOffset.UTC));
    var status = Json.MAPPER.valueToTree(b.status("legacy"));
    assertEquals(500, status.at("/windows/0/reserved").asLong());
    assertEquals(4400, status.at("/windows/0/remaining").asLong());
  }

  @Test
  void conflictsRollbackInsteadOfOverwritingTask() throws Exception {
    Path old = legacy("coordinator");
    Store store = new Store(dir.resolve("new.db").toString());
    var spec = Json.parse(Files.readString(Path.of("src/test/resources/legacy-spec.json")));
    ((com.fasterxml.jackson.databind.node.ObjectNode) spec).put("title", "Different");
    new CoordinatorService(store, Clock.systemUTC()).enqueue(spec, "owner");
    assertThrows(
        IllegalArgumentException.class, () -> new LegacyImporter(store).importFile(old, "owner"));
  }
}
