package io.agentfarm.core;

import static io.agentfarm.core.Json.*;
import static io.agentfarm.core.Store.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Checks candidates in review from outside the executor: the panel reads GitHub's CI and the
 * changed files for the exact commit with its own credentials, so an executor report alone never
 * closes a criterion. It proves the approved commands passed, not that the tests are any good.
 */
@Service
public class EvidenceVerifier {
  private final Store db;
  private final Clock clock;
  private final GitHubReader.Source github;
  private final java.util.function.BooleanSupplier configured;

  @Autowired
  public EvidenceVerifier(Store db, Clock clock, GitHubReader reader) {
    this(db, clock, reader::get, reader::configured);
  }

  public EvidenceVerifier(
      Store db,
      Clock clock,
      GitHubReader.Source github,
      java.util.function.BooleanSupplier configured) {
    this.db = db;
    this.clock = clock;
    this.github = github;
    this.configured = configured;
  }

  public record Assessment(String state, String reason, Map<String, Object> detail) {}

  @Scheduled(fixedDelay = 30000)
  public void tick() {
    if (!configured.getAsBoolean()) return;
    for (var item :
        db.tx(c -> rows(c, "SELECT * FROM work_items WHERE state='review' AND sha IS NOT NULL")))
      verify(item.get("id").toString());
  }

  /** Reads GitHub outside any transaction and stores the outcome only if the candidate held. */
  public void verify(String id) {
    var snapshot =
        db.tx(
            c -> {
              var item = EvidenceService.item(c, id);
              var scope =
                  one(
                      c,
                      "SELECT * FROM acceptance_scopes WHERE item=? AND version=?",
                      id,
                      item.get("version"));
              if (scope == null || item.get("sha") == null) return null;
              String basis = EvidenceService.basis(item, scope);
              var row = one(c, "SELECT * FROM evidence_verifications WHERE item=?", id);
              if (row != null && basis.equals(row.get("basis")) && "success".equals(row.get("state")))
                return null;
              return Map.of("item", item, "scope", scope, "basis", basis);
            });
    if (snapshot == null) return;
    @SuppressWarnings("unchecked")
    var item = (Map<String, Object>) snapshot.get("item");
    @SuppressWarnings("unchecked")
    var scope = (Map<String, Object>) snapshot.get("scope");
    String repo = item.get("repo").toString(), sha = item.get("sha").toString();
    Assessment result;
    try {
      result =
          assess(
              parse(item.get("evidence").toString()),
              github.get(repo, "/commits/" + sha + "/check-runs?per_page=100"),
              github.get(repo, "/commits/" + sha + "/status"),
              github.get(repo, "/compare/" + scope.get("base_sha") + "..." + sha));
    } catch (Exception e) {
      result = new Assessment("unavailable", "No se pudo leer GitHub: " + e.getMessage(), Map.of());
    }
    var outcome = result;
    db.tx(
        c -> {
          var now = EvidenceService.item(c, id);
          var held = one(c, "SELECT * FROM acceptance_scopes WHERE item=? AND version=?", id, now.get("version"));
          if (held == null || !EvidenceService.basis(now, held).equals(snapshot.get("basis")))
            return null;
          exec(
              c,
              "INSERT OR REPLACE INTO evidence_verifications(item,basis,sha,state,reason,detail,at)"
                  + " VALUES(?,?,?,?,?,?,?)",
              id,
              snapshot.get("basis"),
              sha,
              outcome.state(),
              outcome.reason(),
              encode(outcome.detail()),
              clock.instant().getEpochSecond());
          return null;
        });
  }

  /** Pure decision over GitHub's answers: pending until CI ends, then success or failed. */
  public static Assessment assess(
      JsonNode evidence, JsonNode checks, JsonNode statuses, JsonNode compare) {
    var files = new ArrayList<String>();
    compare.path("files").forEach(f -> files.add(f.path("filename").asText()));
    var runs = new ArrayList<Map<String, Object>>();
    checks
        .path("check_runs")
        .forEach(
            r ->
                runs.add(
                    obj(
                        "name",
                        r.path("name").asText(),
                        "status",
                        r.path("status").asText(),
                        "conclusion",
                        r.path("conclusion").asText())));
    var added = new ArrayList<String>();
    var secrets = new ArrayList<String>();
    compare
        .path("files")
        .forEach(
            f -> {
              if (f.path("status").asText().equals("added")) added.add(f.path("filename").asText());
              secrets.addAll(
                  SecretScan.findings(f.path("filename").asText(), f.path("patch").asText()));
            });
    var detail = obj("checks", runs, "files", files, "added", added, "secrets", secrets);
    var required = evidence.path("ci_checks");
    if (!required.isArray() || required.isEmpty())
      return new Assessment("failed", "El informe no vincula ninguna comprobación de CI.", detail);
    if (checks.path("total_count").asInt() > 100 || files.size() >= 300)
      return new Assessment("failed", "Demasiados resultados para comprobarlos enteros.", detail);
    if (runs.isEmpty()) return new Assessment("pending", "La CI aún no ha empezado.", detail);
    for (var run : runs)
      if (!"completed".equals(run.get("status")))
        return new Assessment("pending", "La CI sigue en marcha.", detail);
    if ("pending".equals(statuses.path("state").asText())
        && statuses.path("total_count").asInt() > 0)
      return new Assessment("pending", "Hay estados de commit pendientes.", detail);
    var failed =
        runs.stream()
            .filter(r -> !Set.of("success", "neutral", "skipped").contains(r.get("conclusion")))
            .map(r -> r.get("name").toString())
            .toList();
    if (!failed.isEmpty())
      return new Assessment("failed", "Falló en CI: " + String.join(", ", failed) + ".", detail);
    if (statuses.path("total_count").asInt() > 0
        && !statuses.path("state").asText().equals("success"))
      return new Assessment("failed", "Un estado de commit no es correcto.", detail);
    for (var expected : required) {
      boolean found =
          runs.stream()
              .anyMatch(
                  r ->
                      "success".equals(r.get("conclusion"))
                          && (expected.asText().equals("*")
                              || expected.asText().equals(r.get("name"))));
      if (!found)
        return new Assessment("failed", "Falta en CI: " + expected.asText() + ".", detail);
    }
    var workflows =
        files.stream()
            .filter(f -> f.startsWith(".github/workflows/"))
            .filter(f -> !f.equals(".github/workflows/agentfarm.yml"))
            .toList();
    if (!workflows.isEmpty())
      return new Assessment(
          "failed", "El candidato cambia la propia CI: " + String.join(", ", workflows) + ".", detail);
    if (!secrets.isEmpty())
      return new Assessment("failed", "Posible secreto en el diff.", detail);
    if (TestPolicy.untestedCode(
        files,
        evidence.path("unit").path("status").asText(),
        evidence.path("integration").path("status").asText()))
      return new Assessment(
          "failed", "Cambia código sin pruebas unitarias y de integración.", detail);
    return new Assessment("success", "La CI del commit pasó entera.", detail);
  }
}
