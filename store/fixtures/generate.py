#!/usr/bin/env python3
"""Build the store-screenshot fixture tree for one app (docs/how-to/store-screenshots.md §3).

    python3 store/fixtures/generate.py drive build/store/fixture/drive
    python3 store/fixtures/generate.py drive --dry-run      # validate + plan; no network, no writes

Reads store/fixtures/<app>/manifest.json and writes one file per entry under <out>, with the
entry's `modified` time as the file's mtime (the app shows it as the file's date).

⚠⚠ No fixture file lives in git (store-presence-and-support-pages.md §3.3). Every entry says how to
GET its file, in exactly one of two ways:
  * "generate": "placeholder" — drawn here: a labelled gradient for a photo, a one-page PDF, a short
    text file. It says it is a placeholder, and its license must be `generated-placeholder`, so
    nobody mistakes it for content we hold the rights to.
  * "fetch": {"url": ..., "sha256": ...} — downloaded into build/store/fixture-cache/<sha256> (once;
    later runs reuse the cache) and refused LOUDLY if its bytes do not hash to the pinned sha256.
    `https://` is fetched directly; `s3://<bucket>/<key>` (the PRIVATE fixtures bucket,
    deploy/cloudflare-assets) through the aws CLI with the R2 key pair. Its license must be a real
    one, with source/author/url naming where it came from.

Needs Pillow for photos (`pip install pillow`). Deterministic: the same manifest gives the same bytes.
"""
import hashlib
import json
import os
import random
import re
import shutil
import subprocess
import sys
import time
import urllib.request
from datetime import datetime

PLACEHOLDER = "generated-placeholder"
HERE = os.path.dirname(os.path.abspath(__file__))

FONT_CANDIDATES = [
    "/System/Library/Fonts/Avenir Next.ttc",
    "/System/Library/Fonts/HelveticaNeue.ttc",
    "/System/Library/Fonts/Helvetica.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
]

TEXTS = {
    "Shakshuka": [
        "Shakshuka (serves 2)",
        "",
        "- 1 onion, 1 red pepper, 2 cloves garlic",
        "- 1 tin chopped tomatoes",
        "- 1 tsp cumin, 1 tsp paprika, pinch of chilli",
        "- 4 eggs, feta, parsley",
        "",
        "Soften onion and pepper, add garlic and spices, then the tomatoes.",
        "Simmer 10 minutes. Make four wells, crack in the eggs, cover until set.",
        "Crumble feta over, scatter parsley, serve with bread.",
    ],
    "Sourdough starter": [
        "Sourdough starter feeding",
        "",
        "Day 1-5: 50 g flour + 50 g water, discard half every 24 h.",
        "Ready when it doubles within 6 hours of feeding.",
        "Keep in the fridge, feed once a week.",
    ],
    "Moving checklist": [
        "Moving checklist",
        "",
        "[x] Give notice on the flat",
        "[x] Book the van for the 31st",
        "[ ] Change address: bank, insurance, employer",
        "[ ] Transfer electricity and internet",
        "[ ] Measure the new living room for the sofa",
        "[ ] Return keys",
    ],
}


def die(message):
    sys.stderr.write(f"generate.py: {message}\n")
    sys.exit(1)


def font(size):
    from PIL import ImageFont

    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                continue
    return ImageFont.load_default()


def hex_rgb(value):
    value = value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


def draw_photo(entry, out_path):
    """A 4:3 photo-sized JPEG: a diagonal two-colour gradient, soft shapes, a label, and a
    small 'PLACEHOLDER' mark. Sized like a phone photo so sizes in the listing look real."""
    from PIL import Image, ImageDraw, ImageFilter

    width, height = 2016, 1512
    seed = sum(ord(c) for c in entry["path"])
    rng = random.Random(seed)
    a, b = (hex_rgb(c) for c in entry.get("palette", ["#334155", "#94a3b8"]))

    # Gradient at a quarter size, upscaled: smooth, and fast without numpy.
    small = Image.new("RGB", (width // 4, height // 4))
    sw, sh = small.size
    px = small.load()
    for y in range(sh):
        for x in range(sw):
            t = (x / sw * 0.6 + y / sh * 0.4)
            px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))
    image = small.resize((width, height), Image.BICUBIC)

    # A few large blurred discs give it depth, like out-of-focus light.
    overlay = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    for _ in range(6):
        r = rng.randint(180, 520)
        cx, cy = rng.randint(0, width), rng.randint(0, height)
        tone = (255, 255, 255, rng.randint(24, 60)) if rng.random() < 0.6 else (0, 0, 0, rng.randint(20, 50))
        od.ellipse((cx - r, cy - r, cx + r, cy + r), fill=tone)
    overlay = overlay.filter(ImageFilter.GaussianBlur(60))
    image = Image.alpha_composite(image.convert("RGBA"), overlay)

    d = ImageDraw.Draw(image)
    label_font = font(150)
    box = d.textbbox((0, 0), entry["label"], font=label_font)
    tw, th = box[2] - box[0], box[3] - box[1]
    d.text(((width - tw) / 2, (height - th) / 2 - 40), entry["label"], font=label_font, fill=(255, 255, 255, 235))
    mark_font = font(44)
    d.text((56, height - 100), "PLACEHOLDER IMAGE", font=mark_font, fill=(255, 255, 255, 170))

    # Fine grain so the JPEG compresses like a photograph, not like a flat graphic.
    grain = Image.effect_noise((width, height), 18).convert("RGBA")
    grain.putalpha(18)
    image = Image.alpha_composite(image, grain).convert("RGB")
    image.save(out_path, "JPEG", quality=88)


