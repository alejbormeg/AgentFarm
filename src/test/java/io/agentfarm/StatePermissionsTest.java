package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentfarm.core.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatePermissionsTest {
  @TempDir Path temp;

  static Set<PosixFilePermission> mode(Path p) throws Exception {
    return Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS);
  }

  void posix() throws Exception {
    assumeTrue(Files.getFileStore(temp).supportsFileAttributeView(PosixFileAttributeView.class));
  }

  @Test
  void aNewStateDirectoryAndItsDatabaseAreBornPrivate() throws Exception {
    posix();
    Path dir = temp.resolve("data/nested"), db = dir.resolve("agentfarm.db");
    var store = new Store(db.toString());
    store.tx(c -> {
      Store.exec(c, "INSERT INTO audit(at,actor,action,subject) VALUES('now','t','x','y')");
      return null;
    });
    assertEquals(StatePermissions.DIRECTORY, mode(dir));
    assertEquals(StatePermissions.FILE, mode(db));
    for (String suffix : new String[] {"-wal", "-shm"}) {
      Path aux = Path.of(db + suffix);
      if (Files.exists(aux)) assertEquals(StatePermissions.FILE, mode(aux), suffix);
    }
  }

  /** What a umask of 022 leaves behind: an installation from before the fix. */
  @Test
  void anExistingStateLeftWithUmask022IsTightenedOnStart() throws Exception {
    posix();
    Path dir = temp.resolve("data"), db = dir.resolve("agentfarm.db");
    new Store(db.toString());
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
    Files.setPosixFilePermissions(db, PosixFilePermissions.fromString("rw-r--r--"));
    for (String suffix : new String[] {"-wal", "-shm", "-journal"}) {
      Path aux = Path.of(db + suffix);
      if (!Files.exists(aux)) Files.createFile(aux);
      Files.setPosixFilePermissions(aux, PosixFilePermissions.fromString("rw-r--r--"));
    }
    new Store(db.toString());
    assertTrue(StatePermissions.exposed(dir).isEmpty());
    assertTrue(StatePermissions.exposed(db).isEmpty());
    // SQLite may remove its WAL and SHM on close; whatever is left is private.
    for (String suffix : new String[] {"-wal", "-shm", "-journal"}) {
      Path aux = Path.of(db + suffix);
      if (Files.exists(aux)) assertTrue(StatePermissions.exposed(aux).isEmpty(), suffix);
    }
    Path left = Files.createFile(dir.resolve("agentfarm.db-shm-left"));
    Files.setPosixFilePermissions(left, PosixFilePermissions.fromString("rw-rw-r--"));
    StatePermissions.ifPresent(left);
    assertEquals(StatePermissions.FILE, mode(left));
    StatePermissions.ifPresent(dir.resolve("missing"));
  }

  @Test
  void aSymlinkedDatabaseIsRefused() throws Exception {
    posix();
    Path dir = Files.createDirectories(temp.resolve("data"));
    Path target = Files.createFile(temp.resolve("elsewhere.db"));
    Files.createSymbolicLink(dir.resolve("agentfarm.db"), target);
    var e =
        assertThrows(
            IllegalStateException.class, () -> new Store(dir.resolve("agentfarm.db").toString()));
    assertTrue(String.valueOf(e.getCause()).contains("enlace simbólico"), String.valueOf(e));
  }

  @Test
  void sharedDirectoriesAreLeftAloneButTheFilesInThemArePrivate() throws Exception {
    posix();
    Path home = Files.createDirectories(temp.resolve("home"));
    Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxr-xr-x"));
    String before = System.getProperty("user.home");
    System.setProperty("user.home", home.toString());
    try {
      new Store(home.resolve("budget.db").toString());
    } finally {
      System.setProperty("user.home", before);
    }
    assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), mode(home));
    assertEquals(StatePermissions.FILE, mode(home.resolve("budget.db")));
  }

  @Test
  void backupsAndRestoresKeepTheSameProtection() throws Exception {
    posix();
    Path db = temp.resolve("data/agentfarm.db");
    new Store(db.toString());
    Path copy = temp.resolve("backups/one.db");
    Backups.backup(db, copy);
    assertEquals(StatePermissions.DIRECTORY, mode(copy.getParent()));
    assertEquals(StatePermissions.FILE, mode(copy));
    Path restored = temp.resolve("restored/agentfarm.db");
    Backups.restore(copy, restored);
    assertEquals(StatePermissions.DIRECTORY, mode(restored.getParent()));
    assertEquals(StatePermissions.FILE, mode(restored));
  }
}
