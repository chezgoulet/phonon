#!/usr/bin/env python3
"""report.py - record generation rows and build the run summary.

Subcommands
-----------
record   append one run-generation record to a raw JSONL file
report   read the JSONL and write bench.csv + summary.md + summary.json

Python 3 stdlib only. Handles no API keys.

The JSONL is the durable artefact: the CSV and both summaries are always
(re)derived from it, so an aborted run still produces a usable report from
whatever rows were completed.
"""

from __future__ import annotations

import argparse
import csv
import datetime as _dt
import json
import sys

COLUMNS = [
    "ts", "node", "regime", "gen_index", "elapsed_s", "prompt_tokens",
    "completion_tokens", "prefill_tok_s", "decode_tok_s", "wall_s",
    "temp_c", "status", "source", "note",
]

METRIC_KEYS = [
    "status", "prompt_tokens", "completion_tokens", "prefill_tok_s",
    "decode_tok_s", "wall_s", "source", "note",
]


def _iso_now():
    return _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# --------------------------------------------------------------------------
# record
# --------------------------------------------------------------------------

def cmd_record(args):
    metrics_text = ""
    if args.metrics_json is not None:
        metrics_text = args.metrics_json
    elif args.metrics_file:
        try:
            with open(args.metrics_file, "r", encoding="utf-8") as handle:
                metrics_text = handle.read()
        except OSError as exc:
            print("report: cannot read metrics file: %s" % exc.strerror,
                  file=sys.stderr)

    try:
        metrics = json.loads(metrics_text) if metrics_text.strip() else {}
    except Exception:  # noqa: BLE001
        metrics = {"status": "error", "note": "unparseable_metrics"}
    if not isinstance(metrics, dict):
        metrics = {"status": "error", "note": "unparseable_metrics"}

    temp_c = None
    temp_source = None
    if args.temp_json:
        try:
            temp = json.loads(args.temp_json)
            if isinstance(temp, dict):
                temp_c = temp.get("temp_c")
                temp_source = temp.get("source")
        except Exception:  # noqa: BLE001
            pass

    record = {
        "node": args.node,
        "regime": args.regime,
        "gen_index": args.gen_index,
        "elapsed_s": args.elapsed_s,
        "temp_c": temp_c,
        "ts": args.ts or _iso_now(),
    }
    record.update({k: metrics.get(k) for k in METRIC_KEYS})
    if not record.get("note"):
        record["note"] = "" if metrics.get("status") == "ok" else (temp_source or "")

    with open(args.jsonl, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(record) + "\n")
    return 0


# --------------------------------------------------------------------------
# report
# --------------------------------------------------------------------------

