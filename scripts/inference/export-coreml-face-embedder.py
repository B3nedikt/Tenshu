#!/usr/bin/env python3
"""Export AuraFace's fp32 embedder to a **Core ML fp16 ML Program**, as one reproducible tar.

The iOS artifact of the face-grouping checkpoint (`docs/proposals/ios-face-grouping.md` §5, §7 F2):
the same `glintr100.onnx` Android's `auraface_int8.onnx` was quantised from, converted a second time
for `platform.CoreML`. It is the `faceEmbedder` of the `auraface-v1-int8` / `coreml+vision` entry in
`deploy/models/models.json`; Apple Vision detects, so this is the entry's only file.

    python3.13 -m venv venv
    ./venv/bin/pip install -r scripts/inference/requirements-coreml-face-export.txt
    ./venv/bin/python scripts/inference/export-coreml-face-embedder.py \
        --source /path/to/glintr100.onnx \
        --faces  /path/to/vec2face/asset/perturbation.png \
        --out    /path/to/out

Produces `auraface_fp16.mlpackage.tar` and `export-manifest.json`. macOS only: the self-check loads
the archive through Core ML.

⚠⚠ **The source is one file, pinned by digest, and the script refuses any other.** `fal/AuraFace-v1`
at commit `af6d057c9b0e…b609`, `glintr100.onnx`, fp32. Never the int8 QDQ model: it is Android's
quantisation, and converting it would stack a second precision loss on the first.

⚠⚠ **The archive is byte-reproducible**, which the SigLIP archives are not
(`ios-on-device-inference.md` §9: two runs, two digests). Three things in a saved `.mlpackage`
vary per run, and all three are fixed here before the tar is written:
- **the file times**, which `write_tar` (shared with `export-coreml-towers.py`) zeroes, with the
  owners, modes and entry order;
- **`Manifest.json`'s item identifiers**, random UUIDs coremltools mints on every save. They are
  keys, not content: Core ML resolves the root model through `rootModelIdentifier`, so any
  consistent set loads. `canonical_manifest` replaces each with a UUIDv5 of the item's own path;
- **`model.mlmodel`'s field order.** Two runs write the same message (their text forms are
  identical) in different bytes: protobuf leaves map order unspecified unless asked. It is
  re-serialised with `deterministic=True`, which sorts map keys; the parse of the result must equal
  the parse of what coremltools wrote, or nothing is written.
`weight.bin` needed nothing: it was byte-identical across runs and to F1a's. The manifest records
the digest of every file inside the package, so a run that differs says which file moved. Two runs
of this script on the pinned toolchain gave the same archive digest (`ios-face-grouping.md` §12).

⚠⚠ **The toolchain is pinned, and the script refuses any other** (`requirements-coreml-face-export.txt`,
which includes the SigLIP export's set). Reproducible means *on that toolchain*: another coremltools
or torch traces another graph, and the digest moves with it.

⚠⚠ **Plain tensor input, never `ct.ImageType`.** The input is what `ImagePreprocessing` produces
(RGB, `(x − 127.5) / 127.5`, 112×112, CHW), the arithmetic Android's calibration was measured on.

⚠ **The archive is checked before it is promoted**: unpacked again, loaded by Core ML under the
product's compute units, and compared per input with the fp32 source under ONNX Runtime. Fixed
synthetic inputs always; with `--faces`, Vec2Face's public synthetic grid as well (80 faces; the
real photograph in each row's first column is skipped, as `quantise-face-embedder.py` skips it).
Below `--min-cosine` nothing is written. The clustering itself (the verified library, the 2× rule)
was measured on these bytes' predecessor in F1a/F1b and is not re-run here.
"""

import argparse
import hashlib
import importlib.util
import json
import os
import platform
import shutil
import sys
import tarfile
import tempfile
import uuid

PINNED = "requirements-coreml-face-export.txt"
# The interpreter the pins were resolved and run under; the requirements file states it.
PINNED_PYTHON = "3.13"
MANIFEST = "export-manifest.json"
# Every package whose version is recorded and must match its pin.
TOOLCHAIN = frozenset({"coremltools", "torch", "numpy", "onnx", "onnx2torch", "onnxruntime", "pillow"})
STEM = "auraface_fp16"

