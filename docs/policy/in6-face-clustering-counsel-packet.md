# People in Photos — counsel packet

**Status:** ready for ⚖️ counsel review. **Owner:** Benedikt. **Opened:** 2026-09-23. **Revised:**
2026-09-24: §4 corrected for the HSFace lineage finding, and §6 now points at the threat-model entry
as it shipped, not the earlier draft. **Revised again 2026-09-24:** the feature was released.

⚠⚠ **We released the feature before this review, and we say so plainly.** On 2026-09-24 the owner
decided to publish People on Android without waiting for counsel. It is off by default, offered only
in Settings, and needs both consent switches. **These asks are therefore a review of something
already available to users, not a gate before release.** If your answer to any of them is no, we will
withdraw the model from the catalog and remove the switch; nothing else in the product depends on
it.

This is a self-contained brief. It does not assume the reader has seen
[`photos-people-and-pets.md`](../proposals/photos-people-and-pets.md) or the threat model, and it
states our own beliefs as beliefs rather than as conclusions.

⚠⚠ **There are three separable asks here and they can be answered independently.** §4 (the model's
licence) is the one we consider most exposed. §5 and §6 are sign-offs on arguments already written.

---

## 1. What the feature does, in one paragraph

Tenshu Photos would show a **People** section: rows of faces the user's own device found in the
user's own photographs, each openable as a grid, each nameable. On a device the user has opted in,
photographs already stored in their library are examined locally, faces are grouped by visual
similarity, and the user may type a name against a group. **Typing the name is the only durable
act.** Nothing is published, nothing is shared, and no third party is involved.

---

## 2. The five architectural facts that bear on the legal analysis

These are properties of the design, not aspirations. Each is enforced in code and testable.

1. **Biometric templates never leave the device.** The per-face identity vector is written to a
   device-local store that synchronises nothing, is re-derivable from content the device already
   holds, and is destroyed when that store is wiped. No template is ever transmitted, backed up, or
   placed in the encrypted sync plane.
2. **What syncs is a string the user typed.** The durable artifact is a label of the form *this
   photograph shows Anna* — user-authored text attached to a photograph, not a model output and not
   a template.
3. **We cannot read any of it.** Tenshu is end-to-end encrypted. The relay stores ciphertext and a
   device identity; it never receives images, templates, or names in a readable form. This is a
   standing architectural rule, not a configuration.
4. **Consent is twice-gated and defaults to off.** The capability requires a realm-level switch
   *and* a per-device switch, both defaulting to "no". It is offered only in Settings and
   **never during onboarding**. Consent is re-read per photograph, so a withdrawal stops a running
   pass rather than taking effect at the next one. With no consent, the photograph's bytes are never
   read.
5. **Scope is the user's own library on the user's own device.** There is no camera, no live
   capture, no enrolment of anyone else, and no identification of a person who did not put the
   photograph there.

⚠ **Our belief, offered for testing rather than asserted:** the face template is special-category
data under Art. 9 while it exists on the device; the person label is also special-category, since it
associates an identified natural person with an image; and the processing sits within the household
exemption (Art. 2(2)(c)) with the user as the party doing it. Because of fact 3 we believe we are
structurally not in a position to be the controller of the content. **We would like this tested
rather than relied upon.**

---

## 3. Why this does not contradict our own published exclusion

We have a **camera** product line where face *recognition* is excluded as a matter of policy, not
sequencing (`camera-service.md` CD-D7). The exclusion says person identification is biometric data
under Art. 9, that this project is an EU seller of record with a public privacy posture to defend,
and that revisiting it would start with counsel.

We believe the two situations differ on every axis that matters:

| | Camera (excluded) | Photos library (proposed) |
|---|---|---|
| Subject | Non-consenting third parties — visitors, delivery drivers, passers-by | The user's own photographs |
| Trigger | Continuous, automatic | A library the user assembled, opt-in, off by default |
| Context | Security | Organising one's own photographs |
| Lawful basis for the bystander | None available | Not applicable — no bystander enrolment |
| Our visibility | — | None: we never see the images, templates, or names |

⚖️ **Ask A (§5):** is that distinction one you are comfortable defending publicly, and does our
public copy need to say anything specific to keep the two positions coherent?

---

## 4. The blocking question: which face model may we use? ⚖️

This is what stops a release, and it is a **licensing and training-data** question rather than a
privacy one.

