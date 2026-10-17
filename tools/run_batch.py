#!/usr/bin/env python3
"""Serial task runner for the phone lane.

One task at a time. Branch, run opencode against the phone model, run the
task's own check, commit on green, hard-reset on red. Never merges, never
pushes.

Task schema (one JSON object per line in tasks.jsonl):

  {
    "id": "rename-foo",              # unique, used for the branch name
    "repo": "/home/robot/work/thing",
    "base": "testing",               # branch to start from
    "title": "Rename foo to bar",    # short, for the commit subject
    "prompt": "In src/foo.py ...",   # what the model must do
    "check": "pytest -q tests/test_foo.py",
    "check_timeout": 600,            # seconds, optional
    "timeout": 2700                  # seconds for the opencode run, optional
  }
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DEFAULT_TASKS = ROOT / "tasks.jsonl"
DEFAULT_CONFIG = ROOT / "opencode.json"
DEFAULT_RESULTS = ROOT / "results.jsonl"
LOG_DIR = ROOT / "logs"
LOCK = ROOT / ".runner.lock"

MODEL = "phone/gemma-4-e2b"
AGENT = "phone-coder"


def now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def log(msg: str) -> None:
    print(f"[{now()}] {msg}", flush=True)


def sh(cmd, cwd=None, check=True, timeout=None, env=None, capture=True):
    return subprocess.run(
        cmd,
        cwd=cwd,
        check=check,
        timeout=timeout,
        env=env,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
    )


def phone_base() -> str:
    url = os.environ.get("PHONE_URL", "http://phone.lan:8080").rstrip("/")
    return url[:-3] if url.endswith("/v1") else url


def health(verbose: bool = True) -> bool:
    base = phone_base()
    req = urllib.request.Request(base + "/health")
    key = os.environ.get("PHONE_KEY")
    if key:
        req.add_header("Authorization", f"Bearer {key}")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            body = r.read(200).decode("utf-8", "replace").strip()
        if verbose:
            log(f"health ok: {base}/health -> {body}")
        return True
    except Exception as e:  # noqa: BLE001 - any failure means the lane is down
        if verbose:
            log(f"HEALTH FAILED: {base}/health -> {type(e).__name__}: {e}")
        return False


def load_tasks(path: Path) -> list[dict]:
    tasks = []
    seen = set()
    for n, line in enumerate(path.read_text().splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            t = json.loads(line)
        except json.JSONDecodeError as e:
            raise SystemExit(f"{path}:{n}: invalid JSON: {e}")
        for key in ("id", "repo", "base", "prompt", "check"):
            if key not in t:
                raise SystemExit(f"{path}:{n}: missing required key {key!r}")
        if t["id"] in seen:
            raise SystemExit(f"{path}:{n}: duplicate id {t['id']!r}")
        seen.add(t["id"])
        tasks.append(t)
    return tasks


def finished_ids(results: Path) -> set[str]:
    if not results.exists():
        return set()
    done = set()
    for line in results.read_text().splitlines():
        try:
            r = json.loads(line)
        except json.JSONDecodeError:
            continue
        if r.get("status") in ("committed", "no-change", "failed", "skipped"):
            done.add(r.get("id"))
    return done


def record(results: Path, row: dict) -> None:
    with results.open("a") as f:
        f.write(json.dumps(row) + "\n")


def git(repo: Path, *args, **kw):
    return sh(["git", *args], cwd=repo, **kw)


def run_one(task: dict, args) -> str:
    repo = Path(task["repo"]).expanduser()
    if not (repo / ".git").exists():
        log(f"{task['id']}: not a git repo: {repo}")
        return "skipped"

    dirty = git(repo, "status", "--porcelain").stdout.strip()
    if dirty:
        log(f"{task['id']}: repo has uncommitted changes; refusing to run")
        return "skipped"

    branch = f"phone/{task['id']}"
    base = task["base"]
    model = task.get("model", args.model)
    timeout = int(task.get("timeout", args.max_minutes * 60))
    check_timeout = int(task.get("check_timeout", 600))

    if args.dry_run:
        log(f"[dry-run] {task['id']}: {repo} @ {base} -> {branch}")
        log(f"[dry-run]   opencode run --auto --model {model} --agent {AGENT} <prompt>")
        log(f"[dry-run]   check: {task['check']}")
        return "skipped"

    log(f"{task['id']}: preparing {repo} from {base}")
    git(repo, "fetch", "--prune", "origin", check=False)
    git(repo, "checkout", base)
    git(repo, "pull", "--ff-only", check=False)
    git(repo, "checkout", "-B", branch)

    env = dict(os.environ)
    env["OPENCODE_CONFIG"] = str(Path(args.config).resolve())
    env.setdefault("OPENCODE_DISABLE_AUTOUPDATE", "1")

    cmd = [args.opencode, "run", "--auto", "--model", model, "--agent", AGENT,
           task["prompt"]]
    LOG_DIR.mkdir(exist_ok=True)
    logfile = LOG_DIR / f"{task['id']}-{time.strftime('%Y%m%d-%H%M%S')}.log"

    log(f"{task['id']}: opencode running (timeout {timeout}s)")
    started = time.time()
    try:
        with logfile.open("w") as fh:
            proc = subprocess.run(cmd, cwd=repo, env=env, timeout=timeout,
                                  text=True, stdout=fh, stderr=subprocess.STDOUT)
        rc_run = proc.returncode
    except subprocess.TimeoutExpired:
        rc_run = 124
        log(f"{task['id']}: opencode timed out after {timeout}s")

    elapsed = int(time.time() - started)

    # opencode can exit non-zero for a stray tool failure while still doing the
    # task correctly (observed: a phantom web-search call after the edit
    # succeeded, exit 1, file correct). The task's own check is the arbiter;
    # the exit code is recorded but does not by itself reject the work.
    if rc_run != 0:
        log(f"{task['id']}: opencode rc={rc_run}; deferring to the check")

    log(f"{task['id']}: check: {task['check']}")
    try:
        chk = subprocess.run(task["check"], shell=True, cwd=repo, timeout=check_timeout,
                             text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        rc_check = chk.returncode
        with logfile.open("a") as fh:
            fh.write("\n\n===== CHECK =====\n")
            fh.write(chk.stdout or "")
    except subprocess.TimeoutExpired:
        rc_check = 124
        log(f"{task['id']}: check timed out after {check_timeout}s")

    if rc_check != 0:
        git(repo, "reset", "--hard", base, check=False)
        git(repo, "clean", "-fd", check=False)
        git(repo, "checkout", base, check=False)
        git(repo, "branch", "-D", branch, check=False)
        log(f"{task['id']}: FAILED check (rc={rc_check}) -> {logfile}")
        return "failed"

    git(repo, "add", "-A")
    staged = git(repo, "diff", "--cached", "--name-only").stdout.strip()
    if not staged:
        git(repo, "checkout", base, check=False)
        git(repo, "branch", "-D", branch, check=False)
        log(f"{task['id']}: no changes made")
        return "no-change"

    subject = f"phone-lane: {task.get('title') or task['id']}"
    body = (f"Task: {task['id']}\nModel: {MODEL}\n"
            f"Check: {task['check']}\nRun: {logfile.name}")
    git(repo, "commit", "-m", subject, "-m", body)
    head = git(repo, "rev-parse", "--short", "HEAD").stdout.strip()
    git(repo, "checkout", base, check=False)
    log(f"{task['id']}: COMMITTED {head} on {branch} ({elapsed}s) -> {logfile}")
    return "committed"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tasks", default=str(DEFAULT_TASKS))
    ap.add_argument("--config", default=str(DEFAULT_CONFIG))
    ap.add_argument("--results", default=str(DEFAULT_RESULTS))
    ap.add_argument("--opencode", default="opencode")
    ap.add_argument("--model", default=MODEL,
                    help="provider/model id; a task's own 'model' field wins")
    ap.add_argument("--max-minutes", type=int, default=45)
    ap.add_argument("--loop", action="store_true", help="keep polling for new tasks")
    ap.add_argument("--sleep", type=int, default=120)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--no-health", action="store_true",
                    help="skip the startup/loop health check (offline testing)")
    args = ap.parse_args()

    tasks_path = Path(args.tasks)
    results = Path(args.results)
    if not tasks_path.exists():
        print(f"no task file: {tasks_path}", file=sys.stderr)
        return 2

    if LOCK.exists():
        print(f"runner already active (lock {LOCK}); refusing to start", file=sys.stderr)
        return 3

    if not args.dry_run and not args.no_health and not health():
        print("phone is not answering; not starting the batch", file=sys.stderr)
        return 4

    LOCK.write_text(str(os.getpid()))
    try:
        while True:
            tasks = load_tasks(tasks_path)
            done = finished_ids(results)
            pending = [t for t in tasks if t["id"] not in done]

            if not pending:
                log("no pending tasks")
                if not args.loop:
                    return 0
                time.sleep(args.sleep)
                continue

            for task in pending:
                if not args.dry_run and not args.no_health and not health(verbose=False):
                    log("phone stopped answering; aborting the batch")
                    return 5
                status = run_one(task, args)
                if args.dry_run:
                    continue
                record(results, {
                    "id": task["id"], "status": status, "at": now(),
                    "model": task.get("model", args.model),
                    "branch": f"phone/{task['id']}",
                })
                if status in ("failed", "skipped"):
                    continue

            if not args.loop:
                return 0
            time.sleep(args.sleep)
    finally:
        LOCK.unlink(missing_ok=True)


if __name__ == "__main__":
    sys.exit(main())
