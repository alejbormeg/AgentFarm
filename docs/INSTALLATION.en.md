# Installation

Development candidate: there are no published releases yet, and installing it has not been measured with new users.

## Panel
Needs Docker Compose v2. From the checkout:

```bash
./agentfarm up
./agentfarm password
```

Open http://localhost:8080 as user `admin`. The panel listens on localhost and keeps its data in SQLite. As an alternative, with Java 21+: `./mvnw package` and then `java -jar target/agentfarm.jar`.

Never put the panel on the Internet without TLS and an access policy.

In **Governance → Connect executor**, select a registered node and account. If the panel has none, **Prepare this machine** creates node `napoleon-local`, its two workers and the account `codex-subscription`, which stops at 2 % of weekly quota left. Connecting checks heartbeats and the authentication the executor reports. It runs no inference and doesn't verify isolation.

## Executor on Linux or WSL Ubuntu 24.04
Needs Java 21+, Git, and `gh` and Codex signed in on the account that will run the service. Use each tool's official sign-in, and never paste tokens into Git.

**From the panel.** In **Conectar ejecutor**, prepare this computer, create the node credential and download it. Then, in your terminal:

```bash
curl -fsSo agentfarm-install.sh http://localhost:8080/executor/install.sh
bash agentfarm-install.sh --panel http://localhost:8080 --token-file /path/to/node-token
```

The installer checks Java 21+, Docker and Codex, downloads the executor of the panel's own version and verifies its SHA-256. It asks the panel which node and account the credential belongs to, writes a private `~/.config/agentfarm/executor.env`, and installs the `agentfarm-executor` user service. It leaves an existing executor alone unless you pass `--replace`. A panel without Docker serves `target/agentfarm-executor-linux.tar.gz`, built with `bash runtime/package.sh`.

**By hand.** Build the package with `bash runtime/package.sh` and extract `target/agentfarm-executor-linux.tar.gz` somewhere permanent. The destination needs neither Maven nor the source code.

Create a private state directory and save the node credential, which the wizard shows only once, as `STATE/node-token` with mode 600. Outside Git, create a private `executor.env`:

```text
AGENTFARM_NODE=my-napoleon
AGENTFARM_ACCOUNT=my-account
AGENTFARM_URL=http://localhost:8080
AGENTFARM_STATE=/private/path/agentfarm
AGENTFARM_IMPLEMENTER=my-boxer
AGENTFARM_REVIEWER=my-reviewer
```

The IDs must match the panel's records. `./executor` tries it in the foreground with those variables, and `./install-executor.sh /path/executor.env` installs the systemd user service. Check or stop it with `systemctl --user status|stop agentfarm-executor`. WSL must stay running: the user service doesn't configure Windows start-up or linger.

The executor uses a revocable node token and keeps CSRF. Its API can't approve, create credentials, change policies or claim merge orders.

Set the panel's GitHub credential in **Governance → Connect GitHub**, or with `./agentfarm github-token` or `github-app` ([which one to use](TEAM.md)). The panel performs merges with it, so never mount it in a worker directory. Without it, merge orders are not processed.

**What agents can read:** Codex's read-only mode reaches the whole disk. The executor applies a permission profile that hides `~/.config`, `~/.ssh`, `~/.aws`, `~/.docker`, `~/.codex/auth.json`, the executor's state, backups, other workspaces, `/mnt`, container sockets and your user's session directory, and turns the network off. Before accepting work, and every ten minutes, the executor checks with the real sandbox that those paths can't be reached. If that fails or it can't tell, it accepts no tasks and the panel says so. `bash tests/codex_sandbox.sh` is a fuller check, useful when you update Codex. This is a deny list: the rest of your files, such as other projects in your home, stay readable. Needs Codex CLI 0.166 or later. A dedicated user for the executor is still safer: see the [threat model](SECURITY.md).

Claude Code doesn't run in that sandbox. It gets the same paths as deny rules for its tools, ignores the repository's settings and has no `Bash`. Those rules are still weaker than system isolation, and the panel warns while any role uses it. They were tested with a real account on Claude Code 2.1.318, including through symlinks: run `bash tests/claude_rules.sh` again after updating it, because it uses very little quota and nothing else checks this.

**Important limit:** `gh` and Codex still run with the service's Linux identity. Tests are isolated in a container, but Codex's write sandbox on the checkout is not a security boundary.

## Repository profile and diagnostics
Copy `examples/agentfarm.yaml` to the repository root and adapt the repository, commands and paths. Check it with:

```bash
java -jar agentfarm.jar profile --file /repo/agentfarm.yaml
java -jar agentfarm.jar doctor --repo /repo --panel http://localhost:8080
java -jar agentfarm.jar doctor --repo /repo --run-tests true
```

