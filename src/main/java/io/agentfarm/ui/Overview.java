package io.agentfarm.ui;

import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.Element;
import java.util.Map;

@Title("Bandeja")
public class Overview {
  @Label(" ")
  Element console = new Element("farm-console", Map.of("id", "farm-console-live"), "");
}
