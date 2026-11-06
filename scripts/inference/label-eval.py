#!/usr/bin/env python3
"""An offline label evaluation: do per-artifact thresholds make two platforms' labels agree?

`ios-on-device-inference.md` D13 gave the Core ML artifact the ONNX entry's catalog identity, and
left one half open: one stamp then covers two precisions, and synced label rows are thresholded.
This script measures that on the host, against ground truth, for three artifacts:

  fp32    PyTorch, both towers: the reference;
  onnx    the uint8 vision + int8 text ONNX pair the catalog publishes (Android, desktop);
  coreml  the fp16 Core ML pair of an export directory (iOS), on the compute units the app ships.

Ground truth is COCO val2017's 80 object categories as image-level presence, reached through a
committed mapping from the candidate label set (`label-eval-coco-mapping.tsv`). That is the first
evaluation, and what the next two warnings are about; Open Images, further down, is the second.

⚠⚠ **What it can score is the mapped subset: 37 of the 207 candidate labels.** Scenes, documents,
screenshots, events and most activities have no COCO counterpart and are not measured at all.
⚠⚠ **COCO is not a personal photo library.** A firing rate here is not a firing rate there.

    python3.13 -m venv venv
    ./venv/bin/pip install -r scripts/inference/requirements-label-eval.txt
    ./venv/bin/python scripts/inference/label-eval.py embed \
        --export /path/to/coreml-export --source /path/to/checkpoint \
        --onnx-vision /path/to/vision_model_uint8.onnx --onnx-text /path/to/text_model_int8.onnx \
        --corpus /path/to/coco-val2017 --out /path/to/label-eval-run
    ./venv/bin/python scripts/inference/label-eval.py evaluate \
        --run /path/to/label-eval-run --annotations /path/to/coco-val2017/instances_val2017.json \
        --source /path/to/checkpoint      # optional: re-reads the score calibration from the weights

`embed` writes each artifact's image and text vectors to `--out`. `evaluate` needs no model: it
reads those vectors and the annotations, and writes `label-eval-result.json`,
`label-eval-tables.md` and the per-image scores beside them.

⚠ **`evaluate --prompt` names the prompt variant every floor and headline figure uses.** `tsv`,
the candidate file's own prompt, is the default and what the first run used; `bare` is the label
word alone, which `inference-service.md` §39.3 decided for the first vocabulary. A floor belongs
to its prompt, and the result records which it was (`promptVariant`).

**A second ground truth: Open Images V7's human-verified image-level labels** (`--truth
open-images`), for what COCO has no category for: scenes, events, weather, documents, art. It has a
mapping (`label-eval-open-images-mapping.tsv`) and a corpus manifest
(`label-eval-open-images-corpus.txt`) of its own, and COCO's path is as it was.

    python3 scripts/inference/label-eval.py select --classes oidv7-class-descriptions.csv \
        --annotations oidv7-val-annotations-human-imagelabels.csv --annotations oidv7-test-annotations-human-imagelabels.csv \
        --image-info validation-images-with-rotation.csv --image-info test-images-with-rotation.csv \
        --per-label 100 --out /path/to/selection          # no package, no image: which images, and their labels
    # fetch <source><split>/<image id>.jpg for every name of selection/image-list.txt, as <split>-<image id>.jpg
    ./venv/bin/python scripts/inference/label-eval.py manifest --truth open-images --selection /path/to/selection \
        --corpus /path/to/images --out scripts/inference/label-eval-open-images-corpus.txt
    ./venv/bin/python scripts/inference/label-eval.py embed --corpus-manifest scripts/inference/label-eval-open-images-corpus.txt …
    ./venv/bin/python scripts/inference/label-eval.py evaluate --truth open-images --run /path/to/run \
        --corpus-manifest scripts/inference/label-eval-open-images-corpus.txt \
        --annotations /path/to/selection/open-images-labels.csv --selection /path/to/selection/selection.json \\
        --classes oidv7-class-descriptions.csv --prompt bare

⚠⚠ **Open Images verifies a class on some images only**: present, absent, or not looked at. So a
label is scored over its SAMPLE and over no other image: the at most `--per-label` images it is
verified present on and as many it is verified absent on that `select` took for it
(`open_images_truth`, `label_samples`, `verified_rows`). ⚠ Not over every image it happens to be
verified on: for a common class those are nearly all positives, and a precision over them is the
base rate. Precision is at the mix of the sample (in the committed corpus, 39 % to 69 % positives
in a held-out half); it is not what a library would give, and the result carries the false-positive rate on the
sample's negatives so that it can be recomputed for another mix. What needs no truth (which labels an artifact would write, whether two artifacts
agree, a firing rate) is over every image. ⚠ The reader is chosen by `--truth`, never inferred:
a manifest that says otherwise is a refusal.

**A corpus with no ground truth** (a camera roll) has a command of its own, and `evaluate` still
refuses one:

    ./venv/bin/python scripts/inference/label-eval.py manifest --truth none \
        --image-list /path/to/names.txt --corpus /path/to/photos --out /path/to/manifest.txt
    ./venv/bin/python scripts/inference/label-eval.py embed --corpus-manifest /path/to/manifest.txt … --threads 4
    ./venv/bin/python scripts/inference/label-eval.py survey --run /path/to/run \
        --corpus-manifest /path/to/manifest.txt --floors /path/to/label-eval-result.json

`survey` reports, for every label of the set and each prompt variant, the score distribution per
artifact, each pair's offset and spread, and how often the label is among an image's top three;
with `--floors`, what another corpus's floors would write and whether the artifacts would agree.
⚠⚠ **Aggregates only, and no count of fewer than `--smallest-count` images**: such a corpus may
be someone's photographs. Its manifest holds their file names and stays outside any repository.

⚠⚠ **Thresholds are chosen on one half of the corpus and reported on the other.** The split is by
a seeded hash of the file name (`split`). A figure is labelled *calibration*, *held-out* or *all*
wherever it appears; only *held-out* precision and recall are untouched by the threshold choice.

⚠⚠ **Nothing is written into the export directory, and nothing there is deleted.** `--out` is
refused when it is, or is inside, `--export`: `retrieval-gate.py` keeps its publication evidence
there.

⚠⚠ **A refused run writes nothing.** Every command checks all of its arguments and inputs, and
whatever its output directory already holds, before its first write; what it computes is staged.
A run that is refused, or fails while computing, leaves an earlier run in the directory byte for
byte as it was, and a missing package or an unreadable file is a refusal (status 2), never a
traceback.

⚠ **The final move is not atomic, and does not pretend to be.** It removes the directory's record
(`label-eval-inputs.json`; for `evaluate`, `label-eval-result.json`) first, moves the staged files
into place, and writes the record last. A run interrupted in that window leaves a directory with
no record, which `evaluate` and `embed --reuse-inputs` refuse as an incomplete run; it never leaves
an old record describing new files.

⚠⚠ **Evaluation outputs never outlive the run they were computed from.** Whenever `embed` changes
a directory, whatever it embedded, it removes every file `evaluate` writes there
(`EVALUATION_OUTPUTS`, the one list both commands use) and says so: a base vector file replaced
makes them wrong, a tagged one added makes them incomplete. And `embed` refuses an `--out` that
holds evaluation outputs and no record, which is an `evaluate --out` directory.

⚠⚠ **Every input that moves a number is checked where it is consumed, and recorded.** The
checkpoint (weights and tokenizer files) against `gate.YARDSTICK_SOURCE` whenever it is read, for
any `--artifacts`; the token rows of the committed label set against `CANONICAL_TOKEN_ROWS_SHA256`;
the export and the ONNX files as the gate checks them; at `evaluate`, the label set and the whole
corpus manifest against the digests `embed` recorded, the annotations against the manifest's pin,
each vector file against the record. A run over anything but the committed label set, mapping and
manifest, or with BASE vectors (`vectors-{fp32,onnx,coreml}.npz`) from outside the pinned
toolchain, is admitted and reads OVERRIDDEN.

⚠⚠ **A `--variant` is judged apart from the base verdict, and never silently.** A tagged vector
file compared with the pinned run of its artifact (another onnxruntime, other compute units) is
off the pins by construction. It changes no base table, so it does not make the run OVERRIDDEN;
it adds tables of its own, so the last line does not stay bare either. Each requested variant's
recorded toolchain is compared with this script's pins, the result lists what it departs from
(`variants.<file>.departsFrom`, `offPinVariants`), its table is headed OFF-PIN VARIANT, and the
last line reads `MEASURED; 1 off-pin variant: onnx-ort1.29.0`. The pixels and token rows a variant
embedded are still bound: `embed --reuse-inputs` checks both against the digests the pinned run
recorded.

⚠ **This is a host run, on the gate's pixels**: Pillow's bicubic squash to the checkpoint's side.
The apps decode and resize with their own platform code, which this does not emulate.

The inputs are verified as `retrieval-gate.py` verifies them, by that script's own functions: the
export against its manifest, the checkpoint against the manifest and the yardstick pin, the ONNX
files against the published digests, the corpus against its manifest, the toolchain against its
pins. A miss is a refusal.

Exit status: 0 measured, 2 refused (an input is not what the evaluation requires). ⚠ There is no
pass rule: this is a measurement, and status 0 says only that it was taken.

The metric code, the selection rule, the split and the refusals need no package:
`scripts/inference/test_label_eval.py`.
"""

import argparse
import hashlib
import importlib.util
import json
import math
import os
import platform
import re
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("retrieval_gate", os.path.join(HERE, "retrieval-gate.py"))
gate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gate)

Refused = gate.Refused

PINNED = "requirements-label-eval.txt"
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
DEFAULT_LABELS = os.path.join(REPO, "inference-service-proxy", "src", "jvmTest", "resources", "label-set-candidate-v1.tsv")
DEFAULT_MAPPING = os.path.join(HERE, "label-eval-coco-mapping.tsv")
DEFAULT_CORPUS_MANIFEST = os.path.join(HERE, "label-eval-corpus.txt")
MAPPING_MAGIC = "label-eval-mapping 1"
# The second ground truth: Open Images V7's human-verified image-level labels (`--truth
# open-images`), with a mapping, a corpus manifest and canonical digests of its own.
DEFAULT_OPEN_IMAGES_MAPPING = os.path.join(HERE, "label-eval-open-images-mapping.tsv")
DEFAULT_OPEN_IMAGES_CORPUS_MANIFEST = os.path.join(HERE, "label-eval-open-images-corpus.txt")
OPEN_IMAGES_MAPPING_MAGIC = "label-eval-open-images-mapping 1"
# 2: adds `overridden` / `overrides`, `offPinVariants` and each variant's `departsFrom`, and the
# count of bootstrap resamples left out of an interval. A format-1 result has none of them.
# 3: adds `promptVariant`, the prompt every headline figure was computed under. A result without
# it was computed under `tsv`.
RESULT_FORMAT = "label-eval 3"
RESULT_NAME = "label-eval-result.json"
TABLES_NAME = "label-eval-tables.md"
INPUTS_NAME = "label-eval-inputs.json"
SETS_NAME = "label-sets-held-out.json"
PIXELS_NAME = "pixels.u8"
TOKENS_NAME = "token-ids.json"
ANNOTATIONS_NAME = "instances_val2017.json"

ARTIFACTS = ("fp32", "onnx", "coreml")


def score_file(artifact: str) -> str:
    return f"scores-{artifact}.npz"


# Everything `evaluate` writes, the result last. ⚠ ONE list: `evaluate` writes exactly these, and
# `embed` removes exactly these when it changes a directory (`remove_evaluation_outputs`), so the
# two cannot drift apart. They are derived from the vectors and the record beside them, and are
# worth nothing once either moved.
EVALUATION_OUTPUTS = (*(score_file(artifact) for artifact in ARTIFACTS), SETS_NAME, TABLES_NAME, RESULT_NAME)
# What `survey` writes, the result last. Derived from the vectors as the evaluation outputs are,
# and removed by `embed` on the same terms (`remove_survey_outputs`).
SURVEY_TABLES_NAME = "label-survey-tables.md"
SURVEY_RESULT_NAME = "label-survey-result.json"
SURVEY_OUTPUTS = (SURVEY_TABLES_NAME, SURVEY_RESULT_NAME)
SURVEY_FORMAT = "label-survey 1"
SHIPPED = ("onnx", "coreml")
# The evaluation this IS: the committed label set, mapping and corpus manifest. Another one is a
# different measurement; it is admitted, and the result's last line reads OVERRIDDEN, as the
# retrieval gate's does. `test_label_eval.py` fails when a committed file moves without these.
CANONICAL_LABELS_SHA256 = "c9d15726b010bb5c8e9ce6a441223046373a925286db74da635793a5405ba489"
CANONICAL_MAPPING_SHA256 = "4259888103db2f4f748129fe562a07b72fa3ffae9245a1f70c94aa24e31ec92c"
CANONICAL_CORPUS_MANIFEST_SHA256 = "5bef0fd970abdd95746419639ee64b62dc072ff523c05b17acfef64d9f8dfbbb"
CANONICAL_OPEN_IMAGES_MAPPING_SHA256 = "606b35bcfbd78c2ce0ae495a05029bacf07c2f21b950c5ecbd0baf70a76dd4b1"
CANONICAL_OPEN_IMAGES_CORPUS_MANIFEST_SHA256 = "30c40843c12f04f2ff6d397a2859642d20a91ec8da3e907686c254697bb45898"
# Which committed mapping and manifest make a run the canonical evaluation, per ground truth.
CANONICAL = {
    "coco": {"mapping": CANONICAL_MAPPING_SHA256, "manifest": CANONICAL_CORPUS_MANIFEST_SHA256, "name": "COCO"},
    "open-images": {"mapping": CANONICAL_OPEN_IMAGES_MAPPING_SHA256, "manifest": CANONICAL_OPEN_IMAGES_CORPUS_MANIFEST_SHA256, "name": "Open Images"},
}
# The token rows the committed label set's 621 prompts become under the pinned checkpoint's
# tokenizer and the pinned `tokenizers`/`sentencepiece`: sha256 over the (621 x 64) ids as
# big-endian int32 (`gate.token_rows_digest`).
#
# ⚠⚠ This is what binds the TOKENIZER. `SiglipTokenizer` reads `spiece.model`, which neither
# `gate.YARDSTICK_SOURCE` nor an export manifest pins; the rows are the tokenizer's whole effect on
# a score, so the rows are what is pinned. Another vocabulary or backend that moves one id is
# refused. ⚠ Only for the committed label set: any other set's rows are recorded, not pinned.
CANONICAL_TOKEN_ROWS_SHA256 = "931173e9ea83aa057e144e4547767251f7118cc5d1aca0eb1e282fc8952fcc43"
# Tokenizer files the checkpoint directory may hold beyond the pinned five. Recorded, see above.
TOKENIZER_FILES = ("spiece.model", "special_tokens_map.json")
DEFAULT_SEED = 20261010
# Target precisions for a floor. A written label is a synced row and a visible claim: at 0.90 one
# photo in ten under a label is wrong, at 0.95 one in twenty. Below 0.90 a "Dogs" collection reads
# as unreliable; above 0.95 the calibration half no longer has the positives to place the cut.
DEFAULT_TARGETS = (0.90, 0.95)
# ⚠ A floor is not placed on fewer firing images than this: precision 1.0 over the top three
# images is a statement about three images. A label that cannot reach the target with this many
# has no floor, and a label with no floor never fires (`LabelVocabulary`'s required threshold).
MIN_FIRED = 10
# Bounds on the integer arguments. Each is where the code downstream stops being able to answer:
# the bootstrap draws a (resamples x held-out images) count matrix, 200 MB at 10 000 over val2017;
# a side is squared, times three, per image. ⚠ argparse's `type=int` admits any integer.
MAX_BOOTSTRAP = 10_000
MAX_SIDE = 4096
MAX_TAG = 40
# `embed --threads`: a cap on the fp32 and ONNX thread pools, for a machine that is doing other
# work. ⚠ Core ML takes no such cap; its compute units decide.
MAX_THREADS = 256
# `survey` writes no count of images between 1 and this, exclusive: its corpus may be a personal
# library, and a figure over a handful of photographs describes those photographs.
DEFAULT_SMALLEST_COUNT = 10
# How many labels, by score, count as an image's "top" ones in `survey` (`models.md` §3.7.4 read
# `indoors` in the top three of 55 to 60 % of a library).
TOP_K = 3
# IN-D9's margin as `EmbeddingHarness.MARGIN` has it: 0.01, in cosine units, "a first guess".
DEFAULT_MARGINS = (0.01, 0.0, 0.02)
# Distances from a floor, in cosine, inside which a disagreement is counted as "at the threshold".
NEAR = (0.005, 0.01)
# A label firing on at least this share of the corpus is flagged (`inference-service.md` §28).
FLAG_FIRING_RATE = 0.25
# Images whose every instance of a category is smaller than this share of the image are the
# "tiny" cases the ground-truth sensitivity drops.
TINY_AREA_SHARE = 0.01

# SigLIP scores `sigmoid(scale · cos + bias)`.
#
# CHECKPOINT_CALIBRATION is the pinned checkpoint's own: `logit_scale = 4.709494` (so
# `exp` = 110.995995) and `logit_bias` in the `model.safetensors` of `gate.YARDSTICK_SOURCE`.
# `evaluate --source` re-reads them from those bytes (`checkpoint_calibration`). ⚠⚠ That run is the
# only check of these numbers against the weights: no gate has the file.
#
# APP_CALIBRATION is what `VisionModel.PinnedSigLip.calibration` carries, and since 2026-10-11 it is
# the same pair: until then the app carried the `-224` checkpoint's (117.330765, −12.932437), which
# this script found (`models.md` §3.10.8). `test_label_eval.py` reads the Kotlin and fails if the
# two part again. A result still reports a floor's probability under each, so a result written
# before the correction and one written after can be read side by side.
CHECKPOINT_CALIBRATION = (110.995995, -12.885268)
APP_CALIBRATION = CHECKPOINT_CALIBRATION

# The prompt variants embedded for every label. `tsv` is the candidate file's own prompt and the
# default of `evaluate --prompt`, which names the one every headline figure uses; the others are
# then the prompt-sensitivity read. ⚠ `inference-service.md` §39.3 decided the bare word for the
# first vocabulary, after the first run: `--prompt bare` is that measurement.
PROMPT_VARIANTS = ("tsv", "bare", "uniform")
DEFAULT_PROMPT = "tsv"
# What a corpus manifest says its truth is (`truth=`; a manifest without the line is `coco`).
#   coco         COCO's instances file, pinned by the manifest's `annotations*=` lines;
#   open-images  an extract of Open Images' human-verified image-level labels (`select` writes
#                it), pinned the same way, and the class descriptions by `classes*=` lines;
#   none         no ground truth: such a corpus can be embedded and surveyed, never evaluated.
MANIFEST_TRUTHS = ("coco", "open-images", "none")
COCO_SOURCE_URL = "http://images.cocodataset.org/val2017/"
# The public bucket the official `downloader.py` reads, unsigned: `<split>/<image id>.jpg`.
OPEN_IMAGES_SOURCE_URL = "https://open-images-dataset.s3.amazonaws.com/"
# The `source=` of a manifest whose files come from nowhere public. Not a URL, on purpose.
NO_SOURCE = "not-published"
EVALUATION_TRUTHS = ("coco", "open-images")
# ⚠ Open Images verifies a class on SOME images: present (confidence 1), absent (0), or not looked
# at. A label is scored only over the images where it was verified one way or the other, and a
# mapped label needs this many verified positives and this many verified negatives in EACH half.
# A floor needs MIN_FIRED firing images at the target precision, so nine of ten must be positives:
# with 20 it can sit at half the positives. Fewer, and the label is `thin`: listed, counted, not
# scored. ⚠ 20 lets a floor be placed; it does not let a held-out lower bound reach 0.90, which
# takes some 35 firing images with none wrong (`wilson`).
MIN_PER_HALF = 20
# What every image of the Open Images validation and test sets is listed as. `select` refuses a
# pool that says otherwise for an image it would choose.
OPEN_IMAGES_LICENCE = "https://creativecommons.org/licenses/by/2.0/"
OPEN_IMAGES_SOURCES = ("verification", "crowdsource-verification")
OPEN_IMAGES_SPLITS = ("validation", "test")
SELECTION_LABELS = "open-images-labels.csv"
SELECTION_LIST = "image-list.txt"
SELECTION_INFO = "image-info.csv"
SELECTION_RECORD = "selection.json"
SELECTION_OUTPUTS = (SELECTION_LABELS, SELECTION_LIST, SELECTION_INFO, SELECTION_RECORD)
SELECTION_FORMAT = "label-eval-selection 1"
MAX_PER_LABEL = 100_000
# How many verified positives, and how many verified negatives, a label is scored over at most:
# `select --per-label` for the committed corpus, and `evaluate --per-label`'s default.
DEFAULT_PER_LABEL = 100

COCO_CATEGORIES = (
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
    "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
    "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
    "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
    "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
    "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
    "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard",
    "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors",
    "teddy bear", "hair drier", "toothbrush",
)


# ---- the label set and its mapping: plain Python ------------------------------------------------


@gate.parses_input("the label set")
def read_label_set(path: str) -> list:
    """The candidate file's rows as `{tier, parent, label, prompt}`, in file order, or `Refused`.

    ⚠ `embed-label-set.py` skips a malformed row with a warning. Here it is refused: a label that
    silently drops out of a measurement is a hole in every table after it.
    """
    rows = []
    with open(path, encoding="utf-8") as handle:
        for number, line in enumerate(handle, 1):
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) != 4 or parts[0] not in ("top", "leaf") or not parts[2] or not parts[3]:
                raise Refused(f"{path}:{number}: not a 'tier<TAB>parent<TAB>label<TAB>prompt' row: {line!r}")
            tier, parent, label, prompt = parts
            if (tier == "top") != (parent == "-"):
                raise Refused(f"{path}:{number}: a top row has parent '-', and only a top row does")
            rows.append({"tier": tier, "parent": None if parent == "-" else parent, "label": label, "prompt": prompt})
    names = [row["label"] for row in rows]
    if not rows or len(set(names)) != len(names):
        raise Refused(f"{path}: no labels, or a label repeats")
    tops = {row["label"] for row in rows if row["tier"] == "top"}
    for row in rows:
        if row["tier"] == "leaf" and row["parent"] not in tops:
            raise Refused(f"{path}: leaf '{row['label']}' names a parent '{row['parent']}' that is not a top row")
    return rows


@gate.parses_input("the mapping file")
def load_mapping(path: str, labels: list) -> dict:
    """`{label: {kind, fidelity, categories, note}}` for every label of the set, or `Refused`.

    ⚠ Every candidate label has exactly one row, `none` included: the labels this evaluation
    cannot score are listed in the file, not left to be inferred from an absence.
    """
    with open(path, encoding="utf-8") as handle:
        lines = handle.read().split("\n")
    if not lines or lines[0] != MAPPING_MAGIC:
        raise Refused(f"{path} does not open with '{MAPPING_MAGIC}'")
    mapping = {}
    for number, line in enumerate(lines[1:], 2):
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 5:
            raise Refused(f"{path}:{number}: not five tab-separated columns: {line!r}")
        label, kind, fidelity, categories, note = parts
        if label in mapping:
            raise Refused(f"{path}:{number}: '{label}' is mapped twice")
        names = [] if categories == "-" else categories.split(";")
        unknown = [name for name in names if name not in COCO_CATEGORIES]
        if unknown:
            raise Refused(f"{path}:{number}: {unknown} are not COCO categories")
        if len(set(names)) != len(names):
            raise Refused(f"{path}:{number}: a category repeats")
        expected = {"none": lambda n: n == 0, "one-to-one": lambda n: n == 1, "one-to-many": lambda n: n > 1}
        if kind not in expected or not expected[kind](len(names)):
            raise Refused(f"{path}:{number}: kind '{kind}' does not describe {len(names)} categories")
        if (kind == "none") != (fidelity == "-") or fidelity not in ("-", "exact", "proxy"):
            raise Refused(f"{path}:{number}: fidelity '{fidelity}' does not fit kind '{kind}'")
        mapping[label] = {"kind": kind, "fidelity": None if fidelity == "-" else fidelity, "categories": names,
                          "note": None if note == "-" else note}
    wanted = [row["label"] for row in labels]
    missing = [label for label in wanted if label not in mapping]
    extra = sorted(set(mapping) - set(wanted))
    if missing or extra:
        raise Refused(f"{path} does not cover the label set exactly: missing {missing[:5]}, not in the set {extra[:5]}")
    return mapping


def mapped_labels(labels: list, mapping: dict) -> list:
    """The labels the evaluation can score, in label-set order. ⚠ Not a `thin` one: it has classes
    behind it and too few verified images to score (`load_open_images_mapping`)."""
    return [row["label"] for row in labels if mapping[row["label"]]["kind"] not in ("none", "thin")]


