package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Synthetic credentials only, built at run time so no scanner mistakes them for real ones. */
class RedactionTest {
  static String run(String prefix, int n) {
    return prefix + "Ab1_".repeat(n / 4 + 1).substring(0, n);
  }

  static final String PEM_BEGIN = "-----BEGIN " + "RSA PRIVATE KEY-----",
      PEM_END = "-----END " + "RSA PRIVATE KEY-----";

  /** One sample per format the policy must hide. */
  static Map<String, String> samples() {
    var s = new LinkedHashMap<String, String>();
    s.put("github fine-grained", run("github" + "_pat_", 60));
    s.put("github classic", run("gh" + "p_", 36));
    s.put("github app", run("gh" + "s_", 36));
    s.put("github oauth", run("gh" + "o_", 36));
    s.put("agentfarm node", "af" + "n_" + "x".repeat(43));
    s.put("openai", run("s" + "k-proj-", 40));
    s.put("anthropic", run("s" + "k-ant-", 40));
    s.put("aws", "AK" + "IA" + "ABCDEFGHIJKLMNOP");
    s.put("google", "AI" + "za" + "x".repeat(35));
    s.put("slack", "xo" + "xb-" + "1234567890-abcdef");
    s.put("bearer", "Bearer " + "abc.def.ghi");
    s.put("assignment", "password=" + "hunter2hunter2");
    s.put(
        "private key",
        PEM_BEGIN + "\nMIIEowIBAAKCAQEA0abc\nZ9+/xyz=\n" + PEM_END);
    return s;
  }

  static String secretPart(String sample) {
    if (sample.startsWith("Bearer ")) return sample.substring(7);
    if (sample.startsWith("password=")) return sample.substring(9);
    if (sample.startsWith("-----BEGIN")) return "MIIEowIBAAKCAQEA0abc";
    return sample;
  }

  @Test
  void everyKnownFormatIsHiddenInTextAndInLogs() {
    for (var e : samples().entrySet()) {
      String line = "fallo: " + e.getValue() + " al publicar";
      for (String out :
          List.of(
              Redaction.secrets(line),
              Redaction.text(line),
              ExecutorWork.clean(line),
              TelemetryService.clean(line)))
        assertFalse(out.contains(secretPart(e.getValue())), e.getKey() + " -> " + out);
      assertTrue(Redaction.secrets(line).startsWith("fallo: "), e.getKey());
      assertTrue(Redaction.secrets(line).endsWith(" al publicar"), e.getKey());
    }
  }

  @Test
  void theExecutorAndTelemetryShareOnePolicy() {
    for (String sample : samples().values()) {
      String line = "\u001B[31mx\u0007 " + sample + "\n";
      assertEquals(Redaction.text(line), ExecutorWork.clean(line));
      assertEquals(Redaction.text(line), TelemetryService.clean(line));
    }
    assertEquals("Sin detalle del proveedor", ExecutorWork.clean(null));
  }

  @Test
  void encodedJsonStaysValidAndHasNoCredential() throws Exception {
    var evidence = new LinkedHashMap<String, Object>();
    int i = 0;
    for (String sample : samples().values()) evidence.put("k" + i++, "salida " + sample + " fin");
    evidence.put("quoted", "password=ab\"cd");
    evidence.put("json", "{\"api_key\": \"" + run("s" + "k-", 30) + "\"}");
    evidence.put("unterminated", PEM_BEGIN + "\nAAAA\nBBBB");
    evidence.put("plain", "sin secretos: ghp, sk, afn y Bearer a secas");
    String encoded = Json.encode(evidence), redacted = Redaction.secrets(encoded);
    var back = Json.parse(redacted);
    for (String sample : samples().values())
      assertFalse(redacted.contains(secretPart(sample)), sample);
    assertFalse(redacted.contains("AAAA"));
    assertEquals("sin secretos: ghp, sk, afn y Bearer a secas", back.path("plain").asText());
    assertEquals(redacted, Redaction.secrets(redacted), "idempotent");
  }

  @Test
  void nullStaysNull() {
    assertNull(Redaction.secrets(null));
    assertNull(Redaction.text(null));
  }
}
