#!/usr/bin/env python3
"""Three ways a code comment goes wrong without anything failing.

`check-doc-refs.py` guards references *between docs*. This is its counterpart for
references *from code*, and for two defects that only comments have. All three
turn up repeatedly in every module, which is why they are a script.

    scripts/check-comment-claims.py             # check the working tree
    scripts/check-comment-claims.py --list      # print every finding, not just counts
    scripts/check-comment-claims.py --update    # re-bank the baseline after fixing some

## The three checks

1. **Dangling doc citations.** A comment cites `some-doc.md` that does not
   exist — usually because the doc graduated into an ADR.

2. **Orphaned KDoc.** Two `/** … */` blocks in a row means the first documents
   *nothing*: Kotlin binds only the adjacent one. It happens when someone
   inserts a declaration between an existing doc and the thing it described, so
   the rationale silently re-attaches to the wrong function.

3. **Cited-but-absent symbols.** A comment names `SomeClass` in backticks and no
   such identifier exists anywhere in the tree — typically a class that was
   consolidated or renamed while comments still name it as live.

## ⚠⚠ This is a baseline gate, not a clean-tree gate

The tree has hundreds of pre-existing findings. A gate that fails with hundreds
of problems teaches everyone to skip it, so the known set is banked in
`comment-claims-baseline.json` and **only new findings fail**. Burn the baseline
down per module, then `--update` to bank the progress.

⚠ The baseline is self-pruning: an entry that no longer reproduces also fails,
with `--update` as the one-command fix. A suppression list nobody prunes is how
the next reader learns to ignore the whole gate, as `check-doc-refs.py` notes too.

⚠⚠ **Entries are keyed by identity, never by line number.** A `path:line` key
makes the gate actively hostile: fixing one finding shifts every line below it,
so untouched findings report as NEW regressions. A gate that punishes editing is
worse than no gate. So a dead symbol
keys on `path::SymbolName`, a citation on `path::doc-name.md`, and an orphaned
KDoc on `path::` plus a fingerprint of its first line of prose. Counts may move
within a file without failing; only a genuinely new (file, thing) pair does.

⚠⚠ **A comment-only diff is not automatically a safe diff.** A path containing
`/*` inside a KDoc — `/admin/export/*.csv` — opens a *nested* block comment, so
the file never closes and every reference after it fails to resolve. Compile
what you edit; the comment-only assertion proves scope, not correctness.

⚠ **Necessary, not sufficient.** Check 1 proves a path resolves, never that the
sentence around it is true. Check 3 proves a name exists, never that the comment
describes it correctly. Both are the cheap half of the sweep; the expensive half
is judgement and does not automate.
"""
import argparse
import collections
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(".")
BASELINE = pathlib.Path("scripts/comment-claims-baseline.json")
SUFFIXES = (".kt", ".kts", ".swift", ".aidl")
# ⚠ `.claude` holds agent worktrees — full checkouts of this repo. Walking them
# scans the tree N+1 times and inflates every count.
SKIP_DIRS = {".git", "build", ".gradle", "node_modules", ".idea", ".kotlin", ".claude"}

# --- check 1 -----------------------------------------------------------------
# ⚠⚠ Match the WHOLE token, then take its basename. A negative lookbehind that
# rejects anything preceded by `/` (to stop `memory/project_cursor_advancement_bug.md`
# matching as "bug.md") throws out every *path-qualified* citation, which is the
# dominant spelling here, and the gate then reports zero.
DOC_TOKEN = re.compile(r'(?<![\w.-])([A-Za-z0-9_./-]*\.md)\b')
# Repo docs are lowercase, hyphen-separated, no underscores — which is also what
# separates them from `project_cursor_advancement_bug.md`.
DOC_SHAPE = re.compile(r'^[a-z][a-z0-9]*(?:-[a-z0-9]+)*\.md$')

# --- check 3 -----------------------------------------------------------------
SYMBOL = re.compile(r'`([A-Z][A-Za-z0-9_]{3,})(?:\(\))?`')
IDENT = re.compile(r'\b([A-Za-z_][A-Za-z0-9_]*)\b')

