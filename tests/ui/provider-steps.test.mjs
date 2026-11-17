// The provider steps name each node's real files, and a choice the node cannot run is said. Runs with `node --test`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { fallbacks, nodeFiles, steps } from "../../src/main/resources/static/app/provider-steps.js";

test("a node added with a code has its own files and unit", () => {
  const files = nodeFiles("~/.local/state/agentfarm-napoleon-64");
  assert.deepEqual(files, {
    state: "~/.local/state/agentfarm-napoleon-64",
    env: "~/.config/agentfarm/executor-napoleon-64.env",
    unit: "agentfarm-executor-napoleon-64",
  });
  const claude = steps("claude-code", files).join("\n");
  assert.match(claude, /agentfarm-napoleon-64\/anthropic-api-key/);
  assert.match(claude, /executor-napoleon-64\.env/);
  assert.match(claude, /restart agentfarm-executor-napoleon-64\./);
});

test("the first executor, or one without a report, keeps the plain names", () => {
  for (const dir of ["~/.local/state/agentfarm", "~/.local/state/agentfarm/", undefined])
    assert.deepEqual(nodeFiles(dir), { state: "~/.local/state/agentfarm", env: "~/.config/agentfarm/executor.env", unit: "agentfarm-executor" });
});

test("a choice the node is not running is reported, an unset one is not", () => {
  assert.deepEqual(fallbacks({ implement: "claude-code", review: "", research: "codex" }, { implement: "codex", review: "codex", research: "codex" }), [
    { role: "implement", chosen: "claude-code", running: "codex" },
  ]);
  assert.deepEqual(fallbacks({ implement: "claude-code" }, undefined), [], "without a report nothing is claimed");
});
