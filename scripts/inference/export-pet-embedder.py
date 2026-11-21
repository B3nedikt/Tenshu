#!/usr/bin/env python3
"""Export Pets' embedder: DINOv2-small zero-shot as ONNX, `[CLS ‖ mean of patch tokens]` (`photos-pets.md` PT-D10).

    python3 scripts/inference/export-pet-embedder.py --out dinov2_small_cls_mean.onnx

Input `input` [1, 3, 224, 224] (ImageNet-normalised RGB), output `embedding` [1, 768]: the CLS token (384)
then the mean of the patch tokens (384). ⭐ The product reads the CLS half only (§10.6: the patch mean pulls
different cats together); the mean stays in the graph so `m2-pet-embedding.py` scores the same artifact.

⚠⚠ The revision is pinned, and the descriptor in `PetEmbedderModel.kt` pins the artifact's SHA-256 and
size. With `requirements-pet-embedder-export.txt`'s toolchain the export is deterministic (two runs, one
digest); another toolchain may not reproduce those bytes — a new file is a new model version, measured
again (M2) before its digest replaces the old one.
⚠ Apache-2.0 weights (`facebook/dinov2-small`); trained on Meta's LVD-142M (§5.3.2). No pet data.
"""

import argparse

REPO = "facebook/dinov2-small"
REVISION = "ed25f3a31f01632728cabb09d1542f84ab7b0056"


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    import hashlib
    import os

    import torch
    from transformers import AutoModel

    model = AutoModel.from_pretrained(REPO, revision=REVISION).eval()

    class ClsMean(torch.nn.Module):
        def __init__(self, inner):
            super().__init__()
            self.inner = inner

        def forward(self, x):
            hidden = self.inner(pixel_values=x).last_hidden_state
            return torch.cat([hidden[:, 0], hidden[:, 1:].mean(1)], 1)

    torch.onnx.export(ClsMean(model), torch.randn(1, 3, 224, 224), args.out, input_names=["input"],
                      output_names=["embedding"], opset_version=17, dynamo=False)
    with open(args.out, "rb") as handle:
        digest = hashlib.sha256(handle.read()).hexdigest()
    print(f"{args.out}: {os.path.getsize(args.out)} bytes, sha256 {digest}")


if __name__ == "__main__":
    main()
