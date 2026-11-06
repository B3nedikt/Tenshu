#!/usr/bin/env python3
"""Print the `GOLDEN` block of `SiglipTextTokenizerTest` from the checkpoint's **own** tokenizer.

⚠⚠ **The reference is `transformers` loading the checkpoint directory** — `tokenizer_config.json`
included — and called the way the model was trained: `padding="max_length"`. Driving
`tokenizer.json` through the bare `tokenizers` library and padding by hand cannot catch a wrong
pad id (`models.md` §3.9.4): that route never reads `pad_token`, so whatever the caller assumes
comes back confirmed.

⚠ **Explicit source directory, never a hub tag** (`models.md` R6).

    ./venv/bin/python scripts/inference/golden-tokenizer-cases.py \
        --source /path/to/google--siglip-base-patch16-256-multilingual

Needs `transformers` and `sentencepiece`; no torch. Paste the output over the `REFERENCE_PAD_ID`
and `GOLDEN` declarations in
`inference-service-proxy/src/commonTest/kotlin/org/tenshu/inference/proxy/onnx/SiglipTextTokenizerTest.kt`.
"""

import argparse
import sys
import unicodedata

ASTRAL_FALLBACK = "astral_fallback"

# The query groups of `models.md` §3.9, plus every character the fix-up table exists for.
CASES = [
    ("english", ["a sunset over water", "my dog in the snow", "birthday cake with candles",
                 "screenshot of a bank statement"]),
    ("german", ["sonnenuntergang am see", "mein hund im schnee", "geburtstagskuchen mit kerzen",
                "kassenbon vom supermarkt"]),
    ("accented_latin", ["coucher de soleil sur la mer", "café au lait", "el niño en la playa",
                        "crème brûlée"]),
    ("punctuation", ["mom's birthday", "the dog's toy", "sunset — golden hour", "a “quoted” sign"]),
    ("emoji", ["🌅 sunset", "birthday 🎂", "dog 🐕 in snow", "🏔️ mountains"]),
    ("cjk", ["夕日と海", "犬と雪", "誕生日ケーキ", "東京タワー"]),
    ("spacing_case", ["A SUNSET over WATER", "  leading and trailing   ", "double  spaced  words",
                      "MiXeD cAsE tExT"]),
    ("unicode_oddities", ["ﬁle screenshot", "ｆｕｌｌｗｉｄｔｈ ｔｅｘｔ", "no​zero​width",
                          "①②③ numbers"]),
    ("fixup", ["no​zero​width", "a▁b", "x\u007fy", "﻿bom at the start",
               "|---|---|", "", "   ", "ﬁle", "ｆｕｌｌ"]),
    # ⚠⚠ Characters above U+FFFF that are NOT pieces of the vocabulary: the reference falls back to
    # the character's four UTF-8 bytes (`models.md` §3.9.5). Alone, adjacent, mid-sentence, beside
    # an emoji that IS a piece, in CJK, and enough of them to be truncated mid-character.
    # ⚠ `require_byte_fallback` below refuses a case here that stopped reaching the fallback.
    (ASTRAL_FALLBACK, ["🫠", "🫠🫨", "a 🫠 melting", "🎳 bowling night", "x🫠y", "🌅🫠", "🫠🌅",
                       "dog 🐕 and 🫠 in snow", "𠀀", "犬と𠀁雪", "𐍈", "🫠" * 20]),
    # Above U+FFFF and never at the fallback: NFKC folds these into pieces the vocabulary has.
    ("astral_folded", ["𝐝𝐨𝐠 in snow", "\U0002f800"]),
    # ⚠ U+FFFD is what the tokenizer makes of half a surrogate pair, which the reference refuses
    # outright: these rows are what that decision is pinned to.
    ("replacement_character", ["a\ufffdb", "\ufffd sunset"]),
]


def kotlin_literal(text: str) -> str:
    """A Kotlin string literal, with every character a reader could not see spelled as an escape."""
    out = []
    for character in text:
        if character in '"\\$':
            out.append("\\" + character)
        elif unicodedata.category(character) in ("Cc", "Cf") or character in "▁\ufffd":
            out.append(f"\\u{ord(character):04x}")
        elif unicodedata.normalize("NFC", character) != character:
            # ⚠ A character NFC replaces (U+2F800 is one) does not survive an editor or a tool that
            # normalises the file, and reads as its replacement meanwhile: spelled as UTF-16 units.
            units = character.encode("utf-16-be")
            out.extend(f"\\u{units[i] << 8 | units[i + 1]:04x}" for i in range(0, len(units), 2))
        else:
            out.append(character)
    return '"' + "".join(out) + '"'


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    args = parser.parse_args()

    from transformers import AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(args.source)
    length = tokenizer.model_max_length
    pad, eos = tokenizer.pad_token_id, tokenizer.eos_token_id
    print(f"pad_token={tokenizer.pad_token!r} id={pad}; eos id={eos}; length={length}", file=sys.stderr)

    vocabulary = tokenizer.get_vocab()
    byte_ids = {vocabulary[f"<0x{byte:02X}>"] for byte in range(256)}

    lines = []
    for group, texts in CASES:
        for text in texts:
            row = tokenizer(text, padding="max_length", max_length=length, truncation=True)["input_ids"]
            content = list(row)
            while content and content[-1] == pad:
                content.pop()
            # ⚠ The test rebuilds the full row as `ids + padding`, so the EOS is only separable
            # from the padding when the two ids differ. For this checkpoint they do not, and one
            # is put back so every case reads `…content, </s>`.
            if pad == eos:
                content.append(eos)
            if len(row) != length or content[-1] != eos or row != content + [pad] * (length - len(content)):
                print(f"⚠⚠ unexpected row shape for {text!r}: {row}", file=sys.stderr)
                return 1
            # ⚠ A case that names the fallback and does not reach it pins nothing: a later
            # vocabulary that learned the character would turn the group into ordinary rows.
            if group == ASTRAL_FALLBACK and not byte_ids.intersection(content):
                print(f"⚠⚠ {text!r} has no byte piece: it is in this vocabulary", file=sys.stderr)
                return 1
            ids = ", ".join(str(i) for i in content)
            lines.append(f"        Golden({kotlin_literal(text)}, intArrayOf({ids})),   // {group}")

    print(f"        private const val REFERENCE_PAD_ID = {pad}")
    print()
    print("        private val GOLDEN = listOf(")
    print("\n".join(lines))
    print("        )")
    return 0


if __name__ == "__main__":
    sys.exit(main())
