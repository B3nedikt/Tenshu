#!/usr/bin/env python3
"""The multilingual retrieval gate for a Core ML export, run on the **archives that will ship**.

`export-coreml-towers.py` checks each tower against fp32 on three fixed inputs. That does not show
the pair still shares a search space, so before an export is published this gate measures what
search does: text queries ranked over an image corpus, each converted pair compared with fp32
PyTorch (`docs/proposals/ios-on-device-inference.md` §2.2, §3.4).

    python3.13 -m venv venv
    ./venv/bin/pip install -r scripts/inference/requirements-retrieval-gate.txt
    ./venv/bin/python scripts/inference/retrieval-gate.py \
        --export      /path/to/export            # export-coreml-towers.py's --out, manifest included
        --source      /path/to/google--siglip-base-patch16-256-multilingual \
        --onnx-vision /path/to/vision_model_uint8.onnx \
        --onnx-text   /path/to/text_model_int8.onnx \
        --corpus      /path/to/a/cache/directory --fetch

Three pairs embed the same pixels and the same token rows, on this Mac:
  (i)   fp32 PyTorch, both towers: the reference;
  (ii)  the uint8 vision + int8 text ONNX pair the catalog publishes: the yardstick;
  (iii) the fp16 Core ML pair, unpacked from the export's two tars: the candidate.

Per query, against (i): top-1 agreement, top-5 and top-10 overlap, Spearman correlation over the
whole corpus. Reported as the mean and the worst query.

⚠⚠ **The pass rule: (iii) is at least as close to fp32 as (ii), on every figure in `FIGURES`.**
Equal passes. It is relative: it says Core ML costs search no more than the quantisation Android
and desktop already ship, and nothing about how good fp32 retrieval is.

⚠⚠ **The export directory is verified before anything is loaded.** `export-manifest.json` must be
there and must name the bytes on disk, as `inference-harness-ios/run-in3m.sh`'s `verify_export`
requires: the export removes the manifest first and writes it last, so two tar names without one
can be a mismatched old/new pair. `--source` must also be the checkpoint the manifest recorded, or
the reference would be other weights.

⚠⚠ **And the export must come from the checkpoint the yardstick is taken to come from**
(`YARDSTICK_SOURCE`: the upstream revision and the source files' sha256). Otherwise a manifest and
a `--source` that agree on some other checkpoint would compare the candidate with its own fp32
weights, an easy pass for a wrong export. See the constant for what that pin does and does not
prove. An export whose manifest says it was made outside the pinned toolchain is refused too
(`--allow-unpinned-export`); a run with any override prints `OVERRIDDEN` in its verdict and records
it in the result.

⚠⚠ **Tokens are padded with `</s>` (id 1)**, by the checkpoint's own tokenizer called with
`padding="max_length"`, the way the model was trained and the way `SiglipTextTokenizer` pads
(`models.md` §3.9.4). Padding with id 0 measures a different model. The script refuses a
tokenizer whose pad id is not the one named here.

⚠ **The corpus is a manifest, never images** (`retrieval-gate-corpus.txt`): each file's name, size
and the sha256 of the **original download**, plus the resize parameters and the digest of the
resized pixels. `--corpus` is checked against it and a mismatch is refused; `--fetch` downloads
what is missing. See `load_corpus_manifest` for why the original bytes are what is pinned.

⚠ **This is a host run.** The Core ML towers run through coremltools on macOS, not on a phone.
`--phone-vectors <in3m-vectors file>` adds a spot-check: the vision vectors the IN-3m harness
wrote on an iPhone for the same pixels (`--dump-pixels` writes them as PNGs to push), compared
with this run's. It never changes the verdict.

Exit status: 0 PASS, 1 FAIL (the rule), 2 refused (the inputs are not what the gate requires).
The result is written as JSON (`--out`, default `<export>/retrieval-gate-result.json`) with the
artifact digests, the corpus id, the queries file's sha256 and the toolchain.

The metric functions and the rule need no package: `scripts/inference/test_retrieval_gate.py`.
"""

import argparse
import hashlib
import json
import math
import os
import platform
import struct
import sys
import tarfile
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
PINNED = "requirements-retrieval-gate.txt"
# The interpreter the pins were resolved and run under; `requirements-coreml-export.txt` states it.
PINNED_PYTHON = "3.13"
DEFAULT_QUERIES = os.path.join(HERE, "retrieval-gate-queries.json")
DEFAULT_CORPUS_MANIFEST = os.path.join(HERE, "retrieval-gate-corpus.txt")
RESULT_NAME = "retrieval-gate-result.json"
RESULT_FORMAT = "retrieval-gate 1"

EXPORT_MANIFEST = "export-manifest.json"
# role -> the file name the export writes; all three are published together.
EXPORT_FILES = {
    "vision": "vision_fp16.mlpackage.tar",
    "text": "text_fp16.mlpackage.tar",
    "vocabulary": "tokenizer.bin",
}

# ⚠ `</s>`: the id the checkpoint pads with (`tokenizer_config.json`'s `pad_token`). Named here so
# a tokenizer that pads with anything else is refused rather than measured.
PAD_ID = 1

# The ONNX pair the signed catalog serves (https://models.service.tenshu.app/catalog.json, build
# 2026-09-25). The yardstick is *what ships*, so another file is refused unless
# `--allow-unpublished-onnx` says the comparison is deliberate; the result records which it was.
# The evaluation this gate IS: the committed queries and corpus manifest, on the compute units the
# app ships (`CoreMlSession`'s constant). ⚠ A run over other queries, another corpus or another
# Core ML execution path is a different measurement; it is admitted, and its verdict reads
# OVERRIDDEN. `test_retrieval_gate.py` fails when a committed file moves without these.
CANONICAL_QUERIES_SHA256 = "cbac832d234bca534263b4ab9342872f9d93aeac8d5cfa26a742eee712380251"
CANONICAL_CORPUS_MANIFEST_SHA256 = "5b7a48f49ecd3b532ffee3e17a7906ad830de94f794c46b054f1fcbf8e790e75"
SHIPPED_COMPUTE_UNITS = "cpuAndNeuralEngine"
# The token rows the committed queries become under the checkpoint's own tokenizer: sha256 over the
# (36 x 64) ids as big-endian int32. ⚠ The requirements pin `tokenizers` too, but a pin is a
# promise and this is the check: another tokenizer backend that moves one id is refused, not gated.
CANONICAL_TOKEN_ROWS_SHA256 = "7ce3eefb006136aa38875e2458ffa50a1ddf788811f7e6244ffd989da5bf231d"

PUBLISHED_ONNX = {
    "vision": "84c7bb927e8b6bd6d79a9ec6da20648193c667b15ee50e0036c62ea23418a7ce",
    "text": "277534a4a9905ea31bccfeee51aa9ae57a92efe56617becadcd68adf7c6abaa3",
}

