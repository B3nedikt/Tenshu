#!/usr/bin/env python3
"""Turn a label set into the vector file `EmbeddingHarness` reads.

⚠⚠ **A measurement tool, and it dies with the harness** (`inference-service.md` §13). It exists
because scoring text against images needs the *text* tower and its tokenizer — a second artifact
and a second dependency — and the harness deliberately loads one model. Running this offline is
what keeps that true.

⚠ Not part of any build. It needs a throwaway Python environment:

    python3 -m venv venv && ./venv/bin/pip install onnxruntime tokenizers numpy
    ./venv/bin/python scripts/inference/embed-label-set.py \
        --model  /path/to/text_model.onnx \
        --tokenizer /path/to/tokenizer.json \
        --labels inference-service-proxy/src/jvmTest/resources/label-set-candidate-v1.tsv \
        --out    /tmp/label-vectors.tsv

Output: `label<TAB>v1,v2,…<TAB>parent` per line — the third column is what lets the harness apply
IN-D9's two-tier confidence rule instead of one flat top-K.
"""

import argparse
import re
import string
import sys

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

# ⚠⚠ SigLIP canonicalises text OUTSIDE the tokenizer — `SiglipTokenizer` strips punctuation and
# lowercases before a single piece is looked up. Skipping this does not fail; it silently embeds
# prompts the model never saw in that form, which is the worst shape a measurement bug can take.
def canonicalize(text: str) -> str:
    text = text.replace("_", " ").lower()
    text = text.translate(str.maketrans("", "", string.punctuation))
    return re.sub(r"\s+", " ", text).strip()


def read_label_set(path: str):
    rows = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 4:
                print(f"⚠ skipping malformed row: {line!r}", file=sys.stderr)
                continue
            tier, parent, label, prompt = parts[0], parts[1], parts[2], parts[3]
            rows.append((tier, "" if parent == "-" else parent, label, prompt))
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--tokenizer", required=True)
    parser.add_argument("--labels", required=True)
    parser.add_argument("--out", required=True)
    # SigLIP pads every prompt to a fixed 64 tokens; the text tower has no attention-mask input,
    # so the padding IS the contract rather than something the model is told to ignore.
    parser.add_argument("--length", type=int, default=64)
    parser.add_argument("--pad-id", type=int, default=1)
    args = parser.parse_args()

    rows = read_label_set(args.labels)
    print(f"{len(rows)} labels ({sum(1 for r in rows if r[0] == 'top')} top)")

    tokenizer = Tokenizer.from_file(args.tokenizer)
    session = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    print(f"  inputs={[i.name for i in session.get_inputs()]} "
          f"outputs={[o.name for o in session.get_outputs()]}")

    input_name = session.get_inputs()[0].name
    written = 0
    with open(args.out, "w", encoding="utf-8") as out:
        for tier, parent, label, prompt in rows:
            ids = tokenizer.encode(canonicalize(prompt), add_special_tokens=True).ids
            ids = ids[: args.length] + [args.pad_id] * max(0, args.length - len(ids))
            outputs = session.run(None, {input_name: np.array([ids], dtype=np.int64)})
            named = {o.name: v for o, v in zip(session.get_outputs(), outputs)}
            # ⚠ By name, never by position — the same trap the Kotlin side hit: the first output of
            # a SigLIP text tower is `last_hidden_state`, one vector per token.
            vector = named.get("pooler_output")
            if vector is None:
                print(f"⚠⚠ no pooler_output for {label!r}; outputs={list(named)}", file=sys.stderr)
                return 1
            vector = np.asarray(vector, dtype=np.float32).reshape(-1)
            vector = vector / (np.linalg.norm(vector) or 1.0)
            out.write(f"{label}\t{','.join(f'{v:.6f}' for v in vector)}\t{parent}\n")
            written += 1

    print(f"wrote {written} vectors ({len(vector)}-d) to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
