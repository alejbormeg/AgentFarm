package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillCatalogTest {
  @TempDir Path dir;

  Set<String> ids(SkillCatalog.Selection selection) {
    var out = new TreeSet<String>();
    selection.references().forEach(r -> out.add(r.get("id").toString()));
    return out;
  }

  @Test
  void everySkillInTheCatalogHasItsFileAndFrontmatter() {
    var list = SkillCatalog.list();
    assertTrue(list.size() >= 10);
    for (var skill : list)
      assertEquals(64, skill.get("hash").toString().length(), skill.toString());
  }

  @Test
  void eachRoleGetsWhatItsWorkNeeds() throws Exception {
    var java = Set.of("java");
    assertEquals(
        Set.of("software-design", "test-design"),
        ids(SkillCatalog.select("research", java, List.of(), null)));
    assertEquals(
        Set.of("change-discipline", "test-design", "secure-coding", "java-spring"),
        ids(SkillCatalog.select("implement", java, List.of(), null)));
    assertEquals(
        Set.of("code-review", "test-design", "secure-coding", "java-spring"),
        ids(SkillCatalog.select("review", java, List.of(), null)));
    assertFalse(
        ids(SkillCatalog.select("implement", Set.of("python"), List.of(), null))
            .contains("java-spring"));
    assertTrue(
        ids(SkillCatalog.select("implement", Set.of("node", "ui"), List.of(), null))
            .contains("web-ui"));
  }

  @Test
  void theTextAndTheRecordMatch() throws Exception {
    var selection = SkillCatalog.select("review", Set.of(), List.of(), null);
    assertTrue(selection.text().contains("<skill id=\"code-review\">"));
    assertTrue(selection.text().contains("passed=false"));
    for (var reference : selection.references()) assertEquals(1, reference.get("version"));
  }

  @Test
  void stacksComeFromWellKnownFiles() throws Exception {
    assertTrue(SkillCatalog.detectStacks(dir).isEmpty());
    Files.writeString(dir.resolve("pom.xml"), "<project/>");
    Files.writeString(dir.resolve("requirements.txt"), "");
    assertEquals(Set.of("java", "python"), SkillCatalog.detectStacks(dir));
    Files.createDirectories(dir.resolve("src/web"));
    Files.writeString(dir.resolve("src/web/app.tsx"), "");
    assertTrue(SkillCatalog.detectStacks(dir).contains("ui"));
  }

  @Test
  void dependenciesDoNotMakeAProjectAUi() throws Exception {
    Files.createDirectories(dir.resolve("node_modules/x"));
    Files.writeString(dir.resolve("node_modules/x/index.html"), "");
    assertFalse(SkillCatalog.detectStacks(dir).contains("ui"));
  }

  @Test
  void aProjectCanOnlyNameSkillsTheOperatorInstalled() throws Exception {
    Path operator = dir.resolve("operator");
    Files.createDirectories(operator.resolve("house-style"));
    Files.writeString(operator.resolve("house-style/SKILL.md"), "Usa tabuladores.");
    var named = SkillCatalog.select("review", Set.of(), List.of("house-style"), operator);
    assertTrue(ids(named).contains("house-style"));
    assertEquals(
        sha256("Usa tabuladores."),
        named.references().stream()
            .filter(r -> r.get("id").equals("house-style"))
            .findFirst()
            .orElseThrow()
            .get("hash"));
    assertThrows(
        IllegalArgumentException.class,
        () -> SkillCatalog.select("review", Set.of(), List.of("unknown"), operator),
        "an unknown name fails instead of being skipped");
    assertThrows(
        IllegalArgumentException.class,
        () -> SkillCatalog.select("review", Set.of(), List.of("../../etc"), operator));
    assertTrue(
        ids(SkillCatalog.select("research", Set.of(), List.of("python"), operator))
            .contains("python"),
        "a project can ask for a built-in outside its stack");
  }

  @Test
  void profilesNameSkillsWithoutBringingTheirContent() {
    String base =
        "version: 1\nrepository: o/r\ntests:\n  unit: u\n  integration: i\nallowed_paths: [src/]\n"
            + "environment: local\nreview: independent\n";
    assertEquals(List.of("python"), ProjectProfile.parse(base + "skills: [python]\n").skills());
    assertThrows(
        IllegalArgumentException.class, () -> ProjectProfile.parse(base + "skills: [../x]\n"));
    assertThrows(
        IllegalArgumentException.class, () -> ProjectProfile.parse(base + "skills: python\n"));
  }
}
