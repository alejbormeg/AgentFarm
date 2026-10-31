package io.agentfarm;

import static io.agentfarm.core.Store.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.Store;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreConcurrencyTest {
  @TempDir Path dir;

  @Test
  void repeatedConnectionLifecyclesAcrossStoresPreserveTransactionsUnderLoad() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(45),
        () -> {
          var first = new Store(dir.resolve("db").toString());
          var second = new Store(dir.resolve("db").toString());
          first.tx(
              c -> {
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
                  pool.submit(
                      () -> {
                        for (int i = 0; i < 250; i++)
                          store.tx(
                              c -> {
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
                    second.tx(
                        c -> {
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
