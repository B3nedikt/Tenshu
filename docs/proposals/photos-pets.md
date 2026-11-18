# Photos — Pets: naming your cat like you name a person

**Status:** v0.5 — **PT-1 (the Things allowlist, §3), PT-2a/2b (the animal veto: Vision on iOS, RF-DETR on
Android, §10–§10.1) are built; PT-2c turned the veto ON for Android (§10.3)**, effective on a device once it
downloads the *cat and dog filter* row. iOS stays off by measurement (§10.4: Vision reports no animal faces). ⚖️ The COCO call was
accepted 2026-11-17 (§5.3). Decisions PT-D1…PT-D9 are proposed (§8), and
⚖️ the embedder is decided: **DINOv2-S zero-shot**, after the audit failed the candidate's training data (§5.3, PT-D10, 2026-11-18). Opened 2026-11-11 from a maintainer request (*"compared
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
| **open-noodle `pet-recognition-small`**: frozen DINOv2-S (Apache-2.0) + a linear projection to 512-d, 224 px, ONNX | Apache-2.0. Training data **claimed** to be Dogs-World (CC0) + Cat Individual Images (CC BY), with PetFace, Avito and MegaDescriptor data explicitly excluded | ✕ **Rejected 2026-11-18** (§5.3.3): Dogs-World is scraped from adoption sites. Held-out: dogs EER 0.068; cats 0.065 (**102 identities only**); worse than zero-shot on an unseen set |
| **DINOv2-S zero-shot**: the frozen backbone's embedding, no pet training data | Apache-2.0, code and weights; LVD-142M is Meta's web crawl, the COCO question again (§5.3.2) | ⭐ **Chosen 2026-11-18 (PT-D10)**, gated on M2. Candidate's own numbers: dogs EER 0.098, cats 0.099, unseen DogFaceNet **0.046** |
| **Our own projection head** on DINOv2-S | Ours, but only on **consented** photos: no clean public identity set exists (§5.3.3) | Later, if M2 shows zero-shot short. Training it on Dogs-World would be worse than shipping the rejected candidate |
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

   ⚖️ **Decided 2026-11-17 by the maintainer, acting as counsel: accept COCO-trained detector weights.**
   The reasoning, so it can be revisited rather than re-derived:
   - What we redistribute is **weights**, released by their author under Apache-2.0. They contain no
     COCO photograph and cannot reproduce one: a detector this size learns box statistics for 80
     categories, not images. Whether weights are a derivative work of their training images is
     unsettled, and the photos' NonCommercial terms attach to the photos.
   - The training was the author's (Roboflow, US), not ours. Training on public photos is argued as
     fair use in the US, and in the EU text-and-data mining for any purpose is permitted unless the
     rightholder opted out (DSM Directive Art. 4). ⚠ Neither is settled for model weights; that is
     the residual risk this decision accepts.
   - The detector outputs only "cat/dog here", which is used to *drop* boxes and never shown or stored
     as content; nothing COCO-derived reaches a user.
   - It is the industry's standing practice, which is not a licence but does mean the risk is shared
     and has not been litigated against a model of this kind.
   - ⚠ **The fallback stays documented**: if this is ever challenged, swap in an Open Images-trained
     detector (above). The per-artifact `licence` field records the decision on the catalog entry.
   The calibration-table question in `models.md` §3.10.1 is the same question at a smaller scale and
   takes the same answer.

