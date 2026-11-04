package io.agentfarm.ui;

import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.Element;
import io.mateu.uidl.data.MicroFrontend;
import java.util.Map;

@Title("Presupuesto")
public class Budgets {
  @Label(" ")
  Element console = new Element("farm-console", Map.of("id", "farm-console-live"), "");

  @Label(" ")
  MicroFrontend status =
      MicroFrontend.builder().route("/budget-status").style("width:100%").build();

  @Label(" ")
  Element reconcile = new Element("farm-advanced", Map.of("kind", "budgets"), "");
}
