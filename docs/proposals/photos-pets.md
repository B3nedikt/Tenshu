# Photos — Pets: naming your cat like you name a person

**Status:** v0.5 — **PT-1 (the Things allowlist, §3), PT-2a/2b (the animal veto: Vision on iOS, RF-DETR on
Android, §10–§10.1) are built; PT-2c turned the veto ON for Android (§10.3)**, effective on a device once it
downloads the *cat and dog filter* row. iOS stays off by measurement (§10.4: Vision reports no animal faces). ⚖️ The COCO call was
accepted 2026-11-17 (§5.3). Decisions PT-D1…PT-D9 are proposed (§8), and
⚖️ the embedder is decided: **DINOv2-S zero-shot**, after the audit failed the candidate's training data (§5.3, PT-D10, 2026-11-18). ⚠⚠ **Its first M2 run fails (§10.6, 2026-11-19): it merges two littermate tabbies, and same-coat strangers.** A second run (§10.7) showed a cat's own photos spread wider than the littermate gap, and DINOv2-Base does no better (§10.8). ⚖️ **So M2's bar is amended to *no automatic merge* (2026-11-19, PT-D11): DINOv2-S, auto-grouping only at cosine ≥ 0.85, crops overlapping another animal skipped, everything else suggest-and-confirm** — it passes on today's set; ⚠ a best guess from one session, re-measured on cross-day photos. ⭐ **PT-3 is built end to end on Android + desktop (2026-11-19)**: device side §10.9, pet ops + inference v2 §10.10, Settings switch + published catalog §10.11, the *People & pets* row §10.12. iOS is PT-4. Opened 2026-11-11 from a maintainer request (*"compared
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
  ⚠⚠ An index pass re-checks the withdrawal **after every template write**, in both halves. Consent
  is read before the model runs. A `petGroups`/`faceGroups` read that sees the switch-off in the
  meantime may already have cleared the store, and an unchecked write would recreate it. No lock is
  needed: a clear that ran before the write saw the withdrawal first, so the read after the write
  sees it too.
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
| **DINOv2-S zero-shot**: the frozen backbone's embedding, no pet training data | Apache-2.0, code and weights; LVD-142M is Meta's web crawl, the COCO question again (§5.3.2) | ⭐ **Chosen 2026-11-18 (PT-D10)**, gated on M2. Candidate's own numbers: dogs EER 0.098, cats 0.099, unseen DogFaceNet **0.046**. ⚠⚠ **First M2 fails** (§10.6): littermate tabbies at cosine 0.81, above a same-cat median. ⭐ Passes the amended bar at a 0.85 grouping cut (§10.8, PT-D11) |
| DINOv2-B zero-shot | Apache-2.0, as Small | ✕ **Measured 2026-11-19 (§10.8)**: better on strangers (EER 0.055 vs 0.074), no better within the household (littermates still 0.81), ~4× the size |
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
| M2 | Embedder separation **within one household**: 2–3 pets, including a same-breed pair, against strangers' animals (walks, other people's photos) | ⚖️ **Amended 2026-11-19 (PT-D11, §10.8): no *automatic* merge** — a grouping cut at which no two of the household's own pets share a group. Below it nothing is merged without the user (PT-D6). Splitting is recoverable and merging is not (People §10 Q2). ⚠ The original bar — one cut with no merge *and* few splits — no zero-shot embedder met (§10.6–§10.8) |
| M3 | Threshold and `minPhotos`, chosen from M2 | Recorded with the run that chose it. ⚠ Provisional grouping cut **0.85** (§10.8), from one session |
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
| **PT-D4** | **Vision's animal request on Apple, RF-DETR N/S elsewhere**, crops normalised, box parity measured (M6); RF-DETR via Core ML as the iOS fallback (§5.1) | Apple half built (PT-2a) for the face veto; ⭐ **for Pets the fallback is taken (§10.13): M6 failed Vision's boxes, so iOS Pets run RF-DETR via Core ML** |
| **PT-D5** | **Cats and dogs only in v1**; other COCO animals detected and discarded | proposed |
| **PT-D6** | **Suggest, never auto-assign**, as People decided on 2026-09-22. The flow is F6c-s's *"Is this Mochi?"* | proposed |
| **PT-D7** | **Whole-body crops first**; face or fused crops only if M2 says so | proposed |
| **PT-D10** | **The embedder is DINOv2-S zero-shot**, no pet training data; the open-noodle candidate is rejected (Dogs-World is scraped, §5.3.3). A trained head only ever on consented photos | ⚖️ **decided 2026-11-18** by the maintainer as counsel; ⚠⚠ its first M2 failed (§10.6); ⭐ **re-decided 2026-11-19** under the amended M2 (PT-D11) after DINOv2-B did no better (§10.8) |
| **PT-D11** | **Best-guess grouping**: auto-group at cosine **≥ 0.85** (DINOv2-S CLS), never below; **skip a crop whose box overlaps another animal's**; between two named pets whose scores are close, ask rather than suggest one; M2 judged as *no automatic merge* | ⚖️ **decided 2026-11-19** by the maintainer (*"go ahead with best guesses"*); ⚠ provisional — re-measure on cross-day photos, and the ambiguity margin is PT-5's to set |
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
| **PT-3** | Pets end to end on Android + desktop: embedder, `pets.log`, clustering, naming, the *People & pets* row, Settings switch and its withdrawal hook. The **HR #9 bump** for the pet ops (§3), and the threat-model note beside A40 naming the new realm-writing op (A40 says `nameFaceGroup` is the only one today); M2, M3. ⭐ PT-1's spread confirmed by the maintainer 2026-11-19 (every device on a recent build). In three slices: | PT-1 shipped and spread, PT-2, an embedder that passes M2 — ⭐ DINOv2-S at PT-D11's 0.85 grouping cut passes the amended bar (§10.8) |
| **PT-3a** ✅ | The device side, no wire change — **built 2026-11-19**, §10.9: `PET_RECOGNITION`, the DINOv2-S descriptor + export script, PIL-exact crop, PT-D11's selection, `pets.log`, the indexer's fourth half, Android + macOS wiring. ⚠ Inert until consent can be granted (PT-3c) and the embedder is installed or side-loaded | — |
| **PT-3b** ✅ | The pet ops (groups, naming, rejections) — the **HR #9 bump** of `:inference-service` (v1 → v2) with its first `SchemaMigration` — and the A40 threat-model note — **built 2026-11-19**, §10.10 | PT-3a |
| **PT-3c** ✅ | The Settings → Models switch (*Recognise your pets*) with its delete-on-off copy, and the catalog entries for the embedder and the detector — **built 2026-11-19**, §10.11. ⚖️ The maintainer publishes the catalog | PT-3b |
| **PT-3d** ✅ | The *People & pets* row in Photos — pet groups, naming, the row's title — and Photos' permission rows for the two ops it calls — **built 2026-11-19**, §10.12 | PT-3c |
| **PT-4** | iOS: Core ML embedder, the SwiftUI row (as People's F7); M6 | PT-3 |
| **PT-4a** ✅ | M6 measured (§10.13): Vision's boxes fail parity, RF-DETR as Core ML passes; `export-coreml-pets.py` builds both iOS models reproducibly — **2026-11-19** | PT-3 |
| **PT-4b1** ✅ | The Core ML runners: multi-output sessions, `CoreMlAnimalDetector` + `CoreMlPetEmbedder`, the iOS descriptors — **built 2026-11-19**, §10.14 | PT-4a |
| **PT-4b2a** ✅ | The inference-side pieces: `ReadinessSlot.PETS`, the pets preflight, the realm custody's clear of `pets.log`, the iOS readiness and `CoreMlPetBackend` — **built 2026-11-19**, §10.15 | PT-4b1 |
| **PT-4b2b** ✅ | The wiring into Photos' graph: the owned pets store (after-prior-writer, relinquish, wipe, unowned), custody + readiness + preparer + preflight bound, the indexer and pet ops, withdrawal through `JoinedModelSource`, the processing wake, closes on teardown, the build flag — **built 2026-11-19**, §10.16 | PT-4b2a |
| **PT-4c** ✅ | iOS Settings → Models (the pets switch, a row per pet model, the immediate withdrawal hook), the pets copy, the SwiftUI *People & pets* title, and the two `coreml` catalog entries (published 2026-11-19) — **built 2026-11-19**, §10.17 | PT-4b2b |
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

### 10.6 M2 on DINOv2-S zero-shot — first run 2026-11-19: ⚠⚠ it merges littermates

`scripts/inference/m2-pet-embedding.py` over two sets, both kept on the maintainer's Mac and never
committed: **strangers** from Wikimedia Commons (`fetch-commons-pets.py`), and **the household**, the
maintainer's own `/Cats/` folder. Detector: the shipped RF-DETR Nano int8. Crop: the most confident box,
square × 1.15, 224² bicubic (PT-D7's whole body). Embedding: DINOv2-small (revision `ed25f3a3…`) as
ONNX, `[CLS ‖ patch mean]`.

**Strangers: 8 named Commons cats, 81 photos, 64 scored.** Commons' individual-animal categories are
noisy: of the names the filter passed, statues (Trim, Filemon), drawings (Foss), Dr. Seuss, a breeder's
several cats (Alpha) and a mislabelled photo of Gli were removed by eye; the script's `EXCLUDED` keeps
them out of a re-run. Wikimedia throttles an anonymous client hard (HTTP 429, then minutes of refusals),
so the set stopped at 8 cats and no dogs.

| Variant | EER (cut) | AUC | Miss at 1 % false match (cut) | Same / different median |
|---|---|---|---|---|
| **CLS** | **0.074** (0.516) | 0.978 | 0.113 (0.577) | 0.743 / 0.314 |
| patch mean | 0.199 | 0.887 | 0.508 | 0.795 / 0.479 |
| CLS + mean | 0.133 | 0.956 | 0.254 | 0.761 / 0.393 |

- ⭐ **The CLS half wins**; the patch mean pulls different cats together. Consistent with the
  candidate's own zero-shot cats EER (0.099).
- ⚠ Flattering: many photos are bursts of one moment (rank-1 is 1.0 for it), and 8 visibly different
  coats give 28 easy pairings.
- Detector: of 17 misses, 11 had no living cat to find (Mikan's tram livery, a Lego model, a screenshot,
  a sign, a goldfinch, a cathedral wide shot); **~6 of ~70 real cat photos missed, mostly tight close-ups
  and a cat held in arms**. Every scored crop was the right cat. An M1 note: phone pet photos are often
  close-ups.

**Household: 4 photos, 4 cats** — a tortoiseshell, a grey-and-white kitten, and **two tabby littermates,
different animals** (confirmed by the maintainer). One photo per cat, so only *different*-pet pairs exist.

| Pair (CLS) | Cosine | At the strict cut, 0.577 |
|---|---|---|
| **Tabby littermate A / tabby littermate B** | **0.811** | ⚠⚠ **merged** — above the strangers' same-cat median (0.743) |
| Grey-and-white kitten / nearest stranger (Oiseau, grey-and-white) | 0.732 | ⚠ merged with a stranger |
| Tabby A, tabby B / nearest stranger (Gli, tabby) | 0.697, 0.639 | ⚠ merged with a stranger |
| Tortoiseshell / anything | ≤ 0.49 | kept apart |
| Pets of different coats / each other | 0.32 – 0.48 | kept apart |

⚠⚠ **M2 fails.** Three of four household cats merge with someone at the strict cut, and M2's bar is no
merge between the household's own pets. The pattern is one finding, not four: **DINOv2-S zero-shot
matches coat, not the individual** — §5.2's objection to SigLIP, in degree as well as kind. No global
cut separates the littermates: it would have to sit above 0.81, where most of the strangers' own
same-cat pairs split.

What is **not** known, and decides the next step:
- ⚠ **How a household pet scores against itself.** One photo per cat measures the littermates' gap, not
  each cat's own spread. Photos from one home share light and camera; if a tabby's own photos sit near
  0.9, a household cut, or nearest-named-example matching once the user has named a few (PT-5's
  prototypes), could still separate them. That needs **8–10 photos of each household pet** in a
  subdirectory per pet; `--household` already scores same-pet pairs from that layout.
- If they do not, the routes in order of cost: **DINOv2-Base** (Apache-2.0, ~4× Small) and higher
  input resolution, an evening with no training; a **cat-face crop** (PT-D7's later option), since coat
  pattern dominates a body crop; then **our own projection head** on consented photos (§5.3.3), whose
  blocker is data — many identities with many photos each, which one household cannot supply.
  MegaDescriptor stays out (CC BY-NC, §5.2).
- Splitting rather than merging stays the product's posture whichever route lands: a strict cut that
  splits a pet into groups is recoverable by naming; a merge is not (People §10 Q2).

### 10.7 M2, second household run — 2026-11-19: several photos per cat, one session

Same pipeline and strangers as §10.6, over 31 cat crops the detector found in the maintainer's photos:
five cats with several photos — **the two tabby littermates** (one in a box, one on cobbles; 9 and 4
photos), a white cat (9), a tuxedo (3), a black cat (2) — and four cats with one. ⚠ All of the
multi-photo cats come from **one burst at one feeding spot, 2026-10-19, within ten minutes**.

Two corrections to §10.6:
- ⚠ Two of its four household photos (`IMG_20261029_212510`, `IMG_20261029_212414`) are edited crops of
  burst frames — same capture time — so they were dropped as duplicates here.
- The littermate pair it measured (0.811) is the box tabby against the cobble tabby; the identities are
  unchanged.

| CLS cosine | Pairs | Lowest | Median | Highest |
|---|---|---|---|---|
| Cobble tabby / itself | 6 | 0.78 | 0.89 | 0.98 |
| Box tabby / itself | 36 | **0.36** | 0.77 | 0.97 |
| White cat / itself | 36 | 0.41 | 0.58 | 0.84 |
| **Littermates / each other** | 36 | 0.52 | 0.67 | **0.81** — 27 merged at the strict cut (0.577) |
| Tuxedo / white cat | 27 | — | 0.61 | **0.93** — 16 merged |

- ⚠⚠ **A cat's own spread is wider than the littermate gap, even inside one session.** The box tabby
  falls to 0.36 against itself (eyes shut, half hidden behind the box) — below most of its pairs with its
  sibling. So pose and occlusion move the score more than identity does, and **no household cut can
  separate the littermates**: §10.6's open question is answered no, before any cross-day photo.
- ⭐ Nearest-named-example is right for 22 of 27 photos and never confuses the tabbies — ⚠ but in a
  burst the nearest photo is the frame a second earlier, so this measures the burst's consistency, not
  recognition on another day. It is the one route still open, and it waits on photos of the tabbies from
  **different days, places and light**.
- ⚠ All five wrong nearest matches are tuxedo ↔ white at up to 0.93: the two ate side by side, and the
  ×1.15 square crop of one is half the other. Pets that huddle will do this in real libraries; a tighter
  crop, or a mask from the detector, is part of any later embedder test.
- ⚠ **`--household` trusts a named folder for every box in it.** Fed crops of crowded frames, it
  re-detects inside each crop and can lock onto the neighbouring cat, which produced a false 0.97
  tuxedo/white pair. The table above pins each crop to its original box with a one-off script; a
  household folder should hold whole photos of one animal each.
- A first look at dogs: four photos of a white puppy, taken minutes apart, score 0.42–0.76 against each
  other (lying front-on vs a sitting profile is the 0.42) and below 0.21 against every cat. No stranger
  dogs exist yet (§10.6), so no dog cut.

What this changes: the cheap routes in §10.6 (DINOv2-Base, higher resolution, a face crop) are now tests
of whether a model separates **pose from identity**, scored on this same set plus cross-day photos; the
trained projection head moves from "if needed" to the likely end state.

### 10.8 DINOv2-Base, and the best-guess bar — 2026-11-19

**DINOv2-Base zero-shot** (`facebook/dinov2-base`, exported to ONNX the same way, `[CLS ‖ patch mean]`
1536 wide; the script now splits the halves at the middle, and Small reproduces §10.6 exactly), CLS half,
over §10.6's strangers and §10.7's pinned household boxes:

| | Small | Base |
|---|---|---|
| Strangers: EER (miss at 1 % false match) | 0.074 (11 %) | **0.055 (7 %)** |
| Strangers: different-cat median | 0.31 | 0.20 |
| Littermates / each other, max | 0.81 | 0.81 |
| Box tabby / itself, median | 0.77 | **0.62** — worse |
| Nearest photo is the right cat | 22 / 27 | 19 / 27 |

⚠⚠ **A bigger backbone separates different-looking cats better and look-alikes no better**: it does
not separate pose from identity, at ~4× the size (346 MB). Small stays; resolution and face crops stay
untested, and so does a trained head, whose blocker is thousands of identities with several photos each
— no household supplies that, and the public pet re-ID sets are non-commercial (§5.2).

**The best-guess bar (PT-D11).** Asking the embedder for one cut with no merges *and* few splits is what
fails. Asking only for *no automatic merge* — group when very sure, leave the rest to naming and to
PT-D6's suggest-and-confirm — is met today. Grouping the household's 31 photos (9 cats) at each cut,
single-link, the linkage most prone to merging:

| Cut | Groups | Groups holding two cats |
|---|---|---|
| 0.58 (strict, 1 % strangers) | 2 | one group of 8 cats |
| 0.70 | 8 | black + both tabbies; tuxedo + white |
| 0.80 | 13 | the littermates; tuxedo + white (×2) |
| **0.85** | **20** | **tuxedo + white (×2)** only |
| 0.90 | 24 | tuxedo + white (×2) only |

- ⭐ At **0.85** the littermates never share a group, and the user joins the ~20 groups by naming them —
  the recoverable direction.
- ⚠ The two remaining mixes are crop contamination, not the embedder: the tuxedo and the white cat ate
  side by side, so each one's ×1.15 square crop is half the other. Hence PT-D11's rule to **skip a crop
  whose box overlaps another animal's**; a huddled pair then goes unindexed in that photo rather than
  merged.
- ⚠ Between named pets PT-D6 already only suggests; PT-D11 adds that when the two best named pets
  score close, the UI asks instead of suggesting one — the littermate case. The margin is PT-5's to set.
- ⚠⚠ **A best guess from one ten-minute burst.** Photos across days will spread each cat wider, which
  means more groups at 0.85, not merges — but that is a prediction. Re-run §10.7's comparison when
  cross-day photos of the tabbies exist, before PT-3's cut is final.

### 10.9 PT-3a — as built (2026-11-19): the device side

Everything is `:inference-service-proxy` plus the proxy graphs; no wire type moved.

- **`InferenceCapability.PET_RECOGNITION("petRecognition", offerableInOnboarding = false)`** (PT-D2). A
  plain enum, not a schema constant: no bump. Every reader tolerates it (catalog decode is `mapNotNull`;
  the Control UI's `when`s have `else`).
- **The embedder.** `PetEmbedderModel.DinoV2SmallZeroShot` pins `dinov2-small-zeroshot` v1 — SHA-256
  `b44ac8be…`, 88 441 580 bytes — built by `scripts/inference/export-pet-embedder.py` at revision
  `ed25f3a3…` with `requirements-pet-embedder-export.txt`. ⭐ The export is deterministic (two runs, one
  digest), and its outputs equal (max |Δ| 0.0) those of the artifact §10.6–§10.8 measured, whose bytes
  differed only in metadata. `OnnxPetEmbedder` runs it; `ModelArtifactRole.PET_EMBEDDER` names it;
  `petEmbedderStoreLocator` finds it installed-first, side-load (`dinov2-small-zeroshot.onnx`) second.
- **The crop is the M2 script's, byte for byte** (`PetCropInput`): the truncating square of the box's
  longer side × 1.15, then PIL's `BICUBIC` reproduced exactly — 22-bit fixed-point weights, 8-bit
  intermediate. `PetCropInputTest` pins it to Pillow's output hashes. ⚠ The margin is a Double:
  `1.15f` widened truncated an edge box a pixel short of Python's.
- ⭐ **Measured end to end on the app's runtime** (`OnnxPetEmbedderRealTest`, flags-only, 64 Commons
  cats): on the script's own box the Kotlin crop + embedder give its vectors at cosine **≥ 0.9992
  (median 1.0000)**. With our detector re-run on ImageIO's pixels rather than Pillow's: median
  **0.9948, min 0.85**. ⚠⚠ A box a few pixels off moves a template by up to 0.15 — at the 0.85 cut a
  split, the recoverable direction, and one more reason not to read a group as identity.
- **Selection** (`PetSelection.BestGuess`, PT-D11): confidence ≥ 0.5; DETR duplicates (IoU > 0.5)
  dropped first — kept, a duplicate would make every cat look crowded; then **a crop holding more than
  0.10 of another animal's box is skipped** (only 0.10 removed §10.8's two tuxedo/white mixes; it skips
  7 of 31 crops there).
- **Clustering**: `PetClustering.BestGuess` = `clusterFaces` at 0.85, `minFaces = 2`. ⭐ Simulated on
  §10.7's set with that greedy-centroid algorithm, not only single-link: 21 clusters at 0.85, the
  littermates never together; the two remaining mixes are the tuxedo/white crops the overlap rule drops.
- **`pets.log`**: `DefaultFaceTemplateStore` under its own magic `TSHUPET1` at `/inference/pets.log` —
  the face log's record format (a box, a confidence, a vector per animal), never its file. Species is
  not stored: across species the cosine stayed under 0.21 (§10.7). Wiped with the realm.
- **The indexer's fourth half** (`MediaIndexer(pets, petBackend)`): its own consent read per file, its
  own `visited` (true for a photo with no animal), all-or-nothing per photograph, a `Failed` never
  recorded and latched, a pipeline change clearing the store (`labelSetVersion` carries
  `PET_PIPELINE_VERSION` and the detector's version), the reconcile dropping a deleted photo's pets.
  ⚠⚠ **An explicit `PET_RECOGNITION` switch-off deletes `pets.log` and never touches `faces.log`, and
  the reverse** — `MediaIndexerPetsTest` holds both. *Index now* and new-arrival batches count the pet
  half; a never-chosen pets switch does not keep an arrivals queue alive.
- **Wiring.** Android binds a `PixelPetBackend` over `AndroidFaceImageDecoder` (People's decode, moved
  unchanged into `decodeUprightBitmap`) with its **own** RF-DETR instance — never the face backend's veto
  detector, which runs under that backend's lock. Desktop binds one over `MacFaces.Decoder`, available
  only where `MacFaces.runsHere`. iOS binds none, and `petTemplatesIfPetsRunHere()` never opens a pets
  log there.
- ⚠ **Why it is inert today**: nothing can grant `PET_RECOGNITION` until PT-3c's switch (absence = no),
  and no catalog entry serves the embedder — the detector's entry is Android's runtime and declares
  `faceClustering` only. A maintainer can exercise it with both files side-loaded and the two consent
  rows written by hand.

### 10.10 PT-3b — as built (2026-11-19): the pet ops and inference-service v2

- **Four operations**, each a face op's contract with its own id: `petGroups(limit)`,
  `namePetGroup(filePaths, name)`, `rejectPetSuggestion(name, filePaths, boxesPerMille)`,
  `petSuggestionRejections()`. ⭐ They reuse `GroupedFace`, `RejectedFace` and `FaceGroupsUnavailable`
  — no new composite — so Photos can read both halves with one code path. Client ROs registered.
- **The implementations are the face ones, parameterised** — capability, param ids and backend lookup as
  constructor parameters with People's values as defaults, so the face ops' behaviour and messages are
  byte-identical (`capability.id` is `"faceClustering"`). `petGroups` asks the pet backend (absent or
  unavailable = `NO_BACKEND`), clears `pets.log` on an explicit `PET_RECOGNITION` withdrawal or a
  pipeline-stamp change before reading, and groups at `PetClustering.BestGuess`. `namePetGroup` is not
  gated on the pets wiring, for `nameFaceGroup`'s reason: a device that cannot index pets must still be
  able to correct a name a peer typed.
- **Wiring**: a second `FaceGroupingComponents` (`PET_GROUPING`) in `ProxyModule` over the same pets store
  the indexer writes, with People's read filter; Android and desktop pass `petGroupingIfPetsRunHere()`
  and the pet backend; `MediaLibraryWiringTest` fails if either stops. iOS wires none.
- ⚠⚠ **HR #9: `InferenceService` v1 → v2**, with the service's first `SchemaMigration` step — empty,
  because nothing stored reshaped (a pet name is a `MediaLabel` with a new capability string). The
  baseline is regenerated: every inference row moves to `|2|` and the four ops are new. ⚠ Control and
  Photos (and every app registering the inference module) move across it in lockstep; no hub is deployed,
  so no redeploy is owed.
- **Threat model**: a *Pets beside it* paragraph in A40 — `pets.log`'s custody, `namePetGroup` as the
  second realm-writing op, and per-switch withdrawal (HR #2).
- `PetOperationsTest` holds the separations end to end: face consent is not pet consent, a withdrawal
  clears `pets.log` and never `faces.log`, a name is a `petRecognition` label by the user and never a
  person's, a rejection lives beside the pet templates.

### 10.11 PT-3c — as built (2026-11-19): the switch and the catalog

- **Settings → Models offers *Recognise your pets*** (`OfferedConsents`), Settings-only and off by default.
  It is shown where faces run — the same build fact, `faceClusteringRunsHere` (Android; macOS with
  Vision) — because pets run exactly there (§10.9); an ON switch is never hidden. The feature text says
  the three things faces' does: where pet data is made, that only typed names sync, and that turning it
  off deletes the pet data and keeps the names. ⚠⚠ The switch-off confirmation says **pet data**, never
  face data (`turnOffDeletes`/`turnOffText`, `ModelsScreenTextTest`).
- **The catalog** (`deploy/models/models.json`), one entry per runtime as the face entries are:
  - `dinov2-small-zeroshot` v1 under `onnx+mediapipe` (Android) and `onnx+vision` (macOS), role
    `petEmbedder`, capability `petRecognition`, the descriptor's digest and size, and the export's pinned
    source (`facebook/dinov2-small` at `ed25f3a3…`, `config.json` + `model.safetensors` hashed).
  - `rfdetr-nano-coco-int8`: the Android entry now serves `faceClustering` **and** `petRecognition`, and
    a macOS entry (`onnx+vision`) serves `petRecognition` — the same bytes. ⚠ Windows and Linux see
    neither: no pet backend runs there.
  - `PetEmbedderCatalogEntryTest` and `AnimalDetectorCatalogEntryTest` pin each entry to its descriptor
    and its runtimes to exactly one entry per platform; the signer's own `checkManifest` passes.
- ⭐ **Published 2026-11-19** at the maintainer's request (`docs/how-to/models-pipeline.md` §2–§5): every
  live artifact staged and re-verified by SHA-256, the embedder added (`export-pet-embedder.py`
  reproduces it byte for byte), signed with the key `MODEL_CATALOG_PUBLIC_KEY_HEX` names, uploaded
  artifacts-first. Verified from outside: catalog and signature byte-identical to the signed pair, the
  Ed25519 signature valid, the embedder served 206 on a ranged GET at 88 441 580 bytes. The diff against
  the previous catalog was exactly the detector's name and added capability plus three new entries.
  ⚠ Control builds older than PT-3a list *Pets (DINOv2-S)* as a download that does nothing there.

### 10.12 PT-3d — as built (2026-11-19): the *People & pets* row

- **The repository** gains `petGroups` and `namePetGroup` (`PhotosRepository`, defaults: no pets /
  `Failed`). `TenshuPhotosRepository` serves both through the face read's and the chunked naming's own
  code (`groups`, `nameInChunks`), so the verdicts — `PartiallySaved`, `NoConsent` — are People's. An
  older Control without the ops answers a failure, read as *no pets*.
- **One row, PT-D3.** `CollectionsViewModel` reads pet groups beside faces; while pet recognition is on
  here the pet cards join `people` — **named first**:
  named people, named pets, then the unnamed of each, each list keeping its own order
  (`mixPeopleAndPets`). Pet cards are `peopleFrom` over pet groups and `petRecognition` labels
  (`petAlbums`), so a named pet folds its groups exactly as a person does, re-addressed `pet:…`. The row
  and its page are titled **People & pets** (`peopleTitle`); a pets read that fails costs the pet cards,
  never the row. With pets off the row is exactly as before — a pet name synced from another device is
  not drawn there.
- ⚠⚠ **"On" is read only from answers given after the proxy's consent check** — `Available`,
  `NOT_INDEXED`, `UNCALIBRATED`. `NO_BACKEND` comes *before* it (`FaceGroupsImplementation` asks for the
  backend first), so a device without the pet models reads as pets off whatever the switch says; the
  first cut counted it as on and drew pet cards there (Codex, PR review).
- ⚠⚠ **A pet card names a pet.** `nameGroup` routes a `pet:` card to `namePetGroup`, never
  `nameFaceGroup` — routed by the id this screen mints, which survives the on-device snapshot where an
  in-memory set would not. `PetCollectionsTest` holds it both ways.
- A row already showing pet cards does not ask for the **face** switch (`NO_CONSENT`'s line): a user who
  chose pets and not faces made that choice. Name suggestions stay people-only until PT-5.
- **Permissions**: Photos declares `petGroups` and `namePetGroup` (`BundledAppTablesTest`); the suggestion
  pair arrives with PT-5, which calls it.
- ⚠ **Not verified on a device yet**: the whole path (switch on, download both models, index, name a
  cat) wants a maintainer pass on the phone; the smoke gate only proves the apps start and admit.

### 10.13 PT-4a — M6 measured (2026-11-19): iOS Pets run RF-DETR, not Vision

M6 asks whether iOS's models make the same templates Android's do — the condition for one 0.85 cut to mean
the same thing on both. Measured on 98 photographs (the Commons cats of §10.6 and the household photos of
§10.7), every vector the CLS half.

| What is compared | Result |
|---|---|
| DINOv2-S, Core ML fp16 vs the ONNX artifact, same crops (66) | cosine ≥ **0.99966** (CPU), ≥ **0.99980** (Neural Engine) — ⭐ passes |
| Vision's `VNRecognizeAnimalsRequest` boxes vs RF-DETR's, same animal (82) | IoU median 0.76; template cosine median 0.97, ⚠⚠ **min 0.665, 8 below 0.85**; Vision found 15 fewer photographs — ✕ fails |
| RF-DETR as Core ML fp16 vs Android's int8 ONNX (99 animals) | the same 82 photographs found (+2); IoU median 0.955; template cosine median 0.989, min 0.870, **none below 0.85** — ⭐ passes |
| **End to end**: iOS (Core ML detector + embedder, the export's archives) vs Android | median **0.9890**, p10 0.967, min **0.8695**, none below 0.85 — ⭐ passes |

⭐ **So PT-D4's fallback is taken for Pets: iOS runs RF-DETR through Core ML.** Vision's boxes frame an animal
differently enough that the same cat's template lands in another group on iPhone than on Android; the face
veto (PT-2a) is untouched, Vision stays there.

`scripts/inference/export-coreml-pets.py` (toolchain `requirements-coreml-pets-export.txt`) converts both from
torch fp32 — never from Android's int8 — into canonicalised fp16 ML Programs, **byte-reproducible** (two runs:
`dinov2_small_cls_mean_fp16` `00c9cd86…`, 43 417 600 B; `rfdetr_nano_fp16` `ed3df779…`, 54 077 440 B).
⚠ **Float32 in and out, fp16 inside** — the face embedder's rule; the first cut left an iOS17 ML Program's
default Float16 IO, which `CoreMlSession` could copy out only element by element (digests then `0e0b7035…` /
`21f8950e…`; parity unchanged after the fix, end to end median 0.989, min 0.8695).
Three trace-time rewrites, each checked against the original before conversion (≤ 2e-5):
- ⚠ DINOv2's position table is bicubic-interpolated 37×37 → 16×16, an op Core ML cannot convert; the input
  is fixed at 224, so the table is computed once and baked in.
- ⚠ RF-DETR's proposal grid (`torch.meshgrid` over traced sizes) is built as two broadcast ranges.
- ⚠⚠ RF-DETR's deformable attention reshapes to **rank 6** and Core ML stops at rank 5; with batch 1 the
  batch axis is dropped throughout.
The self-check compares each archive with its torch source on fixed probes (embedder cosine 0.9997; detector
cat/dog scores within 0.0095). ⚠ Its detector probes are noise, where no query is an animal — the real-photo
parity above is the measurement that matters, and must be re-run for any new export.

⚠ The `coreml` catalog entries wait for PT-4c: until iOS's Settings screen knows the pets feature, a
published pet model would show there under search's.

### 10.14 PT-4b1 — as built (2026-11-19): the Core ML runners

- **One multi-output change to the shared Core ML layer.** `CoreMlTowerSpec.extraOutputs` names further
  outputs with their element counts (checked present at load, the right count of finite values at run
  time — not `TowerGuards`' embedding rules, which judge a logit table wrongly), and
  `CoreMlSessionPort.predictAll` returns every output by name from one prediction. Single-output callers
  are unchanged: `predict` now reads through the same `runAll`. `CoreMlCompiledModels.prepare` knows the
  `petEmbedder` and `animalDetector` roles.
- **`CoreMlAnimalDetector` / `CoreMlPetEmbedder`** are the ONNX twins' contract on iOS: `null` when the
  model cannot run, never "nothing found"; a session per compiled path; ⚠⚠ a model Core ML loads and the
  spec rejects is latched per path, a load failure is not (it may be a memory moment). The detector reads
  `dets` and `labels` through Android's own `RfDetrOutput`, so a box means the same on both.
- **Descriptors**: `AnimalDetectorModel.RfDetrNanoCoreMl` and `PetEmbedderModel.DinoV2SmallZeroShotCoreMl` pin the
  Float32-IO archives (`ed3df779…`, `00c9cd86…`) under the checkpoints' own ids and versions.
- **Tests**: `CoreMlPetModelsTest` (fake sessions: the decode, null-not-empty, the latch, the spec);
  `CoreMlPetModelsRealTest` compiles both real archives through the production prepare step and checks this
  session code against Core ML's own answers for a fixed tensor (flag `tenshu.inference.coremlPetsExport`).
  ⚠ On noise the detector's boxes differ between the CPU and Neural Engine paths by up to 0.07 — nothing
  there is an animal — so the test accepts either path's reference; the simulator runs the CPU's.

### 10.15 PT-4b2a — as built (2026-11-19): readiness, probe and backend for iOS

- **`ReadinessSlot.PETS`** — its own verdict, scene and fingerprint files, and two open records (RF-DETR,
  DINOv2). The fingerprint carries **both** archives as one identity (`petArchivePair`), so a new export of
  either clears `pets.log`; the custody is a `FaceTemplateCustody` over `pets.log` and the pets slot's file,
  refused when bound to any other (`coreMlPetReadiness`).
- **Facts read both install records**: installed only when both models are, prepared only when both are
  compiled for their installed digests — half a pipeline indexes nothing (`coreMlPetModelFacts`).
- **The pets preflight** (`PetPreflight.kt`): the detector, then the embedder **beside it** — both opened
  outside the backend, warmed, predicted 8 times, ⚠⚠ memory read with **both resident** (the backend holds
  the pair in a pass; a phone that fits each alone must not pass), both closed before the verdict. Judged
  **output first** — the embedder's unit CLS against the export's (±0.004; ⚠ a CLS with no length fails,
  since its NaNs would never fail a comparison), the detector's boxes against either compute path's answer
  (±0.01; on noise the two paths differ by up to 0.07, §10.14) — then the median against the shared 500 ms
  budget, the shared memory floor, and the thermal / memory-warning conditions. ⚠⚠ A probe exists only for the pinned archive pair: a new
  export gets no verdict until its references are recorded.
- **`InferenceStoreCustody(pets = …)`** clears `pets.log` wherever it clears the face templates — a realm
  change, a reset, a relinquish.
- **`CoreMlPetBackend`**: the shared pipeline's steps and stamp, with iOS's rules around every load — claims
  only while the pets readiness serves and asks again after the blocking calls; ⚠⚠ every model open goes
  through `AiReadiness.servingOpen` after `beforeLoad`, so a load Photos does not survive is counted; a
  rejected model latched per path; one photograph at a time on `MediaWorkBudget.lane`. `petBackendOver`
  refuses any readiness but the pets slot's.
- Tests: `PetPreflightTest` (common, also run on iOS), `CoreMlPetBackendTest` (iOS), a pets case in
  `InferenceStoreCustodyTest`. ⚠ Nothing is bound in a graph yet — PT-4b2b.

### 10.16 PT-4b2b — as built (2026-11-19): pets in Photos' graph

- **Behind their own build flag**, `TenshuPhotosPets` ← `TENSHU_PHOTOS_PETS` (`photos-ios/project.yml`,
  `NO`): only the exact `YES`, and only over an admitted on-device AI graph — faces' rule (FD10).
  ⚠⚠ Off until Pets' iOS device pass.
- **Bound when admitted** (`photosOnDeviceAiModule(pets = true)`): the pets custody (`FaceTemplateCustody`
  over `pets.log` and the pets slot's fingerprint), the pets readiness, `CoreMlPetBackend` — also under
  `PetBackend`, which is what switches on the common indexer's pet half and the pet ops
  (`petTemplatesIfPetsRunHere`) — the pets preflight, and **two preparers** (one per catalog entry) over
  the one readiness.
- ⚠⚠ **One model load at a time across all three models**: pet loads wait for search's and the face
  probe; search's and the faces' loads now wait for a built pets probe; pets are prepared and probed last
  in the recheck sequence (`petCheck`), never beside the others.
- **Everywhere faces are named, pets are too**: model-store changes (`ByModelChanges` — both pet models
  stand the pets readiness down), the downloader's install / evict callbacks, the scene changes, the
  teardown (`retire`: the pets readiness retired, its probe awaited, its models unloaded), the foreground
  pass's session closes, and ⚠ the processing wake — pets are not in the backend registry, so a device
  with only pets switched on is asked for overnight work through `petsRunHere`.
- **Stores**: `pets.log` in Photos' own container behind the after-prior-writer barrier; the realm custody
  clears it; relinquish erases it at rest; every other route-1 graph binds an unowned stand-in; the iOS
  realm-wipe hook and the inference factory take the pets store, grouping and backend where they exist.
- Tests: `PhotosPetBindingTest` (flag, binding, inert with no model — `petGroups` answers `NO_BACKEND`, off
  and AI-off bind nothing, the pets store is not the face store); the Photos SDK's whole iOS suite green.
- ⚠ **Not yet**: the Models screen's pet rows and switch, and their immediate withdrawal hook — PT-4c, with
  the `coreml` catalog entries and the SwiftUI *People & pets* title.

### 10.17 PT-4c — as built (2026-11-19): Settings, the title, the catalog

- **Settings → Models on iOS** (Photos' own screen): `PhotosOnDeviceAi.features(…, pets = true)` offers
  *Recognise your pets* beside a bound pet backend (pets are not an `InferenceBackend`), and
  `modelFeatures` reads both pet models as its rows. **Two `JoinedModelSource`s** (`petSource`), one per
  pet model, over the one pets readiness and probe; an offered entry's key is built with its partner
  entry from the catalog (`iosPetPreflightKeyOf`). ⚠⚠ The catalog entry is read by id **and** runtime
  `coreml` — Android's and macOS's entries share the ids.
- **Withdrawal at once**: `JoinedModelSource.petsWithdrawn` lets both pet models go and runs `petGroups`'
  withdrawal-first deletion of `pets.log` now — only for an explicit no, and never the face templates.
- **The copy** (`PhotosModelsCopy`): a `DeletingFeatureCopy` for faces (exactly the constants they always
  were) and one for pets — Android's words, faces' shape, ⚠⚠ *pet data*, never *face data*
  (`PhotosPetsCopyTest`). `capabilityOf` reads `petRecognition` rows as pets'.
- **SwiftUI**: the People row's header is the row's own title, and the section page asks
  `PhotosSurface.sectionTitle` — *People & pets* while pet recognition is on (PT-D3), as Compose does.
- **The catalog**: `dinov2-small-zeroshot` and `rfdetr-nano-coco-int8` under runtime `coreml` — the
  Float32-IO archives of §10.14 at `…/1/coreml/…`, with the export's toolchain and manifest digest.
  ⭐ Invisible to every build that has no pets source: the iOS Models screen lists only models with a
  source, and Android and desktop never declare `coreml` (the catalog tests pin one entry per platform).
  ⭐ **Published 2026-11-19** at the maintainer's request (§10.11's procedure): every live artifact re-verified
  by SHA-256, the two archives staged from the reproducible export, signed with the compiled-in key; the diff
  against the live catalog was exactly the two `coreml` entries. Verified from outside: catalog and signature
  byte-identical, Ed25519 valid, both archives served 206 at 43 417 600 / 54 077 440 B.
- ⚠ **What stood between this and a user** was the device pass; it ran (§10.18) and `TENSHU_PHOTOS_PETS`
  is `YES` since 2026-11-20. ⚠⚠ Pets still reach no iPhone user until `TENSHU_PHOTOS_ON_DEVICE_AI` flips —
  they bind only over that graph (FD10).


### 10.18 The iPhone device pass — two sync defects first (2026-11-20, in progress)

The pass began by joining the iPhone to a fresh realm with more cats, built by the maintainer's Mac:
an import at night, its folders deleted the next morning, and the photos re-imported at the same
paths (`/Cats`, `/Sintra`, …). All ten iOS apps were installed from one master build — the shared
replica means a sibling one service version behind wipes the store — with Photos built
`TENSHU_PHOTOS_ON_DEVICE_AI=YES TENSHU_PHOTOS_FACES=YES TENSHU_PHOTOS_PETS=YES`. Joining surfaced two
defects in shared code before pets could run; neither is a pets defect.

- **The join wedged at "Storage isn't ready".** Applying a peer's folder tombstone
  (`applyVerifiedDeleteBlock` → `DeletedFolderSubtreeReaper`) deletes each child, and every child
  delete ran `FileHistoryPolicyObserver.cascadeDeleteHistory`, which read the **whole realm-wide**
  `MODIFICATIONS` relation — all inside one write transaction. On the SQLite replica that passed
  `HOLD_MAX` (1 s): the handle was fenced, the graph rebuilt once, fenced again and stopped; every pull
  re-applied the same block. An open `tech-debt.md` entry, now measured. ⇒ Both history policies read
  the file's own `File.FileHistory` array (`FileHistoryRelationTest`'s `…NotTheRealmWideLog` cases fail
  without it). On the phone: `backfill complete`, no fence, the library showed.
- **Deleted photos came back as live files with grey tiles.** The relay held every thumbnail of every
  live file; the iPhone asked for refs it had freed at 09:17Z. A diagnostic build showed it resolving
  paths the Mac no longer has (`/Cats/22.jpg`, `/Sintra/IMG_0062.HEIC`): **the first import's
  children, alive again.** A folder's children die with it and carry no tombstone of their own; a
  joiner pulls tombstones and history on separate lanes and back-fills history backwards, so it applied
  the folder's tombstone before the children existed — nothing to reap — then saved each older child
  as it arrived. Inside the re-created folder the orphan sweep never sees them, and their content was
  already freed. `content-plane-cr2-b2-audit.md` §3.4 had called this direction "a leak, not a loss".
  ⇒ Fixed in master the same day, from the Mac's side of the same realm: `resolveConflictAndSave`
  refuses a block at or below the deletion point of any container up its entity-field chain, and a
  tombstone landing after a same-path re-create still reaps the old members
  (`FolderRecreatedOverTombstoneTest`). This pass added the join order's own cases to
  `ReapOrderingTest`. ⚠ The ghosts already on a device are not removed — the iPhone re-joins.
- **The pets probe failed twice on the Neural Engine, then passed.** Its detector check compares the first
  eight noise-box coordinates against the Mac's references at ±0.01; the iPhone 17e's NE put one
  0.0185 from the Mac NE's (`[0.4846, 0.4258, 0.9814, 0.7954, …]`, identical both times) — the same
  detection, another chip. ⚠⚠ Three failed probes latch the install as failed for good
  (`FAILED_PROBES_FOR_FAILED_INSTALL`), so this was one attempt from "pets never work on this phone".
  ⇒ The iPhone's answer is a third reference and the box tolerance is 0.03 — chip drift covered, still
  far inside the 0.07 between compute paths; the archive's SHA-256 is what proves identity
  (`PetPreflightTest.anotherChipsNeuralEngineDriftPasses` fails at 0.01).
- ⭐ **Measured (iPhone 17e, third probe):** `Passed`, state `Ready`. Median predict **14.5 ms** detector /
  **4.2 ms** DINOv2 against the 500 ms budget (margin ×34); first open after install 3.46 s / 1.71 s
  (on-device compile); footprint 38 → 90 MB with both models resident; thermal 0 → 0, no memory
  warning.
- ⭐ **Indexed and grouped on the iPhone.** *Index now* over the 113-photo realm (pets alone on; search
  and faces off): **28 animals in 26 photographs** — every photo in `Cats`, the puppy's three in
  `Home`, eight in `Sintra`, one in `Weekends` — detector confidence 0.72–0.97, five pairs at or above
  the 0.85 cut. Collections → *People & pets*: **four cat groups** (the black-and-white, the
  tortoiseshell, two of the tabbies). The tabbies split rather than merge — PT-D11's bar working as
  meant; suggestions are what join them.
- ⚠⚠ **But not until Photos was relaunched.** `MediaIndexRunner.faceChanges` — the only thing iOS
  Collections re-reads *People & pets* on — moved only for face-store writes; `MediaIndexer.petStoreWrites`
  existed and nothing read it. So a pass that found only pets left the screen on its empty answer
  (`Collections -> walk=skipped (clean)`). ⇒ The runner counts both stores' writes, in a pass and in a
  refusal's withdrawal (`MediaIndexerPetsTest`'s two runner cases fail without it). The pass's summary
  line now prints the pets half too — it printed none, so the console could not say whether pets ran.
- ⚠ The Models screen's *Indexing* line read "0 of 113 photos on this iPhone can be searched" with
  search off and pets indexed — it counted the search store, which a pets pass never touches, and the
  system banner promised search. ⇒ Fixed: with search's switch off the line says only the pass's reach
  (*113 photos on this iPhone can be indexed.*), the idle prompt names the switched-on features
  instead of search, and the banner's subtitle (`PhotosIndexNowTask.SUBTITLE`, fixed at install for
  every pass) no longer names search (`PhotosIndexingSectionTest.withSearchOffTheSectionPromisesNoSearch`).
  ⚠ There is still no per-feature count: how many photos pets covered is not on the screen.
- ⚠ Android/desktop did **not** refresh — after a pets-only pass or a faces one — until the
  Collections tab was tapped again. ⇒ Fixed by §10.19.
- ⭐ **`TENSHU_PHOTOS_PETS` flipped to `YES`** (2026-11-20, the maintainer's call after this pass). It binds
  nothing until `TENSHU_PHOTOS_ON_DEVICE_AI` flips, which waits on search's own device pass.

### 10.19 People & pets follow the index on Android and desktop — `peopleIndexChanges` (2026-11-20)

**The defect** (found by tracing the code, after §10.18's iPhone fix). On Android and desktop, Photos' *People &
pets* re-read only when the Collections tab was tapped, so after any pass that changed the groups
(faces or pets) the tab kept its old answer, and nothing refreshed it when Photos resumed.
Three causes, each sufficient:
- the groups are clustered from **device-local logs** (`faces.log`, `pets.log`), not realm rows, so no
  chain read observes them — `faceGroups` / `petGroups` answer once;
- the library watch is iOS-only (`watchScope` is wired by the iOS runtime alone), and would not see
  a pass anyway: its marker label is written only by the tagging half, which no backend claims;
- the Compose shell never called `CollectionsViewModel.setShown`, so even a change it was told of was
  held until the next tap.

**The fix — a reactive stream, not a resume refresh.** A resume refresh means something different
on each platform (an Activity's resume vs. a macOS window's focus, or nothing for a window left open
beside Control), and without a watch every resume would re-walk the whole tab. Instead:
- ⭐ `peopleIndexChanges` — an `InferenceService` **push stream** of `MediaIndexRunner.faceChanges`,
  the same counter iOS Collections follows in-process (§10.18's runner fix). It sends the value at
  subscribe, then every later value; it never ends by itself.
- ⚠⚠ **A push stream that stays open needed a core hook.** `EndStream` tore down only database
  listeners, so an implementation's own collector outlived its subscriber for the life of the session
  (the leak `MediaIndexProgressImplementation`'s KDoc avoids by ending itself).
  `ExecutionContext.launchStreamJob(streamId)` binds the feeder to the stream
  (`StreamRegistry.ownStreamJob`): the stream's `EndStream`, a resubscribe of the same id, or the
  session's end cancels it (`PeopleIndexChangesTest.endingTheStreamStopsTheProxysFeeder`, which
  fails with the cancel removed).
- ⚠ Two collectors of one chain share one stream id and one feeder (a stream id is its callstack's
  hash); ending one leaves the other fed (`oneCollectorsEndLeavesAnotherFed`).
- `TenshuPhotosRepository.peopleIndexChanges()` **compares values rather than counting them**: a
  subscription's first value is a baseline, and every different value is a change. After a Control
  restart the counter starts again from 0 and the stream resubscribes (`core-client-resubscription.md`), so
  the first value after the restart is a change too — correctly, because a restart may have lost one.
- The Compose shell passes it as `CollectionsViewModel(indexChanges = …)` and reports `setShown`
  while Collections, a section page or a person page is on screen **and** the app is resumed — a
  change is walked for while shown and owed to the return otherwise, iOS's existing rule.
- ⚠⚠ **HR #9: `InferenceService` v2 → v3**, empty `SchemaMigration` step (one new operation; its element
  is a bare integer, nothing stored reshaped). Baseline regenerated, iOS pin moved. An older Control
  has no op and fails the stream; the view model logs it and Collections behaves as before
  (`aProxyWithoutTheOpFailsRatherThanFallingSilent`).
- ⚠ Not device-verified: no pass was run on an Android or desktop device against this. The tests
  cover the wire, the teardown and the repository's comparison; the view model's handling of
  `indexChanges` is iOS's, already tested.

