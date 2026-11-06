# Models Tenshu ships

**Status:** the format is built (IN-2); **the list is empty, and deliberately so** — ✅ but the
model is no longer undecided. **R1 + R2 PINNED 2026-08-25 (§3.8): `siglip-base-patch16-256-multilingual`,
SigLIP v1.** ⚠⚠ A pin is not a quality claim and not a decision to ship: ground truth exists for 37
object labels on COCO and, on small samples, for 156 on Open Images (§3.12, 2026-10-12: one label holds 0.90 by a lower bound), and a floor does not travel between the two (§3.10, 2026-10-10; ⚠ it finds the two platforms' labels are **not**
interchangeable under per-artifact thresholds), and **IN-3m — wall time and battery on real hardware — is now the gate**, alongside
⚖️ counsel on R9.
⚠ A change of mind from here costs a re-embed of every photo on every device.
First measurements against SigLIP v1 recorded 2026-08-14 (§3.3) — ⚠ desktop, not a phone. Candidate
comparison + the measurements that gate a choice added 2026-08-12 (§3.1–§3.3). Licence clearance
turned into a one-time checklist 2026-08-13 (§2.1), and running it **eliminated two of the four
candidates**: MetaCLIP (CC-BY-NC-4.0) and MobileCLIP/DFN (Apple ML Research Model TOU —
research-only, derivatives inherit; §3.1.1). Of the two survivors, **`siglip-base-patch16-224` (v1)
is the checkpoint to measure first** — §3.1.2; ⚠ SigLIP **2** is not a free upgrade, its text tower
pins the Gemma 2 tokenizer. OpenCLIP is fully MIT and fails elsewhere — on corpus, not licence
(§3.1.3). ⚠⚠ **All four checks found the deciding fact below the model card**, in a tokenizer, a
deleted file, a loader tag or a training date. **Recommended decisions R1–R9 in §3.4**, including
⚠⚠ R4 — calibrated thresholds have no home in the catalog and need one — and ⭐ R7, which points out
that §3.3's first measurement needs no phone and no labels and can run today.

This file is the review surface for every model Tenshu would ever download onto a user's device.
It exists before any model does, because the questions it asks — *what licence, trained on what,
how large, serving which capability* — are the ones that are hardest to ask retroactively.

⚠⚠ **Nothing ships until a row here is filled in and reviewed.** `MODEL_CATALOG_PUBLIC_KEY_HEX` is
blank, and the verifier refuses every catalog while it is: a client with no pinned key downloads
nothing, loudly. That is the intended state until there is something to sign.

---

## 1. The catalog

Clients discover models **only** through a Tenshu-signed `catalog.json`
(`inference-service.md` IN-D10, inheriting `maps-region-delivery.md` D3):

```json
{
  "schemaVersion": 1,
  "buildDate": "2026-08-12",
  "models": [
    {
      "id": "siglip-base-patch16",
      "version": 3,
      "capabilities": ["semanticImageSearch", "contentTagging"],
      "labelSetVersion": 2,
      "runtime": "onnx",
      "artifactVersion": 1,
      "licence": "Apache-2.0",
      "artifacts": [
        { "role": "vision",     "path": "models/siglip/v3/vision_uint8.onnx", "sha256": "…", "byteSize": 104857600 },
        { "role": "text",       "path": "models/siglip/v3/text_int8.onnx",    "sha256": "…", "byteSize": 293601280 },
        { "role": "vocabulary", "path": "models/siglip/v3/tokenizer.bin",     "sha256": "…", "byteSize":   3250585 }
      ]
    }
  ]
}
```

- ⚠⚠ **The signing key is pinned in source; the hashes are not.** IN-D4 originally pinned each
  model's hash, which would mean an app-store release to ship a model update — *including one that
  fixes a bad model*. IN-D10 amended it to the tiles precedent: pin the key, let the signed catalog
  carry the hashes. Same supply-chain property, plus revocation.
- ⚠ **Signature first, parse second.** A parser is a far larger attack surface than a signature
  check, so no field is read until the bytes are known to be ours.
- ⚠ **Per capability, never one blob.** Capabilities are individually opt-in (IN-D3), so a bundle
  would make a user who wants text-in-images pay for face clustering they refused.
- `artifactVersion` is filtered client-side: one catalog serves every client version at once, and
  an entry a client cannot read is skipped rather than downloaded.
- ⚠⚠ **One entry, N artifacts — and the distinction from the point above is the whole design.** A
  *capability* split exists so a user can decline half of what a model offers. A checkpoint's
  **files** cannot be declined half: the pinned SigLIP needs its vision tower, its text tower and
  its vocabulary, and any two of the three is a device that answers questions *wrongly* rather than
  one that answers fewer of them — a text tower from a different export yields cosines that are
  meaningless **and computable**, so nothing downstream can notice. They are one entry so that a
  half-installed checkpoint is unrepresentable: the store commits all artifacts and writes one index
  record, or it writes nothing (`inference-service.md` §22.2).
- ⚠ **A digest per artifact, not per entry.** Three files are three independent chances for a CDN to
  serve the wrong bytes, and one entry-level hash could only be checked after all ~383 MB arrived.
- ⚠ `byteSize` is per artifact; what a screen shows the user is their **sum**, because that is what
  installing the model actually costs.
- ⚠⚠ **One checkpoint, one `id`, one entry per runtime** (`ios-on-device-inference.md` D13, decided
  2026-10-07). The pinned SigLIP is listed twice: `runtime: "onnx"` (uint8 vision, int8 text) and
  `runtime: "coreml"` (two fp16 `.mlpackage` archives). Same `id`, same `version`, same vocabulary
  file at the same path. A build keeps the entries whose runtime it executes, so it sees one entry
  per id, and everything after the filter may keep resolving a model by id alone.
  - The signer refuses the same `(id, runtime)` twice, and entries that share an id and differ in
    `version` or `labelSetVersion`.
  - ⚠ A build must contribute **one runtime per model id**. That is a wiring rule with no check.
  - ⚠ **One provenance stamp then covers two precisions.** Vectors never leave the device, so
    nothing compares an fp16 vector with a uint8 one. Labels do sync, and are inert today (`inference-service.md` §27.2):
    whether the two artifacts' labels are interchangeable is owed before they stop being inert.
- **`source`** (optional, per entry) names the upstream repo and commit the artifact was exported
  from, with the source files' hashes and the export toolchain. It is in the signed bytes and no
  client reads it. The Core ML entry has one. ⚠ The ONNX entry has none: nothing recorded which
  snapshot those files were exported from, and a revision written there would be a claim.

### 1.1 What is published

| Entry | `runtime` | Artifacts | Download | State |
|---|---|---|---|---|
| `siglip-base-patch16-256-multilingual` v1 | `onnx` | `vision_model_uint8.onnx` 100 273 427 B, `text_model_int8.onnx` 279 628 799 B, `tokenizer.bin` 3 066 150 B | 382 968 376 B | published 2026-09-25 |
| `auraface-v1-int8` v1 | `onnx+mediapipe` | `face_landmarker.task` 3 758 596 B, `auraface_int8.onnx` 66 033 423 B | 69 792 019 B | published 2026-09-25 |
| `siglip-base-patch16-256-multilingual` v1 | `coreml` | `coreml/vision_fp16.mlpackage.tar` 184 842 240 B, `coreml/text_fp16.mlpackage.tar` 555 540 480 B, and the ONNX entry's `tokenizer.bin` | 743 448 870 B | published 2026-10-11 (the three URLs answer with these sizes [M: headers read 2026-10-11]; the signature was verified against the pinned key by the maintainer's own check, not re-run for this line). ⚠ No shipped build is offered it: Photos on iOS contributes `coreml` behind a build flag that is off (`ios-on-device-inference.md` §14.11). ⚠ The text archive does not always honour a `Range` (§14.12) |

The Core ML archives are the export of 2026-10-05 that passed the retrieval gate
(`ios-on-device-inference.md` §9.6): vision sha256 `a30d4dde…10d8de`, text `628f7f32…41907d`,
upstream commit `8952a4ea…84ca9c6`. ⚠ On a phone the download is the smaller part: the prepare step
brought a first install to ~2.58 GB of free space on an iPhone 17e (same doc, §3.4).

## 2. What each row must answer before it ships

