#!/usr/bin/env python3
"""Run the staged promo photos through macOS desktop's People pipeline and print the groups it makes
(docs/proposals/store-screenshot-stories.md §4.1a, "Before capturing").

    ~/tenshu-models/venv/bin/python -I store/fixtures/people/check_people.py

The pipeline is desktop's own, ported: Apple Vision landmarks (revision 3) → the corners-mid five
points → alignFace → nearest-neighbour resample → AuraFace int8 on ONNX Runtime → clusterFaces at
0.44 / minFaces 2, visiting rows in FILE-PATH order as the product does. The ports are the F1a
harness's (`ios-face-grouping.md` §10.1/§10.8), read from --harness; the model from --model.
⚠ It decodes with Pillow, not desktop's decoder (§10.8 measured that gap at ~0.004 on the nearest
pair) — a pass here is the gate before a capture, the app on the seeded realm is the proof.

⚠⚠ clusterFaces is greedy and order-dependent. The fixture is deterministic (same photos, same
paths, same groups), but Kwame and Tomás sit at 0.41–0.51 on several pairs: adding, removing or
renaming ANY photo can merge them. Re-run this after every change to the promo set.

Writes the aligned crops as a montage into --out (biometric-shaped data of generated people; the
scratchpad, never the repo).
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw, ImageOps

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))

# Who is in each staged generated photo, by file stem; group photos list every face that should
# count. Only the generated-people albums appear here; any other face found is reported as "?".
CAST = {
    "IMG_0598": "Hannah", "IMG_0987": "Hannah", "IMG_1402": "Hannah", "IMG_1844": "Hannah",
    "IMG_0612": "Tomás", "IMG_0615": "Tomás", "IMG_1019": "Tomás", "IMG_1371": "Tomás", "IMG_1760": "Tomás",
    "IMG_0812": "Amara", "IMG_1104": "Amara", "IMG_1532": "Amara", "IMG_1650": "Amara", "IMG_2031": "Amara",
    "IMG_0819": "Kwame", "IMG_0821": "Kwame", "IMG_1131": "Kwame", "IMG_1590": "Kwame",
    "IMG_1012": "Hannah+Tomás", "IMG_1655": "Tomás+Amara", "IMG_1658": "Kwame+Tomás+Amara",
}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--promo", default=os.path.join(ROOT, "build", "store", "fixture", "promo"))
    p.add_argument("--harness", default=os.path.expanduser("~/tenshu-models/face-f1a-2026-11-09/code"))
    p.add_argument("--model", default=os.path.expanduser(
        "~/tenshu-models/catalog-stage-2026-11-18/artifacts/auraface-v1-int8/1/auraface_int8.onnx"))
    p.add_argument("--out", default=tempfile.mkdtemp(prefix="people-check-"))
    args = p.parse_args()
    for path in (args.promo, os.path.join(args.harness, "facelib.py"), args.model):
        if not os.path.exists(path):
            sys.exit(f"check-people: no {path}")
    sys.path.insert(0, args.harness)
    import facelib as fl

    dec = os.path.join(args.out, "dec")
    os.makedirs(dec, exist_ok=True)
    paths = {}
    for album in sorted(os.listdir(args.promo)):
        d = os.path.join(args.promo, album)
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            if f.lower().endswith(".jpg"):
                key = f"{len(paths):04d}"
                im = ImageOps.exif_transpose(Image.open(os.path.join(d, f))).convert("RGB")
                im.thumbnail((2048, 2048))
                im.save(os.path.join(dec, key + ".png"))
                paths[key] = f"{album}/{f}"
    cli = os.path.join(args.out, "vision_detect")
    subprocess.run(["swiftc", "-O", os.path.join(args.harness, "vision_detect.swift"), "-o", cli], check=True)
    subprocess.run([cli, dec, os.path.join(args.out, "vn.json")], check=True, capture_output=True)
    vn = json.load(open(os.path.join(args.out, "vn.json")))["photos"]

    sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0].name
    rows, crops = [], {}
    for key in sorted(vn):
        faces = sorted(vn[key].get("faces", []), key=lambda f: -(f["box"][2] - f["box"][0]) * (f["box"][3] - f["box"][1]))[:10]
        rgb = np.asarray(Image.open(os.path.join(dec, key + ".png")).convert("RGB"))
        h, w = rgb.shape[:2]
        vecs = []
        for f in faces:
            if "leftPupil" not in f.get("regions", {}):
                continue
            crop = fl.resample(rgb, fl.align_face(fl.vision_five(f, w, h, nose="mid", eyes="corners"), w, h))
            crops[(paths[key], len(vecs))] = crop
            vecs.append(fl.unit(sess.run(None, {inp: fl.preprocess(crop)})[0][0]))
        rows.append((paths[key], vecs))  # the file path, so cluster() visits in the product's order

    groups = fl.cluster(rows, 0.44, min_faces=1)
    visible = [g for g in groups if len(g) >= 2]
    bad = 0
    for k, g in enumerate(visible):
        names = [CAST.get(os.path.splitext(os.path.basename(path))[0], "?") for path, _ in g]
        singles = {n for n in names if "+" not in n}
        verdict = "ok" if len(singles) == 1 and "?" not in singles else "⚠ MIXED or UNKNOWN"
        bad += verdict != "ok"
        print(f"person {k + 1} ({len(g)} faces) {verdict}: " + ", ".join(f"{os.path.basename(pth)}#{i} {n}" for (pth, i), n in zip(g, names)))
    print(f"{len(groups) - len(visible)} faces hidden (a group of one)")
    people = {n for g in visible for path, _ in g for n in [CAST.get(os.path.splitext(os.path.basename(path))[0], "?")] if "+" not in n}
    for n in sorted({v for v in CAST.values() if "+" not in v} - people):
        print(f"⚠ {n} is in no group"); bad += 1
    sheet = Image.new("RGB", (60 + 112 * max([len(g) for g in groups] or [1]), 112 * max(len(groups), 1)), "white")
    d = ImageDraw.Draw(sheet)
    for r, g in enumerate(groups):
        d.text((4, r * 112 + 50), f"{r + 1}", fill="black")
        for c, ref in enumerate(g):
            sheet.paste(Image.fromarray(crops[ref]), (60 + c * 112, r * 112))
            d.text((62 + c * 112, r * 112 + 2), os.path.basename(ref[0])[4:8], fill="yellow")
    sheet.save(os.path.join(args.out, "groups.jpg"), quality=88)
    print(f"montage: {os.path.join(args.out, 'groups.jpg')}")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
