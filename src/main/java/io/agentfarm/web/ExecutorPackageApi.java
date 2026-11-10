package io.agentfarm.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * The executor package this panel was built with, and the script that installs it. Both are public:
 * the package is the open-source code the panel already runs, and the script holds no secret. The
 * node credential reaches the script from the terminal or a private file, never from here.
 */
@RestController
@RequestMapping("/executor")
public class ExecutorPackageApi {
  static final String PACKAGE = "agentfarm-executor-linux.tar.gz";
  private static final String URL = "https?://[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?";
  private final Path packageFile;
  private final String panelUrl;
  private volatile String digest;
  private volatile long digestFor = -1;

  public ExecutorPackageApi(
      @Value("${agentfarm.executor-package:}") String packageFile,
      @Value("${agentfarm.panel-url:}") String panelUrl) {
    this.packageFile = Path.of(packageFile.isBlank() ? "target/" + PACKAGE : packageFile);
    // From configuration, never from the request: it ends up in a script that someone runs.
    String trimmed = panelUrl.strip().replaceAll("/+$", "");
    this.panelUrl = trimmed.matches(URL) ? trimmed : "http://localhost:8080";
  }

  @GetMapping(value = "/install.sh", produces = "text/x-shellscript;charset=UTF-8")
  public String installer() throws Exception {
    try (var in = getClass().getResourceAsStream("/executor/install.sh")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .replace("@PANEL_URL@", "'" + panelUrl + "'");
    }
  }

  @GetMapping("/" + PACKAGE)
  public ResponseEntity<?> archive() {
    if (!Files.isReadable(packageFile)) return missing();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/gzip"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + PACKAGE + "\"")
        .body(new FileSystemResource(packageFile));
  }

  @GetMapping(value = "/" + PACKAGE + ".sha256", produces = "text/plain;charset=UTF-8")
  public ResponseEntity<String> checksum() throws Exception {
    if (!Files.isReadable(packageFile)) return missing();
    long modified = Files.getLastModifiedTime(packageFile).toMillis();
    if (modified != digestFor) {
      var sha = MessageDigest.getInstance("SHA-256");
      try (var in = Files.newInputStream(packageFile)) {
        byte[] buffer = new byte[65536];
        for (int n; (n = in.read(buffer)) > 0; ) sha.update(buffer, 0, n);
      }
      digest = HexFormat.of().formatHex(sha.digest());
      digestFor = modified;
    }
    return ResponseEntity.ok(digest + "  " + PACKAGE + "\n");
  }

  private static ResponseEntity<String> missing() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
        .body(
            "Este panel no trae el paquete del ejecutor. Con Docker viene en la imagen; sin Docker,"
                + " compílalo con bash runtime/package.sh y vuelve a arrancar el panel.\n");
  }
}
