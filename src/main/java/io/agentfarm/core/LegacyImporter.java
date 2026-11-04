package io.agentfarm.core;

import static io.agentfarm.core.Store.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class LegacyImporter {
  private final Store store;

  public LegacyImporter(Store store) {
    this.store = store;
  }

  public Map<String, Object> importFile(Path source, String actor) {
    if (!Files.isRegularFile(source))
      throw new IllegalArgumentException("Existing SQLite file required");
    try (Connection old =
        DriverManager.getConnection(
            "jdbc:sqlite:" + source.toAbsolutePath().toUri() + "?mode=ro")) {
      try (var begin = old.createStatement()) {
        begin.execute("BEGIN");
      }
      Map<String, Object> result =
          store.tx(c -> {
            int copied = 0;
            for (String table :
                List.of(
                    "tasks",
                    "runs",
                    "budget_accounts",
                    "budget_windows",
                    "budget_runs",
                    "budget_allocations")) {
              if (one(old, "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", table)
                  == null) continue;
              List<String> columns =
                  rows(c, "PRAGMA table_info(" + table + ")").stream()
                      .map(r -> (String) r.get("name"))
                      .toList();
              for (var row : rows(old, "SELECT * FROM " + table)) {
                if (!row.keySet().equals(new LinkedHashSet<>(columns)))
                  throw new IllegalArgumentException("Unexpected legacy schema for " + table);
                Object[] values = columns.stream().map(row::get).toArray();
                String placeholders = String.join(",", Collections.nCopies(columns.size(), "?"));
                if (exec(
                        c,
                        "INSERT OR IGNORE INTO "
                            + table
                            + "("
                            + String.join(",", columns)
                            + ") VALUES("
                            + placeholders
                            + ")",
                        values)
                    == 0) {
                  String where =
                      String.join(" AND ", columns.stream().map(k -> k + " IS ?").toList());
                  if (one(c, "SELECT 1 FROM " + table + " WHERE " + where, values) == null)
                    throw new IllegalArgumentException(
                        "Conflicting existing legacy row in "
                            + table
                            + "; import into a new database");
                } else copied++;
              }
            }
            exec(c, "INSERT OR IGNORE INTO task_runtime(project,id) SELECT project,id FROM tasks");
            audit(c, actor, "legacy.import", "sqlite");
            return Json.obj("state", "imported", "rows", copied);
          });
      try (var rollback = old.createStatement()) {
        rollback.execute("ROLLBACK");
      }
      return result;
    } catch (SQLException e) {
      throw new IllegalArgumentException("Cannot read legacy SQLite database", e);
    }
  }
}
