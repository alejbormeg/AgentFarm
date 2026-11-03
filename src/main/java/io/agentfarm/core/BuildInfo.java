package io.agentfarm.core;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The commit a panel or executor was built from, written by the packaging scripts. */
public final class BuildInfo {
  public static final String UNKNOWN = "unknown";

  private BuildInfo() {}

  public static String commit() {
    try (InputStream in = BuildInfo.class.getResourceAsStream("/build-commit.txt")) {
      if (in == null) return UNKNOWN;
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
      return text.isEmpty() ? UNKNOWN : text;
    } catch (java.io.IOException e) {
      return UNKNOWN;
    }
  }

  /** Only two known, different commits count: a local build without one is not an error. */
  public static boolean mismatch(String panel, String executor) {
    return !UNKNOWN.equals(panel)
        && executor != null
        && !executor.isBlank()
        && !UNKNOWN.equals(executor)
        && !panel.equals(executor);
  }
}
