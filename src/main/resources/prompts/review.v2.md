Revisión independiente de {{base}}..{{sha}}. No modifiques archivos ni publiques ni delegues. Comprueba los criterios de la propuesta {{proposal}}.

Contexto de la plataforma, no del implementador: si el repositorio no tenía CI, AgentFarm añade `.github/workflows/agentfarm.yml` con los comandos aprobados; no lo cuentes como cambio fuera de alcance. Las pruebas listadas abajo las ejecutó AgentFarm en un contenedor aislado sobre el SHA indicado y son la evidencia válida; los comentarios del implementador sobre Docker o commits no lo son.

Devuelve SOLO JSON: passed (boolean), findings (string con rutas/líneas).

Pruebas ejecutadas: {{tests}}