| Field | Why it gates a release |
|---|---|
| **Licence** | A model whose licence we cannot state is one we must not ship. Carried in the catalog *and* on the installed copy, so the question is answerable from the device. ⚠ This is a **one-time clearance** per model — §2.1 — not a standing concern. Once a permissive checkpoint is cleared, the whole remaining obligation is a NOTICE artifact and the catalog's `licence` field. |
| **Training corpus** | ⚖️ Open item (`inference-service.md` §7.7), and the one that **stays** open after clearance. Provenance is not a licence: a permissively-licensed model trained on a corpus we cannot describe is still a claim we cannot make. This is the row a LICENSE file cannot close — and ⚠ it fails in **two** directions, a corpus too opaque to describe or one documented enough to be indefensible (§3.1.3). |
| **Size** | The user is told before the download, and it must be the real figure. IN-D3's copy promises "what it downloads". |
| **Capability** | What consent it satisfies. A model serving two capabilities (IN-D8's embeddings) is a feature; a model *bundling* two is not. |
| **Measured cost** | ⚖️ Nothing is measured yet — §7.7 says so plainly. First-index time and battery on a mid-range phone decide whether a candidate is shippable at all. |

### 2.1 Licence clearance: four questions, asked once per model

⚠⚠ **"Open weights" is a marketing term, not a legal one.** Almost none of these models are open
source in the OSI sense; the weights ship under bespoke terms that vary *per checkpoint within a
family*, and **change over time for the same artifact** (§3.1.1). Verified 2026-08-13: of §3.1's four
candidate families, **two are eliminated on licence alone** — so this is not a hypothetical caution.

Three properties of how Tenshu ships a model decide which clauses actually bite. All three are
things most consumers of these models never do:

1. **We redistribute.** §4 serves the bytes from our own CDN behind a Tenshu-signed catalog. Merely
   *pointing* a user at Hugging Face would make the terms their problem; hosting the bytes makes
   them ours. Redistribution is where attribution, notice and non-commercial clauses attach.
2. **We are commercial.** The commerce plane sells storage, so Tenshu is "a commercial product or
   service" under every research-only licence — no matter that the client is open source.
3. **We quantise.** Int8 + ONNX export produces a **derivative**, and restrictive licences make
   derivatives inherit their restriction.

The checklist, then:

| # | Question | Fails if |
|---|---|---|
| 1 | May it be used **commercially**? | Any `-NC` clause, or "Research Purposes" terms |
| 2 | May we **redistribute the weights** from our own CDN? | Redistribution barred, or gated behind acceptance we cannot obtain on the user's behalf |
| 3 | May we ship a **quantised ONNX derivative** under the same terms? | Derivative restrictions inherit, or export is barred |
| 4 | What **attribution artifact** do we owe, and where does it live? | Nothing fails here — Apache-2.0 owes a NOTICE; this is work, not a blocker |

⭐ Questions 1–3 are pass/fail and close permanently once answered for a given checkpoint. Question 4
is the only one that produces ongoing work, and it is two lines: a NOTICE artifact alongside the
model, and the catalog's `licence` field. ⚠ Answer these against **the specific checkpoint we would
ship**, not the family — §3.1.1 is a worked example of why the distinction is load-bearing.

⚠⚠ **Archive the licence text at clearance time, next to the artifact.** "Close permanently" above
means *our* answer is final, not that upstream's terms are: MobileCLIP's permissive weights licence
was deleted from its own repository in 2025 (§3.1.1). Recording the version we cleared against is
what makes that survivable.

## 3. The models

**None shipped.** IN-D8 recommends one image–text embedding model serving classification *and*
search; IN-D8's 2026-08-12 revision rules out the shortcut of using a platform "foundation model"
for it (those are text LLMs — no joint image–text space).

| Model | Version | Capabilities | Licence | Corpus | Size | Measured on |
|---|---|---|---|---|---|---|
| _(none yet)_ | | | | | | |

### 3.1 Candidates, and what is still open on each

⚠⚠ **No number in this table was measured by us**, and nothing has been confirmed against the exact
artifact we would ship. The **licence** column reflects a 2026-08-13 check against the published
terms and is good enough to *eliminate* candidates; it is not good enough to clear one — re-run
§2.1 against the specific checkpoint before it becomes a row in §3.

| Family | Why it is a candidate | Licence (§2.1) | ⚖️ What is still open |
|---|---|---|---|
| **SigLIP** (Google, **v1**) | Strong per parameter; sigmoid loss trains well at small sizes; ONNX exports exist in the wild | ✅ Apache-2.0, and the tokenizer is the checkpoint's own — clears 1–3 cleanly; owes a NOTICE. **§3.1.2** | **Corpus.** WebLI has never been published, so we can state the licence with confidence and still not answer "trained on what?" |
| **SigLIP 2** (Google) | Same, plus multilingual (109 languages) | ⚠⚠ Apache-2.0 tag, but the **text tower needs the Gemma 2 tokenizer** — Gemma Terms of Use, not Apache-2.0. See §3.1.2 | The Gemma question (§3.1.2), the same corpus question, **and** a text tower ~8× larger |
| **OpenCLIP ViT-B/16 or B/32** (LAION) | The baseline everyone benchmarks against; fully open code | ✅ MIT — library, weights **and** tokenizer. Clears 1–4. ⚠⚠ But see §3.1.3: `open_clip` is a **loader**, and most tags it can load are models we eliminated | ⚠⚠ **Corpus.** The LAION-2B checkpoints were trained pre-cleanup and **never retrained**, and the model card disclaims deployed use in its own words. §3.1.3 |
| **MetaCLIP** (Meta) | Data curation is *documented*, which is exactly our weak spot | ❌ **ELIMINATED — CC-BY-NC-4.0.** Fails §2.1 Q1 outright | Nothing. ⚠ Do not spend measurement time here. Its documented-curation property is the one we want and cannot have; if Meta ever re-releases permissively, reopen |
| **MobileCLIP / DFN** (Apple) | Designed for on-device latency; the size class we actually need | ❌ **ELIMINATED in practice — see §3.1.1.** Every current checkpoint is **Apple ML Research Model TOU**: *Research Purposes* only, explicitly excluding "use in any commercial product or service", and **derivatives inherit it**, so our int8 export would too. The one arguable exception rests on a licence file Apple deleted | Only whether Apple would grant terms if asked — ⚖️ a commercial conversation, not a technical check |

⚠ The uncomfortable shape of this table: the candidate built for exactly our constraint (on-device
latency) is unusable, and the candidate that documents its corpus is unusable too. **SigLIP v1 is
the default by elimination** — and per §3.1.2 it is a clean default, not merely the last one
standing: weights, tokenizer and derivative all clear, leaving corpus as the single open item §2
already says stays open.

#### 3.1.1 MobileCLIP, checkpoint by checkpoint (checked 2026-08-13)

⚠⚠ **This family was relicensed mid-life**, which is why "some checkpoints are permissive" is a
trap rather than an opportunity.

Until the **MobileCLIP2 release (commit `e15d36e`, 2025-08-29)**, `apple/ml-mobileclip` carried a
single `LICENSE_weights_data` granting *"a personal, non-exclusive license … to use, reproduce,
modify and redistribute"* — Apple-Sample-Code-shaped, and permissive enough to clear §2.1. That
commit **deleted it**, replacing it with `LICENSE_MODELS` (Apple ML Research Model TOU) and
`LICENSE_DATA` (CC-BY-NC-ND). The README's licence section now reads, with no per-generation
carve-out:

> Code: MIT · **ML models: Apple ML Research Model TOU** · Data: CC-BY-NC-ND

| Checkpoint | Hugging Face tag | What the repo says today | §2.1 |
|---|---|---|---|
| **MobileCLIP-S0 / S1 / S2 / B / B-LT** (v1, 2024) | `apple-ascl` — but the tag links to `LICENSE_weights_data`, which now **404s** | AMLR, via the blanket README statement | ⚠⚠ **Contradictory.** The permissive grant is real but its text was withdrawn |
| **MobileCLIP-S3 / S4 / L-14** | `apple-amlr` | AMLR | ❌ Fails Q1 + Q3 |
| **MobileCLIP2-S0 / S2 / B / S3 / S4 / L-14** | `apple-amlr` | AMLR | ❌ Fails Q1 + Q3 |
| **DFN5B-CLIP-ViT-H-14 / -378** | `apple-amlr` | AMLR | ❌ Fails Q1 + Q3 (and far too large regardless) |

⚠⚠ **Do not ship the v1 checkpoints on the strength of the stale `apple-ascl` tag.** The position
would be "we rely on a grant whose text the licensor removed, contradicted by the licensor's current
blanket statement covering the same artifacts" — survivable in a research context, not something to
build a commercial product's core feature on. ⚖️ If MobileCLIP's latency is worth wanting badly
enough, the move is to **ask Apple for terms**, not to litigate a deleted file.

⚠ Note also that DataCompDR — the distillation dataset behind every MobileCLIP checkpoint — is
**CC-BY-NC-ND**. That does not by itself bind the weights, but it does mean the corpus column here
would be no easier to fill than SigLIP's.

#### 3.1.2 SigLIP: which checkpoint, and the tokenizer nobody looks at (checked 2026-08-13)

⭐ **The weights clear §2.1 on every SigLIP checkpoint.** `google/siglip-*` and `google/siglip2-*`
are Apache-2.0, and `big_vision` — the upstream training repo — states that *"everything in the
big_vision codebase (including models and colabs) is released under the Apache2 license"*. Q1
commercial ✅, Q2 redistribution ✅, Q3 derivative ✅ (int8/ONNX is fine; Apache-2.0 asks only that
modified files be marked), Q4 owes a `LICENSE` + `NOTICE` alongside the artifact.

⚠⚠ **But §3.2 ships two artifacts, and the text encoder is not just weights — it is weights *plus a
tokenizer*.** That is where the checkpoints diverge, and it is invisible from the model card:

| Checkpoint | Tokenizer it needs | Vocab | Licence of the *tokenizer* |
|---|---|---|---|
| `siglip-base-patch16-224` (v1, English) | its own `spiece.model`, shipped in the repo | 32k | ✅ Apache-2.0, same repo |
| `siglip-base-patch16-256-multilingual` (v1, i18n) | its own `spiece.model`, shipped in the repo | ~250k | ✅ Apache-2.0, same repo |
| `siglip2-*` (all sizes) | ⚠⚠ **`GemmaTokenizerFast` from `google/gemma-2-9b-it`** | 256k | ⚠⚠ **Gemma Terms of Use**, not Apache-2.0 |

The SigLIP 2 row is not a model-card claim — it is what the conversion path actually does
(`transformers/models/siglip/convert_siglip_to_hf.py` loads the v2 tokenizer from the Gemma 2
repo, while the v1 branch pulls `spiece.model` from the SigLIP repo itself).

⚖️ **Why the Gemma dependency matters even though Google tags the siglip2 repos Apache-2.0.** The
Gemma ToU carries a Prohibited Use Policy *and a propagation obligation* — a redistributor must pass
the use restrictions downstream as enforceable terms and give recipients a copy of the agreement.
We have no click-through: §4 pushes bytes from a CDN to a device. If the obligation attaches, we
cannot cleanly discharge it; if Google's Apache-2.0 tag on the siglip2 distribution supersedes it
(same rightsholder, deliberately re-published), it never arises. ⚠ That is a **counsel question, not
a docs question** — and note it is *narrower than it looks*: Gemma 4 moved to Apache-2.0 in 2026,
but Gemma **2** is what SigLIP 2 pinned, and that is not retroactive.

⭐⭐ **The size arithmetic points the same way, and does not need counsel.** A 256k vocabulary at
768 dims is ~197 M parameters **in the embedding table alone** — at int8, roughly 197 MB, larger
than the rest of the model put together. The 32k vocabulary is ~25 MB on the same arithmetic.
⚠ These are multiplications, **not measurements** — but an 8× difference in the artifact §3.2 says
we ship separately is not a rounding error, and it lands directly on IN-D3's "tell the user what it
downloads".

**Recommendation: `siglip-base-patch16-224` (v1) as the checkpoint to measure first.** It is the
only candidate where the weights, the tokenizer and the derivative all clear without a legal
question, and it is the smallest text tower of the three.

⚠⚠ **The cost is real and it is a product decision, not a technical one: v1-English will not
understand a German query.** Two mitigations exist and they are not equivalent —

- **Zero-shot classification** (IN-D9's vocabulary) is unaffected: those labels are *ours*, so they
  can be English internally and translated in the UI. This half is free.
- **Free-text search** (Mia's "photos of a cat") is affected: a user typing *"Fotos von einer
  Katze"* hits the text encoder directly. Either the multilingual v1 checkpoint (same ~250k vocab
  size cost, but no Gemma question) or query-side translation.

⚠ Do not resolve this by reaching for SigLIP 2 — it costs the Gemma question *and* the size, and buys
nothing the multilingual v1 checkpoint does not also buy.

#### 3.1.3 OpenCLIP: the licence is clean, and it is not the question (checked 2026-08-13)

⭐ **OpenCLIP clears §2.1 more cleanly than anything else here** — and unusually, all three layers
are MIT, so there is no SigLIP-2-style surprise underneath:

| Layer | Licence |
|---|---|
| `mlfoundations/open_clip` library | ✅ MIT |
| `laion/CLIP-ViT-B-32-laion2B-s34B-b79K` weights | ✅ MIT |
| Tokenizer (OpenAI CLIP BPE, vendored into `open_clip`) | ✅ MIT, 49 408 vocab — ~25 MB int8 on §3.1.2's arithmetic, the same class as SigLIP v1 |

⚠⚠ **Trap 1 — `open_clip` is a loader, not a model, and its `pretrained=` tags include every model
we just eliminated.** `pretrained.py` carries `metaclip_400m`, `metaclip_fullcc`,
`metaclip2_worldwide` (CC-BY-NC-4.0 — §3.1), `dfn2b`, `dfn2b_s39b`, `dfn5b`, `datacompdr`,
`dfndr2b` (Apple ML Research Model TOU — §3.1.1), alongside the `laion2b` and `openai` tags. **One
string in a config is a licence decision**, it looks like a hyperparameter, and nothing in the API
distinguishes a permissive checkpoint from a research-only one.

⭐⭐ **Our architecture already answers this, and it is worth naming as a reason the design is
right.** §1's catalog pins `id` + `sha256` + `licence` per artifact and the client fetches by
digest from a signed row. **Nothing may load by `pretrained=` tag** — the tag is a name resolved at
runtime against a third party, which is exactly the thing IN-D10 refuses. If OpenCLIP is ever
adopted, the export happens once, off-device, and what ships is a digest-pinned artifact whose
licence travelled with it.

⚠⚠ **Trap 2 — MIT permits what the model card disclaims.** The LAION cards say, verbatim:

> Any deployed use case of the model — whether commercial or not — is currently out of scope. This
> model is intended as a research output for research communities.

…and separately place surveillance and facial recognition permanently out of scope regardless of
performance. ⚠ This is **not** a licence restriction — MIT grants what MIT grants, and the authors
cannot withdraw it through a model card. It is the **inverse** of the Apple case in §3.1.1: there,
the licence forbade what we wanted; here the licence permits it and the authors ask us not to. That
makes it a positioning question rather than a legal one, and ⚖️ for a project whose entire pitch is
that it does not do the untrustworthy thing, "we deployed it against the authors' stated intent" is
a sentence worth not having to say.

⚠⚠ **The corpus is the real blocker, and it is worse than SigLIP's.** The LAION-2B checkpoints were
trained in 2022 on the English subset of LAION-5B — before the Stanford Internet Observatory's
December 2023 finding (1 679 CSAM links, later 2 236 after partner matching) and before the
**Re-LAION-5B** re-release of August 2024. ⚠⚠ **The original checkpoints retain their original
weights and were never retrained.** Shipping `laion2B-s34B-b79K` means shipping a model trained on
a corpus known to have contained that material.

⭐ **There is a clean-corpus path if OpenCLIP is otherwise wanted**: LAION's own newer
openMaMMUT/openCLIP runs are trained on **Re-LAION-2B** (and DataComp-1.4B / DFN-1.4B) — new
training on the cleaned dataset rather than the 2022 weights. ⚠ Check the licence per checkpoint
before assuming it inherits MIT, and ⚠ note that a DFN-trained run raises §3.1.1's Apple question
about the *data* even where the weights are permissive.

⭐⭐ **The asymmetry worth carrying into §2's corpus row.** SigLIP's corpus is *undisclosed*: we can
say almost nothing about WebLI. LAION's is *fully disclosed*: we can say a great deal, and part of
it is bad. Transparency cuts both ways, and ⚖️ under an AI-Act-style training-data-summary
expectation the two fail differently — SigLIP is hard to **describe**, LAION is hard to **defend**.
Neither is a licence problem, and neither is fixed by picking a more permissive licence.

⭐ **The transferable lesson, which outlives this family:** a permissive licence is a fact about a
*moment*, not a property of a model. Since §2 already carries `licence` in the catalog and on the
installed copy, it should carry **the licence text we actually cleared against**, archived at
clearance time — otherwise a future upstream relicensing leaves us unable to reconstruct what we
were relying on. Cheap now, impossible later.

### 3.2 The shape I would ship, once one is chosen

- **ONNX, int8-quantised.** Quantisation is what makes this fit a mid-range phone at all — ⚠ and
  it costs accuracy, by an amount that must be measured against *our* vocabulary rather than a
  published benchmark.
- ⭐ **Two artifacts, not one: image encoder and text encoder.** ⚠ §3.4 R3 revises this to **three**
  — a precomputed label-embedding table sits between them and removes the text encoder from the
  classification path entirely. They are needed at different
  times — the image side indexes, the text side only answers queries — and a device that never
  searches (a hub doing the indexing) can skip the text half entirely. The catalog is already
  per-capability (IN-D10); this is the same argument one level down.
  ⚠⚠ **The text artifact is weights *plus a tokenizer*, and the tokenizer can carry its own
  licence** — that is the whole of §3.1.2's finding, and it is the reason a checkpoint has to be
  cleared as a *shipping bundle* rather than as a model card.
- **One artifact per platform, not one per platform family** (IN-D2): a row produced on a Mac and
  a row produced on a phone must be interchangeable. ⚠ Execution providers perturb floats, which
  is harmless here *only because* IN-D5 keeps vectors device-local — see IN-D13.

### 3.4 Recommended decisions (2026-08-13)

✅ **R1 and R2 are PINNED as of 2026-08-25 — see §3.8.** The rest of this section is unchanged and
still carries recommendation strength. It is kept as written because it is the reasoning that
produced the pin, including the precondition R2 set for itself and the cost it accepted.

⚠ These were recommendations, not pins — ⚖️ the licence readings behind them are not counsel's, and
§3.3's measurements were unmade when they were written. But leaving them in a chat log is how this track ended up
with "three model families and no re-index story" (`inference-service.md` §7 preamble), so they are
written down at the strength they actually have.

**R1 — Family: SigLIP v1.** ✅ **PINNED 2026-08-25 (§3.8).** The only candidate where weights, tokenizer *and* derivative clear with
no open legal question (§3.1.2). MetaCLIP and MobileCLIP are out on licence; SigLIP 2 buys
multilingual for the Gemma dependency plus an 8× text tower; OpenCLIP's blocker is corpus, which no
licence choice fixes.

**R2 — Checkpoint: `siglip-base-patch16-256-multilingual`, not `-224`.** ✅ **PINNED 2026-08-25
(§3.8).** ⚠⚠ **This is the one
genuinely committing decision, because it is not deferrable.** The two are separate checkpoints with
separate embedding spaces, so "English now, multilingual later" is not an upgrade — it is a
re-embed of every photo on every device. ⚠ Note the asymmetry with IN-D4's "model upgrades must not
force a re-index": that rule holds for **re-labelling** (`producedBy` covers it, §9.4) and does
*not* hold for a change of embedding space. Re-embedding is device-local work (IN-D5), so it is
cheap in *bytes* and expensive in *hours*. ⚠ The honest cost of R2: a multilingual checkpoint
typically trades some English accuracy for coverage, and its vocabulary size is unverified here —
both fall out of §3.3's measurement 1, which is why that measurement runs **before** this is pinned.

✅ **That measurement ran (§3.7.7), on both checkpoints, and found no delta** — the multilingual one
is marginally *better* on drift. The precondition this paragraph set for itself is met, which is
what made the pin available. ⚠ The vocabulary-size half is **not** answered; it is *deferred with
the text encoder* by R3 — see §3.8.

**R3 — Ship three artifacts, and the text encoder is not one of them at first.** ⭐⭐ IN-D9's label
vocabulary is fixed and known, and IN-D8 already says the label texts are *"encode[d] once,
compare[d]"* — so those embeddings can be computed **off-device, once, and shipped as a table**.
Zero-shot classification then needs no text encoder on the device at all:

| Artifact | Needed for | Rough size |
|---|---|---|
| **Image encoder** | indexing — always | ~93 M params → ~93 MB int8 |
| **Label embedding table** | classification | N × 768 floats — negligible |
| **Text encoder** | free-text search only | ~277 MB int8 at a ~250k vocab |

⚠ Those are published parameter counts × 1 byte for int8, **not measurements** — §3.3 owns the real
figures. ⚠ The `371000000` in §1's example catalog looks like an fp32 figure and should be
re-derived once a real artifact exists.

⭐ **Why this ordering is free.** The expensive artifact serves only the similarity op, which does
not exist yet — MQ-4 is blocked on IN-3b, and IN-4a's label search already works with no model at
all. So R2 fixes the embedding space correctly *now* while shipping roughly a quarter of the bytes,
and the text encoder arrives later as a separate opt-in download (IN-D10 is already per-capability)
with the right language coverage built in.

**R4 — ⚠⚠ Calibrated thresholds need a home, and they do not have one.** IN-D8 says each label needs
its own threshold and §13.3's harness is built to measure the score range — but the §1 catalog
carries `labelSetVersion` and no threshold artifact, and `inference-service.md` notes that nothing
reads the confidence the rows already carry. Thresholds **cannot live in source**: they move when
the checkpoint moves *and* when quantisation moves (§3.3 measurement 1 says exactly this).

⚠⚠ **They belong to the *artifact*, not the model id.** Re-quantising the same checkpoint produces
the same `id` and different thresholds, so keying them per-`(modelId × labelSetVersion)` is not
tight enough. ⭐ R3 then solves this for free — the label embedding table and the threshold table are
the same data, they share a key, and the tightest available key is the artifact they were calibrated
against. ⚠⚠ **Measured 2026-10-10, and the artifact is not tight enough either** (§3.10.6): the same
ONNX bytes under onnxruntime 1.20.0 and 1.29.0 need different floors, so the key is the artifact
*and the runtime that executes it*. So ship them as **one more file inside the model's existing catalog entry**:

```json
"calibration": { "path": "…/v3-calib.bin", "sha256": "…", "labelSetVersion": 2 }
```

⭐⭐ One row, two files, verified together. That makes model/calibration skew **unrepresentable**
rather than merely checked — a model cannot be installed without the calibration it was measured
with. ⚠ The rejected alternatives: inline in `catalog.json` (it is an index every client fetches, and
recalibration would force a whole-catalog re-sign) and a separate catalog row (two artifacts to
version-match, which is the skew back again).

**R5 — Corpus stays open, and nothing above closes it.** SigLIP means shipping a model trained on
WebLI, which Google has never published: licence stated with confidence, provenance unanswerable.
⚖️ If corpus provenance is ever treated as *blocking* rather than open, exactly one candidate is
worth a §2.1 pass — LAION's openMaMMUT/openCLIP runs on **Re-LAION-2B**, the only option whose
corpus is both documented and remediated (§3.1.3). ⚠ Licence per checkpoint, and a DFN-trained
variant reopens §3.1.1's Apple question about the data.

**R6 — Standing rule: never load by `pretrained=` tag.** §3.1.3's trap generalises past OpenCLIP.
The catalog's digest pin is what keeps an eliminated checkpoint from arriving through a config
string, and any loader API that resolves a third-party *name* at runtime defeats IN-D10 by
construction.

**R7 — §3.3 is a ladder, not a gate; climb the first rung before pinning R2.** The two measurements
are usually read as one blocked-on-IN-3b task. They are not — ⭐ **§13's harness already exists for
exactly this**, in `:inference-service-proxy`'s test source set, built to be pointed at real assets:

| Step | Needs | Gives |
|---|---|---|
| **0** | a desktop and an int8 ONNX export | §13.3's two ground-truth-free readings — nearest-neighbour image similarity ("the quickest read on whether the embeddings separate anything") and the score range across all (image, label) pairs |
| **1** | a few hundred hand-labelled photos | measurement 1 proper: per-label accuracy and calibrated thresholds |
| **2** | a phone and IN-3b's Android path | measurement 2: wall time and battery |

⚠⚠ **Do step 0 before pinning R2, and run it on *both* candidate checkpoints** — the English/
multilingual accuracy delta is R2's whole open question, and it is one harness run twice. It is the
cheapest possible check on the most expensive assumption: if int8 SigLIP-base separates nothing,
every other decision in §3.4 is moot.

⭐ **Step 1 pays for itself twice** — the labelled pass that produces measurement 1 *is* the pass that
produces R4's threshold table. One piece of work, two deliverables. ⚠ Honest limitation to record
with the results: the maintainer's own library is the only realistic ground truth here, so the
distribution is one person's. Worth naming, not worth blocking on.

**R8 — The signing key: inherit D11 wholesale, but mint a *second* key.**
⭐ `maps-region-delivery.md` **D11 already answers the hard half**, and `TILES_MANIFEST_PUBLIC_KEY_HEX`
is the shipped precedent: the pipeline splits at the signature, artifact production is automatable,
and catalog signing is *"Maintainer's machine, offline. Never in CI, never on a rented VM, never in
a secret store."* Models inherit that unchanged — ⚠ do not re-derive it, and do not let a model
signing key into CI on the argument that models ship rarely.

⚠ The one open sub-decision is **same key or a new one**, and D11's own principle ("have fewer
secrets in fewer places") argues for reuse. **Recommend a separate key anyway.** It is one more file
on the same offline machine, so custody cost is ≈0, and it buys **independent revocation** — which
is the property IN-D10 chose the pin-the-key design to get in the first place. Sharing one anchor
means revoking after a bad model also takes out offline maps. ⚠ The blast radii differ too: a
tampered tile is parsed by MapLibre, a tampered model is hundreds of megabytes into ONNX Runtime and
then silently steers what the user finds.

**R9 — Corpus: ship with disclosure, and the disclosure goes in the consent copy.** R5 says the
provenance question has no good answer among these candidates. ⭐ **The variable we control is what
we say, not what we can find** — and IN-D3 already puts a per-capability opt-in switch in front of
the user, which is the natural place for it: someone enabling content tagging should be told the
model was trained on a web-scraped corpus its authors have never published. That is a UI string, and
it is the whole deliverable.

⚠ The alternative worth naming so it is visibly rejected rather than overlooked: **ship no model at
all.** It is coherent — the capability is optional — but it forfeits IN-3, IN-4 *and* MQ-4 together,
because with no backend there are no `MediaLabel` rows and even IN-4a's label search has nothing to
search.

⚖️ One factor that may make this lighter than it looks, and is explicitly not a call this doc can
make: we would be an **integrator**, not a GPAI provider, and the training-data-summary obligation
sits with the model's provider. Worth putting to counsel before treating it as heavier than it is.

### 3.5 First measurements — SigLIP v1, 2026-08-14

⚠⚠ **Read the caveats before the numbers.** Measured on a **desktop Mac, ORT CPU EP**, over **11
images that are UI screenshots rather than photographs**, using a **third-party ONNX re-export**
(`Xenova/siglip-base-patch16-224`, base model `google/siglip-base-patch16-224`) and the **vision
tower only**. Every one of those weakens a different conclusion; each is called out below.

| Variant | Size | p50 / image | 5 000-photo pass | |
|---|---|---|---|---|
| fp32 | 355 MB | 84 ms | ~7 min | the reference |
| fp16 | 178 MB | 147 ms | ~12 min | ⚠ **slower than fp32** |
| `quantized` | 95 MB | 218 ms | ~18 min | ⚠ slowest of all |
| **uint8** | **90 MB** | **24 ms** | **~2 min** | ⭐ **3.5× faster than fp32** |
| int8 | 90 MB | — | — | ❌ **does not run**: `ConvInteger` unimplemented in ORT's Java CPU build |

⭐⭐ **Quantisation drift, uint8 vs fp32 — mean cosine 0.981, worst 0.965, and all 11
nearest-neighbour pairings identical.** Retrieval order survives; the *scores* move by up to 0.015.

⚠⚠ **That does not clear a threshold.** IN-D8 says each label needs its own calibrated cut-off, and
a 0.02–0.035 shift is the same order as the gap between labels sitting near one — so a threshold
calibrated on fp32 **cannot be assumed to transfer to uint8**, even though ranking does. Calibrate
on the variant you ship.

⚠ **"Smaller is faster" is false here, twice.** fp16 and `quantized` are both *slower* than fp32 on
a CPU EP — fp16 because CPUs have no native half-precision compute and pay conversion, `quantized`
presumably for its QDQ node layout. Only uint8 pays off. This is exactly why the harness exists.

⚠ **What these numbers are not:**
- **Not the gating number.** IN-3b's question is a **mid-range Android phone**, and a Mac's CPU is a
  floor, not an estimate. Expect materially worse; NNAPI/vendor EPs may claw some back (IN-D13).
- **Not evidence about a photo library.** Eleven UI screenshots say nothing about zero-shot quality
  on holiday photos. ⚠ The drift figure is the one result that survives this caveat — it compares
  two models on *identical* input, so what the input is barely matters.
- **Not the whole model.** The text tower is untouched, so "photos of a cat" end to end — the thing
  IN-4a's fake stands in for — remains unmeasured.
- **Not a cleared artifact.** §3.1's licence pass was against Google's checkpoint; this re-export
  owes its own §2.1 pass before it could ever become a row in §3.

#### 3.5.1 Addendum — the same run over real photographs (2026-08-14)

Repeated over **7 actual camera JPEGs** rather than UI screenshots. Two things changed, and only
one of them is a number worth keeping.

⭐ **Drift is *better* on photographs: mean cosine 0.987, worst 0.974** (screenshots: 0.981 / 0.965).
That direction makes sense — a screenshot is out-of-distribution for a model trained on web photos
(hard edges, flat saturated fills, text), so its activations sit at more extreme values and quantise
worse. ⚠ So §3.5's drift figure was **pessimistic**, and uint8 is closer to fp32 on the content the
feature is actually for than the first run suggested.

⚠⚠ **The timing from this run is worthless, and instructively so.** p50 read 88 ms against the
screenshot run's 24 ms — not because photographs are slower (preprocessing sits outside the timer;
the model always sees 224×224) but because **7 images is too few to amortise warm-up**: the first
inference carries graph setup and JIT, and with n=7 the median never escapes it. That is §15.4's
argument for N ≥ 200 demonstrated on a desktop, before anyone spends a phone on it.

⚠ The nearest-neighbour structure is a correctness signal rather than a quality one: the highest
pair (0.921) is the *same photograph* stored twice in different formats, which is what a working
embedding should say. Seven photos from one occasion cannot tell us more than that.

⚠⚠ **Still not a library measurement.** `~/Pictures` held seven images — the Photos library is
iCloud-optimised with no originals on disk — so the "is zero-shot good enough" question is exactly
as open as it was. ⚠ And a real library would be mostly **HEIC**, which `ImageIO` does not decode:
the harness would report those unreadable and silently measure only the JPEGs. A library run needs
that fixed first (`drive-content-preview.md` §3.1 has the desktop HEIC bridge this repo already
bundles).

#### 3.5.2 The run that used Tenshu's own store — 90 real photographs (2026-08-14)

⭐ The best available answer short of a phone: point the harness at
`~/.tenshu/app.tenshu.control.desktop/content`, which is a real Drive with real photographs in it.

**uint8, 90 images: p50 20 ms, p90 30 ms, max 46 ms — a 5 000-photo pass in ~100 s.** Drift against
fp32: **mean cosine 0.988, worst 0.971**, over 90 distinct images.

⭐ **Re-run after the library grew** (80 → 140 blobs; 60 → 90 readable images) and the figures did
not move: p50 identical, drift 0.989 → 0.988. The desktop measurement has **converged** — more
photographs will not change it, and what remains open is *quality*, which drift does not touch.

⚠⚠ That re-run needed a build fix first: the photo directory is **not** a Gradle task input (hashing
it would mean hashing gigabytes of unrelated content), so adding photographs changed nothing Gradle
could see and the task was **UP-TO-DATE** — it would have reported the previous run's numbers as
fresh. A measurement task is now never up to date. ⚠ This is the third variant of the same defect
in this harness: `-D` flags that never arrived, a flag missing from the forwarding list, and now a
result cached across a changed world.

⭐ The drift trend now has three points and one direction: **screenshots 0.981 → 7 photos 0.987 →
60 photos 0.989.** Photographic content quantises better than UI, consistently. uint8 is a safer
choice than the first measurement suggested.

⚠⚠ **Reaching the store at all took two harness fixes, each of which failed *silently*:**

1. **The content plane stores extensionless blobs in per-file subdirectories**
   (`.thumbnails/<uuid>/large`), so an extension filter over one flat directory found **zero images
   in a store full of them** — and said "no images", which reads as *"your folder is empty"* rather
   than *"I cannot see this shape"*. The harness now walks recursively and decides by **magic
   bytes**.
2. ⚠⚠ **Keying by filename collapsed 60 images into four map entries**, because every blob in that
   store is named after its size tier. The timings stayed honest — they are per read, not per key —
   while the drift average silently covered a handful of files. **And it reported the same headline
   mean (0.989), which is exactly why nobody would have looked.** Keyed by relative path now.

⚠ 48 of the 138 blobs were `list`-tier thumbnails `ImageIO` returned null for (18 of 78 before the
library grew — the ratio is stable); the `large`/`xlarge` tiers all decoded. Not chased, because `large` is the tier a real index would read anyway.

⚠ **These are thumbnails, not originals** — the store keeps full-size bytes only for files pinned
offline (3 of them here). For an embedding measurement that matters less than it sounds: the model
sees 224×224 whatever it is handed. It would matter for OCR, where resolution is the feature.

⚠⚠ **Still not the answer to "is zero-shot good enough".** That needs a vocabulary, the text tower,
and someone's judgement about whether the labels are right — none of which a drift number touches.

⚠⚠ **Superseded in part by §3.7.** Every run in §3.5–§3.5.2 fed the model **CLIP's** normalisation
and a centre crop; SigLIP ships `image_mean = image_std = 0.5` and squashes to 224² with no crop.
The timings stand. The drift numbers were measured on inputs SigLIP's own processor would never
produce, and — worse — the comparison was structurally incapable of noticing, because both models
got the same wrong pixels. Re-measured correctly in §3.7.2, mean drift is **0.979, not 0.988**.

#### 3.5.3 ⭐ The phone — IN-3m, 562 photographs on a OnePlus CPH2581 (2026-09-01)

The measurement §3.5 has been a *floor* for: the same shape of pass, on a mid-range Android phone,
over 562 JPEGs spanning eighteen months of a real camera roll. Full write-up, conditions and
consequences in [`inference-service.md`](proposals/inference-service.md) §15.12; the numbers, beside
the desktop column, are:

| | desktop (§3.5.2, 90 images) | phone, cold | phone, already warm |
|---|---|---|---|
| **uint8, CPU EP** | **20 ms** | **121 ms** (113–133 over three passes) | **133 ms** (tail **186 ms**) |
| 5 000-photo pass | ~100 s | ~10 min of inference / **~18 min wall clock** | ~21 min, still climbing |

⚠ **The phone is ~6× the desktop, and the floor language was right.** Note the desktop column is
`-224` at 20 ms; the pinned `-256-multilingual` measured 27 ms there, so like-for-like the gap is
closer to 4.5×.

⭐⭐ **The throttle question — the whole reason for a device run — answers *conditionally*, and one
pass could not have found it.** Within a single 562-image pass there is **no throttling at all**
(the last 50 images run *faster* than the first 50, ratio 0.80, because warm-up sits in the first
window). Run the identical pass again on the warmed device and **the same 50 images cost 186 ms
instead of 113 ms — 1.65×**. A first index over 5 000 photos is three times longer than the pass
that measured it, so the honest planning figure is the warm tail, not the cold median.

⚠⚠ **The accelerated execution providers both lost to the CPU.** NNAPI 196/191 ms and XNNPACK
147/167 ms against the CPU EP's 121/133 — and both genuinely placed the graph rather than falling
back (§15.5's 2 ms tell did not fire). IN-D13's ladder is not a preference order on this device.

⚠ Battery is unusable from this run: at 100 % on AC the charge counter measures the charger, and the
per-pass deltas flip sign. The thermal figure is the real one — **37.7 °C → 44.1 °C** over ~15 min.

⚠ **Nothing here says the labels are good.** 562 photographs went past a model and nobody looked at
what it said about them; §3.7.10 still stands.

#### 3.5.4 The iPhone — a partial IN-3m-iOS run, 554 photographs on an iPhone 17e (2026-10-07)

⚠⚠ **IN-3m-iOS is still open.** This is one short pass per row on iOS's own artifact, the fp16 Core
ML export (`coreml` runtime, same pinned checkpoint). The cold / warm / +20-minute sequence, the
battery run, the post-reboot launch and the HEIC sample are owed, so this is not §3.5.3's
measurement repeated on iOS and no gate reads as passed from it. Full write-up, what is done and
what is owed in [`inference-service.md`](proposals/inference-service.md) §15.13; the headline row is:

| | prediction, median | decode, median | per photo, median | wall clock per photo |
|---|---|---|---|---|
| **fp16 Core ML, `.cpuAndNeuralEngine`** | **13 ms** | 20 ms | **33 ms** | 37 ms |

554 embedded, none failed; last-50 per photo 31 ms; `thermalState` nominal throughout one 20 s pass.
What that establishes: latency per photo, that `thermalState` stayed nominal over a ~20 s pass on the charger,
and vector agreement across compute units (min cosine 0.999998 between `.cpuAndNeuralEngine` and `.all`).

⚠⚠ **Not a column for §3.5.3's table.** Another device class, another runtime and precision, and
the same *recipe* from the same camera roll but not the same files (554 against 562). The ~9 ×
on prediction is stated, with its caveats, in §15.13.6 and nowhere else.
⚠ Decode is the larger half on camera originals. ⚠ **Whether a pass throttles is not answered**:
the first-50 / last-50 ratio is not a throttle reading here (§15.13.1), and nothing ran long enough
to heat the phone, so §3.5.3's warm-tail finding has no iOS counterpart yet.

### 3.6 ⚖️ The two measurements that gate IN-3b — ✅ both answered (§3.7, §3.5.3)

Nothing about a model can be pinned until these exist, and neither is a literature value:

1. **Int8 accuracy against IN-D9's vocabulary** — ✅ **answered, and not the way §3.5 implied**
   (§3.7): against a real 207-label vocabulary, uint8 changes the winning label on **1 image in 7**,
   while the vector drift it was being judged by reads a comfortable 0.979. Ranking surviving *in
   aggregate* is not the same as a label surviving, and the drift proxy was too generous.
2. **First-index wall time and battery on a mid-range Android phone** — ✅ **answered 2026-09-01**
   (§3.5.3): **121 ms/image** on the CPU EP, ~18 min wall clock for 5 000 photos, and no throttling
   within a pass but **1.65×** across a warm one. The feature is shippable; IN-D6's metered
   scheduling is now **required** rather than prudent, which is the design `retention-quota`'s
   local-cache ceiling should be sized against.

⭐ Both are now measured, so what gates IN-3b is no longer a *measurement*: it is ⚖️ counsel on R9,
and the design consequences §3.5.3 hands it — a required idle+charging gate, a resumable progress
surface, and an EP choice that cannot be taken from IN-D13's written order.
⚠ §7.7 of `inference-service.md` still reads as though nothing here is measured; it predates both.

### 3.7 The zero-shot read — 207 labels against a real library (2026-08-14)

The first measurement of the thing the feature actually does: not "do the vectors move" but **"what
does the model say is in this photograph"**. Inputs: SigLIP v1 base-224, the 98 decodable images in
the maintainer's own Control store, and `label-set-candidate-v1.tsv` — 14 top-tier categories and
193 leaves, written against IN-D9 and reviewed by nobody yet.

Reproduce with `scripts/inference/embed-label-set.py` (text tower, offline) feeding
`EmbeddingHarness` (vision tower). ⚠ The label vectors came from the **fp32** text tower while the
images went through the **uint8** vision tower — the shipping configuration is not necessarily that
mix, and nothing here measures a quantised text tower.

#### 3.7.1 ⚠⚠ Two sessions of quality numbers were measured on the wrong pixels

The harness normalised with CLIP's dataset statistics and centre-cropped to a square. SigLIP's
`preprocessor_config.json` asks for `mean = std = 0.5` and a straight resize to 224×224 — no crop.

⚠⚠ **The centre crop was not sloppiness; it was a deliberate choice with a written rationale** —
"squashing distorts every photo in the library, so the model gets judged on images no camera ever
produced" — pinned by a test named `aWideImageIsCentreCroppedRatherThanSquashed`. The reasoning is
sound in the abstract and wrong in fact: the model was *trained* on squashed images, so the crop
was the distortion. A test can pin a well-argued belief just as firmly as a fact.

⚠⚠ **Nothing in a model-vs-model comparison can see this.** Both models received identical wrong
input and agreed with each other beautifully (0.988). It took labels to expose it, because a label
is the first quantity computed **outside** the harness — the first thing a self-consistent mistake
cannot stay consistent with. Preprocessing is now a per-model `Preprocessing` profile, printed on
every run.

#### 3.7.2 SigLIP's score is not a cosine, and the constants are in the graph

SigLIP is trained with a **sigmoid** loss, so its score is `sigmoid(scale · cos + bias)` — and the
two constants live in the full `model.onnx`, not in the vision and text towers the harness loads.
Recovered by reading the graph rather than by memory: the `Add` feeding `logits_per_text` takes
`logit_bias = -12.932437`, and the `Mul` before it takes `exp(logit_scale) = 117.330765`.

⚠⚠ **These are the `-224` checkpoint's, and so is everything measured below.** The pinned
`-256-multilingual` has **110.995995** and **−12.885268** (p = 0.5 at cos ≈ 0.116), and those are
what `VisionModel.PinnedSigLip` carries since 2026-10-11 (§3.10.8).

On the `-224` checkpoint that puts the p = 0.5 decision boundary at **cos ≈ 0.110**, and it makes
three things concrete:

- ⚠⚠ **A raw-cosine threshold is not a threshold.** Text-to-text cosines across this vocabulary run
  **0.57 … 0.98** — `pizza`↔`glacier` is 0.827 — while image-to-text cosines over the same library
  run **−0.13 … 0.123**. Two completely different scales, and only one of them is the model's.
- ⚠⚠ **Nothing in the library reaches p = 0.5.** The highest score observed on any (image, label)
  pair is ≈ 0.25. A "50% confident" cut-off would label nothing at all; usable thresholds sit
  around 0.05–0.15, which is normal for SigLIP and looks broken if you expect softmax numbers.
- Re-measured with correct preprocessing, uint8-vs-fp32 drift is **mean 0.979, worst 0.942** —
  slightly worse than the 0.988/0.971 of §3.5.2, and measured on the right inputs.

#### 3.7.3 ⚠⚠ uint8 changes the winning label on one image in seven

**84 of 98 images keep their top-1 label** when the vision tower is quantised. The other 14 change.

⚠ Read honestly, most flips are near-ties between two labels that are both defensible — `selfie` ↔
`indoors`, `sunset` ↔ `river`, `parade` ↔ `child` — and a coin toss between two plausible labels is
not a quality loss. A few are not: `rain` where fp32 said `qr code`, `sunset` where fp32 said
`child`.

⭐ **The finding is about the method more than the model.** §3.5 concluded "ranking identical" from
a 0.98 drift; a 0.98 cosine turns out to leave ample room for the top two labels to swap. When the
question is "which label wins", measure which label wins.

uint8 remains the recommendation — it is **2× faster** (p50 25 ms vs 53 ms on the same 98 images)
and its errors are concentrated in ties — but the claim "quantisation is free" does not survive.

#### 3.7.4 The vocabulary is the weak part, not the model

| What | Number |
|------|--------|
| Labels reaching any image's top 3 | **48 of 207** (49 at fp32) |
| Labels ever winning outright | 20 |
| `indoors` in the top 3 | **55% of the library** (60% at fp32) |
| Leaf rule degraded to parent | 51 of 98 at margin 0.01 |

- ⚠⚠ **`indoors` is in the top 3 for more than half the library.** It is a *top-tier* label, and
  IN-D9 gives top-tier labels independent thresholds — so as written, the vocabulary would tag most
  of the library `indoors` and mean nothing by it. Per-image output hides this completely: every
  line looks individually reasonable. It is only visible per *label*, which is why the harness now
  reports both.
- ⚠ **158 labels never reached any top 3.** Partly the library — 98 images, heavy on screenshots and
  thumbnails, is not a photo collection — but a vocabulary written from an armchair will always
  have a long dead tail, and the tail costs nothing to carry and nothing to cut.

#### 3.7.5 ⚠⚠ IN-D9's leaf rule has no floor, and needs one

The rule as written: argmax within a sibling set, degrade to the parent when the top two are close.
Run against real images, **the best sibling is frequently negative while the parent is positive** —
`indoors 0.061` with a winning child of `staircase −0.009`, `indoors 0.044` with `gym −0.006`.

An argmax always returns something. With only a *relative* margin test, a branch whose children the
model actively rejects still emits its least-rejected child, and a clear margin between two
confidently-wrong siblings reads as confidence. ⚠⚠ **The rule needs an absolute floor on the leaf
before the margin question is meaningful** — recorded as an amendment in `inference-service.md`
§7.7.

#### 3.7.6 ⚠⚠ R7 asked for this on **both** checkpoints, and it ran on one

§3.7 measured `-224`. **R2 recommends `-256-multilingual`**, and R7 is explicit that step 0 should
run on *both* before R2 is pinned, because the English/multilingual accuracy delta is R2's whole
open question.

⭐ **Closed by §3.7.7** — step 0 has now run on both, and the checkpoints are equivalent under
quantisation. What that run found instead is that our *export* is worse than the third-party one.

⚠⚠ **The gap mattered because R2 is the one decision that is not deferrable.**
The two are separate checkpoints with separate embedding spaces, so "ship `-224` now, move later" is
not an upgrade — it is a re-embed of every photo on every device. Everything in §3.7 that reads as
*"SigLIP is fine"* is evidence about a checkpoint the track is **not** currently recommending.

⚠ They also differ in **input size** — 224² against 256² — which is why the harness's side length
became a flag (IN-3b step 0). Feeding a 256-patch model 224² pixels does not fail: ORT accepts the
tensor and returns a vector computed from an image the model never saw in training, which is the
shape of wrong answer this harness has produced twice before.

#### 3.7.7 ⭐⭐ R7 step 0 on both checkpoints — and the variable is the **exporter**, not the model

Run 2026-08-15 over the same 98 images, both checkpoints exported by the same script
(`scripts/inference/export-vision-tower.py`) and quantised by the same call.

| Artifact | uint8-vs-fp32 drift (mean / worst) | p50 | uint8 size |
|---|---|---|---|
| `-224`, **Xenova's** export | 0.979 / 0.942 | 25 ms | — |
| `-224`, **our** export | **0.935** / 0.836 | 20 ms | 100 MB |
| `-256-multilingual`, **our** export | **0.949** / 0.835 | 27 ms | 100 MB |

⚠⚠ **R2 is not threatened by quantisation.** The two checkpoints come out equivalent — the
multilingual one is *marginally better* on mean drift — so the English/multilingual delta that R7
called "R2's whole open question" does not show up here at all. On this evidence R2 can be pinned
without a quantisation objection.

⚠ **Quantified in §3.7.9, and it is smaller than it looks — two labels in 98.**

⚠⚠ **Our export pipeline is the thing that is worse.** Same checkpoint, same photos, same
harness: 0.979 through Xenova's artifact against 0.935 through ours. The difference is in how the
weights were quantised — `quantize_dynamic(weight_type=QUInt8)` is the naive call, and per-channel
quantisation or excluding the sensitive layers is the usual gap. ⚠ That matters because §3.7.3
measured **one top-1 label flip in seven at 0.979**; a pipeline that lands at 0.935 will flip more.

⭐ **So IN-3b's next open question is export tooling, not model selection** — which is a much
cheaper question, and one nobody would have asked without running the control.

⚠⚠ **This reverses the reading taken from the first run of the day.** The multilingual checkpoint
measured at 0.949 against Xenova's 0.979 for `-224`, which looked like "the recommended checkpoint
quantises worse" — a conclusion about **R2, the one decision that cannot be revisited**. It was
wrong, and only the like-for-like control caught it: comparing two artifacts produced by different
toolchains measures the toolchains.

⚠ Timing is a real difference: 20 ms against 27 ms per image, ~35% for the larger input (256² is
256 patches against 224²'s 196). ⭐ And the size estimate in R3 holds — **100 MB** measured against
"~93 MB int8" predicted.

#### 3.7.9 Chasing our export's quantisation gap — and the proxy that overstated it

§3.7.7 left our pipeline losing more to uint8 than a third-party artifact of the same weights.
Six experiments later, all on `-224` over the same 98 images:

| Variant | mean drift | worst | top-1 label agreement |
|---|---|---|---|
| ours, per-tensor (the naive call) | 0.935 | 0.836 | — |
| **ours, per-channel** | **0.941** | 0.866 | **82 / 98** |
| ours, `reduce_range` | 0.914 | 0.822 | — |
| ours, per-channel + `reduce_range` | 0.934 | 0.853 | — |
| ours, **legacy** (torchscript) exporter, per-tensor | 0.934 | 0.828 | — |
| ours, legacy exporter, per-channel | 0.940 | 0.852 | — |
| Xenova's uint8 | 0.979 | 0.942 | 84 / 98 |

⭐⭐ **The answer is that the gap barely exists in the metric that decides anything.** A 0.038
difference in mean cosine drift is worth **two images out of 98** — 82 against 84 top-1 label
agreements. Both pipelines flip roughly one label in six.

⚠⚠ **So the drift proxy overstated it, exactly as it did in §3.7.3** — where a comfortable 0.979
turned out to hide a 1-in-7 label flip. The proxy is directionally useful and quantitatively
misleading in *both* directions, and this is the second time in two days that reading it as a
quality number produced a wrong conclusion. **Measure the label.**

What the experiments did establish, and what they cost:

- ⭐ **`per_channel=True` is the best of the four quantiser settings** and is now the default in
  `export-vision-tower.py`. ⚠ `reduce_range=True` makes it *worse* in both configurations: it is an
  AVX2 overflow workaround, not an accuracy knob.
- ⚠ **The exporter is not the variable.** The legacy torchscript path and the dynamo path produce
  graphs of very different size (1702 against 902 nodes) and quantise to within 0.001 of each other.
- ⚠ **Our fp32 export is equivalent to theirs** — mean cosine **1.000**, worst 0.999, measured
  directly. So nothing is wrong with the export; only the quantisation differs.
- ⚠ **The op coverage is identical**: 1 `ConvInteger` and ~76 `MatMulInteger` in both, and the
  producer chain behind `pooler_output` is node-for-node the same. The pooling head — the tensor we
  actually read — is quantised in both.
- ⚖️ **The residual is unexplained.** Same weights, equivalent fp32 graphs, same ops quantised, and
  their artifact still holds 0.979. Most likely a different `onnxruntime` version's scale
  computation. ⚠ Left open deliberately: it is worth two labels in a hundred, and the next person
  should know these six experiments were already run rather than run them again.

#### 3.7.10 What this does not establish

- ⚠⚠ **No ground truth.** Nothing here checks a label against what is actually in the photograph;
  it measures agreement, distribution and self-consistency. (§3.10 does, for the 37 candidate labels
  COCO can score, on COCO's images.) The eyeball pass is a maintainer's, and
  its findings do not belong in a public repo.
- **98 images from one store**, biased toward screenshots and app content, mostly `large`-tier
  thumbnails rather than originals.
- **Desktop only** (M-series, ORT CPU). Phone numbers are IN-3m.
- **One model, one prompt phrasing.** Prompt wording is a real lever on zero-shot quality and
  nothing here varies it.

### 3.8 ✅ PINNED — R1 and R2 (2026-08-25)

**The model is `siglip-base-patch16-256-multilingual` (SigLIP v1).** R1 and R2 move from
recommendation to decision. Nothing else in §3.4 moves with them.

#### 3.8.1 Why now, and why it is R2 that needed pinning

R2 is the only decision in this file that **cannot be revisited cheaply**. The two checkpoints have
separate embedding spaces, so a later change is not an upgrade — it is a re-embed of every photo on
every device, which IN-D5 makes device-local work: cheap in bytes, expensive in hours, and paid by
the user rather than by us.

⭐ **R2 named its own precondition and that precondition has been met.** It said the English/
multilingual accuracy delta *"falls out of §3.3's measurement 1, which is why that measurement runs
before this is pinned."* §3.7.7 ran exactly that, on **both** checkpoints, through the same exporter
and the same quantiser call — the like-for-like control that §3.7.8 records as having reversed an
earlier wrong reading. The delta is not there: the multilingual checkpoint measured *marginally
better* on drift (0.949 against 0.935), at 27 ms against 20 ms per image and the same 100 MB.

⚠ Leaving it open has a cost that only grows. Every downstream piece — R4's calibration table, the
label vocabulary's thresholds, IN-3m's on-device timings — is measured **against an embedding space**,
so measuring before the space is fixed is measuring something we might discard.

#### 3.8.2 What this pin does and does not commit us to

| Committed | Not committed |
|---|---|
| the family (SigLIP v1) and the checkpoint | that the feature ships at all — IN-3m can still say no |
| the embedding space every vector is computed in | the *text* encoder, which R3 defers (and with it the vocabulary-size question) |
| the ~100 MB image-encoder artifact and its 27 ms/image desktop cost | any accuracy claim — §3.7.10 stands, there is still no ground truth |
| ⚠ that a later change of mind costs a full re-embed | thresholds, calibration, signing key, corpus disclosure (R4/R8/R9, all still open) |

⚠⚠ **The pin is not a quality claim, and should not be read as one.** What §3.7 establishes is that
this checkpoint is *not disqualified* — it quantises no worse than its sibling, it separates, and it
clears licence. Whether its labels are any *good* is measured by nobody yet, and quantisation still
flips roughly **one top-1 label in six**.

#### 3.8.3 ⚠ The vocabulary-size half of R2's stated cost is deferred, not answered

R2 recorded two costs: an English-accuracy trade, and an unverified tokenizer vocabulary. The first
was measured and is absent. The second is **untouched** — and it is deferred rather than resolved,
because R3 does not ship the text encoder at first. A ~250k-vocab text tower is R3's ~277 MB
artifact, and it becomes a real number the day free-text search does. ⚠ A reader who takes this pin
as "the vocabulary question is closed" will be surprised by that download.

#### 3.8.4 What is now the gate

⚠⚠ **IN-3b is no longer gated on choosing a model.** `inference-service.md` §12.6's *"gated on the
model decision and its licence / provenance review"* is now two separate things, and only one of
them is a decision:

- **IN-3m — wall time and battery, on real hardware.** ✅ **Run 2026-09-01 (§3.5.3)**, and it did
  **not** return *"do not ship this"*: 121 ms/image, ~18 min for a 5 000-photo pass, no throttle
  cliff. ⚠ What it changed is the *shape* — IN-D6's metered scheduling moves from prudence to
  requirement, and `retention-quota-model.md`'s local-cache ceiling finally has a figure to size
  against.
- **⚖️ Counsel on R9** — the corpus disclosure. ⚠ Worth putting the §3.4 R9 reading to them at the
  same time: as an **integrator** rather than a GPAI provider, the training-data-summary obligation
  may sit with the model's provider, which would reduce R9 to a UI string.

⭐ Everything else that looked like it was waiting on the model choice is now waiting on **labels**,
not on a decision — R7 step 1's few hundred hand-labelled photos produce measurement 1 proper *and*
R4's threshold table from one pass.

⚠⚠ **And whatever measures next should measure the label, not the vector.** §3.7.3 and §3.7.9 each
reached a wrong conclusion by reading cosine drift as a quality number — once hiding a 1-in-7 label
flip behind a comfortable 0.979, once overstating a gap worth two images in 98. The proxy is
directionally useful and quantitatively misleading in **both** directions.


---

### 3.9 ⭐⭐ R3's tokenizer needs no charsmap — measured, 2026-09-02

R3 ships a **text** encoder, and the weights were never the hard part. `SiglipTokenizer` is
SentencePiece **Unigram, 250 100 pieces, byte-fallback**, max length 64, behind a normalizer chain
of lowercase → punctuation strip → whitespace collapse → **`Precompiled` charsmap** → collapse, with
a Metaspace pre-tokenizer. That charsmap is a 317 KB compiled SentencePiece blob with no cheap
equivalent on any platform we ship — and a tokenizer that disagrees with the reference produces a
*plausible* query vector that quietly retrieves the wrong photographs, which is §3.7.1's failure
class exactly: wrong inputs, right-looking numbers.

So before choosing a route, the disagreement was measured. A good-faith Kotlin-equivalent — NFKC
(which `java.text.Normalizer`, `String.normalize` and `NSString` all provide), the two `Replace`
regexes read out of `tokenizer.json`, Metaspace, Unigram Viterbi and byte fallback — against the
reference implementation:

| Sample | Agreement |
|---|---|
| **5 000 lines of this repo's own prose** (English, code identifiers, symbols, German, paths) | **5 000 / 5 000 — 100 %** |
| 70 photo queries across 8 language and script groups | **70 / 70 — 100 %** |
| every assigned BMP + emoji codepoint, normalizer isolated | **63 852 / 63 859 — 99.99 %** |

⭐ **Verdict: route "hand-written Kotlin" — no `onnxruntime-extensions`, no second native
dependency** on top of the 18.5 MB per ABI that `inference-service.md` §19.8 already spends. What
closes the gap is NFKC plus a **twelve-codepoint fix-up table**, not a charsmap.

⚠ The 7 residual codepoints are all NFKC folding a rare character *more* than the charsmap does —
`ꟲ`→`C`, `㋿`→`令和`, `～`→`~`, one combining-mark ordering. None mangles text and several arguably
help retrieval; none appears in a photo query anyone will type.

#### 3.9.1 ⚠⚠ Three things the measurement caught that reasoning did not

Recorded because each would have shipped silently, and the third is a lesson about measuring rather
than about tokenizers.

1. ⚠⚠ **Generalising the fix made it four times worse.** Nine disagreeing characters were `Cf`/`Cc`,
   so the first fix deleted those two Unicode categories — and the gap went **17 → 72**, because the
   charsmap *keeps* most of them (soft hyphen, U+0080) and drops exactly three. Enumerate what was
   measured; do not promote it to a rule.
2. ⚠⚠ **The probe could not see the distinction it was testing.** Each character was placed between
   spaces (`"photo of X thing"`), where *deleted* and *replaced by a space* collapse to the same
   string. Only the independent 5 000-line corpus exposed it: zero-width and bidi marks become a
   **space**, three control characters are deleted. ⭐ The hand-built query set could never have
   caught this — it was written by the same person as the tokenizer.
3. ⚠ Metaspace's `prepend_scheme: always` does **not** prepend to an empty string: a line of pure
   punctuation normalises away to nothing and the reference emits `</s>` alone.

#### 3.9.2 What the vocabulary costs, and where it should live

250 100 pieces, longest 16 characters: **3.1 MB** packed as `score + length + UTF-8 bytes`, against
16.4 MB as `tokenizer.json`.

⚠ Measured alongside it: the text tower itself is **280 MB** int8, of which **192 MB is the
multilingual vocabulary's embedding table** (250 000 × 768). §3.8.3 predicted a reader would be
surprised by this download; a searchable device carries ~383 MB in total, against the vision
tower's 100 MB alone. That is the price of R2's multilingual pin, now paid in numbers rather than
in prose.

⚠⚠ **It ships beside the model artifact, not inside the app.** The tokenizer is useless without the
weights, and §19.8's 18.5 MB is already paid by every install including those that never switch
inference on. A second unconditional megabyte for a capability that is off by default is the wrong
default — and putting it with the weights means a model change can bring its own vocabulary, which
is what a checkpoint change actually requires.

#### 3.9.3 ⚠⚠ The text tower needs **int8**, and the vision tower's recipe would have shipped a worse search

Exported with the vision tower's settings — `quantize_dynamic`, `QUInt8`, `per_channel=True` — the
text tower drifts **0.866 mean / 0.823 worst** against fp32. The vision tower measured 0.966/0.947.

⚠⚠ And unlike §3.5's drift figure, this one *changes answers*. Over 12 photographs × 12 captions,
uint8 against fp32 text embeddings:

| | |
|---|---|
| top-ranked caption identical | **75 %** — one query in four ranks something else first |
| full ranking identical | **0 %** |
| per-photo score correlation | 0.699 |

⭐ **`QInt8` fixes it at the identical file size: drift 0.987 / 0.983.** Same 280 MB, same graph,
one flag.

⚠ §3.5 recorded int8 refusing to run at all — *"`ConvInteger` unimplemented in ORT's Java CPU
build"* — which is why the **vision** tower ships uint8 and why nobody tried int8 here. The text
tower contains no convolutions, so the blocker does not apply; a JVM test loads the int8 artifact
through ORT's Java build and embeds with it, because "should not apply" is not a measurement.

⚠⚠ **A hypothesis worth recording because it was wrong.** The obvious suspect was the 250 000 × 768
token-embedding table, which `per_channel=True` quantises with a **single scalar scale** — 192
million values, one divisor, rows of wildly different norm. Re-quantising with the table left in
fp32 changed the drift by 0.002 (0.855 against 0.853). The loss is in the transformer's MatMuls,
not the table, and an artifact redesigned around the table would have cost a week and fixed nothing.
⭐ It is the same lesson as §3.7.9's *"the drift proxy overstated the gap"*, in the other direction:
measure the fix before building on the diagnosis.

#### 3.9.4 ⚠⚠ The padding is `</s>`, not `<pad>` — and §3.9's agreement figures could not see it (2026-10-04)

The checkpoint's `tokenizer_config.json` declares `pad_token` = `</s>` (id 1). The vocabulary also
holds a `<pad>` piece at id 0, and the Kotlin tokenizer padded with that one. The text tower has
**no attention mask**: every one of the 64 positions is input, so the padding id is part of the
query and the model was trained on exactly one.

⚠⚠ **Every agreement number in §3.9 compared ids with the padding stripped**, on both sides, and
the 41 golden cases did the same — so 5 000/5 000 was true and the rows fed to the model were still
wrong in up to 63 positions of 64. ⚠ A reference driven through `tokenizer.json` with the bare
`tokenizers` library has the same blind spot: that route never reads `pad_token`, so whatever
padding the caller assumes comes back confirmed. ⭐ The goldens are now generated by `scripts/inference/golden-tokenizer-cases.py` —
`transformers` loading the checkpoint directory with `padding="max_length"` — and compared as whole
rows.

Measured on the host over 36 queries in four languages against 300 COCO images, reference = fp32
with pad 1:

| pair | padding | top-1 agrees with reference | mean top-1 cosine | queries whose best match clears the `p ≥ 0.10` floor |
|---|---|---|---|---|
| fp32 | pad 0 | 6 / 36 | 0.050 | 0 / 36 |
| fp32 | pad 1 | (reference) | 0.089 | 19 / 36 |
| shipped ONNX (uint8 vision, int8 text) | pad 0 | **2 / 36** | 0.045 | **0 / 36** |
| shipped ONNX (uint8 vision, int8 text) | pad 1 | **29 / 36** | 0.089 | 19 / 36 |

The two paddings' text vectors sit at mean cosine 0.71–0.72 to each other. ⚠ No labels: by eye,
unblinded, the top-1 image was on topic for 17 of 36 queries with pad 0 and 27 of 36 with pad 1
(partly on topic: 12 and 5; off topic: 7 and 4).

⚠⚠ **The last column is the product consequence, and it is larger than the ranking one.**
`photosLike`'s relevance floor (`inference-service.md` §35) is cosine ≥ 0.0915 — written `p ≥ 0.10`
here and in the table because that is what it read under the constants the app then carried
(§3.10.8; the cosine has not moved) —
taken from §3.7.2's band — which was measured on label vectors that `embed-label-set.py` had padded
with id 1 all along. No pad-0 query in this set reached 0.0915 against any image (highest 0.081), so
on this corpus search-by-description returned **nothing** for every query, not a worse ranking.
⚠ 17 of 36 pad-1 queries still clear nothing here, several with a correct top-1 at cosine 0.06–0.09:
whether 0.10 is too strict is now a question that can be asked, and it has not been answered.

⚠ Nothing stored moved. Text vectors are computed per query and never written; the image vectors,
the label vectors and `ScoreCalibration` were never touched by the Kotlin tokenizer.

#### 3.9.5 ⚠⚠ A character above U+FFFF that is not a piece is its four UTF-8 bytes (2026-10-06)

The byte fallback walked the string one UTF-16 unit at a time. A character above U+FFFF that the
vocabulary does not hold reached it as two surrogate halves, and each half was encoded as the single
byte `?`. So **every** such character was the same two ids, `<0x3F>` `<0x3F>` (66, 66): the query
could not tell one from another. The reference emits the character's four UTF-8 bytes as byte
pieces (`<0xNN>` is id 3 + NN), and so does the Kotlin tokenizer now: the fallback's span is one
code point.

Measured with the checkpoint's own tokenizer (`SiglipTokenizer` over `spiece.model`;
`transformers` 4.53.3, `sentencepiece` 0.2.1), each character tokenised alone [M]:

| characters above U+FFFF | count | not a piece: byte fallback |
|---|---|---|
| `Emoji=Yes` (emoji-data 16.0) | 1 249 | **436** |
| `Extended_Pictographic`, assigned | 1 699 | 911 |
| U+1F000–U+1FAFF, assigned | 2 342 | 1 371 |
| CJK Extension B (U+20000–U+2A6DF), all of it | 42 720 | **42 718** |
| every assigned one outside the private-use planes (Unicode 15.1) | 94 239 | 91 826 |

- The vocabulary holds 987 pieces that are one character above U+FFFF, and no longer piece
  contains such a character. So the fallback is the only path through one that is not a piece.
- ⚠ About a third of the emoji a person can type were affected, `🎳` and `🫠` among them; the four
  emoji in the goldens were pieces, which is why 41 of 41 agreed.
- Emoji at or below U+FFFF (170 beyond ASCII, 13 of them not pieces) are three bytes and one UTF-16
  unit. They were never wrong.
- **The Kotlin tokenizer against the reference, whole rows, on the JVM** [M]: each of the 94 239
  alone, and 4 000 of them mid-sentence, between two letters and beside another. 106 239 rows:
  103 366 differed before, **48** differ now, and none of the 48 is at the fallback. 35 are the
  Vithkuqi capitals (Unicode 14), which the build's JDK 17 does not lowercase; 13 hold a character
  NFKC maps (U+1F16C, one squared sign, the segmented digits U+1FBF0–1FBF9) and are §3.9's class,
  or the JDK's Unicode 13 — not told apart. A throwaway harness; what stays is the goldens.
- The goldens went from 41 rows to 57: twelve with such a character (alone, adjacent, mid-sentence,
  beside an emoji that is a piece, in a CJK run, and twenty in a row so that the 64-id window ends
  inside one), two that NFKC folds into pieces, and two with U+FFFD. The first 41 are byte for
  byte what they were. `golden-tokenizer-cases.py` refuses a fallback case that has no byte piece
  in its row.

⚠⚠ **Half a surrogate pair is U+FFFD, and so a space. Decided, not measured**: the reference has no
answer. A lone surrogate is not UTF-8, and `transformers` and SentencePiece both refuse the string
(`TypeError`) [M]. Left to each platform's encoder it is `?` on the JVM and U+FFFD's three bytes on
Kotlin/Native and in a browser, and Swift's string bridge has replaced it with U+FFFD before a query
reaches Kotlin at all. The tokenizer therefore replaces every unpaired surrogate with U+FFFD as its
first step, before any library reads the string. U+FFFD the reference does answer for: a space
(`TO_SPACE`, from §3.9.1's corpus; the two new golden rows). ⚠ Before this it was the byte `?` on every platform
(`ios-on-device-inference.md` §10.2); that pin is gone.

⚠ Nothing stored moved, for §3.9.4's reason: a query's text vector is computed and never written.

### 3.10 Labels against ground truth: COCO val2017, three artifacts (2026-10-10)

The first read of labels against **what is in the photograph**, and the measurement
`ios-on-device-inference.md` D13 left open: one catalog stamp covers the uint8/int8 ONNX pair
(Android, desktop) and the fp16 Core ML pair (iOS), label rows sync, and a label is a thresholded
score. Do per-artifact thresholds make the two platforms write the same labels?

**They do not.** With a floor per label per artifact, each chosen for precision 0.90 on one half of
the corpus and applied to the other, the two shipped artifacts agree on **78 %** of the (image,
label) pairs that either of them labels. One shared table gives 69 %. Under the full selection rule
they write the identical label set for 78 % of images, and for 72 % of the images where either
writes anything. The fp16 Core ML pair is not the side that moved: it agrees with fp32 on 98.8 %.
The distance is the ONNX pair's quantisation, and it is as large between two `onnxruntime` versions
running the same ONNX bytes as between the two platforms (§3.10.6).

⚠⚠ **What this scores is 37 of the 207 candidate labels**, the ones with a COCO object category
behind them. Every scene, document, screenshot, event and weather label, and most activities, are
outside it (§3.10.9). ⚠⚠ **COCO is not a photo library**: nothing here is a firing rate on one.

Reproduce: `scripts/inference/label-eval.py` (`embed`, then `evaluate`), toolchain
`requirements-label-eval.txt` (the retrieval gate's, unchanged), tests `test_label_eval.py`.

#### 3.10.1 What was measured, on what

| | |
|---|---|
| Reference | fp32 PyTorch, revision `8952a4ea…`, `model.safetensors` sha256 `00df88d8…` |
| ONNX pair | `vision_model_uint8.onnx` `84c7bb92…` + `text_model_int8.onnx` `277534a4…`, the published digests, on onnxruntime **1.20.0** (the version the apps ship) |
| Core ML pair | the kept export `coreml-export-3fd1918106d4` (`vision_fp16` `a30d4dde…`, `text_fp16` `628f7f32…`), host, coremltools 9.0, `cpuAndNeuralEngine` |
| Corpus | COCO val2017, 5 000 images; manifest `label-eval-corpus.txt`, id `f893efc6…`; annotations `instances_val2017.json` sha256 `e8c7f790…`, pinned in the same manifest |
| Pixels | Pillow 12.3.0 bicubic squash to 256², the retrieval gate's transform; digest `c4134586…` |
| Label set | `label-set-candidate-v1.tsv` sha256 `c9d15726…`, its own prompts; token rows `931173e9…`, padded with id 1 |
| Mapping | `label-eval-coco-mapping.tsv` sha256 `42598881…` |
| Split | seed 20261010: 2 500 images calibrate, 2 500 are held out (by sha256 of `"<seed>:<file name>"`) |
| Host | Apple silicon, macOS 26; Python 3.13.7, torch 2.7.0, numpy 2.2.6, transformers 4.53.3 |

Each artifact scores an image against a label with **its own two towers**: the cosine of its image
vector and its text vector for the label's prompt. That is what each platform would compute.

**Ground truth** is image-level presence: an image is positive for a label when it has at least
one instance of a mapped category. ⚠ Two effects of that choice, both measured (§3.10.7): `iscrowd`
regions change nothing in val2017 (no image has a category only as a crowd region), and dropping
instances under 1 % of the image area removes half or more of the positives of eight labels.

**Which numbers are which.** A floor is chosen on the calibration half. Precision, recall, AP,
agreement and label sets are reported on the **held-out** half unless a figure says *all*; the
score distributions and firing rates are over all 5 000 and say so. Intervals are 95 % percentile
bootstraps over held-out images (1 000 resamples) ⚠ with the floor held fixed: they cover which
images were held out, not where the calibration half put the cut. A resample that draws no
firing image has no precision; it is left out of that interval and counted in the result. On this
run that is at most 2 of 1 000 for a shipped artifact (`truck` on ONNX, `sandwich` and
`snowboarding` on Core ML) and 7 for fp32; no resample lacked a positive.

**The floor rule.** The lowest threshold whose calibration-half precision reaches the target with
at least 10 images firing, so the most recall the target allows. Targets 0.90 and 0.95: a written
label is a synced row and a visible claim, and one wrong photograph in ten under "Dogs" is about
where a collection stops being trusted. A label that cannot reach the target has **no floor** and,
as `VocabularyLabel` requires one, never fires.

**Licences.** COCO's annotations are CC BY 4.0 (COCO Consortium). The images are Flickr photographs
whose copyright the consortium does not hold; `instances_val2017.json` lists each one's licence.
In val2017: 1 431 CC BY-NC-SA 2.0, 1 414 BY-NC-ND 2.0, 857 BY 2.0, 630 BY-NC 2.0, 417 BY-SA 2.0,
246 BY-ND 2.0, 5 with no known restrictions. **Nothing from COCO is in this repository**: the
manifest holds file names, sizes and digests. ⚖️ 3 475 of the 5 000 images are NonCommercial. A
threshold table shipped in a product and derived from them is a question for counsel before any
number below becomes a `calibration` file (R4).

#### 3.10.2 The mapping, and what falls outside it

`label-eval-coco-mapping.tsv` has one row for every candidate label: 29 one-to-one, 8 one-to-many,
**170 none**. Scored: `people`, `animals`, `food and drink` and `transport` of the 14 top-tier
labels, and 33 leaves. Eight of the leaves are **proxies** (ᵖ below): `skiing` scored against the
presence of skis, `wine` against a wine glass, `computer screen` against COCO's `tv`. A proxy's
figures describe the proxy.

⚠⚠ **Not scored at all**: `indoors`, `landscape`, `urban`, `documents and screens`, `events`,
`art and media`, `sky and weather`, `plants`, `objects`, `activities` as top-tier labels, and every
leaf under them that is not listed in the table. That includes the labels §3.7.4 found to be the
problem (`indoors`) and the ones Tenshu most wants (`receipt`, `screenshot`, `document`).

#### 3.10.3 Per label

Held-out half, target precision 0.90. Positives are calibration / held-out counts. A floor is a
cosine.

| Label | COCO categories | positives | AP fp32 | AP ONNX [95 % CI] | AP Core ML | floor ONNX / Core ML | P / R ONNX | P / R Core ML | both / only ONNX / only Core ML | Jaccard |
|---|---|---|---|---|---|---|---|---|---|---|
| `people` (top) | person | 1338 / 1355 | 0.893 | 0.894 [0.88, 0.91] | 0.893 | 0.0081 / -0.0128 | 0.92 / 0.58 | 0.92 / 0.57 | 742 / 110 / 92 | 0.79 |
| `animals` (top) | 10 categories | 501 / 515 | 0.933 | 0.933 [0.91, 0.95] | 0.933 | 0.0188 / 0.0180 | 0.87 / 0.87 | 0.87 / 0.88 | 488 / 30 / 31 | 0.89 |
| `dog` | dog | 85 / 92 | 0.801 | 0.817 [0.74, 0.88] | 0.800 | 0.0508 / 0.0516 | 0.84 / 0.72 | 0.89 / 0.63 | 62 / 17 / 3 | 0.76 |
| `cat` | cat | 87 / 97 | 0.920 | 0.909 [0.86, 0.95] | 0.920 | 0.0402 / 0.0385 | 0.77 / 0.86 | 0.88 / 0.87 | 92 / 16 / 4 | 0.82 |
| `bird` | bird | 56 / 69 | 0.721 | 0.673 [0.57, 0.78] | 0.720 | 0.0540 / 0.0468 | 0.85 / 0.49 | 0.98 / 0.61 | 35 / 5 / 8 | 0.73 |
| `horse` | horse | 52 / 76 | 0.896 | 0.917 [0.86, 0.96] | 0.896 | 0.0578 / 0.0629 | 0.93 / 0.72 | 0.96 / 0.57 | 45 / 14 / 0 | 0.76 |
| `cow` | cow | 42 / 45 | 0.851 | 0.821 [0.71, 0.90] | 0.850 | 0.0688 / 0.0593 | 0.83 / 0.67 | 0.86 / 0.67 | 35 / 1 / 0 | 0.97 |
| `sheep` | sheep | 38 / 27 | 0.938 | 0.930 [0.84, 0.99] | 0.937 | 0.0564 / 0.0600 | 0.85 / 0.85 | 0.96 / 0.85 | 24 / 3 / 0 | 0.89 |
| `food and drink` (top) | 10 categories | 345 / 363 | 0.630 | 0.706 [0.66, 0.76] | 0.631 | — / — | — / 0.00 | — / 0.00 | 0 / 0 / 0 | — |
| `sandwich` | sandwich | 42 / 56 | 0.670 | 0.628 [0.50, 0.75] | 0.670 | 0.0871 / 0.0926 | 0.83 / 0.18 | 1.00 / 0.11 | 6 / 6 / 0 | 0.50 |
| `pizza` | pizza | 73 / 80 | 0.881 | 0.865 [0.79, 0.93] | 0.881 | 0.0492 / 0.0475 | 0.86 / 0.81 | 0.94 / 0.82 | 69 / 7 / 1 | 0.90 |
| `dessert` ᵖ | cake; donut | 90 / 90 | 0.498 | 0.542 [0.44, 0.67] | 0.498 | — / — | — / 0.00 | — / 0.00 | 0 / 0 / 0 | — |
| `cake` | cake | 66 / 58 | 0.755 | 0.725 [0.60, 0.85] | 0.755 | 0.0630 / 0.0638 | 0.87 / 0.57 | 0.91 / 0.52 | 32 / 6 / 1 | 0.82 |
| `fruit` | banana; apple; orange | 101 / 99 | 0.727 | 0.723 [0.64, 0.80] | 0.724 | 0.0562 / 0.0530 | 0.95 / 0.41 | 0.88 / 0.45 | 42 / 1 / 9 | 0.81 |
| `vegetables` | broccoli; carrot | 61 / 64 | 0.647 | 0.654 [0.53, 0.76] | 0.646 | — / 0.0590 | — / 0.00 | 0.76 / 0.25 | 0 / 0 / 21 | 0.00 |
| `wine` ᵖ | wine glass | 49 / 61 | 0.451 | 0.442 [0.33, 0.56] | 0.450 | 0.0544 / 0.0564 | 0.78 / 0.23 | 1.00 / 0.20 | 12 / 6 / 0 | 0.67 |
| `houseplant` | potted plant | 91 / 81 | 0.212 | 0.173 [0.12, 0.27] | 0.212 | — / — | — / 0.00 | — / 0.00 | 0 / 0 / 0 | — |
| `transport` (top) | 8 categories | 575 / 585 | 0.770 | 0.754 [0.72, 0.79] | 0.770 | 0.0024 / 0.0105 | 0.87 / 0.37 | 0.85 / 0.45 | 208 / 44 / 106 | 0.58 |
| `car` | car | 267 / 268 | 0.440 | 0.408 [0.35, 0.47] | 0.440 | — / — | — / 0.00 | — / 0.00 | 0 / 0 / 0 | — |
| `bicycle` | bicycle | 74 / 75 | 0.474 | 0.472 [0.36, 0.58] | 0.474 | 0.0557 / 0.0572 | 0.85 / 0.29 | 0.79 / 0.31 | 23 / 3 / 6 | 0.72 |
| `motorcycle` | motorcycle | 79 / 80 | 0.729 | 0.721 [0.62, 0.81] | 0.730 | 0.0478 / 0.0505 | 0.88 / 0.62 | 0.89 / 0.64 | 53 / 4 / 4 | 0.87 |
| `bus` | bus | 102 / 87 | 0.831 | 0.811 [0.74, 0.88] | 0.832 | 0.0691 / 0.0717 | 0.86 / 0.69 | 0.89 / 0.66 | 61 / 9 / 3 | 0.84 |
| `train` | train | 69 / 88 | 0.947 | 0.936 [0.89, 0.97] | 0.947 | 0.0774 / 0.0809 | 0.93 / 0.78 | 0.96 / 0.76 | 66 / 8 / 4 | 0.85 |
| `boat` | boat | 70 / 51 | 0.859 | 0.839 [0.75, 0.92] | 0.860 | 0.0568 / 0.0490 | 0.97 / 0.59 | 0.95 / 0.73 | 31 / 0 / 8 | 0.79 |
| `airplane` | airplane | 49 / 48 | 0.914 | 0.936 [0.88, 0.98] | 0.915 | 0.0402 / 0.0390 | 0.73 / 0.92 | 0.75 / 0.92 | 56 / 4 / 3 | 0.89 |
| `truck` | truck | 141 / 109 | 0.486 | 0.465 [0.37, 0.55] | 0.485 | 0.0839 / 0.0815 | 1.00 / 0.07 | 1.00 / 0.09 | 7 / 1 / 3 | 0.64 |
| `computer screen` ᵖ | tv | 107 / 100 | 0.468 | 0.459 [0.36, 0.56] | 0.468 | 0.0863 / 0.0848 | 0.83 / 0.15 | 1.00 / 0.12 | 12 / 6 / 0 | 0.67 |
| `phone` | cell phone | 109 / 105 | 0.379 | 0.372 [0.29, 0.47] | 0.379 | 0.0677 / 0.0715 | 0.89 / 0.15 | 1.00 / 0.11 | 11 / 7 / 1 | 0.58 |
| `laptop` | laptop | 95 / 88 | 0.767 | 0.748 [0.66, 0.82] | 0.766 | 0.0647 / 0.0619 | 0.85 / 0.47 | 0.89 / 0.44 | 41 / 7 / 3 | 0.80 |
| `bag` | backpack; handbag; suitcase | 236 / 254 | 0.305 | 0.307 [0.25, 0.37] | 0.306 | 0.0670 / — | 0.79 / 0.06 | — / 0.00 | 0 / 19 / 0 | 0.00 |
| `furniture` | 5 categories | 601 / 636 | 0.447 | 0.456 [0.42, 0.50] | 0.447 | 0.0673 / 0.0690 | 0.82 / 0.05 | 0.77 / 0.04 | 23 / 16 / 8 | 0.49 |
| `book` | book | 114 / 116 | 0.173 | 0.150 [0.10, 0.23] | 0.175 | 0.0455 / 0.0363 | 0.75 / 0.05 | 0.55 / 0.05 | 8 / 0 / 3 | 0.73 |
| `skiing` ᵖ | skis | 62 / 58 | 0.884 | 0.917 [0.86, 0.96] | 0.885 | 0.1129 / 0.1157 | 1.00 / 0.36 | 1.00 / 0.29 | 17 / 4 / 0 | 0.81 |
| `snowboarding` ᵖ | snowboard | 24 / 25 | 0.628 | 0.622 [0.44, 0.78] | 0.629 | 0.1093 / 0.1111 | 0.90 / 0.36 | 0.90 / 0.36 | 9 / 1 / 1 | 0.82 |
| `surfing` ᵖ | surfboard | 82 / 67 | 0.947 | 0.954 [0.91, 0.99] | 0.948 | 0.0630 / 0.0567 | 0.93 / 0.93 | 0.92 / 0.90 | 64 / 3 / 1 | 0.94 |
| `tennis` ᵖ | tennis racket | 93 / 74 | 0.954 | 0.955 [0.91, 0.99] | 0.955 | 0.0702 / 0.0726 | 0.85 / 0.96 | 0.86 / 0.95 | 80 / 4 / 1 | 0.94 |
| `skateboarding` ᵖ | skateboard | 54 / 73 | 0.953 | 0.962 [0.92, 0.99] | 0.953 | 0.0795 / 0.0768 | 0.93 / 0.90 | 0.96 / 0.88 | 65 / 6 / 2 | 0.89 |

- **32 of 37 labels get a floor at 0.90** on each artifact, not the same 32: `bag` only on ONNX,
  `vegetables` only on Core ML. `food and drink`, `dessert`, `houseplant` and `car` get none on
  either. At 0.95 it is 25 (ONNX) and 28 (Core ML), and five labels have a floor on one side only.
  ⚠ A label with a floor on one platform and none on the other is written by one platform and never
  by the other; that is the Jaccard 0.00 in the last column.
- **The floors held roughly, not exactly.** Pooled over the labels with a floor, held-out precision
  is 0.879 (ONNX) and 0.894 (Core ML) against the target of 0.90, and 0.939 / 0.942 against 0.95.
  Per label, 22 of ONNX's 32 and 15 of Core ML's 32 landed below 0.90; for 3 and 4 of them the
  interval excludes 0.90. The rule takes the lowest threshold that touches the target, so it
  selects for a favourable calibration sample. A floor placed on the lower confidence bound would
  hold better and recall less.
- **Read the count before the number.** `snowboarding` has 25 held-out positives and 10 firing
  images: its precision 0.90 has an interval of [0.67, 1.00]. `people` has 1 355 positives and 852
  firing: 0.92, [0.90, 0.93].
- **Recall is modest.** The median label recalls 57 % at 0.90 (0.569 ONNX, 0.566 Core ML). Small or incidental objects are
  where it goes: `phone` 0.15, `truck` 0.07, `furniture` 0.05, `book` 0.05 (AP 0.15: the model does
  not separate images holding a book from the rest).
- **Best** (ONNX AP ≥ 0.9): `skateboarding` ᵖ, `tennis` ᵖ, `surfing` ᵖ, `train`, `airplane`,
  `animals`, `sheep`, `horse`, `skiing` ᵖ, `cat`. **Worst** (AP < 0.45): `book`, `houseplant`, `bag`,
  `phone`, `car`, `wine` ᵖ.

⚠⚠ **A floor's probability is nowhere near the band §3.7.2 names.** The floors run from cosine
−0.013 to 0.116, median about 0.06. Under the checkpoint's own sigmoid (§3.10.8) the median floor is
**p ≈ 0.002** on both (0.0016 ONNX, 0.0018 Core ML), 30 of ONNX's 32 floors are below p = 0.05, and
9 are below p = 0.001. `dog` reaches
precision 0.84 at p = 0.0007. SigLIP's sigmoid is a pair-matching score trained against a bias for
a very large batch; it is not the probability that a category is present. ⚠ Consequence for the
row: `MediaIndexer` stores `confidencePerMille = (confidence · 1000).toInt()`, which for a
probability at the median floor is 1 of 1 000, and 0 for those nine labels.

#### 3.10.4 One threshold table for both artifacts: no

Each shipped artifact's held-out scores under the **other** one's floors, against its own:

| Label | Core ML, own floors P / R | Core ML under ONNX floors | ONNX, own floors P / R | ONNX under Core ML floors |
|---|---|---|---|---|
| `people` | 0.92 / 0.57 | 0.98 / **0.28** | 0.92 / 0.58 | **0.76** / 0.84 |
| `transport` | 0.85 / 0.45 | 0.81 / 0.58 | 0.87 / 0.37 | 0.94 / 0.25 |
| `boat` | 0.95 / 0.73 | 0.97 / 0.61 | 0.97 / 0.59 | 0.85 / 0.76 |
| `book` | 0.55 / 0.05 | 0.57 / 0.03 | 0.75 / 0.05 | 0.46 / 0.05 |
| `sandwich` | 1.00 / 0.11 | 0.88 / 0.12 | 0.83 / 0.18 | 0.89 / 0.14 |
| `animals` | 0.87 / 0.88 | 0.88 / 0.87 | 0.87 / 0.87 | 0.86 / 0.88 |

Of the 31 labels with a floor on both sides, borrowing the other table moves precision by 0.05 or
more on 5 (Core ML borrowing) and 8 (ONNX borrowing), and recall by 0.05 or more on 6 and 7. Most
labels barely move (`animals`); a few move a great deal, and `people`, the most common label of
all, is the worst: iOS under Android's table would label half as many people photographs.

The cause is a per-label **offset**. ONNX's score minus Core ML's, over the same images, has a mean
between −0.017 (`car`) and +0.023 (`people`, `skiing`) depending on the label. That is the int8
text tower moving each label's vector by its own amount, and a per-artifact floor absorbs it.

#### 3.10.5 With per-artifact floors: the answer to D13's open half

Every (image, label) cell of the held-out half, 2 500 images × 37 labels, each artifact under its
own floors, target 0.90:

| Pair | both fire | only the first | only the second | neither | cell agreement | Jaccard | disagreements within 0.005 / 0.01 of a floor |
|---|---|---|---|---|---|---|---|
| **ONNX vs Core ML** | 2 519 | 368 | 327 | 89 286 | 99.25 % | **0.784** | 74 % / 94 % |
| ONNX vs fp32 | 2 528 | 359 | 336 | 89 277 | 99.25 % | 0.784 | 75 % / 94 % |
| Core ML vs fp32 | 2 838 | 8 | 26 | 89 628 | 99.96 % | **0.988** | 100 % / 100 % |
| ONNX vs Core ML, both under ONNX's floors | 2 202 | 685 | 290 | 89 323 | 98.95 % | 0.693 | 57 % / 85 % |
| ONNX vs Core ML, target 0.95 | 1 853 | 328 | 291 | 90 028 | 99.33 % | 0.750 | 71 % / 93 % |

⚠⚠ **Cell agreement is the wrong number to quote.** 96.5 % of cells are *neither fires*, so it
reads 99 % whatever the artifacts do. **Jaccard** is the share of written labels both sides wrote:
of 3 214 labels either platform would write, 2 519 are written by both and **695 (22 %) by one
only**.

- **Per-artifact floors help and do not close it**: 0.693 → 0.784. They remove the offset of
  §3.10.4. What is left is per-image noise: the standard deviation of ONNX's score minus Core
  ML's is about **0.0076** per label (0.007 to 0.012), against 0.0002 for Core ML minus fp32. The
  gap between a label's typical positive and typical negative is some 0.07 (`dog`: medians 0.067
  and −0.005), so a tenth of that separation is noise, and the images near a floor are many.
- **The disagreement is at the threshold**: 94 % of it is within 0.01 cosine of a floor, 74 %
  within 0.005. These are not confident labels flipping. They are also not removable by choosing
  thresholds: any floor has images beside it.
- **The fp16 Core ML pair is fp32 for this purpose** (0.988). The question D13 asked about two
  precisions is a question about the ONNX pair's quantisation. Both of its towers contribute about
  equally: with only its vision tower quantised (fp32 text) it agrees with fp32 on 0.843, with only
  its text tower on 0.842, with both on 0.784.
- Per label it runs from 0.94 – 0.97 (`surfing` ᵖ, `tennis` ᵖ, `cow`) down to 0.58 (`transport`,
  `phone`), 0.50 (`sandwich`, `furniture`) and 0.00 (`bag`, `vegetables`: a floor on one side only).

**So for D13: per-artifact thresholds do not make the two platforms' labels interchangeable.**
About one written label in five would differ between an Android device and an iPhone indexing the
same photograph, and whichever device wrote the row, the other would have written something else
for it at that rate. ⭐ **Decided 2026-10-11** (`inference-service.md` §39.1): the first, with
*the first writer's labels stand* as the merge rule; the second and third are rejected there. The
three ways out, as this measurement left them:

1. **The stamp tells them apart** (D13's stated alternative), and a merge rule says what a
   photograph's labels are when two platforms indexed it.
2. **The row tolerates it**: label rows from any platform are one set, and a search or a collection
   reads the union. ⚠ Then precision is that of the union, which no floor here was chosen for.
3. **Remove the cause**: an ONNX pair that sits where the Core ML pair does. §3.10.6 suggests the
   distance is not fixed even for the bytes already published.

#### 3.10.6 ⚠⚠ The same ONNX bytes under another onnxruntime differ as much as the two platforms do

The retrieval gate found onnxruntime 1.29 and 1.20.0 rank differently on the published pair. Here
the same pixels and the same token rows went through the same two files on **1.29.0** (`embed
--reuse-inputs --tag ort1.29.0`).

⚠⚠ **This table is an off-pin comparison, not part of the pinned measurement.** Every other number
in §3.10 comes from the three artifacts embedded on the pinned toolchain. The 1.29.0 vectors were
embedded with `--allow-unpinned` in a second environment, and the result records what it departs
from: onnxruntime 1.29.0 (pinned 1.20.0), and also numpy 2.5.2 (2.2.6), torch 2.13.0, transformers
5.16.1 and tokenizers 0.23.1, with no coremltools or sentencepiece. Of those only onnxruntime and
numpy ran: the pixels and the token rows are the pinned run's own, checked by digest, so no decoder,
resampler or tokenizer of that environment touched an input, and numpy's part is the float32
arithmetic that scales pixels and the normalisation of the output vectors. ⚠ That the difference is
onnxruntime's is therefore an inference from what ran, not a measurement that varied onnxruntime
alone. `evaluate` now heads such a table OFF-PIN VARIANT and ends `MEASURED; 1 off-pin variant:
onnx-ort1.29.0`.

| Compared on the held-out half, own floors, target 0.90 | Jaccard |
|---|---|
| ONNX on 1.20.0 vs ONNX on 1.29.0 | **0.829** |
| ONNX on 1.20.0 vs ONNX on 1.29.0, both under 1.20.0's floors | 0.706 |
| ONNX on 1.29.0 vs fp32 | 0.852 |
| ONNX on 1.29.0 vs Core ML | 0.854 |
| ONNX on 1.20.0 vs Core ML (§3.10.5) | 0.784 |

`people` again shows it plainly: its mean score moves by −0.027 between the runtimes and its floor
from 0.0081 to −0.0180, next to Core ML's −0.0128. Under 1.20.0's floors, 1.29.0 would recall 24 %
of people photographs in place of 57 %.

⚠⚠ **A threshold table is keyed tighter than R4 says.** R4 keys calibration to the artifact
because re-quantising moves thresholds. The runtime that executes the quantised kernels moves them
too: an `onnxRuntime` bump in `libs.versions.toml` invalidates a calibration file without changing
one byte of the model. ⚠ It follows that this host run does not vouch for a phone:
`onnxruntime-android` 1.20.0 is the same version with other kernels, and nothing here ran on one.

#### 3.10.7 The full selection rule, and the other sensitivities

**The rule end to end.** `selectLabels` ported line for line (`select_labels`; `test_label_eval.py`
runs `LabelSelectionTest.kt`'s cases against it), over the 37 mapped labels with each artifact's
own floors. A mapped leaf's unmapped parent (`objects`, `plants`, `activities`, `documents and
screens`) is in the vocabulary with a floor of +∞, since an uncalibrated label cannot be expressed.
Margin 0.01 cosine, `EmbeddingHarness`'s value:

| Pair | label sets identical | identical where either wrote a label | mean set Jaccard where either wrote a label |
|---|---|---|---|
| **ONNX vs Core ML** | **78.3 %** | **71.9 %** | 0.807 |
| ONNX vs fp32 | 78.5 % | 72.3 % | 0.808 |
| Core ML vs fp32 | 99.0 % | 98.6 % | 0.990 |

A margin of 0 or 0.02 moves these by less than a point. Against truth the rule trades a little
recall for precision: ONNX 0.906 / 0.399 (micro precision / recall) under the rule, 0.879 / 0.408
with independent floors. Two things it does on COCO that it would not on a library of single-subject
photographs: a sibling set writes **one** leaf, so a photograph of a dog and a cat is labelled with
one of them (`dog` recall 0.72 → 0.65), and the margin test almost never decides anything (13 of
2 500 images under `food and drink`, 12 under `animals`): the floor does the work.

**Prompts.** Held-out AP, mean over the 37 labels:

| | TSV prompt | bare label word | `This is a photo of <label>.` |
|---|---|---|---|
| fp32 | 0.697 | **0.724** | 0.705 |
| ONNX | 0.692 | **0.716** | 0.698 |
| Core ML | 0.697 | **0.724** | 0.705 |

⚠ **The candidate file's hand-written prompts lose to the bare word on 30 of 37 labels** (fp32;
29 on ONNX). `book` 0.17 → 0.25, `snowboarding` 0.63 → 0.73, `cake` 0.76 → 0.82. The file's header
argues that spelling prompts out per label makes them reviewable; on object labels the spelling
itself costs AP. ⚠ Only on object labels, and only AP: the floors were not re-derived per variant.
(Re-derived since, under the bare word: §3.11.4.)

**Ground truth.** `noCrowd` is identical to `any` on every label. `notTiny` (an instance must cover
1 % of the image) removes more than half the held-out positives of `bag`, `book`, `phone`, `wine` ᵖ
and four activity proxies, and the proxies' AP collapses with it (`tennis` ᵖ 0.96 → 0.37): the
label is recognising the scene, and the racket is small. For `phone` it rises, 0.37 → 0.64. The
presence rule decides what "the model missed it" means, and for small objects most of the low
recall in §3.10.3 is objects of a few dozen pixels.

**Preprocessing: a limit, not a measurement.** Every image here is Pillow's bicubic squash. Android
decodes with `Bitmap`, desktop with AWT, iOS with ImageIO and vImage, each with its own resampler.
§3.10.5's noise scale (0.0076) is the size of thing a resampler could add or remove, and nothing
here says which.

#### 3.10.8 ⚠⚠ `VisionModel`'s score calibration was the other checkpoint's (corrected 2026-10-11)

Until 2026-10-11 `VisionModel.PinnedSigLip.calibration` was `ScoreCalibration(117.330765,
−12.932437)`: the constants §3.7.2 read out of the **`-224`** graph, eleven days before §3.8 pinned
`-256-multilingual`. It now carries the pinned checkpoint's own, read from the `model.safetensors`
the catalog pins (`google/siglip-base-patch16-256-multilingual` at revision `8952a4ea…`, sha256
`00df88d8…`): `logit_scale = 4.709494` (bytes `2d b4 96 40`), so **`exp` = 110.995995**, and
**`logit_bias` = −12.885268** (bytes `0f 2a 4e c1`). p = 0.5 is at cosine 0.116, not 0.110.
`InferenceBackend.kt` already said why it matters: *"two checkpoints have two calibrations, and a
constant carried by the consumer would silently describe whichever model it was written against."*

⚠⚠ **The correction changed no search result, by construction.** The only reader of the calibration
is `photosLike`'s `SEMANTIC_RELEVANCE_FLOOR`. `p ≥ 0.10` under the old constants was cosine ≥
**0.09149529** (the smallest admitted `Float`), which the pinned checkpoint scores at
**p ≈ 0.0612459** — so the floor became `0.061245844` in the same change, and the cut is on the same
`Float`. `SemanticFloorCarryOverTest` holds the two pairs against each other over 2,000,001 cosines
and every `Float` for 65,536 either side of the cut.

⚠ What "no result" rests on. The old pair scored the cut cosine at **exactly** its floor, to the
bit, so its cut hung on the last bit of `exp`; the new floor sits 8 ulps inside the gap it
separates and does not. The comparison ran on the JVM (the arithmetic Android and desktop use:
IEEE `Float` multiply and add around a double `exp`) and on the iOS simulator (`expf`). It did not
run on a phone. Were a device's `expf` to round that one value the other way, the old build would
have rejected the single `Float` `0x3DBB61E2` and the new one admits it.

⚠⚠ **So the floor is a carried-over cosine and not a measurement.** `0.10` was the middle of
§3.7.2's band, and that band was read on the `-224` checkpoint. A true p ≥ 0.10 on the pinned model
is cosine ≥ 0.0963. Re-measuring the floor is owed (§3.10.10 item 5; `tech-debt.md`).

⚠ **No gate can read the weights.** `PinnedSigLipCalibrationPinTest` holds the two literals beside
the repository, revision and digest they were read from, and `PinnedSigLipCalibrationSourceTest`
holds that provenance to `deploy/models/models.json`. The check that reads the bytes is
`label-eval.py evaluate --source <checkpoint>`: it refuses weights with another digest, refuses
constants the weights do not hold, and its own tests refuse a `VisionModel.kt` that states another
pair. ⚠ The catalog's ONNX entry records no source, so the binding of *those* artifacts to this
revision rests on the Core ML entry's.

**And what the rule's "floor" is.** `selectLabels` compares `ImageTag.confidence >= floor` and
`winner − runnerUp >= margin` and never asks what unit the numbers are in. Nothing fixes it in
code, because no production backend returns a tag. The KDoc on `VocabularyLabel` and the
`confidencePerMille` column say probability; the only numbers the rule has ever been fed, in
`LabelSelectionTest` (`indoors 0.061`, `staircase −0.009`), are **cosines** (a probability is not
negative), as is `EmbeddingHarness.MARGIN = 0.01`. For a floor the two are interchangeable, the
sigmoid being monotone. For the **margin** they are not: 0.01 of cosine is a factor of about 3 in odds
wherever it sits, and 0.01 of probability is nothing at p = 0.002 and everything at p = 0.5. This
evaluation used cosines throughout. ⭐ **Decided 2026-10-11: the cosine**, for the floor, the
margin and the stored column (`inference-service.md` §39.2); at the probabilities of §3.10.3 the
per-mille column argued for it.

#### 3.10.9 What this does not establish

- ⚠⚠ **Nothing about 170 of the 207 labels**: no scene, document, screenshot, event, weather or
  artwork label, and no top-tier label except the four mapped. Not their floors, not whether the
  two platforms agree on them. (§3.12 scores 120 of the 170 against Open Images.)
- ⚠⚠ **No firing rate on a library.** The only label over 25 % here is `people` (34 % of images
  fire; 54 % of val2017 holds a person), and a floor chosen for precision cannot over-fire on the
  corpus it was chosen on. §3.7.4's `indoors` at 55 – 60 % is untouched by this: `indoors` is not
  scored, and a COCO floor says nothing about how often a label fires on screenshots and receipts.
  A first look at a camera roll, with these floors as an instrument and no truth: §3.11.
- **Not a phone.** Host onnxruntime and host Core ML. §3.10.6 is the reason that matters for ONNX.
  The retrieval gate's `--phone-vectors` spot-check is the existing bridge for Core ML; it was not
  run over this corpus.
- **Not the apps' pixels** (§3.10.7).
- **English prompts only**, and one corpus. COCO's categories are objects in everyday scenes
  photographed by Flickr users before 2015; the floors are floors for that.
- **No floor here is a recommendation.** They are the instrument for the agreement question. §3.10.3
  shows the rule that placed them is optimistic.
- **Proxy labels** measure the proxy.

#### 3.10.10 The next measurement

1. ✅ **D13's open half is decided on §3.10.5** (2026-10-11, `inference-service.md` §39.1): the
   first writer wins, each artifact under its own floors. What that owes is listed in §39.4.
2. **An ONNX pair that sits closer to fp32**, measured by this harness: the vision tower at fp16 or
   with the sensitive layers excluded (§3.7.9's unexplained 0.979), and the text tower likewise.
   §3.10.5 says both towers matter equally. Target: the Core ML pair's 0.99. ⚠ Not planned: §39.1
   rejected moving Android to such a pair, on a download and indexing cost nobody has measured.
3. **The same evaluation on a phone's vectors.** `evaluate` reads vector files and needs no model,
   and the IN-3m harnesses already write vectors on both phones; what is missing is the converter
   and the label text vectors from the device. That answers §3.10.6 for `onnxruntime-android` and
   §3.10.7 for the decoders in one run.
4. ◐ **A labelled sample of the 170.** §3.12 scores 156 labels against Open Images V7, 120 of them
   new. ⚠ Still owed: the 51 it cannot score (`receipt`, `qr code`, `landscape` among them), real
   screenshots and documents (§3.12.9), and a sample large enough to confirm a bar and not only
   to refute one (§3.12.6; ◐ selected for L5's 23 and not yet embedded, §3.13). `inference-service.md` §7.7's labelled sample of a *library* is
   untouched by it.
5. **Re-measure `SEMANTIC_RELEVANCE_FLOOR`** on the pinned checkpoint. Its constants are corrected
   (§3.10.8); the floor's cosine was carried over unchanged and has never been measured on it.
6. ✅ **The floors under the bare-word prompt** (`inference-service.md` §39.4 item 11): re-derived
   from the saved vectors, §3.11.4. 29 labels get a floor on both artifacts, not the 31 of §3.10.3.
7. ◐ **A firing rate on a real library** (§39.4 item 15): §3.11.5 is a first look at 554 camera
   photographs, ⚠ under COCO's floors and with no truth. It is not the rate a shipped table would
   give.

Raw results (not in the repository): `~/tenshu-models/label-eval-2026-10-10/` on the maintainer's
Mac. `label-eval-result.json` sha256 `3ddf64fc…` records every digest above, the toolchain and the
seed. ⚠ It is a format-1 result, written before the harness stated a status: it has no `overridden`
or `offPinVariants` field, and its log ends in a bare `MEASURED`. Its content is what the current
rule would describe as *not overridden, one off-pin variant (`onnx-ort1.29.0`)*: the three base
entries are on every pin, and the variant's entry records `pinned: false` and its versions. It was
not rewritten.

⚠ **Its quantile summaries predate a fix, and three figures above were corrected for it
(2026-10-10).** Every median and quantile here is a *nearest-rank* one, an observed value; the
harness took the observation after the right one wherever `q·n` was a whole number (the upper of
the two middle values for an even count). In the saved file that is: 264 of the 1 554 values under
`perLabel.<artifact>.<label>.distribution` (the 5, 25, 50, 75 and 95 % points; the largest is off
by 0.003 cosine), 157 of the 297 `agreement.….nearThreshold.medianDistance` values, and
`summary.floors.<target>.heldOut.<artifact>.medianRecall` (0.576 → 0.569 for ONNX at 0.90, 0.5675 →
0.566 for Core ML). Recomputed from the saved vectors, the figures quoted in §3.10 that moved are
the median floor's probability (ONNX 0.003 → 0.002, taken by hand the same wrong way: cosine 0.063
→ 0.058) and the per-mille value at it (2 → 1); "57 %" and "about 0.06" stand at their precision,
and `dog`'s medians do not move (odd counts). ⚠ **Nothing else depends on it**: no AP, floor,
precision, recall, Jaccard, agreement count, near-threshold share or label set is a quantile, and
the bootstrap intervals are NumPy's percentiles, not this function.

Beside it, `scores-{fp32,onnx,coreml}.npz` hold every image's cosine for all 207 labels and three prompt
variants; `label-sets-held-out.json` the label sets of §3.10.7.

### 3.11 Every label on a camera roll, no ground truth: 554 phone photographs (2026-10-11)

§3.10 scored 37 labels on web photographs. This asks what needs no truth, of all 207 labels, on
the one real library at hand: how far the three artifacts' scores sit apart per label, which
label wins, and what COCO's floors would write there. It also re-derives those floors under the
bare-word prompt that `inference-service.md` §39.3 decided, from the vectors §3.10 saved.

**What it found.** The ONNX pair's distance from the other two is the same thing on a camera roll
as on COCO: a per-label offset and a per-image spread of about 0.007 to 0.009 cosine on every one
of the 207 labels, against 0.0003 to 0.0004 for Core ML against fp32. The bare word halves the
offsets and leaves the spread. Under COCO's floors the two shipped artifacts write the identical
label set for about 70 to 75 % of the photographs either labels (72 to 78 % on COCO), and Core ML
differs from fp32 on fewer than ten photographs.
No label COCO could floor fires on a quarter of this library except `people`.

⚠⚠ **Nothing here is a precision, a recall or a calibrated rate.** There is no truth for these
photographs. A floor applied below is a COCO floor on another kind of image: an instrument.

Reproduce: `label-eval.py manifest --truth none`, `embed --threads 4`, `survey --floors …`; for
§3.11.4 `evaluate --prompt bare`. Tests: `test_label_eval.py` (`Survey`, `SurveyArithmetic`).

#### 3.11.1 What was run, on what

| | |
|---|---|
| Corpus | the 554 camera JPEGs of the 2026-10-07 IN-3m corpus (§3.5.4), from the maintainer's phone. ⚠ Personal photographs: read in place, copied nowhere |
| Pixels | Pillow 12.3.0 bicubic squash to 256², §3.10's rule. ⚠ **Not the apps' decoders.** All 554 files carry EXIF orientation 1, so the rule's ignoring of orientation costs nothing here |
| Artifacts | the three of §3.10.1, same bytes, same pinned toolchain |
| Texts | the same 621 token rows (`931173e9…`). The ONNX and Core ML text vectors are bit-identical to §3.10's; fp32's differ by at most 1.2 × 10⁻⁷ |
| Threads | fp32 and ONNX capped at 4 (`embed --threads 4`, recorded per vector file), under `nice`, because the machine was running other work. ⚠ Core ML takes no cap |
| Floors | from §3.10's vectors, re-evaluated by this version: target 0.90, split seed 20261010 |

**What `survey` writes, and what it never writes.** Aggregates over the corpus: per label, each
artifact's score quantiles (5 % to 95 %; no minimum or maximum, each of which is one photograph's
score), each pair's offset and spread, and counts of images. ⚠ **A count between 1 and 9, or
within 9 of all 554, is not written**; the result says *suppressed*. Nor is a share that would
give such a count back. The cells of a pair of artifacts (both wrote, only one wrote) and their
Jaccard are written together, pooled over the labels and for no single label, and only when
every label's own cells could be written; otherwise nothing pooled is, and nothing stands in for
it (a bound and a rounded share were each tried in review, and each said something by being
there). The shares of identical label sets are written together or not at all. A quantile is
written only with ten photographs at or below it and ten at or above. Where a group of counts
has a total anyone can work out (every photograph has one top label) and the total would pin a
withheld count down, the whole group is withheld. A corpus of fewer than twenty images is
refused. ⚠ **And a count that is written is rounded to the nearest ten**, with every share taken
of rounded counts: two exact counts subtract to a third, and no rule on each figure alone stops
that. So every share of the 554 below is *about*, good to some two points. No file name,
no per-image score and no image's label set is in the result. ⚠ The rule is not a proof that no
two written figures subtract to a small one (`run_survey` says which it knows of): a result is
to quote from, not to publish whole. The vectors `embed` wrote are per image and stay beside the corpus,
outside the repository, as does the manifest, which holds its file names.

`evaluate` is unchanged for a corpus like this one: it refuses a manifest that says `truth=none`,
and `survey` refuses one that does not.

#### 3.11.2 How far apart the artifacts score, for all 207 labels

ONNX's cosine minus Core ML's, over the same 554 photographs. The **offset** is its mean, which a
per-artifact floor absorbs; the **spread** is its standard deviation, which no floor does.

| Prompt | median absolute offset | offset, lowest … highest label | median spread | spread, lowest … highest label |
|---|---|---|---|---|
| candidate file's prompt | 0.0074 | −0.018 … +0.030 | 0.0087 | 0.0069 … 0.0181 |
| **bare word** | **0.0028** | −0.010 … +0.022 | **0.0073** | 0.0058 … 0.0166 |
| Core ML − fp32, either prompt | 0.0001 | −0.0005 … +0.0004 | 0.0003 to 0.0004 | 0.0003 … 0.0005 |

- **Every label has it.** The smallest spread of any of the 207 is 0.0058: no label, scored or not,
  is one the two platforms would score alike. §3.10.5 measured 0.0076 on COCO for 37 labels; this
  is the same quantity for the other 170.
- **The bare word helps the offset, not the spread**: the median absolute offset falls from 0.0074
  to 0.0028. The offset is the int8 text tower moving a label's vector (§3.10.4), and a one-word
  prompt gives it less to move. ⚠ Inferred from the two prompts; no tower was varied here.
- **Largest spread, bare word**: `parade` 0.0166, `canyon` 0.0126, `screenshot` 0.0121, `tool`,
  `indoors`, `storm` (0.0104 to 0.0100). **Largest offset**: `screenshot` +0.022, `canyon` +0.019,
  `parade` +0.018, `person` +0.012, `qr code` +0.012, `people` +0.011. **Smallest spread**: `baby`
  0.0058, `family`, `wine`, `moon`, `cocktail` (0.0060 to 0.0061).
- **Against the label's own range.** The spread divided by the standard deviation of the label's
  fp32 score across the library has a median of 0.33 (bare word; 0.36 under the file's prompts),
  from 0.19 (`mountain`) to 0.52 (`indoors`). For `indoors`, half of what separates one photograph
  from another on this library is the artifact.
- ⚠ `documents and screens`, `screenshot` and `logo` are among the least stable and are labels
  Tenshu wants most. Nothing here says they are *wrong* on either artifact; there is no truth.

#### 3.11.3 Which label wins

§3.7.3 found the quantised vision tower changes an image's top label on one image in seven (98
images, the `-224` checkpoint, our own export). On this library, the published pair against Core
ML, over all 207 labels:

| Prompt | top-1 label differs, ONNX vs Core ML | the top-3 set differs | Core ML vs fp32, either |
|---|---|---|---|
| candidate file's prompt | **about 32 %** of 554 | about 65 % | fewer than 10 images |
| bare word | **about 24 %** | about 56 % | fewer than 10 images |

⚠ Top-1 over 207 labels is a harsher test than a floor: most of these are near-ties among labels
nobody would write. It is the reason §19.3 budgets for flips, restated on a real library and both
towers quantised.

**`indoors` (§3.7.4, §28).** In an image's top three for about 31 % of the library at fp32 under
the file's prompt, 24 % on ONNX, 31 % on Core ML; §3.7.4 read 55 to 60 % on 98 images heavy with
screenshots. ⚠ **Under the bare word it is about 5 %** (fp32 and Core ML; fewer than 10 images on
ONNX). The prompt *"This is a photo taken indoors."* is what made `indoors` common, as much as the
label. Under the bare word the most frequent top-three label is `parade` (about 13 %), then `toy`
(about 11 % on ONNX, 9 % on Core ML): a top-three place is the least rejected label, not a claim.
35 to 38 of the 207 labels never reach a top three here under the bare word (48 under the file's
prompts; §3.7.4 had 158 on its 98 images).

#### 3.11.4 COCO's floors under the bare-word prompt (`inference-service.md` §39.4, item 11)

§39.3 decided the bare word and noted the floors had been placed under the other prompt. The same
vectors, `evaluate --prompt bare`, held-out half, target 0.90:

| | candidate file's prompt (§3.10.3) | bare word |
|---|---|---|
| Labels with a floor, ONNX / Core ML | 32 / 32 | **31 / 29** |
| On both shipped artifacts | 31 | **29** |
| On one only | `bag` (ONNX), `vegetables` (Core ML) | `bag`, `sandwich` (ONNX) |
| On neither | `food and drink`, `dessert`, `houseplant`, `car` | those four, `vegetables`, `computer screen` ᵖ |
| Pooled held-out precision, ONNX / Core ML | 0.879 / 0.894 | 0.881 / 0.884 |
| Labels below 0.90 held out, ONNX / Core ML | 22 of 32 / 15 of 32 | 21 of 31 / 16 of 29 |
| Median recall, ONNX / Core ML | 0.569 / 0.566 | **0.616 / 0.686** |
| ONNX vs Core ML, Jaccard, own floors | 0.784 | **0.842** |
| Label sets identical under the full rule | 78.3 % | 83.3 % |
| At target 0.95: labels with a floor | 25 / 28 | 27 / 26 |

- **The set for the first vocabulary, as §39.3 words it, is 29**: §3.10.3's 31 without `sandwich`
  (a floor on ONNX only) and `computer screen` ᵖ (on neither). Six of the 29 are proxies (`wine`,
  `skiing`, `snowboarding`, `surfing`, `tennis`, `skateboarding`); without them it is 23.
- **The bare word buys recall and agreement at the same precision.** That is the lower offsets of
  §3.11.2 arriving at the floors: fewer photographs sit between the two artifacts' cuts.
- **A floor moves with the prompt** by a median of 0.007 cosine (ONNX; 0.006 Core ML), from −0.017
  to +0.028. `people` goes from 0.0081 / −0.0128 to −0.0089 / −0.0186; `transport` from 0.0024 /
  0.0105 to 0.0299 / 0.0354. ⚠ A floor belongs to its prompt as it does to its artifact and
  runtime (§3.10.6): `survey` refuses a floors file that does not say which prompt it used.
- ⚠ **"A floor could be placed" is still not "held its precision".** Held out, 9 of the 29 are at
  0.90 or above on both artifacts as a point estimate (`people`, `horse`, `cake`, `fruit`, `train`,
  `truck`, `wine` ᵖ, `skiing` ᵖ, `skateboarding` ᵖ), and 3 have a bootstrap lower bound there on
  both (`truck`, `wine` ᵖ, `skateboarding` ᵖ). §39.3's first and second owed questions stand.

#### 3.11.5 COCO's floors on the camera roll

⚠⚠ **An instrument.** Floors placed on COCO for precision 0.90 on COCO, applied to camera
photographs with no truth. What can be read is how often each artifact would write a label and
whether they would write the same one; not whether either is right.

The full selection rule over the floored labels, margin 0.01:

| Floors | label sets identical, ONNX vs Core ML | identical where either wrote a label | mean set Jaccard where either wrote |
|---|---|---|---|
| file's prompt (32 / 32 labels) | about 86 % | **about 70 %** | 0.75 |
| **bare word (31 / 29)** | about 89 % | **about 75 %** | 0.79 |
| the same on COCO's held-out half, file's prompt / bare word | 78.3 % / 83.3 % | 71.9 % / 77.9 % | 0.807 / 0.850 |
| Core ML vs fp32, either prompt | withheld | withheld | withheld |

⚠ No pooled Jaccard over (photograph, label) pairs, which is §3.10.5's figure: most labels here
fire on a handful of photographs, and §3.11.1's rule writes pooled cells only when every label's
own could be written. ⚠ Core ML against fp32 is withheld because so few photographs differ: the
two write another top label, and another label set, on fewer than ten of the 554.

- **The disagreement of §3.10.5 is not a property of COCO.** Of the photographs either artifact
  labels, about 70 to 75 % get the identical set from both here, against 72 to 78 % there, under floors
  that were not placed for these photographs.
- **Firing rates** (share of 554; fp32 / ONNX / Core ML; bare word): `people` about 24 % / 22 % /
  24 %; `animals` about 14 % on each; `cat` about 7 % on each; `transport` and `bird` about 2 %.
  Every other floored label fires on fewer than ten photographs or none. Under the file's prompt
  `people` is about 25 % on each artifact, and on ONNX it is over §28's 25 % flag.
- About 40 to 42 % of the photographs get at least one of these labels (bare word).
- ⚠ **This does not answer §28.** The labels that could fire on everything (`indoors`, `landscape`,
  `urban`, `objects`) have no floor to fire under. §3.11.3's top-three share is the only reading of
  them here.

#### 3.11.6 What this does not establish

- ⚠⚠ **Nothing about correctness.** No photograph here has a label attached.
- ⚠⚠ **One person's library, 554 photographs.** Their mix decides every rate above. Another
  library would give others.
- **Not a phone, and not the apps' pixels.** Host onnxruntime and host Core ML over Pillow's
  resize, as §3.10. The same photographs were embedded on an iPhone (§3.5.4); those vectors were
  not compared here.
- **COCO's floors are COCO's.** §3.10.9: no floor there is a recommendation. Applied to another
  kind of image they are less of one.
- **English prompts only.**
- **The thread cap was not varied.** The text vectors show what it did to the texts (nothing on
  the two shipped artifacts, one unit in the seventh place on fp32). The image vectors of this
  corpus have no uncapped run to compare with.

Raw results (not in the repository): `~/tenshu-models/label-eval-wide-2026-10-11/` on the
maintainer's Mac. `phone/survey/label-survey-result.json` sha256 `7cd1d236…`;
`coco-bare/label-eval-result.json` `5992a47f…` and `coco-tsv/label-eval-result.json` `a5d4dd1f…`
(§3.10's vectors under each prompt, format 3). ⚠ `coco-tsv` is §3.10 again and not a new
measurement: every figure in it equals what `master`'s script gives for the same vectors, compared
field by field. Both were written after §3.10.8's constants were corrected, so the probability
beside each floor is the pinned checkpoint's under either name; no figure of §3.11 is a
probability.

### 3.12 Labels against a wider ground truth: Open Images V7, 156 labels (2026-10-12)

§3.10 could score 37 of the 207 candidate labels; scenes, events, weather, documents and art had
no COCO category. Open Images V7 has human-verified image-level labels for some 20 000 classes,
present **and absent**, and its images are listed CC BY 2.0. This scores **156 labels** against
it, on 25 803 images, with all three artifacts, floors placed under the bare-word prompt
(`inference-service.md` §39.3) and, for comparison, under the candidate file's.

**What it found.**

- **A floor can be placed for about two labels in three, and few of them hold it.** At target
  precision 0.90, bare word: a floor on ONNX for 109 labels, on Core ML for 110, on **both for
  100**. Held out, **25** of those are at 0.90 or above on both artifacts as a point estimate,
  **4** by the bootstrap's lower bound, and **1** by a Wilson lower bound, which is a proxy
  (§3.12.6). The held-out halves hold some 50 positives a label: most of that gap is sample size,
  and the rest is the floor rule's optimism that §3.10.3 already showed.
- **The two shipped artifacts agree less here than on COCO.** Each under its own floors, they
  write the same label for **0.54** of the (image, label) pairs either writes (COCO: 0.84 under
  the bare word). Over the 100 labels with a floor on both it is 0.68; 19 labels have a floor on
  one artifact only and are written by one platform and never by the other. Core ML against fp32
  is 0.96 (§3.12.7).
- **A floor does not travel between datasets.** For the labels scored on both, Open Images'
  floor is higher than COCO's by a median of 0.011 cosine, and by more than 0.02 for 7 of 26.
  COCO's floors applied to Open Images' held-out half give a pooled precision of **0.77**, not
  0.86; Open Images' floors on COCO give 0.93 at two thirds of the recall (§3.12.8).
- **`indoors`, scored by proxy, gets no floor on either artifact** (AP 0.53 to 0.58 against a base
  rate of 0.52). Nor do `food and drink`, `documents and screens`, `art and media` or
  `sky and weather` among the top-tier labels.

⚠⚠ **Every precision here is over a label's sample of some 100 verified positives and 100
verified, hard negatives** (§3.12.4). It is not a precision on a library, in either direction.
⚠⚠ **51 labels are still unscored**, among them `receipt`, `qr code` and `landscape`.

Reproduce: `label-eval.py select`, `manifest --truth open-images`, `embed --threads 4`, `evaluate
--truth open-images --selection … --prompt bare`; mapping `label-eval-open-images-mapping.tsv`, corpus manifest
`label-eval-open-images-corpus.txt`; tests `test_label_eval.py` (`OpenImagesTruth`,
`MaskedMetrics`, `Select`, `OpenImagesEndToEnd`).

| | |
|---|---|
| Artifacts, texts, toolchain | §3.10.1's and §3.11.1's: the same bytes, the same 621 token rows, the pinned toolchain; fp32 and ONNX capped at 4 threads under `nice` |
| Corpus | 25 803 images; manifest id `eb825cd3…`, sha256 `30c40843…`; pixels `a8b3e206…` (Pillow 12.3.0 bicubic squash to 256²) |
| Annotations | `open-images-labels.csv` sha256 `e8d362e4…` (440 872 rows), an extract of `oidv7-val-annotations-human-imagelabels.csv` `92ddbdfc…` and `oidv7-test-annotations-human-imagelabels.csv` `917d3ca8…`; classes `oidv7-class-descriptions.csv` `84a4373a…`; selection record `3bd85d4b…` |
| Mapping | `label-eval-open-images-mapping.tsv` sha256 `606b35bc…` |
| Split and seed | 20261010 for the selection order and the split: 12 901 images calibrate, 12 902 are held out |
| Status | both runs end `MEASURED`, not overridden |

#### 3.12.1 The dataset, its licence, and what "verified" means

Checked at the dataset's own pages on 2026-10-11, before anything was downloaded:
[`factsfigures_v7.html`](https://storage.googleapis.com/openimages/web/factsfigures_v7.html) and
[`download_v7.html`](https://storage.googleapis.com/openimages/web/download_v7.html).

| Question | What the page says, or the files show |
|---|---|
| Annotations | *"The annotations are licensed by Google LLC under CC BY 4.0 license."* |
| Images | *"The images are listed as having a CC BY 2.0 license."* ⚠ And: *"we make no representations or warranties regarding the license status of each image and you should verify the license for each image yourself."* |
| Per image | The image information files carry `License`, `AuthorProfileURL`, `Author`, `Title` and the Flickr URLs for every image. **Measured**: all 41 620 validation rows and all 125 436 test rows list `https://creativecommons.org/licenses/by/2.0/`; no row has an empty author |
| Human-verified labels | `Source` is `verification` (*"verified by in-house annotators at Google"*) or `crowdsource-verification`; *"Labels that are human-verified to be present in an image have confidence = 1 (positive labels). Labels that are human-verified to be absent from an image have confidence = 0 (negative labels)."* |
| What is not verified | *"All other classes which are not explicitly marked as positive or negative for an image are not annotated."* |
| How good | *"This verification process practically eliminates false positives (but not false negatives: some labels might be missing from an image)."* |
| Machine-generated labels | A separate file; *"a substantial false positive rate"*, fractional confidences. ⚠ **Not used.** The reader refuses a file holding one (`open_images_row`) |
| Download | `downloader.py` reads the public bucket `open-images-dataset`, unsigned, as `<split>/<image id>.jpg`. No account |

- **The trap COCO had is not in the listing.** COCO's own file listed 3 475 of 5 000 val2017 images
  as NonCommercial (§3.10.1). Here every image of both sets is listed CC BY 2.0. ⚖️ That is
  Google's listing, which it declines to warrant; nobody here checked an image's licence at
  Flickr. `select` refuses an image listed otherwise, and writes the chosen images' information
  rows (author, title, URL) beside the corpus, because CC BY is owed an attribution wherever an
  image is shown.
- **Nothing from Open Images is in this repository** but 25 803 image ids with the sha256 of the
  bytes fetched, 254 class names in the mapping, and counts.
- ⚠ **The images served are not the originals.** The bucket holds copies at most 1 024 px on the
  long side (the first one fetched: 290 621 bytes, where its row gives an original of 2 038 323).
  **Measured** on the corpus: 25 761 of the 25 803 are within 1 024 px, 42 are larger, and 33 are
  multi-picture (MPO) files, of which Pillow reads the first frame. None carries an EXIF
  orientation other than 1. The manifest pins the bytes fetched; a sample of 150 fetched a second
  time came back byte for byte.
- ⚠ **`Rotation`.** *"The number of degrees that the image should be rotated counterclockwise to
  match the Flickr user intended orientation"*; empty where unknown. Of the 167 056 images, 143 494
  say 0, 1 986 say 90, 180 or 270, and 21 576 say nothing. The pool is the 143 494: an image
  embedded on its side would be scored against truth for the upright one.

**Where a verified negative comes from.** Inferred from the page, not stated by it in these
words: candidate labels for an image were proposed by classifiers (*"Multiple computer vision
models were used to generate the samples"*) and a person then confirmed or rejected each. So a
verified negative for `Beach` is mostly an image some model took for a beach. They are **hard
negatives**, harder than a photograph drawn at random, and the share of them among a class's
verified images is a property of those models, not of the world.

#### 3.12.2 The corpus: 25 803 images chosen for the labels

`label-eval.py select`, seed 20261010, from the validation and test sets (the training set's
verified labels are 58 million rows and were not read):

1. The pool is the 143 494 images with `Rotation` 0.
2. For each mapped label, the images where it is verified present and the images where it is
   verified absent (§3.12.3's rule), each ordered by sha256 of `"<seed>:<label>:<image id>"`. The
   first **100** of each are taken.
3. The corpus is the union: **25 803 images** (6 138 validation, 19 665 test), named
   `<split>-<image id>.jpg`. The split is §3.10.1's rule under the same seed: 12 901 calibrate,
   12 902 are held out.
4. Every verified row of those images is copied out whole (440 872 rows; every class, not only
   the mapped ones), and the corpus manifest pins that extract, the class descriptions, the selection
   record and the two official files by digest.

**A label's sample.** A label is scored over the images step 2 took for it and over no other: at
most 100 it is verified present on and at most 100 it is verified absent on. ⚠⚠ **Not over every
corpus image it happens to be verified on.** The first version of this evaluation did that, and a
dry run over vectors drawn at random reported `sky and weather` as holding precision 0.90 on both
artifacts with a Wilson lower bound above it: `Sky` is verified present on 98 % of the corpus
images that say anything about it, nearly all of them chosen for some other label, so any score
at all clears the bar. With each label on its own sample the same random vectors hold nothing.
The share of positives in a held-out sample then runs from 39 % (`comic`) to 69 % (`kitchen`,
`barbecue`, `cafe`), and §3.12.5 gives the counts. ⚠ Only the selection knows where a label's
own images stop: `evaluate` reads the selection record the manifest pins, and refuses another
mapping than the one the corpus was selected with, or a sample larger than was selected; a
smaller one is a subsample and is admitted (`--per-label`).

**The minimum.** A label is scored only with at least **20 verified positives and 20 verified
negatives in each half** of its sample. A floor needs ten firing images of which nine are right
(§3.10.1), so 20 positives lets a floor sit at half of them; below that a floor is a statement
about a handful. `select` and `evaluate` both refuse a mapped label that falls short, naming its
counts, and such a label is marked `thin` in the mapping. ⚠ 20 is where a floor can be *placed*.
It is not where a held-out lower bound can reach 0.90, which takes some 35 firing images with
none wrong (§3.12.6). The median scored label has 50 positives in its held-out half; the least,
23 (`rainbow`), and 21 negatives (`kitchen`, calibration half).

⚠ Some labels are `thin` by a few images: `waterfall`, `island`, `glacier`, `skateboarding`,
`menu`, `tram` and `moon` each have 36 to 52 verified negatives in the whole pool, which a split
puts on either side of 20 a half. Another seed would move some of them across the line and
scored ones off it. The seed was not chosen for the outcome: it is §3.10's.

#### 3.12.3 The mapping, and the calls in it

`label-eval-open-images-mapping.tsv`, one row a label: its positive classes, its negative classes,
and the verified positives and negatives in the pool. ⚠⚠ **It is a set of judgements and wants a
review.**

| | labels |
|---|---|
| **Scored** | **156**: 121 on one class, 35 on several. By fidelity: 131 exact, 14 **broader** ᵇ (the classes cover more than the label), 3 **narrower** ⁿ, 8 **proxy** ᵖ |
| `thin` | 39: classes exist, too few verified images (§3.12.2) |
| `none` | 12: `person`, `couple`, `person from behind`, `people at a table`, `shop front`, `office`, `hotel room`, `book page`, `form`, `keys`, and the top-tier `objects` and `activities` |

Twelve of the 14 top-tier labels are scored (`landscape` is thin: 810 verified positives and no
verified negative; `objects` and `activities` have no class). Scored on COCO **or** here: 157 of
207 (`skateboarding` is COCO's only).

**The rule.** An image is **positive** for a label when any of its positive classes is verified
present; **negative** when none is and any of its negative classes is verified absent; otherwise
the label was **not verified** on it. A negative class is one whose absence means the label's
absence, which is why the two lists can differ: `animals` is `Animal` on both sides and never
`Dog`, since an image verified to hold no dog may hold a cat.

**The calls a reviewer should look at first:**

- **`indoors` ᵖ → `Room`.** Open Images has no indoor class. A room verified present is indoors; a
  room verified absent is *taken* as not indoors, which a stadium or a station hall contradicts.
  This is the label §28 asks about, and its figures are the proxy's.
- **`documents and screens` ᵖ** is a union of ten leaf classes, negative where `Document` or
  `Screenshot` is verified absent and none of the ten is present.
- **`food and drink`** → `Food`; `Drink`, negative on `Food` alone: a drink verified for `Food`
  and not for `Drink` would count against the label.
- **`farm animal` ᵖ** → six species, negative where `Animal` is absent: never tested against a
  wild animal or a pet.
- **`urban` ⁿ** (five city classes), **`art and media` ⁿ** (`Art`), **`sky and weather` ⁿ** (`Sky`):
  each top-tier label is scored on the part of it that has a class.
- **Broader** ᵇ: `ocean` (`Sea` counts), `snowy landscape` (`Snow`), `night sky` (`Night`), `field`
  (sports fields count), `city street` (`Street`), `skyline` (`Cityscape` counts), `market stall`
  (`Market`), `shop interior` (`Retail`), `plate of food` (`Dish`), `sports match` (`Sports`),
  `group of people` (`People`), `team`, `chart` (`Diagram` counts), `conference`.
- **Other proxies** ᵖ: `zoo animal` (`Zoo`), `autumn leaves` (`Autumn`), `slide` (`Presentation`),
  `package` (`Box`), `snowfall` (`Blizzard`; `Winter storm`).
- **`people`** → `Person`; `People`, **`cow`** → `Cattle`, **`fish`** → `Fish` and not `Fish (Food)`,
  **`football`** → both codes, **`fog`** → `Fog`; `Mist`, **`stars`** → `Star` (thin; ⚠ the class is
  not only the night-sky kind).
- **The activities are the activity here**, where COCO offered the equipment: `skiing` is `Skiing`,
  not the presence of skis; `wine` is `Wine`, not a wine glass; `computer screen` is `Computer
  monitor`, not COCO's `tv`.

⚠⚠ **Seventeen rows break the rule they are written under, and their negatives are partly
unsound.** A negative class must be one whose absence *alone* means the label is absent. Where a
label is a union of alternatives with no broader class over them, the row lists the alternatives
themselves, and the reader takes any one of them verified absent as a negative: `Seminar`
verified absent makes an image a negative for `conference` though `Convention` was never looked
at. Found in review, after the corpus was selected and embedded; the mapping is pinned by the
selection (§3.12.2), so the rows are not corrected here and the affected figures are marked
instead. For each, the sample negatives that rest on one alternative only, and the held-out
precision / recall (firing images) as scored and with those negatives left out (computed beside
the results, `analysis/union-negatives.py`; floors as placed):

| Label | sample negatives | resting on one alternative only | ONNX as scored | ONNX without them | Core ML as scored | Core ML without them |
|---|---|---|---|---|---|---|
| `food and drink` | 100 | 97 | — | — | — | — |
| `ocean` ᵇ | 100 | 6 | — | — | — | — |
| `urban` ⁿ | 100 | 85 | 0.71 / 0.51 (28) | 0.77 / 0.51 (26) | 0.73 / 0.62 (33) | 0.77 / 0.62 (31) |
| `skyline` ᵇ | 97 | 12 | 0.88 / 0.25 (16) | 1.00 / 0.25 (14) | 0.92 / 0.20 (12) | 1.00 / 0.20 (11) |
| `tall building` | 100 | 94 | — | — | — | — |
| `church` | 100 | 39 | 0.79 / 0.22 (14) | 0.85 / 0.22 (13) | 0.80 / 0.24 (15) | 0.86 / 0.24 (14) |
| `parking` | 87 | 29 | — | — | — | — |
| `documents and screens` ᵖ | 100 | 100 | — | — | — | — |
| `chart` ᵇ | 100 | 100 | 0.67 / 0.20 (15) | 1.00 / 0.20 (10) | 0.64 / 0.18 (14) | 1.00 / 0.18 (9) |
| `conference` ᵇ | 100 | 97 | 0.85 / 0.22 (13) | 1.00 / 0.22 (11) | — | — |
| `phone` | 100 | 74 | 0.82 / 0.69 (44) | 0.90 / 0.69 (40) | 0.77 / 0.65 (44) | 0.89 / 0.65 (38) |
| `football` | 100 | 96 | 0.84 / 0.31 (19) | 1.00 / 0.31 (16) | 0.83 / 0.47 (29) | 0.96 / 0.47 (25) |
| `hiking` | 65 | 18 | 0.88 / 0.58 (17) | 0.94 / 0.58 (16) | 0.93 / 0.54 (15) | 1.00 / 0.54 (14) |
| `comic` | 100 | 18 | — | — | — | — |
| `storm` | 52 | 18 | 0.94 / 0.37 (17) | 0.94 / 0.37 (17) | 1.00 / 0.14 (6) | 1.00 / 0.14 (6) |
| `snowfall` ᵖ | 65 | 29 | 0.80 / 0.70 (40) | 0.82 / 0.70 (39) | 0.76 / 0.63 (38) | 0.78 / 0.63 (37) |
| `package` ᵖ | 100 | 98 | — | — | — | — |

⚠ *Without them* is a bound, not a correction: an image left out may well be a true negative, and
a floor was placed with the unsound negatives in its calibration half. The truth for these labels
lies between the two columns, and their rows in §3.12.5 are the *as scored* ones. An unsound
negative can only make a label look worse (a positive counted as a false firing), so none of them
is flattered. Of §3.12.6's lists only `storm` is among the seventeen, and its figures do not move.
**Owed before the next selection:** each of these rows needs a single class whose absence
excludes every alternative, or the reader needs an all-alternatives-absent rule for it.

#### 3.12.4 ⚠⚠ How a figure here must be read

On COCO every category is annotated on every image, so a label's precision is over the corpus.
Here a label is scored **over its sample, and over no other image**: at most 200 of the 25 803,
about 100 of them held out. Three consequences.

1. **Prevalence is the selection's, not the world's.** A label's sample is about half positives
   by construction. A real library holds a `waterfall` in far fewer than half its photographs.
2. **So a precision here is not the precision a library would see.** Precision is hits over
   firing images, and the number of false firings scales with how many negatives there are. At a
   lower prevalence the same floor has a lower precision. Against that, the negatives here are
   hard ones (§3.12.1), and a library's are mostly easy. The two push in opposite directions and
   nothing here says which wins. The result therefore also carries, per label, the
   **false-positive rate on verified negatives** (the share of them at or above the floor), from
   which precision at any assumed prevalence π follows: `π·R / (π·R + (1 − π)·FPR)`.
3. **A firing rate against truth does not exist.** What is reported as a firing rate is over all
   25 803 images, verified or not: it needs no truth, and it is a rate on a corpus assembled from
   200 images a label, which is no library.

What needs no truth is over every image of the held-out half: which labels each artifact would
write, and whether two artifacts agree (§3.12.7). An image outside a label's sample is **not** a
negative anywhere. `test_label_eval.py` holds that in place: a label scored through its mask
equals the same label scored on a corpus of its sample alone, for AP, the floor, the held-out
figures and the bootstrap; and an image outside the sample scoring above every floor moves none
of them. Each of those reads was replaced by the unmasked one in turn, and the suite went red
for each.

**Intervals.** The bootstrap is §3.10.1's: 1 000 resamples of held-out *images*, the floor held
fixed; a label reads the draws of the images of its sample. ⚠ **A percentile bootstrap cannot
see past its sample**: where every firing image is a hit, every resample is 1.00 and the interval
is [1.00, 1.00], for ten firing images as for a thousand. So each precision also carries a
**Wilson** 95 % interval, whose lower bound for 10 of 10 is 0.72 and for 35 of 35 is 0.90. Both
are reported; they answer different questions and §3.12.6 gives the count under each.

#### 3.12.5 Per label

Held-out half, bare-word prompt, target precision 0.90. ᵖ proxy, ᵇ the classes are broader than
the label, ⁿ narrower. *Sample* is the label's verified positives / negatives in the held-out
half. *Fired* is how many of them are at or above the floor; the precision is the share of those
that are positives, with its bootstrap interval and, in brackets, its Wilson lower bound. The
last column is ONNX against Core ML over **all** 12 902 held-out images: of the images either
would label, the share both would.

| Label | classes | held-out sample, pos / neg | AP ONNX / Core ML | floor ONNX / Core ML | ONNX: fired, precision [bootstrap] (Wilson low), recall | Core ML: fired, precision [bootstrap] (Wilson low), recall | Jaccard |
|---|---|---|---|---|---|---|---|
| `people` (top) | Person; People | 47 / 53 | 0.83 / 0.84 | 0.0181 / -0.0075 | 9, 1.00 [1.00, 1.00] (0.70), 0.19 | 18, 0.94 [0.81, 1.00] (0.74), 0.36 | 0.42 |
| `portrait` | Portrait | 47 / 47 | 0.76 / 0.76 | — / — | — | — | — |
| `group of people` ᵇ | People; Social group | 48 / 48 | 0.94 / 0.93 | 0.0027 / 0.0172 | 39, 0.90 [0.79, 0.98] (0.76), 0.73 | 33, 0.94 [0.85, 1.00] (0.80), 0.65 | 0.74 |
| `crowd` | Crowd | 45 / 38 | 0.75 / 0.77 | — / 0.0725 | — | 17, 0.88 [0.71, 1.00] (0.66), 0.33 | 0.00 |
| `baby` | Baby | 53 / 47 | 0.82 / 0.82 | 0.0906 / — | 15, 0.93 [0.79, 1.00] (0.70), 0.26 | — | 0.00 |
| `child` | Child | 60 / 45 | 0.85 / 0.85 | 0.0646 / 0.0770 | 31, 0.81 [0.65, 0.94] (0.64), 0.42 | 17, 0.88 [0.71, 1.00] (0.66), 0.25 | 0.57 |
| `selfie` | Selfie | 51 / 36 | 0.59 / 0.64 | 0.0859 / 0.0763 | 6, 0.33 [0.00, 0.75] (0.10), 0.04 | 11, 0.73 [0.46, 1.00] (0.43), 0.16 | 0.40 |
| `team` ᵇ | Team | 56 / 44 | 0.74 / 0.71 | 0.0495 / 0.0518 | 16, 0.75 [0.53, 0.93] (0.51), 0.21 | 15, 0.60 [0.36, 0.85] (0.36), 0.16 | 0.64 |
| `animals` (top) | Animal | 46 / 58 | 0.83 / 0.88 | 0.0423 / 0.0302 | 27, 0.78 [0.61, 0.93] (0.59), 0.46 | 47, 0.83 [0.71, 0.92] (0.70), 0.85 | 0.70 |
| `dog` | Dog | 48 / 45 | 0.97 / 1.00 | 0.0647 / 0.0622 | 46, 0.96 [0.88, 1.00] (0.85), 0.92 | 47, 0.98 [0.93, 1.00] (0.89), 0.96 | 0.88 |
| `cat` | Cat | 48 / 51 | 0.96 / 0.97 | 0.0694 / 0.0733 | 44, 0.91 [0.82, 0.98] (0.79), 0.83 | 40, 0.97 [0.92, 1.00] (0.87), 0.81 | 0.72 |
| `bird` | Bird | 51 / 53 | 0.87 / 0.87 | 0.0710 / 0.0765 | 42, 0.86 [0.74, 0.95] (0.72), 0.71 | 31, 0.84 [0.70, 0.96] (0.67), 0.51 | 0.67 |
| `horse` | Horse | 55 / 57 | 0.93 / 0.89 | 0.0685 / 0.0668 | 56, 0.88 [0.79, 0.96] (0.76), 0.89 | 55, 0.85 [0.76, 0.94] (0.74), 0.85 | 0.84 |
| `cow` | Cattle; Dairy cow | 50 / 55 | 0.87 / 0.88 | 0.0720 / 0.0655 | 31, 0.90 [0.80, 1.00] (0.75), 0.56 | 37, 0.92 [0.82, 1.00] (0.79), 0.68 | 0.77 |
| `sheep` | Sheep | 52 / 49 | 0.87 / 0.87 | 0.1002 / 0.1089 | 30, 0.90 [0.77, 1.00] (0.74), 0.52 | 16, 0.94 [0.79, 1.00] (0.72), 0.29 | 0.51 |
| `fish` | Fish | 54 / 54 | 0.84 / 0.87 | 0.0713 / 0.0741 | 34, 0.91 [0.81, 1.00] (0.77), 0.57 | 35, 0.91 [0.81, 1.00] (0.78), 0.59 | 0.77 |
| `insect` | Insect | 50 / 56 | 0.92 / 0.92 | 0.0598 / 0.0568 | 53, 0.81 [0.70, 0.91] (0.69), 0.86 | 53, 0.83 [0.72, 0.92] (0.71), 0.88 | 0.84 |
| `butterfly` | Butterfly | 48 / 49 | 0.96 / 0.95 | 0.0768 / 0.0725 | 44, 0.91 [0.82, 0.98] (0.79), 0.83 | 50, 0.84 [0.74, 0.93] (0.71), 0.88 | 0.85 |
| `squirrel` | Squirrel | 53 / 49 | 0.93 / 0.93 | 0.0746 / 0.0706 | 51, 0.86 [0.76, 0.95] (0.74), 0.83 | 51, 0.88 [0.78, 0.96] (0.77), 0.85 | 0.89 |
| `deer` | Deer | 54 / 58 | 0.83 / 0.83 | 0.1062 / 0.1076 | 11, 0.91 [0.67, 1.00] (0.62), 0.19 | 9, 1.00 [1.00, 1.00] (0.70), 0.17 | 0.50 |
| `duck` | Duck | 53 / 47 | 0.87 / 0.86 | 0.0992 / 0.1010 | 9, 1.00 [1.00, 1.00] (0.70), 0.17 | 11, 1.00 [1.00, 1.00] (0.74), 0.21 | 0.69 |
| `farm animal` ᵖ | Cattle + 5 | 46 / 43 | 0.98 / 0.98 | 0.0298 / 0.0271 | 40, 1.00 [1.00, 1.00] (0.91), 0.87 | 41, 1.00 [1.00, 1.00] (0.91), 0.89 | 0.82 |
| `zoo animal` ᵖ | Zoo | 56 / 38 | 0.81 / 0.85 | 0.0796 / 0.0848 | 15, 0.87 [0.67, 1.00] (0.62), 0.23 | 16, 0.94 [0.79, 1.00] (0.72), 0.27 | 0.68 |
| `food and drink` (top) | Food; Drink | 48 / 64 | 0.80 / 0.79 | — / — | — | — | — |
| `plate of food` ᵇ | Dish | 51 / 49 | 0.80 / 0.82 | 0.0346 / 0.0385 | 32, 0.81 [0.67, 0.94] (0.65), 0.51 | 37, 0.78 [0.66, 0.91] (0.63), 0.57 | 0.79 |
| `breakfast` | Breakfast | 55 / 47 | 0.75 / 0.77 | 0.0660 / 0.0706 | 8, 0.88 [0.60, 1.00] (0.53), 0.13 | 6, 1.00 [1.00, 1.00] (0.61), 0.11 | 0.70 |
| `sandwich` | Sandwich | 45 / 47 | 0.69 / 0.71 | 0.0971 / 0.0870 | 16, 0.75 [0.50, 0.94] (0.51), 0.27 | 32, 0.75 [0.59, 0.89] (0.58), 0.53 | 0.45 |
| `pizza` | Pizza | 38 / 40 | 0.93 / 0.93 | 0.0788 / 0.0746 | 47, 0.79 [0.67, 0.90] (0.65), 0.97 | 47, 0.79 [0.67, 0.90] (0.65), 0.97 | 0.92 |
| `pasta` | Pasta | 53 / 30 | 0.89 / 0.89 | 0.0938 / — | 13, 1.00 [1.00, 1.00] (0.77), 0.25 | — | 0.00 |
| `salad` | Salad | 42 / 47 | 0.71 / 0.71 | 0.0817 / 0.0768 | 12, 0.83 [0.57, 1.00] (0.55), 0.24 | 15, 0.73 [0.50, 0.93] (0.48), 0.26 | 0.84 |
| `burger` | Hamburger | 47 / 24 | 0.93 / 0.95 | 0.0814 / 0.0834 | 34, 0.94 [0.85, 1.00] (0.81), 0.68 | 31, 0.97 [0.90, 1.00] (0.84), 0.64 | 0.73 |
| `sushi` | Sushi | 53 / 37 | 0.82 / 0.83 | 0.0977 / 0.0960 | 36, 0.83 [0.70, 0.95] (0.68), 0.57 | 39, 0.82 [0.69, 0.94] (0.67), 0.60 | 0.89 |
| `dessert` | Dessert | 39 / 51 | 0.65 / 0.63 | 0.0670 / 0.0676 | 14, 0.71 [0.44, 0.93] (0.45), 0.26 | 13, 0.69 [0.40, 0.93] (0.42), 0.23 | 0.74 |
| `cake` | Cake | 55 / 48 | 0.92 / 0.87 | 0.0816 / 0.0839 | 18, 0.94 [0.82, 1.00] (0.74), 0.31 | 19, 0.84 [0.64, 1.00] (0.62), 0.29 | 0.65 |
| `ice cream` | Ice cream | 52 / 46 | 0.92 / 0.88 | 0.0897 / 0.0868 | 22, 0.95 [0.84, 1.00] (0.78), 0.40 | 26, 0.96 [0.87, 1.00] (0.81), 0.48 | 0.85 |
| `bread` | Bread | 56 / 46 | 0.76 / 0.76 | — / — | — | — | — |
| `fruit` | Fruit | 45 / 49 | 0.89 / 0.89 | 0.0656 / 0.0623 | 29, 1.00 [1.00, 1.00] (0.88), 0.64 | 29, 0.93 [0.83, 1.00] (0.78), 0.60 | 0.84 |
| `vegetables` | Vegetable | 56 / 45 | 0.78 / 0.79 | 0.0540 / 0.0562 | 24, 0.92 [0.78, 1.00] (0.74), 0.39 | 26, 0.92 [0.80, 1.00] (0.76), 0.43 | 0.84 |
| `coffee` | Coffee (drink) | 47 / 53 | 0.81 / 0.82 | 0.0926 / 0.0951 | 13, 0.92 [0.75, 1.00] (0.67), 0.26 | 11, 0.91 [0.70, 1.00] (0.62), 0.21 | 0.71 |
| `cocktail` | Cocktail | 57 / 46 | 0.91 / 0.92 | 0.0839 / 0.0856 | 28, 0.93 [0.82, 1.00] (0.77), 0.46 | 26, 1.00 [1.00, 1.00] (0.87), 0.46 | 0.80 |
| `wine` | Wine | 44 / 53 | 0.77 / 0.79 | — / 0.0888 | — | 13, 1.00 [1.00, 1.00] (0.77), 0.30 | 0.00 |
| `beer` | Beer | 52 / 53 | 0.91 / 0.92 | 0.0993 / 0.0975 | 17, 0.94 [0.80, 1.00] (0.73), 0.31 | 25, 0.96 [0.87, 1.00] (0.80), 0.46 | 0.55 |
| `barbecue` | Barbecue | 53 / 24 | 0.78 / 0.76 | — / — | — | — | — |
| `plants` (top) | Plant | 52 / 46 | 0.80 / 0.79 | — / 0.0537 | — | 11, 1.00 [1.00, 1.00] (0.74), 0.21 | 0.00 |
| `flower` | Flower | 52 / 51 | 0.93 / 0.93 | 0.0471 / 0.0478 | 51, 0.86 [0.76, 0.95] (0.74), 0.85 | 52, 0.87 [0.76, 0.95] (0.75), 0.87 | 0.82 |
| `tree` | Tree | 50 / 50 | 0.60 / 0.59 | — / — | — | — | — |
| `forest` | Forest | 47 / 29 | 0.75 / 0.74 | 0.0745 / — | 11, 0.82 [0.50, 1.00] (0.52), 0.19 | — | 0.00 |
| `grass` | Grass | 42 / 49 | 0.54 / 0.53 | — / — | — | — | — |
| `mushroom` | Mushroom | 49 / 41 | 0.96 / 0.97 | 0.0849 / 0.0776 | 44, 0.93 [0.85, 1.00] (0.82), 0.84 | 49, 0.94 [0.86, 1.00] (0.83), 0.94 | 0.77 |
| `houseplant` | Houseplant | 55 / 44 | 0.72 / 0.72 | — / 0.0717 | — | 10, 0.90 [0.67, 1.00] (0.60), 0.16 | 0.00 |
| `garden` | Garden | 44 / 58 | 0.72 / 0.68 | — / — | — | — | — |
| `cactus` | Cactus | 58 / 29 | 0.92 / 0.92 | 0.0863 / 0.0882 | 45, 0.96 [0.88, 1.00] (0.85), 0.74 | 50, 0.86 [0.75, 0.94] (0.74), 0.74 | 0.90 |
| `autumn leaves` ᵖ | Autumn | 48 / 50 | 0.72 / 0.72 | 0.0866 / 0.0763 | 12, 0.83 [0.58, 1.00] (0.55), 0.21 | 19, 0.74 [0.53, 0.93] (0.51), 0.29 | 0.59 |
| `blossom` | Blossom | 55 / 50 | 0.83 / 0.83 | 0.0582 / 0.0722 | 33, 0.79 [0.64, 0.92] (0.62), 0.47 | 27, 0.85 [0.71, 0.97] (0.68), 0.42 | 0.67 |
| `beach` | Beach | 55 / 56 | 0.67 / 0.63 | 0.0787 / — | 20, 0.75 [0.55, 0.94] (0.53), 0.27 | — | 0.00 |
| `ocean` ᵇ | Ocean; Sea | 55 / 50 | 0.63 / 0.67 | — / — | — | — | — |
| `mountain` | Mountain | 36 / 38 | 0.54 / 0.53 | — / — | — | — | — |
| `lake` | Lake | 48 / 52 | 0.72 / 0.75 | — / — | — | — | — |
| `river` | River | 56 / 46 | 0.60 / 0.58 | — / — | — | — | — |
| `snowy landscape` ᵇ | Snow | 54 / 49 | 0.57 / 0.60 | — / — | — | — | — |
| `field` ᵇ | Field | 51 / 46 | 0.70 / 0.70 | — / — | — | — | — |
| `valley` | Valley | 53 / 41 | 0.82 / 0.81 | — / — | — | — | — |
| `cliff` | Cliff | 49 / 53 | 0.60 / 0.63 | — / — | — | — | — |
| `canyon` | Canyon | 42 / 52 | 0.73 / 0.69 | — / — | — | — | — |
| `urban` ⁿ (top) | Urban area + 4 | 39 / 57 | 0.74 / 0.74 | 0.0385 / 0.0301 | 28, 0.71 [0.55, 0.87] (0.53), 0.51 | 33, 0.73 [0.58, 0.86] (0.56), 0.62 | 0.72 |
| `city street` ᵇ | Street | 44 / 24 | 0.82 / 0.83 | 0.0468 / 0.0536 | 35, 0.77 [0.62, 0.91] (0.61), 0.61 | 28, 0.86 [0.71, 0.97] (0.69), 0.55 | 0.63 |
| `skyline` ᵇ | Skyline; Cityscape | 56 / 41 | 0.80 / 0.84 | 0.0792 / 0.0825 | 16, 0.88 [0.69, 1.00] (0.64), 0.25 | 12, 0.92 [0.73, 1.00] (0.65), 0.20 | 0.50 |
| `tall building` | Skyscraper; Tower block | 52 / 48 | 0.61 / 0.62 | — / — | — | — | — |
| `house` | House | 51 / 58 | 0.53 / 0.54 | — / — | — | — | — |
| `bridge` | Bridge (structure) | 49 / 49 | 0.78 / 0.80 | 0.0724 / 0.0814 | 40, 0.75 [0.62, 0.88] (0.60), 0.61 | 35, 0.77 [0.63, 0.90] (0.61), 0.55 | 0.65 |
| `church` | Church; Cathedral | 51 / 55 | 0.73 / 0.74 | 0.0792 / 0.0879 | 14, 0.79 [0.55, 1.00] (0.52), 0.22 | 15, 0.80 [0.58, 1.00] (0.55), 0.24 | 0.64 |
| `market stall` ᵇ | Market + 2 | 52 / 29 | 0.87 / 0.91 | 0.0935 / 0.0759 | 23, 0.87 [0.71, 1.00] (0.68), 0.38 | 40, 0.88 [0.77, 0.97] (0.74), 0.67 | 0.50 |
| `airport` | Airport | 53 / 38 | 0.85 / 0.82 | 0.0816 / 0.0863 | 49, 0.82 [0.70, 0.91] (0.69), 0.75 | 46, 0.80 [0.68, 0.91] (0.67), 0.70 | 0.82 |
| `construction site` | Construction | 42 / 28 | 0.90 / 0.89 | 0.0975 / 0.0957 | 10, 1.00 [1.00, 1.00] (0.72), 0.24 | 10, 1.00 [1.00, 1.00] (0.72), 0.24 | 0.71 |
| `parking` | Parking; Parking lot | 37 / 43 | 0.56 / 0.54 | — / — | — | — | — |
| `monument` | Monument | 53 / 58 | 0.82 / 0.81 | 0.0739 / 0.0741 | 31, 0.77 [0.62, 0.91] (0.60), 0.45 | 30, 0.83 [0.68, 0.95] (0.66), 0.47 | 0.65 |
| `fountain` | Fountain | 53 / 60 | 0.85 / 0.84 | 0.1020 / — | 14, 1.00 [1.00, 1.00] (0.78), 0.26 | — | 0.00 |
| `indoors` ᵖ (top) | Room | 50 / 46 | 0.53 / 0.58 | — / — | — | — | — |
| `living room` | Living room | 50 / 57 | 0.71 / 0.77 | 0.0547 / 0.0550 | 41, 0.63 [0.47, 0.78] (0.48), 0.52 | 38, 0.74 [0.59, 0.88] (0.58), 0.56 | 0.77 |
| `kitchen` | Kitchen | 48 / 22 | 0.94 / 0.93 | 0.0619 / 0.0486 | 46, 0.89 [0.80, 0.98] (0.77), 0.85 | 56, 0.79 [0.67, 0.89] (0.66), 0.92 | 0.67 |
| `bathroom` | Bathroom | 54 / 32 | 0.89 / 0.92 | — / — | — | — | — |
| `restaurant` | Restaurant | 59 / 52 | 0.75 / 0.76 | 0.0569 / 0.0627 | 33, 0.79 [0.63, 0.93] (0.62), 0.44 | 21, 0.76 [0.57, 0.94] (0.55), 0.27 | 0.62 |
| `cafe` | Coffeehouse | 53 / 24 | 0.83 / 0.82 | 0.0924 / 0.0937 | 30, 0.80 [0.64, 0.93] (0.63), 0.45 | 29, 0.76 [0.59, 0.91] (0.58), 0.42 | 0.81 |
| `library` | Library | 56 / 28 | 0.94 / 0.94 | 0.0817 / 0.0838 | 54, 0.91 [0.83, 0.98] (0.80), 0.88 | 51, 0.90 [0.82, 0.98] (0.79), 0.82 | 0.93 |
| `classroom` | Classroom | 52 / 51 | 0.75 / 0.73 | 0.0942 / 0.0938 | 19, 0.74 [0.52, 0.92] (0.51), 0.27 | 16, 0.75 [0.50, 0.94] (0.51), 0.23 | 0.69 |
| `staircase` | Stairs | 48 / 60 | 0.49 / 0.51 | — / — | — | — | — |
| `shop interior` ᵇ | Retail | 54 / 57 | 0.55 / 0.54 | — / — | — | — | — |
| `transport` (top) | Vehicle + 2 | 54 / 51 | 0.69 / 0.67 | 0.0381 / 0.0430 | 11, 0.82 [0.56, 1.00] (0.52), 0.17 | 7, 0.57 [0.17, 1.00] (0.25), 0.07 | 0.72 |
| `car` | Car | 47 / 47 | 0.92 / 0.91 | 0.0508 / 0.0590 | 45, 0.87 [0.76, 0.96] (0.74), 0.83 | 25, 0.92 [0.80, 1.00] (0.75), 0.49 | 0.46 |
| `bicycle` | Bicycle | 47 / 59 | 0.67 / 0.70 | — / — | — | — | — |
| `motorcycle` | Motorcycle | 50 / 49 | 0.79 / 0.78 | 0.0722 / 0.0713 | 36, 0.81 [0.67, 0.93] (0.65), 0.58 | 38, 0.79 [0.64, 0.91] (0.64), 0.60 | 0.76 |
| `bus` | Bus | 43 / 47 | 0.86 / 0.85 | 0.0744 / 0.0714 | 41, 0.76 [0.62, 0.88] (0.61), 0.72 | 38, 0.82 [0.69, 0.93] (0.67), 0.72 | 0.79 |
| `train` | Train | 53 / 47 | 0.88 / 0.85 | 0.0747 / 0.0809 | 46, 0.83 [0.71, 0.93] (0.69), 0.72 | 39, 0.85 [0.73, 0.95] (0.70), 0.62 | 0.75 |
| `boat` | Boat | 49 / 48 | 0.80 / 0.79 | 0.0777 / 0.0871 | 19, 0.84 [0.68, 1.00] (0.62), 0.33 | 13, 0.77 [0.50, 1.00] (0.50), 0.20 | 0.66 |
| `ship` | Ship | 57 / 46 | 0.85 / 0.87 | 0.0992 / 0.0984 | 15, 0.93 [0.79, 1.00] (0.70), 0.25 | 16, 0.94 [0.78, 1.00] (0.72), 0.26 | 0.66 |
| `airplane` | Airplane + 2 | 47 / 53 | 0.87 / 0.92 | 0.0600 / 0.0656 | 38, 0.87 [0.74, 0.97] (0.73), 0.70 | 30, 0.93 [0.82, 1.00] (0.79), 0.60 | 0.78 |
| `truck` | Truck | 45 / 52 | 0.74 / 0.75 | 0.0813 / 0.0799 | 14, 0.93 [0.77, 1.00] (0.69), 0.29 | 17, 0.94 [0.81, 1.00] (0.73), 0.36 | 0.66 |
| `documents and screens` ᵖ (top) | Document + 9 | 48 / 49 | 0.81 / 0.84 | — / — | — | — | — |
| `screenshot` | Screenshot | 54 / 37 | 0.94 / 0.92 | 0.0443 / 0.0465 | 28, 0.96 [0.88, 1.00] (0.82), 0.50 | 17, 0.94 [0.81, 1.00] (0.73), 0.30 | 0.54 |
| `document` | Document | 46 / 42 | 0.67 / 0.60 | — / — | — | — | — |
| `handwriting` | Handwriting | 43 / 44 | 0.87 / 0.86 | 0.0629 / 0.0643 | 29, 0.93 [0.83, 1.00] (0.78), 0.63 | 32, 0.88 [0.76, 0.98] (0.72), 0.65 | 0.71 |
| `whiteboard` | Whiteboard | 39 / 28 | 0.77 / 0.77 | 0.1136 / — | 14, 0.93 [0.77, 1.00] (0.69), 0.33 | — | 0.00 |
| `chart` ᵇ | Chart; Diagram | 50 / 52 | 0.68 / 0.65 | 0.0602 / 0.0555 | 15, 0.67 [0.42, 0.91] (0.42), 0.20 | 14, 0.64 [0.38, 0.90] (0.39), 0.18 | 0.88 |
| `slide` ᵖ | Presentation | 49 / 50 | 0.59 / 0.54 | 0.0383 / — | 4, 0.25 [0.00, 1.00] (0.05), 0.02 | — | 0.00 |
| `sign with text` | Signage + 3 | 52 / 63 | 0.73 / 0.75 | — / 0.0711 | — | 16, 0.75 [0.53, 0.94] (0.51), 0.23 | 0.00 |
| `computer screen` | Computer monitor | 52 / 54 | 0.77 / 0.78 | — / 0.0901 | — | 11, 0.82 [0.56, 1.00] (0.52), 0.17 | 0.00 |
| `events` (top) | Event | 49 / 47 | 0.70 / 0.73 | 0.0321 / 0.0386 | 12, 0.67 [0.38, 0.93] (0.39), 0.16 | 11, 0.73 [0.43, 1.00] (0.43), 0.16 | 0.67 |
| `wedding` | Wedding; Wedding reception | 56 / 30 | 0.93 / 0.95 | 0.0707 / 0.0754 | 48, 0.90 [0.80, 0.98] (0.78), 0.77 | 38, 0.95 [0.86, 1.00] (0.83), 0.64 | 0.73 |
| `concert` | Concert; Rock concert | 50 / 31 | 0.63 / 0.69 | 0.0956 / 0.0942 | 13, 0.62 [0.33, 0.88] (0.36), 0.16 | 14, 0.64 [0.36, 0.89] (0.39), 0.18 | 0.67 |
| `festival` | Festival | 43 / 48 | 0.60 / 0.59 | — / — | — | — | — |
| `sports match` ᵇ | Sports | 54 / 52 | 0.81 / 0.79 | — / 0.0610 | — | 13, 0.85 [0.62, 1.00] (0.58), 0.20 | 0.00 |
| `conference` ᵇ | Academic conference + 2 | 49 / 46 | 0.82 / 0.84 | 0.0959 / — | 13, 0.85 [0.60, 1.00] (0.58), 0.22 | — | 0.00 |
| `phone` | Mobile phone + 3 | 52 / 50 | 0.79 / 0.81 | 0.0554 / 0.0498 | 44, 0.82 [0.70, 0.93] (0.68), 0.69 | 44, 0.77 [0.65, 0.89] (0.63), 0.65 | 0.79 |
| `laptop` | Laptop | 47 / 44 | 0.78 / 0.80 | 0.0769 / 0.0788 | 16, 0.88 [0.69, 1.00] (0.64), 0.30 | 12, 1.00 [1.00, 1.00] (0.76), 0.26 | 0.82 |
| `camera` | Camera | 53 / 53 | 0.75 / 0.81 | 0.0741 / 0.0829 | 36, 0.86 [0.74, 0.97] (0.71), 0.58 | 24, 0.92 [0.79, 1.00] (0.74), 0.42 | 0.65 |
| `watch` | Watch | 38 / 54 | 0.71 / 0.71 | 0.0746 / — | 13, 0.77 [0.50, 1.00] (0.50), 0.26 | — | 0.00 |
| `shoes` | Shoe; Footwear | 45 / 53 | 0.78 / 0.79 | 0.0291 / 0.0245 | 16, 0.88 [0.71, 1.00] (0.64), 0.31 | 19, 0.89 [0.75, 1.00] (0.69), 0.38 | 0.60 |
| `clothing` | Clothing | 50 / 45 | 0.86 / 0.87 | — / — | — | — | — |
| `bag` | Bag + 3 | 49 / 53 | 0.71 / 0.70 | 0.0695 / 0.0610 | 13, 0.92 [0.75, 1.00] (0.67), 0.24 | 17, 0.82 [0.62, 1.00] (0.59), 0.29 | 0.58 |
| `furniture` | Furniture | 46 / 51 | 0.65 / 0.65 | — / 0.0537 | — | 11, 0.73 [0.40, 1.00] (0.43), 0.17 | 0.00 |
| `tool` | Tool | 46 / 49 | 0.56 / 0.60 | — / — | — | — | — |
| `toy` | Toy | 50 / 57 | 0.84 / 0.83 | 0.0472 / 0.0406 | 24, 0.88 [0.73, 1.00] (0.69), 0.42 | 29, 0.90 [0.77, 1.00] (0.74), 0.52 | 0.56 |
| `book` | Book | 53 / 55 | 0.71 / 0.73 | — / 0.0428 | — | 33, 0.79 [0.63, 0.91] (0.62), 0.49 | 0.00 |
| `candle` | Candle | 47 / 61 | 0.81 / 0.81 | 0.0920 / 0.0887 | 25, 0.76 [0.58, 0.91] (0.57), 0.40 | 31, 0.81 [0.65, 0.93] (0.64), 0.53 | 0.76 |
| `jewellery` | Jewellery | 50 / 48 | 0.90 / 0.86 | 0.0467 / 0.0483 | 41, 0.88 [0.77, 0.97] (0.74), 0.72 | 42, 0.88 [0.78, 0.97] (0.75), 0.74 | 0.68 |
| `glasses` | Glasses; Sunglasses | 43 / 53 | 0.67 / 0.67 | — / — | — | — | — |
| `package` ᵖ | Box + 2 | 46 / 50 | 0.65 / 0.65 | — / — | — | — | — |
| `running` | Running + 2 | 53 / 41 | 0.83 / 0.83 | — / 0.0994 | — | 7, 0.86 [0.50, 1.00] (0.49), 0.11 | 0.00 |
| `cycling` | Cycling | 49 / 47 | 0.88 / 0.88 | 0.1018 / 0.0923 | 8, 1.00 [1.00, 1.00] (0.68), 0.16 | 24, 1.00 [1.00, 1.00] (0.86), 0.49 | 0.38 |
| `swimming` | Swimming | 46 / 50 | 0.60 / 0.60 | — / — | — | — | — |
| `skiing` | Skiing | 47 / 34 | 0.88 / 0.86 | 0.1002 / 0.1001 | 23, 0.87 [0.71, 1.00] (0.68), 0.43 | 23, 0.83 [0.67, 0.96] (0.63), 0.40 | 0.69 |
| `snowboarding` | Snowboarding | 25 / 35 | 0.84 / 0.87 | 0.1097 / 0.1080 | 9, 0.89 [0.62, 1.00] (0.57), 0.32 | 13, 0.92 [0.71, 1.00] (0.67), 0.48 | 0.53 |
| `surfing` | Surfing | 50 / 49 | 0.91 / 0.88 | 0.1017 / 0.1091 | 34, 0.91 [0.80, 1.00] (0.77), 0.62 | 25, 0.92 [0.80, 1.00] (0.75), 0.46 | 0.72 |
| `climbing` | Climbing; Rock climbing | 45 / 42 | 0.89 / 0.91 | 0.0998 / 0.1010 | 33, 0.88 [0.76, 0.97] (0.73), 0.64 | 32, 0.91 [0.79, 1.00] (0.76), 0.64 | 0.79 |
| `football` | Football + 3 | 51 / 52 | 0.82 / 0.85 | 0.0943 / 0.0934 | 19, 0.84 [0.65, 1.00] (0.62), 0.31 | 29, 0.83 [0.69, 0.96] (0.65), 0.47 | 0.62 |
| `basketball` | Basketball (Sport) | 56 / 42 | 0.96 / 0.95 | 0.0880 / 0.0818 | 46, 0.96 [0.88, 1.00] (0.85), 0.79 | 55, 0.89 [0.80, 0.97] (0.78), 0.88 | 0.83 |
| `tennis` | Tennis | 52 / 37 | 0.89 / 0.92 | 0.0924 / 0.1038 | 51, 0.86 [0.76, 0.95] (0.74), 0.85 | 32, 0.91 [0.79, 1.00] (0.76), 0.56 | 0.58 |
| `dancing` | Dance | 58 / 46 | 0.81 / 0.76 | — / — | — | — | — |
| `fishing` | Fishing | 55 / 34 | 0.90 / 0.92 | 0.0973 / 0.1026 | 47, 0.89 [0.80, 0.98] (0.77), 0.76 | 42, 0.90 [0.80, 0.98] (0.78), 0.69 | 0.85 |
| `sailing` | Sailing; Sailing (Sport) | 40 / 47 | 0.86 / 0.84 | 0.0917 / 0.0985 | 44, 0.84 [0.73, 0.95] (0.71), 0.93 | 34, 0.85 [0.71, 0.97] (0.70), 0.72 | 0.78 |
| `hiking` | Hiking; Backpacking | 26 / 29 | 0.87 / 0.90 | 0.0941 / 0.1000 | 17, 0.88 [0.70, 1.00] (0.66), 0.58 | 15, 0.93 [0.78, 1.00] (0.70), 0.54 | 0.86 |
| `art and media` ⁿ (top) | Art | 44 / 42 | 0.56 / 0.59 | — / — | — | — | — |
| `painting` | Painting | 40 / 50 | 0.71 / 0.68 | 0.0708 / 0.0830 | 16, 0.69 [0.43, 0.92] (0.44), 0.28 | 10, 0.80 [0.50, 1.00] (0.49), 0.20 | 0.44 |
| `drawing` | Drawing; Sketch | 47 / 50 | 0.81 / 0.83 | 0.0571 / 0.0606 | 25, 0.80 [0.63, 0.95] (0.61), 0.43 | 18, 0.94 [0.83, 1.00] (0.74), 0.36 | 0.65 |
| `sculpture` | Sculpture; Statue | 50 / 57 | 0.66 / 0.70 | 0.0834 / 0.0758 | 16, 0.69 [0.45, 0.91] (0.44), 0.22 | 24, 0.79 [0.62, 0.95] (0.60), 0.38 | 0.55 |
| `mural` | Mural | 40 / 38 | 0.82 / 0.81 | 0.0859 / 0.0791 | 17, 0.88 [0.72, 1.00] (0.66), 0.38 | 20, 0.85 [0.68, 1.00] (0.64), 0.42 | 0.58 |
| `poster` | Poster | 45 / 36 | 0.53 / 0.56 | 0.0568 / 0.0525 | 3, 0.67 [0.00, 1.00] (0.21), 0.04 | 2, 1.00 [1.00, 1.00] (0.34), 0.04 | 0.43 |
| `comic` | Comics; Comic book | 33 / 52 | 0.82 / 0.84 | — / — | — | — | — |
| `sky and weather` ⁿ (top) | Sky | 47 / 35 | 0.58 / 0.52 | — / — | — | — | — |
| `sunset` | Sunset | 58 / 28 | 0.90 / 0.90 | 0.0776 / 0.0841 | 38, 0.89 [0.78, 0.98] (0.76), 0.59 | 31, 0.94 [0.83, 1.00] (0.79), 0.50 | 0.71 |
| `night sky` ᵇ | Night | 50 / 24 | 0.81 / 0.80 | 0.0686 / 0.0660 | 21, 0.86 [0.70, 1.00] (0.65), 0.36 | 22, 0.77 [0.60, 0.95] (0.57), 0.34 | 0.64 |
| `clouds` | Cloud | 43 / 62 | 0.28 / 0.28 | — / — | — | — | — |
| `rainbow` | Rainbow | 23 / 29 | 0.92 / 0.94 | 0.0811 / 0.0876 | 21, 0.86 [0.67, 1.00] (0.65), 0.78 | 20, 0.85 [0.65, 1.00] (0.64), 0.74 | 0.83 |
| `storm` | Storm; Thunderstorm | 43 / 25 | 0.87 / 0.81 | 0.1024 / 0.1121 | 17, 0.94 [0.80, 1.00] (0.73), 0.37 | 6, 1.00 [1.00, 1.00] (0.61), 0.14 | 0.35 |
| `rain` | Rain | 36 / 35 | 0.73 / 0.73 | 0.1041 / 0.1011 | 12, 0.75 [0.50, 1.00] (0.47), 0.25 | 17, 0.71 [0.48, 0.92] (0.47), 0.33 | 0.50 |
| `snowfall` ᵖ | Blizzard; Winter storm | 46 / 38 | 0.81 / 0.78 | 0.0751 / 0.0809 | 40, 0.80 [0.67, 0.92] (0.65), 0.70 | 38, 0.76 [0.63, 0.89] (0.61), 0.63 | 0.64 |


- **Read the count before the number**, as in §3.10.3 and more so: `duck` holds 1.00 on ONNX
  with nine firing images, and its Wilson lower bound is 0.70.
- **The floor rule is optimistic here too.** Pooled over the labels with a floor, held-out
  precision is 0.856 (ONNX) and 0.860 (Core ML) against the target of 0.90; 74 of ONNX's 109 and
  65 of Core ML's 110 landed below it, and for 6 and 8 the bootstrap interval excludes it. At
  target 0.95: 60 and 62 floors, pooled 0.890 and 0.899.
- ⚠ **One floor is plainly a fluke of its calibration sample**: `slide` ᵖ on ONNX, placed at
  0.0383, fires on four of its held-out sample with one right, and on **19 %** of all held-out
  images. A floor placed where ten calibration images happened to line up is not a floor.
- **Recall is lower than on COCO**: the median label with a floor recalls 43 % (ONNX) and 46 %
  (Core ML).
- **Best** (ONNX AP ≥ 0.94): `farm animal` ᵖ, `dog`, `cat`, `mushroom`, `basketball`,
  `butterfly`, `library`, `group of people` ᵇ, `screenshot`, `kitchen`. **Worst**: `clouds`
  (0.28), `staircase`, `indoors` ᵖ, `poster`, `house`, `mountain`, `grass`, `shop interior` ᵇ
  (0.49 to 0.55).
- ⚠ **`clouds` scores below its base rate** (AP 0.28, 41 % positives): the images verified to
  hold no cloud score *higher* for "clouds" than the ones verified to hold one. Not looked into.
  The likeliest reading is §3.12.1's: its verified negatives are images a classifier took for
  clouds (fog, smoke, snow), and its positives are any photograph with sky in it.
- **The scenes are the weak family.** Of the ten scored `landscape` leaves, nine get no floor on
  either artifact (`ocean` ᵇ, `mountain`, `lake`, `river`, `snowy landscape` ᵇ, `field` ᵇ,
  `valley`, `cliff`, `canyon`) and `beach` gets one on ONNX only; so do not `tall building`,
  `house` and `parking` under `urban`, or `tree`, `grass` and `garden` under `plants`. The
  labels with a floor on both, by branch: animals 14, food and drink 15, activities 11, urban 8,
  transport 8, objects 8, indoors 6, sky and weather 6, plants 5, art and media 5, people 4,
  documents and screens 3 (`screenshot`, `handwriting`, `chart` ᵇ), events 2.
- **The bare word against the candidate file's prompt**, held-out AP, mean over 156 labels: 0.781
  against 0.771 on ONNX, 0.783 against 0.772 on Core ML; the bare word is ahead on 104 and 99 of
  the 156. Smaller than COCO's 0.716 against 0.692 (§3.10.7), and the same direction. Under the
  file's prompts 105 and 107 labels get a floor, 95 on both.

#### 3.12.6 Which labels hold a bar on the held-out half

Nothing here chooses a bar. For a bar of held-out precision 0.90, under floors placed for 0.90,
the number of labels that hold it, read three ways:

| Held-out precision ≥ 0.90 | ONNX | Core ML | both shipped artifacts |
|---|---|---|---|
| **Bare word.** Labels with a floor | 109 | 110 | 100 |
| as a point estimate | 35 | 45 | **25** |
| lower bound of the bootstrap interval | 8 | 14 | **4** |
| Wilson lower bound | 1 | 1 | **1** |
| **Candidate file's prompt.** Labels with a floor | 105 | 107 | 95 |
| as a point estimate | 44 | 46 | 26 |
| lower bound of the bootstrap interval | 15 | 13 | 8 |
| Wilson lower bound | 0 | 0 | 0 |

Bare word, both artifacts:

- **Point estimate (25)**: `people`, `dog`, `cat`, `cow`, `sheep`, `fish`, `deer`, `duck`,
  `farm animal` ᵖ, `burger`, `ice cream`, `fruit`, `vegetables`, `coffee`, `cocktail`, `beer`,
  `mushroom`, `construction site`, `library`, `ship`, `truck`, `screenshot`, `cycling`,
  `surfing`, `storm`. 24 exact, one proxy.
- **Bootstrap lower bound (4)**: `duck`, `farm animal` ᵖ, `construction site`, `cycling`.
  On one artifact only: `people`, `pasta`, `fruit`, `fountain` (ONNX); `dog`, `cat`, `deer`,
  `breakfast`, `cocktail`, `wine`, `plants`, `laptop`, `poster`, `storm` (Core ML).
- **Wilson lower bound (1)**: `farm animal` ᵖ (40 and 41 firing images, none wrong; 0.91).

⚠⚠ **Three of the bootstrap's four are there because nothing they fired on was wrong, on as few
as 8 images.** `duck` (9 and 11 firing), `construction site` (10 and 10) and `cycling` (8 and 24) have
an interval of [1.00, 1.00] and Wilson lower bounds of 0.68 to 0.86. A percentile bootstrap
cannot say less than the sample holds (§3.12.4); `poster` holds the bar on Core ML with **two**
firing images. That count is the reason the Wilson column exists, and the reason it is the one to
read.

⚠⚠ **And the Wilson column is nearly empty because of how many images there are, not only because
of the model.** A held-out sample has about 50 positives. A lower bound of 0.90 takes 35 firing
images with none wrong, or 53 with one: a label must recall seven tenths of its positives and
make no mistake at all. `dog` on Core ML fires on 47 with one wrong and reaches 0.89; `fruit` on
ONNX on 29 with none wrong, 0.88. **What this corpus can establish is which labels do not hold
0.90. It can confirm that one does only for a label that recalls seven tenths of its positives
without a single mistake**, which `farm animal` ᵖ alone did. A sample of 300 a label would do it
for the rest; `select --per-label 300` is that corpus, at about three times the images.

The same reading of §3.10's COCO run, bare word, which has 2 500 held-out images and far more
positives for the common labels: 9 labels at 0.90 or above on both artifacts as a point estimate,
3 by the bootstrap's lower bound (`wine` ᵖ, `truck`, `skateboarding` ᵖ), **none** by Wilson's.
`people` is the nearest, 0.909 and 0.907 over 793 and 778 firing images, lower bounds 0.887 and
0.885; `truck` holds the bootstrap's bar with nine firing images and has a Wilson lower bound of
0.70.

#### 3.12.7 The two artifacts

Every (image, label) cell of the held-out half, 12 902 images × 156 labels, each artifact under
its own floors, target 0.90. ⚠ Over **all** held-out images, in or out of a label's sample: which
labels an artifact would write needs no truth.

| Pair | both fire | only the first | only the second | Jaccard | disagreements within 0.005 / 0.01 of a floor |
|---|---|---|---|---|---|
| **ONNX vs Core ML, bare word** | 10 407 | 4 687 | 4 357 | **0.535** | 57 % / 79 % |
| ONNX vs fp32 | 10 520 | 4 574 | 4 540 | 0.536 | 56 % / 79 % |
| Core ML vs fp32 | 14 604 | 160 | 456 | **0.960** | 95 % / 100 % |
| ONNX vs Core ML, both under ONNX's floors | 12 716 | 2 378 | 2 040 | 0.742 | 89 % / 99 % |
| ONNX vs Core ML, target 0.95 | 5 278 | 1 386 | 1 264 | 0.666 | 66 % / 82 % |
| ONNX vs Core ML, each label over its own sample only | 2 430 | 507 | 483 | 0.711 | 71 % / 88 % |
| ONNX vs Core ML, candidate file's prompt | 11 215 | 2 940 | 3 624 | 0.631 | 62 % / 82 % |

- **0.54, where COCO gave 0.84** (bare word; §3.11.4). Three things differ, and they are not
  separated here: 156 labels and not 37, floors placed on 100 images a half and not 1 250, and
  images that were chosen because some label is present or nearly so.
- ⚠⚠ **Here one shared table agrees better than two: 0.742 against 0.535.** On COCO it was the
  other way round (0.693 against 0.784, §3.10.5). A per-artifact floor absorbs the per-label
  offset, and it also adds its own error: each floor is one draw from some 50 calibration
  positives, and two floors drawn apart disagree by more than the offset they were meant to
  remove. That is an inference from the two tables; what is measured is that with samples this
  small, per-artifact calibration made the two platforms agree *less*. `inference-service.md`
  §39.1 chose per-artifact tables on COCO's result; it holds only for floors placed on enough
  images.
- **Nineteen labels have a floor on one artifact only** (ten on Core ML, nine on ONNX) and
  account for 4 050 of the 19 451 cells either wrote. `slide` ᵖ alone is 2 504 of them. Over the
  100 labels with a floor on both the Jaccard is 0.676.
- **Fewer of the disagreements are at a floor**: 79 % within 0.01 cosine, where COCO had 94 %.
  The rest are the one-sided labels, which disagree at any distance.
- **Core ML is fp32 again**, 0.960, a little under COCO's 0.988: with 156 labels there are more
  floors for an image to sit beside.
- **Which tower**: ONNX with only its vision tower quantised agrees with fp32 on 0.591, with only
  its text tower on 0.784, with both on 0.536. Under the bare word the vision tower is the larger
  part; under the file's prompts the two are equal again (0.715 and 0.700), as on COCO.
- **Score difference, ONNX − Core ML**, bare word: a per-label spread of 0.0060 (median; 0.0046
  to 0.0116) and an offset from −0.007 to +0.024. §3.11.2's camera roll gave 0.0073.
- **The full rule** (`selectLabels`, margin 0.01, the 156 labels as the vocabulary, unscored
  parents at +∞): the two shipped artifacts write the identical set for **55.8 %** of held-out
  images and for 34.3 % of those where either writes anything. Core ML against fp32: 96.5 % and
  94.0 %. Against truth, over the samples, the rule gives precision 0.871 at recall 0.290 on
  ONNX, where independent floors give 0.856 at 0.330.

**On the camera roll.** §3.11's 554 photographs under these floors (`survey --floors`, bare word;
⚠ an instrument, no truth, and every share of a count rounded to the nearest ten, §3.11.1): the
two shipped artifacts write the identical label set for about 57 % of the photographs, and for
**about 35 %** of those either labels; Core ML against fp32 is withheld, so few differ. About 56
to 60 % of photographs get at least one label. The labels firing most (fp32 / ONNX / Core ML):
`urban` ⁿ about 14 % / 11 % / 14 %; `people` 14 % / **5 %** / 14 %; `shoes` 13 % / 11 % / 13 %;
`animals` 13 % / 9 % / 11 %; `flower` 13 % / 9 % / 13 %; `toy` 11 % / 9 % / 11 %; and `slide` ᵖ on
**about 18 %** of them on ONNX alone. No label reaches §28's 25 %. On each artifact 17 labels
fire on ten photographs or more, about 110 on none, and the rest on fewer than ten.

#### 3.12.8 COCO's floors and Open Images' floors

Thirty-six labels are scored on both datasets. For `skiing`, `snowboarding`, `surfing`, `tennis`,
`wine` and `computer screen` the truth itself differs: COCO offered the equipment, Open Images
the activity (§3.12.3). Bare word, target 0.90, each floor placed on its own dataset's
calibration half.

| | ONNX | Core ML |
|---|---|---|
| A floor on both datasets | 26 of 36 | 27 of 36 |
| On COCO only | `wine`, `bicycle`, `furniture`, `book` | `bicycle` |
| On Open Images only | `dessert`, `vegetables`, `car` | `sandwich`, `dessert`, `vegetables`, `houseplant`, `car`, `computer screen`, `bag` |
| Open Images' floor minus COCO's, median | **+0.0107** | +0.0095 |
| … lowest and highest | −0.004 (`train`) … +0.043 (`surfing`) | −0.011 … +0.051 |
| … more than 0.01 apart / more than 0.02 | 13 / 7 | 14 / 9 |

For scale: §3.10.5's per-image noise between the two artifacts is 0.0076, and a floor moves
0.007 with the prompt (§3.11.4). **Between datasets it moves more than between platforms.**

What that does to a label, ONNX, held-out halves. ⚠ These four columns are not in a result file:
they apply one result's floors to the other's saved scores (`analysis/tables.py`, beside the
results).

| Label | COCO categories → Open Images classes | floor ONNX: COCO / Open Images | Open Images held-out under COCO's floor, ONNX P / R | under its own, ONNX P / R | COCO held-out under Open Images' floor, ONNX P / R | under its own, ONNX P / R |
|---|---|---|---|---|---|---|
| `people` | person → Person; People | -0.0089 / 0.0181 | 0.81 / 0.47 | 1.00 / 0.19 | 0.99 / 0.14 | 0.91 / 0.53 |
| `animals` | bird; cat; dog → Animal | 0.0224 / 0.0423 | 0.72 / 0.91 | 0.78 / 0.46 | 0.95 / 0.64 | 0.86 / 0.89 |
| `dog` | dog → Dog | 0.0547 / 0.0647 | 0.92 / 0.98 | 0.96 / 0.92 | 0.93 / 0.67 | 0.84 / 0.75 |
| `cat` | cat → Cat | 0.0491 / 0.0694 | 0.72 / 0.98 | 0.91 / 0.83 | 0.95 / 0.77 | 0.80 / 0.85 |
| `bird` | bird → Bird | 0.0625 / 0.0710 | 0.82 / 0.90 | 0.86 / 0.71 | 0.92 / 0.33 | 0.88 / 0.54 |
| `horse` | horse → Horse | 0.0570 / 0.0685 | 0.77 / 0.93 | 0.88 / 0.89 | 0.98 / 0.72 | 0.91 / 0.88 |
| `cow` | cow → Cattle; Dairy cow | 0.0729 / 0.0720 | 0.90 / 0.56 | 0.90 / 0.56 | 0.88 / 0.64 | 0.88 / 0.64 |
| `sheep` | sheep → Sheep | 0.0669 / 0.1002 | 0.69 / 0.90 | 0.90 / 0.52 | 1.00 / 0.63 | 0.82 / 0.85 |
| `food and drink` | banana; apple; sandwich → Food; Drink | — / — | — / 0.00 | — / 0.00 | — / 0.00 | — / 0.00 |
| `sandwich` | sandwich → Sandwich | 0.0941 / 0.0971 | 0.74 / 0.31 | 0.75 / 0.27 | 0.86 / 0.11 | 0.73 / 0.14 |
| `pizza` | pizza → Pizza | 0.0628 / 0.0788 | 0.71 / 0.97 | 0.79 / 0.97 | 0.95 / 0.75 | 0.88 / 0.84 |
| `dessert` | cake; donut → Dessert | — / 0.0670 | — / 0.00 | 0.71 / 0.26 | 0.67 / 0.22 | — / 0.00 |
| `cake` | cake → Cake | 0.0682 / 0.0816 | 0.91 / 0.71 | 0.94 / 0.31 | 0.86 / 0.31 | 0.92 / 0.60 |
| `fruit` | banana; apple; orange → Fruit | 0.0563 / 0.0656 | 0.87 / 0.76 | 1.00 / 0.64 | 0.98 / 0.42 | 0.91 / 0.62 |
| `vegetables` | broccoli; carrot → Vegetable | — / 0.0540 | — / 0.00 | 0.92 / 0.39 | 0.71 / 0.42 | — / 0.00 |
| `wine` | wine glass → Wine | 0.0646 / — | 0.70 / 0.59 | — / 0.00 | — / 0.00 | 1.00 / 0.16 |
| `houseplant` | potted plant → Houseplant | — / — | — / 0.00 | — / 0.00 | — / 0.00 | — / 0.00 |
| `transport` | bicycle; car; motorcycle → Vehicle; Transport; Mode of transport | 0.0299 / 0.0381 | 0.79 / 0.20 | 0.82 / 0.17 | 0.90 / 0.32 | 0.88 / 0.44 |
| `car` | car → Car | — / 0.0508 | — / 0.00 | 0.87 / 0.83 | 0.56 / 0.09 | — / 0.00 |
| `bicycle` | bicycle → Bicycle | 0.0680 / — | 0.64 / 0.53 | — / 0.00 | — / 0.00 | 0.89 / 0.33 |
| `motorcycle` | motorcycle → Motorcycle | 0.0531 / 0.0722 | 0.75 / 0.96 | 0.81 / 0.58 | 0.97 / 0.47 | 0.90 / 0.65 |
| `bus` | bus → Bus | 0.0665 / 0.0744 | 0.69 / 0.72 | 0.76 / 0.72 | 0.89 / 0.68 | 0.87 / 0.71 |
| `train` | train → Train | 0.0788 / 0.0747 | 0.89 / 0.64 | 0.83 / 0.72 | 0.92 / 0.88 | 0.93 / 0.80 |
| `boat` | boat → Boat | 0.0564 / 0.0777 | 0.76 / 0.76 | 0.84 / 0.33 | 1.00 / 0.43 | 0.89 / 0.61 |
| `airplane` | airplane → Airplane; Fixed-wing aircraft; Airliner | 0.0461 / 0.0600 | 0.77 / 0.91 | 0.87 / 0.70 | 0.91 / 0.83 | 0.79 / 0.92 |
| `truck` | truck → Truck | 0.0809 / 0.0813 | 0.93 / 0.31 | 0.93 / 0.29 | 1.00 / 0.08 | 1.00 / 0.08 |
| `computer screen` | tv → Computer monitor | — / — | — / 0.00 | — / 0.00 | — / 0.00 | — / 0.00 |
| `phone` | cell phone → Mobile phone; Smartphone; Telephone | 0.0568 / 0.0554 | 0.86 / 0.69 | 0.82 / 0.69 | 0.85 / 0.22 | 0.85 / 0.22 |
| `laptop` | laptop → Laptop | 0.0550 / 0.0769 | 0.65 / 0.66 | 0.88 / 0.30 | 1.00 / 0.19 | 0.89 / 0.53 |
| `bag` | backpack; handbag; suitcase → Bag; Handbag; Luggage and bags | 0.0598 / 0.0695 | 0.77 / 0.41 | 0.92 / 0.24 | 0.90 / 0.04 | 0.86 / 0.07 |
| `furniture` | chair; couch; bed → Furniture | 0.0562 / — | 0.80 / 0.17 | — / 0.00 | — / 0.00 | 0.81 / 0.07 |
| `book` | book → Book | 0.0353 / — | 0.78 / 0.53 | — / 0.00 | — / 0.00 | 0.40 / 0.05 |
| `skiing` | skis → Skiing | 0.0978 / 0.1002 | 0.86 / 0.51 | 0.87 / 0.43 | 0.95 / 0.72 | 0.96 / 0.79 |
| `snowboarding` | snowboard → Snowboarding | 0.1064 / 0.1097 | 0.86 / 0.48 | 0.89 / 0.32 | 1.00 / 0.20 | 0.88 / 0.28 |
| `surfing` | surfboard → Surfing | 0.0589 / 0.1017 | 0.71 / 0.92 | 0.91 / 0.62 | 1.00 / 0.66 | 0.91 / 0.96 |
| `tennis` | tennis racket → Tennis | 0.0612 / 0.0924 | 0.71 / 0.98 | 0.86 / 0.85 | 0.97 / 0.86 | 0.84 / 0.99 |

| Pooled over the labels that fire, ONNX (Core ML) | precision | recall | labels at 0.90 or above |
|---|---|---|---|
| Open Images, under its own floors | 0.864 (0.862) | 0.436 (0.453) | 11 of 29 (16 of 34) |
| Open Images, **under COCO's floors** | **0.769** (0.770) | 0.566 (0.546) | 4 of 30 (3 of 28) |
| COCO, under its own floors | 0.879 (0.882) | 0.414 (0.409) | 9 of 30 (12 of 28) |
| COCO, **under Open Images' floors** | **0.926** (0.925) | 0.263 (0.328) | 20 of 29 (23 of 34) |

- **A COCO floor is too low for Open Images**: the precision it was placed for, 0.90, arrives as
  0.77. `cat` goes from 0.91 to 0.72, `sheep` from 0.90 to 0.69, `laptop` from 0.88 to 0.65.
- **An Open Images floor is too high for COCO**: precision 0.93, and a third of the recall gone.
  `people` under Open Images' floor recalls 14 % of COCO's people photographs, not 53 %.
- **The direction is what §3.12.1 predicts.** Open Images' negatives are images a classifier took
  for the class; a floor has to sit higher to keep them out. COCO's negatives are everything
  else in an everyday scene. A library's negatives are neither.
- ⚠⚠ **So neither set of floors is a number to ship**, and the two together are the measurement of
  why: the same model, the same artifact, the same prompt and the same rule give floors a median
  of 0.011 apart and a pooled precision 0.10 apart, because the negatives differ. A floor is a property
  of the artifact, the runtime (§3.10.6), the prompt (§3.11.4) **and the population it was placed
  on**. The population a shipped floor will meet is a personal library, and no floor has been
  placed on one.

#### 3.12.9 Documents and screenshots: how thin

Verified positives / negatives in the whole pool of 143 494 images:

| | labels |
|---|---|
| Scored | `screenshot` 388 / 1 029, `document` 322 / 353, `handwriting` 217 / 129, `whiteboard` 87 / 66, `computer screen` 437 / 398, `sign with text` 482 / 216, `chart` ᵇ 289 / 234, `slide` ᵖ 109 / 506, and the top-tier `documents and screens` ᵖ 1 550 / 1 183 |
| Thin | `receipt` **48 / 32**, `ticket` 32 / 65, `menu` 57 / 36, `map` 195 / 31, `business card` **1 / 0**, `qr code` **4 / 0** |
| No class | `book page`, `form` |

⚠⚠ **And the scored ones are Flickr's.** Open Images is photographs people uploaded to a photo
site, most of them before 2017. What its 388 `Screenshot` images show was not looked at here; it is
unlikely to be a phone's screenshot of a chat, a ticket or a banking app, and its `Document` images
are unlikely to be a receipt photographed on a kitchen table. ⚠ That is an inference from where
the images come from. If it holds, the `screenshot` and `document` rows of §3.12.5 measure
something adjacent to what a camera roll holds, and the labels Tenshu wants most (§3.10.2) are the
ones this dataset serves worst.

**What a real set would take** (described, not built):

- **Screenshots.** Real ones, from real devices, in the languages and apps people use: a few
  hundred positives, and negatives that are hard in the way that matters (photographs *of* a
  screen, scanned pages, posters, memes with text). The maintainer's own are the obvious source
  and fall under §3.11.1's rule: read in place, aggregates only. `survey` already scores such a
  set without truth; with a list of which files are screenshots it becomes a third reader beside
  COCO's and Open Images', of the size of `read_open_images_labels`.
- **Documents.** Rendered pages (PDF to image) are cheap and exact, and are not photographs: a
  phone picture of a receipt has perspective, shadow and a table around it. A set needs both.
  Public candidates, ⚠ **none verified as §3.12.1 was**: CORD (receipts; its Hugging Face card
  lists `cc-by-4.0` and 1 000 images, and nothing else was checked), DocLayNet (80 863 pages; its
  licence was not established from its repository page), RVL-CDIP and RICO (not reached).
- ⚠⚠ **Not a generator.** Synthesising "a photo of a receipt" with a diffusion model and placing
  floors on the result would be circular: such generators are trained with text-image models of
  this family, so their images sit where this model already expects the words to be, and every
  score would flatter the label.

#### 3.12.10 What this does not establish

- ⚠⚠ **Web photographs, not camera rolls.** Flickr uploads, chosen by their authors, largely
  before 2017, and here chosen again by class. §3.11 is the only camera roll, and it has no truth.
- ⚠⚠ **A precision here is at the verified set's mix** of positives and hard negatives (§3.12.4).
  It is not the precision a library would see, in either direction.
- ⚠⚠ **51 labels are still unscored**: 39 thin, 12 with no class. Among them `landscape`,
  `receipt`, `qr code`, `objects`, `activities`.
- ⚠ **Broader, narrower and proxy rows measure the classes**, not the label (§3.12.3). `indoors` is
  one of them.
- **Truth has holes on the positive side**: Open Images says its verification does not eliminate
  false negatives. An image holding a waterfall nobody proposed is unverified, not negative, so it
  costs nothing here; an image wrongly verified absent would. Not measured.
- **A host, not a phone**, and **Pillow, not the apps' decoders**, as §3.10.9. And the images are
  the bucket's 1 024 px copies, which a phone's camera originals are not.
- **English prompts only.**
- **The mapping was written by the same hand that ran the evaluation**, in one sitting, and has
  not been reviewed. ⚠⚠ Seventeen of its rows are known to be unsound on the negative side
  (§3.12.3), `food and drink`, `documents and screens`, `urban`, `phone` and `football` among
  them.
- **No floor here is a recommendation** (§3.10.9), and §3.12.8 is the measurement of why.

Raw results (not in the repository): `~/tenshu-models/label-eval-wide-2026-10-11/` on the
maintainer's Mac. `open-images-bare/label-eval-result.json` sha256 `ea275df5…`,
`open-images-tsv/…` `265eb070…`; §3.10's vectors re-evaluated by this version, which adds the
Wilson bounds and the false-positive rates, `coco-bare-s2/…` `d3734160…` and `coco-tsv-s2/…`
`11404813…` (equal to §3.11's results in every field those hold); the camera roll under Open
Images' floors, `phone/survey-open-images/label-survey-result.json` `25a853b2…`. The selection
(`open-images-selection/`: the annotations extract, the image list, the chosen images' licence
and author rows, the record) and the 25 803 images are beside them; the resized-pixel cache was
deleted after embedding, as §3.10's was.

### 3.13 L5's larger sample: Open Images V7, the 23 candidates, 1 000 a label — measured 2026-10-15 (§3.13.4)

`inference-service.md` §39.7 (L5) made *passed* a held-out precision whose **Wilson lower bound
is ≥ 0.90**, on both shipped artifacts, bare word, proxies out: **23 candidates**. §3.12's samples
hold about 50 held-out positives a label, so they can refute that bar and almost never confirm it
(§3.12.6). This is the larger sample: the same dataset, the same rules, the 23 labels alone, and
up to **1 000** verified positives and 1 000 verified negatives a label where the pool has them.

⚠⚠ **Status: the corpus is built and pinned; nothing is embedded, so no verdict exists — ONNX's
included.** The run was a scheduled cloud session whose network policy denied
`huggingface.co` (the fp32 checkpoint) and `models.service.tenshu.app` (the catalog's ONNX pair).
No artifact was reachable, and none was stood in for. ⚠ It would not have run fp32 there in any
case: `embed` requires `--export` (the Core ML export directory) for `fp32` as well as `coreml`
(`embed_arguments`), so even an fp32 + ONNX run off the Mac needs a copy of that directory. ⚠ So
**every row of §3.13.3 is a sample size, not a result**, and every statement about what a label
will do is **Inferred** from §3.12 and from Wilson's arithmetic. The two shipped artifacts are
owed, on the Mac, by §3.13.5's commands; they ran on 2026-10-15 (§3.13.4). ⚠ **fp32 is run too.**
A trim to the shipped pair was merged the same day and is withdrawn: `evaluate` refuses a run with
no `vectors-fp32.npz` (`label-eval.py`, *"run `embed` for fp32"*), because fp32 is its reference
for AP, the agreement tables and the tower swap. `embed` adds an artifact to an existing run, so
fp32 was embedded afterwards into the same directory. On a loaded Mac it took 2 h 24 min, ONNX
and Core ML together 1 h 21 min.

| | |
|---|---|
| Labels | the 23 of §39.7: `people`, `animals`, `transport`; `dog`, `cat`, `bird`, `horse`, `cow`, `sheep`; `pizza`, `cake`, `fruit`; `bicycle`, `motorcycle`, `bus`, `train`, `boat`, `airplane`, `truck`; `phone`, `laptop`, `furniture`, `book`. **All 23 have a usable class** in `label-eval-open-images-mapping.tsv`; none was added or changed |
| Mapping | `label-eval-open-images-mapping-l5.tsv` sha256 `8effec50…`: the committed mapping (`606b35bc…`) with the 23 rows byte for byte and every other label `none`, so that `select` takes images for the 23 and `evaluate` scores the 23 |
| Inputs | the files §3.12 read, re-downloaded 2026-10-14 from the URLs of §3.12.1, same digests: classes `84a4373a…`, validation labels `92ddbdfc…`, test labels `917d3ca8…`; image information (with rotation) `ed93a0e1…` (validation), `de55c6b8…` (test) |
| Selection | `select --per-label 1000`, seed 20261010, pool `Rotation == 0.0` (143 494 images): **25 996 images** (6 253 validation, 19 743 test), split 12 998 / 12 998. Record `label-eval-open-images-l5-selection.json`, sha256 `36cecaa6…`; annotations extract `2cbd947f…` (434 012 rows). A second `select` with the same inputs wrote the same four files byte for byte |
| Corpus | manifest `label-eval-open-images-l5-corpus.txt`, id `73b1a56c…`, sha256 `b88bae72…`; pixels `393ae14a…` (Pillow 12.3.0 bicubic squash to 256²). ⚠ Computed on Linux x86-64; that the Mac's Pillow 12.3.0 wheel decodes these bytes to the same pixels is **not checked** |
| Images | fetched unsigned from `open-images-dataset` as `<split>-<image id>.jpg`, 8 at a time with retry and backoff, in 22.6 minutes with no failure. ⚠ Bytes stable: the 7 472 images also in §3.12's corpus (fetched for it on 2026-10-11) have the same sha256 and size in both manifests, all of them, and 150 drawn at random and fetched a second time came back byte for byte |
| Box | Linux x86-64 cloud container, 4 cores, 15 GB; Python 3.13, the pinned set installed (`requirements-label-eval.txt`: onnxruntime 1.20.0, Pillow 12.3.0, numpy 2.2.6, torch 2.7.0, transformers 4.53.3, tokenizers 0.21.4, sentencepiece 0.2.1, and coremltools 9.0, which installed on Linux). Only `select` and `manifest` ran |
| Overlap with §3.12 | 7 472 of the 25 996 images are in §3.12's corpus |

#### 3.13.1 What is the same as §3.12, and what is not

**The same** (§3.12.1–§3.12.2): human-verified labels only (`verification`, `crowdsource-verification`;
machine labels refused), CC BY 2.0 listing only (`select` refuses another), `Rotation` 0 only, the
selection order (sha256 of `"<seed>:<label>:<image id>"`), the split rule and seed, the 20-a-half
minimum, a label scored over its own sample and no other image.

**Not the same.**

- ⚠ **Only 23 labels are in the selection.** A run under the committed mapping would fill the other
  133 labels' samples with images taken for these 23, the base-rate error §3.12.2 describes;
  `evaluate` refuses that mapping for this corpus (the record pins the mapping it was selected
  with). So the result reads **OVERRIDDEN** twice over, *the mapping is not the committed mapping*
  and *the corpus manifest is not the committed one*, by construction and not by a fault.
- ⚠⚠ **The sample is no longer balanced.** At 100 a label every pool had both; at 1 000 most pools
  run out of verified negatives first (`pizza` has 85 in the whole pool, `sheep` 153, `phone`
  153). The positives' share of a held-out sample now runs from 0.49 (`animals`) to 0.83
  (`cat`), and **precision rises with that share at a fixed floor** (§3.12.4). A precision here and
  one in §3.12.5 are at different mixes and do not compare as they stand. The result carries each
  label's false-positive rate on its negatives, from which precision at any assumed prevalence π
  follows: `π·R / (π·R + (1 − π)·FPR)`. ⚠ The L5 verdict should be read at the sample's own mix
  **and** at 0.5, and a label that passes only at its own mix said so.
- **Same images, other halves.** At `--per-label 100` a label's sample here is exactly §3.12's for
  that label (same pool, same order), but `split` cuts the corpus at its median digest, so which
  of those images calibrate moves with the corpus: `dog`'s held-out half is 49 / 45 here and was
  48 / 45. `evaluate --per-label 100` over this corpus is §3.12's sample, not §3.12's figure.

#### 3.13.2 ⚠⚠ What the bar asks of a sample, and of a floor

Wilson's 95 % lower bound (`wilson`), held out. **Arithmetic, not measured:**

| wrong among the firing images | 0 | 1 | 2 | 3 | 5 |
|---|---|---|---|---|---|
| firing images for a lower bound ≥ 0.90 | 35 | 53 | 69 | 84 | 113 |

| true precision | 0.93 | 0.94 | 0.95 | 0.96 | 0.97 | 0.98 |
|---|---|---|---|---|---|---|
| firing images for a lower bound ≥ 0.90, at that precision | 385 | 217 | 139 | 97 | 71 | 55 |

⚠⚠ **Inferred: under the floor rule at target 0.90, a larger sample does not make the bar
reachable.** The rule places a floor at the lowest score whose *calibration* precision reaches the
target (§3.10.1), so the expected held-out precision at a 0.90 floor is about 0.90, and the lower
bound of a large sample converges on that from below. §3.12's one pass, `farm animal` ᵖ, was a
label with no wrong firing image at all. So a label clears L5 at target 0.90 only if its
precision just above the floor is well over 0.90 — a jump in the curve the floor sits on, not the
target itself. The result already computes floors for **0.95** as well (`--targets 0.9,0.95`, the
default); at 0.95 the held-out precision of a label is expected near 0.95, and the second table
says that takes some 139 firing images. ⭐ **Decided 2026-10-15: floors are placed for target
0.95** (`inference-service.md` §39.7). §3.13.4's verdicts are read at 0.95; 0.90 is reported beside
them, not as a verdict.

#### 3.13.3 Per label: the sample, before any score

Each label's sample, verified positives / negatives, from the selection with the harness's own
functions (`open_images_truth`, `label_samples`, `split`). *Pool* is the whole Rotation-0 pool.
*Fires needed at 0.95* is whether the held-out positives can supply the 139 firing images a true
precision of 0.95 needs (§3.13.2), and at what recall. ⚠ No score is in this table.

| Label | pool pos / neg | sample, calibration | sample, held out | positives' share held out | recall needed at true precision 0.95 |
|---|---|---|---|---|---|
| `people` | 28 533 / 20 176 | 489 / 491 | 511 / 509 | 0.50 | 0.26 |
| `animals` | 10 121 / 2 215 | 512 / 492 | 488 / 508 | 0.49 | 0.27 |
| `transport` | 13 259 / 5 303 | 502 / 486 | 498 / 514 | 0.49 | 0.27 |
| `dog` | 5 646 / 393 | 517 / 198 | 483 / 195 | 0.71 | 0.27 |
| `cat` | 1 202 / 218 | 499 / 113 | 501 / 105 | 0.83 | 0.26 |
| `bird` | 2 971 / 449 | 498 / 226 | 502 / 223 | 0.69 | 0.26 |
| `horse` | 965 / 311 | 454 / 154 | 511 / 157 | 0.76 | 0.26 |
| `cow` | 452 / 294 | 232 / 139 | 220 / 155 | 0.59 | 0.60 |
| `sheep` | 219 / 153 | 105 / 76 | 114 / 77 | 0.60 | cannot (> 1) |
| `pizza` | 231 / 85 | 129 / 45 | 102 / 40 | 0.72 | cannot (> 1) |
| `cake` | 966 / 371 | 469 / 183 | 497 / 188 | 0.73 | 0.27 |
| `fruit` | 2 317 / 2 371 | 507 / 510 | 493 / 490 | 0.50 | 0.27 |
| `bicycle` | 980 / 430 | 500 / 207 | 480 / 223 | 0.68 | 0.28 |
| `motorcycle` | 544 / 243 | 273 / 131 | 271 / 112 | 0.71 | 0.49 |
| `bus` | 339 / 169 | 161 / 82 | 178 / 87 | 0.67 | 0.74 |
| `train` | 379 / 214 | 180 / 115 | 199 / 99 | 0.67 | 0.66 |
| `boat` | 2 153 / 661 | 523 / 322 | 477 / 339 | 0.58 | 0.28 |
| `airplane` | 2 929 / 864 | 490 / 419 | 510 / 445 | 0.53 | 0.26 |
| `truck` | 1 141 / 588 | 516 / 289 | 484 / 299 | 0.62 | 0.27 |
| `phone` | 560 / 153 | 283 / 73 | 277 / 80 | 0.78 | 0.48 |
| `laptop` | 367 / 200 | 182 / 110 | 185 / 90 | 0.67 | 0.71 |
| `furniture` | 2 815 / 1 969 | 480 / 520 | 520 / 480 | 0.52 | 0.25 |
| `book` | 762 / 232 | 380 / 108 | 382 / 124 | 0.75 | 0.35 |

- **Cannot confirm at a true precision of 0.95 at all: `sheep` (114 held-out positives) and
  `pizza` (102)**, whose pools run out first. They can pass only at a higher true precision (55
  firing images at 0.98, a recall of about half). **Need a recall of 0.6 or more at 0.95**: `cow`,
  `bus`, `train`, `laptop`. The other seventeen need about a quarter to a half. ⚠ For scale,
  §3.12.5's ONNX recalls for these labels at a 0.90 floor run from 0.17 (`transport`) to 0.97
  (`pizza`), `bicycle`, `furniture` and `book` had no ONNX floor there at all, and a 0.95 floor
  recalls less than a 0.90 one.
- ⚠ **`phone` is one of §3.12.3's seventeen rows with unsound negatives** (`Mobile phone`;
  `Telephone`: either one verified absent counts). Its figures are a bound on that side, as
  §3.12.3 describes; it was not corrected here because the mapping row is the committed one.
- `people`, `animals`, `transport` are top-tier; `cow` is `Cattle`; `Dairy cow`, `airplane` three
  classes (§3.12.3). None is a proxy.

#### 3.13.4 Verdicts (2026-10-15, the maintainer's Mac)

**Measured**, all three artifacts, bare word, `--per-label 1000`, split seed 20261010 (12 998
calibration / 12 998 held-out). Result `OVERRIDDEN MEASURED`, naming the mapping and the corpus
manifest and nothing else (§3.13.1). The Mac's resized pixels matched the Linux digest, so the
manifest was not re-written. Toolchain as pinned (onnxruntime 1.20.0, coremltools 9.0, torch
2.7.0, Pillow 12.3.0). Archived: `scripts/inference/runs/2026-10-15-l5-open-images/` (result JSON
and tables, for 1 000 and 300 a label; the result for 1 000 is sha256 `7450d9383622…`).

**Read at the 0.95 floor (§3.13.2), against L5's bar.** Precision is at the sample's own mix;
*P@0.5* is the same firing reweighted to 50/50 (point only).

| Label | held-out pos / neg | ONNX fired / hits | ONNX P (Wilson low) | ONNX R | ONNX P@0.5 | Core ML fired / hits | Core ML P (Wilson low) | Core ML R | Core ML P@0.5 | L5 |
|---|---|---|---|---|---|---|---|---|---|---|
| `dog` | 483 / 195 | 482 / 463 | 0.961 (0.939) | 0.96 | 0.908 | 490 / 468 | 0.955 (0.933) | 0.97 | 0.896 | ✅ passes |
| `cat` | 501 / 105 | 503 / 481 | 0.956 (0.935) | 0.96 | 0.821 | 503 / 480 | 0.954 (0.932) | 0.96 | 0.814 | ✅ passes |
| `bird` | 502 / 223 | 360 / 346 | 0.961 (0.936) | 0.69 | 0.917 | 396 / 381 | 0.962 (0.938) | 0.76 | 0.919 | ✅ passes |
| `horse` | 511 / 157 | 507 / 472 | 0.931 (0.906) | 0.92 | 0.806 | 483 / 459 | 0.950 (0.927) | 0.90 | 0.855 | ✅ passes |
| `cake` | 497 / 188 | 282 / 272 | 0.965 (0.936) | 0.55 | 0.911 | 265 / 255 | 0.962 (0.932) | 0.51 | 0.906 | ✅ passes |
| `fruit` | 493 / 490 | 241 / 229 | 0.950 (0.915) | 0.46 | 0.950 | 199 / 190 | 0.955 (0.916) | 0.39 | 0.955 | ✅ passes |
| `train` | 199 / 99 | 76 / 75 | 0.987 (0.929) | 0.38 | 0.974 | 137 / 125 | 0.912 (0.853) | 0.63 | 0.838 | undecided |
| `cow` | 220 / 155 | 152 / 139 | 0.914 (0.859) | 0.63 | 0.883 | 156 / 149 | 0.955 (0.910) | 0.68 | 0.937 | undecided |
| `airplane` | 510 / 445 | 52 / 51 | 0.981 (0.899) | 0.10 | 0.978 | 24 / 24 | 1.000 (0.862) | 0.05 | 1.000 | undecided |
| `book` | 382 / 124 | 92 / 89 | 0.967 (0.908) | 0.23 | 0.906 | 175 / 165 | 0.943 (0.898) | 0.43 | 0.843 | undecided |
| `phone` | 277 / 80 | 209 / 196 | 0.938 (0.897) | 0.71 | 0.813 | 218 / 204 | 0.936 (0.895) | 0.74 | 0.808 | undecided |
| `bicycle` | 480 / 223 | 109 / 101 | 0.927 (0.862) | 0.21 | 0.854 | 189 / 178 | 0.942 (0.899) | 0.37 | 0.883 | undecided |
| `bus` | 178 / 87 | 120 / 114 | 0.950 (0.895) | 0.64 | 0.903 | 128 / 120 | 0.938 (0.882) | 0.67 | 0.880 | undecided |
| `boat` | 477 / 339 | 122 / 115 | 0.943 (0.886) | 0.24 | 0.921 | 35 / 33 | 0.943 (0.814) | 0.07 | 0.921 | undecided |
| `motorcycle` | 271 / 112 | 178 / 165 | 0.927 (0.879) | 0.61 | 0.840 | 190 / 177 | 0.932 (0.886) | 0.65 | 0.849 | undecided |
| `sheep` | 114 / 77 | 59 / 57 | 0.966 (0.885) | 0.50 | 0.951 | 34 / 33 | 0.971 (0.851) | 0.29 | 0.957 | undecided |
| `people` | 511 / 509 | 91 / 85 | 0.934 (0.864) | 0.17 | 0.934 | 126 / 117 | 0.929 (0.870) | 0.23 | 0.928 | undecided |
| `animals` | 488 / 508 | 81 / 76 | 0.938 (0.864) | 0.16 | 0.941 | 151 / 144 | 0.954 (0.907) | 0.30 | 0.955 | undecided |
| `laptop` | 185 / 90 | 38 / 37 | 0.974 (0.865) | 0.20 | 0.947 | 12 / 12 | 1.000 (0.758) | 0.06 | 1.000 | undecided |
| `pizza` | 102 / 40 | 103 / 93 | 0.903 (0.830) | 0.91 | 0.785 | 103 / 93 | 0.903 (0.830) | 0.91 | 0.785 | undecided |
| `furniture` | 520 / 480 | 12 / 11 | 0.917 (0.646) | 0.02 | 0.910 | no floor | — | — | — | ❌ refuted (no floor on Core ML) |
| `truck` | 484 / 299 | no floor | — | — | — | no floor | — | — | — | ❌ refuted (no floor) |
| `transport` | 498 / 514 | no floor | — | — | — | no floor | — | — | — | ❌ refuted (no floor; none at 0.90 either) |

- ⭐ **Six pass as L5 reads today: `dog`, `cat`, `bird`, `horse`, `cake`, `fruit`.** 14 are
  undecided at this size and three are refuted. No label's Wilson *upper* bound is below 0.90, so
  none is refuted on precision; the three refusals are labels with no 0.95 floor.
- ⚠⚠ **The 50/50 reading takes five of the six away.** The samples' positive share runs
  0.50–0.83 (§3.13.1), and the passes are mostly the imbalanced ones: `cat` is 83 % positive.
  Reweighted to 50/50, `cat` reads 0.82 / 0.81 and `horse` 0.81 / 0.86 (point); `dog`, `bird` and
  `cake` read 0.90–0.92 point with an approximate Wilson low of 0.87–0.89; **only `fruit`, sampled
  at 50/50 already, keeps a low of ~0.92.** [I] The 50/50 low is approximate: the negatives are
  reweighted, not resampled. ⭐ Decided 2026-10-23 (`inference-service.md` §39.7): the six that pass at
  the sample's own mix are the candidates for a personal-library check, and that check, not either
  reading here, is the gate.
- **At `--per-label 300`** (a subsample of this corpus): one label passes, six are refuted (no
  floor). What 1 000 decides, 300 mostly cannot: the sample size, not the model, was the binding
  constraint at §3.12.
- **fp32 against the shipped pair** (held-out, target 0.95): micro-precision 0.949 / 0.948 / 0.948
  (fp32 / ONNX / Core ML), labels with a floor 20 / 21 / 20. Conversion moves single labels
  (`airplane`, `boat`, `train` fire very differently on the two shipped artifacts) but not the
  pooled figure.
- ⚠ **Open Images' negatives are hard ones** (§3.12.1: images some classifier took for the
  class), so precision on a library is not this precision, in either direction. §39.4 item 19
  (floors placed on a personal library) still stands for every label that passes here.

#### 3.13.5 Reproduce, and run, on the maintainer's Mac

Everything below is pinned by the committed manifest: the 25 996 image ids with the sha256 and
size of the bytes fetched, the annotations extract, the class descriptions and the selection
record by digest. `embed` checks every image against the manifest and refuses a byte that moved.

```bash
cd ~/Drive && git checkout measure/l5-open-images-wide   # or master, once merged
python3.13 -m venv venv && ./venv/bin/pip install -r scripts/inference/requirements-label-eval.txt

# 1. Open Images metadata, no account (§3.12.1); the three §3.12 files keep §3.12's digests.
mkdir -p ~/oi-l5/meta && cd ~/oi-l5/meta
for u in v7/oidv7-class-descriptions.csv v7/oidv7-val-annotations-human-imagelabels.csv \
         v7/oidv7-test-annotations-human-imagelabels.csv \
         2018_04/validation/validation-images-with-rotation.csv 2018_04/test/test-images-with-rotation.csv; do
  curl -fLO "https://storage.googleapis.com/openimages/$u"; done
shasum -a 256 *        # 84a4373a… de55c6b8… 917d3ca8… 92ddbdfc… ed93a0e1…
cd ~/Drive

# 2. The selection: deterministic, and must reproduce the committed record byte for byte.
./venv/bin/python scripts/inference/label-eval.py select \
  --mapping scripts/inference/label-eval-open-images-mapping-l5.tsv \
  --classes ~/oi-l5/meta/oidv7-class-descriptions.csv \
  --annotations ~/oi-l5/meta/oidv7-val-annotations-human-imagelabels.csv \
  --annotations ~/oi-l5/meta/oidv7-test-annotations-human-imagelabels.csv \
  --image-info ~/oi-l5/meta/validation-images-with-rotation.csv \
  --image-info ~/oi-l5/meta/test-images-with-rotation.csv \
  --per-label 1000 --out ~/oi-l5/selection
cmp ~/oi-l5/selection/selection.json scripts/inference/label-eval-open-images-l5-selection.json && echo same

# 3. The images. 7 472 are already in §3.12's corpus: copy those first, fetch the rest.
mkdir -p ~/oi-l5/images
awk -F'\t' 'NF==3 {print $3}' scripts/inference/label-eval-open-images-l5-corpus.txt > ~/oi-l5/names.txt
( cd /path/to/section-3.12-images && xargs -I{} cp -n {} ~/oi-l5/images/ < ~/oi-l5/names.txt 2>/dev/null ) || true
xargs -P 8 -I{} sh -c 'n={}; [ -f ~/oi-l5/images/$n ] || curl -sSf --retry 5 --retry-delay 2 \
  -o ~/oi-l5/images/$n "https://open-images-dataset.s3.amazonaws.com/${n%%-*}/${n#*-}"' < ~/oi-l5/names.txt

# 4. Embed all three artifacts (none ran in the cloud). ⚠ fp32 is required: `evaluate` refuses
#    without it (§3.13 above).
./venv/bin/python scripts/inference/label-eval.py embed \
  --corpus-manifest scripts/inference/label-eval-open-images-l5-corpus.txt --corpus ~/oi-l5/images \
  --export ~/tenshu-models/coreml-export-3fd1918106d4 --source /path/to/siglip-base-patch16-256-multilingual \
  --onnx-vision /path/to/vision_model_uint8.onnx --onnx-text /path/to/text_model_int8.onnx \
  --artifacts fp32,onnx,coreml --threads 4 --out ~/tenshu-models/label-eval-l5-2026-10-14/run

# 5. Evaluate, bare word: the full sample, and the 300 a label subsample.
for n in 1000 300; do
./venv/bin/python scripts/inference/label-eval.py evaluate --truth open-images \
  --run ~/tenshu-models/label-eval-l5-2026-10-14/run \
  --out ~/tenshu-models/label-eval-l5-2026-10-14/per-label-$n --per-label $n \
  --corpus-manifest scripts/inference/label-eval-open-images-l5-corpus.txt \
  --mapping scripts/inference/label-eval-open-images-mapping-l5.tsv \
  --annotations ~/oi-l5/selection/open-images-labels.csv --selection ~/oi-l5/selection/selection.json \
  --classes ~/oi-l5/meta/oidv7-class-descriptions.csv --prompt bare
done
```

Expected last line: `OVERRIDDEN` (§3.13.1), naming the mapping and the manifest and nothing
else. Any other override — a vector file off the pins, Core ML off `cpuAndNeuralEngine` — is a
fault. ⚠ **Other pixels stop step 4 instead**: under the pins `embed` refuses (*"the resized
pixels are …, the manifest recorded …"*), and the digest here was computed on Linux (§3.13's
table). If it does, run `manifest --truth open-images --selection ~/oi-l5/selection --corpus
~/oi-l5/images --out scripts/inference/label-eval-open-images-l5-corpus.txt` on the Mac: the image lines and the id cannot move, only `pixels=`. Commit that
line and say so here. If the result JSON and the tables `evaluate` writes beside it together
are under 5 MB, they go to `scripts/inference/runs/2026-10-14-l5-open-images/`; otherwise the
tables, and the result's sha256 here. (As run, 1.5 MB:
`scripts/inference/runs/2026-10-15-l5-open-images/`.)

#### 3.13.6 What this does not establish

Nothing yet about any label's precision: no artifact ran. When it has run, still not:

- ⚠⚠ **Web photographs, not camera rolls** (§3.12.10). Flickr uploads, chosen by class.
- ⚠⚠ **A precision at the verified set's mix**, now unbalanced and per-label different
  (§3.13.1), against hard negatives (§3.12.1). Not a library's precision, in either direction.
- ⚠ **Both artifacts or no verdict.** L5 is on both; a run of one platform is provisional.
- **A host, not a phone** (§3.10.6: another onnxruntime moves floors as much as the platforms do),
  and **Pillow, not the apps' decoders**, on the bucket's ≤ 1 024 px copies.
- **English prompts only**; **no floor here is a recommendation** (§3.12.8, §39.4 item 19).
- **The mapping is §3.12's, unreviewed**, and `phone`'s negatives are partly unsound (§3.12.3).
- **Not a recall bar, and not the parents.** L5 left both open (§39.7).

## 4. Where the bytes live

Static object storage behind a CDN, no serving compute (IN-D10 / D1). The **proxy** owns the
canonical store and the download manager, so apps stay INTERNET-free (D5/D6).

⚠⚠ A model is a **device-local cache**, never realm content: it is a public artifact any device can
re-fetch, so syncing it would push hundreds of megabytes through the relay and charge it to the
user's quota for nothing. It belongs in the local-cache tier (`retention-quota-model.md` P2) where
it is visible and evictable.

---

## 5. Face models — the IN-6 survey (2026-09-21)

**Nothing clears the bar**, and §3.1.2's meta-lesson held for the fourth time: *"All four checks found
the deciding fact below the model card."* Here it was in a training-data README line.

⚖️ The decision this survey serves is `photos-people-and-pets.md` §10; this section is the evidence.

### 5.1 ⚠⚠ Both halves have the same owner, and that is the finding

IN-6 needs two checkpoints: a **detector** emitting a box plus five landmarks, and an **identity
embedder**. They fail for the same reason, traced link by link:

1. **WIDER FACE ships no landmark annotations at all.** The 5-point annotations everyone trains on
   were produced by **RetinaFace (Deng et al., 2019)** — an InsightFace project — which annotated
   ~76k WIDER FACE faces by hand.
2. InsightFace distributes them as `labelv2.txt` from SCRFD's README.
3. `ShiqiYu/libfacedetection.train` — the repo that trains **YuNet** — states verbatim:
   *"The `labelv2` annotations come from SCRFD."* The same dependency is documented for
   Ultra-Light (*"the cleaned widerface labels provided by Retinaface"*), biubug6, yolov5-face and
   CenterFace.
4. InsightFace's licence, verbatim: *"The code of InsightFace is released under the MIT License.
   There is no limitation for both academic and commercial usage. **The training data containing the
   annotation (and the models trained with these data) are available for non-commercial research
   purposes only.**"* Its `model_zoo/README.md` is blunter: *"ALL models are available for
   non-commercial research purposes only."*

⚠ So a candidate like YuNet presents as *MIT from the model's own author, over annotations whose
distributor says models trained with them are non-commercial* — two contradictory claims about one
artifact. ⚠⚠ **This is structural for the class, not specific to YuNet:** WIDER FACE has no native
landmarks, so *any* WIDER-FACE-trained 5-landmark detector needs InsightFace's annotations.

### 5.2 The corpus wall

Every open identity embedder traces to one of four corpora, and all four are closed:

| Corpus | Status |
|---|---|
| **MS-Celeb-1M / MS1MV2 / MS1MV3** | Deleted by Microsoft in 2019 after an FT investigation into ~10M scraped faces. MSR-LA: *"You may not use or distribute this Data or any derivative works in any form for commercial purposes."* ⚠ No live licensor to negotiate with. |
| **CASIA-WebFace** | *"Researcher shall use the Database only for non-commercial research and educational purposes."* No public-distribution grant at all. |
| **VGGFace2** | CC BY-**NC**-SA 4.0; Oxford withdrew the downloads and the licence text with them. |
| **WebFace260M** (and its 42M/12M/4M/600K subsets) | Academic-only, signed agreement, bars redistribution. |

⭐ Put in §3.1.3's terms — *"SigLIP is hard to **describe**, LAION is hard to **defend**"* —
**MS-Celeb-1M is both**, and it is the one a privacy-focused product would least want to name,
because its own publisher withdrew it over exactly the concern this product claims to address.

⚠⚠ **The industry's practice is not a legal basis, and it is now quantified.** Of 21 GitHub
repositories found containing models pre-trained on MS-Celeb-1M, **18 were released under commercial
licences** and only 3 carried the original designation (arXiv 2108.02922). Expect to be told
"everyone ships these"; that is the number to answer with.

### 5.3 ⚠⚠ Five instances of a redistributor granting what it does not own

§3.1.3's Trap 1 was *"one string in a config is a licence decision."* These are the same shape one
level up — **a redistributor's label is not the rightsholder's grant** — and the pattern recurred so
often it is the survey's most transferable finding.

- **fal/AuraFace-v1** — see §5.4, where a hash comparison settles it.
- **ONNX Model Zoo / OpenVINO ArcFace** — tagged Apache-2.0 while the same card says it was trained
  on *"Refined MS-Celeb-1M"* and derives from InsightFace.
- **OpenCV Zoo SFace** — Apache-2.0 applied by OpenCV over an upstream (`zhongyy/SFace`) with **no
  licence at all** (API `license: null`, `LICENSE` 404). ⚠ Note the asymmetry with YuNet, whose
  per-model `LICENSE` is MIT **naming Shiqi Yu**: one is a grant from the rightsholder, the other a
  bare template with the copyright line never filled in. ⚠⚠ Worse: `zhongyy/SFace` **releases no
  pretrained models at all** and its paper contains no MobileFaceNet, so the shipped 128-d artifact
  has no documented origin whatsoever. Three public clearance questions (`opencv_zoo#313`, `opencv_zoo#124`,
  `opencv#21192`) — none answered.
- **FaceX** — claims *"trained from scratch by us"*; its `download_weights.sh` pulls Idiap's
  **EdgeFace** (CC-BY-NC-SA) and InsightFace's **det_500m**, and `training/` contains no
  face-recognition training code.
- **Vec2Face** — MIT, but the same repo-level tag also covers `arcface-r100-glint360k.pth` and a
  WebFace4M dataset file, which the author cannot license. ⭐ The `hsface*` weights are the half that
  *is* clean (synthetic corpus), and one of them is now exported and measured — §5.11.

⭐ **Precedent that the argument lands:** InstantID was publicly challenged — *"this is non commercial
usage only and cannot be Apache 2.0 if it is using Insight Face"* — and conceded: *"We agree, we plan
to train on other face encoders that support commercial license."* Still unresolved two years later.

### 5.4 ⭐ AuraFace, and the hash test that settles it

`fal/AuraFace-v1` is the only candidate claiming **commercially-licensed training data**
(*"trained on a commercial dataset comprising face images from various sources"* — no dataset named,
no vendor named). R100/ArcFace, 512-d, standard `arcface_dst` alignment, buffalo_l-class accuracy
(LFW 0.9965), ready ONNX.

⚖️ **The commercial claim's primary source** is fal's announcement, [`https://huggingface.co/blog/isidentical/auraface`](https://huggingface.co/blog/isidentical/auraface)
(community article, 2024-08-26, re-read 2026-09-24): *"Unlike the original ArcFace, which has
non-commercial restrictions due to its training data, AuraFace can be used in commercial projects
without limitations."* ⚠ It names no dataset. It describes the corpus only as smaller than ArcFace's,
and it invites anyone with *"access to diverse face data"* to get in touch, so how the faces were
sourced is undocumented. ⚠ A commenter under the post asks *"so is this really commerical usable ?"*
and has no reply. This is a public statement of intent, not a grant or a warranty.

Hash-compared file by file against InsightFace's `antelopev2`:

| File | Result |
|---|---|
| `1k3d68.onnx`, `2d106det.onnx`, `genderage.onnx`, `scrfd_10g_bnkps.onnx` | **byte-identical** |
| `glintr100.onnx` | **different** — different digest, +28,817 bytes |

⭐ **So the recognition weight is genuinely fal's own** — the only model in this survey where a
"trained it ourselves" claim survives a byte-level check. ⚠⚠ **And the same repository is the fifth
instance of §5.3**: four files that are provably InsightFace's non-commercial weights sit under a
repo-level `license: apache-2.0`. The `LICENSE.md` is a copy-paste from choosealicense.com — front
matter (`title:`, `spdx-id:`, `redirect_from:`) intact, copyright appendix left as
`[yyyy] [name of copyright owner]` — so **fal never names itself as grantor**. Provenance questions
have gone unanswered since September 2025 across three threads. ⚠ The recognition file is still named
`glintr100.onnx`, InsightFace's name for the Glint360K-trained R100, on a model whose selling point is
not being trained on Glint360K.

⭐ **Measured on a device 2026-09-24**: `photos-people-and-pets.md` §12.8a. The file measured is
sha256 `a7933ea5…25c60` at repo commit `af6d057c`, and it groups the verified library identically to
HSFace. ⭐ An int8 QDQ export (66 MB, sha256 `1b7b3c18…89a9a`) runs on the Android AAR at ~60 ms per
face with fp32↔int8 cosine 0.994 (§12.8b); `scripts/inference/quantise-face-embedder.py` reproduces it.

⭐⭐ **Chosen 2026-09-24** (`photos-people-and-pets.md` §10 Q1): shipped as the int8 export above, on
fal's public claim; the written confirmation below is deferred until the product has traction.

⚠ Mirror `glintr100.onnx` **only**, never the repo, and obtain written confirmation of
(a) from-scratch training, (b) datasets and licence basis, (c) that Apache-2.0 extends to CDN
redistribution and quantisation. Apache-2.0 §§8–9 disclaim all warranty, so the risk sits with us.

### 5.5 Two candidates eliminated on facts other than licence

- ⚠⚠ **BlazeFace / MediaPipe emits the wrong six points.** Google's docs: *"left eye, right eye, nose
  tip, **mouth**, left eye tragion, right eye tragion"* — a mouth **centre**, not the two corners, so
  it cannot produce the 5-point set alignment needs. ⚖️ And its card — which holds **the only clean
  corpus in this survey**, *"consented images of people using a mobile AR application"*,
  fairness-stratified — also says: *"Any form of surveillance or identity recognition is explicitly
  out of scope and not enabled by this technology."* ⚠ This closes the escape route someone will
  propose: *BlazeFace for detection, recover the five points from Face Mesh* clears the corpus wall
  and hits the posture wall.
- ⚠ **OpenVINO `face-reidentification-retail-0095`** ships **no ONNX** — IR only, and IR→ONNX is not
  a supported path. Genuinely Intel-trained, but a hard blocker for ORT on Android and JVM.

### 5.6 ⭐⭐ What is banked regardless of the decision

**The 5-point alignment template is verified three independent ways and they agree exactly:**

```
(38.2946, 51.6963)  (73.5318, 51.5014)  (56.0252, 71.7366)  (41.5493, 92.3655)  (70.7299, 92.2041)
```

at 112×112 — OpenCV's `FaceRecognizerSF::alignCrop` (Apache-2.0), InsightFace's `arcface_dst`
(**MIT — the *code* is unrestricted; only the models are not**), and AdaFace/EdgeFace/HyperFace's
`REFERENCE_FACIAL_POINTS` at 96×112 squared by adding 8 to x. ⭐ It is the ArcFace canonical template,
shared across that whole family, so `alignFace` can be written and pinned **today**, against
`alignCrop` as an executable oracle, whichever embedder eventually ships.

⚠⚠ **One trap inside it, and it already produced a wrong instruction.** OpenCV labels the point that
maps to template row 1 the *"right eye"* (the **subject's** right, which appears on the image's left);
InsightFace's variable names call the same point the left one. Same point, opposite word. A parallel
review read that as a reindex and filed *"swap the landmark order (0↔1, 3↔4)"* — which would **mirror
every crop**. ⭐ The rule: **do not reason about landmark names at all.** Port `alignCrop`'s pairing
and pin it with a fixture against that implementation. A naming argument cannot settle this; a test
can.

⚠ **FaceNet is the odd one out** — 160×160, MTCNN bbox plus margin, no affine warp — so it cannot be
swapped into an ArcFace-aligned pipeline without re-measuring everything.

### 5.7 ⚠ Two operational facts about YuNet, if it is ever chosen

- **The tidy `[N,15]` output is produced by OpenCV's C++ wrapper, not by the model.** The graph emits
  `cls/obj/bbox/kps` × strides {8,16,32}; under ORT we own prior generation, decode and NMS. Size it
  as real work, or take a dependency on OpenCV `objdetect` on both Android and JVM — far heavier than
  ORT.
- **It detects faces *"between around 10x10 to 300x300"* pixels.** Fed full-resolution photographs,
  **close-up portraits are missed while group shots work** — a miserable thing to chase in a personal
  library.

### 5.9 ⭐⭐ The landmark half has a clean answer — MediaPipe (2026-09-22)

**Found by asking a question the first survey never asked: landmarks need not come from the
detector.** A standalone landmark model trained on data with no WIDER FACE lineage routes around
§5.1 entirely. Seven such datasets were checked; six are a clean negative (§5.10). The answer turned
out not to be a dataset at all.

**MediaPipe Face Landmarker (FaceMesh V2) is Apache-2.0 on the weights, over Google's own consented
in-house data.** Verified by downloading the card and extracting it:

> **`LICENSED UNDER` — Apache License, Version 2.0**
> **`AUTHORS`** — Geng Yan, Google / Ivan Grishchenko, Google
> **`APPLICATIONS`** — *"Detection of human facial surface landmark from monocular video"*

Training data names **no public dataset**: FaceMesh V2 is trained on *"smartphone front-facing camera
selfies taken in real-world environments"*, and the bundled BlazeFace detector on *"**consented
images** of people using a mobile AR application"*. ⭐ That is the only clean corpus in this entire
audit — the row §2 has never been able to fill for any candidate.

Apache-2.0 answers all three clauses §2.1 says actually bite us: commercial use, redistribution via
our own CDN (§4 — keep the licence text and NOTICE), and quantisation (§2 grants derivative works).
No field-of-use restriction, and nothing reaching models trained on the data.

**The five canonical points are derivable.** From the repo's own `face_mesh_connections.py`:
`FACEMESH_RIGHT_IRIS = {469,470,471,472}`, `FACEMESH_LEFT_IRIS = {474,475,476,477}`, and lip vertices
**61** and **291** are the mouth corners. ⭐ Eye centres from the iris rings are *true pupil centres* —
better than most markups, which give eye corners. The nose tip comes free from the bundled detector's
own keypoints.

**What ships**, verified by unzipping `face_landmarker.task` rather than by reading the card:

| File | Bytes |
|---|---|
| `face_detector.tflite` | 229,746 |
| `face_landmarks_detector.tflite` | 2,553,590 |

⭐ ~2.7 MB for the pair, against SigLIP's ~100 MB. The detector's 229,746 B cross-validates the card's
stated "224KB", which is decent evidence the card describes the file you actually get.

#### 5.9.1 ⚠⚠ The posture question, and why it reads differently here than for BlazeFace

`models.md` §3.1.3's rule is that a card's stated intent binds us even when the licence does not. Both
cards have an out-of-scope section, and **they are not the same statement**:

- **BlazeFace**: *"Any form of surveillance or identity recognition is explicitly out of scope and
  **not enabled by this technology**."* — reads as forbidding a use.
- **FaceMesh V2**, verbatim (⚠ the card's PDF drops "ti" ligatures; restored here): *"**OUT-OF-SCOPE
  APPLICATIONS** — Not appropriate for: ● This model is not intended for human life-critical
  decisions. ● **Predicted face landmarks do not provide facial recognition or identification and do
  not store any unique face representation.**"*

⭐ The second is a **capability statement, not a prohibition** — and it is *true of how we would use
it*. The landmarks say where a face is; the identity comes from a separate model we supply. That is
the same distinction `camera-service.md` CD-D7 already draws between detection and identification,
and the same one IN-D3 used to reconcile it.

⚖️ ⚠ Counsel's to settle, not ours, and there are two reasons not to call it settled here: the
section is still headed *"Not appropriate for"*, so a cautious reading of the second bullet is *"do
not build recognition on this"* rather than *"this is not recognition"*; and the **bundled detector is
BlazeFace**, whose wording is the stronger one. ⭐ Pairing FaceMesh with a different box source would
remove the second objection entirely.

#### 5.9.2 ⚠ Three things to weigh before banking it

1. **No official ONNX.** Google ships `.tflite`. Conversion is licence-permitted but is work, and the
   alternative — a LiteRT runtime on Android *and* desktop JVM — is a **new native dependency**
   where the repo has only ONNX Runtime today. Size this properly; §19's *"IN-3b looks like one item
   and is 'a native dependency on four platforms' wearing one id"* is the precedent.
2. ⚠ **The Apache grant lives in the model-card PDF, not beside the weights.** The `.tflite` files
   were removed from the Apache-2.0 repo and are served bare from `storage.googleapis.com`. The card
   is Google-published and explicit, so this is solid — but capture a dated copy of the card onto our
   own CDN beside the weights, because the grant and the artifact are currently two clicks apart.
3. ⚠ **Its stated intent is AR entertainment on front-facing cameras**, and the card says videos
   *"should be captured in 'selfie' mode. As such, it's not suitable for"* other capture. A library of
   ordinary rear-camera photographs is **not** what it was optimised for. That is an empirical
   question about detection quality on group shots and candids, entirely separate from licensing, and
   it is owed a measurement before this is called done.

### 5.10 The landmark datasets — six clean negatives, and one that only looks positive

| Dataset | Commercial? | Reaches trained models? | Redistribution? |
|---|---|---|---|
| **300-W** | No | **Yes, explicitly** | not addressed |
| **IBUG 68-point** (300-W, LFPW, HELEN, AFW, XM2VTS, FRGC, IBUG) | No | Yes | not addressed |
| **AFLW** | No | **"any portion of derived data"** | No |
| **LaPa** | No | not addressed | not addressed |
| **WFLW** | no terms published at all | — | — (its *images* are WIDER FACE: CC BY-NC-**ND**) |
| **300-VW** | no terms published at all | — | — |
| **COFW** | *apparently* CC BY 4.0 | — | — ⚠ see below |

- **300-W** names our exact activity: *"The data are provided for research purposes only. Commercial
  use (i.e., **use in training commercial algorithms**) is not allowed."*
- **AFLW** reaches derivatives: *"you agree not to … exploit for any commercial purposes, any portion
  of the images and **any portion of derived data**"*, plus *"not to further copy, publish or
  distribute any portion"*.
- ⚠⚠ **COFW is the trap.** CaltechDATA tags it CC BY 4.0 — but that is **2022 publisher metadata over
  the authors' 2013 terms, which licensed only the code** (*"This code is licensed under the
  Simplified BSD License"*; no dataset licence on the original page). And **63% of its training split
  is LFPW** — the authors' own text: *"for training we use the original non-augmented **845 LFPW
  faces + 500 COFW faces**"* — whose owner says *"The database is made available only for
  non-commercial use"* and *"we cannot distribute image files in any format to anyone"*. So every
  COFW-*trained* model inherits LFPW's NC clause. Same shape as §5.3: a redistributor granting what it
  does not hold.
- ⚠ **LS3D-W** (under FAN and OpenSeeFace) declares BSD-3 over images *"from AFLW, 300VW, 300W and
  FDDB"* — a BSD grant over AFLW pixels whose owner forbids redistributing derived data.
- Several landmark models have **no licence at all** on their weights: SPIGA (`cardData: null`),
  `cunjian/pytorch_face_landmark`, PFLD (`polarisZhao`) — no `LICENSE` file in the repo.

⭐ **The precedent that proves the premise generalises**, from dlib's own README: the 300-W-derived
68-point model carries *"The license for this dataset excludes commercial use and Stefanos Zafeiriou
… asked me to include a note here saying that the trained model therefore **can't be used in a
commercial product**"* — while the 5-point model, trained on the author's own data, carries no such
note under a blanket public-domain dedication. **The data's terms follow the weights, and a
rightsholder asked for it in writing.**

### 5.11 ⭐⭐ HSFace is exported and verified — the numbers, and the trap (2026-09-22)

`BooBooWu/Vec2Face`'s `fr_weights/hsface100k.pth` is now an ONNX graph that has been checked against
the PyTorch it came from **and** against faces. It is the first identity half with a number attached
rather than a licence argument.

**What it is.** IResNet-50, blocks `[3, 4, 14, 3]`, 512-d output, 112×112 input (`fc.weight` is
`(512, 25088)` = `512 × 512 × 7 × 7`, which is where the input size is actually stated). Input is
RGB, `(x/255 − 0.5) / 0.5` — ⭐ byte-for-byte `ImagePreprocessing.Profile.SIGLIP`, so the shipped
preprocessing already covers it and nothing needs a second spelling.

⚠⚠ **The trap, and it is a silent one.** The checkpoint carries eight `layer<N>.0.attn.fc{1,2}`
tensors: an **SE block, applied to the *identity* (skip) branch**, present only in block 0 of each
layer. Two things follow. Stock IResNet-50 — including the one in **Vec2Face's own
`models/iresnet.py`**, which does *not* match its own `fr_weights/` — will not load it; and the
obvious repair, putting the SE block where SE blocks normally go (on the residual output), **loads
with `strict=True` and silently computes a different function**. The definition that matches is
`HaiyuWu/SOTA-Face-Recognition-Train-and-Test`, `model/iresnet.py`, `mode="se"`; `_make_layer` passes
the mode to block 0 only, which is exactly the eight tensors.

⚠ **That repo has no `LICENSE` file.** It does not need one for this: the ONNX graph carries the
architecture, so the export is the deliverable and the Python definition never enters our tree. But
it does mean the definition cannot be vendored if anyone later wants it in-repo.

**Measured** on `asset/perturbation.png` from the model's own repo — 8 identities × 10 variants,
public and synthetic, so no private photograph is involved:

| | same identity | different identity | gap |
|---|---|---|---|
| fp32 | **+0.816** (min +0.622) | +0.007 (max +0.259) | **+0.809** |
| int8 | +0.805 (min +0.639) | +0.008 (max +0.255) | +0.797 |

⭐ **int8 is the shipping candidate: 42.1 MB against fp32's 166.5**, for 0.012 of gap. Static QDQ,
per-channel, calibrated on `asset/random-id.png` (90 distinct faces) and evaluated on the grid the
quantiser never saw — calibrating and scoring on one set would produce a number that cannot fail.
fp32↔int8 embedding agreement is 0.981 cosine; torch↔ONNX is 4.3e-07 absolute.

⭐⭐ **And int8 runs — on a CNN, under ORT on Android.** §3.5 recorded int8 *"does not run at all —
`ConvInteger` unimplemented in ORT's Java CPU build"*, and §5.8 listed this as still unverified for
the face models. It is now half-answered, and the distinction is the whole content:

- ⭐ `QuantFormat.QDQ` does **not** emit `ConvInteger`. It brackets ops in
  `QuantizeLinear`/`DequantizeLinear`, which ORT fuses to `QLinearConv` — so the op that blocked §3.5
  never appears. ⚠ That is a property of the **quantisation recipe**, not of the model: the same
  weights through `quantize_dynamic` would reproduce §3.5's failure.
- ⚠⚠ **Measured on the Android AAR (`onnxruntime-android`), which is a different artifact from the
  Java jar §3.5 tested** — different native build, different op coverage. The desktop JVM path for
  this model is **still unverified**, and §5.11's figures do not cover it. Do not read "int8 works"
  off this row for `:inference-service-proxy`'s `jvmMain`.

⭐⭐⭐ **CONFIRMED 2026-09-23 — HSFace carries a real person across a real camera roll.** 400
photographs, 46 faces: one cluster of **29, all the same person, confirmed by the subject by eye**,
stable across a 0.20–0.52 threshold range, with every other cluster at 0.10–0.20 nearest-pair from
it. ⭐ A photograph of a **cat**, false-positived by the detector, separates itself out — so the
embedder is discriminating, not merely grouping whatever it was handed.
⚠ One subject, one library; and ~90% of photographs yield no face at all, which is a **detector**
question (`photos-people-and-pets.md` §12.6).

⚠⚠ **A negative result was published against this model on 2026-09-22 and RETRACTED the same day.**
A real camera roll split one person into two orthogonal clusters, and that was written up here as a
synthetic-to-real domain gap in HSFace. It was not the model: the harness was feeding `alignFace`
**normalised** detector coordinates, which are anisotropic on a non-square photograph, so every crop
was stretched by `w/h`. Corrected, the same 46 faces give one cluster of 29 that is stable across a
0.20–0.52 threshold range. ⭐ HSFace stands. ⚠ The retracted claim is kept visible rather than
deleted, because the *reasoning* that produced it looked sound —
`photos-people-and-pets.md` §12.5 is the post-mortem.

⚠⚠ **What these numbers are NOT.** Same-identity pairs here are *generated variants of one vector*,
which is an easier problem than the same person across five years, two haircuts and a dim restaurant.
They establish that the export is correct and that int8 costs nothing; they do **not** establish a
clustering threshold. ⚠ The impostor tail already reaches +0.259 on 8 identities — a real library has
thousands, and the tail grows with the number of pairs. The threshold is `FacePipelineMeasurementTest`'s
job, on a real camera roll, and it is deliberately swept rather than guessed.

### 5.8 ⚠⚠ How firm this section is

Three of the four researchers had shell access refused for their whole run, so most quotes arrived
through a summarising fetch layer — **one transcription step from literal bytes**. Short quotes are
reliable; treat long reproductions as near-verbatim. ⭐ The **hash comparison in §5.4 is the
exception** and is primary: it was run with real downloads and digests.

⚠ **Before counsel signs anything, re-pull and hash the four texts that carry the decision**: YuNet's
and SFace's `LICENSE` files, InsightFace's licence paragraph, and `libfacedetection.train`'s
`labelv2` line.

Still unverified by anyone: the WebFace260M agreement wording (`face-benchmark.org` no longer
resolves), CASIA-WebFace's agreement PDF, VGGFace2's original licence text (gone with the downloads),
whether OpenCV obtained permission from Zhong Yaoyao, SFace 2021dec's corpus, and InsightFace's
pricing.

⭐ **Removed from that list, 2026-09-22:** *"whether int8 artifacts run under ORT"* — §5.11 measured a
QDQ int8 CNN running on the **Android** AAR. ⚠ The **desktop Java jar** remains untested for this
model, and the recipe matters: QDQ avoids `ConvInteger`, `quantize_dynamic` would not.
