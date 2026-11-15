#!/usr/bin/env python3
"""What `scripts/run-gate.sh --quick` has to run for the change on this branch.

    affected_modules.py GRAPH_JSON BASE_REF [HEAD_REF]

GRAPH_JSON is the root build's `-Ptenshu.projectGraph` output. The change is everything between
the merge-base with BASE_REF and the working tree, untracked files included. Prints shell
assignments for run-gate.sh to `eval`:

    QUICK_MODE=full|none|quick    QUICK_REASON=…    QUICK_TEST_TASKS=…    QUICK_JS_TEST_TASKS=…
    QUICK_JS_LANE=…    QUICK_IOS_LANE=…    QUICK_MODULES=…    QUICK_NOTES=…

A module is affected when a file under its directory changed, or when it depends — through any
configuration, tests included — on an affected module. ⚠ That is the code graph only: a test that
reads another module's files at runtime is not followed, which is why a shared-code change still
owes the full gate before it merges (the script says so).
"""
import json
import shlex
import subprocess
import sys

# A change here can change what any module builds or how the gate judges it: run everything.
FULL_IF = [
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/", "buildSrc/",
    "build-logic/", "scripts/run-gate.sh", "scripts/lib/gate-lock.sh",
    "scripts/lib/affected_modules.py", "scripts/js-test-exclusions.txt", "config/",
]
# Outside every Gradle module and read by no Gradle test: nothing for this gate to run.
# ⚠ `scripts/` is NOT here as a whole: some scripts are build inputs (the native-library builds
# behind core-common's iOS cinterops). A script counts as test-free only if no build file names it.
NOT_GRADLE = [
    "docs/", ".github/", ".claude/", ".githooks/", "store/", "deploy/", "web/",
]
# Modules whose change reaches nearly everything; the quick run still covers their dependents,
# but such a change owes the full gate before it merges.
SHARED = {":core", ":core-common", ":core-client", ":core-proxy", ":core-test-utils", ":ui-core"}


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def changed_files(base, head=None):
    """The working tree against the merge-base — or, with [head], just that commit range."""
    merge_base = git("merge-base", base, head or "HEAD").strip()
    if head:
        return sorted(set(git("diff", "--name-only", "--no-renames", merge_base, head).split()))
    # ⚠ `--no-renames`: a rename shows only its destination otherwise, and the module a file moved
    # OUT of — with every module depending on it — would go untested.
    files = set(git("diff", "--name-only", "--no-renames", merge_base).split())  # committed + staged + unstaged
    files |= set(git("ls-files", "--others", "--exclude-standard").split())
    return sorted(files)


def named_by_a_build_file(path):
    """Whether any Gradle build file mentions this file by name — then it is a build input."""
    name = path.rsplit("/", 1)[-1]
    found = subprocess.run(["git", "grep", "-lF", name, "--", "*.gradle.kts", "gradle/"],
                           capture_output=True, text=True).stdout
    return bool(found.strip())


def test_free(path):
    if any(path.startswith(r) for r in NOT_GRADLE) or ("/" not in path and path.endswith(".md")):
        return True
    return path.startswith("scripts/") and not named_by_a_build_file(path)


def main():
    graph = json.load(open(sys.argv[1]))
    files = changed_files(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
    by_path = {p["path"]: p for p in graph}
    # Longest directory first, so a nested module wins over its parent.
    dirs = sorted(((p["dir"].rstrip("/") + "/", p["path"]) for p in graph), key=lambda d: -len(d[0]))
    # A source directory a project compiles from another's tree (`kotlin.srcDir("../x/…")`): a
    # change there is ALSO a change to the project compiling it.
    borrowed = [(d.rstrip("/") + "/", p["path"]) for p in graph for d in p.get("srcDirs", [])]

    out = {"QUICK_MODE": "quick", "QUICK_REASON": "", "QUICK_NOTES": ""}
    full = [f for f in files if any(f == r or (r.endswith("/") and f.startswith(r)) for r in FULL_IF)]
    if full:
        out["QUICK_MODE"] = "full"
        out["QUICK_REASON"] = f"{full[0]} changed — it can affect every module"
        return emit(out)

    touched, outside = set(), []
    for f in files:
        owner = next((path for d, path in dirs if f.startswith(d)), None)
        touched.update(path for d, path in borrowed if f.startswith(d))
        if owner:
            touched.add(owner)
        elif not test_free(f):
            outside.append(f)
    if outside:
        out["QUICK_MODE"] = "full"
        out["QUICK_REASON"] = f"{outside[0]} is in no module and not known to be test-free"
        return emit(out)

    notes = []
    if any(f.startswith("web/") for f in files):
        notes.append("web/ changed: this gate runs no web tests — run that app's `pnpm test`.")
    if not touched:
        out["QUICK_MODE"] = "none"
        out["QUICK_REASON"] = "no Gradle module changed"
        out["QUICK_NOTES"] = " ".join(notes)
        return emit(out)

    dependents = {}
    for p in graph:
        for d in p["deps"]:
            dependents.setdefault(d, set()).add(p["path"])
    affected, queue = set(touched), list(touched)
    while queue:
        for nxt in dependents.get(queue.pop(), ()):
            if nxt not in affected:
                affected.add(nxt)
                queue.append(nxt)

    if touched & SHARED:
        notes.append(f"touches shared code ({', '.join(sorted(touched & SHARED))}): "
                     "run the full gate once before it merges.")

    def tasks(*names):
        return " ".join(f"{p}:{t}" for p in sorted(affected) for t in names if t in by_path[p]["tasks"])

    out.update(
        QUICK_MODULES=" ".join(sorted(affected)),
        QUICK_TEST_TASKS=tasks("desktopTest", "jvmTest", "test", "testAndroidHostTest"),
        QUICK_JS_TEST_TASKS=tasks("jsTest"),
        QUICK_JS_LANE=tasks("compileTestKotlinJs"),
        QUICK_IOS_LANE=tasks("compileTestKotlinIosSimulatorArm64"),
        QUICK_REASON=f"{len(touched)} changed module(s), {len(affected)} with dependents",
        QUICK_NOTES=" ".join(notes),
    )
    return emit(out)


def emit(values):
    for key in ["QUICK_MODE", "QUICK_REASON", "QUICK_MODULES", "QUICK_TEST_TASKS",
                "QUICK_JS_TEST_TASKS", "QUICK_JS_LANE", "QUICK_IOS_LANE", "QUICK_NOTES"]:
        print(f"{key}={shlex.quote(values.get(key, ''))}")


if __name__ == "__main__":
    main()
