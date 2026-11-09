package io.agentfarm.web;

import jakarta.servlet.http.*;
import java.io.IOException;
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
   * Optional team file:
   * [{"username","role":"owner|reviewer|observer","password_hash":"{bcrypt}..."}]. Hashes come from
   * `agentfarm.jar user-hash`; the file never holds plain passwords.
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
  io.agentfarm.core.LoginThrottle loginThrottle(java.time.Clock clock) {
    return new io.agentfarm.core.LoginThrottle(clock);
  }

  /** Behind a proxy with TLS the panel sees plain HTTP: its public address says what is true. */
  @Bean
  org.springframework.boot.web.servlet.ServletContextInitializer secureCookie(
      @Value("${agentfarm.panel-url:}") String panelUrl) {
    return context -> {
      if (panelUrl.startsWith("https://")) context.getSessionCookieConfig().setSecure(true);
    };
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      io.agentfarm.core.NodeCredentials nodes,
      io.agentfarm.core.TwoFactor twoFactor,
      io.agentfarm.core.LoginThrottle throttle)
      throws Exception {
    String[] people = {"OWNER", "REVIEWER", "OBSERVER"};
    http.authorizeHttpRequests(a ->
            a.requestMatchers("/login", "/error", "/api/health", "/art/**", "/fonts/**")
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
            new LoginThrottleFilter(throttle),
            org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                .class)
        .addFilterBefore(
            new NodeTokenFilter(nodes),
            org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                .class)
        .addFilterBefore(
            new TwoFactorFilter(twoFactor),
            org.springframework.security.web.access.intercept.AuthorizationFilter.class)
        // The panel's own calls get JSON they can act on, never the sign-in page: an expired
        // session (401), a missing permission or form token (403).
        .exceptionHandling(e ->
            e.defaultAuthenticationEntryPointFor(
                    SecurityConfig::sessionExpired, SecurityConfig::api)
                .defaultAccessDeniedHandlerFor(SecurityConfig::denied, SecurityConfig::api))
        .formLogin(f -> f.loginPage("/login"))
        .httpBasic(Customizer.withDefaults())
        // Everything is served from here. Inline styles remain for the sign-in page and Mermaid's
        // diagrams.
        // GitHub receives one form: the App manifest, when an owner connects it.
        .headers(h ->
            h.contentSecurityPolicy(c ->
                c.policyDirectives(
                    "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'"
                        + " data:; font-src 'self'; connect-src 'self'; manifest-src 'self'; base-uri 'none';"
                        + " frame-ancestors 'none'; form-action 'self' https://github.com")));
    return http.build();
  }

  static boolean api(jakarta.servlet.http.HttpServletRequest request) {
    return request.getRequestURI().startsWith("/api/");
  }

  static void json(
      jakarta.servlet.http.HttpServletResponse response, int status, String reason, String error)
      throws java.io.IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response
        .getWriter()
        .write(
            io.agentfarm.core.Json.encode(
                io.agentfarm.core.Json.obj("error", error, "reason", reason)));
  }

  static void sessionExpired(
      jakarta.servlet.http.HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response,
      org.springframework.security.core.AuthenticationException e)
      throws java.io.IOException {
    json(response, 401, "session_expired", "Tu sesión ha caducado. Entra de nuevo para seguir.");
  }

  static void denied(
      jakarta.servlet.http.HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response,
      org.springframework.security.access.AccessDeniedException e)
      throws java.io.IOException {
    if (e instanceof org.springframework.security.web.csrf.CsrfException)
      json(
          response,
          403,
          "csrf",
          "El formulario ha caducado. Vuelve a intentarlo; si sigue fallando, recarga la página.");
    else json(response, 403, "forbidden", "Tu cuenta no tiene permiso para esta acción.");
  }
}
