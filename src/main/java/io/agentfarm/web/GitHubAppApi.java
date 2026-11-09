package io.agentfarm.web;

import io.agentfarm.core.GitHubAppSetup;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

/**
 * The owner's side of GitHub's manifest flow. The two GET addresses are where GitHub sends the
 * browser back.
 */
@RestController
public class GitHubAppApi {
  private final GitHubAppSetup setup;

  /** The configured panel address, only if GitHub can reach it: the App's webhook goes there. */
  private final Optional<String> publicUrl;

  public GitHubAppApi(GitHubAppSetup setup, @Value("${agentfarm.panel-url:}") String panelUrl) {
    this.setup = setup;
    this.publicUrl = GitHubAppSetup.publicUrl(panelUrl);
  }

  @GetMapping("/api/github-app")
  public Object status() throws Exception {
    var status = new LinkedHashMap<>(setup.status());
    status.put("webhook", publicUrl.isPresent());
    return status;
  }

  @PostMapping("/api/github-app/start")
  public Object start(HttpServletRequest request) {
    String url = request.getRequestURL().toString();
    return setup.start(url.substring(0, url.indexOf(request.getRequestURI())), publicUrl);
  }

  @GetMapping("/github-app/created")
  public ResponseEntity<String> created(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String state,
      Principal p) {
    try {
      return redirect(setup.created(code, state, p.getName()));
    } catch (Exception e) {
      return problem(e);
    }
  }

  @GetMapping("/github-app/installed")
  public ResponseEntity<String> installed(
      @RequestParam(name = "installation_id", required = false) String installation,
      @RequestParam(required = false) String state,
      Principal p) {
    try {
      setup.installed(installation, state, p.getName());
      return redirect("/governance");
    } catch (Exception e) {
      return problem(e);
    }
  }

  private static ResponseEntity<String> redirect(String to) {
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(to)).build();
  }

  private static ResponseEntity<String> problem(Exception e) {
    return ResponseEntity.badRequest()
        .header("Content-Type", "text/html;charset=UTF-8")
        .body(
            "<!doctype html><html lang=\"es\"><meta charset=\"utf-8\"><title>GitHub · AgentFarm</title><main><h1>No se pudo conectar GitHub</h1><p>"
                + HtmlUtils.htmlEscape(String.valueOf(e.getMessage()))
                + "</p><p><a href=\"/governance\">Volver a Gobierno</a></p></main></html>");
  }
}
