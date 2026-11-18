#!/usr/bin/env python3
"""Export RF-DETR Nano (COCO) to the int8 ONNX animal detector Pets and People's veto run on Android.

`photos-pets.md` §10.1 (PT-2b). One run does the whole chain and refuses to finish if any step drifts:

1. the upstream checkpoint, checked against its pinned sha256;
2. rfdetr's own ONNX export (fp32, 384², opset 17, outputs `dets` [1,300,4] cxcywh and `labels` [1,300,91]);
3. static QDQ int8, calibrated on one seeded half of COCO val2017;
4. a self-check on the OTHER half: image-level cat and dog recall and precision for fp32 and int8 at the
   detector's confidence floor, held to the bars below;
5. `export-manifest.json`: every digest, version and number above, so the artifact can be rebuilt and argued.

    python3.13 -m venv venv && ./venv/bin/pip install -r scripts/inference/requirements-animal-detector-export.txt
    ./venv/bin/python scripts/inference/export-animal-detector.py \\
        --coco ~/tenshu-models/coco-val2017/corpus --out build/animal-detector

⚠⚠ **QDQ per-TENSOR, `MatMulConstBOnly`.** Per-channel quantisation of the attention MatMuls fails on
onnxruntime's CPU kernel (`QLinearMatMul … weight zero point must be a scalar`), and quantising a MatMul
whose two inputs are both activations is not a weight quantisation at all. `quantize_dynamic` is out for
the reason `quantise-face-embedder.py` gives (ConvInteger has no CPU kernel).

⚠ Calibrate on one half, score on the other: a score on the calibration set could not fail.
⚠ COCO category ids, not the 80-class index: the logits are 91 wide and cat is 17, dog is 18.
⚖️ COCO-trained weights were accepted by the maintainer on 2026-11-17 (`photos-pets.md` §5.3).
"""

import argparse
import hashlib
import json
import os
import platform
import shutil
import sys
import tempfile
import time

PINNED = "requirements-animal-detector-export.txt"
PINNED_PYTHON = "3.13"
TOOLCHAIN = frozenset({"rfdetr", "torch", "torchvision", "numpy", "onnx", "onnxruntime", "pillow"})
MANIFEST = "export-manifest.json"
STEM = "rfdetr_nano_int8"

SOURCE = "roboflow/rf-detr"
SOURCE_URL = "https://storage.googleapis.com/rfdetr/nano_coco/checkpoint_best_regular.pth"
SOURCE_SHA256 = "d8d6b9ee57d4d0ed2b1f305163624712a0532cb7bce0c747317984fc5457440d"
LICENCE = "Apache-2.0"

COCO_ANNOTATIONS_SHA256 = "e8c7f7908f1d7278341fae127d0da654f102f11bd7b21d8aeefa635b8c810b6f"
SPLIT_SEED = "20261117"
CALIBRATION_IMAGES = 200

SIDE = 384
MEAN = (0.485, 0.456, 0.406)
STD = (0.229, 0.224, 0.225)
CAT, DOG = 17, 18
FLOOR = 0.5

# The int8 artifact's held-out bars, set just under what the 2026-11-17 run measured (cat recall 0.918,
# dog 0.837, precision 0.990 / 0.901) so a toolchain drift that costs recall fails the export.
BARS = {"cat": {"recall": 0.88, "precision": 0.95}, "dog": {"recall": 0.80, "precision": 0.86}}


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def pinned_versions() -> dict:
    pins = {}
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), PINNED), encoding="utf-8") as handle:
        for line in handle:
            line = line.split("#", 1)[0].strip()
            if "==" in line:
                package, version = line.split("==", 1)
                pins[package.strip().lower()] = version.strip()
    return pins


def check_toolchain(allow_unpinned: bool) -> dict:
    from importlib.metadata import PackageNotFoundError, version

    pins = pinned_versions()
    installed = {}
    for name in sorted(TOOLCHAIN):
        try:
            installed[name] = version(name)
        except PackageNotFoundError:
            installed[name] = None
    wrong = {name: (installed[name], pins.get(name)) for name in TOOLCHAIN if installed[name] != pins.get(name)}
    running = platform.python_version()
    if running.split(".")[:2] != PINNED_PYTHON.split("."):
        wrong["python"] = (running, PINNED_PYTHON)
    if wrong and not allow_unpinned:
        raise SystemExit(f"toolchain differs from {PINNED}: {wrong} (--allow-unpinned to run anyway, unrecorded as pinned)")
    installed["python"] = running
    return {"versions": installed, "matchesPins": not wrong}


