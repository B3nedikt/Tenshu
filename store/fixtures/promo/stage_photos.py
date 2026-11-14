#!/usr/bin/env python3
"""Stage the maintainer's own photos for the promo realm (docs/how-to/store-screenshots.md §9,
"The promo set"): the input of scripts/store-screenshots-android.sh --set promo.

    python3 store/fixtures/promo/stage_photos.py              # from ~/Movies/Tenshu promo/photos
    python3 store/fixtures/promo/stage_photos.py --src DIR --upload IMG_1234   # the photo uploaded on camera

Reads <src>/<Album>/<photo> (JPG, JPEG, PNG or HEIC) — photos loose in <src> form one album named
--loose-album — and writes, under build/store/fixture/:
  promo/<Album>/<name>.jpg   the seed: every photo but the held-back ones
  promo-upload/<Album>/<name>.jpg  the photo uploaded on camera (--upload, else the newest of the
                             first album), held back from the seed; the walk uploads it into <Album>
  promo-backup/<name>.jpg    with --backup: a photo held back from the seed AND the upload, so it is
                             new to the realm when the walk drops it into a "Chat" folder for
                             Synced Folders (a copy of anything already in the realm is skipped as
                             already held, so it would back up nothing)
  promo-review/sheet-N.jpg   numbered contact sheets, to check every photo by eye before a run

Each photo is converted to JPEG, scaled down to a 2400 px long edge, and stripped of every metadata
tag but its capture date, orientation and colour profile — ⚠ no GPS, no camera serial, no owner
name, no time-zone offset leaves this Mac.
The file's mtime is set to the capture date: Drive shows it as the file's date, and Photos groups
by it. Needs `sips` (macOS) and `exiftool`.

⚠ Nothing here is ever committed (build/ is gitignored), and the photos are the maintainer's own:
the review sheets exist so a person on a photo is caught before it reaches a realm or a video.
"""
import argparse
import os
import shutil
import subprocess
import sys
from datetime import datetime

from PIL import Image, ImageDraw, ImageOps

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "build", "store", "fixture")
KINDS = (".jpg", ".jpeg", ".png", ".heic")


def run(*cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"stage-photos: {' '.join(cmd)} failed: {r.stderr.strip()}")
    return r.stdout


def capture_date(path):
    out = run("exiftool", "-s3", "-d", "%Y:%m:%d %H:%M:%S", "-DateTimeOriginal", "-CreateDate", path).split("\n")
    for line in out:
        if line.strip():
            return datetime.strptime(line.strip(), "%Y:%m:%d %H:%M:%S")
    return datetime.fromtimestamp(os.path.getmtime(path))


class _NoImage:
    """Pillow cannot open HEIC: treat it as large (sips scales it down)."""
    def __enter__(self):
        return None

    def __exit__(self, *exc):
        return False


def stage(src, dest):
    """SRC → a clean JPEG at DEST; returns its capture date."""
    when = capture_date(src)
    with Image.open(src) if not src.lower().endswith(".heic") else _NoImage() as im:
        small = im is not None and max(im.size) <= 2400
    resize = [] if small else ["-Z", "2400"]  # down only: a small photo stays its size
    run("sips", "-s", "format", "jpeg", "-s", "formatOptions", "88", *resize, src, "--out", dest)
    # The colour profile stays (an iPhone photo is Display P3; without it the colours go flat).
    run("exiftool", "-q", "-overwrite_original", "-all=", "-tagsfromfile", "@",
        "-DateTimeOriginal", "-CreateDate", "-Orientation", "-ICC_Profile", dest)
    leftover = run("exiftool", "-s", "-G", "-gps:all", "-SerialNumber", "-OwnerName", "-Artist", dest).strip()
    if leftover:
        sys.exit(f"stage-photos: {dest} still carries {leftover}")
    stamp = when.timestamp()
    os.utime(dest, (stamp, stamp))
    return when


