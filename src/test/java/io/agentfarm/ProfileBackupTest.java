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
    assertNull(profile.sandbox("img:1"));
  }

  @Test
  void shippedExampleProfileRunsInAContainer() throws Exception {
    var example = ProjectProfile.load(Path.of("examples/agentfarm.yaml"));
    assertDoesNotThrow(example::requireSupportedExecution);
    assertEquals("setup", example.sandbox(TestSandbox.DEFAULT_IMAGE).network());
  }

  @Test
  void adoptionFormatGivenToAgentsIsAValidProfile() {
    var profile = ProjectProfile.parse(ProjectProfile.FORMAT);
    assertEquals("owner/name", profile.repository());
    assertEquals("container", profile.environment());
    assertFalse(profile.quality().isEmpty());
  }

  @Test
  void containerProfileDeclaresSandboxPolicy() {
    var defaults = ProjectProfile.parse(PROFILE.replace("local", "container"));
    assertDoesNotThrow(defaults::requireSupportedExecution);
    assertEquals(TestSandbox.Policy.defaults("img:1"), defaults.sandbox("img:1"));
    var custom =
        ProjectProfile.parse(
            PROFILE.replace("local", "container")
                + "container: {image: 'maven:3.9-eclipse-temurin-21', network: none, cpus: 4,"
                + " memory_mb: 8192, timeout_minutes: 20}\n");
    assertEquals(
        new TestSandbox.Policy("maven:3.9-eclipse-temurin-21", "none", 4, 8192, 20),
        custom.sandbox("ignored"));
    assertNotEquals(defaults.hash(), custom.hash());
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
        () -> ProjectProfile.parse(PROFILE + "container: {image: node:22}\n"));
    for (String container :
        new String[] {
          "container: {image: '-v /:/host'}\n",
          "container: {image: node:22, network: host}\n",
          "container: {image: node:22, cpus: 64}\n",
          "container: {image: node:22, privileged: true}\n",
          "container: {image: node:22, memory_mb: '4g'}\n"
        })
      assertThrows(
          IllegalArgumentException.class,
          () -> ProjectProfile.parse(PROFILE.replace("local", "container") + container),
          container);
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
