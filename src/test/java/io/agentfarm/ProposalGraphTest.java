package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.*;
import org.junit.jupiter.api.Test;

class ProposalGraphTest {
  private static JsonNode proposal(String json) {
    var p = Json.parse(json);
    ProposalShape.normalize(p);
    return p;
  }

  @Test
  void aGraphBecomesTheSingleSourceOfTheMermaidText() {
    var p =
        proposal(
            "{\"as_is\":\"flowchart LR; old\",\"to_be_graph\":{\"nodes\":["
                + "{\"id\":\"api\",\"label\":\"API\",\"type\":\"service\",\"change\":\"modified\"},"
                + "{\"id\":\"db\",\"label\":\"Pedidos\",\"type\":\"database\",\"change\":\"new\"},"
                + "{\"id\":\"cfg\",\"label\":\"app.yml\",\"type\":\"config\"}],"
                + "\"edges\":[{\"from\":\"api\",\"to\":\"db\",\"label\":\"escribe\"}]}}");
    String mermaid = p.path("to_be").asText();
    assertTrue(mermaid.startsWith("flowchart LR"), mermaid);
    assertTrue(mermaid.contains("db[(\"Pedidos\")]"), "a database is a cylinder: " + mermaid);
    assertTrue(mermaid.contains("cfg{{\"app.yml\"}}"), mermaid);
    assertTrue(mermaid.contains("api -->|\"escribe\"| db"), mermaid);
    assertTrue(mermaid.contains("class api modified"), mermaid);
    assertEquals("flowchart LR; old", p.path("as_is").asText(), "no as_is_graph, text untouched");
    assertEquals("unchanged", p.path("to_be_graph").path("nodes").get(2).path("change").asText());
  }

  @Test
  void aGraphIsCleanedNotRejected() {
    var nodes = new StringBuilder();
    for (int i = 0; i < 40; i++)
      nodes.append(i == 0 ? "" : ",").append("{\"id\":\"n").append(i).append("\",\"label\":\"N\"}");
    var p =
        proposal(
            "{\"to_be_graph\":{\"nodes\":["
                + nodes
                + ",{\"id\":\"n0\",\"label\":\"duplicate id\"},{\"id\":\"bad id!\",\"label\":\"x\"},"
                + "{\"id\":\"w\",\"label\":\"<img src=x onerror=alert(1)>`a`\\n\\\"b\\\"\","
                + "\"type\":\"spaceship\",\"change\":\"exploded\"}],"
                + "\"edges\":[{\"from\":\"n0\",\"to\":\"n1\"},{\"from\":\"n0\",\"to\":\"n1\"},"
                + "{\"from\":\"n0\",\"to\":\"ghost\"}]}}");
    var graph = p.path("to_be_graph");
    assertEquals(24, graph.path("nodes").size(), "bounded");
    assertEquals(1, graph.path("edges").size(), "duplicates and dangling edges are dropped");
    assertFalse(p.path("to_be").asText().contains("<"), p.path("to_be").asText());

    var odd =
        proposal(
            "{\"to_be_graph\":{\"nodes\":[{\"id\":\"w\",\"label\":\"<b>x</b> `y`\","
                + "\"type\":\"spaceship\",\"change\":\"exploded\"}],\"edges\":[]}}");
    var node = odd.path("to_be_graph").path("nodes").get(0);
    assertEquals("other", node.path("type").asText());
    assertEquals("unchanged", node.path("change").asText());
    assertEquals("b x /b y", node.path("label").asText());
  }

  @Test
  void anUnusableGraphKeepsTheTextOrAPlaceholderSoTheTurnIsNotLost() {
    var kept = proposal("{\"as_is\":\"flowchart LR; a-->b\",\"as_is_graph\":{\"nodes\":[]}}");
    assertEquals("flowchart LR; a-->b", kept.path("as_is").asText());
    assertFalse(kept.has("as_is_graph"));

    var bare = proposal("{\"to_be_graph\":\"not a graph\"}");
    assertTrue(bare.path("to_be").asText().contains("Sin diagrama"));
    assertFalse(bare.has("to_be_graph"));
  }

  @Test
  void theGlanceFieldsAreShortPlainTextOrAbsent() {
    var p =
        proposal(
            "{\"headline\":\"  Ya está adoptado:\\n solo hace falta documentarlo  \","
                + "\"will_change\":[\"Un informe\",\"\",7,\"a\",\"b\",\"c\",\"d\",\"e\"],"
                + "\"will_not_change\":\"no es una lista\"}");
    assertEquals("Ya está adoptado: solo hace falta documentarlo", p.path("headline").asText());
    assertEquals(5, p.path("will_change").size());
    assertEquals("Un informe", p.path("will_change").get(0).asText());
    assertFalse(p.has("will_not_change"));

    var long_ = proposal("{\"headline\":\"" + "x".repeat(400) + "\"}");
    assertTrue(long_.path("headline").asText().length() <= 160);
    assertFalse(proposal("{\"headline\":42}").has("headline"));
  }
}
