# Equipo y credenciales de GitHub

## Roles
`admin` (propietario) siempre existe. Con `AGENTFARM_USERS_FILE` añades personas:

```json
[
  {"username": "rita", "role": "reviewer", "password_hash": "{bcrypt}$2a$10$..."},
  {"username": "oscar", "role": "observer", "password_hash": "{bcrypt}$2a$10$..."}
]
```

```bash
read -rs PASS; printf '%s\n' "$PASS" | java -jar agentfarm.jar user-hash   # imprime {bcrypt}...
```

| Rol | Puede |
| --- | --- |
| owner | Todo: integrar por SHA, gobierno, presupuesto, ejecutores, automatización |
| reviewer | Ver todo, crear peticiones, aprobar o corregir propuestas, cancelar y reintentar |
| observer | Solo lectura |

La auditoría registra el nombre de cada persona. El panel oculta las acciones
que tu rol no permite (`GET /api/me`); el servidor las rechaza igualmente.
El fichero solo contiene hashes bcrypt. 2FA queda pendiente.

## GitHub App (recomendado)
El panel usa GitHub para disparadores, diffs e integración. En vez de un token
personal (`AGENTFARM_GITHUB_TOKEN_FILE`), crea una GitHub App instalada solo en
tus repositorios con permisos de repositorio:

- Contents: read & write (integración), Pull requests: read & write,
  Issues: read, Actions: read, Checks: read, Commit statuses: read,
  Dependabot alerts: read.

```bash
openssl pkcs8 -topk8 -nocrypt -in app.private-key.pem -out app-pkcs8.pem && chmod 600 app-pkcs8.pem
AGENTFARM_GITHUB_APP_ID=123456
AGENTFARM_GITHUB_APP_KEY_FILE=/ruta/app-pkcs8.pem
AGENTFARM_GITHUB_INSTALLATION_ID=7890123
```

El panel firma un JWT RS256 de 10 minutos, obtiene un token de instalación y lo
reutiliza hasta 5 minutos antes de caducar. El ejecutor WSL sigue usando su
propio `gh` autenticado.

## Coste esperado
Antes de aprobar, la propuesta muestra la mediana y el p90 del coste observado
al implementar tareas de la misma receta (`GET /api/work/costs`), a partir de
reservas Clover liquidadas. Sin histórico no se muestra ninguna estimación.
