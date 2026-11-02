package io.agentfarm.ui;

import io.agentfarm.core.*;
import io.mateu.uidl.data.*;
import io.mateu.uidl.di.MateuBeanProvider;
import io.mateu.uidl.fluent.Component;
import java.util.*;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;

public final class UiSupport {
  static final int REFRESH_MILLIS = 5000;
  static final String FULL_WIDTH = "width:100%;box-sizing:border-box";

  private UiSupport() {}

  public static TelemetryService telemetry() {
    return MateuBeanProvider.getBean(TelemetryService.class);
  }

  public static boolean isOwner() {
    return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
        .anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));
  }

  static Component heading(String icon, String text) {
    Element title = new Element("h2", Map.of("style", "margin:0;font-size:var(--lumo-font-size-l)"), text);
    if (icon == null)
      return new Element(
          "h2", Map.of("style", "margin:var(--lumo-space-m) 0 0;font-size:var(--lumo-font-size-l)"), text);
    return HorizontalLayout.builder()
        .content(
            List.of(
                new Element("img", Map.of("src", icon, "alt", "", "width", "32", "height", "32"), ""),
                title))
        .verticalAlignment(VerticalAlignment.CENTER)
        .style("gap:var(--lumo-space-s);margin-top:var(--lumo-space-m)")
        .build();
  }

  /** Relative, so a server in another time zone can't show the operator a misleading clock. */
  static String ago(long seconds) {
    if (seconds < 0) return "con fecha futura";
    if (seconds < 60) return "hace " + seconds + " s";
    if (seconds < 3600) return "hace " + seconds / 60 + " min";
    if (seconds < 86400) return "hace " + seconds / 3600 + " h";
    return "hace " + seconds / 86400 + " d";
  }

  static String number(double value) {
    return String.format(Locale.of("es", "ES"), "%.2f", value).replaceAll(",00$", "");
  }

  static String period(String period) {
    return "weekly".equals(period) ? "Semanal" : period;
  }

  public static ControlService control() {
    return MateuBeanProvider.getBean(ControlService.class);
  }

  public static CoordinatorService tasks() {
    return MateuBeanProvider.getBean(CoordinatorService.class);
  }

  public static BudgetService budgets() {
    return MateuBeanProvider.getBean(BudgetService.class);
  }

  public static int weeklyPercent(String account) {
    return budgets().accounts().stream().filter(a->account.equals(a.get("account")))
      .findFirst().map(a->(int)(Json.parse(a.get("shares").toString()).path("weekly").asLong(5000)/100))
      .orElse(50);
  }

  public static String actor() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }

  public static String reason(String reason) {
    String[] parts = reason.split(":", 2);
    String label =
        switch (parts[0]) {
          case "missing_active_window" -> "Falta una ventana vigente";
          case "missing_or_stale_usage" -> "Falta una lectura vigente";
          case "budget_exhausted" -> "Cuota agotada";
          case "insufficient_budget" -> "Saldo insuficiente";
          case "unreconciled_usage" -> "Hay consumo pendiente de conciliación";
          case "reservation_crossed_reset" -> "Una reserva cruzó un reinicio";
          case "human_pause" -> "Pausa del operador";
          default -> parts[0];
        };
    return label + (parts.length == 2 ? " (" + period(parts[1]).toLowerCase(Locale.ROOT) + ")" : "");
  }

  private static Object display(String key, Object value) {
    if (value == null)
      return key.equals("progress")
          ? "Desconocido"
          : key.equals("heartbeat") ? "Sin heartbeat" : "—";
    if (key.equals("progress") && value instanceof Number n && n.intValue()==0) return "Sin estimación";
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
                  if(row.get("spec")!=null) {
                    try { copy.put("title",Json.parse(row.get("spec").toString()).path("title").asText()); }
                    catch(Exception ignored) {}
                  }
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
