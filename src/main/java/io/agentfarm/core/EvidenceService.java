package io.agentfarm.core;

import static io.agentfarm.core.Json.*;
import static io.agentfarm.core.Store.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Immutable acceptance scope and explicit human dispositions. Agent reports are unverified input.
 */
@Service
public class EvidenceService {
  /** Recipes whose candidates may be approved in a batch once every gate has passed. */
  static final Set<String> LOW_RISK =
      Set.of("dependency-update", "test-improvement", "project-memory");

  private final Store db;
  private final Clock clock;

  public EvidenceService(Store db, Clock clock) {
    this.db = db;
    this.clock = clock;
    db.tx(c -> {
      schema(c);
      return null;
    });
  }

  /** Other tasks in review on this repository that change the same files as this one. */
  static List<Map<String, Object>> overlaps(
      Connection c, Map<String, Object> item, Map<String, Object> verification) throws Exception {
    var result = new ArrayList<Map<String, Object>>();
    if (verification == null) return result;
    var mine = new HashSet<String>();
    parse(verification.get("detail").toString()).path("files").forEach(f -> mine.add(f.asText()));
    for (var other :
        rows(
            c,
            "SELECT w.id,w.title,w.pr,v.detail FROM work_items w JOIN evidence_verifications v"
                + " ON v.item=w.id WHERE w.repo=? AND w.state='review' AND w.id<>?",
            item.get("repo"),
            item.get("id"))) {
      var shared = new ArrayList<String>();
      parse(other.get("detail").toString())
          .path("files")
          .forEach(f -> {
            if (mine.contains(f.asText())) shared.add(f.asText());
          });
      if (!shared.isEmpty())
        result.add(
            obj(
                "item",
                other.get("id"),
                "title",
                other.get("title"),
                "pr",
                other.get("pr"),
                "files",
                shared));
    }
    return result;
  }

  static void schema(Connection c) throws Exception {
    statement(
        c,
        "CREATE TABLE IF NOT EXISTS acceptance_scopes(item TEXT NOT NULL REFERENCES"
            + " work_items(id),version INTEGER NOT NULL,hash TEXT NOT NULL,criteria TEXT NOT"
            + " NULL,base_sha TEXT NOT NULL,proposal TEXT NOT NULL,PRIMARY KEY(item,version))");
    statement(
        c,
        "CREATE TABLE IF NOT EXISTS evidence_decisions(id INTEGER PRIMARY KEY,request_id TEXT NOT"
            + " NULL UNIQUE,item TEXT NOT NULL REFERENCES work_items(id),version INTEGER NOT"
            + " NULL,sha TEXT NOT NULL,basis TEXT NOT NULL,criterion TEXT NOT NULL,kind TEXT NOT"
            + " NULL,reason TEXT NOT NULL,environment TEXT NOT NULL,actor TEXT NOT NULL,at INTEGER"
            + " NOT NULL,request_hash TEXT NOT NULL)");
    statement(
        c,
        "CREATE TABLE IF NOT EXISTS evidence_verifications(item TEXT PRIMARY KEY REFERENCES"
            + " work_items(id),basis TEXT NOT NULL,sha TEXT NOT NULL,state TEXT NOT NULL,reason"
            + " TEXT NOT NULL,detail TEXT NOT NULL,at INTEGER NOT NULL)");
  }

  public static JsonNode criteria(JsonNode proposal) {
    var entries = proposal.path("criteria");
    if (entries.isMissingNode()) {
      return MAPPER.valueToTree(
          List.of(
              obj("id", "AC-1", "description", text(proposal, "acceptance"), "method", "manual")));
    }
    if (!entries.isArray() || entries.isEmpty() || entries.size() > 50)
      throw new IllegalArgumentException("Provide 1–50 acceptance criteria");
    Set<String> ids = new HashSet<>();
    for (var entry : entries) {
      String id = text(entry, "id"),
          description = text(entry, "description"),
          method = text(entry, "method");
      if (!id.matches("AC-[1-9][0-9]{0,3}")
          || !ids.add(id)
          || description.length() > 4000
          || !Set.of("manual", "test").contains(method))
        throw new IllegalArgumentException("Invalid acceptance criterion");
      if (method.equals("test")) {
        var checks = entry.path("checks");
        if (!checks.isArray() || checks.isEmpty() || checks.size() > 2)
          throw new IllegalArgumentException("Test criterion requires unit/integration checks");
        Set<String> seen = new HashSet<>();
        for (var check : checks)
          if (!check.isTextual()
              || !Set.of("unit", "integration").contains(check.asText())
              || !seen.add(check.asText()))
            throw new IllegalArgumentException("Invalid criterion check");
        for (var check : checks)
          if (proposal.path(check.asText() + "_test").asText().startsWith("N/A:"))
            throw new IllegalArgumentException(
                "A test criterion cannot rely on a command marked N/A: " + id);
      }
    }
    return entries;
  }

