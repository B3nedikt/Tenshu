#!/usr/bin/env python3
"""Export this (private) repository's tree as one summary commit for the public repository.

Development happens here, in the private repository; the public one receives a squashed copy of
the tree from time to time, one commit per sync, opened as a PR. Design and runbook:
`docs/proposals/public-export.md`.

    scripts/public-export.py                      # dry run: build the export, run every gate
    scripts/public-export.py --keep DIR           # dry run, and leave the cleaned tree in DIR
    scripts/public-export.py --publish CLONE      # also commit it into a clone of the public repo

⚠⚠ **Every sync publishes.** The gates are the only thing between this tree and the public one,
so a finding is fatal, never a warning to read later. `review` findings are the exception: they
are things a script cannot decide (is this coordinate a landmark or somebody's home?).

⚠⚠ **It never pushes, and it never writes into a clone of THIS repository.** One push of a branch
from a history-carrying clone publishes all of its history. `--publish` refuses any clone that
shares a commit with this repository; the push and the PR are left to the maintainer.

The rules live in `scripts/public-export-rules.json`. That file names the private strings it
removes, so it is itself never exported (hard-coded below, not just listed in the rules).
"""
import argparse
import datetime
import fnmatch
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
RULES_PATH = os.path.join(HERE, "public-export-rules.json")
# Never exported, whatever the rules say: it lists the very strings the export removes.
ALWAYS_EXCLUDED = ["scripts/public-export-rules.json"]
TRAILER = "Internal-Source"
# Subjects this script writes. A trailer counts only on these, so a contributor's commit carrying
# an `Internal-Source:` line cannot pass as the last sync. (A squash merge keeps the subject.)
SYNC_SUBJECTS = ("Tenshu, initial public release", "Sync from the internal repository (")

# ---------------------------------------------------------------- reference rewriting

# A bare `#NNNN` is a PR/issue of the repository the text lives in. The public repository's
# numbering restarts at 1, so every bare reference written here is qualified on the way out.
# Not after a word character, `/`, `&`, `#`, `.`, `-` (anchors, entities, `owner/repo#N`), not
# inside quotes (`'#555'`) and not after a colon (`color:#666`): those are colours.
BARE_REF = re.compile(r"""(?<![\w/&#.\-'":])#(\d{2,5})(?![\w-])""")
# `#111` … `#999` are colours far more often than PRs; a real PR #333 left bare only mislinks.
REPDIGIT = re.compile(r"(\d)\1\1")
# The repository's earlier name; GitHub's redirect from it becomes ambiguous after the cut.
OLD_NAME_REF = re.compile(r"B3nedikt/Drive(?=(?:/pull/|/issues/|#)\d)")


def rewrite_refs(text, internal_repo, min_ref, max_ref):
    def bare(m):
        n = int(m.group(1))
        if n < min_ref or n > max_ref or REPDIGIT.fullmatch(m.group(1)):
            return m.group(0)
        return f"{internal_repo}#{n}"

    text = OLD_NAME_REF.sub(internal_repo, text)
    return BARE_REF.sub(bare, text)


# ---------------------------------------------------------------- rules


def path_matches(path, globs):
    return any(fnmatch.fnmatchcase(path, g) for g in globs)


def coarsen(text, pattern, decimals):
    def r(m):
        return f"{float(m.group(0)):.{decimals}f}"

    return re.sub(pattern, r, text)


def transform_text(path, text, rules):
    """Every text transform the export applies to one file, in order."""
    for rep in rules.get("replace", []):
        if path_matches(path, rep.get("paths", ["*"])):
            text = re.sub(rep["pattern"], rep["with"], text)
    for c in rules.get("coarsen_coords", []):
        if path_matches(path, c["paths"]):
            text = coarsen(text, c["pattern"], c["decimals"])
    refs = rules.get("rewrite_refs", {})
    if refs.get("enabled", True) and not path_matches(path, refs.get("skip", [])):
        text = rewrite_refs(text, rules["internal_repo"], refs.get("min", 1), refs["max"])
    return text


def read_text(full):
    """The file's text, or None when it is binary (left byte-for-byte as it is)."""
    with open(full, "rb") as f:
        data = f.read()
    if b"\0" in data[:8192]:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


# ---------------------------------------------------------------- building the export


