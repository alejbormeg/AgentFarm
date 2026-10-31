package io.agentfarm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentFarmApplication {
  public static void main(String[] args) {
    if (args.length > 0
        && java.util.Set.of("coordinator", "budget", "scope", "import").contains(args[0])) {
      System.exit(Cli.run(args));
      return;
    }
    SpringApplication.run(AgentFarmApplication.class, args);
  }
}
