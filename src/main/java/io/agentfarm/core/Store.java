package io.agentfarm.core;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Store {
  private static final java.util.concurrent.ConcurrentMap<String, Object> GATES =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final String url;
  private final Object gate;

  public Store(@Value("${agentfarm.db}") String path) {
    try {
      Path file = Path.of(path).toAbsolutePath().normalize();
      Files.createDirectories(file.getParent());
      url = "jdbc:sqlite:" + file;
      gate = GATES.computeIfAbsent(url, ignored -> new Object());
      synchronized (gate) {
        try (Connection c = connection()) {
          statement(c, "PRAGMA journal_mode=WAL");
          String schema =
              """
              CREATE TABLE IF NOT EXISTS tasks(project TEXT,id TEXT,spec TEXT NOT NULL,hash TEXT NOT NULL,state TEXT,PRIMARY KEY(project,id));
              CREATE TABLE IF NOT EXISTS runs(id INTEGER PRIMARY KEY,project TEXT,task TEXT,hash TEXT,started TEXT,mode TEXT,result TEXT);
              CREATE TABLE IF NOT EXISTS budget_accounts(account TEXT PRIMARY KEY,mode TEXT NOT NULL,shares TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS budget_windows(account TEXT NOT NULL REFERENCES budget_accounts(account),period TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,total INTEGER NOT NULL,share INTEGER NOT NULL,used INTEGER NOT NULL,observed_used INTEGER,observed_at INTEGER,max_age INTEGER NOT NULL,source TEXT NOT NULL,PRIMARY KEY(account,period,start));
              CREATE TABLE IF NOT EXISTS budget_runs(account TEXT NOT NULL REFERENCES budget_accounts(account),run_id TEXT NOT NULL,amounts TEXT NOT NULL,state TEXT NOT NULL,actual TEXT,PRIMARY KEY(account,run_id));
              CREATE TABLE IF NOT EXISTS budget_allocations(account TEXT NOT NULL,run_id TEXT NOT NULL,period TEXT NOT NULL,start INTEGER NOT NULL,reserved INTEGER NOT NULL,PRIMARY KEY(account,run_id,period),FOREIGN KEY(account,run_id) REFERENCES budget_runs(account,run_id),FOREIGN KEY(account,period,start) REFERENCES budget_windows(account,period,start));
              CREATE TABLE IF NOT EXISTS farm_nodes(id TEXT PRIMARY KEY,role TEXT NOT NULL,enabled INTEGER NOT NULL DEFAULT 1,heartbeat INTEGER,reported_state TEXT NOT NULL DEFAULT 'unknown');
              CREATE TABLE IF NOT EXISTS farm_workers(id TEXT PRIMARY KEY,node TEXT NOT NULL REFERENCES farm_nodes(id),enabled INTEGER NOT NULL DEFAULT 1,heartbeat INTEGER,reported_state TEXT NOT NULL DEFAULT 'unknown');
              CREATE TABLE IF NOT EXISTS task_runtime(project TEXT,id TEXT,worker TEXT,progress INTEGER,candidate_sha TEXT,cancel_requested INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(project,id),FOREIGN KEY(project,id) REFERENCES tasks(project,id));
              CREATE TABLE IF NOT EXISTS governance(id INTEGER PRIMARY KEY CHECK(id=1),paused INTEGER NOT NULL);
              INSERT OR IGNORE INTO governance(id,paused) VALUES(1,0);
              CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY,at TEXT NOT NULL,actor TEXT NOT NULL,action TEXT NOT NULL,subject TEXT NOT NULL);
              """;
          for (String sql : schema.split(";")) if (!sql.isBlank()) statement(c, sql);
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("Cannot initialize AgentFarm database", e);
    }
  }

  private Connection connection() throws SQLException {
    Connection c = DriverManager.getConnection(url);
    statement(c, "PRAGMA foreign_keys=ON");
    statement(c, "PRAGMA busy_timeout=10000");
    return c;
  }

  public static void statement(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  @FunctionalInterface
  public interface Work<T> {
    T run(Connection c) throws Exception;
  }

  public <T> T tx(Work<T> work) {
    // BEGIN IMMEDIATE already serializes transactions. Include native open/close in
    // that serialization so concurrent polling cannot race their lifecycle.
    synchronized (gate) {
      try (Connection c = connection()) {
        statement(c, "BEGIN IMMEDIATE");
        try {
          T result = work.run(c);
          statement(c, "COMMIT");
          return result;
        } catch (Exception e) {
          statement(c, "ROLLBACK");
          if (e instanceof RuntimeException r) throw r;
          throw new IllegalStateException("Database operation failed", e);
        }
      } catch (SQLException e) {
        throw new IllegalStateException("Database unavailable", e);
      }
    }
  }

  public static int exec(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement p = c.prepareStatement(sql)) {
      bind(p, args);
      return p.executeUpdate();
    }
  }

  public static List<Map<String, Object>> rows(Connection c, String sql, Object... args)
      throws SQLException {
    try (PreparedStatement p = c.prepareStatement(sql)) {
      bind(p, args);
      try (ResultSet rs = p.executeQuery()) {
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
            row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
          rows.add(row);
        }
        return rows;
      }
    }
  }

  public static Map<String, Object> one(Connection c, String sql, Object... args)
      throws SQLException {
    var rows = rows(c, sql, args);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public static long num(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static void bind(PreparedStatement p, Object[] args) throws SQLException {
    for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
  }

  /** Adds a nullable column to an existing table once; safe on fresh and upgraded databases. */
  public static void ensureColumn(Connection c, String table, String column, String type)
      throws SQLException {
    if (!table.matches("[a-z_]+") || !column.matches("[a-z_]+") || !type.matches("[A-Z]+"))
      throw new IllegalArgumentException("Invalid schema identifier");
    for (var col : rows(c, "PRAGMA table_info(" + table + ")"))
      if (column.equals(col.get("name"))) return;
    statement(c, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
  }

  public static void audit(Connection c, String actor, String action, String subject)
      throws SQLException {
    exec(
        c,
        "INSERT INTO audit(at,actor,action,subject) VALUES(?,?,?,?)",
        java.time.Instant.now().toString(),
        actor,
        action,
        subject);
  }
}
