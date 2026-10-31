package io.agentfarm.ui;

import io.agentfarm.core.Json;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.Grid;
import io.mateu.uidl.data.Text;
import java.util.List;

@Title("Panorama de la granja")
@Trigger(type = TriggerType.OnLoad, actionId = "refresh", timeoutMillis = 5000, background = true)
@Trigger(
    type = TriggerType.OnSuccess,
    actionId = "refresh",
    calledActionId = "refresh",
    timeoutMillis = 5000,
    background = true)
public class Overview {
  Text admission =
      new Text(
          UiSupport.control().paused()
              ? "Admisión pausada por el operador."
              : "Admisión habilitada, sujeta a presupuesto y capacidad.");
  Text mode =
      new Text(
          "Control local. No hay un proveedor de IA conectado. Los heartbeats y el progreso se"
              + " muestran como datos declarados, no como verificación de implementación.");

  @Label("Registro")
  Grid inventory =
      UiSupport.table(
          "overview",
          List.of(
              Json.obj(
                  "resource", "Nodos registrados", "count", UiSupport.control().nodes().size()),
              Json.obj(
                  "resource",
                  "Nodos con heartbeat vigente",
                  "count",
                  UiSupport.control().nodes().stream()
                      .filter(n -> List.of("active", "idle", "busy").contains(n.get("status")))
                      .count()),
              Json.obj(
                  "resource", "Boxer registrados", "count", UiSupport.control().workers().size()),
              Json.obj(
                  "resource",
                  "Boxer con heartbeat vigente",
                  "count",
                  UiSupport.control().workers().stream()
                      .filter(w -> List.of("active", "idle", "busy").contains(w.get("status")))
                      .count()),
              Json.obj("resource", "Tareas en la cola", "count", UiSupport.tasks().tasks().size())),
          "resource",
          "Recurso",
          "count",
          "Total");

  @Label("Actualizar")
  @Toolbar
  @Action
  public Overview refresh() {
    return new Overview();
  }
}
