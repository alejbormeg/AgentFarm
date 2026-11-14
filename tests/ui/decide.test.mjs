// Can the owner integrate from the panel, and why not. Runs with `node --test`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { integration } from "../../src/main/resources/static/app/decide.js";

const ready = { state: "review", pr: 7, sha: "a".repeat(40), ready: true, blockers: [], role: "owner", githubConfigured: true, twoFactor: { required_to_integrate: false, active: false } };

test("a ready PR can be integrated by the owner", () => {
  assert.deepEqual(integration(ready), { show: true, ok: true, reasons: [] });
});

test("only the owner decides, and only on a review", () => {
  assert.equal(integration({ ...ready, role: "observer" }).show, false);
  assert.equal(integration({ ...ready, role: "member" }).show, false);
  assert.equal(integration({ ...ready, state: "blocked" }).show, false);
});

test("each reason is said before the click, with where to fix it", () => {
  const github = integration({ ...ready, githubConfigured: false });
  assert.equal(github.ok, false);
  assert.equal(github.reasons[0].to, "/governance");
  const factor = integration({ ...ready, twoFactor: { required_to_integrate: true, active: false } });
  assert.match(factor.reasons[0].text, /segundo factor/);
  assert.equal(integration({ ...ready, twoFactor: { required_to_integrate: true, active: true } }).ok, true);
  const open = integration({ ...ready, ready: false, blockers: ["AC-1: evidence missing"] });
  assert.equal(open.reasons[0].text, "AC-1: sin evidencia todavía");
  assert.equal(integration({ ...ready, pr: null }).reasons[0].text.startsWith("Todavía no hay una PR"), true);
});

test("unknown GitHub or 2FA state is left to the server, not blocked here", () => {
  assert.equal(integration({ ...ready, githubConfigured: undefined, twoFactor: null }).ok, true);
});

test("several reasons are all listed", () => {
  const r = integration({ ...ready, ready: false, blockers: [], githubConfigured: false, twoFactor: { required_to_integrate: true, active: false } });
  assert.equal(r.reasons.length, 3);
});
