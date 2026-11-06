#!/usr/bin/env python3
"""Export a SigLIP checkpoint's two towers to **Core ML fp16 ML Programs**, one tar per tower.

The iOS artifact of the pinned checkpoint (`docs/proposals/ios-on-device-inference.md` §3.4): the
same weights `export-vision-tower.py` and `export-text-tower.py` export to ONNX, converted a second
time for `platform.CoreML`.

    python3.13 -m venv venv
    ./venv/bin/pip install -r scripts/inference/requirements-coreml-export.txt
    ./venv/bin/python scripts/inference/export-coreml-towers.py \
        --source   /path/to/google--siglip-base-patch16-256-multilingual \
        --revision <the upstream commit that directory is a snapshot of> \
        --out      /path/to/out

Produces `vision_fp16.mlpackage.tar`, `text_fp16.mlpackage.tar`, `tokenizer.bin` and
`export-manifest.json`.

⚠ **Explicit source directory, never a hub tag** (`models.md` R6), and a **required** `--revision`:
the repo pins an id and paths for its ONNX artifacts and no upstream revision, so nothing records
which snapshot they came from. This script records it, with the sha256 of every source file it read.

⚠⚠ **The toolchain is pinned, and the script refuses any other** (`requirements-coreml-export.txt`).
The first conversion of this checkpoint ran on coremltools 9.0 with torch 2.13 and numpy 2.5, a
pairing coremltools does not test, and only worked after a one-line edit inside coremltools
(`np.asarray(x.val).item()` in `_cast`): numpy 2.4 turned the size-1-array-to-scalar conversion that
line relied on into an error. An archive from a hand-patched environment is not reproducible. The
pinned set converts both towers unpatched; `--allow-unpinned` exists to try a newer set, and the
manifest then says the versions were not the pinned ones.

⚠⚠ **Plain tensor inputs, never `ct.ImageType`.** An image input would let Core ML resize and scale
by its own rules and bypass `ImagePreprocessing`, the shared arithmetic every platform's vectors
depend on. The names and types below are the ones `CoreMlSession`'s shape guard checks.

⚠ **Each tower is checked against fp32 PyTorch before its archive is written**, on fixed inputs, and
an archive below `--min-cosine` is not written. That is a per-tower check. It does not show the
pair still shares a search space: the multilingual retrieval gate (`retrieval-gate.py`, §3.4) is
run on the final archives before they are published.

`--fixture <dir>` writes tiny ML Programs with the real towers' names and shapes, for
`:inference-service-proxy`'s `iosTest`. It needs only coremltools, torch and numpy.
"""

import argparse
import hashlib
import importlib.util
import io
import json
import os
import platform
import shutil
import sys
import tarfile
import tempfile

PINNED = "requirements-coreml-export.txt"
# The interpreter the pins were resolved and run under; the requirements file states it.
PINNED_PYTHON = "3.13"
# `retrieval-gate.py`'s result file, beside the export it judged (its `RESULT_NAME`).
GATE_RESULT = "retrieval-gate-result.json"
DEFAULT_REPO = "google/siglip-base-patch16-256-multilingual"
DEFAULT_SEQUENCE_LENGTH = 64

# ⚠ `</s>`, the id the checkpoint pads with (`tokenizer_config.json`'s `pad_token`; `models.md`
# §3.9.4). The self-check feeds the text tower what the Kotlin tokenizer feeds it.
EOS = 1

# Token ids of fixed captions, from `SiglipTextTokenizerTest`'s golden cases. Fixed ids rather than
# a tokenizer, so the check has no dependency that could move under it.
TEXT_PROBES = [
    [259, 262, 259, 114441, 910, 4582, 1],  # a sunset over water
    [7399, 36148, 859, 163403, 265, 265, 1],  # mein hund im schnee
    [317, 64078, 269, 259, 57109, 865, 283, 1420, 1],  # coucher de soleil sur la mer
]


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def pinned_versions() -> dict:
    """`name==version` lines of the requirements file beside this script."""
    pins = {}
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), PINNED), encoding="utf-8") as handle:
        for line in handle:
            line = line.split("#", 1)[0].strip()
            if "==" in line:
                name, version = line.split("==", 1)
                pins[name.strip().lower()] = version.strip()
    return pins


def installed_versions(names) -> dict:
    from importlib.metadata import PackageNotFoundError, version

    found = {}
    for name in names:
        try:
            found[name] = version(name)
        except PackageNotFoundError:
            found[name] = None
    return found


