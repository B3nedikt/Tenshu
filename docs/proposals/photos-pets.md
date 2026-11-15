# Photos — Pets: naming your cat like you name a person

**Status:** v0.2 — **design; only PT-1 (the Things allowlist, §3) is built.** Decisions PT-D1…PT-D9 are proposed (§8), and
⚖️ two licence questions are owed (§5.3). Opened 2026-11-11 from a maintainer request (*"compared
to competitors … they also have pets, like 'People and Pets', and you can name your cat"*). This is
phase **P1** of [`photos-people-and-pets.md`](photos-people-and-pets.md), which has carried pets as a
one-line row since 2026-09-21 (§6, §9). It is IN-6's second capability in
[`inference-service.md`](inference-service.md), which owns every policy this doc builds on.

**Owner:** Benedikt

⚠⚠ **The design move is to copy People, not invent beside it.** A pet's name is a `MediaLabel` per
photograph, *this photo shows Mochi*, exactly as a person's is (IN-D5). Embeddings stay
device-local, clusters are local scaffolding, and the device suggests rather than auto-assigns.
The new parts are the **model** (a human-face embedder does not tell two cats apart, §5), the
**consent** (pets are not Article 9 data, §4), and **one trap that ships before any of it** (§3).

---

## 1. What competitors ship (surveyed 2026-11-11)

| App | What it does | Taken from it |
|---|---|---|
| **Google Photos** | Pets join face groups in *People and pets* and are named like people. A separate setting, *Show pets with people*, turns pets off while keeping faces on. Server-side | One section, and **its own switch** (PT-D2, PT-D3) |
| **Apple Photos** (iOS 17+) | *People* became *People & Pets*: individual cats and dogs, named, then suggestions confirmed. **No public API** exposes pet identity: Vision gives species boxes and pose only | The confirm-suggestions flow, which we already have for people (F6c-s) |
| **Samsung Gallery** | *People and pets*. Users report weak, inconsistent pet grouping | What a confident-but-wrong grid costs (PT-D6) |
| **Immich** | Core Immich has a long-open request and no feature. The **open-noodle/gallery** fork ships it: RF-DETR detection, cats and dogs only, its own re-ID model, off by default, max cosine distance 0.55 | The closest precedent for our licence posture (§5) |

⚠ Nobody ships more than **cats and dogs** as individuals, and neither do we in v1 (PT-D5).

---

## 2. The architecture: People's, with three swaps

```
 photo ─▶ DETECT animals ─▶ crop (whole body) ─▶ EMBED ─▶ pet template (device-local, never syncs)
          (cat, dog boxes)                       (pet re-ID      │
                                                  model)         ▼
                                                        cluster (device-local)
                                                                 │
                                                       user names "Mochi"
                                                                 ▼
                                    one MediaLabel per photo, capability = petRecognition ─▶ SYNCS
```

| People has | Pets use | Why |
|---|---|---|
| MediaPipe face detector + 5-point alignment | an **animal detector**, whole-body crop, no alignment (§5.1) | there are no landmarks to align a cat to; re-ID literature crops the body |
| AuraFace embedder | a **pet re-ID embedder** (§5.2) | AuraFace on a cat measures how face-like the crop is, not which cat it is (People §12.15) |
| `FACE_CLUSTERING` consent, Article 9, A40 | a **`PET_RECOGNITION`** consent, not special-category (§4) | an animal is not a natural person |

Everything else is reused as built: the device-local template store's log discipline (People
§3.1, F6c.1), the per-photo label, `ModelProvenance.User` on a typed name, the suggestion flow
(F6c-s), the cover rule (People §7), and the Collections row (`photos-app.md` §35).

⚠ The template store is a **separate file** from `faces.log` (proposed `/inference/pets.log`, its own
magic). Withdrawing face consent deletes face templates (A40); it must not take pets with it, and
the other way round.

---

## 3. ⚠⚠ The trap that ships first: a pet's name must not become a *Thing*

