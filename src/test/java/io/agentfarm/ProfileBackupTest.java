package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.nio.file.*;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ProfileBackupTest {
  @TempDir Path dir;
  static final String PROFILE =
"""
version: 1
repository: owner/repo
setup: ""
tests:
  unit: ./mvnw test
  integration: ./mvnw verify
allowed_paths: [src/, pom.xml]
environment: local
review: human-and-independent
secret_refs: {}
""";

  @Test
  void profileIsDeclarativeAndEnforcesScope() {
    var profile = ProjectProfile.parse(PROFILE);
    assertEquals(64, profile.hash().length());
    assertTrue(profile.permits("src/main/A.java"));
    assertFalse(profile.permits("pom.xml.bak"));
    assertFalse(profile.permits(".github/workflows/ci.yml"));
    assertEquals(profile.hash(), ProjectProfile.parse("# comment\n" + PROFILE).hash());
    assertDoesNotThrow(profile::requireSupportedExecution);
  }

  @Test
  void rejectUnsafeYamlAndUnimplementedExecution() {
    for (String bad :
        new String[] {
          PROFILE + "version: 1\n",
          PROFILE.replace("src/", "../"),
          PROFILE.replace("src/", ".git"),
          PROFILE.replace("src/", "C:/"),
          PROFILE + "surprise: 1\n",
          "!!java.net.URL [https://example.com]",
          PROFILE.replace("secret_refs: {}", "secret_refs: {TOKEN: literal-secret}")
        }) assertThrows(Exception.class, () -> ProjectProfile.parse(bad), bad);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProjectProfile.parse(PROFILE.replace("local", "container"))
                .requireSupportedExecution());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProjectProfile.parse(
                    PROFILE.replace("secret_refs: {}", "secret_refs: {TOKEN: 'env:TEST_TOKEN'}"))
                .requireSupportedExecution());
  }

  @Test
  void backupIncludesCommittedWalAndRestoreNeverOverwrites() throws Exception {
    Path db = dir.resolve("live.db"),
        backup = dir.resolve("backup.db"),
        restored = dir.resolve("restore.db");
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + db);
        var q = c.createStatement()) {
      q.execute("PRAGMA journal_mode=WAL");
      q.execute("PRAGMA wal_autocheckpoint=0");
      q.execute("CREATE TABLE sample(id INTEGER)");
      q.execute("INSERT INTO sample VALUES(42)");
      assertTrue(Files.exists(Path.of(db + "-wal")));
      Backups.backup(db, backup);
      q.execute("INSERT INTO sample VALUES(43)");
      Backups.restore(backup, restored);
      try (var r = DriverManager.getConnection("jdbc:sqlite:" + restored);
          var result = r.createStatement().executeQuery("SELECT sum(id) FROM sample")) {
        assertTrue(result.next());
        assertEquals(42, result.getInt(1));
      }
    }
    assertThrows(IllegalArgumentException.class, () -> Backups.backup(db, backup));
    assertThrows(IllegalArgumentException.class, () -> Backups.restore(backup, restored));
    Path corrupt = dir.resolve("bad.db");
    Files.writeString(corrupt, "not sqlite");
    assertThrows(
        Exception.class, () -> Backups.restore(corrupt, dir.resolve("should-not-exist.db")));
    assertFalse(Files.exists(dir.resolve("should-not-exist.db")));
  }
}
