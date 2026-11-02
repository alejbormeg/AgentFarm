# Product
<!-- impeccable:product-schema 1 -->
## Platform
web
## Stack
Java 21, Spring Boot 4, Mateu. Requested by the project owner. Ubuntu 24.04 LTS WSL development.
## Users
Developers who want their repositories maintained without doing the maintenance themselves. People with ideas but no coding background are a later audience: they can't judge code, so automated gates and previews must come first.
## Product Purpose
"Stop maintaining your repos": connect a team of agents that keeps a repository healthy and brings each change with its evidence, so the owner only decides. Quality is defined by the Seven Commandments, not by what agents claim.
## Operating Context
Docker Compose is the confirmed primary installation; executable JAR is the alternative.
## Capabilities and Constraints
Port simulator, commit scope gate and API/subscription budget ledger. Preserve existing SQLite data.
A persistent local Codex WSL executor handles owner-approved proposals, isolated checkouts, unit/integration tests, independent review and automatic PR publication. It reports scoped events and Linux process evidence. GitHub merge requires explicit approval of the exact SHA and passing CI. Distributed orchestration remains unimplemented.
## Brand Commitments
AgentFarm: Orwell's Animal Farm as a lens on power. Boxer works, Napoleon directs, Benjamin verifies, Clover guards the budget, Squealer is what agents merely claim; nobody repaints the commandments at night. Metaphor names things; actions and states use plain words. Human acceptance; a batch approval still names each exact commit.
## Product Principles
Reported progress is distinct from verified implementation. Unknown nodes are not active.
Governance changes are audited. No automatic merge or paid fallback.