3. **⚠⚠ The embedder audit — done 2026-11-18, and the candidate's training data FAILS it.** Verified
   against primary sources (Kaggle's dataset metadata, the model repo, the training code):
   - **The model** (`open-noodle/pet-recognition-small`, HF commit `9dd4c915…`, by Pierre Marais) is
     licensed Apache-2.0 **by front-matter tag only**: there is no LICENSE file.
     - Its training code (`open-noodle/gallery`, `machine-learning/pet-recognition-training/`, AGPL-3.0,
       irrelevant unless reused) reads exactly Dogs-World and Cat Individual Images, with DogFaceNet as
       eval only. That is consistent with the card.
     - ⚠ The code was committed six weeks after the weights were uploaded, and nothing ties the ONNX to
       a revision. So the data claim is consistent with the code, not proven by it.
   - ✕ **Dogs-World.** Kaggle metadata, verbatim: *"The data was scraped from the following Pet adoption
     websites"* (adoptapet.com, petrescue.com.au, rspcaqld.org.au). The scraper could not dedicate
     shelters' and volunteers' photos to CC0; the label covers his compilation at most.
   - ⚠ **Cat Individual Images** (CC BY 4.0): mostly shelter photos taken by the collector, but *"the
     remaining images were collected from social media"*. Those rights are not the uploader's, and they
     are not separable.
   - ✓ **DINOv2-S**: Apache-2.0 for code **and** weights since 2023-08-31. Its LVD-142M training set is
     web-crawled by Meta: the COCO question again, already accepted (2. above). ⚠ Pin `dinov2-small`;
     XRay-DINO and Cell-DINO in the same repo are non-commercial.
   - ✕ **No clean fallback dataset exists.** DogFaceNet is "retrieved from the web",
     WildlifeReID-10k inherits 37 sources' terms, and PetFace is research-only. Training our own head
     on Dogs-World is **worse** than shipping the candidate: then we are the party that trained on
     scraped photos, which is exactly what the COCO reasoning (2.) leaned on not being.

   **The candidate's own numbers** (its `metrics.json`, EER, lower is better):

   | Test set | DINOv2-S zero-shot | Trained projection |
   |---|---|---|
   | dogs | 0.0975 | 0.0675 |
   | cats (102 identities) | 0.0987 | 0.0655 |
   | DogFaceNet (unseen) | **0.0457** | 0.0547 |

   The projection buys ~0.03 EER on its own data's held-out split and **loses** on an unseen set.

   **Recommendation: DINOv2-S zero-shot, no pet training data at all**, with the decision taken on M2
   (§7, a same-breed pair in one home). ⚖️ **Decided 2026-11-18 by the maintainer: DINOv2-S zero-shot (PT-D10).**
   - ⚠ This doc's own objection to SigLIP (§5.2: general features match breed, not the individual)
     applies to DINOv2 in kind, if less in degree. That is exactly what M2 measures, and if M2 fails,
     the feature waits.
   - The clean way to a trained head is **consented** photos: the maintainer's own pets, or an explicit
     opt-in donation flow. Never a scrape.
   - Accepting the candidate anyway is a ⚖️ counsel call weaker than COCO's. Here the dataset's own
     publisher says the photos were scraped, and the artifact exists only to match identities. If
     taken, the conditions are: pin the SHA-256, record "Dogs-World (scraped; CC0 label) + Cat
     Individual Images (CC BY 4.0)" in the entry's `licence`, ship the cat set's attribution, and keep
     zero-shot as the documented swap.
   - The questions for the author, if the candidate stays on the table: which commit and dataset
     versions produced `9dd4c915…`; whether any other images were used in training, tuning or
     selection; whether the Dogs-World rights were assessed before calling the model *"safe for
     commercial use"*; and whether a LICENSE file and dataset attribution will be added.

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
| **PT-D4** | **Vision's animal request on Apple, RF-DETR N/S elsewhere**, crops normalised, box parity measured (M6); RF-DETR via Core ML as the iOS fallback (§5.1) | proposed; Apple half built (PT-2a) |
| **PT-D5** | **Cats and dogs only in v1**; other COCO animals detected and discarded | proposed |
| **PT-D6** | **Suggest, never auto-assign**, as People decided on 2026-09-22. The flow is F6c-s's *"Is this Mochi?"* | proposed |
| **PT-D7** | **Whole-body crops first**; face or fused crops only if M2 says so | proposed |
| **PT-D10** | **The embedder is DINOv2-S zero-shot**, no pet training data; the open-noodle candidate is rejected (Dogs-World is scraped, §5.3.3). A trained head only ever on consented photos | ⚖️ **decided 2026-11-18** by the maintainer as counsel |
| **PT-D8** | **Things shows `CONTENT_TAGGING` labels only** (allowlist), shipped before any pet name is written (§3) | built (PT-1) |
| **PT-D9** | **The detector vetoes face boxes inside animal boxes in People** (§6), under face consent alone | built on iOS (PT-2a), ⚠ off until M5 |

---

## 9. Phases

| # | Slice | Depends on |
|---|---|---|
| **PT-0** | Settle PT-D1…D9; ⚖️ §5.3's two licence calls | — |
| **PT-1** ✅ | Things → allowlist (PT-D8) — **built 2026-11-11**: `THING_CAPABILITIES` in `PhotoLabel.kt`, pinned by `LabelCollectionsTest`'s two capability tests (both fail against the old denylist) | — |
| **PT-2a** ✅ | The shared veto (`AnimalVeto`) and Vision's animal request on iOS, behind an off-by-default switch — **built 2026-11-17**, §10 | — |
| **PT-2b** ✅ | The Android detector: RF-DETR Nano int8 over ONNX, wired into `MediaPipeFaceBackend` behind the same switch with its own pipeline stamp — **built 2026-11-17**, §10.1. ⚠ Side-loaded only; the catalog entry moves to PT-2c | ⚖️ §5.3's COCO call — accepted 2026-11-17 |
| **PT-2c** | ⭐ **Android ON 2026-11-18** (§10.3): M5/M4 passed (§10.2), catalog entry + store locator built, veto passed in `PlatformProxyModule`. Catalog **published** 2026-11-18. **iOS measured 2026-11-18 and left OFF** (§10.4: Vision does not report the animal faces, so the veto removes nothing). Otherwise: **Turn the veto on** where M5 passes: a graph passes `AnimalVeto.Proposed` (or the measured numbers), which re-indexes that platform's faces (§10). Before it: the iOS face preflight learns the animal request, and Android's detector gets its **catalog entry** and store locator (§10.1) | M5 on that platform; M4 on a phone |
| **PT-3** | Pets end to end on Android + desktop: embedder, `pets.log`, clustering, naming, the *People & pets* row, Settings switch and its withdrawal hook. The **HR #9 bump** for the pet ops (§3), and the threat-model note beside A40 naming the new realm-writing op (A40 says `nameFaceGroup` is the only one today); M2, M3 | PT-1 shipped and spread, PT-2, M2 on DINOv2-S zero-shot (PT-D10) |
| **PT-4** | iOS: Core ML embedder, the SwiftUI row (as People's F7); M6 | PT-3 |
| **PT-5** | Suggestions (*"Is this Mochi?"*): first F6c-s's label-anchored form, which needs no prototype; per-pet prototypes later, as F6c.3 plans them for people | PT-3 |

⚠ **Not in this doc:** other species, pets in Mia's answers beyond what label search already gives,
and linking a pet to anything outside Photos.

---

## 10. PT-2a — as built (2026-11-17)

Scoped by the maintainer to **iOS plus the shared veto**, with the veto **off until M5**. Android's
detector is PT-2b, behind ⚖️ the COCO call.

**Shared (`inference-service-proxy` commonMain, `AnimalDetection.kt`).**
- `AnimalSpecies` (`CAT`, `DOG`) and `DetectedAnimal`: a top-left fractional box, `FaceBox`'s
  convention, with the detector's confidence that it is a cat or dog at all.
- `AnimalVeto(minAnimalConfidence, minFaceInside)`: a face is vetoed when at least `minFaceInside` of
  **the face's own area** lies inside one animal box at or above `minAnimalConfidence`.
  ⚠⚠ Over the face, never IoU: a small face wholly inside a large dog box has a tiny IoU, and an IoU
  rule would never veto the case the veto exists for. `AnimalVeto.Proposed` = 0.5 / 0.8, ⚠ unmeasured.
- `FaceDetectionOutcome.Detected.insideAnimals` carries how many faces a backend's veto removed, and
  the pass report prints it as `facesInsideAnimals=` (`IndexPassResult.facesInsideAnimals`). The
  vetoed faces are not in `facesFound`. A veto leaves the photograph **done**: it is a fact about that
  face, like `notAFace`, not a failure.
- ⚠ There is **no `InferenceBackend.detectAnimals`** yet. The veto runs inside each face backend,
  where the decoded image already is; PT-3, the first caller that needs animals on their own, adds
  the seam.

**iOS (`VisionCoreMlFaceBackend`).**
- `VisionAnimalDetector` / `SystemVisionAnimalDetector`: `VNRecognizeAnimalsRequest` pinned at
  revision 2 (`FaceModel.VISION_ANIMALS_REVISION`), over the same pixel-buffer route as the face
  detector (a CGImage finds nothing on the simulator). An observation's species is its top label;
  any other label is dropped (PT-D5).
- The backend takes `animalVeto: AnimalVeto? = null`. ⚠⚠ **Null in every shipping graph**
  (`PhotosOnDeviceAi.kt` passes nothing), so nothing a user runs changed.
- On, the backend stamps **`FaceModel.VISION_ANIMAL_VETO_PIPELINE_VERSION` = 101** instead of 100.
  ⚠⚠ That is the trap for PT-2c: the template store clears on a stamp change, so turning the veto on
  re-indexes every iOS library's faces, and turning it off re-indexes them back. Names are labels and
  survive both. A new threshold is a new number.
- The animal request runs **only when Vision found a face**, so a faceless photograph costs nothing.
  ⚠⚠ If the animal request throws or reports another revision, the photograph is `Failed`, never
  "keep every face": the stamp says the veto ran.
- ⚠ The veto runs after `facesOf`'s ten-largest cap, so a photograph with more than ten faces can keep
  fewer than ten after a veto. Rare; revisit only if M1 shows it.
- ⚠ The face preflight (`CoreMlFacePreflightModels`) does not exercise the animal request. PT-2c owes it.

**Measured so far.** On the build Mac's simulator, the real request runs at the pinned revision in
~70 ms warm (128 ms first) on the bundled face probe and finds no animal in it
(`VisionCoreMlFaceRealTest`). That is no phone timing (M4), and no recall (M1): no animal photograph
is committed, and per §7 none of the maintainer's may be.

**Tests.** `AnimalVetoTest` (geometry), `MediaIndexerFacesTest.facesInsideAnimalsAreCountedAndThePhotoIsStillDone`
(the fake's `^` token is a vetoed face), the `on…`/`off…` cases in `VisionCoreMlFaceBackendTest`, and
both stamps pinned in `FaceModelTest` and `FaceDetectionStrategyStampTest`. The three iOS guards
(throw → `Failed`, revision → `Failed`, no faces → not asked) were each broken once and seen red.

**What M5 needs before PT-2c.** A device run over the verified People library with the veto on: how
many of the non-faces in People §12.15 it removes, and **every** removed face checked by eye. One real
person lost fails it.

### 10.1 PT-2b — as built (2026-11-17)

**The artifact.** RF-DETR **Nano** (Apache-2.0 code and weights, COCO-trained; ⚖️ §5.3 accepted),
exported by `scripts/inference/export-animal-detector.py` under `requirements-animal-detector-export.txt`
(Python 3.13, rfdetr 1.8.3, torch 2.15.0). The script pins the upstream checkpoint
(`checkpoint_best_regular.pth`, sha256 `d8d6b9ee…`), checks the export's interface, quantises, scores and
writes `export-manifest.json` (the 2026-11-17 run's is kept in `scripts/inference/runs/2026-11-17-animal-detector/`). Re-running it rebuilds the same bytes.
- Interface: `input` [1, 3, 384, 384] → `dets` [1, 300, 4] (normalised cx, cy, w, h) and `labels`
  [1, 300, 91]. ⚠⚠ The 91 logits are **COCO category ids** (cat 17, dog 18), not the 80-class index.
- **int8, static QDQ, per-tensor, `MatMulConstBOnly`**: 28.8 MB against fp32's 108 MB, sha256
  `e3523f75…` (`AnimalDetectorModel.RfDetrNanoInt8`). ⚠ Per-channel fails onnxruntime's CPU
  `QLinearMatMul` on the attention MatMuls; `quantize_dynamic` is out for `ConvInteger`.
- Calibrated on 200 images of one seeded half of COCO val2017 (seed `20261117`), scored on the other
  2 547.

**The preprocessing is shared, and pinned to the export.** `AnimalDetectorInput.resize` is an
antialiased bilinear (a triangle filter whose support grows with the shrink, as torchvision's
antialiased resize that RF-DETR trained on), then ImageNet mean/std, CHW.
- ⚠⚠ Shared commonMain code, not `createScaledBitmap`: from a 2048-px decode, plain bilinear aliases.
  A box filter was measured and cost recall (cat 0.909 → 0.873 at 0.5).
- `AnimalDetectorInputGoldenTest` pins it pixel for pixel to the export's `resize()`, which calibration
  and the self-check both used.

**Measured (held-out half, image-level, confidence ≥ 0.5).**

| | cat recall / precision | dog recall / precision | per image |
|---|---|---|---|
| fp32, export (ORT 1.31, numpy) | 0.945 / 0.990 | 0.867 / 0.944 | 54 ms, Mac CPU ×4 |
| **int8, export (ORT 1.31, numpy)** | 0.909 / 1.000 | 0.857 / 0.933 | 62 ms, Mac CPU ×4 |
| **int8, app runtime (ORT 1.22, Kotlin, JDK JPEG)** | 0.945 / 1.000 | 0.857 / 0.894 | 93 ms incl. decode, Mac |

The app-runtime row is `OnnxAnimalDetectorRealTest` (`-Dtenshu.inference.animalDetector`, `-Dtenshu.inference.coco`),
held to the same bars as the export (cat ≥ 0.88 / 0.95, dog ≥ 0.80 / 0.86). ⚠ COCO is not a home
camera roll (M1), and no number here is a phone's (M4).

**The backend.** `OnnxAnimalDetector` (onnxMain, so the JVM test runs the Android code path) behind
`PixelAnimalDetector`; `MediaPipeFaceBackend(animalVeto = …, animalDetector = …)`:
- The detector sees the **same upright decode** the faces were found on, so face and animal boxes
  share one frame.
- It runs only when a face was found. If it cannot run, the photograph is `Failed`, never "keep every
  face".
- ⚠ *(Superseded by §10.3.)* As built here, with the veto on and no detector file `supports()` was
  false. PT-2c changed that: without the file the backend runs the plain pipeline under the plain stamp.
- ⚠⚠ The stamp with the veto on is `FaceDetectionStrategy.animalVetoPipelineVersion` =
  `pipelineVersion + ANIMAL_VETO_OFFSET` (50): **72** for the shipped `TwoStage(2)`, against 22 off.
  Turning it on re-indexes the library's faces. ⚠ A fifth plain revision would collide (51–54), and
  `FaceDetectionStrategyStampTest` says so.
- ⚠⚠ **Off, and not even constructed**: `PlatformProxyModule` passes neither argument.

**Delivery: side-load only.** `OnnxModelFiles.animalDetectorLocator` finds
`inference/models/rfdetr-nano-coco-int8.onnx` by size. There is **no catalog entry** yet, on purpose:
- Adding a role to the AuraFace entry would not reach installed devices without a version bump, and
  that bump re-stamps every face template (`ModelSetupJoin` offers an update only on a higher version;
  `faceStoreLocator` refuses any other version).
- So the detector gets its **own** entry in PT-2c. ⚠ Its runtime must not be plain `onnx`, which
  desktop supports and would download for nothing; `onnx+mediapipe` keeps it Android-only.
  ⚠ `ModelCatalogBuilder` also needs `petRecognition` before the entry can list both capabilities (§4).

**Tests.** `AnimalDetectorInputGoldenTest`, `RfDetrOutputTest` (common), `OnnxAnimalDetectorRealTest`
(JVM, real model), `MediaPipeFaceAnimalVetoTest` (Robolectric: off/on, `Failed`, no-faces, `supports`,
stamp), `FaceDetectionStrategyStampTest`. The two Android guards (cannot run → `Failed`, no faces →
not asked) were each broken once and seen red.

### 10.2 M5 and M4 on Android — measured 2026-11-18

`AnimalVetoMeasurementTest` (androidDeviceTest; measure-only, veto off) on the maintainer's OnePlus
CPH2581, Android 16, over **the §12.15 set**: the 283 face photos' 1024-px previews with their
orientation manifest. It found the same **495 faces** §12.15 judged by eye.

**M5: passes, with a wide margin.**
- At `AnimalVeto.Proposed` (animal ≥ 0.5, face ≥ 80 % inside) the veto removes **3 faces**, and each
  was checked by eye: a cat in a garden (cat 0.79), a cat lying on a quay (0.94), a dog in a yard
  (0.91). **No person is removed.**
- The margin is all-or-nothing. Only 19 of the 495 faces had any cat or dog box in their photo; the
  3 removed lie wholly inside one, and the other 16 do not touch one at all (0 % inside, animal
  confidence 0.30–0.78). No face sits anywhere between, so the 0.8 bar is not near a person.
- ⚠ As §6 predicted, it is a **partial** fix. §12.15's other non-faces (a lucky-cat figurine,
  figures, a teddy bear, a mannequin, cartoons, a rose, a pacifier, pastries) are not cats or dogs,
  and they stay.

**M4: 333 ms median per photo with faces** (p90 367 ms, max 822 ms over 271 photos), detector only,
decode excluded. It runs only on photos where a face was found, inside the idle+charging index pass.

⚠ One library, one phone. The thresholds stay `Proposed`: nothing here argues for moving them, and
an all-or-nothing margin on 495 faces says nothing about how far either number could move.
⚠ Not run on iOS: Vision's face boxes differ from MediaPipe's, so §12.15's verdicts do not transfer,
and iOS needs its own by-eye pass before its veto turns on.

**What is left for PT-2c on Android:** the detector's catalog entry and store locator (§10.1), then
passing `AnimalVeto.Proposed` in `PlatformProxyModule`, which re-indexes every Android library's
faces once (stamp 22 → 72).

### 10.3 PT-2c on Android — the veto on (2026-11-18)

**The catalog entry.** `rfdetr-nano-coco-int8` v1 in `deploy/models/models.json`:
- Named *"People: cat and dog filter (RF-DETR Nano)"*, capability `faceClustering`, one
  `animalDetector` artifact pinned to the export's sha256 and size.
- Its own entry, **never a third role on AuraFace's**: that entry reaches installed devices only through
  a version bump, which re-stamps every face template (§10.1).
- Runtime `onnx+mediapipe`, which only Android declares. iOS and desktop drop the entry at parse
  (`AnimalDetectorCatalogEntryTest`).
- Checked against the builds already installed: on Android it is **one more row in Settings → Models,
  downloaded only when the user taps it**. Every model lookup is by id (download, update, delete, the
  face locator), so it cannot shadow AuraFace. An unknown role passes every layer untouched, and
  withdrawing face consent deletes templates, never models.

**The locator.** `OnnxModelFiles.animalDetectorStoreLocator`, `faceStoreLocator`'s twin: the installed
record first, the side-load second, resolved per call. ⚠ Another installed version, or a file of the
wrong size, is `null`, never the side-load.

**The switch.** `PlatformProxyModule` passes `AnimalVeto.Proposed` and an `OnnxAnimalDetector` over that
locator. ⚠⚠ **The veto engages only while the detector is present**:
- `MediaPipeFaceBackend.provenance` is now read per call: 72 while the detector is there, 22 when it
  is not.
- So People keeps working, unchanged, on every device that has not downloaded the filter.
- Downloading the filter moves the stamp, and the indexer and `faceGroups` clear and **re-index the
  faces once** with the veto. Removing it does the same in reverse. Typed names are labels and
  survive both.
- The rule that replaced §10.1's: no file means the plain pipeline under the plain stamp. A file that
  is there but will not *run* is `Failed`, because the stamp then says the veto ran.
- ⚠ A pass straddling the download can read one stamp and detect under the other. The store refuses
  the mismatched write, and the next pass clears and redoes it.

⚠ Two side effects, both small and both accepted:
- Installing the filter while People is off makes the Models screen offer to turn People on
  (`ModelsViewModel.offerAfterDownload`).
- A device holding only the filter counts as "a model is installed" in the indexing copy.

**Published 2026-11-18** (`docs/how-to/models-pipeline.md` §3–§4, signed and uploaded by the maintainer):
- Before signing, the live catalog matched the tree's manifest except for the new entry. Every live
  artifact was re-staged and checked against its live sha256, so the re-signed catalog changes only
  this entry.
- Checked from outside: `catalog.json` and its `.sig` are byte-identical to the signed pair; the
  detector serves `206` to a Range request with `immutable`; its bytes hash to `e3523f75…`.

**What is NOT done:**
- Nothing downloads the filter for a user. Offering it from People itself is a later UX decision.
- No installed build has yet run the veto end to end: download the filter on a device, then watch the
  re-index and the pass report's `facesInsideAnimals`.

### 10.4 M5 on iOS — measured 2026-11-18: nothing to veto, so the veto stays off

`VisionAnimalVetoMeasurementTest` (iosTest, measure-only, through the product's
`VisionCoreMlFaceBackend` and `IosFaceImageDecoder`) on the iOS simulator. It ran over **the same 283
previews** as §10.2, pulled from the phone, with their orientation manifest. The previews stay in
`~/tenshu-models`, never the repository. `scripts/inference/draw-veto-removals.py` draws what a pass
would remove, for the by-eye check.

**Result:**
- Vision finds **676 faces** in 251 photos, against MediaPipe's 495.
- At `AnimalVeto.Proposed` the veto removes **0**, and the product backend with the veto on agrees (0).
- Only 7 faces had any cat or dog box in their photo (two photos, Vision's animal confidence 0.63), and
  none touches it (0 % inside). **No person is at risk.**
- ⭐ **The reason is upstream.** The three animal faces the Android veto removes (§10.2: two cats, one
  dog) are **not faces to Vision at all**: it reports 0 faces in each of those photos. Vision's face
  detector already rejects them, so on this library the iOS veto has nothing to do.

**Decision: iOS stays OFF.** Turning it on would cost every iOS library a face re-index
(stamp 100 → 101) and an animal request per photo with a face, for no measured gain. M5's bar (no person
lost) is met, but it is met by doing nothing. Revisit if an iOS library shows a cat or dog grouped as a
person, or when PT-3 runs the animal request anyway for pets.

⚠ **Not measured:**
- §12.15's other non-faces (figurines, pastries…) on Vision. This pass asked only about cats and dogs.
- The phone: the simulator runs iOS's Vision on the Mac's hardware (`ios-face-grouping.md` §11.3). A
  device run would only confirm a zero.

### 10.5 The Android veto on a real device — 2026-11-18

Control built from master (`3e786dd`, the veto on) was installed over the maintainer's OnePlus CPH2581,
data kept. No stored schema had moved since the other apps' install the day before, so Control alone
stayed in lockstep. Then the filter was downloaded from Settings → Models, and *Index now* run.

- The filter landed in the model store as `rfdetr-nano-coco-int8.animalDetector.e3523f75….model`
  (28,806,262 B), 12 s before the pass started.
- ⭐ **The switch works as designed.** The face store's header now reads `auraface-v1-int8@1/72`: the
  detector's arrival moved the stamp from 22 to 72, the old templates were cleared, and the pass
  re-indexed the whole library under the veto pipeline. No failures, no latch.
- The pass report: `visited=79 facePhotos=79 facesFound=4 facesEmbedded=4 facesNotHuman=0
  facesInsideAnimals=0`.
- ⚠ **It proves the wiring, not the removal.** This realm's library is now 79 photos (store stock
  photos, a Sintra trip, four cat photos), not the 283-photo M5 set. MediaPipe found **no face at all**
  in the four cat photos, so there was nothing to veto, and nothing the veto should have removed was
  kept. The removal itself is measured only by the harness (§10.2), on the same phone and model.