SOURCE_REPO = "fal/AuraFace-v1"
# ⚠ The full commit, resolved from the short `af6d057c` the earlier docs cite; it is the repo's
# head, "Create LICENSE.md", 2026-08-26 upstream time. The signer refuses anything shorter.
SOURCE_REVISION = "af6d057c9b0ec4071d4c49c80e3539258798b609"
SOURCE_FILE = "glintr100.onnx"
SOURCE_SHA256 = "a7933ea5330113b01c9b60351d8f4c33003f145d8470ac5f0e52ee2effe25c60"
SOURCE_BYTES = 260_694_151

# Vec2Face `asset/perturbation.png`, the public synthetic grid F1a's public set was sliced from.
FACES_SHA256 = "99b83a9b00c7547f1063d8055b8c4e13c63e5f77831d71ef7aec62fdfb0d1215"

SIDE = 112
WIDTH = 512
INPUT = "input"
OUTPUT = "embedding"

# ⚠ Fixed: the item identifiers are derived from it, so changing it changes every archive.
MANIFEST_NAMESPACE = uuid.UUID("6f1f5b0e-7a3c-5d2e-9a41-3c0e8b7f2d10")

# Shown by Xcode and `MLModel.modelDescription`. ⚠ The second half is Apache-2.0 §4(b)'s notice that
# the file was changed; `models.md` §5.4 carries the rest of the obligation.
DESCRIPTION = (
    f"AuraFace v1 face embedder ({SOURCE_REPO} {SOURCE_FILE} @ {SOURCE_REVISION[:12]}). "
    "Converted by Tenshu from ONNX fp32 to a Core ML ML Program with fp16 compute; weights otherwise unchanged."
)
LICENCE = "Apache-2.0"


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def pinned_versions(name: str = PINNED, seen=None) -> dict:
    """`name==version` lines of a requirements file beside this script, following `-r` includes."""
    seen = set() if seen is None else seen
    if name in seen:
        return {}
    seen.add(name)
    pins = {}
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), name), encoding="utf-8") as handle:
        for line in handle:
            line = line.split("#", 1)[0].strip()
            if line.startswith("-r "):
                pins.update(pinned_versions(line[3:].strip(), seen))
            elif "==" in line:
                package, version = line.split("==", 1)
                pins[package.strip().lower()] = version.strip()
    return pins


def check_toolchain(names, allow_unpinned: bool) -> tuple:
    """Returns (installed, matches_pins). Exits when a pin is missed and that is not allowed."""
    from importlib.metadata import PackageNotFoundError, version

    pins = {name: pin for name, pin in pinned_versions().items() if name in names}
    missing = sorted(set(names) - set(pins))
    if missing:
        raise SystemExit(f"toolchain: {missing} have no pin in {PINNED}")
    installed = {}
    for name in pins:
        try:
            installed[name] = version(name)
        except PackageNotFoundError:
            installed[name] = None
    wrong = {name: (installed[name], pin) for name, pin in pins.items() if installed[name] != pin}
    # ⚠ The interpreter is part of the toolchain: the same wheels under another Python are another build.
    running = platform.python_version()
    if running.split(".")[:2] != PINNED_PYTHON.split("."):
        wrong["python"] = (running, PINNED_PYTHON)
    if wrong and not allow_unpinned:
        for name, (have, want) in sorted(wrong.items()):
            print(f"toolchain: {name} is {have}, the pinned version is {want}", file=sys.stderr)
        raise SystemExit(
            f"refusing to export outside the pinned toolchain; install scripts/inference/{PINNED} "
            "or pass --allow-unpinned"
        )
    return installed, not wrong


def check_source(path: str) -> None:
    """Exits unless `path` is the pinned fp32 checkpoint."""
    size = os.path.getsize(path)
    digest = sha256_of(path)
    if digest != SOURCE_SHA256 or size != SOURCE_BYTES:
        raise SystemExit(
            f"refusing: {path} is {size} B with sha256 {digest}; the pinned source is "
            f"{SOURCE_REPO}@{SOURCE_REVISION} {SOURCE_FILE}, {SOURCE_BYTES} B, sha256 {SOURCE_SHA256}"
        )


