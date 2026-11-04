package io.agentfarm;

import static io.agentfarm.core.Store.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.Store;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreConcurrencyTest {
  @TempDir Path dir;
  Path memoryDir;

  /**
   * 4000 durable commits take 24 s on an idle ext4 disk and 1.6 s in memory: the cost is fsync.
   * This test is about atomicity and the connection lifecycle, so a slow CI disk must not fail it.
   */
  Path database() throws Exception {
    Path shm = Path.of("/dev/shm");
    if (!Files.isWritable(shm)) return dir.resolve("db");
    memoryDir = Files.createTempDirectory(shm, "agentfarm-store-");
    return memoryDir.resolve("db");
  }

  @AfterEach
  void removeMemoryDir() throws Exception {
    if (memoryDir == null) return;
    try (var files = Files.list(memoryDir)) {
      for (Path file : files.toList()) Files.deleteIfExists(file);
    }
    Files.deleteIfExists(memoryDir);
  }

  @Test
  void repeatedConnectionLifecyclesAcrossStoresPreserveTransactionsUnderLoad() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(45),
        () -> {
          Path db = database();
          var first = new Store(db.toString());
          var second = new Store(db.toString());
          first.tx(c -> {
            statement(c, "CREATE TABLE counter(value INTEGER)");
            exec(c, "INSERT INTO counter VALUES(0)");
            return null;
          });
          var pool =
              Executors.newFixedThreadPool(
                  16,
                  r -> {
                    var t = new Thread(r);
                    t.setDaemon(true);
                    return t;
                  });
          try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int worker = 0; worker < 16; worker++) {
              var store = worker % 2 == 0 ? first : second;
              jobs.add(
                  pool.submit(() -> {
                    for (int i = 0; i < 250; i++)
                      store.tx(c -> {
                        long value = num(one(c, "SELECT value FROM counter"), "value");
                        exec(c, "UPDATE counter SET value=?", value + 1);
                        return null;
                      });
                  }));
            }
            for (var job : jobs) job.get(40, TimeUnit.SECONDS);
            long result = first.tx(c -> num(one(c, "SELECT value FROM counter"), "value"));
            assertEquals(4000, result);
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    second.tx(c -> {
                      exec(c, "UPDATE counter SET value=0");
                      throw new IllegalArgumentException("rollback");
                    }));
            long afterRollback = first.tx(c -> num(one(c, "SELECT value FROM counter"), "value"));
            assertEquals(4000, afterRollback);
          } finally {
            pool.shutdownNow();
          }
        });
  }
}