# The checkpoint the published ONNX pair is **taken to come from**: the upstream revision, and the
# sha256 of the files an export reads from it.
#
# ⚠⚠ Without this the gate could be passed by a wrong export: `--source` and the manifest agreeing
# on some other shape-compatible checkpoint would compare the candidate with its own fp32 weights,
# while the yardstick stays the pair below. So an export from any other checkpoint is refused.
#
# ⚠ What is established, and no more: the local `model.safetensors` has the hash upstream serves
# for this commit, and the local ONNX files have the published digests. Nothing records that those
# ONNX files were exported from this snapshot (`ios-on-device-inference.md` §2.2), so this pins
# the gate's assumption about the yardstick, not a proven provenance. An ONNX re-export that
# records its source is what would close it.
YARDSTICK_SOURCE = {
    "revision": "8952a4eafcde3cb7ab46b1dd629b33f8784ca9c6",
    "files": {
        "model.safetensors": "00df88d823790b34a4d2d0047803ffabd47497ae724fd6659b1533dd793255be",
        "config.json": "af92473ce89269ba92b2fb33466179aef6ceeb62d3c2f4db55da9a3380e67320",
        "preprocessor_config.json": "e12b577bd0da1f9bf6a0b8d3d78c8f9b55b9d99dec25556954e19cfc7b8ecf28",
        "tokenizer_config.json": "d6423dae508cc3a129d22ea443841c111832a1a73125b8f25ea8736951698bcb",
        "tokenizer.json": "babbf9fed5f505ac6b28fd820b5108ca6882332ce6b921c8d33f8ff69bf841d9",
    },
}

CORPUS_MAGIC = "retrieval-gate-corpus 1"
PHONE_MAGIC = "in3m-vectors 3"

# The figures the rule compares, higher is closer to fp32. ⚠ Worst-query figures are in the rule
# on purpose: a pair that is right on average and wrong for one language must not pass on its mean.
FIGURES = (
    "top1",
    "top5Mean",
    "top5Worst",
    "top10Mean",
    "top10Worst",
    "spearmanMean",
    "spearmanWorst",
)


class Refused(Exception):
    """An input is not what the gate requires. Nothing was measured; exit status 2."""


# ---- metrics: plain Python, no package ---------------------------------------------------------


def ranks(scores) -> list:
    """Rank of each score, 0 for the highest; tied scores share the mean of their ranks."""
    order = sorted(range(len(scores)), key=lambda index: -scores[index])
    out = [0.0] * len(scores)
    start = 0
    while start < len(order):
        end = start
        while end + 1 < len(order) and scores[order[end + 1]] == scores[order[start]]:
            end += 1
        shared = (start + end) / 2.0
        for position in range(start, end + 1):
            out[order[position]] = shared
        start = end + 1
    return out


def spearman(a, b) -> float:
    """Spearman's rank correlation: Pearson's over `ranks`, so ties are handled."""
    if len(a) != len(b) or len(a) < 2:
        raise ValueError("spearman needs two rows of one length, at least 2 long")
    ra, rb = ranks(a), ranks(b)
    mean_a, mean_b = sum(ra) / len(ra), sum(rb) / len(rb)
    da = [value - mean_a for value in ra]
    db = [value - mean_b for value in rb]
    spread = math.sqrt(sum(x * x for x in da) * sum(y * y for y in db))
    if spread == 0.0:
        raise ValueError("spearman is undefined for a row whose scores are all equal")
    return sum(x * y for x, y in zip(da, db)) / spread


def top_k(scores, k: int) -> list:
    """Indices of the `k` highest scores, best first; a tie goes to the lower index."""
    return sorted(range(len(scores)), key=lambda index: (-scores[index], index))[:k]


def overlap(a, b, k: int) -> float:
    """The share of `a`'s top `k` that is in `b`'s top `k`. Order inside the top `k` is ignored."""
    return len(set(top_k(a, k)) & set(top_k(b, k))) / k


def summarise(scores, reference) -> dict:
    """One pair's figures against the reference. `scores[q][i]` is query q's score for image i."""
    if len(scores) != len(reference) or not scores:
        raise ValueError("summarise needs the same queries on both sides")
    per_query = []
    for row, ref in zip(scores, reference):
        per_query.append(
            {
                "top1": top_k(row, 1) == top_k(ref, 1),
                "top5": overlap(row, ref, 5),
                "top10": overlap(row, ref, 10),
                "spearman": spearman(row, ref),
            }
        )
    count = len(per_query)
    return {
        "queries": count,
        "top1": sum(1 for entry in per_query if entry["top1"]),
        "top5Mean": sum(entry["top5"] for entry in per_query) / count,
        "top5Worst": min(entry["top5"] for entry in per_query),
        "top10Mean": sum(entry["top10"] for entry in per_query) / count,
        "top10Worst": min(entry["top10"] for entry in per_query),
        "spearmanMean": sum(entry["spearman"] for entry in per_query) / count,
        "spearmanWorst": min(entry["spearman"] for entry in per_query),
        "perQuery": per_query,
    }


def verdict(candidate: dict, yardstick: dict) -> tuple:
    """(passed, the figures on which the candidate is further from fp32 than the yardstick)."""
    behind = [figure for figure in FIGURES if candidate[figure] < yardstick[figure]]
    return (not behind, behind)


# ---- inputs: the export, the corpus, the queries -----------------------------------------------


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def parses_input(what: str):
    """Marks a function that reads a file somebody handed the gate.

    Inside it, a wrong type, a missing key or a short line is a malformed INPUT, so it is
    `Refused` (status 2), never a traceback that automation reads as a measured FAIL (status 1).
    ⚠ Only on the readers: the measurement is not wrapped, so a bug there stays loud.
    """
    def wrap(function):
        import functools

        @functools.wraps(function)
        def reader(*args, **kwargs):
            try:
                return function(*args, **kwargs)
            except Refused:
                raise
            except (TypeError, ValueError, KeyError, IndexError, AttributeError) as error:
                raise Refused(f"{what} is malformed: {type(error).__name__}: {error}") from None
        return reader
    return wrap


@parses_input("the export manifest")
def verify_export(directory: str) -> dict:
    """The parsed manifest of a complete export, or `Refused`.

    ⚠ Complete means the manifest is there AND names these bytes, for all three artifacts: the
    vocabulary is published with the towers, so it is checked with them.
    """
    path = os.path.join(directory, EXPORT_MANIFEST)
    if not os.path.isfile(path):
        raise Refused(f"{directory} has no {EXPORT_MANIFEST}: not a complete export")
    try:
        with open(path, encoding="utf-8") as handle:
            manifest = json.load(handle)
        artifacts = manifest["artifacts"]
        manifest["source"]["files"], manifest["source"]["revision"]
    except (ValueError, KeyError, TypeError) as error:
        raise Refused(f"{path} is not an export manifest ({error!r})")
    for role, name in EXPORT_FILES.items():
        entry = artifacts.get(role)
        if not isinstance(entry, dict) or entry.get("file") != name:
            raise Refused(f"the manifest does not name {name} as its {role} artifact")
        file = os.path.join(directory, name)
        if not os.path.isfile(file):
            raise Refused(f"no {file}")
        if os.path.getsize(file) != entry.get("bytes"):
            raise Refused(f"{name} is {os.path.getsize(file)} bytes, the manifest recorded {entry.get('bytes')}")
        got = sha256_of(file)
        if got != entry.get("sha256"):
            raise Refused(f"{name} is not the file the manifest recorded (sha256 {got}, manifest {entry.get('sha256')})")
    return manifest


