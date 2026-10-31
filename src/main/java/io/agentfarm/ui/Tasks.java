package io.agentfarm.ui;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.Element;
import java.util.Map;
@Title("Tareas y progreso")
public class Tasks {
 @Label(" ")
 Element console = new Element("farm-console", Map.of("id","farm-console-live"), "");
}
