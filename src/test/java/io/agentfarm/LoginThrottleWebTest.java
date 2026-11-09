package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;

/**
 * The sign-in limit through the real filters, with a clock the test moves: the form and Basic wait
 * and then recover, and an executor's node token is never held back by them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginThrottleWebTest {
  static Path dir;

  static {
    try {
      dir = Files.createTempDirectory("agentfarm-throttle-");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** A clock moved by hand, shared by the panel under test. */
  static final class Hand extends Clock {
    volatile Instant now = Instant.parse("2026-11-09T10:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  static final Hand CLOCK = new Hand();

  @TestConfiguration
  static class Time {
    @Bean
    @Primary
    Clock handClock() {
      return CLOCK;
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("agentfarm.db", () -> dir.resolve("farm.db").toString());
  }

  @LocalServerPort int port;
  @Autowired NodeCredentials nodes;
  @Autowired Store store;

  final HttpClient client = HttpClient.newBuilder().build();

  String password() throws Exception {
    return Files.readString(dir.resolve("admin-password.txt")).trim();
  }

  HttpResponse<String> basic(String user, String secret) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me"))
            .header(
                "Authorization",
                "Basic "
                    + Base64.getEncoder()
                        .encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** A form sign-in with its own cookies and CSRF token, as a browser does it. */
  HttpResponse<String> form(String user, String secret) throws Exception {
    var browser =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String page =
        browser
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login")).build(),
                HttpResponse.BodyHandlers.ofString())
            .body();
    var m = java.util.regex.Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(page);
    if (!m.find())
      m = java.util.regex.Pattern.compile("value=\"([^\"]+)\"[^>]*name=\"_csrf\"").matcher(page);
    assertTrue(m.find(0), "the sign-in form carries a CSRF token");
    String body =
        "username="
            + URLEncoder.encode(user, StandardCharsets.UTF_8)
            + "&password="
            + URLEncoder.encode(secret, StandardCharsets.UTF_8)
            + "&_csrf="
            + URLEncoder.encode(m.group(1), StandardCharsets.UTF_8);
    return browser.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  static String location(HttpResponse<String> r) {
    return r.headers().firstValue("Location").orElse("");
  }

  @Test
  void formAndBasicWaitThenRecoverWhileANodeTokenKeepsWorking() throws Exception {
    store.tx(c -> {
      Store.exec(c, "INSERT OR IGNORE INTO farm_nodes(id,role) VALUES('throttle-node','napoleon')");
      Store.exec(
          c, "INSERT OR IGNORE INTO budget_accounts VALUES('throttle-sub','subscription','{}')");
      return null;
    });
    String token =
        Json.MAPPER
            .valueToTree(nodes.issue("throttle-node", "throttle-sub", "test"))
            .path("token")
            .asText();
    java.util.concurrent.Callable<Integer> node =
        () ->
            client
                .send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/runtime"))
                        .header("Authorization", "Bearer " + token)
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode();

    // Basic: five wrong passwords, then even the right one waits, with Retry-After.
    for (int i = 0; i < LoginThrottle.FREE_FAILURES; i++)
      assertEquals(401, basic("admin", "wrong-" + i).statusCode(), "attempt " + i);
    var blocked = basic("admin", password());
    assertEquals(429, blocked.statusCode());
    assertEquals(
        String.valueOf(LoginThrottle.FIRST_WAIT_SECONDS),
        blocked.headers().firstValue("Retry-After").orElse(""));
    assertEquals(200, node.call(), "a node token is not a password: the limit doesn't touch it");
    CLOCK.now = CLOCK.now.plusSeconds(LoginThrottle.FIRST_WAIT_SECONDS - 1);
    assertEquals(429, basic("admin", password()).statusCode(), "one second early");
    CLOCK.now = CLOCK.now.plusSeconds(1);
    assertEquals(200, basic("admin", password()).statusCode(), "the wait is over");

    // The form: same limit for another account, said on the sign-in page.
    for (int i = 0; i < LoginThrottle.FREE_FAILURES; i++)
      assertTrue(location(form("nadie", "wrong-" + i)).endsWith("/login?error"), "attempt " + i);
    assertTrue(location(form("nadie", "wrong")).endsWith("/login?blocked"));
    assertEquals(200, node.call());
    CLOCK.now = CLOCK.now.plusSeconds(LoginThrottle.FIRST_WAIT_SECONDS);
    assertTrue(
        location(form("nadie", "wrong")).endsWith("/login?error"),
        "after the wait the form checks the password again");
    assertTrue(
        location(form("admin", password())).matches(".*/(overview)?$"),
        "the owner, who signed in from here before, is not held back");
  }
}
