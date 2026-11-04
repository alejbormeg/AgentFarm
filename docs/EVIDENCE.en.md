# Evidence and acceptance

## Criteria
A proposal has identified criteria:

```json
{"criteria":[
  {"id":"AC-1","description":"The operation keeps the expected data","method":"test","checks":["unit","integration"]},
  {"id":"AC-2","description":"The user can see the block in the interface","method":"manual"}
]}
```

IDs are unique, from AC-1 to AC-9999, with at most 50 criteria. An immutable snapshot of the proposal is taken when you press **Implement**.

## The dossier
The exportable dossier keeps the repository, issue, PR, branch, base, candidate, proposal version, scope hash, executor reports and human decisions. Reports are labeled as declared: a `passed` from Boxer verifies no criterion by itself. Known credential fields are hidden on export, and no secret should ever be sent as evidence.

Opening a task shows which criteria are pending, manually validated, rejected or under an exception. A decision needs an explanation of at least twenty characters and the environment used. A manual validation means the operator declares having done that check: it doesn't certify an automatic test or an independent observation by the server.

A `test` criterion can't be replaced by a manual validation. It only closes when the verifier checks it. Otherwise it stays pending or needs an explicit, justified and visible exception, and the result reads as accepted with exceptions. A human rejection always wins.

## The verifier
`EvidenceVerifier` runs in the panel every 30 seconds, with the panel's GitHub credential and without depending on the executor. For each task in review it reads the exact commit's check runs, statuses and changed files, and stores the result tied to its `basis`: if the SHA, version or report changes, it's no longer valid.

A `test` criterion is **checked** if CI passed entirely and, for each check, the report shows the approved command, unaltered, passing in the disposable container. Tests on the host don't count. CI doesn't pass if the candidate changes a workflow other than `.github/workflows/agentfarm.yml`.

**N/A:** a `test` criterion can't rely on an `N/A:` command, and a candidate that changes code with a test type in `N/A:` doesn't pass. N/A is only accepted when the changes are documentation (`docs/`, `.md`, `.txt`, licenses, project memory).

It checks that the approved commands passed, not that the tests are good. Without a GitHub credential in the panel, `test` criteria stay unchecked.

A correction can't lose approved criteria: they stay unchanged even if the agent reuses their id, and what it brings enters as new criteria. Only a person retires one, when asking for the correction and with a reason of at least 20 characters, which stays visible on the proposal and in the audit.

A new rejection revokes an earlier acceptance of the same criterion, and the history is kept. Retrying the same request doesn't duplicate decisions. Changing the SHA, the approved version or the reviewed report invalidates decisions. The server checks coverage again when a merge is requested and before publishing the integration.

When it receives a proposal, the panel warns about `test` criteria that may never be accredited: those asking for something tests rarely show, such as a binary's identity or the process user, and those closed by a command that always passes. It's a warning and doesn't block, because a phrase can be a false alarm. Decide before approving: correct the proposal, or turn it into a `manual` criterion. A `test` criterion on an `N/A:` command is rejected.

## Compatibility and API
Historical tasks get no acceptances. For a historical candidate in review, the operator must import its scope and then review it. An integrated task keeps its history.

Authenticated operator API, with CSRF on changes:

- `GET /api/evidence`: candidates in review.
- `GET /api/evidence/{item}`: JSON download.
- `POST /api/evidence/{item}/import-scope`: current version and SHA.
- `POST /api/evidence/{item}/decisions`: `request_id`, `version`, `sha`, `basis`, `criterion`, `kind` (`manual`, `exception` or `reject`), `reason`, `environment`.

`basis` comes from the dossier and ties a decision to the data reviewed, so an old one is rejected. Node credentials can't read dossiers or record decisions. Decisions don't authorize a merge by themselves: a separate human action on the exact commit is still needed.

The interface tells apart the researched base, the approved To-be and the declared result. These diagrams still need checking and are no proof of architecture.

## Not done yet
Automatically recorded tools and dependencies, durable artifacts with integrity linked from the PR, previews and screenshots, building and contrasting the As-is from the integrated commit, and visual validation on desktop and mobile.
