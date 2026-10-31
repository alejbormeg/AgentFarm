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
      if (mode.equals("scope")) {
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
        if (Set.of("paused", "unknown", "overrun", "crossed_reset", "duplicate", "blocked")
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
}
