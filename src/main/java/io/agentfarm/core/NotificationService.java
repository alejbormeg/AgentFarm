package io.agentfarm.core;

import static io.agentfarm.core.Json.*;
import static io.agentfarm.core.Store.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Delivers the notification outbox to one owner-configured webhook. Messages carry titles and
 * states only: no proposal text, code, logs or credentials leave the control plane.
 */
@Service
public class NotificationService {
  private final Store db;
  private final String url, format, panel;
  private final Sender sender;

  @FunctionalInterface
  public interface Sender {
    int post(URI uri, String contentType, Map<String, String> headers, String body) throws Exception;
  }

  @org.springframework.beans.factory.annotation.Autowired
  public NotificationService(
      Store db,
      @Value("${agentfarm.notify-url:}") String url,
      @Value("${agentfarm.notify-format:json}") String format,
      @Value("${agentfarm.panel-url:http://localhost:8080}") String panel) {
    this(db, url, format, panel, NotificationService::http);
  }

  public NotificationService(Store db, String url, String format, String panel, Sender sender) {
    if (!Set.of("json", "slack", "ntfy").contains(format))
      throw new IllegalArgumentException("notify-format must be json, slack or ntfy");
    if (!url.isBlank()) {
      var uri = URI.create(url);
      boolean local = Set.of("localhost", "127.0.0.1").contains(uri.getHost());
      if (!"https".equals(uri.getScheme()) && !(local && "http".equals(uri.getScheme())))
        throw new IllegalArgumentException("Notification URL must use https (or http on localhost)");
    }
    this.db = db;
    this.url = url;
    this.format = format;
    this.panel = panel;
    this.sender = sender;
  }

  static int http(URI uri, String type, Map<String, String> headers, String body) throws Exception {
    var b =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", type)
            .POST(HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(b::header);
    return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  static String text(String state, String title) {
    return switch (state) {
          case "proposed" -> "Propuesta lista para aprobar: ";
          case "review" -> "PR lista para revisar e integrar: ";
          default -> "Tarea bloqueada: ";
        }
        + title;
  }

  @Scheduled(fixedDelay = 15000)
  public int deliver() {
    if (url.isBlank()) return 0;
    var pending =
        db.tx(c -> rows(c, "SELECT * FROM notifications WHERE sent=0 AND attempts<5 ORDER BY id LIMIT 20"));
    int sent = 0;
    for (var n : pending) {
      String message = text(n.get("state").toString(), n.get("title").toString());
      String link = panel + "/tasks";
      int status;
      try {
        status =
            switch (format) {
              case "slack" -> sender.post(URI.create(url), "application/json", Map.of(), encode(obj("text", message + " · " + link)));
              case "ntfy" -> sender.post(URI.create(url), "text/plain", Map.of("Title", "AgentFarm", "Click", link), message);
              default -> sender.post(URI.create(url), "application/json", Map.of(), encode(obj("event", "agentfarm.attention", "item", n.get("item"), "state", n.get("state"), "title", n.get("title"), "text", message, "url", link)));
            };
      } catch (Exception e) {
        status = 0;
      }
      boolean ok = status >= 200 && status < 300;
      if (ok) sent++;
      db.tx(
          c ->
              exec(
                  c,
                  "UPDATE notifications SET sent=?,attempts=attempts+1 WHERE id=?",
                  ok ? 1 : 0,
                  n.get("id")));
    }
    return sent;
  }
}
