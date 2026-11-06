#!/usr/bin/env python3
"""Records the cross-tower probe a phone's preflight checks an export against (D12 ②).

A phone that has downloaded and compiled the two Core ML towers must show they still answer
before search is offered: a tower can load, run and return 768 NaNs, or finite numbers from the
wrong space (`docs/proposals/ios-on-device-inference.md` §2.1, §7 D12). So the preflight embeds
one fixed image and two fixed captions, one that describes the image and one that does not, and
requires them to rank in that order by a margin. This script measures that margin **for one
export**, on this Mac, and writes the record the app ships.

    ./venv/bin/pip install -r scripts/inference/requirements-retrieval-gate.txt
    ./venv/bin/python scripts/inference/preflight-probe.py \
        --export /path/to/export            # export-coreml-towers.py's --out, gate result included
        --source /path/to/google--siglip-base-patch16-256-multilingual

It writes `preflight-probe-records/<id>.json`, where `<id>` is the first twelve characters of the
export manifest's sha256, and then regenerates the Kotlin the app compiles: one
`PreflightProbeRecord_<id>.kt` per committed record and `PreflightProbeRegistry.kt`, which lists
them all.

⚠⚠ **One record per export, and adding one never touches another.** A phone keeps the export it
downloaded until it downloads the next, so the app carries a record for every export a phone may
still hold. A record that exists is not overwritten (`--replace` says it is meant), and a run for
a new export changes no other export's files. Removing a record is deleting its JSON and running
`--render`, and is the maintainer's decision (`docs/how-to/models-pipeline.md` §2b).

⚠⚠ **The export directory is read, never written.** It is what gets signed and published. Every
output path is refused inside it, the towers are unpacked into the system's temporary directory,
and the run compares the directory's listing before and after.

⚠⚠ **The record is bound to the bytes.** It holds the sha256 of both archives and of the
vocabulary, read from a manifest that is first verified against the files (`retrieval-gate.py`'s
`verify_export`). The app looks a record up by those three digests, so another export, or the
same towers with another vocabulary, has no probe and is never checked against this one.

⚠⚠ **Only a gated export gets a record.** `retrieval-gate-result.json` must sit beside the
archives, name these digests, and read `PASS` with no override: a probe record is what lets a
phone call an artifact ready, and it must not exist for one that never passed the gate.
`--allow-ungated` admits one for an experiment; the record then lists the override, is written
only to an explicit `--out` file, and is never rendered.

⚠⚠ **The image is a formula, not a file the phone reads.** `probe_pixels` draws a red disc on
white with integer arithmetic; the app draws the same pixels with the same arithmetic
(`PreflightProbeImage`), so the tensor the phone feeds its vision tower is this run's, bit for
bit, with no decoder and no resampler in between. `--image` must be the PNG `--write-image`
writes (committed beside this script, so a reader can look at the probe); any other pixels are
refused, because the phone could not reproduce them. Synthetic on purpose: no licence, no person.

⚠ **Tokens come from the checkpoint's own tokenizer**, padded with `</s>` as the model was
trained (`retrieval-gate.py`'s `tokenise`). The record carries both rows, and the preflight
requires the app's tokenizer to produce exactly them from the downloaded vocabulary: that is its
check of the vocabulary and of `SiglipTextTokenizer` on the phone.

⚠⚠ **This is a host measurement.** The towers run through coremltools on macOS, under
`cpuAndNeuralEngine` (the app's constant) and again under `cpuOnly`, the only second execution
path a Mac offers. The threshold the phone applies is `THRESHOLD_SHARE` of the smaller of the two
margins. **No phone has run this probe**: what an iPhone's Neural Engine returns for it is
unmeasured until a device run, and the record says so.

Exit status: 0 recorded, 1 the probe is unusable (mis-ranked, or a margin under
`MIN_HOST_MARGIN`), 2 refused (the inputs are not what the script requires). Nothing is written
on 1 or 2.

The formula, the PNG, the record's rendering and the refusals need no package:
`scripts/inference/test_preflight_probe.py`.
"""

import argparse
import hashlib
import importlib.util
import json
import math
import os
import platform
import struct
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("retrieval_gate", os.path.join(HERE, "retrieval-gate.py"))
gate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gate)
Refused = gate.Refused