def contact_sheets(entries, out_dir, per_sheet=20, cell=360):
    os.makedirs(out_dir, exist_ok=True)
    for start in range(0, len(entries), per_sheet):
        chunk = entries[start:start + per_sheet]
        cols = 5
        rows = (len(chunk) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * cell, rows * (cell + 30)), "white")
        draw = ImageDraw.Draw(sheet)
        for i, (label, path) in enumerate(chunk):
            img = ImageOps.exif_transpose(Image.open(path))
            img.thumbnail((cell - 10, cell - 10))
            x, y = (i % cols) * cell, (i // cols) * (cell + 30)
            sheet.paste(img, (x + (cell - img.width) // 2, y + (cell - img.height) // 2))
            draw.text((x + 6, y + cell + 6), f"{start + i + 1}. {label}", fill="black")
        sheet.save(os.path.join(out_dir, f"sheet-{start // per_sheet + 1}.jpg"), quality=80)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--src", default=os.path.expanduser("~/Movies/Tenshu promo/photos"))
    p.add_argument("--loose-album", default="Photos", help="the album photos loose in --src go into")
    p.add_argument("--upload", help="the photo uploaded on camera, by file name with or without extension")
    p.add_argument("--backup", help="the photo Synced Folders backs up on camera, by file name")
    args = p.parse_args()
    if not os.path.isdir(args.src):
        sys.exit(f"stage-photos: no {args.src} — put the photos there, one folder per album")
    for tool in ("sips", "exiftool"):
        if not shutil.which(tool):
            sys.exit(f"stage-photos: needs {tool}")
    folders = {d: os.path.join(args.src, d) for d in os.listdir(args.src)
               if os.path.isdir(os.path.join(args.src, d)) and not d.startswith(".")}
    if any(os.path.splitext(f)[1].lower() in KINDS for f in os.listdir(args.src)):
        folders.setdefault(args.loose_album, args.src)
    albums = sorted(folders)
    if not albums:
        sys.exit(f"stage-photos: {args.src} has no photos")

    seed, upload, backup, review = (os.path.join(OUT, d) for d in ("promo", "promo-upload", "promo-backup", "promo-review"))
    for d in (seed, upload, backup, review):
        shutil.rmtree(d, ignore_errors=True)
    os.makedirs(upload)

    staged = []  # (album, name, date, path)
    for album in albums:
        os.makedirs(os.path.join(seed, album))
        for f in sorted(os.listdir(folders[album])):
            if os.path.splitext(f)[1].lower() not in KINDS:
                continue
            name = os.path.splitext(f)[0] + ".jpg"
            dest = os.path.join(seed, album, name)
            staged.append((album, name, stage(os.path.join(folders[album], f), dest), dest))
    if args.upload:
        want = os.path.splitext(args.upload)[0] + ".jpg"
        held = [s for s in staged if s[1] == want]
        if not held:
            sys.exit(f"stage-photos: no photo {args.upload} in {args.src}")
    else:
        first = next((a for a in albums if any(s[0] == a for s in staged)), None)
        if first is None:
            sys.exit(f"stage-photos: {args.src} has no photos")
        held = sorted((s for s in staged if s[0] == first), key=lambda s: s[2])[-1:]
    final = {path: path for *_, path in staged}
    for album, name, _, path in held:
        os.makedirs(os.path.join(upload, album), exist_ok=True)
        final[path] = os.path.join(upload, album, name)
        shutil.move(path, final[path])
    if args.backup:
        want = os.path.splitext(args.backup)[0] + ".jpg"
        backed = [s for s in staged if s[1] == want and final[s[3]] == s[3]]
        if not backed:
            sys.exit(f"stage-photos: no photo {args.backup} in {args.src}, or it is the --upload one")
        os.makedirs(backup)
        _, name, _, path = backed[0]
        final[path] = os.path.join(backup, name)
        shutil.move(path, final[path])
    contact_sheets([(f"{a}/{n}", final[path]) for a, n, _, path in staged], review)
    albums = [a for a in albums if os.listdir(os.path.join(seed, a)) or os.rmdir(os.path.join(seed, a))]

    for album in albums:
        print(f"{album}: {len(os.listdir(os.path.join(seed, album)))} photos")
    print(f"uploaded on camera: {held[0][0]}/{held[0][1]}")
    if args.backup:
        print(f"backed up on camera: {os.listdir(backup)[0]}")
    print(f"review sheets: {review}")


if __name__ == "__main__":
    main()