EXPORT_PINNED = "requirements-coreml-export.txt"


def export_pins() -> dict:
    """The distributions `export-coreml-towers.py` is pinned to, read from its requirements file."""
    return pinned_versions(os.path.join(HERE, EXPORT_PINNED))


def recorded_toolchain_misses(toolchain, pins: dict) -> dict:
    """`{name: (recorded, pinned)}` for every version the manifest records that is not the pin.

    ⚠ The manifest's `pinned` flag is what the exporter believed, and an exporter older than the
    interpreter check believed it under any Python. The versions it recorded are the evidence, so
    they are judged here against today's pins, the interpreter included.
    """
    if not isinstance(toolchain, dict):
        return {}
    misses = {name: (toolchain.get(name), pin) for name, pin in pins.items() if toolchain.get(name) != pin}
    misses.update(python_pin_miss(str(toolchain.get("python", ""))))
    return misses


@parses_input("the export manifest")
def admit_export(manifest: dict, yardstick_is_published: bool, allow_unpinned_export: bool) -> dict:
    """Whether this export may be gated at all, beyond its bytes being intact. `Refused`, or a
    record of what was checked and what was overridden, for the result.

    **The checkpoint binding.** The export must come from `YARDSTICK_SOURCE`: its revision, and
    every pinned source file's sha256. ⚠ Waived only when the yardstick is *not* the published
    pair (`--allow-unpublished-onnx` with at least one other ONNX file): the pin describes the
    published pair's checkpoint and says nothing about another one. Passing that flag with the
    published files waives nothing.

    **The export's toolchain.** `export-coreml-towers.py --allow-unpinned` writes
    `toolchain.pinned = false`: an archive from an untested pairing, which the export script
    exists to keep from being published. Refused unless `allow_unpinned_export`; a manifest that
    does not say `true` counts as unpinned.
    """
    if yardstick_is_published:
        revision = manifest["source"].get("revision")
        if revision != YARDSTICK_SOURCE["revision"]:
            raise Refused(
                f"the export was made from revision {revision}; the published ONNX pair is taken to come from "
                f"{YARDSTICK_SOURCE['revision']}, and a reference from another checkpoint is not its reference"
            )
        recorded = manifest["source"]["files"]
        for name, want in sorted(YARDSTICK_SOURCE["files"].items()):
            if recorded.get(name) != want:
                raise Refused(
                    f"the export read a {name} with sha256 {recorded.get(name)}; the checkpoint the published ONNX "
                    f"pair is taken to come from has {want}"
                )
        binding = "pinned"
    else:
        binding = "waived: the yardstick is not the published ONNX pair"

    toolchain = manifest.get("toolchain")
    misses = recorded_toolchain_misses(toolchain, export_pins())
    export_pinned = isinstance(toolchain, dict) and toolchain.get("pinned") is True and not misses
    if not export_pinned and not allow_unpinned_export:
        detail = "; ".join(f"{name} is {have}, pinned {want}" for name, (have, want) in sorted(misses.items()))
        raise Refused(
            "the export was not made with the pinned toolchain ("
            + (detail or "its manifest does not say toolchain.pinned = true")
            + "); re-export with scripts/inference/requirements-coreml-export.txt, or pass --allow-unpinned-export"
        )
    overrides = []
    if not yardstick_is_published:
        overrides.append("the yardstick is not the published ONNX pair, so the checkpoint is not bound to it")
    if not export_pinned:
        overrides.append("the export was made outside the pinned toolchain")
    return {"checkpointBinding": binding, "exportToolchainPinned": export_pinned, "overrides": overrides}


@parses_input("the export manifest's source block")
def verify_source(source: str, manifest: dict) -> None:
    """`source` is the checkpoint the export was made from, file for file, or `Refused`."""
    for name, want in sorted(manifest["source"]["files"].items()):
        file = os.path.join(source, name)
        if not os.path.isfile(file):
            raise Refused(f"--source has no {name}, which the export read")
        got = sha256_of(file)
        if got != want:
            raise Refused(f"--source's {name} is not the one the export read (sha256 {got}, manifest {want})")


def unpack(tar_path: str, into: str) -> str:
    """Unpack one tower's tar and return its `.mlpackage` directory.

    ⚠ The same shape `CoreMlModelCache` accepts on the phone: regular files and directories only,
    under one top-level `<stem>.mlpackage`. Anything else is refused, not skipped.
    """
    with tarfile.open(tar_path, "r:") as archive:
        members = archive.getmembers()
        tops = set()
        for member in members:
            parts = member.name.split("/")
            if member.name.startswith("/") or ".." in parts or not (member.isreg() or member.isdir()):
                raise Refused(f"{os.path.basename(tar_path)}: entry '{member.name}' is not a plain file or directory inside the archive")
            tops.add(parts[0])
        if len(tops) != 1 or not next(iter(tops)).endswith(".mlpackage"):
            raise Refused(f"{os.path.basename(tar_path)}: expected one top-level .mlpackage directory, found {sorted(tops)}")
        archive.extractall(into, members=members, filter="data")
    return os.path.join(into, tops.pop())


def corpus_id(entries) -> str:
    """sha256 over the `name<TAB>sha256` lines, in name order.

    The construction `run-in3m.sh`'s `corpus_manifest` uses, kept identical on purpose and shared
    by no code: pushing this corpus's original files with `push-photos` gives the harness the id
    this gate reports. `test_retrieval_gate.py` pins both against one golden value.
    """
    lines = "".join(f"{name}\t{digest}\n" for name, digest in sorted(entries))
    return hashlib.sha256(lines.encode("utf-8")).hexdigest()


