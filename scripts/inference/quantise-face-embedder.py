#!/usr/bin/env python3
"""Quantise an ArcFace-family face embedder to static QDQ int8, and score it against its fp32 source.

Written for AuraFace (`photos-people-and-pets.md` §12.8b). It is the recipe `models.md` §5.11 used for
HSFace, which was never committed and had to be rebuilt from that section's prose.

⚠⚠ **QDQ, never `quantize_dynamic`.** QDQ brackets ops in Quantize/DequantizeLinear, which ORT fuses
to `QLinearConv`. Dynamic quantisation emits `ConvInteger`, which ORT's CPU builds do not implement
(`models.md` §3.5), so the export loads on a desktop and fails on the device.

⚠⚠ **Calibrate on one set, score on another.** Calibration uses Vec2Face's `random-id.png` (90
synthetic faces) and scoring uses `perturbation.png` (8 identities × 10 synthetic variants; the real
reference photograph in each row's first column is skipped). Scoring on the calibration set would
produce a number that cannot fail. Both are public synthetic grids, so no private photograph is
involved. Fetch them from `huggingface.co/BooBooWu/Vec2Face`, under `asset/`.

    python3 -m venv venv && ./venv/bin/pip install onnx onnxruntime pillow numpy sympy
    ./venv/bin/python scripts/inference/quantise-face-embedder.py \\
        --source glintr100.onnx --grids /path/to/vec2face/asset --out auraface_int8.onnx

⚠ The preprocessing is the harness's ArcFace profile: RGB, (x − 127.5) / 127.5, 112×112, CHW. Check
that the source graph does not normalise internally (its first op should be `Conv`, not `Sub`/`Mul`),
or the calibration data is normalised twice.
"""

import argparse
import os
import sys

# Tile geometry of the two Vec2Face grids: 336-px tiles (112-px aligned faces at 3×) under a black header.
TILE = 336
CALIBRATION = ("random-id.png", 0, 132, 10, 9)  # file, x0, y0, columns, rows
SCORING = ("perturbation.png", 420, 216, 10, 8)  # x0 skips the real-photograph column


def tiles(grids, spec):
    import numpy as np
    from PIL import Image

    name, x0, y0, columns, rows = spec
    sheet = Image.open(os.path.join(grids, name)).convert("RGB")
    out = []
    for r in range(rows):
        for c in range(columns):
            x, y = x0 + c * TILE, y0 + r * TILE
            face = sheet.crop((x, y, x + TILE, y + TILE)).resize((112, 112), Image.BILINEAR)
            pixels = (np.asarray(face, dtype=np.float32) - 127.5) / 127.5
            out.append(pixels.transpose(2, 0, 1)[None])
    return out


def embed(path, inputs):
    import numpy as np
    import onnxruntime as ort

    session = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    name = session.get_inputs()[0].name
    vectors = np.concatenate([session.run(None, {name: x})[0] for x in inputs])
    return vectors / np.linalg.norm(vectors, axis=1, keepdims=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, help="A LOCAL fp32 ONNX file whose provenance you established.")
    parser.add_argument("--grids", required=True, help="Directory holding random-id.png and perturbation.png.")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    import numpy as np
    import onnx
    from onnx import version_converter
    from onnxruntime.quantization import CalibrationDataReader, QuantFormat, QuantType, quantize_static
    from onnxruntime.quantization.shape_inference import quant_pre_process

    first = onnx.load(args.source).graph.node[0].op_type
    if first != "Conv":
        print(f"⚠ first op is {first}, not Conv — the graph may normalise internally; refusing")
        return 1

    # ⚠ Per-channel QDQ needs QuantizeLinear's `axis`, which arrives in opset 13.
    work = args.out + ".work"
    model = onnx.load(args.source)
    if model.opset_import[0].version < 13:
        model = version_converter.convert_version(model, 13)
    onnx.save(model, work + ".op13.onnx")
    quant_pre_process(work + ".op13.onnx", work + ".pre.onnx")

    calibration = iter([{model.graph.input[0].name: x} for x in tiles(args.grids, CALIBRATION)])

    class Reader(CalibrationDataReader):
        def get_next(self):
            return next(calibration, None)

    quantize_static(
        work + ".pre.onnx", args.out, Reader(),
        quant_format=QuantFormat.QDQ, per_channel=True,
        activation_type=QuantType.QInt8, weight_type=QuantType.QInt8,
    )
    for suffix in (".op13.onnx", ".pre.onnx"):
        os.remove(work + suffix)

    ops = {n.op_type for n in onnx.load(args.out).graph.node}
    if "ConvInteger" in ops:
        print("⚠⚠ ConvInteger in the output — this will not run on the device (models.md §3.5)")
        return 1

    scoring = tiles(args.grids, SCORING)
    identity = np.repeat(np.arange(SCORING[4]), SCORING[3])
    upper = np.triu_indices(len(scoring), 1)
    same = identity[upper[0]] == identity[upper[1]]
    results = {}
    for label, path in (("fp32", args.source), ("int8", args.out)):
        vectors = embed(path, scoring)
        results[label] = vectors
        cosines = (vectors @ vectors.T)[upper]
        print(
            f"{label}: same {cosines[same].mean():+.3f} (min {cosines[same].min():+.3f})  "
            f"diff {cosines[~same].mean():+.3f} (max {cosines[~same].max():+.3f})  "
            f"gap {cosines[same].mean() - cosines[~same].mean():+.3f}"
        )
    agreement = (results["fp32"] * results["int8"]).sum(axis=1)
    print(f"fp32↔int8 per-face cosine: mean {agreement.mean():.3f}, min {agreement.min():.3f}")
    print(f"wrote {args.out} ({os.path.getsize(args.out) / 1e6:.1f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