  static Map<String, Object> scope(Connection c, Map<String, Object> item) throws Exception {
    var old =
        one(
            c,
            "SELECT * FROM acceptance_scopes WHERE item=? AND version=?",
            item.get("id"),
            item.get("version"));
    if (old != null) return old;
    throw new IllegalArgumentException(
        "Acceptance scope not captured; approve a proposal or explicitly import the legacy scope");
  }

  static void capture(Connection c, Map<String, Object> item) throws Exception {
    var proposal = parse(item.get("proposal").toString());
    var criteria = criteria(proposal);
    if (!text(proposal, "base_sha").matches("[a-f0-9]{40}"))
      throw new IllegalArgumentException("Full base commit SHA required for the acceptance scope");
    String hash = sha256(canonical(proposal));
    var old =
        one(
            c,
            "SELECT hash FROM acceptance_scopes WHERE item=? AND version=?",
            item.get("id"),
            item.get("version"));
    if (old != null) {
      if (!old.get("hash").equals(hash))
        throw new IllegalArgumentException("Approved proposal version changed");
      return;
    }
    exec(
        c,
        "INSERT INTO acceptance_scopes VALUES(?,?,?,?,?,?)",
        item.get("id"),
        item.get("version"),
        hash,
        encode(criteria),
        proposal.path("base_sha").asText(""),
        encode(proposal));
  }

  public Object importLegacy(String id, JsonNode n, String actor) {
    if (actor.startsWith("node:"))
      throw new IllegalArgumentException("Human scope import required");
    return db.tx(c -> {
      var item = item(c, id);
      checkVersion(item, n);
      if (!item.get("state").equals("review"))
        throw new IllegalArgumentException("Only review candidates can import legacy scope");
      capture(c, item);
      audit(c, actor, "evidence.scope.import", id);
      return dossier(c, item);
    });
  }

  static Map<String, Object> item(Connection c, String id) throws Exception {
    var item = one(c, "SELECT * FROM work_items WHERE id=?", id);
    if (item == null) throw new IllegalArgumentException("Unknown work item");
    return item;
  }

  private static void checkVersion(Map<String, Object> item, JsonNode n) {
    if (number(n, "version") != num(item, "version") || !text(n, "sha").equals(item.get("sha")))
      throw new IllegalArgumentException("Candidate changed; reload the dossier");
  }

  static String basis(Map<String, Object> item, Map<String, Object> scope) {
    return sha256(
        encode(
            obj(
                "item",
                item.get("id"),
                "version",
                item.get("version"),
                "sha",
                item.get("sha"),
                "scope",
                scope.get("hash"),
                "reports",
                parse(item.get("evidence").toString()))));
  }

  public Object decide(String id, JsonNode n, String actor) {
    if (actor.startsWith("node:")) throw new IllegalArgumentException("Human decision required");
    String request = text(n, "request_id"),
        criterion = text(n, "criterion"),
        kind = text(n, "kind"),
        reason = text(n, "reason"),
        environment = text(n, "environment");
    if (!request.matches("[A-Za-z0-9-]{8,80}")
        || !Set.of("manual", "exception", "reject").contains(kind)
        || reason.strip().length() < 20
        || reason.length() > 4000
        || environment.length() > 1000)
      throw new IllegalArgumentException(
          "Explicit decision, environment and meaningful evidence/reason required");
    String requestHash = sha256(canonical(n));
    return db.tx(c -> {
      var prior = one(c, "SELECT * FROM evidence_decisions WHERE request_id=?", request);
      if (prior != null) {
        if (!prior.get("request_hash").equals(requestHash)
            || !prior.get("item").equals(id)
            || !prior.get("actor").equals(actor))
          throw new IllegalArgumentException("Decision request id already used");
        return obj("state", "recorded", "decision", prior);
      }
      var item = item(c, id);
      checkVersion(item, n);
      if (!item.get("state").equals("review"))
        throw new IllegalArgumentException("Candidate must be in review");
      var scope = scope(c, item);
      if (!scope.get("hash").equals(sha256(canonical(parse(item.get("proposal").toString())))))
        throw new IllegalArgumentException("Approved proposal changed");
      String basis = basis(item, scope);
      if (!basis.equals(text(n, "basis")))
        throw new IllegalArgumentException("Evidence changed; inspect the current dossier");
      JsonNode match = null;
      for (var entry : parse(scope.get("criteria").toString()))
        if (entry.path("id").asText().equals(criterion)) match = entry;
      if (Commandments.EXCEPTABLE.contains(criterion)) {
        if (!kind.equals("exception"))
          throw new IllegalArgumentException("A commandment only admits a signed exception");
        match = MAPPER.valueToTree(obj("method", "test"));
      }
      if (match == null) throw new IllegalArgumentException("Unknown criterion");
      if (kind.equals("manual") && !match.path("method").asText().equals("manual"))
        throw new IllegalArgumentException(
            "This criterion requires independent tests; only an explicit justified exception"
                + " can replace them");
      exec(
          c,
          "INSERT INTO"
              + " evidence_decisions(request_id,item,version,sha,basis,criterion,kind,reason,environment,actor,at,request_hash)"
              + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
          request,
          id,
          item.get("version"),
          item.get("sha"),
          basis,
          criterion,
          kind,
          TelemetryService.clean(reason),
          TelemetryService.clean(environment),
          actor,
          clock.instant().getEpochSecond(),
          requestHash);
      audit(c, actor, "evidence." + kind, id + "/" + criterion + "@" + item.get("sha"));
      return dossier(c, item);
    });
  }

