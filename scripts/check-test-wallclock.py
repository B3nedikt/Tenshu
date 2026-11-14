#!/usr/bin/env python3
"""Tests may not judge code by the wall clock — a ratchet on real-time bounds and sleeps in test sources.

A test that asserts `elapsed < 1_500`, or that sleeps 100 ms and assumes another thread got somewhere
in that time, is measuring the machine as well as the code. It passes on an idle Mac and fails on a
2-core Linux runner or a loaded gate, and its failure says nothing about the change under test
(`docs/testing-flakes.md`, category C). The remedies, in order: virtual time (`runTest`), a
happens-before taken from the code (a latch, a gate the producer must pass), or a deterministic
measure of the thing the bound stood for (a count of reads, a growth ratio over operations).

    scripts/check-test-wallclock.py               # every tracked test source against the baseline
    scripts/check-test-wallclock.py --list        # per-file counts, heaviest first
    scripts/check-test-wallclock.py --show FILE   # the flagged lines of one file
    scripts/check-test-wallclock.py --update      # bank progress: lower the baseline to today's counts

⚠ **A baseline gate.** Only a **rise** fails: a file may not gain flagged lines, and a file not in
`test-wallclock-baseline.json` may have none. `--update` only ever lowers an entry.

⚠ **The way out is a reason, not a bigger number.** Where the real clock *is* the subject (a watchdog
that must fire, an expiry that must cut a drain short — `docs/testing.md` §10's exception), put
`wall-clock:` and the reason in a comment on the flagged line or the line above it. The reason is
what review reads: "an upper bound far from the alternative it rules out" is the honest form, and
it must name that alternative.

What is flagged, in test source sets (`src/*Test*/`, `src/test/`, `src/iosSqliteTest/` …):
- an assertion comparing a real-time measure — `elapsed`, `tookMs`, `elapsedNow()`,
  `inWholeMilliseconds`, `measureTime*`, `nanoTime()`, `currentTimeMillis()` — with `<`, `<=`,
  `>` or `>=`;
- `Duration.parse(` — the spelling every wall-clock budget in this repo has used;
- `Thread.sleep(` and `usleep(` — an ordering assumed from elapsed time; and
  `withContext(Dispatchers.Default) { delay(…) }`, the same sleep spelled to escape `runTest`'s
  virtual clock (`StreamSubscribeRetryTest` counts retries in a real 7.5 s window that way).
A `runTest(timeout = …)` is NOT flagged: it is a hang guard, not a bound on the code.
"""
import json
import os
import re
import subprocess
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
BASELINE = os.path.join(HERE, "test-wallclock-baseline.json")

TEST_DIR = re.compile(r"/src/(?:[A-Za-z]*Test|test)[A-Za-z]*/")
EXTENSIONS = (".kt", ".swift")

REAL_TIME = re.compile(
    r"\b(?:elapsed\w*|took\w*|\w*[Ee]lapsedNow\(\)|inWhole(?:Milli|Nano|Micro)?[Ss]econds"
    r"|measureTime\w*|measureNanoTime|nanoTime\(\)|currentTimeMillis\(\))"
)
COMPARISON = re.compile(r"(?<![<>=!-])(?:<=?|>=?)(?![<>=])")
ASSERTION = re.compile(r"\b(?:assert\w*|check|require|XCTAssert\w*)\s*\(")
DURATION_PARSE = re.compile(r"\bDuration\.parse\(")
SLEEP = re.compile(r"\b(?:Thread\.sleep|usleep)\(|Dispatchers\.(?:Default|IO)\)?\s*\{\s*delay\(")
RUN_TEST_TIMEOUT = re.compile(r"runTest\s*\(\s*timeout\s*=")
EXEMPT = "wall-clock:"
STRING = re.compile(r'"(?:[^"\\]|\\.)*"')


def flagged_lines(text):
    """(line number, kind, line) for each flagged line of one file."""
    out = []
    lines = text.splitlines()
    for i, line in enumerate(lines):
        # Judge code, not the words in a failure message or a comment.
        code = STRING.sub('""', line).split("//", 1)[0]
        if not code.strip():
            continue
        if EXEMPT in line or (i > 0 and EXEMPT in lines[i - 1]):
            continue
        if SLEEP.search(code):
            out.append((i + 1, "sleep", line.strip()))
        elif DURATION_PARSE.search(code) and not RUN_TEST_TIMEOUT.search(code):
            out.append((i + 1, "duration", line.strip()))
        elif ASSERTION.search(code) and REAL_TIME.search(code) and COMPARISON.search(code):
            out.append((i + 1, "bound", line.strip()))
    return out


def test_sources():
    names = subprocess.run(
        ["git", "ls-files", "-z"], cwd=REPO, capture_output=True, check=True
    ).stdout.decode().split("\0")
    return [n for n in names if n.endswith(EXTENSIONS) and TEST_DIR.search("/" + n)]


def counts():
    result = {}
    for name in test_sources():
        try:
            with open(os.path.join(REPO, name), encoding="utf-8") as f:
                n = len(flagged_lines(f.read()))
        except (OSError, UnicodeDecodeError):
            continue
        if n:
            result[name] = n
    return result


def load_baseline():
    if not os.path.exists(BASELINE):
        return {}
    with open(BASELINE) as f:
        return json.load(f)


def write_baseline(data):
    with open(BASELINE, "w") as f:
        json.dump(dict(sorted(data.items())), f, indent=2)
        f.write("\n")


def main(argv):
    now = counts()
    if "--show" in argv:
        name = argv[argv.index("--show") + 1]
        with open(os.path.join(REPO, name), encoding="utf-8") as f:
            for n, kind, line in flagged_lines(f.read()):
                print(f"{name}:{n}: [{kind}] {line}")
        return 0
    if "--list" in argv:
        for name, n in sorted(now.items(), key=lambda kv: -kv[1]):
            print(f"{n:4d}  {name}")
        print(f"{sum(now.values())} flagged lines in {len(now)} files")
        return 0
    base = load_baseline()
    if "--init" in argv:
        write_baseline(now)
        print(f"baseline written: {sum(now.values())} lines in {len(now)} files")
        return 0
    if "--update" in argv:
        lowered = {k: min(v, now.get(k, 0)) for k, v in base.items()}
        write_baseline({k: v for k, v in lowered.items() if v})
        print(f"baseline: {sum(base.values())} → {sum(lowered.values())} lines")
        return 0
    risen = [(k, base.get(k, 0), v) for k, v in now.items() if v > base.get(k, 0)]
    if risen:
        print("✗ A test gained a wall-clock bound or a real-time sleep (docs/testing-flakes.md, category C):\n")
        for name, was, n in sorted(risen):
            print(f"  {name}: {was} → {n}")
            with open(os.path.join(REPO, name), encoding="utf-8") as f:
                for ln, kind, line in flagged_lines(f.read()):
                    print(f"      {ln}: [{kind}] {line[:140]}")
        print(
            "\nUse virtual time, a happens-before from the code, or a deterministic measure instead.\n"
            "If the real clock IS the subject, say why with a `wall-clock: <reason>` comment on the line\n"
            "or the line above it."
        )
        return 1
    lower = sum(base.values()) - sum(now.values())
    print(f"✓ no test gained a wall-clock bound ({sum(now.values())} grandfathered"
          + (f"; {lower} fewer than the baseline — run --update to bank it" if lower > 0 else "") + ")")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