def check_toolchain(names, allow_unpinned: bool) -> tuple:
    """Returns (installed, matches_pins). Exits when a pin is missed and that is not allowed."""
    pins = {name: pin for name, pin in pinned_versions().items() if name in names}
    installed = installed_versions(pins.keys())
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


def convert(module, example, input_name: str, input_dtype):
    """Trace `module` and convert it to an fp16 ML Program with explicit names and types."""
    import coremltools as ct
    import numpy as np
    import torch

    with torch.no_grad():
        traced = torch.jit.trace(module, example)
    return ct.convert(
        traced,
        inputs=[ct.TensorType(name=input_name, shape=tuple(example.shape), dtype=input_dtype)],
        # ⚠ Named, because the backend selects the pooled embedding BY NAME, and a traced model
        # converted without one gets a generated name. Float32 out, whatever the graph computes in.
        outputs=[ct.TensorType(name="pooler_output", dtype=np.float32)],
        convert_to="mlprogram",
        compute_precision=ct.precision.FLOAT16,
        minimum_deployment_target=ct.target.iOS17,
    )


def write_tar(package_dir: str, tar_path: str) -> None:
    """One uncompressed ustar of `package_dir`, the same bytes for the same package.

    ⚠ Sorted entries, zeroed times and owners, fixed modes. ustar, not pax: the device's reader
    (`TarReader.kt`) accepts regular files and directories and refuses extension headers, and ustar
    fails here, at export, on a name it cannot hold.
    """
    top = os.path.basename(package_dir.rstrip("/"))
    paths = []
    for directory, subdirectories, files in os.walk(package_dir):
        subdirectories.sort()
        relative = os.path.relpath(directory, package_dir)
        paths.append((top if relative == "." else f"{top}/{relative}", directory, True))
        for name in sorted(files):
            full = os.path.join(directory, name)
            paths.append((f"{top}/{os.path.relpath(full, package_dir)}", full, False))
    paths.sort(key=lambda entry: entry[0])

    with tarfile.open(tar_path, "w", format=tarfile.USTAR_FORMAT) as archive:
        for name, full, is_directory in paths:
            info = tarfile.TarInfo(name=name)
            info.mtime = 0
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            if is_directory:
                info.type = tarfile.DIRTYPE
                info.mode = 0o755
                archive.addfile(info)
            else:
                info.mode = 0o644
                info.size = os.path.getsize(full)
                with open(full, "rb") as handle:
                    archive.addfile(info, handle)


def save_as_tar(model, out_dir: str, stem: str) -> str:
    """Save `model` as `<stem>.mlpackage` in a temporary directory and tar it into `out_dir`."""
    tar_path = os.path.join(out_dir, f"{stem}.mlpackage.tar")
    with tempfile.TemporaryDirectory() as scratch:
        package = os.path.join(scratch, f"{stem}.mlpackage")
        model.save(package)
        write_tar(package, tar_path)
    return tar_path


def cosine(a, b) -> float:
    import numpy as np

    a = np.asarray(a, dtype=np.float64).reshape(-1)
    b = np.asarray(b, dtype=np.float64).reshape(-1)
    return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b)))


def vision_probes(side: int):
    """Three fixed inputs in [-1, 1], the range `ImagePreprocessing` produces."""
    import numpy as np

    count = 3 * side * side
    ramp = ((np.arange(count, dtype=np.int64) * 7919) % 2001).astype(np.float32) / 1000.0 - 1.0
    probes = [ramp.reshape(1, 3, side, side)]
    generator = np.random.default_rng(20261005)
    for _ in range(2):
        probes.append(generator.uniform(-1.0, 1.0, size=(1, 3, side, side)).astype(np.float32))
    return probes


def text_probes(length: int):
    import numpy as np

    return [np.array([ids + [EOS] * (length - len(ids))], dtype=np.int32) for ids in TEXT_PROBES]


def self_check(name: str, converted, reference_module, probes, input_name: str, minimum: float, to_torch) -> list:
    """Cosine of the converted tower against fp32 PyTorch on every probe. Exits below `minimum`."""
    import torch

    cosines = []
    for probe in probes:
        with torch.no_grad():
            reference = reference_module(to_torch(probe)).numpy()
        produced = converted.predict({input_name: probe})["pooler_output"]
        cosines.append(cosine(produced, reference))
    print(f"{name}: cosine against fp32 on {len(probes)} fixed inputs: " + ", ".join(f"{c:.6f}" for c in cosines))
    if min(cosines) < minimum:
        raise SystemExit(f"{name}: worst cosine {min(cosines):.6f} is below --min-cosine {minimum}; no archive written")
    return cosines