`groupPhotosByLabel` (`PhotoLabel.kt`) builds the Things section from every `MediaLabel` **except**
`FACE_CLUSTERING` ones, a **denylist**. A pet name is a `MediaLabel` with a new capability id, so
every Photos build released today would show *Mochi* as a Things card beside *Dog*. That happens on
any device that has not updated, the moment another device names a pet. People's code guards
against exactly this for people (*"A PERSON is not a THING"*), and only for people.

**Fix, as its own small PR before any pets code (PT-1, ✅ built 2026-11-11):** turn the filter into an **allowlist**.
Things shows `CONTENT_TAGGING` rows only, because a *Thing* is what a model said about a picture,
and that is the only capability that says it. A capability added later is then invisible to Things
until someone decides otherwise, which is the safe default for every future label kind, not only
pets.

⚠ This only protects clients that have it, so it has to **ship and spread before PT-3 writes the
first pet name**. Today the risk is nil: no client writes `CONTENT_TAGGING` (labels are inert,
`inference-service.md` §37.1), so the change hides nothing that exists.
`CollectionsViewModel`'s People read already filters `FACE_CLUSTERING` explicitly, so pets cannot
leak into People. Every other `MediaLabel` reader ignores the capability and shows the label text,
and for a pet that is the intended result: Photos search (`citableLabelMatches`) shows *tagged
"Mochi"*, and Mia's label search cites the photo with the name. Web, Timeline and hub read no
labels. Things is the only mis-render (checked 2026-11-11).

**The label is not a reshape; the ops are.** `MediaLabel.capability` is a `TString` and
`InferenceCapability` is a plain enum, not a schema constant, so a `petRecognition` row needs no
bump. ⚠⚠ But PT-3 cannot be schema-free. `nameFaceGroup` hard-codes `FACE_CLUSTERING`
(`NameFaceGroupImplementation`), and `faceGroups`, `groupedFace` and `rejectFaceSuggestion` are
pinned in `SchemaShapeBaseline`. Pet groups, naming and rejections, whether as new ops or as a
capability parameter on the existing ones (an optional op parameter is a reshape too), are a
**HR #9 bump of `:inference-service` with its `SchemaMigration`**, and Control and Photos move
across it in lockstep. PT-3 owns that bump.

---

## 4. Consent: its own switch, not Article 9

- **PT-D2 — its own capability, `PET_RECOGNITION("petRecognition")`, under IN-D3's two switches
  (realm ∧ device, absence = no).** It should not ride `FACE_CLUSTERING`. Many people who refuse
  face recognition will want their dog, and Google offers the reverse split. Folding pets into face
  consent would make the more sensitive switch the price of the harmless one.
- **Not special-category.** GDPR Art. 9 covers natural persons, and a pet's name on a photo is not
  biometric data about anyone. It is ordinary realm data: sealed like any row (A2), so HR #1 holds
  trivially. ⚠ It is still personal data *about the owner* (a pet's name is a common security-question
  answer, and photos of a dog at home are photos of a home). That is why it stays off by default, and
  why the threat model gets a one-paragraph note beside A40 rather than nothing (HR #2, landing with
  PT-3, the first slice that writes a pet name).
- **`offerableInOnboarding`: proposed `false` for v1.** The data is not sensitive, but IN-D3's
  argument applies to any feature that is fine when chosen: asking at first run is a nudge. Settings →
  Models (`inference-service.md` §38) is where downloads and what syncs are explained. Revisit once
  the feature has shipped.
- **Withdrawal mirrors People's F4: templates are deleted, names are kept.** Turning
  `PET_RECOGNITION` off deletes `pets.log` and keeps the typed names, which are the user's own words.
  ⚠ Face withdrawal is special-cased by capability (`JoinedModelSource`), so pets need their own
  hook. Re-using the face one would delete the wrong store.