RECORD_FORMAT = "preflight-probe 1"
DEFAULT_IMAGE = os.path.join(HERE, "preflight-probe-image.png")

# ---- the probe ---------------------------------------------------------------------------------

SIDE = 256
BACKGROUND = (255, 255, 255)
DISC = (188, 0, 45)
# In pixels. A pixel is in the disc when its centre is within this distance of the image centre.
DISC_RADIUS = 77
# sha256 of the SIDE x SIDE RGB bytes `probe_pixels` draws. The test pins it; `PreflightProbeImage`
# (Kotlin) is pinned to the same digest through the record.
PROBE_RGB_SHA256 = "d03297ece493720d4670953fcef1d0403d64cf8de83d39943041e5a25bbfb387"

CAPTION_DESCRIBES = "a red circle on a white background"
CAPTION_DOES_NOT = "a city street at night"

# The compute units the app ships, and the one other execution path this host has.
SHIPPED_COMPUTE_UNITS = gate.SHIPPED_COMPUTE_UNITS
SECOND_COMPUTE_UNITS = "cpuOnly"

# ⚠ A probe narrower than this is not recorded. SigLIP's cosines are small: for this image, seven
# captions that do not describe it scored between -0.033 and +0.050 on this export, so a margin
# has to stand clear of 0.08 to say anything.
MIN_HOST_MARGIN = 0.12
# The share of the host margin the phone must reach. ⚠ A choice, not a measurement, and a
# trade: the lower it is, the more a phone's Neural Engine may differ from this Mac (which
# nobody has measured) and the likelier a tower that answers at random is to rank one pair right
# by this much. At a half, the threshold sits at the spread quoted above and no higher.
THRESHOLD_SHARE = 0.5
# A raw pooled vector's norm on the phone must be within this factor of the host's, either way.
# ⚠ A choice: wide enough for any fp16 path, narrow enough to refuse a collapsed or exploded one.
NORM_BAND = 2.0


def probe_pixels() -> bytes:
    """The probe as SIDE x SIDE RGB bytes, row-major: a disc on a plain background.

    ⚠⚠ Integer arithmetic only, in doubled coordinates so a pixel's centre is an integer. The
    Kotlin twin must give the same bytes on every platform, and a float comparison at the rim
    would not.
    """
    rows = bytearray()
    limit = (2 * DISC_RADIUS) ** 2
    for y in range(SIDE):
        dy = 2 * y + 1 - SIDE
        for x in range(SIDE):
            dx = 2 * x + 1 - SIDE
            rows += bytes(DISC if dx * dx + dy * dy <= limit else BACKGROUND)
    return bytes(rows)


def _chunk(kind: bytes, body: bytes) -> bytes:
    return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))