def pack_vocabulary(source: str, out: str) -> None:
    """The vocabulary is the ONNX entry's own file, so it is packed by the ONNX script's own code."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "export-text-tower.py")
    sys.dont_write_bytecode = True  # no `__pycache__` beside the scripts
    spec = importlib.util.spec_from_file_location("export_text_tower", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.pack_vocabulary(source, out)


def export_towers(args) -> int:
    """Export into a staging directory and promote only a complete, self-checked set.

    ⚠ Never written in place: a run that fails after the vision tower would otherwise leave a new
    vision archive beside an old text archive and an old manifest, and a publish step reading that
    directory would ship a mismatched pair under stale provenance.
    """
    out = os.path.abspath(args.out)
    os.makedirs(out, exist_ok=True)
    stage = tempfile.mkdtemp(prefix=".export-staging-", dir=out)
    try:
        status = _export_towers_into(args, stage)
        if status != 0:
            return status
        promote(stage, out)
        print(f"wrote {os.path.join(out, 'export-manifest.json')}")
        return 0
    finally:
        shutil.rmtree(stage, ignore_errors=True)


def promote(stage: str, out: str) -> None:
    """Move a finished export into `out`; the manifest goes first out and last in.

    A promotion interrupted part-way therefore leaves **no** manifest, which a consumer must treat
    as no export at all, rather than an old manifest describing new files.
    """
    manifest = "export-manifest.json"
    names = sorted(name for name in os.listdir(stage) if name != manifest)
    # ⚠ The retrieval gate's verdict is evidence about the bytes it ran on. It goes with the old
    # manifest, before any archive is replaced, or an earlier PASS would sit beside ungated bytes.
    for stale in (os.path.join(out, manifest), os.path.join(out, GATE_RESULT)):
        if os.path.exists(stale):
            os.remove(stale)
    for name in names:
        os.replace(os.path.join(stage, name), os.path.join(out, name))
    os.replace(os.path.join(stage, manifest), os.path.join(out, manifest))


def _export_towers_into(args, stage: str) -> int:
    installed, pinned = check_toolchain({"coremltools", "torch", "numpy", "transformers"}, args.allow_unpinned)

    import numpy as np
    import torch
    from transformers import SiglipModel


    # ⚠ Both sizes come from the checkpoint's own configs, never from a constant: a wrong one
    # produces a graph that accepts the wrong tensor silently.
    with open(os.path.join(args.source, "preprocessor_config.json"), encoding="utf-8") as handle:
        side = json.load(handle)["size"]["height"]
    with open(os.path.join(args.source, "tokenizer_config.json"), encoding="utf-8") as handle:
        length = int(json.load(handle).get("model_max_length") or DEFAULT_SEQUENCE_LENGTH)
    print(f"vision input {side}², text length {length} (from the checkpoint's configs)")

    # ⚠ Loaded as the FULL model and narrowed, as the ONNX scripts do: the tower classes read a
    # config shape a full checkpoint directory does not have.
    model = SiglipModel.from_pretrained(args.source, torch_dtype=torch.float32).eval()

    class Pooled(torch.nn.Module):
        """⚠ `pooler_output` only. SigLIP pools with a trained attention head; any other output
        is a different embedding space of the same width."""

        def __init__(self, tower, keyword):
            super().__init__()
            self.tower = tower
            self.keyword = keyword

        def forward(self, tensor):
            return self.tower(**{self.keyword: tensor}).pooler_output

    vision = Pooled(model.vision_model, "pixel_values").eval()
    text = Pooled(model.text_model, "input_ids").eval()

    vision_inputs = vision_probes(side)
    text_inputs = text_probes(length)

    outputs = {}
    checks = {}

    converted = convert(vision, torch.from_numpy(vision_inputs[0]), "pixel_values", np.float32)
    checks["vision"] = self_check(
        "vision", converted, vision, vision_inputs, "pixel_values", args.min_cosine, torch.from_numpy
    )
    outputs["vision"] = save_as_tar(converted, stage, "vision_fp16")
    del converted

    # ⚠ Traced with int64 ids, which is what the embedding lookup takes; the Core ML input is Int32,
    # the type `MLMultiArray` offers and the backend writes.
    as_long = lambda ids: torch.from_numpy(ids.astype(np.int64))
    converted = convert(text, as_long(text_inputs[0]), "input_ids", np.int32)
    checks["text"] = self_check("text", converted, text, text_inputs, "input_ids", args.min_cosine, as_long)
    outputs["text"] = save_as_tar(converted, stage, "text_fp16")
    del converted

    pack_vocabulary(args.source, stage)
    outputs["vocabulary"] = os.path.join(stage, "tokenizer.bin")

    source_files = [
        "model.safetensors",
        "config.json",
        "preprocessor_config.json",
        "tokenizer_config.json",
        "tokenizer.json",
    ]
    manifest = {
        "source": {
            "repo": args.repo,
            "revision": args.revision,
            "files": {name: sha256_of(os.path.join(args.source, name)) for name in source_files},
        },
        "toolchain": {
            "python": platform.python_version(),
            "pinned": pinned,
            **installed,
        },
        "conversion": {
            "format": "mlprogram",
            "computePrecision": "FLOAT16",
            "minimumDeploymentTarget": "iOS17",
            "archive": "ustar, uncompressed, one top-level <stem>.mlpackage directory",
            "vision": {"input": "pixel_values", "shape": [1, 3, side, side], "type": "Float32"},
            "text": {"input": "input_ids", "shape": [1, length], "type": "Int32"},
            "output": {"name": "pooler_output", "type": "Float32"},
        },
        "selfCheck": {
            "minCosine": args.min_cosine,
            "against": "fp32 PyTorch, same process",
            "vision": checks["vision"],
            "text": checks["text"],
        },
        "artifacts": {
            role: {"file": os.path.basename(path), "sha256": sha256_of(path), "bytes": os.path.getsize(path)}
            for role, path in outputs.items()
        },
    }
    manifest_path = os.path.join(stage, "export-manifest.json")
    with open(manifest_path, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2, sort_keys=True)
        handle.write("\n")
    for role, entry in manifest["artifacts"].items():
        print(f"wrote {entry['file']} ({entry['bytes'] / 1e6:.1f} MB) sha256 {entry['sha256']}")
    return 0


def export_fixtures(args) -> int:
    """Tiny ML Programs with the real towers' names and shapes, through the same `convert`.

    ⚠ They embed nothing. They exist so `CoreMlSession`'s guards meet a model Core ML really
    loaded: the right shape, a wrong side, an output of zeros, and the text tower's Int32 input.
    """
    check_toolchain({"coremltools", "torch", "numpy"}, args.allow_unpinned)

    import numpy as np
    import torch

    os.makedirs(args.fixture, exist_ok=True)
    width = 768

    class Vision(torch.nn.Module):
        def __init__(self, scale: float):
            super().__init__()
            self.head = torch.nn.Linear(3, width)
            self.scale = scale

        def forward(self, pixels):
            return self.head(pixels.mean(dim=(2, 3))) * self.scale

    class Text(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.embedding = torch.nn.Embedding(32, 4)
            self.head = torch.nn.Linear(4, width)

        def forward(self, ids):
            return self.head(self.embedding(ids).mean(dim=1))

    def vision(stem: str, side: int, scale: float) -> None:
        torch.manual_seed(7)
        module = Vision(scale).eval()
        converted = convert(module, torch.zeros(1, 3, side, side), "pixel_values", np.float32)
        print(f"wrote {save_as_tar(converted, args.fixture, stem)}")

    vision("fixture_vision", 256, 1.0)
    vision("fixture_vision_224", 224, 1.0)
    vision("fixture_vision_zero", 256, 0.0)

    torch.manual_seed(7)
    converted = convert(Text().eval(), torch.zeros(1, DEFAULT_SEQUENCE_LENGTH, dtype=torch.int64), "input_ids", np.int32)
    print(f"wrote {save_as_tar(converted, args.fixture, 'fixture_text')}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", help="A LOCAL checkpoint directory (R6).")
    parser.add_argument("--revision", help="The upstream commit --source is a snapshot of. Recorded, not resolved.")
    parser.add_argument("--repo", default=DEFAULT_REPO, help="The upstream repository, for the manifest.")
    parser.add_argument("--out")
    parser.add_argument("--min-cosine", type=float, default=0.99, help="Per-tower floor against fp32.")
    parser.add_argument("--fixture", metavar="DIR", help="Write the test fixtures to DIR and stop.")
    parser.add_argument("--allow-unpinned", action="store_true", help=f"Run outside {PINNED}; the manifest says so.")
    args = parser.parse_args()

    if args.fixture:
        return export_fixtures(args)
    missing = [flag for flag in ("source", "revision", "out") if not getattr(args, flag)]
    if missing:
        parser.error("missing " + ", ".join(f"--{flag}" for flag in missing))
    return export_towers(args)


if __name__ == "__main__":
    sys.exit(main())
