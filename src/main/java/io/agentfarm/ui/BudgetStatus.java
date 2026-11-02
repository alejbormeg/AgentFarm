package io.agentfarm.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.agentfarm.core.AgentAdapters;
import io.mateu.uidl.annotations.Action;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.annotations.Trigger;
import io.mateu.uidl.annotations.TriggerType;
import io.mateu.uidl.annotations.UI;
import io.mateu.uidl.data.Meter;
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

/** Provider quota, Clover's ledger and the engines, re-fetched in place. Read-only. */
@UI("/budget-status")
@Style(UiSupport.FULL_WIDTH)
@Trigger(
    type = TriggerType.OnLoad,
    actionId = "refresh",
    times = 0,
    timeoutMillis = UiSupport.REFRESH_MILLIS,
    background = true)
public class BudgetStatus implements ComponentTreeSupplier {
  @Action
  Object refresh() {
    return this;
  }

  @Override
  public Component component(HttpRequest request) {
    var parts = new ArrayList<Component>();
    parts.add(UiSupport.heading(null, "Cuota del proveedor"));
    parts.add(quota(UiSupport.telemetry().snapshot()));
    parts.add(UiSupport.heading("/icons/clover.svg", "Clover · lo reservado y lo que queda"));
    for (var account : UiSupport.budgets().accounts()) {
      String name = String.valueOf(account.get("account"));
      var status = UiSupport.budgets().status(name);
      parts.add(new Text(name));
      String unit = String.valueOf(status.get("unit"));
      for (Object w : (List<?>) status.get("windows")) parts.add(window((Map<?, ?>) w, unit));
      for (Object reason : (List<?>) status.get("reasons"))
        parts.add(new Text("⚠ " + UiSupport.reason(String.valueOf(reason))));
    }
    parts.add(UiSupport.heading(null, "Motores y capacidades"));
    parts.add(
        StatusList.builder()
            .style(UiSupport.FULL_WIDTH)
            .items(AgentAdapters.catalog().stream().map(BudgetStatus::engine).toList())
            .build());
    parts.add(
        new Text(
            "Sin cambio automático de proveedor ni pagos adicionales. Económico y equilibrado usan"
                + " la misma política actual; revisión reforzada espera al verificador separado."));
    return VerticalLayout.builder().content(parts).style(UiSupport.FULL_WIDTH).fullWidth(true).build();
  }

  // A stale reading never appears as a gauge: it would look like the current balance.
  static Component quota(Map<String, Object> runtime) {
    JsonNode q =
        runtime.get("snapshot") instanceof JsonNode n
            ? n.path("quota")
            : MissingNode.getInstance();
    String observed = q.path("observed_at").asText("");
    String seen = "Sin observación";
    if (!observed.isEmpty()) {
      try {
        seen = "Última observación " + UiSupport.ago(Instant.now().getEpochSecond() - Instant.parse(observed).getEpochSecond());
      } catch (java.time.format.DateTimeParseException ignored) {
        // An unreadable date stays reported as no observation.
      }
    }
    if (!Boolean.TRUE.equals(runtime.get("quota_fresh")))
      return new Text("Cuota actual desconocida. " + seen + ".");
    double used = q.path("weekly_used_percent").asDouble();
    return Meter.builder()
        .style(UiSupport.FULL_WIDTH)
        .label("Semanal")
        .value(used)
        .max(100d)
        .unit("%")
        .warnAt(80d)
        .dangerAt(95d)
        .caption(UiSupport.number(100 - used) + " % restante · " + seen)
        .build();
  }

  static Meter window(Map<?, ?> w, String unit) {
    double divisor = "usd_micros".equals(unit) ? 1_000_000d : 100d;
    String symbol = "usd_micros".equals(unit) ? " USD" : " %";
    double used = number(w, "used") / divisor,
        reserved = number(w, "reserved") / divisor,
        cap = number(w, "cap") / divisor;
    return Meter.builder()
        .style(UiSupport.FULL_WIDTH)
        .label(UiSupport.period(String.valueOf(w.get("period"))))
        .value(used + reserved)
        .max(cap)
        .unit(symbol)
        .warnAt(cap * 0.8)
        .dangerAt(cap * 0.95)
        .caption(
            "Consumido "
                + UiSupport.number(used)
                + symbol
                + " · Reservado "
                + UiSupport.number(reserved)
                + symbol
                + " · Margen "
                + UiSupport.number(number(w, "remaining") / divisor)
                + symbol)
        .build();
  }

  private static double number(Map<?, ?> w, String key) {
    return w.get(key) instanceof Number n ? n.doubleValue() : 0;
  }

  static StatusItem engine(Map<String, Object> provider) {
    boolean implemented = "implemented".equals(provider.get("state"));
    String quota =
        switch (String.valueOf(provider.get("quota"))) {
          case "unknown" -> "desconocida";
          case "api-cost-observed" -> "coste API en USD por turno (cuenta Clover api)";
          default -> "suscripción observada";
        };
    return StatusItem.builder()
        .id(String.valueOf(provider.get("id")))
        .title(String.valueOf(provider.get("id")))
        .description("Cuota: " + quota)
        .status(implemented ? "Ejecutor integrado" : "Bloqueado: integración pendiente")
        .statusColor(implemented ? "success" : "contrast")
        .build();
  }
}