Diagnostics run no profile command without `--run-tests true`, and show neither tool output nor credential values. Quota shows as unknown: being signed in doesn't prove there's quota left. The configuration is tied to the proposal by hash, so changing it needs a new approval. Changed files must be inside `allowed_paths`. `secret_refs` can be described, but running with them is blocked.

## Tests in a container
The executor runs `setup` and the test commands in a disposable Docker container per candidate, never in the host checkout:

- The container gets a copy of the commit's tree (`git archive`): no `.git`, no hooks, no `~/.config/gh`, no Codex credentials or token variables.
- `--cap-drop ALL`, except the minimum ownership permissions for package managers, plus `no-new-privileges` and limits on CPU, memory, processes and time.
- `setup` runs as the container's root with network. Then the network is disconnected (`network: setup`, the default) and tests run with your uid. `network: none` never gives network, and `always` keeps it during tests.
- The container and the copy are always removed, also on failure or cancellation.
- The policy is part of the proposal you approve and stays in the evidence.

```yaml
environment: container
container:
  image: maven:3.9.11-eclipse-temurin-21   # buildpack-deps:noble by default
  network: setup                          # setup | none | always
  cpus: 2                                 # 1-16
  memory_mb: 4096                         # 256-32768
  timeout_minutes: 20                     # 1-60
```

Without a profile, tasks use `buildpack-deps:noble` (set it with `-Dagentfarm.sandbox-image=`). A profile with `environment: local` only runs if the operator starts the executor with `-Dagentfarm.sandbox=host`; otherwise the task is blocked with the reason.

Limit: Docker with the executor's uid is equivalent to root on the host. The container protects from the candidate's code, not from the operator.

## Backup and recovery

### With Docker Compose
```bash
./agentfarm backup                       # ~/agentfarm-backups/agentfarm-<UTC date>.db
./agentfarm backup /backups/farm.db      # an existing directory also works
./agentfarm restore /backups/farm.db     # asks for confirmation; --yes without a terminal
```

`backup` makes the copy inside the running container, streams it out and checks SHA-256 and `integrity_check` before giving it its final name, with mode 0600 and without overwriting anything. The tmpfs holds 128 MB: with a larger database the backup fails and leaves nothing behind.

`restore` validates the copy beside the running panel, so a damaged copy is refused without interrupting it. It then stops the panel, keeps the current database in the volume as `/data/agentfarm.db.pre-restore-<date>` and starts with the copy. If that doesn't start, for example because the copy comes from a newer version, it recovers the previous database and ends with an error. Stop the executor before restoring. On a new install, run `./agentfarm up` first.

### With the JAR
With no service running, `./agentfarm backup [destination]` uses the JAR on `AGENTFARM_DB` (`./data/agentfarm.db` by default) and says so. If you use Compose and it's stopped, start it first: that database isn't the volume's.

```bash
java -jar agentfarm.jar backup --db /data/agentfarm.db --output /backups/farm-2026-10-31.db
java -jar agentfarm.jar restore --snapshot /backups/farm-2026-10-31.db --db /data/restored.db
```

Restoring with the JAR needs a new destination. Stop the panel before pointing `AGENTFARM_DB` at the restored file.

### In both cases
The backup uses `VACUUM INTO` and `integrity_check`. The database holds history, credential hashes and second-factor keys, so protect the backup too. It doesn't include external credentials, checkouts, receipts or local artifacts: copy those separately, with the executor stopped. There's no automatic retention. Don't restart blocked work without reconciling receipts, reservations, the remote PR and the SHA.

## Several tasks in parallel
The panel allows at most `agentfarm.max-parallel` claimed commands (2 by default, `AGENTFARM_MAX_PARALLEL`) and `agentfarm.max-per-repository` per repository (1). Each executor claims one command, so install several slots of the same node to work in parallel:

```bash
./install-executor.sh /path/executor.env 1
./install-executor.sh /path/executor.env 2
systemctl --user status agentfarm-executor-s1 agentfarm-executor-s2
```

Each slot claims as `<node>-s<N>` with the node's credential, and has its own working directory, receipts and reservation journal. They share the quota account, so Clover still limits them together.

## The project's CI
If the base commit already has workflows in `.github/workflows/`, AgentFarm doesn't add its own: the evidence requires **all** of the SHA's GitHub checks to succeed. Only repositories with no CI get `.github/workflows/agentfarm.yml` with the approved commands. A candidate that changes the project's own workflows stays a draft PR, because a person must review a change that could weaken the check validating it.

## Versioned prompts
The research, implementation and review instructions live in `src/main/resources/prompts/*.vN.md`. The proposal and the evidence keep the template's id and SHA-256. Changing a prompt means creating `vN+1` and updating `Prompts.CURRENT`, so earlier results stay traceable.

## Schema migrations
`schema_migrations` records the applied versions. Each new version runs once, in one transaction, and an older panel refuses a database written by a newer version. Run `backup` before updating.