def git(*args, cwd=None, check=True):
    cwd = cwd or REPO
    out = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)
    if check and out.returncode != 0:
        sys.exit(f"git {' '.join(args)} failed in {cwd}:\n{out.stderr}")
    return out.stdout.strip()


def build(ref, dest, rules):
    """Extract `ref`'s tree into `dest` (committed files only: nothing ignored can come along),
    drop the excluded paths, apply the text transforms. Returns the exported paths."""
    excluded = ALWAYS_EXCLUDED + rules.get("exclude", [])
    # `export-subst` would expand author e-mails and ref names into files, and `export-ignore`
    # would hide paths from every gate below. Neither is used; refuse rather than reason about it.
    attrs = git("grep", "-l", "export-", ref, "--", "*.gitattributes", ".gitattributes", check=False)
    if attrs:
        sys.exit(f"export-* git attributes found ({attrs}): git archive would apply them. Remove them.")
    archive = os.path.join(dest, "..", os.path.basename(dest) + ".tar")
    subprocess.run(["git", "archive", "--format=tar", "-o", archive, ref], cwd=REPO, check=True)
    with tarfile.open(archive) as tar:
        tar.extractall(dest, filter="tar")
    os.remove(archive)

    exported = []
    for root, dirs, files in os.walk(dest):
        dirs.sort()
        # os.walk lists a symlink to a directory under `dirs` and never descends: handle it as a file.
        links = [d for d in dirs if os.path.islink(os.path.join(root, d))]
        dirs[:] = [d for d in dirs if d not in links]
        for name in sorted(files) + links:
            full = os.path.join(root, name)
            rel = os.path.relpath(full, dest)
            if path_matches(rel, excluded):
                os.remove(full)
                continue
            if os.path.islink(full):
                target = os.path.realpath(full)
                if not target.startswith(os.path.realpath(dest) + os.sep):
                    sys.exit(f"{rel} is a symlink pointing outside the tree ({os.readlink(full)})")
                exported.append(rel)
                continue
            text = read_text(full)
            if text is not None:
                new = transform_text(rel, text, rules)
                if new != text:
                    with open(full, "w", encoding="utf-8", newline="") as f:
                        f.write(new)
            exported.append(rel)
    # Directories left empty by an exclusion would not exist in git either.
    for root, dirs, files in os.walk(dest, topdown=False):
        if root != dest and not os.listdir(root):
            os.rmdir(root)
    return exported


# ---------------------------------------------------------------- gates


def scan(dest, paths, rules):
    """Returns (fatal, review): lists of human-readable findings."""
    fatal, review = [], []
    max_bytes = rules.get("max_file_bytes", 10_000_000)
    forbid = [(re.compile(f["pattern"]), f.get("why", "")) for f in rules.get("forbid", [])]
    reviews = [(re.compile(r["pattern"]), r.get("allow", []), r.get("why", "")) for r in rules.get("review", [])]
    for rel in paths:
        full = os.path.join(dest, rel)
        if path_matches(rel, rules.get("forbidden_paths", [])) and not path_matches(rel, rules.get("allowed_paths", [])):
            fatal.append(f"{rel}: forbidden path")
        if os.path.islink(full):
            continue
        size = os.path.getsize(full)
        if size > max_bytes:
            fatal.append(f"{rel}: {size} bytes, over the {max_bytes} byte limit")
        text = read_text(full)
        if text is None:
            # Binary or not UTF-8: a PDF's metadata or a Latin-1/UTF-16 file can still carry a name.
            with open(full, "rb") as f:
                data = f.read()
            views = [data.decode(enc, "ignore") for enc in ("utf-8", "latin-1", "utf-16-le", "utf-16-be")]
            for rx, why in forbid:
                if any(rx.search(v) for v in views):
                    fatal.append(f"{rel}: forbidden string in binary content ({why})")
            continue
        for rx, why in forbid:
            for m in rx.finditer(text):
                line = text.count("\n", 0, m.start()) + 1
                fatal.append(f"{rel}:{line}: forbidden string ({why})")
        for rx, allow, why in reviews:
            if path_matches(rel, allow):
                continue
            hits = [text.count("\n", 0, m.start()) + 1 for m in rx.finditer(text)]
            if hits:
                review.append(f"{rel}:{','.join(map(str, hits[:8]))}: {why}")
    return fatal, review


