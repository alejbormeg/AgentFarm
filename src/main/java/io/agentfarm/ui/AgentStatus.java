package io.agentfarm.ui;

import io.mateu.uidl.annotations.Action;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.annotations.Trigger;
import io.mateu.uidl.annotations.TriggerType;
import io.mateu.uidl.annotations.UI;
import io.mateu.uidl.data.Message;
import io.mateu.uidl.data.StatusItem;
import io.mateu.uidl.data.StatusList;
import io.mateu.uidl.data.Text;
import io.mateu.uidl.data.VerticalLayout;
import io.mateu.uidl.fluent.Component;
import io.mateu.uidl.interfaces.ComponentTreeSupplier;
import io.mateu.uidl.interfaces.HttpRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The nodes and workers, re-fetched in place so a lost heartbeat shows without a reload. */
@UI("/agent-status")
@Style(UiSupport.FULL_WIDTH)
@Trigger(
    type = TriggerType.OnLoad,
    actionId = "refresh",
    times = 0,
    timeoutMillis = UiSupport.REFRESH_MILLIS,
    background = true)
public class AgentStatus implements ComponentTreeSupplier {
  @Action
  Object refresh() {
    return this;
  }

  // The panel's /api rules don't cover /mateu routes, so owner-only actions check it here.
  @Action
  Object toggle(HttpRequest request) {
    if (!UiSupport.isOwner())
      return List.of(Message.error("Solo el propietario puede cambiar un agente."), this);
    String[] target = String.valueOf(request.runActionRq().parameters().get("_item")).split(":", 3);
    if (target.length != 3) return List.of(Message.error("Agente desconocido."), this);
    try {
      UiSupport.control()
          .enable(target[0], target[1], Boolean.parseBoolean(target[2]), UiSupport.actor());
      return this;
    } catch (IllegalArgumentException e) {
      return List.of(Message.error(e.getMessage()), this);
    }
  }

  @Override
  public Component component(HttpRequest request) {
    long now = Instant.now().getEpochSecond();
    boolean executorFresh = Boolean.TRUE.equals(UiSupport.telemetry().snapshot().get("fresh"));
    boolean owner = UiSupport.isOwner();
    var parts = new ArrayList<Component>();
    section(parts, "/icons/napoleon.svg", "Nodos dirigentes", "node", UiSupport.control().nodes(), now, executorFresh, owner);
    section(parts, "/icons/boxer.svg", "Trabajadores Boxer", "worker", UiSupport.control().workers(), now, executorFresh, owner);
    return VerticalLayout.builder().content(parts).style(UiSupport.FULL_WIDTH).fullWidth(true).build();
  }

  private static void section(
      List<Component> parts,
      String icon,
      String heading,
      String kind,
      List<Map<String, Object>> rows,
      long now,
      boolean executorFresh,
      boolean owner) {
    parts.add(UiSupport.heading(icon, heading));
    if (rows.isEmpty()) {
      parts.add(new Text("Ninguno registrado todavía."));
      return;
    }
    parts.add(
        StatusList.builder()
            .style(UiSupport.FULL_WIDTH)
            .items(rows.stream().map(a -> item(a, kind, now, executorFresh, owner)).toList())
            .build());
  }

  static StatusItem item(
      Map<String, Object> a, String kind, long now, boolean executorFresh, boolean owner) {
    boolean enabled = ((Number) a.get("enabled")).intValue() != 0;
    Long heartbeat = a.get("heartbeat") instanceof Number n ? n.longValue() : null;
    boolean alive =
        executorFresh && heartbeat != null && now - heartbeat >= 0 && now - heartbeat <= 12;
    String state, color;
    if (!enabled) {
      state = "Deshabilitado";
      color = "contrast";
    } else if (!alive) {
      state = "Sin señal";
      color = "warning";
    } else if ("busy".equals(a.get("reported_state"))) {
      state = "Trabajando";
      color = "primary";
    } else {
      state = "Disponible";
      color = "success";
    }
    String id = String.valueOf(a.get("id"));
    var row =
        StatusItem.builder()
            .id(kind + ":" + id + ":" + !enabled)
            .title(id)
            .description(
                ("snowball".equals(a.get("role")) ? "Snowball · " : "")
                    + (heartbeat == null
                        ? "Nunca ha dado señal"
                        : "Última señal " + UiSupport.ago(now - heartbeat)))
            .status(state)
            .statusColor(color);
    if (owner) row.actionLabel(enabled ? "Deshabilitar" : "Habilitar").actionId("toggle");
    return row.build();
  }
}