@gate.parses_input("the class descriptions file")
def read_open_images_classes(path: str) -> dict:
    """`{display name: class id}` of Open Images' `oidv7-class-descriptions.csv`, or `Refused`.

    The mapping names classes by display name, which is what a reviewer reads; the annotations
    name them by id (`/m/0bt9lr`). ⚠ A display name that two ids share would make a mapping row
    ambiguous: refused.
    """
    import csv

    with open(path, encoding="utf-8", newline="") as handle:
        rows = list(csv.reader(handle))
    if not rows or rows[0] != ["LabelName", "DisplayName"]:
        raise Refused(f"{path} does not open with the header 'LabelName,DisplayName'")
    classes = {}
    for number, row in enumerate(rows[1:], 2):
        identifier, name = row
        if not identifier or not name or name in classes:
            raise Refused(f"{path}:{number}: an empty field, or the display name '{name}' a second time")
        classes[name] = identifier
    if not classes:
        raise Refused(f"{path} lists no class")
    return classes


def open_images_row(row: list, where: str) -> tuple:
    """`(image id, class id, present)` of one image-level label row, or `Refused`.

    ⚠⚠ Human-verified rows only. A `machine` row is a classifier's guess with a fractional
    confidence; Open Images says of them that they have "a substantial false positive rate". One
    in the file is a refusal, not a row to skip: the file is then not the one this reads.
    """
    image, source, identifier, confidence = row
    if source not in OPEN_IMAGES_SOURCES:
        raise Refused(f"{where}: source '{source}' is not a human verification ({', '.join(OPEN_IMAGES_SOURCES)}); machine-generated labels are not ground truth")
    if float(confidence) not in (0.0, 1.0):
        raise Refused(f"{where}: confidence {confidence} is neither 1 (verified present) nor 0 (verified absent)")
    if not image or not identifier:
        raise Refused(f"{where}: an empty image or class id")
    return image, identifier, float(confidence) == 1.0


@gate.parses_input("the Open Images labels file")
def read_open_images_labels(path: str, into=None, keep=None) -> dict:
    """`{image id: {class id: verified present?}}` of an image-level labels CSV, or `Refused`.

    `into` adds to an earlier file's rows (the validation and the test file); `keep`, a set of
    image ids, drops every other image's rows. A pair verified twice with the two answers is
    refused.
    """
    import csv

    labels = {} if into is None else into
    with open(path, encoding="utf-8", newline="") as handle:
        reader = csv.reader(handle)
        if next(reader, None) != ["ImageID", "Source", "LabelName", "Confidence"]:
            raise Refused(f"{path} does not open with the header 'ImageID,Source,LabelName,Confidence'")
        for number, row in enumerate(reader, 2):
            image, identifier, present = open_images_row(row, f"{path}:{number}")
            if keep is not None and image not in keep:
                continue
            verified = labels.setdefault(image, {})
            if verified.get(identifier, present) != present:
                raise Refused(f"{path}:{number}: {image} is verified both with and without {identifier}")
            verified[identifier] = present
    return labels


@gate.parses_input("the Open Images mapping file")
def load_open_images_mapping(path: str, labels: list, classes=None) -> dict:
    """`{label: {kind, fidelity, categories, negatives, pool, note}}` for every label, or `Refused`.

    One row a label, eight columns:

        label  kind  fidelity  positive classes  negative classes  pool positives  pool negatives  note

    An image is POSITIVE for a label when any positive class is verified present. It is NEGATIVE
    when none is, and any negative class is verified absent. Otherwise the label was not verified
    on it, and the image is left out of every figure of that label. ⚠ A negative class is one
    whose absence means the label's absence: for `animals` it is `Animal`, never `Dog`.

      kind      one-to-one | one-to-many (by the number of positive classes) | thin | none
                ⚠ `thin` has classes and too few verified images (`MIN_PER_HALF`): not scored.
      fidelity  exact | broader (the classes cover more than the label) | narrower (less) |
                proxy (they stand in for it; the note says how). `-` for `none`.
      pool      verified positives and negatives among the images `select` chooses from. For a
                reviewer; `select` recomputes both and refuses a file that disagrees.

    With `classes` (`read_open_images_classes`), every class named must be one.
    """
    with open(path, encoding="utf-8") as handle:
        lines = handle.read().split("\n")
    if not lines or lines[0] != OPEN_IMAGES_MAPPING_MAGIC:
        raise Refused(f"{path} does not open with '{OPEN_IMAGES_MAPPING_MAGIC}'")
    mapping = {}
    for number, line in enumerate(lines[1:], 2):
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 8:
            raise Refused(f"{path}:{number}: not eight tab-separated columns: {line!r}")
        label, kind, fidelity, positives, negatives, pool_positives, pool_negatives, note = parts
        if label in mapping:
            raise Refused(f"{path}:{number}: '{label}' is mapped twice")
        names = [] if positives == "-" else positives.split(";")
        absent = [] if negatives == "-" else negatives.split(";")
        for what, row in (("positive", names), ("negative", absent)):
            if len(set(row)) != len(row) or not all(row):
                raise Refused(f"{path}:{number}: a {what} class repeats, or is empty")
            unknown = [name for name in row if classes is not None and name not in classes]
            if unknown:
                raise Refused(f"{path}:{number}: {unknown} are not Open Images classes")
        expected = {"none": lambda n: n == 0, "one-to-one": lambda n: n == 1, "one-to-many": lambda n: n > 1, "thin": lambda n: n >= 1}
        if kind not in expected or not expected[kind](len(names)):
            raise Refused(f"{path}:{number}: kind '{kind}' does not describe {len(names)} positive classes")
        if (kind == "none") != (fidelity == "-") or fidelity not in ("-", "exact", "broader", "narrower", "proxy"):
            raise Refused(f"{path}:{number}: fidelity '{fidelity}' does not fit kind '{kind}'")
        if (kind == "none") != (not absent):
            raise Refused(f"{path}:{number}: a mapped label names at least one negative class, and only a mapped label does")
        if kind == "none":
            if (pool_positives, pool_negatives) != ("-", "-"):
                raise Refused(f"{path}:{number}: an unmapped label has no pool counts")
            pool = None
        else:
            if not (pool_positives.isdigit() and pool_negatives.isdigit()):
                raise Refused(f"{path}:{number}: the pool counts are not two whole numbers")
            pool = {"positives": int(pool_positives), "negatives": int(pool_negatives)}
        mapping[label] = {"kind": kind, "fidelity": None if fidelity == "-" else fidelity, "categories": names, "negatives": absent, "pool": pool,
                          "note": None if note == "-" else note}
    wanted = [row["label"] for row in labels]
    missing = [label for label in wanted if label not in mapping]
    extra = sorted(set(mapping) - set(wanted))
    if missing or extra:
        raise Refused(f"{path} does not cover the label set exactly: missing {missing[:5]}, not in the set {extra[:5]}")
    return mapping


def open_images_verdicts(verified: dict, mapping: dict, classes: dict) -> dict:
    """`{label: True | False}` for one image's verified classes: every label of the mapping that
    was verified on it, one way or the other. A label that is absent was not verified."""
    out = {}
    for label, entry in mapping.items():
        if entry["kind"] == "none":
            continue
        if any(verified.get(classes[name]) is True for name in entry["categories"]):
            out[label] = True
        elif any(verified.get(classes[name]) is False for name in entry["negatives"]):
            out[label] = False
    return out


def open_images_id(name: str) -> tuple:
    """`(split, image id)` of a corpus file name, `validation-0001eeaf4aed83f9.jpg`, or `Refused`.

    The split is in the name because the image is fetched from `<source><split>/<id>.jpg`, and a
    corpus manifest's names have no directory.
    """
    match = re.fullmatch(r"(validation|test)-([0-9a-f]{16})\.jpg", name)
    if not match:
        raise Refused(f"'{name[:80]}' is not an Open Images corpus file name, '<validation|test>-<16 hex digits>.jpg'")
    return match.group(1), match.group(2)


def open_images_truth(annotations: dict, mapping: dict, classes: dict, names: list) -> tuple:
    """`(truth, verified)`: for every mapped label and every image, whether the label is present,
    and whether it was verified at all. `truth` is `False` where `verified` is.

    ⚠⚠ `verified` is the mask every figure against truth is taken through. An unverified image is
    not a negative: nobody looked.
    """
    scored = {label for label, entry in mapping.items() if entry["kind"] not in ("none", "thin")}
    truth, verified = {label: [] for label in scored}, {label: [] for label in scored}
    for name in names:
        image = open_images_id(name)[1]
        if image not in annotations:
            raise Refused(f"the annotations hold no verified label for {name}")
        verdicts = open_images_verdicts(annotations[image], mapping, classes)
        for label in scored:
            truth[label].append(verdicts.get(label) is True)
            verified[label].append(label in verdicts)
    return truth, verified


def selection_order(seed: int, label: str, images) -> list:
    """`images` in the order `select` takes them for one label: by sha256 of `"<seed>:<label>:<id>"`.
    As `split`, it depends on the seed and the names alone."""
    return sorted(images, key=lambda image: hashlib.sha256(f"{seed}:{label}:{image}".encode("utf-8")).hexdigest())


def label_samples(truth: dict, verified: dict, names: list, seed: int, per_label: int) -> dict:
    """`{label: [in its sample?]}`: for each label, the first `per_label` images it is verified
    present on and the first `per_label` it is verified absent on, in `selection_order`.

    ⚠⚠ **A label is scored over its sample, not over every image it happens to be verified on.**
    A corpus chosen for 156 labels holds, for a common class, thousands of images that were
    chosen for some other label and are verified for this one too, nearly all of them positives:
    `Sky` is verified present on 98 % of the corpus images that say anything about it. A
    precision over those is the base rate. Scores drawn at random then "hold" 0.90 for such a
    label, which is how this was found. The sample is what `select` took for the label: at most
    `per_label` of each kind, so no label's sample is mostly one class by accident of what else
    was selected, and the share of positives in it is in the result (`positives`).

    ⚠ The first `per_label` of the corpus's images in this order ARE the ones `select` took from
    the pool under the same seed: it took the pool's first `per_label`, they are all in the
    corpus, and no image of the corpus sorts before them.
    """
    samples = {}
    identifiers = [open_images_id(name)[1] for name in names]
    for label, mask in verified.items():
        kept = set()
        for present in (True, False):
            images = [identifiers[index] for index in range(len(names)) if mask[index] and truth[label][index] == present]
            kept.update(selection_order(seed, label, images)[:per_label])
        samples[label] = [identifier in kept for identifier in identifiers]
    return samples


@gate.parses_input("the selection record")
def selection_parameters(path: str, pinned_sha256: str) -> dict:
    """`{perLabel, mapping, seed}` of the `selection.json` a corpus manifest pins, or `Refused`."""
    if not os.path.isfile(path):
        raise Refused(f"no selection record at {path}")
    got = gate.sha256_of(path)
    if got != pinned_sha256:
        raise Refused(f"{path} is not the selection record the corpus manifest names (sha256 {got})")
    with open(path, encoding="utf-8") as handle:
        record = json.load(handle)
    if record["format"] != SELECTION_FORMAT:
        raise Refused(f"{path} is not a '{SELECTION_FORMAT}' record")
    return {"perLabel": int(record["perLabel"]), "mapping": str(record["inputs"]["mapping"]), "seed": int(record["seed"])}


def rows_of(rows, label: str):
    """The image rows one label is scored over. A list is every label's rows (COCO annotates every
    category on every image); a dict gives each label its own (Open Images: the images where the
    label was verified)."""
    return rows[label] if isinstance(rows, dict) else rows


def verified_rows(rows: list, verified) -> object:
    """`rows` itself with no mask, else `{label: the rows of it the label was verified on}`."""
    return rows if verified is None else {label: [index for index in rows if mask[index]] for label, mask in verified.items()}


def wilson(hits: int, count: int, z: float = 1.959964) -> list:
    """The Wilson score interval of a proportion `hits / count`, 95 % by default; `None` for none.

    ⚠ Beside the bootstrap, not in place of it. A percentile bootstrap of a precision whose every
    firing image is a hit is [1, 1], whether ten images fired or a thousand: no resample can draw
    the miss the sample does not hold. Wilson's lower bound for 10 of 10 is 0.72, for 35 of 35 it
    is 0.90.
    """
    if count == 0:
        return None
    share = hits / count
    centre = share + z * z / (2 * count)
    half = z * math.sqrt(share * (1 - share) / count + z * z / (4 * count * count))
    return [max(0.0, (centre - half) / (1 + z * z / count)), min(1.0, (centre + half) / (1 + z * z / count))]


def unscorable(truth: dict, mapped: list, halves: dict) -> list:
    """`"<label>: no positive in the <half> half"` for every mapped label and half where the truth
    has only one class, in label order."""
    lacking = []
    for label in mapped:
        for half, rows in halves.items():
            rows = rows_of(rows, label)
            positives = sum(1 for index in rows if truth[label][index])
            if positives == 0:
                lacking.append(f"{label}: no positive in the {half} half")
            if positives == len(rows):
                lacking.append(f"{label}: no negative in the {half} half")
    return lacking


def refuse_thin(truth: dict, mapped: list, halves: dict, minimum: int = MIN_PER_HALF) -> None:
    """`Refused` unless every mapped label has `minimum` verified positives and `minimum` verified
    negatives in each half. ⚠ For a truth verified on some images only: where the corpus was
    chosen to give every label that many, a label that falls short says the mapping, the corpus
    or the seed is not the one the corpus was chosen for."""
    short = []
    for label in mapped:
        for half, rows in halves.items():
            rows = rows_of(rows, label)
            positives = sum(1 for index in rows if truth[label][index])
            if positives < minimum or len(rows) - positives < minimum:
                short.append(f"{label}: {positives} positives and {len(rows) - positives} negatives verified in the {half} half")
    if short:
        raise Refused(f"{len(short)} label halves have fewer than {minimum} verified positives or negatives: " + "; ".join(short)
                      + ". Mark those labels `thin` in the mapping, or evaluate with the seed the corpus was selected with")


def refuse_unscorable(truth: dict, mapped: list, halves: dict) -> None:
    """`Refused` unless every mapped label has a positive and a negative image in each half.

    ⚠ One rule for the whole evaluation, applied with the input checks and before anything is
    computed or written. A label with one class has no precision/recall curve, no floor to place
    and no negatives to describe, and every table downstream takes those for granted. Reporting
    such a label as "unavailable" in each of them would be a second code path that only a corpus
    other than the committed one can reach (val2017 has both classes for all 37 labels in both
    halves), so the corpus is refused instead, naming every label it cannot score.
    """
    if not mapped:
        raise Refused("the mapping maps no label onto a COCO category: there is nothing to score")
    lacking = unscorable(truth, mapped, halves)
    if lacking:
        raise Refused(f"this corpus, split this way, cannot score {len(lacking)} label halves: " + "; ".join(lacking)
                      + ". Every mapped label needs a positive and a negative image in each half; use a larger corpus, another --seed, or a mapping without these labels")


def split(names, seed: int) -> tuple:
    """(calibration, held-out): two halves of `names`, each in name order.

    ⚠ By sha256 of `"<seed>:<name>"`, not by a random generator: the halves depend on the seed and
    the names alone, on any interpreter and with no package. The first half by digest calibrates.
    """
    names = sorted(names)
    if len(set(names)) != len(names) or len(names) < 2:
        raise ValueError("split needs at least two distinct names")
    order = sorted(names, key=lambda name: hashlib.sha256(f"{seed}:{name}".encode("utf-8")).hexdigest())
    half = len(order) // 2
    return sorted(order[:half]), sorted(order[half:])


@gate.parses_input("the annotations file")
def read_annotations(path: str) -> dict:
    """COCO instances as `{"images": {file name: (width, height)}, "instances": {file name:
    [(category name, iscrowd, area)]}}`, or `Refused`."""
    with open(path, encoding="utf-8") as handle:
        document = json.load(handle)
    categories = {entry["id"]: entry["name"] for entry in document["categories"]}
    if sorted(categories.values()) != sorted(COCO_CATEGORIES):
        raise Refused(f"{path} does not carry COCO's 80 object categories")
    by_id = {entry["id"]: entry["file_name"] for entry in document["images"]}
    images = {entry["file_name"]: (int(entry["width"]), int(entry["height"])) for entry in document["images"]}
    instances = {name: [] for name in images}
    for entry in document["annotations"]:
        instances[by_id[entry["image_id"]]].append((categories[entry["category_id"]], int(entry["iscrowd"]), float(entry["area"])))
    return {"images": images, "instances": instances}


GROUND_TRUTHS = ("any", "noCrowd", "notTiny")


def ground_truth(annotations: dict, mapping: dict, names: list, rule: str = "any") -> dict:
    """`{label: [bool per name]}` for every mapped label.

      any      at least one instance of a mapped category. The headline rule.
      noCrowd  as `any`, counting only instances with `iscrowd = 0`.
      notTiny  as `any`, counting only instances covering at least `TINY_AREA_SHARE` of the image.

    ⚠ The two stricter rules turn positives into negatives; they do not remove the image.
    """
    if rule not in GROUND_TRUTHS:
        raise ValueError(f"no ground-truth rule '{rule}'")
    present = []
    for name in names:
        width, height = annotations["images"][name]
        kept = set()
        for category, crowd, area in annotations["instances"][name]:
            if rule == "noCrowd" and crowd:
                continue
            if rule == "notTiny" and area < TINY_AREA_SHARE * width * height:
                continue
            kept.add(category)
        present.append(kept)
    return {
        label: [bool(kept.intersection(entry["categories"])) for kept in present]
        for label, entry in mapping.items()
        if entry["kind"] != "none"
    }


# ---- metrics: plain Python ----------------------------------------------------------------------


def probability(cosine: float, calibration=CHECKPOINT_CALIBRATION) -> float:
    """SigLIP's own score for a cosine: `ScoreCalibration.probability` (`InferenceBackend.kt:64`)."""
    scale, bias = calibration
    return 1.0 / (1.0 + math.exp(-(scale * cosine + bias)))


@gate.parses_input("the checkpoint's model.safetensors")
def checkpoint_calibration(path: str) -> tuple:
    """`(exp(logit_scale), logit_bias)` read from a safetensors file, with no package.

    The format: an unsigned 64-bit little-endian header length, a JSON header naming each tensor's
    dtype, shape and byte range, then the tensor bytes.
    """
    import struct

    with open(path, "rb") as handle:
        prefix = handle.read(8)
        length = struct.unpack("<Q", prefix)[0] if len(prefix) == 8 else 0
        if not 0 < length < 100 << 20:
            raise Refused(f"{path} does not open with a safetensors header")
        header = json.loads(handle.read(length).decode("utf-8"))
        values = []
        for name in ("logit_scale", "logit_bias"):
            entry = header[name]
            if entry["dtype"] != "F32" or entry["data_offsets"][1] - entry["data_offsets"][0] != 4:
                raise Refused(f"{path}: {name} is not one float32")
            handle.seek(8 + length + entry["data_offsets"][0])
            raw = handle.read(4)
            if len(raw) != 4:
                raise Refused(f"{path} ends before {name}")
            values.append(struct.unpack("<f", raw)[0])
    return math.exp(values[0]), values[1]


def verify_pinned_source(source: str) -> dict:
    """`source` is the pinned checkpoint, file for file, or `Refused`. Returns what was checked.

    ⚠ For every `--artifacts`: `embed --artifacts onnx` reads no weights from `--source`, but it
    reads the tokenizer and the input side from it, and token rows from some other checkpoint with
    the same pad id would be scored against the published ONNX pair without a trace. With fp32 or
    coreml the export manifest binds the same files a second time (`gate.verify_source`).
    """
    checked = {}
    for name, want in sorted(gate.YARDSTICK_SOURCE["files"].items()):
        path = os.path.join(source, name)
        if not os.path.isfile(path):
            raise Refused(f"--source has no {name}: not the pinned checkpoint")
        got = gate.sha256_of(path)
        if got != want:
            raise Refused(f"--source's {name} is not the pinned checkpoint's (sha256 {got}, pinned {want})")
        checked[name] = got
    extra = {name: gate.sha256_of(os.path.join(source, name)) for name in TOKENIZER_FILES if os.path.isfile(os.path.join(source, name))}
    return {"revision": gate.YARDSTICK_SOURCE["revision"], "pinnedFiles": checked, "tokenizerFilesRecorded": extra}


def admit_token_rows(digest: str, labels_sha256: str) -> bool:
    """Whether the rows are pinned ones. `Refused` when the committed label set did not become
    the committed rows: the tokenizer in this environment, or its vocabulary, is another one."""
    if labels_sha256 != CANONICAL_LABELS_SHA256:
        return False
    if digest != CANONICAL_TOKEN_ROWS_SHA256:
        raise Refused(
            f"the committed label set tokenised to {digest}, this script pins {CANONICAL_TOKEN_ROWS_SHA256}: "
            "another tokenizer backend or vocabulary; the rows, not the scores, are what moved"
        )
    return True


def evaluation_overrides(inputs: dict, labels_sha256: str, mapping_sha256: str, manifest_sha256: str, manifest_pixels: str, pinned: bool, truth: str = "coco") -> list:
    """Each way a run is not the canonical evaluation of its ground truth, for the result and its
    last line."""
    overrides = []
    if not pinned:
        overrides.append("`evaluate` ran outside its pinned toolchain")
    if labels_sha256 != CANONICAL_LABELS_SHA256:
        overrides.append("the label set is not the committed candidate set")
    if mapping_sha256 != CANONICAL[truth]["mapping"]:
        overrides.append("the mapping is not the committed mapping")
    if manifest_sha256 != CANONICAL[truth]["manifest"]:
        overrides.append("the corpus manifest is not the committed one")
    if inputs["corpus"].get("pixelsSha256") != manifest_pixels:
        overrides.append("the embedded pixels are not the ones the corpus manifest records: another decoder or resampler")
    for artifact in ARTIFACTS:
        entry = inputs["artifacts"].get(vectors_name(artifact, ""), {})
        if (entry.get("toolchain") or {}).get("pinned") is not True:
            overrides.append(f"the {artifact} vectors were embedded outside the pinned toolchain")
        if artifact == "coreml" and entry.get("shippedComputeUnits") is not True:
            overrides.append("Core ML did not run on the compute units the app ships")
    return overrides


def verify_calibration(source: str) -> dict:
    """`CHECKPOINT_CALIBRATION` is what `source`'s weights say, and `source` holds the pinned
    weights, or `Refused`."""
    path = os.path.join(source, "model.safetensors")
    digest = gate.sha256_of(path)
    if digest != gate.YARDSTICK_SOURCE["files"]["model.safetensors"]:
        raise Refused(f"{path} is not the pinned checkpoint's weights (sha256 {digest})")
    scale, bias = checkpoint_calibration(path)
    if abs(scale - CHECKPOINT_CALIBRATION[0]) > 1e-3 or abs(bias - CHECKPOINT_CALIBRATION[1]) > 1e-5:
        raise Refused(f"{path} carries scale {scale}, bias {bias}; this script states {CHECKPOINT_CALIBRATION}")
    return {"modelSafetensorsSha256": digest, "scale": scale, "bias": bias}


def pr_points(scores, positives, weights=None) -> list:
    """`(threshold, precision, recall, fired)` at every distinct score, highest threshold first.

    An image fires at a threshold when its score is `>=` it, as `selectLabels` compares
    (`LabelSelection.kt:47`). Tied scores are one point. `weights` counts an image more than once.
    """
    if len(scores) != len(positives):
        raise ValueError("pr_points needs one truth value per score")
    weights = [1] * len(scores) if weights is None else weights
    total = sum(weight for weight, positive in zip(weights, positives) if positive)
    if total == 0:
        raise ValueError("pr_points needs at least one positive")
    order = sorted(range(len(scores)), key=lambda index: -scores[index])
    points, fired, hits, position = [], 0, 0, 0
    while position < len(order):
        threshold = scores[order[position]]
        while position < len(order) and scores[order[position]] == threshold:
            fired += weights[order[position]]
            hits += weights[order[position]] if positives[order[position]] else 0
            position += 1
        if fired:
            points.append((threshold, hits / fired, hits / total, fired))
    return points


def average_precision(scores, positives, weights=None) -> float:
    """Σ (recall step) × (precision at that step) over the distinct thresholds: the area under
    the uninterpolated precision/recall curve."""
    area, previous = 0.0, 0.0
    for _, precision, recall, _ in pr_points(scores, positives, weights):
        area += (recall - previous) * precision
        previous = recall
    return area


def threshold_for_precision(scores, positives, target: float, min_fired: int = MIN_FIRED):
    """The lowest threshold whose precision is at least `target` with at least `min_fired` images
    firing: the most recall the target allows. `None` when no threshold does.

    ⚠ The lowest, so precision may dip below the target at a higher threshold and recover. That is
    a property of the calibration sample; the held-out half is what says whether the cut held.
    """
    chosen = None
    for threshold, precision, _, fired in pr_points(scores, positives):
        if fired >= min_fired and precision >= target:
            chosen = threshold
    return chosen


def fires(scores, threshold) -> list:
    """Whether each score clears the floor. No floor (`None`) never fires."""
    return [threshold is not None and score >= threshold for score in scores]


def confusion(fired, positives) -> dict:
    """Precision and recall of one firing row. Precision is `None` when nothing fired."""
    hits = sum(1 for f, p in zip(fired, positives) if f and p)
    count, total = sum(1 for f in fired if f), sum(1 for p in positives if p)
    return {
        "fired": count,
        "hits": hits,
        "positives": total,
        "precision": hits / count if count else None,
        "recall": hits / total if total else None,
    }


