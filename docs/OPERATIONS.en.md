# Operating AgentFarm

The panel and the executor are separate processes. The panel mounts neither the Docker socket nor the executor's credentials. The executor uses Codex app-server and the GitHub CLI signed in on its Linux account.

## From the panel

1. Press **New task** in any view and give `owner/repository`, a title and a goal. **Create issue and research** queues a persistent order, and shows it as pending until GitHub confirms the issue exists.
2. Napoleon inspects the checkout and searches the web when needed. It's the only turn with web search: implementation and review have it turned off. The issue holds sources, scope, criteria, diagrams and test commands.
3. Review the scope and the commands, including dependency installation. **Implement proposal vN** approves that version. **Ask for corrections** reworks the proposal without authorizing implementation.
4. Boxer works in `agentfarm/<issue>-<title>`. Unit and integration tests run, then a separate read-only review. If the repository has no CI, the executor adds a workflow with the approved commands. It then publishes the PR, as a draft if anything failed.
5. Check the links and the evidence. **Approve and integrate this commit** is at the top of the task, shows the SHA and asks for explicit confirmation. The panel checks GitHub again (SHA, draft, expected checks, CI states and requested changes), and records the integration only after GitHub confirms it.

Test commands are no mathematical proof of quality. Their output, result and SHA are kept for human review. An N/A exception needs a reason visible in the approved proposal. An integrated PR doesn't mean a deployment.

## Installing a local executor

On WSL Ubuntu 24.04 you need Java 21, Git, Codex CLI and GitHub CLI. Sign Codex in with the subscription account, and GitHub with:

```bash
gh auth login --hostname github.com --git-protocol https --web
gh auth refresh --hostname github.com --scopes workflow
gh auth setup-git
bash runtime/install-service.sh /absolute/path/executor.env
systemctl --user status agentfarm-executor
```

[Installation](INSTALLATION.en.md) explains `executor.env` and the node token. The service starts with WSL's user manager, and WSL must be running. This executor stays local, with one operator and one node: it's neither distributed authentication nor multi-user isolation.

## Signals and budget

Telemetry and heartbeats go every 2 seconds, and with no signal for 12 seconds the state expires. An idle app-server PID isn't presented as active inference. Quota is read without inference every 30 seconds at rest and every 5 during an order, and a reading older than 60 seconds isn't shown as the current balance.

Clover keeps observed quota and reservations separate. New provider windows are reconciled without erasing history and with no pending reservations. Stopping at 2 % uses discrete checks, so a running operation can cross the threshold between readings. There's no top-up and no API fallback.

## Recovery

Each request has a stable ID, and each order has an owner and a heartbeat. An order lost for more than 120 seconds is blocked for review. An inference turn is never restarted automatically. Retrying keeps the issue, branch, candidate and available evidence, and PRs are reconciled by branch. Reservations go to a local journal and are only released with a current reading of their window, once the process has ended.

Approval, cancellation and publication changes are audited. **Retry** doesn't approve merges. A failed integration returns to review and needs another click on the SHA. GitHub errors show in the task.

## Interfaces

- `GET/POST /api/work`: requests and state.
- `POST /api/work/{id}/actions/{implement|correct|retry|cancel|merge}`.
- `POST /api/work/import-candidate`: an existing candidate, approved by the operator, with base, SHA, issue and test strategy. It's tested and reviewed again.
- `POST /api/work/agent/claim` and `/agent/commands/{id}`: orders and results.
- `GET /api/runtime`: signal and quota, with freshness computed on the server.
- `POST /api/budget/rollover` and `/settle-observed`: subscription reconciliation.
- `GET /api/budget/{account}/reservations`: reservation history.

These APIs need the operator's session or credential, and changes need CSRF. The agent's name identifies the process and isn't an independent cryptographic identity.

## Visual dependencies

Mermaid 11.12.0 is served locally, with `securityLevel: strict` and an MIT license, and diagrams go to no external service. The UI uses the Mateu shell and a web component served by Spring, so installing needs no Node build.