def read_jsonl(path):
    rows = []
    try:
        with open(path, "r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except Exception:  # noqa: BLE001
                    continue
    except OSError:
        pass
    return rows


def _numbers(rows, key):
    out = []
    for row in rows:
        value = row.get(key)
        if isinstance(value, (int, float)):
            out.append(float(value))
    return out


def stats(values):
    if not values:
        return {"n": 0, "median": None, "min": None, "max": None}
    ordered = sorted(values)
    count = len(ordered)
    if count % 2:
        median = ordered[count // 2]
    else:
        median = (ordered[count // 2 - 1] + ordered[count // 2]) / 2.0
    return {"n": count, "median": round(median, 2),
            "min": round(ordered[0], 2), "max": round(ordered[-1], 2)}


def regime_stats(rows):
    ok = [row for row in rows if row.get("status") == "ok"]
    elapsed = _numbers(rows, "elapsed_s")
    return {
        "generations": len(rows),
        "ok_generations": len(ok),
        "duration_s": round(max(elapsed) - min(elapsed), 1) if len(elapsed) > 1 else 0.0,
        "prefill_tok_s": stats(_numbers(ok, "prefill_tok_s")),
        "decode_tok_s": stats(_numbers(ok, "decode_tok_s")),
        "wall_s": stats(_numbers(ok, "wall_s")),
        "temp_c": stats(_numbers(ok, "temp_c")),
    }


def time_to_first_throttle(rows, baseline, frac, window, warmup=1):
    """First sustained generation whose decode sits at/below frac*baseline for
    `window` consecutive samples.  Returns (detail, status).

    The first `warmup` sustained generations are skipped: the first generation
    after a one-off burst reflects page-cache and governor settling, not heat,
    and flagging it would report a throttle that never happened."""
    if not baseline or baseline <= 0:
        return None, "no_baseline"
    threshold = baseline * frac
    run = 0
    run_start = None
    ordered = sorted(rows, key=lambda r: r.get("gen_index") or 0)
    for row in ordered[max(0, warmup):]:
        decode = row.get("decode_tok_s")
        if not isinstance(decode, (int, float)):
            run = 0
            run_start = None
            continue
        if decode <= threshold:
            if run == 0:
                run_start = row
            run += 1
            if run >= max(1, window):
                return {
                    "elapsed_s": run_start.get("elapsed_s"),
                    "gen_index": run_start.get("gen_index"),
                    "decode_tok_s": run_start.get("decode_tok_s"),
                    "threshold_tok_s": round(threshold, 2),
                }, "throttled"
        else:
            run = 0
            run_start = None
    return None, "not_throttled"


def build_summary(rows, args):
    nodes = {}
    for row in rows:
        nodes.setdefault(row.get("node") or "unknown", []).append(row)

    summary = {
        "generated_at": _iso_now(),
        "throttle_frac": args.throttle_frac,
        "throttle_window": args.throttle_window,
        "baseline_samples": args.baseline_samples,
        "nodes": {},
    }

    for node, node_rows in nodes.items():
        regimes = {}
        for regime in ("burst", "sustained", "recovery"):
            subset = [r for r in node_rows if r.get("regime") == regime]
            if subset:
                regimes[regime] = regime_stats(subset)

        sustained = [r for r in node_rows if r.get("regime") == "sustained"]
        burst = [r for r in node_rows if r.get("regime") == "burst"]

        burst_median = None
        if burst:
            burst_median = stats(_numbers([r for r in burst
                                           if r.get("status") == "ok"], "decode_tok_s"))["median"]

        # The throttle baseline is the head of the sustained run, never the
        # burst. A one-off short generation is faster than any continuous rate
        # on a phone, so measuring decay against it reports the difference
        # between burst and steady state as if it were heat.
        early = sorted([r for r in sustained if r.get("status") == "ok"],
                       key=lambda r: r.get("gen_index") or 0)[:args.baseline_samples]
        baseline = stats(_numbers(early, "decode_tok_s"))["median"]
        baseline_source = "sustained_head"

        detail, status = time_to_first_throttle(
            [r for r in sustained if r.get("status") == "ok"],
            baseline, args.throttle_frac, args.throttle_window, warmup=1)

        errors = {}
        for row in node_rows:
            key = row.get("status") or "unknown"
            errors[key] = errors.get(key, 0) + 1

        summary["nodes"][node] = {
            "regimes": regimes,
            "baseline_decode_tok_s": baseline,
            "burst_decode_tok_s": burst_median,
            "baseline_source": baseline_source,
            "time_to_first_throttle": detail,
            "throttle_status": status,
            "status_counts": errors,
        }
    return summary


# --------------------------------------------------------------------------
# rendering
# --------------------------------------------------------------------------

def _fmt(value):
    if value is None:
        return "-"
    if isinstance(value, float):
        return ("%.2f" % value).rstrip("0").rstrip(".")
    return str(value)


def _stats_table(rows_stats):
    lines = [
        "| metric | n | median | min | max |",
        "|---|---|---|---|---|",
    ]
    for key, name in (("prefill_tok_s", "prefill tok/s"),
                      ("decode_tok_s", "decode tok/s"),
                      ("wall_s", "wall s"),
                      ("temp_c", "battery temp C")):
        block = rows_stats.get(key, {})
        lines.append("| %s | %s | %s | %s | %s |" % (
            name, block.get("n", 0), _fmt(block.get("median")),
            _fmt(block.get("min")), _fmt(block.get("max"))))
    return "\n".join(lines)


def render_markdown(summary):
    out = []
    out.append("# Phonon sustained-load benchmark summary")
    out.append("")
    out.append("Generated: %s" % summary["generated_at"])
    out.append("")
    out.append("Throttle rule: decode tok/s <= %.2f x baseline for %d consecutive "
               "sustained generations, after a one-generation warm-up."
               % (summary["throttle_frac"], summary["throttle_window"]))
    out.append("")

    for node, data in summary["nodes"].items():
        out.append("## %s" % node)
        out.append("")
        out.append("- baseline decode: %s tok/s (source: %s)"
                   % (_fmt(data["baseline_decode_tok_s"]), data["baseline_source"]))
        detail = data["time_to_first_throttle"]
        if data["throttle_status"] == "throttled" and detail:
            out.append("- time to first throttle: %s s (generation %s, decode %s tok/s; "
                       "threshold %s tok/s)"
                       % (_fmt(detail["elapsed_s"]), _fmt(detail["gen_index"]),
                          _fmt(detail["decode_tok_s"]), _fmt(detail["threshold_tok_s"])))
        else:
            out.append("- time to first throttle: none (%s)" % data["throttle_status"])
        out.append("- status counts: %s" % json.dumps(data["status_counts"]))
        out.append("")

        for regime in ("burst", "sustained", "recovery"):
            block = data["regimes"].get(regime)
            if not block:
                continue
            out.append("### %s" % regime)
            out.append("")
            out.append("generations: %d (ok: %d), span: %s s"
                       % (block["generations"], block["ok_generations"],
                          _fmt(block["duration_s"])))
            out.append("")
            out.append(_stats_table(block))
            out.append("")

    # Suggested defaults table.
    out.append("## Suggested per-device defaults")
    out.append("")
    out.append("| node | burst decode median | sustained decode median | sustained floor (min) | time to first throttle |")
    out.append("|---|---|---|---|---|")
    for node, data in summary["nodes"].items():
        regimes = data["regimes"]
        burst = regimes.get("burst", {}).get("decode_tok_s", {}).get("median")
        sustained = regimes.get("sustained", {}).get("decode_tok_s", {})
        detail = data["time_to_first_throttle"]
        ttf = (_fmt(detail["elapsed_s"]) + " s") if detail else "none in budget"
        out.append("| %s | %s | %s | %s | %s |" % (
            node, _fmt(burst), _fmt(sustained.get("median")),
            _fmt(sustained.get("min")), ttf))
    out.append("")
    out.append("See README.md for how to turn these numbers into per-device "
               "defaults (burst for latency-sensitive work, sustained for "
               "throughput planning).")
    out.append("")
    return "\n".join(out)


def cmd_report(args):
    rows = read_jsonl(args.jsonl)
    summary = build_summary(rows, args)

    with open(args.csv, "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=COLUMNS, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow({key: row.get(key) for key in COLUMNS})

    with open(args.md, "w", encoding="utf-8") as handle:
        handle.write(render_markdown(summary))

    with open(args.json, "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2, sort_keys=True)
        handle.write("\n")

    print("report: %d rows -> %s, %s, %s" % (len(rows), args.csv, args.md, args.json))
    return 0


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)

    rec = sub.add_parser("record", help="append one record to a JSONL file")
    rec.add_argument("--jsonl", required=True)
    rec.add_argument("--metrics-json", default=None)
    rec.add_argument("--metrics-file", default=None)
    rec.add_argument("--temp-json", default=None)
    rec.add_argument("--node", required=True)
    rec.add_argument("--regime", required=True)
    rec.add_argument("--gen-index", type=int, default=0)
    rec.add_argument("--elapsed-s", type=float, default=0.0)
    rec.add_argument("--ts", default=None)
    rec.set_defaults(func=cmd_record)

    rep = sub.add_parser("report", help="JSONL -> CSV + summaries")
    rep.add_argument("--jsonl", required=True)
    rep.add_argument("--csv", required=True)
    rep.add_argument("--md", required=True)
    rep.add_argument("--json", required=True)
    rep.add_argument("--throttle-frac", type=float, default=0.8)
    rep.add_argument("--throttle-window", type=int, default=2)
    rep.add_argument("--baseline-samples", type=int, default=5)
    rep.set_defaults(func=cmd_report)
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