def encode_png(rgb: bytes, side: int) -> bytes:
    """An 8-bit RGB, non-interlaced PNG with every row unfiltered: the only shape `decode_png` reads."""
    if len(rgb) != 3 * side * side:
        raise ValueError(f"{len(rgb)} bytes is not {side}x{side} RGB")
    raw = b"".join(b"\x00" + rgb[3 * side * row : 3 * side * (row + 1)] for row in range(side))
    header = struct.pack(">IIBBBBB", side, side, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", header) + _chunk(b"IDAT", zlib.compress(raw, 9)) + _chunk(b"IEND", b"")


def decode_png(data: bytes) -> tuple:
    """`(rgb bytes, side)` of a PNG `encode_png` wrote, or `Refused`.

    ⚠ Deliberately not a PNG decoder: the probe image is one this script wrote, and reading any
    other file would need a resampler and a colour conversion the phone does not share.
    """
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise Refused("the probe image is not a PNG")
    position, header, body = 8, None, b""
    while position + 8 <= len(data):
        (length,) = struct.unpack(">I", data[position : position + 4])
        kind = data[position + 4 : position + 8]
        chunk = data[position + 8 : position + 8 + length]
        (crc,) = struct.unpack(">I", data[position + 8 + length : position + 12 + length])
        if zlib.crc32(kind + chunk) != crc:
            raise Refused("the probe image has a damaged chunk")
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", chunk)
        elif kind == b"IDAT":
            body += chunk
        position += 12 + length
    if header is None or header[2:] != (8, 2, 0, 0, 0) or header[0] != header[1]:
        raise Refused("the probe image is not a square 8-bit RGB PNG as --write-image writes it")
    side = header[0]
    raw = zlib.decompress(body)
    stride = 3 * side + 1
    if len(raw) != stride * side or any(raw[stride * row] != 0 for row in range(side)):
        raise Refused("the probe image is not a PNG --write-image wrote (filtered rows)")
    return b"".join(raw[stride * row + 1 : stride * (row + 1)] for row in range(side)), side


def read_probe_image(path: str) -> dict:
    """The image's identity, or `Refused` unless its pixels are exactly `probe_pixels()`."""
    with open(path, "rb") as handle:
        data = handle.read()
    try:
        rgb, side = decode_png(data)
    except (struct.error, zlib.error, IndexError) as error:
        raise Refused(f"the probe image could not be read: {type(error).__name__}: {error}") from None
    if side != SIDE or rgb != probe_pixels():
        raise Refused(
            f"{path} does not hold the probe's pixels. The phone draws the probe from the formula "
            "(`probe_pixels`, `PreflightProbeImage`), so another image cannot be checked there; "
            "write the file with --write-image"
        )
    return {
        "file": os.path.basename(path),
        "sha256": hashlib.sha256(data).hexdigest(),
        "side": side,
        "rgbSha256": hashlib.sha256(rgb).hexdigest(),
    }


def tensor_bytes(rgb: bytes, side: int) -> bytes:
    """The vision tower's input for `rgb`, as little-endian float32 in CHW order.

    ⚠ `ImagePreprocessing.preprocessPixels`' arithmetic for this checkpoint, in single precision
    and in its order of operations: `(value / 255 - 0.5) / 0.5`. `struct` rounds each step to
    float32 the way Kotlin's `Float` does, so the digest of these bytes is the phone's tensor's.
    """
    def single(value: float) -> float:
        return struct.unpack("<f", struct.pack("<f", value))[0]

    table = [single(single(single(value / single(255.0)) - 0.5) / 0.5) for value in range(256)]
    plane = side * side
    out = [0.0] * (3 * plane)
    for index in range(plane):
        for channel in range(3):
            out[channel * plane + index] = table[rgb[3 * index + channel]]
    return struct.pack(f"<{len(out)}f", *out)


# ---- the export --------------------------------------------------------------------------------


def listing(directory: str) -> list:
    """Every entry under `directory` with its size and modification time: what "not written" means."""
    entries = []
    for parent, directories, files in os.walk(directory):
        for name in sorted(directories + files):
            path = os.path.join(parent, name)
            status = os.lstat(path)
            entries.append((os.path.relpath(path, directory), status.st_size, status.st_mtime_ns))
    return sorted(entries)


def refuse_inside(path: str, export: str, what: str) -> None:
    """`Refused` when `path` is the export directory or inside it. Compared on real paths."""
    real, root = os.path.realpath(path), os.path.realpath(export)
    if real == root or real.startswith(root + os.sep):
        raise Refused(f"{what} {path} is inside the export directory {export}: this script writes nothing there")


@gate.parses_input("the retrieval gate's result")
def admit_gate_result(export: str, manifest: dict, allow_ungated: bool) -> dict:
    """What the export's gate result says, or `Refused` unless it is a clean PASS for these bytes."""
    path = os.path.join(export, gate.RESULT_NAME)
    digests = {role: manifest["artifacts"][role]["sha256"] for role in gate.EXPORT_FILES}
    problem = None
    record = {"file": gate.RESULT_NAME, "sha256": None, "verdict": None}
    if not os.path.isfile(path):
        problem = f"{export} has no {gate.RESULT_NAME}"
    else:
        with open(path, encoding="utf-8") as handle:
            result = json.load(handle)
        record["sha256"] = gate.sha256_of(path)
        record["verdict"] = result.get("verdict")
        gated = {role: result["export"]["artifacts"][role]["sha256"] for role in gate.EXPORT_FILES}
        if gated != digests:
            problem = f"{gate.RESULT_NAME} is about other archives than the manifest names"
        elif result.get("verdict") != "PASS" or result.get("overridden") is not False:
            problem = f"{gate.RESULT_NAME} is not a clean PASS (verdict {result.get('verdict')!r}, overridden {result.get('overridden')!r})"
    if problem and not allow_ungated:
        raise Refused(f"{problem}: a probe is recorded only for an export that passed the retrieval gate (--allow-ungated)")
    record["overridden"] = problem
    return record


# ---- the measurement ---------------------------------------------------------------------------


def judge(vision: list, describes: list, does_not: list) -> dict:
    """Both cosines, the margin and the three norms of one execution path's raw vectors.

    `ValueError` for a vector that is not finite or is zero: a host that cannot embed the probe
    measures nothing.
    """
    def norm(vector: list) -> float:
        if not all(math.isfinite(value) for value in vector):
            raise ValueError("a vector is not finite")
        length = math.sqrt(math.fsum(value * value for value in vector))
        if length == 0.0:
            raise ValueError("a vector is zero")
        return length

    def cosine(a: list, b: list) -> float:
        return math.fsum(x * y for x, y in zip(a, b)) / (norm(a) * norm(b))

    yes, no = cosine(vision, describes), cosine(vision, does_not)
    return {
        "cosineDescribes": yes,
        "cosineDoesNot": no,
        "margin": yes - no,
        "norms": {"vision": norm(vision), "describes": norm(describes), "doesNot": norm(does_not)},
    }


def threshold(paths: dict) -> dict:
    """What the phone applies, from every host path's reading. `ValueError` for an unusable probe."""
    narrowest = min(reading["margin"] for reading in paths.values())
    if narrowest < MIN_HOST_MARGIN:
        raise ValueError(f"the narrowest host margin is {narrowest:.4f}, under {MIN_HOST_MARGIN}: not a probe")
    return {
        "minMargin": round(narrowest * THRESHOLD_SHARE, 4),
        "rule": f"{THRESHOLD_SHARE} of the narrowest host margin",
        "normBand": NORM_BAND,
        "phoneMeasured": False,
    }


def embed(export: str, tensor: bytes, rows: list, compute_units: str) -> tuple:
    import numpy as np

    pixels = [np.frombuffer(tensor, dtype="<f4").reshape(1, 3, SIDE, SIDE).copy()]
    images, texts = gate.embed_coreml(export, pixels, np.array(rows, dtype=np.int64), compute_units)
    return [float(v) for v in images[0]], [float(v) for v in texts[0]], [float(v) for v in texts[1]]


# ---- the Kotlin half ---------------------------------------------------------------------------

DEFAULT_RECORDS = os.path.join(HERE, "preflight-probe-records")
DEFAULT_KOTLIN = os.path.join(
    HERE, "..", "..", "inference-service-proxy", "src", "commonMain", "kotlin", "org", "tenshu", "inference", "proxy"
)
RECORD_PREFIX = "PreflightProbeRecord_"
REGISTRY_NAME = "PreflightProbeRegistry.kt"
ID_LENGTH = 12


def record_id(record: dict) -> str:
    """What a record is filed under: the head of its export manifest's sha256."""
    digest = record["export"]["manifestSha256"]
    if not (isinstance(digest, str) and len(digest) == 64 and all(c in "0123456789abcdef" for c in digest)):
        raise Refused(f"the record's manifest digest is not a sha256: {digest!r}")
    return digest[:ID_LENGTH]


def render_kotlin(record: dict) -> str:
    """One record as the constants the app compiles in. `Refused` for a record with an override."""
    if record.get("format") != RECORD_FORMAT:
        raise Refused(f"not a {RECORD_FORMAT} record")
    if record["overrides"]:
        raise Refused(f"a record made with an override does not ship: {record['overrides']}")
    shipped = record["host"][SHIPPED_COMPUTE_UNITS]
    name = record_id(record)

    def row(ids: list) -> str:
        lines = [", ".join(str(int(value)) for value in ids[start : start + 16]) for start in range(0, len(ids), 16)]
        return "intArrayOf(\n" + "".join(f"            {line},\n" for line in lines) + "        )"

    def text(value: str) -> str:
        return json.dumps(value, ensure_ascii=True).replace("$", "\\$")

    return f"""package org.tenshu.inference.proxy

// GENERATED by scripts/inference/preflight-probe.py from
// scripts/inference/preflight-probe-records/{name}.json.
// Do not edit: `scripts/inference/test_preflight_probe.py` fails when this file is not that record's
// rendering. Another export gets a file of its own; this one is never rewritten for it.

/**
 * The cross-tower probe of the export whose manifest is `{name}`, measured on the build Mac
 * ([PreflightProbe]; `ios-on-device-inference.md` §14).
 *
 * ⚠⚠ Host figures. No phone has run this probe.
 */
@Suppress("ClassName")
internal object {RECORD_PREFIX}{name} {{
    val probe = PreflightProbe(
        id = "{name}",
        visionArchiveSha256 = "{record["export"]["vision"]}",
        textArchiveSha256 = "{record["export"]["text"]}",
        vocabularySha256 = "{record["export"]["vocabulary"]}",
        imageRgbSha256 = "{record["image"]["rgbSha256"]}",
        imageTensorSha256 = "{record["image"]["tensorSha256"]}",
        captionDescribes = {text(record["captions"]["describes"])},
        captionDoesNot = {text(record["captions"]["doesNot"])},
        tokensDescribes = {row(record["tokenRows"]["describes"])},
        tokensDoesNot = {row(record["tokenRows"]["doesNot"])},
        hostMargin = {shipped["margin"]!r},
        minMargin = {record["threshold"]["minMargin"]!r},
        hostVisionNorm = {shipped["norms"]["vision"]!r},
        hostDescribesNorm = {shipped["norms"]["describes"]!r},
        hostDoesNotNorm = {shipped["norms"]["doesNot"]!r},
        normBand = {record["threshold"]["normBand"]!r},
    )
}}
"""


def render_registry(ids: list) -> str:
    """The list of every record the app carries, in id order."""
    entries = "".join(f"        {RECORD_PREFIX}{name}.probe,\n" for name in sorted(ids))
    return f"""package org.tenshu.inference.proxy

// GENERATED by scripts/inference/preflight-probe.py from the records in
// scripts/inference/preflight-probe-records/. Do not edit: `test_preflight_probe.py` fails when
// this is not exactly the committed set.

/**
 * Every export this build can vouch for, one probe each ([PreflightProbes]).
 *
 * ⚠⚠ Adding an export adds a line and a file and changes no other. Removing one is the
 * maintainer's decision: a phone that still holds that export gets no new verdict from a build
 * without its record (`docs/how-to/models-pipeline.md` §2b).
 */
internal object PreflightProbeRegistry {{
    val records: List<PreflightProbe> = listOf(
{entries}    )
}}
"""


def load_records(directory: str) -> dict:
    """`{id: record}` for every record in `directory`. `Refused` for a file that is not filed under its own id."""
    records = {}
    for name in sorted(os.listdir(directory)) if os.path.isdir(directory) else []:
        if not name.endswith(".json"):
            continue
        with open(os.path.join(directory, name), encoding="utf-8") as handle:
            record = json.load(handle)
        try:
            own = record_id(record)
        except (KeyError, TypeError) as error:
            raise Refused(f"{name} is not a probe record ({error!r})") from None
        if name != f"{own}.json":
            raise Refused(f"{name} holds the record of export {own}: a record is filed under its own id")
        records[own] = record
    exports = {}
    for own, record in records.items():
        triple = tuple(record["export"][role] for role in gate.EXPORT_FILES)
        if triple in exports:
            raise Refused(f"records {exports[triple]} and {own} are for the same three artifacts: the app could not tell which to use")
        exports[triple] = own
    return records


def generated(records: dict) -> dict:
    """`{file name: content}` of everything the Kotlin directory must hold for `records`."""
    files = {f"{RECORD_PREFIX}{name}.kt": render_kotlin(record) for name, record in records.items()}
    files[REGISTRY_NAME] = render_registry(list(records))
    return files


def generated_on_disk(kotlin: str) -> list:
    return sorted(
        name for name in os.listdir(kotlin)
        if name == REGISTRY_NAME or (name.startswith(RECORD_PREFIX) and name.endswith(".kt"))
    )


def check_generated(records_dir: str, kotlin: str) -> list:
    """Every way the Kotlin directory is not exactly the committed records, as sentences. Empty when it is."""
    wanted = generated(load_records(records_dir))
    problems = []
    found = generated_on_disk(kotlin)
    for name in sorted(set(found) - set(wanted)):
        problems.append(f"{name} has no record in {os.path.basename(records_dir)}/: a probe the app compiles that nothing recorded")
    for name, content in sorted(wanted.items()):
        path = os.path.join(kotlin, name)
        if not os.path.isfile(path):
            problems.append(f"{name} is missing: a committed record the app does not carry")
            continue
        with open(path, encoding="utf-8") as handle:
            if handle.read() != content:
                problems.append(f"{name} is not the rendering of the committed records")
    return problems


def sync_generated(records_dir: str, kotlin: str) -> list:
    """Writes what `generated` says and removes generated files no record stands behind. Returns what changed."""
    wanted = generated(load_records(records_dir))
    changed = []
    for name in sorted(set(generated_on_disk(kotlin)) - set(wanted)):
        os.remove(os.path.join(kotlin, name))
        changed.append(f"removed {name}")
    for name, content in sorted(wanted.items()):
        path = os.path.join(kotlin, name)
        current = None
        if os.path.isfile(path):
            with open(path, encoding="utf-8") as handle:
                current = handle.read()
        if current != content:
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(content)
            changed.append(f"wrote {name}")
    return changed


# ---- the run -----------------------------------------------------------------------------------


def write_image(path: str) -> int:
    with open(path, "wb") as handle:
        handle.write(encode_png(probe_pixels(), SIDE))
    print(f"wrote the probe image to {path}")
    return 0


def render_only(records_dir: str, kotlin: str) -> int:
    changed = sync_generated(records_dir, kotlin)
    print("\n".join(changed) if changed else "the Kotlin is already the committed records")
    return 0


def place_record(record: dict, records_dir: str, replace: bool) -> str:
    """Where `record` goes among the committed ones. `Refused` rather than touch another export's, or its own unasked."""
    render_kotlin(record)
    path = os.path.join(records_dir, f"{record_id(record)}.json")
    if os.path.lexists(path) and not replace:
        raise Refused(
            f"{path} exists: this export has its record. Replacing it changes what a pass means for phones "
            "that hold the export, so it takes --replace and a bump of PREFLIGHT_REVISION"
        )
    others = {name: other for name, other in load_records(records_dir).items() if name != record_id(record)}
    triple = tuple(record["export"][role] for role in gate.EXPORT_FILES)
    for name, other in others.items():
        if tuple(other["export"][role] for role in gate.EXPORT_FILES) == triple:
            raise Refused(f"record {name} is already for these three artifacts")
    return path


def run(args) -> int:
    for what, path in (("--out", args.out), ("--records", args.records), ("--kotlin", args.kotlin)):
        if path:
            refuse_inside(path, args.export, what)
    installed, pinned = gate.check_toolchain(args.allow_unpinned)
    overrides = [] if pinned else ["--allow-unpinned"]
    before = listing(args.export)
    manifest = gate.verify_export(args.export)
    gated = admit_gate_result(args.export, manifest, args.allow_ungated)
    if gated["overridden"]:
        overrides.append("--allow-ungated")
    if overrides and not args.out:
        raise Refused(f"a run with {overrides} records nothing the app ships: name an --out file for it")
    gate.verify_source(args.source, manifest)
    image = read_probe_image(args.image)
    rgb = probe_pixels()
    tensor = tensor_bytes(rgb, SIDE)
    image["tensorSha256"] = hashlib.sha256(tensor).hexdigest()

    ids, length = gate.tokenise(args.source, [CAPTION_DESCRIBES, CAPTION_DOES_NOT])
    rows = [[int(value) for value in row] for row in ids]

    paths = {}
    for units in (SHIPPED_COMPUTE_UNITS, SECOND_COMPUTE_UNITS):
        try:
            paths[units] = judge(*embed(args.export, tensor, rows, units))
        except ValueError as error:
            print(f"preflight probe: UNUSABLE — under {units}: {error}", file=sys.stderr)
            return 1
        print(f"  {units}: describes {paths[units]['cosineDescribes']:+.4f}  does not {paths[units]['cosineDoesNot']:+.4f}  margin {paths[units]['margin']:.4f}")
    try:
        limits = threshold(paths)
    except ValueError as error:
        print(f"preflight probe: UNUSABLE — {error}", file=sys.stderr)
        return 1
    if listing(args.export) != before:
        raise Refused(f"{args.export} changed during the run: nothing is recorded about a directory that moved")

    record = {
        "format": RECORD_FORMAT,
        "export": {
            "manifestSha256": gate.sha256_of(os.path.join(args.export, gate.EXPORT_MANIFEST)),
            **{role: manifest["artifacts"][role]["sha256"] for role in gate.EXPORT_FILES},
            "source": manifest["source"]["revision"],
            "retrievalGate": gated,
        },
        "image": image,
        "captions": {"describes": CAPTION_DESCRIBES, "doesNot": CAPTION_DOES_NOT},
        "tokenRows": {"describes": rows[0], "doesNot": rows[1], "length": length, "padId": gate.PAD_ID},
        "host": paths,
        "threshold": limits,
        "where": {"coreml": "host, coremltools", "macOS": platform.mac_ver()[0], "machine": platform.machine()},
        "toolchain": {**installed, "python": platform.python_version(), "pinned": pinned},
        "overrides": overrides,
    }
    # ⚠ Validated in full before the first write: where it may go, and that it renders.
    target = args.out or place_record(record, args.records, args.replace)
    with open(target, "w", encoding="utf-8") as handle:
        json.dump(record, handle, indent=2, sort_keys=True)
        handle.write("\n")
    print(f"preflight probe: RECORDED — the phone must reach a margin of {limits['minMargin']} (unmeasured on any phone)")
    print(f"  {target}")
    if not args.out:
        print("\n".join(f"  {line}" for line in sync_generated(args.records, args.kotlin)))
    return 0


def exit_status(action) -> int:
    """`action()`'s status: 0 recorded, 1 unusable probe, 2 refused."""
    try:
        return action()
    except Refused as refusal:
        print(f"preflight probe: REFUSED — {refusal}", file=sys.stderr)
        return 2
    except (OSError, json.JSONDecodeError, UnicodeDecodeError) as error:
        print(f"preflight probe: REFUSED — an input could not be read: {type(error).__name__}: {error}", file=sys.stderr)
        return 2


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--export", help="export-coreml-towers.py's --out. Read, never written.")
    parser.add_argument("--source", help="The checkpoint directory the export was made from.")
    parser.add_argument("--image", default=DEFAULT_IMAGE, help="The probe PNG, as --write-image writes it.")
    parser.add_argument("--records", default=DEFAULT_RECORDS, help="The committed records, one <id>.json per export.")
    parser.add_argument("--kotlin", default=DEFAULT_KOTLIN, help="Where the generated Kotlin goes.")
    parser.add_argument("--replace", action="store_true", help="Overwrite this export's own record. Owes a PREFLIGHT_REVISION bump.")
    parser.add_argument("--out", help="Write the record to this file only, and render nothing: for a run with an override.")
    parser.add_argument("--allow-unpinned", action="store_true", help="Run outside the pinned toolchain; recorded, never shipped.")
    parser.add_argument("--allow-ungated", action="store_true", help="Record for an export with no clean gate PASS; recorded, never shipped.")
    parser.add_argument("--write-image", metavar="PATH", help="Write the probe PNG and stop.")
    parser.add_argument("--render", action="store_true", help="Regenerate the Kotlin from the committed records and stop.")
    args = parser.parse_args()
    if args.write_image:
        return exit_status(lambda: write_image(args.write_image))
    if args.render:
        return exit_status(lambda: render_only(args.records, args.kotlin))
    if not (args.export and args.source):
        parser.error("--export and --source are required")
    return exit_status(lambda: run(args))


if __name__ == "__main__":
    sys.exit(main())
