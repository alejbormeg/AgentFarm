package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.adapters.*;
import io.agentfarm.core.Store;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SkillIndexTest {
  @TempDir Path dir;
  SkillIndex catalog;

  @BeforeEach
  void setup() {
    catalog = new SkillIndex(new Store(dir.resolve("db").toString()), Clock.systemUTC());
  }

  static SkillIndex.Entry entry(String repo, String path, String name, String description) {
    return new SkillIndex.Entry(
        repo,
        path,
        name,
        description,
        SkillTopics.tagsFor(name + " " + description),
        "sha-" + path,
        10);
  }

  /** A small catalogue: coding, design, documents and something unrelated. */
  void seed() {
    long anthropic = ((Number) catalog.sources().get(0).get("id")).longValue();
    catalog.apply(
        anthropic,
        50,
        List.of(
            entry(
                "anthropics/skills",
                "skills/python-testing/SKILL.md",
                "python-testing",
                "Write pytest tests and improve Python code quality"),
            entry(
                "anthropics/skills",
                "skills/frontend-design/SKILL.md",
                "frontend-design",
                "Create distinctive production-grade UI with careful typography and layout"),
            entry(
                "anthropics/skills",
                "skills/pdf/SKILL.md",
                "pdf",
                "Read, create and edit PDF documents and forms"),
            entry(
                "anthropics/skills",
                "skills/bees/SKILL.md",
                "bees",
                "Everything about keeping honey bees")),
        Set.of(
            "skills/python-testing/SKILL.md",
            "skills/frontend-design/SKILL.md",
            "skills/pdf/SKILL.md",
            "skills/bees/SKILL.md"));
  }

  @Test
  void aSpanishRequestFindsEnglishSkillsByTopic() {
    seed();
    var programming = catalog.search("quiero programar en python", null, 10);
    assertEquals("python-testing", programming.results().get(0).get("name"));
    assertTrue(
        programming.understood().stream().anyMatch(t -> t.get("id").equals("programming")),
        "it says what it understood");
    var design = catalog.search("diseño gráfico", null, 10);
    assertEquals("frontend-design", design.results().get(0).get("name"));
    var documents = catalog.search("necesito algo para documentos pdf", null, 10);
    assertEquals("pdf", documents.results().get(0).get("name"));
    assertTrue(
        catalog.search("bees", null, 10).results().stream()
            .anyMatch(r -> r.get("name").equals("bees")),
        "plain words still match");
    assertTrue(
        catalog.search("programar", null, 10).results().stream()
            .noneMatch(r -> r.get("name").equals("bees")),
        "unrelated skills stay out");
  }

  @Test
  void aNameThatSaysTheTopicBeatsAMention() {
    long id = ((Number) catalog.sources().get(0).get("id")).longValue();
    catalog.apply(
        id,
        0,
        List.of(
            entry(
                "o/r",
                "skills/gh-address-comments/SKILL.md",
                "gh-address-comments",
                "Help address review comments on the PR; verify gh auth first and prompt the user to authenticate if not logged in"),
            entry(
                "o/r",
                "skills/security-threat-model/SKILL.md",
                "security-threat-model",
                "Threat modeling for repositories"),
            entry("o/r", "skills/pdf/SKILL.md", "pdf", "Work with PDF files")),
        Set.of(
            "skills/gh-address-comments/SKILL.md",
            "skills/security-threat-model/SKILL.md",
            "skills/pdf/SKILL.md"));
    assertEquals(
        "security-threat-model", catalog.search("seguridad", null, 5).results().get(0).get("name"));
  }

  @Test
  void graphicDesignIsNotDataAndVerbFormsStillMatch() {
    var topics = SkillTopics.detect("diseño gráfico").stream().map(SkillTopics.Topic::id).toList();
    assertTrue(topics.contains("design"));
    assertFalse(topics.contains("data"), "a graphic is not a data analysis");
    assertTrue(
        SkillTopics.detect("programando").stream().anyMatch(t -> t.id().equals("programming")));
  }

  @Test
  void searchTextNeverReachesFtsAsSyntax() {
    seed();
    for (String hostile :
        new String[] {
          "\"python\" OR *",
          "python AND NOT",
          "NEAR(python design)",
          "python*",
          "a\" ; DROP TABLE skill_catalog; --",
          "name:python",
          "(((",
          "\u0000python"
        }) assertDoesNotThrow(() -> catalog.search(hostile, null, 10), hostile);
    assertEquals(4, catalog.size(), "nothing was dropped");
    assertTrue(catalog.search("   ", null, 10).results().isEmpty());
    assertThrows(IllegalArgumentException.class, () -> catalog.search("x".repeat(201), null, 10));
  }

  @Test
  void vanishedSkillsAreRemovedAndChangedOnesReplaced() {
    seed();
    long id = ((Number) catalog.sources().get(0).get("id")).longValue();
    catalog.apply(
        id,
        60,
        List.of(
            entry(
                "anthropics/skills", "skills/pdf/SKILL.md", "pdf", "Now about spreadsheets only")),
        Set.of("skills/pdf/SKILL.md"));
    assertEquals(1, catalog.size());
    assertTrue(
        catalog.search("bees", null, 10).results().isEmpty(),
        "a removed skill is not found any more");
    assertEquals(
        "Now about spreadsheets only",
        catalog.search("pdf", null, 10).results().get(0).get("description"));
  }

  @Test
  void sourcesAreBoundedValidatedAndOfficialOnesStay() {
    var added = catalog.addSource("o/skills-pack", "pack", "owner");
    assertEquals("custom", added.get("kind"));
    assertThrows(
        IllegalArgumentException.class,
        () -> catalog.addSource("o/skills-pack", "pack", "owner"),
        "no duplicates");
    assertThrows(IllegalArgumentException.class, () -> catalog.addSource("nope", "", "owner"));
    assertThrows(IllegalArgumentException.class, () -> catalog.addSource("o/r", "../x", "owner"));
    assertThrows(IllegalArgumentException.class, () -> catalog.addSource("o/r2", "", "node:evil"));
    long official =
        ((Number)
                catalog.sources().stream()
                    .filter(s -> s.get("kind").equals("official"))
                    .findFirst()
                    .get()
                    .get("id"))
            .longValue();
    assertThrows(IllegalArgumentException.class, () -> catalog.removeSource(official, "owner"));
    catalog.removeSource(((Number) added.get("id")).longValue(), "owner");
    assertEquals(2, catalog.sources().size());
  }

  @Test
  void frontmatterAndPlainTextBothDescribeASkill() {
    var fm =
        SkillMeta.parse(
            "---\nname: casa\ndescription: Hace cosas\n  en varias líneas\nallowed-tools: Read\n---\n# Otro título\ntexto",
            "folder");
    assertEquals("casa", fm.name());
    assertEquals("Hace cosas en varias líneas", fm.description());
    var plain =
        SkillMeta.parse(
            "# Mi skill\n\nPrimera frase útil.\nSegunda línea.\n\nOtro párrafo.", "folder");
    assertEquals("Mi skill", plain.name());
    assertEquals("Primera frase útil. Segunda línea.", plain.description());
    var broken = SkillMeta.parse("---\nname: [sin cerrar\n: :\n---\n# Título\nTexto", "folder");
    assertEquals("Título", broken.name());
    var evil =
        SkillMeta.parse(
            "---\nname: !!java.lang.Runtime x\ndescription: &a [*a]\n---\ncuerpo", "folder");
    assertNotNull(evil.name(), "hostile YAML falls back instead of failing");
    assertEquals("folder", SkillMeta.parse("", "folder").name());
    assertFalse(
        SkillMeta.parse("---\nname: a\u0007b\ndescription: " + "x".repeat(900) + "\n---\n", "f")
            .name()
            .contains("\u0007"));
    assertEquals(
        500,
        SkillMeta.parse("---\nname: a\ndescription: " + "x".repeat(900) + "\n---\n", "f")
            .description()
            .length());
  }

  // ---- synchronisation against a GitHub double ----

  static String b64(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  /** A repository with two skills; counts the calls it receives. */
  static class Github implements SkillSources.Fetch {
    final List<String> calls = new ArrayList<>();
    String shaOfPdf = "pdf-1";
    boolean failTree = false, truncated = false;
    int extra = 0;

    public JsonNode get(String repo, String path) {
      calls.add(path);
      if (path.isEmpty()) return parse("{\"default_branch\":\"main\",\"stargazers_count\":77}");
      if (path.startsWith("/git/trees/")) {
        if (failTree) throw new IllegalStateException("GitHub HTTP 403 for tree (rate limit)");
        var tree =
            new StringBuilder(
                "{\"truncated\":"
                    + truncated
                    + ",\"tree\":[{\"path\":\"README.md\",\"type\":\"blob\",\"sha\":\"r\"},{\"path\":\"skills/python/SKILL.md\",\"type\":\"blob\",\"sha\":\"py-1\"},{\"path\":\"skills/pdf/SKILL.md\",\"type\":\"blob\",\"sha\":\""
                    + shaOfPdf
                    + "\"},{\"path\":\"other/x/SKILL.md\",\"type\":\"blob\",\"sha\":\"o\"},{\"path\":\"skills/dir\",\"type\":\"tree\",\"sha\":\"d\"}");
        for (int i = 0; i < extra; i++)
          tree.append(
              ",{\"path\":\"skills/extra"
                  + i
                  + "/SKILL.md\",\"type\":\"blob\",\"sha\":\"e"
                  + i
                  + "\"}");
        return parse(tree + "]}");
      }
      String text =
          path.contains("python")
              ? "---\nname: python\ndescription: Pytest helper for Python code\n---\nbody"
              : path.contains("pdf")
                  ? "# PDF tools\n\nWork with PDF documents."
                  : "---\nname: extra\ndescription: Another skill\n---\nx";
      String sha = path.contains("python") ? "py-1" : path.contains("pdf") ? shaOfPdf : "e";
      return parse(
          "{\"type\":\"file\",\"size\":"
              + text.length()
              + ",\"encoding\":\"base64\",\"sha\":\""
              + sha
              + "\",\"content\":\""
              + b64(text)
              + "\"}");
    }
  }

  @Test
  void aSyncReadsOnlyTheSkillsUnderTheSourcePathAndOnlyWhatChanged() {
    var github = new Github();
    var sync = new SkillCatalogSync(catalog, github, () -> true);
    long id =
        ((Number) catalog.sources().get(0).get("id"))
            .longValue(); // anthropics/skills, path "skills"
    sync.sync(id);
    assertEquals(2, catalog.size(), "other/x is outside the source path, README is not a skill");
    assertEquals("python", catalog.search("python", null, 5).results().get(0).get("name"));
    assertEquals(
        77, ((Number) catalog.search("python", null, 5).results().get(0).get("stars")).intValue());
    int first = github.calls.size();
    github.calls.clear();
    sync.sync(id);
    assertEquals(
        2, github.calls.size(), "unchanged SHAs: only the repository and its tree are asked for");
    github.calls.clear();
    github.shaOfPdf = "pdf-2";
    sync.sync(id);
    assertEquals(3, github.calls.size(), "one changed file is read again");
    assertTrue(first > 2);
  }

  @Test
  void aSkillThatCannotBeIndexedIsReportedNotSilentlyDropped() {
    var github =
        new Github() {
          public JsonNode get(String repo, String path) {
            if (path.contains("pdf"))
              return parse(
                  "{\"type\":\"file\",\"size\":99999,\"encoding\":\"base64\",\"sha\":\"pdf-1\",\"content\":\"\"}");
            return super.get(repo, path);
          }
        };
    long id = ((Number) catalog.sources().get(0).get("id")).longValue();
    new SkillCatalogSync(catalog, github, () -> true).sync(id);
    assertEquals(1, catalog.size());
    assertTrue(catalog.source(id).get("last_error").toString().contains("1 skill no se indexa"));
  }

  @Test
  void aFailureKeepsTheCatalogueAndSaysWhy() {
    var github = new Github();
    var sync = new SkillCatalogSync(catalog, github, () -> true);
    long id = ((Number) catalog.sources().get(0).get("id")).longValue();
    sync.sync(id);
    github.failTree = true;
    sync.sync(id);
    assertEquals(2, catalog.size(), "what was there is kept");
    assertTrue(catalog.source(id).get("last_error").toString().contains("rate limit"));
    github.failTree = false;
    sync.sync(id);
    assertNull(catalog.source(id).get("last_error"), "a good run clears the error");
  }

  @Test
  void theCallBudgetNeverDeletesWhatItDidNotRead() {
    var github = new Github();
    github.extra = SkillCatalogSync.MAX_CALLS + 20;
    var sync = new SkillCatalogSync(catalog, github, () -> true);
    long id = ((Number) catalog.sources().get(0).get("id")).longValue();
    sync.sync(id);
    assertTrue(github.calls.size() <= SkillCatalogSync.MAX_CALLS + 1, "the budget holds");
    assertTrue(catalog.source(id).get("last_error").toString().contains("Presupuesto"));
    github.calls.clear();
    sync.sync(id);
    assertTrue(
        catalog.size() > SkillCatalogSync.MAX_CALLS - 5, "the next run continues where it stopped");
  }

  @Test
  void withoutGitHubNothingIsSynchronisedInTheBackground() {
    var sync = new SkillCatalogSync(catalog, new Github(), () -> false);
    sync.daily();
    assertEquals(0, catalog.size());
    assertThrows(IllegalArgumentException.class, () -> sync.start(1));
  }

  @Test
  void topicsKnowSpanishAndEnglishAndLeaveFillerOut() {
    assertTrue(
        SkillTopics.detect("quiero programar").stream()
            .anyMatch(t -> t.id().equals("programming")));
    assertTrue(
        SkillTopics.detect("graphic design").stream().anyMatch(t -> t.id().equals("design")));
    assertTrue(SkillTopics.detect("seguridad").stream().anyMatch(t -> t.id().equals("security")));
    assertTrue(SkillTopics.detect("zzzz qqqq").isEmpty());
    assertEquals(List.of("diseno", "grafico"), SkillTopics.tokens("Diseño   gráfico para el"));
    assertTrue(SkillTopics.tagsFor("Write pytest tests for Python").contains("python"));
  }
}