- **The detector needs no identity consent of its own when People uses it** (§6). A box that says
  *"cat here"* identifies nobody. So the detector may run under **either** switch, but only the
  embedder and the pet store require `PET_RECOGNITION`. Under IN-D10 (per-capability downloads) the
  detector is its **own catalog artifact**, listed under both capabilities and fetched by whichever
  is on first. A face-only user therefore downloads it too, which is the price of §6's veto. That
  user is told so in Settings, the same way every download is listed.

---

## 5. The models

### 5.1 Detection: Vision on Apple, one open detector elsewhere

| Candidate | Licence | Verdict |
|---|---|---|
| **RF-DETR Nano / Small** (COCO) | Apache-2.0, code **and** weights. ⚠ XL/2XL are proprietary (PML); sources conflict on the smallest sizes, so pin N/S | ⭐ **Proposed.** A COCO-weights ONNX export at 512 px already exists |
| D-FINE / DEIM (COCO) | Apache-2.0 | Fallback if RF-DETR's recall or speed fails §7 |
| YOLOX Nano/Tiny | Apache-2.0 (unverified) | Fallback |
| MediaPipe EfficientDet-Lite0 | Google's page states no licence | ✕ Unless a licence is found. It would otherwise fit, since we already ship MediaPipe |
| Apple `VNRecognizeAnimalsRequest` | OS API, cat and dog only, with boxes, iOS 13+ / macOS 10.15+ | ⭐ **Proposed on Apple platforms** (PT-D4, below) |
| Ultralytics YOLOv5/8/11 | **AGPL** | ✕ Never |

**PT-D4 — Vision's animal request on Apple, RF-DETR elsewhere (IN-D13), with box parity measured
(M6).** People §7a argued for one face detector everywhere, but iOS People did not follow it:
`ios-face-grouping.md` §3.4 chose Vision, because iOS's runtime is Core ML and a converted
third-party graph costs more than the parity measurement it would avoid. For pets the case for
Vision is stronger:
- §7a's main ground was landmark conventions and alignment templates, and pets use neither (§2).
- `VNRecognizeAnimalsRequest` returns cat and dog boxes on iOS 13+ / macOS 10.15+ with no weights
  to ship. That also keeps COCO-derived weights off Apple platforms entirely (§5.3).

What does carry over from §7a is that **the crop is the embedder's input**: two detectors that frame
the same cat differently embed it to different places. So M6 measures box agreement between Vision
and RF-DETR on the same photos, and the crop is normalised (square, fixed margin around the box)
before embedding, which absorbs small framing differences. If M6 fails, iOS runs RF-DETR through
Core ML instead, the fallback `ios-face-grouping.md` §3.4 kept for faces too. ⚠ Cross-device
consistency does not hang on this either way: names sync as labels (§2), and only the
*suggestions* depend on each device's own embeddings.

Classes kept: COCO `cat` and `dog` (PT-D5). Other animal classes (bird, horse and the rest) are
detected and **discarded** in v1. Naming a horse is a reasonable later ask, but the re-ID evidence
(§5.2) covers cats and dogs only.

### 5.2 Re-identification: the embedder

| Candidate | Licence | Verdict |
|---|---|---|
| **open-noodle `pet-recognition-small`**: frozen DINOv2-S (Apache-2.0) + a linear projection to 512-d, 224 px, ONNX | Apache-2.0. Training data **claimed** to be Dogs-World (CC0) + Cat Individual Images (CC BY), with PetFace, Avito and MegaDescriptor data explicitly excluded | ⭐ **Proposed candidate**, ⚖️ provenance self-asserted (§5.3). Held-out: dogs whole-body EER 0.068 / AUC 0.980; cats EER 0.065 (**102 identities only**) |
| **Our own projection head** on DINOv2-S, trained on the same audited CC0/CC BY sets | Ours. DINOv2's Apache licence permits it | ⭐ **The fallback that makes provenance ours** if the audit cannot clear the above. The head is linear, so training it is small |
| MegaDescriptor | **CC BY-NC 4.0** | ✕ Never. It is the strongest on wildlife benchmarks, and non-commercial |
| PetFace-trained anything | PetFace is **research-only** | ✕ Never |
| AvitoTech SigLIP/DINOv2 fine-tunes | Tagged Apache-2.0, but **trained on PetFace + scraped images** | ✕ Never. The licence tag is not the licence. Useful only as an accuracy reference (cats top-1 0.865) |
| Our SigLIP image vectors, reused | Already shipped | ✕ as the identity signal. General-purpose features match **breed**, not the individual. Two black cats in one home, which is the hard case, look identical to it |

