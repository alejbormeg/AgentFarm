package io.agentfarm.web;

import io.agentfarm.core.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class NodeTokenFilter extends OncePerRequestFilter {
  private final NodeCredentials credentials;

  public NodeTokenFilter(NodeCredentials credentials) {
    this.credentials = credentials;
  }

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String authorization = request.getHeader("Authorization");
    if (authorization != null && authorization.startsWith("Bearer ")) {
      var identity = credentials.authenticate(authorization.substring(7));
      if (identity == null) {
        response.sendError(401);
        return;
      }
      String path = request.getRequestURI(), method = request.getMethod();
      boolean allowed =
          method.equals("GET")
              && (Set.of("/api/csrf", "/api/overview", "/api/runtime").contains(path)
                  || path.equals("/api/budget/" + identity.get("account"))
                  || path.equals("/api/budget/" + identity.get("account") + "/reservations"));
      allowed |=
          method.equals("POST")
              && (Set.of(
                          "/api/work/agent/claim",
                          "/api/runtime/observations",
                          "/api/budget/observe",
                          "/api/budget/reserve",
                          "/api/budget/settle-observed",
                          "/api/budget/rollover")
                      .contains(path)
                  || path.startsWith("/api/work/agent/commands/")
                  || path.startsWith("/api/heartbeat/"));
      if (!allowed) {
        response.sendError(403);
        return;
      }
      request.setAttribute("agentfarm.node", identity.get("node"));
      request.setAttribute("agentfarm.account", identity.get("account"));
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  "node:" + identity.get("node"),
                  null,
                  List.of(new SimpleGrantedAuthority("ROLE_EXECUTOR"))));
    }
    chain.doFilter(request, response);
  }
}