def canonical_manifest(text: str) -> str:
    """`Manifest.json` with each random item identifier replaced by a UUIDv5 of the item's path.

    ⚠ The identifiers are the only per-save content coremltools writes into a package. The rewrite
    keeps every entry and remaps `rootModelIdentifier` with them; an identifier the root names that
    has no entry is refused rather than guessed.
    """
    manifest = json.loads(text)
    entries = manifest["itemInfoEntries"]
    renamed = {}
    for old, entry in entries.items():
        new = str(uuid.uuid5(MANIFEST_NAMESPACE, entry["path"])).upper()
        if new in renamed.values():
            raise ValueError(f"two package items share the path {entry['path']!r}")
        renamed[old] = new
    root = manifest.get("rootModelIdentifier")
    if root not in renamed:
        raise ValueError(f"rootModelIdentifier {root!r} names no item")
    manifest["itemInfoEntries"] = {renamed[old]: entry for old, entry in entries.items()}
    manifest["rootModelIdentifier"] = renamed[root]
    return json.dumps(manifest, indent=4, sort_keys=True) + "\n"


def canonical_spec(data: bytes) -> bytes:
    """`model.mlmodel` re-serialised with sorted map keys: the same message, in one byte order.

    ⚠ Refuses a rewrite that is not the same message. A canonical form that changed the model would
    make the archive reproducible and wrong.
    """
    from coremltools.proto import Model_pb2

    written = Model_pb2.Model()
    written.ParseFromString(data)
    canonical = written.SerializeToString(deterministic=True)
    reread = Model_pb2.Model()
    reread.ParseFromString(canonical)
    if reread != written:
        raise SystemExit("model.mlmodel: the deterministic re-serialisation is not the same message")
    return canonical


def canonicalise_package(package_dir: str) -> None:
    path = os.path.join(package_dir, "Manifest.json")
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(canonical_manifest(text))
    spec = os.path.join(package_dir, "Data", "com.apple.CoreML", "model.mlmodel")
    with open(spec, "rb") as handle:
        data = handle.read()
    with open(spec, "wb") as handle:
        handle.write(canonical_spec(data))


def package_digests(package_dir: str) -> dict:
    """sha256 of every file in the package, by its path inside it — what tells two runs apart."""
    found = {}
    for directory, subdirectories, files in os.walk(package_dir):
        subdirectories.sort()
        for name in sorted(files):
            full = os.path.join(directory, name)
            found[os.path.relpath(full, package_dir)] = sha256_of(full)
    return found


def _towers_module():
    """`export-coreml-towers.py`, for its archive rule: one rule for every Core ML tar in the catalog."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "export-coreml-towers.py")
    sys.dont_write_bytecode = True  # no `__pycache__` beside the scripts
    spec = importlib.util.spec_from_file_location("export_coreml_towers", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_tar(package_dir: str, tar_path: str) -> None:
    """Sorted entries, zeroed times and owners, fixed modes, ustar: `export-coreml-towers.py`'s rule."""
    _towers_module().write_tar(package_dir, tar_path)


def unpack(tar_path: str, into: str) -> str:
    """Unpack the archive as the device would, and return its one top-level `.mlpackage`."""
    with tarfile.open(tar_path, "r") as archive:
        tops = {member.name.split("/", 1)[0] for member in archive.getmembers()}
        if len(tops) != 1 or not next(iter(tops)).endswith(".mlpackage"):
            raise SystemExit(f"{tar_path}: expected one top-level .mlpackage, found {sorted(tops)}")
        archive.extractall(into, filter="data")
    return os.path.join(into, next(iter(tops)))


def cosine(a, b) -> float:
    import numpy as np

    a = np.asarray(a, dtype=np.float64).reshape(-1)
    b = np.asarray(b, dtype=np.float64).reshape(-1)
    return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b)))


def synthetic_probes():
    """Three fixed inputs in [-1, 1], the range `ImagePreprocessing` produces (as the SigLIP export)."""
    import numpy as np

    count = 3 * SIDE * SIDE
    ramp = ((np.arange(count, dtype=np.int64) * 7919) % 2001).astype(np.float32) / 1000.0 - 1.0
    probes = [ramp.reshape(1, 3, SIDE, SIDE)]
    generator = np.random.default_rng(20261110)
    for _ in range(2):
        probes.append(generator.uniform(-1.0, 1.0, size=(1, 3, SIDE, SIDE)).astype(np.float32))
    return probes


# `perturbation.png`'s geometry (`quantise-face-embedder.py`'s SCORING): 336-px tiles under a header;
# x0 skips column 0, the real reference photograph of each identity.
FACE_TILE = 336
FACE_GRID = (420, 216, 10, 8)  # x0, y0, columns, rows


