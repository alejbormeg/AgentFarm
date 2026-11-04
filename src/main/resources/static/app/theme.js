// Before the first paint, and a file for the CSP: light is La ventana, dark is Atardecer.
(() => {
  const key = "agentfarm.theme";
  const media = matchMedia("(prefers-color-scheme: dark)");
  const stored = () => {
    try {
      return localStorage.getItem(key) || "auto";
    } catch {
      return "auto";
    }
  };
  const apply = () => {
    const choice = stored();
    document.documentElement.dataset.theme = choice === "auto" ? (media.matches ? "dark" : "light") : choice;
  };
  apply();
  media.addEventListener("change", apply);
  window.agentfarmTheme = {
    choice: stored,
    cycle() {
      const next = { auto: "light", light: "dark", dark: "auto" }[stored()];
      try {
        localStorage.setItem(key, next);
      } catch {}
      apply();
      return next;
    },
  };
})();