def half(file_name: str) -> int:
    return int(hashlib.sha256(f"{SPLIT_SEED}:{file_name}".encode()).hexdigest(), 16) % 2


def triangle_weights(n_in: int, n_out: int) -> list:
    """Per output index, (first input index, weights): an antialiased bilinear — a triangle filter whose
    support grows with the shrink, as torchvision's antialiased resize (what RF-DETR trained on) and PIL's
    BILINEAR are. ⚠⚠ The Kotlin twin is `AnimalDetectorInput.resize`, pinned to this by
    `AnimalDetectorInputGoldenTest`; change one and the golden moves."""
    import numpy as np

    scale = n_in / n_out
    support = max(scale, 1.0)
    out = []
    for i in range(n_out):
        centre = (i + 0.5) * scale
        lo = max(int(np.floor(centre - support)), 0)
        hi = min(int(np.ceil(centre + support)), n_in)
        xs = np.arange(lo, hi)
        weights = np.maximum(0.0, 1.0 - np.abs((xs + 0.5 - centre) / support))
        out.append((lo, weights / weights.sum()))
    return out


def resize(rgb, side: int):
    """HxWx3 uint8 → side × side × 3 uint8: horizontal pass, vertical pass, in float64, rounded half up once."""
    import numpy as np

    height, width, _ = rgb.shape
    source = rgb.astype(np.float64)
    rows = np.empty((height, side, 3))
    for j, (lo, weights) in enumerate(triangle_weights(width, side)):
        rows[:, j, :] = np.tensordot(source[:, lo:lo + len(weights), :], weights, axes=([1], [0]))
    out = np.empty((side, side, 3))
    for i, (lo, weights) in enumerate(triangle_weights(height, side)):
        out[i, :, :] = np.tensordot(weights, rows[lo:lo + len(weights), :, :], axes=([0], [0]))
    return np.clip(np.floor(out + 0.5), 0, 255).astype(np.uint8)


def pixels(path: str):
    """The detector's input, exactly as the Kotlin side builds it: RGB, [resize] squash to 384², ImageNet mean/std, CHW."""
    import numpy as np
    from PIL import Image

    image = resize(np.asarray(Image.open(path).convert("RGB")), SIDE)
    array = (image.astype(np.float32) / 255.0 - np.array(MEAN, np.float32)) / np.array(STD, np.float32)
    return array.transpose(2, 0, 1)[None]


