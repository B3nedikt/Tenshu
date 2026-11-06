#!/usr/bin/env python3
"""Find references that point at nothing, and numbers that point at two things.

Docs have no tests, so a reference rots silently: a doc gets renamed or graduates
into an ADR, the pointer stays, and the next reader follows it into a 404. The
2026-08-13 audit found 10 dead links and 20 stale names that way, plus two ADRs
filed as 0004 — `production-plan.md` was citing "ADR 0004" for two different
decisions, one row apart.

    scripts/check-doc-refs.py            # check the working tree
    scripts/check-doc-refs.py --known    # also print the allowlist and why

⚠⚠ **The hard part of this check is what NOT to report.** Of the ~50 mentions
the first naive pass flagged, roughly 14 were correct as written: references to
docs that deliberately do not exist yet ("a hypothetical `core-database-events.md`",
"would-be `external-pairing-flow.md` (does not exist)"), design handoffs that are
gitignored on purpose, `docs/README.md` naming two proposals in the very sentence
that says it *graduated* them, and a user memory file that was never a repo doc.
A bulk "fix the dead links" pass over that list does real damage. So every
suppression here carries a reason, and

⚠ **the allowlist is itself checked.** An entry that no longer matches anything
fails the run and must be deleted. A suppression list nobody prunes is how the
next reader learns to ignore the whole gate.

Three checks:

1. **Dead links** — `[text](path)` whose target does not exist. Targets inside
   gitignored trees are resolved through `git check-ignore`, so the design
   handoffs under `docs/design/{timeline,fit,maps}/` are recognised rather than
   listed by name.
2. **Stale names** — a backticked `something.md` that matches no file in the
   repo *and* is not the label of a link whose target resolves. The label carve-out
   matters: `[`backend-relay-plan.md`](../proposals/sync-backend-relay-plan.md)`
   is a shortened label, not a broken reference.
3. **Duplicate numbers** — two files sharing a `NNNN-` prefix in one directory.

⚠ Necessary, not sufficient. This proves a path resolves, never that the sentence
around it is still true — a link can point at a live doc that says the opposite.
For AGENTS.md's rows specifically, `check-doc-claims.py` is the companion check.
"""
import argparse
import collections
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SKIP_DIRS = {".git", "build", "node_modules", ".gradle", "DerivedData", ".idea", "out"}

# Whole files whose references are frozen history and must not be "fixed".
# (Empty today. `open-design-decisions.md` lived here until 2026-08-13, when its
# retention window expired and it was deleted; the five decisions still cited
# were inlined at their citation sites first.)
SKIP_FILES = {}

# Bare `name.md` mentions that correctly refer to something that is not a repo file.
ALLOW_NAMES = {
    "core-database-events.md":
        "Deliberately unwritten — cited as '(TBD)' and 'a hypothetical'.",
    "external-pairing-flow.md":
        "tech-debt.md says outright: 'would-be docs/sync/external-pairing-flow.md "
        "(does not exist)' and then 'Write ...'.",
    "web-third-party-services.md":
        "Named as a proposal still to be written.",
    "app-console-error-reports.md":
        "Named as a proposal still to be written.",
    "control-plane-ux.md":
        "Named as future work — 'covered in ... (when ...)'.",
    "CONVENTIONS.md":
        "A suggested filename — 'in web/sdk-adapter/CONVENTIONS.md (or similar)'.",
    "core-value-property.md":
        "docs/README.md names it in the sentence saying it GRADUATED; the doc is "
        "meant to be gone.",
    "core-property-relation-unification.md":
        "Same graduated-proposal sentence in docs/README.md.",
    "design-system-audit.md":
        "ADR 0001 names the filename it had when the ADR was written, next to a "
        "live link to docs/design/audit.md.",
    "design-system-improvement-plan.md":
        "Same historical-filename note in ADR 0001.",
    "sync-device-management.md":
        "Named in sync-tombstone-ordering-race.md as where the note came FROM, "
        "beside live links to the threat model and ADR 0007.",
    "sync-replica-identity.md":
        "Withdrawn before it landed, and named in drive-upload-stop-and-reclamation.md "
        "§4.26 precisely to record how far a wrong finding propagated — 'a whole design "
        "doc (`sync-replica-identity.md`, withdrawn)'. Never committed; must stay gone.",
    "feedback_mirror_production_wiring.md":
        "A user MEMORY file, not a repo doc — 'the user's ... memory cautions "
        "against'. Never existed here and should not.",
    "timeline-agent.md":
        "Design handoff under the gitignored docs/design/timeline/.",
    "fit-agent.md":
        "Design handoff under the gitignored docs/design/fit/.",
    "fit-brief.md":
        "Design handoff under the gitignored docs/design/fit/.",
    "maps-agent.md":
        "Design handoff under the gitignored docs/design/maps/.",
}

# Dead links that are known-unresolved and awaiting a decision, not an oversight.
# Kept visible rather than silently suppressed: printed every run, under their own
# heading, so "we chose not to fix this" never decays into "nobody noticed".
#
# Empty today — both original entries were resolved on 2026-08-13 rather than
# carried. ⚠ That is the intended lifecycle: an entry here is a decision deferred,
# not a decision made, so adding one should feel more expensive than fixing the link.
KNOWN_DEAD = {}

LINK = re.compile(r"\[([^\]]*)\]\(([^)\s]+?)(?:#[^)]*)?\)")
BARE = re.compile(r"`([A-Za-z0-9._/-]+\.(?:md|adoc))`")
EXTERNAL = re.compile(r"^(https?:|mailto:|tel:|#)")
NUMBERED = re.compile(r"^(\d{2,4})-")


def md_files():
    for p in sorted(ROOT.rglob("*.md")):
        if not any(s in p.parts for s in SKIP_DIRS):
            yield p


