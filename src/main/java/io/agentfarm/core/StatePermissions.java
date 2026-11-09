package io.agentfarm.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.Set;

/**
 * The panel's state (database, its WAL/SHM, secrets, backups) is readable only by the account that
 * runs it, whatever the umask. On file systems without POSIX permissions nothing is changed: there
 * the host's ACLs are the protection (see docs/SECURITY.md).
 */
public final class StatePermissions {
  public static final Set<PosixFilePermission>
      DIRECTORY = PosixFilePermissions.fromString("rwx------"),
      FILE = PosixFilePermissions.fromString("rw-------");

  private StatePermissions() {}

  /**
   * Creates the directory private, or makes an existing one private; refuses one it can't own. The
   * working directory, the home directory and the root are shared places: they are left alone, and
   * only the files in them are made private.
   */
  public static void directory(Path dir) throws IOException {
    if (!posix(dir)) {
      Files.createDirectories(dir);
      return;
    }
    if (!Files.exists(dir))
      Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIRECTORY));
    Path real = dir.toRealPath();
    if (shared(real)) return;
    restrict(real, DIRECTORY);
  }

  static boolean shared(Path real) throws IOException {
    if (real.getParent() == null) return true;
    for (String place : java.util.List.of("user.home", "user.dir")) {
      String value = System.getProperty(place);
      if (value != null && Files.exists(Path.of(value)) && Files.isSameFile(real, Path.of(value)))
        return true;
    }
    return false;
  }

  /** Creates an empty private file if missing, so SQLite never creates it with the umask. */
  public static void file(Path file) throws IOException {
    if (!posix(file.getParent())) {
      if (!Files.exists(file)) Files.createFile(file);
      return;
    }
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
      Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE));
    restrict(file, FILE);
  }

  /** Tightens a file that may exist (WAL, SHM, journal); missing is fine. */
  public static void ifPresent(Path file) throws IOException {
    if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && posix(file.getParent()))
      restrict(file, FILE);
  }

  /** Permissions anyone but the owner holds; empty when private or not POSIX. */
  public static Set<PosixFilePermission> exposed(Path path) throws IOException {
    if (!posix(path)) return Set.of();
    var exposed =
        new java.util.HashSet<>(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
    exposed.removeAll(
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
    return exposed;
  }

  private static void restrict(Path path, Set<PosixFilePermission> wanted) throws IOException {
    if (Files.isSymbolicLink(path))
      throw new IllegalStateException(
          "El estado del panel no puede ser un enlace simbólico: " + path);
    if (Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(wanted)) return;
    String me = System.getProperty("user.name"),
        owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName();
    try {
      Files.setPosixFilePermissions(path, wanted);
    } catch (IOException e) {
      throw new IllegalStateException(
          path
              + " pertenece a "
              + owner
              + " y el panel corre como "
              + me
              + ": no puede hacerlo privado. Cambia su propietario o ejecuta el panel con esa cuenta.",
          e);
    }
  }

  private static Path existing(Path path) {
    Path p = path.toAbsolutePath();
    while (p != null && !Files.exists(p)) p = p.getParent();
    return p == null ? path.toAbsolutePath().getRoot() : p;
  }

  private static boolean posix(Path path) {
    try {
      return path != null
          && Files.getFileStore(existing(path))
              .supportsFileAttributeView(PosixFileAttributeView.class);
    } catch (IOException e) {
      return false;
    }
  }
}
