# Publishing a model catalog

The runbook for IN-2 / IN-D10: turn a checkpoint on disk into a **signed catalog** that devices
will install from. The twin of [`tiles-pipeline.md`](tiles-pipeline.md), deliberately — model
distribution *is* the region-delivery problem, and IN-D10 inherits its shape rather than inventing
one.

⚠⚠ **Nothing in the inference track works until this has been run once.** No key ⇒ no catalog ⇒ no
model ⇒ every index pass is a counted no-op. `inference-service.md` §28.6 has been writing that
sentence since IN-3b.

---

## What exists, and what only you can provide

| | state |
|---|---|
| the client (fetch, verify, filter, resumable download, integrity gate) | **built and tested** |
| `:model-signer` (build + sign) and `scripts/models-publish.sh` | **built** — this document |
| an Ed25519 **signing key** | ✅ **rotated 2026-09-25** — the 2026-09-06 key's seed was lost; the new one lives in the login Keychain (`tenshu-model-signing-key`) plus an off-machine copy |
| a **bucket** at `models.service.tenshu.app` | [`deploy/cloudflare-models/`](../../deploy/cloudflare-models/) — ⚠ **you apply it** |
| an actual **checkpoint** | ✅ SigLIP pinned (`models.md` §3.8); the three exports are side-loaded today, ⚠ not in the tree |
| `named("modelCatalogUrl")` | `https://models.service.tenshu.app` — ⚠ answers 404 until the first publish |

---

## 1. Generate the signing key — once, ever

⚠⚠ **Rotated 2026-09-25 because the first seed was lost** — nobody could find the file when the
face model had to be published, and a lost seed can only be replaced, never recovered. So the
current key was generated **straight into the macOS Keychain**, never resting on disk, and then
copied off the machine. `security` writes only to the local login keychain, which is not a backup:

```bash
( umask 077; D=$(mktemp -d -t tenshu-model-key); trap 'rm -rf "$D"' EXIT; \
  ./gradlew -q :model-signer:run --args="generate-key --out $D/seed" && \
  security add-generic-password -s tenshu-model-signing-key -a "$USER" -w "$(cat "$D/seed")" && \
  echo "✓ stored in Keychain as tenshu-model-signing-key" )
```

⚠ A temp *directory*: `generate-key` refuses to write over an existing file, so it cannot be
pointed at a pre-made temp file. ⚠ Without `-U`, an existing Keychain item is never replaced. Pin
the printed public key only once the `✓` line appears — otherwise the seed behind it is already
gone. Then put the seed in an off-machine store (Passwords / iCloud Keychain or a password manager).

The original file-based form, for reference:

```bash
./gradlew :model-signer:run --args="generate-key --out $HOME/.tenshu/model-keys/model-signing.key"
```

It prints the **public** key. Pin it:

```kotlin
// inference-service-proxy/.../catalog/ModelCatalog.kt
const val MODEL_CATALOG_PUBLIC_KEY_HEX: String = "<the printed hex>"
```

⚠⚠ **The private seed never leaves that machine, and it is not recoverable.** Losing it does not
merely block publishing: every installed device has the *public* half compiled in, so a new key
means catalogs are refused until an app-store release ships the new pin. That is the cost IN-D10
accepted deliberately — pinning the *key* rather than each hash is what makes rotation possible at
all, but "possible" is not "cheap".

⚠ `generate-key` refuses to overwrite an existing file. A second run over a live key would orphan
every device that trusts the first.

⚠⚠ **Not `/secure/...`, and not anywhere else under `/`.** On modern macOS the root filesystem is
read-only, so a top-level directory cannot be created by anyone — root included. The first draft of
this runbook said `/secure/model-keys` and the command simply could not work; it failed with a
`FileNotFoundException` naming the *key file*, which pointed at the wrong thing entirely.
`generate-key` now creates the directory **0700** and, when it cannot, says so and names it.

⚠ Anywhere writable and private is fine — `$HOME/.tenshu/model-keys` above, an encrypted volume, a
hardware-backed store. What matters is that the seed is not readable by anyone else and is backed
up somewhere you will still have it in a year.