@parses_input("the corpus manifest")
def load_corpus_manifest(path: str) -> dict:
    """Parse and self-check the corpus manifest.

        retrieval-gate-corpus 1
        id=<sha256 of the "name<TAB>sha256" lines, in name order>
        count=<n>
        source=<base URL; a file is fetched from source + name>
        resize=<side>x<side> bicubic rgb stretch
        pixels=<sha256 of every image's resized RGB bytes, in name order>
        <blank>
        <sha256><TAB><bytes><TAB><name>        one line a file, in name order

    ⚠ **The pinned bytes are the original downloads, not the resized images.** A download is
    checked with nothing but sha256, so the corpus's identity depends on no library; the hash of a
    resized image depends on the JPEG decoder and the resampler, and would turn a Pillow bump into
    a corpus nobody can fetch. The resize is stated instead, and `pixels=` records what it gave
    under the pinned Pillow, so a run that saw other pixels says so rather than passing quietly.
    """
    with open(path, encoding="utf-8") as handle:
        lines = handle.read().split("\n")
    if not lines or lines[0] != CORPUS_MAGIC:
        raise Refused(f"{path} does not open with '{CORPUS_MAGIC}'")
    try:
        blank = lines.index("")
    except ValueError:
        raise Refused(f"{path} has no blank line after its header")
    header = dict(line.split("=", 1) for line in lines[1:blank] if "=" in line)
    files = []
    for line in lines[blank + 1 :]:
        if not line:
            continue
        fields = line.split("\t")
        if len(fields) != 3 or len(fields[0]) != 64 or not fields[1].isdigit() or "/" in fields[2]:
            raise Refused(f"{path}: not a '<sha256><TAB><bytes><TAB><name>' line: {line!r}")
        files.append({"sha256": fields[0], "bytes": int(fields[1]), "name": fields[2]})
    names = [entry["name"] for entry in files]
    if names != sorted(names) or len(set(names)) != len(names):
        raise Refused(f"{path}: the file lines are not in name order, or a name repeats")
    for key in ("id", "count", "source", "resize", "pixels"):
        if key not in header:
            raise Refused(f"{path}: the header has no {key}=")
    if header["count"] != str(len(files)) or not files:
        raise Refused(f"{path}: count={header['count']} but {len(files)} files are listed")
    computed = corpus_id((entry["name"], entry["sha256"]) for entry in files)
    if header["id"] != computed:
        raise Refused(f"{path}: id={header['id']} does not describe the listed files ({computed})")
    words = header["resize"].split()
    side = words[0].split("x")
    if len(side) != 2 or side[0] != side[1] or not side[0].isdigit() or words[1:] != ["bicubic", "rgb", "stretch"]:
        raise Refused(f"{path}: resize={header['resize']} is not a transform this version applies")
    return {
        "id": header["id"],
        "count": len(files),
        "source": header["source"],
        "resize": header["resize"],
        "side": int(side[0]),
        "pixels": header["pixels"],
        "files": files,
        # How a file's name becomes its address, where that is not `source + name`
        # (`fetch_url`). `label-eval.py`'s Open Images manifests carry it.
        "names": header.get("names"),
    }


SPLIT_NAMES = "<split>-<image id>.jpg, fetched from <source><split>/<image id>.jpg"


def fetch_url(manifest: dict, name: str) -> str:
    """Where one file of a corpus is fetched from: `source + name`, unless the manifest's `names=`
    line says the name carries a directory (`SPLIT_NAMES`: `test-0123….jpg` is
    `<source>test/0123….jpg`). ⚠ A manifest's names have no `/`, and Open Images keeps its sets
    in two directories of one bucket. Any other `names=` line is `Refused`: an address this
    cannot form must not be guessed."""
    names = manifest.get("names")
    if names is None:
        return manifest["source"] + name
    if names != SPLIT_NAMES or "-" not in name:
        raise Refused(f"the corpus manifest names its files '{names}', which this version cannot turn into an address for {name}")
    split, rest = name.split("-", 1)
    return f"{manifest['source']}{split}/{rest}"


def verify_corpus(directory: str, manifest: dict, fetch: bool) -> None:
    """Every file the manifest names is in `directory` with its size and sha256, or `Refused`.

    Files the manifest does not name are never read. A fetched file is written only after its
    digest matched, so a refused download leaves nothing behind to be trusted later.
    """
    os.makedirs(directory, exist_ok=True)
    fetched = 0
    for entry in manifest["files"]:
        path = os.path.join(directory, entry["name"])
        if not os.path.isfile(path):
            if not fetch:
                raise Refused(f"{path} is missing; pass --fetch to download the corpus from {manifest['source']}")
            import urllib.request

            url = fetch_url(manifest, entry["name"])
            try:
                with urllib.request.urlopen(url, timeout=60) as response:
                    body = response.read()
            except OSError as error:
                raise Refused(f"could not fetch {url}: {error}")
            if hashlib.sha256(body).hexdigest() != entry["sha256"]:
                raise Refused(f"{url} did not return the file the manifest names (sha256 {hashlib.sha256(body).hexdigest()})")
            partial = path + ".partial"
            with open(partial, "wb") as handle:
                handle.write(body)
            os.replace(partial, path)
            fetched += 1
            continue
        if os.path.getsize(path) != entry["bytes"]:
            raise Refused(f"{path} is {os.path.getsize(path)} bytes, the manifest says {entry['bytes']}")
        got = sha256_of(path)
        if got != entry["sha256"]:
            raise Refused(f"{path} is not the file the manifest names (sha256 {got}, manifest {entry['sha256']})")
    if fetched:
        print(f"corpus: fetched {fetched} files from {manifest['source']}")


@parses_input("the queries file")
def load_queries(path: str) -> list:
    """The `{"language", "text"}` entries of the queries file, or `Refused`."""
    with open(path, encoding="utf-8") as handle:
        document = json.load(handle)
    queries = document.get("queries") if isinstance(document, dict) else None
    def text_field(value) -> bool:
        return isinstance(value, str) and bool(value.strip())

    if not isinstance(queries, list) or not queries or not all(
        isinstance(q, dict) and text_field(q.get("language")) and text_field(q.get("text")) for q in queries
    ):
        raise Refused(f"{path} has no list of {{language, text}} queries")
    texts = [q["text"] for q in queries]
    if len(set(texts)) != len(texts):
        raise Refused(f"{path} repeats a query")
    return queries


@parses_input("the phone vector file")
def read_phone_vectors(path: str) -> tuple:
    """(fingerprint, {file name: vector}) of a vector file the IN-3m harness wrote (`VectorFile.kt`)."""
    with open(path, encoding="utf-8") as handle:
        lines = handle.read().split("\n")
    if not lines or lines[0] != PHONE_MAGIC or "" not in lines:
        raise Refused(f"{path} is not an '{PHONE_MAGIC}' file")
    blank = lines.index("")
    fingerprint = dict(line.split("=", 1) for line in lines[1:blank] if "=" in line)
    vectors = {}
    for line in lines[blank + 1 :]:
        if "\t" not in line:
            continue
        name, floats = line.split("\t", 1)
        try:
            raw = bytes.fromhex(floats)
        except ValueError:
            raise Refused(f"{path}: the vector of {name} is not hex") from None
        if not raw or len(raw) % 4:
            raise Refused(f"{path}: the vector of {name} is {len(raw)} bytes, not a whole number of floats")
        vectors[name] = list(struct.unpack(f">{len(raw) // 4}f", raw))
    return fingerprint, vectors


# ---- toolchain ---------------------------------------------------------------------------------


def pinned_versions(path: str) -> dict:
    """`name==version` lines of a requirements file, following its `-r` lines."""
    pins = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.split("#", 1)[0].strip()
            if line.startswith("-r "):
                pins.update(pinned_versions(os.path.join(os.path.dirname(path), line[3:].strip())))
            elif "==" in line:
                name, version = line.split("==", 1)
                pins[name.strip().lower()] = version.strip()
    return pins