def re2_literal(text):
    """`text` as a Go (RE2) regex matching exactly it. Python's re.escape also escapes spaces,
    which RE2 rejects."""
    out = []
    for ch in text:
        out.append("\\n" if ch == "\n" else ("\\" + ch if ch in "\\.^$|?*+()[]{}" else ch))
    return "".join(out)


def gitleaks(dest, rules, scratch):
    """gitleaks over the cleaned tree as files (it has no history to scan, which is the point)."""
    if shutil.which("gitleaks") is None:
        return ["gitleaks is not installed (brew install gitleaks): the secret scan did not run"]
    config = os.path.join(scratch, "gitleaks.toml")
    paths = rules.get("gitleaks_allow_paths", [])
    values = rules.get("gitleaks_allow_matches", [])

    def toml_list(xs):
        return "".join(f"  '''{x}''',\n" for x in xs)

    with open(config, "w") as f:
        f.write("[extend]\nuseDefault = true\n")
        # A finding is allowed by its exact matched text, not by its file: allowlisting a test file
        # whole would wave through a real secret pasted into it later. `gitleaks_allow_paths` is the
        # exception, kept for third-party files nobody here edits (sqlite3.c).
        if paths or values:  # gitleaks rejects an empty allowlist table
            f.write("\n[allowlist]\nregexTarget = \"match\"\n")
            f.write(f"paths = [\n{toml_list(paths)}]\n")
            f.write(f"regexes = [\n{toml_list('^' + re2_literal(v) + '$' for v in values)}]\n")
    report = os.path.join(scratch, "gitleaks.json")
    run = subprocess.run(
        ["gitleaks", "dir", dest, "--no-banner", "--redact", "--config", config,
         "--report-format", "json", "--report-path", report, "--exit-code", "0"],
        capture_output=True, text=True,
    )
    if run.returncode != 0 or not os.path.exists(report):
        return [f"gitleaks did not complete (exit {run.returncode}): {run.stderr.strip()[-300:]}"]
    with open(report) as f:
        findings = json.load(f) or []
    return [f"{x['File'].replace(dest + os.sep, '')}:{x['StartLine']}: gitleaks {x['RuleID']}" for x in findings]


# ---------------------------------------------------------------- publishing


def check_public_clone(clone, rules):
    """Refuse anything but a clean clone of the public repository that shares no history with
    this one. Returns the base branch name. ⚠⚠ Every check here fails closed: an error is a refusal."""
    if not os.path.isdir(os.path.join(clone, ".git")):
        sys.exit(f"{clone} is not a git clone (a worktree or bare repo is not accepted either)")
    url = git("remote", "get-url", "origin", cwd=clone, check=False)
    if not re.search(rules["public_remote"], url) or "Internal" in url:
        sys.exit(f"{clone}'s origin is {url!r}, not the public repository ({rules['public_remote']})")
    if os.path.exists(os.path.join(clone, ".git", "objects", "info", "alternates")):
        sys.exit(f"{clone} borrows objects from another repository (alternates): refusing")
    if git("status", "--porcelain", cwd=clone):
        sys.exit(f"{clone} has uncommitted changes")
    for repo, name in ((clone, "the clone"), (REPO, "this repository")):
        if git("rev-parse", "--is-shallow-repository", cwd=repo) != "false":
            sys.exit(f"{name} is shallow: its roots cannot be compared. Use full clones.")
    has_commits = bool(git("rev-parse", "--verify", "--quiet", "HEAD", cwd=clone, check=False))
    roots = git("rev-list", "--max-parents=0", "--all", cwd=clone).split()
    if has_commits and not roots:
        sys.exit(f"{clone} has commits but no root commit could be listed: refusing")
    for root in roots:
        if subprocess.run(["git", "cat-file", "-e", f"{root}^{{commit}}"], cwd=REPO,
                          capture_output=True).returncode == 0:
            sys.exit(f"⚠⚠ {clone} shares history with this repository (root {root[:12]}). "
                     "It is a clone of the INTERNAL repo: pushing from it publishes that history.")
    return rules.get("public_branch", "master")