def pdf_escape(text):
    return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def draw_pdf(entry, out_path):
    """A one-page A4 PDF: a coloured header band, the title, a 'placeholder' line, and grey
    bars where the body text would be. Hand-written PDF with a correct xref, no dependency."""
    rng = random.Random(sum(ord(c) for c in entry["path"]))
    ops = [
        "0.36 0.29 0.59 rg 0 762 595 80 re f",
        "BT /F2 26 Tf 1 1 1 rg 48 790 Td (" + pdf_escape(entry["label"]) + ") Tj ET",
        "BT /F1 11 Tf 0.4 0.4 0.45 rg 48 730 Td (Placeholder document generated for the Tenshu store screenshots.) Tj ET",
    ]
    y = 690
    while y > 120:
        if rng.random() < 0.12:
            y -= 22  # paragraph break
            continue
        w = rng.randint(300, 499)
        ops.append(f"0.85 0.86 0.88 rg 48 {y} {w} 9 re f")
        y -= 18
    content = "\n".join(ops)
    objects = [
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R "
        "/Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> >>",
        f"<< /Length {len(content)} >>\nstream\n{content}\nendstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>",
    ]
    out = "%PDF-1.4\n"
    offsets = []
    for i, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n{body}\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n"
    out += "".join(f"{o:010d} 00000 n \n" for o in offsets)
    out += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n"
    with open(out_path, "w", encoding="latin-1") as f:
        f.write(out)


def draw_text(entry, out_path):
    lines = TEXTS.get(entry["label"], [entry["label"], "", "Placeholder text."])
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


DRAW = {"photo": draw_photo, "pdf": draw_pdf, "text": draw_text}


CACHE = os.path.join(HERE, "..", "..", "build", "store", "fixture-cache")
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def keychain(service):
    try:
        return subprocess.run(["security", "find-generic-password", "-a", "tenshu", "-s", service, "-w"],
                              capture_output=True, text=True).stdout.strip()
    except FileNotFoundError:
        return ""


def download(url, dest):
    """`url` → `dest`. https directly; s3:// through the aws CLI against R2 (the private bucket)."""
    if url.startswith("https://"):
        request = urllib.request.Request(url, headers={"User-Agent": "tenshu-store-fixtures"})
        with urllib.request.urlopen(request, timeout=60) as response, open(dest, "wb") as out:
            shutil.copyfileobj(response, out)
        return
    endpoint = os.environ.get("FIXTURES_ENDPOINT") or os.environ.get("ASSETS_ENDPOINT")
    if not endpoint:
        die(f"{url}: set FIXTURES_ENDPOINT (or ASSETS_ENDPOINT) to the R2 S3 endpoint")
    env = {k: v for k, v in os.environ.items()
           if k not in ("AWS_SESSION_TOKEN", "AWS_SECURITY_TOKEN", "AWS_PROFILE", "AWS_DEFAULT_PROFILE")}
    env["AWS_ACCESS_KEY_ID"] = os.environ.get("R2_ACCESS_KEY_ID") or keychain("r2-assets-access-key-id")
    env["AWS_SECRET_ACCESS_KEY"] = os.environ.get("R2_SECRET_ACCESS_KEY") or keychain("r2-assets-secret-access-key")
    if not env["AWS_ACCESS_KEY_ID"] or not env["AWS_SECRET_ACCESS_KEY"]:
        die(f"{url}: no R2 key pair (R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY or the Keychain's r2-assets-*)")
    done = subprocess.run(["aws", "--endpoint-url", endpoint, "--region", "auto", "s3", "cp", url, dest, "--only-show-errors"],
                          env=env, capture_output=True, text=True)
    if done.returncode != 0:
        die(f"{url}: download failed: {done.stderr.strip()}")