def score(model: str, corpus: str, images: list, truth: dict) -> dict:
    import numpy as np
    import onnxruntime as ort

    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    session = ort.InferenceSession(model, options, providers=["CPUExecutionProvider"])
    started = time.time()
    best = {}
    for image in images:
        _, logits = session.run(None, {"input": pixels(os.path.join(corpus, image["file_name"]))})
        probability = 1.0 / (1.0 + np.exp(-logits[0]))
        best[image["id"]] = (float(probability[:, CAT].max()), float(probability[:, DOG].max()))
    result = {"msPerImage": round((time.time() - started) / len(images) * 1000, 1), "images": len(images)}
    for name, category, index in (("cat", CAT, 0), ("dog", DOG, 1)):
        fired = {key for key, value in best.items() if value[index] >= FLOOR}
        positive = {key for key in best if category in truth.get(key, ())}
        hits = len(fired & positive)
        result[name] = {
            "positives": len(positive),
            "recall": round(hits / len(positive), 3),
            "precision": round(hits / max(1, len(fired)), 3),
            "falsePositives": len(fired - positive),
        }
    result["scores"] = best
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--coco", required=True, help="COCO val2017: the images and instances_val2017.json")
    parser.add_argument("--out", required=True)
    parser.add_argument("--checkpoint", help="the upstream .pth; downloaded into a temporary RF_HOME when absent")
    parser.add_argument("--allow-unpinned", action="store_true")
    args = parser.parse_args()

    toolchain = check_toolchain(args.allow_unpinned)
    annotations_path = os.path.join(args.coco, "instances_val2017.json")
    if sha256_of(annotations_path) != COCO_ANNOTATIONS_SHA256:
        raise SystemExit(f"{annotations_path} is not the pinned COCO val2017 annotations")
    os.makedirs(args.out, exist_ok=True)
    work = tempfile.mkdtemp(prefix="animal-detector-")

    checkpoint = args.checkpoint
    if checkpoint is None:
        os.environ["RF_HOME"] = work
        checkpoint = os.path.join(work, "rf-detr-nano.pth")
    from rfdetr import RFDETRNano

    model = RFDETRNano(pretrain_weights=checkpoint)
    if sha256_of(checkpoint) != SOURCE_SHA256:
        raise SystemExit(f"{checkpoint} is not the pinned RF-DETR Nano checkpoint")
    fp32 = str(model.export(output_dir=work, verbose=False))

    import onnx
    graph = onnx.load(fp32)
    shapes = {
        value.name: [d.dim_value for d in value.type.tensor_type.shape.dim]
        for value in list(graph.graph.input) + list(graph.graph.output)
    }
    expected = {"input": [1, 3, SIDE, SIDE], "dets": [1, 300, 4], "labels": [1, 300, 91]}
    if shapes != expected:
        raise SystemExit(f"the export's interface moved: {shapes}, expected {expected}")

    annotations = json.load(open(annotations_path, encoding="utf-8"))
    truth = {}
    for annotation in annotations["annotations"]:
        if annotation["category_id"] in (CAT, DOG) and not annotation["iscrowd"]:
            truth.setdefault(annotation["image_id"], set()).add(annotation["category_id"])
    images = sorted(
        (image for image in annotations["images"] if os.path.exists(os.path.join(args.coco, image["file_name"]))),
        key=lambda image: image["file_name"],
    )
    if len(images) != 5000:
        raise SystemExit(f"{len(images)} images under {args.coco}, expected COCO val2017's 5 000")
    calibration = [image["file_name"] for image in images if half(image["file_name"]) == 0][:CALIBRATION_IMAGES]
    held_out = [image for image in images if half(image["file_name"]) == 1]

    from onnxruntime.quantization import CalibrationDataReader, QuantFormat, QuantType, quant_pre_process, quantize_static

    class Calibration(CalibrationDataReader):
        def __init__(self):
            self.files = iter(calibration)

        def get_next(self):
            name = next(self.files, None)
            return None if name is None else {"input": pixels(os.path.join(args.coco, name))}

    prepared = os.path.join(work, "prepared.onnx")
    int8 = os.path.join(args.out, f"{STEM}.onnx")
    quant_pre_process(fp32, prepared)
    quantize_static(
        prepared, int8, Calibration(),
        quant_format=QuantFormat.QDQ, per_channel=False,
        activation_type=QuantType.QUInt8, weight_type=QuantType.QInt8,
        op_types_to_quantize=["Conv", "MatMul", "Gemm"], extra_options={"MatMulConstBOnly": True},
    )

    reference = score(fp32, args.coco, held_out, truth)
    shipped = score(int8, args.coco, held_out, truth)
    flips = {
        name: sum(
            1 for key in reference["scores"]
            if (reference["scores"][key][index] >= FLOOR) != (shipped["scores"][key][index] >= FLOOR)
        )
        for name, index in (("cat", 0), ("dog", 1))
    }
    for result in (reference, shipped):
        del result["scores"]
    failed = [
        f"{name} {metric} {shipped[name][metric]} < {bar}"
        for name, bars in BARS.items() for metric, bar in bars.items() if shipped[name][metric] < bar
    ]

    manifest = {
        "artifact": {"file": os.path.basename(int8), "sha256": sha256_of(int8), "byteSize": os.path.getsize(int8)},
        "source": {"repo": SOURCE, "url": SOURCE_URL, "sha256": SOURCE_SHA256, "licence": LICENCE},
        "fp32Export": {"sha256": sha256_of(fp32), "byteSize": os.path.getsize(fp32), "interface": expected},
        "preprocessing": {"side": SIDE, "resize": "antialiased triangle squash (resize())", "mean": MEAN, "std": STD, "layout": "NCHW RGB"},
        "classes": {"cat": CAT, "dog": DOG, "logits": "sigmoid over [300, 91] COCO category ids"},
        "quantisation": {"format": "QDQ", "perChannel": False, "matMulConstBOnly": True, "calibrationImages": len(calibration)},
        "split": {"seed": SPLIT_SEED, "calibration": "half 0", "heldOut": len(held_out), "annotationsSha256": COCO_ANNOTATIONS_SHA256},
        "selfCheck": {"floor": FLOOR, "fp32": reference, "int8": shipped, "decisionFlips": flips, "bars": BARS},
        "toolchain": toolchain,
    }
    with open(os.path.join(args.out, MANIFEST), "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2, sort_keys=True)
        handle.write("\n")
    shutil.rmtree(work, ignore_errors=True)
    print(json.dumps(manifest["artifact"]), json.dumps(manifest["selfCheck"]["int8"]), f"flips={flips}")
    if failed:
        raise SystemExit("self-check FAILED: " + "; ".join(failed))


if __name__ == "__main__":
    sys.exit(main())
