package io.agentfarm.web;

import io.agentfarm.core.LoginThrottle;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.web.filter.OncePerRequestFilter;

/** Stops a blocked key before its password is even checked, so a right guess reveals nothing. */
final class LoginThrottleFilter extends OncePerRequestFilter {
  private final LoginThrottle throttle;

  LoginThrottleFilter(LoginThrottle throttle) {
    this.throttle = throttle;
  }

  static String key(String username, String address) {
    return username + "|" + address;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    boolean form = "POST".equals(request.getMethod()) && "/login".equals(request.getServletPath());
    String username = form ? request.getParameter("username") : basicUsername(request);
    long wait = username == null ? 0 : throttle.waitSeconds(key(username, request.getRemoteAddr()));
    if (wait == 0) {
      chain.doFilter(request, response);
      return;
    }
    if (form) {
      response.sendRedirect(request.getContextPath() + "/login?blocked");
      return;
    }
    response.setStatus(429);
    response.setHeader("Retry-After", String.valueOf(wait));
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"error\":\"Demasiados intentos. Espera y vuelve a probar.\"}");
  }

  private static String basicUsername(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) return null;
    try {
      String decoded =
          new String(
              Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
      int colon = decoded.indexOf(':');
      return colon < 0 ? null : decoded.substring(0, colon);
    } catch (IllegalArgumentException malformed) {
      return null; // Spring answers a malformed header itself.
    }
  }
}
