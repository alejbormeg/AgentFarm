package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.Json;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CliTest {
  @TempDir Path dir;

  @Test
  void commandsUseJavaServicesAndPreserveJsonExitCodes() throws Exception {
    String db = dir.resolve("state.db").toString();
    var out = new ByteArrayOutputStream();
    var original = System.out;
    try {
      System.setOut(new PrintStream(out));
      assertEquals(
          0, Cli.run(new String[] {"coordinator", "--db", db, "enqueue", "examples/task.json"}));
      assertTrue(out.toString().contains("enqueued"));
      out.reset();
      assertEquals(0, Cli.run(new String[] {"coordinator", "--db", db, "simulate"}));
      assertTrue(out.toString().contains("simulation"));
      out.reset();
      assertEquals(2, Cli.run(new String[] {"coordinator", "--db", db, "invalid"}));
      assertEquals("error", Json.parse(out.toString()).get("state").asText());
    } finally {
      System.setOut(original);
    }
  }

  @Test
  void profileProducesExactlyOneJsonWithoutStateField() throws Exception {
    Path profile = dir.resolve("agentfarm.yaml");
    Files.writeString(profile, ProfileBackupTest.PROFILE);
    var out = new ByteArrayOutputStream();
    var original = System.out;
    try {
      System.setOut(new PrintStream(out));
      assertEquals(0, Cli.run(new String[] {"profile", "--file", profile.toString()}));
      assertEquals(1, out.toString().lines().count());
      assertEquals("owner/repo", Json.parse(out.toString()).path("repository").asText());
    } finally {
      System.setOut(original);
    }
  }

  @Test
  void missingBudgetObservationPausesWithoutProviderCall() {
    String db = dir.resolve("budget.db").toString();
    var original = System.out;
    try {
      System.setOut(new PrintStream(new ByteArrayOutputStream()));
      assertEquals(
          0,
          Cli.run(
              new String[] {
                "budget", "--db", db, "configure", "examples/budget-subscription.json"
              }));
      assertEquals(1, Cli.run(new String[] {"budget", "--db", db, "status", "codex-subscription"}));
    } finally {
      System.setOut(original);
    }
  }
}
