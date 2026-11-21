package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.adapters.RepoCandidates;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class RepoCandidatesTest {
  static JsonNode file(String text) {
    return MAPPER.valueToTree(
        obj(
            "encoding",
            "base64",
            "content",
            Base64.getMimeEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))));
  }

  RepoCandidates with(Map<String, String> files) {
    return new RepoCandidates((repo, path) -> {
      String text = files.get(repo + path);
      if (text == null) throw new IllegalStateException("GitHub HTTP 404 for " + path);
      return file(text);
    });
  }

  @Test
  void findsSubmodulesAndReadmeLinksWithTheirSource() {
    var found =
        with(Map.of(
                "o/forja/contents/.gitmodules",
                    "[submodule \"api\"]\n\tpath = api\n\turl = https://github.com/o/api.git\n"
                        + "[submodule \"x\"]\n\turl = git@github.com:o/web.git\n",
                "o/forja/readme",
                    "Mira https://github.com/o/api y [docs](https://github.com/o/docs/)."
                        + " Tambien https://github.com/otro/lib. No https://github.com/features/actions"))
            .find("o/forja", Set.of());
    var names = new ArrayList<String>();
    for (var c : found) names.add(c.get("repo").toString());
    assertEquals(List.of("o/api", "o/web", "o/docs", "otro/lib"), names);
    assertEquals(List.of("submódulo", "README"), found.get(0).get("found_in"));
    assertEquals(true, found.get(0).get("same_owner"));
    assertEquals(false, found.get(3).get("same_owner"));
  }

  @Test
  void leavesOutTheIntegratorAndTheMembersAlreadyChosen() {
    var found =
        with(Map.of(
                "o/forja/readme",
                "https://github.com/o/forja https://github.com/o/api https://github.com/o/web"))
            .find("o/forja", Set.of("O/API"));
    assertEquals(1, found.size());
    assertEquals("o/web", found.get(0).get("repo"));
  }

  @Test
  void nothingToReadMeansNothingToSuggest() {
    assertTrue(with(Map.of()).find("o/forja", Set.of()).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> with(Map.of()).find("../x", Set.of()));
  }

  @Test
  void hostileTextOnlyYieldsWellFormedRepositoriesAndIsCapped() {
    var text = new StringBuilder("github.com/../x github.com/a/.. github.com/orgs/x\n");
    for (int i = 0; i < 100; i++) text.append("github.com/o/r").append(i).append(" ");
    var found = with(Map.of("o/forja/readme", text.toString())).find("o/forja", Set.of());
    assertEquals(RepoCandidates.MAX, found.size());
    for (var c : found) assertTrue(c.get("repo").toString().matches("o/r[0-9]+"));
  }

  @Test
  void aFileThatIsNotBase64IsIgnored() {
    var repo =
        new RepoCandidates((r, p) -> MAPPER.valueToTree(obj("encoding", "none", "content", "x")));
    assertTrue(repo.find("o/forja", Set.of()).isEmpty());
  }
}
