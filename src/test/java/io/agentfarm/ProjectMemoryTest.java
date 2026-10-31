package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static io.agentfarm.core.Store.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ProjectMemoryTest {
  static final String REPO = "owner/repo", SHA = "a".repeat(40);
  @TempDir Path dir;

  JsonNode document() {
    return MAPPER.valueToTree(
        obj(
            "version",
            1,
            "repository",
            REPO,
            "entries",
            List.of(
                obj(
                    "id",
                    "ADR-1",
                    "kind",
                    "decision",
                    "summary",
                    "Keep the interface",
                    "rationale",
                    "Existing consumers require compatibility"))));
  }

  JsonNode snapshot() {
    return ProjectMemory.snapshot(REPO, SHA, encode(document()));
  }

  @Test
  void snapshotIsCanonicalAndBindsSourceRepositoryAndCommit() {
    var first = snapshot();
    ProjectMemory.validate(first, REPO);
    assertEquals(sha256(canonical(document())), first.path("hash").asText());
    assertEquals(SHA, first.path("base_sha").asText());
    var reordered =
        "{\"entries\":"
            + encode(document().path("entries"))
            + ",\"repository\":\"owner/repo\",\"version\":1}";
    assertEquals(first, ProjectMemory.snapshot(REPO, SHA, reordered));
    assertThrows(IllegalArgumentException.class, () -> ProjectMemory.validate(first, "other/repo"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectMemory.snapshot(REPO, "main", encode(document())));
  }

  @Test
  void appearanceChangeAndRemovalRequireNewProposalButUnrelatedCommitsDoNot() {
    var missing = ProjectMemory.absent(REPO, SHA);
    assertDoesNotThrow(() -> ProjectMemory.requireUnchanged(null, missing));
    assertThrows(
        IllegalArgumentException.class, () -> ProjectMemory.requireUnchanged(null, snapshot()));
    assertThrows(
        IllegalArgumentException.class, () -> ProjectMemory.requireUnchanged(missing, snapshot()));
    assertThrows(
        IllegalArgumentException.class, () -> ProjectMemory.requireUnchanged(snapshot(), missing));
    var next = ProjectMemory.snapshot(REPO, "b".repeat(40), encode(document()));
    assertDoesNotThrow(() -> ProjectMemory.requireUnchanged(snapshot(), next));
    var changed = document();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("entries").get(0))
        .put("rationale", "Changed reason");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProjectMemory.requireUnchanged(
                snapshot(), ProjectMemory.snapshot(REPO, SHA, encode(changed))));
  }

  @Test
  void rejectsMalformedAmbiguousOrOversizedDocuments() {
    for (var raw :
        List.of(
            "",
            "null",
            "{}",
            encode(document()) + " {}",
            encode(document()).replace("\"version\":1", "\"version\":1,\"version\":1"),
            " ".repeat(65537))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ProjectMemory.snapshot(REPO, SHA, raw),
          raw.length() > 200 ? "oversized" : raw);
    }
    var changed = (com.fasterxml.jackson.databind.node.ObjectNode) document();
    changed.put("commands", "run arbitrary shell");
    assertThrows(
        IllegalArgumentException.class, () -> ProjectMemory.snapshot(REPO, SHA, encode(changed)));
  }

  @Test
  void rejectsDuplicateIdsUnknownKindsAndInvalidReferences() {
    var doc = document();
    var entries = (com.fasterxml.jackson.databind.node.ArrayNode) doc.path("entries");
    entries.add(entries.get(0).deepCopy());
    assertThrows(
        IllegalArgumentException.class, () -> ProjectMemory.snapshot(REPO, SHA, encode(doc)));
    var single = document();
    var entry = (com.fasterxml.jackson.databind.node.ObjectNode) single.path("entries").get(0);
    entry.put("kind", "approval");
    assertThrows(
        IllegalArgumentException.class, () -> ProjectMemory.snapshot(REPO, SHA, encode(single)));
    entry.put("kind", "rejection");
    entry.set("references", MAPPER.valueToTree(List.of("javascript:alert(1)")));
    assertThrows(
        IllegalArgumentException.class, () -> ProjectMemory.snapshot(REPO, SHA, encode(single)));
  }

  @Test
  void refusesTamperedSnapshotsAndExplicitlyRepresentsAbsence() {
    var changed = (com.fasterxml.jackson.databind.node.ObjectNode) snapshot();
    changed.put("hash", "f".repeat(64));
    assertThrows(IllegalArgumentException.class, () -> ProjectMemory.validate(changed, REPO));
    var missing = ProjectMemory.absent(REPO, SHA);
    assertFalse(missing.path("present").asBoolean());
    ProjectMemory.validate(missing, REPO);
  }

  @Test
  void proposalApprovalPersistsExactMemoryAndRejectsInvalidReportAtomically() {
    var db = new Store(dir.resolve("db").toString());
    var work = new WorkflowService(db, Clock.systemUTC());
    work.create(
        MAPPER.valueToTree(
            obj(
                "request_id",
                "memory-task-1",
                "repository",
                REPO,
                "title",
                "Task",
                "request",
                "Investigate")),
        "owner");
    var claim = MAPPER.valueToTree(work.claim("worker", false));
    String command = claim.path("command").path("id").asText();
    var p =
        MAPPER.valueToTree(
            obj(
                "summary",
                "Change",
                "acceptance",
                "Check",
                "as_is",
                "flowchart TD; A-->B",
                "to_be",
                "flowchart TD; A-->C",
                "unit_test",
                "N/A: documentation only",
                "integration_test",
                "N/A: documentation only",
                "base_sha",
                SHA,
                "project_memory",
                snapshot()));
    var invalid = p.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("project_memory"))
        .put("hash", "invalid");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            work.report(
                command,
                MAPPER.valueToTree(obj("state", "proposed", "proposal", invalid)),
                "worker"));
    work.report(command, MAPPER.valueToTree(obj("state", "proposed", "proposal", p)), "worker");
    work.action("memory-task-1", "implement", MAPPER.valueToTree(obj("version", 1)), "owner");
    var saved =
        db.tx(c -> one(c, "SELECT proposal FROM acceptance_scopes WHERE item=?", "memory-task-1"));
    assertEquals(snapshot(), parse(saved.get("proposal").toString()).path("project_memory"));
  }
}
