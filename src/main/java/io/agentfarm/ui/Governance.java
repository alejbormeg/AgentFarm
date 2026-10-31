package io.agentfarm.ui;

import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.Grid;
import io.mateu.uidl.data.Text;

@Title("Gobierno y auditoría")
public class Governance {
  Text admission =
      new Text(
          UiSupport.control().paused()
              ? "Estado: admisión pausada."
              : "Estado: admisión habilitada.");
  Text limits =
      new Text(
          "Pausar bloquea asignaciones, simulaciones y reservas nuevas. No cancela turnos"
              + " existentes. No hay fusión automática, recarga de crédito ni consenso distribuido"
              + " implementado.");
  Grid history =
      UiSupport.table(
          "audit",
          UiSupport.control().audit(),
          "at",
          "Fecha UTC",
          "actor",
          "Operador",
          "action",
          "Acción",
          "subject",
          "Recurso");

  @Label("Pausar admisión")
  @Toolbar
  @Action(
      confirmationRequired = true,
      confirmationMessage =
          "Se pausará toda admisión nueva. Los procesos existentes requerirán una solicitud de"
              + " cancelación.")
  public Governance pause() {
    UiSupport.control().pause(true, UiSupport.actor());
    return new Governance();
  }

  @Label("Reanudar admisión")
  @Toolbar
  @Action
  public Governance resume() {
    UiSupport.control().pause(false, UiSupport.actor());
    return new Governance();
  }

  @Label("Actualizar")
  @Toolbar
  @Action
  public Governance refresh() {
    return new Governance();
  }
}