def gitignored(paths):
    """Subset of paths that git ignores. One batched call, not one per path."""
    if not paths:
        return set()
    try:
        r = subprocess.run(["git", "check-ignore", "--stdin"], cwd=ROOT, text=True,
                           input="\n".join(paths), capture_output=True)
    except OSError:
        return set()
    return {ln.strip() for ln in r.stdout.splitlines() if ln.strip()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--known", action="store_true",
                    help="print the allowlist with the reason for each entry")
    args = ap.parse_args()

    docs = [p for p in md_files() if str(p.relative_to(ROOT)) not in SKIP_FILES]
    existing = {p.name for p in ROOT.rglob("*")
                if p.is_file() and not any(s in p.parts for s in SKIP_DIRS)}

    if args.known:
        print("Suppressed on purpose — each entry must still match something:\n")
        for name, why in sorted(ALLOW_NAMES.items()):
            print(f"  `{name}`\n      {why}")
        for f, why in sorted(SKIP_FILES.items()):
            print(f"  (whole file) {f}\n      {why}")
        print()

    dead, stale = [], []
    used_allow, used_known = set(), set()

    # Pass 1 — every link, with the label names it would vouch for. A link's label
    # only counts as "explained" once we know whether its target resolves, is
    # gitignored, or is a known gap, and gitignored-ness needs one batched call.
    lines = {}                # (rel, lineno) -> raw line
    links = []                # (rel, lineno, target, abs, label_names)
    for p in docs:
        rel = str(p.relative_to(ROOT))
        for i, line in enumerate(p.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            lines[(rel, i)] = line
            for label, target in LINK.findall(line):
                if EXTERNAL.match(target):
                    continue
                t = target.split("#")[0]
                if not t:
                    continue
                links.append((rel, i, target, str((p.parent / t).resolve()),
                              {n.split("/")[-1] for n in BARE.findall(label)}))

    ignored = gitignored([abs_ for _, _, _, abs_, _ in links
                          if not pathlib.Path(abs_).exists()])

    explained = collections.defaultdict(set)   # (rel, lineno) -> label names to skip
    for rel, i, target, absolute, label_names in links:
        if pathlib.Path(absolute).exists():
            explained[(rel, i)] |= label_names
            continue
        if absolute in ignored:                # deliberately local-only tree
            explained[(rel, i)] |= label_names
            continue
        if (rel, target) in KNOWN_DEAD:        # reported below, not twice
            used_known.add((rel, target))
            explained[(rel, i)] |= label_names
            continue
        dead.append((rel, i, target))

    # Pass 2 — bare names, now that we know which labels are already accounted for.
    for (rel, i), line in lines.items():
        for name in BARE.findall(line):
            base = name.split("/")[-1]
            if base in existing or base in explained[(rel, i)]:
                continue
            if base in ALLOW_NAMES:
                used_allow.add(base)
                continue
            stale.append((rel, i, base, line.strip()[:96]))
    stale.sort()

    failures = 0

    print("=" * 76)
    print(f"DEAD LINKS — target does not exist ({len(dead)})")
    print("=" * 76)
    for rel, i, target in dead:
        print(f"  {rel}:{i}  ->  {target}")
        failures += 1
    if not dead:
        print("  none")

    print()
    print("=" * 76)
    print(f"STALE NAMES — `x.md` matches no file, and no link on the line ({len(stale)})")
    print("=" * 76)
    for rel, i, base, ctx in stale:
        print(f"  {rel}:{i}  `{base}`")
        print(f"      {ctx}")
        failures += 1
    if not stale:
        print("  none")

    print()
    print("=" * 76)
    print("DUPLICATE NUMBERS — two files sharing a NNNN- prefix in one directory")
    print("=" * 76)
    dirs = collections.defaultdict(lambda: collections.defaultdict(list))
    for p in ROOT.rglob("*.md"):
        if any(s in p.parts for s in SKIP_DIRS):
            continue
        m = NUMBERED.match(p.name)
        if m:
            dirs[p.parent][m.group(1)].append(p.name)
    dupes = 0
    for d, byname in sorted(dirs.items()):
        for num, names in sorted(byname.items()):
            if len(names) > 1:
                print(f"  {d.relative_to(ROOT)}/  number {num}:")
                for n in sorted(names):
                    print(f"        {n}")
                dupes += 1
                failures += 1
    if not dupes:
        print("  none")

    if KNOWN_DEAD:
        print()
        print("-" * 76)
        print("KNOWN GAPS — awaiting a decision, not an oversight (not failures)")
        print("-" * 76)
        for (rel, target), why in sorted(KNOWN_DEAD.items()):
            print(f"  {rel}  ->  {target}\n      {why}")

    # The allowlist checks itself: an entry matching nothing is dead weight, and a
    # suppression list nobody prunes teaches the next reader to ignore the gate.
    rot = sorted(set(ALLOW_NAMES) - used_allow)
    rot_known = sorted(set(KNOWN_DEAD) - used_known)
    if rot or rot_known:
        print()
        print("=" * 76)
        print("STALE ALLOWLIST — these no longer match anything; delete them")
        print("=" * 76)
        for name in rot:
            print(f"  ALLOW_NAMES[{name!r}] — nothing references it any more.")
            failures += 1
        for key in rot_known:
            print(f"  KNOWN_DEAD[{key!r}] — that link is gone or now resolves.")
            failures += 1

    print()
    if failures:
        print(f"FAIL — {failures} problem(s).")
        print("⚠ Read the sentence before redirecting: some references point at a "
              "doc that is meant not to exist.")
        print("  If this one is correct as written, add it to ALLOW_NAMES with the "
              "reason, not a bare suppression.")
        return 1
    print("OK — every reference resolves, and no number is claimed twice.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
