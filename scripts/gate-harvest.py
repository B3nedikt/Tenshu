#!/usr/bin/env python3
"""Turn one gate run's JUnit XML into one line of durable history.

Called by `scripts/gate-run.sh`; usable standalone against a run you did by hand
(`scripts/gate-harvest.py --run-id manual-1 --log /dev/null`).

The point is not reporting — Gradle already prints a report nobody keeps. It is
that `docs/tech-debt.md`'s flake entries are built out of observations that each
cost a full gate and were then thrown away: two victims are recorded there with
their failure message lost to an overwrite, and the entry's central claims have
twice been drawn from sample sizes (3 runs, 16 runs) that could not support
them. One JSON object per run, appended forever, is what makes the next claim
cheap to check.

⚠ What lands here is FACT, not diagnosis. A test in `failures` is a test that
went red in this run — whether it is a flake, a load-driven regression or a real
bug is exactly what the two-step triage in `docs/tech-debt.md` decides, and
nothing in this file guesses at it.

⚠⚠ A run with *zero* test tasks is recorded, loudly, rather than skipped. That
is the UP-TO-DATE trap the entry names twice: a gate that executed nothing looks
identical to a green one from the outside, and the only way to tell is to count.
"""
import argparse
import json
import os
import pathlib
import platform
import re
import socket
import sys
import xml.etree.ElementTree as ET

MESSAGE_CHARS = 2_000
# Generous, because this is the field the whole exercise exists to keep. The
# root build sets `exceptionFormat = FULL` + `showCauses` precisely so an
# `UncaughtExceptionsBeforeTest` carries the original throw as a cause — the
# only line that names the actual culprit — and truncating that away here would
# undo the fix that made it printable.
DETAIL_CHARS = 8_000


# Pruned so a ~130-module repo with a node_modules tree in it does not cost a
# full walk per run. `build` is descended into only as far as `test-results`.
SKIP_DIRS = {".git", ".gradle", ".kotlin", ".idea", "node_modules", ".tenshu-gate"}


def find_result_dirs(root: pathlib.Path):
    """Yield (module, task, dir) for every `*/build/test-results/<task>/`.

    The module is the path above `build/`, so `:sync-service-proxy` and
    `:core-common` come out the way you would type them at Gradle.
    """
    for dirpath, dirnames, _ in os.walk(root):
        current = pathlib.Path(dirpath)
        if current.name == "build":
            dirnames[:] = [d for d in dirnames if d == "test-results"]
            continue
        if current.name == "test-results":
            module_dir = current.parent.parent
            module = ":" + str(module_dir.relative_to(root)).replace(os.sep, ":")
            for task in sorted(dirnames):
                yield module, task, current / task
            dirnames[:] = []
            continue
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]


def parse_suite(path: pathlib.Path):
    """Parse one TEST-*.xml. Returns (counts, failures, note)."""
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        # A JVM that died mid-write leaves a truncated file. That is a finding
        # (it means a worker crashed), not something to silently drop.
        return None, [], f"unparseable XML {path}: {exc}"

    counts = {
        "tests": int(root.get("tests", 0)),
        "failures": int(root.get("failures", 0)),
        "errors": int(root.get("errors", 0)),
        "skipped": int(root.get("skipped", 0)),
        "time": float(root.get("time", 0) or 0),
    }

    failures = []
    for case in root.iter("testcase"):
        for kind in ("failure", "error"):
            node = case.find(kind)
            if node is None:
                continue
            failures.append({
                "class": case.get("classname", ""),
                "test": case.get("name", ""),
                "time": float(case.get("time", 0) or 0),
                "kind": kind,
                "type": node.get("type", ""),
                "message": (node.get("message") or "")[:MESSAGE_CHARS],
                "detail": (node.text or "")[:DETAIL_CHARS],
            })
    return counts, failures, None


