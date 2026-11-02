# AgentFarm control plane
Operate mode. Java 21, Spring Boot and the Mateu navigation shell host one
shared web component across Bandeja, Tareas, Agentes, Presupuesto and Gobierno.
Agentes and Presupuesto render their read-only state with native Mateu components,
refreshed in place every 5 s; the component keeps the toolbar, notices and write forms.

Light working surface, ink #142D4E, action blue #285BC5, quiet paper #F6F7F9.
System sans-serif themed through Lumo tokens. One primary action per surface,
notices only when something needs doing, each with the way to resolve it.
No decorative charts or invented percentage of task completion.
Animal Farm's cast names the roles with labelled flat pictograms that stay legible
at 20 px: a pig in a bicorne for Napoleon (also logo and favicon), a draft-horse head
with a harness for Boxer, a four-leaf clover for budget, a snowflake reserved for
Snowball. The reviewer is a magnifying glass with a check.

The metaphor names things and plain words name actions: docs/ANIMAL_FARM.md holds the glossary.
Bandeja puts the week and what waits on the owner first, then work in progress. Tareas is a
board by stage; cards open the task in a side drawer and are not dragged, because
stages only change through evidence and approvals. Nueva tarea is a drawer too.
Console, PID, session, test outputs and diagrams are progressive disclosures.
Task forms and feedback drafts survive polling, also inside drawers; state changes
every 2 seconds. An action's error stays visible until the next action.
Two-column desktop workspace collapses to one below 800px and the board stacks,
hiding empty columns. Tables scroll on narrow screens. Visible keyboard focus, labelled controls, textual states.

WSL observer/executor availability and Codex inference are separate signals.
Signals expire after 12 seconds. Quota older than 60 seconds is unknown; a prior
reading belongs in dated details. Provider quota and ledger margin are distinct.
Mermaid 11.12.0 is served locally with strict security.
