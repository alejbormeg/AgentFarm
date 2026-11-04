#!/usr/bin/env python3
"""One real pilot from the panel, measured as docs/PILOTS.md asks. Spends quota; stops at --max-quota.

It only presses what a person would press, and never edits a criterion: a task that blocks is a finding
and is reported, not worked around. Merging is opt-in, because it writes to the repository's main branch.
"""
import argparse, base64, json, sys, time, urllib.error, urllib.request, uuid
from http.cookiejar import CookieJar


class Panel:
    def __init__(self, url, user, password):
        self.url = url.rstrip("/")
        self.auth = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))
        self.csrf = None

    def call(self, path, body=None):
        headers = {"Authorization": self.auth}
        data = None
        if body is not None:
            if self.csrf is None:
                self.csrf = self.call("/csrf")
            headers.update({"Content-Type": "application/json", self.csrf["header"]: self.csrf["token"]})
            data = json.dumps(body).encode()
        request = urllib.request.Request(self.url + "/api" + path, data=data, headers=headers)
        with self.http.open(request, timeout=30) as response:
            return json.load(response)

    def item(self, item_id):
        return next(w for w in self.call("/work") if w["id"] == item_id)


class Stop(Exception):
    """The pilot cannot go on; the reason is part of the report."""


def quota_used(panel):
    runtime = panel.call("/runtime")
    quota = runtime.get("snapshot", {}).get("quota") or {}
    used = quota.get("weekly_used_percent", quota.get("used_percent"))
    if not runtime.get("quota_fresh") or used is None:
        raise Stop("Cuota desconocida: no se lanza trabajo sin leerla.")
    return float(used)


def wait(panel, item_id, wanted, args, measures):
    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        item = panel.item(item_id)
        if item["state"] == wanted:
            return item
        if item["state"] in ("blocked", "cancelled"):
            measures["blocked"].append({"task": item_id, "waiting_for": wanted, "error": item.get("error") or ""})
            raise Stop(f"{item_id} quedó en {item['state']} esperando {wanted}.")
        used = quota_used(panel)
        if used >= args.max_quota:
            raise Stop(f"Cuota al {used} %, por encima del límite de {args.max_quota} %.")
        time.sleep(args.poll)
    measures["blocked"].append({"task": item_id, "waiting_for": wanted, "error": "tiempo agotado"})
    raise Stop(f"{item_id} no llegó a {wanted} en {args.timeout} segundos.")


def task(panel, args, measures, title, request, recipe=None):
    item_id = "pilot-" + uuid.uuid4().hex[:12]
    body = {"request_id": item_id, "repository": args.repo, "title": title, "request": request}
    if recipe:
        body["recipe"] = recipe
    panel.call("/work", body)
    started = time.monotonic()
    entry = {"task": item_id, "title": title, "integrated": False}
    measures["tasks"].append(entry)
    proposal = wait(panel, item_id, "proposed", args, measures)
    panel.call(f"/work/{item_id}/actions/implement", {"version": proposal["version"]})
    candidate = wait(panel, item_id, "review", args, measures)
    entry["pr"] = candidate.get("pr")
    entry["sha"] = candidate.get("sha")
    entry["seconds_to_review"] = round(time.monotonic() - started)
    evidence = json.loads(candidate.get("evidence") or "{}")
    entry["review_passed"] = (evidence.get("review") or {}).get("passed")
    # A candidate that reached review blocked is a finding: the run must not look like a success.
    if evidence.get("passed") is False:
        reason = (evidence.get("review") or {}).get("summary") or "las evidencias no pasan"
        measures["blocked"].append({"task": item_id, "waiting_for": "review", "error": reason})
        raise Stop(f"{item_id} llegó a revisión sin pasar sus evidencias.")
    if args.merge:
        deadline = time.monotonic() + args.timeout
        while not panel.call(f"/evidence/{item_id}").get("ready"):
            if time.monotonic() > deadline:
                blockers = panel.call(f"/evidence/{item_id}").get("blockers")
                measures["blocked"].append({"task": item_id, "waiting_for": "acceptance", "error": str(blockers)})
                raise Stop(f"{item_id} no quedó listo para integrar.")
            time.sleep(args.poll)
        panel.call(f"/work/{item_id}/actions/merge", {"sha": candidate["sha"]})
        wait(panel, item_id, "integrated", args, measures)
        entry["integrated"] = True
    return entry


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="owner/repo: a disposable repository, never a real product")
    parser.add_argument("--panel", default="http://localhost:8080")
    parser.add_argument("--user", default="admin")
    parser.add_argument("--password-file", required=True)
    parser.add_argument("--request", action="append", default=[], help="a task to launch after adopting; repeatable")
    parser.add_argument("--skip-adoption", action="store_true", help="the repository already has agentfarm.yaml")
    parser.add_argument("--merge", action="store_true", help="also integrate by SHA from the panel")
    parser.add_argument("--max-quota", type=float, default=90, help="weekly percent used at which to stop")
    parser.add_argument("--timeout", type=int, default=3600, help="seconds to wait for each stage")
    parser.add_argument("--poll", type=float, default=10)
    args = parser.parse_args()
    with open(args.password_file) as f:
        panel = Panel(args.panel, args.user, f.read().strip())
    measures = {"repository": args.repo, "tasks": [], "blocked": [], "stopped": None, "commit": None}
    code = 0
    try:
        measures["commit"] = panel.call("/version").get("commit")
        measures["quota_start"] = quota_used(panel)
        if measures["quota_start"] >= args.max_quota:
            raise Stop(f"Cuota al {measures['quota_start']} %, por encima del límite de {args.max_quota} %.")
        if not args.skip_adoption:
            task(panel, args, measures, "Adoptar " + args.repo,
                 "Adopta este repositorio para que los agentes lo mantengan.", {"id": "adopt-repo", "version": 2})
        for n, request in enumerate(args.request, 1):
            task(panel, args, measures, f"Piloto {n}", request)
    except Stop as stop:
        measures["stopped"] = str(stop)
        code = 1
    except urllib.error.HTTPError as e:
        measures["stopped"] = f"El panel respondió {e.code}: {e.read().decode(errors='replace')[:300]}"
        code = 1
    try:
        measures["quota_end"] = quota_used(panel)
    except (Stop, urllib.error.URLError):
        measures["quota_end"] = None
    done = [t for t in measures["tasks"] if t["integrated"]]
    measures["integrated"] = len(done)
    if measures.get("quota_start") is not None and measures["quota_end"] is not None and done:
        spent = measures["quota_end"] - measures["quota_start"]
        # The provider reads in whole percents: no rise means under 1 %, not free.
        measures["quota_per_integrated_task"] = round(spent / len(done), 2) if spent > 0 else None
        if spent <= 0:
            measures["quota_note"] = "La cuota no subió entre lecturas, que son en enteros: menos de 1 % en total."
    print(json.dumps(measures, ensure_ascii=False, indent=2))
    return code


if __name__ == "__main__":
    sys.exit(main())