# Symbols that are prose, platform API, or another project's vocabulary. Each
# carries its reason; the set is checked for rot like every other list here.
# Doc names a comment may legitimately cite even though the file is not here.
# Same discipline as check-doc-refs.py's ALLOW_NAMES: a reason each, and the set
# is checked for rot.
ALLOW_DOCS = {
    "fit-agent.md": "design handoff under the gitignored docs/design/fit/",
    "maps-agent.md": "design handoff under the gitignored docs/design/maps/",
    "timeline-agent.md": "design handoff under the gitignored docs/design/timeline/",
    "design-system-improvement-plan.md":
        "the filename ADR 0001 had when it was written, cited beside a live link "
        "to docs/design/audit.md — same entry exists in check-doc-refs.py",
}

ALLOW_SYMBOLS = {
    "K_link": "threat-model key name (contact invitation), not a Kotlin symbol",
    "K_share": "threat-model key name (Mode-A link sharing), not a Kotlin symbol",
    "K_entity": "threat-model key name (per-entity content key), not a Kotlin symbol",
    "SIGSEGV": "POSIX signal named in prose about a crash class",
    "StackOverflowError": "JDK throwable named in prose; never referenced in code here",
    "ForegroundServiceStartNotAllowedException": "Android platform throwable, named in prose",
    "LSApplicationQueriesSchemes": "an iOS Info.plist key, not a Kotlin symbol",
    "OPEN_DOCUMENT_TREE": "Android Intent action constant, referenced by string",
    "SaveableStateHolder": "Compose API named in prose about navigation state",
    "UncaughtExceptionsBeforeTest": "Gradle/JUnit concept named in a build script comment",
    "SCHEDULE_EXACT_ALARM": "Android manifest permission name, referenced as a string",
    "SecAccessControl": "iOS Security.framework type named in prose",
    "ActivityResult": "AndroidX Activity API named in prose",
    "OutOfMemoryError": "JDK throwable named in prose about the store OOM incident",
    "ClosedScopeException": "Koin throwable quoted in prose about an orphaned runtime",

    # --- :ui-core / :ui-map / :basemap-core / :vector-tile ------------------------------------
    # Compose / Material 3 API and token names, cited in prose about why a primitive is shaped
    # the way it is. None is declared here — they are androidx's, and the comments say so.
    "CompositionLocal": "Compose runtime concept named in prose (units, nav-rail preference)",
    "MutableState": "Compose runtime type named in prose about the entryProvider capture bug",
    "Popup": "Compose UI type named in prose about the second semantics root in a render test",
    "DefaultBasicAlertDialogOverride": "Material 3 internal named in AppDialog's history note",
    "AppBarTokens": "Material 3 token object named in prose about the scroll container colour",
    "PermanentDrawerSheet": "Material 3 API named in prose about the drawer the rail replaced",
    "NavigationDrawerItem": "Material 3 API named in prose about the drawer the rail replaced",
    "NavigationRailHorizontalItemTokens": "Material 3 token object behind the 36 dp icon column",
    "ItemHorizontalPadding": "Material 3 rail token (20 dp) named in the icon-column arithmetic",
    "FullWidthLeadingSpace": "Material 3 rail token (16 dp) named in the icon-column arithmetic",
    "WideNavigationRailResponsiveSample": "the AOSP sample NavRailToggle is modelled on",

    # Platform APIs on targets whose source this repo does not hold in Kotlin.
    "UINavigationController": "UIKit class named in prose about the swipe-back gap on iOS",
    "MLNStyle": "MapLibre's native iOS style class — the `styleJSON` consumer of the style pair",
    "MKMapRect": "MapKit type named in prose about how the iOS hero frames a route",
    # ⚠ Allowlisted only because all three citations name this route as DEAD. Allowlisting a
    # name drops its baseline entries, which may be the only pointer to stale prose: a
    # name-keyed exception cannot tell a correct mention from a stale one, so check the sites
    # before adding one.
    "ProxyFileDescriptor": "Android StorageManager proxy-fd route, named as dead at all three sites",
    "DecompressionStream": "the Web Compression Streams API, named as why `decompress` is injected",
    "TENSHU_CONTROL_APP_STORE_ID": "an Xcode build setting (see the *-ios project.yml files)",

    # Format and domain vocabulary.
    "MoveTo": "MVT geometry command (spec \u00a74.3), not a Kotlin symbol",
    "LineTo": "MVT geometry command (spec \u00a74.3), not a Kotlin symbol",
    "ClosePath": "MVT geometry command (spec \u00a74.3), not a Kotlin symbol",
    "WESTbahn": "an Austrian train operator, quoted from a real de-bayern overlay build",
}


