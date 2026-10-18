#!/usr/bin/env python3
"""node_probe.py - health check and single-generation probe for one Phonon
phone node (an OpenAI-compatible llama.cpp server). Python 3 stdlib only.

Subcommands
-----------
health    GET  <base>/health   -> {"ready": bool, ...}
generate  POST <base>/v1/completions -> one JSON line of generation metrics

Safety
------
* The API key is read from a file and placed only in the Authorization request
  header. It is never printed, logged, or included in any output, on any path
  (including errors).
* ``generate --simulate`` performs no network I/O and needs no key file, so the
  whole harness can be exercised locally.

Exit codes
----------
health:   0 ready | 3 not ready | 4 unreachable | 2 usage/config error
generate: 0 ok    | 5 request or parse error | 2 usage/config error
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import time
import urllib.error
import urllib.request

DEFAULT_PROMPT = (
    "Produce a precise technical explanation of how a mobile GPU schedules "
    "matrix-multiplication tiles, covering tiling, occupancy and "
    "synchronisation, and explain what limits decode throughput on such a "
    "device. Avoid filler."
)

# ~4 characters per token is the usual rough heuristic for a filler prompt.
_FILLER_UNIT = (
    "Mobile GPUs execute matrix multiplication by tiling operands into "
    "shared-memory blocks and scheduling warps to hide latency. "
)


def _headers(key, content_json=True):
    headers = {}
    if content_json:
        headers["Content-Type"] = "application/json"
    if key:
        headers["Authorization"] = "Bearer " + key
    return headers


def read_key(path):
    """Read the bearer token. Never echo the value."""
    if not path:
        return None
    try:
        with open(path, "r", encoding="utf-8") as handle:
            key = handle.read().strip()
    except OSError as exc:
        sys.stderr.write("node_probe: cannot read key file (%s)\n" % exc.strerror)
        sys.exit(2)
    if not key:
        sys.stderr.write("node_probe: key file is empty\n")
        sys.exit(2)
    return key


def _r(value, places=2):
    if value is None:
        return None
    try:
        return round(float(value), places)
    except (TypeError, ValueError):
        return None


def build_prompt(args):
    if args.prompt_file:
        with open(args.prompt_file, "r", encoding="utf-8") as handle:
            return handle.read()
    if args.prompt:
        return args.prompt
    tokens = args.fill_prompt_tokens
    if tokens and tokens > 0:
        target = tokens * 4
        reps = max(1, target // len(_FILLER_UNIT) + 1)
        return (_FILLER_UNIT * reps)[:target]
    return DEFAULT_PROMPT


def cmd_health(args):
    key = read_key(args.key_file)
    url = args.url.rstrip("/") + "/health"
    request = urllib.request.Request(
        url, headers=_headers(key, content_json=False), method="GET"
    )
    try:
        with urllib.request.urlopen(request, timeout=args.timeout) as response:
            code = response.status
            body = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        code = exc.code
        body = exc.read().decode("utf-8", "replace")
    except Exception as exc:  # noqa: BLE001 - report the class only
        print(json.dumps({"ready": False, "reason": "unreachable",
                          "detail": type(exc).__name__}))
        return 4
    ready = code == 200 and "ok" in body.lower()
    print(json.dumps({"ready": ready, "http_status": code,
                      "reason": "ok" if ready else "http_%d" % code}))
    return 0 if ready else 3


def cmd_generate(args):
    if args.simulate:
        return _simulate(args)

    key = read_key(args.key_file)
    url = args.url.rstrip("/") + "/v1/completions"
    payload = {
        "model": args.model,
        "prompt": build_prompt(args),
        "max_tokens": args.max_tokens,
        "temperature": 0.0,
        "top_p": 1.0,
        "stream": False,
        "cache_prompt": False,  # force a real prefill on every generation
    }
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers=_headers(key),
        method="POST",
    )
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=args.timeout) as response:
            code = response.status
            raw = response.read()
    except urllib.error.HTTPError as exc:
        code = exc.code
        raw = exc.read()
    except Exception as exc:  # noqa: BLE001
        _emit_error("request_" + type(exc).__name__, None)
        return 5
    wall = time.time() - started

    try:
        obj = json.loads(raw.decode("utf-8", "replace"))
    except Exception:  # noqa: BLE001
        _emit_error("bad_json", wall)
        return 5

    if code != 200:
        note = "http_%d" % code
        error = obj.get("error") if isinstance(obj, dict) else None
        if isinstance(error, dict) and error.get("message"):
            note = "http_%d: %s" % (code, str(error["message"])[:120])
        _emit_error(note, wall)
        return 5

    usage = obj.get("usage") or {}
    timings = obj.get("timings") or {}
    prefill = timings.get("prompt_per_second")
    decode = timings.get("predicted_per_second")
    prompt_tokens = timings.get("prompt_n", usage.get("prompt_tokens"))
    completion_tokens = timings.get("predicted_n", usage.get("completion_tokens"))

    if decode is not None:
        source = "server"
    elif completion_tokens and wall > 0:
        decode = completion_tokens / wall
        source = "estimated"
    else:
        source = "none"

    print(json.dumps({
        "status": "ok",
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
        "prefill_tok_s": _r(prefill),
        "decode_tok_s": _r(decode),
        "wall_s": _r(wall, 3),
        "source": source,
        "note": "",
    }))
    return 0


def _emit_error(note, wall):
    print(json.dumps({
        "status": "error",
        "prompt_tokens": None,
        "completion_tokens": None,
        "prefill_tok_s": None,
        "decode_tok_s": None,
        "wall_s": _r(wall, 3),
        "source": "none",
        "note": note,
    }))


def _simulate(args):
    """Deterministic synthetic metrics - no network, no key.

    Models a phone that runs cool and fast for the first ~18 generations of a
    sustained run, then throttles.  Used to exercise the whole pipeline
    (CSV + summary + throttle detection) without touching a device.
    """
    index = args.sim_index
    rnd = random.Random((args.seed or 0) * 1000003 + index)
    regime = args.regime or "sustained"
    if regime == "burst":
        decode = 14.6 + rnd.uniform(-0.5, 0.5)
    elif regime == "recovery":
        decode = 13.9 + rnd.uniform(-0.5, 0.5)
    elif index < 18:
        decode = 14.2 + rnd.uniform(-0.4, 0.4)
    elif index < 30:
        decode = 9.6 + rnd.uniform(-0.5, 0.5)
    else:
        decode = 8.3 + rnd.uniform(-0.4, 0.4)
    prefill = 72.0 + rnd.uniform(-6.0, 6.0)
    completion_tokens = args.max_tokens
    prompt_tokens = args.fill_prompt_tokens or 512
    wall = (completion_tokens / decode + prompt_tokens / prefill)
    print(json.dumps({
        "status": "ok",
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
        "prefill_tok_s": round(prefill, 2),
        "decode_tok_s": round(decode, 2),
        "wall_s": round(wall, 3),
        "source": "simulated",
        "note": "",
    }))
    return 0


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)

    health = sub.add_parser("health", help="GET <base>/health")
    health.add_argument("--url", required=True, help="base URL, e.g. http://10.0.0.1:8080")
    health.add_argument("--key-file", default=None)
    health.add_argument("--timeout", type=float, default=10.0)
    health.set_defaults(func=cmd_health)

    gen = sub.add_parser("generate", help="one generation; prints metrics JSON")
    gen.add_argument("--url", required=True)
    gen.add_argument("--key-file", default=None)
    gen.add_argument("--model", default="qwen3.5-2b")
    gen.add_argument("--max-tokens", type=int, default=128)
    gen.add_argument("--timeout", type=float, default=120.0)
    gen.add_argument("--prompt", default=None)
    gen.add_argument("--prompt-file", default=None)
    gen.add_argument("--fill-prompt-tokens", type=int, default=0,
                     help="build a filler prompt of roughly this many tokens")
    gen.add_argument("--regime", default=None, help="burst|sustained|recovery (sim only)")
    gen.add_argument("--sim-index", type=int, default=0)
    gen.add_argument("--seed", type=int, default=0)
    gen.add_argument("--simulate", action="store_true",
                     help="no network; emit synthetic metrics")
    gen.set_defaults(func=cmd_generate)
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
