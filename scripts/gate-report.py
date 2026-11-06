#!/usr/bin/env python3
"""Read the gate history that `scripts/gate-run.sh` accumulates.

    scripts/gate-report.py                       # rates and victims
    scripts/gate-report.py --test PagingPresenter # every occurrence, with the message
    scripts/gate-report.py --compare pre-fix post-fix  # a measured before/after
    scripts/gate-report.py --runs                # one line per run

Why a report tool at all, when the failures are printed as they happen: because
every quantitative claim in `docs/tech-debt.md`'s flake entries had to be
re-obtained by re-running the gate, and the two claims that turned out to be
wrong were wrong for the same reason — a sample too small to say anything. Three
clean serial runs "proved" contention and were refuted by seven more; 0/10 vs
2/10 "proved" a Kotlin 2.4 regression and was refuted at 3/20 vs 2/20.

Hence `--compare`, which prints the Fisher exact p alongside the two rates. It
is there to make the weak claim visibly weak: at a ~12 % base rate, 16 clean runs
after a fix give p ≈ 0.26, which is no evidence at all. ⚠⚠ The standard this
repo actually settles fixes with is the **forced-interleaving pair** —
deterministic red under a forced deschedule without the fix, deterministic green
with it — and a rate is corroboration for that, never a substitute.
"""
import argparse
import collections
import json
import math
import pathlib
import re
import sys


