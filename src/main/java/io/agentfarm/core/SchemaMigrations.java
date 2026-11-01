package io.agentfarm.core;

import static io.agentfarm.core.Store.*;

import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Ordered, recorded schema changes. Version 1 is the baseline that services create on startup;
 * later versions run once, in one transaction each. A database written by a newer AgentFarm is
 * refused instead of being silently used with an older schema.
 */
@Component
public class SchemaMigrations {
  public record Migration(int version, String name, List<String> statements) {}

  public static final List<Migration> ALL =
      List.of(
          new Migration(1, "baseline", List.of()),
          new Migration(
              2,
              "indexes for claims, audit and initiatives",
              List.of(
                  "CREATE INDEX IF NOT EXISTS work_commands_state ON work_commands(state, kind)",
                  "CREATE INDEX IF NOT EXISTS work_commands_item ON work_commands(item)",
                  "CREATE INDEX IF NOT EXISTS audit_at ON audit(at)")));

  // Services create their baseline tables in their constructors; depending on them orders this.
  public SchemaMigrations(Store store, WorkflowService work, TelemetryService telemetry) {
    apply(store, ALL);
  }

  public static int apply(Store store, List<Migration> migrations) {
    return store.tx(
        c -> {
          statement(
              c,
              "CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY,name TEXT"
                  + " NOT NULL,applied_at TEXT NOT NULL)");
          int latest = migrations.getLast().version();
          int current =
              (int)
                  num(one(c, "SELECT COALESCE(MAX(version),0) v FROM schema_migrations"), "v");
          if (current > latest)
            throw new IllegalStateException(
                "Database schema v" + current + " is newer than this AgentFarm (v" + latest + ")");
          for (var m : migrations) {
            if (m.version() <= current) continue;
            for (String sql : m.statements()) statement(c, sql);
            exec(
                c,
                "INSERT INTO schema_migrations VALUES(?,?,?)",
                m.version(),
                m.name(),
                java.time.Instant.now().toString());
          }
          return latest;
        });
  }

  public static int version(Store store) {
    return store.tx(
        c -> {
          if (one(c, "SELECT name FROM sqlite_master WHERE name='schema_migrations'") == null)
            return 0;
          return (int)
                num(
                    one(c, "SELECT COALESCE(MAX(version),0) v FROM schema_migrations"),
                    "v");
        });
  }
}
