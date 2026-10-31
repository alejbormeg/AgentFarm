package io.agentfarm.ui;

import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.Grid;
import io.mateu.uidl.data.Text;

@Title("Trabajadores Boxer")
public class Workers {
  @Label("Información")
  Text note =
      new Text(
          "«Trabajaré más duro». Cada Boxer conserva su nodo, estado declarado y heartbeat."
              + " Registrar un Boxer no inicia un agente.");

  @Label("Registro")
  Grid inventory =
      UiSupport.table(
          "workers",
          UiSupport.control().workers(),
          "id",
          "Boxer",
          "node",
          "Nodo",
          "status",
          "Estado declarado",
          "heartbeat",
          "Último heartbeat (UTC Unix)",
          "enabled",
          "Habilitado");

  @Label("Identificador del Boxer")
  String workerId;

  @Label("Nodo dirigente existente")
  String nodeId;

  @Label("Registrar")
  @Button
  @Action
  public Workers register() {
    UiSupport.control().registerWorker(workerId, nodeId, UiSupport.actor());
    return new Workers();
  }

  @Label("Deshabilitar")
  @Button(group = "Disponibilidad")
  @Action(
      confirmationRequired = true,
      confirmationMessage =
          "Se bloqueará la admisión de trabajo nuevo para este Boxer. No cancela procesos en"
              + " curso.")
  public Workers disable() {
    UiSupport.control().enable("worker", workerId, false, UiSupport.actor());
    return new Workers();
  }

  @Label("Habilitar")
  @Button(group = "Disponibilidad")
  @Action
  public Workers enable() {
    UiSupport.control().enable("worker", workerId, true, UiSupport.actor());
    return new Workers();
  }

  @Label("Actualizar")
  @Toolbar
  @Action
  public Workers refresh() {
    return new Workers();
  }
}