def loadavg():
    try:
        return list(os.getloadavg())
    except (OSError, AttributeError):
        return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--history", default=".tenshu-gate/history.jsonl")
    ap.add_argument("--run-id", required=True)
    ap.add_argument("--label", default="")
    ap.add_argument("--commit", default="unknown")
    ap.add_argument("--dirty", default="false")
    ap.add_argument("--tree-changed", default="false",
                    help="the tracked tree was edited mid-run; its failures may be the edit")
    ap.add_argument("--started", default="")
    ap.add_argument("--finished", default="")
    ap.add_argument("--duration", type=int, default=0)
    ap.add_argument("--exit-code", type=int, default=0)
    ap.add_argument("--build-result", default="unknown")
    ap.add_argument("--log", default="")
    ap.add_argument("--gradle-args", default="")
    ap.add_argument("--root", default=str(pathlib.Path(__file__).resolve().parent.parent))
    args = ap.parse_args()

    root = pathlib.Path(args.root)
    tasks, failures, notes = [], [], []
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "classes": 0}

    for module, task, results_dir in find_result_dirs(root):
        xmls = sorted(results_dir.glob("TEST-*.xml"))
        if not xmls:
            continue
        task_counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "time": 0.0}
        for xml in xmls:
            counts, suite_failures, note = parse_suite(xml)
            if note:
                notes.append(note)
                continue
            for key in ("tests", "failures", "errors", "skipped", "time"):
                task_counts[key] += counts[key]
            for failure in suite_failures:
                failure["module"] = module
                failure["task"] = task
                failures.append(failure)
        tasks.append({"module": module, "task": task, "classes": len(xmls), **task_counts})
        totals["classes"] += len(xmls)
        for key in ("tests", "failures", "errors", "skipped"):
            totals[key] += task_counts[key]

    # The three cross-checks that separate a real result from a plausible-looking
    # one. Each corresponds to a trap `docs/tech-debt.md` records having been hit.
    if not tasks:
        notes.append(
            "NO test task produced XML — this run verified nothing. Either every "
            "task was UP-TO-DATE (use --rerun) or the build failed before tests."
        )
    if args.exit_code == 0 and totals["failures"] + totals["errors"] > 0:
        notes.append(
            f"exit code 0 but {totals['failures'] + totals['errors']} test failure(s) "
            "on disk — a wrapper or a pipe is masking Gradle's status."
        )
    if args.build_result == "unknown":
        notes.append("log carried neither 'BUILD SUCCESSFUL' nor 'BUILD FAILED'.")
    if args.tree_changed == "true":
        notes.append(
            "⚠⚠ THE WORKING TREE CHANGED DURING THIS RUN — treat every failure here as "
            "possibly your own edit, not a sighting. Re-run against a still tree before "
            "attributing anything."
        )
    if args.build_result == "failed" and totals["failures"] + totals["errors"] == 0:
        # The hang shape (docs/tech-debt.md § "A hung test does not go red"): a worker
        # parked on an unbounded wait is eventually killed, and its in-flight case is
        # recorded SKIPPED — so the build is red while every test on disk reads green.
        # Without this note the harvested record corroborates exactly the wrong story.
        notes.append(
            f"build FAILED but no test went red ({totals['skipped']} skipped) — suspect a "
            "hung/killed worker, not a passing run. Check the log for the last task started."
        )

    record = {
        "run_id": args.run_id,
        "label": args.label,
        "commit": args.commit,
        "dirty": args.dirty == "true",
        "tree_changed_during_run": args.tree_changed == "true",
        "started": args.started,
        "finished": args.finished,
        "duration_s": args.duration,
        "exit_code": args.exit_code,
        "build_result": args.build_result,
        "gradle_args": args.gradle_args,
        "log": args.log,
        "host": socket.gethostname(),
        "platform": platform.platform(),
        "cpus": os.cpu_count(),
        "loadavg": loadavg(),
        "totals": totals,
        "tasks": sorted(tasks, key=lambda t: (t["module"], t["task"])),
        "failures": failures,
        "notes": notes,
    }

    history = pathlib.Path(args.history)
    history.parent.mkdir(parents=True, exist_ok=True)
    with history.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, ensure_ascii=False) + "\n")

    print(
        f"   harvested: {len(tasks)} test tasks, {totals['classes']} classes, "
        f"{totals['tests']} tests, {totals['failures'] + totals['errors']} red"
    )
    for failure in failures:
        print(f"     ✗ {failure['module']} {failure['class']}.{failure['test']}")
    for note in notes:
        print(f"     ⚠ {note}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
