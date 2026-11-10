package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.adapters.RequesterCredentials;
import io.agentfarm.core.Store;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RequesterCredentialsTest {
  @TempDir Path dir;

  @Test
  void aTokenIsShownOnceKeptAsAHashAndRevocable() {
    var store = new Store(dir.resolve("db").toString());
    var clock = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC);
    var credentials = new RequesterCredentials(store, clock);
    var issued = credentials.issue("mcp-portatil", "owner");
    String token = issued.get("token").toString();
    assertTrue(token.matches("afr_[A-Za-z0-9_-]{43}"), token);
    assertEquals("mcp-portatil", credentials.authenticate(token));
    assertNull(credentials.authenticate(token + "x"));
    assertNull(credentials.authenticate("afn_" + token.substring(4)), "a node token is not one");

    var listed = credentials.list();
    assertEquals(1, listed.size());
    assertFalse(listed.toString().contains(token.substring(4, 20)), "the list has no secret");
    assertFalse(listed.get(0).containsKey("hash"));
    assertEquals(1794304800L, ((Number) listed.get(0).get("last_used")).longValue());

    credentials.revoke(issued.get("id").toString(), "owner");
    assertNull(credentials.authenticate(token));
    assertThrows(
        IllegalArgumentException.class,
        () -> credentials.revoke(issued.get("id").toString(), "owner"),
        "nothing left to revoke");
    for (String bad : new String[] {"", "Mayúsculas", "con espacio", "x".repeat(41), "-guion"})
      assertThrows(IllegalArgumentException.class, () -> credentials.issue(bad, "owner"), bad);
  }
}
