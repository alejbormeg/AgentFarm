package io.agentfarm.core;

import static io.agentfarm.core.Json.*;
import static io.agentfarm.core.Store.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * What maintaining a repository asks of its owner: a look at the week, and approving what is ready
 * in one go. Each approval still names its exact commit and passes the same gates as a single merge.
 */
@Service
public class DigestService {
  private static final long WEEK = 7 * 86400;
  private final Store db;
  private final Clock clock;
  private final WorkflowService work;

  public DigestService(Store db, Clock clock, WorkflowService work) {
    this.db = db;
    this.clock = clock;
    this.work = work;
  }

  public Map<String, Object> digest() {
    long since = clock.instant().getEpochSecond() - WEEK;
    return db.tx(
        c -> {
          var integrated = new ArrayList<Map<String, Object>>();
          var blocked = new ArrayList<Map<String, Object>>();
          var ready = new ArrayList<Map<String, Object>>();
          int waiting = 0, moving = 0;
          for (var item : rows(c, "SELECT * FROM work_items ORDER BY created DESC")) {
            String state = item.get("state").toString();
            var row = obj("id", item.get("id"), "title", item.get("title"), "repo", item.get("repo"));
            if (state.equals("integrated")
                && item.get("state_since") instanceof Number at
                && at.longValue() >= since) integrated.add(row);
            if (state.equals("blocked")) blocked.add(row);
            if (Set.of("queued", "approved", "integrating").contains(state)) moving++;
            if (state.equals("proposed")) waiting++;
            if (state.equals("review")) {
              waiting++;
              if (Boolean.TRUE.equals(EvidenceService.dossier(c, item).get("low_risk"))) {
                row.put("sha", item.get("sha"));
                row.put("pr", item.get("pr"));
                ready.add(row);
              }
            }
          }
          return obj(
              "days",
              7,
              "integrated",
              integrated,
              "blocked",
              blocked,
              "ready",
              ready,
              "waiting",
              waiting,
              "in_progress",
              moving);
        });
  }

  /** Merges what is still low risk at the exact commit asked for; one refusal doesn't stop the rest. */
  public Map<String, Object> approve(JsonNode items, String actor) {
    if (!items.isArray() || items.isEmpty() || items.size() > 20)
      throw new IllegalArgumentException("Approve between 1 and 20 tasks at once");
    var results = new ArrayList<Map<String, Object>>();
    for (var entry : items) {
      String id = text(entry, "id"), sha = text(entry, "sha");
      try {
        boolean low =
            db.tx(
                c ->
                    Boolean.TRUE.equals(
                        EvidenceService.dossier(c, EvidenceService.item(c, id)).get("low_risk")));
        if (!low) throw new IllegalArgumentException("Ya no es de bajo riesgo: revísala aparte");
        work.action(id, "merge", MAPPER.valueToTree(obj("sha", sha)), actor);
        results.add(obj("id", id, "state", "integrating"));
      } catch (IllegalArgumentException e) {
        results.add(obj("id", id, "state", "refused", "error", e.getMessage()));
      }
    }
    return obj("results", results);
  }

  /** One message a week through the notification outbox, and none for a quiet week. */
  @Scheduled(fixedDelay = 3600000)
  public void weekly() {
    long now = clock.instant().getEpochSecond();
    boolean sent =
        db.tx(
            c ->
                one(
                        c,
                        "SELECT 1 FROM notifications WHERE state='digest' AND created>? LIMIT 1",
                        now - WEEK)
                    != null);
    if (sent) return;
    var d = digest();
    int done = ((List<?>) d.get("integrated")).size(),
        stuck = ((List<?>) d.get("blocked")).size(),
        waiting = (Integer) d.get("waiting");
    if (done + stuck + waiting == 0) return;
    String title =
        done + " integradas · " + waiting + " esperan tu decisión · " + stuck + " bloqueadas";
    db.tx(
        c ->
            exec(
                c,
                "INSERT INTO notifications(item,state,title,created) VALUES('digest','digest',?,?)",
                title,
                now));
  }
}
