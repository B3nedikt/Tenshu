#!/usr/bin/env python3
"""M2 for Pets (`photos-pets.md` §7, PT-D10): does DINOv2-S zero-shot keep one animal's photos together
and different animals apart?

Over a set laid out `<set>/<species>/<animal>/<photo>` with a `manifest.tsv` (`fetch-commons-pets.py`),
or any directory in that shape (the maintainer's own household photos):

⚠ With a `manifest.tsv`, **only the photos it lists are scored**. A re-run of the fetcher that excludes an
animal leaves the old directory on disk; scoring directories would bring it straight back.

1. **Detect** with the shipped detector (`rfdetr_nano_int8.onnx`), through the export's own preprocessing
   (`export-animal-detector.py`'s `resize`), and keep the most confident box of the animal's species.
2. **Crop** square around that box (side = the box's longer edge × [MARGIN], clamped into the image),
   resized bicubic to 224², ImageNet mean/std, CHW — PT-D7's whole-body crop.
3. **Embed** with a DINOv2 ONNX (`[CLS ‖ mean of patch tokens]`: 768 wide for Small, 1536 for Base; the halves are split at the middle), scored three ways:
   the CLS half, the mean half, and both, each L2-normalised.
4. **Score**, per species and per variant, every pair within a species: EER, ROC AUC, the miss rate at
   a 1 % false-match rate, and rank-1 (each photo's nearest other photo is the same animal).
5. **Household** (`--household <dir>`, M2 proper): every animal in every photo there — a subdirectory per
   pet names it, a loose photo is its own pet — scored on the CLS half against the set's cut-offs: each
   pair of the household's pets (a merge is the failure, `photos-pets.md` §7), each pet's own photos
   (once there are several), and each pet's nearest stranger from the set.

    python3 scripts/inference/m2-pet-embedding.py --set <dir> --detector <rfdetr_nano_int8.onnx> \\
        --embedder <dinov2_small_cls_mean.onnx> --out <dir> [--household <dir>]

⚠ Pairs are image-level: one animal's photos may come from one day or one photographer, which flatters
any embedder. The household set (same-breed pairs, one home) is the honest test; this is the first one.
⚠⚠ Evaluation only — nothing here trains anything, and none of the photographs is ever committed.
"""

import argparse
import importlib.util
import itertools
import json
import os

MARGIN = 1.15
SIDE = 224
SPECIES = {"cat": 17, "dog": 18}
FLOOR = 0.5
MEAN = (0.485, 0.456, 0.406)
STD = (0.229, 0.224, 0.225)


