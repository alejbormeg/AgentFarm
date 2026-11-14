package io.agentfarm.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.GitHubCredentials;
import io.agentfarm.core.GitHubReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Finds skills online for the owner to add to a repository: the official catalogues that publish on
 * GitHub (Anthropic, OpenAI), any other GitHub repository, and a repository search. The panel does
 * the fetching, never the browser or a node, only from GitHub, and what comes back is text: it is
 * shown whole and added only when the owner says so, pinned by the blob SHA and a hash. A
 * downloaded skill is as hostile as any outside text (docs/SECURITY.md).
 */
@Service
public class SkillSources {
  public static final int MAX_BYTES = 32768;

  /** Reads one GitHub path of a repository, as {@link GitHubReader#get}. */
  @FunctionalInterface
  public interface Fetch {
    JsonNode get(String repo, String path) throws Exception;
  }

  /** Searches repositories; returns GitHub's {@code items}. */
  @FunctionalInterface
  public interface Search {
    JsonNode repositories(String query) throws Exception;
  }

  /**
   * What the owner can start from. Google publishes no official registry: its skills use the same
   * format and are found as any GitHub repository.
   */
  public static final List<Map<String, String>> OFFICIAL =
      List.of(
          Map.of(
              "id",
              "anthropic",
              "name",
              "Anthropic · skills oficiales",
              "repo",
              "anthropics/skills",
              "path",
              "skills"),
          Map.of(
              "id",
              "openai",
              "name",
              "OpenAI · skills de Codex",
              "repo",
              "openai/skills",
              "path",
              ""));

  private final Fetch fetch;
  private final Search search;

  @Autowired
  public SkillSources(GitHubReader reader, GitHubCredentials credentials) {
    this(
        reader::get,
        query -> {
          String base = System.getenv("AGENTFARM_GITHUB_API");
          if (base == null || base.isBlank()) base = "https://api.github.com";
          var response =
              HttpClient.newBuilder()
                  .connectTimeout(Duration.ofSeconds(5))
                  .build()
                  .send(
                      HttpRequest.newBuilder(
                              URI.create(
                                  base
                                      + "/search/repositories?per_page=10&q="
                                      + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                          .timeout(Duration.ofSeconds(20))
                          .header("Authorization", "Bearer " + credentials.token())
                          .header("Accept", "application/vnd.github+json")
                          .GET()
                          .build(),
                      HttpResponse.BodyHandlers.ofString());
          if (response.statusCode() != 200)
            throw new IllegalStateException(
                "GitHub HTTP " + response.statusCode() + " en la búsqueda");
          return io.agentfarm.core.Json.parse(response.body()).path("items");
        });
  }

  public SkillSources(Fetch fetch, Search search) {
    this.fetch = fetch;
    this.search = search;
  }

  static String repo(String repo) {
    if (repo == null || !repo.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))
      throw new IllegalArgumentException("Repositorio no válido: usa propietario/nombre");
    return repo;
  }

  static String path(String path) {
    String p = path == null ? "" : path.strip().replaceAll("^/+|/+$", "");
    if (p.length() > 200 || p.contains("..") || !p.matches("[A-Za-z0-9_./ -]*"))
      throw new IllegalArgumentException("Ruta no válida");
    return p.replace(" ", "%20");
  }

  /** Folders and Markdown files at a path, folders first. */
  public List<Map<String, String>> browse(String repo, String path) throws Exception {
    String clean = path(path);
    var node = fetch.get(repo(repo), "/contents" + (clean.isEmpty() ? "" : "/" + clean));
    if (!node.isArray())
      throw new IllegalArgumentException("Esa ruta es un archivo, no una carpeta");
    var out = new ArrayList<Map<String, String>>();
    for (var e : node) {
      String type = e.path("type").asText(), name = e.path("name").asText();
      if (type.equals("dir") || (type.equals("file") && name.toLowerCase().endsWith(".md")))
        out.add(
            Map.of(
                "name",
                name,
                "type",
                type.equals("dir") ? "dir" : "file",
                "path",
                e.path("path").asText()));
    }
    out.sort(
        Comparator.comparing((Map<String, String> m) -> m.get("type"))
            .thenComparing(m -> m.get("name")));
    return out;
  }

  /** One SKILL.md, whole and exactly as published, or the reason it cannot be offered. */
  public Map<String, Object> fetchSkill(String repo, String path) throws Exception {
    String clean = path(path);
    if (!clean.endsWith("SKILL.md"))
      throw new IllegalArgumentException("Solo se ofrecen archivos SKILL.md");
    var node = fetch.get(repo(repo), "/contents/" + clean);
    if (!node.isObject() || !node.path("type").asText().equals("file"))
      throw new IllegalArgumentException("No es un archivo");
    if (node.path("size").asLong(Long.MAX_VALUE) > MAX_BYTES)
      throw new IllegalArgumentException("La skill pasa de 32 KiB y no se ofrece");
    if (!node.path("encoding").asText("base64").equals("base64"))
      throw new IllegalArgumentException("Codificación no admitida");
    byte[] bytes = Base64.getMimeDecoder().decode(node.path("content").asText(""));
    if (bytes.length > MAX_BYTES)
      throw new IllegalArgumentException("La skill pasa de 32 KiB y no se ofrece");
    String text = new String(bytes, StandardCharsets.UTF_8);
    if (text.indexOf('\u0000') >= 0 || text.isBlank())
      throw new IllegalArgumentException("El archivo no es texto");
    String[] parts = clean.split("/");
    String folder = parts.length >= 2 ? parts[parts.length - 2] : parts[0];
    String id = folder.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    if (id.isEmpty()) id = "skill";
    if (id.length() > 40) id = id.substring(0, 40);
    String source =
        repo.equals("anthropics/skills")
            ? "anthropic"
            : repo.equals("openai/skills") ? "openai" : "github";
    return Map.of(
        "id",
        id,
        "title",
        folder,
        "text",
        text,
        "source",
        source,
        "origin",
        repo
            + ":"
            + clean
            + "@"
            + node.path("sha")
                .asText("")
                .substring(0, Math.min(12, node.path("sha").asText("").length())),
        "sha",
        node.path("sha").asText(""),
        "url",
        node.path("html_url").asText(""),
        "bytes",
        bytes.length,
        "hash",
        ProfilePrompt.hash(text));
  }

  /** Repositories that look like skill collections, for a free-text search. */
  public List<Map<String, Object>> searchRepos(String text) throws Exception {
    String q = text == null ? "" : text.strip();
    if (q.length() < 2 || q.length() > 100 || !q.matches("[\\p{L}\\p{N} _.:/+-]+"))
      throw new IllegalArgumentException("Escribe entre 2 y 100 caracteres, sin símbolos raros");
    var out = new ArrayList<Map<String, Object>>();
    for (var r : search.repositories(q + " skills in:name,description,topics")) {
      String full = r.path("full_name").asText("");
      if (!full.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) continue;
      String description = r.path("description").asText("").replaceAll("\\s+", " ").strip();
      out.add(
          Map.of(
              "repo", full,
              "description", description.substring(0, Math.min(200, description.length())),
              "stars", r.path("stargazers_count").asInt(0)));
    }
    return out;
  }
}
