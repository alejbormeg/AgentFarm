#!/usr/bin/env python3
"""Compares the reference tasks with and without skills from tests/eval/run.sh results."""
import json
import pathlib

root = pathlib.Path(__file__).parent / "results"


def rows(mode):
    path = root / (mode + ".json")
    if not path.exists():
        return {}
    out = {}
    for item in json.loads(path.read_text()):
        evidence = json.loads(item.get("evidence") or "{}")
        metrics = evidence.get("quality", {}).get("metrics", [])
        mutation = next((m for m in metrics if m["name"] == "mutation"), {})
        out[item["id"]] = {
            "estado": item["state"],
            "pruebas": "/".join(evidence.get(k, {}).get("status", "?")[:4] for k in ("unit", "integration")),
            "revisión": evidence.get("review", {}).get("passed"),
            "mutación": mutation.get("candidate"),
            "hallazgos": len(str(evidence.get("review", {}).get("findings", ""))),
            "regresión": evidence.get("regression", {}).get("status"),
        }
    return out


on, off = rows("on"), rows("off")
for task in sorted(set(on) | set(off)):
    print(task)
    print("  con skills:", on.get(task, "sin datos"))
    print("  sin skills:", off.get(task, "sin datos"))
