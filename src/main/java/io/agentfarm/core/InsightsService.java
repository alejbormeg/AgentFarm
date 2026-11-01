package io.agentfarm.core;

import static io.agentfarm.core.Json.*;
import static io.agentfarm.core.Store.*;

import java.time.*;
import java.time.temporal.IsoFields;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Outcome metrics derived from the audit trail and the work items. No estimates: every number is
 * a count or a duration between two recorded events.
 */
@Service
public class InsightsService {
  private final Store db;
  private final Clock clock;

  public InsightsService(Store db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  static String week(Instant at) {
    var d = LocalDate.ofInstant(at, ZoneOffset.UTC);
    return d.get(IsoFields.WEEK_BASED_YEAR)
        + "-W"
        + String.format("%02d", d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
  }

  /**
   * Observed cost of finished runs per recipe and command kind (research, implement…), from
   * settled Clover reservations. Used to show an expected range before approving a proposal.
   */
  public Map<String, Object> costs() {
    return db.tx(
        c -> {
          var samples = new TreeMap<String, List<Long>>();
          var units = new HashMap<String, String>();
          for (var r :
              rows(
                  c,
                  "SELECT b.run_id, b.actual, a.mode FROM budget_runs b JOIN budget_accounts a"
                      + " USING(account) WHERE b.actual IS NOT NULL AND b.state IN"
                      + " ('settled','overrun','reconciled')")) {
            String run = r.get("run_id").toString();
            String command =
                run.startsWith("work-")
                    ? run.substring(5)
                    : run.startsWith("claude-") ? run.substring(7, Math.min(run.length(), 43)) : null;
            if (command == null) continue;
            var cmd =
                one(
                    c,
                    "SELECT w.kind, COALESCE(json_extract(r.snapshot,'$.id'),'libre') recipe FROM"
                        + " work_commands w LEFT JOIN work_recipes r ON r.item=w.item WHERE w.id=?",
                    command);
            if (cmd == null) continue;
            long value = 0;
            for (var v : parse(r.get("actual").toString())) value = Math.max(value, v.asLong());
            String key = cmd.get("recipe") + "/" + cmd.get("kind") + "/" + r.get("mode");
            samples.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            units.put(key, r.get("mode").equals("api") ? "usd_micros" : "basis_points");
          }
          var out = new TreeMap<String, Object>();
          for (var e : samples.entrySet()) {
            var v = e.getValue();
            Collections.sort(v);
            String[] k = e.getKey().split("/");
            out.put(
                e.getKey(),
                obj(
                    "recipe", k[0],
                    "kind", k[1],
                    "unit", units.get(e.getKey()),
                    "runs", v.size(),
                    "median", v.get(v.size() / 2),
                    "p90", v.get(Math.min(v.size() - 1, (int) Math.ceil(v.size() * 0.9) - 1))));
          }
          return out;
        });
  }

  public Map<String, Object> metrics(int weeks) {
    if (weeks < 1 || weeks > 26) throw new IllegalArgumentException("weeks must be 1-26");
    Instant since = clock.instant().minus(Duration.ofDays(7L * weeks));
    return db.tx(
        c -> {
          var series = new TreeMap<String, Map<String, Object>>();
          for (int i = weeks - 1; i >= 0; i--)
            series.put(
                week(clock.instant().minus(Duration.ofDays(7L * i))),
                new LinkedHashMap<>(
                    obj(
                        "proposals", 0, "approved", 0, "auto_approved", 0, "corrections", 0,
                        "integrated", 0, "blocked", 0)));
          var integratedAt = new HashMap<String, Instant>();
          for (var a :
              rows(
                  c,
                  "SELECT at,actor,action,subject FROM audit WHERE action LIKE 'work.%' AND at>=?"
                      + " ORDER BY id",
                  since.toString())) {
            Instant at = Instant.parse(a.get("at").toString());
            var bucket = series.get(week(at));
            if (bucket == null) continue;
            String action = a.get("action").toString(), actor = a.get("actor").toString();
            String key =
                switch (action) {
                  case "work.proposed" -> "proposals";
                  case "work.implement" ->
                      actor.startsWith("policy:") ? "auto_approved" : "approved";
                  case "work.correct" -> "corrections";
                  case "work.integrated" -> "integrated";
                  case "work.blocked" -> "blocked";
                  default -> null;
                };
            if (key != null) bucket.merge(key, 1, (x, y) -> (Integer) x + (Integer) y);
            if (action.equals("work.integrated")) integratedAt.put(a.get("subject").toString().split(" ")[0], at);
          }
          var cycle = new ArrayList<Long>();
          for (var e : integratedAt.entrySet()) {
            var item = one(c, "SELECT created FROM work_items WHERE id=?", e.getKey());
            if (item != null) cycle.add(e.getValue().getEpochSecond() - num(item, "created"));
          }
          Collections.sort(cycle);
          long proposals = 0, approved = 0, corrections = 0, integrated = 0;
          for (var b : series.values()) {
            proposals += (Integer) b.get("proposals");
            approved += (Integer) b.get("approved") + (Integer) b.get("auto_approved");
            corrections += (Integer) b.get("corrections");
            integrated += (Integer) b.get("integrated");
          }
          var spend = new ArrayList<Object>();
          for (var r :
              rows(
                  c,
                  "SELECT b.account, a.mode, b.actual FROM budget_runs b JOIN budget_accounts a"
                      + " USING(account) WHERE b.actual IS NOT NULL")) {
            spend.add(obj("account", r.get("account"), "mode", r.get("mode"), "actual", parse(r.get("actual").toString())));
          }
          var cost = new TreeMap<String, Object>();
          for (var s : spend) {
            var m = MAPPER.valueToTree(s);
            long total = 0;
            for (var v : m.path("actual")) total = Math.max(total, v.asLong());
            String account = m.path("account").asText();
            var entry =
                (Map<String, Object>)
                    cost.computeIfAbsent(
                        account, k -> new LinkedHashMap<>(obj("unit", m.path("mode").asText().equals("api") ? "usd_micros" : "basis_points", "runs", 0L, "total", 0L)));
            entry.put("runs", (Long) entry.get("runs") + 1);
            entry.put("total", (Long) entry.get("total") + total);
          }
          return obj(
              "weeks",
              series,
              "totals",
              obj(
                  "proposals",
                  proposals,
                  "approval_rate",
                  proposals == 0 ? null : (double) approved / proposals,
                  "corrections",
                  corrections,
                  "integrated",
                  integrated,
                  "median_cycle_hours",
                  cycle.isEmpty() ? null : cycle.get(cycle.size() / 2) / 3600.0),
              "cost",
              cost);
        });
  }
}
