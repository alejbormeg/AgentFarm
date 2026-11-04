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

  private static final String SCENE =
      "<picture><source srcset=\"/art/granja-atardecer.png\" media=\"(prefers-color-scheme: dark)\">"
          + "<img class=\"scene\" src=\"/art/granja-dia.png\" alt=\"\"></picture>";

  private static final String STYLE =
      "@font-face{font-family:Geist;src:url(/fonts/geist-latin-wght-normal.woff2) format(\"woff2\");font-weight:100 900}"
          + ":root{color-scheme:light dark;--bg:#f6f5f1;--sheet:#fff;--line:#e6e3da;--ink:#1b2433;--text:#2e3744;"
          + "--muted:#5f6875;--btn:#1b2433;--btn-text:#fff;--field:#fff;--bad:#9a2d22;--bad-bg:#fbe9e6;--ok:#24663a;--ok-bg:#e5f2e8}"
          + "@media (prefers-color-scheme:dark){:root{--bg:#12131f;--sheet:#171926;--line:#2c3045;--ink:#f1eff6;--text:#dddbe6;"
          + "--muted:#a5a8bb;--btn:#e9b94f;--btn-text:#241703;--field:#1d2030;--bad:#f09a8c;--bad-bg:#3a2020;--ok:#86cf9c;--ok-bg:#1b3026}}"
          + "body{margin:0;min-height:100vh;display:grid;place-items:center;background:var(--bg);"
          + "color:var(--text);font:14px/20px Geist,system-ui,sans-serif}"
          + "main{width:min(420px,100vw);background:var(--sheet);border:1px solid var(--line);border-radius:14px;overflow:hidden}"
          + ".scene{display:block;width:100%;height:150px;object-fit:cover;object-position:50% 40%;image-rendering:pixelated}"
          + "h1,p,form,.notice{margin-left:28px;margin-right:28px}"
          + "h1{margin-top:20px;margin-bottom:2px;font-size:22px;line-height:28px;font-weight:600;color:var(--ink)}"
          + "p{margin-top:0;margin-bottom:16px;color:var(--muted)}"
          + "label{display:grid;gap:4px;margin:0 0 12px;font-weight:500;color:var(--ink)}"
          + "input{font:inherit;font-weight:400;min-height:36px;padding:6px 10px;border:1px solid var(--line);"
          + "border-radius:8px;color:var(--ink);background:var(--field)}"
          + "input:focus-visible,button:focus-visible{outline:2px solid #1f5fbf;outline-offset:2px}"
          + "button{width:100%;min-height:38px;margin-bottom:28px;font:inherit;font-weight:500;border:0;border-radius:8px;"
          + "background:var(--btn);color:var(--btn-text);cursor:pointer}"
          + ".notice{padding:8px 12px;border-radius:8px;background:var(--bad-bg);color:var(--bad)}"
          + ".notice.ok{background:var(--ok-bg);color:var(--ok)}";

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
        + SCENE
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
        + SCENE
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
