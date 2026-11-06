#!/usr/bin/env python3
"""Export a SigLIP checkpoint's **text tower** to ONNX, quantise it, and pack its vocabulary.

⚠⚠ **The text tower alone**, for the same reason `export-vision-tower.py` exports the vision tower
alone (`models.md` R3): a device that only *indexes* never carries the text half, and a device that
searches carries both. One graph taking both `pixel_values` and `input_ids` would make that
impossible.

⚠ **Explicit source directory, never a hub tag** (`models.md` R6).

    ./venv/bin/python scripts/inference/export-text-tower.py \
        --source /path/to/google--siglip-base-patch16-256-multilingual \
        --out    /path/to/out

Produces `text_model.onnx` (fp32, with external weights), `text_model_int8.onnx` — the artifact a
device installs — and `tokenizer.bin`, the packed 250 100-piece vocabulary the Kotlin tokenizer
loads (`models.md` §3.9.2).

⚠ Needs `onnxscript` as well as torch/transformers/onnx/onnxruntime — torch 2.13 routes
`torch.onnx.export` through the dynamo exporter.
"""

import argparse
import json
import os
import struct
import sys

# ⚠⚠ SigLIP's text tower is trained at a FIXED sequence length and pads to it — 64 for every
# published checkpoint. It is read from the tokenizer config rather than assumed, for the same
# reason the vision tower reads its input size from `preprocessor_config.json`: guessing produces a
# graph that accepts the wrong tensor shape silently rather than failing.
DEFAULT_SEQUENCE_LENGTH = 64


def pack_vocabulary(source: str, out: str) -> int:
    """`tokenizer.json` (16.4 MB) → `tokenizer.bin` (3.1 MB), the form a device reads.

    ⚠ Deliberately dumb and explicit: a little-endian header, then one record per piece as
    `float32 score | uint8 byte-length | utf-8 bytes`. No compression — the file sits beside a
    100 MB model and the parse has to be fast on a phone, not small.
    """
    with open(os.path.join(source, "tokenizer.json"), encoding="utf-8") as handle:
        tokenizer = json.load(handle)
    model = tokenizer["model"]
    if model.get("type") != "Unigram":
        raise SystemExit(f"expected a Unigram tokenizer, got {model.get('type')!r}")

    pieces = model["vocab"]
    path = os.path.join(out, "tokenizer.bin")
    with open(path, "wb") as handle:
        # magic, version, piece count, unk id, byte-fallback flag
        handle.write(b"TSHU")
        handle.write(struct.pack("<IIB", 1, len(pieces), int(bool(model.get("byte_fallback")))))
        handle.write(struct.pack("<I", int(model.get("unk_id", 0))))
        for piece, score in pieces:
            encoded = piece.encode("utf-8")
            if len(encoded) > 255:
                raise SystemExit(f"piece too long to pack: {piece!r}")
            handle.write(struct.pack("<fB", float(score), len(encoded)))
            handle.write(encoded)
    print(f"wrote {path} ({os.path.getsize(path) / 1e6:.1f} MB, {len(pieces)} pieces)")
    return len(pieces)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, help="A LOCAL checkpoint directory (R6).")
    parser.add_argument("--out", required=True)
    parser.add_argument("--sequence-length", type=int, default=None)
    args = parser.parse_args()

    import torch
    from transformers import SiglipModel

    os.makedirs(args.out, exist_ok=True)

    with open(os.path.join(args.source, "tokenizer_config.json"), encoding="utf-8") as handle:
        config = json.load(handle)
    length = args.sequence_length or int(config.get("model_max_length") or DEFAULT_SEQUENCE_LENGTH)
    print(f"sequence length {length} (from tokenizer_config.json)")

    # ⚠ Narrowed from the full model for the same reason the vision script does it:
    # `SiglipTextModel.from_pretrained` on a full checkpoint reads the top-level `SiglipConfig`,
    # which nests `text_config` rather than carrying `hidden_size`, and dies on the attribute.
    model = SiglipModel.from_pretrained(args.source).text_model
    model.eval()

    fp32 = os.path.join(args.out, "text_model.onnx")
    torch.onnx.export(
        model,
        torch.ones(1, length, dtype=torch.long),
        fp32,
        input_names=["input_ids"],
        # ⚠⚠ `pooler_output` by NAME, as on the vision side: SigLIP pools with a trained head, so
        # taking output 0 by position lands on the per-token tensor and mean-pooling it is a
        # different embedding space that still produces 768 plausible numbers.
        output_names=["last_hidden_state", "pooler_output"],
        dynamic_axes={"input_ids": {0: "batch"}},
        opset_version=14,
    )
    print(f"wrote {fp32} ({os.path.getsize(fp32) / 1e6:.0f} MB + external weights)")

    from onnxruntime.quantization import QuantType, quantize_dynamic

    # ⚠⚠ **int8 here, uint8 on the vision side, and the difference is measured** (`models.md`
    # §3.9.3). uint8 drifts 0.866 mean / 0.823 worst against fp32 on this tower and changes the
    # top-ranked caption for one query in four; int8 drifts 0.987 / 0.983 at the identical size.
    # ⚠ §3.5 recorded int8 refusing to run at all in ORT's Java build — `ConvInteger` unimplemented
    # — which is why the vision tower is uint8. The text tower has no convolutions, so that blocker
    # does not apply, and a JVM test asserts it rather than assuming it.
    int8 = os.path.join(args.out, "text_model_int8.onnx")
    quantize_dynamic(fp32, int8, weight_type=QuantType.QInt8, per_channel=True)
    print(f"wrote {int8} ({os.path.getsize(int8) / 1e6:.0f} MB)")

    pack_vocabulary(args.source, args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
