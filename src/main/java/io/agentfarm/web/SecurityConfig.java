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
import org.springframework.http.HttpMethod;
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
  UserDetailsService users(
      @Value("${agentfarm.db}") String db, @Value("${agentfarm.users-file:}") String usersFile)
      throws IOException {
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
    var users = new ArrayList<UserDetails>();
    users.add(
        User.withUsername("admin")
            .password("{bcrypt}" + new BCryptPasswordEncoder().encode(password))
            .roles("OWNER")
            .build());
    users.addAll(teamUsers(usersFile));
    return new InMemoryUserDetailsManager(users);
  }

  /**
   * Optional team file: [{"username","role":"owner|reviewer|observer","password_hash":"{bcrypt}..."}].
   * Hashes come from `agentfarm.jar user-hash`; the file never holds plain passwords.
   */
  static List<UserDetails> teamUsers(String file) throws IOException {
    if (file == null || file.isBlank()) return List.of();
    var out = new ArrayList<UserDetails>();
    var names = new HashSet<String>(Set.of("admin"));
    for (var u : io.agentfarm.core.Json.parse(Files.readString(Path.of(file)))) {
      String name = u.path("username").asText(),
          role = u.path("role").asText(),
          hash = u.path("password_hash").asText();
      if (!name.matches("[a-z][a-z0-9._-]{1,39}") || !names.add(name))
        throw new IllegalArgumentException("Invalid or duplicate team username: " + name);
      if (!Set.of("owner", "reviewer", "observer").contains(role))
        throw new IllegalArgumentException("Team role must be owner, reviewer or observer");
      if (!hash.startsWith("{bcrypt}$2"))
        throw new IllegalArgumentException("Team passwords must be bcrypt hashes (user-hash)");
      out.add(User.withUsername(name).password(hash).roles(role.toUpperCase(Locale.ROOT)).build());
    }
    return out;
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http, io.agentfarm.core.NodeCredentials nodes, io.agentfarm.core.TwoFactor twoFactor)
      throws Exception {
    String[] people = {"OWNER", "REVIEWER", "OBSERVER"};
    http.authorizeHttpRequests(
            a ->
                a.requestMatchers("/login", "/error", "/api/health")
                    .permitAll()
                    .requestMatchers("/github-app/**")
                    .hasRole("OWNER")
                    // Executor (node credential) operations; NodeTokenFilter narrows them further.
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/providers/admission",
                        "/api/runtime/observations",
                        "/api/budget/**",
                        "/api/heartbeat/**",
                        "/api/work/agent/**")
                    .hasAnyRole("OWNER", "EXECUTOR")
                    .requestMatchers(
                        HttpMethod.GET, "/api/csrf", "/api/overview", "/api/runtime", "/api/budget/**")
                    .hasAnyRole("OWNER", "EXECUTOR", "REVIEWER", "OBSERVER")
                    // Reviewers file requests and decide on proposals; integration stays with owners.
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/work",
                        "/api/work/*/actions/implement",
                        "/api/work/*/actions/correct",
                        "/api/work/*/actions/cancel",
                        "/api/work/*/actions/retry")
                    .hasAnyRole("OWNER", "REVIEWER")
                    .requestMatchers("/api/me/2fa/**")
                    .hasAnyRole(people)
                    .requestMatchers(HttpMethod.GET, "/api/**")
                    .hasAnyRole(people)
                    .requestMatchers("/api/**")
                    .hasRole("OWNER")
                    .anyRequest()
                    .hasAnyRole(people))
        .addFilterBefore(
            new NodeTokenFilter(nodes),
            org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                .class)
        .addFilterBefore(
            new TwoFactorFilter(twoFactor),
            org.springframework.security.web.access.intercept.AuthorizationFilter.class)
        .formLogin(f -> f.loginPage("/login"))
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
