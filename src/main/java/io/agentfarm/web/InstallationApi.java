package io.agentfarm.web;

import io.agentfarm.core.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class InstallationApi {
  private final IntegrationPublisher publisher;

  public InstallationApi(IntegrationPublisher publisher) {
    this.publisher = publisher;
  }

  @GetMapping("/api/installation")
  public Object state() {
    return Json.obj(
        "integration_publisher",
        publisher.configured(),
        "execution_environment",
        "local",
        "node_credentials",
        true,
        "worker_identity_isolated",
        false,
        "container_executor",
        false,
        "secret_injection",
        false,
        "phase",
        "1-in-progress");
  }
}
