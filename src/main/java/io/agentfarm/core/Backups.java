package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Consistent SQLite snapshots via SQLite's online backup statement; never copies a live WAL. */
public final class Backups {
  public static Object backup(Path source, Path destination) throws Exception {
    source = source.toAbsolutePath().normalize();
    destination = destination.toAbsolutePath().normalize();
    if (!Files.isRegularFile(source) || Files.exists(destination) || source.equals(destination))
      throw new IllegalArgumentException("Existing source and a new destination required");
    Files.createDirectories(destination.getParent());
    try {
      Files.createFile(
          destination,
          java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
              java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException e) {
      Files.createFile(destination);
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + source)) {
      try (var query = connection.prepareStatement("VACUUM INTO ?")) {
        query.setString(1, destination.toString());
        query.execute();
      }
    }
    integrity(destination);
    permissions(destination);
    return obj("state", "backed_up", "path", destination.toString(), "sha256", digest(destination));
  }

  public static Object restore(Path snapshot, Path destination) throws Exception {
    snapshot = snapshot.toAbsolutePath().normalize();
    destination = destination.toAbsolutePath().normalize();
    if (!Files.isRegularFile(snapshot)
        || Files.exists(destination)
        || Files.exists(Path.of(destination + "-wal"))
        || Files.exists(Path.of(destination + "-shm")))
      throw new IllegalArgumentException(
          "Restore requires a new destination; stop the panel before switching databases");
    integrity(snapshot);
    Files.createDirectories(destination.getParent());
    Files.copy(snapshot, destination);
    permissions(destination);
    integrity(destination);
    return obj("state", "restored", "path", destination.toString());
  }

  static void integrity(Path file) throws Exception {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
        var q = c.createStatement();
        var r = q.executeQuery("PRAGMA integrity_check")) {
      if (!r.next() || !r.getString(1).equals("ok"))
        throw new IllegalArgumentException("SQLite integrity check failed");
    }
  }

  static String digest(Path file) throws Exception {
    var digest = java.security.MessageDigest.getInstance("SHA-256");
    try (var in = Files.newInputStream(file)) {
      byte[] b = new byte[8192];
      int count;
      while ((count = in.read(b)) != -1) digest.update(b, 0, count);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  static void permissions(Path file) throws Exception {
    try {
      Files.setPosixFilePermissions(
          file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
    }
  }
}
