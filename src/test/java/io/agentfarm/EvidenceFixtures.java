package io.agentfarm;

import static io.agentfarm.core.Json.*;

import io.agentfarm.core.*;
import java.time.Clock;
import java.util.UUID;

final class EvidenceFixtures {
  static void accept(Store store, Clock clock, String id) {
    var service = new EvidenceService(store, clock);
    var dossier = MAPPER.valueToTree(service.get(id));
    for (var criterion : dossier.path("criteria")) {
      service.decide(
          id,
          MAPPER.valueToTree(
              obj(
                  "request_id",
                  UUID.randomUUID().toString(),
                  "version",
                  dossier.path("version").asInt(),
                  "sha",
                  dossier.path("sha").asText(),
                  "basis",
                  dossier.path("basis").asText(),
                  "criterion",
                  criterion.path("id").asText(),
                  "kind",
                  "manual",
                  "reason",
                  "Manual acceptance fixture checked by the human test actor",
                  "environment",
                  "JUnit fixture; no provider calls")),
          "owner");
    }
  }
}
