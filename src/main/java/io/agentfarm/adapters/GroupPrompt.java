package io.agentfarm.adapters;

import com.fasterxml.jackson.databind.JsonNode;

/** The part of the research prompt about the repositories the task's repository integrates. */
public final class GroupPrompt {
  private GroupPrompt() {}

  /**
   * The repositories this one integrates, and how to propose splitting the change among them. Empty
   * when the repository leads no group. The group is the owner's; this task neither reads nor
   * changes those repositories, it only names the parts and each one is researched, approved and
   * implemented as its own task in its own repository.
   */
  public static String context(JsonNode item) {
    var members = item.path("repo_group").path("members");
    if (!members.isArray() || members.isEmpty()) return "";
    var out =
        new StringBuilder(
            "\n## Repositorios que integra este repositorio\nEl propietario ha declarado que"
                + " {{self}} integra estos repositorios. NO los leas ni los modifiques desde esta"
                + " tarea:\n");
    for (var m : members)
      out.append("- ")
          .append(m.path("repo").asText())
          .append(m.path("role").asText("").isBlank() ? "" : ": " + m.path("role").asText())
          .append('\n');
    out.append(
        "\nSi la petición exige cambiar también alguno de esos repositorios, añade a tu JSON"
            + " `parts`: una lista de hasta 8 objetos {\"repo\": \"owner/repo\", \"title\":"
            + " frase corta, \"request\": lo que debe hacer esa parte, autocontenido, \"depends_on\":"
            + " [índices desde 0 de partes ANTERIORES que deben integrarse antes]}. repo solo puede"
            + " ser este repositorio o uno de la lista. Cada parte se investigará, aprobará e"
            + " implementará como una tarea propia en su repositorio, con su perfil: no prometas su"
            + " alcance ni sus pruebas aquí. Pon antes la parte que define el contrato o la interfaz."
            + " Si algo de este repositorio debe ir al final, hazlo una parte de este repositorio que"
            + " dependa de todas las demás. Si el cambio solo afecta a este repositorio, no añadas"
            + " `parts`.\n");
    return out.toString().replace("{{self}}", item.path("repo").asText());
  }
}
