#!/usr/bin/env python3
"""Export a SigLIP checkpoint's **vision tower** to ONNX, then quantise it to uint8.

⚠⚠ **The vision tower alone, not the model.** `optimum-cli export onnx` on a SigLIP checkpoint
produces one graph taking *both* `pixel_values` and `input_ids` — a 1.5 GB artifact that cannot
embed an image without also being handed text. `models.md` R3 ships three artifacts precisely so a
device that only indexes never carries the text half, so the image encoder has to be exported as
its own graph.

⚠ **Explicit source directory, never a hub tag** (`models.md` R6): the standing rule is that no
loader may resolve a third-party *name* at runtime, because the catalog's digest pin is what keeps
an eliminated checkpoint from arriving through a config string. Point this at a directory whose
provenance you established.

    python3 -m venv venv && ./venv/bin/pip install torch transformers onnx onnxruntime onnxscript
    # ⚠ `onnxscript` is not optional since torch 2.13: `torch.onnx.export` routes through the
    # dynamo exporter, which imports it. Without it the export dies at the first call.
    ./venv/bin/python scripts/inference/export-vision-tower.py \
        --source /path/to/google--siglip-base-patch16-256-multilingual \
        --out    /path/to/out

Produces `vision_model.onnx` (fp32) and `vision_model_uint8.onnx`. ⚠ uint8 is the only quantisation
that runs on ORT's Java CPU build — `models.md` §3.5 measured int8 refusing to load at all.
"""

import argparse
import json
import os
import sys


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, help="A LOCAL checkpoint directory (R6).")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    import torch
    from transformers import SiglipModel

    os.makedirs(args.out, exist_ok=True)

    # ⚠ The input size comes from the checkpoint's own preprocessor config, never from a constant:
    # `-224` and `-256-multilingual` differ, and guessing produces a graph that accepts the wrong
    # tensor shape silently rather than failing.
    preprocessor = os.path.join(args.source, "preprocessor_config.json")
    with open(preprocessor, encoding="utf-8") as handle:
        config = json.load(handle)
    side = config["size"]["height"]
    print(f"input size {side}² (from preprocessor_config.json)")
    print(f"image_mean={config['image_mean']} image_std={config['image_std']}")

    # ⚠ Loaded as the FULL model and then narrowed to `.vision_model`. `SiglipVisionModel
    # .from_pretrained` on a full checkpoint directory reads the top-level `SiglipConfig`, which
    # nests `vision_config` rather than carrying `hidden_size` itself, and dies on the attribute.
    model = SiglipModel.from_pretrained(args.source).vision_model
    model.eval()

    fp32 = os.path.join(args.out, "vision_model.onnx")
    torch.onnx.export(
        model,
        torch.randn(1, 3, side, side),
        fp32,
        input_names=["pixel_values"],
        # ⚠⚠ `pooler_output` is named because the harness — and any backend — selects the pooled
        # embedding BY NAME. SigLIP pools with a trained attention head, so mean-pooling
        # `last_hidden_state` is a different embedding space, and taking output 0 by position lands
        # on the per-patch tensor.
        output_names=["last_hidden_state", "pooler_output"],
        dynamic_axes={"pixel_values": {0: "batch"}},
        opset_version=14,
    )
    print(f"wrote {fp32} ({os.path.getsize(fp32) / 1e6:.0f} MB)")

    from onnxruntime.quantization import QuantType, quantize_dynamic

    # ⚠⚠ `per_channel=True` is measured, not assumed (`models.md` §3.7.9). Of the four settings
    # tried it is the best: per-tensor loses more (0.935 mean drift against 0.941), and
    # `reduce_range=True` makes it WORSE in both configurations — it is an AVX2 overflow
    # workaround, not an accuracy knob, and it costs a bit of weight precision to buy nothing on a
    # machine that does not need it.
    uint8 = os.path.join(args.out, "vision_model_uint8.onnx")
    quantize_dynamic(fp32, uint8, weight_type=QuantType.QUInt8, per_channel=True)
    print(f"wrote {uint8} ({os.path.getsize(uint8) / 1e6:.0f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
