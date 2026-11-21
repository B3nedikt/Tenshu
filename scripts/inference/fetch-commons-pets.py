#!/usr/bin/env python3
"""Build Pets' M2 evaluation set from Wikimedia Commons: individual named cats and dogs, several photos each.

`photos-pets.md` §7 M2 asks whether the embedder keeps one animal's photos together and different
animals apart. That needs several photos per individual, which Commons has under `Category:Individual
cats` / `Category:Individual dogs`: one subcategory per named animal, every file under an explicit free
licence.

    python3 scripts/inference/fetch-commons-pets.py --out ~/tenshu-models/m2-commons-<date>

Writes `<out>/<species>/<animal>/<n>.jpg` (Commons' **960 px** thumbnail — a standard step it keeps
ready-made; an odd width such as 1024 is rendered per request and answered with 429 almost at once) and `<out>/manifest.tsv`
— one row per photo: species, animal, file, Commons title, licence, artist, source URL.

⚠⚠ **Evaluation only.** These photos are measured on locally and never trained on, shipped or committed
(PT-D10 keeps training data to consented photos). The manifest is what makes each one's licence and
author checkable later.
⚠ Containers ("Cats of Wikipedians", "Famous dogs", "… by name") are walked into; a category naming
several animals ("A and B (cats)", "A, B, …") is skipped, because its photos have no single identity.
⚠ Only standard library, and a generic User-Agent with no personal details.
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://commons.wikimedia.org/w/api.php"
AGENT = "TenshuM2Fetch/0.1 (evaluation-set script)"
ROOTS = {"cat": "Category:Individual cats", "dog": "Category:Individual dogs"}
CONTAINER = re.compile(r"\b(of Wikipedians|Famous|Fictional|by name|by named|Assistance|owners|actors?|statues?|sculptures?|memorials?|mascots?)\b", re.I)
GROUP = re.compile(r"(\band\b|,|&|\(cats\)|\(dogs\))", re.I)
# ⚠⚠ Kept only when NAMED like one animal — "Rambo (cat)", "Larry the cat", "Bo (dog)" — or in [FAMOUS].
# Skipping collection-looking names was not enough (2026-11-18): "Cat actors", "Cat mascots" and "Cat
# advertisement models" each got in as one cat, which poisons every same-animal pair they form.
INDIVIDUAL = re.compile(r"(\((?:[^()]*\b)?(cat|dog|kitten|puppy)\b[^()]*\)|\bthe (cat|dog)$)", re.I)
FAMOUS = {"Grumpy Cat", "Lil Bub", "Maru (cat)", "Hamilton the Hipster Cat", "Butler Blue"}
# ⚠ Named like one animal, but checked by eye to be no photos of it: Trim's are statues and plaques,
# Foss's are Edward Lear's drawings, "The Cat in the Hat" is Dr. Seuss, Filemon is statues of a cartoon, Alpha is several cats. Same-statue pairs flatter any embedder.
EXCLUDED = {"Trim (cat)", "Foss (cat)", "The Cat in the Hat", "The Adventures of Filemon the Cat", "Alpha (cat)"}
EXTENSIONS = (".jpg", ".jpeg", ".png")


PAUSE = 1.0  # seconds between every request: Commons answers 429 to a faster script (2026-11-18)


def fetch(url, timeout=60):
    """GET [url] politely: one request per [PAUSE], and on 429/503 wait what `Retry-After` says (or back off)."""
    for attempt in range(8):
        time.sleep(PAUSE)
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": AGENT}), timeout=timeout) as response:
                return response.read()
        except urllib.error.HTTPError as failure:
            if failure.code not in (429, 503) or attempt == 7:
                raise
            wait = failure.headers.get("Retry-After")
            time.sleep(float(wait) if wait and wait.isdigit() else 10 * 2 ** attempt)
        except urllib.error.URLError:
            if attempt == 7:
                raise
            time.sleep(5 * 2 ** attempt)


def api(**params):
    params.update(format="json", formatversion="2", maxlag="5")
    return json.loads(fetch(API + "?" + urllib.parse.urlencode(params)))


def members(category, kind):
    out, cont = [], {}
    while True:
        page = api(action="query", list="categorymembers", cmtitle=category, cmtype=kind, cmlimit="500", **cont)
        out += [m["title"] for m in page["query"]["categorymembers"]]
        if "continue" not in page:
            return out
        cont = {"cmcontinue": page["continue"]["cmcontinue"]}


def individuals(root, depth=2):
    """Every individual's category under [root], walking containers up to [depth] levels."""
    found = []
    for sub in members(root, "subcat"):
        name = sub.split(":", 1)[1]
        if CONTAINER.search(name):
            if depth > 0:
                found += individuals(sub, depth - 1)
        elif name not in EXCLUDED and not GROUP.search(name) and (INDIVIDUAL.search(name) or name in FAMOUS):
            found.append(sub)
    return list(dict.fromkeys(found))


