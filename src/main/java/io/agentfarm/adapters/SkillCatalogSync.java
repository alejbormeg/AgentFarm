package io.agentfarm.adapters;

import io.agentfarm.core.GitHubReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps the catalogue in step with its sources: one tree call per repository, then only the
 * SKILL.md files whose blob SHA changed. Bounded (skills per source, calls per run, file size); a
 * failure keeps what was stored and says why. Everything read is hostile text, stored as such.
 */
@Service
public class SkillCatalogSync {
  public static final int MAX_SKILLS = 500, MAX_CALLS = 300;
  private final SkillIndex catalog;
  private final SkillSources.Fetch fetch;
  private final BooleanSupplier configured;
  private final Set<Long> running = ConcurrentHashMap.newKeySet();
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "agentfarm-skill-sync");
        t.setDaemon(true);
        return t;
      });

  @Autowired
  public SkillCatalogSync(SkillIndex catalog, GitHubReader reader) {
    this(catalog, reader::get, reader::configured);
  }

  public SkillCatalogSync(
      SkillIndex catalog, SkillSources.Fetch fetch, BooleanSupplier configured) {
    this.catalog = catalog;
    this.fetch = fetch;
    this.configured = configured;
  }

  public boolean configured() {
    return configured.getAsBoolean();
  }

  public Set<Long> running() {
    return Set.copyOf(running);
  }

  /** Once a day, and a couple of minutes after the panel starts. */
  @Scheduled(initialDelay = 120_000, fixedDelay = 86_400_000)
  public void daily() {
    if (configured()) syncAll();
  }

  /** In the background, so a request from the panel never waits on GitHub. */
  public void startAll() {
    for (var s : catalog.sources()) start(((Number) s.get("id")).longValue());
  }

  public void start(long id) {
    if (!configured())
      throw new IllegalArgumentException("Conecta GitHub en Gobierno para sincronizar el catálogo");
    if (!running.add(id)) return;
    worker.execute(() -> {
      try {
        sync(id);
      } finally {
        running.remove(id);
      }
    });
  }

  public void syncAll() {
    for (var s : catalog.sources())
      if (Number.class.cast(s.get("enabled")).intValue() == 1)
        sync(((Number) s.get("id")).longValue());
  }

  /** Synchronises one source now; never throws, it records the failure on the source. */
  public void sync(long id) {
    Map<String, Object> source;
    try {
      source = catalog.source(id);
    } catch (IllegalArgumentException e) {
      return;
    }
    String repo = source.get("repo").toString(), prefix = source.get("path").toString();
    try {
      int[] calls = {0};
      var info = fetch.get(repo, "");
      calls[0]++;
      String branch = info.path("default_branch").asText("");
      if (!branch.matches("[A-Za-z0-9_./-]{1,100}"))
        throw new IllegalStateException("Rama por defecto no válida");
      int stars = info.path("stargazers_count").asInt(0);
      var tree = fetch.get(repo, "/git/trees/" + branch + "?recursive=1");
      calls[0]++;
      var found = new ArrayList<String[]>();
      for (var e : tree.path("tree")) {
        String path = e.path("path").asText("");
        if (!e.path("type").asText().equals("blob")
            || !(path.equals("SKILL.md") || path.endsWith("/SKILL.md"))) continue;
        if (!prefix.isEmpty() && !path.startsWith(prefix + "/")) continue;
        found.add(new String[] {path, e.path("sha").asText("")});
        if (found.size() >= MAX_SKILLS) break;
      }
      var known = catalog.shas(id);
      var seen = new LinkedHashSet<String>();
      var fresh = new ArrayList<SkillIndex.Entry>();
      var reader = new SkillSources(fetch, null);
      String note =
          tree.path("truncated").asBoolean(false)
              ? "El árbol de GitHub vino truncado: puede faltar alguna skill"
              : "";
      for (String[] f : found) {
        seen.add(f[0]);
        if (f[1].equals(known.get(f[0]))) continue;
        if (calls[0] >= MAX_CALLS) {
          note =
              "Presupuesto de llamadas agotado: faltan skills por leer; se completará en la próxima sincronización";
          break;
        }
        calls[0]++;
        try {
          var skill = reader.fetchSkill(repo, f[0]);
          String[] parts = f[0].split("/");
          String folder =
              parts.length >= 2 ? parts[parts.length - 2] : repo.substring(repo.indexOf('/') + 1);
          var meta = SkillMeta.parse(skill.get("text").toString(), folder);
          String tags =
              SkillTopics.tagsFor(
                  meta.name() + " " + meta.description() + " " + String.join(" ", parts));
          fresh.add(
              new SkillIndex.Entry(
                  repo,
                  f[0],
                  meta.name(),
                  meta.description(),
                  tags,
                  skill.get("sha").toString(),
                  ((Number) skill.get("bytes")).intValue()));
        } catch (IllegalArgumentException skip) {
          // Too big, not text or an odd path: that one skill is left out, the rest goes on.
        }
      }
      // With the call budget spent, what was not read must not be deleted as if it had vanished.
      catalog.apply(id, stars, fresh, seen);
      if (!note.isEmpty()) catalog.fail(id, note);
    } catch (Exception e) {
      catalog.fail(id, "No se pudo sincronizar: " + e.getMessage());
    }
  }
}