URL = re.compile(r'https?://\S+')


def tracked_md_paths() -> set[str]:
    """Every .md path the repo has, forward-slashed and repo-relative.

    ⚠ Paths, not just names. Reducing a citation to its basename made
    `docs/missing/agent.md` valid because some *other* `agent.md` exists, so a
    directory move or a typo stayed invisible. A qualified citation is now
    checked as a path suffix; only a bare name falls back to the basename set.
    """
    out = set()
    for p in ROOT.rglob("*.md"):
        if any(part in SKIP_DIRS for part in p.parts):
            continue
        out.add(p.as_posix().lstrip("./"))
    try:
        got = subprocess.run(["git", "ls-files", "--others", "--ignored",
                              "--exclude-standard", "*.md"],
                             capture_output=True, text=True, timeout=30).stdout
        out.update(l.strip() for l in got.split("\n") if l.strip())
    except Exception:
        pass
    return out


def tracked_md_names() -> set[str]:
    """Every .md the repo has, including gitignored design handoffs."""
    names = set()
    for p in ROOT.rglob("*.md"):
        if any(part in SKIP_DIRS for part in p.parts):
            continue
        names.add(p.name)
    # Gitignored trees (docs/design/{fit,timeline,maps}/) are real targets that
    # simply are not committed; rglob already sees them on a working checkout,
    # but on a clean clone they are absent, so ask git as well.
    try:
        out = subprocess.run(["git", "ls-files", "--others", "--ignored",
                              "--exclude-standard", "*.md"],
                             capture_output=True, text=True, timeout=30).stdout
        names.update(pathlib.PurePath(l).name for l in out.split("\n") if l.strip())
    except Exception:
        pass
    return names


def source_files() -> list[pathlib.Path]:
    out = []
    for p in ROOT.rglob("*"):
        if p.suffix in SUFFIXES and not any(part in SKIP_DIRS for part in p.parts):
            out.append(p)
    return sorted(out)


def split_line(line: str) -> tuple[str, str, bool]:
    """Split one line into (code, comment, opens_block) outside string literals.

    ⚠ Handles BOTH trailing forms. The first version typed a whole line by what
    it started with, so `val m = x // see retired-doc.md` was pure code; the
    second handled `//` only, so `val x = 1 /* see doc.md */` still was. Both
    hid citations *and* fed their words to `code_idents`, which can make a
    genuinely dead symbol look live.

    Quote tracking is deliberately simple — enough to keep `"http://x"` and
    `"/* not a comment */"` out of the comment half, which is what matters here.
    """
    q = None
    i = 0
    while i < len(line):
        c = line[i]
        if q:
            if c == "\\":
                i += 2
                continue
            if c == q:
                q = None
        elif c in "\"'":
            q = c
        elif c == "/" and line.startswith("//", i):
            return line[:i], line[i:], False
        elif c == "/" and line.startswith("/*", i):
            rest = line[i:]
            # A block that closes on the same line leaves the tail as code.
            end = rest.find("*/", 2)
            if end != -1:
                return line[:i] + rest[end + 2:], rest[:end + 2], False
            return line[:i], rest, True
        i += 1
    return line, "", False


