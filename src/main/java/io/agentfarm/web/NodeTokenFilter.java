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
  private final io.agentfarm.adapters.RequesterCredentials requesters;

  public NodeTokenFilter(
      NodeCredentials credentials, io.agentfarm.adapters.RequesterCredentials requesters) {
    this.credentials = credentials;
    this.requesters = requesters;
  }

  /**
   * What a requester (an MCP client) may do: read tasks, the overview and its own identity, and
   * file a request. Nothing that approves, corrects, integrates or reads the audit.
   */
  static boolean requesterMay(String method, String path) {
    if (method.equals("POST")) return path.equals("/api/work");
    return method.equals("GET")
        && (Set.of("/api/csrf", "/api/me", "/api/overview", "/api/runtime", "/api/work")
                .contains(path)
            || path.matches("/api/work/[A-Za-z0-9-]{1,80}"));
  }

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String authorization = request.getHeader("Authorization");
    if (authorization != null
        && authorization.startsWith(
            "Bearer " + io.agentfarm.adapters.RequesterCredentials.PREFIX)) {
      String label = requesters.authenticate(authorization.substring(7));
      if (label == null) {
        response.sendError(401);
        return;
      }
      if (!requesterMay(request.getMethod(), request.getRequestURI())) {
        response.sendError(403);
        return;
      }
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  "requester:" + label,
                  null,
                  List.of(new SimpleGrantedAuthority("ROLE_REQUESTER"))));
    } else if (authorization != null && authorization.startsWith("Bearer ")) {
      var identity = credentials.authenticate(authorization.substring(7));
      if (identity == null) {
        response.sendError(401);
        return;
      }
      String path = request.getRequestURI(), method = request.getMethod();
      boolean allowed =
          method.equals("GET")
              && (Set.of("/api/csrf", "/api/overview", "/api/runtime", "/api/executors/me")
                      .contains(path)
                  || path.equals("/api/budget/" + identity.get("account"))
                  || path.equals("/api/budget/" + identity.get("account") + "/reservations")
                  || (!identity.get("api_account").toString().isEmpty()
                      && path.equals("/api/budget/" + identity.get("api_account"))));
      allowed |=
          method.equals("POST")
              && (Set.of(
                          "/api/providers/admission",
                          "/api/work/agent/claim",
                          "/api/runtime/observations",
                          "/api/budget/observe",
                          "/api/budget/reserve",
                          "/api/budget/settle-observed",
                          "/api/budget/settle",
                          "/api/budget/rollover")
                      .contains(path)
                  || path.startsWith("/api/work/agent/commands/")
                  || path.startsWith("/api/heartbeat/"));
      if (!allowed) {
        response.sendError(403);
        return;
      }
      if (((Number) identity.get("enabled")).intValue() != 1
          && !NodeCredentials.allowedWhileDisabled(method, path)) {
        response.sendError(403, "Nodo deshabilitado");
        return;
      }
      request.setAttribute("agentfarm.node", identity.get("node"));
      request.setAttribute("agentfarm.account", identity.get("account"));
      request.setAttribute("agentfarm.api-account", identity.get("api_account"));
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
