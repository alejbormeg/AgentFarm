package io.agentfarm.core;

import static io.agentfarm.core.Json.*;
import static io.agentfarm.core.Store.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class BudgetService {
  private final Store store;
  private final Clock clock;

  public BudgetService(Store store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  private long now() {
    return clock.instant().getEpochSecond();
  }

  private String id(JsonNode n, String key) {
    String v = text(n, key);
    if (!v.matches("[a-zA-Z0-9_-]{1,80}"))
      throw new IllegalArgumentException("Invalid identifier: " + key);
    return v;
  }

  private Map<String, Object> account(Connection c, String a) throws Exception {
    var row = one(c, "SELECT * FROM budget_accounts WHERE account=?", a);
    if (row == null) throw new IllegalArgumentException("Unknown budget account");
    return row;
  }

  public List<Map<String, Object>> accounts() {
    return store.tx(c -> rows(c, "SELECT * FROM budget_accounts ORDER BY account"));
  }

  public Map<String, Object> execute(String operation, JsonNode input, String actor) {
    if (input == null || !input.isObject() || input.has("now"))
      throw new IllegalArgumentException("Object required; clock cannot be overridden");
    return store.tx(c -> {
      String a = id(input, "account");
      Map<String, Object> result =
          switch (operation) {
            case "configure" -> configure(c, a, input);
            case "open-window" -> open(c, a, input);
            case "rollover" -> rollover(c, a, input);
            case "observe" -> observe(c, a, input);
            case "reserve" -> reserve(c, a, input);
            case "settle" -> settle(c, a, input);
            case "settle-observed" -> settleObserved(c, a, input);
            case "status" -> status(c, a);
            default -> throw new IllegalArgumentException("Unknown budget operation");
          };
      if (!operation.equals("status"))
        audit(
            c,
            actor,
            "budget." + operation,
            a + (input.has("run_id") ? "/" + id(input, "run_id") : ""));
      return result;
    });
  }

  private Map<String, Object> configure(Connection c, String a, JsonNode n) throws Exception {
    String mode = text(n, "mode");
    JsonNode shares = n.get("shares");
    if (!Set.of("api", "subscription").contains(mode)
        || shares == null
        || !shares.isObject()
        || shares.isEmpty()) throw new IllegalArgumentException("Invalid mode or shares");
    for (var e : shares.properties()) {
      if (!e.getKey().matches("[a-zA-Z0-9_-]{1,80}") || number(shares, e.getKey()) > 10000)
        throw new IllegalArgumentException("Shares must be 0..10000 basis points");
    }
    String encoded = canonical(shares);
    var prior = one(c, "SELECT * FROM budget_accounts WHERE account=?", a);
    if (prior != null) {
      if (!mode.equals(prior.get("mode")) || !parse((String) prior.get("shares")).equals(shares))
        throw new IllegalArgumentException("Existing account policy is immutable");
      return obj("state", "already_configured");
    }
    exec(c, "INSERT INTO budget_accounts VALUES(?,?,?)", a, mode, encoded);
    return obj("state", "configured", "unit", mode.equals("api") ? "usd_micros" : "basis_points");
  }

  private Map<String, Object> open(Connection c, String a, JsonNode n) throws Exception {
    var config = account(c, a);
    String period = id(n, "period");
    var shares = parse((String) config.get("shares"));
    if (!shares.has(period)) throw new IllegalArgumentException("Unknown policy period");
    long start = number(n, "start"),
        end = number(n, "end"),
        total = number(n, "total"),
        age = n.has("max_age") ? number(n, "max_age") : 300;
    String source = n.has("source") ? text(n, "source") : "manual";
    if (start >= end
        || total < 1
        || age < 1
        || age > 86400
        || !Set.of("manual", "provider").contains(source))
      throw new IllegalArgumentException("Invalid window");
    if (config.get("mode").equals("subscription") && total != 10000)
      throw new IllegalArgumentException("Subscription windows use 10000 basis points, never USD");
    boolean hasUsed = n.hasNonNull("used"), hasAt = n.hasNonNull("observed_at");
    if (hasUsed != hasAt)
      throw new IllegalArgumentException("Usage and timestamp must be supplied together");
    Long used = hasUsed ? number(n, "used") : null, at = hasAt ? number(n, "observed_at") : null;
    if (at != null && (at < start || at >= end || at > now()))
      throw new IllegalArgumentException("Invalid observation time");
    if (one(
            c,
            "SELECT 1 FROM budget_windows WHERE account=? AND period=? AND start<? AND end>?",
            a,
            period,
            end,
            start)
        != null) throw new IllegalArgumentException("Cannot replace or overlap an existing window");
    exec(
        c,
        "INSERT INTO budget_windows VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        a,
        period,
        start,
        end,
        total,
        shares.get(period).asLong(),
        used == null ? 0 : used,
        used,
        at,
        age,
        source);
    return obj("state", "window_opened");
  }

  private Map<String, Object> rollover(Connection c, String a, JsonNode n) throws Exception {
    if (!account(c, a).get("mode").equals("subscription") || !text(n, "source").equals("provider"))
      throw new IllegalArgumentException("Provider subscription window required");
    long start = number(n, "start"), end = number(n, "end");
    if (end - start != 604800 || start > now() || end <= now())
      throw new IllegalArgumentException("Invalid weekly provider window");
    if (one(c, "SELECT 1 FROM budget_runs WHERE account=? AND state IN ('reserved','unknown')", a)
        != null)
      throw new IllegalArgumentException(
          "Reconcile outstanding reservations before changing windows");
    var overlapping =
        rows(
            c,
            "SELECT * FROM budget_windows WHERE account=? AND period='weekly' AND end>?",
            a,
            start);
    for (var row : overlapping) {
      if (num(row, "start") == start && num(row, "end") == end) return obj("state", "already_open");
      if (num(row, "start") >= start
          || (row.get("observed_at") != null && num(row, "observed_at") >= start))
        throw new IllegalArgumentException(
            "Provider window conflicts with historical observations");
      exec(
          c,
          "UPDATE budget_windows SET end=? WHERE account=? AND period='weekly' AND start=?",
          start,
          a,
          row.get("start"));
    }
    return open(c, a, n);
  }

  private Map<String, Object> observe(Connection c, String a, JsonNode n) throws Exception {
    String period = id(n, "period");
    long start = number(n, "start"), used = number(n, "used"), at = number(n, "observed_at");
    var row =
        one(
            c,
            "SELECT * FROM budget_windows WHERE account=? AND period=? AND start=?",
            a,
            period,
            start);
    if (row == null || at < start || at >= num(row, "end") || at > now())
      throw new IllegalArgumentException("Observation outside window or in future");
    if ((row.get("observed_at") != null && at <= num(row, "observed_at"))
        || (row.get("observed_used") != null && used < num(row, "observed_used")))
      throw new IllegalArgumentException("Observation cannot decrease usage or time");
    exec(
        c,
        "UPDATE budget_windows SET used=max(used,?),observed_used=?,observed_at=? WHERE account=?"
            + " AND period=? AND start=?",
        used,
        used,
        at,
        a,
        period,
        start);
    return obj("state", "observed");
  }

  public Map<String, Object> status(String a) {
    return store.tx(c -> status(c, a));
  }

  private Map<String, Object> status(Connection c, String a) throws Exception {
    var config = account(c, a);
    List<String> reasons = new ArrayList<>();
    List<Map<String, Object>> windows = new ArrayList<>();
    if (one(
            c,
            "SELECT 1 FROM budget_runs WHERE account=? AND state IN"
                + " ('unknown','overrun','crossed_reset') LIMIT 1",
            a)
        != null) reasons.add("unreconciled_usage");
    if (one(
            c,
            "SELECT 1 FROM budget_allocations a JOIN budget_runs r USING(account,run_id) JOIN"
                + " budget_windows w USING(account,period,start) WHERE a.account=? AND"
                + " r.state='reserved' AND NOT(w.start<=? AND ?<w.end) LIMIT 1",
            a,
            now(),
            now())
        != null) reasons.add("reservation_crossed_reset");
    for (String period :
        parse((String) config.get("shares")).properties().stream()
            .map(Map.Entry::getKey)
            .sorted()
            .toList()) {
      var row =
          one(
              c,
              "SELECT * FROM budget_windows WHERE account=? AND period=? AND start<=? AND ?<end",
              a,
              period,
              now(),
              now());
      if (row == null) {
        reasons.add("missing_active_window:" + period);
        continue;
      }
      long held =
          num(
              one(
                  c,
                  "SELECT coalesce(sum(a.reserved),0) n FROM budget_allocations a JOIN budget_runs"
                      + " r USING(account,run_id) WHERE a.account=? AND a.period=? AND a.start=?"
                      + " AND r.state IN ('reserved','unknown')",
                  a,
                  period,
                  row.get("start")),
              "n");
      long cap = num(row, "total") * num(row, "share") / 10000,
          remaining = Math.max(0, cap - num(row, "used") - held);
      if (row.get("observed_at") == null
          || now() - num(row, "observed_at") < 0
          || now() - num(row, "observed_at") > num(row, "max_age"))
        reasons.add("missing_or_stale_usage:" + period);
      if (remaining == 0) reasons.add("budget_exhausted:" + period);
      windows.add(
          obj(
              "period",
              period,
              "start",
              row.get("start"),
              "end",
              row.get("end"),
              "cap",
              cap,
              "used",
              num(row, "used"),
              "reserved",
              held,
              "remaining",
              remaining,
              "observed_at",
              row.get("observed_at"),
              "source",
              row.get("source")));
    }
    return obj(
        "state",
        reasons.isEmpty() ? "available" : "paused",
        "account",
        a,
        "mode",
        config.get("mode"),
        "unit",
        config.get("mode").equals("api") ? "usd_micros" : "basis_points",
        "reasons",
        reasons,
        "windows",
        windows);
  }

  private String amounts(Connection c, String a, JsonNode n, long minimum) throws Exception {
    var config = account(c, a);
    var shares = parse((String) config.get("shares"));
    if (n == null
        || !n.isObject()
        || !n.properties().stream()
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet())
            .equals(
                shares.properties().stream()
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toSet())))
      throw new IllegalArgumentException("Amounts required for every configured window");
    Set<Long> values = new HashSet<>();
    for (var e : n.properties()) {
      long value = number(n, e.getKey());
      if (value < minimum)
        throw new IllegalArgumentException("Reservation amounts must be positive");
      values.add(value);
    }
    if (config.get("mode").equals("api") && values.size() != 1)
      throw new IllegalArgumentException("Same API cost must count against every window");
    return canonical(n);
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> reserve(Connection c, String a, JsonNode n) throws Exception {
    String run = id(n, "run_id");
    String encoded = amounts(c, a, n.get("amounts"), 1);
    var prior = one(c, "SELECT * FROM budget_runs WHERE account=? AND run_id=?", a, run);
    if (prior != null) {
      if (!canonical(parse((String) prior.get("amounts"))).equals(encoded))
        throw new IllegalArgumentException("Run ID has different reservation amounts");
      return obj("state", "duplicate", "run_id", run, "previous_state", prior.get("state"));
    }
    var status = status(c, a);
    var reasons = (List<String>) status.get("reasons");
    var windows = (List<Map<String, Object>>) status.get("windows");
    if (num(one(c, "SELECT paused FROM governance WHERE id=1"), "paused") == 1)
      reasons.add("human_pause");
    for (var w : windows)
      if (n.get("amounts").get((String) w.get("period")).asLong() > num(w, "remaining"))
        reasons.add("insufficient_budget:" + w.get("period"));
    if (!reasons.isEmpty()) {
      status.put("state", "paused");
      return status;
    }
    exec(c, "INSERT INTO budget_runs VALUES(?,?,?,?,NULL)", a, run, encoded, "reserved");
    for (var w : windows)
      exec(
          c,
          "INSERT INTO budget_allocations VALUES(?,?,?,?,?)",
          a,
          run,
          w.get("period"),
          w.get("start"),
          n.get("amounts").get((String) w.get("period")).asLong());
    return obj("state", "reserved", "run_id", run, "amounts", n.get("amounts"));
  }

  private Map<String, Object> settleObserved(Connection c, String a, JsonNode n) throws Exception {
    if (!account(c, a).get("mode").equals("subscription"))
      throw new IllegalArgumentException("Subscription provider observation required");
    String run = id(n, "run_id");
    var r = one(c, "SELECT * FROM budget_runs WHERE account=? AND run_id=?", a, run);
    if (r == null) throw new IllegalArgumentException("Unknown reservation");
    if (r.get("state").equals("reconciled")) return obj("state", "already_reconciled");
    if (!Set.of("reserved", "unknown").contains(r.get("state")))
      throw new IllegalArgumentException("Reservation already finalized");
    var windows =
        rows(
            c,
            "SELECT w.* FROM budget_allocations a JOIN budget_windows w USING(account,period,start) WHERE a.account=? AND a.run_id=?",
            a,
            run);
    var observations = new LinkedHashMap<String, Object>();
    for (var w : windows) {
      if (!w.get("source").equals("provider")
          || w.get("observed_at") == null
          || w.get("observed_used") == null
          || now() - num(w, "observed_at") < 0
          || now() - num(w, "observed_at") > 60
          || now() >= num(w, "end"))
        throw new IllegalArgumentException(
            "Fresh observation from the reservation window required");
      observations.put(
          w.get("period").toString(),
          obj("cumulative_used", w.get("observed_used"), "observed_at", w.get("observed_at")));
    }
    // Subscription counters already include this execution. Never add their delta a second time.
    exec(
        c,
        "UPDATE budget_runs SET state='reconciled',actual=? WHERE account=? AND run_id=?",
        encode(obj("source", "provider-cumulative", "windows", observations)),
        a,
        run);
    return obj("state", "reconciled", "usage", observations);
  }

  public Object reservations(String a) {
    return store.tx(c ->
        rows(
            c,
            "SELECT run_id,state,amounts,actual FROM budget_runs WHERE account=? ORDER BY rowid",
            a));
  }

  private Map<String, Object> settle(Connection c, String a, JsonNode n) throws Exception {
    String run = id(n, "run_id");
    var row = one(c, "SELECT * FROM budget_runs WHERE account=? AND run_id=?", a, run);
    if (row == null) throw new IllegalArgumentException("Unknown reservation");
    JsonNode actual = n.get("actual");
    boolean unknown = actual == null || actual.isNull();
    String encoded = unknown ? null : amounts(c, a, actual, 0);
    if (Set.of("settled", "overrun", "crossed_reset").contains(row.get("state"))) {
      if (!Objects.equals(
          row.get("actual") == null ? null : canonical(parse((String) row.get("actual"))), encoded))
        throw new IllegalArgumentException("Finalized usage cannot be rewritten");
      return obj("state", "already_settled");
    }
    if (unknown) {
      exec(c, "UPDATE budget_runs SET state='unknown' WHERE account=? AND run_id=?", a, run);
      return obj("state", "unknown", "reason", "usage_required_before_more_work");
    }
    var requested = parse((String) row.get("amounts"));
    boolean overrun =
        requested.properties().stream()
            .anyMatch(e -> actual.get(e.getKey()).asLong() > e.getValue().asLong());
    boolean crossed =
        one(
                c,
                "SELECT 1 FROM budget_allocations a JOIN budget_windows w"
                    + " USING(account,period,start) WHERE a.account=? AND a.run_id=? AND w.end<=?"
                    + " LIMIT 1",
                a,
                run,
                now())
            != null;
    for (var allocation :
        rows(c, "SELECT * FROM budget_allocations WHERE account=? AND run_id=?", a, run))
      exec(
          c,
          "UPDATE budget_windows SET used=used+? WHERE account=? AND period=? AND start=?",
          actual.get((String) allocation.get("period")).asLong(),
          a,
          allocation.get("period"),
          allocation.get("start"));
    String state = overrun ? "overrun" : crossed ? "crossed_reset" : "settled";
    exec(
        c,
        "UPDATE budget_runs SET state=?,actual=? WHERE account=? AND run_id=?",
        state,
        encoded,
        a,
        run);
    return obj("state", state);
  }
}