def split_comments(lines: list[str]):
    """Yield (index, comment_text, code_text) for every line.

    Either half may be empty. Block state is tracked across lines, including a
    block opened partway through an otherwise-code line.
    """
    inblk = False
    for i, l in enumerate(lines):
        if inblk:
            yield i, l, ""
            if "*/" in l:
                inblk = False
            continue
        if l.strip().startswith("*"):
            yield i, l, ""
            continue
        code, comment, opens = split_line(l)
        yield i, comment, code
        inblk = opens


def cited_target_exists(token: str, md_names: set[str], md_paths: set[str]) -> bool:
    """A bare name matches by basename; a qualified one must match a real path.

    ⚠ Basename-only matching made `docs/missing/agent.md` valid because some
    other `agent.md` exists, so a directory move or typo stayed invisible.
    """
    if "/" not in token:
        return token in md_names
    t = token.lstrip("./")
    return any(full == t or full.endswith("/" + t) for full in md_paths)


def scan(files, md_names, md_paths):
    docs, orphans, symbols = [], [], []
    # ⚠ Which allowlist entries actually suppressed something. Without this the
    # rot check is dead code: scan() removes every allowed name before returning,
    # so a set derived from the findings can never contain one. That let unused
    # entries sit forever while the gate printed OK — caught in review.
    suppressed: set[str] = set()
    code_idents = collections.Counter()
    cited = collections.defaultdict(list)

    # ⚠ A comment often cites a FILE by its basename — `ProxyModule`, whose file
    # declares `buildProxyModule()` and no identifier of that name. Those are live
    # references, so basenames count as existing. Note this deliberately does NOT
    # rescue `SharedFileRelationMaintainer` just because
    # `SharedFileRelationMaintainerTest.kt` exists: the test is named after a
    # concept the class no longer has, which is the finding, not a false positive.
    for f in files:
        code_idents[f.stem] += 1

    for p in files:
        try:
            lines = p.read_text(encoding="utf-8").split("\n")
        except (OSError, UnicodeDecodeError):
            continue

        # ⚠ Doc names wrap across comment lines at a hyphen — `contact-invitation-\n
        # * handshake.md` is one name, not a citation of "handshake.md". Nine of the
        # first run's 65 findings were exactly that, so the citation scan runs over a
        # de-hyphenated join of each comment run rather than line by line.
        run, run_start = [], None
        def flush_run():
            if not run:
                return
            text = ""
            for frag in run:
                if text.endswith("-"):
                    # keep the hyphen: it belongs to the wrapped name
                    text = text + frag.lstrip()
                else:
                    text += " " + frag
            # ⚠ Strip URLs from the text rather than skipping any run that holds
            # one. The old `if "http" not in text` silenced doc scanning for the
            # whole adjacent comment, so a KDoc citing both a local doc and an
            # external link checked neither.
            for token in DOC_TOKEN.findall(URL.sub(" ", text)):
                # `// --- docs/foo.md` hands back `---docs/foo.md`; the rule is
                # decoration, not part of the path.
                token = token.lstrip("-")
                name = token.rsplit("/", 1)[-1]
                if not DOC_SHAPE.match(name):
                    continue
                if name in ALLOW_DOCS:
                    suppressed.add(name)
                    continue
                if not cited_target_exists(token, md_names, md_paths):
                    docs.append(f"{p}::{token if '/' in token else name}")

        for i, comment, code in split_comments(lines):
            if comment:
                if run_start is None:
                    run_start = i
                run.append(re.sub(r'^\s*(?:/\*\*?|\*/|\*|/{2,})\s?', "", comment).rstrip())
                for sym in SYMBOL.findall(comment):
                    cited[sym].append(f"{p}:{i + 1}")
            else:
                flush_run()
                run, run_start = [], None
            if code:
                for tok in IDENT.findall(code):
                    code_idents[tok] += 1
        flush_run()

        # check 2 — a KDoc block whose next non-blank line opens another one.
        i = 0
        while i < len(lines):
            if lines[i].strip().startswith("/**"):
                j = i
                while j < len(lines) and "*/" not in lines[j]:
                    j += 1
                if j >= len(lines):
                    break
                k = j + 1
                while k < len(lines) and not lines[k].strip():
                    k += 1
                if k < len(lines) and lines[k].strip().startswith("/**") and i > 3:
                    # ⚠ Not a defect inside an argument list: `service(id = ID, /** v2 … */ …)`
                    # stacks version-history blocks on purpose, and they document
                    # nothing by design rather than by accident.
                    b = i - 1
                    while b >= 0 and not lines[b].strip():
                        b -= 1
                    prev = lines[b].strip() if b >= 0 else ""
                    if not prev.endswith(",") and not prev.endswith("("):
                        # Fingerprint the block's first line of prose, so the
                        # entry survives the block moving up or down the file.
                        gist = ""
                        for probe in range(i, j + 1):
                            t = re.sub(r'^\s*(?:/\*\*?|\*/|\*)\s?', "", lines[probe]).strip()
                            if t:
                                gist = t[:60]
                                break
                        orphans.append(f"{p}::{gist}")
                i = j + 1
            else:
                i += 1

    for sym, sites in cited.items():
        if sym in ALLOW_SYMBOLS:
            suppressed.add(sym)
            continue
        if code_idents[sym]:
            continue
        # One entry per (file, symbol): three citations of a dead name in one
        # file are one thing to fix, and collapsing them keeps the key stable
        # when two of the three are fixed.
        for f in sorted({s.rsplit(":", 1)[0] for s in sites}):
            symbols.append(f"{f}::{sym}")

    return sorted(set(docs)), sorted(set(orphans)), sorted(set(symbols)), suppressed


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--list", action="store_true", help="print every finding")
    ap.add_argument("--update", action="store_true", help="re-bank the baseline")
    args = ap.parse_args()

    docs, orphans, symbols, suppressed = scan(
        source_files(), tracked_md_names(), tracked_md_paths())
    found = {"doc_citations": docs, "orphaned_kdoc": orphans, "dead_symbols": symbols}

    print(f"dangling doc citations : {len(docs)}")
    print(f"orphaned KDoc blocks   : {len(orphans)}")
    print(f"cited-but-absent symbols: {len(symbols)}")

    if args.list:
        for key, items in found.items():
            print(f"\n--- {key} ({len(items)})")
            for it in items:
                print(f"  {it}")

    if args.update:
        BASELINE.write_text(json.dumps(found, indent=2, sort_keys=True) + "\n",
                            encoding="utf-8")
        print(f"\nbaseline re-banked: {sum(len(v) for v in found.values())} known findings.")
        return 0

    if not BASELINE.exists():
        print(f"\nNo baseline at {BASELINE}. Run with --update to create one.")
        return 1

    base = json.loads(BASELINE.read_text(encoding="utf-8"))
    failures = 0
    for key, items in found.items():
        known = set(base.get(key, []))
        new = [i for i in items if i not in known]
        fixed = [i for i in known if i not in set(items)]
        if new:
            print(f"\nNEW {key} ({len(new)}) — these are regressions:")
            for i in new[:40]:
                print(f"  {i}")
            if len(new) > 40:
                print(f"  … and {len(new) - 40} more")
            failures += len(new)
        if fixed:
            print(f"\n{key}: {len(fixed)} baseline entries no longer reproduce — "
                  f"bank the progress with --update.")
            failures += 1

    # The allowlist is checked for rot, like check-doc-refs.py's.
    spent = [s for s in ALLOW_SYMBOLS if s not in suppressed]
    spent += [d for d in ALLOW_DOCS if d not in suppressed]
    if spent:
        print("\nALLOWLIST ROT — these entries suppressed nothing, so they are "
              "no longer doing anything. Delete them:")
        for s in spent:
            print(f"  {s!r}")
        failures += len(spent)

    print()
    if failures:
        print(f"FAIL — {failures} problem(s).")
        print("⚠ Presence only. A resolving path and an existing symbol still say "
              "nothing about whether the sentence around them is true.")
        return 1
    print("OK — no new dangling citations, orphaned KDoc or absent symbols.")
    print(f"⚠ {sum(len(v) for v in base.values())} known findings remain in the "
          f"baseline; burn them down per module.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
