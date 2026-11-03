package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import org.junit.jupiter.api.Test;

class ProposalShapeTest {
  @Test
  void checkShapesFromRealAgentsAreNormalizedWithoutInventingAnything() {
    // Shape returned by Codex in the first real pilot (checks as an object of commands).
    var p =
        Json.parse(
            "{\"criteria\":["
                + "{\"id\":\"AC-1\",\"method\":\"test\",\"checks\":{\"unit\":\"python3 -m unittest\"}},"
                + "{\"id\":\"AC-2\",\"method\":\"test\",\"checks\":\"integration\"},"
                + "{\"id\":\"AC-3\",\"method\":\"test\",\"checks\":[\"Unit\",\"unit\",\"integration\"]},"
                + "{\"id\":\"AC-4\",\"method\":\"test\",\"checks\":{\"e2e\":\"x\"}},"
                + "{\"id\":\"AC-5\",\"method\":\"manual\",\"checks\":{\"unit\":\"x\"}}]}");
    ProposalShape.normalize(p);
    var c = p.path("criteria");
    assertEquals("[\"unit\"]", c.get(0).path("checks").toString());
    assertEquals("[\"integration\"]", c.get(1).path("checks").toString());
    assertEquals("[\"unit\",\"integration\"]", c.get(2).path("checks").toString());
    assertTrue(c.get(3).path("checks").isObject(), "unknown checks are left for the panel to reject");
    assertTrue(c.get(4).path("checks").isObject(), "manual criteria untouched");
  }

  @Test
  void changesListOnlyWhatAnOperatorApproves() {
    var before = Json.parse("{\"summary\":\"a\",\"allowed_paths\":[\"src/\"],\"prompt\":{\"id\":\"x\"}}");
    var after = Json.parse("{\"summary\":\"a\",\"allowed_paths\":[\"lib/\"],\"prompt\":{\"id\":\"y\"}}");
    var changes = ProposalShape.changes(before, after);
    assertEquals(1, changes.size());
    assertEquals("allowed_paths", changes.get(0).path("field").asText());
    assertEquals("[\"src/\"]", changes.get(0).path("before").toString());
    assertEquals("[\"lib/\"]", changes.get(0).path("after").toString());
    assertEquals(0, ProposalShape.changes(Json.parse("{}"), after).size(), "a first version has nothing to compare");
  }

  @Test
  void researchPromptShowsTheChecksShape() {
    assertTrue(Prompts.template(Prompts.CURRENT.get("research")).contains("\"checks\": [\"unit\"]"));
  }
}