def last_sync(clone, branch):
    """(commit, internal sha) of the newest sync commit on `branch`, or (None, None)."""
    if not git("rev-parse", "--verify", "--quiet", branch, cwd=clone, check=False):
        return None, None
    # --topo-order: a child before its parent, so the newest sync wins even within one second.
    log = git("log", "--topo-order", branch, f"--grep=^{TRAILER}: ", "--format=%H%x00%B%x01", cwd=clone)
    for entry in filter(None, (e.strip("\n") for e in log.split("\x01"))):
        commit, body = entry.split("\0", 1)
        m = re.search(rf"^{TRAILER}: ([0-9a-f]{{40}})$", body, re.M)
        if body.startswith(SYNC_SUBJECTS) and m:
            return commit, m.group(1)
    return None, None


def walk_files(top):
    for root, dirs, files in os.walk(top):
        links = [d for d in dirs if os.path.islink(os.path.join(root, d))]
        yield from files
        yield from links


def message_findings(message, rules, scratch):
    """The summary lists internal commit subjects, and they go public too: every text gate runs on
    them, review patterns included (there is no one to review a commit message later)."""
    found = [f"commit message: forbidden string ({f.get('why', '')})" for f in rules.get("forbid", [])
             if re.search(f["pattern"], message)]
    found += [f"commit message: {r.get('why', '')}" for r in rules.get("review", [])
              if re.search(r["pattern"], message)]
    box = os.path.join(scratch, "message")
    os.makedirs(box, exist_ok=True)
    with open(os.path.join(box, "COMMIT_MESSAGE"), "w") as f:
        f.write(message)
    found += [f"commit message: {x}" for x in gitleaks(box, rules, scratch)]  # fails closed if absent
    return found


def summary_message(ref_sha, since, rules):
    short = ref_sha[:10]
    if since is None:
        return f"Tenshu, initial public release\n\n{TRAILER}: {ref_sha}\n"
    subjects = git("log", "--no-merges", "--format=%s", f"{since}..{ref_sha}").splitlines()
    msg_rules = dict(rules, coarsen_coords=[dict(c, paths=["*"]) for c in rules.get("coarsen_coords", [])])
    body = "\n".join(f"- {transform_text('COMMIT_MESSAGE', s, msg_rules)}" for s in subjects)
    return f"Sync from the internal repository ({short})\n\n{body}\n\n{TRAILER}: {ref_sha}\n"


def internal_remotes_pointing_public(rules):
    """Remotes of THIS repository that name the public one. After the rename, a plain `git push`
    from here through such a remote publishes the whole history."""
    bad = []
    for name in git("remote").split():
        for url in git("remote", "get-url", "--all", name).splitlines() + \
                   git("remote", "get-url", "--push", "--all", name).splitlines():
            if re.search(rules["public_remote"], url) and "Internal" not in url:
                bad.append(f"{name} → {url}")
    return sorted(set(bad))


