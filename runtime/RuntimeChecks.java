import java.time.Instant;

public class RuntimeChecks {
  static int checks = 0;

  static void check(boolean condition) {
    checks++;
    if (!condition) throw new AssertionError("Check " + checks);
  }

  static String sample(double percent, String extra) {
    return "{\"rateLimits\":{\"secondary\":{\"usedPercent\":"
        + percent
        + ",\"windowDurationMins\":10080,\"resetsAt\":2000000}"
        + extra
        + "}}";
  }

  public static void main(String[] args) throws Exception {
    var now = Instant.ofEpochSecond(1000000);
    check(
        FarmDaemon.quotaObservation(CodexRunner.JSON.readTree(sample(97, "")), now)
            .path("allowed")
            .asBoolean());
    check(
        !FarmDaemon.quotaObservation(CodexRunner.JSON.readTree(sample(98, "")), now)
            .path("allowed")
            .asBoolean());
    check(
        !FarmDaemon.quotaObservation(
                CodexRunner.JSON.readTree(
                    sample(20, ",\"primary\":{\"usedPercent\":99,\"windowDurationMins\":300}")),
                now)
            .path("allowed")
            .asBoolean());
    for (String invalid :
        new String[] {
          "{}", sample(-1, ""), sample(101, ""), sample(20, "").replace("2000000", "999999")
        }) {
      boolean rejected = false;
      try {
        FarmDaemon.quotaObservation(CodexRunner.JSON.readTree(invalid), now);
      } catch (Exception e) {
        rejected = true;
      }
      check(rejected);
    }
    String value = "quoted ' text $(not-a-command)";
    var process =
        new ProcessBuilder("bash", "-c", "printf '%s' " + FarmDaemon.shellQuote(value)).start();
    check(
        new String(process.getInputStream().readAllBytes()).equals(value)
            && process.waitFor() == 0);
    String sha = "a".repeat(40);
    var remote =
        CodexRunner.JSON.valueToTree(
            CodexRunner.obj("head", CodexRunner.obj("sha", sha), "state", "open", "draft", false));
    var evidence =
        CodexRunner.JSON.valueToTree(
            CodexRunner.obj(
                "sha", sha, "passed", true, "ci_checks", java.util.List.of("unit", "integration")));
    var success =
        CodexRunner.JSON.valueToTree(
            CodexRunner.obj(
                "total_count",
                2,
                "check_runs",
                java.util.List.of(
                    CodexRunner.obj("name", "unit", "status", "completed", "conclusion", "success"),
                    CodexRunner.obj(
                        "name", "integration", "status", "completed", "conclusion", "success"))));
    var statuses = CodexRunner.JSON.readTree("{\"total_count\":0}");
    var reviews = CodexRunner.JSON.readTree("[]");
    FarmDaemon.validateMerge(remote, evidence, success, statuses, reviews, sha);
    check(true);
    for (int fault = 0; fault < 6; fault++) {
      var r = remote.deepCopy();
      var e = evidence.deepCopy();
      var c = success.deepCopy();
      var v = reviews.deepCopy();
      switch (fault) {
        case 0 ->
            ((com.fasterxml.jackson.databind.node.ObjectNode) r.path("head"))
                .put("sha", "b".repeat(40));
        case 1 -> ((com.fasterxml.jackson.databind.node.ObjectNode) r).put("draft", true);
        case 2 ->
            ((com.fasterxml.jackson.databind.node.ObjectNode) c.path("check_runs").get(0))
                .put("conclusion", "failure");
        case 3 ->
            ((com.fasterxml.jackson.databind.node.ObjectNode) c.path("check_runs").get(1))
                .put("status", "in_progress");
        case 4 -> ((com.fasterxml.jackson.databind.node.ObjectNode) e).put("passed", false);
        case 5 ->
            v =
                CodexRunner.JSON.readTree(
                    "[{\"user\":{\"login\":\"reviewer\"},\"state\":\"CHANGES_REQUESTED\"}]");
      }
      boolean rejected = false;
      try {
        FarmDaemon.validateMerge(r, e, c, statuses, v, sha);
      } catch (Exception expected) {
        rejected = true;
      }
      check(rejected);
    }
    check(!FarmDaemon.clean(null).isBlank());
    var releaseQuota = new java.util.concurrent.CountDownLatch(1);
    var heartbeat = new java.util.concurrent.CountDownLatch(1);
    var loops =
        FarmDaemon.observationLoops(
            () -> {
              try {
                releaseQuota.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            heartbeat::countDown,
            20,
            20);
    try {
      check(heartbeat.await(1, java.util.concurrent.TimeUnit.SECONDS));
    } finally {
      releaseQuota.countDown();
      loops.shutdownNow();
    }
    System.out.println(checks + " runtime checks passed; no inference or network calls");
  }
}