A face pipeline needs two models. **The first is solved.** Google's MediaPipe Face Landmarker is
Apache-2.0 on the weights, trained on Google's own consented in-house data, with no public-dataset
lineage we can find. We are satisfied with it and are not asking about it.

**The second — the face *recognition* model that turns a face into a comparable vector — is the
problem**, and the binding constraint is one layer below the model's own licence: **what the model
was trained on.** Every open face-recognition model we surveyed descends from a corpus whose terms
the redistributor does not appear to hold the rights to grant. Our survey is in
[`models.md`](../models.md) §5 with the primary sources.

⚠ **The operating systems do not offer a way round this.** Neither Apple nor Google gives
applications a face-*recognition* model. Apple's Vision framework detects faces but has no public
face-identity API; Apple Photos groups people using a private one. Google's ML Kit detects faces and
landmarks but produces no identity vector. So every platform, iOS included, needs a recognition model
that we ship ourselves. We would also need one model on every platform, because a person's vectors
from two devices must be comparable.

Four routes, cheapest first. **Three of them cost only an email, and asking is reversible.**

| | Route | What we would be relying on | The question for you |
|---|---|---|---|
| 1 | **SFace** via OpenCV's model zoo | An unanswered clearance question already open upstream | Is an upstream maintainer's reply sufficient when the corpus itself is undocumented? |
| 2 | **HSFace / Vec2Face**: MIT-licensed, trained on **synthetic** faces | The synthetic step breaking the chain back to the real corpus | ⚠⚠ **We believe this does not clear, and we ask you to confirm or overturn that.** The faces are synthetic, but the generator that made them was trained on **WebFace4M**, a subset of WebFace260M. That corpus is licensed for academic research only and may not be redistributed, which is the same restriction that rules out the other open models. We verified this in the upstream README. Does a recogniser trained only on a generator's output inherit the restriction on the generator's training data? ⚠ An earlier draft of this packet framed route 2 around **FFHQ**. That question is moot for a separate reason: FFHQ is CC BY-NC-SA, so its non-commercial term blocks us before its facial-recognition clause is reached |
| 3 | **AuraFace** (fal.ai) | A vendor's claim of commercially-licensed training data, no corpus named | ⭐ **Now our preferred route.** It is the only Apache-2.0 face model whose author *affirmatively* claims a commercially-licensed corpus. fal's announcement ([https://huggingface.co/blog/isidentical/auraface](https://huggingface.co/blog/isidentical/auraface), 2024-08-26) says the original model *"has non-commercial restrictions due to its training data"* and that AuraFace *"can be used in commercial projects without limitations"*. It names no dataset, and a reader's question under the post, *"so is this really commerical usable ?"*, is unanswered. Another open-source photo product (PhotoPrism) already ships it. On our test library it groups faces exactly as the route-2 model does, with one false group fewer. We verified by hash that the recognition weight is genuinely theirs and not a relabelled InsightFace file. ⚠ But the same repository Apache-tags four files that provably *are* InsightFace's, and provenance questions have gone unanswered for a year. Is a written confirmation from them worth obtaining, what would it need to say, and does it matter that they will not name the corpus? |
| 4 | **Licence from InsightFace** | An actual written grant | The only route with a real contract. ⚠ We would want a **written warranty that they hold redistribution rights to WebFace260M** — otherwise we are paying for the same defect that disqualified the free options. Is that warranty obtainable and would it be sufficient? |

⭐⭐ **Our decision (2026-09-24): route 3, AuraFace, relying on fal's public claim.** We have not
written to fal. We intend to once the product has enough traction to make the answer material, and
we expect no reply, given a year of unanswered public questions. We accept that the basis is a
public statement of intent, not a grant or a warranty, and that the risk of it being false sits with
us. ⚖️ **What we ask you:** is relying on that statement acceptable for an EU seller, and is there
anything we should do or record now (for example, keeping a dated copy of the announcement) that
would strengthen our position if it were ever challenged?

⚠ **HSFace (route 2) was the engineering stand-in before this decision, and it will not ship.** It is free and MIT on the code. We have also measured it working: on a real 400-photograph
library it grouped 29 photographs of one person into a single correct group with no false members,
and it separated a photograph of a cat that the detector had mistaken for a face. Swapping it for any
other route is a model change behind a fixed interface. The cost is re-examining the library on each
device, and no name a user has typed is lost.

**How comparable projects handled this.** We surveyed six (Ente, Immich, PhotoPrism, Nextcloud
Recognize, digiKam, Frigate). **None has solved it.**
- Immich obtained written permission from InsightFace, and that permission states it does not extend
  to third parties.
- PhotoPrism enforces licence terms in code and refuses its own paid editions the restricted models.
- The other four ship models whose training data is undisclosed or non-commercial, and we found no
  public discussion of it.

We do not take "others do it" as cover. We take it as evidence that no clean option exists that we
have overlooked.

⭐ **Dropping the feature entirely remains coherent and cheap.** It is off by default and
Settings-only, so nothing else in the product regresses if the answer is no. That is the honest
fallback and we are not seeking to be talked out of it.

⚠ **One row stays open on every route**: none of these corpora can be disclosed in detail, because we
do not know their contents. Our existing shipped image model sits in exactly the same position, and
we disclose that in the consent copy. We believe this lands the feature in the posture we are
already in rather than a worse one — ⚖️ **please confirm**.

---

## 5. Ask A — the CD-D7 reconciliation, for sign-off ⚖️

Per §3. We are not asking whether to build it; we are asking whether the published distinction
between the camera case and the library case is defensible, and whether any public wording is
required to keep the two coherent.

---

## 6. Ask B — the threat-model entry, for review ⚖️

Our security documentation carries a numbered asset entry per data class. This feature added the
first entry covering data *derived* by inference: **A40** in
[`sync/threat-model.md`](../sync/threat-model.md). It landed on 2026-09-24 together with the code it
governs, so **this is a review of what shipped, not of a draft.** In substance:

- **Templates stay on the device and never become synced data.** The read operation groups them
  inside the privileged process and returns only file paths and a temporary group id. No face vector
  crosses into the app, so the app that shows People holds no biometric data.
- **Person labels are synced data and are special-category.** They are encrypted like every other
  record. The relay sees ciphertext and a device identity, never a name and never a face.
- **A label is marked as written by the user**, so no later model run may treat it as stale and
  overwrite it. Naming is the only operation in the feature that writes synced data.
- **Consent is gated twice and defaults to off.** It also gates *reading stored templates* and
  *writing a name*, not only producing new templates. A withdrawal while the People screen is open
  therefore serves nothing further and accepts no further names.
- **Camera-side recognition stays excluded** (§3).

⚖️ **We would like this reviewed**, since it is the document we would point to if asked how the
feature is governed. If you want wording changed, we will amend the entry.

⚠ **One question inside it is genuinely open and we would value a view:** *what should withdrawing
consent delete?* Templates are obvious — they go with the local store. **Person labels are not**:
they are the user's own words about their own photographs, and deleting them when a switch is turned
off may destroy something the user meant to keep. Our instinct is that a label is the user's content
rather than a product of the consented processing, and so should survive — but we would rather be
told.

**What we built (2026-09-24):** withdrawing consent deletes the templates and keeps the labels. The
Settings copy says so before the user agrees: *"Turning this off deletes the face data and keeps the
names."* ⚠ The deletion happens the next time the device would use the templates (its next indexing
pass or the next time People is opened), not at the moment of the switch. The templates are never
served in between. ⚖️ Please tell us whether the label half, or that timing, needs to change.

---

## 7. What we need back, in priority order

1. **§4**: whether relying on fal's public claim for route 3 is acceptable, and whether you agree
   that route 2 does not clear.
2. **§5**: sign-off, or required wording, on the camera/library distinction.
3. **§6**: review of the threat-model entry as it shipped, and of what withdrawal deletes.

⚠ **The feature is live on Android** (see the top of this packet). A "no" on any item means we pull
the model from the catalog and remove the switch.

---

## 8. Source documents

| | |
|---|---|
| Feature design, phase map, open questions | [`proposals/photos-people-and-pets.md`](../proposals/photos-people-and-pets.md) |
| Model survey, licences, corpus lineage, the hash test | [`models.md`](../models.md) §5 |
| The consent decisions (IN-D3, IN-D5) | [`proposals/inference-service.md`](../proposals/inference-service.md) §2 |
| The camera exclusion and its reconciliation | [`proposals/camera-service.md`](../proposals/camera-service.md) CD-D7 |
| Encryption posture | [`sync/threat-model.md`](../sync/threat-model.md) |
