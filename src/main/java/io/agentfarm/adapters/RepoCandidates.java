package io.agentfarm.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.GitHubReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Repositories an integrator seems to integrate, found by reading it on GitHub (read only): its
 * submodules and the repositories its README links to. It is a suggestion for the owner, who
 * decides in the panel which become members of the group: text in a repository (a README, a
 * submodule URL) is written by people and never adds anyone by itself.
 */
@Service
public class RepoCandidates {
  public static final int MAX = 30, MAX_TEXT = 200_000;

  private static final Pattern LINK =
      Pattern.compile("github\\.com[/:]([A-Za-z0-9_.-]{1,100})/([A-Za-z0-9_.-]{1,100})");

  /** Paths on github.com that are not an owner. */
  private static final Set<String> RESERVED =
      Set.of(
          "features",
          "orgs",
          "sponsors",
          "topics",
          "marketplace",
          "settings",
          "apps",
          "login",
          "about",
          "pricing",
          "enterprise",
          "collections",
          "trending",
          "explore",
          "notifications",
          "pulls",
          "issues",
          "search",
          "security",
          "readme",
          "site",
          "customer-stories",
          "new");

  private final GitHubReader.Source github;

  @Autowired
  public RepoCandidates(GitHubReader reader) {
    this(reader::get);
  }

  public RepoCandidates(GitHubReader.Source github) {
    this.github = github;
  }

  private String read(String repo, String path) {
    try {
      JsonNode file = github.get(repo, path);
      if (!"base64".equals(file.path("encoding").asText())) return "";
      String text =
          new String(
              Base64.getMimeDecoder().decode(file.path("content").asText("")),
              StandardCharsets.UTF_8);
      return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    } catch (Exception absent) {
      // No such file, or no access: there is just nothing to suggest from it.
      return "";
    }
  }

  static List<String> links(String text) {
    var out = new ArrayList<String>();
    var m = LINK.matcher(text);
    while (m.find()) {
      String owner = m.group(1), name = m.group(2);
      while (name.endsWith(".") || name.endsWith("/")) name = name.substring(0, name.length() - 1);
      if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
      if (name.isEmpty()
          || owner.equals(".")
          || owner.equals("..")
          || name.equals(".")
          || name.equals("..")
          || RESERVED.contains(owner.toLowerCase())) continue;
      out.add(owner + "/" + name);
    }
    return out;
  }

  /**
   * The suggestions for {@code integrator}, except the integrator itself and {@code skip} (the
   * members it already has). Each says where it was found; repositories of the same owner come
   * first.
   */
  public List<Map<String, Object>> find(String integratorName, Set<String> skip) {
    String integrator = RepoGroups.repo(integratorName);
    String owner = integrator.substring(0, integrator.indexOf('/'));
    var found = new LinkedHashMap<String, List<String>>();
    for (String repo : links(read(integrator, "/contents/.gitmodules")))
      add(found, repo, "submódulo", integrator, skip);
    for (String repo : links(read(integrator, "/readme")))
      add(found, repo, "README", integrator, skip);
    var out = new ArrayList<Map<String, Object>>();
    for (boolean same : new boolean[] {true, false})
      for (var e : found.entrySet()) {
        boolean sameOwner = e.getKey().startsWith(owner + "/");
        if (sameOwner != same || out.size() >= MAX) continue;
        var c = new LinkedHashMap<String, Object>();
        c.put("repo", e.getKey());
        c.put("found_in", e.getValue());
        c.put("same_owner", sameOwner);
        out.add(c);
      }
    return out;
  }

  private static void add(
      Map<String, List<String>> found,
      String repo,
      String where,
      String integrator,
      Set<String> skip) {
    if (repo.equalsIgnoreCase(integrator)) return;
    for (String s : skip) if (s.equalsIgnoreCase(repo)) return;
    var places = found.computeIfAbsent(repo, k -> new ArrayList<>());
    if (!places.contains(where)) places.add(where);
  }
}
