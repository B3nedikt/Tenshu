#!/usr/bin/env python3
"""Keep binaries out of store/ (store-presence-and-support-pages.md §3.3).

Nothing binary lives in git under store/: the screenshots are generated and published to R2
(scripts/store-publish.sh), and the fixture photos are fetched by URL + sha256 or generated
(store/fixtures/generate.py). What store/ holds is text: manifests, listing copy, generators.

Checks the git INDEX — every committed file plus everything staged, so `git add -f` of a gitignored
capture is caught before the commit, not after:

  * only an allow-listed text extension (ALLOWED) may be under store/;
  * no generated path (store/<app>/screenshots/) at all;
  * a text file must actually be text (no NUL byte) and under MAX_BYTES;
  * a screenshots manifest's keys are `<platform>/<device>/<lang>/NN-screen` (light, the published
    form) or `<platform>/<device>/<lang>/dark/NN-screen` (dark) — the forms the support-page generator
    resolves (MANIFEST_KEY_RE in web/apex/support-pages/generate.ts); a `screens.json` names only
    known appearances, light among them;
  * a `screenshots.webp.json` (the website's WebP derivatives, scripts/lib/store_webp.py) is keyed by
    source sha256s, each with its derivatives' width, height, sha256 and bytes.

Usage:  scripts/check-store-assets.py          (also run by .githooks/pre-commit when store/ is staged,
                                                 and by both store scripts)
"""
import json
import os
import re
import subprocess
import sys

# `.csv`: the Play Data safety answers (store/<app>/play/data-safety.csv) — the format Play imports.
ALLOWED = {".json", ".py", ".md", ".txt", ".csv"}
# Generous for a manifest or a listing's copy, absurd for anything a person would type: a text file
# this big is data that belongs in R2.
MAX_BYTES = 256 * 1024
GENERATED = re.compile(r"^store/[^/]+/screenshots/")
MANIFEST = re.compile(r"^store/[^/]+/screenshots\.manifest\.json$")
WEBP_SIDECAR = re.compile(r"^store/[^/]+/screenshots\.webp\.json$")
SCREENS = re.compile(r"^store/[^/]+/screens\.json$")
# ⚠ Keep in step with MANIFEST_KEY_RE (web/apex/support-pages/generate.ts) and store-publish.sh's
# APPEARANCE_DIRS. Light has no segment, so the keys published before dark existed stay valid.
MANIFEST_KEY = re.compile(r"^[a-z]+/[a-z0-9]+/[a-z]{2}(-[A-Za-z]+)?(/dark)?/\d\d-[a-z0-9-]+$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
APPEARANCES = {"light", "dark"}


def shape_problems(path, text):
    """What is wrong with a manifest's keys or a screens.json's appearances; [] if nothing."""
    try:
        doc = json.loads(text)
    except ValueError as e:
        return [f"{path}: not JSON ({e})"]
    problems = []
    if MANIFEST.match(path):
        for key, entry in (doc.get("screens") or {}).items():
            if not MANIFEST_KEY.match(key):
                problems.append(f"{path}: key '{key}' is neither <platform>/<device>/<lang>/NN-screen "
                                f"nor <platform>/<device>/<lang>/dark/NN-screen")
            elif not (isinstance(entry, dict) and SHA256.match(str(entry.get("sha256")))):
                problems.append(f"{path}: '{key}' has no sha256")
    if WEBP_SIDECAR.match(path):
        for src, variants in (doc.get("derivatives") or {}).items():
            ok = SHA256.match(src) and isinstance(variants, list) and all(
                isinstance(v, dict)
                and SHA256.match(str(v.get("sha256")))
                and all(isinstance(v.get(k), int) and v[k] > 0 for k in ("width", "height", "bytes"))
                for v in variants
            )
            if not ok:
                problems.append(f"{path}: derivatives of '{src}' are not [{{width, height, sha256, bytes}}] under a sha256 key")
    if SCREENS.match(path):
        appearances = doc.get("appearances", ["light"])
        if not isinstance(appearances, list) or "light" not in appearances or not set(appearances) <= APPEARANCES:
            problems.append(f"{path}: appearances must list light and only {sorted(APPEARANCES)}, got {appearances!r}")
    return problems


def git(*args, data=None):
    return subprocess.run(["git", *args], input=data, capture_output=True, check=True).stdout


def main():
    root = git("rev-parse", "--show-toplevel").decode().strip()
    os.chdir(root)
    entries = git("ls-files", "-s", "-z", "--", "store").split(b"\0")
    problems = []
    checked = 0
    for raw in filter(None, entries):
        meta, path = raw.decode().split("\t", 1)
        blob = meta.split()[1]
        checked += 1
        ext = os.path.splitext(path)[1].lower()
        if GENERATED.match(path):
            problems.append(f"{path}: generated output — publish it (scripts/store-publish.sh), never commit it")
            continue
        if ext not in ALLOWED:
            problems.append(f"{path}: '{ext or 'no extension'}' is not allowed under store/ "
                            f"(only {', '.join(sorted(ALLOWED))}) — host the file on R2 and reference it by URL + sha256")
            continue
        size = int(git("cat-file", "-s", blob))
        if size > MAX_BYTES:
            problems.append(f"{path}: {size} bytes, over the {MAX_BYTES}-byte limit for a text file under store/")
            continue
        content = git("cat-file", "blob", blob)
        if b"\0" in content:
            problems.append(f"{path}: contains a NUL byte — binary content with a text extension")
            continue
        if MANIFEST.match(path) or SCREENS.match(path) or WEBP_SIDECAR.match(path):
            problems.extend(shape_problems(path, content.decode("utf-8")))
    if problems:
        print("check-store-assets: problems in store/ (docs/how-to/store-screenshots.md §3):", file=sys.stderr)
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        print("Unstage with: git rm --cached <path>", file=sys.stderr)
        return 1
    print(f"check-store-assets: ok ({checked} files under store/, all text)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
