#!/usr/bin/env python3
"""Draw the faces an animal-veto pass would remove, for the by-eye check M5 is (`photos-pets.md` §7, §10.4).

Reads `faces.tsv` and `pairs.tsv` from `VisionAnimalVetoMeasurementTest` (iOS) or
`AnimalVetoMeasurementTest` (Android), and for every photograph with a removed face writes it upright,
at most 1024 px, with the removed faces in red and every overlapping cat or dog box in green.

    python3 scripts/inference/draw-veto-removals.py --report <out dir> --photos <photo dir> \\
        [--orientations <"name orientation" file>] --to <dir>

⚠ Boxes are fractions of the UPRIGHT image, so the photo is turned the way the pass turned it: the
manifest's orientation when it names the file (a preview carries no EXIF), else the file's own EXIF.
⚠⚠ The photographs are the operator's own. Nothing this writes belongs in the repository.
"""

import argparse
import collections
import os

TRANSPOSE = {2: "FLIP_LEFT_RIGHT", 3: "ROTATE_180", 4: "FLIP_TOP_BOTTOM", 5: "TRANSPOSE", 6: "ROTATE_270", 7: "TRANSVERSE", 8: "ROTATE_90"}


def rows(path):
    with open(path, encoding="utf-8") as handle:
        return [line.split() for line in handle if line.strip() and not line.startswith("#")]


def upright(path, orientation):
    from PIL import Image, ImageOps

    image = Image.open(path)
    if orientation is None:
        image = ImageOps.exif_transpose(image)
    elif orientation in TRANSPOSE:
        image = image.transpose(getattr(Image.Transpose, TRANSPOSE[orientation]))
    return image.convert("RGB")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--report", required=True)
    parser.add_argument("--photos", required=True)
    parser.add_argument("--orientations")
    parser.add_argument("--to", required=True)
    args = parser.parse_args()
    from PIL import ImageDraw

    orientations = {}
    if args.orientations:
        for parts in rows(args.orientations):
            orientations[parts[0]] = int(parts[1])
    removed = collections.defaultdict(list)
    for parts in rows(os.path.join(args.report, "faces.tsv")):
        if parts[9] == "true":
            removed[parts[0]].append(tuple(map(float, parts[1:5])))
    animals = collections.defaultdict(set)
    # ⚠ Optional: the first Android run (2026-11-18) predates pairs.tsv, and the drawing does without labels.
    pairs = os.path.join(args.report, "pairs.tsv")
    for parts in rows(pairs) if os.path.isfile(pairs) else []:
        animals[parts[0]].add((parts[6], float(parts[7])))
    os.makedirs(args.to, exist_ok=True)
    for index, (name, boxes) in enumerate(sorted(removed.items()), 1):
        # ⚠ `name` is relative to the photo root and may carry subdirectories (the Android harness walks
        # them); only the orientation manifest is keyed by the bare file name.
        image = upright(os.path.join(args.photos, name), orientations.get(os.path.basename(name)))
        scale = min(1.0, 1024 / max(image.size))
        image = image.resize((max(1, int(image.width * scale)), max(1, int(image.height * scale))))
        draw = ImageDraw.Draw(image)
        for left, top, right, bottom in boxes:
            draw.rectangle([left * image.width, top * image.height, right * image.width, bottom * image.height], outline=(255, 0, 0), width=4)
        label = ", ".join(f"{species} {confidence:.2f}" for species, confidence in sorted(animals[name], key=lambda a: -a[1]))
        draw.text((8, 8), label, fill=(0, 255, 0))
        image.save(os.path.join(args.to, f"{index:03d}-{os.path.splitext(name)[0].replace(os.sep, '_')}.jpg"), quality=85)
    print(f"{sum(len(b) for b in removed.values())} removed faces in {len(removed)} photographs → {args.to}")


if __name__ == "__main__":
    main()
