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
El fichero solo contiene hashes bcrypt.

## Segundo factor
Opcional por persona, con cualquier aplicación de autenticación (TOTP). En **Gobierno → Segundo
factor**, genera la clave, añádela a la aplicación y confirma con un código. A partir de ahí, tu
cuenta pide el código después de la contraseña.

- **Exigirlo para integrar:** un propietario con su segundo factor activo puede pulsar **Exigir para
  integrar**. Desde entonces, solo integra quien lo tenga activo.
- **Cuentas con segundo factor:** no pueden usar autenticación HTTP Basic, porque no conserva el código
  validado. Para el [servidor MCP](MCP.md) o tus scripts, usa una cuenta `reviewer` u `observer` sin él.
- **Teléfono perdido:** en el equipo del panel, `./agentfarm 2fa-reset <usuario>`. Queda en la auditoría.
- **Dónde se guarda:** la clave está sin cifrar en la base de datos del panel, y por tanto en sus copias.
  Protege esas copias como la contraseña.
- **Intentos:** cada código sirve una vez, y cinco fallos bloquean la cuenta cinco minutos.

## Credencial de GitHub del panel
Comprueba cualquiera de ellas con `./agentfarm github-check owner/repo`. No escribe nada: lee lo que necesita la
integración y lo que GitHub dice que puede hacer esa credencial. Una regla de protección de rama aún podría
rechazar una fusión.

| Credencial | Sirve | Alcance |
| --- | --- | --- |
| GitHub App | Sí, recomendada | Solo los repositorios donde la instales; credenciales de corta duración |
| Token clásico con `repo` | Sí | **Todos** tus repositorios privados |
| Token de grano fino | **No** | No puede leer check runs: GitHub no ofrece `checks=read` a los tokens, y el panel no podría integrar |

Un token se guarda con `./agentfarm github-token`, que lo pide sin mostrarlo. No lo pegues en
chats ni en comandos. Queda en el volumen del panel, fuera del alcance de los agentes.

## GitHub App (recomendado)
El panel usa GitHub para disparadores, diffs e integración.

**Con un clic:** en **Gobierno → Conectar GitHub**, GitHub te pide confirmar la App y elegir en qué
repositorios instalarla, y vuelves al panel. No copias ninguna clave: se guarda en el volumen del panel,
junto a la base de datos y con permisos 600, y entra en sus copias. **Este flujo aún no se ha probado
contra GitHub real**, solo contra un doble.

**A mano,** si prefieres crearla tú, en vez de un token personal
(`AGENTFARM_GITHUB_TOKEN_FILE`), con permisos de repositorio:

- Contents: read & write (integración), Pull requests: read & write,
  Issues: read & write, Workflows: read & write, Actions: read, Checks: read,
  Commit statuses: read, Dependabot alerts: read.

Con la App, el ejecutor no necesita `gh auth login`: el panel le da por cada orden un token de una
hora limitado a ese repositorio. Solo se ha probado con dobles.

Pasos:

1. En [GitHub Apps](https://github.com/settings/apps/new) crea la App, desactiva **Webhook → Active** y marca los permisos anteriores.
2. Apunta el **App ID** y pulsa **Generate a private key**.
3. Con **Install App**, instálala solo en los repositorios que quieras. El número final de esa URL es el **installation ID**.
4. Convierte la clave y configúrala:

```bash
openssl pkcs8 -topk8 -nocrypt -in app.private-key.pem -out app-pkcs8.pem && chmod 600 app-pkcs8.pem
./agentfarm github-app 123456 7890123 app-pkcs8.pem
./agentfarm github-check owner/repo
```

Sin Compose, define `AGENTFARM_GITHUB_APP_ID`, `AGENTFARM_GITHUB_APP_KEY_FILE` y `AGENTFARM_GITHUB_INSTALLATION_ID`.

El panel firma un JWT RS256 de 10 minutos, obtiene un token de instalación y lo
reutiliza hasta 5 minutos antes de caducar. El ejecutor WSL sigue usando su
propio `gh` autenticado.

## Coste esperado
Antes de aprobar, la propuesta muestra la mediana y el p90 del coste observado
al implementar tareas de la misma receta (`GET /api/work/costs`), a partir de
reservas Clover liquidadas. Sin histórico no se muestra ninguna estimación.