def fetched(entry, dry_run):
    """The verified cache path for a `fetch` entry — downloading it first if it is not cached."""
    fetch = entry["fetch"]
    sha, url = fetch["sha256"], fetch["url"]
    cached = os.path.join(CACHE, sha)
    if os.path.exists(cached) and sha256_of(cached) == sha:
        return cached, "cached"
    if dry_run:
        return None, "would download"
    os.makedirs(CACHE, exist_ok=True)
    partial = cached + ".part"
    try:
        download(url, partial)
        got = sha256_of(partial)
        if got != sha:
            die(f"{entry['path']}: {url} does NOT match its pinned sha256\n"
                f"    pinned: {sha}\n    got:    {got}\n"
                "  The file behind the URL changed (or the pin is wrong). Do not just re-pin: check the new "
                "file is the one the license covers, then update sha256 in the manifest.")
        os.replace(partial, cached)
    finally:
        if os.path.exists(partial):
            os.remove(partial)
    return cached, "downloaded"


def check_entry(entry):
    where = entry.get("path", "?")
    for key in ("path", "kind", "modified", "source", "license", "author", "url"):
        if key not in entry:
            die(f"{where}: manifest entry lacks '{key}'")
    if ("fetch" in entry) == ("generate" in entry):
        die(f"{where}: needs exactly one of \"generate\" or \"fetch\"")
    placeholder = entry["license"] == PLACEHOLDER
    if "generate" in entry:
        if entry["generate"] != "placeholder":
            die(f"{where}: unknown generate recipe '{entry['generate']}'")
        if not placeholder:
            die(f"{where}: a generated file must say license '{PLACEHOLDER}'")
        if entry["kind"] not in DRAW:
            die(f"{where}: no placeholder generator for kind '{entry['kind']}'")
    else:
        fetch = entry["fetch"]
        if not isinstance(fetch, dict) or not SHA256.match(str(fetch.get("sha256", ""))):
            die(f"{where}: fetch needs a lower-case hex sha256")
        if not str(fetch.get("url", "")).startswith(("https://", "s3://")):
            die(f"{where}: fetch url must be https:// or s3://")
        if placeholder:
            die(f"{where}: a fetched file needs its real source, license, author and url, not '{PLACEHOLDER}'")


def redated(manifest, app):
    """A set that is another set's files under other dates: {"from": "<set>", "modified": {path: iso}}.

    ⚠ The files, their sources, licences and sha256 pins stay in the one manifest they come from, so a
    re-pin is made once. Every path named must exist there, and every photo there must be named: a
    photo the base set gains later must get a date here too rather than keep the other set's.
    """
    base_path = os.path.join(HERE, manifest["from"], "manifest.json")
    if not os.path.exists(base_path):
        die(f"{app}: 'from' names no set ({base_path})")
    with open(base_path, encoding="utf-8") as f:
        base = json.load(f)
    dates = manifest.get("modified", {})
    paths = {e["path"] for e in base["files"]}
    unknown = sorted(set(dates) - paths)
    if unknown:
        die(f"{app}: redates paths {manifest['from']} does not have: {unknown}")
    undated = sorted(e["path"] for e in base["files"] if e["kind"] == "photo" and e["path"] not in dates)
    if undated:
        die(f"{app}: every {manifest['from']} photo needs a date here; missing: {undated}")
    for iso in dates.values():
        datetime.fromisoformat(iso)
    files = [dict(e, modified=dates.get(e["path"], e["modified"])) for e in base["files"]]
    return dict(manifest, files=files)


def main():
    args = [a for a in sys.argv[1:] if a != "--dry-run"]
    dry_run = len(args) != len(sys.argv) - 1
    if len(args) != (1 if dry_run else 2):
        die("usage: generate.py <app> <out-dir> | generate.py <app> --dry-run")
    app = args[0]
    manifest_path = os.path.join(HERE, app, "manifest.json")
    if not os.path.exists(manifest_path):
        die(f"no manifest at {manifest_path}")
    with open(manifest_path, encoding="utf-8") as f:
        manifest = json.load(f)
    if "from" in manifest:
        manifest = redated(manifest, app)
    for entry in manifest["files"]:
        check_entry(entry)

    if dry_run:
        for entry in manifest["files"]:
            how = f"generate {entry['kind']} placeholder" if "generate" in entry else fetched(entry, True)[1] + " " + entry["fetch"]["url"]
            print(f"  {entry['path']}: {how}")
        print(f"fixture {app}: {len(manifest['files'])} entries valid (dry run — nothing fetched or written)")
        return

    out_dir = args[1]
    if os.path.isdir(out_dir):
        shutil.rmtree(out_dir)
    os.makedirs(out_dir)

    real, generated = 0, 0
    for entry in manifest["files"]:
        target = os.path.join(out_dir, entry["path"])
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if "fetch" in entry:
            source, how = fetched(entry, False)
            shutil.copyfile(source, target)
            real += 1
        else:
            DRAW[entry["kind"]](entry, target)
            generated += 1
        stamp = time.mktime(datetime.fromisoformat(entry["modified"]).timetuple())
        os.utime(target, (stamp, stamp))

    print(f"fixture {app}: {real} fetched + {generated} placeholder files -> {out_dir}")


if __name__ == "__main__":
    main()
