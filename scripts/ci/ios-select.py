#!/usr/bin/env python3
"""What the iOS CI workflow runs for one change.

    ios-select.py GRAPH_JSON --full            everything (nightly, manual)
    ios-select.py GRAPH_JSON BASE HEAD         what the change between BASE and HEAD can break
    ios-select.py --halves                     the two app halves, one per line (checked against
                                               `ios-smoke.sh --build-only`'s list by the workflow)

GRAPH_JSON is the root build's `-Ptenshu.projectGraph` output. Prints `key=value` lines for
`$GITHUB_OUTPUT`:

    reason=…            why this selection, for the job log
    test_modules=…      the iOS-test modules to run, space-separated
    shards=[1,2,…]      the test matrix; [] runs no test job
    shard_count=N
    app_halves=[…]      the app matrix, each half cut to its affected apps; [] runs no app job

A module is affected exactly as `scripts/run-gate.sh --quick` decides it (`affected_modules.py`:
a changed file under it, or a dependency on an affected module), and an app's Swift shell is
affected when its `<app>-ios/` changed, a path its `project.yml` or an included fragment names
changed (shared Swift folders, bundled assets), or its `:tenshu-sdk-ios-<app>` is affected. That
is added to the Gradle selection, never instead of it. ⚠ That is the code graph only — which is why the
nightly run is `--full`, and the selection falls back to it whenever the change touches the build,
this lane's own scripts, or a file it cannot place.
"""
import json
import math
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "lib"))
import affected_modules  # noqa: E402

# ⚠⚠ Together exactly `ios-smoke.sh --build-only`'s default list; the workflow checks it. Drive
# and Photos (the slowest shells) sit in different halves.
HALVES = ["control drive fit timeline maps", "photos contacts home mia calendar"]
APPS = [a for half in HALVES for a in half.split()]
# This lane's own machinery: a change here can change what any job does.
FULL_IF = [".github/workflows/ios.yml", "scripts/ci/", "scripts/ios-smoke.sh", "scripts/ios-build-sdk.sh",
           "scripts/lib/affected_modules.py", "scripts/test_ios_select.py"]
# One shard per this many modules, up to the full run's three: each shard pays the shared
# compile again, so a small change is cheaper on one runner than spread over three.
MODULES_PER_SHARD = 16
MAX_SHARDS = 3


def swift_inputs():
    """(repo path, app) for everything an app's Xcode build reads outside Gradle: its own
    `<app>-ios/`, and every `../…` path its `project.yml` names — and, through each included
    fragment (`ios-shell/shell.yml`, …), every path that fragment names, resolved against the
    fragment's own folder as XcodeGen resolves it. A path may be a file or a folder."""
    def refs(spec, base):
        try:
            with open(spec) as f:
                lines = [line.split("#", 1)[0] for line in f]
        except FileNotFoundError:
            return []
        found = re.findall(r"\.\./[^\s\"'`]+", "".join(lines))
        # A build-setting path (`$(TENSHU_SDK_MODULE)/build/…`) is Gradle output, not a source.
        return [os.path.normpath(os.path.join(base, r)) for r in found if "$" not in r]

    inputs = set()
    for app in APPS:
        inputs.add((f"{app}-ios", app))
        for path in refs(f"{app}-ios/project.yml", f"{app}-ios"):
            inputs.add((path, app))
            if path.endswith(".yml"):
                # A fragment names its own folder's files bare (`path: Sources`): all of it counts.
                fragment_dir = os.path.dirname(path)
                inputs.add((fragment_dir, app))
                inputs.update((p, app) for p in refs(path, fragment_dir))
    return inputs


def swift_only(top, module_dirs):
    """A top-level folder only Xcode reads: no Gradle build can be affected by it."""
    return (top.endswith("-ios") or top.startswith("ios-")) and top not in module_dirs


def select(graph, files, full_reason=None):
    with_tests = sorted(p["path"] for p in graph if "iosSimulatorArm64Test" in p["tasks"])
    if not with_tests:
        # A graph configured without the iOS targets would otherwise select nothing, and read
        # as "this change touches no iOS code".
        sys.exit("ios-select: the project graph lists no iosSimulatorArm64Test task — refusing to select")

    if full_reason is None:
        lane = [f for f in files if any(f == r or (r.endswith("/") and f.startswith(r)) for r in FULL_IF)]
        if lane:
            full_reason = f"{lane[0]} changed — it is this lane's own machinery"

    if full_reason is None:
        inputs = swift_inputs()
        module_dirs = {p["dir"].split("/", 1)[0] for p in graph}
        # ⚠ Additive: a file Xcode reads is ALSO walked through the Gradle graph unless only Xcode
        # reads its folder — a script Xcode runs can still be a Gradle build input.
        swift_apps, rest = set(), []
        for f in files:
            apps_of = {app for path, app in inputs if f == path or f.startswith(path + "/")}
            swift_apps |= apps_of
            top = f.split("/", 1)[0]
            if "/" in f and swift_only(top, module_dirs):
                if not apps_of:
                    full_reason = f"{f} is in a Swift folder no app's build is known to read"
                    break
            else:
                rest.append(f)
        if full_reason is None:
            c = affected_modules.classify(graph, rest)
            if c["mode"] == "full":
                full_reason = c["reason"]

    if full_reason is not None:
        tests, apps, reason = with_tests, set(APPS), f"full: {full_reason}"
    else:
        affected = c["affected"]
        tests = [p for p in with_tests if p in affected]
        apps = swift_apps | {a for a in APPS if f":tenshu-sdk-ios-{a}" in affected}
        reason = (f"{len(tests)} of {len(with_tests)} iOS-test modules, {len(apps)} of {len(APPS)} apps "
                  f"({c['reason'] or 'no Gradle module changed'}"
                  f"{'; Swift: ' + ' '.join(sorted(swift_apps)) if swift_apps else ''})")

    count = min(MAX_SHARDS, math.ceil(len(tests) / MODULES_PER_SHARD))
    halves = [" ".join(a for a in half.split() if a in apps) for half in HALVES]
    return {
        "reason": reason,
        # ⚠ Empty on a full run: each Mac shard then takes every module ITS graph lists, so a
        # module the Linux graph missed is still tested (`ios-test-shard.py`: empty = all).
        "test_modules": "" if full_reason is not None else " ".join(tests),
        "shards": json.dumps(list(range(1, count + 1))),
        "shard_count": str(count),
        "app_halves": json.dumps([h for h in halves if h]),
    }


def main():
    if sys.argv[1:] == ["--halves"]:
        print("\n".join(HALVES))
        return
    graph = json.load(open(sys.argv[1]))
    if sys.argv[2:] == ["--full"]:
        out = select(graph, [], full_reason="asked for (nightly or manual run)")
    else:
        base, head = sys.argv[2], sys.argv[3]
        out = select(graph, affected_modules.changed_files(base, head))
    for key, value in out.items():
        print(f"{key}={value}")


if __name__ == "__main__":
    main()
