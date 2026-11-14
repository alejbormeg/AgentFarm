package io.agentfarm.adapters;

import static io.agentfarm.core.Store.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentfarm.core.ControlService;
import io.agentfarm.core.Store;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Which provider each role uses on a node, chosen by the owner in the panel. It travels with the
 * order; the executor applies it only if that provider is configured on its own machine, where the
 * credentials live. No secret ever passes through here.
 */
@Service
public class NodeProviders {
  public static final List<String> ROLES = List.of("research", "implement", "review");
  public static final Set<String> PROVIDERS = Set.of("codex", "claude-code", "antigravity");
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Store db;
  private final Clock clock;

  public NodeProviders(Store db, Clock clock) {
    this.db = db;
    this.clock = clock;
    db.tx(c -> {
      statement(
          c,
          "CREATE TABLE IF NOT EXISTS node_providers(node TEXT PRIMARY KEY,payload TEXT NOT"
              + " NULL,updated INTEGER NOT NULL,actor TEXT NOT NULL)");
      return null;
    });
  }

  /** The owner's choice for a node ({} when none), as role to provider. */
  public JsonNode roles(String node) {
    ControlService.identifier(node);
    return db.tx(c -> {
      var row = one(c, "SELECT payload FROM node_providers WHERE node=?", node);
      return row == null
          ? MAPPER.createObjectNode()
          : MAPPER.readTree(row.get("payload").toString());
    });
  }

  public JsonNode save(String node, JsonNode input, String actor) {
    if (actor.startsWith("node:"))
      throw new IllegalArgumentException("Solo el propietario elige los proveedores");
    ControlService.identifier(node);
    var clean = MAPPER.createObjectNode();
    for (String role : ROLES) {
      String provider = input.path("roles").path(role).asText("");
      if (provider.isEmpty()) continue;
      if (!PROVIDERS.contains(provider))
        throw new IllegalArgumentException("Proveedor desconocido para " + role + ": " + provider);
      clean.put(role, provider);
    }
    db.tx(c -> {
      if (one(c, "SELECT 1 FROM farm_nodes WHERE id=?", node) == null)
        throw new IllegalArgumentException("Nodo desconocido");
      exec(
          c,
          "INSERT INTO node_providers(node,payload,updated,actor) VALUES(?,?,?,?) ON CONFLICT(node)"
              + " DO UPDATE SET payload=excluded.payload,updated=excluded.updated,actor=excluded.actor",
          node,
          clean.toString(),
          clock.instant().getEpochSecond(),
          actor);
      audit(c, actor, "node.providers.save", node + " " + clean);
      return null;
    });
    return clean;
  }
}
