#!/usr/bin/env python3
"""Keep AGENTS.md a routing surface instead of a changelog.

AGENTS.md is loaded into *every* agent session before the user has said
anything — by Codex directly, by Claude Code through the one-line CLAUDE.md
that imports it — so every byte is a standing tax paid by work that has
nothing to do with the track that wrote it.

It has been compacted before and grew straight back: between 2026-08-11 and
2026-08-12 it went from ~37 KB to ~52 KB, and 29 of the 30 commits touching it
in that window only ever added. The mechanism is never malice — a PR lands a
slice, the author appends the finding to that track's row, and nobody ever
removes one, so a table of pointers becomes a table of stories. Convention did
not stop it; this repo's pattern for an invariant that decays is a gate
(`SchemaShapeBaseline` for House Rule 9, `android-smoke.sh` for #8), so this is
the gate for House Rule "a row is one line".

    scripts/check-agents-md-size.py            # check the working tree
    scripts/check-agents-md-size.py --fix-hint # also print what to cut first

⚠ This measures *shape*, not *value*. It cannot tell a compressed pointer from
a vandalised one — deleting a true constraint passes it just as well as
relocating one. When you shrink a row, `scripts/check-doc-claims.py --base
master` is the check that the claim survived somewhere; run both.
"""
import argparse
import pathlib
import re
import sys

# Total budget. ⚠⚠ Codex's `project_doc_max_bytes` default: past 32 KiB Codex
# TRUNCATES the file silently, dropping its end with no error, so this is a hard
# ceiling rather than a taste. (Claude Code imports the whole file; only Codex
# cuts.) Raise it only together with a documented Codex config change.
MAX_BYTES = 32_768

# Claude Code reads CLAUDE.md, not AGENTS.md, so CLAUDE.md imports it. Anything
# else written there would reach Claude Code and never Codex.
CLAUDE_STUB = "@AGENTS.md"

# One row == one line == pointer + the constraint. 400 chars is roughly four
# lines of rendered prose: enough for "shipped through X; ⚠ don't do Y", not
# enough for a phase-by-phase account. The record holder before the gate
# existed was 5,553 chars.
MAX_CELL = 400

# Rows whose status cell is at least this long are worth naming even when the
# file as a whole is inside budget — they are where the next 5 KB comes from.
WARN_CELL = 340

TABLE_ROW = re.compile(r"^\|(?![\s:|-]*$)")


def rows(text):
    """Yield (lineno, cells) for every markdown table row that isn't a rule."""
    for lineno, line in enumerate(text.splitlines(), 1):
        if TABLE_ROW.match(line):
            yield lineno, [c.strip() for c in line.split("|")[1:-1]]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fix-hint", action="store_true",
                    help="also list the longest rows, worst first")
    ap.add_argument("path", nargs="?", default="AGENTS.md")
    args = ap.parse_args()

    path = pathlib.Path(args.path)
    text = path.read_text(encoding="utf-8")
    size = len(text.encode("utf-8"))
    failures = []

    if size > MAX_BYTES:
        failures.append(
            f"{path} is {size:,} bytes, over the {MAX_BYTES:,} budget by "
            f"{size - MAX_BYTES:,}. Shrink a row into its linked doc — do not "
            f"raise the budget without saying why in the PR description."
        )

    # ⚠ A MISSING stub fails too: Claude Code reads CLAUDE.md, not AGENTS.md, so
    # without it every Claude session starts with no project instructions at all.
    stub = path.parent / "CLAUDE.md"
    if path.name == "AGENTS.md":
        if not stub.exists():
            failures.append(
                f"{stub} is missing — Claude Code reads CLAUDE.md, not AGENTS.md, "
                f"so it would load none of this. Restore it as `{CLAUDE_STUB}`."
            )
        elif stub.read_text(encoding="utf-8").strip() != CLAUDE_STUB:
            failures.append(
                f"{stub} must contain only `{CLAUDE_STUB}` — anything else there is "
                f"read by Claude Code and never by Codex. Move it into {path.name}."
            )

    # Every row in a table must have the same cell count as that table's first
    # row. A stray `|` silently turns a status cell into two, which is how a
    # duplicated sentence sat unnoticed in the Timeline row.
    width = None
    prev_was_row = False
    long_rows = []
    for lineno, cells in rows(text):
        if not prev_was_row:
            width = len(cells)          # header of a new table
        elif len(cells) != width:
            failures.append(
                f"{path}:{lineno}: malformed table row — {len(cells)} cells, "
                f"expected {width}. Check for a stray `|` in the text."
            )
        prev_was_row = True

        status = cells[-1] if cells else ""
        if len(status) > MAX_CELL:
            failures.append(
                f"{path}:{lineno}: row status is {len(status)} chars, over the "
                f"{MAX_CELL} cap — “{cells[0][:44]}”. The overflow is narrative; "
                f"it belongs in the doc the row links to."
            )
        elif len(status) >= WARN_CELL:
            long_rows.append((len(status), lineno, cells[0][:44]))

    print(f"{path}: {size:,} bytes of {MAX_BYTES:,} "
          f"({100 * size / MAX_BYTES:.0f}% of budget)")

    if args.fix_hint and long_rows:
        print("\nClosest to the cap — trim these first:")
        for length, lineno, name in sorted(long_rows, reverse=True):
            print(f"  {length:4d}  L{lineno}  {name}")

    if failures:
        print("\n" + "=" * 72)
        for f in failures:
            print(f"FAIL {f}")
        print("=" * 72)
        print("\n⚠ Before cutting a row, run:  scripts/check-doc-claims.py --base master")
        return 1

    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
