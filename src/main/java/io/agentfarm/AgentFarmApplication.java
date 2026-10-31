package io.agentfarm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class AgentFarmApplication {
  public static void main(String[] args) {
    if (args.length > 0
        && java.util.Set.of(
                "coordinator",
                "budget",
                "scope",
                "import",
                "doctor",
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
