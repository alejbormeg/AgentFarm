#!/usr/bin/env python3
"""Minimal Codex app-server double for the end-to-end test (stdio JSON-RPC, no network)."""
import json, os, sys, time

RESETS = int(time.time()) + 3 * 86400
threads = {}


def send(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


PROPOSAL = {
    "summary": "Añadir greeting.txt con un saludo",
    "acceptance": "Existe greeting.txt y contiene hola",
    "as_is": "flowchart LR; R[Repo] --> N[Sin saludo]",
    "to_be": "flowchart LR; R[Repo] --> G[greeting.txt]",
    "unit_test": "test -f greeting.txt",
    "integration_test": "grep -q hola greeting.txt",
    "setup": "",
    "criteria": [
        {"id": "AC-1", "description": "greeting.txt existe", "method": "test", "checks": ["unit"]},
        {"id": "AC-2", "description": "contiene hola", "method": "test", "checks": ["integration"]},
    ],
    "sources": [],
    "risks": "Ninguno",
}


def answer(thread, prompt):
    if thread["sandbox"] == "workspace-write":
        with open(os.path.join(thread["cwd"], "greeting.txt"), "w") as f:
            f.write("hola desde AgentFarm\n")
        return {"summary": "Añadido greeting.txt", "as_is": PROPOSAL["as_is"], "to_be": PROPOSAL["to_be"], "limitations": "Ninguna"}
    if prompt.startswith("Revisión independiente"):
        return {"passed": True, "findings": "greeting.txt cumple AC-1 y AC-2"}
    return PROPOSAL


for line in sys.stdin:
    msg = json.loads(line)
    method, mid, params = msg.get("method"), msg.get("id"), msg.get("params") or {}
    if mid is None:
        continue
    if method == "initialize":
        send({"id": mid, "result": {}})
    elif method == "account/read":
        send({"id": mid, "result": {"account": {"type": "chatgpt"}}})
    elif method == "account/rateLimits/read":
        send({"id": mid, "result": {"rateLimits": {"limitId": "codex", "primary": {
            "usedPercent": 10, "windowDurationMins": 10080, "resetsAt": RESETS}}}})
    elif method == "thread/start":
        tid = "t%d" % (len(threads) + 1)
        threads[tid] = {"cwd": params["cwd"], "sandbox": params["sandbox"]}
        send({"id": mid, "result": {"thread": {"id": tid}, "model": "fake-codex"}})
    elif method == "turn/start":
        thread = threads[params["threadId"]]
        text = json.dumps(answer(thread, params["input"][0]["text"]), ensure_ascii=False)
        send({"id": mid, "result": {"turn": {"id": "u-" + params["threadId"]}}})
        send({"method": "item/agentMessage/delta", "params": {"threadId": params["threadId"], "delta": text}})
        send({"method": "turn/completed", "params": {"threadId": params["threadId"], "turn": {"status": "completed"}}})
    else:
        send({"id": mid, "result": {}})
