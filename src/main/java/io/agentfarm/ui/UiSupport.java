package io.agentfarm.ui;

import io.agentfarm.core.*;
import io.mateu.uidl.data.*;
import io.mateu.uidl.di.MateuBeanProvider;
import java.util.*;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;

public final class UiSupport {
  private UiSupport() {}

  public static ControlService control() {
    return MateuBeanProvider.getBean(ControlService.class);
  }

  public static CoordinatorService tasks() {
    return MateuBeanProvider.getBean(CoordinatorService.class);
  }

  public static BudgetService budgets() {
    return MateuBeanProvider.getBean(BudgetService.class);
  }

  public static String actor() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }

  public static String reason(String reason) {
    String[] parts = reason.split(":", 2);
    String label =
        switch (parts[0]) {
          case "missing_active_window" -> "falta una ventana vigente";
          case "missing_or_stale_usage" -> "falta una lectura vigente";
          case "budget_exhausted" -> "cuota agotada";
          case "insufficient_budget" -> "saldo insuficiente";
          case "unreconciled_usage" -> "hay consumo pendiente de conciliación";
          case "reservation_crossed_reset" -> "una reserva cruzó un reinicio";
          case "human_pause" -> "pausa del operador";
          default -> parts[0];
        };
    return label + (parts.length == 2 ? " (" + parts[1] + ")" : "");
  }

  private static Object display(String key, Object value) {
    if (value == null)
      return key.equals("progress")
          ? "Desconocido"
          : key.equals("heartbeat") ? "Sin heartbeat" : "—";
    if (key.equals("enabled") || key.equals("cancel_requested"))
      return ((Number) value).intValue() == 1 ? "Sí" : "No";
    if (key.equals("at") || key.equals("heartbeat")) {
      try {
        var instant =
            value instanceof Number n
                ? java.time.Instant.ofEpochSecond(n.longValue())
                : java.time.Instant.parse(value.toString());
        return java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'")
            .withZone(java.time.ZoneOffset.UTC)
            .format(instant);
      } catch (Exception ignored) {
      }
    }
    if (key.equals("state") || key.equals("status"))
      return switch (value.toString()) {
        case "unknown" -> "Desconocido";
        case "stale" -> "Heartbeat caducado";
        case "node_unavailable" -> "Nodo no disponible";
        case "disabled" -> "Deshabilitado";
        case "active" -> "Activo";
        case "idle" -> "Libre";
        case "busy" -> "Ocupado";
        case "ready" -> "Lista";
        case "assigned" -> "Asignada";
        case "running" -> "En ejecución";
        case "review" -> "Pendiente de revisión";
        case "cancelled" -> "Cancelada";
        default -> value;
      };
    if (key.equals("mode"))
      return value.equals("subscription") ? "Suscripción" : value.equals("api") ? "API" : value;
    if (key.equals("shares")) {
      try {
        return io.agentfarm.core.Json.parse(value.toString()).properties().stream()
            .map(e -> e.getKey() + ": " + (e.getValue().asLong() / 100.0) + "%")
            .collect(java.util.stream.Collectors.joining(", "));
      } catch (Exception ignored) {
      }
    }
    return value;
  }

  public static Grid table(String id, List<Map<String, Object>> rows, String... columns) {
    List<GridContent> headers = new ArrayList<>();
    for (int i = 0; i < columns.length; i += 2)
      headers.add(
          GridColumn.builder()
              .id(columns[i])
              .label(columns[i + 1])
              .autoWidth(true)
              .resizable(true)
              .build());
    List<Map<String, Object>> displayed =
        rows.stream()
            .map(
                row -> {
                  Map<String, Object> copy = new LinkedHashMap<>();
                  row.forEach((key, value) -> copy.put(key, display(key, value)));
                  return copy;
                })
            .toList();
    return Grid.builder()
        .id(id)
        .content(headers)
        .compact(true)
        .wrapCellContent(true)
        .rowStripes(true)
        .page(new Page<>("", Math.max(1, rows.size()), 0, rows.size(), displayed))
        .build();
  }
}