def python_pin_miss(running: str) -> dict:
    """`{"python": (running, pin)}` when the interpreter is not the pinned minor version, else `{}`.

    ⚠ Part of the toolchain: the requirements file pins distributions only, and the same wheels
    under another interpreter are another build of numpy, torch and Pillow.
    """
    return {} if running.split(".")[:2] == PINNED_PYTHON.split(".") else {"python": (running, PINNED_PYTHON)}


def check_toolchain(allow_unpinned: bool) -> tuple:
    """(installed versions, whether they are the pinned ones). `Refused` on a miss, unless allowed."""
    from importlib.metadata import PackageNotFoundError, version

    pins = pinned_versions(os.path.join(HERE, PINNED))
    installed = {}
    for name in pins:
        try:
            installed[name] = version(name)
        except PackageNotFoundError:
            installed[name] = None
    wrong = {name: (installed[name], pin) for name, pin in pins.items() if installed[name] != pin}
    wrong.update(python_pin_miss(platform.python_version()))
    if wrong and not allow_unpinned:
        detail = "; ".join(f"{name} is {have}, pinned {want}" for name, (have, want) in sorted(wrong.items()))
        raise Refused(f"outside the pinned toolchain ({detail}); install scripts/inference/{PINNED} or pass --allow-unpinned")
    return installed, not wrong


# ---- the run -----------------------------------------------------------------------------------


def token_rows_digest(rows) -> str:
    """sha256 over the token ids, row by row, as big-endian int32."""
    digest = hashlib.sha256()
    for row in rows:
        digest.update(struct.pack(f">{len(row)}i", *(int(value) for value in row)))
    return digest.hexdigest()


def admit_token_rows(rows, queries_are_canonical: bool) -> str:
    """The rows' digest. `Refused` when the committed queries did not become the committed rows:
    the tokenizer in this environment is not the one the gate was pinned with."""
    digest = token_rows_digest(rows)
    if queries_are_canonical and digest != CANONICAL_TOKEN_ROWS_SHA256:
        raise Refused(
            f"the committed queries tokenised to {digest}, the gate pins {CANONICAL_TOKEN_ROWS_SHA256}: "
            "another tokenizer backend or vocabulary; the rows, not the verdict, are what moved"
        )
    return digest


def tokenise(source: str, texts: list):
    """Token rows from the checkpoint's own tokenizer, padded the way the model was trained."""
    import numpy as np
    from transformers import AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(source)
    if tokenizer.pad_token_id != PAD_ID:
        raise Refused(f"the checkpoint's tokenizer pads with id {tokenizer.pad_token_id}, this gate is written for {PAD_ID}")
    length = tokenizer.model_max_length
    rows = tokenizer(texts, padding="max_length", max_length=length, truncation=True)["input_ids"]
    ids = np.array(rows, dtype=np.int64)
    unknown = [text for text, row in zip(texts, rows) if tokenizer.unk_token_id in row]
    if unknown:
        raise Refused(f"queries with an unknown token measure nothing: {unknown}")
    return ids, length


def load_pixels(directory: str, manifest: dict):
    """(model inputs, the digest of the resized RGB bytes, the resized images) in manifest order."""
    import numpy as np
    from PIL import Image

    side = manifest["side"]
    digest = hashlib.sha256()
    inputs, images = [], []
    for entry in manifest["files"]:
        with Image.open(os.path.join(directory, entry["name"])) as opened:
            image = opened.convert("RGB").resize((side, side), Image.BICUBIC)
        digest.update(image.tobytes())
        images.append(image)
        # `ImagePreprocessing`'s arithmetic for this checkpoint: mean 0.5, std 0.5, NCHW.
        scaled = (np.asarray(image, dtype=np.float32) / 255.0 - 0.5) / 0.5
        inputs.append(np.ascontiguousarray(scaled.transpose(2, 0, 1)[None]))
    return inputs, digest.hexdigest(), images


def embed_fp32(source: str, pixels: list, ids):
    import numpy as np
    import torch
    from transformers import SiglipModel

    # Loaded as the full model and narrowed, as the export scripts do.
    model = SiglipModel.from_pretrained(source, torch_dtype=torch.float32).eval()
    with torch.no_grad():
        images = np.concatenate([model.vision_model(pixel_values=torch.from_numpy(x)).pooler_output.numpy() for x in pixels])
        texts = model.text_model(input_ids=torch.from_numpy(ids)).pooler_output.numpy()
    return images, texts


def embed_onnx(vision_path: str, text_path: str, pixels: list, ids, threads=None):
    """`threads` caps onnxruntime's intra-op pool (`label-eval.py embed --threads`); `None` leaves
    the session as onnxruntime makes it, which is how this gate has always run."""
    import numpy as np
    import onnxruntime as ort

    def session(path):
        options = None
        if threads is not None:
            options = ort.SessionOptions()
            options.intra_op_num_threads = threads
            options.inter_op_num_threads = 1
        opened = ort.InferenceSession(path, sess_options=options, providers=["CPUExecutionProvider"])
        return opened, opened.get_inputs()[0]

    vision, vision_input = session(vision_path)
    text, text_input = session(text_path)
    # ⚠ By name: SigLIP pools with a trained head, and any other output is another space.
    images = np.concatenate([vision.run(["pooler_output"], {vision_input.name: x})[0] for x in pixels])
    rows = ids if "int64" in text_input.type else ids.astype(np.int32)
    texts = np.concatenate([text.run(["pooler_output"], {text_input.name: rows[i : i + 1]})[0] for i in range(len(rows))])
    return images, texts


def embed_coreml(export: str, pixels: list, ids, compute_units: str):
    import coremltools as ct
    import numpy as np

    units = {
        "cpuAndNeuralEngine": ct.ComputeUnit.CPU_AND_NE,
        "all": ct.ComputeUnit.ALL,
        "cpuOnly": ct.ComputeUnit.CPU_ONLY,
    }[compute_units]
    with tempfile.TemporaryDirectory() as scratch:
        vision = ct.models.MLModel(unpack(os.path.join(export, EXPORT_FILES["vision"]), scratch), compute_units=units)
        text = ct.models.MLModel(unpack(os.path.join(export, EXPORT_FILES["text"]), scratch), compute_units=units)
        images = np.concatenate([vision.predict({"pixel_values": x})["pooler_output"].reshape(1, -1) for x in pixels])
        texts = np.concatenate(
            [text.predict({"input_ids": ids[i : i + 1].astype(np.int32)})["pooler_output"].reshape(1, -1) for i in range(len(ids))]
        )
    return images, texts


def normalised(vectors, what: str):
    import numpy as np

    vectors = np.asarray(vectors, dtype=np.float64)
    norms = np.linalg.norm(vectors, axis=-1, keepdims=True)
    if not np.isfinite(vectors).all() or (norms == 0).any():
        raise Refused(f"{what}: a vector is not finite, or is zero")
    return vectors / norms


