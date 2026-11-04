package io.agentfarm.core;

import static io.agentfarm.core.Store.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Optional TOTP second factor (RFC 6238), compatible with any authenticator app. A code works once,
 * and repeated failures lock the account for a while. Verifying returns a result instead of
 * throwing, because the failure count must survive the transaction.
 */
@Component
public class TwoFactor {
  private static final int STEP_SECONDS = 30, DRIFT_STEPS = 1, MAX_FAILURES = 5, LOCK_SECONDS = 300;
  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  public record Enrollment(String secret, String uri) {}

  private final Store store;
  private final Clock clock;

  public TwoFactor(Store store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public static String code(String secret, long step) {
    try {
      var mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
      byte[] hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array());
      int offset = hash[hash.length - 1] & 0xf;
      int value = java.nio.ByteBuffer.wrap(hash, offset, 4).getInt() & 0x7fffffff;
      return String.format("%06d", value % 1_000_000);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA1 unavailable", e);
    }
  }

  public static String encode(byte[] bytes) {
    var out = new StringBuilder();
    int buffer = 0, bits = 0;
    for (byte b : bytes) {
      buffer = buffer << 8 | b & 0xff;
      bits += 8;
      while (bits >= 5) {
        out.append(ALPHABET.charAt(buffer >> bits - 5 & 31));
        bits -= 5;
      }
    }
    if (bits > 0) out.append(ALPHABET.charAt(buffer << 5 - bits & 31));
    return out.toString();
  }

  static byte[] decode(String secret) {
    var out = new java.io.ByteArrayOutputStream();
    int buffer = 0, bits = 0;
    for (char c : secret.toCharArray()) {
      int value = ALPHABET.indexOf(c);
      if (value < 0) throw new IllegalArgumentException("Invalid secret");
      buffer = buffer << 5 | value;
      bits += 5;
      if (bits >= 8) {
        out.write(buffer >> bits - 8 & 0xff);
        bits -= 8;
      }
    }
    return out.toByteArray();
  }

  private long step() {
    return clock.instant().getEpochSecond() / STEP_SECONDS;
  }

  public boolean enabled(String user) {
    return store.tx(
        c -> one(c, "SELECT 1 FROM user_totp WHERE username=? AND confirmed=1", user) != null);
  }

  public boolean requiredToIntegrate() {
    return store.tx(
        c -> num(one(c, "SELECT require_2fa_merge v FROM governance WHERE id=1"), "v") == 1);
  }

  /** A new secret, unusable until a code from the app confirms it was saved. */
  public Enrollment setup(String user) {
    if (enabled(user)) throw new IllegalArgumentException("Ya tienes el segundo factor activo");
    byte[] bytes = new byte[20];
    new SecureRandom().nextBytes(bytes);
    String secret = encode(bytes);
    store.tx(c ->
        exec(
            c,
            "INSERT INTO user_totp(username,secret) VALUES(?,?) ON CONFLICT(username) DO UPDATE SET"
                + " secret=excluded.secret,last_step=0,failures=0,locked_until=0",
            user,
            secret));
    String label =
        URLEncoder.encode("AgentFarm:" + user, StandardCharsets.UTF_8).replace("+", "%20");
    return new Enrollment(
        secret,
        "otpauth://totp/" + label + "?secret=" + secret + "&issuer=AgentFarm&digits=6&period=30");
  }

  public void confirm(String user, String code) {
    boolean accepted =
        store.tx(c -> {
          if (!matches(c, user, code)) return false;
          exec(c, "UPDATE user_totp SET confirmed=1 WHERE username=?", user);
          audit(c, user, "2fa.enable", user);
          return true;
        });
    if (!accepted)
      throw new IllegalArgumentException("Código incorrecto. Comprueba la hora del teléfono.");
  }

  /** Whether this code is good for an account with the second factor active. */
  public boolean verify(String user, String code) {
    return store.tx(c ->
        one(c, "SELECT 1 FROM user_totp WHERE username=? AND confirmed=1", user) != null
            && matches(c, user, code));
  }

  /** An owner cannot switch it off while integrating depends on it, or nobody could integrate. */
  public void disable(String user, String code, boolean owner) {
    if (owner && requiredToIntegrate())
      throw new IllegalArgumentException("Antes deja de exigir el segundo factor para integrar");
    boolean accepted =
        store.tx(c -> {
          if (one(c, "SELECT 1 FROM user_totp WHERE username=? AND confirmed=1", user) == null
              || !matches(c, user, code)) return false;
          exec(c, "DELETE FROM user_totp WHERE username=?", user);
          audit(c, user, "2fa.disable", user);
          return true;
        });
    if (!accepted) throw new IllegalArgumentException("Código incorrecto.");
  }

  /** For whoever has lost their phone; needs access to the host, not to the panel. */
  public boolean reset(String user, String actor) {
    return store.tx(c -> {
      boolean removed = exec(c, "DELETE FROM user_totp WHERE username=?", user) > 0;
      if (removed) audit(c, actor, "2fa.reset", user);
      return removed;
    });
  }

  /** Only someone with the factor active can change this, in either direction. */
  public void requireToIntegrate(boolean required, String actor) {
    if (!enabled(actor)) throw new IllegalArgumentException("Activa primero tu segundo factor");
    store.tx(c -> {
      exec(c, "UPDATE governance SET require_2fa_merge=? WHERE id=1", required ? 1 : 0);
      audit(c, actor, required ? "2fa.require" : "2fa.optional", "integration");
      return null;
    });
  }

  public void checkIntegration(String user) {
    if (requiredToIntegrate() && !enabled(user))
      throw new IllegalArgumentException("Para integrar, activa tu segundo factor en Gobierno.");
  }

  public Map<String, Object> status(String user) {
    return Json.obj("active", enabled(user), "required_to_integrate", requiredToIntegrate());
  }

  private boolean matches(java.sql.Connection c, String user, String code) throws Exception {
    var row = one(c, "SELECT * FROM user_totp WHERE username=?", user);
    if (row == null || code == null || !code.matches("[0-9]{6}")) return false;
    long now = clock.instant().getEpochSecond();
    if (num(row, "locked_until") > now) return false;
    long current = step(), used = num(row, "last_step");
    for (long s = current - DRIFT_STEPS; s <= current + DRIFT_STEPS; s++) {
      if (s <= used
          || !java.security.MessageDigest.isEqual(
              code(row.get("secret").toString(), s).getBytes(StandardCharsets.UTF_8),
              code.getBytes(StandardCharsets.UTF_8))) continue;
      exec(
          c,
          "UPDATE user_totp SET last_step=?,failures=0,locked_until=0 WHERE username=?",
          s,
          user);
      return true;
    }
    long failures = num(row, "failures") + 1;
    exec(
        c,
        "UPDATE user_totp SET failures=?,locked_until=? WHERE username=?",
        failures >= MAX_FAILURES ? 0 : failures,
        failures >= MAX_FAILURES ? now + LOCK_SECONDS : 0,
        user);
    return false;
  }
}
