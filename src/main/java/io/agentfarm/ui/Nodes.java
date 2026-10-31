package io.agentfarm.ui;

import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.Grid;
import io.mateu.uidl.data.Text;

@Title("Nodos dirigentes")
public class Nodes {
  @Label("Información")
  Text note =
      new Text(
          "Napoleon dirige un nodo; Napoleon y Snowball pueden contrastar propuestas. Un registro"
              + " sin heartbeat aparece como desconocido. Un heartbeat con más de 45 segundos"
              + " aparece como caducado.");

  @Label("Registro")
  Grid inventory =
      UiSupport.table(
          "nodes",
          UiSupport.control().nodes(),
          "id",
          "Nodo",
          "role",
          "Rol",
          "status",
          "Estado declarado",
          "heartbeat",
          "Último heartbeat (UTC Unix)",
          "enabled",
          "Habilitado");

  @Label("Identificador del nodo")
  String nodeId;

  enum Role {
    napoleon,
    snowball
  }

  @Label("Rol")
  Role role = Role.napoleon;

  @Label("Registrar")
  @Button
  @Action
  public Nodes register() {
    UiSupport.control().registerNode(nodeId, role.name(), UiSupport.actor());
    return new Nodes();
  }

  @Label("Deshabilitar")
  @Button(group = "Disponibilidad")
  @Action(
      confirmationRequired = true,
      confirmationMessage =
          "Se impedirá admitir trabajo nuevo en este nodo. Esto no mata procesos en curso.")
  public Nodes disable() {
    UiSupport.control().enable("node", nodeId, false, UiSupport.actor());
    return new Nodes();
  }

  @Label("Habilitar")
  @Button(group = "Disponibilidad")
  @Action
  public Nodes enable() {
    UiSupport.control().enable("node", nodeId, true, UiSupport.actor());
    return new Nodes();
  }

  @Label("Actualizar")
  @Toolbar
  @Action
  public Nodes refresh() {
    return new Nodes();
  }
}
