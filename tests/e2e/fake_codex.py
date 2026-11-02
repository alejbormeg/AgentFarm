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
        # Shape returned by the real Codex in the first forja pilot: commands keyed by check.
        {"id": "AC-1", "description": "greeting.txt existe", "method": "test", "checks": {"unit": "test -f greeting.txt"}},
        {"id": "AC-2", "description": "contiene hola", "method": "test", "checks": ["integration"]},
    ],
    "sources": [],
    "risks": "Ninguno",
}


# A bug fix with its regression test: tests/test_fix.sh fails on the base commit.
BUGFIX = dict(
    PROPOSAL,
    summary="Corregir el saludo",
    acceptance="fixed.txt contiene arreglado",
    unit_test="bash tests/test_fix.sh",
    integration_test="bash tests/test_fix.sh",
    criteria=[{"id": "AC-1", "description": "fixed.txt contiene arreglado", "method": "test", "checks": ["unit", "integration"]}],
)


# What the demo's simulated agents say and change, found by a word in the request.
DEMO = {
    "jackson": ("Subir jackson-databind a la versión con la corrección de seguridad", "dependencias.txt", "Las pruebas siguen pasando con la versión nueva"),
    "login": ("Cubrir el inicio de sesión con contraseñas caducadas", "login.txt", "Una contraseña caducada no permite entrar"),
    "cupón": ("Rechazar los cupones caducados al calcular el carrito", "cupon.txt", "Un cupón caducado no descuenta nada"),
    "enlace": ("Corregir el enlace del README", "enlace.txt", "El enlace apunta a una página que existe"),
    "latencia": ("Medir la latencia del gateway por ruta", "latencia.txt", "Cada petición registra su duración"),
}


def demo(prompt):
    request = prompt[prompt.find("Petición:") :]
    return next((v for k, v in DEMO.items() if k in request), None)


def answer(thread, prompt):
    assert "Skills de AgentFarm" in prompt, "every turn receives its skills"
    scenario = demo(prompt)
    if scenario and not prompt.startswith("Revisión independiente"):
        summary, name, criterion = scenario
        if thread["sandbox"] == "workspace-write":
            with open(os.path.join(thread["cwd"], "demo", name), "w") as f:
                f.write("simulado\n")
            return {"summary": summary, "as_is": "flowchart LR; A[Antes] --> B[Con el problema]", "to_be": "flowchart LR; A[Después] --> B[Resuelto]", "limitations": "Ninguna"}
        return dict(
            PROPOSAL,
            summary=summary,
            acceptance=criterion,
            as_is="flowchart LR; A[Antes] --> B[Con el problema]",
            to_be="flowchart LR; A[Después] --> B[Resuelto]",
            risks="Ninguno relevante",
            criteria=[{"id": "AC-1", "description": criterion, "method": "test", "checks": ["unit", "integration"]}],
        )
    if "Corrige el saludo" in prompt and thread["sandbox"] == "workspace-write":
        os.makedirs(os.path.join(thread["cwd"], "tests"), exist_ok=True)
        with open(os.path.join(thread["cwd"], "fixed.txt"), "w") as f:
            f.write("arreglado\n")
        with open(os.path.join(thread["cwd"], "tests", "test_fix.sh"), "w") as f:
            f.write("grep -q arreglado fixed.txt\n")
        return {"summary": "Corregido", "as_is": BUGFIX["as_is"], "to_be": BUGFIX["to_be"], "limitations": "Ninguna"}
    if "Corrige el saludo" in prompt and not prompt.startswith("Revisión independiente"):
        return BUGFIX
    if thread["sandbox"] == "workspace-write":
        with open(os.path.join(thread["cwd"], "greeting.txt"), "w") as f:
            f.write("hola desde AgentFarm\n")
        return {"summary": "Añadido greeting.txt", "as_is": PROPOSAL["as_is"], "to_be": PROPOSAL["to_be"], "limitations": "Ninguna"}
    if prompt.startswith("Revisión independiente"):
        return {"passed": True, "findings": "Cumple los criterios; sin hallazgos bloqueantes."}
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
