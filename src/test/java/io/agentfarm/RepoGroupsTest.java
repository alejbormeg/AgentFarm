package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.adapters.RepoGroups;
import io.agentfarm.core.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RepoGroupsTest {
  @TempDir Path dir;
  Store store;
  RepoGroups groups;

  @BeforeEach
  void setup() {
    store = new Store(dir.resolve("db").toString());
    new WorkflowService(store, Clock.systemUTC());
    groups = new RepoGroups(store, Clock.systemUTC());
  }

  JsonNode members(String... repoAndRole) {
    var list = new ArrayList<Object>();
    for (int i = 0; i < repoAndRole.length; i += 2)
      list.add(obj("repo", repoAndRole[i], "role", repoAndRole[i + 1]));
    return MAPPER.valueToTree(list);
  }

  @Test
  void definesReplacesAndListsAGroup() {
    var g = groups.define("o/forja", members("o/api", "contrato", "o/web", "interfaz"), "owner");
    assertEquals("o/forja", g.get("integrator"));
    assertEquals(2, ((List<?>) g.get("members")).size());
    // The list is the whole group: a second definition replaces it.
    groups.define("o/forja", members("o/api", "contrato"), "owner");
    assertEquals(Set.of("o/api"), groups.members("o/forja"));
    assertEquals(1, groups.all().size());
    assertEquals(1, groups.of("o/api").size());
    assertEquals(1, groups.of("o/forja").size());
    assertTrue(groups.of("o/otro").isEmpty());
  }

  @Test
  void aMemberIsKnownOnlyWhenThePanelHasWorkedThere() {
    new WorkflowService(store, Clock.systemUTC())
        .create(
            MAPPER.valueToTree(
                obj(
                    "request_id",
                    "known-0001",
                    "repository",
                    "o/api",
                    "title",
                    "t",
                    "request",
                    "r")),
            "owner");
    var g = groups.define("o/forja", members("o/api", "", "o/web", ""), "owner");
    var list = (List<?>) g.get("members");
    assertEquals(true, ((Map<?, ?>) list.get(0)).get("known"));
    assertEquals(false, ((Map<?, ?>) list.get(1)).get("known"));
  }

  @Test
  void rejectsWhatIsNotAGroup() {
    assertThrows(
        IllegalArgumentException.class, () -> groups.define("o/forja", members(), "owner"));
    assertThrows(
        IllegalArgumentException.class,
        () -> groups.define("o/forja", members("o/forja", "yo"), "owner"));
    assertThrows(
        IllegalArgumentException.class,
        () -> groups.define("o/forja", members("o/api", "", "O/API", ""), "owner"));
    assertThrows(
        IllegalArgumentException.class,
        () -> groups.define("o/forja", members("../x", ""), "owner"));
    assertThrows(
        IllegalArgumentException.class, () -> groups.define("..", members("o/api", ""), "owner"));
    assertThrows(
        IllegalArgumentException.class,
        () -> groups.define("o/forja", members("api", "sin propietario"), "owner"));
    var many = new String[(RepoGroups.MAX_MEMBERS + 1) * 2];
    for (int i = 0; i <= RepoGroups.MAX_MEMBERS; i++) {
      many[i * 2] = "o/r" + i;
      many[i * 2 + 1] = "";
    }
    assertThrows(
        IllegalArgumentException.class, () -> groups.define("o/forja", members(many), "owner"));
    assertTrue(groups.all().isEmpty(), "nothing is half saved");
  }

  @Test
  void onlyAPersonDecidesAndTheRoleIsCleaned() {
    for (String actor : List.of("node:n1", "requester:mcp", "trigger:t"))
      assertThrows(
          IllegalArgumentException.class,
          () -> groups.define("o/forja", members("o/api", ""), actor));
    assertThrows(IllegalArgumentException.class, () -> groups.delete("o/forja", "node:n1"));
    var g = groups.define("o/forja", members("o/api", "a\u0000b\n" + "x".repeat(400)), "owner");
    String role = ((Map<?, ?>) ((List<?>) g.get("members")).get(0)).get("role").toString();
    assertTrue(role.length() <= RepoGroups.MAX_ROLE);
    assertFalse(role.contains("\u0000") || role.contains("\n"));
  }

  @Test
  void deleteRemovesTheGroupAndItsMembersAndIsAudited() {
    groups.define("o/forja", members("o/api", ""), "owner");
    groups.delete("o/forja", "owner");
    assertNull(groups.get("o/forja"));
    assertTrue(groups.members("o/forja").isEmpty());
    assertTrue(groups.of("o/api").isEmpty());
  }
}
