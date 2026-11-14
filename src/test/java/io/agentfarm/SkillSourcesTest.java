package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.adapters.SkillSources;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SkillSourcesTest {
  static String b64(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  /** A GitHub with one catalogue: skills/casa/SKILL.md and a README. */
  SkillSources github() {
    return new SkillSources(
        (repo, path) -> {
          if (path.equals("/contents/skills"))
            return parse(
                "[{\"name\":\"casa\",\"type\":\"dir\",\"path\":\"skills/casa\"},{\"name\":\"README.md\",\"type\":\"file\",\"path\":\"skills/README.md\"},{\"name\":\"logo.png\",\"type\":\"file\",\"path\":\"skills/logo.png\"}]");
          if (path.equals("/contents/skills/casa/SKILL.md"))
            return parse(
                "{\"type\":\"file\",\"size\":9,\"encoding\":\"base64\",\"sha\":\"abcdef0123456789\",\"html_url\":\"https://github.com/o/r/blob/main/skills/casa/SKILL.md\",\"content\":\""
                    + b64("# Casa\nok")
                    + "\"}");
          if (path.equals("/contents/skills/big/SKILL.md"))
            return parse(
                "{\"type\":\"file\",\"size\":99999,\"encoding\":\"base64\",\"sha\":\"x\",\"content\":\"\"}");
          if (path.equals("/contents/skills/bin/SKILL.md"))
            return parse(
                "{\"type\":\"file\",\"size\":3,\"encoding\":\"base64\",\"sha\":\"x\",\"content\":\""
                    + Base64.getEncoder().encodeToString(new byte[] {65, 0, 66})
                    + "\"}");
          throw new IllegalStateException("GitHub HTTP 404 for " + path);
        },
        q ->
            parse(
                "[{\"full_name\":\"o/skills-pack\",\"description\":\"Muchas   skills\",\"stargazers_count\":42},{\"full_name\":\"evil name/x\",\"description\":\"\"}]"));
  }

  @Test
  void browsingShowsFoldersAndMarkdownOnly() throws Exception {
    var entries = github().browse("anthropics/skills", "skills");
    assertEquals(2, entries.size(), "the image is left out");
    assertEquals("dir", entries.get(0).get("type"), "folders first");
    assertEquals("README.md", entries.get(1).get("name"));
  }

  @Test
  void aSkillComesWholeWithItsOriginAndPin() throws Exception {
    var skill = github().fetchSkill("anthropics/skills", "skills/casa/SKILL.md");
    assertEquals("# Casa\nok", skill.get("text"));
    assertEquals("casa", skill.get("id"));
    assertEquals("anthropic", skill.get("source"));
    assertEquals("anthropics/skills:skills/casa/SKILL.md@abcdef012345", skill.get("origin"));
    assertEquals(64, skill.get("hash").toString().length());
    assertEquals("github", github().fetchSkill("o/r", "skills/casa/SKILL.md").get("source"));
  }

  @Test
  void whatIsNotAnOrdinarySmallTextSkillIsRefused() {
    var g = github();
    for (String[] bad :
        new String[][] {
          {"o/r", "skills/big/SKILL.md"},
          {"o/r", "skills/bin/SKILL.md"},
          {"o/r", "skills/casa/README.md"},
          {"o/r", "../etc/SKILL.md"},
          {"o/r", "skills/a b;rm/SKILL.md"},
          {"nope", "skills/casa/SKILL.md"},
          {"o/r/extra", "x/SKILL.md"}
        })
      assertThrows(
          IllegalArgumentException.class,
          () -> g.fetchSkill(bad[0], bad[1]),
          bad[0] + " " + bad[1]);
    assertThrows(IllegalStateException.class, () -> g.fetchSkill("o/r", "skills/missing/SKILL.md"));
  }

  @Test
  void searchKeepsOnlyWellFormedRepositoriesAndCleansTheirText() throws Exception {
    var found = github().searchRepos("seguridad");
    assertEquals(1, found.size());
    assertEquals("Muchas skills", found.get(0).get("description"));
    assertThrows(IllegalArgumentException.class, () -> github().searchRepos("x"));
    assertThrows(IllegalArgumentException.class, () -> github().searchRepos("a\"; drop"));
  }

  @Test
  void theOfficialSourcesAreAnthropicAndOpenAI() {
    assertEquals(
        java.util.List.of("anthropic", "openai"),
        SkillSources.OFFICIAL.stream().map(o -> o.get("id")).toList());
  }
}
