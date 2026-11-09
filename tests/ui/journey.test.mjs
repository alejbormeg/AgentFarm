// The task's journey, from what is on record. Runs with `node --test`; no browser needed.
import { test } from "node:test";
import assert from "node:assert/strict";
import { journeySteps } from "../../src/main/resources/static/app/lib.js";

const states = (w, d, ev) => Object.fromEntries(journeySteps(w, d, ev).map((s) => [s.name, s.state]));

test("a task cancelled before research shows nothing as done", () => {
  const s = states({ state: "cancelled", version: 0, proposal: "{}" }, null, {});
  assert.deepEqual(Object.values(s), Array(6).fill("cancelled"));
});

test("a task cancelled after its proposal keeps only the proposal done", () => {
  const s = states({ state: "cancelled", version: 1, proposal: '{"summary":"x"}' }, null, {});
  assert.equal(s["Propuesta"], "done");
  assert.equal(s["Implementación"], "cancelled");
});

test("tests are done only when every suite passed or does not apply", () => {
  const w = { state: "review", version: 1, sha: "a".repeat(40) };
  assert.equal(states(w, null, { unit: { status: "passed" } })["Pruebas"], "now", "integration missing");
  assert.equal(states(w, null, { unit: { status: "passed" }, integration: { status: "planned" } })["Pruebas"], "now");
  assert.equal(states(w, null, { unit: { status: "passed" }, integration: { status: "passed" } })["Pruebas"], "done");
  assert.equal(states(w, null, { unit: { status: "passed" }, integration: { status: "not_applicable" } })["Pruebas"], "done");
  assert.equal(states(w, null, { unit: { status: "not_applicable" }, integration: { status: "not_applicable" } })["Pruebas"], "na");
  assert.equal(states(w, null, { unit: { status: "failed" }, integration: { status: "passed" } })["Pruebas"], "bad");
});

test("nothing after the proposal is done while the task waits for approval", () => {
  const s = states({ state: "proposed", version: 1 }, null, {});
  assert.deepEqual(Object.values(s), ["you", "pending", "pending", "pending", "pending", "pending"]);
});

test("a blocked task shows where it is stuck, in words", () => {
  const steps = journeySteps({ state: "blocked", version: 1, sha: "a".repeat(40) }, null, { unit: { status: "failed" } });
  const tests = steps.find((s) => s.name === "Pruebas");
  assert.equal(tests.state, "blocked");
  assert.equal(tests.text, "bloqueada");
  assert.ok(steps.every((s) => typeof s.text === "string" && s.text.length > 0), "every stage has text, not only a colour");
});

test("an integrated task with its evidence is done end to end", () => {
  const ev = { unit: { status: "passed" }, integration: { status: "passed" }, review: { passed: true } };
  const s = states({ state: "integrated", version: 2, sha: "a".repeat(40), pr: 3 }, { verification: { state: "success" }, ready: true }, ev);
  assert.deepEqual(Object.values(s), Array(6).fill("done"));
});
