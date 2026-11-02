#!/usr/bin/env python3
"""Gives the demo what its simulated world can't produce: CI results and a finished task."""
import base64
import json
import sqlite3
import sys
import time
import urllib.request

db, panel, password = sys.argv[1:]
auth = "Basic " + base64.b64encode(f"admin:{password}".encode()).decode()


def api(path):
    return json.load(urllib.request.urlopen(urllib.request.Request(panel + "/api" + path, headers={"Authorization": auth})))


conn = sqlite3.connect(db, timeout=20)
review = conn.execute("SELECT id,recipe_id,evidence FROM (SELECT w.id,w.evidence,json_extract(r.snapshot,'$.id') recipe_id FROM work_items w LEFT JOIN work_recipes r ON r.item=w.id WHERE w.state='review')").fetchall()
for item, recipe, raw in review:
    evidence = json.loads(raw)
    for kind in ("unit", "integration"):
        evidence[kind]["sandbox"] = {"mode": "container", "image": "demo"}
    evidence["providers"] = {"implement": "codex", "review": "claude-code"}
    evidence["quality"]["metrics"] = [{"name": "coverage", "status": "passed", "base": 81.0, "candidate": 84.5}]
    if recipe == "bugfix":
        evidence["regression"] = {"status": "proved", "reason": "Las pruebas nuevas fallan sobre la base y pasan con el cambio."}
    conn.execute("UPDATE work_items SET evidence=? WHERE id=?", (json.dumps(evidence), item))
    conn.commit()
    dossier = api("/evidence/" + item)
    detail = {
        "checks": [{"name": n, "status": "completed", "conclusion": "success"} for n in ("AgentFarm unit", "AgentFarm integration")],
        "files": ["demo/" + item + ".txt", ".github/workflows/agentfarm.yml"],
        "added": [],
        "secrets": [],
    }
    conn.execute(
        "INSERT OR REPLACE INTO evidence_verifications(item,basis,sha,state,reason,detail,at) VALUES(?,?,?,?,?,?,?)",
        (item, dossier["basis"], dossier["sha"], "success", "La CI del commit pasó entera.", json.dumps(detail), int(time.time())),
    )
    conn.commit()
conn.execute("UPDATE work_items SET state='integrated' WHERE id='demo-readme-00000004'")
conn.execute(
    "INSERT INTO audit(at,actor,action,subject) VALUES(?,?,?,?)",
    (time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "control-plane", "work.integrated", "demo-readme-00000004"),
)
conn.commit()