def agreement(a, b) -> dict:
    """How two firing rows over the same images agree.

    `agreement` counts both-fire and neither-fire; ⚠ on a rare label that is dominated by
    neither-fire and reads high whatever happens. `jaccard` is both / (either): of the images
    either side labelled, the share both did. `None` when neither side fired anywhere.
    """
    if len(a) != len(b) or not a:
        raise ValueError("agreement needs two rows of one length")
    both = sum(1 for x, y in zip(a, b) if x and y)
    only_a = sum(1 for x, y in zip(a, b) if x and not y)
    only_b = sum(1 for x, y in zip(a, b) if y and not x)
    neither = len(a) - both - only_a - only_b
    either = both + only_a + only_b
    return {
        "both": both,
        "neither": neither,
        "onlyA": only_a,
        "onlyB": only_b,
        "agreement": (both + neither) / len(a),
        "jaccard": both / either if either else None,
    }


def near_threshold(scores_a, threshold_a, scores_b, threshold_b, margins=NEAR) -> dict:
    """Of the images on which the two sides disagree, the share where either side's score is
    within each margin of its own floor, and the median of the smaller distance."""
    distances = []
    for a, b in zip(scores_a, scores_b):
        fired_a = threshold_a is not None and a >= threshold_a
        fired_b = threshold_b is not None and b >= threshold_b
        if fired_a == fired_b:
            continue
        near = [abs(score - threshold) for score, threshold in ((a, threshold_a), (b, threshold_b)) if threshold is not None]
        distances.append(min(near))
    distances.sort()
    return {
        "disagreements": len(distances),
        "within": {str(margin): (sum(1 for d in distances if d <= margin) / len(distances) if distances else None) for margin in margins},
        "medianDistance": nearest_rank(distances, 0.5) if distances else None,
    }


def nearest_rank(ordered: list, q) -> float:
    """The nearest-rank quantile of a sorted, non-empty row: the smallest observation with at least
    a share `q` of the row at or below it. Zero-based index `ceil(q·n) − 1`, and the first
    observation for q = 0.

    ⚠ Always an observation, never an interpolation: a cosine quoted from here is some image's
    cosine. The median of [1, 2, 3, 4] is 2. ⚠ `q·n` is taken exactly (`Fraction` of the decimal
    as written), so a rank never rests on float rounding: 0.07 × 100 is 7.000000000000001 in
    floats, and its ceiling is one rank too far. (The seven points `quantiles` uses happen to be
    exact in floats for every row length up to 6 000; the rule should not depend on that.)
    """
    from fractions import Fraction

    if not ordered:
        raise ValueError("a quantile needs at least one value")
    rank = math.ceil(Fraction(str(q)) * len(ordered))
    return ordered[max(0, min(len(ordered), rank) - 1)]


def median(values):
    """The nearest-rank median: for an even count, the lower of the two middle observations."""
    return nearest_rank(sorted(values), 0.5)


def quantiles(values, at=(0.0, 0.05, 0.25, 0.5, 0.75, 0.95, 1.0)) -> dict:
    """Nearest-rank quantiles of a non-empty row (`nearest_rank`)."""
    ordered = sorted(values)
    return {str(q): nearest_rank(ordered, q) for q in at}


def set_agreement(a: list, b: list) -> dict:
    """Two rows of per-image label sets: how often the sets are identical, and their mean Jaccard.

    ⚠ `identical` counts two empty sets as agreeing, and most images of a corpus scored on 37
    labels have a small set. `identicalWhereEitherLabelled` and `meanJaccardWhereEitherLabelled`
    are over the images where at least one side wrote a label.
    """
    if len(a) != len(b) or not a:
        raise ValueError("set_agreement needs two rows of one length")
    identical = sum(1 for x, y in zip(a, b) if set(x) == set(y))
    labelled = [(set(x), set(y)) for x, y in zip(a, b) if x or y]
    return {
        "images": len(a),
        "identical": identical / len(a),
        "eitherLabelled": len(labelled),
        "identicalWhereEitherLabelled": (sum(1 for x, y in labelled if x == y) / len(labelled)) if labelled else None,
        "meanJaccardWhereEitherLabelled": (sum(len(x & y) / len(x | y) for x, y in labelled) / len(labelled)) if labelled else None,
    }


# ---- the selection rule: a port of LabelSelection.kt --------------------------------------------


def vocabulary_of(labels: list) -> list:
    """Checks a vocabulary as `vocabularyOf` does (`LabelVocabulary.kt:115-122`) and returns it.

    Each label is `{label, tier, parent, floor, margin}`. A duplicate, a leaf with no parent and a
    leaf naming a parent that is not in the vocabulary are `ValueError`s, as they are
    `IllegalArgumentException`s there.
    """
    names = [entry["label"] for entry in labels]
    if len(set(names)) != len(names):
        raise ValueError("duplicate label in vocabulary")
    for entry in labels:
        if entry["tier"] == "leaf":
            if entry.get("parent") is None:
                raise ValueError(f"leaf '{entry['label']}' has no parent to degrade to")
            if entry["parent"] not in names:
                raise ValueError(f"leaf '{entry['label']}' names a parent '{entry['parent']}' not in the vocabulary")
    return list(labels)


def tag_scores(tags: list) -> dict:
    """`{label: score}` of `(label, score)` pairs; a repeated label keeps its highest score
    (`LabelSelection.kt:42`)."""
    scores = {}
    for label, score in tags:
        scores[label] = max(score, scores[label]) if label in scores else score
    return scores


def rank_siblings(scores: dict, vocabulary: list, parent: str) -> list:
    """`(entry, score)` for the leaves under `parent` that have a score, best first
    (`LabelSelection.kt:56-58`).

    ⚠ A tie goes to the leaf declared first. `siblingsOf` returns declaration order and
    `sortedByDescending` is a stable sort, as `sorted` is; the key is the score alone, so two
    entries are never compared with each other.
    """
    siblings = [entry for entry in vocabulary if entry["tier"] == "leaf" and entry["parent"] == parent]
    return sorted(((entry, scores[entry["label"]]) for entry in siblings if entry["label"] in scores), key=lambda pair: -pair[1])


def select_labels(tags: list, vocabulary: list) -> dict:
    """`selectLabels` (`LabelSelection.kt:37-84`), line for line. `tags` is `(label, score)` pairs;
    the result is `{label: score}` in the order the Kotlin's `LinkedHashMap` fills.

    ⚠ The rule never looks at what kind of number a score is. It compares `score >= floor` and
    `winner - runnerUp >= margin`, so the floors and the margin must be in the scores' unit; this
    evaluation uses cosines throughout.
    """
    if not vocabulary or not tags:  # :38
        return {}
    scores = tag_scores(tags)  # :42
    by_name = {entry["label"]: entry for entry in vocabulary}
    selected = {}
    for top in vocabulary:  # :45-48
        if top["tier"] == "top" and top["label"] in scores and scores[top["label"]] >= top["floor"]:
            selected[top["label"]] = scores[top["label"]]
    parents = []
    for entry in vocabulary:  # :51-54 — each sibling set competes once, in declaration order
        if entry["tier"] == "leaf" and entry["parent"] not in parents:
            parents.append(entry["parent"])
    for parent in parents:
        ranked = rank_siblings(scores, vocabulary, parent)  # :56-58
        if not ranked:  # :59
            continue
        winner, winner_score = ranked[0]
        runner_up = ranked[1][1] if len(ranked) > 1 else None
        clears_floor = winner_score >= winner["floor"]  # :64
        clears_margin = runner_up is None or (winner_score - runner_up) >= winner["margin"]  # :65
        if clears_floor and clears_margin:  # :67
            selected[winner["label"]] = winner_score
        elif parent in scores and parent in by_name and scores[parent] >= by_name[parent]["floor"]:  # :75-78
            selected[parent] = scores[parent]
    return selected


def leaf_outcome(tags: list, vocabulary: list, parent: str) -> str:
    """What one sibling set did on one image: `leaf`, `belowFloor`, `insideMargin` or `noScores`.

    The winner and the runner-up are `select_labels`'s own (`rank_siblings`), ties included.
    """
    ranked = rank_siblings(tag_scores(tags), vocabulary, parent)
    if not ranked:
        return "noScores"
    winner, winner_score = ranked[0]
    if winner_score < winner["floor"]:
        return "belowFloor"
    if len(ranked) > 1 and (winner_score - ranked[1][1]) < winner["margin"]:
        return "insideMargin"
    return "leaf"


def evaluation_vocabulary(labels: list, mapping: dict, floors: dict, margin: float) -> list:
    """The vocabulary the end-to-end run uses: the mapped labels, with this artifact's floors.

    ⚠ A label whose floor could not be placed, and the parent of a mapped leaf that is not itself
    mapped (`objects`, `plants`, …), get a floor of +∞: `VocabularyLabel` requires a floor, an
    uncalibrated one cannot be expressed, and +∞ is the floor that never fires. Such a parent is
    in the vocabulary only because `vocabularyOf` requires a leaf's parent to be.
    """
    mapped = set(mapped_labels(labels, mapping))
    parents = {row["parent"] for row in labels if row["label"] in mapped and row["tier"] == "leaf"}
    vocabulary = []
    for row in labels:
        if row["label"] not in mapped and row["label"] not in parents:
            continue
        floor = floors.get(row["label"]) if row["label"] in mapped else None
        vocabulary.append({
            "label": row["label"],
            "tier": row["tier"],
            "parent": row["parent"],
            "floor": math.inf if floor is None else floor,
            "margin": margin if row["tier"] == "leaf" else 0.0,
        })
    return vocabulary_of(vocabulary)


# ---- inputs -------------------------------------------------------------------------------------


def prompts_for(labels: list) -> list:
    """`(variant, label, text)` for every label and variant, variant by variant in label order.

      tsv      the candidate file's hand-written prompt;
      bare     the label word alone;
      uniform  `This is a photo of <label>.`: one template, no article, no elaboration.
    """
    texts = []
    for variant in PROMPT_VARIANTS:
        for row in labels:
            text = {"tsv": row["prompt"], "bare": row["label"], "uniform": f"This is a photo of {row['label']}."}[variant]
            texts.append((variant, row["label"], text))
    return texts


def refuse_inside(path: str, export: str, what: str) -> None:
    """`Refused` when `path` is the export directory or inside it. Compared on real paths."""
    path, export = os.path.realpath(path), os.path.realpath(export)
    if path == export or path.startswith(export + os.sep):
        raise Refused(f"{what} {path} is inside the export directory {export}: this evaluation writes nothing there")


@gate.parses_input("the corpus manifest")
def manifest_annotations(path: str) -> dict:
    """The `annotations*=` header lines of a corpus manifest: `{name, bytes, sha256}`, or `Refused`.

    The manifest is `retrieval-gate.py`'s format, which ignores header keys it does not know; the
    annotations are pinned in the same file so one digest names images and truth together.
    """
    header = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line:
                break
            if "=" in line:
                key, value = line.split("=", 1)
                header[key] = value
    for key in ("annotations", "annotationsBytes", "annotationsSha256"):
        if key not in header:
            raise Refused(f"{path}: the header has no {key}=")
    return {"name": header["annotations"], "bytes": int(header["annotationsBytes"]), "sha256": header["annotationsSha256"]}


@gate.parses_input("the corpus manifest")
def manifest_open_images(path: str) -> dict:
    """What an Open Images corpus manifest pins beyond its annotations: the class descriptions
    (`classes*=`), and where the annotations extract came from (`selection*=`, `annotationsFrom=`),
    or `Refused`."""
    header = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line:
                break
            if "=" in line:
                key, value = line.split("=", 1)
                header[key] = value
    for key in ("classes", "classesBytes", "classesSha256", "selectionSha256", "selectionSeed", "annotationsFrom"):
        if key not in header:
            raise Refused(f"{path}: the header has no {key}=")
    return {"classes": {"name": header["classes"], "bytes": int(header["classesBytes"]), "sha256": header["classesSha256"]},
            "selection": {"sha256": header["selectionSha256"], "seed": int(header["selectionSeed"])},
            "annotationsFrom": [dict(zip(("name", "sha256"), entry.split(":"))) for entry in header["annotationsFrom"].split(";")]}


@gate.parses_input("the corpus manifest")
def manifest_truth(path: str) -> str:
    """What the manifest says its ground truth is: its `truth=` header line, `coco` without one
    (the committed val2017 manifest predates the line). `Refused` for a value this version does
    not know."""
    truth = "coco"
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line:
                break
            if line.startswith("truth="):
                truth = line[len("truth="):]
    if truth not in MANIFEST_TRUTHS:
        raise Refused(f"{path}: truth={truth} is not one of {', '.join(MANIFEST_TRUTHS)}")
    return truth


def verify_annotations(path: str, pinned: dict, what: str = "annotations") -> None:
    """`path` is the annotations file the corpus manifest pins, or `Refused`."""
    if not os.path.isfile(path):
        raise Refused(f"no {what} file at {path}")
    if os.path.getsize(path) != pinned["bytes"]:
        raise Refused(f"{path} is {os.path.getsize(path)} bytes, the corpus manifest says {pinned['bytes']}")
    got = gate.sha256_of(path)
    if got != pinned["sha256"]:
        raise Refused(f"{path} is not the {what} file the corpus manifest names (sha256 {got})")


def check_toolchain(allow_unpinned: bool) -> tuple:
    """`retrieval-gate.py`'s check against this script's own requirements file."""
    saved = gate.PINNED
    gate.PINNED = PINNED
    try:
        return gate.check_toolchain(allow_unpinned)
    finally:
        gate.PINNED = saved


def write_json(path: str, document) -> None:
    partial = path + ".partial"
    with open(partial, "w", encoding="utf-8") as handle:
        json.dump(document, handle, indent=2, sort_keys=True, ensure_ascii=False)
        handle.write("\n")
    os.replace(partial, path)


@gate.parses_input("the run's inputs record")
def read_inputs(run: str) -> dict:
    path = os.path.join(run, INPUTS_NAME)
    if not os.path.isfile(path):
        # ⚠ Also what an interrupted final move leaves: `embed` removes the record before it moves
        # its files into place and writes it back last.
        raise Refused(f"{run} has no {INPUTS_NAME}: not an `embed` output, or a run whose final write was interrupted and is incomplete; run `embed` again")
    with open(path, encoding="utf-8") as handle:
        inputs = json.load(handle)
    inputs["labels"]["sha256"], inputs["corpus"]["id"], inputs["corpus"]["manifestSha256"], inputs["texts"]["tokenRowsSha256"]
    for name, entry in inputs["artifacts"].items():
        if entry["artifact"] not in ARTIFACTS or len(entry["sha256"]) != 64 or os.path.basename(name) != name:
            raise Refused(f"{path}: '{name}' is not a vector file entry")
    return inputs


@gate.parses_input("the checkpoint's preprocessor_config.json")
def checkpoint_side(config: dict) -> int:
    return int(config["size"]["height"])


def numbers(text: str, flag: str) -> list:
    """A comma-separated list of numbers from the command line, or `Refused`."""
    try:
        values = [float(value) for value in text.split(",")]
    except ValueError:
        raise Refused(f"{flag} takes comma-separated numbers, not '{text}'") from None
    if not values or not all(math.isfinite(value) for value in values):
        raise Refused(f"{flag} takes comma-separated finite numbers, not '{text}'")
    return values


# ---- embed --------------------------------------------------------------------------------------


class PixelRows:
    """The corpus as model inputs, read lazily from the resized-pixel cache.

    `retrieval-gate.py`'s `embed_*` functions iterate their `pixels` once and predict one image at
    a time, so they take this in place of a list; 5 000 images as float32 would be 3.9 GB.
    """

    def __init__(self, cache, name: str):
        self.cache, self.name = cache, name

    def __len__(self):
        return len(self.cache)

    def __iter__(self):
        import numpy as np

        for index in range(len(self.cache)):
            if index and index % 500 == 0:
                print(f"  {self.name}: {index} / {len(self.cache)} images", flush=True)
            # `ImagePreprocessing`'s arithmetic for this checkpoint, as `gate.load_pixels` has it:
            # mean 0.5, std 0.5, NCHW.
            scaled = (np.asarray(self.cache[index], dtype=np.float32) / 255.0 - 0.5) / 0.5
            yield np.ascontiguousarray(scaled.transpose(2, 0, 1)[None])


def build_pixel_cache(directory: str, manifest: dict, path: str) -> str:
    """Write every image's resized RGB bytes to `path`, in manifest order; return their sha256.

    The transform `gate.load_pixels` applies (`convert("RGB").resize((side, side), BICUBIC)`), one
    image at a time. The file is exactly the bytes the manifest's `pixels=` digest is taken over.
    """
    from PIL import Image

    side, digest = manifest["side"], hashlib.sha256()
    partial = path + ".partial"
    with open(partial, "wb") as handle:
        for index, entry in enumerate(manifest["files"]):
            if index and index % 1000 == 0:
                print(f"  pixels: {index} / {manifest['count']} images", flush=True)
            with Image.open(os.path.join(directory, entry["name"])) as opened:
                raw = opened.convert("RGB").resize((side, side), Image.BICUBIC).tobytes()
            digest.update(raw)
            handle.write(raw)
    os.replace(partial, path)
    return digest.hexdigest()


def open_pixel_cache(path: str, manifest: dict):
    import numpy as np

    side = manifest["side"]
    if os.path.getsize(path) != manifest["count"] * side * side * 3:
        raise Refused(f"{path} is not {manifest['count']} images of {side}² RGB")
    return np.memmap(path, dtype=np.uint8, mode="r", shape=(manifest["count"], side, side, 3))


def vectors_name(artifact: str, tag: str) -> str:
    return f"vectors-{artifact}{('-' + tag) if tag else ''}.npz"