**PT-D7 — whole-body crops first.** Detector boxes are bodies, the candidate was trained on bodies,
and a sleeping cat has no visible face. Face-crop or fused (face + body) embeddings, Apple's pattern
for people, are a later improvement gated on §7's numbers.

### 5.3 ⚖️ Owed before release

1. **The embedder's training data.** Audit Dogs-World and Cat Individual Images against the card's
   claim: licence, and how the photos were collected. Immich's pattern (People §10a) applies: ask
   the author, get the answer in writing, commit the date. If either set fails, train our own head
   (§5.2).
2. **⚖️⚠ COCO for the detector, the larger of the two.** The annotations are CC BY 4.0, but
   `models.md` §3.10.1 records that most COCO *images* are NonCommercial (3,475 of 5,000 in val2017),
   and §2.1 of the same doc says we redistribute, are commercial, and quantise, so derivatives inherit.
   COCO largely **is** a non-commercial corpus, and RF-DETR's Apache-2.0 covers the weights' code
   and release, not the photos they were trained on. This is the question §3.10.1 already raises
   for a calibration table derived from COCO, at a larger scale. COCO-trained detectors ship in almost every app, but
   that is practice, not a licence. Routes if the call goes against it:
   - Vision on Apple (PT-D4) is unaffected.
   - Elsewhere, a detector trained on **Open Images** (annotations CC BY 4.0, images listed as
     CC BY 2.0 [unverified here]) or our own fine-tune on a permissive set.
   This call gates PT-2 on Android and desktop.

Both follow PhotoPrism's pattern, which People already recommends: a per-artifact licence constant
and SHA-256 pinning in the catalog (`models.md`), so a better model can be offered as a power-user
opt-in without contaminating the default.

---

## 6. ⭐ What the detector gives People for free

`tech-debt.md` *"People: a non-human face can form a person"* is open because the only signal that
separated cats from faces, AuraFace's raw norm, collapses on 1024 px previews (People §12.15: 24 real
faces and 21 non-faces in the same norm band). Its *"Next"* asks for a human/non-human check that does
not track image quality.

An animal detector is that check: **a face box lying inside a confident cat or dog box is not a
person.** The People pass can drop it before embedding, under `FACE_CLUSTERING` consent alone, since
a species box identifies no one (§4). ⚠ It covers cats and dogs, not the figurines, cartoons and
pastries in §12.15's list. It is a partial fix, and should be measured as one (§7, M5).

This is a good reason to land the detector first (PT-2): it is useful to People before pets exist.

---

## 7. What has to be measured before PT-3 ships

People's lesson applies to every row: **measure on 1024 px previews**, because that is what the
indexer reads for most of a library (§12.15's gate passed on originals and failed on previews).

| # | Measure | Bar |
|---|---|---|
| M1 | Detector recall for cats and dogs on a real library: close-ups, partial animals, night shots, a cat on a sofa | Characterised, with misses inspected by eye, as People §12.8 did |
| M2 | Embedder separation **within one household**: 2–3 pets, including a same-breed pair, against strangers' animals (walks, other people's photos) | A cosine threshold with no merge between two of the household's own pets. Splitting is recoverable and merging is not (People §10 Q2) |
| M3 | Threshold and `minPhotos`, chosen from M2 | Recorded with the run that chose it |
| M4 | Latency per photo for detector + embedder on a mid-range Android phone, an iPhone and desktop | Fits the existing idle+charging index pass (IN-D6, as quoted in `inference-service.md` §34.1) |
| M5 | §6's veto on the verified People library: how many of the 21 preview non-faces it removes, and whether it removes any real person | Zero real people lost |
| M6 | Parity between platforms: Vision vs RF-DETR boxes on the same photos (PT-D4), and ONNX vs Core ML embeddings of the same crop | Box agreement high enough that the normalised crops embed alike; embedding cosine high enough that one threshold means the same on both |

