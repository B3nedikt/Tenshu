#!/usr/bin/env python3
"""Find marked claims in AGENTS.md that have no home anywhere else in docs/.

AGENTS.md's in-flight table is a set of *pointers*. Its rows accumulate
⚠ / ⚠⚠ / ⭐ / ⚖️ -marked claims — "X was deliberately NOT done because…",
"Y is load-bearing because…" — and those are exactly the sentences a future
reader needs. When a row is shrunk, any such claim that exists only there is
destroyed silently, because docs have no tests.

This is the gate. Relocated text gets reworded, so exact matching is useless;
instead each claim is fingerprinted by its *rare tokens* (backticked symbols,
CamelCase identifiers, SCREAMING_SNAKE constants, §refs, PR numbers) and we ask
whether those appear in the doc the row actually links to.

    scripts/check-doc-claims.py                 # check the working tree
    scripts/check-doc-claims.py --base master   # check the PRE-EDIT rows
                                                # against the POST-EDIT docs

⚠⚠ The second form is the one that detects loss. Running it on the working tree
after a shrink tells you nothing about what the shrink removed — the claims are
already gone from AGENTS.md. `--base` reads AGENTS.md from a git ref and checks
those older, fuller rows against the docs as they stand now.

⚠ Before 2026-09-14 the file was CLAUDE.md (it is now the one-line import of
AGENTS.md), so a ref from before the move is read from CLAUDE.md instead. A
source with no table rows is an error, never a pass: the import stub has no
claims, and "0 orphans" from reading it would look exactly like a clean shrink.

⚠ This is a necessary, not a sufficient, condition. It tests whether a claim's
distinctive *tokens* appear in the target doc — not whether the claim is still
true there. A row and a proposal can agree perfectly and both be stale, and a
token can appear in a paragraph that says the opposite. Read the section.
Expect occasional false positives from punctuation drift (`(0,0)` vs `(0, 0)`);
verify before acting on a reported orphan.
"""
import argparse
import pathlib
import re
import subprocess
import sys

MARKER = re.compile(r"(⚠+|⭐+|⚖️)")
LINK = re.compile(r"\((docs/[\w./-]+\.md)\)")

TOKEN_PATTERNS = [
    re.compile(r"`([^`]{2,60})`"),                            # backticked symbols
    re.compile(r"\b([A-Z][a-z]+(?:[A-Z][A-Za-z0-9]+)+)\b"),   # CamelCase
    re.compile(r"\b([A-Z]{2,}(?:_[A-Z0-9]+)+)\b"),            # SCREAMING_SNAKE
    re.compile(r"(§[\d.]+[a-z]?)"),                           # section refs
    re.compile(r"#(\d{3,5})\b"),                              # PR numbers
]
STOPWORDS = {"Claude", "CLAUDE", "README", "TODO", "NOTE"}

# A claim needs at least this share of its tokens present to count as grounded.
GROUNDED_RATIO = 0.34


def tokens(text):
    out = set()
    for pat in TOKEN_PATTERNS:
        for m in pat.findall(text):
            t = m.strip()
            if " " in t or "/" in t:
                # a backticked span may itself contain the real identifier
                for sub in re.split(r"[\s/(),.:]+", t):
                    if len(sub) > 3 and sub not in STOPWORDS:
                        out.add(sub)
            elif len(t) > 3 and t not in STOPWORDS:
                out.add(t)
    return out


def claims(md):
    """Yield (lineno, row_name, linked_docs, marker, body) per marked claim."""
    for lineno, line in enumerate(md.splitlines(), 1):
        if not line.startswith("|"):
            continue
        row = line.split("|")[1].strip()[:48] if line.count("|") > 2 else "?"
        linked = sorted(set(LINK.findall(line)))
        parts = MARKER.split(line)
        for i in range(1, len(parts) - 1, 2):
            body = parts[i + 1][:400].strip()
            if len(body) > 30:
                yield lineno, row, linked, parts[i], body


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=".", help="repo root (default: cwd)")
    ap.add_argument("--base", metavar="REF",
                    help="read AGENTS.md from this git ref instead of the working "
                         "tree — use it to check pre-edit rows against post-edit docs")
    args = ap.parse_args()
    root = pathlib.Path(args.root)

    if args.base:
        src = f"{args.base}:AGENTS.md"
        shown = subprocess.run(["git", "-C", str(root), "show", src],
                               capture_output=True, text=True)
        if shown.returncode != 0:
            src = f"{args.base}:CLAUDE.md"   # a ref from before the rename
            shown = subprocess.run(["git", "-C", str(root), "show", src],
                                   capture_output=True, text=True, check=True)
        claude = shown.stdout
    else:
        src = "AGENTS.md"
        claude = (root / src).read_text(encoding="utf-8")

    if not any(line.startswith("|") for line in claude.splitlines()):
        print(f"ERROR: {src} has no table rows — nothing to check, which is not a pass. "
              f"Is it the `@AGENTS.md` import stub?", file=sys.stderr)
        return 2

    cache = {}

    def text_of(rel):
        if rel not in cache:
            p = root / rel
            cache[rel] = p.read_text(encoding="utf-8", errors="ignore") if p.exists() else ""
        return cache[rel]

    # everywhere else a claim could legitimately have moved to
    elsewhere = "\n".join(
        p.read_text(encoding="utf-8", errors="ignore")
        for p in list((root / "docs").rglob("*.md")) + list(root.glob("*/*-agent.md"))
    )

    orphan, offsite, ok, nolink = [], [], 0, 0
    for lineno, row, linked, marker, body in claims(claude):
        toks = tokens(body)
        if not toks:
            continue  # no fingerprint — cannot judge mechanically
        if not linked:
            nolink += 1
            continue
        own = "\n".join(text_of(d) for d in linked)
        in_own = {t for t in toks if t in own}
        if len(in_own) / len(toks) >= GROUNDED_RATIO:
            ok += 1
        elif any(t in elsewhere for t in toks - in_own):
            offsite.append((lineno, row, marker, body, linked, sorted(toks - in_own)[:6]))
        else:
            orphan.append((lineno, row, marker, body, linked, sorted(toks)[:8]))

    total = ok + len(offsite) + len(orphan)
    print(f"marker-claims with a fingerprint in {src}: {total}"
          f"  (+{nolink} in rows with no doc link)")
    print(f"  grounded in the row's OWN linked doc : {ok}")
    print(f"  grounded only ELSEWHERE in docs/     : {len(offsite)}")
    print(f"  ORPHANS — nowhere at all             : {len(orphan)}\n")

    if orphan:
        print("=" * 72 + "\nORPHANS — cutting these from AGENTS.md destroys the only copy\n")
        for lineno, row, marker, body, linked, toks in orphan:
            print(f"L{lineno} [{row}] {marker}  -> {', '.join(linked)}")
            print(f"    {body[:240]}\n    tokens: {toks}\n")

    if offsite:
        print("=" * 72 + "\nOFF-SITE — not in the row's own doc, but somewhere in docs/\n")
        for lineno, row, marker, body, linked, missing in offsite:
            print(f"L{lineno} [{row}] {marker}  -> {', '.join(linked)}")
            print(f"    {body[:200]}\n    absent from own doc: {missing}\n")

    return 1 if orphan else 0


if __name__ == "__main__":
    sys.exit(main())