def export_module():
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "export-animal-detector.py")
    spec = importlib.util.spec_from_file_location("export_animal_detector", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def detect(session, ex, rgb, category):
    """The most confident [category] box as top-left fractions, with its confidence; or None."""
    import numpy as np

    small = ex.resize(rgb, ex.SIDE).astype(np.float32)
    x = ((small / 255.0 - np.array(ex.MEAN, np.float32)) / np.array(ex.STD, np.float32)).transpose(2, 0, 1)[None]
    dets, logits = session.run(None, {"input": x})
    p = 1.0 / (1.0 + np.exp(-logits[0][:, category]))
    q = int(p.argmax())
    if p[q] < FLOOR:
        return None
    cx, cy, w, h = dets[0][q]
    count = int((p >= FLOOR).sum())
    return (max(0.0, cx - w / 2), max(0.0, cy - h / 2), min(1.0, cx + w / 2), min(1.0, cy + h / 2)), float(p[q]), count


def detect_all(session, ex, rgb):
    """Every cat or dog box at [FLOOR] or above as (box, confidence, species), most confident first."""
    import numpy as np

    small = ex.resize(rgb, ex.SIDE).astype(np.float32)
    x = ((small / 255.0 - np.array(ex.MEAN, np.float32)) / np.array(ex.STD, np.float32)).transpose(2, 0, 1)[None]
    dets, logits = session.run(None, {"input": x})
    names = list(SPECIES)
    p = 1.0 / (1.0 + np.exp(-logits[0][:, list(SPECIES.values())]))
    kept = []
    for q in np.argsort(-p.max(1)):
        if p[q].max() < FLOOR:
            break
        cx, cy, w, h = dets[0][q]
        box = (max(0.0, cx - w / 2), max(0.0, cy - h / 2), min(1.0, cx + w / 2), min(1.0, cy + h / 2))
        if all(iou(box, k[0]) <= 0.5 for k in kept):  # DETR can answer one animal with two queries
            kept.append((box, float(p[q].max()), names[int(p[q].argmax())]))
    return kept


def iou(a, b):
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - ix * iy
    return ix * iy / union if union else 0.0


def household(args, detector, embedder, ex, results, strangers):
    """M2 proper: the household's pets against each other, themselves, and the set's strangers (CLS)."""
    import numpy as np
    from PIL import Image, ImageOps

    photos = []
    for entry in sorted(os.listdir(args.household)):
        path = os.path.join(args.household, entry)
        if os.path.isdir(path):
            photos += [(entry, os.path.join(path, n), True) for n in sorted(os.listdir(path)) if not n.startswith(".")]
        elif not entry.startswith("."):
            photos.append((os.path.splitext(entry)[0], path, False))
    pets, kinds, vectors = [], [], []
    for pet, path, named in photos:
        image = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
        boxes = detect_all(detector, ex, np.asarray(image))
        for n, (box, _, species) in enumerate(boxes):
            # ⚠ A loose photo with several animals is several pets; a named folder trusts its name.
            # Decided by how the photo was found, never by comparing paths (`/Cats/` vs `/Cats`).
            pets.append(pet if named or len(boxes) == 1 else f"{pet}#{n}")
            kinds.append(species)
            v = embedder.run(None, {"input": crop(image, box)})[0][0]
            v = v[: len(v) // 2]  # the CLS half
            vectors.append(v / np.linalg.norm(v))
    if not vectors:
        print(f"== household: no cat or dog detected in {len(photos)} photos")
        return {"unscored": True, "photos": len(photos)}
    v = np.stack(vectors)
    sim = v @ v.T
    # ⚠ Each species is judged by its OWN strict cut (1 % false match on that species' strangers): a
    # shared minimum would hold cats to the dogs' laxer cut. A species with no strangers has no cut.
    strict = {s: r["cls"]["strictCutExact"] for s, r in results.items() if "cls" in r}
    pairs = []
    for i, j in itertools.combinations(range(len(pets)), 2):
        cut = strict.get(kinds[i]) if kinds[i] == kinds[j] else None  # a cat and a dog never merge
        pairs.append({"a": pets[i], "b": pets[j], "species": kinds[i] if kinds[i] == kinds[j] else None,
                      "cosine": round(float(sim[i, j]), 4), "same": pets[i] == pets[j],
                      # None = unknown: same species, but no cut to judge by — never read as "kept apart".
                      "merged": None if pets[i] != pets[j] and kinds[i] == kinds[j] and cut is None
                      else bool(pets[i] != pets[j] and cut is not None and float(sim[i, j]) >= cut)})  # float: a float32 compare rounds the cut
    nearest = []
    for i, pet in enumerate(pets):
        best = max(((float(v[i] @ sv), label) for sv, label, s in strangers if s == kinds[i]), default=(None, None))
        cut = strict.get(kinds[i])
        nearest.append({"pet": pet, "species": kinds[i], "stranger": best[1],
                        "cosine": round(best[0], 4) if best[0] is not None else None,
                        "merged": None if best[0] is None or cut is None else bool(best[0] >= cut)})
    out = {"strictCuts": strict, "pairs": pairs, "nearestStranger": nearest,
           "mergedAtStrictCut": sum(p["merged"] is True for p in pairs),
           "strangerMergesAtStrictCut": sum(n["merged"] is True for n in nearest),
           "unjudged": sum(p["merged"] is None for p in pairs) + sum(n["merged"] is None for n in nearest)}
    print(f"== household: {len(set(pets))} pets in {len(photos)} photos; strict cuts "
          + ", ".join(f"{k} {c:.4f}" for k, c in strict.items() if c is not None))
    for p in pairs:
        flag = "  ⚠ MERGED" if p["merged"] else ("  ? no cut" if p["merged"] is None else "")
        print(f"   {'same' if p['same'] else 'pair'} {p['a']} / {p['b']}: {p['cosine']}" + flag)
    for n in nearest:
        flag = "  ⚠ MERGED" if n["merged"] else ("  ? no cut" if n["merged"] is None else "")
        print(f"   {n['pet']} nearest stranger {n['stranger']}: {n['cosine']}" + flag)
    return out


def crop(image, box):
    import numpy as np
    from PIL import Image

    W, H = image.size
    l, t, r, b = box[0] * W, box[1] * H, box[2] * W, box[3] * H
    side = max(r - l, b - t) * MARGIN
    cx, cy = (l + r) / 2, (t + b) / 2
    side = min(side, W, H) if side > min(W, H) else side
    x0 = min(max(0, cx - side / 2), max(0, W - side))
    y0 = min(max(0, cy - side / 2), max(0, H - side))
    patch = image.crop((int(x0), int(y0), int(x0 + side), int(y0 + side))).resize((SIDE, SIDE), Image.BICUBIC)
    a = (np.asarray(patch, np.float32) / 255.0 - np.array(MEAN, np.float32)) / np.array(STD, np.float32)
    return a.transpose(2, 0, 1)[None]


def scores(vectors, labels):
    import numpy as np

    v = np.stack(vectors)
    v = v / np.linalg.norm(v, axis=1, keepdims=True)
    sim = v @ v.T
    same, diff = [], []
    for i, j in itertools.combinations(range(len(labels)), 2):
        (same if labels[i] == labels[j] else diff).append(sim[i, j])
    same, diff = np.array(same), np.array(diff)
    # ⚠ Plus a reject-all cut just above the top score: with under 100 different pairs, a top-scoring
    # different pair otherwise leaves no cut at ≤ 1 % false match, and the household reads "no merge".
    top = float(np.concatenate([same, diff]).max())
    thresholds = np.unique(np.concatenate([same, diff, [np.nextafter(top, np.inf)]]))
    # Rates from sorted scores and cumulative counts, never a pair × threshold scan: a default-sized set
    # (60 animals × 12 photos) is ~255k different pairs, and the scan was billions of comparisons.
    same_sorted, diff_sorted = np.sort(same), np.sort(diff)
    far = 1.0 - np.searchsorted(diff_sorted, thresholds, side="left") / len(diff)  # share of diff >= t
    frr = np.searchsorted(same_sorted, thresholds, side="left") / len(same)  # share of same < t
    k = int(np.argmin(np.abs(far - frr)))
    below = np.searchsorted(diff_sorted, same, side="left")  # per same score: diff strictly below it
    ties = np.searchsorted(diff_sorted, same, side="right") - below
    auc = float((below + 0.5 * ties).sum() / (len(same) * len(diff)))
    at1 = thresholds[far <= 0.01]
    miss_at_1 = float((same < at1.min()).mean()) if len(at1) else 1.0
    np.fill_diagonal(sim, -2)
    rank1 = float(np.mean([labels[int(sim[i].argmax())] == labels[i] for i in range(len(labels))]))
    return {
        "photos": len(labels), "animals": len(set(labels)), "samePairs": len(same), "differentPairs": len(diff),
        "eer": round(float((far[k] + frr[k]) / 2), 4), "eerThreshold": round(float(thresholds[k]), 4),
        "auc": round(auc, 4), "missAt1PercentFalseMatch": round(miss_at_1, 4),
        "thresholdAt1PercentFalseMatch": round(float(at1.min()), 4) if len(at1) else None, "rank1": round(rank1, 4),
        # ⚠ The cut household() decides by, unrounded: rounding a reject-all endpoint (1.0000000000000002)
        # to 1.0 would accept an identical different-animal pair again.
        "strictCutExact": float(at1.min()) if len(at1) else None,
        "sameMedian": round(float(np.median(same)), 4), "differentMedian": round(float(np.median(diff)), 4),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--set", required=True)
    parser.add_argument("--detector", required=True)
    parser.add_argument("--embedder", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--household", help="the maintainer's own pets: a subdirectory per pet, or loose photos")
    args = parser.parse_args()
    import numpy as np
    import onnxruntime as ort
    from PIL import Image, ImageOps

    ex = export_module()
    detector = ort.InferenceSession(args.detector, providers=["CPUExecutionProvider"])
    embedder = ort.InferenceSession(args.embedder, providers=["CPUExecutionProvider"])
    os.makedirs(args.out, exist_ok=True)
    rows, results, strangers = [], {}, []
    manifest = os.path.join(args.set, "manifest.tsv")
    listed = None
    if os.path.isfile(manifest):
        with open(manifest, encoding="utf-8") as handle:
            listed = {tuple(line.split("\t")[:3]) for line in list(handle)[1:] if line.strip()}
    for species, category in SPECIES.items():
        root = os.path.join(args.set, species)
        if not os.path.isdir(root):
            continue
        vectors, labels, missed, multiple = [], [], 0, 0
        for animal in sorted(os.listdir(root)):
            directory = os.path.join(root, animal)
            if not os.path.isdir(directory):
                continue
            for name in sorted(os.listdir(directory)):
                if name.startswith(".") or (listed is not None and (species, animal, name) not in listed):
                    continue
                path = os.path.join(directory, name)
                try:
                    image = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
                except Exception:  # noqa: BLE001 — an unreadable file is reported, not fatal
                    rows.append(f"{species}\t{animal}\t{name}\tunreadable")
                    continue
                found = detect(detector, ex, np.asarray(image), category)
                if found is None:
                    missed += 1
                    rows.append(f"{species}\t{animal}\t{name}\tno-{species}")
                    continue
                box, confidence, count = found
                multiple += count > 1
                vectors.append(embedder.run(None, {"input": crop(image, box)})[0][0])
                labels.append(animal)
                rows.append(f"{species}\t{animal}\t{name}\t{confidence:.3f}\t{count}\t" + " ".join(f"{v:.4f}" for v in box))
        same_pairs = sum(labels.count(l) * (labels.count(l) - 1) // 2 for l in set(labels))
        if same_pairs == 0 or len(set(labels)) < 2:
            # ⚠ Calibration needs both same-animal and different-animal pairs: nothing usable, one animal,
            # or one photo per animal gives no cut. Reported, not fatal — the other species and the
            # household still get their results.
            results[species] = {"detectorMissed": missed, "photosWithSeveralAnimals": multiple, "unscored": True,
                                "photos": len(labels), "animals": len(set(labels)), "samePairs": same_pairs}
            continue
        # ⚠ An animal left with one usable photo has no same-animal pair; it still counts as a stranger.
        unit = lambda a: a / np.linalg.norm(a)
        variants = {
            "cls": [v[: len(v) // 2] for v in vectors],
            "mean": [v[len(v) // 2:] for v in vectors],
            "cls+mean": [np.concatenate([unit(v[: len(v) // 2]), unit(v[len(v) // 2:])]) for v in vectors],
        }
        results[species] = {"detectorMissed": missed, "photosWithSeveralAnimals": multiple,
                            **{name: scores(v, labels) for name, v in variants.items()}}
        strangers += [(unit(v[: len(v) // 2]), f"{species}/{label}", species) for v, label in zip(vectors, labels)]
        np.save(os.path.join(args.out, f"{species}-vectors.npy"), np.stack(vectors))
        with open(os.path.join(args.out, f"{species}-labels.txt"), "w", encoding="utf-8") as handle:
            handle.write("\n".join(labels) + "\n")
    if args.household:
        results["household"] = household(args, detector, embedder, ex, dict(results), strangers)
    with open(os.path.join(args.out, "detections.tsv"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(rows) + "\n")
    with open(os.path.join(args.out, "m2-result.json"), "w", encoding="utf-8") as handle:
        json.dump(results, handle, indent=2)
    for species, r in results.items():
        if species == "household":
            continue
        if r.get("unscored"):
            print(f"== {species}: nothing scored")
            continue
        print(f"== {species}: detector missed {r['detectorMissed']}, several animals in {r['photosWithSeveralAnimals']}")
        for variant in ("cls", "mean", "cls+mean"):
            s = r[variant]
            print(f"   {variant:8s} photos {s['photos']} animals {s['animals']}  EER {s['eer']} (at {s['eerThreshold']})  AUC {s['auc']}  "
                  f"miss@1%FM {s['missAt1PercentFalseMatch']}  rank-1 {s['rank1']}  same/diff median {s['sameMedian']}/{s['differentMedian']}")


if __name__ == "__main__":
    main()
