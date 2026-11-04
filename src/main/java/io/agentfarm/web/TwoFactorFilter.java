package io.agentfarm.web;

import io.agentfarm.core.TwoFactor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A password is not enough for an account with a second factor: its session must have passed a
 * code. HTTP Basic carries no session to remember that, so those accounts cannot use it.
 */
final class TwoFactorFilter extends OncePerRequestFilter {
  static final String VERIFIED = "agentfarm.two-factor-verified";
  private static final Set<String> OPEN =
      Set.of("/login", "/login/2fa", "/logout", "/error", "/api/health");
  private final TwoFactor twoFactor;

  TwoFactorFilter(TwoFactor twoFactor) {
    this.twoFactor = twoFactor;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null
        || !auth.isAuthenticated()
        || auth instanceof AnonymousAuthenticationToken
        || auth.getName().startsWith("node:")
        || OPEN.contains(request.getServletPath())
        || !twoFactor.enabled(auth.getName())) {
      chain.doFilter(request, response);
      return;
    }
    var session = request.getSession(false);
    if (session != null && auth.getName().equals(session.getAttribute(VERIFIED))) {
      chain.doFilter(request, response);
      return;
    }
    if (request.getServletPath().startsWith("/api/")
        || request.getHeader("Authorization") != null) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response
          .getWriter()
          .write("{\"error\":\"Esta cuenta usa segundo factor: entra desde el panel.\"}");
      return;
    }
    response.sendRedirect("/login/2fa");
  }
}
