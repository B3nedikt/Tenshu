# Photos — People (and, later, pets)

**Status:** v0.5 — **F1–F4 shipped on Android (§12.12); desktop owes its detector; iOS is F5.**
F6a/F6b built; **F6c designed 2026-09-30, not built** ([§6a](#f6c--design-2026-09-30)), with
maintainer decisions D1–D11 owed.
Embedder AuraFace int8, calibration 0.44 re-checked for it (§12.8c). ⚖️ Published before counsel
answered, by maintainer decision (2026-09-24); the counsel packet is still owed. The seam, the shared alignment, the clustering and the device-local template store
are in (F2); the device harness has run the whole chain over a real camera roll and the result is
a pass (§12.6); People reaches the app (F3a) and a person can be named (F3b).

⭐⭐ **Two of F3's three gates are closed.** **Recall** is characterised and has a candidate fix
(§12.7–§12.8: 80% → 93% via a crop stage, at ~3× less landmarker time). **Calibration** is chosen
— `FaceClustering.Measured` = cosine **0.44**, `minFaces = 2`, wired in production on 2026-09-24
and argued in §10 Q2: it is the operating point of the one human-verified run, placed at the high
end of the measured band because splitting a person is recoverable and merging two is not.

⚠⚠ **What remains is the one gate no engineering closes: ⚖️ which embedder we may licence**
(§10 Q1). ⚠ And the assumption that unblocked the work got *larger* on 2026-09-23 — §10a found
HSFace's generator trains on WebFace4M, so route 2 descends from the encumbrance it was chosen to
escape. Nothing built so far depends on that answer: every slice is tested against the fake, and no
device can produce a template until F4 offers the consent. Originally opened as: Opened 2026-09-21 from a maintainer request: *"what are we
still missing e.g. for a People and Pets section similar to the one in the Google Photos app"*, then
scoped on review to **people first, Android + desktop first, pets later**. This is IN-6 of
[`inference-service.md`](inference-service.md), which owns every decision this document builds on;
what is new here is the *shape of the slice* and the phase map, not the policy.

⚠⚠ **The central design move is already decided and it is counter-intuitive: people are named
per-photo, never per-cluster.** IN-D5 (`inference-service.md` §2, "the important move"):

> *If the synced artifact were "cluster 7 is Anna", cluster identity would have to be stable across
> devices, which forces templates onto the wire. If instead the synced artifact is a plain label on a
> photo — **this photo shows Anna** — then clusters are a local UI convenience for bulk-labelling,
> the durable truth is a string the user typed, and no biometric template ever becomes realm data.*

Everything below follows from that. A cluster is scaffolding the user throws away; a `MediaLabel` is
the product.

⚠⚠ **This slice ships the platform's first threat-model asset entry for derived inference data.**
`docs/sync/threat-model.md` has none today — not for embeddings, not for labels. §8 drafts it.

⚖️ **Counsel review is owed before release, and the packet is now written**:
[`policy/in6-face-clustering-counsel-packet.md`](../policy/in6-face-clustering-counsel-packet.md).
It carries three separable asks — the **licence** question (§4, the one that blocks a release),
the **CD-D7 reconciliation** sign-off, and review of the **A40** threat-model entry as it shipped.
⚠ Two of the three are sign-offs on an argument already written. The licence question is genuinely
open, and its sharp edge is **WebFace4M**: HSFace's synthetic faces come from a generator trained on
it, so the restriction may carry through (§10a).

**Owner:** Benedikt

---

## 1. What this is, in one paragraph

A **People** section in Photos: rows of faces the device found, each openable as a grid of that
person's photographs, each nameable. Naming is the only durable act. Once a face is named, every
photo in that group gets a `MediaLabel` row saying *this photo shows Anna*, those rows sync like any
other label, and search finds them on every device — including devices that have not indexed a single
face themselves.

---

## 2. What is already decided (pointers, not restatements)

| Decision | Where | What it binds here |
|---|---|---|
| **IN-D3** — off by default, Settings-only, never onboarding; two switches (realm ∧ device), absence = no | `inference-service.md` §2 | `FACE_CLUSTERING` already carries `offerableInOnboarding = false`; both consents default `false` |
| **IN-D5** — labels sync; embeddings and **face templates** do not | `inference-service.md` §2 | §3's whole architecture |
| **CD-D7 + its reconciliation** — camera-side recognition of passers-by stays excluded; opt-in clustering of one's own library is permitted | `camera-service.md` §CD-D7, `inference-service.md` §2 | ⚖️ the counsel item, and the framing any public copy must not contradict |
| **IN-D9** — special-category inference is never in the default vocabulary | `inference-service.md` §7 | faces stay **out** of `LabelVocabulary` entirely |
| **IN-D10** — per-capability downloads, never one blob | `inference-service.md` §7 | the face model is its own catalog entry; a user who wants search does not pay for it |
| **IN-D13** — platform contributes where it is free | `inference-service.md` §7 (C0) | detection from Vision on Apple, ours elsewhere; **clustering is ours everywhere** |

⚠ **`InferenceCapability.FACE_CLUSTERING` already exists** and is already correct. What is missing is
everything it would switch on.

---

## 3. The architecture

```
   photo bytes ──▶ detect ──▶ 5 landmarks ──▶ ALIGN ──▶ embed ──▶ template (device-local)
                  (platform-free, Apple)     (OURS,    (OUR model,      │
                  (ours, elsewhere)          shared)    everywhere)     ▼
                                                               cluster (device-local)
                                                                        │
                                                              user names a cluster
                                                                        │
                                                                        ▼
                                                       one MediaLabel per photo ──▶ SYNCS
```

### 3.1 Three artifacts, three custodies

| Artifact | Lives | Syncs | Why |
|---|---|---|---|
| Face **boxes** | nowhere durable — recomputed or cached with the template | no | cheap relative to the embed; not worth a schema |
| Face **template** (the identity vector) | a device-local store shaped exactly like `EmbeddingStore` | **never** | IN-D5; it is the biometric artifact, and it is re-derivable from content the device already has |
| **`MediaLabel`** — *this photo shows Anna* | the realm, like any other label | **yes**, eager | the durable truth is a string the user typed |

⚠⚠ **The template store must not reuse the synced index marker for freshness.** §32.3 of
`inference-service.md` is the scar: a `MediaLabel` marker syncs, so a second device inherits "already
looked at" for vectors it does not have and never embeds anything. `EmbeddingStore.visited` /
`markVisited` exist for exactly this and the face store copies the pattern, including refusing a
vector whose `ModelProvenance` does not match its stamp.

### 3.2 Why a synced *label* gives cross-device consistency anyway

The maintainer's requirement was: *"for our own models the results should be the same."* It holds,
and without syncing a single template:

- Device A clusters locally, the user names a group, N label rows sync.
- Device B — which may have clustered differently, or not at all — **shows the same people on the
  same photographs**, because it is reading labels, not clusters.
- Device B's own clustering then has anchors: faces on already-labelled photos tell it what "Anna"
  looks like *in its own embedding space*, so its suggestions converge on the user's naming without
  any template crossing the wire.

⚠ The consequence IN-D5 tells us to state in the UI, and §7 puts on screen: **clusters may differ
between devices before labelling. The labels do not.**

### 3.3 One model family — and one **crop** — everywhere

Vectors from two different models are not *slightly* different — they are incomparable; cosine
similarity between two spaces is meaningless. So the identity embedder is **ours on every platform**,
distributed through the catalog pipeline (§33 of `inference-service.md`).

⚠⚠ **But sharing the weights is not enough, and an earlier draft of this section was wrong about
it.** That draft said only detection varies by platform, "because a box is a box", and accepted
slightly different crops as a rounding error. It is not one. Identity models are trained on crops
produced by a **5-point similarity transform** onto a canonical template — not on whatever the
detector's box contained. Apple's Vision boxes are notoriously tight (they exclude forehead, chin and
ears) and any detector we ship elsewhere frames differently, so cropping *by the box* would land the
same person's face in a **systematically** different region of the same model's space on each
platform. Not noise that a synced label absorbs: a consistent bias, applied to every photograph on
one platform, and invisible because the arithmetic succeeds either way.

⭐ **So the platform contributes boxes *and* landmarks, and the alignment is ours.** That is exactly
IN-D13's shape — the platform contributes the capabilities it owns — and it puts the maths in
`commonMain`, where it is unit-tested on the JVM rather than trusted separately in each backend.
Apple gives the landmarks free (`VNDetectFaceLandmarksRequest`, which detects as part of the same
request); a detector we ship produces its five points as ordinary output.

| Stage | Who | Why |
|---|---|---|
| detect → box + 5 landmarks | the **platform** where free, ours elsewhere | Vision runs it on the Neural Engine for zero model bytes |
| landmarks → aligned crop | **ours, shared** (`alignFace`) | the crop is what has to be identical, not just the weights |
| crop → template | **our model**, everywhere | one space, so a cosine between two devices' vectors means something |

⚠ A **similarity** transform — rotation, uniform scale, translation — never a full affine: letting
the fit shear would stretch a face onto the template and hand the model a distorted person, which is
a worse input than a slightly misplaced crop.

⚠⚠ The template itself (where the five points must land, and at what size) is a property of the
**checkpoint**, so it is required and has no default — the refusal §4 makes about the clustering
threshold, for the same reason. F2 supplies it from the checkpoint's own documentation.

⚠⚠ **CORRECTED 2026-09-21 — this section understated the constraint, and the correction is the
opposite of reassuring.** The paragraph below used to say the platform difference reduces to landmark
*precision*, which the shared alignment absorbs. It does not absorb a difference of **definition**.
Google's ML Kit documents `NOSE_BASE` as *"the midpoint between the subject's nostrils where the nose
meets the face"* — the nose **base**, not the nose **tip**; MediaPipe's detector emits a mouth
**centre** rather than the two corners. A 5-point similarity fit maps *that detector's idea of those
points* onto the template, so a different idea biases every crop on that platform — which is exactly
the failure this section opened by describing. Alignment fixes **framing**, not **semantics**.

⭐ **So the conclusion is stronger than "share the alignment": one detector everywhere, or a
per-platform template calibrated against that detector's own landmark definitions.** Mixing a
platform detector with ours is not free, and the free-detection argument for Vision (IN-D13) has to
survive this before it is taken.

⚠ What remains genuinely small, *given one detector*: landmark **precision** varies a little between
runs, so a borderline template differs a little. The synced label is authoritative, so that changes a
suggestion, never a fact.

---

## 4. ⚠⚠ The threshold is this slice's uncalibrated vocabulary

Clustering has exactly one number that decides everything: how close two templates must be to be the
same person. Too tight and one person becomes four rows; too loose and two siblings merge. Both are
immediately visible and both read as "this feature is broken".

§27.2 already taught this lesson on the label side, and the quote is worth carrying over: a floor of
0.500 *"sounds cautious and would have labelled **nothing at all**, because no (image, label) pair in
a real library reaches p = 0.5."* The fix there was to make an uncalibrated threshold
**unrepresentable** rather than to guess well.

⭐ **So: no literature default.** The threshold is measured against a labelled sample from a real
library before People ships, and the type that carries it requires it — the `VocabularyLabel`
pattern, not a `const val` with a plausible number in it.

⚠ A second knob hides behind the first: the **minimum cluster size** for a row to appear at all
(Google shows singletons; showing every one-photo stranger is noise). `groupPhotosByLabel` already
has `minPhotos = 2` as precedent for the Things grid.

---

## 5. What a named person costs on the wire

One `MediaLabel` per (photo, person). Fields are unchanged from today's rows:

| Field | Value for a person label |
|---|---|
| `capability` | `faceClustering` |
| `label` | the string the user typed — `"Anna"` |
| `confidencePerMille` | **1000 for a user-confirmed assignment**; the clustering score for a *suggested* one |
| `producedBy` | the detector+embedder provenance that proposed it |

⚠ **Superseded in part by §7's 2026-09-22 decision**: People suggests and never writes a suggestion,
so there is no "suggested" row and every face label is 1000‰ `User`. F6c keeps suggestions out of the
realm entirely (§6a F6c.3). The confidence split is still a design decision worth pinning: a human confirmation is not a model
output, and a reader that cannot tell them apart will eventually re-ask the user to confirm something
they already confirmed.

⚠⚠ **Probably no schema change at all** — `MediaLabel` already carries every field, and faces stay out
of `LabelVocabulary` by IN-D9. If that holds, IN-6 owes **no** `InferenceService` version bump, which
is worth verifying early because it changes the shape of F3 (HR #9). Verify with
`SchemaShapeBaselineEnforcementTest`, which names any reshape rather than leaving it to review.

---

## 6. Phase map

| | Slice | Ships | Gated on |
|---|---|---|---|
| **F0** | This document + the drafted amendment | Design and the threat-model entry drafted in §8, so ⚖️ counsel review can start while F1–F2 are built. ⚠ The amendment does not LAND here — it lands with F3, the slice that first writes a person's name into realm data | — |
| **F1** | The seam, against the fake | Face-shaped members on `InferenceBackend` (detect → box + **landmarks**, embed → vector), defaulted so existing backends stay valid; the **shared 5-point alignment** (§3.3); the device-local template store; clustering in `commonMain`. **No model, nothing user-visible.** | F0 |
| **F2** | The real models (⭐ **Android shipped 2026-09-24, §12.12**; desktop owes an ONNX detector) | A second catalog entry claiming `faceClustering`; new `ModelArtifactRole` constants for detector + embedder; per-artifact SHA-256. ⚠⚠ **Three gates, and TWO ARE NOW CLOSED.** ⭐ **Calibration** — chosen 2026-09-24, `FaceClustering.Measured` = 0.44 / `minFaces = 2`, wired in production (§10 Q2). ⭐ **Recall** — characterised with a candidate fix (§12.7–§12.8). ⚖️ **The licence** (§10 Q1, for the **embedder** alone now the detector half is settled by MediaPipe) is the one that remains, and no measurement answers it. ⭐⭐ Recall is now *characterised and has a candidate fix*: the single-stage path finds **80%** of the faces a short-range detector can see at 0.5, and a crop-stage second pass takes that to **93%** (replacement) or **95%** (hybrid). ⭐ The replacement costs **~3× less** landmarker time for one fewer face, so it is the better trade on this library. ⚠ Not closed — faces both models miss are invisible to that experiment, and the sample is 25 detections on one library. ⭐ **Shipped 2026-09-29 as tiled two-stage detection: 35 → 62 faces on the verified library, ~21 of the new ones real people, one cat group (§12.14).** ⭐ §12.6 removes the *quality* doubt for route 2; it chooses no numbers and measures no recall | F1 + ⚖️ a cleared checkpoint + recorded calibration + a recall answer |
| **F2d** | Desktop's detector — ⏳ **follow-up** | The MediaPipe detector converted to ONNX, with landmark parity against MediaPipe shown; a desktop face backend; a second catalog entry (runtime `onnx`); **the calibration re-measured** for that pipeline; then `faceClusteringRunsHere = true` on desktop. `tech-debt.md` *"People on desktop: no face detector"* | F2 |
| **F2v** | Versioned model installs — ✅ **shipped 2026-09-30** (§12.12, *F2v as built*) | `DefaultModelStore` stops overwriting bytes a live install record points at: every artifact lands at a content-addressed path, closing the equal-length commit window §12.12 records. ⚠ Shared with SigLIP; pre-F2v installs keep resolving at their flat paths with no move and no re-download. | F2 |
| **F3** | People in Photos | `people` beside `things` in `CollectionsUiState`; person grids via `Destination.Smart`; the naming flow; person names in search. **The threat-model amendment lands here** — it is where person names first become realm data | F2 |
| **F4** | The switch — ⭐ **shipped 2026-09-24** | `OfferedConsents += FACE_CLUSTERING`, Settings copy, off by default; withdrawal deletes the templates, keeps the names (§12.12) | F3 |
| **F5** | iOS | Vision detection + our embedder. ⚠⚠ **Bigger than it looks**: there is no iOS inference backend at all today, and **no ONNX Runtime on iOS** — so the embedder has no runtime there and giving it one is a slice about the size of IN-3b's Android step. Detection alone is small and well-precedented; People end to end is not. §11 collects what the research found | F1 |
| **F6** | People are people, not clusters — ⏳ **planned 2026-09-25**; ⭐ F6a (header) and F6b ("With…" chip) built, §6a; F6c **designed 2026-09-30** ([§6a F6c](#f6c--design-2026-09-30)), not built | People lists **named persons** (every photo carrying the name, across clusters) above unnamed clusters; a per-person **prototype** drives *"is this also Anna?"* suggestions (§7, decided 2026-09-22); person-wide rename / merge / remove-from-photo; the cover rule (§7). ⚠⚠ Needs a **face-level** assignment the per-photo label cannot give — §6a. The People row + its *View all* grid (3 columns, named first) follow the Collections-as-rows layout, `photos-app.md` §35 | F4 |
| **P1** | Pets | Its own detector and embedder — a human face model does not cluster dogs — and probably whole-animal re-identification rather than "pet faces" | F3 |

⚠ F1 before F2 is the repo's established order and it is not ceremony: IN-1 shipped *"no model ships;
everything downstream becomes testable"*, and §32 is the record of what happens when the fake is the
only thing a pass was ever run against.

---

## 6a. F6 — people, not clusters (planned 2026-09-25)

**What the user sees today (F3 as built):** People lists **clusters**, and naming writes the name onto
that cluster's photographs. Reported by the maintainer on first use: *"it groups people when they are
from the same group. But it should group all pictures which contain the same person."* Google Photos
behaves the second way, and so should we.

**What already works in the data:** labels are per photograph, and naming two clusters "Anna" folds
into one set of `(file, faceClustering, "Anna")` rows (§8, `labelKey`). So *"every photo Anna is in"*
is already a label query, and **group photos are fine at this level**: a photo of Anna and Bob carries
both names and appears in both people's grids.

⚠⚠ **Where the per-photo choice runs out: anything that needs to know WHICH FACE is whose.** §10 Q3
recorded the cost: two people in one photo give two labels and *"no record of which box was which"*.
F6 needs that record in three places:

1. **The prototype.** "Anna is the average of her confirmed faces" needs Anna's *vectors*. In a
   photo labelled Anna and Bob, the per-photo label cannot say which of the two faces to average.
2. **Suggestions.** *"Is this also Anna?"* asks about a **face**, not a photo. A group photo where
   Anna is already named still has an unnamed Bob to suggest.
3. **Corrections.** "Not Anna" and "remove Anna from this photo" have to name one face, or a
   correction on a group photo removes the wrong person.

**Proposed shape (2026-09-25)** — ⚠ **superseded by [F6c — design (2026-09-30)](#f6c--design-2026-09-30)**,
which makes each bullet exact (the map keys a face by its **box**, not its index; the derivation rule;
rejections recommended device-local, D2). Kept as the record of what was proposed:
- **A device-local face→person map**, kept beside the templates and never synced (IN-D5 stands: no
  biometric data and no per-face state on the wire). It is written at the moment of confirmation,
  when the device knows exactly which face the user tapped.
- **Synced truth stays the per-photo label.** Another device rebuilds its own face→person map from
  labels plus its own templates: a single-face photo is unambiguous; in a multi-face photo, faces
  already assigned to other named people are eliminated; whatever stays ambiguous is left out of the
  prototype rather than guessed.
- **Rejections ("not Anna") are device-local too**, keyed to the face, so the same face is not
  suggested again on this device. ⚖️ Whether a rejection should sync is a privacy question: it is a
  statement about a person in a photo, like a name.

**What Google Photos' person page does (looked at on the maintainer's phone, 2026-09-25):**
- **A header that says who defines the group:** a round crop of the person's face, their name, the
  photo count, and an action ("Share as album"). In a large group of shared photos this is what
  makes it readable: you know whose page it is before you read a single photograph.
- **"Only them":** a chip that narrows the grid to photographs where this person is the *only*
  face. It is the direct answer to group photos crowding a person's page.
- **"[face] +":** add a second person, and the grid shows photographs with both — a name
  intersection, so per-photo labels already support it.
- **Topic and place chips** ("Hiking", a location), combining a person with Things and Places.
- **"Same or different person? Improve these results"** as a quiet banner at the bottom — the
  confirmation flow, offered rather than imposed.

**Slices:** **F6a** — the header (cover face, name or "Add a name", count); "Only them" was pulled
after the device check and waits on a full-range detector (below);
**F6b** — named people across clusters (already true since F3b's `peopleFrom`), plus the "+" chip —
⭐ **built 2026-09-27**, below; **F6c** — the device-local face→person
map, suggestions and corrections (everything that needs face-level identity) — ⭐ **designed
2026-09-30, [below](#f6c--design-2026-09-30)**, as slices F6c-0…F6c-4.

**F6a as built (2026-09-25):**
- `GroupedFace` gained the face's box (thousandths of the photograph) and **`facesInPhoto`**,
  counted on the proxy over *every* face in the photograph. ⚠⚠ A client counting returned rows
  would miss faces in groups the call dropped for its limit or `minFaces`, and would file a photo
  of two people under "Only them". Inference service **1 → 2**, empty `SchemaMigration` step (a
  return type, never stored), baseline regenerated, threat model A40 amended.
- The cover is **the largest face** — §7's rule as far as today's data reaches. "Most frontal"
  needs a head pose, which the template store does not keep. A named person's cover comes only from
  a cluster the name *entirely* covers, because a labelled group photo cannot say which face is hers.
  ⚠ The cover is recomputed per refresh; pinning it once named needs face-level identity (F6c).
- ⚠⚠ **"Only them" was built, checked on the device, and PULLED (2026-09-27).** On the
  maintainer's phone a person with three photographs showed "Only them · 2" — and both were group
  selfies of five and seven people. The count it filtered on is what the **short-range** detector
  finds (`face_landmarker.task` bundles BlazeFace short-range, §12.6), which in a selfie is the one
  face at arm's length; the people behind are never counted. The chip turned §12.7's recall gap
  into a visibly false claim, so it is gone from the UI; `facesInPhoto` stays on the wire,
  documented as *detected* faces, so bringing it back needs no second reshape.
  ⚠ **Its precondition:** a detector that counts the people behind — the full-range model, or
  §12.8's crop stage *plus* full-range, measured on group photos, not only on §12.6's library.
  §12.8's 93–95% is recall of faces a short-range detector can see, which is exactly not the set
  this chip needs. The viewer-list seam (`onPhotoInListClick`) stays for when a filter returns.
- `FaceAvatar` crops the face from the ordinary preview, asking for a larger preview the smaller
  the face, with high-quality sampling (Skia's default is nearest-neighbour).

**F6b as built (2026-09-27) — the "[face] +" chip, client-side only:**
- A person's page shows a **"With…"** chip under the header. It opens a picker of every *other*
  person in `CollectionsUiState.people` — face and name, unnamed groups included as "Unnamed" — and
  choosing one narrows the grid to photographs of both; the header shows both faces (the second
  smaller, overlapping) with "Anna & Bob" and the narrowed count. The chip then reads "With Bob ✕",
  and tapping it restores the whole grid. The viewer pages through the narrowed list via the
  `onPhotoInListClick` seam F6a left for exactly this.
- ⚠⚠ **Intersection is by photo PATH** (`photosWithBoth`), over each person's list as `peopleFrom`
  already computed it. Nothing face-level is inferred: a named person's side is still exactly their
  labelled photographs, and which face in a shared photo is whose remains F6c's question. That is
  also why unnamed groups can be offered at all — a path works the same for both.
- "Other" is decided by every id the person answers to (`companionsFor`), so a route still holding
  a pre-naming cluster alias is never offered itself. The selection is screen-local and keyed on the
  route (`PersonHeader.key`): a different person opens un-narrowed, and a naming that re-keys this
  one keeps it.
- An empty intersection says **"No photos of both yet"** rather than showing a blank grid; the chip
  is **absent** when there is nobody else to pick. No schema or wire change.

**Open questions owed before building F6c** — ⭐ **answered by the design below (2026-09-30)**; each
line points at the part that settles it:
- The **suggestion threshold** is a separate number from the clustering threshold → **left as an
  explicit TBD with a measurement protocol**, [F6c.3](#f6c3-suggestions--is-this-also-anna).
- The **duplicate-face bug** (§12.8b) → ⭐ **closed** — §12.14's full-library store comparison
  (2026-09-30) found one exact duplicate pair in the single-stage store and none in the tiled one —
  and the design does not depend on it either way,
  [F6c.6](#f6c6-the-duplicate-face-question--expected-closed-not-closed).
- **Person-wide rename and merge** → one op, write-new-before-delete-old, `User` stamp copied,
  [F6c.4](#f6c4-person-wide-rename-and-merge).
- **A40 amendment** → drafted in [F6c.7](#f6c7-draft-a40-amendment--lands-with-the-f6c-code-not-here);
  it lands with the code, not with this document (House Rule 2).

### F6c — design (2026-09-30)

⚠ **Design only — nothing below is built.** Written against the code as it stood at `5a7f17874`:
`peopleFrom` / `FaceGroup` in `photos-shared-logic`, `NameFaceGroupImplementation`,
`FaceGroupsImplementation`, `DefaultFaceTemplateStore`, `clusterFaces`, and the
`NamingOutcome` / `UnfinishedNaming` machinery in `CollectionsViewModel` +
`TenshuPhotosRepository.nameFaceGroup`.

⚠ **Re-checked against master on 2026-10-14 (`5ebced788`).** What moved since `5a7f17874`, each
fixed in place below and marked *(2026-10-14)*: the pipeline stamp is **22**, not 2 (§12.15 — two
re-indexes since, one of which changed which box is kept); the non-human gate is **off**, so the
cat, doll, drawing and pastry are back in the store (§12.15); §12.8b's duplicate is **closed**
(§12.14); a removed template is now **struck and erased in place**, with no tombstone record, and a
deleted *or trashed* photograph leaves the store at the next complete pass (P0f-a, threat model A40
*Deleting the photograph*); the iOS template log has a fixed home (A40 *Where the templates live on
iOS*); and P0f-b's purge and move hooks (`media-processing-pipeline-sweep.md` S2/S4, designed, not
built) need the same label-tombstoning F6c-1 does. Still true on master: no `TombstoneEmitter` in
`:inference-service-proxy`, `InferenceService` at v2, `FaceClustering.Measured` = 0.44 /
`minFaces = 2`, and every type named above.

**The shape in one paragraph.** The synced truth stays exactly what it is — one `MediaLabel` per
(photo, name), stamped `ModelProvenance.User`. F6c adds one **device-local** store beside the
templates, the *face→person map*, holding a face's **position** and a **name**, never a vector.
Prototypes and suggestions are computed from that map plus the templates, inside the proxy, per
call, the way `faceGroups` already computes clusters. Rename, merge and removal are label edits:
they write new rows before tombstoning old ones, so an interruption can only ever leave a photograph
with **both** names, never with neither.

⚠⚠⚠ **The invariant F6c must not break: a named person is EXACTLY their labelled photographs.**
`peopleFrom`'s KDoc records six rounds of inferring a name's photographs from overlap, each wrong in
a new shape. Nothing below adds a photograph to a person without a label, and the only thing that
writes a label is a user's tap (naming, accepting a suggestion, moving a face). The map adds **which
face** on photographs that are already labelled; it never adds **which photographs**.

#### F6c.1 The face→person map

**What it stores.** One record per (face, name) statement the user made on this device:

| Field | What | Why this and not something else |
|---|---|---|
| `filePath` | the photograph, `:file-service`'s path | the key `MediaLabel` and the template store already use; a map keyed any other way could not be joined to either |
| `box` | the face's box as fractions of the upright photograph (the template store's own `FaceBox`) | ⚠⚠ **the face's identity, instead of `faceIndex`** — see below |
| `name` | the typed string, exactly as the label carries it | the synced person is a string (IN-D5); the map must key on the same thing or it cannot be reconciled against labels |
| `kind` | `CONFIRMED` or `REJECTED` | a rejection is a statement about one face and one name, and must survive to stop a repeat suggestion |
| `cover` | whether this face is the person's pinned cover | §7's *"pin it once named"*, which needs face-level identity and so waited for F6c |

⭐⭐ **No vector, no score, no model stamp — and that is the design move.** A template is
biometric and model-specific; a box and a typed name are neither. So:
- **A model rebase costs the map nothing.** §12.12's rebase clears the template store when the
  model or the pipeline stamp moves; the map is a separate file and is not cleared by it. The next
  pass re-derives templates, and the map's entries are re-attached to the new faces by position
  (below). IN-D5's "a swap costs scaffolding, never a name" extends to "never a face assignment".
- **A prototype is always in the current model's space**, because it is computed at read time
  from the *current* store's templates for the faces the map points at. There is no stored
  prototype to go stale.

**Where.** Proxy-side, device-local, a `FileDatabase` log beside `faces.log` (proposed
`/inference/face-people.log`, its own magic), with `DefaultFaceTemplateStore`'s log discipline:
append-only, torn tail truncated, clear by destroying the magic first — and, since P0f-a
*(2026-10-14)*, **a removal strikes the record and zeroes its bytes in place, disk before memory,
with no tombstone record** (`LogStrikes`; A40 *Deleting the photograph*). The map should take the
store's discipline as it now stands, not the 2026-09-30 one: a dropped entry names a person at a
position, so a removal that only hid it would leave that statement in the file. ⚠ Not app-side: prototypes need templates, and templates never cross IPC (A40). ⚠ Not in
the realm DB or the preference service — both persist to the synced realm, which is the thing
IN-D5 keeps per-face state out of.

**⚠⚠ How a face is identified stably — by box, never by `faceIndex`.** Today a face is
`FaceRef(filePath, faceIndex)` and `faceIndex` is a position in the detector's output list. That is
stable only for one store generation: the 2026-09-29 tiled pipeline (§12.14) re-indexed every
library — and has twice since *(2026-10-14: stamps 12 and 22, §12.15; stamp 12 also switched
de-duplication from the best-scoring box to the largest, so the same face can carry a different box
across one re-index)* — found **more** faces per photograph, and nothing makes a new pipeline's output order match the
old one's, so a map keyed on `faceIndex` could have silently pointed an assignment at a different
face. So:

- The map stores the **box**; `faceIndex` is only ever a lookup hint on the wire.
- **Re-anchoring:** on read, each map entry is matched to the current store's faces **in the same
  photograph** by overlap, one-to-one, best match first. The rule is the one the pipeline already
  uses for "the same face, two boxes": **intersection-over-smaller ≥ 0.5** (§12.14's box
  de-duplication). ⚠ Reused, not measured for this purpose — two *different* detectors frame
  differently (§3.3), so F6c-0 checks it against the real transition (single-stage → tiled boxes on
  the verified library) before F6c-2 relies on it. This is §10 Q3's *"matching one detector's face
  to another's by box overlap within a single photograph"* — not needed while no per-face state
  existed, needed now.
- A re-anchored entry is **rewritten with the new box** once the photograph has been visited under
  the current stamp, so drift never accumulates across successive pipelines.
- An entry that matches no face after a visit under the current stamp is **kept, dormant** — the
  detector no longer finds that face, and a later one may. It costs a few bytes and holds no
  biometric data. It is dropped when its photograph leaves the template store
  (`FaceTemplateStore.remove`, or *(2026-10-14)* `forget` from P0f-a's reconcile — the file was
  deleted **or trashed**, proven gone by a complete walk plus a point read) or when its label goes
  (below). ⚠ Between the delete and that pass, P0f-b PR-1's read filter (`LivePaths`) already hides
  the photograph from `faceGroups`; the map's reads must apply the same filter, or a deleted
  photograph's assignment would still shape a prototype.
- ⚠ The map is keyed by path like the labels and the templates, so it inherits whatever a file move
  does to them. *(2026-10-14)* D14 has since decided that a typed name **follows** its photograph on
  a move, rename, trash and restore (P0f-b's move hook S4, designed, not built). Without a matching
  map hook, a moved photograph's `CONFIRMED` entries die with the old path's label and the
  derivation re-finds only the unambiguous ones — degraded, never wrong. Not F6c's to fix; F6c must not make it worse by keying differently.

**How it relates to the synced labels — the map is a cache that may never outrank them.**
- **Labels first, map second.** Every write that creates a `CONFIRMED` entry writes (or finds) the
  label first. Interrupted between the two, the photograph is labelled and the map simply does not
  know which face yet — the pre-F6c state, which the derivation below repairs.
- **A `CONFIRMED` entry whose label is gone is dead**, and is dropped at the next read. This is how
  a rename or removal made on **another device** reaches this one: its tombstones remove the labels,
  and the map follows.
- **Derivation, for everything the map does not say** — how a device that never saw the tap (or a
  photograph named before F6c) learns which face is whose. §6a's proposed shape, made exact. For
  each photograph, with labels `L` and current faces `F`:
  1. Map `CONFIRMED` entries (with live labels) assign their faces.
  2. Remove the assigned names from `L` and the assigned faces from `F`.
  3. If exactly **one** name and exactly **one** face remain, they are each other.
  4. Otherwise the rest stays **unassigned**. ⚠⚠ Never guessed, never broken by a prototype —
     a prototype built from guesses is how one wrong face becomes a wrong person.
  ⚠ Derived assignments are **not written to the map**; they are recomputed per read, so a label
  change elsewhere can never leave a stale derived entry behind. Only a tap writes.
  ⚠ The recall gap cuts both ways here: a group selfie with three names and one *detected* face is
  ambiguous (step 4), which is right — the other two people are in the photograph, just not found.

**What survives what:**

| Event | Templates | Face→person map | Labels |
|---|---|---|---|
| Model or pipeline rebase (§12.12, §12.14) | cleared, re-derived | **kept**; re-anchored by box | kept |
| Photo deleted or trashed *(2026-10-14: P0f-a)* | struck and erased at the next complete pass; hidden from reads at once | its entries removed, the same way | unchanged by F6c (a permanent delete's labels are P0f-b S2's) |
| Consent withdrawn (explicit switch-off) | **deleted** (A40) | **deleted** — D1 | kept (A40) |
| Local store wipe / realm reset | deleted | **deleted** — keyed by path, so it must never outlive a realm (the `clear` KDoc's argument) | the realm's own business |
| Another device renames / removes | — | entries for the dead label dropped at read | synced |

⚠ **Withdrawal deletes the map (recommended, D1).** The Settings copy promises "switching off
deletes the face data and keeps the names". A per-face record of whose face is where is face data
in any reading a user would give that sentence, and it is almost entirely re-derivable: on
re-consent, the derivation above rebuilds every unambiguous assignment from the kept labels. What
is lost is exactly the ambiguous group-photo confirmations and the rejections — a few repeat
questions. ⚠ It happens where template deletion already happens (the next index pass and the next
People read), on an **explicit** switch-off only, for the reason A40 records.

#### F6c.2 What People lists once the map exists

- **Named persons** stay `peopleFrom`'s label-only lists (the invariant above). The map adds, per
  named person, **their faces** on those photographs — cover, header crop and "which face" in the
  viewer.
- **Unnamed groups are clustered from UNASSIGNED faces only.** Today `clusterFaces` sees every
  face and `peopleFrom` hides a group only when a name covers *all* of its photographs, so a group
  that is 80% Anna still shows as an unnamed card of Anna. With the map, Anna's assigned faces
  never enter clustering, so an unnamed group is **people nobody has named** — which is what the
  section is supposed to mean. ⭐ This retires the "entirely covered" hiding rule by construction
  rather than by threshold; the alias mechanism for open routes (`alternateIds`) stays.
- **The cover** is the largest assigned face (§7's rule as far as data reaches — still no head
  pose), chosen once and **pinned** in the map. It is re-chosen only when the pinned face goes
  dormant or its label goes. ⚠ Device-local: two devices may pin different covers. That is the
  price of not syncing per-face state, and §7's complaint was a cover that *changes*, not one that
  differs between devices.

⚠⚠ **Wire.** The app needs each assigned face (name, path, box, cover flag). Recommended as a
**new read op** returning a new composite (proposed `personFaces` → `AssignedFace`), not a field
appended to `GroupedFace`: an appended field is a positional reshape and owes an `InferenceService`
bump 2 → 3 (House Rule 9, F6a's precedent), while a new op is additive — the F3b precedent,
recorded in `NamingOutcome`'s KDoc, is that adding an op does not raise the service version and an
older Control without it is a *normal* mixed-version state the client already reports. ⚠ Confirm
with `SchemaShapeBaselineEnforcementTest` in the slice rather than trusting this paragraph — the
baseline must be regenerated for the new composites either way, and if the test demands a bump
for a pure addition, take it with an empty step. D5.

#### F6c.3 Suggestions — "is this also Anna?"

**Prototype (§7, decided 2026-09-22).** Per named person: the renormalised mean of the current
store's templates for that person's **assigned** faces — map `CONFIRMED` plus derived (step 3). ⚠⚠
Never ambiguous faces, never suggested-but-unanswered faces. Computed per call inside the proxy and
discarded, like a cluster centroid. A person with no assigned face (every photograph a crowded,
ambiguous one) has no prototype and gets no suggestions — correct, because anything proposed from
nothing is a guess.

**Candidates.** Every face that is (a) unassigned, (b) not `REJECTED` for this name, and (c) not
in a photograph where **another** face is already assigned this name — ⭐ one person appears once
per photograph, which also neutralises any duplicate detection that survives (F6c.6). A face on a
photograph *already labelled* Anna but ambiguous is a candidate too, and the most valuable one:
accepting it writes no label, it answers "which face is Anna here".

**Ranking.** Cosine to the prototype, descending, above the **suggestion threshold**. ⚠ The
threshold filters; order is the only thing that crosses IPC (D3) — no score leaves the proxy.

⚠⚠ **The suggestion threshold is TBD, deliberately, and it is NOT 0.44.** It answers a different
question: clustering's 0.44 decides "are these two faces one person" with no human in the loop and
was placed high because a false merge writes synced data (§10 Q2). A suggestion writes **nothing**
until a tap, so a wrong one costs a "No" — the asymmetry that put 0.44 high is absent, and the
number may well land lower. It is also measured against a **prototype** (a mean), whose geometry is
not a pairwise cosine's. ⭐ So F6c ships it as a required, typed value with an `Uncalibrated` case
that **emits no suggestions** — `FaceClustering`'s own pattern — and F6c-3 can merge dark before
the number exists.

**Measurement protocol (F6c-0).** What to run, and what counts:
1. **Where:** the verified library (§12.6's 400 photographs, OnePlus CPH2581), AuraFace int8, the
   shipped tiled pipeline (stamp **22** *(2026-10-14; was 2)*, §12.14–§12.15), through the shipped backend — a device test in the
   shape of `FaceBackendParityTest` (proposed `FaceSuggestionCalibrationTest`).
2. **Ground truth:** the groups verified by eye — the 33-photograph person and the pure 9-photograph
   person of §12.14 — plus every other detected face as a negative, **explicitly including** the
   doll, anime face, pastry and the cat group (§12.15 — ⚠ *(2026-10-14)* the norm gate is **off**,
   so all four are in the store and in the candidate pool, which makes them the negatives that
   matter). ⚠ §12.15 also found that the maintainer's library is indexed mostly from **1024 px
   previews**, where a face's embedding differs from the original's; record which decode each
   scored face came from, so the threshold is not chosen on originals alone. ⚠ Anything not verified by eye is excluded
   from the scoring, not assumed negative; §12.6's clusters 1–3 are unverified.
3. **Seeding:** for each verified person, build the prototype from `k` of their faces, `k ∈ {1, 3,
   5}`, chosen deterministically (sorted by path, and a second run from the other end), exactly as
   a user naming from a small first cluster would.
4. **Score:** every remaining face against that prototype. Sweep the threshold over the measured
   cosine range in fine steps and record, per step: suggestions made, how many are the seeded
   person (**a correct suggestion = a face verified by eye to be the person the prototype was built
   from**), how many are anyone or anything else, and how many of the person's remaining faces were
   never suggested. Also record the **nearest non-person score**, as §12.8c did for clustering.
   ⭐ Record the nearest-assigned-face score beside the prototype score, so whether a mean is the
   right prototype is answered from the same run rather than argued.
5. **Montages** of each threshold band's suggestions, §12.9's way, because a count cannot be checked
   and a montage can.
6. **Choosing:** D7 — the acceptance rule is the maintainer's; the recommendation is `FaceCalibrationTest`'s
   shape, pinned against the measured properties (nearest non-person score) rather than against
   itself, and **zero** non-person suggestions at the chosen value on this library. ⚠ One subject,
   one library again: an operating point, not a benchmark.

**Accept, reject.**
- **Accept** (`this is Anna`): write the label `(path, Anna)` if absent — `User` stamp, 1000‰ —
  then the map `CONFIRMED`. The next read has one more assigned face, so the prototype moves.
- **Reject** (`not Anna`): map `REJECTED(face, Anna)`. **Nothing synced** — D2. The face is never
  offered for Anna again on this device; it stays a candidate for other people.
- ⚠ **Rejections are remembered across rebases** because they key on the box (F6c.1), and
  across **merge**: merging Anna into Anne carries Anna's rejections over (D6) — if they are one
  person, "not Anna" is "not Anne".
- **Where it shows:** a quiet banner on a person's page, Google's *"Same or different person?
  Improve these results"* (§6a), opening a one-face-at-a-time review. ⭐ Offered, never imposed —
  §7's "People SUGGESTS, it does not auto-assign" holds; nothing reaches the realm without a tap.

#### F6c.4 Person-wide rename and merge

⚠⚠ **Why a rename is not a field update.** `MediaLabel.labelKey` is `"$file/$capability/$label"` —
**the name is part of the row's identity**. A rename is therefore, per photograph, a **new row**
under the new key and a **deleted row** under the old one: 2N writes for N photographs. (§9's
deferred `personId` would make it a field update; that is D10.) A **merge** is the same operation
where the target name already exists — the writes fold into existing rows by `labelKey`. One op
covers both.

**The op (proposed `renamePerson(from, to)`).**
- ⭐ **The proxy enumerates `from`'s rows itself.** The app sends two strings, not a path list, so
  none of `nameFaceGroup`'s Binder chunking (`NAME_CALL_BYTE_BUDGET`) applies, and there is no
  window where the app's list and the realm disagree.
- **Per photograph: write `(path, to)`, then delete `(path, from)`.** ⚠⚠ The order is the whole
  atomicity argument: interrupted anywhere, every photograph carries `from`, `to` or both — never
  neither. The screen then shows two people, which the user can see and merge; a photograph that
  silently lost its name is a loss nobody can see.
- ⚠⚠ **The `User` stamp is copied, never re-derived**: the new row is the old row with the name
  replaced — `producedBy`, 1000‰. Only `isUserAuthored` rows are touched; any other face-capability
  row (none exists today — §7's decision means suggestions are never rows) is left alone and
  counted, so a future model-written row can never be promoted to a user's name by a rename.
- **Idempotent**: re-running the same `(from, to)` finishes a torn run — writes fold, deletes of
  absent rows are no-ops.
- **Returns what it did** (photographs moved vs photographs holding `from` when it started), and
  the client maps a short count to `PartiallySaved`. ⭐ The `NamingOutcome` / `UnfinishedNaming`
  precedent applies verbatim: a partial rename is its own outcome, the retry is held keyed on
  **both** names (`UnfinishedRename(from, to)`), cleared only by a completing run, never by
  dismissing the dialog. ⚠ No durable journal (D4): the retry lives in UI state as today's naming
  retry does, and if the process dies the half-state is **visible** — two people — and the same
  gesture completes it.
- **One sync unit.** The client issues it inside `withTask` so the saves ride one task lane and
  reach peers as one checkpoint block (`core-task-composite.md` §10.6). ⚠ **Unverified: whether a
  tombstone emitted in that request joins the lane.** If it does not, peers see the new rows before
  the old ones' tombstones — a transient superset, the safe direction by the same ordering argument.
- ⚠ **Size is unmeasured.** A person with thousands of photographs is thousands of saves and
  tombstones in one request; whether one lane block of that size is acceptable is for the slice to
  measure, and chunking inside the proxy (still new-before-old per chunk) is the fallback.
- ⚠ Serialised per proxy with a mutex over person-mutating ops, so two renames on one device
  cannot interleave.
- The map follows in the same op: `from`'s entries are rewritten to `to`, including rejections
  (D6).

⚠⚠⚠ **Deletes must reach the relay, and the inference proxy cannot do that today.** Nothing in
`:inference-service-proxy` deletes a label: it has only ever saved. A bare
`ExecutionContext.deleteData` removes the local row and records only a *local* short-circuit
tombstone (`TombstoneRegistry.localTombstones`, in-memory) — peers never hear of it, and the
relay's echo of this device's own earlier save can resurrect the row. Label deletion needs a
`TombstoneEmitter` injected into the implementation, as `DeleteFileImplementation` does. ⚠ A
two-device system test is the only thing that catches the omission; a single-store unit test
passes without it.
⚠ *(2026-10-14)* **Not F6c's alone any more.** P0f-b's purge hook (S2) and move hook (S4) —
`media-processing-pipeline-sweep.md` §6.4, designed, not built — also delete `MediaLabel` rows from
`:inference-service-proxy` and plan the same wiring: the `TombstoneEmitterHolder` Control already
hands the file and health services, composed in Control's `ProxyModule`, one `emitAll` batch per
operation, and **delete nothing when `emitAll` answers `null`** (the realm is locked). Whichever of
F6c-1 and P0f-b S2 lands first owns the injection; the other reuses it rather than adding a second.

**Concurrent edits from another device.** `MediaLabel` is `LastWriterWins` per `labelKey`, and
tombstones short-circuit only *older* blocks, so:

| Device A | Device B, concurrently | Result | Why it is acceptable |
|---|---|---|---|
| renames Anna → Anne | names new photographs "Anna" | Anne (old photos) + Anna (B's new ones) | two visible people, one merge fixes it; nothing lost |
| renames Anna → Anne | renames Anna → Ann | Anne and Ann on every photograph | a superset; merge fixes it |
| removes Anna from a photo | re-names Anna on that photo | whichever HLC is later | LWW is the realm's rule for every row; the loser is visible and re-doable |
| renames, then renames back | — | the later rows win over the earlier tombstones | undo works; a tombstone does not block a newer save at the same key |

⚠ The first row is the standing cost of name-as-identity; a `personId` (§9) would remove it, and
the reason §9 defers that is the consent story, not this design.

**Where it shows.** Rename on the person header (the name is tappable once F6c-1 lands). Merge is
rename to a name that already exists, with a confirmation that names both people and the count —
*"Merge Anna (41 photos) into Anne (12)?"* — because it cannot be undone by a second rename once
both names' photographs are one set.

#### F6c.5 Corrections

Three actions, and ⚠⚠ **each says whether it touches synced data**, because a correction that
silently removed a label is exactly the invisible loss rename's ordering exists to prevent.

1. **"Not Anna" on one face** (from a suggestion, or on an assigned face in the viewer):
   map `REJECTED(face, Anna)`, and drop a `CONFIRMED(face, Anna)` if there was one. The **label**
   is deleted only when no other face in that photograph could still be Anna — none is assigned
   Anna, none is unassigned. Otherwise it stays, and the other face becomes a candidate. ⚠ When it
   does delete, the UI says so (*"Removed from Anna's photos"*, with undo) — D8.
   ⚠ Rationale: a label was only ever written from a detected face, so "this face is not Anna" plus
   "no other face could be" means the label rests on nothing. "Anna is not in this photograph" is a
   different claim, which the next action makes explicitly.
2. **Remove a photograph from a person** (selection on the person's grid): tombstone the label
   `(path, Anna)` and drop every map entry for Anna on that path. Photo-level, explicit, no
   inference. Takes a path list, so it chunks like `nameFaceGroup`.
3. **Move a face to another person** ("This is Bob"): write `(path, Bob)` first, then the
   `CONFIRMED(face, Bob)`, then action 1's rule for Anna. New before old, for rename's reason.

⚠ Every write carries the face's **box** as the app received it, beside `faceIndex`. The proxy
re-anchors (F6c.1) and **refuses** a write whose box no longer matches a current face — an index
pass ran while the screen was open — with a new `FaceGroupsUnavailable` reason the client treats
as "refresh and ask again". ⚠⚠ Without that, a stale screen would write a name onto whichever face
now sits at that index: `NameFaceGroup`'s *"a name applied to an id resolved later could land on a
different person"*, one level down.

#### F6c.6 The duplicate-face question — expected closed, not closed

§12.8b found one face detected twice in one photograph; §12.14's tiled pipeline keeps a mesh only
if it is not a second mesh of a face already kept (≥ 0.8 overlap), which **should** close it.
⭐ *(2026-10-14)* **Closed by measurement, on the store rather than on one photograph:** §12.14's
per-photo comparison of the single-stage and tiled stores over the full library (1,258 photographs,
2026-09-30) found **one exact duplicate pair in the old store and none in the tiled one**. ⚠ Whether
`IMG20260110143831` (the photograph §12.8b named) was looked at individually is not recorded; the
store comparison covers it either way. The heading's "not closed" is kept as written on 2026-09-30.

⭐ F6c does not depend on the answer. A surviving duplicate is (a) ambiguity in the derivation
(two faces, one name → step 4, unassigned), never a second person, and (b) excluded from
suggestions for any name already assigned on that photograph (F6c.3's once-per-photograph rule).
What it would still cost is a spurious unassigned face offered for *other* people — a "No" away.

#### F6c.7 Draft A40 amendment — lands with the F6c code, not here

⚠ **DRAFT.** House Rule 2: the amendment lands in `docs/sync/threat-model.md` **in the same PR as
the code it describes**. Each paragraph below is tagged with the slice that lands it; this
document does not edit the threat model.

> **(F6c-1) Label edits.** `nameFaceGroup` is no longer the only operation in this feature that
> writes realm data. `renamePerson`, `removePersonFromPhotos` and the face corrections also write
> `MediaLabel` rows and **delete** them. They carry only paths and typed names, write only rows
> stamped `ModelProvenance.User`, copy that stamp rather than re-deriving it, and never touch a row
> a model produced. A deletion is an ordinary tombstone on the sealed sync plane: the relay sees a
> keyed wire id (EI-1) and a `DeleteBlock`, never a name — **House Rule #1 is unchanged.** ⚠ The
> relay can observe the *shape* of a rename (a burst of N saves and N tombstones), as it can
> observe a naming today; no new metadata class. Consent gates every one of these writes exactly
> as it gates `nameFaceGroup`.
>
> **(F6c-2) The face→person map.** A second device-local face artifact beside the templates: one
> record per face the user assigned to, or rejected for, a name on **this** device, holding the
> photograph's path, the face's **box**, the typed name and whether it is the person's cover — and
> **no vector and no score**. It is special-category (it places a named person at a position in a
> photograph) and has the templates' custody: a per-device log (`/inference/face-people.log`) that
> syncs nothing and appears in no schema. It is **not** cleared by a model rebase (it holds nothing
> model-specific); it **is** deleted on an explicit consent withdrawal at the same two places as
> the templates, and on a local store wipe. *(2026-10-14, to align with A40 as amended since:)*
> its entries for a deleted or trashed photograph are hidden by the same read filter and **struck
> and erased in place** by the same reconcile as the templates (*Deleting the photograph*, P0f-a);
> and on iOS it lives where the templates live — Photos' own container, excluded from backup,
> cleared by the realm-stamp check (*Where the templates live on iOS*). Its read (`personFaces`) returns paths, boxes and
> names to the app — position and a name the app already holds as a label, never a vector.
>
> **(F6c-3) Suggestions.** "Is this also Anna?" is computed in the proxy from the current
> templates and a per-person prototype that is built per call and never stored. ⚠ What crosses IPC
> is new in kind: a **proposed** identity — this face, at this box, may be this named person. It
> carries a path, a face index, a box and a name, and **no similarity score and no vector**; the
> app renders it and stores nothing. A proposal writes no realm data; only a user's acceptance
> does, through the label path above. Rejections are device-local map records and never sync.
>
> **Residual (unchanged in kind).** A device compromise yields the map as it yields the templates.
> A realm member admitted later (A26/A27) sees labels and tombstones, never the map or a
> suggestion.

#### F6c.8 Slices

Each ships alone, behind the existing switch, and each lands its own part of the A40 draft.

| Slice | Ships | HR #9 | Gate |
|---|---|---|---|
| **F6c-0** — measure | `FaceSuggestionCalibrationTest` (F6c.3's protocol); the re-anchoring rule checked against single-stage vs tiled boxes on the verified library (*(2026-10-14)* and stamp 2 vs 22, which changed the kept box from best-scoring to largest); ~~the duplicate-face confirmation (F6c.6)~~ closed by §12.14's store comparison. **No product code.** Can run beside F6c-1. | none | numbers + montages recorded in this doc; the suggestion threshold chosen by D7's rule, or recorded as not yet choosable |
| **F6c-1** — label edits | `renamePerson` (merge = rename onto an existing name), `removePersonFromPhotos`; `TombstoneEmitter` into the inference proxy; rename on the person header, merge confirmation, remove on grid selection; `UnfinishedRename`. **No map needed** — the highest-value slice is independent of face-level identity. | two new ops, no reshape of an existing composite (F3b precedent) — confirm with the baseline test | `jvmTest`: new-before-old order, `User` stamp copied, non-user rows untouched, idempotent re-run, a torn run leaves a superset. ⚠⚠ **A two-device system test**: the tombstones propagate and the echo does not resurrect. `run-gate.sh`; Android smoke; a device rename + merge. A40 ¶ F6c-1 |
| **F6c-2** — the map | the device-local store (F6c.1), re-anchoring, the derivation rule, `personFaces`, clustering over unassigned faces only, the pinned cover; a naming op that records **which faces** were named (proposed `nameFaces`, carrying face addresses; `nameFaceGroup` stays for older clients) | new op(s) + composites, additive; ⚠ `GroupedFace` untouched on purpose (D5) | store tests in `DefaultFaceTemplateStore`'s shape (torn tail, clear, reopen); derivation (single, eliminated, ambiguous, duplicate, recall-gap); rebase keeps the map; withdrawal deletes it; ⚠⚠ `peopleFrom`'s "a named person is exactly their labelled photographs" tests stay green unmodified. Device: re-index with a new pipeline stamp, assignments survive. A40 ¶ F6c-2 |
| **F6c-3** — suggestions | prototype, candidates, `faceSuggestions`, accept / reject, the banner and review; `SuggestionThreshold.Uncalibrated` until F6c-0's number, so it can merge **dark** | new op + composite | ranking and exclusion rules against the fake; `Uncalibrated` emits nothing (a test that fails if it ever emits); device review on the verified library at the chosen threshold. A40 ¶ F6c-3 |
| **F6c-4** — face corrections | "Not Anna" and "This is Bob" on a face in the viewer (F6c.5 actions 1 and 3), the stale-box refusal | new op(s), or F6c-3's accept/reject reused with a face address | the label-deletion rule (only when no other face could carry it), new-before-old on move, stale-box refusal; device check on a group photo |

⚠ Every new op needs its `BundledAppManifestSource` entry **with its caller**, in the same slice
— `BundledAppTablesTest` refuses an early declaration.

#### F6c.9 Decisions owed by the maintainer

| # | Decision | Recommendation | Why |
|---|---|---|---|
| **D1** | Does consent withdrawal delete the face→person map? | **Yes**, with the templates | it is face data by the Settings copy's plain meaning, and all but ambiguous confirmations and rejections re-derive from the kept labels |
| **D2** | Do rejections ("not Anna") sync? (§6a's ⚖️ question) | **No — device-local** | a rejection is a statement about a person in a photograph; syncing it widens A40 and the realm for the cost of one repeat "No" per device |
| **D3** | Does the similarity score cross IPC with a suggestion? | **No — order only** | the app needs a ranking, not a number; a score is derived biometric output the app has no use for |
| **D4** | A durable journal for an interrupted rename? | **No** | write-new-before-delete-old makes every half-state visible (two people) and the same gesture completes it; `UnfinishedNaming` is the precedent |
| **D5** | Assigned faces: a new read op, or a field appended to `GroupedFace`? | **New op** (`personFaces`) | additive; appending is a reshape and an `InferenceService` bump that every client must cross in lockstep |
| **D6** | On merge, do the old name's rejections carry over? | **Yes** | a merge asserts one person, so "not Anna" is "not Anne" |
| **D7** | The acceptance rule for the suggestion threshold | **Zero non-person suggestions on the verified library, and a margin rule pinned to the measured nearest non-person score**, `FaceCalibrationTest`'s shape | pins the number to evidence rather than to itself; the rule is a product call because it trades misses against wrong asks |
| **D8** | May "Not Anna" on a face delete the label? | **Yes, only when no other face in the photograph could carry it**, stated on screen with undo | otherwise a wrongly named one-face photograph can only be fixed by a second, photo-level action the user has to find |
| **D9** | Consent for label-only edits (rename, merge, remove): realm ∧ device, as `nameFaceGroup`, or realm only? | **Realm ∧ device, as today**; revisit with F2d | consistent with A40's "consent gates writing a name"; the cost is that a device without the switch (desktop today) cannot rename, as it cannot name |
| **D10** | Introduce §9's `personId` now, to make rename a field update? | **No, still deferred** | rename works at 2N rows; the concurrent-rename cost is two visible people and one merge; §9's reason to wait (the consent story) is unchanged |
| **D11** | Is the pinned cover device-local? | **Yes** | syncing it is per-face state on the wire, which IN-D5 excludes; §7's complaint was a cover that changes, not one that differs between devices |

## 7. The UI, in the shape Photos already has

- **A `people` list beside `things`** in `CollectionsUiState`. `things` is described as *"the section a
  user cannot get any other way"* and is ordered first; People sits with it, not with places and years.
- **A person opens as a `Fixed` collection** via `Destination.Smart`, like any other smart album.
- **Naming** is the one durable act, and the copy has to say what it does — *"Anna will be added to
  these photos"* — because it writes rows that sync, not a local nickname.
- **Search**: a person's name should hit. `PhotoHitSource` is `{ NAME, LABEL, SEMANTIC }` today and a
  person label rides `LABEL` for free; whether it earns its own source is an F3 judgement.
- ⭐ **The cover photograph is CHOSEN, not taken.** The row shows one face per person, and which one
  decides whether the section looks considered or arbitrary. Raised by the maintainer about Google
  Photos: *"Not sure how Google finds this photo, it seems rather random?"* ⚠ Whatever they do, ours
  should be a **stated rule**, because an arbitrary cover is the first thing a user reads as
  carelessness — and because a rule is testable where a heuristic nobody wrote down is not.

  ⭐ MediaPipe gives what the rule needs, in the same pass: the **box area** (how much of the frame
  the face fills) and, from the bundled face-geometry pipeline and blendshapes, the **head pose**
  (yaw/pitch/roll). So a cover can be the largest, most frontal face for that person rather than the
  first one clustered. ⚠ Sharpness is the third term and we do not get it free — leave it out until
  someone measures whether it is needed, rather than inventing a blur metric to go with it.

  ⚠ Recompute it when the cluster changes, and **pin it once named**: a People row whose face changes
  every time the library grows reads as instability, even when each choice was individually better.
- ⚠ **State that clusters are local.** IN-D5's consequence is not a footnote: a user who names Anna on
  their phone and opens their laptop will see different *unnamed* groups, and nothing about that is a
  bug. One line under the section is enough.
- ⭐⭐ **DECIDED 2026-09-22 — People SUGGESTS, it does not auto-assign.** The device proposes *"is
  this also Anna?"* and a tap confirms; nothing is written to the realm without one.

  ⚠⚠ **This is a licensing decision as much as a product one**, which is why it is recorded here
  rather than left to F3. An auto-assigning grid must be right on its own, so it needs the best
  available embedder — and `models.md` §5 shows the best available ones are the ones we cannot
  licence. A suggesting grid only has to be **good enough to propose**, which brings the MIT
  synthetic checkpoints (HyperFace, HSFace — roughly 0.5–1.5 LFW points behind) into range and may
  remove the need to licence anything. §10.4 carries the reasoning.

  ⚠ It also gives `confidencePerMille` its meaning (§5): **1000 for a confirmed assignment**, the
  clustering score for a proposed one. A reader that cannot tell them apart will re-ask the user to
  confirm what they already confirmed.
- ⚠⚠ **Never in onboarding.** IN-D3: *"an onboarding flow that asks is an onboarding flow that
  pressures."*

---

## 7a. ⭐⭐ One detector everywhere — and why that runs against IN-D13

**Recommendation: ship MediaPipe Face Landmarker on every platform, including Apple.**
⚖️ The Apple half is the maintainer's call and is marked open below.

IN-D13's standing rule is *"the platform contributes acceleration and the capabilities it owns"* —
take the platform's free thing where it exists. Its own table says face **detection** comes from
Vision on Apple. ⚠⚠ **For this capability that rule is the more expensive option, and the reasons are
specific rather than a general objection to IN-D13.**

| | Platform route (Vision on Apple, ML Kit on Android) | One model (MediaPipe) |
|---|---|---|
| Landmark semantics | **three conventions** — Vision's, ML Kit's, ours on desktop | **one**, everywhere |
| Alignment template | one **per detector**, each needing a calibration set nobody has | one, and it is already verified three ways (`models.md` §5.6) |
| Desktop | ❌ nothing exists; ours regardless | ✅ covered |
| Android size | ML Kit bundled is **+6.9 MB** | **~2.7 MB** for the pair |
| What ships | a closed SDK binary | **Apache-2.0 weights we host** |
| Reproducible build | ⚠ no — a blob the APK cannot be built from | yes |

⚠⚠⚠ **Neither platform route can close the licence gate, and that is the likeliest misreading of
this section.** The gate (§10 Q1) is on the **embedder** — stage 3 of §3.3's table — and *no platform
exposes one*:

- **Apple Vision has no public faceprint API.** Verified 2026-09-23 against the macOS SDK headers:
  no `Faceprint` symbol in any public Vision header, and the entire `VNGenerate*Request` family is
  saliency, optical flow, masks, segmentation and `VNGenerateImageFeaturePrintRequest` — a
  **general-image** descriptor, not face identity. Apple Photos clusters people internally; that API
  has never been public.
- **ML Kit Face Detection has no embedding at all** — boxes, contours, classification
  (smiling, eyes-open) and tracking IDs only.

So the platform route swaps out the half that is **already settled** (MediaPipe, Apache-2.0) and
leaves the gated half exactly where it was. ⚠ A hypothetical platform embedder would not rescue it
either: §3.3 requires **one vector space** — *"a cosine between two devices' vectors means
something"* — so we would still ship our own model on every non-Apple platform, Android included.

Three things decide it:

1. ⚠⚠ **Desktop needs our own model whatever we do**, because no platform detector exists there. Once
   one exists, using it on the other platforms costs less than not doing so.
2. ⚠⚠ **iOS needs an inference runtime for the EMBEDDER regardless** — there is none today, and that
   is the largest single item in F5. Once it is there, MediaPipe's detector is 2.7 MB on top. Vision
   saves **bytes, not structure**.
3. ⚠ **A second landmark convention is not free.** §3.3 records that alignment fixes framing, not
   semantics: ML Kit's `NOSE_BASE` is *"the midpoint between the subject's nostrils"*, not the nose
   tip. Every extra detector is another template to calibrate and another way for one platform's
   crops to be quietly worse.

⚠ **What the platform route would genuinely buy on Apple** is the Neural Engine for zero model bytes.
But TFLite has a Core ML delegate, so MediaPipe can reach the ANE too — **unmeasured on both sides**,
and this repo has been wrong about acceleration before (IN-3m: NNAPI lost to the plain CPU provider).

⭐ **It stays reversible by construction.** Detection sits behind the backend seam, so an
iOS Vision detector — or ML Kit on Android — can be added later if a measurement favours it, at the
price, then, of the per-platform template §3.3 describes.

⚠⚠ **But "reversible" is not "free", and the bill is a re-index.** Changing a platform's detector
changes its landmarks, hence its crops, hence **every template that platform has stored** — the old
vectors are not comparable with the new ones, so that platform must re-index its whole library.
⭐ It is recoverable rather than destructive, and that is IN-D5 paying off: templates are
device-local and labels are the synced truth, so a re-index costs the user their clustering
**scaffolding**, never a name they typed. ⚠ Pre-release it is close to free (nobody has a stored
library); after release it is a migration, so a detector swap is a decision to take **before** F4,
not after.

⚖️ **Open — the maintainer's call:** whether to take Vision on Apple anyway. The recommendation here
is no, on grounds 2 and 3; the counter-argument is that Vision is free, native and already blessed by
IN-D13.

---

## 8. The threat-model amendment — ⭐ **LANDED 2026-09-23 as A40**

`docs/sync/threat-model.md` §5 carries it; **that copy is the one to trust**, and the draft below is
kept only as the record of what was proposed. It landed with F3b, the slice that first writes a
person's name into realm data, per House Rule 2.

⚠ The as-built entry says three things this draft did not, because the code answered them:
`faceGroups` returns paths and an ephemeral id so **no vector crosses IPC**; names are stamped
`ModelProvenance.User` so a re-index may never overwrite one; and consent gates **reading stored
templates and writing a name**, not merely deriving new ones — the stale-client window is real.

The draft, as proposed — modelled on **A34** (health) and **A35** (location), both Article 9 or
adjacent and both saying plainly that HR #1 is unchanged:

> **A40. Face templates and person labels (GDPR Article 9 special-category).** Two artifacts with two
> custodies, and the split is the protection.
>
> **Templates** — per-face identity vectors derived on-device by the user's own opted-in devices — are
> **device-local and never enter the realm**: they are written to a per-device store that syncs
> nothing, are re-derivable from content the device already holds, and are dropped when the local
> store is wiped. No biometric template is ever realm data, so none is ever relay-visible, and there
> is no ciphertext on the relay from which one could be recovered.
>
> **Person labels** — `MediaLabel` rows of the form *this photo shows Anna* — **are** realm data and
> are special-category under Art. 9, because they associate an identified natural person with an
> image. Custody is the ordinary sealed sync plane: keyed and encrypted like every other row (A2), so
> **House Rule #1 is unchanged — the relay sees ciphertext blocks and a device identity (A4), never a
> name and never a face.** The label's content is a string the user typed, not a model output.
>
> **Acquisition** is gated twice (IN-D3): a realm switch and a per-device switch, both defaulting to
> *no*, offered in Settings only and never during onboarding, with consent re-read per file so a
> withdrawal stops a running pass rather than taking effect at the next one. With no consent the
> photograph's bytes are never read.
>
> **Scope boundary:** this covers clustering of faces in the user's **own library**, on their **own
> device**. Camera-side recognition of non-consenting third parties remains excluded as policy
> (`camera-service.md` CD-D7); the distinction is argued in `inference-service.md` IN-D3 and is ⚖️ a
> counsel-review item before release.
>
> **Residual:** a device compromise that already yields the realm keys also yields the person labels —
> no new at-rest trust boundary beyond the existing per-app store (A21/A32). A realm member admitted
> later (A26/A27) sees person labels like any other row; the templates never travel to them.

⭐ **DECIDED 2026-09-24 (F4): templates are deleted, names are kept** — as-built in §12.12 and threat model A40; ⚖️ the label half stays a counsel question. The original framing follows. What withdrawing consent *deletes*: Templates are obvious — they
go with the local store. Person labels are less obvious, because they are the user's own words about
their own photographs, and deleting them on a switch-off may destroy something they meant to keep.
`inference-service.md` §32.8 already records *"Consent does not delete"* as unbuilt for the search
case; faces make it pressing rather than theoretical.

---

## 9. What this does not do

- **No pets** (P1). A human-face embedder will not cluster animals, and "pet faces" is probably the
  wrong framing — whole-animal re-identification is the literature's answer.
- **No relationship to `:contact-service`'s `Person`.** The contact graph has a `Person` aggregate
  (threat model A24), and linking the two is tempting and premature: IN-D5's durable truth is *a
  string the user typed*, which needs no identity. Linking would drag the contact graph into Photos'
  consent story for no gain this slice can name.

  ⚠ **Revisited 2026-09-22 and still deferred — but the maintainer's argument for it is sound and
  worth keeping.** A free-text label merges by **string match**: `"Anna"` and `"anna"` collide only
  because `groupPhotosByLabel` lowercases, and `"Anna"` and `"Anna Schmidt"` never merge at all. A
  stable `personId` fixes that, and ⭐ it is **purely additive**: a `MediaLabel` could gain an optional
  id without changing anything already synced. The reason to wait is the consent story, not the
  design.
- **No face-based search grammar** ("photos of Anna at the beach"). Mia's planner is a separate track
  and person labels are ordinary labels to it.
- **No iOS UI.** There is no `photos-ios` module; F5 is the backend only.

---

## 10. Open questions

1. ⚖️ **Which checkpoint — surveyed 2026-09-21, and the answer is that nothing clears the bar.**
   `models.md` §5 is the evidence; the short version is that **both halves have the same owner**.
   WIDER FACE ships no landmark annotations, so every open 5-point detector trains on InsightFace's,
   whose terms say *"the models trained with these data are available for non-commercial research
   purposes only"* — and every open identity embedder descends from MS-Celeb-1M, CASIA-WebFace,
   VGGFace2 or WebFace260M, all four closed. ⚠ The binding constraint is therefore the **training
   data**, one layer below the model card this row originally pointed at.

   ⭐⭐ **UPDATE 2026-09-22 — the problem is now HALVED.** `models.md` §5.9: **MediaPipe Face
   Landmarker is Apache-2.0 on the weights, over Google's own consented in-house data**, with no
   public-dataset lineage at all. It gives boxes *and* the five points (iris rings for true pupil
   centres, lip vertices 61/291 for the corners) in ~2.7 MB. ⚠ It is not free of questions — no
   official ONNX, the grant lives in a model-card PDF rather than beside the weights, and its stated
   intent is AR selfies rather than a library of rear-camera photographs — but the **licence and
   corpus rows are clean**, which nothing else in this audit managed.

   ⚠⚠ **So the routes below now apply to the EMBEDDER ONLY.** That also re-ranks them: we no longer
   need anybody's detector, so route 3 (AuraFace) shrinks to exactly the one file it is defensible
   for, and route 4 (InsightFace) loses half of what it was buying.

   Four routes, cheapest first — **the first three cost only an email, and asking is reversible**:

   | | Route | What it costs / what it leaves open |
   |---|---|---|
   | 1 | Push `opencv_zoo#313` | Free. The clearance question for SFace is already open and unanswered; ⚠ its upstream grants nothing and its corpus is unknown, so even a reply may not suffice |
   | 2 | **Vec2Face/HSFace** or **HyperFace** (MIT, synthetic) | Free, and MIT **on the code**. ⭐⭐ **The accuracy worry is measured rather than assumed**: §12.6 carries 29 photographs of one person in one cluster, confirmed by eye, stable across 0.20–0.52, on a real roll — exported to ONNX ourselves at 42 MB int8 (`models.md` §5.11). ⚠⚠⚠ **CORRECTED 2026-09-23 — FFHQ was never the binding constraint, and this row used to say it was the whole question.** Vec2Face's generator trains on **WebFace4M** (`--train_source ./lmdb_dataset/WebFace4M/WebFace4M.lmdb`, verified in the upstream README), so HSFace sits downstream of WebFace260M — academic-research-only and non-redistributable — exactly like the models route 2 was supposed to escape. §10a |
   | 3 | **AuraFace**, with written confirmation | Best free accuracy, ready ONNX. ⭐⭐ **Measured 2026-09-24 (§12.8a): the same clusters as HSFace on the verified library, with the same members and one false cluster fewer**. The int8 export is 66 MB and ~60 ms per face on the phone (§12.8b), and ⭐ a hash test shows its recognition weight really is fal's own. ⚠⚠ But the same repo Apache-tags four files that are provably InsightFace's, and fal has ignored provenance questions for a year. `glintr100.onnx` **only**, never the repo |
   | 4 | Licence from **InsightFace** | The only route with an actual written grant, and it settles the detector half too since it is the same rightsholder. ⚠⚠ Requires a **written warranty that they hold WebFace260M redistribution rights** — otherwise it repeats the pattern that disqualified the others, with us paying for it. Pricing is not public |

   ⭐⭐ **DECIDED 2026-09-23 (maintainer) — engineering proceeds on route 2. ⚠⚠ The question is NOT
   closed, and collapsing those two is the failure this entry exists to prevent.**

   - **What was decided:** build against **HSFace** as the assumed embedder, so the slices below are
     no longer held up by a question that no amount of engineering can answer. ⚠⚠ **Weaker the same
     day than when it was taken**: §10a found HSFace's generator trains on WebFace4M, so the
     assumption being made is larger than the FFHQ one this entry was written against. It still
     costs nothing, because the thing built against it is a fake.
   - **What was NOT decided:** whether we may **ship** it. ⚠⚠ Route 2's WebFace4M ancestry (§10a) is
     untouched by this, and **shipping weights is redistribution**, which is exactly what WebFace260M's
     terms forbid.
     It stays a **release** gate, owed before F4, not a formality to be waved through later.
   - **Why proceeding is safe regardless:** F3 is built against `FakeInferenceBackend` per F1's rule,
     so no product surface depends on which checkpoint wins. A different embedder is a swap behind
     the seam, and §7a prices it: a re-index that the synced labels survive.

   ⭐⭐ **DECIDED 2026-09-24 (maintainer): route 3. Ship AuraFace, int8, on fal's public claim.**
   This supersedes the 2026-09-23 decision for the *shipping* question, not only engineering.

   - **What:** `glintr100.onnx` from `fal/AuraFace-v1` at repo commit `af6d057c`, exported to
     int8 by `scripts/inference/quantise-face-embedder.py` (sha256 `1b7b3c18…89a9a`, 66 MB,
     §12.8b). Mirror that one file only, never the repo (`models.md` §5.4).
   - **Why:** it matches HSFace's grouping on the verified library, with one false card fewer
     (§12.8a–b). And it is the only candidate whose author *affirmatively claims* commercial-use
     training data. HSFace's lineage is known to reach a research-only corpus (§10a), and knowingly
     shipping that is worse than relying on an unverified claim. This is PhotoPrism's posture.
   - ⚠⚠ **The basis is a public statement, not a grant.** fal names no dataset, and Apache-2.0
     disclaims warranty, so if the claim is false the redistribution risk is ours. That is accepted
     knowingly, not overlooked.
   - **The email to fal is DEFERRED, not dropped.** It goes when the product has traction that makes
     the question material. §5.4's three asks are the draft. Their public threads have gone
     unanswered for a year, so silence is the expected reply, and the decision does not wait on it.
   - ⭐ **Wired 2026-09-24 (§12.12).** The calibration was re-checked against AuraFace int8 and
     **kept at 0.44** (§12.8c). An earlier note here said to lower it; the calibration test's own
     margin rule ruled that out. The digest is pinned by the signed catalog.

   ⚖️⚖️ **PUBLISHED BEFORE COUNSEL, 2026-09-24 (maintainer).** The owner chose to release People on
   Android without waiting for counsel, overriding the release gate this entry recorded. Counsel
   review is still owed, now as a review of a live feature: a "no" means pulling the catalog entry
   and the switch. §4 of the brief asks counsel to **confirm or object to** this choice —
   [`in6-face-clustering-counsel-packet.md`](../policy/in6-face-clustering-counsel-packet.md),
   three separable asks, ready to send whenever that is wanted.

   ⚠ **Dropping IN-6 stays coherent and cheap**: IN-D3 already makes it off by default and
   Settings-only, so nothing else regresses. That is the honest fallback if counsel says no.

   ⚖️ Whichever route, the **corpus disclosure row stays open** — exactly where SigLIP already sits,
   and R9 already answers that class with disclosure in the consent copy. So this lands IN-6 in the
   posture the shipped model is already in, rather than a worse one.
2. ⭐⭐ **The threshold and the minimum cluster size — CHOSEN 2026-09-24. `FaceClustering.Measured`
   = cosine 0.44, `minFaces = 2`**, wired in `ProxyModule` in place of `Uncalibrated`.

   **0.44 is the operating point of the one human-verified run**, not a midpoint of a plausible
   range: §12.6's printout is `threshold 0.44`, and that is the run where the maintainer confirmed
   by eye that cluster 0 held 29 photographs of one person with no intruder, and where a cat the
   detector had mistaken for a face fell out on its own.

   The band is measured: the largest cluster holds at 29 from **0.20 → 0.52**; the nearest pair
   between it and any *other* person sits at **0.10–0.20**; the knee in cluster count is
   **0.28–0.44**.

   ⚠⚠ **It sits at the HIGH end of that band deliberately, and the asymmetry is the whole
   argument.** Too high *splits* one person into two clusters — the user names both, the names fold
   (`labelKey` is per file/capability/label), and it costs one extra tap. Too low *merges* two
   people — and the durable artifact is a synced `MediaLabel` saying *this photograph shows Anna*,
   so a merge writes a stranger's face under a friend's name on every device they own, where
   nothing will correct it. Splitting is recoverable by the person who can see the mistake; merging
   is not.

   ⚠ `minFaces = 2` matches Photos' own `DEFAULT_MIN_LABEL_PHOTOS` — one photograph is a search
   result, not a person. Not 3, although that would drop §12.6's two smallest unverified clusters:
   a spurious card is clutter a user ignores, a missing person is not something they can fix.

   ⚠⚠ **One subject, one library, 46 faces — an operating point, not a benchmark**, and §12.6 is
   explicit that clusters 1–4 were never identity-checked. ⚠ The number describes **this
   checkpoint's geometry** and transfers to no other: a different embedder needs its own
   measurement, not a carried-over constant. `FaceCalibrationTest` pins it against the properties
   §12.6 established rather than against itself, so moving it requires either staying inside the
   measured band or bringing a new measurement.
   ⭐ AuraFace is the first such measurement (§12.8a). 0.44 sits inside its stable band, but at the
   band's top edge. ⭐ Re-checked for AuraFace int8 in §12.8c: **0.44 stays**; 0.40 failed the
   margin rule below.

   ⚠ The old note still stands: OpenCV documents cosine ≥ 0.363 for SFace, but that is a **1:1
   verification** threshold rather than a clustering one, and it must not leak in as a `const val`.

   ⭐ **The alignment template, by contrast, is no longer open**: `models.md` §5.6 verifies it three
   independent ways, and it is the ArcFace canonical template shared across that whole family — so
   `alignFace` can be pinned against OpenCV's `alignCrop` as an executable oracle **before** a
   checkpoint is chosen. ⚠⚠ And the rule that comes with it: **do not reason about the landmark
   names.** OpenCV and InsightFace call the same point opposite things, and a parallel review already
   turned that into a filed instruction to swap the order — which would have mirrored every crop.
3. **What withdrawal deletes** (§8).

   ⭐⭐ **And a property worth stating, because it is what makes §7a reversible:** changing the
   detector — or the embedder — is a **rebase, not a migration**. The template store is stamped with
   `ModelProvenance` and refuses foreign vectors, so a swap clears it and a pass re-derives; the cost
   is a re-index, not data. ⚠⚠ **And because labels are per-PHOTO rather than per-face**
   (`(filePath, capability, label)` — no face index), a new detector finding faces in different
   places, or a different number of them, invalidates **no label at all**. The identity pipeline is
   swappable underneath a stable user-facing truth.

   ⚠ Geometry cannot carry identity across a swap: templates from two crop pipelines sit in
   systematically shifted regions, and boxes are position rather than identity. The one technique
   that exists — matching one detector's face to another's by box **overlap within a single
   photograph** — is not needed here, precisely because no per-face state ever syncs.

   ⚠ The cost of the per-photo choice, for completeness: two people in one photograph give two
   labels and **no record of which box was which**. Fine for a grid of a person's photographs; it
   would bite only if a later slice wanted per-face UI.

   ⚠ **F6c is that slice (2026-09-30).** It keeps per-face state **device-local**, so the synced
   truth above is untouched — but that state does need to survive a detector or pipeline swap, so
   the box-overlap technique the previous paragraph set aside is exactly how F6c re-anchors it
   (§6a F6c.1).
4. ⭐⭐ **Suggestion UX — and it is a licensing decision as much as a product one.** Does the device
   propose *"is this also Anna?"*, or silently assign above the threshold?

   Raised by the maintainer, 2026-09-22: *"is it in our use case possible to train the model by the
   user basically? … we only need weights right?"* The distinction the answer turns on is worth
   keeping:

   - **The metric** — a mapping from crop to vector where *same person = near* — must be learned
     from millions of images across tens of thousands of identities. A library holds 10–200
     identities. ⚠ **No amount of user labelling substitutes**: you cannot learn *what makes two
     faces the same person* from one family's photographs. The pretrained embedder is unavoidable.
   - **The prototypes** — *"Anna is the centroid of these twelve vectors"* — are arithmetic in a
     space that already exists, and are free. ⭐ This is exactly IN-7's *"user-taught prototypes
     (free, given IN-3)"*, and it is what naming a cluster already does here.

   ⭐ **The consequence for §10.1:** if the user confirms and corrects, the embedder only has to be
   good enough to **propose**, not good enough to **decide** — which materially lowers the accuracy
   bar and makes the MIT synthetic route viable where it looked marginal. HyperFace (LFW 98.50) and
   HSFace (99.25) sit ~0.5–1.5 points behind AuraFace and `buffalo_l`; across a grid that asks and
   takes a tap, that gap may simply not show. Across one that auto-assigns, it would.

   ⚠⚠ So **choose the UX before choosing the model**, not after. A suggestion-shaped People section
   may be worth more than a licence negotiation.

---

## 10a. ⭐⭐ What six comparable apps did — surveyed 2026-09-23

Asked because we cannot be the first: *"do we know how other apps made this decision?"* The survey
covered Ente, Immich, PhotoPrism, Nextcloud Recognize, digiKam and Frigate.

⚠⚠ **The headline is that nobody solved it.** One project obtained a written grant that says it does
not extend to anyone else; one built a licence gate in code and excludes its own commercial editions
from the gated weights; the other four ship weights whose corpus is undisclosed or non-commercial,
without publicly discussing it.

| Project | Embedder it ships | Its position |
|---|---|---|
| **Ente** (E2EE, on-device, commercial — our posture) | MobileFaceNet, 192-dim ONNX | ⚠⚠ Publishes **no** provenance. The file traces to a **zip attached to a 2019 GitHub issue comment**, derived from an Apache-2.0 repo whose documented training path is MS-Celeb-1M-derived. The checkpoint's own corpus is stated nowhere |
| **Immich** | InsightFace `buffalo_l` | ⭐ Asked and got written permission (email, dated, committed to the repo) — ⚠⚠ which says outright it *"does not extend to the redistribution or commercial use of their models by third parties"*. The method worked; the grant is not ours |
| **PhotoPrism** | SFace default, AuraFace + ArcFace opt-in | ⭐⭐ **The only one that draws the commercial line in code**: a per-artefact licence constant, sha256 pinning, refusal at **use** and not only at download, and `LicenseEligibleEditions = ["ce", "plus"]` — its own paid editions are refused the gated weights |
| **Nextcloud Recognize** | dlib ResNet-34 via face-api.js | Public-domain weights, ⚠ trained partly on VGG Face (**CC BY-NC**). No discussion found |
| **digiKam** | SFace — *the same file PhotoPrism ships* | No discussion found |
| **Frigate** | FaceNet / ArcFace from a personal GitHub release | ⚠ No README, no model card, no provenance anywhere |

⚠⚠⚠ **Route 2 is worse than §10 Q1 recorded, and this is the survey's load-bearing finding.**
Vec2Face's generator trains on **WebFace4M** — `--train_source ./lmdb_dataset/WebFace4M/WebFace4M.lmdb`,
**verified directly in the upstream README**, not taken from the survey. So HSFace descends from
WebFace260M, academic-research-only and non-redistributable: the exact defect route 2 existed to
escape, one indirection further away. ⚠ The FFHQ question this document spent its argument on is
**moot** for a separate reason — FFHQ's NVIDIA grant is **CC BY-NC-SA** and about half the underlying
photographs are **CC BY-NC**, so the *non-commercial* term blocks a commercial seller before the
facial-recognition sentence is ever reached.

⚠⚠ **Synthetic is not the escape hatch it looked like.** No product, vendor or project ships a
synthetic-trained recogniser. DigiFace-1M — the one corpus built without real photographs — carries a
licence drafted precisely against this move: *"Artificial intelligence models trained on Data … are
Results"*, and Results may not ship commercially. HyperFace (MIT) optimises in a pretrained
recogniser's embedding space, so a real-corpus model is still in the loop.

⭐ **The re-ranking this forces: route 3 rises above route 2.** AuraFace is the only Apache-2.0 face
model whose author *affirmatively claims* a commercially-licensed corpus — built, in fal's words,
because *"the original ArcFace … has non-commercial restrictions due to its training data"* — and
**PhotoPrism already ships it**. ⚠ Its dataset is still unnamed, so it remains an assertion we trust
rather than a chain we check, and §10 Q1's warning about that repository's other four files stands.

⭐ **Two patterns worth copying whatever we choose:** Immich's (ask the author, get it in writing,
commit the date), and PhotoPrism's `internal/ai/face/` licence gate — which is also the answer to
"can a power user opt into a better model without contaminating the shipped product".

⚠ Our own route 1 is **`opencv_zoo#313`**, and the survey found *two other prospective commercial
users* asking the same question on that same issue, with **zero maintainer replies**. That reframes
it: the silence is not about us, and a reply is not owed.

---

## 11. iOS, as researched (2026-09-21)

Findings from a read of the tree, for F5. ⚠ **Nothing was compiled** — these are source-level claims.

- ⭐ **The route is Kotlin/Native Objective-C interop from `iosMain`**, not a Swift shim. The rule is
  already written down for a different framework: *"CryptoKit is Swift-only and not reachable from
  Kotlin/Native's Objective-C interop"* — Vision's `VN*` API **is** Objective-C, so it is reachable.
  Precedents in-tree: LocalAuthentication, Security, Photos, UIKit, PDFKit and AVFoundation are all
  driven from K/N today. ⚠ A Swift shim additionally cannot work here: `InferenceBackend`'s members
  are `suspend`, and a Kotlin interface with a suspend member cannot be conformed to from Swift.
- ⚠ **`macos-helpers/.../Vision.kt` is NOT a precedent** — it is JVM FFM (Panama) for *desktop*, and
  unreachable from K/N. Worth stating because it is the first thing a reader will find.
- ⚠⚠ **No ONNX Runtime on iOS, and it is absent rather than stubbed**: no Apple ORT artifact in the
  version catalog, no iOS source set in `:inference-service-proxy`, and the DI says so outright —
  *"iOS and web contribute nothing and therefore keep exactly today's behaviour"*. There **is** one
  cinterop precedent in the tree (libsodium, built per Apple target by a script and `.def`-ed), so
  the route exists; it is a slice, not a first-of-its-kind.
- ⚠⚠ **The registry cannot express the split as it stands.** `backendFor(capability)` is first-wins,
  one backend per capability, and `FACE_CLUSTERING` is a single consent switch covering detection
  *and* identity. A Vision backend claiming it would win the slot and then be unable to produce a
  template — *"a switch that does nothing is a claim the product cannot keep"*. The answer is a
  separate `FaceDetector` seam contributed per platform, resolved like `named("inferenceBackends")`;
  `MediaIndexer` already resolves *"a backend PER HALF, never the backend"* for the same reason.
  ⚠ Not needed for F1–F3, where detector and embedder are one artifact — decide it with F5.
- ⚠ **Three things would stop an iOS pass even with a backend**: `IndexingConditions` falls back to
  `Never` there (so nothing indexes, not even *Index now*), `PlatformScheduler` is a no-op, and the
  realm-reset hook does not reach route-1's own `ControlSurface.wipeLocalObjectStore` — which for a
  **biometric** store keyed by file path is worse than the vector case §34.6 describes.
- ⚠ **There is no `photos-ios`**, and a tenth Xcode project is also a provisioning problem (the free
  personal team caps at three apps per device). ⚠ But People need not live in Photos on iOS — Drive
  has a shell already.
- ⚠ **Verification is thin**: no `-proxy` module can host an iOS unit test (they are disabled
  repo-wide), so the alignment maths belongs in `commonMain` where the JVM gate tests it, and a
  Vision wrapper's own test belongs in `:tenshu-sdk-ios`, which *is* in `iosTestCheck`.

---

## 12. F2m — measuring the chain before choosing the model (2026-09-22)

F2 is blocked on a licence decision (§10). The measurement it should be decided *against* is not,
and this is that measurement: the whole chain — MediaPipe detection → the shared `alignFace` →
HSFace embedding → `clusterFaces` — run over a real camera roll on real hardware.

⚠⚠ **The reason this is a slice and not a footnote:** every number in `models.md` §5 is an LFW-family
figure, measured on frontal, cropped, celebrity photographs. A camera roll is none of those things,
and the quantity F3 actually needs — the similarity threshold — cannot be derived from a model card
at all. It depends on *this* detector's framing and *this* alignment, which is precisely what §3.3
says must be shared and therefore measured once rather than per platform.

### 12.1 What runs

`inference-service-proxy/src/androidDeviceTest/.../FacePipelineMeasurementTest.kt`, built on IN-3m's
harness (`inference-service.md` §15) and inheriting its rules: ⚠⚠ **not a gate, ever** — it skips
with a printed reason when a model or the corpus is absent, it is out of `android-smoke.sh`, and it
is unreachable from the JVM gate. ⚠⚠ **Not via `connectedAndroidTest`**, which uninstalls the test
APK and deletes the corpus with it (§15.12.6); install once, drive with `am instrument`.

⚠ MediaPipe enters as `androidDeviceTest` scope **only**. It is §7a's choice for the shipped detector,
but a 12 MB AAR added to the product graph ahead of the decision is one nobody re-examines.

### 12.2 ⭐⭐ It scores itself against public ground truth

A cluster count cannot be checked by reading it: *"12 clusters"* is identical whether the grouping is
right or nonsense, and a mirrored alignment or a wrongly-inverted transform still produces tidy
clusters of the wrong faces. So the harness recognises an `id<NN>_*.jpg` corpus and reports **purity
and how many identities were split**, against `models.md` §5.11's public synthetic set — 8 known
identities, published, no private photograph involved.

⭐ Run that first and the real library second. It separates *"the pipeline is wrong"* from *"this
model is not good enough on real photographs"*, which are the two findings most easily confused, and
it means the correctness half never requires anyone to look at someone's family photographs.

⚠⚠ The threshold is **swept, not chosen** — §4's rule, applied one layer down. The harness prints a
row per candidate and picks none.

### 12.3 ⚠⚠ First result — the chain runs on Android, and this proved LESS than it looked (2026-09-22)

Run against the 88-image public synthetic set, on an **emulator** (see below), with the int8 HSFace
export and MediaPipe's `face_landmarker.task`:

```
faces found: 88 across 88 photographs      photographs with no face: 0, undecodable: 0
  0.20–0.44: purity 1.000, 8 clusters for 8 identities, 0 identities split
  0.52–0.60: purity 0.989, 8 clusters for 8 identities, 0 identities split
```

Every cluster held exactly 11 faces — the 10 generated variants **plus the reference**, which is a
real, differently-framed photograph rather than a variant. ⭐ That pair is the informative one: it is
the only case where the alignment has actual work to do, and it landed in the right cluster 8 times
out of 8. Together with the montages (upright, eyes at a consistent height across every crop) this
exercises MediaPipe's landmark indices, `alignFace`, `FaceCrop.unmap`, `Profile.SIGLIP`, ORT int8 and
`clusterFaces` in one run.

⚠⚠⚠ **This was written as "clears the chain in one run". It did not, and the overclaim mattered.**
Every image here is **336×336 — square** — and the defect §12.5 later found lives entirely in the
*aspect ratio*. A square pins it, so this suite was structurally incapable of failing over it, and
purity 1.000 was then cited as evidence the pipeline was sound while a real-library failure was
attributed to the model. ⭐ What it genuinely establishes: the landmark indices are not transposed,
the ONNX graph loads and runs under ORT on Android, and the arithmetic has no NaNs. What it cannot
establish is anything that varies with frame shape.

⚠⚠ **This says nothing whatever about quality.** Same-identity pairs here are generated variants of
one vector, the corpus has 8 identities, and the threshold sweep is *flat* — every candidate returns
the same 8 clusters, because the separation is +0.80 against an impostor tail of +0.007. **A flat
sweep is the signature of a task that is too easy to choose a threshold from.** The number F3 needs
comes from a real camera roll and nowhere else.

⚠ It ran on an emulator deliberately: §12.1's harness suppresses every duration there rather than
refusing to run, because purity against known ground truth is not a timing. **No speed figure exists
for this pipeline yet.**

### 12.4 ⭐⭐ First real camera roll — 400 photographs on a OnePlus CPH2581 (2026-09-22)

Same models, same phone, 400 full-resolution frames (1.8 GB) copied from `DCIM/Camera` **on the
device**. Ground truth first on the same handset to prove nothing was phone-specific: purity 1.000,
8/8, identical to the emulator.

```
minConfidence 0.5:  35 faces across 32 photographs   (368 with none)
minConfidence 0.2:  46 faces across 41 photographs   (359 with none)
detect median 25-48 ms   embed median ~50 ms/face
threshold sweep at 46 faces, minFaces=1:
  0.20:  3 clusters,  0 singletons, largest 25
  0.28:  5 clusters,  1 singleton,  largest 22
  0.44: 12 clusters,  6 singletons, largest 19
  0.60: 15 clusters, 10 singletons, largest 19
```

⚠⚠ **These are the PRE-FIX numbers** — the alignment bug in §12.5 was still present, so treat the
cluster shapes as historical. The corrected run gives one cluster of 29 stable across 0.20–0.52. The
recall findings below are unaffected: they concern detection, which runs before alignment.

⭐ **The sweep is no longer flat** — unlike §12.3's synthetic set, this one has a knee, which is the
whole reason the measurement had to happen on a real library. Between 0.20 and 0.28 the largest
cluster drops 25 → 22 while singletons barely move, which is the signature of an over-merge being
broken up; past 0.52 singletons grow without the grouped count improving.

⚠⚠ **The open question is RECALL, not the embedder.** ~90% of photographs yielded no face at all, and
two variables were tested one at a time rather than assumed:

| Change | Faces | Reading |
|---|---|---|
| decode cap 2048 → **4096** | 35 → **35** | ⭐ resolution is **not** a limiter; §12.5's memory cap is free |
| confidence 0.5 → **0.2** | 35 → **46** | the detector's own threshold costs ~24% of recall |

⚠ `face_landmarker.task` bundles BlazeFace **short-range**, tuned for roughly arm's length, so faces
across a room are expected misses. Whether the remaining 359 are genuinely photographs of things
rather than people is the one question no number here settles — it needs someone to look at their own
library, which is what §12.6's montages are for.

### 12.5 ⚠⚠⚠ The alignment bug that framed the model (2026-09-22)

**A negative result was published against HSFace and retracted within the hour. The retraction is the
more useful half, so both stay here.**

The maintainer identified the two largest clusters — 19 faces and 11 — as the same person. Measured:

```
cluster 0 (19) vs cluster 1 (11):  centroid -0.014,  nearest member pair 0.217
```

Against this model's own calibration, where different *people* scored **+0.008**. So the conclusion
drawn was that HSFace places one person as far from himself as from a stranger — a synthetic-to-real
domain gap, consistent with a checkpoint trained entirely on generated faces, and consistent with the
first run's own real-reference→variant figure of +0.449 (min +0.053). ⚠ **That reasoning was sound,
internally consistent, supported by a second independent number, and wrong.**

⭐⭐ **The cause: `alignFace` was being fed MediaPipe's NORMALISED landmarks.** A similarity transform
has one scale and is only meaningful in an isotropic space; normalised coordinates divide x by width
and y by height *separately*, so on a 4:3 frame the single fitted scale silently becomes a `w/h`
stretch — and portrait and landscape frames stretch **opposite ways**. Feeding the same faces in
**pixels**:

| | before | after |
|---|---|---|
| the maintainer's faces | 19 + 11, centroid **-0.014** | **one cluster of 29** |
| that cluster across 0.20–0.52 | — | ⭐ **stable at 29** — a coherent identity, not a threshold artefact |
| every other cluster vs it | — | centroid -0.03…+0.12 — i.e. *different people*, correctly |

⚠⚠ **Three real hypotheses were tested and eliminated before the model was blamed, and eliminating
them is what made the wrong answer feel earned**: off-frame clipping (2.4% vs 1.2%, clusters
geometrically alike), decode resolution (2048 vs 4096 → identical 35 faces), and mirroring (a face
vs its own mirror scores **0.977**; the author's sum-of-flips protocol adds +0.006). All three were
worth testing. None of them was the bug, and having ruled out three plausible causes made the fourth
conclusion feel like the last one standing rather than the next one to check.

#### What actually gave it away

⭐⭐ **The cat.** One member of the 11-cluster was a photograph of a cat — detected as a face at
`minConfidence 0.2`, then grouped with ten human selfies. *"One person's photographs are far apart"*
is a believable model limitation; *"a cat is closer to you than you are"* is not a limitation of
anything, it is incoherence, and incoherence means the inputs are wrong rather than the model weak.
⚠ The maintainer reported it as an aside — *"one of the pictures in n11 is a cat xD"* — after the
negative result had already been written up and committed.

#### The standing lessons

- ⚠⚠⚠ **A square fixture cannot see an aspect-ratio bug.** §12.3's ground-truth set is 336×336, so it
  scored **purity 1.000** straight over the top of this. It was the strongest evidence that the chain
  was correct, and on this defect it was structurally incapable of failing. A fixture that holds
  constant the very thing that varies in production certifies nothing about it.
- ⚠⚠ **An absurd member is worth more than a plausible aggregate.** The cluster sizes, the centroid
  table and the purity score were all consistent with the wrong conclusion. One member that could not
  possibly belong falsified it instantly. Prefer the check that can be *absurd* over the one that can
  only be *disappointing*.
- ⚠ **Eliminating hypotheses is not the same as finding the cause**, and it feels like it. Each
  elimination raised confidence in what remained, when what remained was still only a guess.

### 12.6 ⭐⭐⭐ CONFIRMED — HSFace clears the bar on a real camera roll (2026-09-23)

The measurement F2m exists to produce, with the alignment defect of §12.5 fixed and the maintainer
confirming the grouping **by eye** rather than by arithmetic:

```
400 photographs, 46 faces, minConfidence 0.2, threshold 0.44
  cluster 0: 29 faces  — ALL the same person, confirmed by the subject
  clusters 1-4: 3, 3, 2, 2
  largest cluster stable at 29 across 0.20 → 0.52
```

⭐⭐ **Three things make this a pass rather than a hopeful number:**

1. **Purity, checked by the only person who can.** Cluster 0 is 29 photographs of one person with
   nobody else mixed in. One intruder would matter more than the count, and there is none.
2. **The false positive separated itself.** A photograph of a **cat** — detected as a face at
   `minConfidence 0.2` — was grouped *with* the human selfies before the fix, and falls out on its
   own afterwards. That is the single most informative row in the run: the embedder is not merely
   grouping "things the detector emitted", it is discriminating.
3. **The cluster does not fragment as the threshold tightens.** 29 from 0.20 through 0.52 is what a
   coherent identity looks like; a lucky grouping breaks up. And the four other clusters sit at
   **0.10–0.20 nearest-pair** from it — comfortably different-people territory, not borderline.

⚠⚠ **What this does NOT establish, stated so the result is not over-read:**

- **One subject, one library, 46 faces.** It says HSFace can carry *this* person across *these*
  photographs. It is not a benchmark.
- ⚠ **Clusters 1–4 were not verified as four distinct people** rather than one person split. The
  over-splitting question is open at the small-cluster end.
- ⚠⚠ **Recall** — ~10% of photographs yielded any face — **is still open**, and §12.7 explains what
  the detector comparison can and cannot settle: both sides of it are short-range, so they share a
  blind spot, and the detections that differ between them were never classified.
  `face_landmarker.task` bundles BlazeFace **short-range**, tuned for roughly arm's length, so faces
  across a room are expected misses. Whether the other 359 photographs are pictures of *things* or
  of people the detector could not see is still unknown, and it decides whether a People grid looks
  populated or empty.

⭐ **What it unblocks.** §10's route 2 (Vec2Face/HSFace, MIT, synthetic) now has *quality* evidence
behind it where before it had only a licence argument, and the bar it had to clear was already
lowered by §7's suggestion-mode decision.

⚠⚠ **It removes one gate, not both — and the phase map said otherwise until a review caught it.**
HSFace's training lineage is a ⚖️ counsel question that no measurement can answer. ⚠ When this was
written, that question was about FFHQ. §10a later found that the generator trains on **WebFace4M**,
which is the binding restriction. And **the calibration is still open**: §10 Q2 lists the threshold and `minFaces` as
unresolved, §12 sweeps them deliberately rather than choosing, the smaller clusters here were never
identity-checked, and `FaceClustering.Uncalibrated` emits nothing — so a phase map that called F2
"licence only" would let F3 ship an empty People grid. ⭐ What this run *does* give calibration is a
starting range rather than a value: the largest cluster is stable from 0.20 to 0.52, and the knee in
cluster count sits between 0.28 and 0.44.

### 12.7 ⚠⚠ Recall: what the detector comparison can and cannot settle (2026-09-23)

§12.4 and §12.6 both left the same thing open: ~90% of photographs yield no face, and nobody knew
whether that was a library of landscapes or a detector that cannot see across a room.
`face_landmarker.task` bundles BlazeFace **short-range**, tuned for roughly arm's length, so the
obvious suspicion was the model.

**This run compares the two, and settles less than it first appeared to.** A standalone
`FaceDetector` over the same 400 photographs — same bitmaps, same EXIF handling, same decode cap, so
the only difference is the model path:

| `minConfidence` | landmarker | standalone detector |
|---|---|---|
| 0.2 | 46 faces / 41 photographs | **261 / 170** |
| 0.5 | 35 / 32 | 60 / 57 |
| 0.7 | 34 / 31 | **36 / 33** |

⭐ **They converge as the threshold rises**, agreeing within two faces at 0.7.

⚠⚠⚠ **And that is ALL the table establishes — an earlier version drew a conclusion from it that the
same section had already undercut.** It said "the landmarker is not artificially conservative", but
at the thresholds anyone would actually operate at the standalone detector returns **261 against 46**
(0.2) and **60 against 35** (0.5). They agree only at 0.7, which is far tighter than a clustering
pipeline would run. Since the extras are unclassified (below), nothing here decides whether the
landmarker is filtering noise or losing faces at 0.2 and 0.5 — and the conclusion rested entirely on
the classification the next paragraph withdraws. Retracting a premise and keeping the conclusion it
carried is how the same error survives its own correction.

⚠⚠⚠ **What the extra detections ARE was never measured.** The harness sums `detections().size` and nothing else: it saves no
boxes, matches nothing against the faces that were inspected, and has no ground truth. So "the 261
at 0.2 are overwhelmingly false positives" was a **classification nobody made**. Convergence at
higher confidence is *consistent* with the low-confidence extras being poor detections — a bare
detector will find a face in wallpaper — but consistent is not measured, and this section already
concedes the same point about the 25 extras at 0.5.

⚠ **So the two-stage detect-then-landmark path stays OPEN, and so does the landmarker's own
behaviour at operating thresholds.** Both were settled here on the strength of that unmade
classification. ⭐ **§12.8 performs it** — the crops were saved and inspected, and the answer is
"about half", which decides neither question on its own.

⭐ **What the run does give, and it is not nothing:** the pipeline end to end on real photographs,
an identity that holds together across a wide threshold range (§12.6), and a detector-side question
narrowed to one tractable experiment — the 25 extras at 0.5.

⚠⚠⚠ **What this does NOT establish, and an earlier version of this section wrongly claimed it did:
that the ~90% is genuine.** Both sides of that table are **BlazeFace short-range** — the landmarker's
bundled detector and the standalone one are the same model. Their agreement shows two short-range
paths agreeing with each other; it says nothing whatever about faces that **both** miss, which is
precisely the distant-face case the concern was about. The comparison shares the blind spot it was
brought in to rule out. ⚠ The section already said, three paragraphs down, that full range remains
untested — so the conclusion contradicted its own evidence, and a review caught it rather than the
author.

⚠⚠ **What stays untested, and it is the one that looked most promising.** MediaPipe's **full-range**
detector — the one actually built for distant faces — **cannot be tested through the Tasks API at
all**. The models under `mediapipe-assets/face_detection_full_range*.tflite` are built for the legacy
solution graph and carry a different anchor count, so `FaceDetector` rejects them with
`RET_CHECK ... (raw_box_tensor->shape().dims[1])==(num_boxes_)`. Only models published under
`mediapipe-models/face_detector/` are Tasks models, and Google publishes **short-range only** there.
So "full range would find more distant faces" remains a plausible, untested claim, and testing it
would mean adopting the legacy solution rather than Tasks.

⚠ The most tractable zone is `minConfidence` **0.5**, where the detector finds 25 more than the
landmarker — few enough to inspect by hand, and the cheapest way to turn "unclassified extras" into
an answer for both open questions at once.

⚠⚠ **So recall stays an open F2 gate**, and so does the two-stage question. Closing either needs
something this run did not have: the extra detections **inspected** (save their crops — the harness
already knows how, §12.8), **ground truth** over the 359, or an **independent** detector without the
short-range blind spot. The latter means leaving
the Tasks API for the legacy solution, which is why it has not happened yet, not because it is
uninteresting.

### 12.8 ⭐⭐⭐ The 25 extras, inspected — and the second stage, actually run (2026-09-23)

§12.7 could not say what the extra detections were, and said so. This performs the experiment.

The harness writes a crop for every detection the comparison model made that the landmarker did not
report, matched by intersection-over-smaller-area so a detector box and a landmark-mesh box for the
same face count as one. At `minConfidence` 0.5 that is exactly **25** — 60 detections minus the 35
faces the landmarker found, every one of which matched, which is the check that the matching works
at all.

⭐⭐ **And each crop is then put through the SECOND STAGE**: the landmarker is run on it, exactly as a
detect-then-landmark pipeline would, and the verdict is written into the filename. That distinction
is the whole finding, and an earlier version of this section did not make it.

| | |
|---|---|
| unmatched detections | 25 |
| the landmarker accepts when run on the crop | **7** |
| of those 7, real faces | **7 — all of them** |
| real faces among the 18 it rejects | **2** |
| real faces in the 25 altogether | 9 |

⭐⭐⭐ **So the crop stage admits no noise on this sample, and recovers most of what the
single-stage path misses.** Precision **7/7**; it recovers **7 of the 9** faces the landmarker missed
and drops 2 — which the single stage was missing anyway, so those are not a regression.

⚠⚠ **And there are TWO designs here, which an earlier version conflated.** Running the crop stage
only on the *unmatched* boxes measures what a **hybrid** adds on top of today's full-image pass. A
**replacement** pipeline runs it on every detection — including the 35 the full-image pass already
found — and cropping changes scale and context, so keeping those is not free. Both were then
measured:

| | faces found | of 44 detectable | landmarker time |
|---|---|---|---|
| single stage (today) | 35 | 80% | 400 full-image calls |
| **replacement** — crop stage on all 60 | 41 | 93% | **~5.0 s** (60 crop calls) |
| **hybrid** — full image, plus crop recovery of the unmatched | **42** | **95%** | ~14 s (400 full-image + 25 crop) |

⚠ Times are one run each and move a few percent between runs (4 972–4 992 ms and 14 088–14 664 ms
observed); the ~3× ratio is the durable part, not the digits.

⭐⭐ **The 41 and 42 survived a second correction too.** They were first counted as one Boolean per
detection, which is not a face count: a crowded crop returns several meshes and was reduced to one,
and two overlapping detections could each be credited with the same face. The harness now pools every
mesh per photograph, maps it into source pixels and de-duplicates. Re-run, the totals are
**identical** — on this library most photographs hold zero or one face, so neither effect had
anything to bite on. ⚠ That is a property of *this* corpus: a library of group photographs would
exercise both, and the de-duplication is what makes the figure trustworthy there.

⭐ **These figures survived an earlier correction that could have inflated them.** They were first produced
when the harness decided a crop had been accepted with `faceLandmarks().isNotEmpty()` — true
whenever the landmarker finds *any* face in the crop, including a **neighbour** pulled in by the 25%
margin, and able to credit two overlapping crops with the same neighbour twice. The harness now maps
each mesh back onto the photograph and requires it to overlap the detection it came from. Re-run,
the counts are **identical**: the defect was real and did not happen to occur in this sample.

⭐⭐ **And the cost question is answered, against the intuition that framed it.** A replacement runs
the landmarker **~3× less** in wall clock than the hybrid — 4 992 ms against 14 664 ms — for one
fewer face out of 42.

⚠⚠ **The per-call arithmetic is the interesting part, and it is counter-intuitive**: ~83 ms per crop
call against ~31 ms per full-image call. A *smaller* image is the slower call, because the cost is
not the pixels — it is the landmark stage, which only runs when a face is present. Nine in ten
photographs have none, so the full-image pass exits early on most of them, while a crop always
carries a candidate. ⚠ That also means this ratio is a property of **how few faces this library
has** and would move on a library full of people.

⭐ The crop stage keeps **34 of the 35** already-found faces, so a replacement costs one face.

⚠⚠⚠ **And on cost the obvious comparison is backwards.** An earlier version of this section called
the hybrid "cheaper" because it makes 25 crop calls against the replacement's 60 — which counts the
crop stage and forgets that the hybrid *also keeps the full-image pass over every photograph*, while
a replacement drops it entirely:

| | landmarker calls | faces |
|---|---|---|
| hybrid | ~400 full-image **+ 25** crop = **~425** | 42 |
| replacement | **60** crop, no full-image pass | 41 |

Both pay the detector on all 400 either way. So the replacement makes roughly **seven times fewer**
landmarker calls for one fewer face — the opposite of what the crop-call comparison suggested.

⚠ **Call count is not wall clock**, and the measured ratio is **~3×**, not the 7× the call counts
suggested — for the reason above. ⭐ On this library the replacement is the better trade: a third of
the landmarker time for 2.4% fewer faces. ⚠ One library, one threshold; a library with people in
most photographs would narrow the gap, because the full-image pass would stop exiting early.

⚠⚠⚠ **This REVERSES the argument the previous version of this section made**, and the reversal is
the lesson. It said a two-stage pipeline would import "roughly 13 pieces of noise", rejecting the
path on that basis — a claim that assumed the second stage fails and never ran it. Running it turns
18 of the 25 away before alignment. ⚠ The 18 are rejected at the *landmark* stage, so they are never
aligned, never embedded, never clustered; the cost of a false detection is one landmarker call.

⚠ **Sample size.** Twenty-five unmatched detections, nine of them faces, one library, one threshold.
Enough to retract the rejection and to make the crop stage worth building and measuring; not enough
to call 95% a number that generalises.

⭐ **Crop-stage failures are counted separately and there were none.** A thrown `detect` used to be
recorded as "no mesh", which would have let an inference or resource failure masquerade as the
landmarker declining a face — the exact distinction the experiment turns on. ⚠ An earlier estimate in this session put the real-face share at
"about half" before the crops were labelled by verdict and counted carefully; it is 9/25.

⚠⚠ **Still untouched: faces BOTH models miss.** Every number here derives from detections the
short-range model made, so the ~90% no-face rate is narrowed, not explained. Ground truth over the
359 is what closes that, and nothing here substitutes for it.

### 12.8a ⭐⭐ AuraFace on the same library — identical grouping, one false card fewer (2026-09-24)

Route 3's model, measured because §10a ranked it above route 2. The file is `fal/AuraFace-v1`
`glintr100.onnx` at repo commit `af6d057c`, fp32, 260,694,151 bytes, sha256
`a7933ea5330113b01c9b60351d8f4c33003f145d8470ac5f0e52ee2effe25c60` (matches the Hub's published
digest). Its graph has no built-in normalisation (the first op is `Conv`), so the harness's ArcFace
profile (RGB, (x−127.5)/127.5, 112×112) applies unchanged. Same phone, the same 400 photographs, and
the same harness build. HSFace was re-run beside it rather than compared with an older report.

⭐ The harness now writes a sorted `cluster-NN-nK.txt` next to each montage. Matching counts across
two models do not prove matching members, and the listings do.

| | HSFace int8 | AuraFace fp32 |
|---|---|---|
| minConfidence 0.5 (35 faces), clusters at 0.44 | 29 / 3 / 2 | 29 / 3 / 2, **same members** |
| minConfidence 0.2 (46 faces), clusters at 0.44 | 29 / 3 / 3 / 2 / 2 | 29 / 3 / 3 / 2, **same members** |
| largest cluster holds at 29 | 0.20 → 0.52 | 0.20 → 0.44, **26 at 0.52** |
| embed, median per face | 51 ms | **203 ms** |
| model size | 44 MB | **260 MB** |

⭐⭐ **Every cluster the two models share has identical membership**, including the 29-face cluster
the subject verified by eye in §12.6. So AuraFace inherits that verification exactly and does not
need it redone.

⭐ **The one difference favours AuraFace.** HSFace's extra 2-face cluster at confidence 0.2 is two
photographs of **bear-faced pastries**, taken 22 s apart. HSFace grouped them as a person. AuraFace
leaves them as singletons, which `minFaces = 2` drops. That is one false People card fewer.

⚠⚠ **The calibration still holds, but with less headroom.** 0.44 (§10 Q2) is inside AuraFace's stable
band, but it is now the band's top edge: AuraFace's largest cluster starts splitting before 0.52,
where HSFace's held. On the merge side, the closest pair of distinct clusters sits at nearest-pair
≤ 0.30 on both models. For AuraFace the usable band is therefore roughly 0.30–0.44, not HSFace's
0.30–0.52. ⚠ Per §10 Q2 a new model is owed its own number, and a swap should re-examine 0.44 rather
than inherit it. ⚠ This paragraph first said the move, if any, is downward. §12.8c measured it:
0.40 reproduces fp32's clusters but fails the 2× margin over the nearest different person, so 0.44
stays.

⚠ **The cost is real in fp32**: ~6× larger and ~4× slower per face. ⭐ §12.8b closes most of it with
an int8 export.

⚠ Same limits as §12.6: one subject, one library, and clusters 1–3 still not identity-checked.
This shows the two models agree here. It does not show that either generalises.

### 12.8b ⭐⭐ AuraFace int8 — 66 MB, 60 ms, and one borderline face moves (2026-09-24)

Exported with `scripts/inference/quantise-face-embedder.py`, which is §5.11's recipe committed this
time. It uses static QDQ, per-channel, opset 13, calibrated on Vec2Face's `random-id.png` and scored
on `perturbation.png`, which the quantiser never saw. The script reproduces the measured file byte for
byte: sha256 `1b7b3c18b113b1c2cbc99c537dd9b83d65e2bc35f37e948b84131ae092389a9a`, 66,033,423 bytes.
No `ConvInteger` is emitted.

**Host, on the held-out grid** (8 identities × 10 synthetic variants):

| | same identity | different identity | gap |
|---|---|---|---|
| fp32 | +0.793 (min +0.588) | +0.095 (max +0.276) | +0.698 |
| int8 | +0.788 (min +0.585) | +0.095 (max +0.286) | +0.693 |

The fp32↔int8 per-face cosine is **0.994 mean, 0.991 min**. HSFace's int8 managed 0.981.

**Device** (same phone, library and harness as §12.8a):

| | fp32 | int8 |
|---|---|---|
| model size | 260 MB | **66 MB** |
| embed, median per face | 203 ms | **58–65 ms** (HSFace int8: 51) |
| conf 0.5, clusters at 0.44 | 29 / 3 / 2 | 29 / 3 / 2, **same members** |
| conf 0.2, clusters at 0.44 | 29 / 3 / 3 / 2 | 29 / 3 / 2 / 2, one face moved |
| largest cluster holds at 29 | 0.20 → 0.44 | 0.20 → 0.44, 26 at 0.52 |

⭐ **The verified 29-face cluster is identical in all runs.**

⚠ **The one difference is a borderline split, the recoverable direction.** fp32's 3-face cluster
held two faces from `IMG20260110143831` and one from `IMG20260110143847`, taken 16 s apart and, by
eye, the same woman. int8 keeps the pair and leaves the third face as a singleton, so at 0.44 that
face sits just under the threshold. Per §10 Q2's asymmetry, a split costs one extra tap when naming,
and a merge would have been the failure that matters. ⚠ It is also evidence for the calibration note
in §12.8a: on AuraFace, 0.44 is the top of the band, and int8 moves the edge slightly.

⚠⚠ **A pipeline finding, independent of the embedder:** `IMG20260110143831` yields **the same face
twice**, two near-identical crops of one face in one photograph. Every model, HSFace included, then
clusters the pair together, which is why HSFace reported "3 faces over 2 photographs". A label is
per photograph (`labelKey` is file/capability/label), so naming still writes one row, but the
duplicate inflates the cluster's face count and is a de-duplication gap in the detection stage.

⭐ **So int8 AuraFace is the shipping candidate on route 3**, at 1.5× HSFace int8's size and about
its speed. ⚠ It is unmeasured on the desktop JVM, for the same reason §5.11 gives for HSFace.

### 12.8c ⭐⭐ The calibration re-check for AuraFace int8 — 0.44 stays (2026-09-24)

§10 Q2 says a new embedder is owed its own number. The harness gained a 0.40 step and a
`-e montageThreshold` override, so candidates could be compared by **membership**, not by count.
Same phone and library. AuraFace int8 at detector confidence 0.5 and 0.2:

| | at 0.36 | at 0.40 | at 0.44 |
|---|---|---|---|
| membership vs fp32 at 0.44 (the verified groups) | identical | identical | one borderline face split off |
| verified 29-face cluster | intact | intact | intact |

The other numbers the choice turns on:
- **Stability band:** the verified cluster holds at 29 from 0.20 through 0.44 and has fragmented by
  0.52 (26). HSFace held to 0.52.
- **Nearest different person:** the closest pair between the verified cluster and any other cluster
  is **0.215** (conf 0.5). The closest pair between any two clusters is 0.292, between two
  *unverified* clusters that may be one person.

⭐⭐ **Kept at 0.44.** 0.40 reproduces fp32's groups exactly, but `FaceCalibrationTest` demands a
threshold at least twice the nearest different-person cosine (≥ 0.43), and 0.40 fails that. 0.44
passes, and its one cost is a borderline face split off an unverified cluster — the recoverable
direction, per §10 Q2's asymmetry. ⚠ This reverses an earlier note in this document (and in this
session's summary) that said to lower it; the margin rule is the argument that decides it.

`FaceCalibrationTest` now pins AuraFace's evidence: the band upper bound is 0.44 (not HSFace's
0.52), and the nearest different person is 0.215.

### 12.9 What it writes, and where it stays

Face crops, as montages, into the app-specific external directory. ⚠⚠ That is the point rather than a
convenience: the counts above cannot be verified, the montages can. They are of the operator's own
photographs and nothing leaves the device unless the operator pulls it — and the report's numbers,
which carry no image content, are the half that can be shared.

### 12.10 Three traps this cost

- ⚠⚠⚠ **`clusterFaces` drops singletons (`minFaces = 2`), so a sweep at the default reports
  `singletons 0` at every threshold — by construction.** The first real run read as *"every face
  matched something"*, which is a flattering claim the measurement had not made and could not make:
  one-off faces were filtered out before being counted. It also silently lost a face — 35 went in,
  the clusters held 34 — and that one-off arithmetic mismatch is the only reason it was noticed at
  all. ⭐ **The sweep now runs at `minFaces = 1` and prints `faces accounted N/M`**, so the report
  states its own arithmetic instead of leaving a reader to check it by hand. The production default
  is still what the montages use, because that is what a People grid would really show.
  ⚠ The same filter was quietly corrupting **purity**: a dropped face contributed 0 to the numerator
  while staying in the denominator, conflating *"clustered wrongly"* with *"never returned"*.

- ⚠⚠ **EXIF or nothing.** A phone camera writes sensor pixels plus an orientation tag, and
  `BitmapFactory` ignores the tag. A sideways face is mostly not detected — and that failure is
  indistinguishable from *"the detector is bad on my photographs"*, which is the confident wrong
  conclusion this measurement exists to avoid.
- ⚠ **Kotlin block comments nest**, so a shell glob in a KDoc runbook (`debug/` followed by `*.apk`)
  opens a comment that swallows the rest of the file. It reports `Unclosed comment` against the last
  line, naming everything except the cause.

### 12.11 ⚠ `FaceCrop.unmap` — added here, and why it is product code

The resampler needs the inverse of `FaceCrop.map`, and it lives on `FaceCrop` rather than in each
backend for `alignFace`'s own reason: inverting a similarity transform is four lines that are easy to
get subtly wrong — transposing the rotation is *not* the same as negating its angle once a scale is
involved — and a backend that got it wrong would still produce a plausible crop of a face, just not
the one the template describes. Three platforms inverting it privately is §3.3's divergence, one step
later.

### 12.12 ⭐⭐ As built — F2 and F4 on Android (2026-09-24)

- **`MediaPipeFaceBackend`** (`inference-service-proxy/src/androidMain/.../onnx/`). This is the F2m
  harness's chain moved, not re-derived: the bounded EXIF-upright decode, `FaceMesh`, `alignFace`,
  the nearest-neighbour resample and the ArcFace profile. The harness now calls the shipped
  `FaceMesh` and `FaceModel.ArcFaceTemplate`, so the measurement and the product cannot drift.
  Detector confidence is 0.5; every verified cluster is identical at 0.5 and 0.2, and 0.2 adds
  non-faces.
  ⭐⭐ **Verified on the phone:** `FaceBackendParityTest` runs the shipped backend over the same 400
  photographs through `MediaIndexer`'s own calls (`detectFaces`, then `embedFace` per face) and
  clusters at `Measured`. It found 35 faces with 0 detector failures, and clusters 29 / 3 / 2 **with
  membership identical to the harness's AuraFace int8 run**. The move preserved the measured chain. It is a separate backend from the SigLIP one, because it is its own catalog entry and a
  device may hold one without the other.
- **`FaceModel.PinnedAuraFace`** is the descriptor: model id `auraface-v1-int8`, template, profile
  and width. **`ModelBackedBackend`** makes the template store's stamp the model's own provenance,
  which closes F2's "not safe to ship" note.
- **Catalog:** one entry, runtime `onnx+mediapipe` (`FaceModel.RUNTIME`), roles `faceDetector` and
  `faceEmbedder`. Android declares that runtime and desktop does not, so desktop filters the entry
  out by construction. `FaceCatalogEntryTest` pins the tracked `models.json` against the code.
  Older clients drop the entry too, because they don't know the runtime.
- ⚠ **MediaPipe is now in the shipped Android graph** (~12 MB AAR), promoted from test scope.
- **F4, the switch:** `FACE_CLUSTERING` is in `OfferedConsents`, with its own icon and copy that
  says where face data lives, that only typed names sync, and that switching off deletes the face
  data and keeps the names. It stays off by default and Settings-only.
- **Withdrawal deletes the templates, not the names** (decided 2026-09-24, threat model A40): at the
  next index pass and at the next People read. §8's open item is closed; the label half stays a
  counsel question.
- ⚠ **Desktop shows no face switch** (Control has no iOS UI). `visibleConsents`
  offers it where the build flag `faceClusteringRunsHere` is true (Android), or when the switch is
  already on, because a live consent must never become invisible. ⚠ It is a build fact on purpose:
  inferring it from the catalog conflated "cannot run here" with "catalog unreachable", and two
  review rounds found each fallback wrong one way or the other. Desktop People answers NO_BACKEND until the
  detector is converted to ONNX, which is its own slice.
- ⚠⚠ **A model update is refused, never mixed**. `faceStoreLocator` claims the
  installed pair only when its version equals `FaceModel.modelVersion`. Templates carry the pinned
  stamp, so loading another version's weights would add a second embedding space to a store that
  cannot tell them apart. Each file's size must also equal the install record's, because the store
  replaces files before it rewrites the record. Shipping a new version therefore means a build that
  pins it, and a re-index. The equal-length gap that size check could not see is closed by F2v below.
- ⭐ **F2v as built (2026-09-30) — a commit never overwrites bytes a live record names.**
  `DefaultModelStore.commitStaged` lands each artifact at `<modelsDir>/<id>.<role>.<digest>.model`,
  the digest being the signed catalog's sha256 (sanitised, first 32 characters). Two contents never
  share a path, so during the commit the old record still names the old, untouched bytes, and the
  index write flips to a record whose files are all present. The one collision left is the same
  digest, which is identical bytes — and is **adopted, never moved onto** (below).
  - **No reshape.** `InstalledArtifactData.localPath` already stores the path, so the index JSON is
    unchanged, and it is device-local rather than a schema composite. No version bump.
  - ⚠⚠ **Existing installs need no move.** A pre-F2v record names the flat `<id>.<role>.model` path
    and keeps resolving through that record, so SigLIP on today's devices keeps working without a
    re-download. The next update lands at a digest path and reclaims the flat file. The flat path
    survives as the staging stem, so a `.part` begun under an older build still resumes.
  - ⚠ **Reclamation is declared, then swept.** Before the first rename, every path in play (old and
    new) goes on a pending list under `<indexKey>.garbage`. A sweep deletes each listed path
    **that no live record names**; it runs on the first index read in a process and nowhere else
    (next bullet). A death at any step leaves one whole install, and the next start removes what
    is left over. `remove` uses the same list.
    `DefaultModelStoreTest.aCrashAnywhereInAnUpdateLeavesOneWholeInstallAndNoOrphans` crashes
    through every mutation to pin this.
  - ⚠⚠ **A promotion never targets a file that is already there and whole** (2026-10-02). "Identical bytes, so harmless" was wrong about the mechanism: `FileDatabase.moveFile`
    deletes an existing destination and *then* renames, on every platform (`JvmFileDatabase`,
    `AndroidFileDatabaseImpl`: `if (to.exists()) to.delete()`; `IosFileDatabase`:
    `removeItemAtPath` before `moveItemAtPath`). A death in that gap leaves the path empty while
    a durable record names it. Three commits reach an existing destination: a re-install of the
    same version, an update that keeps one artifact's digest (an unchanged detector beside a new
    embedder), and a rollback whose previous index write is still only in memory. The third is
    why "does the live record name it?" cannot be the test: the in-memory record does not, the
    durable one does.
    - **The rule: if the destination holds a file of the staged copy's length, the commit adopts
      it and discards the staged copy.** Nothing is moved, so nothing is deleted.
    - ⚠ **Length, not digest.** Everything the store can leave at a content-addressed path arrived
      by an atomic rename of a file the downloader had verified, with one exception: `moveFile`'s
      copy fallback dying part-way, which leaves a *shorter* file. Length catches that. A digest
      would read the whole artifact under the store-wide lock, and the commit verifies nothing
      itself either: the caller owns the digest. A resident file of the wrong length is replaced,
      which is the one case a move still targets an existing path — and that file was already
      broken.
    - ⚠ **A commit declares what it creates and what it replaces, never what it adopts.** An
      adopted file was there before the commit and the new record names it, so no outcome makes
      it garbage. Paths the commit creates are still declared before the rename: that is what
      removes them if the index flip does not survive.
    - Tests, `DefaultModelStoreTest`, over a file double whose move onto an existing path deletes
      it and dies there: `anUpdateThatKeepsOneArtifactNeverMovesOntoTheFileALiveRecordNames`,
      `reinstallingIdenticalBytesMovesNothing`, `aRollbackNeverMovesOntoFilesTheDurableIndexStillNames`,
      `aRetryAdoptsWhatADeadCommitAlreadyRenamed`, plus `aTruncatedResidentFileIsReplacedNotAdopted`
      and `aCommitNeverDeclaresAPathItAdopts`. ⚠ The double's gap was read from the three
      implementations' code, not produced by killing a process.
  - ⚠⚠ **Reclamation waits for the next process** (2026-10-02). As first built,
    the commit swept right after its index write. That assumed the write was durable when it
    returned, and `SecureStorageDelegate.storeString` promises no such thing:

    | Platform | Delegate Control wires | Index write when `storeString` returns |
    |---|---|---|
    | Android | `AndroidSecureStorageDelegate` | **In memory only.** `SharedPreferences.edit { }` defaults to `apply()`; the file is written later. |
    | Desktop | `DesktopSecureStorageDelegate` | Written to `local-store.properties` before returning. Survives a process death; no `fsync`, so not a power loss. |
    | iOS | `IosSecureStorageDelegate` | Keychain `SecItemAdd`, synchronous. iOS contributes no runtime, so nothing commits there anyway. |

    A file delete is in the kernel at once. On Android a death between the deletes and the
    deferred write therefore restarted on the **old** record over files that were gone, which is
    the exact state F2v exists to rule out.
    **The rule now: a file is deleted only against an index a process read back from storage,
    never one it only wrote.** A commit and an eviction declare and stop. The first index read
    of the next process sweeps, and what that process read is by definition what survived. No
    delegate has to be durable for this to hold, so the store makes no assumption about one.
    - ⚠⚠ **The cost: a replaced or evicted generation stays on disk until Control's process is
      replaced.** An update holds two generations until then. `DeleteModel` stops the model being
      served at once but frees its bytes at the next start. ⚠ The Models screen says exactly that,
      before a removal and after one, and after an update (`inference-service.md` §24.3); it used
      to say "This frees 383 MB on this device". On desktop, whose delegate was already
      durable, this is cost with no gain. Buying it back needs a delegate that *vouches* for a
      write's durability (a `commit()` on Android), which is a `:core-common` interface change and
      an Android smoke-gate run; it was left out of this fix on purpose.
    - ⚠ **What a write-behind delegate can still do is leak, never break.** If neither the
      declaration nor the index write reaches disk, the renamed new files are named by nothing.
      A retry of the same version adopts them at the same content-addressed paths and its record
      names them; a catalog that has moved on meanwhile leaves them behind. ⚠ Adoption does not
      spare the retry its download: the downloader stages before the store looks.
    - ⚠ The Android behaviour was **read from code, not observed on a device**. The tests model
      it with a delegate whose writes reach disk later and in order, and kill it with every
      possible amount of that backlog written:
      `anUpdateKilledBeforeItsIndexWriteReachedDiskStillResolvesWhatTheIndexNames` and
      `anEvictionKilledBeforeItsIndexWriteReachedDiskLeavesTheModelLoadableOrGone`. Each is
      red with its sweep restored (the commit's, the eviction's).
    - ⚠⚠ **"What that process read" has to be an index** (2026-10-11). As built, a read that
      came back empty-handed, or a string that did not decode, was an empty index, and the sweep
      ran against it: the process after an install deleted that install. The sweep now runs at
      the first read that **yields** an index, and never against one that was unreadable or
      another build's record; the same reads refuse a commit and a removal. Record:
      `ios-on-device-inference.md` §13.7.
  - ⚠⚠ **One writer at a time**. Between a commit's declare and its index write,
    its new paths are live only by intent: no record names them yet. Any sweep that runs in that
    window deletes them, and the commit then indexes an artifact that is not there. Two models
    download concurrently, since `DefaultModelDownloader` runs one job per model, so as first built
    a second commit's sweep could do exactly that. So could the first index read of a process,
    which runs the recovery sweep from a stale, uncached read. `DefaultModelStore` therefore holds
    one store-wide lock (atomicfu `SynchronizedObject`) for the whole body of every method that
    touches the index or the garbage list, **reads included**. Only the per-`(model, role)` staging
    calls stay outside it. With that rule, the recovery sweep runs before any mutation in its
    process, so every declaration it sees belongs to a dead process. Deleting a dead commit's
    un-indexed files is then correct: that generation never went live and re-downloads.
    ⚠ **Re-argued after the P2 fix above.** A commit no longer sweeps, so commit-against-commit can
    no longer delete a pending generation. The lock still carries two things. The first read's
    sweep can still land inside a commit. And the index and the garbage list are read-modify-write:
    two unserialised commits lose one model's record while its paths stay declared, and the next
    start then deletes an install that had completed.
    ⚠ The lock is per **process**; the next bullet is what makes one process the writer.
  - ⚠⚠ **One process at a time** (2026-10-02). The earlier note here said one
    process reaches the store per device "on Android and desktop". That was wrong for desktop.
    - **Two desktop Controls can run over one data directory, and both serve.** There is no
      single-instance guard (`control-desktop/.../Main.kt`, `ensureStartAtLoginDefault`). The
      second one unlinks and re-binds the IPC socket (`UnixSocketServer.start`:
      `Files.deleteIfExists(path)`), opens `objects.db` with the contention reported and not
      refused (`SqliteObjectStore`, `crossProcessContended`: *"a diagnostic, not a refusal"*), and
      loads the same `local-store.properties`. It has happened: autostart daemon plus a dev `:run`
      (`tech-debt.md`, *"Desktop Control has no single-instance guard"*).
    - **Android and iOS are one writer by construction.** Android: `proxyModule` is loaded only by
      `ControlApplication`, and no manifest in the repo declares `android:process`. iOS: every
      route-1 app runs `proxyModule`, but none contributes `supportedModelRuntimes`, so nothing
      commits, evicts or declares. **The day iOS gets a runtime it needs a real lock.**
    - ⚠⚠ **Deferring reclamation made the desktop case worse in one way and better in another.**
      Worse: a declaration used to live for the length of one commit and now lives until the next
      process, so a second Control can read a list naming a path the first has re-installed since
      (evict, then download the same version again) and sweep it from under a live index. Before,
      that needed the second one's read to land inside a commit. Better: the first Control no longer
      deletes the old generation itself, so when the second rewrites the properties file from its
      stale copy, the index it restores still names files that exist.
    - **The rule now: `ModelStoreOwnership`, asked once when the store is constructed.** Desktop
      binds `FileLockModelStoreOwnership`: an OS advisory lock on `<content>/inference/models/store.lock`,
      tried once and held for the life of the process. The refused Control still answers
      `installed` and `pathOf`, never sweeps, and every write throws `ModelStoreNotOwnedException`
      with a sentence saying another Control owns the store. `beginStaging` refuses too, so a
      download fails before it fetches anything. A lock that cannot be taken at all is refused as
      well, with its own sentence.
    - ⚠⚠ **The refused Control is told up front, and offered nothing that cannot work**. The
      sentence above reached nobody: `DownloadModel` mapped the plain exception
      to the retryable `TRANSPORT_FAILED`, and the Models screen discarded a removal's failure.
      Now the refusal is `ModelUnavailable.Reasons.STORE_NOT_OWNED` over the wire, and the screen
      does not wait for it: `ModelStoreAccess` (a process-local single beside `LastIndexCoverage`)
      puts the state in the snapshot the screen already reads, so it shows *"Another Control is
      running on this computer and manages its models. Quit the other one and restart this one…"*
      and disables download and remove. A refusal that still arrives is said once and latches the
      same state. Details and the no-reshape argument: `inference-service.md` §23.1.
      ⚠ A lock that could not be taken at all is a second kind, with its own reason
      (`STORE_LOCK_UNAVAILABLE`) and its own sentence: it names the lock path and the cause and
      does not mention another Control, because there may be none.
    - ⚠ **Decided once, and before the index is read.** A refused process holds an index it read
      while the owner could still write, so it never takes over later. For the same reason
      desktop's `SecureStorageDelegate` provider resolves the ownership first:
      `DesktopSecureStorageDelegate` loads the properties file in its constructor.
    - ⚠ **The seam has no default in `:inference-service-proxy`, and one fallback in
      `ProxyModule`**: an absent binding means `SoleProcess`. That is true of Android and iOS and
      false of desktop, so `ModelStoreOwnershipWiringTest` (source text) fails if desktop stops
      binding the lock or takes it after the properties file is read.
    - ⚠ **Not closed here:** the second Control still rewrites `local-store.properties` whole from
      its own copy on any write, to any key. That can drop a completed install's record (the model
      re-downloads) or leave files nothing names. It cannot leave a record naming a missing file,
      because nothing but the owner's first read deletes. **Argued, not tested.** The owner of
      that gap is the single-instance guard, recorded in `tech-debt.md`.
    - `DefaultModelStoreTwoProcessTest` (jvmTest) runs two stores over one directory, each with
      its own lock object and its own copy of the properties. ⚠ It is one JVM: the lock is refused
      through `OverlappingFileLockException`, not through the `null` a real second process gets,
      and the properties double was written from `DesktopSecureStorageDelegate`'s code.
    `DefaultModelStoreConcurrencyTest` (jvmTest) holds one commit open with latches on real threads
    over a real filesystem, once inside its index write against a second commit and once against a
    first read. Each test is red without its half of the lock. A single test dispatcher cannot
    produce either interleaving.
  - A new path per update also fixes a SigLIP defect nobody had filed: `OnnxInferenceBackend` keys
    its session by **path alone**, so a same-path update kept serving the old checkpoint until the
    process died. The locator's size check and `MediaPipeFaceBackend.fileKey`'s size+mtime stay as
    defence for pre-F2v and side-loaded files.
- **Index now covers faces.** `StartMediaIndexPassImplementation.INDEXING_CAPABILITIES` includes
  `FACE_CLUSTERING`, so a device holding only the People model can start a pass.
- ⚠⚠ **A model change rebases the template store.** Both the index pass and the People read clear a
  store stamped by another model, so People answers NOT_INDEXED rather than serving the old space's
  clusters. The cost is a re-index, never a name.
- ⚠ **Updated files reload without a restart**. The backend caches its runtimes
  against path, size and mtime. Since F2v a store update gets a new path anyway. Size and mtime
  still cover a pre-F2v install and a side-loaded file replaced at the same name.
- ⚖️ **Published before counsel answered, by maintainer decision on 2026-09-24.** This overrides the
  release gate §10 Q1 recorded. The packet is still owed and still ready to send.


### 12.14 ⭐⭐ Recall shipped — tiled two-stage detection (2026-09-29)

§12.8 ran the second stage in the harness. This slice put it into `MediaPipeFaceBackend` as a
`FaceDetectionStrategy` and measured the shipped code on the same 400 photographs
(`FaceDetectionStrategyExperimentTest`, OnePlus CPH2581, AuraFace int8, detector confidence 0.5):

| Strategy | Faces | New vs single stage | Detection per photo | Groups (>1 photo) |
|---|---|---|---|---|
| Single stage (landmarker on the whole photo, §12.6) | 35 | — | 131 ms | 3 |
| Two-stage (box detector → landmarker on each crop) | 41 | 7 | 143 ms | 4 |
| ⭐ **Two-stage + 2×2 overlapping tiles** — shipped | 62 | 28 | 481–546 ms | 7 |

Every new face was cropped and looked at, because a higher count is not a better strategy until the
new detections are faces:

- ⭐ **~21 of the 28 are real people.** The verified group grew 29 → 33 photographs and all four
  additions are the same person — **no photograph left a group it was in**. A person single stage
  never found became a pure 9-photo group; a third came out pure but split 4 + 2.
- ⚠⚠ **7 are not people**: a doll, an anime face, a pastry, and three crops of one cat — which form
  a **2-photo group**, i.e. a cat appears in People as a person. ⚠ **The detector's score cannot
  filter them**: the cat scored 0.58–0.72, one real person 0.50–0.65, so every threshold that drops
  the cat drops him. Chosen anyway (maintainer decision, 2026-09-29): the recall gain is several
  people, the false group is one and can be ignored. `tech-debt.md` *"People: a non-human face can
  form a person"* tracks the filter.
- ⚠ **~3.5× the detection time** (a 1,345-photo library: ~4 → ~12 min of detection). Acceptable
  because passes run idle + charging; the embedder's cost is per face and unchanged.

How it is built, and the traps:

- ⭐ **No new artifact.** The box detector is `face_detector.tflite` read from **inside** the installed
  `face_landmarker.task` — byte-identical to the one §12.8 measured. ⚠⚠ Not via `ZipFile`: the
  bundle has two padding bytes before its first local header, which Python's reader skips and
  Android's rejects (*"Entry at offset zero has invalid LFH signature"*) — every photo failed
  silently until the bundle was read as a stream from the first `PK\3\4`.
- Detector on the whole image **plus** overlapping tiles (20% overlap, so a face on a seam is whole in
  one of them); boxes de-duplicated (≥ 0.5 intersection-over-smaller, the LARGEST kept — never the best score, see the as-checked note below); landmarker
  on each box expanded by 25%; a mesh is kept only if it overlaps its own box (≥ 0.3, the crop margin
  can pull in a neighbour) and is not a second mesh of a face already kept (≥ 0.8) — which is
  expected to close §12.8b's *same face twice*. ⚠ Expected, not confirmed on the device: §6a F6c.6.
- ⚠⚠ **The pipeline is part of the face stamp.** `FaceDetectionStrategy.pipelineVersion` rides in
  `ModelProvenance.labelSetVersion` (single stage 0, tiled 2), and the backend puts the **same** stamp
  on every vector it returns. Freshness is `visited`, so without the stamp change a better detector
  would never re-open a photograph it had seen; with two spellings (checked vs written) the store
  would be wiped on every pass. Upgrading therefore re-indexes the library once; names survive.
- ⚠⚠ **What the tiled pipeline loses, checked by eye (2026-09-30).** Per-photo comparison of the
  single-stage and tiled face stores on the full library (1,258 photos): 493 faces against 247, and
  **11 photographs where single stage had a face the tiled pipeline does not.** Looked at one by one,
  **7 were single stage's false positives** (an envelope, a cookie, a tomato, a beer glass, a cucumber
  slice, a cartoon cupcake, a screenshot) — dropping them is a gain — and **4 are real faces lost**: a
  masked face, two small children asleep, and a face in two photos WhatsApp stored sideways (no EXIF, so
  the face lies at 90°). The 400-photo run's one "lost face" was a pastry. ⚠ A first hypothesis — a
  tile's fragment of a face out-scoring the whole face in box de-duplication — was **not** borne out:
  keeping the largest box instead (`selectFaceBoxes`) recovered none of these. It stays, because a
  fragment is never the better crop, but it is not the fix for the four. ⭐ The same comparison closed
  §12.8b's *same face twice*: the old store held one exact duplicate pair, the tiled store none.
- ⚠ The calibration (0.44, `minFaces = 2`) was **not** re-chosen: the verified group's membership
  only grew, with no wrong addition, at the production threshold. Re-measure if a later strategy
  moves faces *between* groups.

### 12.15 ⚠⚠ Non-human faces — a gate on the embedder's own norm (2026-09-30; **OFF since 2026-10-01**)

> ⚠⚠ **Turned off 2026-10-01 — it failed on the real library.** Everything below was measured on the
> verified 400-photo set's **full-resolution originals**. The maintainer's library is indexed mostly
> from **1024 px previews** (1,203 of ~1,290 photos, `fromPreview`), and there the gate at 15.0 left out
> 48 faces: checked by eye, **22 were non-faces** (cats, a lucky-cat figurine, figures, a teddy bear, a
> mannequin, cartoons, a rose, a pacifier, pastries) **and 26 were real people** — clear adult faces,
> children, babies, a masked surgeon, cosplayers, and four rotated photos of the maintainer. A preview
> is recompressed and smaller, and the norm tracks image quality as well as faceness, so real faces
> from previews fall under a threshold set on originals. `FaceModel.minFaceNorm` is now `null` for
> AuraFace int8 (the mechanism stays), pipeline stamp 12 → 22 so libraries indexed under 12 get those
> faces back.
>
> ⚠⚠ **Measured on previews (2026-10-01): no threshold separates them.** The shipped backend over the
> 1024 px previews of all 283 face photos on the maintainer's library (`FaceDetectionStrategyExperimentTest
> -e photos <dir> -e orientations <file>`, orientation passed as the indexer passes it): 495 faces, and
> the by-eye verdicts matched by box — **24 real faces at 10.67–14.95, 21 non-faces at 11.07–14.95**,
> the other 450 at median 21.5. The two classes overlap completely; at preview quality a low-norm real
> face and a figurine look alike to the embedder. ⇒ The norm cannot gate previews at any threshold. It
> still separated perfectly on originals (below), so a gate applied only to faces indexed from the
> original remains possible — but on a library indexed from previews it would barely act. A real fix is
> a human/non-human check that does not depend on image quality (a small classifier on the crop or the
> embedding, `tech-debt.md`).

#### As first built and measured (2026-09-30)

The tiled pipeline (§12.14) also detects faces that are not people: on the verified library a cat
(three crops, two of which formed a 2-photo "person" at cosine 0.53 > 0.44), a doll, an anime drawing
and a pastry. ⚠ The detector's score cannot filter them (cat 0.58–0.72, a real person 0.50–0.65).

**What separates them — measured offline on 29 detections (23 real faces, 6 non-human), reproducing the
shipped path in Python:** the embedder's **raw L2 norm, before normalisation**. AuraFace int8: every
real face ≥ **16.5** (median 19.3), every non-human ≤ **13.8** (11.9–13.8). Nothing else cheap separates
them: cosine to other faces, the landmarker's presence and detection confidences, landmark geometry,
head pose and blendshapes all overlap (the one geometric ratio that separates does so by 0.05 and moves
with pose). ArcFace-family norms track how face-like the input is; that is the signal.

**Built:** `FaceModel.minFaceNorm = 15.0` for AuraFace int8 (model-specific — the norm's scale belongs to
the weights); `embedFace` computes the norm and flags `ImageEmbedding.notAFace`; the indexer leaves that
face out, counts it (`facesNotHuman` in the pass line) and records the photograph as done.
⚠⚠ **Never a `null`**: `null` from `embedFace` means "the model could not run" and latches the face half
off for the rest of the pass. ⚠ Pipeline stamp 2 → 12 (with §12.14's largest-box change), so every
library re-indexes once and the cat's stored "person" goes with it.

⚠ **Limits:**
- Below ~30 px, or under heavy blur, the norm measures image quality rather than faceness: tiny real
  faces fall under 15 and a tiny non-human can rise over it. Every non-human seen here was ≥ 105 px.
- A real face in a poster or on a screen passes; the norm cannot tell print from person.
- The sample is small and correlated (6 crops from 4 objects), and 15.0 was chosen from the same data.

⭐ **Confirmed on the phone (2026-09-30)** — `FaceDetectionStrategyExperimentTest`, which now writes each
face's norm, over the 400-photo library with the shipped decode: the gate left out exactly **5 of 61**
tiled detections — the drawing, the doll, the pastry and two cat crops — and single stage's one
rejection was the kugelhopf §12.14 found. Highest non-human **14.25**; lowest kept face **16.31**, and the
three closest above the gate (16.31, 16.61, 17.04) were checked by eye: all real faces. The cat's
2-photo group is gone (6 → 5 groups of more than one).

