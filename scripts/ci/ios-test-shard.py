#!/usr/bin/env python3
"""The Gradle tasks one iOS CI shard runs.

    ios-test-shard.py GRAPH_JSON SHARD COUNT (link|test)

GRAPH_JSON is the root build's `-Ptenshu.projectGraph` output. Every project with an
`iosSimulatorArm64Test` task goes to exactly one shard, round-robin over the sorted paths, so
together the shards are every iOS test task, and no shard needs to know what the others run.

`IOS_TEST_MODULES` (space-separated, from `ios-select.py`) narrows that to the modules a change
can break; unset or empty is every module.
"""
import os
import json
import sys

graph_path, shard, count, kind = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
task = {"link": "linkDebugTestIosSimulatorArm64", "test": "iosSimulatorArm64Test"}[kind]

graph = json.load(open(graph_path))
paths = sorted(p["path"] for p in graph if "iosSimulatorArm64Test" in p["tasks"])
selected = set(os.environ.get("IOS_TEST_MODULES", "").split())
if selected:
    unknown = selected - set(paths)
    if unknown:
        sys.exit(f"ios-test-shard: selected modules with no iOS tests here: {' '.join(sorted(unknown))}")
    paths = [p for p in paths if p in selected]
mine = [path for i, path in enumerate(paths) if i % count == shard]

print(" ".join(f"{path}:{task}" for path in mine))
print(f"shard {shard + 1}/{count}: {len(mine)} of {len(paths)} modules", file=sys.stderr)
