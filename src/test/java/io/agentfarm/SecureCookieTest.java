package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/** Behind a proxy with TLS the panel sees plain HTTP, so it must learn from its public address. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {
      "agentfarm.panel-url=https://panel.example",
      "agentfarm.db=${java.io.tmpdir}/agentfarm-securecookie-test/farm.db"
    })
class SecureCookieTest {
  @LocalServerPort int port;

  @Test
  void theSessionCookieIsSecureWithAnHttpsAddress() throws Exception {
    var answer =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login")).build(),
                HttpResponse.BodyHandlers.ofString());
    var cookie =
        answer.headers().allValues("Set-Cookie").stream()
            .filter(c -> c.startsWith("JSESSIONID"))
            .findFirst()
            .orElseThrow();
    assertTrue(cookie.contains("Secure"), cookie);
    assertTrue(cookie.contains("HttpOnly"), cookie);
    assertTrue(cookie.contains("SameSite=Strict"), cookie);
  }
}
