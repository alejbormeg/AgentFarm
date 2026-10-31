package io.agentfarm.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@ConditionalOnWebApplication
public class SecurityConfig {
  @Bean
  UserDetailsService users(@Value("${agentfarm.db}") String db) throws IOException {
    String password = System.getenv("AGENTFARM_PASSWORD");
    if (password == null || password.isBlank()) {
      Path file = Path.of(db).toAbsolutePath().getParent().resolve("admin-password.txt");
      Files.createDirectories(file.getParent());
      if (Files.exists(file)) password = Files.readString(file).trim();
      else {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
          Files.createFile(
              file,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
          Files.createFile(file);
        }
        Files.writeString(file, password);
      }
      System.out.println("AgentFarm administrator password file: " + file);
    }
    if (password.length() < 16)
      throw new IllegalArgumentException("AGENTFARM_PASSWORD must contain at least 16 characters");
    return new InMemoryUserDetailsManager(
        User.withUsername("admin")
            .password("{bcrypt}" + new BCryptPasswordEncoder().encode(password))
            .roles("OWNER")
            .build());
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, io.agentfarm.core.NodeCredentials nodes)
      throws Exception {
    http.authorizeHttpRequests(
            a ->
                a.requestMatchers("/login", "/error")
                    .permitAll()
                    .requestMatchers(
                        "/api/csrf",
                        "/api/overview",
                        "/api/runtime",
                        "/api/runtime/observations",
                        "/api/budget/**",
                        "/api/heartbeat/**",
                        "/api/work/agent/**")
                    .hasAnyRole("OWNER", "EXECUTOR")
                    .anyRequest()
                    .hasRole("OWNER"))
        .addFilterBefore(
            new NodeTokenFilter(nodes),
            org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                .class)
        .formLogin(Customizer.withDefaults())
        .httpBasic(Customizer.withDefaults())
        .csrf(c -> c.ignoringRequestMatchers("/mateu/**"))
        .addFilterBefore(new MateuOriginFilter(), CsrfFilter.class);
    return http.build();
  }

  // Mateu actions use JSON without Spring's CSRF token. Require their browser origin
  // to exactly match the local target; other application APIs keep Spring CSRF protection.
  static class MateuOriginFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
      if (req.getRequestURI().startsWith("/mateu/")
          && !Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod())) {
        String origin = req.getHeader("Origin");
        boolean valid = false;
        try {
          URI uri = URI.create(origin == null ? "" : origin);
          int port =
              uri.getPort() == -1 ? (uri.getScheme().equals("https") ? 443 : 80) : uri.getPort();
          valid =
              Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(req.getServerName())
                  && Objects.equals(uri.getScheme(), req.getScheme())
                  && Objects.equals(uri.getHost(), req.getServerName())
                  && port == req.getServerPort()
                  && (req.getContentType() != null
                      && req.getContentType().startsWith("application/json"));
        } catch (Exception ignored) {
        }
        if (!valid) {
          res.sendError(403, "Same-origin JSON request required");
          return;
        }
      }
      chain.doFilter(req, res);
    }
  }
}
