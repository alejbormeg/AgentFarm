package io.agentfarm.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a free-text request is about, in Spanish or English, from a hand-kept dictionary of topics
 * (resource {@code skill-topics.json}). Skills are written in English and the owner will often ask
 * in Spanish, so a matched topic adds its English terms to the search. Deterministic, free, and
 * limited to what the dictionary knows: the answer says which topics it understood.
 */
public final class SkillTopics {
  public record Topic(String id, String label, List<String> es, List<String> en) {}

  private static final Set<String> STOP =
      Set.of(
          "de",
          "la",
          "el",
          "los",
          "las",
          "un",
          "una",
          "unos",
          "unas",
          "para",
          "con",
          "en",
          "y",
          "o",
          "que",
          "quiero",
          "necesito",
          "algo",
          "skills",
          "skill",
          "habilidades",
          "habilidad",
          "sobre",
          "como",
          "por",
          "del",
          "al",
          "mis",
          "me",
          "a",
          "the",
          "an",
          "for",
          "to",
          "of",
          "and",
          "or",
          "with",
          "in",
          "on",
          "i",
          "want",
          "need",
          "something",
          "about",
          "how",
          "some",
          "my",
          "is",
          "are",
          "buscar",
          "busco",
          "dame",
          "hacer",
          "usar");
  private static final List<Topic> TOPICS = load();

  private SkillTopics() {}

  /** Lowercase, no accents, letters and digits separated by single spaces. */
  public static String normalize(String text) {
    String t = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD);
    return t.replaceAll("\\p{M}+", "")
        .toLowerCase(java.util.Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}]+", " ")
        .strip();
  }

  /** The words of a request that carry meaning: no symbols and no filler words. */
  public static List<String> tokens(String text) {
    var out = new ArrayList<String>();
    for (String w : normalize(text).split(" "))
      if (w.length() >= 2 && !STOP.contains(w) && !out.contains(w)) out.add(w);
    return out;
  }

  private static List<Topic> load() {
    try (InputStream in = SkillTopics.class.getResourceAsStream("/skill-topics.json")) {
      if (in == null) throw new IllegalStateException("skill-topics.json missing");
      var out = new ArrayList<Topic>();
      for (JsonNode t : io.agentfarm.core.Json.MAPPER.readTree(in).path("topics"))
        out.add(
            new Topic(
                t.path("id").asText(),
                t.path("label").asText(),
                terms(t.path("es")),
                terms(t.path("en"))));
      return List.copyOf(out);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot load skill topics", e);
    }
  }

  private static List<String> terms(JsonNode array) {
    var out = new ArrayList<String>();
    for (JsonNode n : array) out.add(normalize(n.asText()));
    return List.copyOf(out);
  }

  public static Topic byId(String id) {
    return TOPICS.stream().filter(t -> t.id().equals(id)).findFirst().orElse(null);
  }

  /** A term matches whole words/phrases, or a word sharing its first five letters (verb forms). */
  static boolean matches(String padded, List<String> words, String term) {
    if (term.isEmpty()) return false;
    if (padded.contains(" " + term + " ")) return true;
    if (term.indexOf(' ') >= 0 || term.length() < 5) return false;
    for (String w : words) if (w.length() >= 5 && w.regionMatches(0, term, 0, 5)) return true;
    return false;
  }

  /** Topics the text is about, the best match first. */
  public static List<Topic> detect(String text) {
    String norm = normalize(text);
    if (norm.isEmpty()) return List.of();
    String padded = " " + norm + " ";
    List<String> words = List.of(norm.split(" "));
    var scored = new LinkedHashMap<Topic, Integer>();
    for (Topic t : TOPICS) {
      int hits = 0;
      for (String term : t.es()) if (matches(padded, words, term)) hits++;
      for (String term : t.en()) if (matches(padded, words, term)) hits++;
      if (hits > 0) scored.put(t, hits);
    }
    var out = new ArrayList<>(scored.keySet());
    out.sort((a, b) -> Integer.compare(scored.get(b), scored.get(a)));
    return out;
  }

  /** The English words to add to a search for these topics. */
  public static Set<String> expansion(List<Topic> topics) {
    var out = new LinkedHashSet<String>();
    for (Topic t : topics) for (String term : t.en()) if (!term.contains(" ")) out.add(term);
    return out;
  }

  /**
   * Index-time tags: the topic ids and two of their English terms, so a skill is found by theme.
   */
  public static String tagsFor(String text) {
    var out = new LinkedHashSet<String>();
    for (Topic t : detect(text).stream().limit(4).toList()) {
      out.add(t.id().replace('-', ' '));
      t.en().stream().filter(x -> !x.contains(" ")).limit(2).forEach(out::add);
    }
    return String.join(" ", out);
  }

  public static List<Map<String, String>> catalog() {
    return TOPICS.stream().map(t -> Map.of("id", t.id(), "label", t.label())).toList();
  }
}
