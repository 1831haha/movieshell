#!/usr/bin/env python3
"""Watch for the workflow run for a given head SHA on a branch, poll until complete."""
import json
import sys
import time
import urllib.request
import urllib.error

sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/bin")
from dynamic_credentials import add_surrogate_to_request

REPO = "1831haha/movieshell"
API = f"https://api.github.com/repos/{REPO}"


def api(method, url, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, method=method, data=data)
    req.add_header("Accept", "application/vnd.github+json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    add_surrogate_to_request(req, "custom.github", allowed_hosts=["api.github.com"])
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            detail = json.loads(raw)
        except Exception:
            detail = {"raw": raw[:500].decode("utf-8", "replace")}
        return e.code, detail


def main():
    branch = sys.argv[1]
    want_sha = sys.argv[2]
    deadline = time.time() + 60 * 60  # 60 min max
    run = None
    # Phase 1: find the run (up to 10 min)
    find_deadline = time.time() + 10 * 60
    while time.time() < find_deadline:
        status, runs = api("GET", f"{API}/actions/runs?branch={branch}&per_page=10")
        if status != 200:
            print(f"list runs HTTP {status}: {runs}", flush=True)
            time.sleep(60)
            continue
        for r in runs.get("workflow_runs", []):
            if r.get("head_sha", "").startswith(want_sha):
                run = r
                break
        if run:
            break
        print(f"no run for {want_sha[:8]} yet, retrying...", flush=True)
        time.sleep(60)
    if not run:
        print(f"NO RUN FOUND for {want_sha} within 10 min", flush=True)
        return 2
    run_id = run["id"]
    print(f"RUN {run_id} sha={run['head_sha'][:8]} status={run['status']} conclusion={run.get('conclusion')} url={run['html_url']}", flush=True)
    # Phase 2: wait for completion
    while time.time() < deadline:
        status, r = api("GET", f"{API}/actions/runs/{run_id}")
        if status != 200:
            print(f"get run HTTP {status}: {r}", flush=True)
            time.sleep(120)
            continue
        print(f"[{time.strftime('%H:%M:%S')}] status={r['status']} conclusion={r.get('conclusion')}", flush=True)
        if r["status"] == "completed":
            print(f"COMPLETED run={run_id} conclusion={r.get('conclusion')} url={r['html_url']}", flush=True)
            return 0 if r.get("conclusion") == "success" else 1
        time.sleep(150)
    print("TIMEOUT waiting for completion", flush=True)
    return 3


if __name__ == "__main__":
    sys.exit(main())
