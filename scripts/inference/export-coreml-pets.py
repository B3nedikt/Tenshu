#!/usr/bin/env python3
"""Export Pets' two models for iOS: Core ML fp16 ML Programs, one reproducible tar each (`photos-pets.md` PT-4).

    python3.13 -m venv venv
    ./venv/bin/pip install -r scripts/inference/requirements-coreml-pets-export.txt
    ./venv/bin/python scripts/inference/export-coreml-pets.py \
        --detector-checkpoint /path/to/rf-detr-nano.pth --out /path/to/out

Produces `dinov2_small_cls_mean_fp16.mlpackage.tar` (the `petEmbedder` of `dinov2-small-zeroshot` /
`coreml`) and `rfdetr_nano_fp16.mlpackage.tar` (the `animalDetector` of `rfdetr-nano-coco-int8` /
`coreml`), plus `export-manifest.json`. macOS only: the self-check loads each archive through Core ML.

⚠⚠ **Why RF-DETR and not Vision on iOS** (PT-D4's fallback, §10.13): Vision's cat and dog boxes frame the
same animal differently enough that its template moves to cosine 0.67 against the Android crop's — a
split across platforms — and it found 15 fewer photos of 98. This RF-DETR export gives a median
cosine of 0.989 (min 0.870) against Android's int8 detector, the same photos found.

⚠⚠ **The sources are pinned, and the script refuses others**: DINOv2-small at `export-pet-embedder.py`'s
revision; RF-DETR Nano's COCO checkpoint at `export-animal-detector.py`'s digest. Both are converted from
torch fp32 — never from the int8 ONNX, which would stack Android's quantisation under Core ML's fp16.

⚠⚠ **Three trace-time rewrites, each the same arithmetic** — Core ML lacks the original's ops:
- DINOv2's position embeddings are interpolated 37×37 → 16×16 with a bicubic upsample Core ML cannot
  convert; the input is fixed at 224, so the 16×16 table is computed once in torch and baked in.
- RF-DETR's proposal grid uses `torch.meshgrid` over traced sizes, which the converter refuses; it is
  built as two broadcast ranges.
- RF-DETR's deformable attention reshapes to rank 6 (batch, queries, heads, levels, points, xy) and Core
  ML stops at rank 5; with batch 1 the batch axis is dropped throughout. The script checks the patched
  torch model against the original before tracing, and refuses a difference above 1e-4.

⚠⚠ **Float32 in and out, fp16 inside**, as the face embedder's export: an iOS17 ML Program otherwise takes
and returns Float16, and `CoreMlSession` copies only contiguous Float32 out in one move — Float16 would
cost a per-element read of the detector's 27 300 logits for every photograph.
⚠ The archives are canonicalised exactly as `export-coreml-face-embedder.py`'s are (its helpers are
imported, not copied): zeroed tar metadata, path-derived Manifest item ids, deterministic protobuf.
"""

import argparse
import hashlib
import importlib.util
import json
import os
import platform
import shutil
import sys
import tempfile

PINNED = "requirements-coreml-pets-export.txt"
PINNED_PYTHON = "3.13"
TOOLCHAIN = frozenset({"coremltools", "torch", "torchvision", "rfdetr", "transformers", "numpy", "pillow"})
MANIFEST = "export-manifest.json"

EMBEDDER_REPO = "facebook/dinov2-small"
EMBEDDER_REVISION = "ed25f3a31f01632728cabb09d1542f84ab7b0056"
EMBEDDER_STEM = "dinov2_small_cls_mean_fp16"
EMBEDDER_SIDE = 224
EMBEDDER_WIDTH = 768
# The template is the CLS half (`PetEmbedderModel.TEMPLATE`).
TEMPLATE = 384

DETECTOR_SOURCE_URL = "https://storage.googleapis.com/rfdetr/nano_coco/checkpoint_best_regular.pth"
DETECTOR_SHA256 = "d8d6b9ee57d4d0ed2b1f305163624712a0532cb7bce0c747317984fc5457440d"
DETECTOR_STEM = "rfdetr_nano_fp16"
DETECTOR_SIDE = 384
QUERIES = 300
CLASSES = 91

LICENCE = "Apache-2.0"


