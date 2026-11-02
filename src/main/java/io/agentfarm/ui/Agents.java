package io.agentfarm.ui;

import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.Element;
import io.mateu.uidl.data.MicroFrontend;
import java.util.Map;

@Title("Agentes")
public class Agents {
  @Label(" ")
  Element console = new Element("farm-console", Map.of("id", "farm-console-live"), "");

  @Label(" ")
  MicroFrontend status = MicroFrontend.builder().route("/agent-status").style("width:100%").build();

  @Label(" ")
  Element registerNode = new Element("farm-advanced", Map.of("kind", "nodes"), "");

  @Label(" ")
  Element registerWorker = new Element("farm-advanced", Map.of("kind", "workers"), "");
}
