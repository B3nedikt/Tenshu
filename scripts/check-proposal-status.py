#!/usr/bin/env python3
"""Every proposal must say, near the top, what state it is in.

CLAUDE.md used to carry a status clause per track — "Ph1-3 shipped, remaining
X" — beside the pointer and the trap. That half rotted: the 2026-09-07 sweep
found three rows calling shipped work outstanding while every one of the linked
docs had it right. So the rows dropped status and now *delegate* it to the doc.

That delegation only works if the doc actually has the field, in one place, where
a reader lands. This gate is what makes it safe to have dropped it.

    scripts/check-proposal-status.py           # check the working tree
    scripts/check-proposal-status.py --list    # also print every status line found

⚠⚠ **This checks presence, never truth.** A doc whose status line says "not yet
implemented" over a shipped feature passes here — that is exactly the failure the
sweep found four times, and no script can catch it. What this buys is that there
is one obvious place to look and to correct, instead of the field being absent in
one doc and buried at line 400 in another.

⚠ **The spelling is deliberately loose.** The convention grew rather than being
designed, so a status line is written at least six ways across the tree:

    **Status:** v0.5 - ...          **Status: v0.5 - ...**
    - **Status:** **SHIPPED** ...   > Status: **Proposal ...**
    Status: **v0.7 (2026-08-26).**  ## Status

Normalising those would be a 170-file diff for no reader benefit, and a gate that
demands one spelling would mostly generate churn. So the matcher accepts any of
them, and the rule is only: *the words "Status" and a value, inside the first few
lines.*

The allowlist below is for one genuine genre exception, and it carries a reason
per entry. ⚠ It is itself checked: an entry that has since grown a status line,
or that names a file which no longer exists, fails the run and must be deleted.
A suppression list nobody prunes is how the next reader learns to ignore the gate.
"""
import argparse
import pathlib
import re
import sys

PROPOSALS = pathlib.Path("docs/proposals")

# How many lines from the top count as "near the top". Generous: several docs
# open with a one-line title, a blank, and a ⚠⚠ warning banner before the status.
HEAD_LINES = 10

# Optional list marker or blockquote, optional bold, the word Status, a colon
# somewhere in the vicinity. Case-insensitive; `## Status` headings count too.
STATUS = re.compile(r"^\s*(?:[-*>#]\s*)*\**\s*status\b\s*:?\**\s*:?", re.I)

# ---------------------------------------------------------------------------
# Genre exception. `*-handover.md` files are not proposals: they are session
# handoffs — a kickoff prompt and a context dump for whoever picks the work up
# next. Their state is the state of the track they hand over, which lives in
# that track's own proposal, so a status line here would be a second copy of
# exactly the field this gate exists to keep single.
# ---------------------------------------------------------------------------
ALLOW = {
    "ios-control-handover.md":
        "session handover, not a proposal — the C0 deliverable of the "
        "Control-on-iOS roadmap; state lives in ios-control-notifications-and-shares.md",
    "ios-drive-live-client-handover.md":
        "session handover, not a proposal — state lives in ios-drive-parity.md",
    "ios-proxy-architecture-handover.md":
        "session handover, not a proposal — state lives in ios-proxy-architecture.md",
    "ios-proxy-phase6-handover.md":
        "kickoff prompt for a fresh session — state lives in ios-proxy-architecture.md",
    "ios-proxy-phase6b5-handover.md":
        "kickoff prompt for a fresh session — state lives in ios-proxy-architecture.md",
}


def status_line(path: pathlib.Path) -> str | None:
    """The first line near the top that reads as a status field, if any."""
    try:
        head = path.read_text(encoding="utf-8").split("\n")[:HEAD_LINES]
    except OSError:
        return None
    for line in head:
        # Guard against a prose line that merely opens with the word: require
        # "status" to appear early, so "Status of the relay is discussed below"
        # still matches but "Statuses are modelled as ..." does not.
        if STATUS.match(line) and "status" in line.lower()[:40]:
            return line.strip()
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--list", action="store_true",
                    help="print the status line found for every proposal")
    args = ap.parse_args()

    if not PROPOSALS.is_dir():
        print(f"FAIL — {PROPOSALS} not found. Run from the repo root.")
        return 1

    docs = sorted(p for p in PROPOSALS.glob("*.md"))
    missing, found = [], {}
    for p in docs:
        line = status_line(p)
        if line is None:
            missing.append(p.name)
        else:
            found[p.name] = line

    if args.list:
        for name, line in found.items():
            print(f"  {name}\n      {line[:100]}")
        print()

    print(f"{len(docs)} proposals — {len(found)} carry a status line "
          f"in the first {HEAD_LINES} lines.")

    failures = 0

    unexplained = [m for m in missing if m not in ALLOW]
    if unexplained:
        print()
        print("NO STATUS LINE — add one in the first few lines, or allowlist it "
              "with a reason if it is a handover rather than a proposal:")
        for m in unexplained:
            print(f"  {m}")
        failures += len(unexplained)

    # The allowlist checks itself, both ways.
    stale = [name for name in ALLOW if name in found]
    gone = [name for name in ALLOW if not (PROPOSALS / name).exists()]
    if stale or gone:
        print()
        print("ALLOWLIST ROT — delete these entries:")
        for name in stale:
            print(f"  ALLOW[{name!r}] — it has a status line now, so the exception is spent.")
        for name in gone:
            print(f"  ALLOW[{name!r}] — no such file any more.")
        failures += len(stale) + len(gone)

    print()
    if failures:
        print(f"FAIL — {failures} problem(s).")
        print("⚠ Presence only. This never checks that the status is TRUE, which is "
              "the failure mode that actually bites — see the docstring.")
        return 1
    print("OK — every proposal says what state it is in.")
    print("⚠ Presence only: a status line that lies passes this gate.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