def publish(dest, clone, ref_sha, rules, allow_drift):
    bad = internal_remotes_pointing_public(rules)
    if bad:
        sys.exit("⚠⚠ This repository's remotes still name the public repository:\n  " + "\n  ".join(bad)
                 + "\nRe-point them at the internal one first (public-export.md §5, step 3).")
    branch = check_public_clone(clone, rules)
    has_base = bool(git("rev-parse", "--verify", "--quiet", branch, cwd=clone, check=False))
    sync_commit, since = last_sync(clone, branch)
    if has_base:
        if sync_commit is None:
            sys.exit(f"{branch} has commits but none carries an {TRAILER} trailer: not a repo this script made")
        # Merge commits are how a sync PR lands; only the commits under them are drift.
        drift = git("log", "--no-merges", "--format=%h %s", f"{sync_commit}..{branch}", cwd=clone)
        if drift and not allow_drift:
            sys.exit("Public commits since the last sync, not yet brought into this repository:\n"
                     f"{drift}\nImport them here first (git format-patch there, git am here), or "
                     "pass --allow-drift if they already are. A sync would otherwise revert them.")
        if since == ref_sha:
            sys.exit(f"Nothing to sync: the public repo is already at {ref_sha[:10]}")
        work = f"sync/{datetime.date.today().isoformat()}-{ref_sha[:8]}"
        git("switch", "-c", work, branch, cwd=clone)
    else:
        if git("rev-parse", "--verify", "--quiet", f"refs/remotes/origin/{branch}", cwd=clone, check=False):
            sys.exit(f"origin/{branch} exists but local {branch} does not: check it out first")
        work = branch
        if git("rev-parse", "--verify", "--quiet", "HEAD", cwd=clone, check=False):
            sys.exit(f"{clone} has commits on another branch but no {branch}: refusing to guess")
        git("symbolic-ref", "HEAD", f"refs/heads/{branch}", cwd=clone)

    for name in os.listdir(clone):
        if name == ".git":
            continue
        p = os.path.join(clone, name)
        shutil.rmtree(p) if os.path.isdir(p) and not os.path.islink(p) else os.remove(p)
    shutil.copytree(dest, clone, symlinks=True, dirs_exist_ok=True)
    # -f: the export is exact, so a path its own .gitignore matches is still part of it.
    git("add", "-A", "-f", cwd=clone)
    staged = len(git("ls-files", "-z", cwd=clone).split("\0")) - 1
    exported = sum(1 for _ in walk_files(dest))
    message = summary_message(ref_sha, since, rules)
    fatal = [] if staged == exported else [f"staged {staged} files, exported {exported}"]
    fatal += message_findings(message, rules, os.path.dirname(dest))
    for var in ("GIT_AUTHOR_IDENT", "GIT_COMMITTER_IDENT"):
        ident = git("var", var, cwd=clone)
        fatal += [f"{var}: forbidden string ({f.get('why', '')})" for f in rules.get("forbid", [])
                  if re.search(f["pattern"], ident)]
    if fatal:
        git("reset", "--hard", "-q", cwd=clone)
        sys.exit("\n".join(fatal))
    subprocess.run(["git", "commit", "-q", "-F", "-"], input=message, text=True, cwd=clone, check=True)
    count = git("rev-list", "--count", "HEAD", cwd=clone)
    print(f"\nCommitted to {clone} on {work} ({count} commit(s) on that branch).")
    print("Next, by hand:\n"
          f"  (cd {clone} && scripts/run-gate.sh)        # the export must build on its own\n"
          f"  git -C {clone} push -u origin {work}")
    if has_base:
        print(f"  (cd {clone} && gh pr create --base {branch} --fill)")


# ---------------------------------------------------------------- main


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--ref", default="HEAD", help="internal commit to export (default HEAD)")
    ap.add_argument("--rules", default=RULES_PATH)
    ap.add_argument("--keep", metavar="DIR", help="leave the cleaned tree in DIR (must not exist)")
    ap.add_argument("--publish", metavar="CLONE", help="commit the export into this public clone")
    ap.add_argument("--allow-drift", action="store_true")
    ap.add_argument("--no-gitleaks", action="store_true", help="tests only; never with --publish")
    a = ap.parse_args(argv)
    if a.publish and a.no_gitleaks:
        sys.exit("--no-gitleaks cannot be combined with --publish")

    with open(a.rules) as f:
        rules = json.load(f)
    ref_sha = git("rev-parse", f"{a.ref}^{{commit}}")
    if a.ref == "HEAD" and git("status", "--porcelain", "--untracked-files=no"):
        print("note: uncommitted changes are not exported; only the committed tree is", file=sys.stderr)

    scratch = tempfile.mkdtemp(prefix="public-export-")
    try:
        dest = os.path.join(scratch, "tree")
        os.mkdir(dest)
        paths = build(ref_sha, dest, rules)
        fatal, review = scan(dest, paths, rules)
        if not a.no_gitleaks:
            fatal += gitleaks(dest, rules, scratch)
        missing = [p for p in rules.get("require_files", []) if not os.path.exists(os.path.join(dest, p))]

        print(f"Exported {len(paths)} files from {ref_sha[:10]}.")
        for r in review:
            print(f"review  {r}")
        for m in missing:
            print(f"{'FATAL' if a.publish else 'missing'} {m}: required before publishing")
        for x in fatal:
            print(f"FATAL   {x}")
        if a.keep:
            shutil.copytree(dest, a.keep, symlinks=True)
            print(f"Cleaned tree left in {a.keep}")
        if fatal or (a.publish and missing):
            print(f"\n{len(fatal) + (len(missing) if a.publish else 0)} fatal finding(s): nothing published.")
            return 1
        if a.publish:
            publish(dest, os.path.abspath(a.publish), ref_sha, rules, a.allow_drift)
        else:
            print("\nDry run clean." + (f" {len(review)} item(s) to review." if review else ""))
        return 0
    finally:
        shutil.rmtree(scratch, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
