import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** One durable WSL executor; GitHub credentials stay in gh's user configuration. */
public class FarmDaemon extends CodexRunner {
  static final String AGENT = System.getProperty("agentfarm.node", "napoleon-local"),
      GH = System.getProperty("agentfarm.gh", "gh");
  // Several executor processes of one node run as slots: own claims, workspaces and journal.
  static final String SLOT = System.getProperty("agentfarm.slot", ""),
      SUFFIX = SLOT.isBlank() ? "" : "-s" + SLOT,
      CLAIMANT = AGENT + SUFFIX;
  static final String
      IMPLEMENTER = System.getProperty("agentfarm.implementer", AGENT + SUFFIX + "-boxer"),
      REVIEWER = System.getProperty("agentfarm.reviewer", AGENT + SUFFIX + "-reviewer");
  // Of the process, not of an order: the quota reading, the last failure seen, and the last agent
  // session shown.
  static volatile JsonNode quota = JSON.createObjectNode();
  static volatile String model = "", session = "", failure = "";
  static volatile long quotaAt = 0;
  static final List<Map<String, Object>> activity = new CopyOnWriteArrayList<>();

  /**
   * The order being worked on. It starts empty for each order, so nothing carries over to the next.
   */
  static final class Task {
    final JsonNode claim;
    final String commandId, itemId, repo;
    volatile boolean cancelled;
    volatile Path checkout;
    volatile String defaultBranch;
    volatile List<io.agentfarm.core.McpCatalog.Server> mcpServers = List.of();
    volatile boolean mcpTurn;
    volatile io.agentfarm.core.Prompts.Rendered implementPrompt, reviewPrompt;
    volatile String phase = "idle", active = "", provider = "codex";
    // The quota window this order reserved in; 0 until it has a reservation.
    volatile long reservedReset;
    // A repository-limited token from the panel when it has a GitHub App; otherwise the gh login.
    // In this process's memory, handed only to gh and git here, and never to an agent's turn.
    volatile String githubToken;
    volatile Instant githubTokenExpires = Instant.EPOCH;

    Task(JsonNode claim) {
      this.claim = claim;
      commandId = claim.path("command").path("id").asText();
      itemId = claim.path("item").path("id").asText();
      repo = claim.path("item").path("repo").asText();
    }
  }

  static volatile Task task;

  static boolean cancelled() {
    var running = task;
    return running != null && running.cancelled;
  }

  static final Path WORK = STATE.resolve("workspaces" + SUFFIX);
  // Tests run in a disposable container unless the operator explicitly allows the host.
  static final String SANDBOX = System.getProperty("agentfarm.sandbox", "container"),
      SANDBOX_IMAGE =
          System.getProperty(
              "agentfarm.sandbox-image", io.agentfarm.core.TestSandbox.DEFAULT_IMAGE),
      DOCKER = System.getProperty("agentfarm.docker", "docker");
  static final Path JOURNAL = STATE.resolve("reservation-journal" + SUFFIX + ".json");
  static volatile long lastQuota = 0;

  static String clean(String s) {
    return io.agentfarm.core.ExecutorWork.clean(s);
  }

  static void event(String kind, String message) {
    // The panel rejects a blank event, and one rejected batch would stop all later telemetry.
    if (message == null || message.isBlank()) return;
    var running = task;
    activity.add(
        obj(
            "external_id",
            UUID.randomUUID().toString(),
            "at",
            Instant.now().toString(),
            "worker",
            running == null || running.active.isBlank() ? AGENT : running.active,
            "kind",
            kind,
            "text",
            clean(message).substring(0, Math.min(clean(message).length(), 5500))));
  }