def embed_arguments(args, artifacts: list) -> None:
    """`Refused` unless the arguments name everything this `embed` reads, and it is there.

    argparse makes every path optional, because which ones are needed depends on `--artifacts` and
    `--reuse-inputs`. ⚠ So the combination is judged here, before anything else: an absent flag
    must never reach a path call as `None`, and a directory that is not there must be refused, not
    created (`gate.verify_corpus` makes its directory, for `--fetch`).
    """
    if args.tag and (not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", args.tag) or len(args.tag) > MAX_TAG):
        raise Refused(f"--tag '{args.tag[:60]}' is part of a file name: letters, digits, '.', '_' and '-' only, {MAX_TAG} at most")
    needed = {}
    if args.reuse_inputs:
        needed["--reuse-inputs"] = (args.reuse_inputs, os.path.isdir)
    else:
        needed["--source"] = (args.source, os.path.isdir)
        needed["--corpus"] = (args.corpus, os.path.isdir)
    if "fp32" in artifacts or "coreml" in artifacts:
        needed["--export"] = (args.export, os.path.isdir)
        needed["--source"] = (args.source, os.path.isdir)
    if "onnx" in artifacts:
        needed["--onnx-vision"] = (args.onnx_vision, os.path.isfile)
        needed["--onnx-text"] = (args.onnx_text, os.path.isfile)
    missing = sorted(flag for flag, (value, _) in needed.items() if not value)
    if missing:
        how = "reusing an earlier run's inputs" if args.reuse_inputs else "embedding from the corpus"
        raise Refused(f"embedding {', '.join(artifacts)}, {how}, needs {', '.join(missing)}")
    for flag, (value, exists) in sorted(needed.items()):
        if not exists(value):
            raise Refused(f"{flag} {value} is not there, or is not a {'directory' if exists is os.path.isdir else 'file'}")


@gate.parses_input("the earlier run's token rows")
def read_token_rows(path: str, count: int, digest: str) -> list:
    """The token rows an earlier `embed` wrote, checked against the digest it recorded."""
    with open(path, encoding="utf-8") as handle:
        rows = json.load(handle)
    if not isinstance(rows, list) or len(rows) != count or not all(isinstance(row, list) and row for row in rows):
        raise Refused(f"{path} does not hold {count} token rows")
    if gate.token_rows_digest(rows) != digest:
        raise Refused(f"{path} is not the token rows its run recorded")
    return rows


def evaluation_outputs_in(directory: str) -> list:
    return [name for name in EVALUATION_OUTPUTS if os.path.lexists(os.path.join(directory, name))]


def remove_evaluation_outputs(directory: str) -> list:
    """Remove every file `evaluate` writes from `directory`, the result first; return their names."""
    present = evaluation_outputs_in(directory)
    for name in sorted(present, key=lambda name: name != RESULT_NAME):
        os.remove(os.path.join(directory, name))
    return present


def survey_outputs_in(directory: str) -> list:
    return [name for name in SURVEY_OUTPUTS if os.path.lexists(os.path.join(directory, name))]


def remove_survey_outputs(directory: str) -> list:
    """Remove every file `survey` writes from `directory`, the result first; return their names."""
    present = survey_outputs_in(directory)
    for name in sorted(present, key=lambda name: name != SURVEY_RESULT_NAME):
        os.remove(os.path.join(directory, name))
    return present


def refuse_another_run(existing, record: dict, keys, out: str) -> None:
    """`Refused` when `out` already holds a run embedded from other inputs than `record`'s.

    A later `embed` into a run directory adds artifacts. It may not change what the ones already
    there were embedded from, and it is refused BEFORE it writes: the record would otherwise name
    files that are no longer the ones it describes.
    """
    def facts(entry: dict, key: str):
        # The texts are the same texts when the rows are; what else the block records (which
        # checkpoint directory they came from) has grown since the first runs were written.
        return {name: entry[key].get(name) for name in ("count", "sequenceLength", "padId", "tokenRowsSha256")} if key == "texts" else entry[key]

    for key in keys if existing else ():
        if facts(existing, key) != facts(record, key):
            raise Refused(f"{out} already holds a run embedded from another {key} ({existing[key]} is not {record[key]}); nothing was written")


def run_embed(args) -> int:
    # ---- everything that can refuse without reading a model or writing a byte ------------------
    out = os.path.abspath(given(args.out, "--out"))
    if args.export:
        refuse_inside(out, args.export, "--out")
    artifacts = [name for name in ARTIFACTS if name in args.artifacts.split(",")]
    if not artifacts or set(args.artifacts.split(",")) - set(ARTIFACTS):
        raise Refused(f"--artifacts takes a comma-separated subset of {', '.join(ARTIFACTS)}")
    embed_arguments(args, artifacts)
    if args.threads is not None:
        checked_integer(args.threads, "--threads", 1, MAX_THREADS)
    if os.path.lexists(out) and not os.path.isdir(out):
        raise Refused(f"--out {out} exists and is not a directory")
    installed, pinned = check_toolchain(args.allow_unpinned)

    labels = read_label_set(args.labels)
    corpus = gate.load_corpus_manifest(args.corpus_manifest)
    record = {
        "labels": {"file": os.path.basename(args.labels), "sha256": gate.sha256_of(args.labels), "count": len(labels)},
        "corpus": {"id": corpus["id"], "count": corpus["count"], "resize": corpus["resize"],
                   "manifestSha256": gate.sha256_of(args.corpus_manifest), "pixelsSha256": corpus["pixels"]},
    }
    texts = prompts_for(labels)
    existing = read_inputs(out) if os.path.isfile(os.path.join(out, INPUTS_NAME)) else None
    if existing is None and os.path.isdir(out) and (evaluation_outputs_in(out) or survey_outputs_in(out)):
        # An `evaluate --out` directory: results of some other run's vectors. Vectors written beside
        # them would make those results read as theirs. A `survey --out` directory likewise.
        raise Refused(f"--out {out} holds evaluation outputs ({', '.join(evaluation_outputs_in(out) + survey_outputs_in(out))}) and no `embed` record: "
                      "it is an `evaluate --out` directory, or a run whose record was lost. Embed into a directory of its own")
    # The label set and the corpus are known now. ⚠ `pixelsSha256` and the token rows are known only
    # once they are computed; they are compared below, still before the first file is replaced.
    refuse_another_run(existing and {**existing, "corpus": {key: existing["corpus"].get(key) for key in ("id", "manifestSha256")}},
                       {**record, "corpus": {key: record["corpus"][key] for key in ("id", "manifestSha256")}}, ("labels", "corpus"), out)

    rows, pixels_source, source = None, None, None
    if not args.reuse_inputs:
        # Fresh token rows come from this directory's tokenizer, whatever is embedded.
        source = verify_pinned_source(args.source)
        with open(os.path.join(args.source, "preprocessor_config.json"), encoding="utf-8") as handle:
            if checkpoint_side(json.load(handle)) != corpus["side"]:
                raise Refused(f"the checkpoint does not take {corpus['side']}² images, which the corpus manifest resizes to")
    if args.reuse_inputs:
        # ⚠ A second environment (another onnxruntime) embeds the pixels and token rows the pinned
        # run made, so the only thing that differs between the two runs is the runtime.
        earlier = read_inputs(args.reuse_inputs)
        for key in ("labels", "corpus"):
            if earlier[key] != record[key]:
                raise Refused(f"--reuse-inputs was embedded from another {key}: {earlier[key]} is not {record[key]}")
        pixels_source = os.path.join(args.reuse_inputs, PIXELS_NAME)
        if not os.path.isfile(pixels_source):
            raise Refused(f"{args.reuse_inputs} no longer holds {PIXELS_NAME}; embed from the corpus instead")
        if os.path.getsize(pixels_source) != corpus["count"] * corpus["side"] ** 2 * 3 or gate.sha256_of(pixels_source) != corpus["pixels"]:
            raise Refused(f"{pixels_source} is not the pixels the corpus manifest records")
        rows = read_token_rows(os.path.join(args.reuse_inputs, TOKENS_NAME), len(texts), earlier["texts"]["tokenRowsSha256"])
        record["texts"] = {**earlier["texts"], "rowsPinned": admit_token_rows(earlier["texts"]["tokenRowsSha256"], record["labels"]["sha256"])}
        record["inputsReusedFrom"] = os.path.abspath(args.reuse_inputs)
        refuse_another_run(existing, record, ("labels", "corpus", "texts"), out)

    export_manifest = None
    if "coreml" in artifacts or "fp32" in artifacts:
        export_manifest = gate.verify_export(os.path.abspath(args.export))
        admission = gate.admit_export(export_manifest, True, False)
        gate.verify_source(args.source, export_manifest)
        with open(os.path.join(args.source, "preprocessor_config.json"), encoding="utf-8") as handle:
            side = checkpoint_side(json.load(handle))
        if side != corpus["side"]:
            raise Refused(f"the checkpoint takes {side}² images, the corpus manifest resizes to {corpus['side']}²")
        print(f"export: verified against its manifest (source revision {export_manifest['source']['revision']}; "
              f"checkpoint binding {admission['checkpointBinding']})", flush=True)
    onnx = {}
    if "onnx" in artifacts:
        for role, path in (("vision", args.onnx_vision), ("text", args.onnx_text)):
            digest = gate.sha256_of(path)
            if digest != gate.PUBLISHED_ONNX[role]:
                raise Refused(f"{path} is not the {role} ONNX file the catalog publishes (sha256 {digest})")
            onnx[role] = {"file": os.path.basename(path), "sha256": digest}
    if not args.reuse_inputs:
        # `verify_corpus`'s own message for a missing file offers `--fetch`, which this script
        # does not have: 5 000 images are fetched as COCO's one archive, not file by file.
        absent = [entry["name"] for entry in corpus["files"] if not os.path.isfile(os.path.join(args.corpus, entry["name"]))]
        if absent:
            raise Refused(f"--corpus {args.corpus} lacks {len(absent)} of the manifest's {corpus['count']} files, the first is {absent[0]}")
        gate.verify_corpus(args.corpus, corpus, fetch=False)
        print(f"corpus: {corpus['id']}  {corpus['count']} files, each checked by size and sha256", flush=True)

    # ⚠ Imported here, not at the top: everything above refuses with no package installed, and a
    # refusal must not turn into a `ModuleNotFoundError` because numpy is missing.
    import numpy as np

    # ---- from here on this run computes; what it writes goes to a staging directory ------------
    # ⚠⚠ Nothing in `out` is replaced until the last refusal is behind us. The pixels, the token
    # rows and each vector file are written under `staging`, and moved into place together at the
    # end, the record last. A run that is refused, or dies, leaves an earlier run in `out` byte for
    # byte as it was.
    import shutil
    import tempfile

    created = not os.path.isdir(out)
    os.makedirs(out, exist_ok=True)
    staging = tempfile.mkdtemp(prefix=".label-eval-staging-", dir=out)
    cache = None
    try:
        staged = []
        if not args.reuse_inputs:
            pixels_source = os.path.join(staging, PIXELS_NAME)
            digest = build_pixel_cache(args.corpus, corpus, pixels_source)
            if digest != corpus["pixels"]:
                if pinned:
                    raise Refused(f"the resized pixels are {digest}, the manifest recorded {corpus['pixels']}, under the pinned toolchain")
                print(f"⚠ the resized pixels are {digest}, not the manifest's {corpus['pixels']}: another decoder or resampler")
                record["corpus"]["pixelsSha256"] = digest
            ids, length = gate.tokenise(args.source, [text for _, _, text in texts])
            rows = ids.tolist()
            with open(os.path.join(staging, TOKENS_NAME), "w", encoding="utf-8") as handle:
                json.dump(rows, handle)
            record["texts"] = {
                "variants": list(PROMPT_VARIANTS),
                "count": len(texts),
                "sequenceLength": length,
                "padId": gate.PAD_ID,
                "tokenRowsSha256": gate.token_rows_digest(rows),
                "source": source,
            }
            record["texts"]["rowsPinned"] = admit_token_rows(record["texts"]["tokenRowsSha256"], record["labels"]["sha256"])
            refuse_another_run(existing, record, ("labels", "corpus", "texts"), out)
            staged += [PIXELS_NAME, TOKENS_NAME]
        ids = np.array(rows, dtype=np.int64)
        cache = open_pixel_cache(pixels_source, corpus)

        toolchain = {"python": platform.python_version(), "pinned": pinned, **installed}
        record["artifacts"] = {}
        for artifact in artifacts:
            pixel_rows = PixelRows(cache, artifact)
            print(f"{artifact}: embedding {len(pixel_rows)} images and {len(ids)} texts", flush=True)
            if artifact == "fp32":
                if args.threads is not None:
                    import torch

                    torch.set_num_threads(args.threads)
                images, text_vectors = gate.embed_fp32(args.source, pixel_rows, ids)
                identity = {"source": export_manifest["source"]}
            elif artifact == "onnx":
                images, text_vectors = gate.embed_onnx(args.onnx_vision, args.onnx_text, pixel_rows, ids, threads=args.threads)
                identity = {"files": onnx, "onnxruntime": installed.get("onnxruntime")}
            else:
                images, text_vectors = gate.embed_coreml(os.path.abspath(args.export), pixel_rows, ids, args.compute_units)
                identity = {"artifacts": export_manifest["artifacts"], "computeUnits": args.compute_units,
                            "shippedComputeUnits": args.compute_units == gate.SHIPPED_COMPUTE_UNITS,
                            "exportToolchain": export_manifest.get("toolchain")}
            images = gate.normalised(images, f"{artifact} images").astype(np.float32)
            text_vectors = gate.normalised(text_vectors, f"{artifact} texts").astype(np.float32)
            name = vectors_name(artifact, args.tag)
            np.savez(os.path.join(staging, name), images=images, texts=text_vectors)
            record["artifacts"][name] = {**identity, "artifact": artifact, "sha256": gate.sha256_of(os.path.join(staging, name)),
                                         "toolchain": toolchain,
                                         # ⚠ `threads` is the cap asked for, `None` for none. Core ML has no such cap.
                                         "where": {"machine": platform.machine(), "macOS": platform.mac_ver()[0],
                                                   "threads": None if artifact == "coreml" else args.threads}}
            staged.append(name)
            print(f"{artifact}: embedded", flush=True)

        # ---- nothing below refuses: move the staged files into place, the record last ----------
        if existing:
            record["artifacts"] = {**existing["artifacts"], **record["artifacts"]}
        cache = None
        # ⚠⚠ Invalidate first. The moves below are not one atomic step; if the process dies between
        # two of them, the directory must not be left with the old record describing new files. With
        # no record it is refused by every reader (`read_inputs`) as an incomplete run.
        if os.path.lexists(os.path.join(out, INPUTS_NAME)):
            os.remove(os.path.join(out, INPUTS_NAME))
        # ⚠⚠ And every evaluation output goes, whatever this run embedded. They were computed from
        # the directory as it was. A base vector file replaced makes them wrong; a tagged file
        # added or replaced makes the tables that compared it wrong, and the rest a result that no
        # longer says what the directory holds. One rule, no cases: `embed` changed the run, so
        # `evaluate` runs again.
        removed = remove_evaluation_outputs(out)
        if removed:
            print(f"removed {len(removed)} evaluation outputs from {out} ({', '.join(removed)}): they were computed before this embed; run `evaluate` again", flush=True)
        removed = remove_survey_outputs(out)
        if removed:
            print(f"removed {len(removed)} survey outputs from {out} ({', '.join(removed)}): they were computed before this embed; run `survey` again", flush=True)
        for name in staged:
            os.replace(os.path.join(staging, name), os.path.join(out, name))
            print(f"wrote {os.path.join(out, name)}", flush=True)
        inputs_path = os.path.join(out, INPUTS_NAME)
        write_json(inputs_path, record)
        print(f"wrote {inputs_path}")
    finally:
        cache = None
        shutil.rmtree(staging, ignore_errors=True)
        if created:
            try:
                os.rmdir(out)  # only an empty one: a refused run leaves no directory it made
            except OSError:
                pass
    return 0


# ---- evaluate -----------------------------------------------------------------------------------


def load_vectors(vectors_path: str, labels: list, count: int) -> tuple:
    """`(images, texts)` of one artifact's vector file. `Refused` on a shape that is not this
    label set over this corpus."""
    import numpy as np

    import zipfile

    try:
        with np.load(vectors_path) as archive:
            images, texts = archive["images"].astype(np.float64), archive["texts"].astype(np.float64)
    except (zipfile.BadZipFile, ValueError, KeyError, EOFError) as error:
        raise Refused(f"{vectors_path} is not a vector file ({type(error).__name__}: {error})") from None
    if images.ndim != 2 or texts.ndim != 2 or images.shape[0] != count or texts.shape[0] != len(PROMPT_VARIANTS) * len(labels) or images.shape[1] != texts.shape[1]:
        raise Refused(f"{vectors_path} holds {images.shape} image and {texts.shape} text vectors, not this corpus and label set")
    return images, texts


def cosine_scores(vectors: tuple, labels: list, what: str) -> dict:
    """`{variant: [image][label] cosines}` of one set of image vectors against one set of texts."""
    import numpy as np

    images, texts = vectors
    scores = {}
    for position, variant in enumerate(PROMPT_VARIANTS):
        rows = texts[position * len(labels) : (position + 1) * len(labels)]
        # `einsum`, not `@`: see `gate.similarity`.
        scores[variant] = np.einsum("id,ld->il", images, rows)
        if not np.isfinite(scores[variant]).all():
            raise Refused(f"{what}: a similarity is not finite")
    return scores


def bootstrap(scores, positives, threshold, counts) -> dict:
    """95 % percentile intervals over resampled images: AP, and precision and recall at `threshold`.

    `counts[b][i]` is how often resample b drew image i. ⚠ The threshold is held fixed: the
    interval covers which images were held out, not where the calibration half put the cut.

    ⚠ The AP of a resample is `average_precision(scores, positives, weights=counts[b])`, tied
    scores included: every image of a tied group takes the precision at the END of its group, as
    `pr_points` makes the group one threshold. Accumulating image by image would rank a tied
    positive above or below a tied negative by input order. `test_label_eval.py` holds the two
    implementations together, on tied data in two input orders.
    """
    import numpy as np

    scores, positives = np.asarray(scores, dtype=np.float64), np.asarray(positives, dtype=bool)
    order = np.argsort(-scores, kind="stable")
    ranked = scores[order]
    weights, truth = counts[:, order].astype(np.float64), positives[order]
    hits = weights * truth
    cumulative_hits, cumulative_all = np.cumsum(hits, axis=1), np.cumsum(weights, axis=1)
    # For each position, the last position holding the same score.
    group_end = np.searchsorted(-ranked, -ranked, side="right") - 1
    with np.errstate(invalid="ignore", divide="ignore"):
        precision = np.where(cumulative_all[:, group_end] > 0, cumulative_hits[:, group_end] / cumulative_all[:, group_end], 0.0)
        ap = (hits * precision).sum(axis=1) / hits.sum(axis=1)

    # ⚠ A resample can draw no positive (AP and recall are then 0/0) or no firing image (precision
    # is). Such a resample has no value for that figure; it is left out of that figure's interval,
    # and COUNTED, so an interval over fewer resamples than were drawn says so. With every resample
    # undefined there is no interval (`None`), which the tables print as "—".
    undefined = {}

    def interval(name, values):
        defined = values[np.isfinite(values)]
        undefined[name] = int(len(values) - len(defined))
        return [float(np.percentile(defined, 2.5)), float(np.percentile(defined, 97.5))] if len(defined) else None

    out = {"ap": interval("ap", ap), "undefined": undefined}
    if threshold is not None:
        fired = (ranked >= threshold).astype(np.float64)
        fired_hits = (hits * fired).sum(axis=1)
        with np.errstate(invalid="ignore", divide="ignore"):
            out["precision"] = interval("precision", fired_hits / (weights * fired).sum(axis=1))
            out["recall"] = interval("recall", fired_hits / hits.sum(axis=1))
    return out


def curve(points, grid=(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)) -> list:
    """The precision/recall curve cut down for the result: at each recall on the grid, the
    threshold of the first point reaching it and the precision there."""
    out = []
    for wanted in grid:
        reached = next((point for point in points if point[2] >= wanted), None)
        if reached:
            out.append({"recall": wanted, "threshold": reached[0], "precision": reached[1]})
    return out


def target_key(target: float) -> str:
    """The key a target's figures are stored under, in the result and in every table: `"0.90"`.

    ⚠ Two decimals, and so `checked_targets` admits two-decimal targets only. 0.901 and 0.904 would
    share `"0.90"`, the later overwriting the earlier while the result still names the first.
    """
    return f"{target:.2f}"


def checked_targets(text: str, primary: float) -> list:
    """`--targets`, sorted, or `Refused`: precisions in [0.5, 1), each exactly its own two-decimal
    key, no two alike, and `primary` one of them."""
    targets = sorted(numbers(text, "--targets"))
    if any(not 0.5 <= target < 1.0 for target in targets):
        raise Refused("--targets are precisions in [0.5, 1)")
    coarse = [target for target in targets if float(target_key(target)) != target]
    if coarse:
        raise Refused(f"--targets have two decimals at most (0.90, 0.95): {coarse} would be stored under {[target_key(target) for target in coarse]}, "
                      "which is not the precision asked for")
    if len(set(targets)) != len(targets):
        raise Refused(f"--targets repeats a precision: {targets}")
    if primary not in targets:
        raise Refused(f"--primary-target {primary} is not one of --targets {targets}")
    return targets


def given(value, flag: str) -> str:
    """A required path argument, or `Refused` when it is empty: `os.path.abspath("")` is the
    working directory, and an empty `--out` must not mean "write here"."""
    if not value or not value.strip():
        raise Refused(f"{flag} is empty")
    return value


def checked_integer(value: int, flag: str, low: int, high=None) -> int:
    """An integer argument inside the domain the code after it needs, or `Refused`."""
    if value < low or (high is not None and value > high):
        raise Refused(f"{flag} is {'at least ' + str(low) if high is None else f'between {low} and {high}'}, not {str(value)[:40]}")
    return value


def checked_margins(text: str) -> list:
    """`--margins`, in the order given, or `Refused`: none negative, no two alike.

    A margin's sets are stored under `str(margin)`, which is exact for a float, so two margins
    collide only when they are equal (`-0.0` and `0.0` included).
    """
    margins = [margin + 0.0 for margin in numbers(text, "--margins")]  # -0.0 + 0.0 is 0.0
    if any(margin < 0 for margin in margins):
        raise Refused("--margins are not negative")
    if len(set(margins)) != len(margins):
        raise Refused(f"--margins repeats a margin: {margins}")
    return margins


def checked_prompt(name: str) -> str:
    """`--prompt`, or `Refused`: one of the variants `embed` wrote text vectors for."""
    if name not in PROMPT_VARIANTS:
        raise Refused(f"--prompt is one of {', '.join(PROMPT_VARIANTS)}, not {str(name)[:40]!r}")
    return name


def checked_truth(name: str) -> str:
    """`evaluate --truth`, or `Refused`: a ground truth `evaluate` has a reader for."""
    if name not in EVALUATION_TRUTHS:
        raise Refused(f"--truth is one of {', '.join(EVALUATION_TRUTHS)}, not {str(name)[:40]!r}")
    return name


def checked_variants(names) -> list:
    """`--variant` names, or `Refused`: none twice, and none of the base vector files, which a
    variant is compared WITH."""
    names = list(names or [])
    base = {vectors_name(artifact, "") for artifact in ARTIFACTS}
    if len(set(names)) != len(names):
        raise Refused(f"--variant names a file twice: {names}")
    if base.intersection(names):
        raise Refused(f"--variant takes a tagged vector file; {sorted(base.intersection(names))} is the pinned run it would be compared with")
    return names


def evaluate_artifact(scores, labels, mapped, truth, cal, held, targets, counts, everything=None, held_all=None) -> dict:
    """Per mapped label, one artifact: the score distribution, AP, and each target's floor with
    its calibration-half and held-out figures.

    `cal` and `held` are every label's rows, or each label's own (`rows_of`). With a label's own
    rows, `everything` is its rows of the whole corpus and `held_all` the held-out half the
    resample counts were drawn over. ⚠ A resample then draws IMAGES, and each label reads the
    draws of the images it was verified on: how many of those a resample holds varies, as it
    would in another corpus verified the same way.
    """
    column = {row["label"]: index for index, row in enumerate(labels)}
    position = None if held_all is None else {index: place for place, index in enumerate(held_all)}
    out = {}
    for label in mapped:
        row = scores[:, column[label]].tolist()
        positives = truth[label]
        cal_rows, held_rows = rows_of(cal, label), rows_of(held, label)
        cal_scores, cal_truth = [row[i] for i in cal_rows], [positives[i] for i in cal_rows]
        held_scores, held_truth = [row[i] for i in held_rows], [positives[i] for i in held_rows]
        label_counts = counts if position is None else counts[:, [position[index] for index in held_rows]]
        scored = range(len(row)) if everything is None else rows_of(everything, label)
        entry = {
            "distribution": {
                "half": "all",
                "positives": quantiles([row[i] for i in scored if positives[i]]),
                "negatives": quantiles([row[i] for i in scored if not positives[i]]),
            },
            "ap": {"all": average_precision([row[i] for i in scored], [positives[i] for i in scored]), "calibration": average_precision(cal_scores, cal_truth),
                   "heldOut": average_precision(held_scores, held_truth)},
            "curveHeldOut": curve(pr_points(held_scores, held_truth)),
            "targets": {},
        }
        for target in targets:
            threshold = threshold_for_precision(cal_scores, cal_truth, target)
            interval = bootstrap(held_scores, held_truth, threshold, label_counts)
            held_fired = fires(held_scores, threshold)
            held_confusion = confusion(held_fired, held_truth)
            negatives = len(held_truth) - held_confusion["positives"]
            entry["ap"]["heldOutCi95"] = interval["ap"]
            entry["ap"]["heldOutCi95UndefinedResamples"] = interval["undefined"]["ap"]
            entry["targets"][target_key(target)] = {
                "threshold": threshold,
                "probability": None if threshold is None else probability(threshold),
                "probabilityAppConstants": None if threshold is None else probability(threshold, APP_CALIBRATION),
                "calibration": confusion(fires(cal_scores, threshold), cal_truth),
                "heldOut": {**held_confusion,
                            "precisionCi95": interval.get("precision"), "recallCi95": interval.get("recall"),
                            "ci95UndefinedResamples": {name: interval["undefined"].get(name) for name in ("precision", "recall")},
                            # Beside the bootstrap: see `wilson`.
                            "precisionWilson95": wilson(held_confusion["hits"], held_confusion["fired"]),
                            # The rate a precision can be re-derived from at another prevalence:
                            # of the held-out negatives, the share the floor lets through.
                            "negatives": negatives,
                            "falsePositiveRate": (held_confusion["fired"] - held_confusion["hits"]) / negatives if negatives else None},
                # ⚠ Over every image of the corpus, verified for this label or not: it needs no truth.
                "firingRateAll": sum(fires(row, threshold)) / len(row),
            }
        out[label] = entry
    return out


def cross_thresholds(scores, per_label, labels, mapped, truth, held, targets) -> dict:
    """Each shipped artifact's scores under the *other* one's floors, on the held-out half."""
    column = {row["label"]: index for index, row in enumerate(labels)}
    out = {}
    for own, other in (("coreml", "onnx"), ("onnx", "coreml")):
        out[f"{own}ScoresUnder{other.capitalize()}Floors"] = table = {}
        for label in mapped:
            row = [scores[own][i, column[label]] for i in rows_of(held, label)]
            held_truth = [truth[label][i] for i in rows_of(held, label)]
            table[label] = {}
            for target in targets:
                key = target_key(target)
                mine = per_label[own][label]["targets"][key]["heldOut"]
                theirs = confusion(fires(row, per_label[other][label]["targets"][key]["threshold"]), held_truth)
                shift = {name: (None if mine[name] is None or theirs[name] is None else theirs[name] - mine[name]) for name in ("precision", "recall")}
                table[label][key] = {"ownFloors": {name: mine[name] for name in ("precision", "recall", "fired")},
                                     "otherFloors": {name: theirs[name] for name in ("precision", "recall", "fired")}, "shift": shift}
    return out


PAIRS = (("onnx", "coreml"), ("onnx", "fp32"), ("coreml", "fp32"))


def pair_key(a: str, b: str) -> str:
    return f"{a}Vs{b.capitalize()}"


def difference(a, b) -> dict:
    """Mean and standard deviation of `a - b` over the same images.

    The mean is an offset a per-artifact floor absorbs. The deviation is per-image noise, which no
    floor absorbs: it is what moves an image across a threshold on one side only.
    """
    deltas = [x - y for x, y in zip(a, b)]
    centre = sum(deltas) / len(deltas)
    return {"mean": centre, "sd": math.sqrt(sum((delta - centre) ** 2 for delta in deltas) / len(deltas))}


def pooled_jaccard(fired_a: dict, fired_b: dict) -> dict:
    """Jaccard over every (image, label) cell of two `{label: firing row}` tables."""
    both = sum(1 for label in fired_a for x, y in zip(fired_a[label], fired_b[label]) if x and y)
    either = sum(1 for label in fired_a for x, y in zip(fired_a[label], fired_b[label]) if x or y)
    return {"both": both, "disagreements": either - both, "jaccard": both / either if either else None}


def own_floor_firing(scores, labels, mapped, truth, cal, rows, target) -> dict:
    """`{label: firing row over rows}` for one score matrix under floors calibrated on its own
    calibration half."""
    column = {row["label"]: index for index, row in enumerate(labels)}
    fired = {}
    for label in mapped:
        row = scores[:, column[label]].tolist()
        floor = threshold_for_precision([row[i] for i in rows_of(cal, label)], [truth[label][i] for i in rows_of(cal, label)], target)
        fired[label] = fires([row[i] for i in rows], floor)
    return fired


def firing_agreement(scores, per_label, labels, mapped, rows, target, floors_from=None) -> dict:
    """Per-image, per-label agreement of each pair over `rows`, each artifact under its own floors
    (or all under `floors_from`'s), per label and pooled over every (image, label) cell."""
    column = {row["label"]: index for index, row in enumerate(labels)}
    key = target_key(target)
    out = {}
    for a, b in PAIRS:
        per, pooled_a, pooled_b = {}, [], []
        for label in mapped:
            floor_a = per_label[floors_from or a][label]["targets"][key]["threshold"]
            floor_b = per_label[floors_from or b][label]["targets"][key]["threshold"]
            row_a = [scores[a][i, column[label]] for i in rows_of(rows, label)]
            row_b = [scores[b][i, column[label]] for i in rows_of(rows, label)]
            fired_a, fired_b = fires(row_a, floor_a), fires(row_b, floor_b)
            entry = agreement(fired_a, fired_b)
            entry["nearThreshold"] = near_threshold(row_a, floor_a, row_b, floor_b)
            entry["scoreDifference"] = difference(row_a, row_b)
            per[label] = entry
            pooled_a += fired_a
            pooled_b += fired_b
        pooled = agreement(pooled_a, pooled_b)
        disagreements = sum(entry["nearThreshold"]["disagreements"] for entry in per.values())
        pooled["nearThreshold"] = {
            "disagreements": disagreements,
            "within": {str(margin): (sum((entry["nearThreshold"]["within"][str(margin)] or 0) * entry["nearThreshold"]["disagreements"]
                                         for entry in per.values()) / disagreements if disagreements else None) for margin in NEAR},
        }
        out[pair_key(a, b)] = {"pooled": pooled, "perLabel": per}
    return out


def selection_run(scores, per_label, labels, mapping, mapped, truth, rows, target, margin, verified=None) -> dict:
    """The full rule over `rows`: each artifact's label sets, their agreement, and what the rule
    costs against independent floors.

    ⚠ The sets and their agreement are over every row: the rule needs no truth. Precision and
    recall are, per label, over the rows it was `verified` on (all of them without a mask).
    """
    column = {row["label"]: index for index, row in enumerate(labels)}
    key = target_key(target)
    sets, detail = {}, {}
    for artifact in ARTIFACTS:
        floors = {label: per_label[artifact][label]["targets"][key]["threshold"] for label in mapped}
        vocabulary = evaluation_vocabulary(labels, mapping, floors, margin)
        terms = [entry["label"] for entry in vocabulary]
        parents = sorted({entry["parent"] for entry in vocabulary if entry["tier"] == "leaf"})
        outcomes = {parent: {"leaf": 0, "belowFloor": 0, "insideMargin": 0} for parent in parents}
        chosen = []
        for i in rows:
            tags = [(term, scores[artifact][i, column[term]]) for term in terms]
            chosen.append(sorted(select_labels(tags, vocabulary)))
            for parent in parents:
                outcomes[parent][leaf_outcome(tags, vocabulary, parent)] += 1
        sets[artifact] = chosen
        independent_hits = independent_fired = rule_hits = rule_fired = positives = 0
        per = {}
        for label in mapped:
            places = [place for place, i in enumerate(rows) if verified is None or verified[label][i]]
            label_truth = [truth[label][rows[place]] for place in places]
            by_rule = confusion([label in chosen[place] for place in places], label_truth)
            alone = confusion(fires([scores[artifact][rows[place], column[label]] for place in places], floors[label]), label_truth)
            per[label] = {"rule": by_rule, "independent": alone}
            rule_hits, rule_fired = rule_hits + by_rule["hits"], rule_fired + by_rule["fired"]
            independent_hits, independent_fired = independent_hits + alone["hits"], independent_fired + alone["fired"]
            positives += by_rule["positives"]
        detail[artifact] = {
            "leafOutcomes": outcomes,
            "imagesWithALabel": sum(1 for entry in chosen if entry) / len(chosen),
            "meanLabelsPerImage": sum(len(entry) for entry in chosen) / len(chosen),
            "micro": {
                "rule": {"precision": rule_hits / rule_fired if rule_fired else None, "recall": rule_hits / positives},
                "independent": {"precision": independent_hits / independent_fired if independent_fired else None, "recall": independent_hits / positives},
            },
            "perLabel": per,
        }
    return {
        "margin": margin,
        "target": target,
        "agreement": {pair_key(a, b): set_agreement(sets[a], sets[b]) for a, b in PAIRS},
        "artifacts": detail,
    }, sets


def sensitivity(scores, labels, mapped, truths, held, baseline) -> dict:
    """Held-out AP of every mapped label under each prompt variant and each ground-truth rule."""
    column = {row["label"]: index for index, row in enumerate(labels)}
    out = {"prompts": {}, "groundTruth": {}}
    for artifact in scores:
        out["prompts"][artifact] = {}
        for label in mapped:
            held_truth = [truths["any"][label][i] for i in rows_of(held, label)]
            out["prompts"][artifact][label] = {
                variant: average_precision([scores[artifact][variant][i, column[label]] for i in rows_of(held, label)], held_truth) for variant in PROMPT_VARIANTS
            }
        out["groundTruth"][artifact] = {}
        for label in mapped:
            row = [scores[artifact][baseline][i, column[label]] for i in rows_of(held, label)]
            entry = {}
            for rule in truths:  # COCO has three rules (`GROUND_TRUTHS`); Open Images has `any` alone
                held_truth = [truths[rule][label][i] for i in rows_of(held, label)]
                entry[rule] = {"positives": sum(held_truth), "ap": average_precision(row, held_truth) if any(held_truth) else None}
            out["groundTruth"][artifact][label] = entry
    return out


def summarise(result: dict) -> dict:
    """The counts the note leads with, taken from the result's own tables."""
    mapped, per = result["labels"]["mapped"], result["perLabel"]
    out = {"floors": {}, "firingFlags": {}, "prompts": {}}
    for target in result["targets"]:
        key = target_key(target)
        placed = {artifact: [label for label in mapped if per[artifact][label]["targets"][key]["threshold"] is not None] for artifact in ARTIFACTS}
        entry = {"labelsWithAFloor": {artifact: len(labels) for artifact, labels in placed.items()},
                 "noFloorOnEitherShipped": [label for label in mapped if all(label not in placed[artifact] for artifact in SHIPPED)],
                 "floorOnOneShippedOnly": {artifact: [label for label in placed[artifact] if any(label not in placed[other] for other in SHIPPED)] for artifact in SHIPPED},
                 "heldOut": {}}
        for artifact in ARTIFACTS:
            rows = [per[artifact][label]["targets"][key]["heldOut"] for label in placed[artifact]]
            fired, hits = sum(row["fired"] for row in rows), sum(row["hits"] for row in rows)
            entry["heldOut"][artifact] = {
                "microPrecision": hits / fired if fired else None,
                "labelsBelowTarget": sum(1 for row in rows if row["precision"] is not None and row["precision"] < target),
                "labelsWhoseCi95ExcludesTarget": sum(1 for row in rows if row["precisionCi95"] and row["precisionCi95"][1] < target),
                "medianRecall": median(row["recall"] for row in rows) if rows else None,
            }
        out["floors"][key] = entry
    # Which labels hold a bar on the HELD-OUT half, three ways of reading "hold". ⚠ This reports;
    # it chooses no bar. `bothShipped` is the labels that hold it on the ONNX pair and on the Core
    # ML pair, each under its own floor.
    out["heldOutBars"] = {}
    for target in result["targets"]:
        key = target_key(target)

        def holds(artifact, label, how):
            row = per[artifact][label]["targets"][key]["heldOut"]
            if row["precision"] is None:
                return False
            if how == "point":
                return row["precision"] >= target
            interval = row["precisionCi95"] if how == "bootstrapLower" else row["precisionWilson95"]
            return bool(interval) and interval[0] >= target

        out["heldOutBars"][key] = {
            how: {**{artifact: [label for label in mapped if holds(artifact, label, how)] for artifact in ARTIFACTS},
                  "bothShipped": [label for label in mapped if all(holds(artifact, label, how) for artifact in SHIPPED)]}
            for how in ("point", "bootstrapLower", "wilsonLower")
        }
    primary = target_key(result["primaryTarget"])
    for artifact in ARTIFACTS:
        out["firingFlags"][artifact] = {label: per[artifact][label]["targets"][primary]["firingRateAll"] for label in mapped
                                        if per[artifact][label]["targets"][primary]["firingRateAll"] >= FLAG_FIRING_RATE}
    for artifact, table in result["sensitivity"]["prompts"].items():
        out["prompts"][artifact] = {
            "meanAp": {variant: mean(table[label][variant] for label in mapped) for variant in PROMPT_VARIANTS},
            "labelsWhereBareBeatsTsv": sum(1 for label in mapped if table[label]["bare"] > table[label]["tsv"]),
            "labelsWhereUniformBeatsTsv": sum(1 for label in mapped if table[label]["uniform"] > table[label]["tsv"]),
        }
    return out


def variant_tag(name: str) -> str:
    """`onnx-ort1.29.0` for `vectors-onnx-ort1.29.0.npz`: how a variant is named in the last line."""
    return name[len("vectors-"):-len(".npz")] if name.startswith("vectors-") and name.endswith(".npz") else name


def variant_departures(entry: dict, pins: dict) -> list:
    """What a variant's recorded provenance departs from, of this script's pins. Empty when none.

    ⚠ Judged from the versions the record holds, not from its `pinned` flag alone: the flag is what
    that `embed` believed (as `gate.recorded_toolchain_misses` has it for an export). A record with
    no toolchain cannot be shown to be on the pins, and is off them.
    """
    toolchain = entry.get("toolchain")
    if not isinstance(toolchain, dict):
        return ["records no toolchain"]
    departs = [f"{name} {toolchain.get(name)} (pinned {pin})" for name, pin in sorted(pins.items()) if toolchain.get(name) != pin]
    departs += [f"python {have} (pinned {want})" for have, want in gate.python_pin_miss(str(toolchain.get("python", ""))).values()]
    if toolchain.get("pinned") is not True and not departs:
        departs.append("its `embed` ran with --allow-unpinned")
    if entry.get("artifact") == "coreml" and entry.get("shippedComputeUnits") is not True:
        departs.append(f"Core ML on {entry.get('computeUnits')}, the app ships {gate.SHIPPED_COMPUTE_UNITS}")
    return departs


def verdict_line(mapped: int, total: int, overrides: list, off_pin_variants=(), prompt: str = DEFAULT_PROMPT, truth: str = "COCO") -> str:
    """The last line of a run. ⚠ An overridden run never prints a bare MEASURED, and neither does
    one whose tables include an off-pin variant: that is said apart, because it is not the base
    measurement that departed. A prompt variant other than the default is named: it is a
    parameter of the run, as the seed is, and every floor in the result belongs to it."""
    line = f"label eval: MEASURED — {mapped} of {total} labels scored against {truth}; {total - mapped} are outside what this can score"
    if prompt != DEFAULT_PROMPT:
        line += f"; prompt variant: {prompt}"
    if off_pin_variants:
        said = f"{len(off_pin_variants)} off-pin variant{'s' if len(off_pin_variants) != 1 else ''}: {', '.join(off_pin_variants)}"
        line = line.replace("label eval: MEASURED", f"label eval: MEASURED; {said}", 1) + " ⚠ a variant's tables are a comparison with the pinned run, not part of it"
    if overrides:
        line = line.replace("label eval: MEASURED", "label eval: OVERRIDDEN MEASURED", 1) + " ⚠ OVERRIDDEN: " + "; ".join(overrides)
    return line


def mean(values) -> float:
    values = [value for value in values if value is not None]
    return sum(values) / len(values) if values else float("nan")


def fmt(value, digits=3) -> str:
    return "—" if value is None else f"{value:.{digits}f}"


def fmt_interval(interval, digits=3) -> str:
    """`[low, high]`, or `[—]` when no resample defined the figure (`bootstrap`)."""
    return "[—]" if interval is None else f"[{fmt(interval[0], digits)}, {fmt(interval[1], digits)}]"


def tables(result: dict) -> str:
    """The Markdown tables of a result, for the note in `docs/models.md`."""
    labels, primary = result["labels"]["mapped"], target_key(result["primaryTarget"])
    info, per = result["labels"]["mapping"], result["perLabel"]
    truth_name, masked = result["truth"]["name"], result["truth"]["kind"] != "coco"
    mark = lambda label: {"proxy": " ᵖ", "broader": " ᵇ", "narrower": " ⁿ"}.get(info[label]["fidelity"], "")
    scored_on = lambda label: result["positives"][label].get("scored", {"all": result["inputs"]["corpus"]["count"]})["all"]
    lines = [f"Corpus {result['inputs']['corpus']['id'][:12]}…, split seed {result['split']['seed']}: "
             f"{result['split']['calibration']} calibration / {result['split']['heldOut']} held-out images. "
             f"Prompt variant: {result.get('promptVariant', DEFAULT_PROMPT)}. Ground truth: {truth_name}.", ""]
    if masked:
        lines += [f"⚠ Each label is scored over ITS SAMPLE and over no other image: at most {result['truth']['perLabel']} images it is verified present on and as many it is "
                  f"verified absent on, at least {result['truth']['minPerHalf']} of each in each half. A precision is at the mix of positives and negatives of that sample.", ""]
    for key, entry in result["summary"]["heldOutBars"].items():
        lines += [f"Held-out precision ≥ {key}, labels (fp32 / ONNX / Core ML / both shipped): "
                  + "; ".join(f"{how} {len(entry[how]['fp32'])} / {len(entry[how]['onnx'])} / {len(entry[how]['coreml'])} / {len(entry[how]['bothShipped'])}" for how in entry), ""]
    for key, entry in result["summary"]["floors"].items():
        lines += [f"Target precision {key}: labels with a floor {entry['labelsWithAFloor']}; none on either shipped artifact: "
                  f"{', '.join(entry['noFloorOnEitherShipped']) or '—'}; on one only: {entry['floorOnOneShippedOnly']}; "
                  f"held-out {json.dumps(entry['heldOut'])}", ""]
    lines += [f"Firing on ≥ {FLAG_FIRING_RATE:.0%} of all images at {primary}: {json.dumps(result['summary']['firingFlags'])}", "",
              f"Prompts: {json.dumps(result['summary']['prompts'])}", ""]

    lines += ["### Per label, held-out half: AP, and the floor for precision " + primary, "",
              f"| Label | {truth_name} | pos (cal / held) | AP fp32 | AP ONNX [95 % CI] | AP Core ML [95 % CI] | floor ONNX | floor Core ML | P / R ONNX | P / R Core ML |",
              "|---|---|---|---|---|---|---|---|---|---|"]
    for label in labels:
        cells = [label + mark(label), "; ".join(info[label]["categories"]) if len(info[label]["categories"]) < 4 else f"{len(info[label]['categories'])} categories",
                 f"{result['positives'][label]['calibration']} / {result['positives'][label]['heldOut']}", fmt(per["fp32"][label]["ap"]["heldOut"])]
        for artifact in SHIPPED:
            cells.append(f"{fmt(per[artifact][label]['ap']['heldOut'])} {fmt_interval(per[artifact][label]['ap']['heldOutCi95'])}")
        for artifact in SHIPPED:
            entry = per[artifact][label]["targets"][primary]
            cells.append("—" if entry["threshold"] is None else f"{entry['threshold']:.4f} (p {entry['probability']:.0e})")
        for artifact in SHIPPED:
            held = per[artifact][label]["targets"][primary]["heldOut"]
            cells.append(f"{fmt(held['precision'], 2)} / {fmt(held['recall'], 2)}")
        lines.append("| " + " | ".join(cells) + " |")

    for target in result["targets"]:
        key = target_key(target)
        lines += ["", f"### One threshold table for both? Held-out, target precision {key}", "",
                  "| Label | Core ML own floors P / R | Core ML under ONNX floors P / R | shift P / R | ONNX own floors P / R | ONNX under Core ML floors P / R | shift P / R |",
                  "|---|---|---|---|---|---|---|"]
        for label in labels:
            cells = [label]
            for table in ("coremlScoresUnderOnnxFloors", "onnxScoresUnderCoremlFloors"):
                entry = result["crossThresholds"][table][label][key]
                cells += [f"{fmt(entry['ownFloors']['precision'], 2)} / {fmt(entry['ownFloors']['recall'], 2)}",
                          f"{fmt(entry['otherFloors']['precision'], 2)} / {fmt(entry['otherFloors']['recall'], 2)}",
                          f"{fmt(entry['shift']['precision'], 2) if entry['shift']['precision'] is None else format(entry['shift']['precision'], '+.2f')} / "
                          f"{fmt(entry['shift']['recall'], 2) if entry['shift']['recall'] is None else format(entry['shift']['recall'], '+.2f')}"]
            lines.append("| " + " | ".join(cells) + " |")

    for name, block in result["agreement"].items():
        lines += ["", f"### Agreement — {name}", "",
                  "| Pair | both | only first | only second | neither | agreement | Jaccard | disagreements within 0.005 / 0.01 of a floor |", "|---|---|---|---|---|---|---|---|"]
        for pair, entry in block.items():
            pooled = entry["pooled"]
            lines.append(f"| {pair} | {pooled['both']} | {pooled['onlyA']} | {pooled['onlyB']} | {pooled['neither']} | {fmt(pooled['agreement'], 4)} | {fmt(pooled['jaccard'])} "
                         f"| {fmt(pooled['nearThreshold']['within']['0.005'], 2)} / {fmt(pooled['nearThreshold']['within']['0.01'], 2)} |")
    block = result["agreement"]["heldOutOwnFloors"][pair_key("onnx", "coreml")]["perLabel"]
    lines += ["", "### ONNX vs Core ML per label, held-out, each under its own floors, target " + primary, "",
              "| Label | both | only ONNX | only Core ML | Jaccard | within 0.005 / 0.01 | score ONNX − Core ML, mean / sd | firing rate ONNX / Core ML (all) | prevalence (all) |", "|---|---|---|---|---|---|---|---|---|"]
    for label in labels:
        entry = block[label]
        rates = [per[artifact][label]["targets"][primary]["firingRateAll"] for artifact in SHIPPED]
        flag = " ⚠" if max(rates) >= FLAG_FIRING_RATE else ""
        lines.append(f"| {label} | {entry['both']} | {entry['onlyA']} | {entry['onlyB']} | {fmt(entry['jaccard'], 2)} "
                     f"| {fmt(entry['nearThreshold']['within']['0.005'], 2)} / {fmt(entry['nearThreshold']['within']['0.01'], 2)} "
                     f"| {entry['scoreDifference']['mean']:+.4f} / {entry['scoreDifference']['sd']:.4f} | {fmt(rates[0])} / {fmt(rates[1])}{flag} | {fmt(result['positives'][label]['all'] / scored_on(label))} |")

    if masked:
        lines += ["", f"### Each label's sample, and the held-out half against a bar of {primary}", "",
                  "ᵖ proxy, ᵇ the classes are broader than the label, ⁿ narrower. Sample counts are verified positives / verified negatives. `fired` is held-out images at or above the floor, of them `hits` positive; "
                  "the precision is hits / fired, with its bootstrap interval and, apart, its Wilson lower bound; FPR is the share of verified negatives that fire.", "",
                  "| Label | classes | sample, calibration | sample, held-out | ONNX fired / hits | ONNX precision [bootstrap] (Wilson low) | ONNX recall | ONNX FPR "
                  "| Core ML fired / hits | Core ML precision [bootstrap] (Wilson low) | Core ML recall | Core ML FPR |", "|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for label in labels:
            counts = result["positives"][label]
            cells = [label + mark(label), "; ".join(info[label]["categories"]),
                     f"{counts['calibration']} / {counts['scored']['calibration'] - counts['calibration']}", f"{counts['heldOut']} / {counts['scored']['heldOut'] - counts['heldOut']}"]
            for artifact in SHIPPED:
                held = per[artifact][label]["targets"][primary]["heldOut"]
                low = held["precisionWilson95"]
                cells += [f"{held['fired']} / {held['hits']}", f"{fmt(held['precision'], 2)} {fmt_interval(held['precisionCi95'], 2)} ({fmt(low[0] if low else None, 2)})",
                          fmt(held["recall"], 2), fmt(held["falsePositiveRate"], 3)]
            lines.append("| " + " | ".join(cells) + " |")

    lines += ["", "### The full selection rule, held-out", "",
              "| margin | pair | sets identical | identical where either labelled | mean Jaccard where either labelled |", "|---|---|---|---|---|"]
    for run in result["selection"]:
        for pair, entry in run["agreement"].items():
            lines.append(f"| {run['margin']} | {pair} | {fmt(entry['identical'])} | {fmt(entry['identicalWhereEitherLabelled'])} | {fmt(entry['meanJaccardWhereEitherLabelled'])} |")
    lines += ["", "| margin | artifact | rule P / R (micro) | independent floors P / R (micro) | images with a label | labels per image |", "|---|---|---|---|---|---|"]
    for run in result["selection"]:
        for artifact, entry in run["artifacts"].items():
            rule, alone = entry["micro"]["rule"], entry["micro"]["independent"]
            lines.append(f"| {run['margin']} | {artifact} | {fmt(rule['precision'])} / {fmt(rule['recall'])} | {fmt(alone['precision'])} / {fmt(alone['recall'])} "
                         f"| {fmt(entry['imagesWithALabel'])} | {fmt(entry['meanLabelsPerImage'], 2)} |")

    lines += ["", "### Which tower moves a pair away from fp32: pooled Jaccard against fp32, held-out, own floors", "",
              "| artifact | both towers | its vision tower + fp32 text | fp32 vision + its text tower |", "|---|---|---|---|"]
    for artifact, entry in result["towersAgainstFp32"].items():
        lines.append(f"| {artifact} | {fmt(entry['bothTowers']['jaccard'])} | {fmt(entry['visionOnly']['jaccard'])} | {fmt(entry['textOnly']['jaccard'])} |")

    for name, entry in result["variants"].items():
        identity = entry["identity"]
        marked = "⚠ OFF-PIN VARIANT — " if entry["offPin"] else ""
        lines += ["", f"### {marked}{name} against the pinned {entry['against']} run (onnxruntime {identity.get('onnxruntime')}), held-out, target {primary}", "",
                  (f"⚠ Not part of the pinned measurement. This variant departs from the pins in: {'; '.join(entry['departsFrom'])}. "
                   "It embedded the pinned run's pixels and token rows." if entry["offPin"] else "This variant was embedded on the pinned toolchain."), "",
                  f"Pooled Jaccard, each under its own floors: {fmt(entry['pooledOwnFloors']['jaccard'])} ({entry['pooledOwnFloors']['disagreements']} disagreements); "
                  f"both under the pinned run's floors: {fmt(entry['pooledPinnedFloors']['jaccard'])} ({entry['pooledPinnedFloors']['disagreements']}). "
                  f"The variant against fp32, own floors: {fmt(entry['againstFp32OwnFloors']['jaccard'])}; against "
                  + ", ".join(f"{other} {fmt(value['jaccard'])}" for other, value in entry["againstOtherShippedOwnFloors"].items()) + ".", "",
                  "| Label | AP pinned / variant | floor pinned / variant | mean score shift | P / R variant under pinned floors | P / R variant under own floors |", "|---|---|---|---|---|---|"]
        for label in labels:
            row = entry["perLabel"][label]
            lines.append(f"| {label} | {fmt(row['apHeldOut']['pinned'])} / {fmt(row['apHeldOut']['variant'])} | {fmt(row['floor']['pinned'], 4)} / {fmt(row['floor']['variant'], 4)} "
                         f"| {row['meanScoreShift']:+.4f} | {fmt(row['variantUnderPinnedFloors']['precision'], 2)} / {fmt(row['variantUnderPinnedFloors']['recall'], 2)} "
                         f"| {fmt(row['variantUnderOwnFloors']['precision'], 2)} / {fmt(row['variantUnderOwnFloors']['recall'], 2)} |")

    lines += ["", "### Prompt sensitivity: held-out AP by prompt variant", "", "| Label | " + " | ".join(f"{a} {v}" for a in result["sensitivity"]["prompts"] for v in PROMPT_VARIANTS) + " |",
              "|---|" + "---|" * (len(result["sensitivity"]["prompts"]) * len(PROMPT_VARIANTS))]
    for label in labels:
        lines.append(f"| {label} | " + " | ".join(fmt(result["sensitivity"]["prompts"][a][label][v]) for a in result["sensitivity"]["prompts"] for v in PROMPT_VARIANTS) + " |")
    lines.append("| **mean** | " + " | ".join(fmt(mean(result["sensitivity"]["prompts"][a][label][v] for label in labels))
                                                for a in result["sensitivity"]["prompts"] for v in PROMPT_VARIANTS) + " |")

    if not masked:  # COCO's instance annotations have three presence rules; verified labels have one
        lines += ["", "### Ground-truth sensitivity: held-out positives and ONNX AP by rule", "", "| Label | any | noCrowd | notTiny |", "|---|---|---|---|"]
        for label in labels:
            entry = result["sensitivity"]["groundTruth"]["onnx"][label]
            lines.append(f"| {label} | " + " | ".join(f"{entry[rule]['positives']}, {fmt(entry[rule]['ap'])}" for rule in GROUND_TRUTHS) + " |")
    return "\n".join(lines) + "\n"


def run_evaluate(args) -> int:
    run = os.path.abspath(given(args.run, "--run"))
    given(args.annotations, "--annotations")
    truth_kind = checked_truth(args.truth)
    if (truth_kind == "open-images") != bool(args.classes):
        raise Refused("--truth open-images reads the class descriptions from --classes, and only it does")
    mapping_path = args.mapping if args.mapping is not None else (DEFAULT_MAPPING if truth_kind == "coco" else DEFAULT_OPEN_IMAGES_MAPPING)
    if args.per_label is not None:
        checked_integer(args.per_label, "--per-label", 1, MAX_PER_LABEL)
        if truth_kind != "open-images":
            raise Refused("--per-label is the size of an Open Images label's sample; COCO annotates every category on every image")
    if (truth_kind == "open-images") != bool(args.selection):
        raise Refused("--truth open-images reads how the corpus was selected from --selection, the selection.json `select` wrote, and only it does")
    out = os.path.abspath(args.out or run)
    if args.export:
        refuse_inside(out, args.export, "--out")
    if os.path.lexists(out) and not os.path.isdir(out):
        raise Refused(f"--out {out} exists and is not a directory")
    if out != run and os.path.lexists(os.path.join(out, INPUTS_NAME)):
        # Results beside another run's vectors would read as that run's results.
        raise Refused(f"--out {out} is another `embed` run's directory; a result goes into its own --run, or into a directory holding no run")
    targets = checked_targets(args.targets, args.primary_target)
    margins = checked_margins(args.margins)
    variant_names = checked_variants(args.variant)
    prompt = checked_prompt(args.prompt)
    checked_integer(args.bootstrap, "--bootstrap", 1, MAX_BOOTSTRAP)
    # ⚠ Not negative: `numpy.random.default_rng` refuses a negative seed, long after the hash split
    # accepted it. No upper bound: both take an integer of any size.
    checked_integer(args.seed, "--seed", 0)
    installed, pinned = check_toolchain(args.allow_unpinned)
    inputs = read_inputs(run)
    for name in variant_names:
        path = os.path.join(run, name)
        if name not in inputs["artifacts"] or not os.path.isfile(path) or gate.sha256_of(path) != inputs["artifacts"][name]["sha256"]:
            raise Refused(f"{path} is not a vector file `embed` recorded in {run}")
    # ⚠ Here, with the other input checks, and not where the result is assembled: by then the score
    # files are written, and a refusal would leave them beside an older result.
    calibration_verified = verify_calibration(args.source) if args.source else None
    labels = read_label_set(args.labels)
    # ⚠ The COCO mapping is checked against the 80 categories this file holds. The Open Images one
    # is checked against the class descriptions, which are an input: below, once they are verified.
    mapping = load_mapping(mapping_path, labels) if truth_kind == "coco" else None
    corpus = gate.load_corpus_manifest(args.corpus_manifest)
    said = manifest_truth(args.corpus_manifest)
    if said == "none":
        raise Refused(f"{args.corpus_manifest} says truth=none: `evaluate` scores against annotations; "
                      "a corpus with no ground truth can be embedded and surveyed (`survey`), not evaluated")
    if said != truth_kind:
        # ⚠ Never inferred from the manifest: the reader is chosen on the command line, and a
        # manifest that says otherwise is a refusal, not a switch.
        raise Refused(f"{args.corpus_manifest} says truth={said}, and this run was asked for --truth {truth_kind}")
    if gate.sha256_of(args.labels) != inputs["labels"]["sha256"]:
        raise Refused(f"{run} was embedded from another label set than {args.labels}")
    if corpus["id"] != inputs["corpus"]["id"]:
        raise Refused(f"{run} was embedded over corpus {inputs['corpus']['id']}, the manifest is {corpus['id']}")
    # ⚠ The id covers the image lines only. The resize, the pixel digest and the annotations pin are
    # header lines, and the result copies the corpus block `embed` recorded: the whole file must be
    # the one `embed` read, or that block would describe a manifest this run did not use.
    manifest_sha256 = gate.sha256_of(args.corpus_manifest)
    if manifest_sha256 != inputs["corpus"]["manifestSha256"]:
        raise Refused(f"{args.corpus_manifest} (sha256 {manifest_sha256}) is not the manifest {run} was embedded with "
                      f"({inputs['corpus']['manifestSha256']}): the same images under other header lines")
    verify_annotations(args.annotations, manifest_annotations(args.corpus_manifest))
    names = [entry["name"] for entry in corpus["files"]]
    open_images = None
    if truth_kind == "coco":
        annotations = read_annotations(args.annotations)
        if set(names) != set(annotations["images"]):
            raise Refused("the annotations do not describe exactly the corpus manifest's images")
        truths = {rule: ground_truth(annotations, mapping, names, rule) for rule in GROUND_TRUTHS}
        verified = None
    else:
        open_images = manifest_open_images(args.corpus_manifest)
        # ⚠⚠ The corpus holds, for each label, the images taken FOR it and the images taken for
        # the other labels. Only the selection's own record says where the first stop: how many
        # it took a label, and under which mapping. A larger sample, or another mapping, would be
        # filled with images that are in the corpus for some other reason, which is the base-rate
        # error `label_samples` exists to prevent. The manifest pins the record; it is read here.
        selection = selection_parameters(args.selection, open_images["selection"]["sha256"])
        if selection["seed"] != open_images["selection"]["seed"]:
            # The record is what the digest vouches for; a manifest line beside it is not.
            raise Refused(f"{args.corpus_manifest} says selectionSeed={open_images['selection']['seed']}, the selection record it pins says {selection['seed']}")
        if gate.sha256_of(mapping_path) != selection["mapping"]:
            raise Refused(f"{mapping_path} is not the mapping this corpus was selected with (sha256 {selection['mapping']}): "
                          "a label's sample would hold images that were taken for another label")
        if args.per_label is not None and args.per_label > selection["perLabel"]:
            raise Refused(f"--per-label {args.per_label} is more than the {selection['perLabel']} a label this corpus was selected with: "
                          "the rest of the sample would be images taken for other labels. A smaller sample is a subsample, and is admitted")
        sample_size = args.per_label if args.per_label is not None else selection["perLabel"]
        verify_annotations(args.classes, open_images["classes"], "class descriptions")
        classes = read_open_images_classes(args.classes)
        mapping = load_open_images_mapping(mapping_path, labels, classes)
        annotations = read_open_images_labels(args.annotations)
        if {open_images_id(name)[1] for name in names} != set(annotations):
            raise Refused("the annotations do not describe exactly the corpus manifest's images")
        only, looked_at = open_images_truth(annotations, mapping, classes, names)
        # ⚠⚠ From here on `verified` is each label's SAMPLE of the images it was verified on
        # (`label_samples`), in the order the selection took them: the seed of the selection
        # record, which the manifest pins by digest, and not `--seed`, which splits.
        verified = label_samples(only, looked_at, names, selection["seed"], sample_size)
        truths = {"any": only}
    overrides = evaluation_overrides(inputs, gate.sha256_of(args.labels), gate.sha256_of(mapping_path), manifest_sha256, corpus["pixels"], pinned, truth_kind)
    truth = truths["any"]
    mapped = mapped_labels(labels, mapping)
    if len(names) < 2:
        raise Refused("the corpus manifest names one image; a calibration half and a held-out half need at least two")
    cal_names, held_names = split(names, args.seed)
    position = {name: index for index, name in enumerate(names)}
    cal, held = [position[name] for name in cal_names], [position[name] for name in held_names]
    # ⚠⚠ From here on `cal` and `held` are the halves of the CORPUS, and `cal_rows` / `held_rows`
    # what each label is scored over: the same lists for COCO, and for Open Images each label's
    # verified images of that half. Whatever is compared with truth reads the second pair; what
    # needs no truth (which labels an artifact would write, and whether two artifacts agree)
    # reads the first.
    cal_rows, held_rows, all_rows = verified_rows(cal, verified), verified_rows(held, verified), verified_rows(list(range(len(names))), verified)
    refuse_unscorable(truth, mapped, {"calibration": cal_rows, "held-out": held_rows})
    if verified is not None:
        refuse_thin(truth, mapped, {"calibration": cal_rows, "held-out": held_rows})

    # ⚠ Imported here, not at the top: every refusal above needs no package (see `run_embed`).
    import numpy as np

    files = {artifact: vectors_name(artifact, "") for artifact in ARTIFACTS}
    scores, vectors = {}, {}
    for artifact, name in files.items():
        path = os.path.join(run, name)
        if name not in inputs["artifacts"] or not os.path.isfile(path):
            raise Refused(f"{run} has no {name}: run `embed` for {artifact}")
        if gate.sha256_of(path) != inputs["artifacts"][name]["sha256"]:
            raise Refused(f"{path} is not the file `embed` recorded")
        vectors[artifact] = load_vectors(path, labels, len(names))
        scores[artifact] = cosine_scores(vectors[artifact], labels, name)
    variants = {}
    for name in variant_names:
        variants[name] = cosine_scores(load_vectors(os.path.join(run, name), labels, len(names)), labels, name)

    masked = (verified is not None) or None  # `None`, not `False`: what `evaluate_artifact` takes for "no mask"
    rng = np.random.default_rng(args.seed)
    counts = rng.multinomial(len(held), [1.0 / len(held)] * len(held), size=args.bootstrap)
    # ⚠ `tsv` is the name the code below has always used for "the scores every headline figure is
    # computed from". They are the scores under `--prompt`, which is `tsv` unless asked otherwise.
    tsv = {artifact: scores[artifact][prompt] for artifact in ARTIFACTS}
    per_label = {}
    for artifact in ARTIFACTS:
        print(f"{artifact}: thresholds and intervals for {len(mapped)} labels", flush=True)
        per_label[artifact] = evaluate_artifact(tsv[artifact], labels, mapped, truth, cal_rows, held_rows, targets, counts, masked and all_rows, masked and held)

    primary = args.primary_target
    everything = list(range(len(names)))

    # Which tower moves a quantised pair away from fp32: each shipped artifact with ONE of its
    # towers swapped for fp32's, floors calibrated on that hybrid's own calibration half, compared
    # with fp32 on the held-out half. ⚠ A hybrid is a diagnostic; nothing ships one.
    reference_fired = own_floor_firing(tsv["fp32"], labels, mapped, truth, cal_rows, held, primary)
    towers = {}
    for artifact in SHIPPED:
        towers[artifact] = {"bothTowers": pooled_jaccard(own_floor_firing(tsv[artifact], labels, mapped, truth, cal_rows, held, primary), reference_fired)}
        for name, pair in (("visionOnly", (vectors[artifact][0], vectors["fp32"][1])), ("textOnly", (vectors["fp32"][0], vectors[artifact][1]))):
            hybrid = cosine_scores(pair, labels, f"{artifact} {name}")[prompt]
            towers[artifact][name] = pooled_jaccard(own_floor_firing(hybrid, labels, mapped, truth, cal_rows, held, primary), reference_fired)
    agreements = {
        "heldOutOwnFloors": firing_agreement(tsv, per_label, labels, mapped, held, primary),
        "allOwnFloors": firing_agreement(tsv, per_label, labels, mapped, everything, primary),
        "heldOutOnnxFloorsForAll": firing_agreement(tsv, per_label, labels, mapped, held, primary, floors_from="onnx"),
    }
    for target in targets:
        if target != primary:
            agreements[f"heldOutOwnFloorsTarget{target_key(target)}"] = firing_agreement(tsv, per_label, labels, mapped, held, target)
    if masked:
        # The tables above are over every held-out image: whether two artifacts would write the
        # same label needs no truth. This one is the same question over the images each label
        # was verified on, which is where its floor was placed and its precision read.
        agreements["heldOutOwnFloorsVerifiedOnly"] = firing_agreement(tsv, per_label, labels, mapped, held_rows, primary)
    selections, set_rows = [], {}
    for margin in margins:
        print(f"selection rule, margin {margin}", flush=True)
        summary, sets = selection_run(tsv, per_label, labels, mapping, mapped, truth, held, primary, margin, verified)
        selections.append(summary)
        set_rows[str(margin)] = sets

    variant_results = {}
    column = {row["label"]: index for index, row in enumerate(labels)}
    for name, variant_scores in variants.items():
        # Another build of one artifact (another onnxruntime): calibrated on its own, then compared
        # with the pinned run of the same artifact on the held-out half.
        base = inputs["artifacts"][name]["artifact"]
        own = evaluate_artifact(variant_scores[prompt], labels, mapped, truth, cal_rows, held_rows, [primary], counts, masked and all_rows, masked and held)
        key, per = target_key(primary), {}
        for label in mapped:
            base_row = [tsv[base][i, column[label]] for i in held]
            row = [variant_scores[prompt][i, column[label]] for i in held]
            base_floor, floor = per_label[base][label]["targets"][key]["threshold"], own[label]["targets"][key]["threshold"]
            scored = rows_of(held_rows, label)
            held_truth, scored_row = [truth[label][i] for i in scored], [variant_scores[prompt][i, column[label]] for i in scored]
            per[label] = {
                "apHeldOut": {"pinned": per_label[base][label]["ap"]["heldOut"], "variant": own[label]["ap"]["heldOut"]},
                "floor": {"pinned": base_floor, "variant": floor},
                "meanScoreShift": sum(v - b for v, b in zip(row, base_row)) / len(row),
                "ownFloors": agreement(fires(base_row, base_floor), fires(row, floor)),
                "pinnedFloors": agreement(fires(base_row, base_floor), fires(row, base_floor)),
                "variantUnderPinnedFloors": confusion(fires(scored_row, base_floor), held_truth),
                "variantUnderOwnFloors": confusion(fires(scored_row, floor), held_truth),
            }
        def pooled(which):
            both = sum(entry[which]["both"] for entry in per.values())
            either = both + sum(entry[which]["onlyA"] + entry[which]["onlyB"] for entry in per.values())
            return {"both": both, "disagreements": either - both, "jaccard": both / either if either else None}
        own_fired = own_floor_firing(variant_scores[prompt], labels, mapped, truth, cal_rows, held, primary)
        departs = variant_departures(inputs["artifacts"][name], gate.pinned_versions(os.path.join(HERE, PINNED)))
        variant_results[name] = {"against": base, "identity": inputs["artifacts"][name], "pooledOwnFloors": pooled("ownFloors"),
                                 "tag": variant_tag(name), "offPin": bool(departs), "departsFrom": departs,
                                 "againstFp32OwnFloors": pooled_jaccard(own_fired, reference_fired),
                                 "againstOtherShippedOwnFloors": {
                                     other: pooled_jaccard(own_fired, own_floor_firing(tsv[other], labels, mapped, truth, cal_rows, held, primary))
                                     for other in SHIPPED if other != base},
                                 "pooledPinnedFloors": pooled("pinnedFloors"), "perLabel": per}

    # ⚠ Nothing above wrote, and nothing below refuses. The files are still staged and moved into
    # place together, the result last, so a run that dies while writing leaves an earlier result
    # with the score files it describes.
    import shutil
    import tempfile

    created = not os.path.isdir(out)
    os.makedirs(out, exist_ok=True)
    staging = tempfile.mkdtemp(prefix=".label-eval-staging-", dir=out)
    try:
        score_files = {}
        for artifact in ARTIFACTS:
            path = os.path.join(staging, score_file(artifact))
            np.savez_compressed(path, **{variant: scores[artifact][variant].astype(np.float32) for variant in PROMPT_VARIANTS})
            score_files[os.path.basename(path)] = gate.sha256_of(path)
        sets_path = os.path.join(staging, SETS_NAME)
        write_json(sets_path, {"images": held_names, "sets": set_rows})

        result = {
            "format": RESULT_FORMAT,
            "inputs": {
                **{key: inputs[key] for key in ("labels", "corpus", "texts")},
                "artifacts": inputs["artifacts"],
                "mapping": {"file": os.path.basename(mapping_path), "sha256": gate.sha256_of(mapping_path)},
                "annotations": manifest_annotations(args.corpus_manifest),
                # Open Images only: the class descriptions, and where the annotations extract came from.
                **({} if open_images is None else {"classes": open_images["classes"], "selection": open_images["selection"], "annotationsFrom": open_images["annotationsFrom"]}),
                "calibration": {"checkpoint": list(CHECKPOINT_CALIBRATION), "app": list(APP_CALIBRATION),
                                "checkpointVerified": calibration_verified},
            },
            "toolchain": {"python": platform.python_version(), "pinned": pinned, **installed},
            "truth": {"kind": truth_kind, "name": CANONICAL[truth_kind]["name"],
                      **({} if verified is None else {
                          "minPerHalf": MIN_PER_HALF,
                          "perLabel": sample_size,
                          "selectedPerLabel": selection["perLabel"],
                          "sampleSeed": selection["seed"],
                          "scoredOver": f"per label, its sample: the first {sample_size} images on which it is verified present and the first {sample_size} on which it is "
                                        "verified absent, in the selection's order. No other image is in any figure of that label against truth, whether the label was "
                                        "verified on it or not. Agreement between artifacts and firing rates are over every image, as they need no truth"})},
            "overridden": bool(overrides),
            "overrides": overrides,
            # Apart from `overrides`: these departed, the base measurement did not.
            "offPinVariants": [entry["tag"] for entry in variant_results.values() if entry["offPin"]],
            "split": {"seed": args.seed, "rule": "sha256('<seed>:<name>'), first half calibrates", "calibration": len(cal), "heldOut": len(held)},
            "targets": targets,
            "primaryTarget": primary,
            # The prompt variant every figure outside `sensitivity.prompts` was computed under.
            "promptVariant": prompt,
            "minFired": MIN_FIRED,
            "bootstrap": {"resamples": args.bootstrap, "seed": args.seed, "over": "held-out images, the floor held fixed"},
            "scoreUnit": "cosine of L2-normalised image and text vectors, each artifact's own two towers",
            "labels": {
                "mapped": mapped,
                "mapping": {label: mapping[label] for label in mapped},
                "tier": {row["label"]: row["tier"] for row in labels if row["label"] in mapped},
                "parent": {row["label"]: row["parent"] for row in labels if row["label"] in mapped},
                "unmapped": [row["label"] for row in labels if row["label"] not in mapped],
            },
            # ⚠ With a mask these are counts over each label's sample: `scored` is its size, and
            # the positives are the positives in it.
            "positives": {label: ({"all": sum(truth[label]), "calibration": sum(truth[label][i] for i in cal), "heldOut": sum(truth[label][i] for i in held)} if verified is None else
                                  {**{half: sum(1 for i in rows[label] if truth[label][i]) for half, rows in (("all", all_rows), ("calibration", cal_rows), ("heldOut", held_rows))},
                                   "scored": {"all": len(all_rows[label]), "calibration": len(cal_rows[label]), "heldOut": len(held_rows[label])}})
                          for label in mapped},
            "perLabel": per_label,
            "crossThresholds": cross_thresholds(tsv, per_label, labels, mapped, truth, held_rows, targets),
            "agreement": agreements,
            "selection": selections,
            "sensitivity": sensitivity({artifact: scores[artifact] for artifact in ARTIFACTS}, labels, mapped, truths, held_rows, prompt),
            "towersAgainstFp32": towers,
            "variants": variant_results,
            "files": {**score_files, os.path.basename(sets_path): gate.sha256_of(sets_path)},
        }
        result["summary"] = summarise(result)
        text = tables(result)
        with open(os.path.join(staging, TABLES_NAME), "w", encoding="utf-8") as handle:
            handle.write(text)
        write_json(os.path.join(staging, RESULT_NAME), result)
        # ⚠ Invalidate first, the result last (see `run_embed`): an interrupted move leaves no
        # result, never an old result beside new score files.
        if os.path.lexists(os.path.join(out, RESULT_NAME)):
            os.remove(os.path.join(out, RESULT_NAME))
        for name in EVALUATION_OUTPUTS:
            os.replace(os.path.join(staging, name), os.path.join(out, name))
        print()
        print(text)
        print(verdict_line(len(mapped), len(labels), overrides, result["offPinVariants"], prompt, CANONICAL[truth_kind]["name"]))
        print(f"wrote {os.path.join(out, RESULT_NAME)}")
        return 0
    finally:
        shutil.rmtree(staging, ignore_errors=True)
        if created:
            try:
                os.rmdir(out)
            except OSError:
                pass


# ---- survey -------------------------------------------------------------------------------------


def shown(count: int, total: int, smallest: int) -> dict:
    """A count of images as `survey` writes it: `{count, rate}`, both `None` when the count, or
    what is left of `total` beside it, is between 1 and `smallest`, exclusive.

    ⚠ The corpus may be someone's own photographs. "This label fired on three of them" describes
    three photographs, and so does "on all but three"; zero, all, and anything `smallest` away
    from both describe the library.
    """
    if 0 < count < smallest:
        return {"count": None, "rate": None, "suppressed": True, "near": "none"}
    if 0 < total - count < smallest:
        # ⚠ Said apart: "withheld" must not read as "few" where it is "nearly all".
        return {"count": None, "rate": None, "suppressed": True, "near": "all"}
    # ⚠⚠ And a count that is written is written ROUNDED to a multiple of `smallest` (`rounded`),
    # unless it is none or all. Two exact counts subtract to a third: a label first on ten
    # photographs and among the first three on thirteen is second or third on exactly three, and
    # no rule about each figure alone prevents that. Rounded, their difference is anything
    # within `smallest` either way.
    count = count if count in (0, total) else rounded(count, smallest)
    return {"count": count, "rate": count / total, "suppressed": False}


def rounded(count: int, smallest: int) -> int:
    """`count` to the nearest multiple of `smallest`, halves up: what `survey` writes of a count
    of images it does not withhold. With a `smallest` of 1 it is the count."""
    return smallest * ((2 * count + smallest) // (2 * smallest))


def shown_rank(entry: dict) -> float:
    """Where a `shown` count sorts: its rate, and a withheld one by the side it is near. A label on
    all but three images is the most frequent, not the least. One withheld with its whole
    group (`cover_group`) says nothing of its size, and sorts with the few."""
    return {"all": 1.0, "none": 0.0, "cover": 0.0}[entry["near"]] if entry["suppressed"] else entry["rate"]


def hidden_counts_are_ambiguous(hidden: list, smallest: int) -> bool:
    """Whether withheld counts whose SUM a reader can work out still cannot be told apart.

    Each is known to be between 1 and `smallest - 1`. They are pinned down when there is one of
    them, when they are all ones or all the largest value (the sum says so: two counts summing
    to 18 are nine and nine), and their sum is itself a handful when it is under `smallest`.
    """
    count, total = len(hidden), sum(hidden)
    return count >= 2 and total >= smallest and count < total < (smallest - 1) * count


def group_gives_one_back(counts, smallest: int, each_of=None) -> bool:
    """Whether a group of counts whose total a reader can work out gives a withheld one back:
    some are between 1 and `smallest`, and they are not ambiguous (`hidden_counts_are_ambiguous`).

    `each_of` is what every count is a count out of (the corpus). ⚠ A count within `smallest` of
    it is withheld too (`shown`, "nearly all"), and is given back by any total: one such count
    is enough to say yes. It is not folded into the ambiguity test, which is about counts
    known to be small.
    """
    if each_of is not None and any(0 < each_of - count < smallest for count in counts):
        return True
    hidden = [count for count in counts if 0 < count < smallest]
    return bool(hidden) and not hidden_counts_are_ambiguous(hidden, smallest)


def cover_group(entries: list, counts: list, smallest: int) -> None:
    """Withhold a whole group of `shown` counts when its total would pin a withheld one.

    ⚠⚠ For counts with a total anyone can compute: every image has one top label, so the top-1
    counts of all labels sum to the corpus, and a single withheld one is the corpus less the
    rest. Where the withheld counts are pinned by their sum (`hidden_counts_are_ambiguous`),
    EVERY entry of the group is withheld under one marker (`near: cover`), the small ones' own
    included: the group then says its total and nothing else.

    ⚠ All of it, and not "the smallest neighbours until there is room": which neighbours such a
    rule stops at depends on the counts it is hiding, and a reader who knows the rule reads them
    back from where it stopped. Whether the group is withheld at all still says that it was
    pinned; that is one bit about the group, and no count.
    """
    hidden = [counts[place] for place, entry in enumerate(entries) if entry["suppressed"] and entry["near"] == "none"]
    # ⚠ A count withheld as "nearly all" is pinned by the total whatever else is withheld: it is
    # not one of the small ones the ambiguity test is about. One of them covers the group.
    nearly_all = any(entry["suppressed"] and entry["near"] == "all" for entry in entries)
    if not nearly_all and (not hidden or hidden_counts_are_ambiguous(hidden, smallest)):
        return
    for entry in entries:
        entry.update(count=None, rate=None, suppressed=True, near="cover")


def spread(values, smallest: int = 1) -> dict:
    """Mean, standard deviation and the inner quantiles of a row. ⚠ No minimum and no maximum:
    each is one image's score.

    ⚠ And a nearest-rank quantile is an observation, so on a short row the 5 % point IS the
    minimum. A quantile is written only when at least `smallest` images score at or below it and
    at least `smallest` at or above; otherwise it is `None`. With ten images that is none of
    them; the 5 % and 95 % points need some two hundred.
    """
    from fractions import Fraction

    values = list(values)
    centre = sum(values) / len(values)
    ordered = sorted(values)
    quantiles = {}
    for q in (0.05, 0.25, 0.5, 0.75, 0.95):
        rank = max(1, min(len(ordered), math.ceil(Fraction(str(q)) * len(ordered))))  # `nearest_rank`'s, one-based
        quantiles[str(q)] = nearest_rank(ordered, q) if rank >= smallest and len(ordered) - rank + 1 >= smallest else None
    return {"mean": centre, "sd": math.sqrt(sum((value - centre) ** 2 for value in values) / len(values)), "quantiles": quantiles}


def paired_cells(cells: dict, smallest: int) -> dict:
    """How two artifacts' firing compares, as `survey` writes it: the three cells, their total
    and the Jaccard, or none of them.

    ⚠⚠ Together, because they give each other away: `either − both` is the disagreements, and
    `either` less one artifact's firing count, which is written elsewhere, is what only the other
    wrote. So when any of the three cells is a count between 1 and `smallest`, exclusive, none of
    the five is written. ⚠ And nothing stands in for them: no bound and no rounded share. Each
    was tried, and each said something by being there (a bound is only given when the agreement
    is at least `smallest`, which beside two written firing counts can be the whole answer).
    """
    both, only_a, only_b = cells["both"], cells["onlyA"], cells["onlyB"]
    either = both + only_a + only_b
    if any(0 < count < smallest for count in (both, only_a, only_b)):
        return {"both": None, "onlyA": None, "onlyB": None, "either": None, "jaccard": None}
    # Written rounded, as every count is (`shown`), and the Jaccard is of the rounded cells.
    both, only_a, only_b = (rounded(count, smallest) for count in (both, only_a, only_b))
    either = both + only_a + only_b
    return {"both": both, "onlyA": only_a, "onlyB": only_b, "either": either, "jaccard": both / either if either else None}


def top_labels(row, k: int) -> list:
    """The positions of the `k` highest scores of one image, best first; a tie goes to the label
    declared first (a stable sort, as `rank_siblings`)."""
    return sorted(range(len(row)), key=lambda index: -row[index])[:k]


def artifact_identity(entry: dict) -> dict:
    """What makes two vector files the same artifact: the model bytes and, for Core ML, the
    compute units."""
    return {key: entry.get(key) for key in ("artifact", "source", "files", "artifacts", "computeUnits")}


def artifact_runtime(entry: dict) -> dict:
    """What executed an artifact when its vectors were embedded: the package versions `embed`
    recorded and the machine, and for Core ML the OS, whose framework is its runtime.

    ⚠⚠ A floor belongs to the runtime as it does to the bytes: the same ONNX files under another
    onnxruntime score differently enough to move a floor (`models.md` §3.10.6). ⚠ Not the thread
    cap, which is recorded and is not a version of anything.
    """
    toolchain, where = entry.get("toolchain") or {}, entry.get("where") or {}
    runtime = {name: version for name, version in toolchain.items() if name != "pinned"}
    runtime["machine"] = where.get("machine")
    if entry.get("artifact") == "coreml":
        runtime["macOS"] = where.get("macOS")
    return runtime


@gate.parses_input("a floors file")
def read_floors(path: str, inputs: dict) -> dict:
    """The floors of one `evaluate` result, to be applied to another corpus: `{prompt, target,
    floors: {artifact: {label: cosine or None}}, …}`, or `Refused`.

    ⚠ A floor belongs to the artifact, the label set's token rows and the prompt variant it was
    placed under. All three are compared with this run's record; a result of an older format,
    which does not say which prompt it used, is refused.
    """
    with open(path, encoding="utf-8") as handle:
        result = json.load(handle)
    if not isinstance(result, dict) or result.get("format") != RESULT_FORMAT:
        raise Refused(f"{path} is not a '{RESULT_FORMAT}' result: run `evaluate` with this version, which records the prompt variant a floor belongs to")
    prompt, key = checked_prompt(result["promptVariant"]), target_key(result["primaryTarget"])
    theirs = result["inputs"]
    if theirs["labels"]["sha256"] != inputs["labels"]["sha256"]:
        raise Refused(f"{path} was evaluated over another label set than this run")
    if theirs["texts"]["tokenRowsSha256"] != inputs["texts"]["tokenRowsSha256"]:
        raise Refused(f"{path} was evaluated over other token rows than this run: its floors are for other text vectors")
    for artifact in ARTIFACTS:
        name = vectors_name(artifact, "")
        if artifact_identity(theirs["artifacts"][name]) != artifact_identity(inputs["artifacts"].get(name, {})):
            raise Refused(f"{path}: its {artifact} vectors are not from the artifact this run embedded; a floor belongs to the artifact")
        placed, here = artifact_runtime(theirs["artifacts"][name]), artifact_runtime(inputs["artifacts"].get(name, {}))
        if placed != here:
            moved = sorted(key for key in set(placed) | set(here) if placed.get(key) != here.get(key))
            raise Refused(f"{path}: its {artifact} vectors were embedded under another runtime than this run's ("
                          + ", ".join(f"{key} {placed.get(key)} there, {here.get(key)} here" for key in moved) + "); a floor belongs to the runtime that placed it")
    mapped = result["labels"]["mapped"]
    floors = {artifact: {label: result["perLabel"][artifact][label]["targets"][key]["threshold"] for label in mapped} for artifact in ARTIFACTS}
    for table in floors.values():
        for label, floor in table.items():
            if floor is not None and not (isinstance(floor, float) and math.isfinite(floor)):
                raise Refused(f"{path}: the floor of '{label}' is not a number")
    # Named with its directory: every result is a `label-eval-result.json`.
    return {"file": os.path.join(os.path.basename(os.path.dirname(os.path.abspath(path))), os.path.basename(path)), "sha256": gate.sha256_of(path), "prompt": prompt, "target": result["primaryTarget"], "labels": mapped, "floors": floors,
            "calibratedOn": {"corpus": theirs["corpus"]["id"], "mapping": theirs["mapping"]["sha256"], "splitSeed": result["split"]["seed"],
                             "overridden": result["overridden"], "overrides": result["overrides"]}}


def survey_overrides(inputs: dict, labels_sha256: str, pinned: bool, floors: list) -> list:
    """Each way a survey is not the pinned toolchain over the committed label set. ⚠ The corpus
    is not judged: a survey has no committed corpus, and its manifest is recorded instead."""
    overrides = []
    if not pinned:
        overrides.append("`survey` ran outside its pinned toolchain")
    if labels_sha256 != CANONICAL_LABELS_SHA256:
        overrides.append("the label set is not the committed candidate set")
    for artifact in ARTIFACTS:
        entry = inputs["artifacts"].get(vectors_name(artifact, ""), {})
        if (entry.get("toolchain") or {}).get("pinned") is not True:
            overrides.append(f"the {artifact} vectors were embedded outside the pinned toolchain")
        if artifact == "coreml" and entry.get("shippedComputeUnits") is not True:
            overrides.append("Core ML did not run on the compute units the app ships")
    for entry in floors:
        if entry["calibratedOn"]["overridden"]:
            overrides.append(f"the floors of {entry['file']} come from an overridden evaluation")
    return overrides


def survey_variant(scores: dict, labels: list, smallest: int) -> dict:
    """One prompt variant, no ground truth: per label, each artifact's score distribution, each
    pair's offset and spread, and how often the label is among an image's top ones."""
    count = len(scores[ARTIFACTS[0]])
    names = [row["label"] for row in labels]
    tops = {artifact: [top_labels(row, TOP_K) for row in scores[artifact]] for artifact in ARTIFACTS}
    per = {}
    for column, label in enumerate(names):
        rows = {artifact: [row[column] for row in scores[artifact]] for artifact in ARTIFACTS}
        entry = {"distribution": {artifact: spread(rows[artifact], smallest) for artifact in ARTIFACTS},
                 "difference": {pair_key(a, b): difference(rows[a], rows[b]) for a, b in PAIRS},
                 "top1": {artifact: shown(sum(1 for top in tops[artifact] if top[0] == column), count, smallest) for artifact in ARTIFACTS},
                 "topK": {artifact: shown(sum(1 for top in tops[artifact] if column in top), count, smallest) for artifact in ARTIFACTS}}
        # The spread of ONNX − Core ML against the spread of the label's own scores: the share of
        # what separates one image from another, on this label, that is the artifact and not the image.
        own = entry["distribution"]["fp32"]["sd"]
        entry["noiseToSignal"] = {pair_key(a, b): (entry["difference"][pair_key(a, b)]["sd"] / own if own else None) for a, b in PAIRS}
        per[label] = entry
    for kind, of in (("top1", lambda top, column: top[0] == column), ("topK", lambda top, column: column in top)):
        for artifact in ARTIFACTS:
            cover_group([per[label][kind][artifact] for label in names], [sum(1 for top in tops[artifact] if of(top, column)) for column in range(len(names))], smallest)
    summary = {}
    for a, b in PAIRS:
        key = pair_key(a, b)
        summary[key] = {
            # ⚠ Whether two artifacts rank alike says nothing of what a photograph shows; these two
            # counts go through the rule all the same, as every count here does.
            "top1Differs": shown(sum(1 for x, y in zip(tops[a], tops[b]) if x[0] != y[0]), count, smallest),
            "topKSetDiffers": shown(sum(1 for x, y in zip(tops[a], tops[b]) if set(x) != set(y)), count, smallest),
            "medianAbsoluteOffset": median(abs(per[label]["difference"][key]["mean"]) for label in names),
            "medianSpread": median(per[label]["difference"][key]["sd"] for label in names),
            "offsetRange": [min(per[label]["difference"][key]["mean"] for label in names), max(per[label]["difference"][key]["mean"] for label in names)],
            "spreadRange": [min(per[label]["difference"][key]["sd"] for label in names), max(per[label]["difference"][key]["sd"] for label in names)],
            "labelsBySpread": sorted(names, key=lambda label: -per[label]["difference"][key]["sd"]),
            "labelsByAbsoluteOffset": sorted(names, key=lambda label: -abs(per[label]["difference"][key]["mean"])),
        }
    return {"perLabel": per, "pairs": summary}


def shown_set_agreement(a: list, b: list, smallest: int) -> dict:
    """`set_agreement` as `survey` writes it: every count and every share of a count through the
    suppression rule."""
    labelled = [(set(x), set(y)) for x, y in zip(a, b) if x or y]
    same = sum(1 for x, y in zip(a, b) if set(x) == set(y))
    same_labelled = sum(1 for x, y in labelled if x == y)
    # ⚠ The four figures are sums of three counts (images labelled by neither, labelled alike,
    # labelled apart), so they go together: with the labelled count written, the share of
    # identical sets returns "labelled alike" by subtraction, and one minus the mean Jaccard
    # counts "labelled apart" where that is a handful. When any of the counts is between 1 and
    # `smallest`, none of the four is written.
    # ⚠ And the count labelled by either is itself part of it: each artifact's own count of
    # labelled images is written beside this, and the two less the union are the images both
    # labelled. So the three counts of that split are in the group too, and when the group is
    # withheld the union goes with it, under the neutral marker.
    by_both = sum(1 for x, y in labelled if x and y)
    only_first, only_second = sum(1 for x, y in labelled if x and not y), sum(1 for x, y in labelled if y and not x)
    counts = (len(labelled), len(a) - len(labelled), same_labelled, len(labelled) - same_labelled, by_both, only_first, only_second)
    whole = not any(0 < count < smallest for count in counts)
    # Each share is of ROUNDED counts (`rounded`): an exact share of a written total is a count.
    alike, either = rounded(same_labelled, smallest), rounded(len(labelled), smallest)
    return {"images": len(a),
            "identical": min(1.0, (alike + len(a) - either) / len(a)) if whole else None,
            "eitherLabelled": shown(len(labelled), len(a), smallest) if whole else {"count": None, "rate": None, "suppressed": True, "near": "cover"},
            "identicalWhereEitherLabelled": min(1.0, alike / either) if whole and either else None,
            # To two places: an exact mean over a written number of images multiplies back.
            "meanJaccardWhereEitherLabelled": round(sum(len(x & y) / len(x | y) for x, y in labelled) / len(labelled), 2) if whole and labelled else None}


def survey_floors(scores: dict, labels: list, entry: dict, smallest: int, margin: float) -> dict:
    """Another corpus's floors applied to this one: what each artifact would write, and whether
    the artifacts agree. ⚠ An instrument: the floors were placed for the corpus they came from."""
    count = len(scores[ARTIFACTS[0]])
    column = {row["label"]: index for index, row in enumerate(labels)}
    floors, floored = entry["floors"], entry["labels"]
    fired = {artifact: {label: fires([row[column[label]] for row in scores[artifact]], floors[artifact][label]) for label in floored} for artifact in ARTIFACTS}
    per = {}
    for label in floored:
        rates = {artifact: shown(sum(fired[artifact][label]), count, smallest) for artifact in ARTIFACTS}
        # ⚠⚠ No agreement of a pair of artifacts PER LABEL: the pooled cells below are the sums of
        # the labels' cells, so each label's written cells, taken from the pool, would leave the
        # cells of a label whose own were withheld. Agreement is written pooled, once a pair.
        per[label] = {"floor": {artifact: floors[artifact][label] for artifact in ARTIFACTS}, "firing": rates}
    pooled = {}
    for a, b in PAIRS:
        cells = agreement([value for label in floored for value in fired[a][label]], [value for label in floored for value in fired[b][label]])
        # The cells are counts of (image, label) pairs: see `paired_cells`. ⚠ And `both + onlyA`
        # is the sum of one artifact's firing counts over the labels, each of which is written
        # or withheld above: where that sum would pin a withheld one down, nothing pooled is
        # written for the pair.
        paired = paired_cells(cells, smallest)
        apart = [agreement(fired[a][label], fired[b][label]) for label in floored]
        # ⚠⚠ A pooled cell is the sum of the labels' cells, and each label's cells are boxed in by
        # its two firing counts, which are written: a label firing on every image must agree
        # wherever the other artifact fires at all. So a pooled cell, less what the other labels
        # are forced to contribute, can be one label's handful. The pool is written only when
        # EVERY label's own cells could be (each zero, or `smallest` and over), and when the
        # total would not pin a label's own firing count; otherwise nothing pooled is written.
        if (any(group_gives_one_back([sum(fired[artifact][label]) for label in floored], smallest, count) for artifact in (a, b))
                or any(0 < one[name] < smallest for one in apart for name in ("both", "onlyA", "onlyB"))):
            paired = paired_cells({"both": 1, "onlyA": 0, "onlyB": 0}, 2)   # the withheld form
        pooled[pair_key(a, b)] = paired
    # The full rule, with the floored labels as the vocabulary (`evaluation_vocabulary`).
    stand_in = {row["label"]: {"kind": "one-to-one" if row["label"] in floored else "none"} for row in labels}
    sets, rule = {}, {}
    for artifact in ARTIFACTS:
        vocabulary = evaluation_vocabulary(labels, stand_in, floors[artifact], margin)
        terms = [term["label"] for term in vocabulary]
        sets[artifact] = [sorted(select_labels([(term, row[column[term]]) for term in terms], vocabulary)) for row in scores[artifact]]
        # ⚠ No mean number of labels per image: it is the sum of every label's count over the
        # total, so beside the counts that are written it returns the ones that are withheld.
        rule[artifact] = {"imagesWithALabel": shown(sum(1 for chosen in sets[artifact] if chosen), count, smallest),
                          "perLabel": {label: shown(sum(1 for chosen in sets[artifact] if label in chosen), count, smallest) for label in floored}}
    flags = {artifact: sorted(label for label in floored if sum(fired[artifact][label]) / count >= FLAG_FIRING_RATE) for artifact in ARTIFACTS}
    return {"file": entry["file"], "sha256": entry["sha256"], "prompt": entry["prompt"], "target": entry["target"], "calibratedOn": entry["calibratedOn"],
            "labelsWithAFloor": {artifact: sum(1 for label in floored if floors[artifact][label] is not None) for artifact in ARTIFACTS},
            "perLabel": per, "pooled": pooled, "firingFlags": flags,
            "selection": {"margin": margin, "artifacts": rule, "agreement": {pair_key(a, b): shown_set_agreement(sets[a], sets[b], smallest) for a, b in PAIRS}}}


def survey_tables(result: dict, rows: int = 15) -> str:
    """The Markdown tables of a survey. ⚠ Aggregates only, as the result is."""
    smallest = result["smallestCount"]
    said = lambda entry: {"all": f"all but < {smallest} images", "none": f"< {smallest} images", "cover": "withheld"}[entry["near"]] if entry["suppressed"] else f"{entry['rate']:.3f}"
    lines = [f"Corpus {result['inputs']['corpus']['id'][:12]}…: {result['inputs']['corpus']['count']} images, no ground truth. "
             f"{len(result['labels'])} labels. A count of images between 1 and {smallest - 1} is not written.", ""]
    for variant, block in result["variants"].items():
        lines += [f"## Prompt variant `{variant}`", "", "| Pair | top-1 label differs | top-3 set differs | median abs offset | offset range | median spread | spread range |", "|---|---|---|---|---|---|---|"]
        for pair, entry in block["pairs"].items():
            lines.append(f"| {pair} | {said(entry['top1Differs'])} | {said(entry['topKSetDiffers'])} | {entry['medianAbsoluteOffset']:.4f} | {entry['offsetRange'][0]:+.4f} … {entry['offsetRange'][1]:+.4f} "
                         f"| {entry['medianSpread']:.4f} | {entry['spreadRange'][0]:.4f} … {entry['spreadRange'][1]:.4f} |")
        key, per = pair_key("onnx", "coreml"), block["perLabel"]
        ordered = block["pairs"][key]["labelsBySpread"]
        for title, chosen in ((f"The {rows} labels with the largest ONNX − Core ML spread", ordered[:rows]), (f"The {rows} with the smallest", ordered[-rows:]),
                              (f"The {rows} with the largest absolute ONNX − Core ML offset", block["pairs"][key]["labelsByAbsoluteOffset"][:rows])):
            lines += ["", f"### {title} (`{variant}`)", "", "| Label | ONNX − Core ML mean / sd | ONNX − fp32 mean / sd | Core ML − fp32 mean / sd | fp32 median [5 %, 95 %] | spread ÷ the label's own sd | in the top 3: ONNX / Core ML |", "|---|---|---|---|---|---|---|"]
            for label in chosen:
                entry = per[label]
                cells = [f"{entry['difference'][pair_key(a, b)]['mean']:+.4f} / {entry['difference'][pair_key(a, b)]['sd']:.4f}" for a, b in PAIRS]
                quant = entry["distribution"]["fp32"]["quantiles"]
                lines.append(f"| {label} | {cells[0]} | {cells[1]} | {cells[2]} | {fmt(quant['0.5'])} [{fmt(quant['0.05'])}, {fmt(quant['0.95'])}] | {fmt(entry['noiseToSignal'][key], 2)} "
                             f"| {said(entry['topK']['onnx'])} / {said(entry['topK']['coreml'])} |")
        common = sorted(per, key=lambda label: -shown_rank(per[label]["topK"]["onnx"]))[:rows]
        lines += ["", f"### The labels most often in an image's top {TOP_K} (`{variant}`)", "", "| Label | fp32 | ONNX | Core ML |", "|---|---|---|---|"]
        lines += [f"| {label} | " + " | ".join(said(per[label]["topK"][artifact]) for artifact in ARTIFACTS) + " |" for label in common]
        lines.append("")
    for block in result["floors"]:
        lines += [f"## Floors of {block['file']} (prompt `{block['prompt']}`, target {target_key(block['target'])}) applied to this corpus", "",
                  f"⚠ An instrument: the floors were placed on corpus {block['calibratedOn']['corpus'][:12]}…, not on this one. Labels with a floor: {block['labelsWithAFloor']}.", "",
                  f"A pair's cells are withheld together when one of them, or one of any single label's, is a count between 1 and {smallest - 1}.", "",
                  "| Pair | both | only first | only second | Jaccard |", "|---|---|---|---|---|"]
        for pair, entry in block["pooled"].items():
            jaccard = "withheld" if entry["either"] is None else fmt(entry["jaccard"])
            lines.append(f"| {pair} | " + " | ".join("withheld" if entry[name] is None else str(entry[name]) for name in ("both", "onlyA", "onlyB")) + f" | {jaccard} |")
        lines += ["", "| Pair | label sets identical | identical where either wrote a label | mean set Jaccard where either wrote a label |", "|---|---|---|---|"]
        for pair, entry in block["selection"]["agreement"].items():
            lines.append(f"| {pair} | {fmt(entry['identical'])} | {fmt(entry['identicalWhereEitherLabelled'])} | {fmt(entry['meanJaccardWhereEitherLabelled'])} |")
        lines += ["", f"Firing on ≥ {FLAG_FIRING_RATE:.0%} of the images: {json.dumps(block['firingFlags'], ensure_ascii=False)}", "",
                  "| Label | floor ONNX / Core ML | firing rate fp32 / ONNX / Core ML | under the full rule ONNX / Core ML |", "|---|---|---|---|"]
        for label, entry in block["perLabel"].items():
            flag = " ⚠" if any(shown_rank(entry["firing"][artifact]) >= FLAG_FIRING_RATE for artifact in SHIPPED) else ""
            rule = block["selection"]["artifacts"]
            lines.append(f"| {label} | {fmt(entry['floor']['onnx'], 4)} / {fmt(entry['floor']['coreml'], 4)} | " + " / ".join(said(entry["firing"][artifact]) for artifact in ARTIFACTS) + flag
                         + f" | {said(rule['onnx']['perLabel'][label])} / {said(rule['coreml']['perLabel'][label])} |")
        lines.append("")
    return "\n".join(lines) + "\n"


def survey_line(labels: int, images: int, floors: list, overrides: list) -> str:
    """The last line of a survey. ⚠ Never "scored": there is no ground truth in one."""
    line = f"label survey: MEASURED — {labels} labels over {images} images, no ground truth"
    if floors:
        line += "; floors applied from " + ", ".join(f"{entry['file']} (prompt {entry['prompt']})" for entry in floors) + " ⚠ placed on another corpus: an instrument"
    if overrides:
        line = line.replace("label survey: MEASURED", "label survey: OVERRIDDEN MEASURED", 1) + " ⚠ OVERRIDDEN: " + "; ".join(overrides)
    return line


def run_survey(args) -> int:
    """Every label of the set over a corpus with no ground truth: score distributions, the
    artifacts' offsets and spreads, and, with `--floors`, what another corpus's floors would write.

    ⚠⚠ Apart from `evaluate`, and it weakens none of its checks: `evaluate` still refuses a
    manifest without annotations. ⚠⚠ What this writes is aggregates over the corpus, and no count
    of fewer than `--smallest-count` images: no file name, no per-image score, no label set of an
    image. The vectors `embed` wrote beside it are per image, and stay where `embed` put them.

    ⚠ **What the rule covers, and what it does not.** A count of 1 to `--smallest-count` less
    one, or that close to its total, is withheld (`shown`). A count that is written is rounded
    to a multiple of `--smallest-count` (`rounded`), so two written figures never subtract to
    an exact third, and every share is of rounded counts (`shown_set_agreement`). A pair's cells
    go together (`paired_cells`) and are written pooled only, never a label. A quantile has
    that many images on each side (`spread`). A group whose total is public is withheld whole
    where the total would pin a withheld count (`cover_group`, `group_gives_one_back`).
    ⚠ This is disclosure control by suppression and rounding, and not a proof: a difference of
    two rounded figures is still known to within `--smallest-count` either way. Treat a result
    as something to quote from, not to publish whole.
    """
    run = os.path.abspath(given(args.run, "--run"))
    out = os.path.abspath(args.out or run)
    if args.export:
        refuse_inside(out, args.export, "--out")
    if os.path.lexists(out) and not os.path.isdir(out):
        raise Refused(f"--out {out} exists and is not a directory")
    if out != run and os.path.lexists(os.path.join(out, INPUTS_NAME)):
        raise Refused(f"--out {out} is another `embed` run's directory; a result goes into its own --run, or into a directory holding no run")
    smallest = checked_integer(args.smallest_count, "--smallest-count", 1, 1_000_000)
    floor_paths = list(args.floors or [])
    if len({os.path.realpath(path) for path in floor_paths}) != len(floor_paths):
        raise Refused(f"--floors names a file twice: {floor_paths}")
    installed, pinned = check_toolchain(args.allow_unpinned)
    inputs = read_inputs(run)
    labels = read_label_set(args.labels)
    corpus = gate.load_corpus_manifest(args.corpus_manifest)
    labels_sha256 = gate.sha256_of(args.labels)
    if labels_sha256 != inputs["labels"]["sha256"]:
        raise Refused(f"{run} was embedded from another label set than {args.labels}")
    if corpus["id"] != inputs["corpus"]["id"]:
        raise Refused(f"{run} was embedded over corpus {inputs['corpus']['id']}, the manifest is {corpus['id']}")
    manifest_sha256 = gate.sha256_of(args.corpus_manifest)
    if manifest_sha256 != inputs["corpus"]["manifestSha256"]:
        raise Refused(f"{args.corpus_manifest} (sha256 {manifest_sha256}) is not the manifest {run} was embedded with "
                      f"({inputs['corpus']['manifestSha256']}): the same images under other header lines")
    if manifest_truth(args.corpus_manifest) != "none":
        # ⚠ As `evaluate` refuses a corpus with no truth, this refuses one that has it: every
        # line this writes says "no ground truth", and of an annotated corpus that is false.
        raise Refused(f"{args.corpus_manifest} says truth={manifest_truth(args.corpus_manifest)}: `survey` is for a corpus with no ground truth "
                      "(`manifest --truth none`); an annotated one is scored by `evaluate`")
    if corpus["count"] < 2 * smallest:
        # ⚠ Twice: under that, "fewer than `smallest`" and "all but fewer than `smallest`" are
        # the same counts, and a withheld one could not say which end it is near.
        raise Refused(f"the corpus has {corpus['count']} images, fewer than twice --smallest-count {smallest}: a withheld count could not say whether it is few or nearly all")
    floors = [read_floors(given(path, "--floors"), inputs) for path in floor_paths]
    prompts = [entry["prompt"] for entry in floors]
    if len(set(prompts)) != len(prompts):
        raise Refused(f"--floors gives two results for one prompt variant ({prompts}): one table of floors a variant")
    overrides = survey_overrides(inputs, labels_sha256, pinned, floors)
    paths = {}
    for artifact in ARTIFACTS:
        name = vectors_name(artifact, "")
        paths[artifact] = os.path.join(run, name)
        if name not in inputs["artifacts"] or not os.path.isfile(paths[artifact]):
            raise Refused(f"{run} has no {name}: run `embed` for {artifact}")
        if gate.sha256_of(paths[artifact]) != inputs["artifacts"][name]["sha256"]:
            raise Refused(f"{paths[artifact]} is not the file `embed` recorded")

    # ⚠ Imported here, not at the top: every refusal above needs no package (see `run_embed`).
    import numpy as np  # noqa: F401  (`load_vectors` and `cosine_scores` need it)

    scores = {artifact: cosine_scores(load_vectors(paths[artifact], labels, corpus["count"]), labels, vectors_name(artifact, "")) for artifact in ARTIFACTS}
    result = {
        "format": SURVEY_FORMAT,
        "inputs": {**{key: inputs[key] for key in ("labels", "corpus", "texts")}, "artifacts": inputs["artifacts"], "truth": manifest_truth(args.corpus_manifest)},
        "toolchain": {"python": platform.python_version(), "pinned": pinned, **installed},
        "overridden": bool(overrides),
        "overrides": overrides,
        "scoreUnit": "cosine of L2-normalised image and text vectors, each artifact's own two towers",
        "smallestCount": smallest,
        "topK": TOP_K,
        "labels": [row["label"] for row in labels],
        "variants": {},
        "floors": [],
    }
    for variant in PROMPT_VARIANTS:
        print(f"survey: prompt variant {variant}", flush=True)
        lists = {artifact: scores[artifact][variant].tolist() for artifact in ARTIFACTS}
        result["variants"][variant] = survey_variant(lists, labels, smallest)
        for entry in floors:
            if entry["prompt"] == variant:
                result["floors"].append(survey_floors(lists, labels, entry, smallest, DEFAULT_MARGINS[0]))

    # ⚠ Nothing above wrote, and nothing below refuses (see `run_evaluate`).
    import shutil
    import tempfile

    created = not os.path.isdir(out)
    os.makedirs(out, exist_ok=True)
    staging = tempfile.mkdtemp(prefix=".label-eval-staging-", dir=out)
    try:
        text = survey_tables(result)
        with open(os.path.join(staging, SURVEY_TABLES_NAME), "w", encoding="utf-8") as handle:
            handle.write(text)
        write_json(os.path.join(staging, SURVEY_RESULT_NAME), result)
        if os.path.lexists(os.path.join(out, SURVEY_RESULT_NAME)):
            os.remove(os.path.join(out, SURVEY_RESULT_NAME))
        for name in SURVEY_OUTPUTS:
            os.replace(os.path.join(staging, name), os.path.join(out, name))
        print()
        print(text)
        print(survey_line(len(labels), corpus["count"], floors, overrides))
        print(f"wrote {os.path.join(out, SURVEY_RESULT_NAME)}")
        return 0
    finally:
        shutil.rmtree(staging, ignore_errors=True)
        if created:
            try:
                os.rmdir(out)
            except OSError:
                pass


# ---- select: an Open Images corpus ----------------------------------------------------------------


@gate.parses_input("an image information file")
def read_image_info(paths: list) -> tuple:
    """`(header, {image id: row})` of Open Images' `*-images-with-rotation.csv` files, or `Refused`.

    The rows are kept whole: `select` copies the chosen images' rows out, licence, author and
    title included, so the attribution a CC BY image is owed travels with the corpus.
    """
    import csv

    header, rows = None, {}
    for path in paths:
        with open(path, encoding="utf-8", newline="") as handle:
            reader = csv.reader(handle)
            first = next(reader, None)
            if not first or not {"ImageID", "Subset", "License", "Rotation"} <= set(first) or (header is not None and first != header):
                raise Refused(f"{path} does not open with an image information header (ImageID, Subset, License, Rotation among its columns), or not the earlier file's")
            header = first
            identifier = header.index("ImageID")
            for row in reader:
                if len(row) != len(header) or row[identifier] in rows:
                    raise Refused(f"{path}: a row of {len(row)} fields, or the image {row[identifier] if row else ''} a second time")
                rows[row[identifier]] = row
    return header, rows


def run_select(args) -> int:
    """Choose an Open Images corpus: for every mapped label, up to `--per-label` verified positives
    and as many verified negatives, in a seeded order, from the validation and test sets.

    Needs no package and no image: it reads the official CSV files and writes, to `--out`,

      open-images-labels.csv  every human-verified row of the chosen images, copied line for line;
      image-list.txt          the corpus's file names, `<split>-<image id>.jpg`;
      image-info.csv          the chosen images' information rows: licence, author, title, URL;
      selection.json          what it read (by digest), the seed, and each label's counts.

    ⚠⚠ The pool is the images whose `Rotation` is `0.0`. The copies Open Images serves are not
    rotated, the column says by how much the photographer's orientation differs, and where it is
    empty nobody knows; an image embedded on its side would be scored against truth for the
    upright one.

    ⚠ Refused, before anything is written, when the mapping's pool counts are not what the
    annotations give, when a chosen image is not listed under the licence this expects, or when a
    mapped label does not reach `MIN_PER_HALF` verified positives and negatives in each half of
    the corpus it would write. The halves are `split`'s, under the same seed.
    """
    out = os.path.abspath(given(args.out, "--out"))
    if os.path.lexists(out) and (not os.path.isdir(out) or set(os.listdir(out)) - set(SELECTION_OUTPUTS)):
        raise Refused(f"--out {out} is not a directory, or holds something other than an earlier selection")
    checked_integer(args.seed, "--seed", 0)
    checked_integer(args.per_label, "--per-label", 1, MAX_PER_LABEL)
    for flag, paths in (("--annotations", args.annotations), ("--image-info", args.image_info)):
        for path in paths:
            given(path, flag)
        if len({os.path.realpath(path) for path in paths}) != len(paths):
            raise Refused(f"{flag} names a file twice: {paths}")
    labels = read_label_set(args.labels)
    classes = read_open_images_classes(given(args.classes, "--classes"))
    mapping_path = args.mapping if args.mapping is not None else DEFAULT_OPEN_IMAGES_MAPPING
    mapping = load_open_images_mapping(mapping_path, labels, classes)
    mapped = mapped_labels(labels, mapping)
    if not mapped:
        raise Refused("the mapping maps no label onto an Open Images class: there is nothing to select for")
    header, info = read_image_info(args.image_info)
    column = {name: header.index(name) for name in ("Subset", "License", "Rotation")}
    pool = {image for image, row in info.items() if row[column["Rotation"]] == "0.0"}
    annotations = {}
    for path in args.annotations:
        read_open_images_labels(path, into=annotations, keep=pool)

    positives, negatives = {label: [] for label in mapping}, {label: [] for label in mapping}
    for image in sorted(annotations):
        for label, present in open_images_verdicts(annotations[image], mapping, classes).items():
            (positives if present else negatives)[label].append(image)
    wrong = [f"{label}: the file says {entry['pool']['positives']} / {entry['pool']['negatives']}, the annotations give {len(positives[label])} / {len(negatives[label])}"
             for label, entry in mapping.items() if entry["kind"] != "none" and (entry["pool"]["positives"], entry["pool"]["negatives"]) != (len(positives[label]), len(negatives[label]))]
    if wrong:
        raise Refused(f"{mapping_path}: the pool counts of {len(wrong)} labels are not the annotations': " + "; ".join(wrong))

    chosen = set()
    for label in mapped:
        chosen.update(selection_order(args.seed, label, positives[label])[: args.per_label])
        chosen.update(selection_order(args.seed, label, negatives[label])[: args.per_label])
    name_of = {}
    for image in chosen:
        row = info[image]
        if row[column["Subset"]] not in OPEN_IMAGES_SPLITS:
            raise Refused(f"image {image} is of the subset '{row[column['Subset']]}', not one of {', '.join(OPEN_IMAGES_SPLITS)}")
        if row[column["License"]] != OPEN_IMAGES_LICENCE:
            raise Refused(f"image {image} is listed under {row[column['License']]}, not {OPEN_IMAGES_LICENCE}: the licence this corpus was cleared on does not cover it")
        name_of[image] = f"{row[column['Subset']]}-{image}.jpg"
    names = sorted(name_of.values())
    if len(names) < 2:
        raise Refused("the selection holds fewer than two images")
    truth, verified = open_images_truth(annotations, mapping, classes, names)
    cal_names, held_names = split(names, args.seed)
    position = {name: index for index, name in enumerate(names)}
    halves = {"calibration": verified_rows([position[name] for name in cal_names], verified), "held-out": verified_rows([position[name] for name in held_names], verified)}
    refuse_thin(truth, mapped, halves)
    # …and over what `evaluate` will score each label on: its own sample (`label_samples`).
    samples = label_samples(truth, verified, names, args.seed, args.per_label)
    refuse_thin(truth, mapped, {"calibration": verified_rows([position[name] for name in cal_names], samples), "held-out": verified_rows([position[name] for name in held_names], samples)})

    # ---- nothing below refuses: stage, then move into place, the record last --------------------
    import csv
    import shutil
    import tempfile

    created = not os.path.isdir(out)
    os.makedirs(out, exist_ok=True)
    staging = tempfile.mkdtemp(prefix=".label-eval-staging-", dir=out)
    try:
        with open(os.path.join(staging, SELECTION_LABELS), "w", encoding="utf-8", newline="") as extract:
            extract.write("ImageID,Source,LabelName,Confidence\n")
            for path in args.annotations:
                with open(path, encoding="utf-8", newline="") as handle:
                    next(handle)
                    for line in handle:  # ⚠ copied whole: every row of the extract is a row of the official file
                        if line.split(",", 1)[0] in chosen:
                            extract.write(line)
        with open(os.path.join(staging, SELECTION_LIST), "w", encoding="utf-8") as handle:
            handle.write("".join(name + "\n" for name in names))
        with open(os.path.join(staging, SELECTION_INFO), "w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle, lineterminator="\n")
            writer.writerow(header)
            for image in sorted(chosen):
                writer.writerow(info[image])
        pinned = lambda path: {"name": os.path.basename(path), "bytes": os.path.getsize(path), "sha256": gate.sha256_of(path)}
        count = lambda rows, label, present: sum(1 for index in rows[label] if truth[label][index] == present)
        record = {
            "format": SELECTION_FORMAT,
            "seed": args.seed,
            "perLabel": args.per_label,
            "minPerHalf": MIN_PER_HALF,
            "pool": {"rule": "Rotation == 0.0", "images": len(pool), "imagesWithAVerifiedLabel": len(annotations)},
            "inputs": {"annotations": [pinned(path) for path in args.annotations], "imageInfo": [pinned(path) for path in args.image_info], "classes": pinned(args.classes),
                       "labels": gate.sha256_of(args.labels), "mapping": gate.sha256_of(mapping_path)},
            "images": {"count": len(names), **{name: sum(1 for file in names if file.startswith(name + "-")) for name in OPEN_IMAGES_SPLITS}, "licence": OPEN_IMAGES_LICENCE},
            "split": {"calibration": len(cal_names), "heldOut": len(held_names)},
            "labels": {label: {"pool": mapping[label]["pool"],
                               **{half: {"positives": count(rows, label, True), "negatives": count(rows, label, False)} for half, rows in halves.items()}} for label in mapped},
            "outputs": {name: gate.sha256_of(os.path.join(staging, name)) for name in SELECTION_OUTPUTS[:-1]},
        }
        write_json(os.path.join(staging, SELECTION_RECORD), record)
        if os.path.lexists(os.path.join(out, SELECTION_RECORD)):
            os.remove(os.path.join(out, SELECTION_RECORD))
        for name in SELECTION_OUTPUTS:
            os.replace(os.path.join(staging, name), os.path.join(out, name))
        print(f"selected {len(names)} images for {len(mapped)} labels ({record['images']['validation']} validation, {record['images']['test']} test) from a pool of {len(pool)}")
        print(f"wrote {os.path.join(out, SELECTION_RECORD)}")
        return 0
    finally:
        shutil.rmtree(staging, ignore_errors=True)
        if created:
            try:
                os.rmdir(out)
            except OSError:
                pass


@gate.parses_input("the selection record")
def read_selection(directory: str) -> dict:
    """What `select` wrote to `directory`, checked against its own record, or `Refused`:
    `{record, names, pins}`, the last being the manifest header lines the selection is pinned by."""
    path = os.path.join(directory, SELECTION_RECORD)
    if not os.path.isfile(path):
        raise Refused(f"{directory} has no {SELECTION_RECORD}: not a `select` output, or one whose final write was interrupted")
    with open(path, encoding="utf-8") as handle:
        record = json.load(handle)
    if record["format"] != SELECTION_FORMAT:
        raise Refused(f"{path} is not a '{SELECTION_FORMAT}' record")
    for name, digest in record["outputs"].items():
        if name not in SELECTION_OUTPUTS or not os.path.isfile(os.path.join(directory, name)) or gate.sha256_of(os.path.join(directory, name)) != digest:
            raise Refused(f"{os.path.join(directory, name)} is not the file the selection recorded")
    names = read_image_list(os.path.join(directory, SELECTION_LIST))
    for name in names:
        open_images_id(name)
    labels_path, classes = os.path.join(directory, SELECTION_LABELS), record["inputs"]["classes"]
    sources = record["inputs"]["annotations"]
    for entry in [classes, *sources]:
        if not re.fullmatch(r"[A-Za-z0-9._-]+", entry["name"]) or not re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]):
            raise Refused(f"{path}: '{str(entry['name'])[:60]}' is not a plain file name with a sha256")
    pins = ["truth=open-images",
            f"annotations={SELECTION_LABELS}", f"annotationsBytes={os.path.getsize(labels_path)}", f"annotationsSha256={gate.sha256_of(labels_path)}",
            "annotationsFrom=" + ";".join(f"{entry['name']}:{entry['sha256']}" for entry in sources),
            f"classes={classes['name']}", f"classesBytes={int(classes['bytes'])}", f"classesSha256={classes['sha256']}",
            f"selectionSha256={gate.sha256_of(path)}", f"selectionSeed={int(record['seed'])}",
            f"names={gate.SPLIT_NAMES}"]
    return {"record": record, "names": names, "pins": pins}


# ---- manifest -----------------------------------------------------------------------------------


@gate.parses_input("the image list")
def read_image_list(path: str) -> list:
    """The names of an image list, one a line, sorted, or `Refused`: none empty, none twice, none
    with a `/` (a corpus is one directory)."""
    with open(path, encoding="utf-8") as handle:
        names = [line.rstrip("\n") for line in handle if line.strip()]
    if not names or len(set(names)) != len(names) or any("/" in name or name != name.strip() or "\t" in name for name in names):
        raise Refused(f"{path} is not a list of distinct file names, one a line")
    return sorted(names)


def run_manifest(args) -> int:
    """Write the corpus manifest for a directory of images.

    `--truth coco` (the default) is a directory holding val2017's images and its annotations, and
    is how the committed `label-eval-corpus.txt` was made. The image list is the annotations' own
    (`images[].file_name`), so a stray file in the directory is not part of the corpus.

    `--truth none` is a corpus with no ground truth, for `embed` and `survey`: the images are the
    ones `--image-list` names, for the same reason, and the manifest says `truth=none`, which
    `evaluate` refuses. ⚠ Such a manifest holds the corpus's file names. For a personal library
    it stays beside the library, outside any repository.

    `--truth open-images` is a corpus `select` chose: the images are the ones its `--selection`
    directory lists, and the manifest pins that directory's annotations extract, the class
    descriptions the selection read and the selection record itself.
    """
    if args.truth not in MANIFEST_TRUTHS:
        raise Refused(f"--truth is one of {', '.join(MANIFEST_TRUTHS)}, not {args.truth[:40]!r}")
    if (args.truth == "none") != bool(args.image_list):
        raise Refused("--truth none takes its images from --image-list, and only it does: --truth coco takes them from the annotations")
    if (args.truth == "open-images") != bool(args.selection):
        raise Refused("--truth open-images takes its images and its annotations from --selection, a `select` output directory, and only it does")
    checked_integer(args.side, "--side", 1, MAX_SIDE)
    # The URL becomes a header line of the manifest, and a file is fetched from it + its name.
    if args.source_url is None:
        # ⚠ COCO's address is the default of a COCO manifest only. A corpus with no truth is
        # somebody's files: its manifest says it has no source, and nothing can be fetched by it.
        source_url = {"coco": COCO_SOURCE_URL, "open-images": OPEN_IMAGES_SOURCE_URL, "none": NO_SOURCE}[args.truth]
    elif not re.fullmatch(r"https?://[!-~]+/", args.source_url):
        raise Refused(f"--source-url is an http(s) URL ending in '/', with no space or control character, not {args.source_url[:80]!r}")
    else:
        source_url = args.source_url
    if not os.path.isdir(given(args.corpus, "--corpus")):
        raise Refused(f"--corpus {args.corpus} is not a directory")
    target = os.path.abspath(given(args.out, "--out"))
    if os.path.isdir(target) or not os.path.isdir(os.path.dirname(target)):
        raise Refused(f"--out {target} is a directory, or its directory does not exist")
    check_toolchain(args.allow_unpinned)
    annotations_path = os.path.join(args.corpus, ANNOTATIONS_NAME)
    selection = None
    if args.truth == "none":
        names = read_image_list(args.image_list)
    elif args.truth == "open-images":
        selection = read_selection(args.selection)
        names = selection["names"]
    else:
        if not os.path.isfile(annotations_path):
            raise Refused(f"{args.corpus} has no {ANNOTATIONS_NAME}")
        names = sorted(read_annotations(annotations_path)["images"])
        if not names:
            raise Refused(f"{annotations_path} lists no image")
    files = []
    for name in names:
        path = os.path.join(args.corpus, name)
        if "/" in name or not os.path.isfile(path):
            raise Refused(f"{path} is missing, or is not a file directly in the corpus directory")
        files.append({"name": name, "bytes": os.path.getsize(path), "sha256": gate.sha256_of(path)})
    manifest = {"side": args.side, "count": len(files), "files": files}
    # ⚠ The existing manifest at --out, if there is one, is replaced only by the finished new one.
    import shutil
    import tempfile

    scratch = tempfile.mkdtemp(prefix=".label-eval-manifest-", dir=os.path.dirname(target))
    try:
        pixels = build_pixel_cache(args.corpus, manifest, os.path.join(scratch, PIXELS_NAME))
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    lines = [
        gate.CORPUS_MAGIC,
        f"id={gate.corpus_id((entry['name'], entry['sha256']) for entry in files)}",
        f"count={len(files)}",
        f"source={source_url}",
        f"resize={args.side}x{args.side} bicubic rgb stretch",
        f"pixels={pixels}",
    ] + (["truth=none"] if args.truth == "none" else selection["pins"] if selection else [
        f"annotations={ANNOTATIONS_NAME}",
        f"annotationsBytes={os.path.getsize(annotations_path)}",
        f"annotationsSha256={gate.sha256_of(annotations_path)}",
    ]) + [
        "",
    ] + [f"{entry['sha256']}\t{entry['bytes']}\t{entry['name']}" for entry in files]
    partial = target + ".partial"
    with open(partial, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    os.replace(partial, target)
    print(f"wrote {target}: {len(files)} files")
    return 0


def exit_status(action) -> int:
    """`action()`'s status: 0 measured, 2 refused. An unreadable input is a refusal, as in the gate.

    ⚠ So is a missing package. The toolchain check refuses first under the pins; with
    `--allow-unpinned` it admits an environment that lacks one, and the import that then fails is
    this environment not being able to measure, not a measurement that failed.
    """
    try:
        return action()
    except Refused as refusal:
        print(f"label eval: REFUSED — {refusal}", file=sys.stderr)
        return 2
    except ModuleNotFoundError as error:
        print(f"label eval: REFUSED — a package this step needs is not installed ({error}); install scripts/inference/{PINNED}", file=sys.stderr)
        return 2
    except (OSError, json.JSONDecodeError, UnicodeDecodeError) as error:
        print(f"label eval: REFUSED — an input could not be read: {type(error).__name__}: {error}", file=sys.stderr)
        return 2


def build_parser():
    """The command line. ⚠ argparse checks a value's type and nothing else: every argument's domain
    is checked by the command that reads it, before its first write (`test_label_eval.py`'s
    `EveryArgumentsDomain` has a row for each)."""
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    def common(sub):
        sub.add_argument("--labels", default=DEFAULT_LABELS)
        sub.add_argument("--corpus-manifest", default=DEFAULT_CORPUS_MANIFEST)
        sub.add_argument("--allow-unpinned", action="store_true", help=f"Run outside {PINNED}; the result records it.")

    embed = commands.add_parser("embed", help="Embed the corpus and the label prompts through each artifact.")
    common(embed)
    embed.add_argument("--export", help="A Core ML export directory, export-manifest.json included. Read only.")
    embed.add_argument("--source", help="The LOCAL checkpoint directory the export was made from.")
    embed.add_argument("--onnx-vision", help="vision_model_uint8.onnx, as published.")
    embed.add_argument("--onnx-text", help="text_model_int8.onnx, as published.")
    embed.add_argument("--corpus", help="A directory holding the corpus's original files.")
    embed.add_argument("--out", required=True, help="Where the vectors go. Never inside --export.")
    embed.add_argument("--artifacts", default=",".join(ARTIFACTS))
    embed.add_argument("--compute-units", default=gate.SHIPPED_COMPUTE_UNITS, choices=["cpuAndNeuralEngine", "all", "cpuOnly"])
    embed.add_argument("--reuse-inputs", metavar="RUN", help="Take the resized pixels and token rows from an earlier run directory.")
    embed.add_argument("--tag", default="", help="A suffix for the vector files, e.g. ort1.29.0 for a second runtime.")
    embed.add_argument("--threads", type=int, help=f"Cap the fp32 and ONNX thread pools (1 to {MAX_THREADS}). Recorded. Core ML is not capped.")

    evaluate = commands.add_parser("evaluate", help="Score the embedded vectors against ground truth. Needs no model.")
    common(evaluate)
    evaluate.add_argument("--run", required=True, help="An `embed` output directory.")
    evaluate.add_argument("--annotations", required=True, help=f"COCO's {ANNOTATIONS_NAME}, or with --truth open-images the {SELECTION_LABELS} that `select` wrote.")
    evaluate.add_argument("--mapping", help="Default: the committed mapping of --truth.")
    evaluate.add_argument("--truth", default="coco", help=f"Which annotations --annotations holds: {', '.join(EVALUATION_TRUTHS)}. Never inferred.")
    evaluate.add_argument("--classes", help="With --truth open-images: oidv7-class-descriptions.csv, as the corpus manifest pins it.")
    evaluate.add_argument("--selection", help="With --truth open-images: the selection.json the corpus manifest pins.")
    evaluate.add_argument("--per-label", type=int, help="With --truth open-images: score a label over fewer verified positives and negatives than the corpus was selected with.")
    evaluate.add_argument("--export", help="If given, --out is refused when it is inside it.")
    evaluate.add_argument("--source", help="The checkpoint directory: re-reads the score calibration from its weights.")
    evaluate.add_argument("--out", help="Where the result goes. Default: --run.")
    evaluate.add_argument("--seed", type=int, default=DEFAULT_SEED)
    evaluate.add_argument("--targets", default=",".join(str(target) for target in DEFAULT_TARGETS))
    evaluate.add_argument("--primary-target", type=float, default=DEFAULT_TARGETS[0])
    evaluate.add_argument("--margins", default=",".join(str(margin) for margin in DEFAULT_MARGINS))
    evaluate.add_argument("--bootstrap", type=int, default=1000)
    evaluate.add_argument("--variant", action="append", metavar="FILE", help="A tagged vector file in --run to compare with the pinned run of its artifact.")
    evaluate.add_argument("--prompt", default=DEFAULT_PROMPT, help=f"The prompt variant the floors and every headline figure use: {', '.join(PROMPT_VARIANTS)}.")

    manifest = commands.add_parser("manifest", help="Write the corpus manifest for a val2017 directory.")
    manifest.add_argument("--corpus", required=True)
    manifest.add_argument("--out", required=True)
    manifest.add_argument("--side", type=int, default=256)
    manifest.add_argument("--source-url", help="Where a file is fetched from, by name. Default: COCO's val2017 address for --truth coco, the Open Images bucket for --truth open-images, none for a corpus with no truth.")
    manifest.add_argument("--allow-unpinned", action="store_true")
    manifest.add_argument("--truth", default="coco", help=f"What the corpus's ground truth is: {', '.join(MANIFEST_TRUTHS)}.")
    manifest.add_argument("--image-list", help="With --truth none: a file naming the corpus's images, one a line.")
    manifest.add_argument("--selection", help="With --truth open-images: the directory `select` wrote.")

    select = commands.add_parser("select", help="Choose an Open Images corpus for the mapped labels. Needs no package and no image.")
    select.add_argument("--labels", default=DEFAULT_LABELS)
    select.add_argument("--mapping", help="Default: the committed Open Images mapping.")
    select.add_argument("--classes", required=True, help="oidv7-class-descriptions.csv.")
    select.add_argument("--annotations", action="append", required=True, metavar="CSV", help="A human-verified image-level labels file (validation, test); repeat for each.")
    select.add_argument("--image-info", action="append", required=True, metavar="CSV", help="A *-images-with-rotation.csv file; repeat for each.")
    select.add_argument("--seed", type=int, default=DEFAULT_SEED, help="Orders the images taken for a label, and splits the corpus as `evaluate` will.")
    select.add_argument("--per-label", type=int, default=DEFAULT_PER_LABEL, help="At most this many verified positives, and as many negatives, taken for a label.")
    select.add_argument("--out", required=True, help="Where the selection goes.")

    survey = commands.add_parser("survey", help="Every label over an embedded corpus with no ground truth. Needs no model.")
    common(survey)
    survey.add_argument("--run", required=True, help="An `embed` output directory.")
    survey.add_argument("--floors", action="append", metavar="RESULT", help="A label-eval-result.json whose floors are applied to this corpus; one a prompt variant.")
    survey.add_argument("--export", help="If given, --out is refused when it is inside it.")
    survey.add_argument("--out", help="Where the result goes. Default: --run.")
    survey.add_argument("--smallest-count", type=int, default=DEFAULT_SMALLEST_COUNT, help="No count of images between 1 and this is written.")
    return parser, {"embed": embed, "evaluate": evaluate, "manifest": manifest, "survey": survey, "select": select}


def main(argv=None) -> int:
    args = build_parser()[0].parse_args(argv)
    action = {"embed": run_embed, "evaluate": run_evaluate, "manifest": run_manifest, "survey": run_survey, "select": run_select}[args.command]
    return exit_status(lambda: action(args))


if __name__ == "__main__":
    sys.exit(main())
