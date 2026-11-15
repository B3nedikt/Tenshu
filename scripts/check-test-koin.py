#!/usr/bin/env python3
"""A test that starts the global Koin stops it, and never stops someone else's.

`GlobalContext` is one process-wide slot shared by every test class in a test JVM, and Gradle runs
classes in filesystem order, which differs between macOS and Linux. Two shapes have turned that
into a failure in a class that did nothing wrong (`docs/testing-flakes.md`, category B):

1. **A leaker.** A class starts the global Koin — `startKoin { … }`, or the composable
   `KoinApplication(…)` — and never stops it. The next class to call `startKoin` throws
   `KoinApplicationAlreadyStartedException`: `ImportRowLayoutTest` → `TimelineEmptyStateRenderTest`,
   red on Linux only, because macOS happened to order the two the other way round.
2. **An absorber.** `stopKoin()` right before `startKoin` "to be safe". It hides shape 1: the
   leaker's graph is silently torn down by whichever class runs next, its still-running loops then
   fail *there* against a closed scope (the wandering `ClosedScopeException`), and nothing ever
   names the class that leaked. `startAppIntegrationKoin`'s KDoc (`:drive-shared-ui`) records
   removing one for exactly this reason.

So, in test sources:
- a file that starts the global Koin must also call `stopKoin()` (in an `@After`/`@AfterTest`, a
  `finally`, or a helper it pairs with — a helper file must offer the stop beside the start);
- a `stopKoin()` ahead of a `startKoin` in the same function is refused — except inside a `@Test`
  of a class whose own `@Before` started the Koin it is replacing.

    scripts/check-test-koin.py            # exit 1 and name each offender

`koinApplication { … }` (an isolated container, not `GlobalContext`) is not a start and is not
flagged — prefer it wherever the code under test does not resolve through `GlobalContext`.
"""
import os
import re
import subprocess
import sys

sys.dont_write_bytecode = True
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEST_DIR = re.compile(r"/src/(?:[A-Za-z]*Test|test)[A-Za-z]*/")
START = re.compile(r"(?<![\w.])(?:startKoin\s*[({]|KoinApplication\s*\()")
STOP = re.compile(r"(?<![\w.])stopKoin\s*\(\s*\)")
STRING = re.compile(r'"(?:[^"\\]|\\.)*"')


def code_lines(text):
    out, in_block = [], False
    for line in text.splitlines():
        code = STRING.sub('""', line)
        if in_block:
            if "*/" not in code:
                out.append("")
                continue
            code, in_block = code.split("*/", 1)[1], False
        if "/*" in code:
            head, tail = code.split("/*", 1)
            in_block = "*/" not in tail
            code = head + (tail.split("*/", 1)[1] if not in_block else "")
        out.append(code.split("//", 1)[0])
    return out


FUN = re.compile(r"\bfun\s+[`\w]")
BEFORE = re.compile(r"@(?:Before|BeforeTest|BeforeEach|BeforeClass)\b")
TEST = re.compile(r"@Test\b")


def enclosing_function(lines, i):
    """(line of the member function containing line [i], its annotations + signature)."""
    j = i
    # A member, not a nested `override fun` in an object expression: indented one level at most.
    while j >= 0 and not (FUN.search(lines[j]) and len(lines[j]) - len(lines[j].lstrip()) <= 4):
        j -= 1
    notes = []
    k = j - 1
    while k >= 0 and (lines[k].strip().startswith("@") or not lines[k].strip()):
        notes.append(lines[k])
        k -= 1
    return j, " ".join(notes) + " " + (lines[j] if j >= 0 else "")


def problems(name, text):
    lines = code_lines(text)
    starts = [i for i, l in enumerate(lines) if START.search(l) and "fun " not in l.split("startKoin")[0]]
    stops = [i for i, l in enumerate(lines) if STOP.search(l)]
    found = []
    absorbing = set()
    # A test that re-starts the Koin its OWN @Before started (to swap a module mid-class) is
    # stopping its own graph, not absorbing another class's: allowed inside a @Test when a
    # @Before of this file starts one.
    setup_starts = any(BEFORE.search(enclosing_function(lines, s)[1]) for s in starts)
    for t in stops:
        fn, notes = enclosing_function(lines, t)
        later_start = next((s for s in starts if s > t and enclosing_function(lines, s)[0] == fn), None)
        if later_start is None:
            continue
        if TEST.search(notes) and setup_starts:
            continue
        absorbing.add(t)
        found.append(f"{name}:{t + 1}: stopKoin() before a start (line {later_start + 1}) absorbs "
                     f"another class's leak — stop in this class's teardown instead")
    if starts and not [t for t in stops if t not in absorbing]:
        found.append(f"{name}:{starts[0] + 1}: starts the global Koin and never stops it — add "
                     f"stopKoin() to an @AfterTest (or a finally), or use koinApplication {{ }}")
    return found


def main():
    names = subprocess.run(["git", "ls-files", "-z"], cwd=REPO, capture_output=True,
                           check=True).stdout.decode().split("\0")
    found = []
    for name in names:
        if not name.endswith(".kt") or not TEST_DIR.search("/" + name):
            continue
        try:
            with open(os.path.join(REPO, name), encoding="utf-8") as f:
                text = f.read()
        except (OSError, UnicodeDecodeError):
            continue
        if "Koin" in text:
            found += problems(name, text)
    if found:
        print("✗ Global Koin isolation (docs/testing-flakes.md, category B):\n")
        print("\n".join("  " + p for p in found))
        return 1
    print("✓ every test that starts the global Koin stops it, and none stops another's")
    return 0


if __name__ == "__main__":
    sys.exit(main())
