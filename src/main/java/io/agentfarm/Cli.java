package io.agentfarm;

import static io.agentfarm.core.Json.*;

import io.agentfarm.core.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

public final class Cli {
  private Cli() {}

  public static int run(String[] args) {
    try {
      String mode = args[0];
      Map<String, String> options = new HashMap<>();
      List<String> positional = new ArrayList<>();
      for (int i = 1; i < args.length; i++) {
        if (args[i].startsWith("--")) {
          if (i + 1 >= args.length) throw new IllegalArgumentException("Missing option value");
          options.put(args[i].substring(2), args[++i]);
        } else positional.add(args[i]);
      }
      Object result;
      if (mode.equals("doctor")) {
        result =
            Diagnostics.run(
                Path.of(options.getOrDefault("repo", ".")),
                options.getOrDefault("panel", "http://localhost:8080"),
                Boolean.parseBoolean(options.getOrDefault("run-tests", "false")));
      } else if (mode.equals("sandbox-profile")) {
        result =
            AgentSandbox.codexConfig(
                AgentSandbox.filesystem(
                    Path.of(System.getProperty("user.home")).toRealPath(),
                    Path.of(required(options, "state")).toRealPath(),
                    Path.of("/mnt"),
                    Path.of(required(options, "checkout")).toRealPath(),
                    Boolean.parseBoolean(options.getOrDefault("write", "false")),
                    AgentSandbox.hostSockets(System.getenv(), required(options, "uid"))));
      } else if (mode.equals("2fa-reset")) {
        var store = new Store(env("AGENTFARM_DB"));
        result =
            obj(
                "removed",
                new TwoFactor(store, Clock.systemUTC()).reset(required(options, "user"), "host-operator"));
      } else if (mode.equals("github-check")) {
        var credentials =
            new GitHubCredentials(
                env("AGENTFARM_GITHUB_TOKEN_FILE"),
                env("AGENTFARM_GITHUB_APP_ID"),
                env("AGENTFARM_GITHUB_APP_KEY_FILE"),
                env("AGENTFARM_GITHUB_INSTALLATION_ID"),
                env("AGENTFARM_DB"));
        if (!credentials.configured())
          throw new IllegalArgumentException("El panel no tiene credencial de GitHub: ./agentfarm github-token");
        var check =
            GitHubAccess.check(credentials.kind(), required(options, "repo"), new GitHubReader(credentials)::get);
        result = check;
        if (check.get("state").equals("failed")) {
          System.out.println(encode(result));
          return 1;
        }
      } else if (mode.equals("profile")) {
        result = ProjectProfile.load(Path.of(required(options, "file"))).summary();
      } else if (mode.equals("memory")) {
        result =
            ProjectMemory.validateFile(
                Path.of(required(options, "file")), required(options, "repository"));
      } else if (mode.equals("backup")) {
        result =
            Backups.backup(Path.of(required(options, "db")), Path.of(required(options, "output")));
      } else if (mode.equals("restore")) {
        result =
            Backups.restore(
                Path.of(required(options, "snapshot")), Path.of(required(options, "db")));
      } else if (mode.equals("scope")) {
        result =
            new ScopeService()
                .check(
                    Path.of(required(options, "repo")),
                    parse(Files.readString(Path.of(required(options, "spec")))),
                    required(options, "base"),
                    required(options, "candidate"));
      } else {
        Store store =
            new Store(options.getOrDefault("db", mode.equals("budget") ? "budget.db" : "state.db"));
        Clock clock = Clock.systemUTC();
        if (mode.equals("import")) {
          if (positional.size() != 1)
            throw new IllegalArgumentException("Use import --db destination.db source.db");
          result =
              new LegacyImporter(store).importFile(Path.of(positional.getFirst()), "local-cli");
        } else {
          if (positional.isEmpty()) throw new IllegalArgumentException("Command required");
          String command = positional.getFirst();
          if (mode.equals("budget")) {
            if (command.equals("status")) {
              if (positional.size() != 2) throw new IllegalArgumentException("Account required");
              result = new BudgetService(store, clock).status(positional.get(1));
            } else {
              if (positional.size() != 2) throw new IllegalArgumentException("JSON file required");
              result =
                  new BudgetService(store, clock)
                      .execute(
                          command,
                          parse(Files.readString(Path.of(positional.get(1)))),
                          "local-cli");
            }
          } else {
            CoordinatorService service = new CoordinatorService(store, clock);
            result =
                switch (command) {
                  case "enqueue" -> {
                    if (positional.size() != 2)
                      throw new IllegalArgumentException("Task JSON file required");
                    yield service.enqueue(
                        parse(Files.readString(Path.of(positional.get(1)))), "local-cli");
                  }
                  case "simulate" ->
                      service.simulate(
                          Integer.parseInt(System.getenv().getOrDefault("ACP_MAX_PENDING", "2")),
                          Integer.parseInt(System.getenv().getOrDefault("ACP_MAX_DAILY_RUNS", "5")),
                          "local-cli");
                  case "status" -> service.tasks();
                  case "report" -> service.report();
                  default -> throw new IllegalArgumentException("Unknown coordinator command");
                };
          }
        }
      }
      System.out.println(encode(result));
      if (result instanceof Map<?, ?> map) {
        Object state = map.get("state");
        if (state != null
            && Set.of("paused", "unknown", "overrun", "crossed_reset", "duplicate", "blocked")
                .contains(state)) return 1;
      }
      return 0;
    } catch (Exception e) {
      System.out.println(
          encode(
              obj(
                  "state",
                  "error",
                  "error",
                  e.getMessage() == null ? "Operation failed" : e.getMessage())));
      return 2;
    }
  }

  private static String required(Map<String, String> options, String key) {
    String value = options.get(key);
    if (value == null) throw new IllegalArgumentException("Required option: --" + key);
    return value;
  }

  private static String env(String name) {
    return System.getenv().getOrDefault(name, "");
  }
}
