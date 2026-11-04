package io.agentfarm.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.Json;
import io.agentfarm.core.TwoFactor;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class TwoFactorApi {
  private final TwoFactor twoFactor;

  public TwoFactorApi(TwoFactor twoFactor) {
    this.twoFactor = twoFactor;
  }

  @GetMapping("/api/me/2fa")
  public Object status(Principal p) {
    return twoFactor.status(p.getName());
  }

  @PostMapping("/api/me/2fa/setup")
  public Object setup(Principal p) {
    return twoFactor.setup(p.getName());
  }

  @PostMapping("/api/me/2fa/confirm")
  public Object confirm(@RequestBody JsonNode n, Principal p, HttpServletRequest request) {
    twoFactor.confirm(p.getName(), Json.text(n, "code"));
    request.getSession().setAttribute(TwoFactorFilter.VERIFIED, p.getName());
    return twoFactor.status(p.getName());
  }

  @PostMapping("/api/me/2fa/disable")
  public Object disable(@RequestBody JsonNode n, Authentication auth) {
    boolean owner =
        auth.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("ROLE_OWNER"));
    twoFactor.disable(auth.getName(), Json.text(n, "code"), owner);
    return twoFactor.status(auth.getName());
  }

  @PostMapping("/api/governance/two-factor-to-integrate/{required}")
  public Object required(@PathVariable boolean required, Principal p) {
    twoFactor.requireToIntegrate(required, p.getName());
    return twoFactor.status(p.getName());
  }
}
