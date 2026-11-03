package io.agentfarm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class AgentFarmApplication {
  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("user-hash")) {
      // Reads one password from stdin and prints the bcrypt hash for AGENTFARM_USERS_FILE.
      String password = new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
      if (password == null || password.length() < 12) {
        System.err.println("Password must contain at least 12 characters");
        System.exit(2);
      }
      System.out.println(
          "{bcrypt}"
              + new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(password));
      return;
    }
    if (args.length > 0 && args[0].equals("mcp")) {
      // stdout carries JSON-RPC only: no Spring context, no banner, no logs.
      var options = new java.util.HashMap<String, String>();
      for (int i = 1; i + 1 < args.length; i += 2) options.put(args[i], args[i + 1]);
      McpServer.main(
          options.getOrDefault("--panel", "http://localhost:8080"),
          java.nio.file.Path.of(
              options.getOrDefault("--password-file", "data/admin-password.txt")));
      return;
    }
    if (args.length > 0
        && java.util.Set.of(
                "coordinator",
                "budget",
                "scope",
                "import",
                "doctor",
                "github-check",
                "profile",
                "memory",
                "backup",
                "restore")
            .contains(args[0])) {
      System.exit(Cli.run(args));
      return;
    }
    SpringApplication.run(AgentFarmApplication.class, args);
  }
}
