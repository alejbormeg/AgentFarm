#!/usr/bin/env python3
"""Minimal Codex app-server double for the end-to-end test (stdio JSON-RPC, no network)."""
import json, os, subprocess, sys, time

# `codex sandbox`: every hidden path is masked, unless the test breaks the sandbox on purpose.
if sys.argv[1:2] == ["sandbox"]:
    args = sys.argv[2:]
    assert 'permissions.agentfarm.network.enabled=false' in args and 'default_permissions="agentfarm"' in args, args
    command = args[args.index("--") + 1:]
    leaked = ["/etc/passwd"] if os.environ.get("E2E_SANDBOX_BROKEN") else []
    sys.exit(subprocess.call(command[:5] + leaked))

# Fixed across restarts of the executor, like the real quota window.
RESETS = int(os.environ.get("E2E_QUOTA_RESETS", int(time.time()) + 3 * 86400))
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


# Adoption: the first candidate writes a profile the parser rejects, the corrected one a valid one.
BAD_PROFILE = "setup: echo hola\nreview: {required: true}\n"
GOOD_PROFILE = """version: 1
repository: e2e/demo
setup: ""
tests:
  unit: test -f adopted.txt
  integration: grep -qs adoptado adopted.txt
allowed_paths: [adopted.txt, tests/]
environment: container
container: {image: 'bash:5.2', network: setup, timeout_minutes: 5}
review: independent
secret_refs: {}
quality:
  checks:
    lint: "true"
"""
ADOPT = dict(PROPOSAL, summary="Adoptar", acceptance="Perfil válido", unit_test="test -f adopted.txt",
             integration_test="grep -qs adoptado adopted.txt",
             criteria=[{"id": "AC-1", "description": "adopted.txt", "method": "test", "checks": ["unit", "integration"]}])


def adoption(thread, prompt):
    assert "Formato obligatorio de agentfarm.yaml" in prompt or prompt.startswith("Revisión independiente"), prompt[:200]
    if thread["sandbox"] == "workspace-write":
        assert not os.path.exists(os.path.join(thread["cwd"], "agentfarm.yaml")), "a correction starts from the base"
        corrected = "Perfil corregido" in prompt
        with open(os.path.join(thread["cwd"], "agentfarm.yaml"), "w") as f:
            f.write(GOOD_PROFILE if corrected else BAD_PROFILE)
        with open(os.path.join(thread["cwd"], "adopted.txt"), "w") as f:
            f.write("adoptado\n")
        return {"summary": "Adoptado", "as_is": ADOPT["as_is"], "to_be": ADOPT["to_be"], "limitations": "Ninguna"}
    if prompt.startswith("Revisión independiente"):
        assert '"profile_validation"' in prompt and "los decide el operador" in prompt, "the reviewer sees the platform checks"
        return {"passed": True, "findings": "Sin hallazgos bloqueantes."}
    return dict(ADOPT, summary="Perfil corregido") if "usa el formato real" in prompt else ADOPT


def answer(thread, prompt):
    assert "Skills de AgentFarm" in prompt, "every turn receives its skills"
    if "Adopta este repositorio" in prompt:
        return adoption(thread, prompt)
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
        filesystem = params["config"]["permissions"]["agentfarm"]["filesystem"]
        assert filesystem[":root"] == "read" and "sandbox" not in params, params
        writes = filesystem.get(os.path.realpath(params["cwd"])) == "write"
        threads[tid] = {"cwd": params["cwd"], "sandbox": "workspace-write" if writes else "read-only", "web_search": params["config"].get("web_search")}
        send({"id": mid, "result": {"thread": {"id": tid}, "model": "fake-codex"}})
    elif method == "turn/start":
        thread = threads[params["threadId"]]
        prompt = params["input"][0]["text"]
        researching = thread["sandbox"] == "read-only" and not prompt.startswith("Revisión independiente")
        assert thread["web_search"] == ("live" if researching else "disabled"), thread
        text = json.dumps(answer(thread, prompt), ensure_ascii=False)
        send({"id": mid, "result": {"turn": {"id": "u-" + params["threadId"]}}})
        send({"method": "item/agentMessage/delta", "params": {"threadId": params["threadId"], "delta": text}})
        send({"method": "turn/completed", "params": {"threadId": params["threadId"], "turn": {"status": "completed"}}})
    else:
        send({"id": mid, "result": {}})
