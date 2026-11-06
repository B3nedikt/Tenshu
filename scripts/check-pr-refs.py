#!/usr/bin/env python3
"""Code and docs cite code, tests and doc sections, not PR numbers.

A bare `#NNNN` links to PR NNNN of whichever repository the text sits in. This tree is exported
to a public repository whose numbering restarted at 1 (`docs/proposals/public-export.md`), so
there it would point at an unrelated PR, and to anyone without access here it says nothing.
Name what the PR left behind instead: the test that pins the bug, the function, the doc section.

    scripts/check-pr-refs.py               # every tracked file against the baseline
    scripts/check-pr-refs.py --staged      # staged files only (the pre-commit hook)
    scripts/check-pr-refs.py --list        # per-file counts, heaviest first
    scripts/check-pr-refs.py --update      # bank progress: lower the baseline to today's counts

⚠ **A baseline gate.** Only a **rise** fails: a file may not gain references, and a file not in
`pr-refs-baseline.json` may have none. The baseline holds only intentional non-references (tile
ids, the export's own test fixtures). `--update` only ever lowers an entry; raising one is a
deliberate edit of the JSON, visible in review.

What counts as a reference is the export's own rule (`public-export.py`'s `BARE_REF`), so this
check and the export never disagree: not `'#555'`, not `color:#666`, not `#111`-style repeats, not
`file.md#123`, and nothing below 100.
"""
import importlib.util
import json
import os
import re
import subprocess
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
BASELINE = os.path.join(HERE, "pr-refs-baseline.json")

spec = importlib.util.spec_from_file_location("public_export", os.path.join(HERE, "public-export.py"))
pe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pe)

# The same file types the export leaves alone, plus third-party text nobody here writes.
SKIP = ["*.css", "*.html", "*.svg", "*.json", "*.pbxproj", "*.c", "*.h",
        "core-common/src/nativeInterop/*", "config/oss-licences/*", "scripts/pr-refs-baseline.json"]
OLD_LINK = re.compile(r"github\.com/B3nedikt/(?:Drive|Tenshu)/(?:pull|issues)/\d+")


def count(text):
    n = len(OLD_LINK.findall(text))
    for m in pe.BARE_REF.finditer(text):
        if int(m.group(1)) >= 100 and not pe.REPDIGIT.fullmatch(m.group(1)):
            n += 1
    return n


def git(*args):
    return subprocess.run(["git", *args], cwd=REPO, capture_output=True, check=True).stdout


def staged_names():
    """Staged added/modified paths, and {new: old} for renames, so a `git mv` keeps its baseline."""
    fields = git("diff", "--cached", "--name-status", "-M", "--diff-filter=ACMR", "-z").decode().split("\0")
    names, renamed, i = [], {}, 0
    while i < len(fields) and fields[i]:
        status = fields[i]
        if status[0] in "RC":
            old, new = fields[i + 1], fields[i + 2]
            names.append(new)
            if status[0] == "R":
                renamed[new] = old
            i += 3
        else:
            names.append(fields[i + 1])
            i += 2
    return names, renamed


def counts(staged):
    if staged:
        names, _ = staged_names()
    else:
        names = git("ls-files", "-z").decode().split("\0")
    out = {}
    for name in filter(None, names):
        if pe.path_matches(name, SKIP):
            continue
        try:
            data = git("show", f":{name}") if staged else open(os.path.join(REPO, name), "rb").read()
        except (OSError, subprocess.CalledProcessError):
            continue
        if b"\0" in data[:8192]:
            continue
        try:
            n = count(data.decode("utf-8"))
        except UnicodeDecodeError:
            continue
        if n:
            out[name] = n
        elif staged:
            out[name] = 0
    return out


def main(argv):
    staged = "--staged" in argv
    baseline = {}
    if os.path.exists(BASELINE):
        with open(BASELINE) as f:
            baseline = json.load(f)
    now = counts(staged)

    if "--list" in argv:
        for name, n in sorted(now.items(), key=lambda kv: -kv[1]):
            if n:
                print(f"{n:5d} {name}")
        print(f"{sum(now.values())} references in {sum(1 for n in now.values() if n)} files")
        return 0

    if "--update" in argv:
        if staged:
            sys.exit("--update reads the whole tree; drop --staged")
        lowered = {k: min(v, now.get(k, 0)) for k, v in baseline.items()}
        lowered = {k: v for k, v in sorted(lowered.items()) if v}
        with open(BASELINE, "w") as f:
            json.dump(lowered, f, indent=0, sort_keys=True)
            f.write("\n")
        print(f"Baseline: {sum(baseline.values())} → {sum(lowered.values())} references "
              f"in {len(lowered)} files.")
        return 0

    if staged:
        for new, old in staged_names()[1].items():
            baseline[new] = baseline.get(new, 0) + baseline.get(old, 0)
    rises = [(k, baseline.get(k, 0), n) for k, n in sorted(now.items()) if n > baseline.get(k, 0)]
    if rises:
        print("New PR references (cite the test, file or doc section instead; "
              "scripts/check-pr-refs.py explains why):", file=sys.stderr)
        for name, was, n in rises:
            print(f"  {name}: {was} → {n}", file=sys.stderr)
        return 1
    if not staged:
        banked = sum(1 for k, v in baseline.items() if now.get(k, 0) < v)
        print(f"OK: {sum(now.values())} references, none new."
              + (f" {banked} file(s) improved; --update banks it." if banked else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