def similarity(texts, images) -> list:
    """`[query][image]` cosine of normalised vectors, as plain lists.

    ⚠ `einsum`, not `@`: numpy's float64 `matmul` on Apple's Accelerate reports divide-by-zero and
    overflow on finite inputs, and a gate that prints those cannot be told from one that hit them.
    """
    import numpy as np

    scores = np.einsum("qd,id->qi", texts, images)
    if not np.isfinite(scores).all():
        raise Refused("a similarity is not finite")
    return scores.tolist()


def cosines(a, b) -> dict:
    row = (a * b).sum(-1)
    return {"mean": float(row.mean()), "min": float(row.min())}


def measure(name: str, images, texts, reference: tuple, languages: list) -> dict:
    """One pair's figures against the reference, with its per-language and per-tower detail."""
    images, texts = normalised(images, f"{name} images"), normalised(texts, f"{name} texts")
    ref_images, ref_texts = reference
    figures = summarise(similarity(texts, images), similarity(ref_texts, ref_images))
    figures["imageCosine"] = cosines(images, ref_images)
    figures["textCosine"] = cosines(texts, ref_texts)
    figures["perLanguage"] = {}
    for language in dict.fromkeys(languages):
        rows = [entry for entry, tag in zip(figures["perQuery"], languages) if tag == language]
        figures["perLanguage"][language] = {
            "queries": len(rows),
            "top1": sum(1 for entry in rows if entry["top1"]),
            "spearmanMean": sum(entry["spearman"] for entry in rows) / len(rows),
        }
    return figures


def with_evaluation_inputs(admission: dict, queries_sha256: str, corpus_manifest_sha256: str, compute_units: str) -> dict:
    """`admission` with an override for each way this run is not the canonical evaluation."""
    overrides = list(admission["overrides"])
    if queries_sha256 != CANONICAL_QUERIES_SHA256:
        overrides.append("the queries are not the committed retrieval-gate queries")
    if corpus_manifest_sha256 != CANONICAL_CORPUS_MANIFEST_SHA256:
        overrides.append("the corpus manifest is not the committed retrieval-gate corpus")
    if compute_units != SHIPPED_COMPUTE_UNITS:
        overrides.append(f"Core ML ran on {compute_units}, the app ships {SHIPPED_COMPUTE_UNITS}")
    return {
        **admission,
        "canonicalQueries": queries_sha256 == CANONICAL_QUERIES_SHA256,
        "canonicalCorpus": corpus_manifest_sha256 == CANONICAL_CORPUS_MANIFEST_SHA256,
        "shippedComputeUnits": compute_units == SHIPPED_COMPUTE_UNITS,
        "overrides": overrides,
    }


def with_gate_toolchain(admission: dict, gate_pinned: bool) -> dict:
    """`admission` with the gate's own toolchain recorded, and an override when it was not pinned.

    ⚠ The gate run outside its pins (`--allow-unpinned`) is an override like the others: another
    Pillow can produce other corpus pixels, so its verdict must never read as a bare PASS.
    """
    overrides = list(admission["overrides"])
    if not gate_pinned:
        overrides.append("the gate itself ran outside its pinned toolchain")
    return {**admission, "gateToolchainPinned": gate_pinned, "overrides": overrides}


def verdict_line(passed: bool, behind: list, overrides: list) -> str:
    """The last line of a run. ⚠ An overridden run never prints a bare PASS."""
    if passed:
        line = "retrieval gate: PASS — Core ML is at least as close to fp32 as the ONNX pair on every figure"
    else:
        line = f"retrieval gate: FAIL — Core ML is further from fp32 than the ONNX pair on: {', '.join(behind)}"
    if overrides:
        line = line.replace("retrieval gate: ", "retrieval gate: OVERRIDDEN ", 1) + " ⚠ OVERRIDDEN: " + "; ".join(overrides)
    return line


def table_row(label: str, figures: dict) -> str:
    return (
        f"| {label} | {figures['top1']} / {figures['queries']} "
        f"| {figures['top5Mean']:.3f} / {figures['top5Worst']:.2f} "
        f"| {figures['top10Mean']:.3f} / {figures['top10Worst']:.2f} "
        f"| {figures['spearmanMean']:.4f} / {figures['spearmanWorst']:.4f} "
        f"| {figures['imageCosine']['mean']:.4f} / {figures['imageCosine']['min']:.4f} "
        f"| {figures['textCosine']['mean']:.4f} / {figures['textCosine']['min']:.4f} |"
    )


def pushed_pixels(directory: str, corpus: dict, pixels_digest: str) -> str:
    """The corpus id of the PNG directory that was pushed to the phone, after checking it holds
    exactly this run's pixels. `Refused` otherwise.

    The id is the one `run-in3m.sh push-photos` writes and the harness stamps into its vector
    file: sha256 over `name<TAB>sha256` of the files as pushed. ⚠ It is taken from the directory,
    never recomputed from a fresh dump: a PNG's bytes depend on the encoder, its pixels do not.
    """
    from PIL import Image

    names = sorted(name for name in os.listdir(directory) if not name.startswith(".") and os.path.isfile(os.path.join(directory, name)))
    want = [os.path.splitext(entry["name"])[0] + ".png" for entry in corpus["files"]]
    if names != sorted(want):
        raise Refused(f"{directory} does not hold exactly this corpus's {len(want)} PNGs (it has {len(names)} files)")
    digest = hashlib.sha256()
    for name in want:
        with Image.open(os.path.join(directory, name)) as opened:
            digest.update(opened.convert("RGB").tobytes())
    if digest.hexdigest() != pixels_digest:
        raise Refused(f"{directory} holds other pixels ({digest.hexdigest()}) than this run resized ({pixels_digest})")
    return corpus_id((name, sha256_of(os.path.join(directory, name))) for name in names)


IOS_DECODER_SOURCE = os.path.join(
    HERE, "..", "..", "inference-service-proxy", "src", "iosMain", "kotlin", "org", "tenshu", "inference", "proxy", "coreml", "IosImageDecoder.kt"
)


def repo_preprocessing_revision(source_text: str) -> int:
    """`IOS_PREPROCESSING_REVISION` as this checkout declares it. `Refused` if it cannot be read:
    the phone check must name the transform it covers, not assume one."""
    import re

    found = re.search(r"const val IOS_PREPROCESSING_REVISION: Int = (\d+)", source_text)
    if not found:
        raise Refused("IOS_PREPROCESSING_REVISION is not declared where this script reads it (IosImageDecoder.kt)")
    return int(found.group(1))