def face_helpers():
    """`export-coreml-face-embedder.py`'s canonicalisation, imported: one rule for every Core ML tar."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "export-coreml-face-embedder.py")
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location("export_coreml_face_embedder", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


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

    pins = {name: pin for name, pin in pinned_versions().items() if name in TOOLCHAIN}
    missing = sorted(TOOLCHAIN - set(pins))
    if missing:
        raise SystemExit(f"toolchain: {missing} have no pin in {PINNED}")
    installed = {}
    for name in pins:
        try:
            installed[name] = version(name)
        except PackageNotFoundError:
            installed[name] = None
    wrong = {name: (installed[name], pin) for name, pin in pins.items() if installed[name] != pin}
    running = platform.python_version()
    if running.split(".")[:2] != PINNED_PYTHON.split("."):
        wrong["python"] = (running, PINNED_PYTHON)
    if wrong and not allow_unpinned:
        for name, (have, want) in sorted(wrong.items()):
            print(f"toolchain: {name} is {have}, the pinned version is {want}", file=sys.stderr)
        raise SystemExit(f"refusing to export outside the pinned toolchain; install scripts/inference/{PINNED}")
    return installed


def convert_embedder():
    import coremltools as ct
    import numpy as np
    import torch
    from transformers import AutoModel

    model = AutoModel.from_pretrained(EMBEDDER_REPO, revision=EMBEDDER_REVISION).eval()
    with torch.no_grad():
        reference = model(pixel_values=torch.zeros(1, 3, EMBEDDER_SIDE, EMBEDDER_SIDE)).last_hidden_state.clone()
        patches = (EMBEDDER_SIDE // 14) ** 2
        fixed = model.embeddings.interpolate_pos_encoding(
            torch.zeros(1, patches + 1, model.config.hidden_size), EMBEDDER_SIDE, EMBEDDER_SIDE
        ).clone()
    model.embeddings.interpolate_pos_encoding = lambda embeddings, height, width: fixed
    with torch.no_grad():
        baked = model(pixel_values=torch.zeros(1, 3, EMBEDDER_SIDE, EMBEDDER_SIDE)).last_hidden_state
    delta = float((baked - reference).abs().max())
    if delta > 1e-4:
        raise SystemExit(f"embedder: baking the position table moved the output by {delta}")

    class ClsMean(torch.nn.Module):
        def __init__(self, inner):
            super().__init__()
            self.inner = inner

        def forward(self, x):
            hidden = self.inner(pixel_values=x).last_hidden_state
            return torch.cat([hidden[:, 0], hidden[:, 1:].mean(1)], 1)

    wrapped = ClsMean(model).eval()
    traced = torch.jit.trace(wrapped, torch.randn(1, 3, EMBEDDER_SIDE, EMBEDDER_SIDE))
    converted = ct.convert(
        traced,
        inputs=[ct.TensorType(name="input", shape=(1, 3, EMBEDDER_SIDE, EMBEDDER_SIDE), dtype=np.float32)],
        outputs=[ct.TensorType(name="embedding", dtype=np.float32)],
        convert_to="mlprogram",
        compute_precision=ct.precision.FLOAT16,
        minimum_deployment_target=ct.target.iOS17,
    )
    converted.short_description = (
        f"DINOv2-small zero-shot pet embedder ({EMBEDDER_REPO} @ {EMBEDDER_REVISION[:12]}), [CLS | patch mean]. "
        "Converted by Tenshu from PyTorch fp32 to a Core ML ML Program with fp16 compute; position table baked for 224."
    )
    converted.license = LICENCE
    return converted, wrapped, delta


def convert_detector(checkpoint: str):
    import coremltools as ct
    import numpy as np
    import torch
    import torch.nn.functional as F

    if sha256_of(checkpoint) != DETECTOR_SHA256:
        raise SystemExit(f"{checkpoint} is not the pinned RF-DETR Nano checkpoint ({DETECTOR_SOURCE_URL})")
    from rfdetr import RFDETRNano
    from rfdetr.models.ops.modules.ms_deform_attn import MSDeformAttn

    net = RFDETRNano(pretrain_weights=checkpoint).model.model.eval()
    net.export()
    probe = torch.rand(1, 3, DETECTOR_SIDE, DETECTOR_SIDE, generator=torch.Generator().manual_seed(20261119))
    with torch.no_grad():
        before = [t.clone() for t in net.forward_export(probe)[:2]]

    def meshgrid_ij(*tensors, indexing="ij"):
        assert indexing == "ij" and len(tensors) == 2
        a, b = (t.reshape(-1) for t in tensors)
        return a[:, None].expand(a.shape[0], b.shape[0]), b[None, :].expand(a.shape[0], b.shape[0])

    def forward_batch_one(self, query, reference_points, input_flatten, input_spatial_shapes,
                          input_level_start_index, input_padding_mask=None, input_spatial_shapes_hw=None):
        _, queries, _ = query.shape
        _, length, _ = input_flatten.shape
        heads, levels, points = self.n_heads, self.n_levels, self.n_points
        value = self.value_proj(input_flatten)[0]
        if input_padding_mask is not None:
            value = value.masked_fill(input_padding_mask[0][..., None], 0.0)
        offsets = self.sampling_offsets(query)[0].view(queries, heads, levels, points, 2)
        weights = F.softmax(self.attention_weights(query)[0].view(queries, heads, levels * points), -1)
        reference = reference_points[0]
        if reference.shape[-1] == 2:
            normaliser = torch.stack([input_spatial_shapes[..., 1], input_spatial_shapes[..., 0]], -1).to(offsets.dtype)
            locations = reference[:, None, :, None, :] + offsets / normaliser[None, None, :, None, :]
        else:
            locations = reference[:, None, :, None, :2] + offsets / points * reference[:, None, :, None, 2:] * 0.5
        head_dim = self.d_model // heads
        value = value.transpose(0, 1).reshape(heads, head_dim, length)
        # Fixed for the fixed input: read on the untraced check run, constants in the trace.
        shapes = input_spatial_shapes_hw or getattr(self, "_fixed_shapes", None)
        if shapes is None:
            shapes = [(int(h), int(w)) for h, w in input_spatial_shapes.tolist()]
            self._fixed_shapes = shapes
        grids = 2 * locations - 1
        sampled, start = [], 0
        for level, (height, width) in enumerate(shapes):
            level_value = value[:, :, start:start + height * width].reshape(heads, head_dim, height, width)
            start += height * width
            sampled.append(F.grid_sample(level_value, grids[:, :, level].transpose(0, 1), mode="bilinear",
                                         padding_mode="zeros", align_corners=False))
        stacked = torch.stack(sampled, dim=-2).flatten(-2)
        out = (stacked * weights.transpose(0, 1).reshape(heads, 1, queries, levels * points)).sum(-1)
        return self.output_proj(out.reshape(heads * head_dim, queries).transpose(0, 1)[None])

    torch.meshgrid = meshgrid_ij
    MSDeformAttn.forward = forward_batch_one
    with torch.no_grad():
        after = net.forward_export(probe)[:2]
    delta = max(float((b - a).abs().max()) for b, a in zip(before, after))
    if delta > 1e-4:
        raise SystemExit(f"detector: the rank-5 rewrite moved the output by {delta}")

    class DetsLabels(torch.nn.Module):
        def __init__(self, inner):
            super().__init__()
            self.inner = inner

        def forward(self, x):
            out = self.inner.forward_export(x)
            return out[0], out[1]

    wrapped = DetsLabels(net).eval()
    traced = torch.jit.trace(wrapped, probe)
    converted = ct.convert(
        traced,
        inputs=[ct.TensorType(name="input", shape=(1, 3, DETECTOR_SIDE, DETECTOR_SIDE), dtype=np.float32)],
        outputs=[ct.TensorType(name="dets", dtype=np.float32), ct.TensorType(name="labels", dtype=np.float32)],
        convert_to="mlprogram",
        compute_precision=ct.precision.FLOAT16,
        minimum_deployment_target=ct.target.iOS17,
    )
    converted.short_description = (
        "RF-DETR Nano COCO (roboflow/rf-detr, checkpoint_best_regular) as Pets' cat and dog detector. "
        "Converted by Tenshu from PyTorch fp32 to a Core ML ML Program with fp16 compute; batch-1 attention rewrite."
    )
    converted.license = LICENCE
    return converted, wrapped, delta


def self_check(tar_path: str, helpers, reference, outputs, floor: float, compare) -> dict:
    """Unpack as a device would, run Core ML on fixed probes, and compare with the torch source."""
    import coremltools as ct
    import numpy as np
    import torch

    with tempfile.TemporaryDirectory() as tmp:
        package = helpers.unpack(tar_path, tmp)
        model = ct.models.MLModel(package, compute_units=ct.ComputeUnit.CPU_ONLY)
        side = model.get_spec().description.input[0].type.multiArrayType.shape[-1]
        rng = np.random.default_rng(20261119)
        worst = 1.0
        for _ in range(4):
            x = rng.normal(size=(1, 3, side, side)).astype(np.float32)
            got = model.predict({"input": x})
            with torch.no_grad():
                want = reference(torch.from_numpy(x))
            want = want if isinstance(want, tuple) else (want,)
            worst = min(worst, compare([np.asarray(got[name]) for name in outputs], [w.numpy() for w in want]))
    if worst < floor:
        raise SystemExit(f"{os.path.basename(tar_path)}: self-check {worst:.5f} is under {floor}")
    return {"probes": 4, "worst": round(worst, 6), "floor": floor}


def embedder_agreement(got, want) -> float:
    import numpy as np

    a, b = got[0].reshape(-1)[:TEMPLATE].astype(np.float64), want[0].reshape(-1)[:TEMPLATE].astype(np.float64)
    return float(a @ b / np.linalg.norm(a) / np.linalg.norm(b))


def detector_agreement(got, want) -> float:
    """1 − the largest disagreement in what Pets reads: the cat and dog scores of all 300 queries, and the
    boxes of the queries the source is confident in (≥ 0.3, `AnimalDetectorModel.minConfidence`).

    ⚠ Scores, not the most confident boxes: on a noise probe no query is an animal, and the top queries are
    near-ties whose boxes move freely between precisions while meaning nothing. Real-photo box parity is
    M6's measurement (`photos-pets.md` §10.13), not this check's.
    """
    import numpy as np

    def scores(logits):
        z = logits.reshape(QUERIES, CLASSES)[:, [17, 18]].astype(np.float64)
        return 1.0 / (1.0 + np.exp(-z))

    got_scores, want_scores = scores(got[1]), scores(want[1])
    worst = float(np.abs(got_scores - want_scores).max())
    confident = want_scores.max(1) >= 0.3
    if confident.any():
        dets_got, dets_want = got[0].reshape(QUERIES, 4), want[0].reshape(QUERIES, 4)
        worst = max(worst, float(np.abs(dets_got[confident] - dets_want[confident]).max()))
    return 1.0 - worst


def package(model, stem: str, out_dir: str, helpers) -> tuple:
    package_dir = os.path.join(out_dir, f"{stem}.mlpackage")
    model.save(package_dir)
    helpers.canonicalise_package(package_dir)
    digests = helpers.package_digests(package_dir)
    tar_path = os.path.join(out_dir, f"{stem}.mlpackage.tar")
    helpers.write_tar(package_dir, tar_path)
    shutil.rmtree(package_dir)
    return tar_path, digests


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--detector-checkpoint", required=True, help=f"the RF-DETR Nano .pth ({DETECTOR_SOURCE_URL})")
    parser.add_argument("--out", required=True)
    parser.add_argument("--allow-unpinned", action="store_true")
    args = parser.parse_args()
    if platform.system() != "Darwin":
        raise SystemExit("macOS only: the self-check runs Core ML")
    installed = check_toolchain(args.allow_unpinned)
    helpers = face_helpers()
    os.makedirs(args.out, exist_ok=True)
    stage = tempfile.mkdtemp(prefix="pets-coreml-", dir=args.out)
    try:
        manifest = {"toolchain": installed, "python": platform.python_version(), "artifacts": {}}

        embedder, embedder_source, embedder_delta = convert_embedder()
        tar, digests = package(embedder, EMBEDDER_STEM, stage, helpers)
        manifest["artifacts"][os.path.basename(tar)] = {
            "role": "petEmbedder", "source": {"repo": EMBEDDER_REPO, "revision": EMBEDDER_REVISION},
            "bakedPositionTableDelta": embedder_delta,
            "selfCheck": self_check(tar, helpers, embedder_source, ["embedding"], 0.999, embedder_agreement),
            "sha256": sha256_of(tar), "byteSize": os.path.getsize(tar), "packageFiles": digests,
        }

        detector, detector_source, detector_delta = convert_detector(args.detector_checkpoint)
        tar, digests = package(detector, DETECTOR_STEM, stage, helpers)
        manifest["artifacts"][os.path.basename(tar)] = {
            "role": "animalDetector", "source": {"url": DETECTOR_SOURCE_URL, "sha256": DETECTOR_SHA256},
            "rank5RewriteDelta": detector_delta,
            "selfCheck": self_check(tar, helpers, detector_source, ["dets", "labels"], 0.97, detector_agreement),
            "sha256": sha256_of(tar), "byteSize": os.path.getsize(tar), "packageFiles": digests,
        }

        with open(os.path.join(stage, MANIFEST), "w", encoding="utf-8") as handle:
            json.dump(manifest, handle, indent=2, sort_keys=True)
            handle.write("\n")
        for name in os.listdir(stage):
            os.replace(os.path.join(stage, name), os.path.join(args.out, name))
        for name, artifact in manifest["artifacts"].items():
            print(f"{name}: {artifact['byteSize']} bytes, sha256 {artifact['sha256']}, self-check {artifact['selfCheck']['worst']}")
    finally:
        shutil.rmtree(stage, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
