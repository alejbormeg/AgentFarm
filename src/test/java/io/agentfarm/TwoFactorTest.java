package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TwoFactorTest {
  @TempDir Path dir;
  Store store;
  MutableClock clock = new MutableClock();
  TwoFactor factor;

  static final class MutableClock extends Clock {
    Instant now = Instant.ofEpochSecond(1_800_000_000L);

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  @BeforeEach
  void setUp() {
    store = new Store(dir.resolve("farm.db").toString());
    new SchemaMigrations(
        store,
        new WorkflowService(store, clock),
        new TelemetryService(store, clock),
        new NodeCredentials(store, clock));
    factor = new TwoFactor(store, clock);
  }

  String current(String secret) {
    return TwoFactor.code(secret, clock.now.getEpochSecond() / 30);
  }

  String enrolled(String user) {
    String secret = factor.setup(user).secret();
    factor.confirm(user, current(secret));
    clock.now = clock.now.plusSeconds(90);
    return secret;
  }

  @Test
  void matchesTheRfc6238Vectors() {
    String secret = TwoFactor.encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
    assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", secret);
    assertEquals("287082", TwoFactor.code(secret, 59 / 30));
    assertEquals("081804", TwoFactor.code(secret, 1111111109L / 30));
    assertEquals("005924", TwoFactor.code(secret, 1234567890L / 30));
  }

  @Test
  void isOnlyActiveOnceACodeConfirmsIt() {
    var enrollment = factor.setup("rita");
    assertTrue(
        enrollment
            .uri()
            .startsWith("otpauth://totp/AgentFarm%3Arita?secret=" + enrollment.secret()));
    assertFalse(factor.enabled("rita"));
    assertFalse(
        factor.verify("rita", current(enrollment.secret())), "not usable before confirming");
    assertThrows(IllegalArgumentException.class, () -> factor.confirm("rita", "000000"));
    assertFalse(factor.enabled("rita"));
    factor.confirm("rita", current(enrollment.secret()));
    assertTrue(factor.enabled("rita"));
    assertThrows(
        IllegalArgumentException.class,
        () -> factor.setup("rita"),
        "cannot silently replace an active one");
  }

  @Test
  void aCodeWorksOnceAndOnlyNearItsTime() {
    String secret = enrolled("rita");
    String code = current(secret);
    assertTrue(factor.verify("rita", code));
    assertFalse(factor.verify("rita", code), "a replay is refused");
    clock.now = clock.now.plusSeconds(600);
    assertFalse(factor.verify("rita", code), "an old code is refused");
    assertTrue(
        factor.verify("rita", TwoFactor.code(secret, clock.now.getEpochSecond() / 30 - 1)),
        "one step of drift");
    for (String bad : new String[] {null, "", "12345", "abcdef", "1234567"})
      assertFalse(factor.verify("rita", bad));
    assertFalse(factor.verify("oscar", "123456"), "an account without it has nothing to verify");
  }

  @Test
  void repeatedFailuresLockTheAccountForAWhile() {
    String secret = enrolled("rita");
    String wrong = "000000".equals(current(secret)) ? "000001" : "000000";
    for (int i = 0; i < 5; i++) assertFalse(factor.verify("rita", wrong));
    assertFalse(factor.verify("rita", current(secret)), "locked: even the right code fails");
    clock.now = clock.now.plusSeconds(301);
    assertTrue(factor.verify("rita", current(secret)));
  }

  @Test
  void integratingCanRequireIt() {
    assertDoesNotThrow(() -> factor.checkIntegration("admin"), "optional by default");
    assertThrows(
        IllegalArgumentException.class,
        () -> factor.requireToIntegrate(true, "admin"),
        "nobody can demand what they do not have themselves");
    String secret = enrolled("admin");
    factor.requireToIntegrate(true, "admin");
    assertDoesNotThrow(() -> factor.checkIntegration("admin"));
    assertThrows(IllegalArgumentException.class, () -> factor.checkIntegration("olga"));
    assertThrows(
        IllegalArgumentException.class,
        () -> factor.disable("admin", current(secret), true),
        "the owner cannot lock everyone out of integrating");
    factor.requireToIntegrate(false, "admin");
    factor.disable("admin", current(secret), true);
    assertFalse(factor.enabled("admin"));
    assertDoesNotThrow(() -> factor.checkIntegration("olga"));
  }

  @Test
  void theHostCanResetALostPhone() {
    enrolled("rita");
    assertTrue(factor.reset("rita", "host-operator"));
    assertFalse(factor.enabled("rita"));
    assertFalse(factor.reset("rita", "host-operator"));
    boolean audited =
        store.tx(c ->
            Store.one(c, "SELECT 1 FROM audit WHERE action='2fa.reset' AND subject='rita'")
                != null);
    assertTrue(audited);
  }
}