def admit_phone_vectors(fingerprint: dict, vision_archive: str, pushed_corpus_id: str, preprocessing_revision: int, path: str) -> None:
    """`Refused` unless the vector file is the vision tower of this archive over the pushed pixels."""
    if fingerprint.get("tower") != "vision" or fingerprint.get("archiveSha256") != vision_archive:
        raise Refused(
            f"{path} holds {fingerprint.get('tower')} vectors of archive {fingerprint.get('archiveSha256')}; "
            f"this export's vision archive is {vision_archive}"
        )
    # ⚠ Names alone prove nothing: another PNG set with the same file names passes the stem check.
    # ⚠ The decoder can change with neither input changing: a file from a harness built before the
    # last transform change measures a pipeline this checkout no longer has.
    if fingerprint.get("preprocessingRevision") != str(preprocessing_revision):
        raise Refused(
            f"{path} was made with preprocessing revision {fingerprint.get('preprocessingRevision')}; "
            f"this checkout's IOS_PREPROCESSING_REVISION is {preprocessing_revision}"
        )
    if fingerprint.get("corpusId") != pushed_corpus_id:
        raise Refused(
            f"{path} was measured over corpus {fingerprint.get('corpusId')}; the pixels this run pushed are corpus {pushed_corpus_id}"
        )


def admit_phone_widths(vectors: dict, width: int, path: str) -> None:
    """`Refused` unless every phone vector has the embedding width this run's Core ML vectors have.

    A file cut on a four-byte boundary still parses; it must be refused here, as input, not left
    to fail inside the measurement, where it would read as a measured FAIL.
    """
    wrong = sorted(name for name, vector in vectors.items() if len(vector) != width)
    if wrong:
        raise Refused(f"{path}: {len(wrong)} vectors are not {width} floats wide, the first is {wrong[0]} ({len(vectors[wrong[0]])})")


def phone_check(path: str, export_manifest: dict, corpus: dict, pushed_corpus_id: str, host_images, host_texts, reference, languages) -> dict:
    """The phone's vision vectors against this run's, image by image. Never part of the verdict."""
    import numpy as np

    fingerprint, vectors = read_phone_vectors(path)
    with open(IOS_DECODER_SOURCE, encoding="utf-8") as handle:
        revision = repo_preprocessing_revision(handle.read())
    admit_phone_vectors(fingerprint, export_manifest["artifacts"]["vision"]["sha256"], pushed_corpus_id, revision, path)
    admit_phone_widths(vectors, len(host_images[0]), path)
    by_stem = {os.path.splitext(name)[0]: vector for name, vector in vectors.items()}
    stems = [os.path.splitext(entry["name"])[0] for entry in corpus["files"]]
    missing = [stem for stem in stems if stem not in by_stem]
    if missing:
        raise Refused(f"{path} has no vector for {len(missing)} corpus images, the first is {missing[0]}")
    phone = normalised(np.array([by_stem[stem] for stem in stems]), "phone images")
    host = normalised(host_images, "Core ML images")
    figures = measure("phone", phone, host_texts, reference, languages)
    return {
        "file": os.path.basename(path),
        "fingerprint": fingerprint,
        "pushedCorpusId": pushed_corpus_id,
        "images": len(stems),
        "cosineAgainstHostCoreMl": cosines(phone, host),
        # The phone's image vectors ranked by this run's (host) Core ML text vectors.
        "retrievalWithHostText": {key: value for key, value in figures.items() if key != "perQuery"},
    }


def clear_stale_result(path: str) -> None:
    """Remove an earlier run's result before this one validates anything.

    ⚠ The result sits beside the export as its publication evidence. A run that is refused, fails
    or dies writes nothing, and must not leave the previous export's PASS next to new bytes.
    """
    if os.path.lexists(path):
        os.remove(path)


def exit_status(action) -> int:
    """`action()`'s status, with the documented contract: 0 PASS, 1 FAIL, 2 REFUSED.

    ⚠ A missing or unreadable input, or one that is not the JSON or text it should be, is a
    refusal: 1 is reserved for a measured failure, and automation reads it as a model-quality FAIL.
    """
    try:
        return action()
    except Refused as refusal:
        print(f"retrieval gate: REFUSED — {refusal}", file=sys.stderr)
        return 2
    except (OSError, json.JSONDecodeError, UnicodeDecodeError) as error:
        print(f"retrieval gate: REFUSED — an input could not be read: {type(error).__name__}: {error}", file=sys.stderr)
        return 2


