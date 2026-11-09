package io.agentfarm.core;

import java.util.*;

/**
 * Every transition a person can ask of a task, in one place: from which states, to which one, and
 * whether it needs the version they saw. The server checks it; the panel only shows what it allows.
 * Who may ask is the web layer's ({@code SecurityConfig}): reviewers implement, correct, retry and
 * cancel; only owners merge and revert. Executors move tasks through their own reports.
 */
public final class WorkStates {
  /**
   * One action: the states it starts from, the state it leaves, and whether a version is needed.
   */
  public record Rule(Set<String> from, String to, boolean version) {}

  public static final Map<String, Rule> ACTIONS =
      Map.of(
          "implement", new Rule(Set.of("proposed"), "approved", true),
          "merge", new Rule(Set.of("review"), "integrating", false),
          "correct", new Rule(Set.of("proposed", "review", "blocked"), "queued", false),
          "retry", new Rule(Set.of("blocked"), "queued", false),
          "cancel",
              new Rule(
                  Set.of("queued", "approved", "proposed", "review", "blocked"),
                  "cancelled",
                  false),
          // Integrated stays integrated: a revert is a new task, linked to it, through every gate.
          "revert", new Rule(Set.of("integrated"), "integrated", false));

  private WorkStates() {}

  /** The rule for {@code action} from {@code state}, or why it can't happen, in words. */
  public static Rule require(String action, String state) {
    var rule = ACTIONS.get(action);
    if (rule == null) throw new IllegalArgumentException("Unknown action");
    if (rule.from().contains(state)) return rule;
    throw new IllegalArgumentException(
        switch (action + ":" + state) {
          case "cancel:integrated" ->
              "Una tarea integrada no se cancela: su cambio ya está en la rama principal. Para"
                  + " deshacerlo, pide su reversión.";
          case "cancel:cancelled" -> "La tarea ya está cancelada.";
          case "cancel:integrating" ->
              "La integración ya está en marcha y no se puede cortar a medias: espera a su"
                  + " resultado.";
          case "implement:approved", "implement:queued" -> "Approve the current proposal version";
          default ->
              switch (action) {
                case "implement" -> "Approve the current proposal version";
                case "merge" -> "Approve the current PR commit";
                case "correct" -> "No result to correct";
                case "retry" -> "Only blocked operations can retry";
                case "revert" -> "Solo se puede revertir una tarea integrada.";
                default -> "La tarea no admite esta acción en su estado actual.";
              };
        });
  }
}
