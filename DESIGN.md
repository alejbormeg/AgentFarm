# AgentFarm control plane
Operate mode. Java 21, Spring Boot and a page of our own: ES modules in `static/app`, no build step, no framework.
Light is **La ventana**, dark is **Atardecer**: same layout, same farm, chosen by the owner on 2026-11-04.

The product's face is a window onto a live farm. Fine pixel art, with shading and a muted palette, stays inside that
window. Everything around it is plain and familiar: Geist, tables, standard controls, never a pixel font.
The map carries real state and nothing invented: one crate per decision, one sack per integrated change, the pasture
is the quota left, and each animal stands where its work is. Sprites move by whole pixels; the scene only scales by
whole multiples (1 on phones, 2 otherwise). Art comes from `tools/pixelart/farm.py`.

The commandments appear on every candidate as seven marks (I to VII): filled is met, outlined is pending, struck is
failed. In Gobierno they are the barn wall: light paint on tar.
Colour goes on data and decisions. Barn red for the marks and what waits on you, gold for the same in Atardecer. A
primary action per row at most. Notices only when something needs doing, each with the way to resolve it.
No decorative charts or invented percentage of task completion. Geist and Geist Mono are served by the panel: nothing
is fetched from elsewhere, and the content policy would refuse it.

The metaphor names things and plain words name actions: docs/ANIMAL_FARM.md holds the glossary.
Bandeja: the farm, the figures beside it, then what waits on the owner, then work in progress. One click approves or
integrates; the drawer is for reading. Tareas is a board by stage; an empty stage folds into a labelled strip, and cards
open the task in a drawer and are not dragged, because stages only change through evidence and approvals.
Agentes is the stable, Presupuesto what was spent and reserved, Gobierno the rules and connections.
Console, PID, session, test outputs and diagrams are progressive disclosures.
Task forms and feedback drafts survive polling, also inside drawers; state changes every 2 seconds. An action's error
stays visible until the next action.
The sidebar becomes a rail below 1080px and a bottom bar below 640px, where the decisions come right after the farm.
Tables and the board scroll on narrow screens. Visible keyboard focus, labelled controls, textual states, and states
that differ in shape as well as colour.

WSL observer/executor availability and Codex inference are separate signals.
Signals expire after 12 seconds. Quota older than 60 seconds is unknown; a prior
reading belongs in dated details. Provider quota and ledger margin are distinct.
Mermaid 11.12.0 is served locally with strict security.