## 2. Stage the artifacts and describe them

Put the checkpoint's files under `deploy/models/build/artifacts/`, laid out exactly as they will be
served (the `path` is what a client GETs under the catalog's base URL).

Then describe them in `deploy/models/models.json` — **identity and policy only**:

```json
{
  "schemaVersion": 1,
  "models": [
    {
      "id": "siglip-base-patch16-256-multilingual",
      "name": "SigLIP base (multilingual)",
      "version": 1,
      "capabilities": ["semanticImageSearch"],
      "labelSetVersion": 1,
      "runtime": "onnx",
      "artifactVersion": 1,
      "licence": "Apache-2.0",
      "artifacts": [
        { "role": "vision",     "path": "siglip-base-patch16-256-multilingual/1/vision_model_uint8.onnx" },
        { "role": "text",       "path": "siglip-base-patch16-256-multilingual/1/text_model_int8.onnx" },
        { "role": "vocabulary", "path": "siglip-base-patch16-256-multilingual/1/tokenizer.bin" }
      ]
    }
  ]
}
```

⚠⚠ **All three roles, or search does not work.** `vision` indexes, `text` embeds the query and
`vocabulary` tokenizes it; a checkpoint without the last two installs, indexes, and answers every
search with `NO_TEXT_EMBEDDING` (`inference-service.md` §21.3). The first draft of this manifest
listed two.

⚠ **The version is in the path.** Artifacts are served `immutable` for a year, so a new version at
an old path would be ignored by every cache that already holds the old bytes.

⚠ **Claim only what the checkpoint does in this build.** `contentTagging` is absent because no
shipped backend tags and the label vocabulary is uncalibrated (§27); add it in the catalog version
that ships alongside the calibration.

⚠⚠ **You do not write `sha256` or `byteSize`.** The signer computes both from the bytes on disk. A
hand-written digest goes stale, and a stale digest is indistinguishable at the client from a CDN
serving the wrong bytes — it fails the integrity gate with no way to tell which.

⚠ **`capabilities` names are checked against `InferenceCapability`** and a typo fails the build.
The client *drops* a capability it cannot resolve (forward compatibility), and an entry left with
none is filtered out entirely — so a misspelling would publish a model every device silently
ignores. `contentTagging`, `semanticImageSearch`, `textInImages`, `faceClustering`.

⚠ **`runtime` must be one this build can execute** or the entry is filtered out on arrival
(IN-D13). `onnx` on JVM and Android; `onnx+mediapipe` (IN-6's face pair, `FaceModel.RUNTIME`) on
Android only, so desktop filters that entry out by construction; `coreml` on no shipped build. In
the repository, Photos on iOS contributes it since 2026-10-11, **behind a build flag that is off**
and two static gates (`ios-on-device-inference.md` §3.1, §14.11); every other iOS app, and Photos
with the flag off, contributes nothing.

⚠⚠ **One checkpoint published for two runtimes is two entries with ONE `id` and ONE `version`**
(`ios-on-device-inference.md` D13). The runtime filter is then the only thing that keeps them apart:
a client resolves a model by id alone, so it must see one entry per id. The signer enforces the
manifest's half and refuses to sign:
- the same `(id, runtime)` twice;
- entries that share an `id` and differ in `version` or `labelSetVersion`.

⚠ **The other half is a rule for whoever wires a build: contribute one runtime per model id.**
Nothing in the client checks it. A build contributing both `onnx` and `coreml` would keep both
entries and install whichever is listed first. ⚠ The one graph that contributes `coreml` is pinned
to exactly `{"coreml"}` by a test (`PhotosModelDownloadTest`), and no iOS source set contains an
ONNX runtime to contribute beside it.

⚠ **`sha256` and `byteSize` may be written for an artifact, and then they are a pin, not the value.**
The signer still computes both; it refuses a file that differs. Use it where something other than
this tool accepted the bytes: the Core ML towers (§2a), where a re-export of the same checkpoint has
other digests. The rule above stands for everything else.

⚠ **`source` is optional and is copied into the signed catalog as written**: `repo`, `revision` (a
40-character commit, never a tag), and optionally `files` (sha256 by upstream file name),
`toolchain` and `exportManifestSha256`. Copy them from the export's `export-manifest.json`. No
client reads it. ⚠ Leave it out when nothing recorded the origin: the ONNX entry has none, because
nothing proves which snapshot those files were exported from.

### 2a. A Core ML export passes the retrieval gate before it is staged

The `coreml` entry's two archives come from `scripts/inference/export-coreml-towers.py`
(`ios-on-device-inference.md` §3.4). Its self-check is per tower; whether the pair still ranks
images the way fp32 does is a separate question, and `scripts/inference/retrieval-gate.py` answers
it on the archives themselves. macOS only (it runs the towers through coremltools), Python 3.13.

```bash
python3.13 -m venv venv
./venv/bin/pip install -r scripts/inference/requirements-retrieval-gate.txt   # the export's pins + onnxruntime, Pillow, sentencepiece

./venv/bin/python scripts/inference/retrieval-gate.py \
    --export      ~/tenshu-models/coreml-export-<id> \
    --source      ~/tenshu-models/siglip-base-patch16-256-multilingual \
    --onnx-vision ~/tenshu-models/export-256ml/vision_model_uint8.onnx \
    --onnx-text   ~/tenshu-models/export-256ml-text/text_model_int8.onnx \
    --corpus      ~/tenshu-models/retrieval-gate-corpus --fetch

python3 scripts/inference/test_retrieval_gate.py     # the arithmetic and the refusals; needs nothing
```

It prints the table, writes `retrieval-gate-result.json` into the export directory, and exits
**0 PASS, 1 FAIL, 2 REFUSED**. Pass means the fp16 Core ML pair is at least as close to fp32 as the
published ONNX pair on every figure. `--fetch` downloads the 300 COCO images the corpus manifest
names (~50 MB, once); nothing is fetched without it.

⚠⚠ **Stage the directory the gate ran on, byte for byte.** Two export runs of one checkpoint give
different archive digests, so a re-export is an ungated artifact, however identical its inputs.
Keep the gated directory outside the repo and copy the two tars from it.

⚠ **It refuses rather than guesses**: an export with no `export-manifest.json` or with bytes the
manifest does not name, a `--source` that is not the recorded checkpoint, ONNX files the catalog
does not serve (`--allow-unpublished-onnx` after an ONNX re-export, and then update
`PUBLISHED_ONNX` in the script), a corpus file that differs from the manifest, an unpinned
toolchain (`--allow-unpinned`; the result then says so, and the verdict reads `OVERRIDDEN`). ⚠ The
interpreter is part of the toolchain: both scripts refuse a Python other than 3.13 the same way.

⚠⚠ **Two more refusals are about what the export *is*, not whether its bytes are intact:**
- **Another checkpoint.** The manifest's `source.revision` and source-file hashes must be the
  script's `YARDSTICK_SOURCE`, the checkpoint the published ONNX pair is taken to come from.
  Otherwise the candidate would be compared with its own fp32 weights and the original's ONNX
  pair. ⚠ "Taken to": nothing records that the ONNX files were exported from that snapshot. A new
  checkpoint therefore means re-exporting ONNX too, updating `PUBLISHED_ONNX` and
  `YARDSTICK_SOURCE` together; until the new pair is published, `--allow-unpublished-onnx` with
  the new ONNX files is the only thing that waives the pin.
- **An export made with `export-coreml-towers.py --allow-unpinned`** (`toolchain.pinned` is not
  `true`). `--allow-unpinned-export` gates it anyway.

⚠ **The gate is one evaluation**: the committed queries and corpus manifest, on the compute units the
app ships (`cpuAndNeuralEngine`). `--queries`, `--corpus-manifest` or `--compute-units` pointing
anywhere else is admitted for exploration and is an override too.

⚠ The committed queries must also become the committed **token rows** (`CANONICAL_TOKEN_ROWS_SHA256`):
a tokenizer backend that moves one id is refused outright, since the rows, not the models, are then
what changed. Editing the queries means updating that constant with the queries' own.

A run with any override prints `OVERRIDDEN PASS` / `OVERRIDDEN FAIL`, never a bare verdict, and
its result has `"overridden": true`. ⚠ Do not publish from an overridden result without reading why.

⚠ **A host run.** For a phone spot-check of the vision tower, add `--dump-pixels <dir>`, push that
directory and the export with `inference-harness-ios/run-in3m.sh`, run the `coreml-ane` row, copy
the `in3m-vectors-…` file back, and pass it as `--phone-vectors` **with `--phone-pixels <dir>`**,
the directory that was pushed. The gate checks that directory holds exactly this run's pixels and
that the vector file's corpus id is that directory's, and refuses otherwise: file names alone do
not show which pixels the phone saw. Keep the directory with the export. The table gains a row; the
verdict does not change. `ios-on-device-inference.md` §9.6 has the current export's result and
what a phone cosine does and does not cover.

### 2b. A gated Core ML export gets its preflight probe, in the app, before it is published

A phone that downloads the towers does not serve them until its preflight has passed
(`ios-on-device-inference.md` §7 D12 ②, §14.6). Part of that preflight is a fixed check across the
two towers: one image, one caption that describes it and one that does not, which must rank in that
order by a margin. The margin is measured per export, on the build Mac:

```bash
./venv/bin/python scripts/inference/preflight-probe.py \
    --export /path/to/export \
    --source /path/to/google--siglip-base-patch16-256-multilingual
python3 scripts/inference/test_preflight_probe.py
```

It reads the export and writes nothing into it. It refuses an export whose
`retrieval-gate-result.json` is missing, is about other bytes, or is not a clean `PASS`; it exits 1
when the two captions do not stand at least 0.12 apart on this Mac.

It writes three things, and commits go together:
- `scripts/inference/preflight-probe-records/<id>.json`, the record, where `<id>` is the first
  twelve characters of the export manifest's sha256;
- `PreflightProbeRecord_<id>.kt`, that record as the constants the app compiles;
- `PreflightProbeRegistry.kt`, the list of every committed record.

Then add one line to `PINNED_RECORDS` in `PreflightRevisionPinTest` (it fails until then, and
prints the line).

⚠⚠ **Adding an export is additive.** It adds a record, a file and a line, and rewrites no other
export's. **It needs no bump of `PREFLIGHT_REVISION`:** a verdict is keyed on both archive digests
and the vocabulary's, so a new export cannot stand on an old verdict, and a bump would void the
verdict of every phone that still holds an earlier export, for nothing. A bump is owed when an
**existing** record's content changes (`--replace`) or the judging code does; the pin test tells
the two apart.

⚠⚠ **The app looks the probe up by the sha256 of both archives and of the vocabulary**, among
every record it carries. A build that carries no record for an export never calls that export
ready: its phones download it, compile it, and stay at *checking*. So **the record ships in an
app release before the export is published.**

⚠⚠ **A record is never removed as part of adding another, and only by the maintainer's
decision.** A phone keeps the export it downloaded until it has downloaded the next one, which
is ~709 MB and Wi-Fi only by default. What a phone that still holds an export experiences on a
build without that export's record:
- a pass it already has **keeps standing** (a `Ready` model is not probed again, and removing a
  record changes no key), so search goes on working;
- the day its verdict is voided (an iOS update, a `PREFLIGHT_REVISION` bump), it has no probe
  to renew it by: the state stays *checking*, search by description answers *"needs a model on
  this device"*, and its vectors are kept, until it has downloaded an export the build has a
  record for.

The time to remove one is when the catalog no longer offers the export **and** a release has
gone out in which phones were moved to its successor. To remove: delete its JSON, run
`preflight-probe.py --render`, and drop its line from `PINNED_RECORDS`. No bump.

⚠ The figures are the host's. What an iPhone's Neural Engine returns for the probe is unmeasured
until a device run; the threshold is half the host margin for that reason.

## 3. Build and sign

```bash
./gradlew :model-signer:run --args="build-and-sign \
  --manifest deploy/models/models.json \
  --artifacts deploy/models/build/artifacts \
  --out deploy/models/build/catalog.json \
  --key $HOME/.tenshu/model-keys/model-signing.key"
```

⭐ With the key in the Keychain, feed it through a private temp file that is deleted on exit. ⚠ Not
`<(…)`: the signer requires `--key` to be a regular file, and process substitution is a pipe.

```bash
( umask 077; K=$(mktemp -t tenshu-model-key); trap 'rm -f "$K"' EXIT; \
  security find-generic-password -s tenshu-model-signing-key -w > "$K" && \
  ./gradlew :model-signer:run --args="build-and-sign --manifest deploy/models/models.json \
    --artifacts deploy/models/build/artifacts --out deploy/models/build/catalog.json --key $K" )
```

Writes `catalog.json` + a detached `catalog.json.sig`, using the **same Ed25519 the client verifies
with** — one signature stack, as D3 requires.

⚠ It prints the public key it signed with on every run. **Compare it against
`MODEL_CATALOG_PUBLIC_KEY_HEX`.** A catalog signed with the wrong key is silently unverifiable at
the client; this line is the only local check that catches it.

## 4. Publish

⭐ **Where the secrets live (decided 2026-09-25).** Names, not values, so this is safe to commit. All
of them are in the **login keychain**, account `tenshu`, which is what `security` reads:

| Service name | What | Recoverable if lost? |
|---|---|---|
| `tenshu-model-signing-key` | the catalog signing seed (§1) | ⚠⚠ **no** — a new key means a rotation |
| `r2-models-access-key-id` / `r2-models-secret-access-key` | the S3 pair of the Cloudflare token **"R2 User Token Models"** (writes `tenshu-models`) | yes — roll it in Cloudflare |
| `r2-access-key-id` / `r2-secret-access-key` | the tiles token's pair — `tiles-pipeline.md`'s `r2publish` reads these | yes — roll it |

⚠⚠ **The tiles pair cannot write the models bucket** — it is scoped to tiles and answers
`AccessDenied` on the first upload (2026-09-25). Use the models pair.

⚠ **Backup is Time Machine.** The login keychain is a file on this Mac and is covered by Time
Machine (restored with the login password of that time) and by Migration Assistant. Nothing else
backs it up, and the Passwords app / iCloud Keychain is a different store that `security` cannot
read. The signing seed never changes, so an off-machine copy (a password manager, or paper) is a
one-time act worth doing; the R2 pairs need none.

The upload, reading the models pair from the Keychain:

```bash
( export R2_ACCESS_KEY_ID=$(security find-generic-password -a tenshu -s r2-models-access-key-id -w) \
         R2_SECRET_ACCESS_KEY=$(security find-generic-password -a tenshu -s r2-models-secret-access-key -w); \
  env -u AWS_SESSION_TOKEN -u AWS_SECURITY_TOKEN SPACES_BUCKET=tenshu-models \
    SPACES_ENDPOINT=$(terraform -chdir=deploy/cloudflare-models output -raw s3_endpoint) \
    AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
    ./scripts/models-publish.sh )
```

Add `DRY_RUN=1` after `env …` to rehearse; a dry run needs no credentials at all.


The bucket is [`deploy/cloudflare-models/`](../../deploy/cloudflare-models/) — an R2 bucket behind
`models.service.tenshu.app`. Apply it first (its README), and wait for `custom_domain_status` to read
`ssl = active`.

⚠⚠ **Two credentials, never mixed.** The Cloudflare **API token** is Terraform's. The upload takes an
**R2 S3 key pair** that can write `tenshu-models` (R2 → Manage API tokens). ⚠ The pair minted for tiles is
bucket-scoped: unless its scope includes this bucket, it authenticates and is then refused.

Terraform prints the whole invocation — one command, deliberately not `export`s, so the R2 key never
lingers in a shell that later runs something else:

```bash
terraform -chdir=deploy/cloudflare-models output -raw publish_env_hint
```

Rehearse with `DRY_RUN=1` added to that command line (prints every remote command, touches nothing),
then run it for real.

⚠⚠ **R2 refuses two things other S3 stores accept**, and the script derives both from the endpoint:
no `x-amz-acl` (public read is the custom domain), and a region of `auto`. ⚠ `SPACES_ENDPOINT` has no
default — the script refuses to run without one.

⚠⚠ **Artifacts upload before the catalog, and that order is load-bearing.** The catalog is the
pointer; publishing it first opens a window in which every install 404s. The reverse costs only
storage.

⚠ The script refuses to publish a catalog naming a file it cannot find locally. The client's
integrity gate would refuse the download anyway — but only after a user spent the bytes.

Artifacts are cached `immutable` for a year (a model version never changes its bytes, and its path
carries the version); the catalog and signature for 5 minutes, because that is what a device re-reads
to notice a new model.

## 5. Verify from outside

```bash
BASE=https://models.service.tenshu.app
curl -fsS  "$BASE/catalog.json" | head -c 400
curl -fsSI "$BASE/catalog.json.sig"
curl -fsSI -r 0-1023 "$BASE/siglip-base-patch16-256-multilingual/1/vision_model_uint8.onnx"
# ⚠⚠ expect 206 — ModelDownloader RESUMES with Range; a 200 restarts every interrupted download
```

Then install from a device via **Settings → Models**, answer the question it asks (or open **Search photos by description** under Features and switch on *This device*), and
leave the device plugged in and unattended — the pass checks both itself (`inference-service.md` §32.9).

## 6. The Core ML entry, end to end

`export → retrieval gate → preflight probe → catalog entry → sign → publish`, archives first and the catalog last.

| Step | What | Where |
|---|---|---|
| Export | `scripts/inference/export-coreml-towers.py` writes two `.mlpackage.tar` towers, `tokenizer.bin` and `export-manifest.json` | `ios-on-device-inference.md` §3.4 |
| Gate | `scripts/inference/retrieval-gate.py` writes `retrieval-gate-result.json` beside them. Needed: `PASS`, `"overridden": false` | §2a |
| Probe | `scripts/inference/preflight-probe.py` writes the export's probe record and its Kotlin constants. Needed: in a shipped app build **before** the publish | §2b |
| Entry | `deploy/models/models.json`: the ONNX entry's `id` and `version`, `runtime: "coreml"`, the two towers under `<id>/<version>/coreml/`, the ONNX entry's own `tokenizer.bin` path, each artifact pinned with the export manifest's `sha256` and `bytes`, and `source` from the same manifest | §2 |
| Sign | `:model-signer` re-signs the **whole** catalog: every entry's files must be staged, not only the new ones | §3 |
| Publish | `scripts/models-publish.sh` | §4 |

⚠⚠ **Re-exporting means a new entry, not new bytes at the old path.** Artifacts are served
`immutable` for a year, and the pins name one export. A second export gets a path of its own (bump
`version` on every runtime of that id, or name the file differently), its own gate run and its own
pins.

### 6.1 What the maintainer runs for the export of 2026-10-05

The entry in the tree describes the gated export kept at
`~/tenshu-models/coreml-export-3fd1918106d4/` (`ios-on-device-inference.md` §9.6, §12). Signing and
publishing are the two steps nobody else can do: the key is in the maintainer's login Keychain.
Run from the repository root, on a tree that contains this entry.

```bash
E=$HOME/tenshu-models/coreml-export-3fd1918106d4
A=deploy/models/build/artifacts
M=siglip-base-patch16-256-multilingual
BASE=https://models.service.tenshu.app

# 1. The directory is still the gated export. Both commands must succeed.
( cd "$E" && shasum -a 256 -c <<'SUMS'
a30d4ddeaa9d9cc6fc9a894c2c01617d0ed0ae6abbdac832337037a5e410d8de  vision_fp16.mlpackage.tar
628f7f32ac0c43e7d7e25b396b5c12b7da8ad3208ca696d1a4a7d0bcfb41907d  text_fp16.mlpackage.tar
0077b8d2b79f30ff96015e1b76ff4c7d3d036667083e8a4da3bf1ee3df3d34aa  tokenizer.bin
3fd1918106d46c76cbebbd5ef0d566c6b941458d00015370be6677e33026b6f0  export-manifest.json
SUMS
)
python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1])); m=json.load(open(sys.argv[2])); assert r["verdict"]=="PASS" and r["overridden"] is False and r["export"]["artifacts"]==m["artifacts"], "NOT the gated export"; print("gate: PASS, not overridden, same artifacts")' "$E/retrieval-gate-result.json" "$E/export-manifest.json"

# 2. Keep what is live now. It is the rollback (step 7) and the yardstick for step 4.
mkdir -p deploy/models/build/live
curl -fsS -o deploy/models/build/live/catalog.json     "$BASE/catalog.json"
curl -fsS -o deploy/models/build/live/catalog.json.sig "$BASE/catalog.json.sig"

# 3. Stage. The three Core ML files come from the kept export; the four already-published files
#    are fetched from the CDN unless they are staged already.
mkdir -p "$A/$M/1/coreml" "$A/auraface-v1-int8/1"
cp "$E/vision_fp16.mlpackage.tar" "$E/text_fp16.mlpackage.tar" "$A/$M/1/coreml/"
cp "$E/tokenizer.bin" "$A/$M/1/tokenizer.bin"
for p in "$M/1/vision_model_uint8.onnx" "$M/1/text_model_int8.onnx" \
         auraface-v1-int8/1/face_landmarker.task auraface-v1-int8/1/auraface_int8.onnx; do
  [ -f "$A/$p" ] || curl -fsS -o "$A/$p" "$BASE/$p"
done

# 4. Sign, with the key from the Keychain. --build-date is pinned so step 5's diff is one block.
( umask 077; K=$(mktemp -t tenshu-model-key); trap 'rm -f "$K"' EXIT; \
  security find-generic-password -s tenshu-model-signing-key -w > "$K" && \
  ./gradlew :model-signer:run --args="build-and-sign --manifest deploy/models/models.json \
    --artifacts deploy/models/build/artifacts --out deploy/models/build/catalog.json \
    --build-date 2026-09-25 --key $K" )
#    ⚠ The last lines must read:
#      Signed with public key: b417e9105c61b59f335bed4f7e6b4a86ca811518c6f90d7184d1239c5c7d8877

# 5. Nothing but the new entry moved. Expect exactly ONE added block, "61a62,113", every line of it
#    starting ">": the coreml entry. ANY "<" line is a staged file that is not the published one:
#    stop, and do not publish.
diff deploy/models/build/live/catalog.json deploy/models/build/catalog.json

# 6. Rehearse, then publish. The dry run needs no credentials and uploads nothing.
( export R2_ACCESS_KEY_ID=$(security find-generic-password -a tenshu -s r2-models-access-key-id -w) \
         R2_SECRET_ACCESS_KEY=$(security find-generic-password -a tenshu -s r2-models-secret-access-key -w); \
  env -u AWS_SESSION_TOKEN -u AWS_SECURITY_TOKEN DRY_RUN=1 SPACES_BUCKET=tenshu-models \
    SPACES_ENDPOINT=$(terraform -chdir=deploy/cloudflare-models output -raw s3_endpoint) \
    AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
    ./scripts/models-publish.sh )
#    ⚠⚠ THE IRREVERSIBLE STEP: the same command without DRY_RUN=1.

# 7. Verify from outside. Both towers must answer 206 and hash to the pins.
curl -fsS "$BASE/catalog.json" | cmp - deploy/models/build/catalog.json && echo "catalog: live"
for f in vision_fp16 text_fp16; do
  curl -fsSI -r 0-1023 "$BASE/$M/1/coreml/$f.mlpackage.tar" | head -1
  curl -fsS "$BASE/$M/1/coreml/$f.mlpackage.tar" | shasum -a 256
done
```

`--build-date 2026-09-25` keeps the build date the live catalog has, so step 5 shows the entry
alone. Leave the flag out to stamp today's date; the diff then has one more line.

⚠ **The publish uploads seven objects, not three.** The script uploads every path the catalog names
(the vocabulary once, since both SigLIP entries name one path). Four of them are already on the CDN
and are overwritten with the bytes staged in step 3, which step 5 has shown to be the published
ones. That is ~1.19 GB of upload for ~740 MB of new content.

⚠⚠ **What cannot be taken back.**
- **The two archives' URLs.** They are public the moment they upload, and they are served
  `Cache-Control: immutable` for a year: a cache that holds them never asks again. So the bytes at
  those two paths are final. A bad archive is fixed with a new path, never by uploading over it.
- **A device that already installed from the entry keeps what it installed.** Removing the entry
  from a later catalog stops new installs and does not uninstall.
- **The catalog is the reversible part.** It is cached for five minutes. Rolling back is uploading
  step 2's pair over it:
  `aws --endpoint-url "$SPACES_ENDPOINT" --region auto s3 cp deploy/models/build/live/catalog.json s3://tenshu-models/catalog.json --content-type application/json --cache-control "public, max-age=300"`,
  and the same for `catalog.json.sig` with `--content-type application/octet-stream`. The archives
  then stay on the CDN with nothing pointing at them.

⚠⚠ **Why this is safe for the builds already installed, and the one condition.** A build keeps an
entry only when its `runtime` is in the set that build contributes
(`ModelCatalog.kt`, `usableModels`). Android contributes `{"onnx", "onnx+mediapipe"}` and desktop
`{"onnx"}`, so both drop the `coreml` entry and still see one entry for the id, the ONNX one. That
filter is older than the first pinned key (2026-08-15 against 2026-09-06), so there is no build that
trusts a catalog and does not filter it; and the client ignores keys it does not know, so `source`
costs it nothing. `CoreMlCatalogEntryTest` and `SignedCatalogRoundTripTest` pin both.
**The condition:** no shipped build may contribute `coreml` alongside `onnx`. None does. If that ever
changes, the two entries need two ids first.

⚠ **Until an iOS build contributes `coreml`, publishing changes nothing on any device.** The entry
is then on the CDN and nobody is offered it. ⚠ As of 2026-10-11 the code that contributes it exists
(Photos on iOS, `ios-on-device-inference.md` §14.11) and its build flag is off, so this still holds
for every build anyone has: nothing shipped is offered the entry.

⚠⚠ **A ranged request to the text archive is not always answered with the range** (2026-10-11,
`ios-on-device-inference.md` §14.12): `curl -r 1000-1999` was answered `200` with all
555 540 480 bytes, twice, and `206` minutes later. Step 5's `curl -fsSI -r 0-1023 … | head -1`
therefore proves less than it reads, and a `curl -r` without `-I` may download the archive. The
opt-in `ModelCdnRangeProbeTest` reads the headers and drops the connection.

---

## What is still unproven after step 5

⚠⚠ **A real download has never happened.** `inference-service.md` §11 is explicit: the machinery is
proven to *refuse* everything it should, and nothing about a real fetch is proven — resumption
across a real network, a genuine ~380 MB artifact, or the local-cache ceiling interacting with it
(`retention-quota-model.md` P2). The first real publish is also the first real test of all three.

⚠ The `:model-signer` ↔ client contract *is* covered:
`SignedCatalogRoundTripTest` (`:inference-service-proxy` `jvmTest`) builds a catalog with the real
tool, signs it, and puts it through the real `ModelCatalogVerifier` — including that an edited
catalog is refused. It lives there rather than in `:model-signer` because `parseModelCatalog` is
`internal`, so a test on the tool's side could only assert against a hand-copied JSON, which is a
second copy of the contract rather than a check on it.