def run(args) -> int:
    # ⚠ First, before even the toolchain is judged, and for the canonical result whatever `--out`
    # says: a run refused at any later point must not leave an earlier PASS beside these bytes.
    export = os.path.abspath(args.export)
    out = args.out or os.path.join(export, RESULT_NAME)
    clear_stale_result(os.path.join(export, RESULT_NAME))
    clear_stale_result(out)
    installed, pinned = check_toolchain(args.allow_unpinned)
    export_manifest = verify_export(export)

    onnx = {}
    for role, path in (("vision", args.onnx_vision), ("text", args.onnx_text)):
        digest = sha256_of(path)
        published = digest == PUBLISHED_ONNX[role]
        if not published and not args.allow_unpublished_onnx:
            raise Refused(f"{path} is not the {role} ONNX file the catalog publishes (sha256 {digest}); pass --allow-unpublished-onnx to compare with it anyway")
        onnx[role] = {"file": os.path.basename(path), "sha256": digest, "isPublished": published}

    # ⚠ Before `--source` is read: what the export says it came from is judged first, so a wrong
    # checkpoint is refused as that, whatever directory was passed beside it.
    admission = admit_export(export_manifest, all(entry["isPublished"] for entry in onnx.values()), args.allow_unpinned_export)
    admission = with_gate_toolchain(admission, pinned)
    admission = with_evaluation_inputs(admission, sha256_of(args.queries), sha256_of(args.corpus_manifest), args.compute_units)
    verify_source(args.source, export_manifest)
    print(f"export: {export} verified against its manifest (source revision {export_manifest['source']['revision']}; "
          f"checkpoint binding {admission['checkpointBinding']})")

    queries = load_queries(args.queries)
    languages = [query["language"] for query in queries]
    corpus = load_corpus_manifest(args.corpus_manifest)
    verify_corpus(args.corpus, corpus, args.fetch)
    print(f"corpus: {corpus['id']}  {corpus['count']} files, each checked by size and sha256")

    with open(os.path.join(args.source, "preprocessor_config.json"), encoding="utf-8") as handle:
        side = json.load(handle)["size"]["height"]
    if side != corpus["side"]:
        raise Refused(f"the checkpoint takes {side}² images, the corpus manifest resizes to {corpus['side']}²")
    pixels, pixels_digest, images = load_pixels(args.corpus, corpus)
    pixels_match = pixels_digest == corpus["pixels"]
    if not pixels_match and pinned:
        raise Refused(f"the resized pixels are {pixels_digest}, the manifest recorded {corpus['pixels']}, under the pinned toolchain")
    if not pixels_match:
        print(f"⚠ the resized pixels are {pixels_digest}, not the manifest's {corpus['pixels']}: another decoder or resampler")
    if args.dump_pixels:
        os.makedirs(args.dump_pixels, exist_ok=True)
        for entry, image in zip(corpus["files"], images):
            image.save(os.path.join(args.dump_pixels, os.path.splitext(entry["name"])[0] + ".png"))
        print(f"pixels: wrote {len(images)} PNGs to {args.dump_pixels}")
    del images
    pushed_corpus_id = None
    if args.phone_vectors:
        if not args.phone_pixels:
            raise Refused("--phone-vectors needs --phone-pixels: the PNG directory that was pushed to the phone")
        pushed_corpus_id = pushed_pixels(args.phone_pixels, corpus, pixels_digest)

    ids, length = tokenise(args.source, [query["text"] for query in queries])
    token_rows = admit_token_rows(ids.tolist(), admission["canonicalQueries"])
    print(f"queries: {len(queries)} ({', '.join(f'{languages.count(tag)} {tag}' for tag in dict.fromkeys(languages))}); "
          f"token rows of {length}, padded with id {PAD_ID} by the checkpoint's own tokenizer")

    reference = embed_fp32(args.source, pixels, ids)
    reference = (normalised(reference[0], "fp32 images"), normalised(reference[1], "fp32 texts"))
    print("fp32 PyTorch: done", flush=True)
    onnx_vectors = embed_onnx(args.onnx_vision, args.onnx_text, pixels, ids)
    print("ONNX: done", flush=True)
    coreml_vectors = embed_coreml(export, pixels, ids, args.compute_units)
    print("Core ML: done", flush=True)

    pairs = {
        "onnx": measure("ONNX", *onnx_vectors, reference, languages),
        "coreml": measure("Core ML", *coreml_vectors, reference, languages),
    }
    passed, behind = verdict(pairs["coreml"], pairs["onnx"])

    phone = None
    if args.phone_vectors:
        phone = phone_check(args.phone_vectors, export_manifest, corpus, pushed_corpus_id, coreml_vectors[0],
                            normalised(coreml_vectors[1], "Core ML texts"), reference, languages)

    print()
    print(f"Against fp32 PyTorch, {len(queries)} queries over {corpus['count']} images, mean / worst query:")
    print()
    print("| Pair | top-1 agreement | top-5 overlap | top-10 overlap | Spearman | image cosine, mean / min | text cosine, mean / min |")
    print("|---|---|---|---|---|---|---|")
    print(table_row("(ii) uint8 vision + int8 text ONNX, host", pairs["onnx"]))
    print(table_row(f"(iii) fp16 Core ML, host, {args.compute_units}", pairs["coreml"]))
    if phone:
        print(table_row("phone vision vectors + host Core ML text (not in the verdict)", {**phone["retrievalWithHostText"]}))
    print()
    for key, label in (("onnx", "ONNX"), ("coreml", "Core ML")):
        per = pairs[key]["perLanguage"]
        print(f"{label} per language (top-1, mean Spearman): " + ", ".join(
            f"{tag} {entry['top1']}/{entry['queries']} {entry['spearmanMean']:.4f}" for tag, entry in per.items()))
        missed = [query["text"] for query, entry in zip(queries, pairs[key]["perQuery"]) if not entry["top1"]]
        print(f"{label} top-1 disagreements: {missed}")
    if phone:
        cosine = phone["cosineAgainstHostCoreMl"]
        print(f"phone vs host Core ML, {phone['images']} images: cosine mean {cosine['mean']:.6f}, min {cosine['min']:.6f} "
              f"(row {phone['fingerprint'].get('row')}, OS build {phone['fingerprint'].get('osBuild')})")
    print()
    print(verdict_line(passed, behind, admission["overrides"]))

    result = {
        "format": RESULT_FORMAT,
        "verdict": "PASS" if passed else "FAIL",
        "rule": "Core ML >= ONNX against fp32 on every figure",
        "figures": list(FIGURES),
        "behind": behind,
        "overridden": bool(admission["overrides"]),
        "admission": admission,
        "where": {
            "coreml": f"host, coremltools, {args.compute_units}",
            "machine": platform.machine(),
            "macOS": platform.mac_ver()[0],
        },
        "export": {
            "artifacts": export_manifest["artifacts"],
            "source": export_manifest["source"],
            "toolchain": export_manifest.get("toolchain"),
        },
        "onnx": onnx,
        "corpus": {
            "id": corpus["id"],
            "count": corpus["count"],
            "resize": corpus["resize"],
            "pixelsSha256": pixels_digest,
            "pixelsMatchManifest": pixels_match,
        },
        "queries": {
            "sha256": sha256_of(args.queries),
            "tokenRowsSha256": token_rows,
            "count": len(queries),
            "languages": {tag: languages.count(tag) for tag in dict.fromkeys(languages)},
            "sequenceLength": length,
            "padId": PAD_ID,
        },
        "toolchain": {"python": platform.python_version(), "pinned": pinned, **installed},
        "pairs": pairs,
        "phone": phone,
    }
    with open(out, "w", encoding="utf-8") as handle:
        json.dump(result, handle, indent=2, sort_keys=True, ensure_ascii=False)
        handle.write("\n")
    print(f"wrote {out}")
    return 0 if passed else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--export", required=True, help="An export directory, export-manifest.json included.")
    parser.add_argument("--source", required=True, help="The LOCAL checkpoint directory the export was made from.")
    parser.add_argument("--onnx-vision", required=True, help="vision_model_uint8.onnx, as published.")
    parser.add_argument("--onnx-text", required=True, help="text_model_int8.onnx, as published.")
    parser.add_argument("--corpus", required=True, help="A directory holding the corpus's original files.")
    parser.add_argument("--fetch", action="store_true", help="Download corpus files that are missing from --corpus.")
    parser.add_argument("--corpus-manifest", default=DEFAULT_CORPUS_MANIFEST)
    parser.add_argument("--queries", default=DEFAULT_QUERIES)
    parser.add_argument("--compute-units", default="cpuAndNeuralEngine", choices=["cpuAndNeuralEngine", "all", "cpuOnly"],
                        help="For the Core ML towers. The default is the constant the apps ship.")
    parser.add_argument("--out", help=f"The JSON result. Default: <export>/{RESULT_NAME}")
    parser.add_argument("--dump-pixels", metavar="DIR", help="Also write the resized images as PNGs, to push to a phone.")
    parser.add_argument("--phone-vectors", metavar="FILE", help="An in3m-vectors file from the harness, for a spot-check.")
    parser.add_argument("--phone-pixels", metavar="DIR", help="With --phone-vectors: the --dump-pixels directory that was pushed.")
    parser.add_argument("--allow-unpinned", action="store_true", help=f"Run outside {PINNED}; the result says so.")
    parser.add_argument("--allow-unpublished-onnx", action="store_true",
                        help="Compare with ONNX files the catalog does not serve. Then, and only then, the checkpoint pin is waived.")
    parser.add_argument("--allow-unpinned-export", action="store_true",
                        help="Gate an export whose manifest says it was made outside the pinned toolchain; the verdict says so.")
    args = parser.parse_args()
    return exit_status(lambda: run(args))


if __name__ == "__main__":
    sys.exit(main())