  public Object get(String id) {
    return db.tx(c -> dossier(c, item(c, id)));
  }

  public List<Map<String, Object>> inbox() {
    return db.tx(c -> {
      var list = new ArrayList<Map<String, Object>>();
      for (var item : rows(c, "SELECT * FROM work_items WHERE state='review' ORDER BY created"))
        list.add(dossier(c, item));
      return list;
    });
  }

  public static void requireReady(Connection c, Map<String, Object> item) throws Exception {
    var dossier = dossier(c, item);
    if (!Boolean.TRUE.equals(dossier.get("ready")))
      throw new IllegalArgumentException(
          "Acceptance evidence missing or stale; inspect the change dossier");
  }

  private static JsonNode sanitize(JsonNode value) {
    if (value.isTextual())
      return MAPPER.getNodeFactory().textNode(TelemetryService.clean(value.asText()));
    if (value.isObject()) {
      var copy = MAPPER.createObjectNode();
      value
          .properties()
          .forEach(entry ->
              copy.set(
                  entry.getKey(),
                  entry
                          .getKey()
                          .matches(
                              "(?i)(access_token|refresh_token|id_token|password|api_key|authorization|token)")
                      ? MAPPER.getNodeFactory().textNode("[oculto]")
                      : sanitize(entry.getValue())));
      return copy;
    }
    if (value.isArray()) {
      var copy = MAPPER.createArrayNode();
      value.forEach(entry -> copy.add(sanitize(entry)));
      return copy;
    }
    return value;
  }

