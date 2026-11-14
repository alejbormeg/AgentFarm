package io.agentfarm.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.adapters.NodeEnrollment;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
public class EnrollmentApi {
  private static final String URL = "https?://[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?";
  private final NodeEnrollment enrollment;
  private final String panelUrl;

  public EnrollmentApi(
      NodeEnrollment enrollment, @Value("${agentfarm.panel-url:}") String panelUrl) {
    this.enrollment = enrollment;
    String trimmed = panelUrl.strip().replaceAll("/+$", "");
    this.panelUrl = trimmed.matches(URL) ? trimmed : "http://localhost:8080";
  }

  /** Owner: a one-use code and the command to run on the new machine. */
  @PostMapping("/api/executors/enroll")
  public Object enroll(@RequestBody JsonNode n, Principal p) {
    var created =
        enrollment.create(n.path("node").asText(""), n.path("account").asText(""), p.getName());
    var out = new LinkedHashMap<String, Object>(created);
    out.put(
        "command",
        "curl -fsSo agentfarm-install.sh "
            + panelUrl
            + "/executor/install.sh && bash agentfarm-install.sh --panel "
            + panelUrl
            + " --code "
            + created.get("code"));
    return out;
  }

  /** The new machine, with no session: only the one-use code opens this. */
  @PostMapping("/executor/redeem")
  public Object redeem(@RequestBody JsonNode n) {
    return enrollment.redeem(n.path("code").asText(""));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
  public Object invalid(IllegalArgumentException e) {
    return Map.of("error", e.getMessage());
  }
}
