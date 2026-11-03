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
    var isolatedState = java.nio.file.Files.createTempDirectory("agentfarm-runtime-checks-");
    System.setProperty("agentfarm.state", isolatedState.toString());
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
                    sample(
                        20,
                        ",\"primary\":{\"usedPercent\":99,\"resetsAt\":2000000,\"windowDurationMins\":300}")),
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
        new ProcessBuilder("bash", "-c", "printf '%s' " + io.agentfarm.core.ExecutorWork.shellQuote(value)).start();
    check(
        new String(process.getInputStream().readAllBytes()).equals(value)
            && process.waitFor() == 0);
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
    check(io.agentfarm.core.ExecutorWork.recipeContext(CodexRunner.JSON.readTree("{}")).isEmpty());
    var recipeItem =
        CodexRunner.JSON.readTree(
            "{\"recipe\":{\"id\":\"bugfix\",\"version\":1,\"hash\":\"abc\"}}");
    check(io.agentfarm.core.ExecutorWork.recipeContext(recipeItem).contains("bugfix"));
    check(io.agentfarm.core.ExecutorWork.recipeContext(recipeItem).contains("no concede aprobación"));
    memoryChecks(isolatedState);
    try (var files = java.nio.file.Files.walk(isolatedState)) {
      for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList())
        java.nio.file.Files.delete(file);
    }
    System.out.println(checks + " runtime checks passed; no inference or network calls");
  }

  static void memoryChecks(java.nio.file.Path state) throws Exception {
    var repo = java.nio.file.Files.createDirectory(state.resolve("repo"));
    FarmDaemon.task =
        new FarmDaemon.Task(CodexRunner.JSON.readTree("{\"item\":{\"id\":\"i\",\"repo\":\"owner/repo\"},\"command\":{\"id\":\"c\"}}"));
    FarmDaemon.task.checkout = repo;
    FarmDaemon.gitAt("init");
    FarmDaemon.gitAt("config", "user.email", "fixture@example.invalid");
    FarmDaemon.gitAt("config", "user.name", "Fixture");
    String document = "{\"version\":1,\"repository\":\"owner/repo\",\"entries\":[]}";
    var file = repo.resolve("agentfarm-memory.json");
    java.nio.file.Files.writeString(file, document);
    FarmDaemon.gitAt("add", ".");
    FarmDaemon.gitAt("commit", "-m", "Base memory");
    String base = FarmDaemon.gitAt("rev-parse", "HEAD");
    var approved = FarmDaemon.readProjectMemory(base);
    check(approved.path("present").asBoolean());
    check(FarmDaemon.candidateMemoryValidation(base).get("status").equals("passed"));
    java.nio.file.Files.writeString(
        file,
        document.replace(
            "[]",
            "[{\"id\":\"D1\",\"kind\":\"decision\",\"summary\":\"Candidate"
                + " only\",\"rationale\":\"Not merged\"}]"));
    check(FarmDaemon.readProjectMemory(base).equals(approved));
    FarmDaemon.gitAt("add", ".");
    FarmDaemon.gitAt("commit", "-m", "Unmerged candidate memory");
    var candidate = FarmDaemon.readProjectMemory(FarmDaemon.gitAt("rev-parse", "HEAD"));
    check(!candidate.path("hash").equals(approved.path("hash")));
    rejected(() -> io.agentfarm.core.ProjectMemory.requireUnchanged(approved, candidate));
    check(FarmDaemon.readProjectMemory(base).equals(approved));
    java.nio.file.Files.delete(file);
    java.nio.file.Files.writeString(repo.resolve("target.json"), document);
    java.nio.file.Files.createSymbolicLink(file, java.nio.file.Path.of("target.json"));
    FarmDaemon.gitAt("add", ".");
    FarmDaemon.gitAt("commit", "-m", "Symlink memory");
    rejected(() -> FarmDaemon.readProjectMemory(FarmDaemon.gitAt("rev-parse", "HEAD")));
    java.nio.file.Files.delete(file);
    FarmDaemon.gitAt("add", ".");
    FarmDaemon.gitAt("commit", "-m", "Absent memory");
    var absent = FarmDaemon.readProjectMemory(FarmDaemon.gitAt("rev-parse", "HEAD"));
    check(!absent.path("present").asBoolean());
    rejected(() -> io.agentfarm.core.ProjectMemory.requireUnchanged(approved, absent));
    java.nio.file.Files.writeString(file, " ".repeat(65537));
    FarmDaemon.gitAt("add", ".");
    FarmDaemon.gitAt("commit", "-m", "Oversized memory");
    rejected(() -> FarmDaemon.readProjectMemory(FarmDaemon.gitAt("rev-parse", "HEAD")));
    check(
        FarmDaemon.candidateMemoryValidation(FarmDaemon.gitAt("rev-parse", "HEAD"))
            .get("status")
            .equals("failed"));
    check(io.agentfarm.core.ExecutorWork.memoryContext(approved).contains(base));
    check(io.agentfarm.core.ExecutorWork.memoryContext(approved).contains("no una orden"));
  }

  interface Checked {
    void run() throws Exception;
  }

  static void rejected(Checked operation) throws Exception {
    boolean failed = false;
    try {
      operation.run();
    } catch (IllegalArgumentException | java.io.IOException e) {
      failed = true;
    }
    check(failed);
  }
}