def photos_of(category):
    """The animal's photo titles: its own files plus one level of its subcategories (e.g. "Larry in 2015")."""
    titles = [t for t in members(category, "file") if t.lower().endswith(EXTENSIONS)]
    for sub in members(category, "subcat"):
        titles += [t for t in members(sub, "file") if t.lower().endswith(EXTENSIONS)]
    return list(dict.fromkeys(titles))


def info(titles):
    page = api(action="query", prop="imageinfo", titles="|".join(titles), iiprop="url|extmetadata|mime", iiurlwidth="960")
    out = {}
    for p in page["query"]["pages"]:
        ii = (p.get("imageinfo") or [None])[0]
        if not ii:
            continue
        meta = ii.get("extmetadata", {})
        strip = lambda v: re.sub(r"<[^>]+>", "", (meta.get(v, {}) or {}).get("value", "")).strip().replace("\t", " ").replace("\n", " ")
        out[p["title"]] = {
            "thumb": ii.get("thumburl") or ii["url"],
            "page": ii.get("descriptionurl", ""),
            "licence": strip("LicenseShortName"),
            "artist": strip("Artist"),
            "mime": ii.get("mime", ""),
        }
    return out


FREE = re.compile(r"^(CC0|CC BY(-SA)?( \d\.\d)?|Public domain|PD.*|GFDL.*|Attribution|FAL)", re.I)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--out", required=True)
    parser.add_argument("--min-photos", type=int, default=5)
    parser.add_argument("--max-photos", type=int, default=12)
    parser.add_argument("--max-animals", type=int, default=60)
    args = parser.parse_args()
    out = os.path.expanduser(args.out)
    rows = ["species\tanimal\tfile\ttitle\tlicence\tartist\tsource"]

    def write_manifest():
        # ⚠ After every animal, not once at the end: a stopped run (the usual end, under Commons' 429s)
        # must still leave the manifest the evaluator selects by, or a stale directory gets scored.
        os.makedirs(out, exist_ok=True)
        with open(os.path.join(out, "manifest.tsv"), "w", encoding="utf-8") as handle:
            handle.write("\n".join(rows) + "\n")
    for species, root in ROOTS.items():
        kept = 0
        for category in individuals(root):
            if kept >= args.max_animals:
                break
            titles = photos_of(category)
            if len(titles) < args.min_photos:
                continue
            animal = re.sub(r"[^A-Za-z0-9]+", "_", category.split(":", 1)[1]).strip("_")
            directory = os.path.join(out, species, animal)
            # ⚠ Resumable: an animal fetched by an earlier run keeps its photos and manifest rows.
            done = os.path.join(directory, ".done")
            if os.path.isfile(done):
                with open(done, encoding="utf-8") as handle:
                    rows += [line.rstrip("\n") for line in handle if line.strip()]
                kept += 1
                write_manifest()
                print(f"{species} {animal}: kept from an earlier run", flush=True)
                continue
            meta = {}
            for i in range(0, min(len(titles), 3 * args.max_photos), 10):
                meta.update(info(titles[i:i + 10]))
            usable = [t for t in titles if t in meta and FREE.search(meta[t]["licence"]) and meta[t]["mime"] == "image/jpeg"]
            if len(usable) < args.min_photos:
                continue
            os.makedirs(directory, exist_ok=True)
            saved = 0
            mine = []
            for title in usable[: args.max_photos]:
                m = meta[title]
                name = f"{saved + 1:02d}.jpg"
                try:
                    body = fetch(m["thumb"], timeout=120)
                except Exception as failure:  # noqa: BLE001 — one missing photo is not a failed set
                    print(f"  skip {title}: {failure}", file=sys.stderr)
                    continue
                with open(os.path.join(directory, name), "wb") as f:
                    f.write(body)
                mine.append("\t".join([species, animal, name, title, m["licence"], m["artist"], m["page"]]))
                saved += 1
            if saved >= args.min_photos:
                rows += mine
                with open(done, "w", encoding="utf-8") as handle:
                    handle.write("\n".join(mine) + "\n")
                kept += 1
                write_manifest()
                print(f"{species} {animal}: {saved} photos", flush=True)
            else:
                print(f"{species} {animal}: only {saved} downloaded, dropped", flush=True)
                for leftover in os.listdir(directory):
                    os.remove(os.path.join(directory, leftover))
                os.rmdir(directory)
        print(f"== {species}: {kept} individuals", flush=True)
    write_manifest()


if __name__ == "__main__":
    main()