def load(paths, label=None, since=None, module=None):
    runs = []
    for path in paths:
        p = pathlib.Path(path)
        if not p.exists():
            continue
        with p.open(encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                try:
                    run = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if label is not None and run.get("label") != label:
                    continue
                if since is not None and run.get("started", "") < since:
                    continue
                if module is not None:
                    run = dict(run)
                    run["failures"] = [f for f in run["failures"] if f["module"] == module]
                runs.append(run)
    return runs


def is_red(run):
    """Red = a test failed. Deliberately NOT 'the build failed'.

    A build that fails to compile is a different animal from a test that goes
    red, and merging them is how a compile error gets counted into a flake rate.
    """
    return bool(run["failures"])


def fisher_exact_two_sided(a, b, c, d):
    """p for the 2x2 table [[a, b], [c, d]]. Stdlib only; the tables are tiny."""
    n, r1, r2, c1 = a + b + c + d, a + b, c + d, a + c
    if min(n, r1, r2, c1) < 0 or n == 0:
        return float("nan")
    denom = math.comb(n, c1)

    def prob(x):
        return math.comb(r1, x) * math.comb(r2, c1 - x) / denom

    observed = prob(a)
    total = 0.0
    for x in range(max(0, c1 - r2), min(r1, c1) + 1):
        p = prob(x)
        if p <= observed * (1 + 1e-9):
            total += p
    return min(1.0, total)


def summarise(runs):
    red = [r for r in runs if is_red(r)]
    print(f"{len(runs)} run(s), {len(red)} with a red test", end="")
    if runs:
        print(f"  ({100 * len(red) / len(runs):.0f}%)")
    else:
        print()

    if not runs:
        return

    # The UP-TO-DATE trap, made visible: a run that executed far fewer test tasks
    # than its peers proved correspondingly less, and from the outside it looks
    # exactly like a fast green one.
    counts = sorted(len(r["tasks"]) for r in runs)
    median = counts[len(counts) // 2]
    thin = [r for r in runs if len(r["tasks"]) < median * 0.9]
    if thin:
        print(f"\n⚠ {len(thin)} run(s) executed fewer test tasks than the median ({median}) "
              "— they verified less than they appear to:")
        for run in thin:
            print(f"    {run['run_id']}  {len(run['tasks'])} tasks  {run['gradle_args']}")

    noted = [r for r in runs if r.get("notes")]
    if noted:
        print(f"\n⚠ {len(noted)} run(s) carry harvest notes:")
        for run in noted:
            for note in run["notes"]:
                print(f"    {run['run_id']}: {note}")

    victims = collections.Counter()
    seen = {}
    for run in runs:
        for failure in run["failures"]:
            key = (failure["module"], failure["class"].rsplit(".", 1)[-1], failure["test"])
            victims[key] += 1
            first, _ = seen.get(key, (run["started"], None))
            seen[key] = (first, run["started"])

    if victims:
        print(f"\n{len(victims)} distinct victim(s):\n")
        width = max(len(f"{k[1]}.{k[2]}") for k in victims)
        for (module, cls, test), count in victims.most_common():
            first, last = seen[(module, cls, test)]
            rate = f"{count}/{len(runs)}"
            print(f"  {rate:>8}  {f'{cls}.{test}':<{width}}  {module}")
            if first != last:
                print(f"            {'':<{width}}  first {first}  last {last}")


def detail(runs, pattern):
    needle = re.compile(pattern, re.IGNORECASE)
    hits = 0
    for run in runs:
        for failure in run["failures"]:
            if not (needle.search(failure["class"]) or needle.search(failure["test"])):
                continue
            hits += 1
            print(f"── {run['run_id']}  {run['started']}  commit {run['commit'][:8]}"
                  f"{' (dirty)' if run['dirty'] else ''}  label={run['label'] or '-'}")
            print(f"   {failure['module']}  {failure['class']}.{failure['test']}"
                  f"  ({failure['time']:.1f}s)")
            print(f"   load {run['loadavg']}  cpus {run['cpus']}  "
                  f"{len(run['tasks'])} test tasks in this run")
            if failure["type"]:
                print(f"   {failure['kind']}: {failure['type']}")
            if failure["message"]:
                print(f"   message: {failure['message'].strip()}")
            if failure["detail"]:
                print("   detail:")
                for line in failure["detail"].strip().splitlines():
                    print(f"     {line}")
            print()
    if not hits:
        print(f"no failure matching /{pattern}/ in {len(runs)} run(s)")


def compare(runs, arm_a, arm_b):
    arms = {}
    for name in (arm_a, arm_b):
        subset = [r for r in runs if r.get("label") == name]
        arms[name] = (len([r for r in subset if is_red(r)]), len(subset))

    for name, (red, total) in arms.items():
        rate = f"{100 * red / total:.0f}%" if total else "-"
        print(f"  {name:<20} {red}/{total} red  ({rate})")

    (red_a, n_a), (red_b, n_b) = arms[arm_a], arms[arm_b]
    if n_a and n_b:
        p = fisher_exact_two_sided(red_a, n_a - red_a, red_b, n_b - red_b)
        print(f"\n  Fisher exact two-sided p = {p:.3f}")
        if p > 0.05:
            print("  ⚠ Not significant. This comparison distinguishes nothing on its own —\n"
                  "    see the module docstring: the evidence for a race fix is a forced\n"
                  "    interleaving that fails deterministically without it.")


def runs_table(runs):
    for run in runs:
        mark = "✗" if is_red(run) else "·"
        print(f"{mark} {run['run_id']}  {run['build_result']:<10} "
              f"{len(run['tasks']):>3} tasks  {run['totals']['tests']:>5} tests  "
              f"{len(run['failures'])} red  {run['duration_s']:>5}s  "
              f"label={run['label'] or '-'}  {run['commit'][:8]}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--history", action="append", default=None,
                    help="history file(s); default .tenshu-gate/history.jsonl")
    ap.add_argument("--label", help="only runs carrying this --label")
    ap.add_argument("--since", help="only runs started at/after this ISO timestamp")
    ap.add_argument("--module", help="only failures in this Gradle module path")
    ap.add_argument("--test", help="show every occurrence matching this regex, in full")
    ap.add_argument("--compare", nargs=2, metavar=("A", "B"), help="two labels, as a rate")
    ap.add_argument("--runs", action="store_true", help="one line per run")
    args = ap.parse_args()

    history = args.history or [".tenshu-gate/history.jsonl"]
    runs = load(history, label=args.label, since=args.since, module=args.module)
    if not runs and not args.compare:
        print(f"no runs in {', '.join(history)} — run scripts/gate-run.sh first")
        return 0

    if args.test:
        detail(runs, args.test)
    elif args.compare:
        compare(load(history, since=args.since, module=args.module), *args.compare)
    elif args.runs:
        runs_table(runs)
    else:
        summarise(runs)
    return 0


if __name__ == "__main__":
    sys.exit(main())