  static String process(Path cwd, int seconds, String... args) throws Exception {
    Path log = Files.createTempFile(STATE, "command-", ".log");
    var b =
        new ProcessBuilder(args)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    var running = task;
    if (running != null
        && running.githubToken != null
        && (args[0].equals(GH) || args[0].equals("git"))) {
      githubAccess();
      b.environment().put("GH_TOKEN", running.githubToken);
    }
    b.environment()
        .put("PATH", System.getProperty("user.home") + "/.local/bin:" + System.getenv("PATH"));
    var p = b.start();
    try {
      long deadline = System.currentTimeMillis() + seconds * 1000L;
      while (!p.waitFor(1, TimeUnit.SECONDS) && System.currentTimeMillis() < deadline) {
        if (cancelled()) {
          p.descendants().forEach(ProcessHandle::destroyForcibly);
          p.destroyForcibly();
          throw new IOException("Operación cancelada");
        }
      }
      if (p.isAlive()) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        throw new IOException("Command timed out: " + args[0]);
      }
      String output = Files.readString(log);
      if (output.length() > 150000) output = output.substring(output.length() - 150000);
      if (p.exitValue() != 0)
        throw new IOException(
            args[0] + ": " + clean(output).substring(0, Math.min(clean(output).length(), 1800)));
      return output.trim();
    } finally {
      Files.deleteIfExists(log);
    }
  }

  static JsonNode github(String method, String endpoint, Object payload) throws Exception {
    Path body = Files.createTempFile(STATE, "github-", ".json");
    try {
      Files.writeString(body, JSON.writeValueAsString(payload == null ? Map.of() : payload));
      var args = new ArrayList<>(List.of(GH, "api", "--method", method, endpoint));
      if (payload != null) args.addAll(List.of("--input", body.toString()));
      // Reads are retried on transient network failures; writes are not, to avoid duplicates.
      for (int attempt = 1; ; attempt++) {
        try {
          String result = process(STATE, 45, args.toArray(String[]::new));
          return result.isBlank() ? JSON.createObjectNode() : JSON.readTree(result);
        } catch (IOException e) {
          String m = String.valueOf(e.getMessage());
          boolean transientFailure =
              m.contains("timeout")
                  || m.contains("TLS handshake")
                  || m.contains("connection reset")
                  || m.contains("HTTP 502")
                  || m.contains("HTTP 503");
          if (!method.equals("GET") || !transientFailure || attempt == 3) throw e;
          event("error", "GitHub no respondió (" + attempt + "/3); reintento: " + clean(m));
          Thread.sleep(5000L * attempt);
        }
      }
    } finally {
      Files.deleteIfExists(body);
    }
  }

  static void githubAccess() throws Exception {
    if (task.githubToken != null
        && Instant.now().isBefore(task.githubTokenExpires.minusSeconds(600))) return;
    var answer =
        post("/work/agent/commands/" + task.commandId + "/github-token", obj("agent", CLAIMANT));
    task.githubToken = answer.path("token").isTextual() ? answer.path("token").asText() : null;
    task.githubTokenExpires =
        task.githubToken == null
            ? Instant.EPOCH
            : Instant.parse(answer.path("expires_at").asText());
  }

  static String gitAt(String... args) throws Exception {
    var a = new ArrayList<>(List.of("git", "-C", task.checkout.toString()));
    if (task.githubToken != null)
      a.addAll(
          List.of(
              "-c",
              "credential.helper=",
              "-c",
              "credential.helper=!" + GH + " auth git-credential"));
    a.addAll(List.of(args));
    return process(STATE, 120, a.toArray(String[]::new));
  }

  static JsonNode report(String state, Object... fields) throws Exception {
    var m = obj("agent", CLAIMANT, "state", state);
    for (int i = 0; i < fields.length; i += 2) m.put((String) fields[i], fields[i + 1]);
    return post("/work/agent/commands/" + task.commandId, m);
  }

  static JsonNode quotaObservation(JsonNode r, Instant now) throws Exception {
    return io.agentfarm.core.AgentAdapters.get("codex").normalizeQuota(r, now);
  }

  static void subscriptionIdentity() throws Exception {
    try {
      io.agentfarm.core.AgentAdapters.get("codex")
          .validateAuthentication(rpc("account/read", obj("refreshToken", false)));
    } catch (IllegalArgumentException e) {
      quota = JSON.createObjectNode();
      quotaAt = 0;
      throw new IOException(e.getMessage());
    }
  }

  static synchronized void refreshQuota() throws Exception {
    subscriptionIdentity();
    var r = rpc("account/rateLimits/read", null);
    quota = quotaObservation(r, Instant.now());
    double percent = quota.path("weekly_used_percent").asDouble();
    long reset = quota.path("resets_at").asLong();
    quotaAt = System.currentTimeMillis();
    lastQuota = quotaAt;
    Files.writeString(STATE.resolve("quota.json"), quota.toString());
    try {
      var status = get("/budget/" + ACCOUNT);
      boolean matching = false;
      for (var w : status.path("windows"))
        if (w.path("period").asText().equals("weekly") && w.path("end").asLong() == reset)
          matching = true;
      if (!matching && task == null)
        post(
            "/budget/rollover",
            obj(
                "account",
                ACCOUNT,
                "period",
                "weekly",
                "start",
                reset - 604800,
                "end",
                reset,
                "total",
                10000,
                "used",
                (int) Math.ceil(percent * 100),
                "observed_at",
                Instant.now().getEpochSecond(),
                "source",
                "provider",
                "max_age",
                60));
      post(
          "/budget/observe",
          obj(
              "account",
              ACCOUNT,
              "period",
              "weekly",
              "start",
              reset - 604800,
              "used",
              (int) Math.ceil(percent * 100),
              "observed_at",
              Instant.now().getEpochSecond()));
      failure = "";
    } catch (Exception e) {
      failure = "Clover necesita reconciliar la ventana: " + e.getMessage();
    }
  }

  static void guard() throws Exception {
    if (task.reservedReset != 0 && quota.path("resets_at").asLong() != task.reservedReset)
      throw new IOException("Ventana de cuota cambió; requiere conciliación");
    if (cancelled()) throw new IOException("Cancelación o pausa solicitada");
    if (System.currentTimeMillis() - quotaAt > 60000
        || !quota.path("allowed").asBoolean()
        || quota.path("weekly_used_percent").asDouble(100) >= 98)
      throw new IOException("Cuota desconocida o límite del 2 % alcanzado");
    // Admission is for new work. An active run already owns the reserved margin.
    // Rechecking free balance here would reject the run's own reservation.
    if (task.reservedReset == 0) {
      // The panel judges by the last snapshot it received, not by the reading just taken here.
      try {
        telemetry();
      } catch (Exception e) {
        event("error", "No se pudo publicar la cuota: " + e.getMessage());
      }
      var admission =
          post(
              "/providers/admission",
              obj(
                  "provider",
                  "codex",
                  "account",
                  ACCOUNT,
                  "profile",
                  System.getProperty("agentfarm.resource-profile", "balanced"),
                  "additional_payment",
                  false));
      if (!admission.path("state").asText().equals("admissible"))
        throw new IOException("Provider admission blocked: " + admission.path("reasons"));
    }
    if (report("heartbeat").path("paused").asBoolean()) throw new IOException("Pausa solicitada");
    var overview = get("/overview");
    for (var node : overview.path("nodes"))
      if (node.path("id").asText().equals(AGENT) && node.path("enabled").asInt() != 1)
        throw new IOException("Nodo deshabilitado");
    for (var w : overview.path("workers"))
      if (w.path("id").asText().equals(task.active) && w.path("enabled").asInt() != 1)
        throw new IOException("Boxer deshabilitado");
  }

  // Provider per role. Claude Code is metered in USD against a Clover API account.
  static final String CLAUDE = System.getProperty("agentfarm.claude", "claude"),
      CLAUDE_ACCOUNT = System.getProperty("agentfarm.claude-account", ""),
      CLAUDE_MODEL = System.getProperty("agentfarm.claude-model", "");
  static final long CLAUDE_TURN_MICROS = Long.getLong("agentfarm.claude-turn-micros", 3_000_000L);

  static String providerFor(String role) {
    String p = System.getProperty("agentfarm.provider." + role, "codex");
    if (!Set.of("codex", "claude-code").contains(p))
      throw new IllegalArgumentException("Unknown provider for " + role + ": " + p);
    return p;
  }

  // MCP servers requested by the approved project profile, resolved against STATE/mcp-servers.json.

  static void useMcp(JsonNode names) throws Exception {
    var list = new ArrayList<String>();
    names.forEach(n -> list.add(n.asText()));
    task.mcpServers =
        list.isEmpty()
            ? List.of()
            : io.agentfarm.core.McpCatalog.load(STATE.resolve("mcp-servers.json")).resolve(list);
  }

  static String agentTurn(String role, String prompt) throws Exception {
    task.provider = providerFor(role);
    task.mcpTurn = !role.equals("implement") && !task.mcpServers.isEmpty();
    try {
      return task.provider.equals("claude-code") ? claudeTurn(role, prompt) : turn(prompt, role);
    } finally {
      task.provider = "codex";
    }
  }

  static String claudeTurn(String role, String prompt) throws Exception {
    if (CLAUDE_ACCOUNT.isBlank())
      throw new IOException(
          "Claude Code requiere -Dagentfarm.claude-account con una cuenta Clover api");
    Path key = STATE.resolve("anthropic-api-key");
    if (!Files.isReadable(key)) throw new IOException("Falta " + key + " (permiso 600)");
    var status = get("/budget/" + CLAUDE_ACCOUNT);
    if (!status.path("mode").asText().equals("api"))
      throw new IOException("La cuenta Claude Code debe ser de modo api (USD)");
    var amounts = new LinkedHashMap<String, Object>();
    for (var w : status.path("windows")) amounts.put(w.path("period").asText(), CLAUDE_TURN_MICROS);
    String run =
        "claude-"
            + task.commandId
            + "-"
            + role
            + "-"
            + UUID.randomUUID().toString().substring(0, 8);
    var reserved =
        post("/budget/reserve", obj("account", CLAUDE_ACCOUNT, "run_id", run, "amounts", amounts));
    if (!reserved.path("state").asText().equals("reserved"))
      throw new IOException("Clover rechazó la reserva Claude Code: " + reserved.path("reasons"));
    event("lifecycle", "Claude Code · " + role + " · reserva " + CLAUDE_TURN_MICROS / 1e6 + " USD");
    Object actual = null;
    try {
      Path mcpConfig = null;
      var names = new ArrayList<String>();
      if (task.mcpTurn) {
        mcpConfig = Files.createTempFile(STATE, "mcp-", ".json");
        Files.writeString(
            mcpConfig,
            JSON.writeValueAsString(
                io.agentfarm.core.McpCatalog.claudeConfig(task.mcpServers, System.getenv())));
        task.mcpServers.forEach(m -> names.add(m.name()));
      }
      var b =
          new ProcessBuilder(
                  io.agentfarm.core.ClaudeCodeAdapter.command(
                      CLAUDE,
                      role,
                      CLAUDE_MODEL,
                      names,
                      mcpConfig == null ? null : mcpConfig.toString(),
                      io.agentfarm.core.AgentSandbox.hiddenPaths(
                          agentProfile(task.checkout, role.equals("implement")))))
              .directory(task.checkout.toFile())
              .redirectErrorStream(false);
      for (String k : List.of("OPENAI_API_KEY", "CODEX_API_KEY", "GH_TOKEN", "GITHUB_TOKEN"))
        b.environment().remove(k);
      b.environment().put("ANTHROPIC_API_KEY", Files.readString(key).trim());
      b.redirectError(STATE.resolve("claude-stderr" + SUFFIX + ".log").toFile());
      Path out = Files.createTempFile(STATE, "claude-", ".json");
      b.redirectOutput(out.toFile());
      var p = b.start();
      try {
        try (var in = p.getOutputStream()) {
          in.write(prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        long deadline = System.currentTimeMillis() + 2_700_000;
        while (!p.waitFor(1, TimeUnit.SECONDS)) {
          if (cancelled() || System.currentTimeMillis() > deadline) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            throw new IOException(cancelled() ? "Operación cancelada" : "Turno supera 45 minutos");
          }
        }
        var result = io.agentfarm.core.ClaudeCodeAdapter.parse(Files.readString(out));
        session = result.session();
        model = result.model();
        var cost = new LinkedHashMap<String, Object>();
        for (var period : amounts.keySet()) cost.put(period, result.costMicros());
        actual = cost;
        event("message", result.text());
        event("lifecycle", "Claude Code · coste " + result.costMicros() / 1e6 + " USD");
        return result.text();
      } finally {
        Files.deleteIfExists(out);
        if (mcpConfig != null) Files.deleteIfExists(mcpConfig);
      }
    } finally {
      // Unknown cost (crash, timeout) leaves the run "unknown": Clover blocks more work until
      // the owner reconciles it.
      try {
        post("/budget/settle", obj("account", CLAUDE_ACCOUNT, "run_id", run, "actual", actual));
      } catch (Exception e) {
        failure = "Liquidación Claude Code pendiente: " + clean(e.getMessage());
      }
    }
  }

  static String turn(String prompt, String role) throws Exception {
    subscriptionIdentity();
    guard();
    events.clear();
    var t =
        rpc(
            "thread/start",
            obj(
                "cwd",
                task.checkout.toString(),
                "approvalPolicy",
                "never",
                "config",
                threadConfig(role, task.mcpTurn)));
    thread = t.path("thread").path("id").asText();
    session = thread;
    model = t.path("model").asText();
    var r =
        rpc(
            "turn/start",
            obj("threadId", thread, "input", List.of(obj("type", "text", "text", prompt))));
    turn = r.path("turn").path("id").asText();
    event("lifecycle", "Turno iniciado");
    long start = System.currentTimeMillis(), next = 0;
    StringBuilder result = new StringBuilder();
    try {
      while (true) {
        if (System.currentTimeMillis() - start > 2700000)
          throw new IOException("Turno supera 45 minutos");
        if (System.currentTimeMillis() >= next) {
          guard();
          next = System.currentTimeMillis() + 5000;
        }
        var n = events.poll(1, TimeUnit.SECONDS);
        if (n == null) {
          if (!server.isAlive()) throw new IOException("Codex detenido");
          continue;
        }
        if (!n.path("params").path("threadId").asText(thread).equals(thread)) continue;
        String method = n.path("method").asText();
        var params = n.path("params");
        if (method.equals("item/agentMessage/delta")) result.append(params.path("delta").asText());
        if (method.equals("item/completed")) {
          var it = params.path("item");
          String type = it.path("type").asText();
          if (type.equals("commandExecution"))
            event(
                "command",
                it.path("command").asText() + "\n" + it.path("aggregatedOutput").asText());
          if (type.equals("agentMessage")) event("message", it.path("text").asText());
        }
        if (method.equals("turn/completed")) {
          if (!params.path("turn").path("status").asText().equals("completed"))
            throw new IOException("Turno incompleto");
          event("lifecycle", "Turno finalizado");
          return result.toString();
        }
      }
    } finally {
      if (turn != null) {
        try {
          rpc("turn/interrupt", obj("threadId", thread, "turnId", turn));
        } catch (Exception ignored) {
        }
        turn = null;
      }
    }
  }

  static String marker() {
    return "<!-- agentfarm:" + task.itemId + " -->";
  }

  static int ensureIssue(JsonNode item) throws Exception {
    if (item.path("issue").asInt() > 0) return item.path("issue").asInt();
    // Enumerate repository issues directly, avoiding eventual-consistency search duplicates.
    for (int page = 1; page <= 100; page++) {
      var issues =
          github("GET", "repos/" + task.repo + "/issues?state=all&per_page=100&page=" + page, null);
      for (var issue : issues)
        if (!issue.has("pull_request") && issue.path("body").asText().contains(marker()))
          return issue.path("number").asInt();
      if (issues.size() < 100) break;
      if (page == 100) throw new IOException("Issue reconciliation needs pagination review");
    }
    return github(
            "POST",
            "repos/" + task.repo + "/issues",
            obj(
                "title",
                item.path("title").asText(),
                "body",
                marker()
                    + "\n## Petición\n"
                    + (item.path("request").asText()
                        + io.agentfarm.core.ExecutorWork.recipeContext(item))
                    + "\n\nEstado: investigación pendiente."))
        .path("number")
        .asInt();
  }

  static JsonNode readProjectMemory(String source) throws Exception {
    var missing = io.agentfarm.core.ProjectMemory.absent(task.repo, source);
    String entry =
        gitAt("ls-tree", "--full-tree", source, "--", io.agentfarm.core.ProjectMemory.FILE);
    if (entry.isBlank()) return missing;
    if (!entry.startsWith("100644 ") && !entry.startsWith("100755 "))
      throw new IOException("Project memory must be a regular committed file, not a symlink");
    String object = source + ":" + io.agentfarm.core.ProjectMemory.FILE;
    if (Long.parseLong(gitAt("cat-file", "-s", object)) > io.agentfarm.core.ProjectMemory.MAX_BYTES)
      throw new IOException("Project memory exceeds 64 KiB");
    return io.agentfarm.core.ProjectMemory.snapshot(task.repo, source, gitAt("show", object));
  }

  static Map<String, Object> candidateMemoryValidation(String sha) {
    try {
      var snapshot = readProjectMemory(sha);
      return obj(
          "status",
          "passed",
          "sha",
          sha,
          "present",
          snapshot.path("present").asBoolean(),
          "hash",
          snapshot.path("hash").asText());
    } catch (Exception e) {
      return obj("status", "failed", "sha", sha, "error", clean(e.getMessage()));
    }
  }

  static JsonNode currentProjectMemory() throws Exception {
    String branch = github("GET", "repos/" + task.repo, null).path("default_branch").asText();
    return readProjectMemory(gitAt("rev-parse", "origin/" + branch));
  }

  static void workspace(JsonNode item, int issue, boolean implement) throws Exception {
    task.checkout = WORK.resolve(task.itemId);
    Files.createDirectories(WORK);
    if (!Files.exists(task.checkout.resolve(".git"))) {
      process(STATE, 120, GH, "repo", "clone", task.repo, task.checkout.toString());
    }
    Files.writeString(
        task.checkout.resolve(".git/info/exclude"),
        "\n__pycache__/\n*.pyc\n",
        StandardOpenOption.APPEND);
    gitAt("fetch", "origin");
    task.defaultBranch = github("GET", "repos/" + task.repo, null).path("default_branch").asText();
    String branch = io.agentfarm.core.ExecutorWork.branchName(item, issue);
    if (implement) {
      if (!gitAt("branch", "--list", branch).isBlank()
          && !(startsOver(item, branch) && !resumable(item, branch))) gitAt("checkout", branch);
      else gitAt("checkout", "-B", branch, "origin/" + task.defaultBranch);
      report("checkpoint", "branch", branch);
    } else if (gitAt("status", "--porcelain").isBlank()) {
      if (item.path("branch").isTextual()
          && !gitAt("branch", "--list", branch).isBlank()
          && !startsOver(item, branch)) gitAt("checkout", branch);
      else gitAt("checkout", "--detach", "origin/" + task.defaultBranch);
    }
  }

  /** Work on a branch the base has moved past would be judged without the base's own files. */
  static boolean startsOver(JsonNode item, String branch) throws Exception {
    String base = "origin/" + task.defaultBranch;
    return io.agentfarm.core.ExecutorWork.isAdoption(item)
        || !gitAt("merge-base", base, branch).strip().equals(gitAt("rev-parse", base).strip());
  }

  /** The receipt of this same version names the branch head: only publishing is left to do. */
  static boolean resumable(JsonNode item, String branch) throws Exception {
    Path receipt = STATE.resolve("receipts" + SUFFIX).resolve(task.itemId + ".json");
    if (!Files.exists(receipt)) return false;
    var saved = JSON.readTree(Files.readString(receipt));
    return io.agentfarm.core.ExecutorWork.receiptAtHead(
        saved, item, gitAt("rev-parse", branch).strip());
  }

  /** The base branch governs: a candidate cannot set the rules it is judged by or block its fix. */
  static io.agentfarm.core.ProjectProfile baseProfile() throws Exception {
    String base = "origin/" + task.defaultBranch;
    if (gitAt("ls-tree", "--name-only", base, "agentfarm.yaml").isBlank()) return null;
    return io.agentfarm.core.ProjectProfile.parse(gitAt("show", base + ":agentfarm.yaml"));
  }

  /** Whether the agent's new profile will load, since the executor reads it on every later task. */
  static Map<String, Object> candidateProfile(JsonNode p, String baseSha, String sha)
      throws Exception {
    if (gitAt("diff", "--name-only", baseSha, sha, "--", "agentfarm.yaml").isBlank())
      return obj("status", "not_applicable");
    return io.agentfarm.core.ProfileCheck.validate(
        gitAt("show", sha + ":agentfarm.yaml"),
        task.repo,
        p.path("unit_test").asText(),
        p.path("integration_test").asText(),
        gitAt("ls-tree", "-d", "--name-only", sha).lines().toList());
  }

  static void research(JsonNode item, JsonNode payload, int issue) throws Exception {
    task.phase = "researching";
    task.active = AGENT;
    workspace(item, issue, false);
    var memory = currentProjectMemory();
    var profile = baseProfile();
    if (profile != null) {
      profile.requireSupportedExecution();
      if (!profile.repository().equals(task.repo))
        throw new IOException("Profile repository mismatch");
    }
    var policy =
        profile == null
            ? io.agentfarm.core.TestSandbox.Policy.defaults(SANDBOX_IMAGE)
            : profile.sandbox(SANDBOX_IMAGE);
    useMcp(JSON.valueToTree(profile == null ? List.of() : profile.mcp()));
    var rendered =
        io.agentfarm.core.Prompts.render(
            "research",
            Map.of(
                "repo",
                task.repo,
                "sandbox",
                policy == null ? "host del ejecutor" : "imagen " + policy.image(),
                "request",
                item.path("request").asText() + io.agentfarm.core.ExecutorWork.recipeContext(item),
                "feedback",
                payload.path("feedback").asText()));
    var skills = skills("research", profile == null ? List.of() : profile.skills());
    String prompt =
        rendered.text() + io.agentfarm.core.ExecutorWork.adoptionContract(item) + skills.text();
    if (profile != null)
      prompt +=
          "\nProject profile (commands and scope are binding): "
              + JSON.writeValueAsString(profile.summary());
    prompt += io.agentfarm.core.ExecutorWork.memoryContext(memory);
    JsonNode p = io.agentfarm.core.ExecutorWork.resultJson(agentTurn("research", prompt));
    io.agentfarm.core.ProposalShape.normalize(p);
    io.agentfarm.core.ExecutorWork.completeProposal(
        p,
        JSON.valueToTree(rendered.reference()),
        providerFor("research"),
        JSON.valueToTree(skills.references()),
        memory,
        profile,
        JSON.valueToTree(policy == null ? Map.of("mode", "host") : policy.summary()),
        gitAt("rev-parse", "HEAD"));
    github(
        "PATCH",
        "repos/" + task.repo + "/issues/" + issue,
        obj("body", io.agentfarm.core.ExecutorWork.issueBody(marker(), item, p)));
    report("proposed", "issue", issue, "proposal", p);
  }

  /** What a Codex thread may touch: see docs/INSTALLATION.md for what this does not cover. */
  static Map<String, Object> agentProfile(Path workspace, boolean write) throws Exception {
    return io.agentfarm.core.AgentSandbox.codexConfig(
        io.agentfarm.core.AgentSandbox.filesystem(
            Path.of(System.getProperty("user.home")).toRealPath(),
            STATE.toRealPath(),
            Path.of("/mnt"),
            workspace.toRealPath(),
            write,
            io.agentfarm.core.AgentSandbox.hostSockets(System.getenv(), hostUser().split(":")[0])));
  }

  /**
   * Rules, not a sandbox: the owner should know. tests/claude_rules.sh checks them against a real
   * account.
   */
  static final java.util.function.Supplier<String> CLAUDE_NOTICE =
      () ->
          List.of("research", "implement", "review").stream()
                  .anyMatch(r -> providerFor(r).equals("claude-code"))
              ? "Claude Code solo tiene reglas de bloqueo, sin aislamiento del sistema."
              : "";

  static volatile String sandboxFailure = "";
  static long sandboxCheckedAt;

  /** Checked before claiming: no task starts unless the real Codex hides what the profile says. */
  static boolean sandboxHolds() throws Exception {
    if (!providerFor("research").equals("codex")
        && !providerFor("implement").equals("codex")
        && !providerFor("review").equals("codex")) return true;
    long interval = sandboxFailure.isEmpty() ? 600_000 : 30_000;
    if (sandboxCheckedAt != 0 && System.currentTimeMillis() - sandboxCheckedAt < interval)
      return sandboxFailure.isEmpty();
    Path probe = Files.createDirectories(WORK.resolve(".sandbox-probe"));
    var problem =
        io.agentfarm.core.SandboxProbe.check(
            io.agentfarm.core.SandboxProbe.command(
                System.getProperty("agentfarm.codex", "codex"), agentProfile(probe, true), probe),
            probe,
            30);
    sandboxCheckedAt = System.currentTimeMillis();
    String found = problem.map(FarmDaemon::clean).orElse("");
    if (!found.isEmpty() && !found.equals(sandboxFailure))
      System.err.println("Sandbox: no se acepta trabajo: " + found);
    sandboxFailure = found;
    return found.isEmpty();
  }

  static Map<String, Object> threadConfig(String role, boolean withMcp) throws Exception {
    var config = new LinkedHashMap<>(agentProfile(task.checkout, role.equals("implement")));
    config.put("web_search", io.agentfarm.core.AgentSandbox.webSearch(role));
    if (withMcp)
      config.put(
          "mcp_servers",
          io.agentfarm.core.McpCatalog.codexConfig(task.mcpServers, System.getenv()));
    return config;
  }

  /** Off only to compare results with and without skills; see docs/SKILLS.md. */
  static final boolean SKILLS = !"off".equals(System.getProperty("agentfarm.skills"));

  static io.agentfarm.core.SkillCatalog.Selection skills(String role, List<String> named)
      throws Exception {
    if (!SKILLS) return new io.agentfarm.core.SkillCatalog.Selection("", List.of());
    return io.agentfarm.core.SkillCatalog.select(
        role,
        io.agentfarm.core.SkillCatalog.detectStacks(task.checkout),
        named,
        STATE.resolve("skills"));
  }

  static List<String> namedSkills(JsonNode p) {
    var names = new ArrayList<String>();
    p.path("named_skills").forEach(n -> names.add(n.asText()));
    return names;
  }

  /** True when the commit has CI workflows besides the AgentFarm template. */
  static boolean ownWorkflows(String ref) throws Exception {
    return gitAt("ls-tree", "--name-only", ref, ".github/workflows/")
        .lines()
        .anyMatch(f -> !f.isBlank() && !f.equals(".github/workflows/agentfarm.yml"));
  }

  /** Checks bound to the evidence: the project's own CI ("*") or the AgentFarm template jobs. */
  static List<String> ciChecks(String sha) throws Exception {
    return io.agentfarm.core.ExecutorWork.ciChecks(ownWorkflows(sha));
  }

  static io.agentfarm.core.TestSandbox.Output sandboxProcess(
      List<String> argv, java.time.Duration timeout) throws Exception {
    Path log = Files.createTempFile(STATE, "sandbox-", ".log");
    var b = new ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(log.toFile());
    for (String key :
        List.of("OPENAI_API_KEY", "CODEX_API_KEY", "ANTHROPIC_API_KEY", "GH_TOKEN", "GITHUB_TOKEN"))
      b.environment().remove(key);
    var p = b.start();
    try {
      long deadline = System.currentTimeMillis() + timeout.toMillis();
      while (!p.waitFor(1, TimeUnit.SECONDS)) {
        if (cancelled() || System.currentTimeMillis() > deadline) {
          p.descendants().forEach(ProcessHandle::destroyForcibly);
          p.destroyForcibly();
          throw new IOException(
              cancelled() ? "Operación cancelada" : "Tiempo agotado: " + argv.get(0));
        }
      }
      String output = Files.readString(log);
      if (output.length() > 150000) output = output.substring(output.length() - 150000);
      return new io.agentfarm.core.TestSandbox.Output(p.exitValue(), clean(output.trim()));
    } finally {
      Files.deleteIfExists(log);
    }
  }

  static String hostUser() throws Exception {
    var attrs =
        Files.readAttributes(STATE, "unix:uid,gid", java.nio.file.LinkOption.NOFOLLOW_LINKS);
    return attrs.get("uid") + ":" + attrs.get("gid");
  }

  /** Runs setup and both test commands for an approved proposal at {@code sha}. */
  static List<Map<String, Object>> runTests(JsonNode p, String sha) throws Exception {
    var tests = new LinkedHashMap<String, String>();
    tests.put("unit", p.path("unit_test").asText());
    tests.put("integration", p.path("integration_test").asText());
    return runCommands(p, sha, tests);
  }

  static boolean onHost(JsonNode p) {
    return p.path("sandbox").path("mode").asText("container").equals("host");
  }

  /**
   * Project-declared gates. On the host commands see the task.checkout, so base can't be measured.
   */
  static Map<String, Object> runQuality(JsonNode p, String baseSha, String sha) throws Exception {
    var checks = new LinkedHashMap<String, String>();
    p.path("quality")
        .path("checks")
        .properties()
        .forEach(c -> checks.put(c.getKey(), c.getValue().asText()));
    var metrics = new LinkedHashMap<String, String>();
    p.path("quality")
        .path("metrics")
        .properties()
        .forEach(m -> metrics.put(m.getKey(), m.getValue().path("command").asText()));
    var measured = new ArrayList<Map<String, Object>>();
    if (!metrics.isEmpty() && !onHost(p)) {
      var base = runCommands(p, baseSha, metrics);
      var candidate = runCommands(p, sha, metrics);
      int i = 0;
      for (String name : metrics.keySet()) {
        measured.add(
            io.agentfarm.core.QualityGate.compare(
                name,
                p.path("quality").path("metrics").path(name).path("better").asText("higher"),
                String.valueOf(base.get(i).get("output")),
                String.valueOf(candidate.get(i).get("output"))));
        i++;
      }
    }
    return obj(
        "checks", checks.isEmpty() ? List.of() : runCommands(p, sha, checks), "metrics", measured);
  }

  /** A bug fix must bring a test that fails without it: the new tests run against the base. */
  static Map<String, Object> proveRegression(
      JsonNode item,
      JsonNode p,
      String baseSha,
      String sha,
      Map<String, Object> unit,
      Map<String, Object> integration)
      throws Exception {
    if (!item.path("recipe").path("id").asText().equals("bugfix"))
      return obj("status", "not_required");
    if (onHost(p))
      return obj(
          "status",
          "not_evaluated",
          "reason",
          "Las pruebas corren en el host: no se puede aislar la base.");
    if (!"passed".equals(unit.get("status")) || !"passed".equals(integration.get("status")))
      return obj("status", "not_evaluated", "reason", "Las pruebas del candidato no pasan.");
    var tests =
        gitAt("diff", "--diff-filter=d", "--name-only", baseSha, sha)
            .lines()
            .filter(io.agentfarm.core.TestPolicy::isTest)
            .toList();
    if (tests.isEmpty())
      return obj(
          "status", "not_proved", "reason", "El cambio no añade ni modifica ninguna prueba.");
    Path tree = Files.createTempDirectory(STATE, "regression-");
    String probe;
    try {
      gitAt("worktree", "add", "--force", "--detach", tree.toString(), baseSha);
      var checkoutTests =
          new ArrayList<>(List.of("git", "-C", tree.toString(), "checkout", sha, "--"));
      checkoutTests.addAll(tests);
      process(STATE, 120, checkoutTests.toArray(String[]::new));
      process(
          STATE,
          120,
          "git",
          "-C",
          tree.toString(),
          "-c",
          "user.name=AgentFarm",
          "-c",
          "user.email=agentfarm@localhost",
          "commit",
          "-q",
          "-m",
          "probe");
      probe = process(STATE, 30, "git", "-C", tree.toString(), "rev-parse", "HEAD").trim();
    } finally {
      gitAt("worktree", "remove", "--force", tree.toString());
    }
    event("command", "Comprobando que las pruebas nuevas fallan sin el cambio");
    var results = runTests(p, probe);
    boolean failed =
        results.stream()
            .anyMatch(r ->
                "failed".equals(r.get("status"))
                    && !String.valueOf(r.get("output")).startsWith("setup failed"));
    return failed
        ? obj(
            "status",
            "proved",
            "reason",
            "Las pruebas nuevas fallan sobre la base y pasan con el cambio.",
            "tests",
            tests)
        : obj(
            "status",
            "not_proved",
            "reason",
            "Las pruebas nuevas ya pasaban sin el cambio.",
            "tests",
            tests);
  }

  static List<Map<String, Object>> runCommands(JsonNode p, String sha, Map<String, String> tests)
      throws Exception {
    var declared = p.path("sandbox");
    if (declared.path("mode").asText("container").equals("host")) {
      if (!SANDBOX.equals("host"))
        throw new IOException(
            "El perfil pide ejecutar tests en el host; el operador debe permitirlo con"
                + " -Dagentfarm.sandbox=host");
      var results = new ArrayList<Map<String, Object>>();
      for (var t : tests.entrySet()) results.add(test(t.getKey(), t.getValue()));
      return results;
    }
    // Proposals approved before sandboxing carry no policy; they get the executor defaults.
    var policy =
        declared.has("image")
            ? io.agentfarm.core.TestSandbox.Policy.fromSummary(declared)
            : io.agentfarm.core.TestSandbox.Policy.defaults(SANDBOX_IMAGE);
    event("command", "Sandbox " + policy.image() + " · red " + policy.network());
    var results =
        new io.agentfarm.core.TestSandbox(FarmDaemon::sandboxProcess, DOCKER, hostUser())
            .run(task.checkout, sha, policy, p.path("setup").asText(""), tests);
    for (var r : results) {
      event("command", r.get("kind") + ": " + Objects.toString(r.get("command"), ""));
      event(
          "passed".equals(r.get("status")) ? "output" : "error",
          Objects.toString(r.get("output"), Objects.toString(r.get("reason"), "")));
      r.put("sandbox", policy.summary());
    }
    return results;
  }

  static Map<String, Object> test(String name, String command) throws Exception {
    if (command.startsWith("N/A:") && command.length() > 12)
      return obj("kind", name, "status", "not_applicable", "reason", command);
    event("command", name + ": " + command);
    try {
      String out = process(task.checkout, 600, "bash", "-lc", command);
      event("output", out);
      return obj("kind", name, "status", "passed", "command", command, "output", clean(out));
    } catch (Exception e) {
      event("error", e.getMessage());
      return obj(
          "kind", name, "status", "failed", "command", command, "output", clean(e.getMessage()));
    }
  }

  static void implement(JsonNode item, int issue) throws Exception {
    task.phase = "implementing";
    task.active = IMPLEMENTER;
    workspace(item, issue, true);
    var p = JSON.readTree(item.path("proposal").asText());
    String baseSha = gitAt("rev-parse", "HEAD");
    io.agentfarm.core.ProjectMemory.requireUnchanged(
        p.get("project_memory"), currentProjectMemory());
    if (p.has("profile_hash")) {
      var profile = io.agentfarm.core.ProjectProfile.load(task.checkout.resolve("agentfarm.yaml"));
      if (!profile.hash().equals(p.path("profile_hash").asText()))
        throw new IOException("Project profile changed; approve a new proposal");
    }
    Path receipt = STATE.resolve("receipts" + SUFFIX).resolve(task.itemId + ".json");
    if (Files.exists(receipt)) {
      var saved = JSON.readTree(Files.readString(receipt));
      if (io.agentfarm.core.ExecutorWork.receiptAtHead(saved, item, baseSha)
          && saved.path("base_sha").asText().equals(p.path("base_sha").asText())) {
        validateAndPublish(
            item, issue, p, saved.path("change"), saved.path("base_sha").asText(), baseSha);
        return;
      }
    }
    if (p.has("base_sha") && !p.path("base_sha").asText().equals(baseSha))
      throw new IOException(
          "La base cambió desde la investigación; solicita actualizar la propuesta");
    boolean ownCi = ownWorkflows(baseSha);
    var implementSkills = skills("implement", namedSkills(p));
    String result =
        agentTurn(
            "implement",
            (task.implementPrompt =
                        io.agentfarm.core.Prompts.render(
                            "implement",
                            Map.of(
                                "ci",
                                ownCi
                                    ? "El repositorio tiene CI propio: no añadas workflows nuevos."
                                    : "AgentFarm añadirá un workflow con los comandos aprobados.",
                                "proposal",
                                p.toString(),
                                "request",
                                item.path("request").asText()
                                    + io.agentfarm.core.ExecutorWork.recipeContext(item))))
                    .text()
                + io.agentfarm.core.ExecutorWork.adoptionContract(item)
                + implementSkills.text());
    var change = io.agentfarm.core.ExecutorWork.resultJson(result);
    ((com.fasterxml.jackson.databind.node.ObjectNode) change)
        .set("prompt", JSON.valueToTree(task.implementPrompt.reference()));
    ((com.fasterxml.jackson.databind.node.ObjectNode) change)
        .set("skills", JSON.valueToTree(implementSkills.references()));
    var workflow = io.agentfarm.core.ExecutorWork.verificationWorkflow(p, SANDBOX_IMAGE);
    // Respect the project's own CI: only repositories without workflows get the template.
    if (!ownCi) {
      Files.createDirectories(task.checkout.resolve(".github/workflows"));
      Files.writeString(
          task.checkout.resolve(".github/workflows/agentfarm.yml"),
          JSON.writerWithDefaultPrettyPrinter().writeValueAsString(workflow));
    }
    gitAt("add", "-A");
    if (!gitAt("diff", "--cached", "--name-only").isBlank())
      gitAt(
          "-c",
          "user.name=AgentFarm",
          "-c",
          "user.email=agentfarm@localhost",
          "commit",
          "-m",
          "[#" + issue + "] " + item.path("title").asText());
    String sha = gitAt("rev-parse", "HEAD");
    if (sha.equals(baseSha)) throw new IOException("No hay cambios de desarrollo");
    Files.createDirectories(receipt.getParent());
    Files.writeString(
        receipt,
        JSON.writeValueAsString(
            obj(
                "version",
                item.path("version").asInt(),
                "base_sha",
                baseSha,
                "sha",
                sha,
                "change",
                change)));
    report("checkpoint", "sha", sha);
    validateAndPublish(item, issue, p, change, baseSha, sha);
  }

  static void validateAndPublish(
      JsonNode item, int issue, JsonNode p, JsonNode change, String baseSha, String sha)
      throws Exception {
    useMcp(p.path("mcp"));
    var previous = JSON.readTree(item.path("evidence").asText("{}"));
    if (previous.path("sha").asText().equals(sha) && previous.has("review")) {
      publish(item, issue, p, previous, sha, previous);
      return;
    }
    task.phase = "testing";
    var results = runTests(p, sha);
    var unit = results.get(0);
    var integration = results.get(1);
    if (!gitAt("status", "--porcelain").isBlank())
      throw new IOException("Las pruebas han cambiado el checkout; revisar antes de publicar");
    // Before the review, so the reviewer judges what the platform checked rather than asking for
    // it.
    var profileValidation = candidateProfile(p, baseSha, sha);
    var quality =
        runQuality(
            io.agentfarm.core.ExecutorWork.withCandidateGates(p, profileValidation), baseSha, sha);
    var regression = proveRegression(item, p, baseSha, sha, unit, integration);
    task.active = REVIEWER;
    task.phase = "reviewing";
    var reviewSkills = skills("review", namedSkills(p));
    JsonNode review =
        io.agentfarm.core.ExecutorWork.resultJson(
            agentTurn(
                "review",
                (task.reviewPrompt =
                            io.agentfarm.core.Prompts.render(
                                "review",
                                Map.of(
                                    "base",
                                    baseSha,
                                    "sha",
                                    sha,
                                    "proposal",
                                    p.toString(),
                                    "platform",
                                    JSON.writeValueAsString(
                                        obj(
                                            "profile_validation",
                                            profileValidation,
                                            "quality",
                                            quality,
                                            "regression",
                                            regression)),
                                    "tests",
                                    unit + " " + integration)))
                        .text()
                    + reviewSkills.text()));
    // Both sides of a rename: moving a file out of a directory is a change there too.
    var changed =
        io.agentfarm.core.ChangePolicy.paths(
            io.agentfarm.core.ScopeService.changes(task.checkout, baseSha, sha));
    boolean untested =
        io.agentfarm.core.TestPolicy.untestedCode(
            changed, String.valueOf(unit.get("status")), String.valueOf(integration.get("status")));
    var workflowChanges =
        gitAt("diff", "--name-only", baseSha, sha, "--", ".github/workflows/")
            .lines()
            .filter(f -> !f.isBlank() && !f.equals(".github/workflows/agentfarm.yml"))
            .toList();
    var protectedFiles =
        io.agentfarm.core.Commandments.protectedChanges(
            changed.stream().filter(f -> f.equals("agentfarm.yaml")).toList(),
            gitAt("diff", "--diff-filter=A", "--name-only", baseSha, sha).lines().toList(),
            item.path("recipe").path("id").asText());
    var verdict =
        new io.agentfarm.core.ExecutorWork.Candidate(
                String.valueOf(unit.get("status")),
                String.valueOf(integration.get("status")),
                review.path("passed").asBoolean(false),
                untested,
                Files.isDirectory(task.checkout.resolve(".github/workflows")),
                ownWorkflows(baseSha) ? workflowChanges : List.of(),
                protectedFiles,
                profileValidation,
                quality)
            .verdict();
    verdict.problems().forEach(problem -> event("error", problem));
    boolean pass = verdict.passed();
    if (!gitAt("rev-parse", "HEAD").equals(sha) || !gitAt("status", "--porcelain").isBlank())
      throw new IOException("El candidato cambió durante la revisión");
    var evidence =
        obj(
            "base_sha",
            baseSha,
            "sha",
            sha,
            "summary",
            change.path("summary").asText(),
            "as_is",
            change.path("as_is").asText(),
            "to_be",
            change.path("to_be").asText(),
            "limitations",
            change.path("limitations").asText(),
            "providers",
            obj("implement", providerFor("implement"), "review", providerFor("review")),
            "prompts",
            obj("implement", change.path("prompt"), "review", task.reviewPrompt.reference()),
            "unit",
            unit,
            "integration",
            integration,
            "review",
            review,
            "workflow_changes",
            workflowChanges,
            "profile_validation",
            profileValidation,
            "untested_code",
            untested,
            "quality",
            quality,
            "regression",
            regression,
            "skills",
            obj(
                "enabled",
                SKILLS,
                "research",
                p.path("skills"),
                "implement",
                change.path("skills"),
                "review",
                reviewSkills.references()),
            "ci_checks",
            p.has("ci_checks") ? p.path("ci_checks") : ciChecks(sha),
            "passed",
            pass);
    report("checkpoint", "sha", sha, "evidence", evidence);
    publish(item, issue, p, change, sha, JSON.valueToTree(evidence));
  }

  static void publish(
      JsonNode item, int issue, JsonNode p, JsonNode change, String sha, JsonNode evidence)
      throws Exception {
    gitAt("fetch", "origin");
    io.agentfarm.core.ProjectMemory.requireUnchanged(
        p.get("project_memory"), currentProjectMemory());
    var memoryValidation = candidateMemoryValidation(sha);
    var checked = (com.fasterxml.jackson.databind.node.ObjectNode) evidence.deepCopy();
    checked.set("project_memory_validation", JSON.valueToTree(memoryValidation));
    boolean pass =
        evidence.path("passed").asBoolean() && memoryValidation.get("status").equals("passed");
    checked.put("passed", pass);
    evidence = checked;
    var unit = evidence.path("unit");
    var integration = evidence.path("integration");
    var review = evidence.path("review");
    if (p.has("profile_hash")) {
      var profile = io.agentfarm.core.ProjectProfile.load(task.checkout.resolve("agentfarm.yaml"));
      if (!profile.hash().equals(p.path("profile_hash").asText()))
        throw new IOException("Project profile changed during execution");
      var violations =
          io.agentfarm.core.ExecutorWork.scopeViolations(
              profile,
              io.agentfarm.core.ScopeService.changes(
                  task.checkout, p.path("base_sha").asText(), sha),
              io.agentfarm.core.ScopeService.tree(task.checkout, sha));
      if (!violations.isEmpty())
        throw new IOException(
            "Out of approved profile scope: "
                + io.agentfarm.core.ChangePolicy.describe(violations));
    }
    // Once pushed a secret is exposed for good, so this check comes before the panel's own.
    var secrets =
        io.agentfarm.core.SecretScan.findings("", gitAt("diff", p.path("base_sha").asText(), sha));
    if (!secrets.isEmpty())
      throw new IOException("Posible secreto en el candidato: " + String.join(", ", secrets));
    String branch = gitAt("branch", "--show-current");
    guard();
    // A corrected candidate may restart from the base, so its branch is replaced, never merged
    // over.
    gitAt("push", "-u", "--force-with-lease", "origin", branch);
    String body =
        io.agentfarm.core.ExecutorWork.pullRequestBody(
            item,
            issue,
            p,
            change,
            sha,
            unit,
            integration,
            review,
            JSON.valueToTree(memoryValidation));
    var prs =
        github(
            "GET",
            "repos/"
                + task.repo
                + "/pulls?state=all&head="
                + task.repo.split("/")[0]
                + ":"
                + branch,
            null);
    int pr;
    if (prs.isEmpty())
      pr =
          github(
                  "POST",
                  "repos/" + task.repo + "/pulls",
                  obj(
                      "title",
                      "[#" + issue + "] " + item.path("title").asText(),
                      "body",
                      body,
                      "head",
                      branch,
                      "base",
                      github("GET", "repos/" + task.repo, null).path("default_branch").asText(),
                      "draft",
                      !pass))
              .path("number")
              .asInt();
    else {
      pr = prs.get(0).path("number").asInt();
      github("PATCH", "repos/" + task.repo + "/pulls/" + pr, obj("body", body));
      if (pass && prs.get(0).path("draft").asBoolean())
        process(STATE, 30, GH, "pr", "ready", Integer.toString(pr), "--repo", task.repo);
      if (!pass && !prs.get(0).path("draft").asBoolean())
        process(STATE, 30, GH, "pr", "ready", Integer.toString(pr), "--repo", task.repo, "--undo");
    }
    github(
        "PATCH",
        "repos/" + task.repo + "/issues/" + issue,
        obj(
            "body",
            io.agentfarm.core.ExecutorWork.issueBody(marker(), item, p)
                + "\n## Resultado\nRama: "
                + branch
                + "\nPR: https://github.com/"
                + task.repo
                + "/pull/"
                + pr
                + "\nCommit: "
                + sha
                + "\nEstado: pendiente de integración humana.\n"
                + change.path("summary").asText()));
    report("review", "issue", issue, "pr", pr, "branch", branch, "sha", sha, "evidence", evidence);
  }

  static void reviewExisting(JsonNode item, int issue) throws Exception {
    task.checkout = WORK.resolve(task.itemId);
    if (!Files.exists(task.checkout.resolve(".git")))
      process(STATE, 120, GH, "repo", "clone", task.repo, task.checkout.toString());
    Files.writeString(
        task.checkout.resolve(".git/info/exclude"),
        "\n__pycache__/\n*.pyc\n",
        StandardOpenOption.APPEND);
    if (!gitAt("branch", "--show-current").equals(item.path("branch").asText())
        || !gitAt("rev-parse", "HEAD").equals(item.path("sha").asText())
        || !gitAt("status", "--porcelain").isBlank())
      throw new IOException("El candidato importado no coincide con el checkout limpio");
    var p = JSON.readTree(item.path("proposal").asText());
    gitAt("fetch", "origin");
    io.agentfarm.core.ProjectMemory.requireUnchanged(
        p.get("project_memory"), currentProjectMemory());
    var change = p.deepCopy();
    if (change instanceof com.fasterxml.jackson.databind.node.ObjectNode object)
      object.put(
          "summary",
          "Candidato importado "
              + item.path("sha").asText()
              + ". Validación del alcance aprobado: "
              + p.path("acceptance").asText());
    validateAndPublish(
        item, issue, p, change, p.path("base_sha").asText(), item.path("sha").asText());
  }

  static synchronized void reconcileReservation() throws Exception {
    if (!Files.exists(JOURNAL)) return;
    var journal = JSON.readTree(Files.readString(JOURNAL));
    if (System.currentTimeMillis() - quotaAt > 60000
        || quota.path("resets_at").asLong() != journal.path("reset").asLong())
      throw new IOException("Reserva pendiente: falta cuota de su ventana para conciliar");
    boolean exists = false;
    for (var r : get("/budget/" + ACCOUNT + "/reservations"))
      if (r.path("run_id").asText().equals(journal.path("reservation").asText())) exists = true;
    if (!exists) {
      Files.delete(JOURNAL);
      return;
    }
    post(
        "/budget/settle-observed",
        obj("account", ACCOUNT, "run_id", journal.path("reservation").asText()));
    Files.delete(JOURNAL);
  }

  static void work(JsonNode claim) throws Exception {
    task = new Task(claim);
    var item = claim.path("item");
    var cmd = claim.path("command");
    String kind = cmd.path("kind").asText();
    boolean reserved = false;
    int initial = 0;
    String reservation = "work-" + task.commandId;
    try {
      githubAccess();
      int issue = ensureIssue(item);
      report("checkpoint", "issue", issue);
      refreshQuota();
      reconcileReservation();
      guard();
      initial = (int) Math.ceil(quota.path("weekly_used_percent").asDouble() * 100);
      var balance = get("/budget/" + ACCOUNT);
      int share =
          io.agentfarm.core.ExecutorWork.reservationShare(
              balance, Integer.getInteger("agentfarm.slots", 1));
      Files.writeString(
          JOURNAL,
          JSON.writeValueAsString(
              obj(
                  "reservation",
                  reservation,
                  "initial",
                  initial,
                  "reset",
                  quota.path("resets_at").asLong(),
                  "command",
                  task.commandId)));
      var r =
          post(
              "/budget/reserve",
              obj("account", ACCOUNT, "run_id", reservation, "amounts", obj("weekly", share)));
      if (!r.path("state").asText().equals("reserved"))
        throw new IOException("Clover rechazó la reserva: " + r);
      reserved = true;
      task.reservedReset = quota.path("resets_at").asLong();
      var payload = JSON.readTree(cmd.path("payload").asText());
      switch (kind) {
        case "research" -> research(item, payload, issue);
        case "implement" -> implement(item, issue);
        case "review_existing" -> reviewExisting(item, issue);
        default -> throw new IOException("Unknown command");
      }
      if (reserved) {
        refreshQuota();
        reconcileReservation();
      }
    } catch (Exception e) {
      // Kill the process tree before releasing a failed run's reservation or accepting another
      // order.
      if (server != null) {
        server.descendants().forEach(ProcessHandle::destroyForcibly);
        server.destroyForcibly();
        server.waitFor(10, TimeUnit.SECONDS);
      }
      try {
        startServer();
        refreshQuota();
        reconcileReservation();
      } catch (Exception reconcile) {
        failure = "Conciliación pendiente: " + reconcile.getMessage();
      }
      event("error", e.getMessage());
      try {
        report("blocked", "error", clean(e.getMessage()));
      } catch (Exception ignored) {
      }
    } finally {
      task = null;
    }
  }

  static synchronized void telemetry() throws Exception {
    var running = task;
    if (running != null)
      try {
        if (report("heartbeat").path("paused").asBoolean()) running.cancelled = true;
      } catch (Exception e) {
        running.cancelled = true;
      }
    if (cancelled() && turn != null)
      try {
        rpc("turn/interrupt", obj("threadId", thread, "turnId", turn));
      } catch (Exception ignored) {
      }
    var snapshot =
        obj(
            "node",
            AGENT,
            "executor",
            CLAIMANT,
            "provider",
            running == null ? "codex" : running.provider,
            "account",
            ACCOUNT,
            "host",
            System.getenv("WSL_DISTRO_NAME") != null
                ? "WSL · " + System.getenv("WSL_DISTRO_NAME")
                : System.getProperty("os.name"),
            "distro",
            System.getProperty("os.name") + " " + System.getProperty("os.version"),
            "observed_at",
            Instant.now().toString(),
            "source",
            "Servicio WSL y protocolo Codex",
            "run_state",
            running == null ? "idle" : running.phase,
            "phase",
            running == null ? "idle" : running.phase,
            "build",
            io.agentfarm.core.BuildInfo.commit(),
            "sandbox_failure",
            sandboxFailure,
            "isolation_notice",
            CLAUDE_NOTICE.get(),
            "supervisor_pid",
            ProcessHandle.current().pid(),
            "executor_available",
            true,
            "quota",
            quota,
            "active_worker",
            running == null ? "" : running.active,
            "session",
            session,
            "model",
            model,
            "repository",
            running == null ? "" : running.repo,
            "github_error",
            failure);
    if (turn != null && server != null && server.isAlive()) snapshot.put("codex_pid", server.pid());
    if (running != null) {
      snapshot.put("task_id", running.itemId);
      snapshot.put("task_title", running.claim.path("item").path("title").asText());
    }
    var queued = new ArrayList<>(activity);
    var batch = new ArrayList<>(queued.subList(0, Math.min(30, queued.size())));
    post("/runtime/observations", obj("snapshot", snapshot, "events", batch));
    activity.removeAll(batch);
    post("/heartbeat/node/" + AGENT, obj("state", running == null ? "idle" : "busy"));
    for (String w : List.of(IMPLEMENTER, REVIEWER))
      post(
          "/heartbeat/worker/" + w,
          obj("state", running != null && running.active.equals(w) ? "busy" : "idle"));
  }

  static ScheduledExecutorService observationLoops(
      Runnable quotaRead, Runnable heartbeat, long quotaDelay, long heartbeatDelay) {
    // Daemon threads: if the main loop dies, the process must end so that systemd restarts it.
    var scheduler =
        Executors.newScheduledThreadPool(
            2,
            r -> {
              var thread = new Thread(r);
              thread.setDaemon(true);
              return thread;
            });
    scheduler.scheduleWithFixedDelay(quotaRead, 0, quotaDelay, TimeUnit.MILLISECONDS);
    scheduler.scheduleWithFixedDelay(heartbeat, 0, heartbeatDelay, TimeUnit.MILLISECONDS);
    return scheduler;
  }

  static volatile int telemetryFailures;

  /** A stopped panel is normal: wait for it here rather than exit and be restarted by systemd. */
  static JsonNode waitForPanel() throws Exception {
    for (int attempt = 0; ; attempt++) {
      try {
        return get("/csrf");
      } catch (IOException e) {
        // An answer such as 401 will not improve by waiting: a bad token must still fail visibly.
        if (String.valueOf(e.getMessage()).startsWith("/csrf HTTP")) throw e;
        if (attempt == 0) System.err.println("Waiting for the panel: " + clean(e.getMessage()));
        Thread.sleep(io.agentfarm.core.Backoff.millis(attempt));
      }
    }
  }

  public static void main(String[] args) throws Exception {
    if (!SLOT.isBlank() && !SLOT.matches("[1-9][0-9]?"))
      throw new IllegalArgumentException("agentfarm.slot must be 1-99");
    Files.createDirectories(STATE);
    try (var ch =
            java.nio.channels.FileChannel.open(
                STATE.resolve("runner" + SUFFIX + ".lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        var lock = ch.tryLock()) {
      if (lock == null) throw new IOException("Another AgentFarm executor is running");
      auth = "Bearer " + Files.readString(STATE.resolve("node-token")).trim();
      var token = waitForPanel();
      csrfHeader = token.path("header").asText();
      csrf = token.path("token").asText();
      startServer();
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(() -> {
                if (server != null) {
                  server.descendants().forEach(ProcessHandle::destroy);
                  server.destroy();
                }
              }));
      var scheduler =
          observationLoops(
              () -> {
                try {
                  if (System.currentTimeMillis() - lastQuota > (task == null ? 30000 : 5000))
                    refreshQuota();
                } catch (Exception e) {
                  lastQuota = System.currentTimeMillis();
                  failure = e.getClass().getSimpleName() + ": " + clean(e.getMessage());
                }
              },
              () -> {
                try {
                  telemetry();
                  telemetryFailures = 0;
                } catch (Exception e) {
                  if (telemetryFailures++ == 0)
                    System.err.println("Panel disconnected: " + e.getClass().getSimpleName());
                }
              },
              1000,
              2000);
      // Publish a quota reading (which opens the Clover window) and a snapshot before the
      // first claim; otherwise the first task fails admission on a fresh account.
      for (int attempt = 0; attempt < 30 && quotaAt == 0; attempt++) {
        try {
          refreshQuota();
          telemetry();
        } catch (Exception e) {
          failure = "Arranque: " + clean(e.getMessage());
          Thread.sleep(2000);
        }
      }
      int failures = 0;
      while (true) {
        long wait = 2000;
        try {
          if (sandboxHolds()) {
            var claim = post("/work/agent/claim", obj("agent", CLAIMANT));
            if (claim.has("command")) work(claim);
          }
          failures = 0;
        } catch (Exception e) {
          if (failures == 0) System.err.println("Dispatcher: " + clean(e.getMessage()));
          wait = io.agentfarm.core.Backoff.millis(failures++);
        }
        Thread.sleep(wait);
      }
    }
  }
}