def face_probes(path: str):
    """The 80 synthetic faces of Vec2Face's public grid, preprocessed as the product preprocesses."""
    import numpy as np
    from PIL import Image

    digest = sha256_of(path)
    if digest != FACES_SHA256:
        raise SystemExit(f"--faces {path} has sha256 {digest}, not Vec2Face perturbation.png's {FACES_SHA256}")
    sheet = Image.open(path).convert("RGB")
    x0, y0, columns, rows = FACE_GRID
    probes = []
    for r in range(rows):
        for c in range(columns):
            x, y = x0 + c * FACE_TILE, y0 + r * FACE_TILE
            face = sheet.crop((x, y, x + FACE_TILE, y + FACE_TILE)).resize((SIDE, SIDE), Image.BILINEAR)
            pixels = (np.asarray(face, dtype=np.float32) - 127.5) / 127.5
            probes.append(np.ascontiguousarray(pixels.transpose(2, 0, 1)[None]))
    return probes


def self_check(tar_path: str, source: str, sets: dict, minimum: float) -> dict:
    """The archive, unpacked and loaded by Core ML, against the fp32 source under ONNX Runtime."""
    import coremltools as ct
    import numpy as np
    import onnxruntime as ort

    reference = ort.InferenceSession(source, providers=["CPUExecutionProvider"])
    reference_input = reference.get_inputs()[0].name
    result = {}
    with tempfile.TemporaryDirectory() as scratch:
        package = unpack(tar_path, scratch)
        # ⚠ The product's compute units (`CoreMlSession`), so the check runs what a phone runs.
        model = ct.models.MLModel(package, compute_units=ct.ComputeUnit.CPU_AND_NE)
        spec = model.get_spec()
        names = ([i.name for i in spec.description.input], [o.name for o in spec.description.output])
        if names != ([INPUT], [OUTPUT]):
            raise SystemExit(f"the archive's inputs/outputs are {names}, not ([{INPUT!r}], [{OUTPUT!r}])")
        for label, probes in sets.items():
            cosines = []
            for probe in probes:
                expected = reference.run(None, {reference_input: probe})[0]
                produced = np.asarray(model.predict({INPUT: probe})[OUTPUT])
                if produced.size != WIDTH or not np.all(np.isfinite(produced)) or not np.any(produced):
                    raise SystemExit(f"{label}: the archive produced a {produced.shape} vector that is not a finite, non-zero {WIDTH}-d embedding")
                cosines.append(cosine(produced, expected))
            summary = {"n": len(cosines), "mean": float(np.mean(cosines)), "min": float(np.min(cosines))}
            print(f"{label}: cosine against fp32 ORT over {summary['n']}: mean {summary['mean']:.6f}, min {summary['min']:.6f}")
            if summary["min"] < minimum:
                raise SystemExit(f"{label}: worst cosine {summary['min']:.6f} is below --min-cosine {minimum}; nothing written")
            result[label] = summary
    return result


def convert(source: str):
    """ONNX → onnx2torch → traced TorchScript → fp16 ML Program, with explicit names and types."""
    import coremltools as ct
    import numpy as np
    import onnx
    import onnx2torch
    import torch

    graph = onnx.load(source)
    # ⚠ The preprocessing is outside the graph. A graph that normalised internally would be fed
    # normalised pixels twice (`quantise-face-embedder.py` makes the same check).
    first = graph.graph.node[0].op_type
    if first != "Conv":
        raise SystemExit(f"the source graph starts with {first}, not Conv: it may normalise internally")
    module = onnx2torch.convert(graph).eval()
    example = torch.zeros(1, 3, SIDE, SIDE)
    with torch.no_grad():
        traced = torch.jit.trace(module, example)
    converted = ct.convert(
        traced,
        inputs=[ct.TensorType(name=INPUT, shape=(1, 3, SIDE, SIDE), dtype=np.float32)],
        outputs=[ct.TensorType(name=OUTPUT, dtype=np.float32)],
        convert_to="mlprogram",
        compute_precision=ct.precision.FLOAT16,
        minimum_deployment_target=ct.target.iOS17,
    )
    converted.short_description = DESCRIPTION
    converted.license = LICENCE
    converted.author = "Tenshu, from fal.ai's AuraFace v1"
    return converted


