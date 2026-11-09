package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the board's list costs with a long history: 1,000 and 10,000 tasks, each with a realistic
 * proposal and evidence. Prints the measurements so a change can be compared before and after.
 */
class WorkListLoadTest {
  @TempDir Path dir;

  static void fill(Store store, int from, int to) {
    String proposal =
        Json.encode(Json.obj("summary", "x".repeat(1500), "criteria", "c".repeat(1500)));
    String evidence = Json.encode(Json.obj("checks", "o".repeat(5000)));
    store.tx(c -> {
      for (int i = from; i < to; i++)
        Store.exec(
            c,
            "INSERT INTO work_items(id,repo,title,request,state,version,proposal,evidence,created)"
                + " VALUES(?,?,?,?,?,?,?,?,?)",
            "load-task-" + i,
            "o/r" + (i % 7),
            "Task " + i,
            "r".repeat(800),
            i % 3 == 0 ? "integrated" : "review",
            1,
            proposal,
            evidence,
            i);
      return null;
    });
  }

  record Measure(long millis, long bytes) {}

  static Measure measure(java.util.function.Supplier<Object> list) {
    list.get(); // warm up
    long start = System.nanoTime();
    long bytes = Json.encode(list.get()).length();
    return new Measure((System.nanoTime() - start) / 1_000_000, bytes);
  }

  @Test
  void theListStaysSmallAsHistoryGrows() {
    var store = new Store(dir.resolve("db").toString());
    var work = new WorkflowService(store, Clock.systemUTC());
    fill(store, 0, 1000);
    var full1k = measure(work::items);
    var summary1k = measure(() -> work.summaries(0, 0));
    fill(store, 1000, 10000);
    var summary10k = measure(() -> work.summaries(0, 0));
    var page10k = measure(() -> work.summaries(50, 0));
    System.out.printf(
        "work list: 1k full %d ms %d KB | 1k summaries %d ms %d KB | 10k summaries %d ms %d KB |"
            + " 10k first page of 50 %d ms %d KB%n",
        full1k.millis(),
        full1k.bytes() / 1024,
        summary1k.millis(),
        summary1k.bytes() / 1024,
        summary10k.millis(),
        summary10k.bytes() / 1024,
        page10k.millis(),
        page10k.bytes() / 1024);
    assertTrue(summary1k.bytes() * 10 < full1k.bytes(), "summaries are a tenth of the full rows");
    assertTrue(page10k.bytes() < 64 * 1024, "a page stays small whatever the history");
    assertEquals(50, work.summaries(50, 0).size());
    assertEquals("load-task-9999", work.summaries(1, 0).get(0).get("id"), "newest first");
    assertEquals("load-task-9949", work.summaries(1, 50).get(0).get("id"), "offset pages on");
    assertFalse(work.summaries(1, 0).get(0).containsKey("evidence"));
    assertTrue(work.get("load-task-9999").containsKey("evidence"), "the detail keeps everything");
    assertThrows(IllegalArgumentException.class, () -> work.summaries(5000, 0));
  }
}
