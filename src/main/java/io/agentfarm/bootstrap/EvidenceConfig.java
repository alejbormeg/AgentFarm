package io.agentfarm.bootstrap;

import io.agentfarm.core.EvidenceVerifier;
import io.agentfarm.core.GitHubReader;
import io.agentfarm.core.Store;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the evidence services, so they don't need to know about Spring or its properties. */
@Configuration
class EvidenceConfig {
  @Bean
  EvidenceVerifier evidenceVerifier(
      Store store,
      Clock clock,
      GitHubReader github,
      @Value("${agentfarm.ci-wait-seconds:600}") long ciWaitSeconds) {
    return new EvidenceVerifier(store, clock, github::get, github::configured, ciWaitSeconds);
  }
}