⚠ M2 needs labelled photos of real pets in real homes, which no public set provides. The maintainer's
own library plus volunteers' photos is the realistic source, and those photos must stay out of the
repository (the public-export rule).

---

## 8. Decisions, proposed

| # | Decision | State |
|---|---|---|
| **PT-D1** | **A pet is named per photo, like a person** (IN-D5): a `MediaLabel`, `ModelProvenance.User`; templates device-local in their own store; clusters are scaffolding | proposed |
| **PT-D2** | **Its own capability, `PET_RECOGNITION`**, two switches, off by default, Settings-only for v1; withdrawal deletes templates and keeps names; not Article 9 (§4) | proposed |
| **PT-D3** | **One Collections row, *People & pets*, titled so only when pet recognition is on.** People and pets are mixed, named first, and use the same circles and *View all* grid (`photos-app.md` §35). A user with pets off sees *People*, exactly as today. ⚠ Desktop has no face detector yet (`tech-debt.md` *"People on desktop"*), so there the row holds pets and any names synced from other devices | proposed |
| **PT-D4** | **Vision's animal request on Apple, RF-DETR N/S elsewhere**, crops normalised, box parity measured (M6); RF-DETR via Core ML as the iOS fallback (§5.1) | proposed |
| **PT-D5** | **Cats and dogs only in v1**; other COCO animals detected and discarded | proposed |
| **PT-D6** | **Suggest, never auto-assign**, as People decided on 2026-09-22. The flow is F6c-s's *"Is this Mochi?"* | proposed |
| **PT-D7** | **Whole-body crops first**; face or fused crops only if M2 says so | proposed |
| **PT-D8** | **Things shows `CONTENT_TAGGING` labels only** (allowlist), shipped before any pet name is written (§3) | built (PT-1) |
| **PT-D9** | **The detector vetoes face boxes inside animal boxes in People** (§6), under face consent alone | proposed |

---

## 9. Phases

| # | Slice | Depends on |
|---|---|---|
| **PT-0** | Settle PT-D1…D9; ⚖️ §5.3's two licence calls | — |
| **PT-1** ✅ | Things → allowlist (PT-D8) — **built 2026-11-11**: `THING_CAPABILITIES` in `PhotoLabel.kt`, pinned by `LabelCollectionsTest`'s two capability tests (both fail against the old denylist) | — |
| **PT-2** | The detector: its own catalog artifact (ONNX, Android/desktop) and Vision's animal request on iOS; M1, M4. Ships **as People's non-human veto** (PT-D9, M5) | PT-D4, PT-D9 and ⚖️ §5.3's **COCO** call only (the embedder audit does not gate it) |
| **PT-3** | Pets end to end on Android + desktop: embedder, `pets.log`, clustering, naming, the *People & pets* row, Settings switch and its withdrawal hook. The **HR #9 bump** for the pet ops (§3), and the threat-model note beside A40 naming the new realm-writing op (A40 says `nameFaceGroup` is the only one today); M2, M3 | PT-1 shipped and spread, PT-2, ⚖️ the embedder audit |
| **PT-4** | iOS: Core ML embedder, the SwiftUI row (as People's F7); M6 | PT-3 |
| **PT-5** | Suggestions (*"Is this Mochi?"*): first F6c-s's label-anchored form, which needs no prototype; per-pet prototypes later, as F6c.3 plans them for people | PT-3 |

⚠ **Not in this doc:** other species, pets in Mia's answers beyond what label search already gives,
and linking a pet to anything outside Photos.
