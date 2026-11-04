package io.agentfarm.web;

import io.agentfarm.core.TwoFactor;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/** The sign-in page and the addresses of the views merged into Agentes. */
@RestController
public class PageRoutes {
  private final TwoFactor twoFactor;

  public PageRoutes(TwoFactor twoFactor) {
    this.twoFactor = twoFactor;
  }

  private static final String HAT =
      "<svg viewBox=\"0 0 64 64\" width=\"64\" height=\"64\" aria-hidden=\"true\">"
          + "<path d=\"M15 27 8 19l13 3zM49 27l7-8-13 3z\" fill=\"#e5909f\"/><ellipse cx="
          + "\"32\" cy=\"42\" rx=\"21\" ry=\"19\" fill=\"#f4b3bd\"/><ellipse cx=\"32\" cy"
          + "=\"49\" rx=\"10\" ry=\"7.5\" fill=\"#e5909f\"/><ellipse cx=\"28\" cy=\"49\" "
          + "rx=\"2\" ry=\"3\" fill=\"#7c3a48\"/><ellipse cx=\"36\" cy=\"49\" rx=\"2\" ry"
          + "=\"3\" fill=\"#7c3a48\"/><circle cx=\"22.5\" cy=\"37\" r=\"3\" fill=\"#142d4"
          + "e\"/><circle cx=\"41.5\" cy=\"37\" r=\"3\" fill=\"#142d4e\"/><path d=\"M2 25"
          + "C11 23 19 7 32 5c13 2 21 18 30 20-9 7-51 7-60 0z\" fill=\"#142d4e\"/><path d"
          + "=\"M4 25c12 6 44 6 56 0\" fill=\"none\" stroke=\"#e3ab3e\" stroke-width=\"3\""
          + " stroke-linecap=\"round\"/><circle cx=\"32\" cy=\"17\" r=\"6.5\" fill=\"#fff"
          + "\"/><circle cx=\"32\" cy=\"17\" r=\"4.2\" fill=\"#285bc5\"/><circle cx=\"32\""
          + " cy=\"17\" r=\"2\" fill=\"#d23c3c\"/>"
          + "</svg>";

  private static final String STYLE =
      "body{margin:0;min-height:100vh;display:grid;place-items:center;background:#f6f7f9;"
          + "color:#233345;font:16px/1.5 system-ui,sans-serif}"
          + "main{width:min(380px,calc(100vw - 32px));background:#fff;border:1px solid #dce3e9;"
          + "border-radius:12px;padding:32px;box-sizing:border-box}"
          + "h1{margin:12px 0 4px;font-size:24px;color:#142d4e}p{margin:0 0 20px;color:#526477}"
          + "label{display:grid;gap:6px;margin:0 0 16px;font-weight:600;font-size:14px}"
          + "input{font:inherit;font-weight:400;padding:10px 12px;border:1px solid #8496a9;"
          + "border-radius:6px;color:#142d4e}"
          + "input:focus-visible,button:focus-visible{outline:3px solid #285bc5;outline-offset:2px}"
          + "button{width:100%;font:inherit;font-weight:600;padding:11px;border:0;border-radius:6px;"
          + "background:#285bc5;color:#fff;cursor:pointer}button:hover{background:#1f4ba6}"
          + ".notice{padding:10px 12px;border-radius:6px;background:#fbe5e4;color:#922f2b}"
          + ".notice.ok{background:#e1f0e8;color:#206444}";

  @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
  public String login(HttpServletRequest request) {
    String notice = "";
    if (request.getParameter("error") != null)
      notice = "<p class=\"notice\" role=\"alert\">Usuario o contraseña incorrectos.</p>";
    else if (request.getParameter("blocked") != null)
      notice =
          "<p class=\"notice\" role=\"alert\">Demasiados intentos. Espera unos minutos y vuelve a"
              + " probar.</p>";
    else if (request.getParameter("logout") != null)
      notice = "<p class=\"notice ok\" role=\"status\">Has cerrado la sesión.</p>";
    CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    return "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<title>Entrar · AgentFarm</title><style>"
        + STYLE
        + "</style></head><body><main>"
        + HAT
        + "<h1>AgentFarm</h1><p>Entra para gobernar tu equipo de agentes.</p>"
        + notice
        + "<form method=\"post\" action=\"/login\"><label>Usuario"
        + "<input name=\"username\" autocomplete=\"username\" required autofocus></label>"
        + "<label>Contraseña<input name=\"password\" type=\"password\""
        + " autocomplete=\"current-password\" required></label>"
        + "<input type=\"hidden\" name=\""
        + HtmlUtils.htmlEscape(csrf.getParameterName())
        + "\" value=\""
        + HtmlUtils.htmlEscape(csrf.getToken())
        + "\"><button>Entrar</button></form></main></body></html>";
  }

  @GetMapping(value = "/login/2fa", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
  public String secondFactor(HttpServletRequest request) {
    String notice =
        request.getParameter("error") == null
            ? ""
            : "<p class=\"notice\" role=\"alert\">Código incorrecto, ya usado o demasiados intentos. Espera y prueba otro.</p>";
    CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    return "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<title>Segundo factor · AgentFarm</title><style>"
        + STYLE
        + "</style></head><body><main>"
        + HAT
        + "<h1>Segundo factor</h1><p>Escribe el código de seis cifras de tu aplicación.</p>"
        + notice
        + "<form method=\"post\" action=\"/login/2fa\"><label>Código"
        + "<input name=\"code\" inputmode=\"numeric\" autocomplete=\"one-time-code\" pattern=\"[0-9]{6}\""
        + " required autofocus></label>"
        + "<input type=\"hidden\" name=\""
        + HtmlUtils.htmlEscape(csrf.getParameterName())
        + "\" value=\""
        + HtmlUtils.htmlEscape(csrf.getToken())
        + "\"><button>Continuar</button></form></main></body></html>";
  }

  @PostMapping("/login/2fa")
  public ResponseEntity<Void> verifySecondFactor(
      @RequestParam String code, HttpServletRequest request, java.security.Principal p) {
    boolean good = twoFactor.verify(p.getName(), code);
    if (good) {
      request.getSession().setAttribute(TwoFactorFilter.VERIFIED, p.getName());
      request.changeSessionId();
    }
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(good ? "/" : "/login/2fa?error"))
        .build();
  }

  @GetMapping({"/nodes", "/workers"})
  public ResponseEntity<Void> mergedAgentViews() {
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/agents")).build();
  }
}