def promote(stage: str, out: str) -> None:
    """Move a finished export into `out`; the manifest goes first out and last in.

    An interrupted promotion therefore leaves **no** manifest, which a consumer must treat as no
    export at all, rather than an old manifest describing a new archive.
    """
    stale = os.path.join(out, MANIFEST)
    if os.path.exists(stale):
        os.remove(stale)
    for name in sorted(n for n in os.listdir(stage) if n != MANIFEST):
        os.replace(os.path.join(stage, name), os.path.join(out, name))
    os.replace(os.path.join(stage, MANIFEST), os.path.join(out, MANIFEST))


def export(args) -> int:
    """Export into a staging directory and promote only a complete, self-checked archive."""
    installed, pinned = check_toolchain(TOOLCHAIN, args.allow_unpinned)
    check_source(args.source)
    out = os.path.abspath(args.out)
    os.makedirs(out, exist_ok=True)
    stage = tempfile.mkdtemp(prefix=".export-staging-", dir=out)
    try:
        converted = convert(args.source)
        tar_path = os.path.join(stage, f"{STEM}.mlpackage.tar")
        with tempfile.TemporaryDirectory() as scratch:
            package = os.path.join(scratch, f"{STEM}.mlpackage")
            converted.save(package)
            canonicalise_package(package)
            contents = package_digests(package)
            write_tar(package, tar_path)
        del converted

        sets = {"synthetic": synthetic_probes()}
        if args.faces:
            sets["vec2face"] = face_probes(args.faces)
        checks = self_check(tar_path, args.source, sets, args.min_cosine)

        manifest = {
            "source": {
                "repo": SOURCE_REPO,
                "revision": SOURCE_REVISION,
                "files": {SOURCE_FILE: SOURCE_SHA256},
            },
            # ⚠ Beside `source`, not in it: `source` is copied into `models.json` as the signer's
            # strict `ModelSource`, which has no such key.
            "sourceBytes": SOURCE_BYTES,
            "toolchain": {"python": platform.python_version(), "pinned": pinned, **installed},
            "conversion": {
                "route": "onnx2torch -> torch.jit.trace -> coremltools.convert",
                "format": "mlprogram",
                "computePrecision": "FLOAT16",
                "minimumDeploymentTarget": "iOS17",
                "archive": "ustar, uncompressed, one top-level <stem>.mlpackage directory; zeroed times, "
                "owners and fixed modes; Manifest.json item identifiers are UUIDv5 of each item's path; "
                "model.mlmodel re-serialised with deterministic map order",
                "input": {"name": INPUT, "shape": [1, 3, SIDE, SIDE], "type": "Float32"},
                "output": {"name": OUTPUT, "width": WIDTH, "type": "Float32"},
            },
            "selfCheck": {
                "minCosine": args.min_cosine,
                "against": "fp32 source, onnxruntime CPUExecutionProvider",
                "computeUnits": "CPU_AND_NE",
                **checks,
            },
            "package": contents,
            "artifacts": {
                "faceEmbedder": {
                    "file": os.path.basename(tar_path),
                    "sha256": sha256_of(tar_path),
                    "bytes": os.path.getsize(tar_path),
                }
            },
        }
        with open(os.path.join(stage, MANIFEST), "w", encoding="utf-8") as handle:
            json.dump(manifest, handle, indent=2, sort_keys=True)
            handle.write("\n")
        promote(stage, out)
        entry = manifest["artifacts"]["faceEmbedder"]
        print(f"wrote {entry['file']} ({entry['bytes']:,} B) sha256 {entry['sha256']}")
        print(f"wrote {os.path.join(out, MANIFEST)} sha256 {sha256_of(os.path.join(out, MANIFEST))}")
        return 0
    finally:
        shutil.rmtree(stage, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", required=True, help=f"A LOCAL {SOURCE_FILE}, fp32 (checked against the pin).")
    parser.add_argument("--out", required=True)
    parser.add_argument("--faces", help="Vec2Face's perturbation.png, for the self-check on synthetic faces.")
    parser.add_argument("--min-cosine", type=float, default=0.999, help="Per-input floor against fp32.")
    parser.add_argument("--allow-unpinned", action="store_true", help=f"Run outside {PINNED}; the manifest says so.")
    return export(parser.parse_args())


if __name__ == "__main__":
    sys.exit(main())