  static Map<String, Object> dossier(Connection c, Map<String, Object> item) throws Exception {
    var scope =
        one(
            c,
            "SELECT * FROM acceptance_scopes WHERE item=? AND version=?",
            item.get("id"),
            item.get("version"));
    var history =
        rows(
            c,
            "SELECT id,request_id,version,sha,basis,criterion,kind,reason,environment,actor,at FROM"
                + " evidence_decisions WHERE item=? ORDER BY id",
            item.get("id"));
    var reports = parse(item.get("evidence").toString());
    boolean sameSha =
        item.get("sha") != null && reports.path("sha").asText().equals(item.get("sha"));
    var result =
        obj(
            "item",
            item.get("id"),
            "repository",
            item.get("repo"),
            "title",
            item.get("title"),
            "state",
            item.get("state"),
            "version",
            item.get("version"),
            "sha",
            item.get("sha"),
            "issue",
            item.get("issue"),
            "pr",
            item.get("pr"),
            "branch",
            item.get("branch"),
            "ready",
            false,
            "scope_missing",
            scope == null,
            "criteria",
            List.of(),
            "history",
            history,
            "agent_reports",
            obj(
                "trust",
                "declared_by_executor",
                "sha_matches",
                sameSha,
                "content",
                sanitize(reports)),
            "independent_verifier_available",
            false);
    if (scope == null) {
      result.put(
          "blockers", List.of("Import the legacy acceptance scope explicitly before reviewing"));
      return result;
    }
    String basis = basis(item, scope);
    result.put("basis", basis);
    var verification =
        one(
            c,
            "SELECT * FROM evidence_verifications WHERE item=? AND basis=?",
            item.get("id"),
            basis);
    result.put("independent_verifier_available", verification != null);
    result.put(
        "verification",
        verification == null
            ? obj("state", "missing")
            : obj(
                "state",
                verification.get("state"),
                "reason",
                verification.get("reason"),
                "at",
                verification.get("at"),
                "detail",
                parse(verification.get("detail").toString())));
    var approved = parse(scope.get("proposal").toString());
    boolean ciPassed =
        sameSha && verification != null && "success".equals(verification.get("state"));
    result.put("scope_hash", scope.get("hash"));
    result.put("base_sha", scope.get("base_sha"));
    result.put("approved_proposal", sanitize(parse(scope.get("proposal").toString())));
    boolean scopeMatches =
        scope.get("hash").equals(sha256(canonical(parse(item.get("proposal").toString()))));
    var criteria = new ArrayList<Map<String, Object>>();
    var blockers = new ArrayList<String>();
    int exceptions = 0, verified = 0;
    int total = parse(scope.get("criteria").toString()).size();
    for (var entry : parse(scope.get("criteria").toString())) {
      Map<String, Object> latest = null;
      for (var decision : history)
        if (decision.get("basis").equals(basis)
            && decision.get("criterion").equals(entry.path("id").asText())) latest = decision;
      String status = latest == null ? "missing" : latest.get("kind").toString();
      // A human rejection outranks the machines; a passing check needs no human sign-off.
      if (!status.equals("reject") && ciPassed && commandsPassed(entry, approved, reports)) {
        status = "verified";
        latest = null;
        verified++;
      }
      if (status.equals("exception")) exceptions++;
      if (status.equals("missing") || status.equals("reject"))
        blockers.add(
            entry.path("id").asText()
                + ": "
                + (status.equals("missing") ? "evidence missing" : "rejected"));
      criteria.add(
          obj(
              "id",
              entry.path("id").asText(),
              "description",
              entry.path("description").asText(),
              "method",
              entry.path("method").asText(),
              "checks",
              entry.path("checks"),
              "status",
              status,
              "decision",
              latest));
    }
    if (!scopeMatches) blockers.add("Approved scope changed");
    if (verification != null
        && parse(verification.get("detail").toString()).path("pull_conflict").asBoolean())
      blockers.add("La PR tiene conflictos con la rama base");
    result.put("overlaps", overlaps(c, item, verification));
    var recipe = one(c, "SELECT snapshot FROM work_recipes WHERE item=?", item.get("id"));
    var commandments =
        Commandments.assess(
            approved,
            reports,
            verification == null ? null : verification.get("state").toString(),
            verification == null ? null : verification.get("reason").toString(),
            verification == null
                ? MAPPER.createObjectNode()
                : parse(verification.get("detail").toString()),
            recipe == null ? "" : parse(recipe.get("snapshot").toString()).path("id").asText());
    for (var commandment : commandments) {
      if (!"failed".equals(commandment.get("status"))) continue;
      Map<String, Object> signed = null;
      for (var decision : history)
        if (decision.get("basis").equals(basis)
            && decision.get("criterion").equals(commandment.get("id"))) signed = decision;
      if (signed != null && Commandments.EXCEPTABLE.contains(commandment.get("id").toString())) {
        commandment.put("status", "exception");
        commandment.put("decision", signed);
        exceptions++;
      } else blockers.add(commandment.get("id") + ": " + commandment.get("detail"));
    }
    result.put("commandments", commandments);
    if (reports.path("untested_code").asBoolean())
      blockers.add("Code changed without unit and integration tests");
    if (item.get("sha") == null || !item.get("sha").toString().matches("[a-f0-9]{40}"))
      blockers.add("Candidate SHA missing");
    result.put("criteria", criteria);
    result.put("blockers", blockers);
    result.put("ready", blockers.isEmpty());
    result.put(
        "low_risk",
        blockers.isEmpty()
            && exceptions == 0
            && LOW_RISK.contains(
                recipe == null
                    ? ""
                    : parse(recipe.get("snapshot").toString()).path("id").asText()));
    result.put("exceptions", exceptions);
    result.put(
        "acceptance_state",
        !blockers.isEmpty()
            ? "needs_decision"
            : exceptions > 0
                ? "accepted_with_exceptions"
                : verified == total ? "verified" : "manually_validated");
    return result;
  }

  /** The executor ran each approved command, unaltered, in the disposable container. */
  private static boolean commandsPassed(JsonNode entry, JsonNode proposal, JsonNode reports) {
    if (!entry.path("method").asText().equals("test")) return false;
    for (var check : entry.path("checks")) {
      var report = reports.path(check.asText());
      if (!report.path("status").asText().equals("passed")
          || !report.path("sandbox").path("mode").asText().equals("container")
          || !report
              .path("command")
              .asText()
              .equals(proposal.path(check.asText() + "_test").asText())) return false;
    }
    return !entry.path("checks").isEmpty();
  }
}
