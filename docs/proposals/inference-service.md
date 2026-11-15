# `:inference-service` — On-Device Content Understanding + Model Distribution

**Status:** v0.2 — IN-1, IN-2, IN-3a/b, IN-4a/b and AV-1 are built (as-built §8–§34). ⭐ Settings →
Models shows consent as a per-feature device list since 2026-10-11 (§38). The first
model was published to R2 and indexed on a real phone on 2026-09-17 (§33, §32); ⚠ labels remain
inert — the vocabulary is uncalibrated (§27.2), and that is one of three layers (§37.1) — so only
*search* produces anything today. ⭐ First labelled read 2026-10-10: §37, `models.md` §3.10.
⭐ **Label decisions 2026-10-11 (§39):** across devices the first writer wins, each artifact under
its own floors; the score unit is the cosine; the first vocabulary is the measured labels only.
L4/L5 decided 2026-10-14 (§39.6–§39.7); the first vocabulary waits for labelled data. Nothing for labels is built before PIO-7 PR-5 is complete.
**C0 decisions pinned 2026-08-12 (§7, IN-D8…IN-D12)**
after the maintainer's review; they amend IN-D4 and re-shape the §5 phase map, which is
restated in §7.6. v0.1 (2026-07-27). Opens the gap named in
[`home-and-mia-apps.md`](home-and-mia-apps.md) §9 and `milestones.md` §Mia: the platform
asserts "on-device inference" as a *principle* in the whitepaper and the milestone doc, but
the only place it is *designed* is the hub
([`hub-hardware-and-management-service.md`](hub-hardware-and-management-service.md) §5,
tiered inference on a base NPU + AI co-computer). For a user with a phone and no hub —
which D1 of the Home/Mia split explicitly names as Mia's core audience — nothing is
specified: no runtime, no model format, no distribution mechanism, no seam.

⚠ **§19 is the phasing** — the order to do the remaining slices in, and why IN-3m comes first.

**Scope:** the execution seam, the per-platform runtime choice, model distribution +
provenance, the consent model, and how derived artifacts relate to sync. **Not** in scope:
the Voice App Service, the speaker audio path
([`audio-and-voice.md`](audio-and-voice.md)), Mia's query planner
([`mia-query-layer.md`](mia-query-layer.md)), and the on-hub camera detection slice
(`camera-service.md` CM-4) — all three are *consumers* of this seam and keep their own docs.

**Three consumers, one substrate.** This doc exists because three tracks were about to
solve the same problem separately: Drive wants image understanding (faces, places, text,
semantic search), Mia wants retrieval over realm content
([`mia-query-layer.md`](mia-query-layer.md) MQ-D5), and CM-4 wants person/vehicle detection
on the hub. They want the same thing — a way to run a model over content the user already
has, wherever it is cheapest to run it.

---

## 1. The shape in one paragraph

An app declares an **inference task** ("embed this image", "read the text in this image",
"tag this illustration", "transcribe this audio") against an `InferenceService` operation.
*Where* the work runs — in-process on this device, on the hub over the H4 command pattern,
or on the AI co-computer behind the hub — is a **binding**, not a call site. Models are
**fetched, not bundled**, content-addressed and hash-pinned, through a proxy-mediated
egress op (apps stay INTERNET-free, the `FetchPlaceImage` precedent). Every capability is
**separately opt-in**; anything involving people defaults OFF. What inference produces is
**derived data**: small *labels* sync as ordinary realm rows, large *embeddings and
biometric templates* stay device-local and are re-derivable — so the durable artifact of
"who is in this photo" is a name the user typed, never a face vector.

---

## 2. Decisions

### IN-D1 — One seam, many execution locations

A module set `:inference-service` + `-common`/`-client`/`-proxy` (symmetric with every
other service), whose surface is task-shaped, not model-shaped:

```
InferImageEmbedding(contentRef)        -> Vector
RecognizeText(contentRef)              -> [TextRegion]
DetectFaces(contentRef)                -> [FaceRegion + template]
TagImage(contentRef, vocabulary)       -> [Tag + confidence]
TranscribeAudio(contentRef)            -> Transcript
```

Behind it, an `InferenceBackend` seam picks the location. The hub proposal §5 already
states that with no hub on the LAN, light inference "lands on a paired phone/desktop" —
⭐ making location a binding is what makes that sentence true *by construction* instead of
a rewrite when the hub arrives.

- **`FakeInferenceBackend`** ships first and returns canned results, so the entire consumer
  stack (Drive's indexer, Mia's retrieval, Home's camera events) is testable with zero
  models and zero hardware. This is the `SimulatedDeviceBridge` pattern from HD-1 and the
  `FakeMqttClient` pattern from HD-2, and it is the reason those slices were provable
  before the Pi existed.
- ⚠ Apps must never name a runtime or a model file. A call site that says "run
  `wd-swinv2-v3.onnx`" cannot be moved to the co-computer, cannot degrade when the
  capability is off, and cannot be re-pointed at a better model later.
- **Remote execution reuses shipped transport.** Hub-side inference is an ordinary op
  against the hub's proxy or the H4 `HubCommand` row pattern, depending on latency class;
  it does *not* get a bespoke channel, and it does not reopen the §11 no-listener decision.

### IN-D2 — Runtime: ONNX Runtime is the common denominator; Vision/Core ML where iOS gives it free

| Target | Runtime | Note |
|---|---|---|
| Android | **ONNX Runtime** (NNAPI / XNNPACK EP) | ⚠ ML Kit / Play Services **rejected** — see below |
| Desktop (JVM) | ONNX Runtime | |
| Hub / co-computer | ONNX Runtime (+ CUDA/ROCm on the co-computer) | same artifact as desktop |
| iOS | **Vision / Core ML** for OCR + face *detection*; ⚠ **native Core ML for our own weights too** (amended 2026-10-04, below) | a converted fp16 artifact of the same checkpoint |
| Web | ORT Web (WASM / WebGPU) | deliberately last — bundle weight, HR#5 posture |

- ⚠ **ML Kit and Play Services ML are rejected**, for the same reason Timeline's capture
  uses bare `LocationManager` rather than fused location: they are Google-coupled, absent
  on de-Googled Android, and they route a privacy-first product's content understanding
  through a dependency we do not control. That posture is a product claim, not a
  preference.
- ⚖️ **iOS is a deliberate split.** Vision ships OCR and face *detection* with zero model
  bytes and gets the Neural Engine for free; refusing it to keep one runtime everywhere
  would cost us hundreds of megabytes of download and worse battery for no gain. But
  anywhere we ship *our own* weights (embeddings, taggers), iOS uses ONNX Runtime too — so
  the **model artifact is identical across platforms** and a derived row produced on a Mac
  matches one produced on a phone. The split is by *who owns the model*, not by platform
  convenience.
- ⚠⚠ **Amended 2026-10-04 for iOS: our own weights run on native Core ML, not ONNX Runtime**
  ([`ios-on-device-inference.md`](ios-on-device-inference.md) D1, measured in its §2.1–§2.2). The
  paragraph above was the plan and is no longer what iOS does. On an iPhone 17e the converted fp16
  SigLIP vision tower runs at 5.9 ms against 112 ms on ORT's CPU EP, ORT's own CoreML EP returned
  NaN or failed to build, and fp16 Core ML retrieval agrees with the fp32 reference on 35 of 36
  queries against 23 of 36 for the uint8 ONNX pair. So on iOS the **checkpoint** is shared and the
  **artifact** is not: the catalog carries a second, `coreml` artifact of the same model.
  What that gives up: a vector produced on an iPhone is *not* comparable with one produced by the
  ONNX artifact. That was already the rule — vectors are device-local (IN-D5) — so it costs nothing
  today, and it is the open question for synced derived rows once labels stop being inert (that
  doc's D13). Android, desktop and the hub are unchanged.
  ⚠ **On a camera-roll corpus, in a partial run of 2026-10-07 (§15.13):** 13 ms of prediction and
  33 ms a photo with decode, on the same iPhone, in single ~20 s passes. The 5.9 ms above is the
  spike's figure on one synthetic input. IN-3m-iOS is still open.
- ⚠ This is the point where a native dependency enters the Kotlin stack per platform.
  Keeping it strictly behind `InferenceBackend` is what keeps the choice revisable —
  ExecuTorch, LiteRT, and llama.cpp-class runtimes all move fast, and none of them should
  be able to reach a call site.

### IN-D3 — Capability-scoped, opt-in; never a blanket "AI" switch

Each capability is separately consented, with its own copy stating what it does, what it
downloads, and what it costs in battery:

| Capability | Default | Why |
|---|---|---|
| Text in images (OCR) | opt-in, safe to offer prominently | no persons, high search value |
| Semantic image search | opt-in | large-ish model, big battery cost on first index |
| Character / content tagging | opt-in | domain-specific, optional download (IN-D7) |
| **Face clustering** | **OFF, offered in Settings only** | see below |
| Speech-to-text | opt-in, gated on the voice track | |

- ⚠⚠ **Face clustering is not pushed during onboarding.** Many users do not want it at all,
  and an onboarding flow that asks is an onboarding flow that pressures. It lives in
  Settings, off, described plainly. A feature that is *fine when chosen* becomes a
  different thing when it is the default.
- Consent rides `PreferenceService`, split deliberately across both setting kinds:
  **`RealmSetting`** answers "may this realm be indexed for X at all" (a user decision that
  should follow them to a new device), **`DeviceSetting`** answers "may *this* device do
  the work" (a phone on battery and a plugged-in hub are different answers to the same
  question).
- ⭐ **This is what reconciles `camera-service.md` CD-D7.** That decision excludes face
  *recognition* as policy — and it should stand — but its subject is a camera classifying
  *non-consenting third parties* (visitors, delivery drivers) continuously, in a security
  context, where no lawful basis covers the bystanders. Clustering faces in the user's own
  photo library is a materially different situation: the images are ones the user chose to
  keep, the processing is on their own device, the household exemption (GDPR Art. 2(2)(c))
  is in play, and under E2E we never see the images or the templates (HR#1) so we are
  structurally not the controller. **Both decisions are recorded so the pair does not read
  as a contradiction:** camera-side recognition of passers-by stays excluded; opt-in
  clustering of one's own library is permitted. ⚖️ The distinction is the argument, and it
  is a counsel-review item before release — same category as the folder-link-sharing D8
  notice-and-action policy and the retention-model D4 closure clause.

### IN-D4 — Models are fetched, hash-pinned, and never ride the content plane

Weights run 10 MB to 1 GB. Bundling them in five app-store binaries is a non-starter, and
bundling the *union* of capabilities most users will not enable is worse.

- **Fetch on first enable of a capability**, through a **proxy-mediated egress op** — apps
  are INTERNET-free and Control does the network, exactly as `FetchPlaceImage` does for
  place imagery and the region service does for map tiles. There is no new egress posture
  here, only a new payload.
- **Model identity is its content hash** (SHA-256), the same shape as `contentId` in
  `file-content-addressing.md`, with the hash **pinned in source**. Verify-on-download,
  reject on mismatch. ⭐ That is the supply-chain answer: a compromised mirror or CDN
  cannot swap a model, and "which model produced this row" is answerable forever.
- ⚠ **Models do NOT go on the content plane.** They are public artifacts, not user data:
  they must not consume the user's storage quota, must not ride E2E sync (every device
  re-downloading 400 MB through the relay is absurd), and must not appear in Drive. They
  live in a per-device cache keyed by hash.
- **The hub is the natural mirror, later.** A hub that already holds a model can serve it
  on the LAN to a phone that does not. That is an optimisation, not v1 — but the
  content-addressed identity is what makes it a five-line change rather than a redesign.
- ⚠ **Model upgrades must not force a re-index.** Every derived row carries `producedBy`
  (model id + version). A new model is a *lazy backfill* — old rows stay valid and useful
  until something re-derives them — never a forced re-run over a 20 000-photo library. This
  is the same floor-and-upcast thinking as `core-schema-evolution.md`, applied to derived
  data instead of wire formats.
- ⚖️ **Provenance is recorded per model, in-repo.** A `docs/models.md` manifest lists each
  shipped model with its licence, its training-corpus provenance, and its hash. Several of
  the strongest candidates are permissively licensed *weights* trained on scraped corpora
  (IN-D7) — that is a "know exactly what you ship" item rather than an automatic blocker,
  but it needs a per-model decision and it belongs in front of counsel before release, not
  after.

### IN-D5 — Labels sync; embeddings and templates do not

This is the decision that makes the whole feature set legally and operationally cheap.

- **Precedent:** Fit's `LatestSample` rollup (`fit-read-scaling.md`) — locally derived,
  maintained by an observer, backfillable for stores that predate it. Derived data does not
  automatically deserve the wire.
- **Split by size *and* sensitivity:**
  - **Assignments and labels** — "this photo contains text `…`", "this photo is at Place
    *p*", "this photo shows Hatsune Miku", "this photo shows Anna" — are small, high-value
    across devices, and ordinary realm data. **Sync them.**
  - **Embeddings and face templates** — 512–1024 floats per item, and in the face case
    biometric — **stay device-local in v1** and are re-derivable from content the device
    already has. Each opted-in device derives its own.
- ⚠ The honest cost: a second device redoes the indexing work. Accept it for v1; the hub is
  the answer (one indexer, everyone benefits) and it is exactly the kind of overnight,
  plugged-in job the hub exists for.
- ⭐⭐ **The important move: name people per-photo, not per-cluster.** If the synced artifact
  were "cluster 7 is Anna", cluster identity would have to be stable across devices, which
  forces templates onto the wire. If instead the synced artifact is a plain label on a
  photo — *this photo shows Anna* — then clusters are a **local UI convenience for
  bulk-labelling**, the durable truth is a string the user typed, and **no biometric
  template ever becomes realm data.** Smaller, simpler, and it moves the feature out of the
  hardest part of Art. 9 rather than arguing about it.
- ⚠ Consequence to accept and state in the UI: clusters may differ between devices before
  labelling. The labels do not.

### IN-D6 — Batch indexing is background, metered, and one task

Indexing a library is not an interactive workload.

- Runs as `PlatformScheduler` idle+charging maintenance, never on the foreground path.
- Expressed as a **`TaskSpec` / `withTask` composite** (TS-3): one background task, one
  liveness op, one sync lane — not N thousand individual saves. The task-lane work exists
  precisely for this shape of consumer, and an indexer is its most extreme case yet.
- ⚠ **Known scaling trap:** `RecursiveListWalk` loses the projection, so a naive walk
  materialises every stored value property including `File.Thumbnail` blobs for every row
  (recorded in `ios-drive-parity.md` §24.5 and hit first by the offline keeper). An indexer
  that walks a large library this way dies on heap long before it saturates compute. Thread
  the projection, or drive the walk from a thin id list.
- Progress must be visible and pausable. First-index of a large library is a multi-hour,
  battery-visible operation and silently doing it is the kind of thing this product exists
  to not do. ◐ **The mechanism SHIPPED 2026-09-04 — §29**: a device-scoped pause the pass honours
  per image, and a progress holder ticked per image. ⚠ Nothing renders either yet — the stream op
  and the UI are 1e-3, so *visible* is half-kept.

### IN-D6a — New arrivals are indexed without being asked; the backlog is not (2026-11-11)

**Decided by the maintainer, 2026-11-11.** Narrows IN-D6; it does not replace it.

- **Why.** IN-D6's reason is the *first index of a large library* — "a multi-hour,
  battery-visible operation" that must never run silently. It was never written for one new
  photo, which costs ~33 ms for its search vector and a few hundred ms for faces (§9.7 of
  `ios-on-device-inference.md`). Applied to a single upload it reads as "I added a photo of
  myself and People never found me", which is not what a user expects. Found on the iPhone in
  `ios-face-grouping.md`'s F8 device pass.
- **How others do it.** OneDrive and Google Photos analyse on their servers after upload, which
  is why it looks instant there; HR 1 (the relay sees ciphertext only) rules that out here.
  Apple Photos, the on-device analogue, does most of its analysis while locked and charging,
  plus some light work at capture. IN-D6a is that shape: bulk overnight, small batches at once.
- **The rule.** A *small batch* of photos no pass has seen yet — local uploads and files synced
  in from other devices alike — is indexed without a press, for each capability whose own
  consent is on and whose own model serves (absence = NO, unchanged; IN-D3's per-half gating,
  unchanged). ⚠ The cap is a constant to measure, starting at **50 photos per batch**; anything
  beyond it is left to the scheduled pass or *Index now*, and never chained into a long run.
  Progress shows on the same surface *Index now* uses (§34), so it is not silent.
- **Same runner.** It goes through `MediaIndexRunner` (§34.3): single-flight with *Index now*
  and the scheduled pass, never a second runner.
- **⚖️ The platforms differ on purpose**, because their background rules do:
  - **Android (and desktop):** Control is long-lived and WorkManager runs a short job reliably
    in the background. A **debounced one-time job** (about two minutes after the last arrival)
    runs the small batch with **battery-not-low** as its only constraint — Photos need not be
    open. A short job needs no foreground-service notification. The backlog stays on the
    12-hour idle+charging pass (`MediaIndexSchedule.kt`). ⭐ Built 2026-11-11 — as-built below.
  - **iOS:** no background work at arrival, ever — about 30 s of time, and a suspended app
    holding the store lock is the `0xdead10cc` kill (`ios-on-device-inference.md` §3.3). The
    small batch runs **while Photos is in the foreground**, under `ForegroundIndexingConditions`
    (thermal, Low Power Mode, battery), and stops when Photos leaves the foreground. The backlog
    goes to PR-6's overnight `BGProcessingTask` (⭐ built 2026-11-11 behind the AI flag,
    `ios-on-device-inference.md` §16; 📱 its nights owed). Planned as **PR-6b** in
    `ios-on-device-inference.md`.
- **Open:** the batch cap (measure on the floor-class phone); what "arrived" is detected from on
  iOS (the library watch's revision, `CollectionsViewModel`) — never by walking the whole library to
  find out. Android and desktop: decided, below.

#### IN-D6a as built — Android and desktop (2026-11-11)

- **"Arrived" is a modification row, not a listing.** ⚠ The doc said "a listing change"; no listing
  holds the photographs — they live in any folder, and watching every folder's array is the walk this
  must not be. Every file write op stamps a `FileModification` row and a peer's row syncs in with the
  file, so `NewArrivalsObserver` (`control-shared-proxy`, `NewArrivalsIndexing.kt`) — a
  `DataObserver` — takes the **path** of each saved `Created`/`Moved`/`Renamed` row for an image
  outside the trash, **whatever its origin**. A type-id prefix test rejects every other save before a
  schema is looked up. ⚠ Moved and renamed count (the stores are keyed by path); a content change at
  the same path does not (nothing re-indexes it).
  - ⚠⚠ **Two blind spots:** a file op writes these rows only when it has a device id (production
    always passes one); and a shared realm's `SharedFile` content writes none, so a photograph that
    arrives in a **shared folder** is not seen — the scheduled pass is its route.
- ⚠⚠ **The batch indexes the ARRIVED paths and nothing else — never a walk.** A first build walked
  the library to find "photos no pass has seen" and capped that: one upload then cost a whole-tree
  read, a whole-library reconcile and per-photo checks on battery, every two minutes under a trickle —
  and with any backlog the 50 slots went to the first backlog photos in tree order, so **the photograph
  that arrived might never be indexed**, the exact failure IN-D6a exists for. As built:
  - `ArrivalQueue` (`:inference-service-proxy`, general — iOS's foreground batch can use it): the
    arrived paths, de-duplicated, in arrival order, **bounded at 500** (past it the oldest fall to the
    backlog), persisted to `/inference/arrivals.queue` in the device-local inference file store (a
    WorkManager input is limited to 10 KB). ⚠ Flushed on the scheduler's cadence, not per save: an
    arrival in the last seconds before the process dies is the scheduled pass's.
  - `MediaIndexRunner.runPathsBatch(ec, paths, cap, bar)` — **general "index these paths"** — reads
    them through `MediaSource.mediaAt`, which Control answers with **point reads** (`livePaths` per
    path), and reconciles nothing (`wholeLibrary = null`). The bar counts the batch, not the library.
    `runArrivalsBatch(ec, queue)` is that over the queue, settled afterwards.
  - Per path (`PathOutcome`), settled **after** the photograph's work: indexed / already current /
    nothing to do → leaves; **no bytes and no preview** (relay-only on a metered network), **a half that
    failed on it**, or ⚠⚠ **no live row yet** → stays for 3 batches, then leaves; not reached (cap,
    deferral) → stays for the next batch. ⚠ "No row" is retried rather than dropped because a peer's
    modification row can be applied before its `File` row. A batch that did not finish (superseded,
    stopped) settles nothing.
  - ⚠⚠ **The queue is the realm's**: `wipeDerivedInferenceState` (every platform's
    `onLocalStoreWiped`) clears it with the vectors and templates — memory and file — and so do iOS's
    custody on a realm change or relinquish and `eraseAtRest`, and a batch that finds **every** half
    explicitly withdrawn (absence keeps it). File names can say a lot; the next realm's first batch
    must not work them.
  - ⚠ `MediaSource.mediaAt`'s default walks and filters — correct, and the cost the batch exists to
    avoid — so it logs that it did; Control overrides it. (Not abstract only because `MediaSource` is
    a `fun interface`.)
  - ⚠⚠ **A path batch is no library coverage**: `IndexPassResult.libraryWalk` is set for walks only,
    and the Models screen's "N of M aren't on this device" is recorded from those alone — a batch of
    three must not read "3 of 3" over "1,290 of 1,300".
- **The cap — `NEW_ARRIVALS_BATCH_CAP = 50`** — counts photographs **whose bytes were read**. ⚠⚠ An
  unavailable photograph does not spend it, or relay-only arrivals would exhaust every batch for ever.
  It BREAKS with `IndexPassResult.batchCapReached`; ⚠⚠ nothing re-runs it — what is left waits for the
  next arrival's batch, the 12 h pass or *Index now*.
- **Its own bar, `PassBar.ARRIVALS`.** `IndexingConditions.allowArrivalIndexing()`: battery not low,
  and ⚠⚠ **unknown is NO** — nobody asked. Defaults to `false` (absent means never); Android (capacity
  > 15 %) and desktop (no macOS low-battery warning; Windows/Linux never) override it. The 12 h pass
  keeps charging + not in use, untouched.
- **The ONE runner.** `runPathsBatch` returns `null` while any pass runs (never joined: a 10-minute
  job waiting on an hours-long pass would be stopped, and its stop would end that pass); refuses
  before taking the slot when no half may run (`MediaIndexer.anyHalfMayRun`) or the bar says no — ⚠⚠
  and **still deletes what a face-consent withdrawal owes**, as the pass's own refusals do (now true of
  `runToCompletion`'s pre-check too). A press and the scheduled pass REPLACE a running batch.
  ⚠ iOS's wake expiry (`cancelRunningScheduledPass`) does **not** stop one: it is not the wake's, and
  its own trigger owns its end.
- **Android:** `ThrottledArrivalScheduler` arms `NewArrivalsIndexWorker` — a plain `CoroutineWorker`,
  one-time, `REPLACE` + `setInitialDelay(2 min)` + `setRequiresBatteryNotLow(true)` alone — looking at
  most once per 15 s:
  - no job / a finished one → arm; a waiting one → push it back, ⚠⚠ but only while it is still **due
    within 10 minutes of its first arm** (`NEW_ARRIVALS_MAX_LATENCY_MS`, the 2-minute delay counted),
    so an hours-long sync cannot starve it. ⚠ The first-arm time is per process — a Control restarted
    mid-stream starts a fresh window — and a re-arm of a job waiting on a retry resets its WorkManager
    attempt count, so the retry bound is per armed job;
  - ⚠⚠ a **running** one → left alone and looked at again next window: `REPLACE` would cancel the
    batch. Its arrivals wait on the queue. ⚠ One race remains — a job that starts between the state
    read and the write is cancelled before its first stamp, and the next arm re-runs it.
  - A regular job's 10-minute limit is far above 50 photographs, so no foreground notification.
    A deferral, and a cold wake that finds no proxy session, retry once and are then left; no model /
    no consent is not retried. ⚠ The `Schedule` op was not used: it dispatches an op by id, and a new
    op is a schema addition this needed no reason for.
  - ⚠⚠ **"About two minutes" is the best case.** A one-time job is subject to App Standby buckets and
    Doze; for Control in the *rare* or *restricted* bucket Android may hold it for hours. The queue
    keeps the paths meanwhile.
- **Desktop:** no WorkManager — `InProcessArrivalScheduler` runs the batch 2 min after the last
  arrival or 10 min after the first, one batch at a time; the batch's own bar is its only constraint.
- **Progress** is the runner's: the same `IndexProgressHolder` *Index now* publishes into, so Control's
  Models screen shows the batch — its own total, since the batch is its paths. No Android notification.
- **Pinned:** `NewArrivalsBatchTest`, `NewArrivalsIndexingTest`, `NewArrivalsIndexWorkerTest`,
  `DesktopIndexingConditionsTest`; each guard mutation-checked. 📱 Owed on a phone: an upload and a
  synced-in photo each indexed after the debounce with Photos closed; a low battery deferring it; the
  cap measured on the floor-class phone.
- **As built — iOS (PR-6b, 2026-11-11, behind the AI flag; 📱 owed):** `ios-on-device-inference.md`
  §17. The library watch's revision, while Photos is in front, is the cue; **which paths arrived** is
  read from the rows of the same modification log **past a persisted cursor** (`ArrivedPhotoPaths`,
  at most 500, never a walk; no cursor ⇒ nothing queued, the wake's) into the shared `ArrivalQueue`,
  bound in Photos' graph and stored in Photos' private inference store (so the wipe and custody above
  reach it), and the batch is the shared `runArrivalsBatch`, with iOS's `MediaSource.mediaAt` a point
  read. ⚠ The log is not read unless a half may run. The queue is flushed on every change (the look, the
  batch's settle), never at the leave. ⚠ Two iOS differences (§17.3): the batch **yields to a running wake** rather
  than replacing it, and it is cancelled when Photos leaves the foreground. ⚠ "Never chained" is per
  foreground; the next leave still arms PR-6's wake after a capped batch.

### IN-D7 — Character and content tagging: buy the vocabulary, never train

The interesting question — *can we recognise characters from games and films in cosplay
photos, fan art, and screenshots?* — has an unusually good answer in one domain and an
unusually bad answer in the other, and the split is worth recording because it is not
obvious.

**The anime / illustration / game-art domain is genuinely served off the shelf.** The
fandom has already built and published exactly this:

- **Danbooru-trained multi-label taggers** — the "WD tagger" family (SmilingWolf's
  `wd-v1-4-*` and the v3 SwinV2 / ConvNeXt / ViT / EVA02 variants). They emit a ~10 000-tag
  vocabulary that includes **character tags** (`hatsune_miku`, `artoria_pendragon`) and
  **copyright / series tags** (`genshin_impact`, `fate/grand_order`) alongside general
  content tags. ONNX exports are published, so they drop straight onto the IN-D2 runtime.
  Sizes range from roughly 100 MB (smaller ConvNeXt/ViT) to around 1 GB (EVA02-large).
- **DeepDanbooru** — the older TensorFlow equivalent; same idea, weaker, largely superseded.
- The `deepghs`/`imgutils` ecosystem publishes anime **face and character detection** plus
  character-embedding models in ONNX — useful for cropping before tagging.

So for anime, manga, game art, and cosplay *of* those characters, a single optional
download gives real character names with **zero training on our side**. ⭐ And the same
model's general tags ("umbrella", "night", "sword") are a search feature in their own right
for an illustration collection — a second feature for the same bytes.

**Live-action is the opposite, and no amount of effort fixes it.** There is no open
"recognise the film character" model, and there structurally cannot be a good one:
identifying a live-action character reduces to identifying the *actor*, which needs a
celebrity gallery we will not ship — and it fails on cosplay **by construction**, since a
cosplayer is precisely not the actor. What *is* tractable is franchise- or costume-level
recognition ("this is Star Wars"), which is a zero-shot embedding job, not a classifier.

**The tier that costs nothing extra.** CLIP/SigLIP-class image embeddings are already the
substrate for semantic photo search and for Mia's retrieval (MQ-D5). Given them, two things
fall out free:

1. **Zero-shot franchise labels** by comparing image embeddings against text prompts ("a
   photo of someone cosplaying Link from The Legend of Zelda"). Decent at franchise level,
   mediocre at fine-grained character identity.
2. ⭐⭐ **Few-shot, user-taught characters.** The user tags one photo *"this is Zelda"*; we
   store that embedding as a prototype; nearest-neighbour does the rest. **No training, no
   shipped celebrity gallery, entirely on-device** — and it is *the user's own vocabulary*,
   which is both better product (their original characters, their friends' cosplays,
   obscure characters no public model knows) and exactly the privacy posture. It also
   reuses IN-D5 unchanged: the prototype is a local embedding, the durable synced artifact
   is the name the user typed.

**Recommended ladder:** embeddings (free, general, user-taught) → optional anime/game
tagger download (real names, one domain, opt-in) → **never train a custom classifier**.
Training is a data-collection and maintenance commitment this project has no reason to take
on, and IN-D4's lazy-backfill rule means a better public model can simply replace ours.

⚠ **Caveats to record before shipping a booru-trained tagger:**
- The vocabulary contains NSFW tags. Ship a **tag allowlist**, not the raw vocabulary.
- Weights are commonly Apache-2.0, but ⚠ **verify per checkpoint** — and the training
  corpus is scraped, which is the IN-D4 provenance item.
- These models are useless on photographs of real people and real places. The UI must not
  imply general "what is this" competence, or the first holiday photo will make it look
  broken.

---

## 3. What this unlocks in Drive

Recorded here because it motivated the doc, and because most of it is **not an ML problem**
— which is the useful finding. Drive's parity story with Google/OneDrive, cheapest first:

1. **Location on images is nearly free and needs no model.** EXIF GPS → reverse geocode →
   `Place`. The `:place-service` + G1–G5 geocoding track is shipped, including the
   proxy-mediated egress that keeps the app INTERNET-free. ⭐ Drive photos folding into the
   same `Place` spine Timeline already uses means "photos from that café" works across
   *both* apps for the cost of a projection. Landmark recognition from pixels is real ML
   and far lower value once EXIF is handled — a later refinement, not the feature.
2. **OCR is the cheapest genuine ML win** — mature, small, free on iOS via Vision, and
   enormous search value (receipts, screenshots, whiteboards, documents photographed in the
   wild).
3. **Semantic search** (image embeddings) is the one that most feels like Google Photos —
   and it is the same index Mia needs. One index, two apps.
4. **Face clustering** is the expensive one in compute, consent, and legal surface. IN-D3
   puts it off by default in Settings; IN-D5 keeps templates off the wire.
5. **Character tagging** (IN-D7) is the one nobody else ships, and it is nearly free once
   (3) exists.

## 4. Threat model duties

Per House Rule #2, amendments land with the slice that creates each surface:

- **New in-use plaintext locations.** On a phone or desktop this is not new — the app
  already holds decrypted content. On the **hub / co-computer** it is, and
  `hub-hardware-and-management-service.md` §5.3 already flags it; the slice that lands
  remote inference must reflect it in `threat-model.md` (A5 family).
- **Model-fetch egress.** The request itself carries no user content, but ⚠ *which model
  you fetch reveals which capability you enabled*. It goes through Control like every other
  egress; whether to pad or batch the fetch set is a judgement call to make when the slice
  is real, not to over-engineer now.
- **Derived rows as new realm data.** Labels naming people are special-category data under
  Art. 9 even inside an E2E realm. IN-D5's per-photo-label shape is what keeps this to
  "strings the user typed" rather than biometric templates — say so in the amendment.
- **A generative layer** (if MQ-5 ever lands) adds prompts as a new in-use plaintext class,
  and prompts are often more revealing than the data they retrieve (see MQ-D8).

## 5. Phasing

⚠ **Superseded by §7.6** (C0, 2026-08-12), which collapses IN-2/IN-3 and lifts voice out. Kept
for the per-slice rationale, which still holds.

Each slice is independently useful and every one is provable without hardware, on
`FakeInferenceBackend` plus a single real model.

- **IN-1 — the seam.** Module set, task operations, `InferenceBackend`,
  `FakeInferenceBackend`, capability registry + `PreferenceService` wiring (IN-D3), the
  `producedBy` stamp on derived rows. **No model ships.** Everything downstream becomes
  testable.
- **IN-2 — first real runtime + first real capability: OCR.** ONNX Runtime on
  Android/desktop, Vision on iOS; model fetch op + hash pinning + the `docs/models.md`
  manifest. OCR chosen first: no persons, no consent complexity, immediate search value.
- **IN-3 — image embeddings + the index.** The shared substrate for Drive semantic search
  and Mia retrieval (MQ-4). Batch indexing per IN-D6 (task composite, idle+charging,
  projection-safe walk).
- **IN-4 — faces, opt-in.** Detection + clustering + the per-photo-label shape (IN-D5).
  Settings-only entry, off by default. Ships with the threat-model amendment and the ⚖️
  counsel item resolved.
- **IN-5 — character/content tagging.** User-taught prototypes first (free, given IN-3),
  then the optional booru-tagger download with a tag allowlist.
- **Later:** remote execution binding (hub/co-computer) — reuses the seam, no consumer
  changes; speech-to-text with the voice track; CM-4 on-hub detection.

## 6. Pointers

- [`hub-hardware-and-management-service.md`](hub-hardware-and-management-service.md) §5 —
  tiered inference, the base-NPU/co-computer split, §5.3 in-use plaintext.
- [`mia-query-layer.md`](mia-query-layer.md) — the first consumer; MQ-D5 is IN-3's client.
- [`camera-service.md`](camera-service.md) — CD-D7 (the face-recognition policy exclusion
  IN-D3 reconciles) and CM-4 (on-hub detection, a later consumer).
- [`audio-and-voice.md`](audio-and-voice.md) AV-D6 — where STT runs for **satellites and the
  hub**. ⚠ IN-D11 decides the *phone/desktop* case, which AV-D6 does not cover.
- [`maps-region-delivery.md`](maps-region-delivery.md) D1–D9 — the shipped shape IN-D10
  inherits: signed catalog, static storage + CDN, proxy-owned store and download manager.
- [`retention-quota-model.md`](retention-quota-model.md) P2 — the local cache ceiling a
  downloaded model has to be visible in (IN-D10).
- [`place-geocoding.md`](place-geocoding.md) / [`place-imagery.md`](place-imagery.md) —
  the proxy-mediated egress pattern IN-D4 reuses, and the `Place` spine §3(1) folds into.
- [`file-content-addressing.md`](file-content-addressing.md) — content-hash identity, the
  shape IN-D4 borrows for models.
- [`fit-read-scaling.md`](fit-read-scaling.md) — `LatestSample`, the locally-derived +
  backfill precedent behind IN-D5.
- [`core-task-composite.md`](core-task-composite.md) §5–§6 — `TaskSpec`/`withTask`, the
  IN-D6 batching shape.
- [`core-background-processing.md`](core-background-processing.md) — `PlatformScheduler`,
  idle+charging maintenance.

---

## 7. C0 — the decisions this track was blocked on (2026-08-12)

v0.1 settled the *seam* (IN-D1–D7) and left the choices that actually cost money open:
which model, where it is hosted, what the labels are, where voice fits, and how any of it
reaches a query. Those are pinned here because they were being decided implicitly, one slice
at a time, which is how a track ends up with three model families and no re-index story.

⚠ Nothing below is built. This section is what a reviewer should disagree with *now*, not
after IN-2 ships. ⭐ **IN-D9 has already been revised once on that basis** (2026-08-12) — its
first draft was a flat label list with a blanket ban on special-category labels, and both halves
were wrong in instructive ways. The revision is recorded inline rather than silently applied.

### IN-D8 — One model family for classification **and** search: an image–text embedding model

The instinct is that "tag my photos" and "search my photos by description" are two features
needing two models. They are not, and picking the obvious tool for the first one forecloses
the second.

- **A fixed-vocabulary classifier** (MobileNet/EfficientNet-class, ImageNet-1k) emits a label
  and nothing else. To add search later you ship a *second* model and **re-index the entire
  library**.
- **An image–text embedding model** (the CLIP/SigLIP family) emits a vector. From that one
  vector you get **both**: zero-shot classification against any label list — encode the label
  texts once, compare — **and** the semantic index MQ-D5 and IN-3 need.

⭐⭐ **So the taxonomy becomes data rather than weights.** Adding "receipt" to the vocabulary
next year is a label-list change, not a model change, and it costs **no re-index** because the
embeddings are unchanged. That property is worth more than a few points of per-label accuracy,
and it is unavailable under the classifier route at any price.

⚠ **The honest cost.** Zero-shot is *weaker* than a classifier fine-tuned on a fixed set, and
each label needs its own calibrated threshold — one global cutoff will over-fire on some labels
and miss on others. Expect a calibration pass over a real library, not a constant.

⚠ These models carry documented dataset biases and are trained on scraped web corpora. That is
an IN-D4 `docs/models.md` provenance item, and it is a ⚖️ counsel question before release, not
a blocker for prototyping.

⚖️ **Candidate families, verification owed per candidate** — ONNX export, licence, size, and
on-device latency all need checking rather than assuming: OpenCLIP ViT-B/32 (the baseline
everyone compares to), SigLIP (stronger per parameter), MobileCLIP (designed for on-device).
⚠ Do **not** treat the numbers in any blog post as our numbers; the measurement that matters is
on a mid-range Android phone against a real library. The comparison lives in
[`docs/models.md`](../models.md) §3, filled in 2026-08-12.

⭐ **Measured 2026-08-14 ([`models.md`](../models.md) §3.5), and it inverts the obvious
assumption.** For SigLIP v1 on a desktop CPU EP, **uint8 is 3.5× faster than fp32** (24 ms/image
against 84 ms, 90 MB against 355 MB) at **mean cosine 0.981** drift, worst 0.965, with every
nearest-neighbour pairing unchanged — retrieval order survives, the scores move.

⚠⚠ **The other three quantisations are traps, so pick by measurement rather than by size:**

| variant | outcome |
|---|---|
| **fp16** | **slower** than fp32 (147 ms) — CPUs have no native half-precision compute and pay the conversion |
| **`quantized`** | slowest of all (218 ms) |
| **int8** | ❌ **does not run at all** — `ConvInteger` is unimplemented in ONNX Runtime's Java CPU build |

⚠⚠ "Smaller is faster" is false here twice over, and nothing about the `int8` artifact warns you:
it is **the same 90 MB as uint8** and only fails when the runtime is asked to execute it, so a
size-led choice picks the one variant that cannot run. ⚠ These
are **desktop** figures and therefore a **floor**: the on-device measurement is IN-3m (§15), and the emulator
must never stand in for it (host CPU, Android costume).

#### Revision 2026-08-12 — ⚠⚠ a platform "foundation model" does **not** satisfy this decision

Raised on maintainer review: *"on Apple we could likely use the platform's Foundation Models and
get hardware acceleration, on Android as well"*. The instinct is right about acceleration and
wrong about the model, and the difference is worth recording because it will be raised again
every time a platform ships a headline model.

**Apple's Foundation Models framework (iOS/macOS 26) and Gemini Nano via AICore are on-device
*text* LLMs.** Neither exposes a **joint image–text embedding space**, which is the entire
property this decision rests on: the phrase the user types has to land in the same vector space
as the photograph. A text LLM cannot put it there.

What Apple's Vision framework *does* give free is real and already in IN-D2's split:

| Vision request | What it is | Serves |
|---|---|---|
| `VNRecognizeTextRequest` | OCR on the Neural Engine, zero model bytes | ✅ IN-5, outright |
| `VNGenerateImageFeaturePrintRequest` | an image embedding | ⚠⚠ **image ↔ image only** — there is no text side, so "photos of a cat" is unreachable |
| face **detection** | boxes, not identity | ✅ IN-6's cheap half |
| `VNClassifyImageRequest` | a fixed Apple taxonomy | ⚠ their vocabulary, not IN-D9's, and not extensible as data |

⚠ On Android the platform options fail an earlier test rather than this one: ML Kit and
Gemini Nano/AICore are Play-Services-coupled and absent on de-Googled devices, which **IN-D2
already rejects** — the same posture that makes Timeline's capture use bare `LocationManager`.

⭐ **So the shipped model is ours on every platform, and the platform contributes *acceleration*
and the capabilities it happens to own outright.** That is IN-D13.

#### The alternative considered and declined: caption-then-search

Use the platform LLM to caption each photo, embed the caption with a text model, and search the
captions. It needs no CLIP-class weights of ours and rides the free accelerator.

⚠ Declined because a caption is **lossy in a way an embedding is not**: everything the caption
did not happen to mention is unfindable, and what it mentions is the model's idea of salience
rather than the user's. It is also slower per image (generation, not a single forward pass), and
it makes the searchable surface depend on a platform model that differs per OS version — so two
of the user's devices would disagree about what is in the same photograph.

Recorded so the next person weighing "but the platform model is free" finds the reasoning rather
than re-deriving it.

### IN-D9 — The vocabulary is a shallow **hierarchy**, and special-category inference is a separate capability

*(Revised 2026-08-12 on maintainer review. The first draft said "a flat curated list" and
"the list must not contain" — both were the easy version of a harder question, and both were
challenged correctly. What changed and why is recorded inline, because the reasoning is the
useful part.)*

The OneDrive/Google Photos experience — "cat", "beach", "receipt" — is a **retrieval**
vocabulary, not a classification benchmark.

**Shape: a shallow hierarchy, not a flat list.** `Animals → Dog → Corgi`. That is how photo
search actually works, and it is what lets a future Drive filter drill down instead of showing
300 checkboxes.

- ⚠ **The first draft's dig at ImageNet-1k was aimed at the wrong thing.** Its problem is not
  that fine-grained labels *exist* — breeds are genuinely useful — it is the **imbalance and the
  omissions**: a vocabulary shaped by a 2012 benchmark, heavy on species, with no *receipt*, no
  *screenshot*, no *whiteboard*, no *document*. Inheriting it because the model shipped with it
  would still be choosing a vocabulary by accident; inheriting its granularity would not.
- **~150–300 leaf labels to start**, chosen from what people search for, versioned as a
  `labelSetVersion` alongside IN-D4's `producedBy`.
- ⭐⭐ **The hierarchy is data, so none of this has to be right now.** Under IN-D8 the label set
  is text, not weights: ship coarse tiers first, add breeds later where they prove reliable, and
  **nothing re-indexes**. A taxonomy decision that would ordinarily be irreversible is a
  revisable one here, which is the strongest practical argument for IN-D8 that is not about
  accuracy.

**⚠⚠ Confidence has to work differently per tier, and this is the part that decides whether
fine-grained labels help or hurt.**

- **At the top, independent thresholds.** "Is there a dog here at all?" is a question a
  zero-shot model answers reliably, and the labels are not mutually exclusive — a photo can be
  both *beach* and *dog*.
- **At the leaves, argmax within a sibling set plus a margin test.** Breeds are mutually
  confusable, so independent thresholds either fire several at once or none. Pick the best
  sibling — and ⚠ **if the top two are close, emit the parent and stop.** Degrading to "dog" is
  the honest answer; a confidently wrong "Corgi" on someone's shiba inu is worse than no breed
  at all, because it teaches the user the whole feature is unreliable.
- ⚠ Which tiers earn leaves is an **empirical** question per branch, not a uniform rule. Breeds
  may work; distinguishing bird species may not. That is threshold-calibration work (§7.7), and
  it needs a labelled sample we do not have.

**⚠⚠ Special-category inference: not in the default vocabulary — and not banned forever either.**

The first draft said the list "must not contain" labels inferring health, religion, ethnicity,
sexuality or politics. That is the right instinct and the wrong rule, because it forecloses
things the platform may legitimately want later.

The distinction that actually matters is **given versus inferred**:

- Fit already holds health data, because the user *deliberately imported it* from Health
  Connect / HealthKit behind an explicit consent surface. Mia reading that is ordinary.
- **Deriving** a health attribute from a photograph is a different act: unsolicited, never asked
  for, and it can be **wrong**. "Mia noticed you look pregnant" is a catastrophic failure mode in
  a way that "your Health Connect import says X" simply is not.

So the rule is the one this doc already applies to faces:

> **Special-category inference is never part of the default vocabulary.** A capability that
> genuinely wants it earns its **own** opt-in with its own consent surface, exactly as face
> clustering does (IN-D3 / IN-D5) — off by default, Settings-only, and shipping with its own
> threat-model amendment.

⚠ **Why the control has to sit at creation, not at query time.** IN-D5 syncs labels. The moment
a special-category label is *written* it is realm data on every device — so filtering it out of
a search result changes nothing about what is held. Restricting the label set that runs is the
same mechanism as "only tagging what we need", and it is the only one that works.

⚠ **This is not a performance argument.** Zero-shot classification costs one dot product per
label, so a 300-label vocabulary is free at inference time and excluding labels buys nothing.
The reason is entirely about what data comes into existence.

⚠ **And the line is not always clean.** *Wheelchair* is ordinary scene content as an object, but
a vocabulary containing it lets someone filter people by disability. Naming that tension is more
honest than pretending a keyword list resolves it; the per-label review is a judgement call the
slice that ships the vocabulary has to actually make.

- ⚠ Faces stay out of this list entirely — they are IN-D4/IN-D5's separate, off-by-default
  capability with its own consent surface.

### IN-D10 — Model distribution is the region-delivery problem again; inherit it, and amend IN-D4

`maps-region-delivery.md` D1–D9 already solved "ship large, immutable, public binaries to
clients that must not talk to the internet", and it is **built**. Re-deriving it would be the
expensive kind of not-invented-here.

Inherited wholesale:

- **D1** — static object storage behind a CDN, **no serving compute**.
- **D3** — a **Tenshu-signed catalog** is the client's only discovery surface.
- **D5/D6** — the **proxy** owns the canonical store and the download manager; delivery is
  proxy-mediated, so apps stay INTERNET-free.

⚠⚠ **This amends IN-D4's "hash pinned in source".** Pinning each model's hash in source means
**an app-store release to ship a model update** — including one that fixes a bad model. The
tiles precedent is better and already shipped: pin the **signing key** in source
(`TILES_MANIFEST_PUBLIC_KEY_HEX`), and let the *signed catalog* carry the hashes. The
supply-chain property IN-D4 wanted is preserved — a compromised CDN still cannot swap a model —
while revocation and rotation become possible. ⚠ The catalog must be versioned and its
signature verified before any byte is trusted, exactly as `RegionCatalogVerifier` does.

- ⚠ **Per-capability downloads, never one blob.** IN-D3 makes capabilities individually opt-in;
  a single bundle would make a user who wants OCR pay for face clustering they refused.
- **Control's UI mirrors the shipped Offline maps screen** — list, size, state (absent /
  downloading with progress / installed), delete — and the IN-D3 capability switches live
  beside it, because "what is enabled" and "what is downloaded" are two halves of one question.
  ⚠ Deleting a model must not silently disable the capability, nor vice versa; say which one the
  action does.
- ⚠ **Storage pressure is a real interaction.** `retention-quota-model.md` P2 shipped a local
  cache ceiling; a 500 MB model sitting in a per-device cache is exactly the kind of thing that
  should be *visible* there and evictable, and it must never count against the user's realm
  quota (IN-D4 already says models are not content-plane).

### IN-D11 — Phone and desktop STT is **on-device or absent** — and this is *not* AV-D6's question

⚠⚠ AV-D6 decided where STT runs for **satellites and the hub** (audio crosses your LAN, never
the internet). It says nothing about the case the Mia app actually has today: a phone with an
OS recogniser and a text field.

- **On-device only, and refuse rather than degrade.** Android's default `SpeechRecognizer` may
  route audio to a vendor service; the on-device variant (API 33+) does not. iOS's
  `SFSpeechRecognizer` exposes `requiresOnDeviceRecognition` with a `supportsOnDeviceRecognition`
  probe. ⚠ Using the cloud path silently would break the product's central claim for a feature
  nobody asked to be online — so when on-device recognition is unavailable, **the capability is
  absent and says so**, exactly as a de-Googled device gets no fused-location provider.
- ⚠ **Desktop has no OS STT worth using** ⇒ out of scope for v1. Whisper-via-ONNX on the same
  IN-D2 runtime is the later answer, and it is the same shape as the hub's.
- ⭐ **Voice needs no query-layer change at all.** MQ-1 was built for this: *a transcript is just
  an utterance*. Voice flips `MiaCapabilities.voiceInput` and feeds `askMia` — no new intent, no
  new op, no new validator surface. That makes voice **much cheaper than the model track** and
  independent of it.
- ⚠ It is not free, though: a spoken misheard slot is what MQ-D7's confirmation exists for, and
  the confirmation cell becomes load-bearing rather than polite the moment voice ships.

### IN-D12 — Labels are realm rows in their own service; ⚠⚠ semantic search needs an **op**, not a predicate

Where derived data lands, and how a question reaches it.

- **`:inference-service` owns the derived rows** (a `MediaLabel`-shaped row per
  `(file, label, confidence, producedBy, labelSetVersion)`), following the module conventions.
  ⚠ `:file-service` stays free of ML concepts — a file does not know what a model thinks of it,
  and putting labels on `File` would reshape the platform's hottest composite for one feature.
- ⭐ **Label search costs Mia nothing structural.** It is an MQ-2-shaped source adapter: "photos
  of cats" lowers to an ordinary `filter { … contains … }` over label rows joined to files, and
  it passes MQ-D10's validator like every other read.
- ⚠⚠ **But semantic search cannot be a chain predicate.** The DSL has `equalTo`, `contains` and
  (since MQ-2) `inRange` — nearest-neighbour over a vector is expressible as none of them, and
  no predicate we could add would make a *ranked* similarity query into a filter. Semantic
  retrieval therefore needs a **dedicated operation**, the `photosCapturedBetween` precedent:
  the op ranks and caps, and Mia's plan lowers to it exactly as `FindPhotos` already does.
  ⭐ Worth knowing now — MQ-4 was written assuming an index would slot in behind the existing
  chain, and it will not.
- ⚠ The op's cost profile is a scan until an index exists. A 20 000-photo library at 512 floats
  is ~40 MB of vectors — fine to hold, not fine to re-read per keystroke. That is a real
  design question for the slice that builds it, not now.

### IN-D13 — The platform contributes **acceleration and the capabilities it owns**, never the shipped model

The split IN-D2 drew by *runtime* is really a split by **who owns the model**, and IN-D8's
revision makes that concrete. Stated as a table, per capability rather than per platform:

| Capability | Apple | Android | Desktop | Whose model |
|---|---|---|---|---|
| Text in images (OCR) | Vision, free, ANE | ⚠ nothing acceptable (ML Kit rejected, IN-D2) | ours | **platform where free** |
| Semantic image search | ours | ours | ours | ⭐ **ours, one checkpoint everywhere**; one ONNX artifact, plus a Core ML artifact on iOS (amended below) |
| Content tagging | ours (same weights) | ours | ours | **ours** |
| Face *detection* | Vision, free | ⚠ nothing acceptable | ours | **platform where free** |
| Face *clustering* | ours | ours | ours | **ours** |
| Speech to text | on-device `SFSpeechRecognizer` (AV-1) | on-device `SpeechRecognizer` (AV-1) | ⚠ none (IN-D11) | **platform, or absent** |

**Acceleration needs no platform *model*.** ONNX Runtime's execution providers are exactly this
ladder, and it is a ladder rather than a fork:

```
CoreML EP (Apple)  ·  NNAPI / vendor EP (Android)  ·  DirectML / CUDA (desktop GPU)
                                   ↓  when unavailable
                          XNNPACK CPU — everywhere, slower
```

⚠ So "an older desktop" is not a fallback problem: it is the CPU floor, and the *same artifact*
runs on it. There is nothing to fall back **to**, because there was never a platform model in
this path to fall back **from**.

⚠⚠ **Amended 2026-10-04 for iOS, with IN-D2: the ladder above is not what iOS does.** The CoreML
execution provider over the shared ONNX artifact was measured on an iPhone and rejected — it
returned NaN in one format and failed to build in the other — while the same checkpoint converted
to a native fp16 Core ML model ran ~19× faster than the CPU floor and tracked fp32 retrieval more
closely than the ONNX pair ([`ios-on-device-inference.md`](ios-on-device-inference.md) §2.1–§2.2,
D1). So on iOS this capability takes a **second catalog entry**: the same model id and version,
`runtime: "coreml"`, selected by `supportedModelRuntimes`. The rule this decision exists for is
unchanged — the *model* is still ours and the platform contributes only acceleration — but
"one artifact everywhere" becomes "one checkpoint everywhere". Android, desktop and the hub keep
the single ONNX artifact and the EP ladder.

⚠⚠ **Amended 2026-09-01 by the first measurement of this ladder (§15.12.3): the arrow is not an
ordering.** On a OnePlus CPH2581, NNAPI (196 ms) and XNNPACK (147 ms) both placed the graph and both
**lost to ORT's default CPU EP (121 ms)** — so the "slower" rung won by 21 % and 62 %. What survives
is the claim that acceleration needs no platform model; what does not is *"try the accelerator
first"*. ⚠ A backend picks its EP by measuring on the device it is on, and **CPU is a legitimate
winner rather than a floor**. One phone is not a fleet — but that cuts the same way, since the
finding is precisely that the order is not knowable in advance.

⭐ And IN-D1's hub binding is the other answer to a slow machine: a co-computer with a GPU serves
the phone or the laptop, which is a *location* change and not a model change — the reason
`InferenceBackend` is task-shaped in the first place.

⭐⭐ **A property IN-D5 buys us that was not the reason for it.** Different execution providers
perturb floating-point results slightly, so the *same* model over the *same* bytes can produce
vectors that differ in the last decimals between a Mac and a phone. That would be a real problem
if vectors crossed the wire — and IN-D5 already says they do not, for bandwidth reasons. Labels
do cross, and a threshold absorbs that last-decimal noise. ⚠⚠ **It does not absorb a different
quantisation**: the uint8/int8 ONNX pair and the fp16 Core ML pair agree with a Jaccard of 0.784
over the labels either writes, each under its own floors, while Core ML against fp32 is 0.988
(`models.md` §3.10.5), and the same ONNX bytes on two onnxruntime versions differ as much
(§3.10.6). So labels from two artifacts are not interchangeable, and §39.1 decides what a
photograph's labels then are: the first writer's. The decision taken to save relay traffic still
removes the hazard for vectors.

⚠ **Consequence for the catalog:** a `models.json` entry already carries `runtime`; an entry
whose runtime this build cannot execute must be filtered exactly as an unreadable
`artifactVersion` is (IN-2, §10.2). Today's filter does not check `runtime` because no build can
execute anything — ⚠ **IN-3b owes that filter**, and forgetting it means downloading weights
nothing can run.

### 7.6 Revised phase map

C0 collapses v0.1's IN-2/IN-3 split (one model, one index pass) and lifts voice out of "later"
into its own track, because it depends on none of this.

| | Slice | Ships | Gated on |
|---|---|---|---|
| **IN-1** ✅ | The seam | Module set, `InferenceBackend` + `FakeInferenceBackend`, capability registry + IN-D3 preferences, `producedBy`. **No model.** **SHIPPED 2026-08-12** — as built §9 (⚠ two modules, not four: no op yet ⇒ no `-common`/`-client`). | — |
| **IN-2** ◐ | Distribution | Signed model catalog (IN-D10), proxy-side store + download manager, Control UI beside Offline maps, `docs/models.md`. **No inference.** **SHIPPED 2026-08-12**: catalog + docs (§10), then the durable store + HTTP fetch (§11). ⚠⚠ The Control screen moved to **IN-2c, gated on IN-3's op surface** — it reads through *operations*, and those need the `-common`/`-client` modules IN-3 needs anyway (§11.1). | IN-1 |
| **IN-3** ◐ | Embeddings + labels | The IN-D8 model, one index pass producing both vectors and IN-D9 labels; IN-D6 batch indexing; `MediaLabel` rows. **IN-3a SHIPPED 2026-08-12** (§12): rows + the pass, against the fake. ⚠ **IN-3b step 1 + R3 SHIPPED 2026-09-02** (§20, §21): the ONNX backend and the text half, JVM + Android, CPU EP. ⚠⚠ Remaining: IN-D6's scheduling — **nothing indexes yet** (§21.3), the last step before it works on a *side-loaded* device — and §19.2's **1c**, without which a user still has no route to a model (**1b** shipped 2026-09-03, §22, and is inert until a key and a catalog exist). ✅ The model is pinned (`models.md` §3.8) and ✅ **IN-3m is measured** (§15.12) — the last gate is ⚖️ counsel on R9. ⚠ IN-3b now inherits three constraints: a required idle+charging gate, a resumable progress surface, and an EP chosen by measurement (§15.12.3). | IN-2 |
| **IN-2c** | Control UI | The Offline-maps-shaped model list. ⚠ **Ungated since IN-4b** (§16): the service now has an op surface, so the `-common`/`-client` modules Control reads through exist. Its own ops (list/install/remove a model) still have to be written. | IN-4b |
| **IN-3m** ✅ | **Wall time and battery, on real hardware** | The desktop harness as a `connectedAndroidTest`, because §3.5's figures are a desktop **floor**. Spec §15; harness §15.11. **SHIPPED — measured 2026-09-01 on a OnePlus CPH2581 (§15.12): 121 ms/image, §15.9 row 3 did NOT fire.** ⚠⚠ No throttle within a pass, **1.65× across a warm one** — plan against the warm tail. | — |
| **IN-4** ◐ | Consumption | Drive label search + Mia's label source adapter; the IN-D12 similarity **op** for semantic retrieval (this is MQ-4). **IN-4a SHIPPED 2026-08-12** (§14): Mia's label source. **IN-4b SHIPPED 2026-08-14** (§16): the vector sink + `photosLike`, so the op MQ-4 lowers to now exists. ⚠ Remaining: Mia's planner lowering to it, Drive's own search UI, content ∧ place. | IN-3 |
| **IN-5** | OCR | Second capability on the same seam. ⚠ Demoted from v0.1's "first" — it needs a *second* model, and IN-D8's one-model-two-features argument makes embeddings the cheaper opener. | IN-2 |
| **IN-6** | Faces | Opt-in, off by default, per-photo labels (IN-D5). Ships with its threat-model amendment and the ⚖️ counsel item. | IN-3 |
| **IN-7** | Character tagging | User-taught prototypes (free given IN-3), then the optional booru tagger. | IN-3 |
| **AV-1** ✅ | Voice input | On-device STT → `askMia` (IN-D11). ⚠ **Depends on none of the above.** **SHIPPED 2026-08-12 (Android)** — as built §8; iOS + desktop remain. | — |

### 7.7 Still open

- ⚖️ **Model choice is a recommendation, not a decision** (IN-D8): the candidate families need
  measuring on a mid-range phone against a real library before one is pinned. ⭐ **Narrowed
  2026-08-12**: IN-D8's revision rules out the platform-foundation-model shortcut (those are text
  LLMs, with no joint image–text space), IN-D13 fixes what the platform *does* contribute, and
  `docs/models.md` §3.1–§3.3 names the four candidate families and **the two measurements that
  gate IN-3b**. What is left is genuinely a decision — licence + corpus per checkpoint, and the
  numbers — not a design question.
- ⚖️ **Licence and training-corpus provenance per candidate** — `docs/models.md` does not exist
  yet, and it is the artifact counsel will actually read.
- **Where the catalog and models are hosted.** Region delivery answered this for tiles; whether
  models share that bucket/CDN or get their own is an ops decision with a cost attached.
- **Threshold calibration** (IN-D8) needs a labelled sample to calibrate *against*, and we have
  no such sample. ⚠ IN-D9's revision makes this bigger than it looks: *which branches of the
  hierarchy earn leaf labels at all* is decided by that calibration, not in advance.
  ⭐ **Partly narrowed 2026-08-14** by the first zero-shot read (`models.md` §3.7): the model's own
  calibration is recoverable from its graph rather than guessable — SigLIP scores
  `sigmoid(117.33·cos − 12.93)` (⚠ the `-224` checkpoint's; the pinned one's is
  `111.00·cos − 12.89`, `models.md` §3.10.8) — so what a labelled sample is still needed for is the *per-label
  cut-off*, not the scale. ⚠⚠ And the scale is nothing like the intuition: **no (image, label) pair
  in a real library reached p = 0.5**, so a threshold that sounds cautious rejects everything.
  ⭐ **A first labelled read exists, 2026-10-10** (`models.md` §3.10; §37 here): COCO val2017, the
  37 candidate labels with an object category behind them. ⚠⚠ It scores no scene, document,
  screenshot or event label, and it is not a library.
- **The per-label review** IN-D9 ends on — deciding case by case where scene content shades into
  an attribute inference — is owed by whichever slice ships the vocabulary, and it is a judgement
  call rather than a rule that can be written here.
- ⚠ **Nothing here is measured.** Every size, latency and accuracy statement in this section is
  a starting assumption to be checked, and the checking is IN-1's real first task.

#### ⚠⚠ IN-D9 amendment (2026-08-14) — the leaf rule needs an absolute floor

IN-D9's leaf rule is *relative*: pick the best sibling, degrade to the parent when the top two are
close. Run against a real library for the first time (`models.md` §3.7.5), the best sibling is
routinely **negative** while the parent is positive — `indoors 0.061` winning with `staircase
−0.009`, `indoors 0.044` winning with `gym −0.006`.

An argmax always returns something. A purely relative margin cannot tell "this is a bedroom, not a
living room" from "the model rejects all fourteen of these and this one is rejected least" — and a
*clear* margin between two confidently-wrong siblings reads as confidence, so the rule is most
wrong exactly where it feels most certain.

**So the rule gains a first step: the winning leaf must clear its own absolute floor before the
margin test runs at all; below the floor, emit the parent.** Two thresholds per leaf, not one, and
the floor is the one that stops the vocabulary inventing detail. ⚠ Neither is calibrated — that
still needs the labelled sample above, and the harness now prints the margin distribution so the
floor can be chosen from data rather than picked.

⚠ The parent's own threshold is unaffected; this changes only what a leaf may claim.

#### ⚠⚠ And the top tier needs the opposite check — a label can be true and useless

`indoors` reached the top 3 for **55–60%** of a real library (§3.7.4). It is not wrong; most photos
are indoors. But a top-tier label with an independent threshold, applied to something that common,
is a label that carries no information and costs an index row on every photo.

⚠⚠ **This is invisible per image** — each individual result looks reasonable — and only shows up
when you ask how often a label fires *across* the library. Whatever slice ships the vocabulary owes
that view, and a rule for what to do with a label that fires on everything: drop it, raise its
threshold, or demote it to a filter that is never surfaced as a tag.

✅ **The view SHIPPED 2026-09-04 — §28**: the pass counts per-image firing rates and reports them.
⚠ The *rule* is deliberately not shipped with it — which of the three responses applies is a
per-label judgement that needs this view's data first, and inventing it ahead of the evidence is
§19.3's error.

---

## 8. As built — AV-1: speaking to Mia (2026-08-12)

**Merged:** _(this PR)_ · **Scope:** `mia-shared-logic`, `mia-shared-ui`, `mia-android`. No schema
change, no new op, no validator surface, and **no dependency on the model track**.

### 8.1 ⭐ Voice really did cost nothing at the query layer

IN-D11 predicted it and it held exactly: *a transcript is just an utterance*. `VoiceInput` ends at
the composer, `askMia` is untouched, and there is no new intent, op or validator entry. The whole
slice is one interface, one Android implementation, a mic button and a permission.

### 8.2 ⚠⚠ On-device or absent — enforced at the binding, not at the call site

`AndroidVoiceInput` uses `createOnDeviceSpeechRecognizer`, never `createSpeechRecognizer`: the
default recogniser is free to ship the audio to a vendor service, and taking that path silently
would break the product's central claim for a feature nobody asked to be online.

The gate is `SpeechRecognizer.isOnDeviceRecognitionAvailable`, checked **where the binding is
made** — below API 33, or on a 33+ device with no language pack, Mia binds no `VoiceInput`, the mic
does not render, and there is nothing to fall back to. `EXTRA_PREFER_OFFLINE` is set as well, but
it is only a *preference* on the default recogniser; the recogniser choice is the actual guarantee
and the flag is the second lock.

⚠ "Absent **and says so**": Settings carries a dictation entry that reads either *"your words are
turned into text on this device"* or *"this device can't recognise speech without sending the audio
away, so Mia has no microphone here"*. A hidden control with no explanation is how a user concludes
the feature is broken.

### 8.3 ⚠⚠ A transcript lands in the composer; it is never sent

The decision worth arguing with. Auto-sending is what a hands-free assistant would do, and it is
wrong here: a misheard word is the failure voice actually has, and **MQ-D7's confirmation only
covers the write intents**. A misheard *search* would answer a different question with nothing for
the user to notice. One tap is not a burden; a silently wrong answer is.

⚠ Partial results do fill the composer as they arrive — a recogniser that appears to do nothing for
four seconds reads as broken.

### 8.4 The microphone's lifecycle

⚠ Sending stops the recogniser (it must not stay open behind an answer), the view model stops it in
`onCleared`, and the Activity `release()`s it in `onDestroy`. The OS's recording indicator is how a
user finds out about a mic left open, and it reads as spying rather than as a bug.

⚠ `RECORD_AUDIO` is asked on the **first tap of the mic**, never at launch: a microphone prompt on
a cold start, before the user has expressed any interest in speaking, is what teaches people to deny
by reflex — and Mia is entirely usable by typing, so there is nothing to gate.

### 8.5 What is proven, and what a device still owes

- **6 view-model tests**: no binding → no mic (and an inert tap), start/stop, partials filling the
  composer, ⚠⚠ the final transcript **not** being sent, sending closing the microphone, and the mic
  being inert while Mia is replying.
- Full gate green; **Android smoke gate PASSED** (Mia builds, installs and launches with the new
  permission and the DI branch).

⚠ **Unproven, and only provable on a device:** that `isOnDeviceRecognitionAvailable` answers true on
the emulator's/phone's actual configuration, that the language pack exists, and that recognition
quality is usable. The gate proves the app starts; it cannot speak.

⚠ **iOS and desktop are not built.** iOS is `SFSpeechRecognizer` with `requiresOnDeviceRecognition`
plus its `supportsOnDeviceRecognition` probe — the same shape, in SwiftUI. Desktop stays out per
IN-D11 (no OS STT worth using; Whisper on the IN-D2 runtime is the later answer).

---

## 9. As built — IN-1: the seam (2026-08-12)

**Merged:** _(this PR)_ · **Scope:** two new modules, `:inference-service` and
`:inference-service-proxy`. **No model, no runtime, no native dependency, and nothing wired into an
app.** Nothing in this slice can run inference, and that is the point.

### 9.1 ⚠ Two modules, not four — and why the other two are absent

The phase map says "module set". What shipped is `:inference-service` (capabilities, consent,
provenance) and `:inference-service-proxy` (the backend seam, the fake, the registry, the consent
read).

There is deliberately **no `-common` / `-client`**: IN-1 ships no operation, so those modules would
contain nothing. An empty module is a promise rather than a seam, and the registration ceremony it
implies (`dtoFactory`, `roFactory`, wire ids) is exactly the kind of thing that gets written once,
copied wrong, and debugged three slices later. They arrive with the first op — IN-3's `MediaLabel`
rows or IN-4's similarity op.

### 9.2 ⚠⚠ Two switches, because they answer different questions

> ⚠ **The stored shape below is unchanged; the screen no longer shows it as two switches** — §38
> (2026-10-11) presents the pair as one list of devices per feature.

IN-D3's consent shape, as `InferenceCapability`:

- **`realmConsent`** — *"may this realm be indexed for X at all"*, a decision that follows the user
  to a new device.
- **`deviceConsent`** — *"may **this** device do the work"*: a phone on battery and a plugged-in hub
  are legitimately different answers to the same question.

Both must be yes. ⚠⚠ **Absence means no** — the opposite of Mia's read grants, and deliberately:
Mia defaults on because reading was already the shipped behaviour and defaulting off would break a
working assistant, whereas nothing here has ever run. Silence means the user has not asked for
this, and indexing a library on the strength of a missing row would be helping ourselves.

⚠⚠ **A `DeviceSetting`'s key is `"$deviceId/$preferenceId"`**, so `hasInferenceConsent` takes the
device id as a *required* parameter rather than defaulting it. A read that guesses the device half
finds no row for anything — and since absence means no, the switch would appear to work while never
taking effect. `ProxySyncRules` takes it as a parameter for exactly this reason; the test pins the
two-device case.

⚠ `FACE_CLUSTERING` carries `offerableInOnboarding = false` as a **property of the capability**
rather than a convention a future screen might forget. IN-D3's argument is that an onboarding flow
that asks is an onboarding flow that pressures; encoding it in the enum is what makes that survive
the screen being rewritten.

### 9.3 The backend seam, and the fake that makes it provable

`InferenceBackend` is **task-shaped, never model-shaped** (IN-D1): callers ask for *an embedding*,
and what satisfies it — ONNX Runtime on the phone, Vision on iOS, a co-computer across the LAN — is
a binding. That is what makes the hub proposal's "with no hub on the LAN, light inference lands on
a paired phone/desktop" true by construction rather than a rewrite later.

`FakeInferenceBackend` returns canned results so the whole consumer stack is testable with no model
and no hardware — the `SimulatedDeviceBridge` / `FakeMqttClient` pattern that made HD-1 and HD-2
provable before the Pi existed.

⚠⚠ Its embeddings are **derived from the bytes**, not random: the same photo embeds the same way
and a different photo does not, which is the only property of an embedding a consumer depends on. A
random vector would make every nearest-neighbour test meaningless while looking like it worked.

⚠⚠ Everything it produces is stamped `ModelProvenance.Fake`, so a row it wrote is recognisable in
production data forever. A fixture that stamps like the real thing can contaminate a store.

⚠ `InferenceBackendRegistry.Empty` is the production state until IN-2: **"nothing can do this here"
is an ordinary answer**, and the product must read as a feature that has not been set up rather
than a broken one. Consumers get `null`; nobody throws.

### 9.4 `producedBy` is what makes a re-index cheaper than a wipe

`ModelProvenance(modelId, modelVersion, labelSetVersion)` stamps every derived row. Without it,
"we improved the tagger" means discarding every row — including ones the *user* corrected —
because nothing can tell which came from where.

⭐ It is also what makes IN-D9's "the taxonomy is data, not weights" real: `labelSetVersion` is
separate from `modelVersion` on purpose, so adding a label next year re-labels without re-embedding.

⚠ A row from a **newer** model is not stale: a second device may simply be ahead, and re-deriving
it would start a ping-pong between two devices that never converges. ⚠ `parse` returns `null`
rather than throwing — the string is read back from rows written by older versions of us, and
unreadable means "re-derive", never "crash the indexer".

### 9.5 What is proven

- **8 domain tests**: every capability off by default, the two scopes, face clustering's onboarding
  exclusion, preference-id uniqueness (a collision would silently make two capabilities one
  switch), provenance round-trip, unreadable stamps, staleness in both directions.
- **13 proxy tests**: consent requiring both switches, realm revocation outvoting a device yes,
  ⚠⚠ another device's consent not being this device's, per-capability isolation, re-read-not-cached,
  the empty registry, backend precedence, the consent ∧ capability join, and the fake's determinism,
  stamping, capability gating and vocabulary filtering.
- Full gate green; JS and iOS targets compile (the modules are KMP-honest, though no iOS or web
  consumer exists yet).

⚠ **No Android smoke gate**: nothing here is wired into an app — no `androidMain`, no `:control`,
no manifest, no DI line. The gate would prove nothing this slice can break.

### 9.6 ⚠ What IN-1 deliberately does not do

- **No model, no runtime, no native dependency.** ONNX Runtime does not appear anywhere yet; that
  is IN-2's decision to make behind this interface.
- **No consumer.** Drive's indexer and Mia's retrieval are IN-3/IN-4. Nothing calls
  `InferenceBackend` in production today — the seam exists so those slices have somewhere to land.
- **No Settings UI.** The capability copy IN-D3 describes ("what it does, what it downloads, what
  it costs in battery") is honest only once IN-2 knows the download size, so writing it now would
  mean writing a number we do not have.

---

## 10. As built — IN-2: distribution (2026-08-12)

**Merged:** _(this PR)_ · **Scope:** `:inference-service-proxy` (catalog + store) and the new
[`docs/models.md`](../models.md). **No inference, no model, no network call yet.**

### 10.1 The catalog is the only discovery surface, and the key is what is pinned

`ModelCatalogVerifier` checks a detached signature over the raw `models.json` bytes against a
pinned key, then parses — the shape `RegionCatalogVerifier` already ships, down to the
`SignatureVerifier` SAM that keeps a crypto backend out of `commonTest`.

⚠⚠ **Signature first, parse second, and never the other way round.** A parser is a much larger
attack surface than a signature check, so nothing about the document — not even its schema version
— is read until the bytes are known to be ours.

⚠⚠ **`MODEL_CATALOG_PUBLIC_KEY_HEX` is blank, and a blank pin refuses everything.** A verifier that
treats "no key configured" as "skip the check" is the single most dangerous default available here.
Until the release key exists, this client downloads nothing — loudly nothing, rather than quietly
anything. Test-pinned.

⚠ IN-D10's amendment of IN-D4 is what makes this shape possible at all: pinning each *hash* in
source would mean an app-store release to ship a model update, **including one that fixes a bad
model**. Pinning the key and letting the signed catalog carry the hashes keeps the supply-chain
property and buys revocation.

### 10.2 What the client filters out, and why each filter exists

- **`schemaVersion` above ours** → refuse the whole document rather than guess at it.
- **`artifactVersion` above ours** → drop that entry. The `tileSchemaVersion` precedent: one
  catalog serves every client version at once, and an entry a client cannot read must be skipped
  rather than downloaded and puzzled over.
- **An entry serving no capability we know** → dropped, so a future catalog cannot have us download
  bytes nothing can consume. ⚠ But an *unknown* capability name alongside a known one does **not**
  discard the entry — a newer catalog must still deliver what this build understands.

### 10.3 ⚠⚠ The digest gate, and where a bad CDN fails

`ModelStore.add` lands bytes only when their digest matches the **signed catalog's** figure. A CDN
serving the wrong bytes fails there, not in an inference run three weeks later.

⚠ The licence travels onto the installed copy, not just the catalog entry: *"which licence is this
model under"* must be answerable from the device, not only from a catalog it may no longer be able
to fetch.

### 10.4 ⚠⚠ "Downloaded" and "enabled" are different questions

`ModelInventory` answers only the first, and consent (`permittedInference`, IN-1) answers only the
second. IN-D10 requires the UI to say which one an action does — deleting a model must not silently
disable a capability, nor turning a capability off silently delete anything — and keeping the two
apart in code is what makes that hard to get wrong.

⚠ An installed copy of an **older** version reads as *installed*, not absent: it still works, and
presenting it as missing would push the user into a download they do not need to make. Staleness is
`ModelProvenance`'s business, and it re-derives **rows**, never re-downloads weights.

### 10.5 ⚠ What IN-2 deliberately leaves for IN-2b

- **No Control screen.** IN-D10 wants the Offline-maps-shaped list, and it would today render an
  empty catalog fetched with a blank key — while IN-D3's copy promises "what it downloads and what
  it costs in battery", numbers §7.7 says we do not have. A screen that can only say "nothing
  available" is not the screen that decision describes.
- **No HTTP source.** `HttpRegionCatalogSource`'s twin is a small thing to write and pointless
  before a URL and a key exist.
- ⚠⚠ **The store is in-memory and named `InMemoryModelStore` for that reason.** A
  `DefaultModelStore` that silently forgot everything on restart would be discovered as "my model
  downloads again every launch". A filesystem-backed one, with region delivery's **staged**
  download path (`beginStaging`/`writeStaged`/`commitStaged`), is IN-2b's — a real 500 MB model
  must never be materialised in memory, and this slice does not pretend otherwise.

### 10.6 What is proven

- **9 catalog tests**: a signed catalog parses; ⚠⚠ a tampered one and a **blank key** are refused;
  newer schema refused; unreadable artifact filtered; unknown-capability entries dropped without
  discarding known ones; unknown fields tolerated; garbage never throws.
- **6 store tests**: the digest gate, the licence travelling with the copy, removal, the three
  install states, an older version still counting as installed, and downloaded-≠-enabled.
- Full gate green. ⚠ No Android smoke gate — still nothing wired into an app.

⚠ **`docs/models.md` exists and its model table is empty**, which is the honest state: ⚖️ model
choice, licence and corpus provenance, and every measurement are open (§7.7). The file is the
review surface those answers land in, written before there is anything to review precisely because
those questions are hardest to ask retroactively.

---

## 11. As built — IN-2b: the durable store, the fetch, and a correction to the phase map (2026-08-12)

**Merged:** _(this PR)_ · **Scope:** `:inference-service-proxy`. Still no inference and no model.

### 11.1 ⚠⚠ The Control screen is not a UI slice — it is an **op-surface** slice

IN-2's §10.5 deferred the Control screen because it could only say "nothing available". Building
IN-2b surfaced the more important reason: **Control's Offline-maps screen reads through
operations**, not through proxy internals — `OfflineMapsRepository` calls `mapRegionService`'s
`catalog` / `installed` / `downloadRegion` / `deleteRegion` over the client surface, exactly as any
app would.

So a models screen needs `ListModels` / `DownloadModel` / `DeleteModel` ops, which needs
`:inference-service-common` and `:inference-service-client` — **the very modules IN-1 deferred**
because there was nothing for them to carry, and the ceremony they imply (`dtoFactory`,
`roFactory`, wire ids, registration) is what gets copied wrong and debugged three slices later.

⭐ **The sequencing correction:** that op surface is the same one **IN-3** needs for its
`MediaLabel` rows. Building it once, with IN-3, and landing the Control screen on it is strictly
cheaper than building it twice — and it means the screen arrives when there is a real catalog, a
real key and a real download size to show, which is the screen IN-D10 actually describes.

⚠ The phase map now reads **IN-2c: Control UI, gated on IN-3's op surface** rather than pretending
the screen is a small follow-up to this slice.

### 11.2 The durable store, and the two properties a crash can violate

`DefaultModelStore` mirrors `DefaultRegionStore`: index in `SecureStorageDelegate`, bytes in
`FileDatabase`, both device-local and never synced (IN-D4 — a model is a public artifact any device
can re-fetch, so syncing one would push hundreds of megabytes through the relay and charge the
user's quota for nothing).

Two orderings are asserted by tests that watch *how* bytes land rather than where they end up:

- ⚠ **Write to staging, then rename.** Overwriting a live artifact in place means that for the
  whole write the file is neither the old model nor the new one, and a process death leaves a
  **truncated file the index still vouches for**. The index is written *after* the rename, so a
  crash between them re-downloads rather than loading half a model.
- ⚠ **Evict the index first, then the bytes.** A crash in between leaks an unreferenced file, which
  the next download overwrites; the other order leaves an index entry vouching for a file that is
  gone — a model that reports itself installed and then loads nothing.
- ⚠⚠ **Since F2v, one writer at a time.** Paths are content-addressed, and a sweep deletes declared
  paths that no live record names. So every index and garbage access, reads included, holds one
  store-wide lock. Without it, a process's first read sweeps a pending commit's new files, and two
  commits lose one model's index record. The rule and its tests are in
  `photos-people-and-pets.md` §12.12, *F2v as built*.
- ⚠⚠ **A commit never moves onto a file that is already there and whole** (F2v).
  `moveFile` deletes a destination before it renames, on every platform. An update that keeps one
  artifact's digest, a re-install, or a rollback therefore adopts the resident file (same length as
  the staged copy) and discards the staged one. A resident file of another length is replaced.
- ⚠⚠ **And one process at a time** (F2v). That lock is per process, and two desktop
  Controls can run over one data directory. The store's constructor therefore takes a
  `ModelStoreOwnership`: desktop binds an OS file lock held for the life of the process, Android
  and iOS are `SoleProcess` by platform construction. A Control that is refused reads what is
  installed, never sweeps, and fails every write with a sentence naming the other Control.
- ⚠⚠ **Since F2v, "then the bytes" means the next process**. The index write is
  not durable when it returns: on Android `storeString` ends in `SharedPreferences.apply()`. A
  delete right after it can outlive the write it depends on. So a commit and an eviction only
  declare what they replaced, and the one sweep is the first index read of the next process, which
  filters against an index it read back. ⚠ A replaced or evicted generation therefore stays on
  disk until Control restarts, and `DeleteModel` frees its bytes then, not at the call. Per-platform
  durability and the cost are in the same §12.12 section.

⚠⚠ **The resume marker matters more here than for regions.** A model id is stable across versions,
so `.part` bytes from an earlier attempt may belong to a *different version of the same model*.
Appending to them yields a file that fails the digest gate only after the whole remainder has been
downloaded — over a metered connection, twice. `beginStaging` records the expected digest beside
the staging file and discards anything staged for other content.

⚠ An unreadable index reads as empty rather than throwing: the models are still on disk and will be
re-downloaded, which is recoverable. A proxy that will not start is not.

### 11.3 ⚠⚠ The fetch checks the status, and the test that caught it

`HttpModelCatalogSource` GETs `models.json` and its detached signature and hands both to the
verifier.

The first draft did what its region twin does and read `.body()` directly — and a test asserting
*"a CDN that serves the document but 404s the signature must not yield a catalog"* **failed**:
Ktor does not throw on a 404 by default, so the error page's bytes were passed to the verifier as
if they were a signature. In production the signature check would still have refused them, but a
path whose safety rests on a fake being honest is not one to leave open. The status is now checked
explicitly.

⚠ It returns `null` rather than throwing, unlike the region twin, and the difference is what the
caller can do: a missing basemap catalog blocks a download the user just asked for and deserves an
error, whereas the model catalog is polled in the background — a proxy that threw on every refresh
against an untrusted CDN would fill the log and change nothing.

### 11.4 What is proven

- **10 store tests**: index survives a restart, the rename-not-overwrite landing, the digest gate,
  resume-only-for-the-same-content, staged bytes invisible until commit, abort, eviction ordering,
  update-replaces-not-duplicates, an unreadable index, and `pathOf` (which IN-3's backend will need
  and nothing else does yet).
- **4 source tests** over Ktor's `MockEngine`: the two GETs, a failed signature check, ⚠⚠ the
  missing-signature case that found the status bug, and a transport failure.
- Full gate green. ⚠ Still no Android smoke gate — nothing is wired into an app.

⚠ **Nothing here has fetched a real catalog**, because there is no URL, no signing key and no
model. What is proven is that the machinery refuses everything it should; what is unproven is
everything about a real download — resumption across a real network, a real 500 MB artifact, and
the local-cache ceiling interacting with it (`retention-quota-model.md` P2).

---

## 12. As built — IN-3a: labels, and one pass that produces them (2026-08-12)

**Merged:** _(this PR)_ · **Scope:** `:inference-service` (schema), the new
`:inference-service-common`, `:inference-service-proxy` (the indexer), plus the schema baseline.
**Still no model** — the whole pass runs against `FakeInferenceBackend`, which is exactly what IN-1
built it for.

### 12.1 `MediaLabel` is a row, and only labels sync

⚠⚠ **A row, never a property on `File`.** Putting labels on `File` would reshape the platform's
hottest composite for one feature and teach `:file-service` what a model is. `PlaceRole`'s argument
applies verbatim: a separately-keyed row cannot be erased by an unrelated write to the thing it
describes.

⚠ **Labels sync; embeddings do not** (IN-D5). A label is small, durable and useful on every device
the moment one device derives it; a vector is large, model-specific and re-derivable from bytes
each device already has.

⚠ `confidence` is stored as **per-mille**, not a float — the DSL's numeric field is an integer, and
⚠⚠ it is a **Long** on the wire: passing an `Int` compiles and then fails at save time with
`Value 0 is not a long`. Found by a test, not by the compiler. ⚠ The unit is decided otherwise
since (§39.2): the cosine in thousandths, which can be negative. The column is unchanged until
labels are built.

`InferenceService` is **new at v1**, so there is no migration chain to register — the first bump
that reshapes a label owes one.

### 12.2 ⚠⚠ The baseline was green because the service was outside its graph

`SchemaShapeBaselineEnforcementTest` passed the moment `MediaLabel` existed — and that was not
reassurance, it was **absence of coverage**: the baseline pins the service graph
`:system-test-utils` wires, and a new service outside it is a service whose positional layout
nothing is watching. Adding `:inference-service-common` to that module made the test fail with "2
new types are not pinned yet", which is the answer it should have given from the start.

⚠ Worth generalising: *the next new service will pass the baseline too, for the same wrong reason.*

### 12.3 One pass, and what it refuses to do

`MediaIndexer` walks a list of files, asks the backend once per file, and writes labels stamped with
the model's provenance.

- ⭐ **One pass, not one per capability.** IN-D8's argument is that one model serves classification
  *and* search; the library walk is the expensive part, and doing it per feature is how "add OCR"
  becomes "re-read every photo again".
- ⚠⚠ **Consent is re-read per *file*.** A pass runs for minutes to hours; a check hoisted to the top
  would keep grinding through a library the user just switched off. Test-pinned by revoking
  mid-pass.
- ⚠⚠ **With no consent, the bytes are never fetched** — not merely "no rows written". A pass that
  reads a library it has no permission to look at has already done the thing consent was about.
  `IndexableMedia` carries a *lazy* byte accessor precisely so a skip costs no read.
- ⚠ **Every skip is counted and named** (`alreadyCurrent` / `unreadable` / `skippedNoConsent` /
  `skippedNoBackend`). "Indexed 0 of 4 000" with no reason is the report that gets filed as "the
  feature does nothing".

### 12.4 ⚠⚠ "It ran" and "it found something" are different facts

A second pass skips what is already current — which is the whole reason `producedBy` exists, and
saves hours of battery producing byte-identical rows.

Currency is judged on a **marker row** the pass writes whether or not the model had an opinion. The
obvious alternative — "does this file have any label?" — would re-read every unremarkable photo on
every pass, forever. The marker is a sentinel label rather than a second row type, because
`MediaLabel`'s key already gives one row per `(file, capability, label)` and a parallel
"index state" type would be a second thing to keep in step with the first.

⚠ **Vectors are not produced.** The pass is shaped to emit them in the same visit (`embedImage` is
right there), but a vector has nowhere device-local to live until IN-4 builds the index it feeds,
and putting one in a synced row would violate IN-D5 outright. The gap is recorded rather than
half-filled.

### 12.5 What is proven

**8 indexer tests**, all against the fake: no consent → nothing read, no backend → a no-op pass,
labels written with provenance, a second pass skipping, a newer model re-indexing, an opinion-less
file still counting as indexed, mid-pass revocation, and an unreadable file counted rather than
swallowed. Baseline regenerated; full gate green.

⚠ **Unproven:** everything about a real model. Whether a pass over a real library finishes in
minutes or hours, what it costs in battery, and whether the labels are any good are all ⚖️ open
(§7.7) — and IN-3b is where they become answerable.

### 12.6 What is left

- **IN-3b — the real backend.** ONNX Runtime and a native dependency per platform, all behind
  `InferenceBackend`. ✅ **The model is no longer part of this**: R1 + R2 were pinned 2026-08-25 —
  `siglip-base-patch16-256-multilingual`, SigLIP v1 (`docs/models.md` §3.8). ⚠⚠ What remains of the
  old *"gated on the model decision and its licence / provenance review"* is **two separate things,
  and only one is a decision**: **IN-3m**, the wall-time-and-battery number that could still return
  *"do not ship this"*, and ⚖️ counsel on R9's corpus disclosure. ⭐⭐ **IN-3m ran 2026-09-01 and did
  not say no** (§15.12): 121 ms/image on a mid-range phone, no throttle cliff, so the last gate here
  is counsel alone. ⚠ What IN-3b inherits instead is *shape* — IN-D6's gate is required, the UI owes
  resumable progress, and IN-D13's EP ladder is not a preference order. ⚠ A pin is not a quality
  claim — there is still no ground truth for any label (`models.md` §3.7.10).
- **IN-4 — consumption**, which also brings the op surface (`-client` + read ops) that **IN-2c's
  Control screen** waits on. ⚠ IN-3a ships rows but no ops: nothing outside the proxy can read a
  label yet.
- **IN-D6's scheduling.** The pass is a function; making it *background, metered and one task* is
  wiring it to `PlatformScheduler` + `withTask`, and belongs with the first real backend rather than
  with a fake that costs nothing to run.

---

## 13. The measurement harness (2026-08-12)

**Merged:** _(this PR)_ · **Scope:** `:inference-service-proxy` **test source set only**.

⚠⚠ **A harness, not a feature, and deletable by design.** It exists to answer the two questions
`docs/models.md` §3.3 says gate IN-3b — *does a candidate survive int8 against our vocabulary*, and
*what does a pass cost per image* — **before** the track takes a native dependency in the shipped
graph. When IN-3b picks a model, the real backend replaces this and the ONNX dependency leaves
`jvmTest` with it. Leaving a measurement harness behind as infrastructure is how a repo ends up
with two ways to run a model.

### 13.1 Measure before taking the dependency

ONNX Runtime is a **`jvmTest` dependency**, nowhere else. That ordering is the point: a runtime
added "just to try it" is one nobody removes, and IN-D2's whole argument for keeping the choice
behind `InferenceBackend` is undermined the moment a specific runtime is in the production graph
before anything has been measured.

### 13.2 Run it like `MvtRealArchiveTest`

```
./gradlew :inference-service-proxy:jvmTest --tests '*EmbeddingHarness*' \
  -Dtenshu.inference.model=/path/to/image-encoder.onnx \
  -Dtenshu.inference.photos=/path/to/a/folder \
  [-Dtenshu.inference.labels=/path/to/label-vectors.tsv]
```

Unset, it prints why it skipped and passes — the shipped-asset test pattern this repo already uses.

⚠ It **prints; it does not assert.** A harness that failed a build on a quality number would be a
benchmark pretending to be a test, and what it produces is an input to a decision rather than a
contract.

⚠ The label file is optional and **pre-computed** (`label<TAB>v1,v2,…`): scoring text against
images needs the *text* encoder and its tokenizer — a second artifact and a second dependency —
and keeping that offline holds this harness to one model.

### 13.3 ⚠⚠ What it reports, and why each number

- **p50 / p90 / max per image, and the extrapolated 5 000-photo pass.** A mean would hide the
  shape every ORT run has: the first inference is far slower than the rest, so the median is what a
  library pass costs and the max is what a user notices.
- **Top-3 labels per image**, and — the number that matters most — **the score range across all
  (image, label) pairs**. IN-D8 asserts that one global threshold cannot work; this is where that
  stops being an assumption.
- **Nearest neighbour by image similarity**, which needs no text encoder at all and is the quickest
  read on whether the embeddings separate anything.

### 13.4 ⚠ The arithmetic is tested even though the harness cannot be

The harness cannot run in CI — it needs weights nobody has chosen. That makes the pieces which can
be **wrong quietly** the ones worth pinning, and `HarnessMathTest` does (8 tests): the
normalisation constants and channel order, ⚠⚠ **centre-crop rather than squash** (squashing
distorts *every* photo in the library, so the model would be judged on images no camera ever
produced), unit-vector cosine, the zero-vector guard, top-k order, and the percentile shape.

⚠ Measuring badly is worse than not measuring, because it *looks* like evidence.

---

## 14. As built — IN-4a: "photos of a cat" (2026-08-12)

**Merged:** _(this PR, stacked on IN-3a)_ · **Scope:** the new `:inference-service-client`,
`:mia-service` + `-proxy`, `mia-shared-ui`. **Still no model.**

⭐⭐ **What IN-1's fake was built for.** An index pass, real `MediaLabel` rows, a real chain, the
real validator and real citations — with **no model, no runtime and no hardware**. When IN-3b binds
ONNX Runtime, only the backend changes; nothing in this path does.

⭐ And IN-D12's claim, exercised: **label search costs Mia nothing structural**. It is an ordinary
MQ-2 source adapter that lowers to `filter { label contains … }`. The search it distinguishes
itself from — nearest-neighbour over embeddings — still needs an **op**, and is still not built.

### 14.1 `of` is what separates content from place and time

Three prepositions, three intents, and English hands them to us cleanly:

| Utterance | Intent | Since |
|---|---|---|
| "photos **of** a cat" | content | IN-4 |
| "photos **in / from** Munich" | place | Track A |
| "photos **from** Tuesday" | time | MQ-1 |

⚠ Content is checked **before** the place join, because it is the narrower signal: a place matches
thousands of photos, a label matches few. ⚠ "Photos of a cat in Munich" is therefore answered as
*content*, not as both — the combination is not expressible yet, and §14.4 records it rather than
letting the router pick one silently.

### 14.2 ⚠⚠ Its own grant, and one chain rather than two

`MiaSource.LABELS` is a **separate** grant from `FILES`. "Mia may read my file names" and "Mia may
read what a model thinks is *in* my photos" are different permissions, and the second is the one a
user is more likely to want off; folding them together would have made enabling either enable both.
⚠ Both are pinned by tests, including that revoking labels refuses **by name** (MQ-D6).

⚠ Unlike the place joins there is **no stage two**: a `MediaLabel` row already carries the file's
identity, so re-reading `File` would buy exactly one field — the capture time — at the cost of a
second chain per result. `MiaHit.atMs` is documented as `0` for *unknown, not 1970*, which is the
honest answer and the cheaper one.

⚠ A label row is cited as the **file** it describes, never as itself. A `MediaLabel` among the
results would be showing our working.

### 14.3 ⚠⚠ The index marker leaked into search, and a test caught it

IN-3a writes a `__indexed` marker row per file so an opinion-less photo is not re-read forever
(§12.4). Label search is a `contains` — so **"photos of indexed" matched the marker** and cited
files by their machinery.

Dropped at the hit mapping, and the constant is now public precisely because *every reader has to
exclude it*. ⚠ Excluding it in the **chain** would be better and is not possible: it needs a
`notEqualTo` predicate the DSL does not have (`equalTo`, `contains`, `inRange` are the three). So a
file whose only row is the marker still consumes one slot of the chain's `take` — a mild ranking
cost, recorded rather than hidden.

⭐ The general lesson is worth more than the fix: **machinery that is indistinguishable from data at
the read side leaks into the UI.** A sentinel row is cheap to write and easy to forget.

### 14.4 What is proven, and what is not

- **6 end-to-end tests**: the cat is found and the spaniel is not, `of` vs place, an unapplied label
  finding *nothing* rather than everything, ⚠⚠ the marker never being citable, the grant refusing
  by name, and the label grant being independent of the files grant.
- The rows under test are written by a **real `MediaIndexer` pass**, not hand-seeded — so IN-3a and
  IN-4 are proven together.
- Full gate green; **Android smoke gate PASSED** (Mia's Settings gained a grant row, so the app
  actually changed).
- ⚠ The exhaustive `when` over `MiaSource` in Mia's Settings **failed to compile** when the source
  was added, which is the design working: a source cannot be added without deciding what to call it.

⚠ **Not built, and not pretended otherwise:**

- **Semantic search** (MQ-4 proper) — the IN-D12 similarity op, which needs vectors, which need a
  device-local sink nothing has built (§12.4).
- **Content ∧ place** ("photos of a cat in Munich") — two adapters, no conjunction.
- **Confidence anywhere in the UI.** The rows carry it; nothing reads it, because a threshold that
  has never been calibrated (⚖️ IN-D8) would be a number invented to look rigorous.

---

## 15. IN-3m spec — wall time and battery, on real hardware (2026-08-14, no code)

⚠ Renamed 2026-08-25. This phase was labelled *"the phone number"* — a pun on "the number you can
only get from a phone" that reads as a contact detail the moment it leaves this section, which is
where a routing-table label spends most of its life. The **id `IN-3m` is unchanged**; only the
label moved.

§3.5 of [`models.md`](../models.md) measured SigLIP v1 on a desktop Mac: uint8 at 24 ms/image, a
5 000-photo pass in ~2 minutes. **That is a floor, and the number IN-3b actually needs is a
mid-range Android phone.** This is the spec for getting it.

### 15.1 ⚠⚠ The emulator is not a phone, and running this there is worse than not running it

The AVD the smoke gate uses executes **on the host CPU**. A measurement taken there is a Mac number
wearing an Android costume — and unlike a missing number, a wrong one gets quoted.

⚠⚠ **So IN-3m has no automatable gate.** It is a maintainer action on real hardware, and the test
must refuse to produce headline figures anywhere else: if `Build.FINGERPRINT` says generic/emulator,
it prints *"emulator — timings meaningless, ignoring"* and reports nothing but a pass. That check is
not a nicety; it is the difference between a spec that gets used correctly and one that produces
folklore.

### 15.2 The wiring already exists in this repo

`:core-client` and `:core-proxy` both do exactly this, so IN-3m copies rather than invents:

```kotlin
android {
    withDeviceTest { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
}
sourceSets.getByName("androidDeviceTest") {
    dependencies {
        implementation(libs.runner); implementation(libs.androidxTestCore); implementation(libs.ext.junit)
        implementation(libs.onnxRuntimeAndroid)   // ⚠ NEW — see below
    }
}
```

⚠⚠ **ONNX Runtime for Android is a different artifact.** `com.microsoft.onnxruntime:onnxruntime`
is a JVM jar with no native libraries for Android ABIs; the device needs
`onnxruntime-android` (an AAR shipping `.so` per ABI). Reusing the jar compiles and fails at
runtime with an `UnsatisfiedLinkError` — the failure looks like a device problem and is a
dependency problem.

⚠ `HarnessMath` and `EmbeddingHarness` live in `jvmTest`, which `androidDeviceTest` cannot see. The
arithmetic (preprocess/cosine/percentile) must move to a source set both can reach — the cheapest
honest option is a small `harness/` package in **`androidMain`+`jvmMain`** via an intermediate set,
or simply duplicated with a pointer. ⚠ Do **not** move it to `commonMain`: it is test scaffolding,
and `commonMain` is shipped code.

### 15.3 Side-load, never bundle

Push the artifact and the photos to the app-specific external directory, which needs **no runtime
permission** and no 90 MB test APK:

```
adb push vision_model_uint8.onnx /sdcard/Android/data/org.tenshu.inference.proxy.test/files/
adb push ~/photos/. /sdcard/Android/data/org.tenshu.inference.proxy.test/files/photos/
```

⚠ Read them via `InstrumentationRegistry…targetContext.getExternalFilesDir(null)`. ⚠⚠ **Nothing is
committed** — not the weights, not the photos, not the report. The repo is public.

### 15.4 ⚠⚠ What the desktop run cannot tell us, and is the actual reason for this slice

**Thermal throttling.** A 5 000-photo first index is minutes of sustained compute on a device with
no fan. §3.5's percentiles come from **11 images** — a cold, unthrottled burst — and extrapolating
them by ×5 000 assumes the 4 000th image costs what the first did. On a phone that is the assumption
most likely to be false, and it is the one the whole schedule rests on.

So IN-3m's run is **not** a percentile snapshot. It requires:

- **N ≥ 200 images**, one pass, no pauses.
- The report gives **median of the first 50 vs median of the last 50**, not just an overall p50. A
  ratio near 1.0 means the extrapolation holds; 2× means the pass is throttle-bound and every
  scheduling decision downstream changes.
- **Wall-clock for the whole pass**, which is the only figure that needs no extrapolation at all.

⚠ Run it twice: once from cold, once immediately after. The second run's first-50 median is the
"device already warm" case, which is what an incremental re-index actually experiences.

### 15.5 Execution providers — the first test of IN-D13's ladder

IN-D13 asserts a ladder (NNAPI/vendor EP → XNNPACK → CPU). **Nothing has ever verified it.** The
report must state, per provider: whether the session **initialised at all**, and its median.

⭐ **Verified 2026-09-01 (§15.12.3), and the ladder came out inverted** — both accelerated providers
ran the graph and both lost to CPU. The expectation below was that an EP would *silently fall back*;
what happened is stranger and more useful: it accelerated nothing while genuinely executing.

⚠ Expect the ladder to be shorter than the doc implies. NNAPI is deprecated as of Android 15 in
favour of vendor delegates, and ORT's NNAPI EP silently falls back to CPU for operators it cannot
place — so *"NNAPI initialised"* and *"NNAPI ran the model"* are different claims, and a per-EP
timing that matches CPU exactly is the tell.

### 15.6 Battery: directional only, and deliberately not a gate

Report `BatteryManager` charge-counter deltas across the pass. ⚠ Treat it as directional: a debug
APK under instrumentation with the screen on is not the shipping condition, and IN-D6's scheduling
(idle + charging) makes the absolute figure less decisive than the *shape* — if a pass visibly
warms the device, that is the finding, and it is already in §15.4's throttle ratio.

### 15.7 Getting the numbers out

Write a plain-text report to the same external files dir and `adb pull` it. ⚠ Instrumentation
stdout is awkward to read and easy to truncate; a file survives, and it is the artifact that gets
pasted into `models.md` §3.5 beside the desktop column.

### 15.8 ⚠⚠ It must never join a gate

Same rule as the desktop harness, for the same reasons plus a device: **skips with a printed reason**
when the model or photo directory is absent, is excluded from `scripts/android-smoke.sh`, and is not
in `./gradlew desktopTest jvmTest test`. A measurement that can fail a build is a benchmark
pretending to be a test.

### 15.9 What the numbers would actually decide

Recorded so the slice is judged on what it changes, not on producing a table:

| Result on a mid-range phone | Consequence |
|---|---|
| uint8 ≲ 60 ms, throttle ratio ~1 | First index is an ordinary background task; IN-D6's idle+charging gate is prudence, not necessity |
| uint8 ~150–300 ms | A 5 000-photo pass is 12–25 min of sustained compute ⇒ IN-D6's gate becomes **required**, and the UI owes a resumable progress surface |
| uint8 > 500 ms, or throttle ratio > 2 | SigLIP base is too big for the phone tier; revisit §3.2's size class — and ⚠ MobileCLIP, the family designed for exactly this, is the one §3.1.1 eliminated on licence |
| NNAPI/vendor EP never places the graph | IN-D13's ladder is one rung shorter than written, and the doc owes a correction |

⚠⚠ **The third row is the one to watch.** If the measurement says the shippable model is the one we
are not allowed to ship, that is a **licence conversation with Apple**, not an engineering task —
and it is better to learn it from a 200-image run than after IN-3b is built.

⭐ **Measured 2026-09-01 (§15.12):** row 3 did **not** fire; the result sits between rows 1 and 2 on
the cold median (121 ms) and inside **row 2** on the warm tail (186 ms), which is the figure to plan
against. Row 4 half-fired — the EPs placed the graph and still lost, which the table did not
anticipate.

⚠ **A partial run on an iPhone, 2026-10-07 (§15.13):** row 3 did not fire in any of its single
~20 s passes (last-50 per photo 31 ms, decode included). The warm case that produced §15.12's 186 ms
tail was not run there, so this is **not** row 3 answered for iOS. ⚠ The table's rows are written
for a mid-range Android phone; the iPhone 17e is not one, and its rows decide nothing about this
table's other rows.

### 15.10 Size and scope

**S–M.** In: the source-set wiring, the ONNX Android dependency, moving the shared arithmetic, the
device test, the emulator guard, the report file. Out: the text tower, labels and thresholds (they
need IN-D9's vocabulary and a second artifact), any production binding, and any CI integration.

⚠ **Depends on hardware, not on code.** Nothing else in the track blocks it, and it blocks nothing —
IN-3b can be *built* without it. What it gates is whether IN-3b is the **right shape**.

### 15.11 As built — the harness exists, the number does not (2026-08-25)

**Merged:** _(this PR)_ · **Scope:** `:inference-service-proxy` test source sets only.

⚠⚠ **This slice ships the instrument, not the measurement.** The number IN-3m exists to produce
still does not exist, because producing it needs a phone with photographs on it. What is here is
everything up to that: the source-set wiring, the Android ONNX dependency, the shared arithmetic,
the device test, the emulator refusal and the report file.

#### 15.11.1 ⭐ The emulator refusal is verified, and it is the only part that could be

Run against the smoke gate's AVD, the test prints its device line and then exactly what §15.1 asks
for, and stops:

```
IN-3m: device: Google sdk_gphone64_arm64 / ranchu / sdk 36
IN-3m: emulator — timings meaningless, ignoring
```

No report file is written and nothing else is reported. ⭐ **Verified the honest way**: with
`isEmulator()` forced to `false` the same run proceeds past it to *"skipped: no model at …"*, which
is what proves the emulator check is what stopped it rather than the absent model.

⚠ The check reads several `Build` fields, not just `FINGERPRINT`. This AVD reports
`sdk_gphone64_arm64` / `ranchu`, and a check that only looked for `generic` would have let it
through — which is the failure mode §15.1 is about, since a wrong number gets quoted.

#### 15.11.2 ⚠⚠ `HarnessMath.preprocess` could not be shared, and splitting it improved it

§15.2 predicted the arithmetic would have to move somewhere both source sets can reach. The
obstacle is narrower than "a source set": `preprocess` took a `java.awt.image.BufferedImage`, and
AWT is not on Android.

⭐ The split is **pixels in, floats out**: `preprocessPixels(argb: IntArray, …)` holds the part that
can be silently wrong — channel order, per-channel normalisation, CHW layout — and each platform
keeps its own decode-and-resize. So the half that `models.md` §3.7.1 got wrong for two sessions is
now written once and pinned once, and it is testable without any image library at all.

⚠⚠ **What is NOT shared is the resize, and the report must not be read as if it were.** AWT's
`SCALE_SMOOTH` and Android's `Bitmap.createScaledBitmap` are different resamplers. IN-3m's
deliverable is wall time and a throttle ratio, which are unaffected; its **vectors are not
comparable to the desktop harness's**, and nobody should compare them.

⚠ It lives in `src/harnessShared/kotlin`, added to both source sets with `kotlin.srcDir` — one file,
two compilations, no copy. Not a shared *source set*, because §15.2 rules out `commonMain` on the
grounds that it is shipped code and `androidMain` would ship it too.

#### 15.11.3 What the test will report

Per execution provider (§15.5): whether the session initialised at all, the median, the first-50 and
last-50 medians and their **throttle ratio** (§15.4), wall clock for the whole pass, decode/run
failures, and the `BatteryManager` charge delta (§15.6, "unavailable" on devices with no charge
counter rather than a nonsense number).

⭐ It also prints the §15.5 tell rather than leaving it to the reader: an accelerated provider whose
median matches CPU within 2 ms *initialised but almost certainly ran on CPU*.

⚠ And it prints which row of §15.9 the result lands in — as prose, never as an assertion. The
consequence of a slow device is a design change upstream, not a red build (§15.8). ⚠ Since
2026-09-01 that row is read from the **last-window median, not the overall one** (§15.12.4).

#### 15.11.4 How to run it

⚠⚠ **Superseded by §15.12.6, and by `DeviceMeasurementTest`'s own KDoc, which now carries the
working sequence — the one below does not work on a real device.** AGP uninstalls
the test APK after `connectedAndroidTest`, taking the pushed corpus *and the report* with it; and a
photo directory created from `adb shell` is unreadable by the app, which the run reports as *"no
photos"*. Read §15.12.6 before running this.

Nothing is committed — not the weights, not the photos, not the report (§15.3). The pinned
checkpoint is `siglip-base-patch16-256-multilingual` (`models.md` §3.8), so the default side is
**256**.

```bash
P=/sdcard/Android/data/org.tenshu.inference.proxy.test/files
adb push vision_model_uint8.onnx $P/model.onnx
adb push ~/photos/. $P/photos/
./gradlew :inference-service-proxy:connectedAndroidTest
adb pull $P/in3m-report.txt
```

⚠ **≥ 200 photos** (§15.4). Fewer still gives a usable median, and the test says in the report that
the throttle ratio is not usable — the ratio is the reason the slice exists.
⚠ **Run it twice**: once cold, once immediately after. The second run's first-window median is the
"device already warm" case an incremental re-index actually experiences.
⚠ A different checkpoint needs `-Dtenshu.inference.side=…`; feeding a 256-patch model 224² pixels
does not fail, it returns a vector computed from an image the model never saw.

#### 15.11.5 What is still not known

Everything §15 was written to find out. ⚠⚠ In particular §15.9's **third row** — *"if the
measurement says the shippable model is the one we are not allowed to ship"* — is still an open
possibility, and it is a licence conversation rather than an engineering task. That is the outcome
worth learning from a 200-image run rather than after IN-3b is built.

⭐ **Answered 2026-09-01 — see §15.12.** Row 3 did not fire. ⚠ Three things in §15.11.4's runbook
did not survive contact with a device, all silently; §15.12.6 has them.


### 15.12 As built — the number, on a phone (2026-09-01)

**Device:** OnePlus CPH2581 (`OnePlus/CPH2581EEA/OP595DL1:16/BP2A.250605.015`), Android 16 / sdk 36,
Snapdragon. **Corpus:** 562 JPEGs — every 4th file across the maintainer's camera roll, Dec 2024 →
Jun 2026, so the pass sees a library rather than a burst. **Model:** our own export of the pinned
`siglip-base-patch16-256-multilingual` vision tower, uint8, 100 MB, side 256. **Condition:** plugged
in, screen on, debug APK under instrumentation — which is IN-D6's own shipping condition (idle +
charging), not a concession.

Run cold from 37.7 °C, then again immediately at 43.6 °C (§15.4). Every figure is ms/image; 3 372
inferences, **zero decode or run failures**.

| EP | run 1, cold | first50 → last50 | run 2, warm | first50 → last50 | run 3, +20 min idle | first50 → last50 |
|---|---|---|---|---|---|---|
| **cpu** | **121** | 141 → 113 (**0.80**) | **133** | 134 → **186** (**1.39**) | **113** | 123 → 109 (0.89) |
| nnapi | 196 | 202 → 186 (0.92) | 191 | 228 → 192 (0.84) | 192 | 181 → 197 (1.09) |
| xnnpack | 147 | 122 → 160 (1.31) | 167 | 150 → 168 (1.12) | 127 | 136 → 132 (0.97) |

Wall clock for the CPU pass, decode included: **220 ms/photo cold, 246 ms warm**. ⭐ Across three
passes the CPU median reads **113–133 ms**, which is the repeatability this figure has.

#### 15.12.1 ⭐⭐ §15.9's third row did not fire — the model we may ship is fast enough

The outcome this slice existed to make possible: **121 ms is not 500 ms, and 1.39 is not 2.0.**
SigLIP base is not too big for this phone tier, so `models.md` §3.2's size class stands and the
**⚖️ licence conversation with Apple about MobileCLIP is not needed**. That was the branch where an
engineering track turned into a commercial negotiation, and it is closed.

#### 15.12.2 ⭐⭐ The throttle answer is conditional, and only the two-run design could see it

Read the first run alone and the conclusion is *"no throttling — the last 50 images are **faster**
than the first 50"* (0.80). That is true and it is not the answer: warm-up sits in the first window
and flatters it, which is §3.5.1's n=7 lesson at n=562.

⚠⚠ **The same 50 images, the same provider, the same binary: 113 ms on a cold device, 186 ms on a
warm one — 1.65×.** A 562-image pass does not throttle. A device that has already been working for
ten minutes does, and a 5 000-photo first index is **three times longer than the pass that measured
it**. So §3.5's desktop extrapolation holds for a short pass and breaks for the real one, which is
exactly the assumption §15.4 was written to distrust.

⚠ The honest planning figure is therefore the **warm tail, not the cold median**: 5 000 photos is
**~18–21 minutes** of sustained compute at 220–246 ms/photo wall clock, trending worse as it runs.

⚠⚠ **But the predictor is accumulated work, not the thermometer — and recovery is fast.** Run 3
started at **41 °C**, hotter than run 1's 37.7 °C, and was the *quickest* of the three (113 ms,
ratio 0.89) after ~20 minutes of idle. So the 186 ms tail is what ~13 minutes of unbroken compute
costs, not what a warm chassis costs, and it comes back on its own. ⭐ That is an argument *for* the
resumable, chunked shape row 2 asks for rather than merely a caveat about it: a pass that yields
periodically may never reach the tail that made this row fire. ⚠ Run 1 also paged a 100 MB model in
from a cold file cache, which is part of why "cold" read slower than run 3 — that cost is not
thermal and should not be extrapolated as if it were.

#### 15.12.3 ⚠⚠ IN-D13's ladder is upside down on this device

Both accelerated providers **initialised and ran** — neither median sits within the 2 ms tolerance
that would mean the graph was never placed (§15.5) — and both **lost to the plain CPU EP in all
three runs**: NNAPI by **44–70 %**, XNNPACK by **12–26 %**. ⚠ NNAPI is the stable finding (191–196 ms
whatever the device is doing); XNNPACK's margin varies enough that only its *direction* is solid.

⚠⚠ So a backend that picks its provider by the ladder as written (vendor/NNAPI → XNNPACK → CPU,
best first) picks **the worst rung on this phone**. IN-D13 owes a correction: the ladder is a list of
things to *measure*, never a preference order, and CPU is a legitimate winner rather than the
fallback. ⚠ One device is not a fleet — the finding is that the order is not knowable a priori,
which is the same conclusion whichever way the next phone lands.

#### 15.12.4 §15.9 row 2 applies, and the harness's own verdict line understates it

The printed interpretation reads *"between §15.9's rows"*, because it is computed from the **cold
CPU median** — the most flattering of the six numbers the run collected. The warm tail (186 ms) is
inside row 2, and row 2's consequences are the ones to act on: **IN-D6's idle+charging gate is
REQUIRED, not prudence, and the indexing UI owes a resumable progress surface.**

✅ **Changed in this PR** — the verdict now reads the last-window median and names the figure it
used (`§15.9 row 2 [last-50 median 186ms]`), falling back to the overall median below
`MIN_IMAGES`, where a 50-image tail is noise. The number it printed was never wrong; the row it
named was chosen by the one figure least likely to survive scale.

#### 15.12.5 ⚠ The battery figure is noise, and was always going to be

`charge=8000uAh`, `-4000`, `-16000`, sign-flipping between passes — and a flat `0uAh` for all three
passes of run 3: the device is at 100 % on AC, so the counter measures the charger, not the model. §15.6 called this directional; at full charge it is
not even that. ⭐ What the run *does* measure is the thermal cost — **37.7 °C → 44.1 °C** across
~15 minutes — and that is the figure IN-D6 actually needs, because it is what the throttle ratio in
§15.12.2 is made of.

#### 15.12.6 ⚠⚠ §15.11.4's runbook does not work on a real device — three reasons, all silent

Each of these produced a green, passing test that measured nothing.

1. ⚠⚠ **AGP uninstalls the test APK after `connectedAndroidTest`**, and that deletes
   `/sdcard/Android/data/<pkg>/files` — the side-loaded corpus **and the report the run just
   wrote**. The documented `push → gradle → adb pull` sequence cannot return a report at all. Use
   `adb install` + `am instrument` directly, which also keeps the corpus in place between the cold
   and warm runs — the thing that makes run 2 the *warm* case rather than a second cold one.
2. ⚠⚠ **A directory created from `adb shell` is `shell`-owned `0770`, and the app cannot traverse
   it.** `adb push` gives *files* mode 0666 but *directories* 0770, so `model.onnx` was found and
   the photos beside it were not. ⚠ `walkTopDown` swallows the permission error, so **an unreadable
   directory and an empty one are the same event** — the run reported *"skipped: no photos"* and
   passed. `chmod -R 777` the photo directory after pushing.
3. ⚠⚠ **The skip paths write no report**, and the test's `println` reaches logcat under the
   `System.out` tag but **not** `am instrument`'s output — so the one situation where there is no
   report to read is also the one that prints nothing where you are looking. The skip reason was
   recovered with `adb logcat -d | grep IN-3m`. ✅ **Fixed in this PR**: both skips now write the
   report — §15.7's reasoning ("a file survives") applies most to the case it excluded, and the
   no-photos skip additionally names the `chmod` above, because that is what it usually means.
   ⚠ The emulator path stays silent on purpose (§15.1): there, saying nothing *is* the answer.

⚠ And the export tooling: `scripts/inference/export-vision-tower.py`'s own pip line is incomplete —
torch 2.13 routes `torch.onnx.export` through the dynamo exporter, which needs **`onnxscript`**.

#### 15.12.7 What this does and does not settle

⭐ **Settled:** the model runs on a mid-range phone at a cost the product can absorb (113–133 ms
across three passes); IN-D6's gate is required; the EP ladder is not a preference order; the artifact re-exports reproducibly (desktop
drift over 12 of these same photos: mean 0.966, worst 0.947, consistent with §3.7.7's 0.949).

⚠⚠ **Not settled, and not touched:** whether the labels are any *good*. This is a wall-clock
measurement over 562 photographs whose contents nobody looked at. §3.7.10 stands.


### 15.13 A partial run on an iPhone — IN-3m-iOS is still open (2026-10-07)

⭐ **§4.4's remaining steps ran on 2026-10-22/23: §15.14.** What follows is the first, partial run.

⚠⚠ **This is not IN-3m-iOS completed.** It is one short pass per row over a camera-roll corpus. The
protocol (§15.4, and [`ios-on-device-inference.md`](ios-on-device-inference.md) §4.4) asks for a
cold / warm / +20-minute sequence, a deliberate run on battery and a launch after a reboot, and
none of those ran. §15.12's central finding came from exactly the second run this one lacks. **No
later phase may read the gate as passed.** What the rows do establish: latency per photo, that
`thermalState` stayed nominal over a ~20 s pass on the charger, and vector agreement across compute
units. Whether a pass throttles is **not** among them (§15.13.1). §15.13.7
lists what is done and what is owed.

The measurement is on the Core ML route that IN-D2's amendment chose for iOS. The harness, its plan
and what it still owes are in that doc's §4 and §9.7.
This section is the figures, in §15.12's form. Marks: **[M]** read from the harness's report ·
**[I]** inferred, not measured. Nothing of the run is committed: not the photos, not the models, not
the report (§15.3).

**Device:** iPhone 17e (`iPhone18,5`), iOS 26.6.1 (23G83). **Corpus:** 554 JPEGs, 3.0 GB, pulled from
the same OnePlus CPH2581's `DCIM/Camera` by §15.12's recipe: every 4th `.jpg` in name order among the
files dated 2024-12-01 to 2026-06-30. ⚠⚠ **The same recipe, not the same files:** 554 against
§15.12's 562, because the camera roll changed in between. The harness's corpus id is
`10b25ab76b09c3314bc683527c140ad16822ad96da6deba745d825df17983463`. The originals are 2–64 MP, median
12 MP. **Model:** the fp16 Core ML export of the pinned `siglip-base-patch16-256-multilingual`, side
256, the export the retrieval gate passed (that doc's §9.6): vision archive sha256 `a30d4dde…`
(184.8 MB), text `628f7f32…` (555.5 MB). **Condition:** a release Kotlin/Native build of the harness
from master at `323f7bb76`, one process per row, started over `devicectl`. Battery 100 % and Low
Power Mode off in every row.

Every figure is in ms. *Per photo* is decode + preprocess + predict; the wall clock also includes
the file read. Each vision row: **554 embedded, 0 unreadable, 0 failed** [M].

| Row, in the order run | power, as reported | predict, median / p90 | decode, median / p90 | per photo, median / p90 | wall clock per photo |
|---|---|---|---|---|---|
| text, `coreml-ane` (200 predictions of one caption) | unplugged | 2.82 / 2.89 | — | — | — |
| `coreml-cpu` (`.cpuOnly`, the control) | unplugged | 20.45 / 21.90 | 20.95 / 31.43 | 41.89 / 51.55 | 44.92 |
| **`coreml-ane`** (`.cpuAndNeuralEngine`, what ships) | full | **13.23** / 14.86 | 20.23 / 30.71 | **33.33** / 43.24 | **36.65** |
| `coreml-all` (`.all`) | full | 13.60 / 14.96 | 20.14 / 30.41 | 33.53 / 44.34 | 37.31 |
| `coreml-ane`, a second run | full | 12.92 / 14.85 | 20.46 / 30.76 | 32.76 / 43.26 | 36.39 |

The windows §15.4 asks for, first-50 → last-50 medians and their ratio:

| Row | predict | decode | per photo |
|---|---|---|---|
| text, `coreml-ane` | 2.83 → 2.82 (1.00) | — | — |
| `coreml-cpu` | 19.74 → 21.60 (**1.09**) | 23.59 → 18.92 (0.80) | 43.19 → 41.60 (0.96) |
| **`coreml-ane`** | 14.29 → 12.94 (0.91) | 22.91 → 17.27 (0.75) | 37.84 → **31.25** (0.83) |
| `coreml-all` | 14.54 → 12.31 (0.85) | 22.51 → 17.45 (0.78) | 36.82 → 30.57 (0.83) |
| `coreml-ane`, a second run | 14.41 → 11.26 (0.78) | 22.70 → 17.82 (0.78) | 36.77 → 29.93 (0.81) |

Open time, memory (`phys_footprint`, MB) and vector agreement:

| Row | open | footprint after open | pass peak, sampled per photo | ledger peak | cosine vs `coreml-cpu`, mean / min |
|---|---|---|---|---|---|
| text, `coreml-ane` | 40.58 | 27.6 | 32.1 | 34.8 | — |
| `coreml-cpu` | 148.12 | 33.3 | 189.8 | 195.1 | — |
| **`coreml-ane`** | 76.98 | 25.1 | **170.8** | 183.3 | 0.997962 / 0.994439 |
| `coreml-all` | **2 647.83** | 131.6 | 187.7 | 223.3 | 0.997962 / 0.994439 |
| `coreml-ane`, a second run | 77.84 | 25.0 | 180.4 | 192.9 | 0.997962 / 0.994439 |

`coreml-ane` against `coreml-all`: mean 1.000000, min 0.999998 over the 554 photos. `thermalState`
read **0** at the start, at every 50th photo and at the end of every row. Preprocess is 0.14 ms a
photo. The file read is 2.3–2.7 ms and warm: the harness hashes every corpus file before the pass.
The shipped row's pass took 20.3 s, about 27 photos a second.

#### 15.13.1 §15.9's third row did not fire in these passes, and the ratios below 1 are not a throttle answer

The harness reads row 3 from the last-window median of the whole photo (decode included), as
§15.12.4 requires: 31.25 ms and 29.93 ms on the shipped row, against 500 ms. No ratio is near 2.
⚠ That is each row's one ~20 s pass. Row 3 is not answered for iOS until the warm run exists.

⚠⚠ **A last window faster than the first is true and is not the answer**, for §15.12.2's reason and
for one of this corpus's own:
- Warm-up sits in the first window and flatters the ratio (§15.12.2).
- ⚠ **The two windows hold different photographs.** The harness walks the corpus in name order, which
  is date order. The first 50 files have a median of 12.6 MP and the last 50 a median of 9.4 MP
  [M: read from the corpus on the Mac]. So decode's 0.75–0.80 is at least partly the files, not the
  phone; how much is [I].
- ⚠ **The predict ratio cannot be read as independent of the files either.** The model input is a
  fixed 256², but §15.13.3 names decode and prediction sharing the CPU as a possible cause of the
  slower prediction. If that holds, a large photo slows the prediction that follows it, and the
  smaller files of the last window flatter the predict ratio as well. Until that contention is
  isolated (prediction timed with and without decode beside it, on the same files), 0.78–0.91 is not
  a statement about the phone.
- `coreml-cpu`'s prediction is the one figure that rose (19.74 → 21.60, 1.09). Its per-photo ratio
  is still below 1, because its decode window fell.
- The report holds medians, a p90 and the two windows, not the per-photo distribution. The decode
  median is taken over a corpus of mixed sizes and is not a per-megapixel figure.

#### 15.13.2 ⚠⚠ Decode is the larger half on camera originals

About 20 ms of ImageIO beside about 13 ms of prediction on the shipped row, with a decode p90 of
31 ms. The stand-in run's 7 ms (that doc's §9.4) was taken on 640 × 480 COCO files and under the
earlier long-edge decode rule; it does not describe a camera roll. So a library pass on this phone is
sized by decode first, and the place to buy time is the decode seam, not the model.

#### 15.13.3 ⚠ Prediction is slower than in the earlier iPhone runs, and the cause is not measured

13.2 ms and 12.9 ms here; 8.7 ms in the stand-in run; 5.8 ms in the PR-0 spike. The device, the
compute units and the 256² model input are the same in all three. What differs between the runs:
the archive (the spike's export against PR-1's), the loop (the spike predicted back to back on one
synthetic input; both harness runs interleave decode with prediction) and the photographs. Which of
them costs the 4.5 ms over the stand-in run is **not measured**. Candidates, all [I]: decode and
prediction sharing the CPU cores on larger files; the harness's per-photo allocations (§15.13.5).
Plan against 13 ms, not 5.8 ms.

#### 15.13.4 The rows favour `.cpuAndNeuralEngine`, the constant already chosen

One or two short passes a setting, on one phone and one OS build. Within that:

Against `.all`: the same vectors (min cosine 0.999998), the same speed (13.23 against 13.60 ms), no
2.6 s open (77 ms), and 25 MB after open against 132 MB. Against `.cpuOnly`: 13.2 ms against 20.5 ms
a prediction, and vectors that differ at min cosine 0.9944, which is why the compute units are part
of the vector store's fingerprint (that doc's §3.2).

#### 15.13.5 ⚠⚠ The 171–190 MB pass footprint is the harness's, not the backend's

The harness drives `CoreMlSession` and `IosImageDecoder` directly and allocates each photo's bytes,
pixel array and tensor anew. `CoreMlInferenceBackend` reuses its buffers (that doc's §10) and was
not on this path. So 171–190 MB is an upper reading of a pass that reuses nothing, on 12 MP
originals, and **the backend's footprint is still owed**. No device-capability threshold may be set
from this number without that caveat (that doc's D12).

#### 15.13.6 Beside §15.12 — a ratio, and why it says little

| | OnePlus CPH2581, §15.12 | iPhone 17e, this run | ratio |
|---|---|---|---|
| prediction, median over the pass | 121 (113–133 over three passes) | 13.2 (12.9–13.2 over two) | **~9 ×** (8.5–10) |
| prediction, last-50 of the first pass | 113 | 12.9 | ~9 × |
| wall clock per photo, decode included | 220 cold, 246 warm | 36.7 | **~6 ×** (6.0–6.8) |

Android's per-image figure excludes decode, so the first two lines compare prediction with
prediction. ⚠⚠ **Read the ratio only with all of these:**
- **Device class.** One Snapdragon phone that §15.12 calls mid-range against one current iPhone.
  Neither is its platform's floor, and the ratio says nothing about either floor.
- **Runtime and precision.** ONNX Runtime's CPU provider on a uint8 artifact against Core ML on the
  Neural Engine with an fp16 artifact. Same checkpoint, two artifacts.
- **Corpus.** The same recipe from the same camera roll, 554 files against 562. Not the same files.
- **Decode is not the same work.** Two decoders, and iOS caps the long edge of the decode where
  Android does not. The wall-clock line compares two pipelines, not two decoders.
- **Build.** A debug APK under instrumentation with the screen on, against a release build.
- ⚠ **No warm-tail ratio exists.** Android's planning figure is the 186 ms tail after ~13 minutes of
  unbroken compute (§15.12.2). The longest unbroken work here is one 25 s pass, so there is no iOS
  figure to put beside it.
- ⚠ **5 000 photos at 36.7 ms is about 3 minutes, and that is [I].** It extrapolates a 20 s pass to
  nine times its length, which is the step §15.4 was written to distrust.

Compare the iOS rows with each other, and Android with Android.

#### 15.13.7 What this does and does not settle

**What the rows show, on this phone, and no more:**
- Latency: the shipped configuration embedded a camera-roll photo in 33 ms with decode, in two
  passes.
- `thermalState` stayed nominal: three ~20 s passes on the charger within ten minutes did not move
  it off 0. ⚠ That is the whole thermal finding. It is not a throttle answer: the first-50 / last-50
  ratios cannot give one here (§15.13.1).
- Vector agreement across compute units: `.cpuAndNeuralEngine` and `.all` at min cosine 0.999998,
  either against `.cpuOnly` at 0.9944. Nothing here argues against the chosen constant (§15.13.4).

⚠⚠ **IN-3m-iOS stays open.** Of that doc's §4.4:

| §4.4 step | State |
|---|---|
| Harness built, signed and installed; the phone unlocked | done |
| The two tar archives side-loaded, so unpack, `compileModel` and first open ran on the phone | done, once per tower |
| The JPEG corpus pushed through the driver, with its manifest | done, with 554 files by §15.12's recipe. ⚠ §4.4 asked for *the same 562 files*; those no longer exist as a set |
| One pass per row: `coreml-ane`, `coreml-all`, `coreml-cpu`, and the text tower | done; `coreml-ane` twice, nine minutes apart with another row between |
| The phone plugged in for every run | **partly**: the three later vision passes. The text and `coreml-cpu` rows report *unplugged* |
| Three runs: cold, warm immediately after, +20 minutes idle | done 2026-10-22/23 (§15.14.1) |
| One run on battery | done 2026-10-23 (§15.14.3) |
| One launch after a reboot | done 2026-10-23 (§15.14.4) |
| A HEIC sample | done 2026-10-23, 53 photos (§15.14.6) |
| The report copied off the phone | done |

⚠ **How the run went:**
- The rows finished at 13:07 (text), 13:12 (`coreml-cpu`), 13:23 (`coreml-ane`), 13:28
  (`coreml-all`) and 13:32 (`coreml-ane` again). There is no cold / warm / +20-minute triple.
- Three launches lost the console connection (`CoreDeviceError 4016`) and kept no result: a
  `coreml-ane` vision row at 12:17, after the phone had been unplugged, then `coreml-all` at 13:17
  and `coreml-ane` at 13:22. None left a block in the report.
- The text and `coreml-cpu` rows report *unplugged* at 100 %. That was not a planned battery run.

⚠⚠ **Not measured, beyond the owed steps above:**
- Sustained load: nothing ran longer than one ~25 s pass, so §15.12.2's warm-tail finding has no
  iOS counterpart, for or against.
- The backend path (§15.13.5), and both towers open in one process beside an app.
- Where the disk taken at a model's first open goes, and whether it comes back (that doc's D14).
- A floor-class iPhone (that doc's D12). The iPhone 17e is not the floor.
- Whether search is any *good* on these photographs. As in §15.12.7, this is a wall-clock
  measurement over photographs whose contents nobody looked at.


---

### 15.14 IN-3m-iOS, the rest of §4.4: three runs, sustained, battery, reboot, compile, HEIC (2026-10-22/23)

⭐ **§4.4's sequence is now run end to end** on the phone of §15.13, which had only one short pass a
row. This section is the runbook's Part A ([`pio7-phone-session.md`](../how-to/pio7-phone-session.md)),
in §15.13's form and marks. ⭐ **IN-3m-iOS is passed** (maintainer, 2026-10-23), on §15.14.8's readings:
what is still owed (the backend's footprint, a floor-class phone) is tracked under that doc's D10 /
D12, not under this gate. The memory gate admits only the measured class (§15.14.7), so the phones
it lets through are the kind measured here.

**Same as §15.13:** iPhone 17e (`iPhone18,5`), iOS 26.6.1 (23G83); the fp16 export, vision
`a30d4dde…`, text `628f7f32…`; the 554-JPEG corpus `10b25ab76b09…`, pushed again and re-verified
(554 copied, 0 failed, same id). **Different:** the harness was rebuilt from master
`a34033f9e`, which adds the `physicalMemory` line; nothing in the measuring path changed. Low Power
Mode off in every row. Every vision row: 554 embedded, 0 unreadable, 0 failed [M], except A8 (53 HEIC,
53 embedded).

| Step | power, as reported | thermal at start | open | predict, median / p90 | decode, median / p90 | per photo, median / p90 | first-50 → last-50 (ratio) | wall clock per photo | pass peak, MB |
|---|---|---|---|---|---|---|---|---|---|
| A1, cold | 10 %, charging | 0 | ⚠ 2 730.76 | 13.13 / 14.93 | 20.35 / 30.80 | **33.33** / 42.94 | 37.74 → 29.29 (0.78) | 36.51 | 189.9 |
| A2, warm, at once | 10 %, charging | 0 | 48.03 | 12.55 / 14.76 | 20.22 / 30.75 | **32.70** / 43.57 | 36.06 → 28.80 (0.80) | 35.96 | 198.6 |
| A3, +20 min idle (first try) | 30 %, charging | ⚠ **2** | 85.23 | 12.82 / 14.85 | 20.36 / 30.68 | 32.80 / 42.30 | 36.52 → 29.89 (0.82) | 36.13 | 191.0 |
| **A3**, retried next day | 80 %, charging | 0 | 132.04 | 12.63 / 14.78 | 20.36 / 30.85 | **32.58** / 43.32 | 37.88 → 29.33 (0.77) | 36.06 | 201.1 |
| **A5**, on battery | 100 %, **unplugged** | 0 | 76.56 | 13.38 / 14.84 | 20.41 / 31.00 | **33.37** / 42.94 | 35.92 → 30.54 (0.85) | 36.84 | 189.5 |
| A5 side row, `coreml-all` | 100 %, full | 0 | 2 820.70 | 13.51 / 15.00 | 20.37 / 30.84 | 33.51 / 43.80 | 36.47 → 29.15 (0.80) | 37.39 | 199.5 |
| **A8**, 53 HEIC, retried cool | 100 %, full | 0 | 77.07 | 8.42 / 11.72 | **52.47** / 54.16 | **61.30** / 65.70 | — (< 200 photos) | 60.51 | 127.2 |
| A8, first try | 100 %, full | ⚠ 2 | 56.04 | 7.69 / 12.45 | 57.25 / 63.45 | 65.59 / 75.51 | — | 65.14 | 131.4 |

Thermal read 0 at every 50th photo of every row that started at 0, and 2 throughout the two that
started at 2. The first A3 and the first A8 started at `.serious` and the harness marked them *"NOT a
planning figure"*; they are kept as side readings, and the retried rows are the steps. The first A3
most likely heated from charging from 10 % during the idle wait; the first A8 from A7's recompiles and
the push before it. A5's ledger peak (223.8 MB) equals the side row's, so the app was probably not
relaunched between them: A5's ledger peak is not its own; its timings and pass peak are.

#### 15.14.1 Three runs: the same 33 ms, and §15.9's third row does not fire

A1, A2 and A3 agree within 0.8 ms a photo (32.58–33.33), and so do A5 and the eleven A4 passes
(32.45–33.46). Last-50 is 28.8–30.5 ms against row 3's 500 ms; no ratio is above 0.85. ⚠ §15.13.1's
caveats stand: a ratio below 1 is warm-up and the corpus's smaller late files, not a throttle answer.
The throttle answer is A4's.

#### 15.14.2 ⭐ Sustained: `.serious` after ~4 700 photos on the charger, and no slowdown

Eleven `coreml-ane` passes back to back over `devicectl` (A4; the harness has no long mode, so this is
the runbook's stand-in, with a ~3 s relaunch between passes), 6 094 photos in about 4 min 20 s, on the
charger at 85 %. Stopped by the rule set for it: two passes in a row starting at `.serious`.

| | where | photos in | time in |
|---|---|---|---|
| 0 → 1 (fair) | pass 4, photo ~350 | ~2 000 | ~80 s |
| 1 → 2 (serious) | pass 9, photo ~250 | ~4 700 | ~3.5 min |

Per photo, the median of every pass was 32.45–33.46 ms, at 0, at 1 and at 2 alike [M]. ⚠⚠ **So on this
phone `.serious` is a temperature, not a slowdown**: the Neural Engine path was not throttled at it.
That bears on the foreground pass of that doc's §14.19, which stops at the next photo once `.serious`
is read and rests `REST_AFTER_HEAT_MS` (60 s, placeholder): on this phone that stop would fall about
every 4 700 photos and would buy no speed. ⭐ **Decided 2026-10-23: kept as built, with no chunking**
(that doc's §14.19.8): the stop protects the phone in the hand, not the speed. ⚠ The cool-down from
`.serious` to `.fair`, which sets how long each pause lasts, is not measured.

#### 15.14.3 On battery: no trigger

A5, unplugged at 100 %, Low Power Mode off: 33.37 ms, thermal 0 throughout, the battery still 100 % at
the end [M]. §4.5's battery reading (*`.serious` inside 200 photos, or a steep delta*) does not fire.
⚠ One 20 s pass cannot move a 5 %-step reading, so D5's 20 % line is neither confirmed nor refuted; it
stays the recommendation. ⚠ It ran from the app's own **Run** button: unplugged, the phone was not
reachable over the network (`CoreDeviceError 4016`).

#### 15.14.4 ⚠⚠ Open time: slow after a long gap unused, not after a reboot

| open, ms | vision | text |
|---|---|---|
| first open of the week, no reboot (A1) | **2 730.76** | — |
| after a reboot, first use in ~2 weeks (A6) | 104.25 | **4 674.49** |
| at once again (A6) | — | 26.89 |
| opened warm, **then** rebooted, then opened first (A6 rerun) | — | **101.16** |
| otherwise, warm | 48–132 | 27–44 |

A reboot alone costs ~100 ms. The 2.7 s and 4.7 s opens were the first of each tower after about two
weeks unused, and they match A7's first open after a compile (3.1 s and 4.2 s, §15.14.5): iOS appears
to discard the device-specific copy it builds at first open, and to rebuild it on the next [I: the
timings match; the mechanism is not visible to the harness]. **For §4.5's reading:** the warm-up is
required, but its case is *the first open after a long gap*, not *after a reboot*. How long the gap
must be is not measured: under two minutes stayed fast, about two weeks did not.

#### 15.14.5 Compile and disk (A7): the compile is cheap; the first open is the cost

| `recompile` | vision (184.9 MB) | text (555.6 MB) |
|---|---|---|
| unpack | 460.73 ms, −184.9 MB | 1 292.72 ms, −566.4 MB |
| `compileModel` | **80.56 ms**, −0.2 MB | **73.36 ms**, +10.6 MB |
| first open | **3 065.75 ms, −370.0 MB** | **4 183.93 ms, −727.1 MB** |
| the whole step | 3 619.61 ms, −554.9 MB | 5 555.66 ms, −1 282.8 MB |

It repeats that doc's §9.7 figures closely for space (within 5 MB) and loosely for time (unpack up to
1.5 ×, first open 1.1–1.25 ×, compile 61–82 ms both times). The
first open takes ~2.0 × the vision model and ~1.3 × the text model in free space, ~1.1 GB together,
outside Photos' container. §3.4's budget (2.58 GB additional) still covers the 1.84 GB both steps
take. ⚠ D14's question, where that space is kept and whether it comes back, has no method yet;
§15.14.4 suggests iOS drops and rebuilds the copy, so the space may come back, which is [I].

#### 15.14.6 ⚠⚠ HEIC (A8): decode is the pass

53 originals from this iPhone's own camera (HEIC, all with Apple's HDR gain map; 41 at 24 MP, 9 at
12 MP, 3 at 7 MP), corpus `21a2d242036d…`, retried on a cool phone: **decode 52.47 ms against predict
8.42 ms, 61.30 ms a photo** [M], about 1.9 × the JPEG corpus's 33 ms, and almost all of it decode.
*"decode ms ≫ predict ms"* (§4.5) holds here, where it did not for the JPEGs (20 against 13). So an
iPhone library pass is decode-bound, and the place to buy time is the decode seam (decoding at the
model's size, not the photo's), not the model. ⚠ 53 is under `MIN_IMAGES` (200): the medians and the
split stand; the windows mean nothing. ⚠ The photos came through Tenshu Drive's iPhone backup, which
kept them as HEIC originals: no conversion on upload [M: format, camera model and gain map read on the
Mac].

⚠ **Prediction was faster on two rows, unexplained:** 8.42 ms at A8 and at A6's post-reboot vision
row, against 12.5–13.4 ms in every other vision row, on the same model and compute units. §15.13.3's
13 ms is therefore not a fixed property of the phone; what moves it is not measured.

#### 15.14.7 `physicalMemory`: the placeholder gate admits this phone

**8 081 145 856 bytes (7.53 GiB)** [M, A1], against D12 ①'s placeholder 6 979 321 856 (6.5 GiB, that
doc's §14.2). The gate admits the one phone the feature was measured on. That does not make the
placeholder a measured threshold: it still needs the backend's footprint and a floor-class phone.

#### 15.14.8 §4.5's readings, and what is still owed

| §4.5 reading | On this phone |
|---|---|
| last-50 > 500 ms, or throttle > 2 | does not fire, in any step (§15.14.1) |
| `.serious` inside a realistic pass on the charger | **yes, after ~4 700 photos**, with no slowdown (§15.14.2) |
| on battery: `.serious` inside 200 photos, or a steep delta | does not fire (§15.14.3) |
| open after a reboot slow again | **no** for a reboot; **yes** after a long gap unused → the warm-up is required (§15.14.4) |
| `compileModel` more than a few seconds; free-space delta ≈ 2 × the package | compile: no (~80 ms). The first open: ~2 × (vision), ~1.3 × (text) (§15.14.5) |
| decode ≫ predict | **yes on HEIC** (52 against 8); not on JPEG (§15.14.6) |

**Still owed, and not by the harness:** D10 / D12 ①'s footprint of a pass through
`CoreMlInferenceBackend` with both towers open (runbook B6); §15.13.5's caveat holds for every pass
peak above. A floor-class iPhone. §4.4's *same 562 files*, which no longer exist as a set. Part A
again after the next iOS update (runbook Part C): the per-image vectors stay on the phone for it.

## 16. As built — IN-4b: the vector sink and `photosLike` (2026-08-14)

**Merged:** _(this PR)_ · **Scope:** `:inference-service` (two composites + an op), `-common`,
`-client`, `-proxy`. **Still no model** — the retrieval is real, the weights are not.

⭐⭐ **The whole of semantic search, proved with no model, no runtime and no hardware.** Real
vectors from `FakeInferenceBackend`, a real store, a real ranking, the real op surface and the real
error paths. IN-3b changes the backend and nothing else in this path.

### 16.1 The sink is device-local, and single-model **by construction**

`EmbeddingStore` + `DefaultEmbeddingStore` (an append-only log via `FileDatabase`) close the gap
§12.4 recorded: IN-3a produced no vectors because they had nowhere to live, and putting one in a
`Syncable` row would have violated IN-D5 outright.

⚠⚠ **The property that had to be structural: cosine between two models' vectors is meaningless
*and computable*.** The arithmetic succeeds, the number looks like a similarity, the results are
noise, and there is no value a caller could inspect to notice. So `put` refuses a foreign stamp,
`rebase` is the only way the stamp moves and it *drops everything*, and the op re-checks the store's
stamp against the query's. Three checks for one hazard, because a comment would have been the
fourth place it was not enforced.

⚠ There is no migration between two models' spaces — that is what a model *is* — so "re-index" is
the only honest answer to a model change. `rebase` is named for stating which model the store now
belongs to rather than for clearing it.

⚠⚠ **A torn tail is discarded, not an error.** An index pass runs for hours and can be killed at
any moment, so the last record is *routinely* half-written. Refusing to open would turn one
interrupted pass into a store that never opens again.

⚠⚠ **A row leaves the log by being struck and scrubbed in place — never by a rewrite, and no
longer by a tombstone** (P0f-a; `LogStrikes.kt`, shared with `DefaultFaceTemplateStore`).
`remove(path)` and the reconcile's `forget(paths)` do two positional writes inside each record of
the path: the flag byte gains `STRUCK` (`0x40`) — that single byte *is* the removal, and cannot be
torn — and then the record's path and floats are overwritten with zeros, its length fields left as
they were so the replay still steps over it. Consequences worth knowing before touching either store:
- **There is no second file.** Nothing copies, renames or deletes to remove a row, so no crash can
  leave a half-promoted copy and no `clear()` can miss one. Opening needs no recovery step: the
  parse skips struck records and re-queues any whose bytes are not yet zero.
- **The log never shrinks on removal.** A removed row keeps its length, as zeros, until `clear` or
  `rebase`. Compaction would need an atomic replace `FileDatabase` does not have; it is absent on
  purpose.
- **`remembered()` is what a reconcile enumerates** — every path with a vector **or** a
  `markVisited` record (and, in the face store, an empty row). `entries()` would miss the visits.
- **Format compatibility.** A log written before this opens unchanged (it holds no struck record;
  `REMOVE` tombstones still replay, and the bytes they hid are erased the first time that path is
  forgotten). A *downgraded* build stops replaying at the first struck record, as at a torn tail.
- **It needs a true positional `writeBytesRange`** — JVM, Android and iOS have one; the interface
  default is a whole-file rewrite. And `FileDatabase` has no `fsync`: the guarantees are against a
  killed process and a failed call, not a power cut (a lost strike leaves the row for the next pass).
- **`clear` and `rebase` replace the file in one loud write**; a write that throws re-reads the
  file rather than guessing what it left.
- **No write is believed because the call returned**. The strike and the scrub are
  read back; an append is confirmed by the file's length (one `stat` — or by its bytes where the
  vector log writes over a torn tail, or the face log over a blanked one); a header, a rebase and
  a wipe's emptying by length and content; the face log's blanking of a torn tail by read-back. Only a wipe's `deleteFile` is unconfirmed, and nothing
  rides on it. Cost of a removal: 2 writes and 3 ranged reads per record — measured on the JVM
  against a 20 000-row, 42 MB face log, 0.24 ms for one row and 113 ms for 1 000.
- **One recovery path**. The invariant: after any operation returns *or throws*,
  memory is what a reopen would parse, and every struck record whose bytes are not zero is queued.
  Any write of unknown outcome — a throw, a tear, a confirmation read that failed after the write
  landed — is settled by `resync()`: discard memory, re-read the log. No branch patches memory to
  match a guess. If the re-read fails the store is *out of step*: it holds nothing and every read
  and write throws `LogOutOfSync` until a re-read succeeds (`clear`/`rebase` are exempt — they
  replace the file). A log that will not read at open is out of step, not empty.
- **`forget` returns a `Removal`** — removed, refused, erasure owed, records not erased — rather
  than throwing one of them. `remove` throws `RemovalIncomplete` carrying it.
- **The face log blanks a torn tail with zeros in place**; it no longer rewrites the file to
  truncate it. `FileDatabase` has no truncate, so that was a rewrite of every live template to
  drop a few torn bytes, and a kill inside it cut the log (found by the open sweep: an older
  template of a re-indexed photo came back). A zero is not a record flag, so the replay stops
  there, and a shorter record written over zeros leaves zeros. ⚠ The vector log is unchanged: it
  leaves a torn tail in place and confirms each append over it by reading the bytes back, so a
  shorter record written over a longer torn one can still leave a stale suffix there — as on
  master, not fixed here.
The crash-point table — every file operation of a removal, a wipe, a rebase, an append and an
open, with every write *and every read* stopped every way, including the recovery re-read — is
`LogStrikesCrashPointsTest`.

### 16.2 ⚠⚠ Unavailable is an error, never an empty list

`photosLike` throws `SemanticSearchUnavailable` — with four distinguishable reasons — where the
easy thing was to return nothing.

"No photos match your search" and "this device cannot search your photos" are different sentences,
and an empty success says the first while meaning the second: it sends the user to look for a photo
they *do* have, and it makes a **switched-off feature indistinguishable from a working one with no
results**, so nobody is ever told to switch it on. The four reasons exist because they need four
different responses — turn it on, install a model, wait for a pass, or nothing you can do.

⚠ An empty *query*, by contrast, returns an empty list. Nothing is wrong with the device; the caller
asked for nothing, and claiming unavailability there would be the same mistake pointed the other
way.

### 16.3 The query is text, and no vector ever crosses a wire

The op takes an utterance, embeds it **proxy-side**, ranks against the proxy's own store and returns
paths and scores. Embedding client-side would put a 768-float array on the IPC wire — the exact
thing IN-D5 keeps off the sync wire — and would require every client to hold the model.

⭐ IN-D5 keeps paying out in places it was not written for: after the numerical-reproducibility
hazard (IN-D13), it now also means a client needs no model, no vectors, and no knowledge that
either exists.

### 16.4 One visit, two consents

The indexer now emits a vector in the same visit as the labels — IN-D8's argument made concrete,
since the library walk rather than the model call is the expensive part.

⚠⚠ **Under `SEMANTIC_IMAGE_SEARCH`'s own consent, not tagging's.** They are separate capabilities
with separate switches, so a user who wants labels but not a searchable index of their photos gets
exactly that. Reusing the tagging check would have widened one consent into two silently — the
kind of thing that is invisible in review because the code is *shorter*.

⚠ A vector the sink refuses is **counted, not thrown** (`embeddingsRejected`), like an unreadable
file: a pass that meets a stale sink half-way through a library should finish and report rather
than abandon three hours of work.

### 16.5 What is proven

- **28 tests** in the module: 6 sink, 7 durable sink, 9 op end-to-end over the wire, plus 4 new
  indexer tests. The op tests drive RO → params → proxy → returned composites, so the client
  registration is exercised rather than assumed.
- ⚠⚠ **Two op tests were rewritten after they passed.** The expected hit was also *first in
  insertion order*, so an implementation that ranked nothing at all would have passed them and every
  ranking claim would have been untested. Seeding the match last is what gave them teeth.
- **No version bump.** Three additive types, nothing reshaped — the same call Track A made when it
  added `photosNear` to `:file-service` without touching `FileService.kt`. `SchemaShapeBaseline`
  named all three and the regenerated file shows three added lines and no moved field.
- Full gate green (1900 tasks); `iosKotlinCheck` green.

### 16.6 What is not

- ✅ **Wired 2026-08-14** (§17). ⚠⚠ It was worse than this line said: not just the op factory but
  the *whole service* was absent from the proxy's schema list, so IN-3a's rows and IN-4a's search
  were unreachable too.
- ✅ **MQ-4 landed 2026-08-14** (`mia-query-layer.md` §13): a third grant, `MiaSource.VISUAL_SEARCH`.
  ⚠⚠ It is the first time two sources share one service, which broke an unwritten assumption in
  Mia's validator — the op allowlist now narrows per grant, or revoking visual search while keeping
  labels would have changed nothing the validator could see.
- ⚠ **It is a scan**, one dot product per indexed photo per call, as IN-D12 said it would be. Fine
  at IN-D12's sizing (~40 MB for 20 000 photos, held rather than re-read); a caller that searches as
  the user types owes debouncing.
- ⚠ The log **only grows** while one model is current. `isCurrent` stops the pass that would do
  that, so it is a latent cost rather than a live one — but nothing compacts it short of `rebase`.
- ⚠ **Every quality claim is still the fake's.** The fake embeds text and image bytes with the same
  hash, so retrieval is provable and *meaningless* about real photographs. Whether the answers are
  good is `models.md` §3.7's question and cannot be asked of a hash.

---

## 17. As built — the wiring slice: four green slices that no device could run (2026-08-14)

**Merged:** _(this PR)_ · **Scope:** `control-shared-proxy` (schema list, three platform factory
chains, one guard test). No new behaviour, no schema change.

### 17.1 ⚠⚠ The whole track was absent from the proxy, not just the new op

§16.6 recorded that nothing wired `inferenceServiceProxyImplementationFactory`. Checking it turned
up something larger: **`inferenceServiceCommonModule` was not in `coreSchemaConfigurations()` either**
— so on a real device the proxy could not materialise a `MediaLabel` at all.

That makes four merged slices unreachable rather than one:

| Slice | Shipped as | Actually reachable |
|---|---|---|
| IN-3a | rows + an index pass | ❌ no schema |
| IN-4a | Mia's label search, "photos of a cat" | ❌ no schema |
| IN-4b | vector sink + `photosLike` | ❌ no schema, no factory |
| MQ-4 | "photos like a sunset over water" | ❌ same |

⚠ Mia's Settings has shown a **Photo contents** grant since IN-4a. The switch was real, the
preference row was real, and the chain behind it could not have run.

### 17.2 ⚠⚠ Why every test passed anyway — and why this is structural

`ProxyModule.kt` already carried the answer, three lines above where the fix belongs, about
Calendar:

> *"Absent until CA-5c, which is why Calendar never worked on a device … Every calendar test used
> its own `TestProxy` registry, so nothing failed until something ran for real."*

**Every service test builds its own registry.** That is the *right* way to test a service — it is
what keeps a service's tests independent of the app — but it means the registry a test assembles is
never the registry the app assembles. No amount of unit or integration testing *inside* a service
can observe this, and the Android smoke gate does not either: it launches apps and asserts no crash,
and a service nobody exercises at startup does not crash.

⭐ **The general rule: a service ships when it is in the proxy's schema list, not when its tests are
green.** Second occurrence, same shape, four slices deep.

### 17.3 The guard

`ProxySchemaCoverageTest` asserts, over the **production** list:

- every `MiaSource`'s service resolves — a grant offered for a service the proxy cannot read is a
  switch that controls nothing; and
- `MediaLabel`, `SimilarPhoto` and `PhotosLike` resolve.

⚠ Verified by removing the two lines and watching both tests go red with the naming message, then
restoring. A guard that has never failed is a guard nobody has checked.

⚠ It asserts **reachability, not correctness**: a registered service that is broken fails somewhere
useful; an absent one fails nowhere.

### 17.4 ⚠⚠ The backend registry is EMPTY in production, deliberately

`InferenceBackendRegistry.Empty` is what the proxy binds. Binding `FakeInferenceBackend` would make
every device claim it can search photos and answer with canned nonsense — *fakes are opt-in, never
a default*, the rule Control-on-iOS paid for.

⭐ **Superseded 2026-09-02 by §20.1**: JVM and Android now contribute `OnnxInferenceBackend` through
a `named("inferenceBackends")` list. ⚠ The rest of this section stands unchanged — the fake is still
never bound in production, iOS and web still contribute nothing, and a device with no artifact still
answers `NO_BACKEND`.

So on a device today: `photosLike` reaches its implementation and answers **`NO_BACKEND`** — "I
don't have a model on this device that can look at your photos yet" — and an index pass is a counted
no-op. ⭐ That is the honest state of the feature, and it is now *reachable* rather than failing as
an unsupported operation. IN-3b becomes a backend swap with nothing else to move.

### 17.5 What is still not wired

- ⚠ **`MediaIndexer` has no schedule** — IN-D6's metered background pass. Deliberately left: with an
  empty registry every pass is a no-op, so a scheduler would be machinery whose only observable
  effect is a log line. It belongs with IN-3b, where it can be watched doing something. ⚠⚠ **Still
  true after §20**, and now the *last* step rather than the next one: `MediaIndexer` is constructed
  nowhere in production and no operation runs a pass, so the store stays empty by construction
  (§20.5). It was sequenced behind R3 — a filled store is unsearchable without the text tower —
  and **R3 shipped 2026-09-02** (§21), so this is now genuinely the next step.
- ✅ **`DefaultModelStore` is wired** as of step 1b (§22), together with the catalog fetch and a
  downloader that drives its staged path — the piece that had never been written. ⚠⚠ It is **inert**:
  with no release key and no published catalog the store is empty on every device, so the backend
  still resolves the **side-loaded, digest-unverified** artifact (§22.4). What remains is not code —
  mint the key, publish `models.json` — plus IN-2c's screen to call the downloader at all.
- ⚠ The vector sink writes to `/inference/embeddings.log` under the `FileDatabase` root. Nothing
  evicts it; `retention-quota-model.md`'s local-cache tier is where that belongs.

### 17.6 ⚠⚠ `INDEX_MARKER_LABEL` was public to the wrong half of the tree (amended 2026-08-30)

§12.4's sentinel carries the note *"public because every reader has to exclude it"* — and it lived
in `:inference-service-proxy`, which **no client module may depend on**. So the readers who most
need it could not see it, and it went unexcluded in the one that shipped first:
`TenshuPhotosRepository.searchByLabel` matches with `contains`, so a Photos user typing `ind` would
have got their entire indexed library back, each hit captioned `__indexed`. `AskMiaImplementation`
was never exposed — it is in a proxy module, so it could always import the constant.

⭐ **The constant now lives in `:inference-service`, beside `MediaLabel`.** A rule that every reader
must obey belongs where every reader can reach it; a rule enforceable only from one side of the
client/proxy line is a rule half the consumers cannot follow.

⚠ Invisible on every device today for the usual reason — with `InferenceBackendRegistry.Empty`
there are no labels, so neither the leak nor its fix can be observed. It was a defect **waiting for
IN-3b to make it visible**, which is the worst moment to discover one.

⚠ Photos is now a **built, waiting consumer** of the label rows: `photos-app.md` §24.3 ships the
grouping, the read and twelve tests for the Things section of its Collections tab, all green against
zero real labels. IN-3b remains a backend swap with nothing else to move — on that side too.

---

## 18. As built — `ClassifyIntent`, the capability Mia's planner consumes (2026-08-15)

A sixth capability and one op, added for `mia-query-layer.md` **MQ-5a** (its §16 carries the
reasoning; this section records what landed on *this* side of the seam).

**`InferenceCapability.QUERY_PLANNING`** — off by default like every other, both switches, absence =
no. ⚠ The content it processes is one **utterance**, which is already device-local conversation
history under MQ-D8, so it stores nothing new. It does make §4's "prompts as an in-use plaintext
class" real for the first time, and the amendment that section names is owed **when a binding sends
one off-device** — the in-process backend does not.

**`ClassifyIntent(utterance, candidates) → List<IntentChoice>`**, at most one.

- ⭐⭐ **The candidate set comes from the caller and the answer is validated against it before it
  returns.** A backend that answers with an id nobody offered is treated exactly as one that
  answered nothing. That is what makes "constrained decoding, never free-text parsed" a property of
  the code rather than a hope about a prompt — and it is why this op, unlike a general text
  generator, can be exposed to a planner at all.
- ⚠⚠ **It returns empty where `PhotosLike` throws**, and the rule behind the difference is worth
  keeping: **throw when the user loses a capability, return empty when only the implementation
  changes.** Semantic search has no fallback, so its refusal is the answer and must name something
  the user can do; a caller of this one has a deterministic planner and needs no sentence about
  models.
- ⚠ `FakeInferenceBackend` implements it as **word overlap with the candidates' own descriptions** —
  deterministic, content-derived, and emphatically not a small model. It proves the plumbing and
  nothing about answer quality, the same caveat `embedText`'s shared-hash trick carries.
- ⚠ Nothing wires a real backend, so on a device this returns empty and Mia's grammar answers
  exactly as before. That is IN-3b's gap, unchanged by this slice.

⚠⚠ **`IntentCandidate` is a *parameter* composite, and forgetting to register it in
`inferenceServiceCommonModule` failed 14 tests with `Schema not found`.** A type that only ever
travels *into* an op is as much a wire shape as a return type, and it is the easier one to miss
because no `returnType` mentions it. Three new types total; nothing reshaped, so no version moved.

---

## 19. The order to do the rest in (2026-08-30)

§7.6's table says what each slice *is* and what it is gated on. It does not say what to do on a
Monday, and read as a to-do list it is misleading in one specific way: **IN-3b looks like one item
and is "a native dependency on four platforms" wearing one id.** This section is the sequence.

Written alongside `photos-app.md` §25, which carries the same phasing from the consumer's side.

### 19.1 Phase 0 — buy the decision before building for it

**IN-3m, on a real phone.** The instrument shipped 2026-08-25 (§15.11); what is missing is hardware
with photographs on it. ⚠⚠ **It is first because it can still say *do not ship this*** (§15.9, third
row), and everything below is wasted effort if it does. It is also the cheapest item on this page.

⚠ Not the emulator, and this is not fussiness: the AVD executes on the host CPU, so a number from it
is a desktop number wearing an Android costume — and a wrong number gets quoted where a missing one
does not (§15.1). The harness refuses to print headline figures when `Build.FINGERPRINT` says
generic/emulator.

⚠ Two prerequisites, and **neither is in this repo by design** (§15.3): the exported
`vision_model_uint8.onnx`, and ≥ 200 real photographs on the device.

⚖️ Running in parallel and blocking nothing technical: **counsel on R9's corpus disclosure.** SigLIP
v1's *weights* are cleanly Apache-2.0 (§3.1.2); WebLI has never been published, so "trained on what?"
has no answer to give. That is a lawyer question, and it does not gate a measurement.

### 19.2 Phase 1 — IN-3b, cut so each step is observable

| | Step | Why in this position |
|---|---|---|
| **1a** ✅ | ONNX Runtime behind `InferenceBackend`, **desktop/JVM first** | No NNAPI, no per-ABI packaging, and §13's harness already runs there. Produces the first device in the project that answers anything but `NO_BACKEND`. **SHIPPED 2026-09-02 — §20** |
| **1b** ✅ | Wire `DefaultModelStore` + the catalog fetch | ⚠ Built and **unwired** since IN-2b (§17.5). Owes the `runtime` filter before a download is safe. **SHIPPED 2026-09-03 — §22**, with the downloader nothing had written and the catalog reshaped to one entry per checkpoint. ⚠⚠ Inert until a key and a catalog exist, which are **deployment** steps |
| **1c** ✅ | **IN-2c** — the Control screen (list / install / remove) | Ungated since IN-4b. ⚠ Not polish: without it a user has **no route to obtain a model**, so a shipped backend is unreachable. **1c-1 (ops) + 1c-2 (the screen) + 1c-3 (the progress stream) SHIPPED 2026-09-04 — §23–§25.** ⭐ A user can now obtain a model, and watch it arrive |
| **1d** ✅ | Android backend (NNAPI / XNNPACK EP) | The genuine risk step — native deps per ABI, APK size, HR #8's smoke gate. ⚠⚠ `onnxruntime-android`, never the JVM jar (§15.2). **SHIPPED with 1a, not after it — §20**, and on the **CPU EP**: §15.12.3 measured NNAPI and XNNPACK both *losing*, so the parenthesis in this row's title is what the step disproved |
| **R3** | The text tower + its tokenizer | ⚠⚠ **A step this cut missed.** A vision tower fills a store nothing can query: `photosLike` takes *text*, so without it a fully indexed device answers `NO_TEXT_EMBEDDING`. Sequenced **before 1e** for that reason — an unindexed device should say "no photos indexed yet", a sentence about the user's device, not "the model cannot embed text", a sentence about us |
| **1e** ◐ | IN-D6's scheduling | ⚠⚠ **Not "add a schedule".** `MediaIndexer` had *no production call site at all*. **SHIPPED 2026-09-04 — §26**: a `HeadlessSafe` pass on an idle+charging tick, over a `MediaSource` seam. ⚠ IN-D6's *visible, pausable* progress is the remaining half |

⭐ Desktop-first is the whole point of the cut: 1a is the step that proves the seam holds a real
runtime, and it can be wrong in ways that are much cheaper to discover without an ABI matrix
underneath.

### 19.3 Phase 2 — the vocabulary, which is where quality lives

A backend gets labels; it does not get *good* labels, and §7.7 is candid that nothing is calibrated.

- ✅ **IN-D9's amended leaf rule** — the per-leaf **absolute** floor, ahead of the margin test. The
  relative rule alone returns "least rejected" and reads most confident exactly where it is most
  wrong (§7.7's `indoors 0.061` over `staircase −0.009`). **SHIPPED 2026-09-04 — §27**; ⚠ the rule
  only, the numbers are still uncalibrated and an uncalibrated label now emits nothing.
- **A labelled sample**, which does not exist. The *scale* is recoverable from the model's graph;
  the per-label cut-off is not.
- ⚠ Budget for §3.7.3: **one image in seven changes its top-1 label** under uint8. Most flips are
  near-ties between two defensible labels; some are `rain` where fp32 said `qr code`.
- The per-label review where scene content shades into attribute inference — a judgement call owed
  by whichever slice ships the vocabulary.

⚠⚠ **A consumer must not invent a threshold to compensate for this.** `photos-app.md` §24.3 records
the trap concretely: a first draft set the Things section's confidence floor at 0.500, which sounds
cautious and would have labelled **nothing at all** — no (image, label) pair in a real library
reaches p = 0.5 (§3.7.2). The cut-off belongs where the vocabulary is calibrated, and a plausible
constant somewhere else is worse than none, because nobody re-examines it.

### 19.4 Phase 3 — consumption, which is mostly already built

- Photos' **Things** section fills with no further Photos work (`photos-app.md` §24.3), and S2's
  label and semantic sources become real for the first time.
- **IN-5 (OCR)** and **IN-6 (faces)** are each a *second model*, not a threshold change — they are
  what "Documents" and "People and pets" actually cost.
- Remaining IN-4: Mia's planner lowering, Drive's own search UI, content ∧ place.

### 19.5 What this ordering is asserting

That the risk on this track is **not** in the Kotlin. It is in three places, and the sequence puts
each one as early as it can go: the *measurement* that can invalidate the model choice (Phase 0),
the *route by which a user gets a model at all* (1c), and the *calibration* that decides whether a
label is worth showing (Phase 2). Building 1a–1e in a different order still produces a working
backend; skipping Phase 0 risks producing it for the wrong model.

---

## 20. As built — IN-3b step 1: the real backend (2026-09-02)

**Scope:** `:inference-service-proxy` (a new `onnx` package across four source sets) and
`:control-shared-proxy` (the DI seam). **ONNX Runtime is now in the shipped graph on JVM and
Android** — the dependency four slices deliberately did not take until §15.12 had measured whether
it was worth taking.

⭐ `MediaIndexer`, `EmbeddingStore` and `photosLike` are untouched. §16 predicted this would be *"a
backend swap with nothing else to move"*, and it was.

### 20.1 The four things that had to move together

Any one of them alone is a feature that lies about itself:

1. **`OnnxInferenceBackend`** — ORT over the pinned vision tower, replacing nothing else.
2. **`SUPPORTED_RUNTIMES` gains `"onnx"`** — IN-3b step 0's rule was *add it in the same commit that binds
   an ONNX runtime, never before, or the filter starts lying in the permissive direction*. This is
   that commit.
3. **The production registry stops being `Empty`** — supplied per platform through a
   `named("inferenceBackends")` list, the same shape `platformSchemaConfigs` already uses. ⚠ Not a
   Koin override: that would make the *order of module installation* decide whether a device can
   search its photos.
4. **The service was already in the proxy's schema list** (§17), so nothing was owed there — which
   is exactly what §17's guard was built to make true in advance.

### 20.2 ⚠⚠ Preprocessing became shipped code, and §15.2's instruction inverted

§15.2 put the pixel arithmetic in test scaffolding and said explicitly **not** to move it to
`commonMain` because "it is test scaffolding, and `commonMain` is shipped code". That was right for
exactly as long as nothing shipped called it. IN-3b makes production a caller, so
`ImagePreprocessing` is now shipped and **the harness calls the shipped one** — including the AWT
resize, via `AwtImageDecoder.pixelsOf`.

⭐ The gain is not tidiness: a harness with its own copy of either half measures a program nobody
ships. §15.12's numbers now describe the code a device runs, and `models.md` §3.7.1 — two sessions
of quality numbers measured on the wrong pixels — is the reason that matters.

### 20.3 ⚠⚠ CPU only, and it is a measurement rather than a default

§15.12.3: NNAPI lost to the plain CPU EP by 44–70 % and XNNPACK by 12–26 %, across three passes, on
the one phone anyone has measured. ⚠ It is also a hard constraint: `addNnapi` ships only in
`onnxruntime-android`, and `src/onnxMain` compiles into **both** artifacts, so an accelerated
provider needs a per-platform seam. Worth building the day a device is found where one wins — and
IN-D13 now says the ladder is a list of things to *measure*, so that day is a measurement, not a
guess.

### 20.4 ⚠⚠ A filename is not provenance — what the shape guard is for

Until IN-2c verifies a digest against the signed catalog, the artifact is asserted to be the pinned
model **by its filename**. A vector from a different checkpoint, stamped as this one, is §16.1's
failure exactly: cosine across two spaces is meaningless *and computable*, so nothing downstream
could notice. So the backend checks the two things ORT gives away for free, and refuses the
artifact otherwise:

- **the input's spatial size** — feeding a 256-patch model 224² pixels returns a vector rather than
  an error (§15.11.4); and
- **the presence of `pooler_output`** — SigLIP pools with a *trained attention head*, so
  mean-pooling `last_hidden_state` is a different embedding space that also produces 768 plausible
  numbers.

⚠ The rejection is **latched**: it is a property of the artifact, not of the call, and re-checking
would re-fail once per photograph in a library pass.

### 20.5 ⚠⚠ What a device can actually do now — and what it still cannot

Precisely, because "the backend is real" invites a wrong reading:

| | Before | After |
|---|---|---|
| `photosLike` on a device with the model side-loaded | `NO_BACKEND` | **`NOT_INDEXED`** |
| `photosLike` on a device without it | `NO_BACKEND` | `NO_BACKEND` (unchanged) |
| iOS route-1, web | `NO_BACKEND` | `NO_BACKEND` (unchanged — no runtime there) |

⚠⚠ **Nothing indexes.** `MediaIndexer` is constructed **nowhere** in production and the service
exposes exactly two operations — `PhotosLike` and `ClassifyIntent` — neither of which runs a pass.
The store therefore stays empty by construction, which is why the visible change is one error
becoming a different, more accurate error.

⚠⚠ **And `photosLike` takes text**, so even a full store would answer `NO_TEXT_EMBEDDING` until R3
lands the text tower. ⭐ That is why the remaining order is **R3 first, then IN-D6's schedule**
(swapped 2026-09-02): with the text half present, an unindexed device says *"no photos have been
indexed yet"*, which is a sentence a user can act on; the other order says *"the installed model
cannot embed text"*, which is a sentence about us.

### 20.6 The side-load convention, and why it is not delivery

`OnnxModelFiles` puts the artifact at `<FileDatabase root>/inference/models/<modelId>.onnx`, beside
`/inference/embeddings.log`, so a device's inference state lives in one place.

⚠⚠ **Nothing verifies these bytes.** IN-2b's `DefaultModelStore` — staging, digest check against the
signed catalog, an index — remains unwired (§17.5). A maintainer pushing a file by hand is enough to
run the feature end to end and is emphatically not enough to ship it.

⚠ The locator resolves **per call**, not once, so a model that arrives after the proxy started is
picked up without a restart, and a deleted model stops being claimed. `supports()` is false whenever
the file is absent, which is what keeps `NO_BACKEND` honest.

### 20.7 What the tests pin

- Six `OnnxInferenceBackendTest` cases, five of which run **against the real 100 MB artifact** when
  it is handed to them (`-Dtenshu.inference.model=…`) and skip with a printed reason otherwise: it
  embeds into a 768-d unit vector stamped with the pinned provenance, it is deterministic, two
  different images point in different directions, and **a model described with the wrong input size
  is refused** rather than embedded.
- ⚠ The no-model cases are the ones that run in the gate, so they carry the behaviour a device
  without an artifact gets: claims nothing, throws nothing, returns `null`.
- ⚠ Untested by any of this: whether the labels are any good, and whether the vectors a *phone*
  produces match a desktop's (they do not exactly — different resamplers, §20.2 — which is
  tolerable only because IN-D5 keeps embeddings device-local).

### 20.8 ⚠⚠ What it costs in the download, measured

ONNX Runtime ships as native libraries, and they are **stored uncompressed** in the APK, so the
figure is the same either way:

| | ORT in `control-debug.apk` |
|---|---|
| **arm64-v8a — what a modern phone downloads** | **18.5 MB** (`libonnxruntime.so` 17.6 + the JNI shim 0.9) |
| armeabi-v7a | 13.1 MB |
| all four ABIs (the universal debug APK) | 73.8 MB |

⚠⚠ **Every Control install pays this, including the ones that never switch inference on.** A release
build ships one ABI per device through the App Bundle, so the real figure is the first row — but it
is unconditional, and it is spent on a capability that is off by default (IN-D3) and inert without a
model. That is the honest trade for the backend being *in* the app rather than a download away.

⚠ Reducing it is possible and is not free: a dynamic feature module would make the runtime arrive
with the model, at the cost of a delivery mechanism per platform. Worth revisiting **if** the
feature ships off by default and stays that way — not before, because the measurement to make that
call is how many people enable it.

### 20.9 Verification

- `:inference-service-proxy:jvmTest` — six cases, five run against the real 100 MB artifact when
  handed it (§20.7); `:control-shared-proxy:desktopTest` green.
- **JS and iOS lanes** both green (`compileTestKotlinJs`, `compileTestKotlinIosSimulatorArm64`) —
  the two the JVM gate never reaches, and this change touches `commonMain` of two modules.
- **Android smoke gate PASSED** (House Rule 8): ten apps launched, no crash or ANR, schema lockstep
  **v20** across all of them, every session admitted. ⚠ It reported one typed-IPC WARN on
  `tenshu/health/service/workouts → unannouncedSession` — the Fit canary, which the gate
  deliberately does not rebuild, and which shares no code with this change.
- ⚠ **Not run: the full test gate.** The main checkout was simultaneously holding another feature's
  work, so a green there would have vouched for someone else's tree as much as this one; this
  change was built and verified in its own worktree instead.

---

## 21. As built — IN-3b R3: the text half (2026-09-02)

**Scope:** `:inference-service-proxy` (a tokenizer, a vocabulary reader, `embedText`),
`:control-shared-proxy` (two more locators), and `scripts/inference/export-text-tower.py`.
`photosLike` now returns **photographs** on a device carrying all three artifacts, instead of
`NO_TEXT_EMBEDDING`.

### 21.1 The tokenizer is ours, and that was measured rather than assumed

`SiglipTokenizer` is SentencePiece Unigram behind a normalizer chain ending in a **`Precompiled`
charsmap** — a 317 KB compiled blob. `models.md` §3.9 is the measurement that decided not to carry
it: a Kotlin-equivalent using NFKC plus a **twelve-codepoint fix-up table** reproduces the reference
on **5 000/5 000 lines of prose, 70/70 queries, and 63 852/63 859 codepoints**.

⭐ So R3 adds **no second native dependency** on top of §20.8's 18.5 MB per ABI. The vocabulary is
3.1 MB packed and ships **beside the weights**, never in the app.

⚠⚠ The fidelity tests pin **token ids against reference output**, not "reasonable-looking"
tokenization — 41 golden cases covering eight scripts and every character the fix-up table exists
for. A tokenizer that disagrees returns a plausible vector for the wrong query, and there is nothing
downstream that could notice.

⚠⚠ **As built, corrected 2026-10-04: those tests compared ids with the padding stripped, and the
padding was wrong.** The tokenizer filled the 64-token row with `<pad>` (id 0); the checkpoint pads
with `</s>` (id 1) and the tower has no attention mask. It now pads with the vocabulary's `</s>` id
— there is no fallback, a vocabulary without the piece is refused — and the goldens are whole rows
generated by `scripts/inference/golden-tokenizer-cases.py`. On the shipped ONNX pair, top-1
agreement with the fp32 reference went **2/36 → 29/36** (`models.md` §3.9.4).

⚠ **As built, corrected 2026-10-06:** a character above U+FFFF that is not a piece was two `?`
bytes, and is now its four UTF-8 bytes as the reference has it — 436 of the 1 249 such emoji. The
goldens are 57 rows (`models.md` §3.9.5).

### 21.2 ⚠⚠ int8 here, uint8 on the vision side

The vision tower's own recipe drifts **0.866/0.823** on this tower and **changes the top-ranked
caption for one query in four**; `QInt8` gives **0.987/0.983** at the identical 280 MB
(`models.md` §3.9.3). ⚠ §3.5's *"int8 does not run in ORT's Java build"* is `ConvInteger`-specific
and the text tower has no convolutions — asserted by a JVM test that loads the int8 artifact
through the Java build, because "should not apply" is not a measurement.

⚠ **The obvious diagnosis was wrong**, and §3.9.3 records it: the 250 000 × 768 embedding table
does get a single scalar scale, and fixing that changes the drift by 0.002. The loss is in the
transformer.

### 21.3 What a device can do now

| | `photosLike` answers |
|---|---|
| all three artifacts present, store indexed | **ranked photographs** |
| all three present, nothing indexed | `NOT_INDEXED` |
| vision tower only | `NO_TEXT_EMBEDDING` |
| no artifacts, or iOS/web | `NO_BACKEND` |

⚠⚠ **Nothing indexes yet** — `MediaIndexer` still has no schedule and no operation runs a pass
(§20.5), so the second row is what a real device sees today. IN-D6's schedule is the last step
before a **side-loaded** device works.

⚠⚠ It is *not* the last step before a user gets this. §19.2's **1b** (wire `DefaultModelStore` +
the catalog fetch) and **1c** (IN-2c's install screen) are both unbuilt, and without them there is
no route by which anyone obtains the ~383 MB these three artifacts weigh — §20.6's side-load
convention runs the feature and does not ship it.

### 21.4 ⚠ The `-D` forwarding list bit for the third time

`build.gradle.kts`'s `harnessProperties` exists because a flag the tests read but the list does not
forward makes a test **skip while reporting success**. `tenshu.inference.tokenizer` was added with
the tokenizer tests; `tenshu.inference.textModel` was forgotten one commit later, and the text tests
passed having run nothing — with a skip line naming a *different* absent flag, which is what made it
look fine. ⚠ The XML report's `skipped=0` is the check that catches this; a green build is not.

---

## 22. As built — step 1b: the store and the catalog fetch, wired (2026-09-03)

**Scope:** `:inference-service-proxy` (a downloader, a progress holder, a components factory, and a
reshaped catalog), `:control-shared-proxy` (the DI seam) and `:core-common` (one liveness key).

§19.2's step 1b. IN-2b built `DefaultModelStore` and `HttpModelCatalogSource` in August and left
them unwired because "they need the catalog fetch and IN-2c's screen to be worth anything" (§17.5).
This connects them, adds the piece that was never written — **nothing drove the staged path** — and
reshapes the catalog so that the three files a checkpoint actually consists of are one entry.

⭐ `MediaIndexer`, `EmbeddingStore`, `photosLike` and the backends are untouched.

### 22.1 ⚠⚠ Wired, and still inert — which is the honest outcome, not a shortfall

`MODEL_CATALOG_PUBLIC_KEY_HEX` is blank and there is no published catalog, so:

| | Today |
|---|---|
| `catalogSource.load()` | `null`, **without making a request** |
| `downloader.download(id)` | throws `ModelCatalogUnavailableException` |
| the store | empty on every device |
| the backend | resolves the **side-loaded** file, exactly as before |

⚠⚠ **Both blanks are deployment facts, not code ones.** Minting the release key — its private half
kept offline, as `tiles-signer keygen` does — and publishing `models.json` beside the artifacts are
maintainer actions, and they are the only two things standing between this machinery and a user who
can obtain a model. `models.md`: *a client with no pinned key downloads nothing, loudly.*

⚠ **Nothing calls the downloader**, and that is the slice boundary. IN-D3 keeps inference off by
default and IN-D10 requires the user to see what a model costs before it is fetched, so an automatic
first-launch fetch would be the wrong feature. The trigger is IN-2c's screen (step 1c).

### 22.2 ⚠⚠ One entry, N artifacts — a half-installed checkpoint is now unrepresentable

`ModelEntryData` carried one `path` and one `sha256`. A working device carries **three files**: the
100 MB vision tower, the 280 MB text tower and the 3.1 MB vocabulary. Three entries would have cost
no format change, and would have left the failure that `OnnxModelFiles` already warns about wide
open: nothing ties a text tower to its vision sibling except a filename, and a mismatched pair
produces cosines that are **meaningless and computable**, so nothing downstream could notice (§21.1).

So an entry now carries `artifacts: List<ModelArtifactData>`, each with its own `role`, `path`,
`sha256` and `byteSize`, and the store commits **all of them or none**: every rename happens first
and the single index record is written last. A crash anywhere in between leaves files nothing
references — harmless, the next attempt overwrites them — and no index record, so nothing is
installed.

⭐ The distinction against IN-D10's *per capability, never one blob* is the load-bearing part, and
they point opposite ways for a reason: a **capability** split exists so a user can decline half of
what a model offers; a checkpoint's **files** cannot be declined half. Two of three artifacts is a
device that answers wrongly, not one that answers less.

⚠ Free to do now and expensive later: no catalog has ever been published, so the reshape has no
migration and no deployed reader. The same change after a key exists is a signed-format change.

### 22.3 What the downloader inherits, and the two places it differs

`DefaultModelDownloader` is `DefaultRegionDownloader` with the model catalog's shapes substituted —
streamed (memory is O(chunk), not O(model)), resumable via `Range`, digest-gated, joining a running
job rather than starting a second, and holding `BackgroundLiveness` under its own `MODEL_DOWNLOAD`
key so an overlapping map-region download cannot lower it out from under this one. Every ⚠ in that
class was paid for once already. Two differences:

- ⚠⚠ **A null catalog becomes an error here.** `load()` returns `null` rather than throwing because
  it is *polled* (§11.3) — but a download is a thing a user asked for, and reporting "no such model"
  when the truth is "no trusted catalog" blames the wrong thing.
- ⚠⚠ **Progress spans the checkpoint, not the file.** A user installing SigLIP is waiting for one
  ~383 MB job; three bars that each run 0→100 describe work nobody asked about. The total is the
  entry's and `written` accumulates across artifacts.

⚠ The resume subtlety is carried over verbatim and tested: the first published snapshot already
carries the staged byte count, because publishing `0` and correcting it one step later is not the
same thing — the `0` goes out on the wire, and that is precisely the bug the region twin shipped
(`tech-debt.md` row 17).

### 22.3a ⚠⚠ The all-or-nothing commit created a resume bug, and it was found by review

§22.2's property has a consequence the region twin cannot have: because artifacts commit
**together**, an interrupted download can leave one of them **fully staged and still uninstalled** —
a region's single bundle is either committed or partial, never finished-but-waiting.

Resuming such an artifact asks for `Range: bytes=<its own length>-`, an empty tail, which a real
server answers **416 Range Not Satisfiable**. 416 is not a success, so the fetch threw and **a
checkpoint interrupted between artifacts could never finish** — it would fail identically on every
retry, forever, having already paid for the bytes.

⭐ The fix recognises a finished artifact *before* any request. ⚠⚠ **By its digest, never by its
byte count**: a truncation that happens to land on the advertised length, or staged bytes left by an
attempt against different content, would otherwise be promoted having never passed the gate — the
one thing this path must not do. Full length with the wrong digest starts over.

⚠ Found by reading the diff, not by a test — nothing in the suite covered an interrupt *between*
artifacts, because that state did not exist until this slice created it. The two cases now pin both
directions.

### 22.4 The backend prefers a verified copy, and still falls back

`OnnxModelFiles.storeLocator(store, root, role)` asks the store first and the side-load convention
second. ⚠⚠ The store wins because it is the only answer that has been *verified* — every artifact's
sha256 checked against the signed catalog, and all three landed together — and an unverified file
that happens to share the name must not shadow it.

⚠ The fallback stays because it is the only route to a model that exists today. Removing it before
1c would take the feature from "runs on a maintainer's phone" to "runs nowhere". ⚠ An index record
whose bytes were deleted from under it falls through rather than naming a missing file.

⚠⚠ **The locator is on the per-image path, and the wiring nearly made it expensive.** The backend
resolves the model for every `supports` check and every embed; the locator this replaced was a bare
`isFile`, whereas asking the store means reading `SecureStorage` and parsing the index JSON — ten
thousand times over a real library, against §15.12's 121 ms/image. `DefaultModelStore` therefore
**memoises the index**, refreshed by every write (it is a process-wide `single`, and nothing else
writes the key). ⚠ It memoises the *index*, never the resolved file, so an artifact deleted from
under the record is still noticed. ⭐ The general rule: a slice that only *wires* something must not
leave the hot path costlier than it found it.

### 22.5 ⚠ A cost guard, and why it is not the safety one

`HttpModelCatalogSource` now returns `null` **without a request** when the key or the base URL is
blank. `ModelCatalogVerifier` already refuses a blank pin, and *that* is what makes this safe; what
the guard saves is one DNS failure per refresh, forever, on every device — an error that reads like
a network fault rather than "this is not set up yet". Asserted on `requestHistory`, because the
returned `null` looks identical with the guard removed.

### 22.6 What the tests pin

**130 module tests, 0 skipped** (confirmed in the XML, not from the green line), of which 22 are new:

- **15 downloader cases** — a clean landing; the digest gate; ⚠⚠ a mismatch **discarding** staged
  bytes while a transport failure **keeps** them; a `Range` resume appending only the tail; ⚠⚠ a
  range request answered `200` restarting instead of appending; the resumed-progress snapshot; a
  second call joining the first; an unknown id and an untrusted catalog each refused **before any
  request**; liveness raised and released; and the three multi-artifact cases — every role landing,
  progress accumulating across files, and ⚠⚠ **a failure on the second artifact installing nothing
  at all**; and §22.3a's pair — a fully staged artifact **not requested again**, and one staged to
  full length with the wrong bytes **re-fetched**.
- **6 locator cases** — the store/side-load precedence in both directions, an index record with no
  bytes falling through, per-role fallback, and resolution per call.
- **1 memoisation case** — fifty `pathOf` calls read the delegate **zero** times after the first,
  and a `remove` still takes effect (a memo that outlived a mutation would report an evicted model
  as installed).

⚠ **Five mutants, and one survived first time.** Deleting the 200-not-206 restart, the mismatch
abort, the resume-progress argument, the all-or-nothing commit and §22.3a's digest check each fail a
named test *now* — but
`aDigestMismatchThrowsAndDiscardsTheStagedBytes` originally passed against a build that kept the
corrupt bytes, because it asked for resumable bytes under the *good* digest, which returns `0`
either way. ⚠⚠ The assertion had to name the digest the failed attempt actually staged under. A test
that cannot fail is worth less than no test, because it is counted.

### 22.7 What is still not true

- ⚠ **Nobody has fetched a real catalog or downloaded a real artifact**, for the same reason as
  IN-2b: no key, no URL, no published model. What is proven is that the machinery refuses everything
  it should and lands bytes in the right order; what is unproven is everything about a real
  download — resumption across a real network, a real 383 MB checkpoint, and the local-cache ceiling
  interacting with it (`retention-quota-model.md` P2).
- ⚠ **No eviction policy.** A checkpoint is the largest thing this project writes to a device and
  nothing reclaims it; `retention-quota-model.md`'s local-cache tier is where that belongs.
- ⚠ The store has **no `Downloading` state wired to the progress holder** — `ModelInventory.stateOf`
  still takes it as an argument. IN-2c is what joins them.

---

## 23. As built — step 1c-1: the operation surface for models (2026-09-04)

**Scope:** `:inference-service` (three wire types, four operations), `:inference-service-common`
(registration), `:inference-service-client` (four ROs), `:inference-service-proxy` (four
implementations) and `:control-shared-proxy` (the DI seam).

§19.2's step **1c**, first half. IN-2c is *"the Offline-maps-shaped model list"*, and §11.1 is why
it was never a UI slice: **Control reads through operations**, so the screen was gated on ops that
did not exist. They exist now. ⚠ **The screen itself is 1c-2** — this slice is deliberately the
half that can be tested over the wire.

### 23.1 The four operations, and the one asymmetry worth knowing

| Op | Reads | Fails with |
|---|---|---|
| `availableModels` | the **verified** catalog (network, proxy-side) | `NO_CATALOG` |
| `installedModels` | proxy-local state only | — |
| `downloadModel(id)` | 1b's downloader | `NO_CATALOG` / `UNKNOWN_MODEL` / `TRANSPORT_FAILED` / `INTEGRITY_FAILED` / `STORE_NOT_OWNED` / `STORE_LOCK_UNAVAILABLE` |
| `deleteModel(id)` | the store | `NOT_INSTALLED` / `STORE_NOT_OWNED` / `STORE_LOCK_UNAVAILABLE` |

⚠⚠ **`availableModels` throws `NO_CATALOG` where `installedModels` returns an empty list**, and the
asymmetry is the point. "We publish no models" is a false sentence about *us*; "this device holds
none" is a true one about the *device*, and it is answerable offline — which is what lets a screen
still offer **delete** with no network. Today every device takes the first path, because no release
key is pinned.

⚠ The four download failures stay four reasons because a screen owes each a different sentence and
two of them a different button: retrying a transport failure is sensible, retrying an integrity
failure fetches the same wrong bytes from the same CDN.

⚠⚠ **`STORE_NOT_OWNED` is the one that is neither retryable nor transient** (F2v, 2026-10-02).
A second desktop Control is refused the model store for as long as it runs
(`photos-people-and-pets.md` §12.12, *one process at a time*). Before this reason existed the
refusal reached `downloadModel`'s catch-all, which is `TRANSPORT_FAILED` — the bucket whose sentence
is "try again" — and `deleteModel` had no mapping at all. ⚠ It is a new **value** of the `reason`
string, not a new member of a `TConstant` family, so it reshapes nothing: no version bump, and
`SchemaShapeBaselineEnforcementTest` is unchanged.
⚠ The screen should rarely see it. A refused store is a standing state, so Control's Models screen
learns it with the list (`ModelStoreAccess`, process-local like `LastIndexCoverage`), says so up
front and takes the download and remove buttons away. The typed reason is the backstop.
⚠⚠ **`STORE_LOCK_UNAVAILABLE` is not `STORE_NOT_OWNED`.** The store's lock can also fail to be
created or taken at all: permissions, an I/O failure, a filesystem with no locks. Then there may be
no other Control, and a sentence that says to quit one sends the user after a process that is not
there. The refusal is categorical from the lock to the sentence (`ModelStoreRefusal` →
`ModelStoreAccess.Refusal` → `StoreReadOnly`), each kind has one sentence (`storeReadOnlyNotice`),
and the second says what failed and where — the lock path and the cause — and names no remedy,
because none is known. Download and remove are disabled in both. Also a plain string value.
⚠ `ClientModelsRepository.delete` used to drop the op's result, so **every** removal failure was
silent; it now returns an outcome and the screen says a failed removal.

⚠⚠ **`deleteModel` touches the store and nothing else.** IN-D10 requires a screen to say which of
"downloaded" and "enabled" an action changes; keeping consent out of this *operation* is what makes
that promise enforceable rather than remembered.

### 23.2 ⚠⚠ Wiring the ops exposed a runtime claim that was false off JVM/Android

`SUPPORTED_RUNTIMES` says `onnx`. It lives in **`commonMain`**, while `OnnxInferenceBackend` lives in
**`onnxMain`** — which compiles into the JVM and Android artifacts only. So an iOS or web build has
always *claimed* a runtime it does not contain.

That was harmless for exactly as long as nothing could download. `DownloadModel` ended that: an iOS
device would have listed the pinned SigLIP and offered to spend **~383 MB** on a checkpoint it can
never execute — the precise failure IN-D13's filter exists to prevent, and one §20's own comment
asserts does not happen.

⭐ The claim is now **contributed per platform**, the same shape as `named("inferenceBackends")`, and
`modelDeliveryComponents` defaults to the **empty** set. ⚠⚠ Absence is therefore the *safe* answer
rather than the permissive one — a new platform that says nothing offers nothing, which is the
direction a forgotten wiring line should fail in.

⚠ The constant is not deleted: it is the honest claim *of the `onnxMain` source set*, and the two
DI sites that link it are the two that pass it.

### 23.3 ⚠⚠ A registration gap the wire test caught, and the rule that predicts it

`downloadModel` returned `No request object factory registered for type:
tenshu/inference/installedModel` — at the call site, a long way from the cause.

`InstalledModel` needs a **typed request object** in `-client`; `AvailableModel` does not. The
difference is not "return type vs stored row", which is how §16 phrased it for `SimilarPhoto`:

⭐ **The question is whether the type is returned _bare_ or _inside a list_.** A `TList` result
builds its own members, so a list-only type needs nothing beyond its `dtoFactory`. A type returned
*directly* is what the chain must construct, and it needs the `roFactory`. `downloadModel` returns
one `InstalledModel`; `availableModels` returns a `TList<AvailableModel>`.

⚠ Found by a test that runs the **real wire**, not by a proxy-side unit test — the same class of
miss as §18's `IntentCandidate`, and only the end-to-end shape sees it.

### 23.4 What the tests pin

**142 module tests, 0 skipped** (confirmed in the XML), 12 new:

- **10 operation cases** over the real op wire — the catalog crossing as renderable rows with the
  **summed** byte size and decoded capability ids; a nameless entry falling back to its id; ⚠⚠
  `NO_CATALOG` as a typed failure rather than an empty list, *and* an empty list as a distinct,
  reachable outcome; `installedModels` answering with no catalog at all; each of the four download
  failures keeping its own reason; delete removing, delete-absent failing, and delete leaving
  consent untouched.
- **2 delivery cases** — the same catalog, key and bytes offered to a build that claims no runtime
  (nothing) and one that claims `onnx` (the model).

⚠ One mutant per new guard, all caught — including restoring the old `SUPPORTED_RUNTIMES` default,
which fails `aPlatformThatClaimsNoRuntimeIsOfferedNoModels`.

### 23.5 What is still not true

- ⚠ **No screen.** These ops have no caller, so a user still cannot obtain a model — 1c-2.
- ⚠ **Still inert.** No release key, no published catalog: `availableModels` answers `NO_CATALOG` on
  every device, which makes the *error* path the only one a real device exercises today.
- ⚠ **No progress on the wire.** `ModelDownloadProgressHolder` exists proxy-side and nothing streams
  it; `downloadModel` is one-shot, so a screen shows an indeterminate state until it resolves. The
  streaming op lands with 1c-2, where there is something to render it.

---

## 24. As built — step 1c-2: the Control screen (2026-09-04)

**Scope:** `:control-shared` (a repository, a view model, a screen, the destination, the Settings
row and the DI).

⭐⭐ **A user can now obtain a model.** §19.2 called 1c *"not polish: without it a user has no route
to obtain a model, so a shipped backend is unreachable"* — and for four slices that was literally
true: the ONNX backend (§20), the text tower (§21), the store and downloader (§22) and the
operations (§23) were all built, tested, and reachable by nothing. This is the caller.

⚠ **1c-3 is the determinate progress bar.** `downloadModel` is one-shot, so the row shows an
indeterminate spinner — exactly what `downloadRegion` v1 shipped, and honest: there is no byte count
to render until the progress stream op exists.

### 24.1 Settings → Models, mirroring Offline maps because it is the same problem

Control owns the screen for the same reason it owns Offline maps: **the proxy owns the store and the
egress**, so the management surface cannot live in whichever app happens to want inference. The row
sits beside Offline maps under Settings, and the shape — list, size, state, delete — is IN-D10's.

### 24.2 ⚠⚠ "No catalog" and "an empty catalog" stay two different screens

The op surface distinguishes them (§23.1) and this is where that pays: `catalogUnavailable` renders
*"Can't reach the model list. Showing what's already on this device."* above the installed rows,
rather than *"No models are available for this device yet."*

⚠ Collapsing them would tell a user with no pinned key that we publish nothing — **and hide the
models they have already installed**, which are precisely the ones that still work with no network.
The region twin shipped that bug once; the join here takes a **nullable** catalog rather than a
possibly-empty one so the two cannot be confused.

⚠⚠ **A model on disk that the catalog no longer lists is still shown.** It occupies the space either
way, and a management screen that cannot list it cannot delete it — which is how a withdrawn model
becomes unreclaimable space.

### 24.3 ⚠⚠ What the delete dialog has to say

IN-D10: *"deleting a model must not silently disable the capability, nor vice versa; say which one
the action does."* §23 made that enforceable in the **operation** — `deleteModel` touches the store
and nothing else. This is where it gets said:

> The model stops being used right away. Its 383 MB is released the next time Control starts. The
> features that use it stay switched on, and stop working until you download it again.

⚠⚠ It said *"This frees 383 MB on this device"* until 2026-10-02. Since F2v
that is false for as long as Control keeps running: a removal clears the index at once and the
bytes go at the next process's first index read (`photos-people-and-pets.md` §12.12,
*reclamation waits for the next process*). So the dialog says what happens, a removal is followed
by *"Removed. Its space is released the next time Control starts."*, and an update by *"Updated.
The previous version's space is released the next time Control starts."* — the old generation is
held the same way. ⚠ No figure on the screen is derived from the disk: every size is the
checkpoint's own, from the index or the catalog, and there is no "space used" or "space free"
total, so nothing counts a removed model's bytes either way. The op's and the repository's
contracts say the same (`DeleteModel`, `ModelsRepository.delete`, `ModelDeleteOutcome.Removed`).

⚠ A dialog rather than an instant delete, because the undo is a ~383 MB re-download.

### 24.4 Four outcomes, four sentences, and one that must not say "try again"

`IntegrityFailed` renders *"That download didn't match its signature and was discarded. Nothing was
installed."* ⚠⚠ Deliberately **not** a retry prompt: the bytes did not match the signed catalog, so
retrying fetches the same wrong bytes from the same CDN. `TransportFailed` is the one that says try
again. Collapsing the two either offers a button that cannot work or withholds one that would.

### 24.5 What the tests pin

**18 cases, 0 skipped**, all pure — no client, no Compose:

- **9 snapshot cases** — the join, the version-based staleness rule in both directions (an older
  resident copy is *installed with an update*, never absent; a newer one offers nothing), ⚠⚠ the
  no-catalog-vs-empty-catalog distinction, the withdrawn-but-resident row, and the supporting line
  always carrying the size except while removing.
- **9 view-model cases** — load, a landed download refreshing the row, ⚠⚠ the single busy slot
  refusing a second download, each of the four failure outcomes keeping its own message *and*
  releasing the row, a throwing repository still releasing it, and delete asking first.

⚠⚠ **A mutant that does not compile looks exactly like a surviving one.** The first sweep reported
the no-catalog guard as untested; the mutation had left `available.map` on a nullable and the build
had failed, which the runner swallowed. A **value** flip (`catalogUnavailable = true → false`)
compiles and fails the right test. All four guards are load-bearing under the corrected sweep.

### 24.6 What is still not true

- ⚠ **Still inert.** No release key and no published catalog, so this screen says *"Can't reach the
  model list"* on every device today. It is the honest sentence, and it is the one a user can act on
  only once the two **deployment** steps happen: mint the key, publish `models.json`.
- ⚠ **No determinate progress** (1c-3), and no `Downloading…` byte count.
- ⚠ **The capability switches do not live beside it yet.** IN-D10 wants "what is enabled" next to
  "what is downloaded"; today they are two surfaces, and the delete dialog is what carries the
  relationship in words.

---

## 25. As built — step 1c-3: the download bar tells the truth (2026-09-04)

**Scope:** `:inference-service` (a wire composite + a stream op), `:inference-service-common` and
`:inference-service-client` (registration + the stream RO), `:inference-service-proxy` (the stream
implementation) and `:control-shared` (repository, view model, screen).

§24 shipped the screen with an **indeterminate** spinner, because `downloadModel` is one-shot and
there was no byte count to render. This is the count. ⭐ **1c is now complete.**

### 25.1 A device-local push, not a poll

`downloadProgress(modelId)` is a stream op whose source is the proxy's in-memory
`ModelDownloadProgressHolder` — ticked once per streamed chunk by the downloader §22 already built.

⚠ **Not a `DynamicProperty`, and never the synced store.** Model install progress is device state
(IN-D4/D5): pushing a download bar to the user's other devices would describe work none of them is
doing. The stream reuses the framework's `StreamCallback`/`StreamElement` wire and nothing else.

⚠ Success or failure of the whole download stays on `downloadModel`'s own return — it yields the
`InstalledModel` or throws `ModelUnavailable`. The stream carries no phase field; it drives the bar
and completes when the download does.

### 25.2 ⚠⚠ The bar spans the checkpoint, not the file

A checkpoint is three artifacts fetched in sequence. `downloadedBytes` accumulates across them and
`totalBytes` is the entry's sum, so the bar runs 0→100 **once**. Three bars that each run 0→100
describe work nobody asked about, and a per-file count would visibly reset twice on a ~383 MB
install. The row's second line becomes **"100 MB of 383 MB"** for the same reason.

### 25.3 ⚠⚠ The registration wait is asymmetric, and that is the whole design

The subscriber can arrive before the download has registered its entry, and an absent entry means
two opposite things:

| | |
|---|---|
| **in flight** (`isDownloading`) | the entry is merely **late** — wait, however long it takes |
| **not in flight** | nothing is coming — **END the stream** |

⚠⚠ A plain wall-clock timeout is wrong in both directions. `DefaultModelDownloader` registers
*after* `catalogSource.load()` — a network fetch of tens of seconds — and on a resume, after
replaying staged bytes through a digest, which for a partly-downloaded 280 MB tower is longer still;
a timeout would cut off healthy downloads on a slow link. And without the bounded half, a client
resubscribing after a proxy restart hangs forever behind a bar that never moves and never errors —
the region twin's R3 regression, inherited here deliberately rather than rediscovered.

⚠ The grace window therefore covers **only** the client-side ordering race, and resets whenever the
downloader says a fetch is genuinely running.

### 25.4 ⚠ Determinate only once bytes exist

The row shows a determinate bar **only** while the stream is reporting. A delete has no byte count,
and neither does the window before the first tick — a bar pinned at 0 there reads as a *stalled*
download rather than a starting one, so the spinner remains the honest shape until there is
something to draw.

### 25.5 What the tests pin

**145 proxy tests + 20 screen tests, 0 skipped.** Five new:

- **3 stream cases over the real wire** (client RO → streamId → proxy executor → pushed elements →
  the client's `Flow`): ticks pushed and the stream ending with the download; ⚠⚠ **ending** when
  nothing is in flight; ⚠⚠ **still waiting** past the grace when something is.
- **2 view-model cases**: pushed bytes reaching the UI state and being cleared afterwards, and a
  delete carrying no byte count.

⚠ Four mutants, all caught — including both halves of §25.3: removing the bounded branch hangs
`theStreamEndsWhenNoDownloadIsInFlight`, and removing the `isDownloading` reset breaks
`theStreamKeepsWaitingWhileADownloadIsActuallyInFlight`. ⚠⚠ Every mutation was compile-checked
first: §24.5 recorded a sweep where a non-compiling mutation read as a surviving guard.

### 25.6 What is still not true

- ⚠ **Still inert.** No release key and no published catalog: the screen says *"Can't reach the
  model list"* on every device, so this bar has never rendered against a real download. What is
  proven is the wire and the waiting rules; what is unproven is everything about a real ~383 MB
  fetch over a real network.
- ⚠ **The capability switches still do not live beside it** (IN-D10 wants them adjacent).
- ⚠ **`MediaIndexer` still has no schedule** — 1e, and now the only remaining code between this
  track and a working feature.

---

## 26. As built — step 1e: the indexer runs (2026-09-04)

**Scope:** `:inference-service` (a `HeadlessSafe` op), `:inference-service-proxy` (the pass, a media
seam, the schedule), `:file-service-proxy` (the image walk) and `:control-shared-proxy` (the
composition and the init hook).

⭐⭐ **`MediaIndexer`'s first production call site.** It was built in IN-3a, extended in IN-4b, and
for five slices was constructed **only in tests** — so a device with a model, consent and a library
still indexed nothing and `photosLike` answered `NOT_INDEXED` forever. §19.2 was explicit that this
step is *"not 'add a schedule'"*; it is calling the indexer for the first time.

### 26.1 A `HeadlessSafe` op, which is most of the design

`MediaIndexPass` carries `HeadlessSafe`, so it needs **no receiver, no params and no client RO** —
only the scheduler dispatches it by id (`ScheduleDrainLoop` in-process, or Android `WorkManager`
when Control is closed). `ScheduleService` refuses to schedule an op without the trait and the drain
loop re-checks before dispatch: fail-closed, the `OfflineKeeper` shape.

⚠ Registered in `-common` like any other op — an unregistered id fails at **registration**, loudly
at proxy init, rather than silently never running.

### 26.2 ⚠⚠ Idle **and** charging, not merely unmetered

A first index of a large library is a multi-hour, battery-visible run of a neural network over every
photograph the user owns. IN-D6: *"silently doing it is the kind of thing this product exists to not
do."* The constraints are what make the silence acceptable until the progress surface exists — it
runs plugged in and unused, never while someone is holding the device. ⚠ `BATTERY_NOT_LOW` too: a
device on a weak charger can still be discharging, and this is the workload that would win that
race. ⚠ **No** network constraint — the pass reads local files and runs a local model, so asking for
a connection would idle a device that is ready to work.

⚠⚠ **"No network constraint" has to be *declared*.** Leaving `network` out of `ConstraintSpec.create`
is not the same thing: the default is `NETWORK_ANY`, and the platform mapping
(`ConstraintSpec.networkRequirement()`, `ScheduledWorkPlan.kt`) folds `NETWORK_ANY` to "any live
connection" — WorkManager `NetworkType.CONNECTED`. Until 2026-10-02 the schedule left it out, so the
claim above was false on Android: the stored job required a connection (measured from
`androidx.work.workdb`, `media-processing-pipeline-sweep.md` F3.2). P0 of that sweep (D7) declares
`NETWORK_NONE`, which maps to `NOT_REQUIRED`; `ScheduleNetworkConstraintTest` (`:control-shared-proxy`)
pins the mapped value. The one networked step — the relay XLARGE fallback in `IndexPreview` — is
metered-gated on its own and counts `notOnDevice` when offline, so it needs no job-level constraint.

⚠ The 12-hour period is long on purpose. The pass is idempotent and nearly free when there is
nothing new (§26.4), so the tick only has to be frequent enough that a library catches up over a few
nights. ⚠⚠ The period is what follows a pass that **ran**. A pass the device's state declined (the
`deferred` of §32.9) throws `ScheduledRunDeferred` and is looked at again after
`MEDIA_INDEX_RETRY_MS` (15 min) — in-process by `ScheduleDrainLoop`, every time. ⚠⚠ On Android the
WorkManager half is `Result.retry()` under the item's linear `RetryPolicy`, and **15 min is only the
first wait**: consecutive deferrals wait 15, 30, 45 … min, capped at 5 h (media pipeline P4, §32.9).

### 26.3 ⚠⚠ IN-D6's "known scaling trap" has been overtaken, and the walk says so

IN-D6 warns that a naive walk materialises every stored value property *"including `File.Thumbnail`
blobs"* and dies on heap. **C7 closed that**: `Thumbnail`/`ThumbnailLarge` moved to
`dynamicProperty` on the content plane — they had been **97.26 % of every byte a `File` row cost** —
so a row is now ~336 B of metadata that arrives without them, and `PhotosCapturedBetween` already
reads whole rows on exactly that basis.

⭐ So the walk reads whole rows and mirrors the two precedents rather than inventing a thin one.
⚠ The warning is **dated, not wrong**: re-introducing a stored blob property on `File` makes it true
again, and this walk is among the first things that would die. That is recorded at the walk.

### 26.4 The seam, and why the walk is not in the inference service

`:inference-service-proxy` depends on no other service, and **no service-proxy in this repo does** —
composition across services is Control's proxy job, which is where the backends and the model
delivery components are already assembled. So the pass takes a `MediaSource` seam, the file-tree
walk lives in `:file-service-proxy` where that knowledge belongs, and the two meet in
`ProxyModule`. Taking a `:file-service` dependency would have been the first edge of its kind for a
shortcut worth nothing.

⚠⚠ **The byte read is lazy, and that is what makes a 12-hourly tick affordable.** `MediaIndexer`
checks the staleness stamp *before* calling `bytes()`, so a re-run over an unchanged library costs a
stamp read per photo rather than a file read. Over 10 000 photos that is the whole cost of the pass,
and there is a test that counts the reads rather than trusting the claim.

⚠⚠ The indexer is built with the **same `EmbeddingStore` singleton** `photosLike` reads. Two
instances give a search that finds nothing while indexing reports success, with no error on either
side.

### 26.5 What the tests pin

**5 pass cases + 4 walk cases, 0 skipped:**

- ⚠⚠ **A second pass over an unchanged library reads no bytes** — counted, not asserted from a
  number the code computed.
- Consent is the pass's own gate: with none, nothing is read and nothing is written (the scheduled
  tick fires regardless of what the user switched on). With no backend the pass is a counted no-op.
- ⚠⚠ Registering the schedule cannot escape as an uncaught throw — every call site is a bare
  `sessionScope.launch { … }` with no `CoroutineExceptionHandler`, which on Android kills Control.
- The walk: nested images found, ⚠⚠ **trashed images skipped** (a deletion that did not hold is a
  deletion that comes back as a search result), non-images skipped, an empty library empty.

⚠ Four mutants, all caught, each compile-checked first.

### 26.6 What is still not true

- ⚠⚠ **Still nothing to index with.** No release key ⇒ no catalog ⇒ no model on any device, so the
  scheduled pass runs, finds no backend, and reports a counted no-op. The pass is now *the thing
  that would work* the moment a model lands — which is the whole point of shipping it before the
  key exists rather than after.
- ⚠ **No visible progress and no pause** — IN-D6's last bullet, and the one item of that decision
  this slice does not deliver. A `println` per pass is the floor until then.
- ⚠ Nobody has run a pass over a real library on a real device, so the walk's cost at 10 000 photos
  is reasoned from C7's measurement rather than measured here.

---

## 27. As built — Phase 2 step 1: the leaf rule exists (2026-09-04)

**Scope:** `:inference-service-proxy` (a vocabulary type, the selection rule, and the indexer's
write path).

§19.3's first bullet: *"IN-D9's amended leaf rule — the per-leaf **absolute** floor, ahead of the
margin test."* ⚠⚠ **Until now none of IN-D9's tiered rule existed in code.** `MediaIndexer` wrote
*every* tag the backend returned — no tier, no floor, no margin, no hierarchy. §19.3's own sentence
was literally true of the implementation: *"a backend gets labels; it does not get good labels."*

### 27.1 The rule, in the order that matters

1. **Top tier — independent thresholds.** Not mutually exclusive: a photo can be both *beach* and
   *dog*, so each clears its own floor or is dropped.
2. **Leaves — floor, then margin, then parent.** The best sibling must clear its **own absolute
   floor first**, and only then beat its nearest sibling by the margin. Failing either, degrade.

⚠⚠ **The ordering is the amendment.** A purely relative rule cannot tell *"this is a bedroom, not a
living room"* from *"the model rejects all fourteen of these and this one is rejected least"* —
measured, the winner is routinely **negative** while the parent is positive (`indoors 0.061` over
`staircase −0.009`). Worse, a *clear* margin between two confidently-wrong siblings reads as
confidence, so the relative rule alone is most wrong exactly where it feels most certain. There is a
test with those measured numbers in it.

⚠⚠ **Degrade, never invent.** A leaf failing its floor is not evidence *for* its parent: the parent
is emitted only if it earned its own place in the top-tier pass. Promoting an unearned parent would
smuggle back the confidence the floor just refused.

### 27.2 ⚠⚠ An uncalibrated label cannot be expressed, so it cannot be emitted

Every threshold on `VocabularyLabel` is **required** — there is no default. §19.3's trap is a
consumer inventing a plausible constant, and `photos-app.md` §24.3 is the worked example: a floor of
0.500 sounds cautious and would have labelled **nothing at all**, because no (image, label) pair in
a real library reaches p = 0.5.

⭐ So `LabelVocabulary.Empty` — the production value until one is calibrated — emits **nothing**,
however confident the model is. That is deliberately not "write whatever the model says": labels
nobody calibrated are how a user learns the feature is unreliable. ⚠ It also means this slice
*reduces* what a hypothetical device would write, from "everything" to "nothing", and that is the
correct direction to be wrong in while the numbers are unknown.

⚠ A leaf with no parent, a leaf naming a missing parent, and a duplicated label all fail at
**construction** rather than per photograph.

### 27.3 What this does not do

- ⚖️ **The labels themselves.** ~150–300 leaves across a shallow hierarchy is a content decision,
  and IN-D9 ends on a per-label review — *where scene content shades into attribute inference* —
  that is explicitly a judgement call, not a rule. The structure is here; the list is not.
- ⚠⚠ **The numbers are still uncalibrated**, and this slice does not pretend otherwise: it makes
  their absence *unrepresentable* rather than filling them in. §7.7 still owes a labelled sample,
  and IN-D9's revision makes that bigger than it looks — *which branches earn leaves at all* is
  decided by that calibration.
- ⚠ **The "true but useless" check is not here** (§7.7's second amendment): `indoors` reached the
  top 3 for 55–60 % of a real library. That is invisible per image and only shows up as a
  **firing rate across the library**, so it needs a view this slice does not build.

### 27.4 What the tests pin

**14 rule cases, 0 skipped**, all pure — no model, no library, no labelled sample. Both top-tier
outcomes; a leaf winning on floor + margin; ⚠⚠ the measured `indoors 0.061` / `staircase −0.009`
case degrading despite a clear margin; a leaf failing only the margin; a lone sibling judged on its
floor alone; a failing leaf **not** promoting an unearned parent; a label outside the vocabulary
dropped; an empty vocabulary emitting nothing; and the three construction-time rejections.

⚠ Three mutants, all caught: removing the floor, removing the margin, and promoting the parent
unconditionally each fail a named test. ⚠ `MediaIndexerTest` needed a vocabulary to keep passing —
six of its cases went red on the empty default, which is the rule proving it is load-bearing rather
than a regression.

---

## 28. As built — Phase 2 step 2: the firing-rate view (2026-09-04)

**Scope:** `:inference-service-proxy` (a pure rate computation, the counts the pass now keeps, and
the line it prints).

§7.7's second amendment: *"a label can be true and useless"*. `indoors` reached the top 3 for
**55–60 %** of a real library — not wrong, since most photographs are indoors, but a label that
common carries no information and costs an index row on every photograph.

### 28.1 ⚠⚠ Why a view, and not a review

**Per image, every one of those results is correct.** Nothing about a single photograph tagged
`indoors` is suspicious, so no amount of sampling finds this — the defect exists only in the
aggregate. That is exactly why §7.7 obliges the slice shipping the vocabulary to *build the view*
rather than trust inspection, and it is the reason this is code at all.

### 28.2 The counts live where the knowledge is

The pass already visits every image it indexes, so it counts there: `consideredImages` and a
per-label `labelFiringCounts`. No new store read, no new op.

⚠⚠ **Counted per image, never per row.** "On how many photographs did this fire" is the question; a
label writing two rows for one photograph would otherwise read as twice as common as it is.

⚠⚠ **A skipped image is not considered.** An incremental pass over an already-indexed library
considers almost nothing, and a rate off a denominator of nothing would read as a library-wide
finding while being a sample of none. ⭐ The run where this *is* the whole-library view is the
**first** index of a library — which is precisely the run §3.7.4's own 55–60 % came from.

### 28.3 ⚠ A rate, deliberately not a verdict

§7.7 lists three responses to a label that fires on everything — drop it, raise its threshold, or
demote it to a filter never surfaced as a tag — and which applies is a **per-label judgement that
needs this data first**. Emitting a "too common" flag here would invent the policy ahead of the
evidence, the same error §19.3 warns about for thresholds. The view reports; the decision is ⚖️.

⚠ The report names its denominator, because the number is meaningless without it, and caps the list
so a 300-label vocabulary does not turn one pass into 300 lines of log.

### 28.4 ⚠⚠ A one-off-by-one the test caught

`(0.57 * 100).toInt()` renders 57 of 100 as **56 %** — `0.57` is `56.999…` in binary floating point
and `toInt()` truncates. That is a wrong number in the single figure this whole view exists to
report, and one nobody would question, because 56 % looks exactly as plausible as 57 %. It rounds
now, and a mutant restoring the truncation fails a named test.

### 28.5 What the tests pin

**9 cases, 0 skipped** — 7 on the pure computation (the measured `indoors` shape; ordering stable
enough to diff; zero considered images as a rate rather than a divide; the denominator named; the
singular; the cap; and nothing-fired said plainly) and 2 on the pass (per-image counting, and a
second pass claiming no rates).

⚠ Two mutants, both caught: truncating instead of rounding, and not counting considered images.

### 28.6 What Phase 2 still owes

- ⚖️ **The label list itself**, and IN-D9's per-label review of where scene content shades into
  attribute inference — a judgement call, not a rule.
- ⚠⚠ **The calibration numbers.** §7.7 still owes a labelled sample; the leaf rule (§27) makes
  their absence unrepresentable rather than filling them in. ⚠ §37 measured floors for 37 object
  labels on COCO; they are an instrument for comparing artifacts, not numbers to ship.
- ⚠ **Nobody has run this against a real library**, because no device can install a model. The view
  is built and untested against the thing it was built to measure — which is the same sentence the
  rest of this track has been writing since IN-3b, and it stays true until a release key exists.

---

## 29. As built — 1e-2: the pass is pausable, and it says where it is (2026-09-04)

**Scope:** `:inference-service` (a device preference), `:inference-service-proxy` (the progress
holder, the pause read, the pass honouring both) and `:control-shared-proxy` (one binding).

IN-D6's last bullet: *"Progress must be visible and pausable. First-index of a large library is a
multi-hour, battery-visible operation and silently doing it is the kind of thing this product exists
to not do."* §26.6 flagged it as the one item 1e did not deliver.

⚠ **This is the mechanism, not the surface.** Nothing renders the bar yet — the stream op and the
Control UI are 1e-3. What exists now is a pass that *can* be watched and *can* be stopped.

### 29.1 Pause is a device preference, not a flag

⚠⚠ **Device-scoped**, the same argument `deviceConsent` makes: a phone about to travel and a
plugged-in desktop are legitimately different answers, and pausing a laptop must not stop a hub.

⚠ **A preference rather than an in-memory flag**, because a pause has to survive the proxy dying —
which on Android it does routinely. A pause that forgets itself on the next launch is one the user
has to keep re-asserting, and they will conclude it does not work.

⚠⚠ **Deliberately not folded into consent.** Withdrawing consent means *"do not index my library"*
and IN-D5 implies the derived rows should eventually go; pausing means *"not right now"* and keeps
everything. Reusing `deviceConsent` as a pause button would make "my phone is hot" and "I changed my
mind about this feature" the same act — and make un-pausing look like fresh consent.

### 29.2 ⚠⚠ A pause **stops** the pass — and that claim needed making testable

Read per image, like consent, so a pause flipped mid-pass takes effect on the next one rather than
hours later. But it `break`s where consent `continue`s: a user whose phone is hot does not want the
remaining nine thousand images each considered and rejected.

⚠⚠ **Stopping and skipping write exactly the same rows**, so a mutant swapping `break` for
`continue` survived every assertion in the file. The fix was not a better assertion but a better
*result*: `IndexPassResult.imagesVisited` records how far the pass got, which makes the difference
observable — and is what a UI needs to say *"stopped at 300 of 9 000"* rather than just "stopped".
⭐ The mutant caught a comment claiming behaviour nothing verified.

### 29.3 The bar counts skips too, and always clears

⚠ Ticked per image **including** the ones skipped as already current: *"how far through my library
are you"* is the question, and a bar that only moved on newly-indexed photographs would sit still
for an entire incremental pass and read as stuck.

⚠⚠ `finish()` runs on **every** exit — finished, refused or paused. A bar that is not cleared says
"indexing" forever, which is worse than never showing one: the user cannot tell a stuck pass from a
finished one, and there is nothing they can do about either.

### 29.4 ⚠⚠ A conflation trap, avoided this time

The obvious test collects `progress` and asserts on what it saw. That is wrong here for the same
reason §25 records: `progress` is a conflating `StateFlow` and the pass runs to completion without
suspending, so a collector is scheduled only after `finish()` has set it back to null — it sees
nothing, and the test asserts on a race.

⭐ So the progress cases read `holder.progress.value` **synchronously from inside the pass**, at a
known point in the loop. A fact, not a timing.

### 29.5 What the tests pin

**6 new cases, 0 skipped**: a paused device stops rather than walking the library (with
`imagesVisited` proving it); a pause flipped mid-pass stopping the next image; the bar advancing
over skipped images; its total being the whole library handed to the pass; and the bar cleared on
both the finished and the paused exit.

⚠ Three mutants: `continue` for `break`, and removing `finish()` — both caught. ⚠⚠ The first was
caught **only after** `imagesVisited` existed; the honest record is that it survived the first sweep.

### 29.6 What is still not true

- ⚠ **Nothing renders any of this.** The holder has one reader — nobody. The stream op and the
  Control UI are 1e-3, and until then IN-D6's *visible* is half-kept: the mechanism exists, the
  surface does not.
- ⚠ **No way to pause from a UI either.** The preference is device-scoped and writable through the
  preference service, so the button is a UI question, not a plumbing one.
- ⚠⚠ **Still nothing to index.** No release key ⇒ no model ⇒ every pass is a counted no-op, so the
  bar has never moved on a real device.

## 30. As built — 1e-3: the bar reaches a screen, and the pause has a switch (2026-09-05)

§29 built the mechanism and rendered none of it. This slice is the surface: a `MediaIndexProgress`
wire composite, an `IndexProgress` **stream** op mirroring `DownloadProgress`, its `-common` /
`-client` registration and proxy implementation, and Control's Models screen growing a progress
line and a pause switch.

Schema baseline: **2 additions, no existing line modified**. Nothing reshaped, so HR #9 owes no
version bump — and `mediaIndexProgress` has no `dataIdProvider` sibling, because it is a wire
element and never a stored row.

### 30.1 ⚠⚠ No `awaitRegistration`, and the asymmetry is the point

`ModelDownloadProgressImplementation` waits for its holder entry to appear, because a download
registers one *after* a catalog fetch: an absent entry there means either "late" or "never", and
`isDownloading` is what tells them apart.

Indexing has no such ambiguity. `MediaIndexer` calls `start()` as its first act and is the holder's
only writer, so an absent entry means exactly **"no pass is running"** — a fact, answered with one
tick, not an ambiguity to wait out. Copying the twin's wait here would have reproduced the region
twin's R3 regression: a client hanging behind a bar that never moves and never errors.

### 30.2 The idle tick is explicit, and it goes first

The stream's last word is an `indexing = false` element, sent *before* the terminal `End`. A UI that
watched a pass to its end is **told** it ended rather than inferring it from a closed flow — which
matters because "finished" and "the proxy went away" are otherwise the same observation.

### 30.3 ⚠⚠ The leak guard had two tests named for it, and neither could fail

The collector stops via `transformWhile { emit(snapshot); snapshot != null }`. A mutant replacing
that predicate with `true` — making the collector run forever — **passed every case in the file**,
including `anIdleDeviceIsToldSoAndTheStreamEnds` and
`ticksArePushedOverTheWireAndTheStreamEndsWithThePass`, both named for the property it broke.

⭐ The reason generalises well beyond this op: **`endElement` is sent from the `collect` body, so
the client terminates correctly whether or not the collector ever stops.** Everything a client-side
assertion can see is unchanged by the mutation. What breaks is invisible from the client — the
proxy's collector lives for the session, and every later pass pushes ticks at a streamId nobody is
listening to, which is the entire reason the predicate exists.

`subscriptionCount` is declared on `MutableSharedFlow` and so is unreachable through the read-only
`progress` view. `IndexProgressHolder` therefore exposes an `internal subscriberCount` for exactly
this test: a production line added to make a claim **checkable** rather than merely written down.

⚠ The new case waits for the count to fall rather than reading it once — the client's `End` can
arrive before the proxy's collector has unwound, so a bare assertion would be a race, not a fact.

### 30.4 ⚠ A pass that starts after you subscribe is not reported

Stated rather than hidden. Staying open for it would leak in the same way §30.3 describes —
`EndStream` tears down `StreamRegistry` listeners, not an implementation's own launched job — and
it would be waiting for something that cannot arrive while anyone is watching: the pass runs only
when the device is **idle** and charging, and a device with this screen open is not idle. A
cross-process subscriber that must see later passes asks again.

⚠⚠ **Control's Models screen no longer uses this op** (2026-10-10). It re-subscribed on Refresh, and
that was a button the user had to know to press: *Index now* starts a pass while the screen is up,
so the premise above does not hold for it. Control's UI shares the proxy's process, so
`ClientModelsRepository.indexProgress()` now reads the `IndexProgressHolder` singleton directly
through `IndexProgressSource` (`:control-shared-logic`, adapted and bound in `:control-shared-proxy`
beside the holder). That flow **never completes** and reports every pass on one subscription; the
leak above cannot occur because the collector is the screen's own, cancelled with its view model. The
op and its bound are unchanged for every other client. ⚠ The source is a **required** constructor
parameter, resolved with `get` — an unbound one fails at resolution rather than quietly bringing the
Refresh-only bar back. The consents' device half was the screen's other Refresh dependency: the
read now follows a device id that appears after subscribing (`deviceIdOnceKnown`, a capped backoff
over the one-shot `getDeviceId`), so Refresh re-fetches the model list and nothing else.

### 30.5 What the tests pin

**10 new cases, 0 skipped** — 4 on the stream, 6 in Control (joining suites that now total 26):
ticks crossing the wire and the stream ending
with the pass; an idle device answered in one tick rather than left waiting; the proxy-side
collector stopping (§30.3); an empty-library pass reported as *running*, not idle — `start(0)` is
what the indexer does when the walk finds nothing, so `total == 0` cannot mean "nothing is running".

⚠ Three mutants, all caught after the §30.3 fix: the non-terminating collector, a suppressed
terminal `End`, and dropping the ViewModel's optimistic pause update.

⚠ The optimistic update is deliberate: a device that has never paused has **no preference row**, so
the first echo may never come and a switch waiting for it would not move on the very first tap.

### 30.6 What is still not true

- ⚠⚠ **Still nothing to index.** No release key ⇒ no model ⇒ every pass is a counted no-op, so this
  UI has never shown a moving bar on real data. The bar is verified over the wire, not over work.
- ⚠ **Only Control renders it.** The stream op is service-level and any app could subscribe; none
  does.
- ⚠ **No history.** The holder is in-memory and device-local, so "when did this last finish" is
  still unanswerable — deliberately, since that is synced state and IN-D4/D5 keeps it off the realm.

## 31. As built — the publishing pipeline: a catalog can now be signed (2026-09-06)

Every "still not true" note on this track since IN-3b has ended in the same clause: *no release key
⇒ no model ⇒ every pass is a counted no-op*. §11 named the three missing things — **no URL, no
signing key, no model**. None of them was a coding problem, which is why none of them moved.

This slice builds the half that *is* code, so the remaining work is an ops task rather than a
project: `:model-signer` (a JVM build tool), `scripts/models-publish.sh`, and
[`how-to/models-pipeline.md`](../how-to/models-pipeline.md).

### 31.1 The manifest carries identity; the bytes carry integrity

An operator writes `id`, `name`, `version`, `capabilities`, `runtime`, `licence` and the artifact
**paths**. The signer computes `sha256` and `byteSize` by streaming each file.

⚠⚠ **A hand-written digest is the failure worth designing out.** It goes stale silently, and a
stale digest is indistinguishable at the client from a CDN serving the wrong bytes — the integrity
gate refuses the download either way, with nothing to say which happened.

### 31.2 ⚠⚠ A typo'd capability is now a build failure, and it was not

Found by the round-trip test, on its first draft: `"photoLabels"` parsed, verified, and then
**vanished**. The client `mapNotNull`s capability names it cannot resolve — deliberate forward
compatibility (§IN-D10) — and `usableModels` drops an entry left with none. So a single misspelling
publishes a model **every device silently ignores**, with nothing in any log to say why.

⭐ The builder now validates against `InferenceCapability.entries` and fails naming the offender.
Forward compatibility is not lost: the check runs at *build* time from the same tree as the client,
where an unknown name cannot be a newer capability — only a mistake.

### 31.3 One signature stack, two keys

`ModelSigner` uses `:core-common`'s `BouncyCastleEd25519Backend` — the primitive
`ModelCatalogVerifier` calls — so the model catalog inherits D3 rather than growing a second stack.
⚠ It is deliberately the *twin* of `:tiles-signer`'s `ManifestSigner` rather than a shared helper:
the two catalogs are signed by **different keys**, and one helper taking "a key" invites signing one
with the other's.

⚠ `build-and-sign` prints the public key it signed with on **every** run, not just on `generate-key`.
A catalog signed with the wrong key is silently unverifiable at the client; comparing that line
against `MODEL_CATALOG_PUBLIC_KEY_HEX` is the only local check that catches it.

### 31.4 Publish order is load-bearing

`models-publish.sh` uploads **artifacts first, catalog last**. The catalog is the pointer:
publishing it before the bytes it names opens a window in which every install 404s. The reverse
costs only storage. It also refuses to publish a catalog naming a file it cannot find locally —
the client's integrity gate would refuse that download anyway, but only after a user spent the
bytes.

### 31.5 What the tests pin

**4 cases** in `SignedCatalogRoundTripTest` (`:inference-service-proxy` `jvmTest`): the tool's real
output through the real `ModelCatalogVerifier` — signature, parse and the IN-D13 runtime filter in
one `verifyAndParse` — plus digests and sizes checked against the bytes on disk, an edited catalog
refused, a missing artifact file refused, and the typo'd capability of §31.2.

⚠ It lives in `:inference-service-proxy`, not `:model-signer`, because `parseModelCatalog` is
`internal`: a test on the tool's side could only assert against a hand-copied JSON, which is a
second copy of the contract rather than a check on it.

### 31.6 What is still not true

- ⚠⚠ **No real download has happened.** §11's list is unchanged in substance: there is still no key,
  no published catalog and no checkpoint, because those are artifacts only the maintainer can make.
  What changed is that producing them is now a documented 20-minute task instead of unstarted work.
- ⚠⚠ **The first real publish is also the first real test** of resumption over a real network, a
  genuine ~380 MB artifact, and the local-cache ceiling interacting with it
  (`retention-quota-model.md` P2). None of that is exercised by a 200 KB fixture.
- ⚠ The end-to-end flow *was* rehearsed — `generate-key` → `build-and-sign` → `DRY_RUN=1` publish,
  against a throwaway key and fake artifacts — so the documented commands are known to be
  copy-pasteable. That is a different claim from "a device installed a model".
- ⚠⚠ **No app could grant consent** (found 2026-09-17). `hasInferenceConsent` needs both of IN-D3's
  switches and absence means no (§9.2), but nothing outside `:inference-service*` wrote
  `realmConsent` or `deviceConsent` — the only record was a comment in `AskMiaImplementation`. So a
  published catalog and a downloaded model would still have indexed nothing: every photo counted
  under `noConsent`. ✅ **Closed by §32**, which found three more reasons on the way.

---

## 32. As built — the consent switches, and three reasons no device could have indexed (2026-09-17)

**Scope:** `:inference-service-proxy` (the indexer, the vector store, `photosLike`'s refusal order),
`:control-shared` (the switches on Settings → Models), the Photos and Mia refusal copy, and
`deploy/models/models.json`.

⚠⚠ **Started as the consent screen §31.6 was missing. Tracing what a switch would actually unlock
showed the index pass could not have worked on any device even with consent**, for three separate
reasons — each invisible against `FakeInferenceBackend`, which supports every capability.

### 32.1 ⚠⚠ The pass asked for a tagging backend, and production registers a search backend

`MediaIndexPass` indexes under `CONTENT_TAGGING` and `MediaIndexer` began with
`registry.backendFor(capability) ?: return skippedNoBackend`. The only backend a device registers is
`OnnxInferenceBackend`, whose `supports()` is **`SEMANTIC_IMAGE_SEARCH` only** (§20). So every
scheduled pass on every real device returned *no backend* for the whole library, and `photosLike`
answered `NOT_INDEXED` forever.

⭐ The fix is structural: **each half resolves its own backend** — a tagger for labels, an embedder
for vectors — and the pass is a no-op only when neither exists. ⚠ The regression test registers a
**search-only** fake, the shape of the one backend production has; the default fake is exactly the
fixture that hid this.

### 32.2 ⚠⚠ Search consent alone indexed nothing

The per-file gate was the *tagging* consent, and the vector was written inside it. A user who agreed
to search and not to labels — the only combination §32.5 offers — got no index. §16.4's *"one visit,
two consents"* held for the write and not for the gate. Now a file is skipped for consent only when
**neither** half may run, and each half runs under its own switch.

### 32.3 ⚠⚠ Vectors were judged fresh by a marker that syncs

A photo was skipped when the pass's `MediaLabel` marker said it was current. That marker is a
`Syncable` row; the vector is device-local (IN-D5). So:

- a **second device** received the first one's markers and none of its vectors, and never embedded;
- **this device after a reinstall** got its markers back by sync and its vectors not;
- a user who **enabled search after labels** had every tagged photo skipped.

⭐ Vector freshness is now the store's own answer — `EmbeddingStore.visited(path)` — and the marker
governs labels only, which is what it was for. Two consequences had to be handled once the marker
stopped standing in for "looked at":

- ⚠⚠ **A file this device cannot decode** is recorded device-locally (`markVisited`, a new `VISITED`
  record in the embeddings log), or every idle-and-charging pass would re-read it — a whole library
  in a format a platform cannot decode, forever. ⚠ Only once the store has a stamp: with none there
  is no model the visit belongs to.
- ⚠⚠ **Which "no vector" may be remembered is the backend's to say, not the indexer's to guess.**
  `embedImage`'s `null` meant both *"not an image"* and *"the model could not run"*, and ONNX returns
  it for a session that will not open or a run that throws **while `supports()` stays true** — so a
  first draft that re-checked the claim would have remembered every photo that met a bad moment, out
  of the index for good (Codex, P1). `embedImageOutcome` now answers `Embedded` / `Unreadable` /
  `Failed`; only `Unreadable` is remembered, and the interface's default maps every `null` to
  `Failed`, so a backend that cannot tell costs re-reads rather than photos. A run of `EMBED_FAILURE_STREAK_LIMIT` failures stands the
  vector half down for the rest of the pass (the streak bullet below); one failure does not.
- ⚠⚠ **A vector that cannot be an embedding is `Failed` too** (2026-10-06): non-finite, all zeros or
  another width, refused by `TowerGuards.unusableReason` in both backends (ONNX and Core ML) before
  it is normalised. Never `Unreadable` — nothing shows it is a fact about the file — and never a
  backend latch: `supports()` stays true and the next input is embedded. `embedText` answers `null`
  for the same vector, and for a vocabulary with no `</s>`, which `photosLike` renders as
  `NO_TEXT_EMBEDDING`. It counts toward the failure streak below as any `Failed` does: one refused
  photo no longer ends the pass's vector half.
- ⚠ **A sink that refuses** (another model's stamp, another width) refuses every vector, so the pass
  latches after the first refusal instead of re-embedding the library to be refused once per photo.
  Every photo it could not index is still counted in `embeddingsRejected`.
- ⚠⚠ **One `Failed` does not end the pass's vector half; `EMBED_FAILURE_STREAK_LIMIT` (3) in a row
  do** (2026-10-06, `MediaIndexer`, both backends). A
  `Failed` is the model's (will not open, throws) or this photo's (a vector the output guard
  refused), and one cannot say which. The first
  version latched on one — for ONNX a failed open is a 100 MB session attempt per image — so a
  single photo the model failed on every time ended every pass at itself and nothing walked after
  it was ever embedded.
  - **The rule.** Each `Failed` adds one to an in-memory, per-pass streak; an `Embedded` resets it
    (before the sink is asked); `Unreadable` leaves it alone. At the limit the pass stops asking
    for vectors. The failed photo is still not stored and not visited.
  - **Why 3.** The cost of a higher limit is that many wasted asks on a broken model per pass (a
    photo's bytes read, and on ONNX a failed session open); the scheduled pass runs every 12 h. The
    cost of a lower one is fewer bad photos tolerated: limit − 1.
  - **The report.** `embeddingsFailed` is photos asked and failed, never a verdict;
    `embeddingsNotAttempted` is photos not asked after the stand-down; `embeddingStoodDown` is the
    verdict, and its own field because a streak that completes at the end of the walk leaves
    nothing un-asked. Summary line: `embedFailed=… embedNotAttempted=… embedStopped=modelFailing|no`.
    ⚠ The face half still adds to `embeddingsFailed`, including what it skipped after its latch.
  - ⚠⚠ **Consecutive ASKS, not consecutive photos — the limit of this rule.** A photo that has a
    vector is skipped unasked and resets nothing. On an indexed library the only photos asked are
    the ones that failed before and the new ones, so **three photos that fail every time, with no
    un-embedded good photo walked between them, stand the half down exactly as a broken model
    does**, and new photos walked after the third are not embedded. Indistinguishable from a
    broken model without stored state. The maintainer accepted the first-pass form of it (a
    library whose first three photos are bad, 2026-10-06); the steady-state form is the same
    mechanism and was not separately decided.
  - ⚠ **The walk is deterministic** (`walkImageLibrary` reads the tree in stored order), so a photo
    that fails every time is asked again at the same place on every pass: one wasted embed per
    pass, for good. Accepted.
  - **Open, not built: remembering a repeat offender**, as `Unreadable` is remembered. It needs a
    per-photo failure count that survives the pass — a new record kind in the embeddings log (as
    `VISITED` was: additive, device-local, a downgraded build stops replaying at it), keyed to the
    model stamp so a new model asks again, plus a rule for when a count is trusted (a broken model
    fails every photo N passes running and must not mark the library). Device-local, so no synced
    row and no `SchemaMigration`; if it were made a synced row instead, House Rule 9 applies. It
    would also close the three-bad-photos case above.
  - ⚠ **The face half is unchanged** and still latches on one failure (`detectorFailing`), so it
    has the same one-photo shape.
  - **Pinned** (`MediaIndexerTest`, each rule removed once and seen red): one failing photo in the
    middle; a broken model asked exactly the limit; failures with a vector between them; a streak
    that ends the walk; no carry into the next pass; an undecodable file neither resets nor advances.

⚠ The `VISITED` record is additive — an old log never contains it — and keeps the `TSHUEMB1` magic. A
**downgraded** build stops replaying at the first one as it would at a torn tail; what it loses is
re-derivable vectors, never content.

### 32.4 The switches — two per capability, and what each direction writes

> ⚠ **The screen described here was replaced on 2026-10-11 (§38).** What each direction *writes*,
> the order of the pair, the serialised writes and the re-read are all still the code; the two
> switch rows per capability, and the copy quoted for them, are gone.

Control → Settings → **Models** now opens with IN-D3's switches, beside the models as IN-D10 asks.

- **The realm switch** (*Search photos by description*) writes the synced answer. ⚠⚠ **On writes
  this device's answer too** — the person holding this device is asking for the feature, and a realm
  "yes" alone is a switch reading on while the proxy, which needs both, skips every photo. **Off
  clears this device's too**, so a later "on" from another device cannot quietly resume work here.
- **On this device** appears once the realm answer is yes; it is the state a second device opens in
  (*"On for your devices, but not this one"*).
- ⚠⚠ **A device answer that did not save is shown as the answer that is stored, in both directions.**
  Switching off, a realm write that lands with a device clear that does not leaves this device's yes
  behind — and a later "on" from another device would silently resume the work here (Codex, P1). The
  switch keeps that yes and says *"Couldn't save that on this device yet"*.
- ⚠⚠ **The order of the pair is the safety property** (Codex, P1). On writes the realm first; off
  clears this device first. A pair interrupted between its writes — view model cleared, process
  killed — therefore always leaves this device *not* consented, never a stored yes under a realm no.
- ⚠⚠ **Writes are serialised, in press order** (Codex, P1). Each press launched its own write, so on
  then off faster than a write lands could store the older `true` last; a fair `Mutex` queues them,
  and only the newest press repaints the switch.
- ⚠⚠ **Every write ends in a re-read of what is stored** (Codex, two P2s). The rollback value is the
  screen at press time, which after queued presses is an earlier press's *optimistic* state — so
  failing writes could end on on/on over an off/off store. And the read binds to this device's id
  when it subscribes, so a screen opened before the id existed would never see the row it wrote.
- ⚠⚠ **A yes this device still holds under a realm no stays visible** (Codex, P1). It is an ordinary
  state — another device switching the realm off clears only its own answer, and a failed clear here
  leaves one too — and a hidden one resumes the work the moment any device turns the realm back on.
  The row says *"Still allowed here. If it's turned back on from another device, this one starts
  indexing again."* ⚠ Declined: not clearing the realm when the device clear fails. That would keep
  every device indexing to protect one against a later "on", and it cannot prevent the state anyway.
- ⚠⚠ **A device answer is never written under a placeholder id.** `ModelsRepository` falls back to
  `"local-device"` for the pause, where the worst case is a pause that does not stick; for consent a
  row under the wrong id is a switch that visibly moves and never takes effect. The switch goes back
  and says *"Couldn't save that on this device yet"*.
- ⚠ The indexing line now says *"Nothing is switched on for this device, so nothing is indexed"*
  rather than *"Runs when idle and charging"* over a switch that is off.

⚠⚠ **Only capabilities a switch changes something for are offered** (`OfferedConsents`) — today
search alone. `CONTENT_TAGGING` waits for Phase 2 (no shipped backend tags, and the vocabulary is
empty); `QUERY_PLANNING` has no backend implementing `classifyIntent`; OCR and speech have no backend;
`FACE_CLUSTERING` is IN-6 and unbuilt. A switch that does nothing is a claim the product cannot keep.

### 32.5 `photosLike` reports the missing model before the missing consent

The refusal copy in Photos and Mia now **names the switch** — *Turn on "Search photos by description"
in Control, under Settings → Models* — closing the *"revisit when the capability UI ships"* notes in
both. ⚠⚠ That made the check order matter: consent was checked first, so a device that can never run
a model (iOS, web) would be sent to a switch that changes nothing. `NO_BACKEND` now comes first.
`backendFor` reads no query and no photo, so nothing consent governs happens before it.

### 32.6 The manifest named two of the three artifacts

`deploy/models/models.json` listed `vision` and `vocabulary` only. Published as written, a device
would install, index, and answer every search `NO_TEXT_EMBEDDING` (§21.3). It now lists `vision`,
`text` and `vocabulary`, under a versioned path (artifacts are served `immutable`), and claims
`semanticImageSearch` alone for the §32.4 reason.

### 32.7 What the tests pin

- **Indexer** (`MediaIndexerTest`): a search-only backend builds the index; search consent alone
  builds it and writes no labels or marker; a second device embeds photos another device tagged; a
  second pass with both halves current reads **no bytes**; a refusing sink is learned from one image and a failing model from a streak of three, and both
  still account for every photo; an undecodable file is not re-read; a model
  failure is not remembered against the photo.
- **ONNX** (`OnnxInferenceBackendTest`): no model and an unopenable artifact are `Failed`; with the
  real `vision_model_uint8.onnx`, non-image bytes are `Unreadable`. Over a fake tower: a NaN, an
  infinity, all zeros and another width are refused for image and text, and the next input is served.
- **Store**: a visit survives a reopen and is not an entry; a visit on an unstamped store is refused; a
  removal clears a visit.
- **Switches** (`ModelsViewModelTest`, 12 cases): offered off before anything is read; on writes both
  answers, realm first; off clears both, device first; another device's realm answer shows with this
  device off; an unsaved device answer goes back and says so; a realm yes whose device half fails keeps
  the realm; a failed device clear when switching off keeps the stored yes; a failed realm write puts
  both back; rapid presses land in press order (a gated fake — writes that land instantly can never
  race); queued presses whose writes fail end on what is stored; a landed device answer re-reads. Plus
  the supporting lines, the indexing line, the `photosLike` refusal order, and the Photos and Mia
  sentences.
- ⭐ **Every guard mutation-checked** as it landed — the backend per half, store-local freshness, both
  latches, `markVisited`, the either-consent gate, the outcome split, the write order, the `Mutex`, the
  re-read: each mutant compiled and was killed by the test named for it.

### 32.8 What is still not true

- ~~**Still nothing published.**~~ **Superseded 2026-09-17** (§34's opening): the R2 bucket at
  `models.service.tenshu.app` was applied and the first model published. ⚖️ R9's corpus disclosure is
  still with counsel.
- ⚠⚠ **A model change leaves search refusing.** Nothing in production calls `EmbeddingStore.rebase`,
  so after a checkpoint version bump the store keeps the old stamp, every new vector is refused, and
  `photosLike` answers `NOT_INDEXED` (*re-index required*). Harmless while one version exists; it
  must land before a second is published.
- ~~**Nobody has run a pass on a device.**~~ **Superseded 2026-09-17**: a phone downloaded the
  published model and wrote 14 vectors overnight (width 768, correct stamp). ⚠ What that did *not*
  test is library scale — the walk, the decode rate and the store were exercised over a handful of
  images, not a real library.
- ⚠ **Consent does not delete.** Withdrawing search consent stops the pass and the query; the vectors
  already derived stay in the device-local log. IN-D5 implies they should go — unbuilt.
- ⚠⚠ **A photo replaced in place keeps its old vector** (Codex, P2, declined for this slice). Freshness
  is keyed by path, and `ReplaceFile` keeps the path while changing the bytes; nothing in production
  calls `EmbeddingStore.remove`, so search scores the replacement with the old photograph's vector,
  and a path once recorded as unreadable stays skipped. ⚠ Not new: the tagging marker it replaced
  was path-keyed too. The fix is content identity (`File.ContentHash`) in the visit record or an
  overwrite observer that invalidates — its own slice, before the index is trusted on a library
  that edits photos in place.

### 32.9 ⚠⚠ The scheduler never enforced idle-and-charging — the pass does now

Codex, P1, on the fourth pass: *once a model and consent exist, starting Control launches the
multi-hour pass while the device is active or on battery.* **True, and older than this slice.**
`registerMediaIndexSchedule` declares charging + device-idle + battery-not-low (§26.2), but
`ScheduleDrainLoop` stores a `ConstraintSpec` **without enforcing it** — its KDoc says so — and fires a
periodic item on its first tick. Only Android's WorkManager honours the constraints, and only on the
wakes it drives. While the pass was a no-op on every device that cost nothing; §32.1 made it real.

⭐ **The pass asks for itself**, through `IndexingConditions`: before the bar starts, and again per
image, where it stops like a pause (`IndexPassResult.deferred`). `indexingConditionsAllow` is the shared
policy, and ⚠⚠ **every unknown is a no**.

| | on external power | battery not low | not in use |
|---|---|---|---|
| **Android** | `BatteryManager.isCharging` | capacity > 15 % (WorkManager's threshold) | screen off (`PowerManager.isInteractive`) |
| **macOS** | `IOPSGetTimeRemainingEstimate() == -2` | `IOPSGetBatteryWarningLevel() == none` | no HID input for 5 min |
| **Windows, Linux, iOS** | — | — | — ⇒ **never indexes** |

- ⚠⚠ **Absent means never.** `MediaIndexer` defaults to `IndexingConditions.Never`, and Control binds a
  platform's conditions under `named("indexingConditions")` the way it binds runtimes. A wiring that
  forgets indexes nothing rather than everything, on battery.
- ⚠ **Not the Synced Folders stance.** `DesktopBackupEnvironment` treats a desktop as always wired,
  which is fine for an upload measured in seconds and wrong for hours of every core on a laptop in use.
- ⚠ **Android's "not in use" is screen-off**, weaker than WorkManager's doze-window idle, on purpose:
  the property is that nobody is holding the device, and waiting for doze would idle an awake proxy
  through the night.
- ⭐ The macOS probe (`MacPowerProbe`, `:macos-helpers`) is three scalar C calls over FFM — no
  CoreFoundation objects, no structs — and `MacPowerProbeTest` pins that each resolves to a sane value
  on a real Mac (the idle timer inside a plausible range, because a mis-declared return reads garbage
  rather than throwing).

⚠⚠ **What this did not fix: the tick — media pipeline P4 did** (`media-processing-pipeline-sweep.md`
F3.1/F8). Before P4 the in-process loop fired the pass at startup, the pass declined (screen on), and
the loop reset the whole period, so the next look was **12 h of process uptime** later; on desktop,
where nothing else wakes it, indexing happened only when such a tick landed on an idle, plugged-in
moment. As built:

- ⭐ **A declined pass asks to be retried.** When `IndexPassResult.deferred` is set (and the pass was
  not paused), `MediaIndexPassImplementation` throws `ScheduledRunDeferred(MEDIA_INDEX_RETRY_MS)`
  after printing its report. `ScheduleDrainLoop` fires that item again **15 min** later instead of
  after the period, leaving its row untouched (`PENDING`, `attempts` unchanged); the next run that
  completes puts the 12 h period back. Android's `TenshuOperationWorker` maps the same throw to
  `Result.retry()`, and the schedule's `RetryPolicy` (LINEAR, 15 min) becomes the WorkManager backoff.
- ⚠⚠ **On Android the WorkManager retry is not a fixed 15 min.** `Result.retry()` carries no delay;
  WorkManager computes it as *backoff × attempts so far* for a linear policy
  (`WorkSpec.calculateNextRunTime`, work-runtime 2.11.2), capped at 5 h. A pass that keeps being
  declined is therefore looked at after **15, 30, 45, 60 … min** — 5 h from the 20th in a row —
  each still subject to the idle + charging constraints, and back to 15 once a pass completes.
  Still far inside the 12 h period, and the in-process loop keeps its fixed 15 min whenever Control
  is alive. A fixed interval needs the worker to re-enqueue itself; not done, by decision.
- ⚠ **A deferral does not spend the worker's failure retries.** WorkManager has one attempt counter
  and it counts deferrals too, so `runAttemptCount < 3` alone refused a real error its retries after
  three deferrals. `ScheduledRetryAccounting` counts the deferrals apart (a small device-local
  preferences file, keyed by work id) and subtracts them. ⚠ Not verified on a device.
- ⚠⚠ **Only a state that passes is a deferral.** Paused, no consent and no backend return normally —
  a retry cannot change them, and deferring them would wake the device every 15 min for nothing.
- ⚠ The signal is a **Kotlin-only exception in `:sync-service`**, not a `TError` and not a declared
  return type, so no schema moved (no HR 9 bump) and any scheduled op can use it.
- ⭐ **Items no longer block each other.** The loop starts each due item in its own coroutine with
  per-key single flight, so a multi-hour pass no longer stops `drainSync` or the offline keeper
  ticking in-process; the period still counts from the end of a run.
- ⚠ **A run's completion writes only the row it started from.** Re-registering or cancelling a
  schedule while its run is in flight used to be undone by that run's completion; the guard is
  `ScheduleRunState` (`core-background-processing.md`, Phase 5 amendment).

⚠ The drain loop's unenforced constraints also cover the slice-snapshot and content-backfill
schedules (`sync-service-proxy`), which this does not touch.

**Pinned:** a device that is not ready reads nothing and never starts the bar; unplugging mid-pass stops
at the next image; an indexer wired without conditions never indexes; the policy reads each unknown as
no; the desktop conditions over a scripted probe; the real probe on this Mac. Each guard
mutation-checked.

### 32.10 The vector store is shared, and now locked

Codex, P2: `photosLike` iterated `entries()` — a live sequence over the store's `LinkedHashMap` — while
the index pass, now real, writes to the same singleton from another coroutine: a
`ConcurrentModificationException` on the JVM and a data race elsewhere. **Every `EmbeddingStore` member
now suspends behind a `Mutex`** (common code has no blocking lock), and `entries()` returns a
**snapshot** — references, not vectors, since a stored vector is only ever replaced, so 20 000 photos
cost 20 000 pairs rather than ~40 MB. Pinned: a scan's snapshot is not reached by a later write.

## 33. Hosting — the catalog moves to R2 on our own name (2026-09-17)

**Decided by the maintainer:** the model catalog is served from an **R2 bucket behind
`models.service.tenshu.app`** (`deploy/cloudflare-models`), the sibling of the tiles bucket. The
Scaleway module that lived in `deploy/models` was **never applied** and is removed; the
directory keeps the manifest and the local build.

⚠⚠ **The deciding property is the name, not the provider.** `modelCatalogUrl` is compiled into Control.
The Scaleway module's URL, `https://s3.fr-par.scw.cloud/tenshu-models`, named a provider *and a region*,
so any later move would have been an app release on every device — the cost IN-D10 accepted for the
pinned key, and one there was no reason to pay twice. On our own name a move is a DNS change, which is
exactly how the tiles moved to R2 (2026-09-14). R2 because it is where that name already works (same
zone, same account, a two-level name with a verified certificate) and because egress is free for a
~380 MB per-device download that resumes with `Range`.

- ⚠ **`modelCatalogUrl` is set before anything is published.** Until the first upload it answers 404,
  and the Models screen says it cannot reach the list — the sentence a blank URL already produced.
- ⚠ **`models-publish.sh` is R2-aware**, the `tiles-publish.sh` rule: no `x-amz-acl` (R2 refuses it),
  region forced to `auto`, and **no default endpoint** (the old default pointed at a store that never
  held a model bucket).
- ⚠⚠ **The client and the publisher named the catalog differently** (Codex, P1). `HttpModelCatalogSource`
  requested `models.json` while `models-publish.sh` uploaded `catalog.json`, so the first successful
  publish would have been a 404 on every device — invisible to both sides' tests, since each served its
  own name. The client now reads `catalog.json` (the tiles name, and the one every runbook and output
  uses; nothing shipped against the old one, because the URL was blank), and
  `PublishedCatalogPathsTest` reads the script and fails when the two disagree.
- ⚠ **Two credentials.** Terraform takes a Cloudflare API token; the upload takes an R2 S3 pair that can
  write `tenshu-models` — the tiles pair is bucket-scoped and may not.
- ⚠ **No new threat-model surface.** Model fetches already egress through Control and carry no user
  content (§4); the observer of *which* model a device fetched becomes Cloudflare, which already
  serves the tiles.


---

## 34. As built — *Index now*, and a realm reset takes the vectors with it (2026-09-18)

**Scope:** `:inference-service` (a new op and a typed refusal), `:inference-service-proxy`
(`MediaIndexRunner`, `EmbeddingStore.clear`), `:control-shared` (the button and its six messages),
`:control` (the foreground notification), `:sync-service-proxy` (one hook on the local wipe).

Two things the first real device run made obvious, once §32's fixes let a pass actually work
(2026-09-17: a model published, downloaded, and 14 vectors written overnight).

### 34.1 ⚠⚠ The only way to index was to wait up to twelve hours

IN-D6 chose *idle + charging*, periodic at 12 h, and `MediaIndexSchedule`'s own comment says why
there is no manual start: *"Responsiveness is what an explicit 'index now' would be for, and IN-D6
does not ask for one."* That holds for the steady state and fails for the one moment that matters —
the minute after someone downloads a ~380 MB model **because they want their photos searchable**.
The screen then says nothing, does nothing, and is indistinguishable from the three defects §32
found. ⭐ `StartMediaIndexPass` is the lever, and the pass it starts is the same pass.

### 34.2 A refusal is four sentences, not one

⚠⚠ **A button that reports success and indexes nothing is the shape this service has spent five
slices removing.** The scheduled pass refuses by *counting* (`noBackend=635`, `deferred=true`) —
right for a job nobody is watching, useless to a person who just pressed something. So every
condition is read **in the op, before the runner**, and turned into `IndexPassRefused`:

| Reason | What the screen says | Why it is its own reason |
|---|---|---|
| `PAUSED` | *"Indexing is paused on this device. Resume it first."* | The user's own switch; the answer is Resume, not a second press. **Checked first**, so a paused device that also lacks consent is told the thing it can act on. |
| `NO_BACKEND` | *"No model on this device can index photos yet"* | An action: download one, on that same screen. |
| `NO_CONSENT` | *"Nothing is switched on for this device. Pick a feature under Features first."* (§38.3) | A different action, on that same screen. |
| `NOT_READY` | *"battery is too low"* | Not an action — a state that passes. |

⚠ **An already-running pass is a success**, never a refusal: the user asked for indexing to be
happening, and it is.

⚠⚠ **A pressed pass is held to a different bar than a scheduled one.** `IndexingConditions` grew
`allowManualIndexing()`, which keeps **only** *battery not low*: requiring "not in use" of a pass the
user just started by using the device is a refusal that can never clear, and requiring a *charger* of
someone who asked for their photos to be searchable is a refusal they cannot act on either. So a
press runs on battery — visibly, with a bar and a pause — and the scheduled bar is unchanged.

⚠ `IndexingConditions.Never`, the fail-closed sentinel for a platform whose wiring contributed
nothing, is an explicit object rather than a SAM lambda **because of that default**: a lambda
implements `allowIndexing` only and would inherit the permissive manual answer, so the sentinel that
exists to index nothing would have let a press run a neural network over the whole library.

### 34.3 ⚠⚠ `MediaIndexRunner` — one pass at a time, however it was asked for

Two entry points became three, and two of them already existed: the schedule is dispatched by the
in-process `ScheduleDrainLoop` **and** by WorkManager waking a closed Control. Both now go through
one runner — ⭐ and so does the fourth, IN-D6a's **new-arrivals batch** (`runPathsBatch` over the
arrived paths — never a walk: the debounced `NewArrivalsIndexWorker` on Android, an in-process
debounce on desktop), which never joins a running pass and is replaced by either of the others:

- `start()` returns **as soon as the pass is running** — an IPC call from a screen cannot be held
  open for the hours a first index takes.
- `runToCompletion()` **waits**, and joins one already in flight rather than starting a second — an
  OS-woken job holds its wakelock until the op returns, so returning early would let Android stop
  the work mid-library.
- ⚠⚠ It raises `BackgroundLiveness` while it works, in a `finally`. A pass started from a screen has
  nothing holding the proxy up once the user leaves, and Android reaps a proxy with no clients bound
  — while a liveness source that leaks is a foreground notification that never goes away.
- ⚠⚠ **The bar is asked before the slot is taken, not inside the pass.** `ScheduleDrainLoop` dispatches
  without enforcing the charging/idle constraint, so a scheduled pass is dispatched at every Control
  start; checking the conditions inside it meant walking the whole realm tree first and only then
  deferring — every launch, for nothing — and, worse, occupying the single-flight slot while doing it.
- ⚠⚠ **A press REPLACES a running scheduled pass rather than joining it**, because the two are held to
  different bars: the scheduled one stands down the moment the device stops being idle, which is the
  moment somebody picks it up to press this. Joining would report *"started"* over work about to stop.
  Superseding runs one way only — a scheduled pass still joins a manual one.
- ⚠ The outgoing pass is cancelled **and joined** before the new one starts: it clears the progress
  holder as it unwinds, so overlapping them would wipe the bar the press just published.
- ⚠⚠ **It must be a `single`.** Two runners are two single-flights, which is none. The test fixture
  built one inside the per-operation factory lambda and the second-press test duly walked the
  library twice — the exact production failure, found in the harness.

### 34.4 The notification says what the phone is doing

`ForegroundNotificationContent` gained `Indexing(done, total)`, ranked **below `Uploading` and above
`Tasks`**: an upload is something the user just did and is waiting on; indexing is long and
background, so it explains a warm phone without displacing the message about the thing in flight.
⚠ A pass over a library with no images really does report `0 of 0`, and null is not the same as zero
— *"Indexing 0 of 0"* is a notification about nothing, so it falls through to the task count.

⚠⚠ **The pass had to be folded into the notification's *re-post* loop, not only its build.** A pass
raises `BackgroundLiveness` exactly once, so the liveness path posts a single notification — at a
moment when the library walk has not finished and there is no total yet — and nothing re-posted
after it. The first draft would have read *"Keeping 1 background task running"* for the whole pass,
which is the P5 defect the upload figures already fixed once. ⚠ The fold is coarsened to one re-post
per 20 images: the holder ticks per image (~121 ms, IN-3m) and Android drops notifications updated
~8×/s. The build reads the holder's current value, so the number posted is exact, just not every
number.

⚠ **Android only.** Desktop and iOS run the same runner and the same liveness source; neither has
this notification, so on those platforms a manual pass is visible on the Models screen alone.

### 34.5 ⚠⚠ The progress stream is bounded, so the pass must be published before the op returns

`indexProgress()` **ends with the pass it was watching**, and an *empty* holder means "no pass is
running" — one idle tick, then `End`. Two things follow, and the first was got wrong first:

- ⚠⚠ **`MediaIndexRunner` publishes the pass, not `MediaIndexer`.** `start()` returns as soon as the
  pass is scheduled; the pass then walks the whole realm tree before the indexer reaches its own
  `progress.start(total)`. The screen re-subscribes the instant the op returns — inside that window —
  so a holder published by the indexer is published too late: the subscriber is told nothing is
  happening and its stream **ends**, for the hours the pass then takes. The runner now publishes
  `start(0)` synchronously before launching, and the indexer replaces the total once the walk is done.
  `total = 0` renders as "no message" on every surface, so the placeholder shows no wrong number.
  ⚠ The test for this needs a **dispatched** scope: under `UnconfinedTestDispatcher` the pass body runs
  on the spot and the assertion holds whether or not the runner published anything.
- ⚠ **A screen open since before the press was collecting a stream that had already completed**, so
  `indexNow()` used to re-subscribe on `Started`. ⚠ Superseded (2026-10-10): Control's screen now reads
  the holder in-process through a flow that never completes (§30.4), subscribes once and never again.
  The runner's synchronous `start(0)` still matters — to any op subscriber, and so the screen's bar
  moves the moment the press returns rather than after the walk.

⚠⚠ **The bar is cleared in a `finally`, in both places.** A throw from anywhere in the walk — a full
disk rethrown by `FileDatabase` — used to unwind past the ordinary clear, leaving a holder that says
*"indexing"* for the life of the process with *Index now* disabled behind it, and the notification
free to re-post those stale numbers. ⚠ The `async` is never awaited on the manual path, and an
un-awaited `async` under a `SupervisorJob` reports its exception **nowhere**, so the pass also logs its
own failure: before the runner existed the pass ran inline and a throw reached `LocalTaskExecutor`.

### 34.6 ⚠⚠ A realm reset must take the vectors with it

`SyncManagerImpl.wipeLocalStore` dropped every synced row and left the device-local embedding log
untouched. The vectors are keyed by **file path**, so after a reset (or a re-pair into another realm)
the new realm's `/DCIM/IMG_0001.jpg` would be answered by the old realm's vector — a search returning
photographs the user no longer has, from a realm they left. Nothing would report it; the store is
device-local by design (IN-D5) and every half works perfectly on its own.

- `SyncManagerImpl` takes an `onLocalStoreWiped` hook, called **after** the rows go and wrapped in
  `runCatching`: ⚠ a derived store that will not clear must not strand a half-wiped device, because
  the realm copy going is the irreversible half.
- Control wires it to `EmbeddingStore.clear()`. ⚠⚠ `clear()` **deletes the log file**, header and
  all. Emptying the in-memory maps is not a clear: the file *is* the store, so the next process start
  replays it and every vector comes back. A mutation that dropped only the `deleteFile` call survived
  the whole suite until `aClearedStoreStaysClearedAcrossAReopen` was written.
- ⚠ Truncating to the header would be just as wrong — a store owned by a model whose vectors are
  gone, against which the next `put` is then judged.
- ⚠⚠ **The hook stops the running pass before it clears.** `clear()` alone is not enough: a pass
  suspended in the model holds a list of paths from the realm being wiped, and resumes after the
  clear to find an unstamped store — so it writes a fresh header and repopulates it with the *old*
  realm's vectors, which is the cross-realm answer the clear exists to prevent. Cancellation lands at
  the pass's next suspension point and every store write goes through a `Mutex`, so a cancelled pass
  cannot complete one afterwards.
- ⚠⚠ **iOS route-1 does not go through this hook.** `ControlSurface.wipeLocalObjectStore` is its own
  reset and calls the store directly, never `SyncManagerImpl.wipeLocalStore`. Harmless today — iOS
  registers no inference backend, so it holds no vectors to inherit — and a trap the day one ships.
- ⚠ A cancellation is **not** swallowed: `runCatching` would eat `CancellationException` with
  everything else and report a wipe that cleared nothing, from a coroutine no longer running.

### 34.7 What this does not do

- **No re-index button**, only *index what has not been indexed*. Discarding good vectors to redo
  them is a battery cost with no user-visible purpose yet; a model upgrade already rebases the store
  by provenance (§26).
- **No scheduled-pass surface on desktop or iOS** beyond the Models screen (§34.4).
- **Nothing about labels.** The vocabulary is still uncalibrated (§27.2), so `CONTENT_TAGGING`
  indexes and emits nothing — pressing the button on a device with only the tagging switch on is
  honest work with no visible result. Calibration is its own slice.

---

## 35. As built — a relevance floor, because ranking is not matching (2026-09-21)

**Scope:** `PhotosLikeImplementation`, `InferenceBackend.scoreCalibration`, `VisionModel`.

⚠⚠ **Found on the first device ever to run semantic search for real, and it was not a wiring
fault — it was a missing comparison.** `photosLike` ranked every indexed vector and returned the
top `limit`. No score was compared against anything; `scorePerMille` was computed as an output
field and had **zero production readers**. On a two-photo library the maintainer searched *"cat"*
and got both photographs — a cat and a saved pairing QR code — each badged *"looks like this"* by
Photos. Searching *"xylophone"* returned **the same two**, merely re-ordered.

⚠ Not a small-library artefact. At any library size, a query matching nothing returns `limit`
photographs, each asserting a likeness.

### 35.1 ⚠⚠ The floor is on the model's probability, never on the cosine

The obvious fix is the one §19.3 forbids — *"a consumer must not invent a threshold"* — and §3.7.2
of `models.md` shows why a raw-cosine floor is not merely arbitrary but **meaningless**: image↔text
cosines over a real library span **−0.13 … 0.123** while text↔text cosines over the same vocabulary
span **0.57 … 0.98**. A number that looks cautious on one scale admits everything on the other.

⭐ So the conversion comes from the model: `ScoreCalibration(scale, bias)` gives
`sigmoid(scale · cos + bias)`, the constants read from the pinned checkpoint's weights (`logit_bias =
-12.885268`, `exp(logit_scale) = 110.995995`) rather than remembered. They live on `VisionModel`,
beside the checkpoint's other facts, because **two checkpoints have two calibrations** — a constant
carried by the consumer would silently describe whichever model it was written against.

⚠⚠ **Which is what happened.** As shipped on 2026-09-21 `VisionModel` carried the `-224`
checkpoint's pair (117.330765, −12.932437) while pinning `-256-multilingual`; corrected 2026-10-11
(`models.md` §3.10.8), with a test that holds the literals beside the weights they were read from.

The floor is **cosine ≈ 0.05998** (0.06 is admitted, 0.0599 is not), written
`SEMANTIC_RELEVANCE_FLOOR = 0.00197`: the probability the pinned checkpoint gives that cosine.
⭐ **Measured on the pinned checkpoint, 2026-11-09** (§35.1.1). Until then it was cosine 0.0915
(`0.061245844`), carried over from the `-224` checkpoint's band and never measured on this one, and
⚠⚠ **that hid every true match on the first iPhone to search**: five cat photographs scored cosine
0.080–0.084 for *"cat"*, all below 0.0915, and search answered *no results*. Ranking was perfect —
the best non-cat scored 0.021 — so the towers were fine and the floor was wrong. ⚠ It errs low on
purpose: a floor slightly too generous shows a weak match the user can dismiss, where one too strict
makes a photograph they know they own unfindable — and they cannot tell that from it never having
been indexed.

#### 35.1.1 How the floor was measured

**Inputs, all on the pinned `siglip-base-patch16-256-multilingual`**, through all three pairs: fp32
PyTorch, the published ONNX pair (uint8 vision + int8 text, Android/desktop) and the Core ML fp16
export the phone runs (archives `a30d4ddeaa9d…`/`628f7f32ac0c…`, CPU_AND_NE), with
`retrieval-gate.py`'s tokeniser (pad id 1) in the pinned toolchain.

- **The iPhone's own 22 vectors** (5 cats, 17 photographs of Sintra), read from its
  `DefaultEmbeddingStore` log. ⭐ The host's Core ML text tower reproduces the device's printed
  cosines to **1.2 × 10⁻⁷**, so host Core ML numbers *are* phone numbers.
- **COCO val2017, 5,000 images with ground truth**: the 28 exactly-mapped bare-word labels of
  `label-eval-coco-mapping.tsv` (`people` left out), and the retrieval gate's 36 queries, 16 of them
  matched to a COCO category.
- **The maintainer's 554-photo camera roll** (no truth), against 22 objects that are not obviously
  in it — five instruments (*xylophone, saxophone, accordion, harp, tuba*) among them.

**What it shows.**

| | |
|---|---|
| True matches of a one-word query | typically cosine **0.06–0.10**, not all: COCO's cats median 0.085, 5th percentile 0.038; the iPhone's cats 0.080–0.084 |
| An object not in the library | the five instruments peak at **0.030–0.057** over the camera roll; *xylophone* at 0.021 over the iPhone; the prefix *"ca"* (as-you-type) at 0.016 |
| Bare-word labels over COCO, at cosine 0.06 | median recall **0.57**, median precision **0.88** (Core ML; ONNX 0.56 / 0.88) — against 0.08 / 1.0, and 6 of 28 labels finding nothing, at 0.0915 |
| The same at 0.065 / 0.07 | 0.53 / 0.92; 0.45 / 0.96 |

0.06 is the lowest cut at which no instrument returns a photograph on the camera roll on any of the
three pairs (0.055 lets one or two through), and it is where §3.10's per-label floors for precision
0.90 already had their median (`models.md` §3.10.3: cosine about 0.06, p ≈ 0.002). ⚠ It is
**not** a cut that rejects every absent object: at 0.06 the iPhone returns one photograph for
*igloo* (0.062) and the camera roll a handful for *igloo*, *snowman*, *penguin* or *skiing* — weak,
dismissible matches, which is the side this floor is meant to err on. ⚠ And a random letter string can
beat it: observed for *qwzx*, which scores 0.117 on the camera roll (its median there 0.049), above
any floor that keeps true matches. One string, not a measured rule about such queries.

**⚠ One absolute floor, not a relative rule — decided on these numbers.** A cut relative to the
best score always admits the best, so it can never return nothing for *xylophone*, whose top over
the camera roll is a smooth tail (0.048, 0.044, 0.041, …), not an outlier. A cut relative to the
library's median returns nothing for *"cat"* in a library that is mostly cats. And within one query
true matches spread wide (COCO's cats: 0.038 to 0.10), so any gap narrow enough to matter drops real
ones.

**Platform skew — what was observed.** On this 554-photo camera roll no instrument crosses 0.06 on
any of the three pairs, so one shared constant behaved the same on all of them here. ⚠ The margin
is thin next to the skew: the worst instrument (harp on ONNX, 0.0567) sits ~0.003 below the floor,
far less than the per-pair differences below, so another library may put an absent object just over
the cut on one platform and not the other. ONNX minus Core ML for the same image and query, over pairs
near the cut (either score in 0.04–0.10): mean **+0.001 to +0.002**, sd 0.009–0.014, |difference|
≤ **0.019–0.028** for 95 % of pairs (real words, camera roll and COCO; sentences the wider end).
On COCO's 184 cat photographs for *"cat"*, mean −0.0024, sd 0.0053. The ONNX *text* tower alone,
against the iPhone's Core ML image vectors: within 0.0053 of the device. The sweep's figures are the
same to within 0.01–0.03 on all three pairs.

⚠ The sweep's scripts and raw results are not in the repository; they were written to a session
scratchpad. Re-running needs only the inputs, all on the maintainer's Mac: the image vectors in
`~/tenshu-models/label-eval-2026-10-10/` (COCO) and `label-eval-wide-2026-10-11/phone/` (camera
roll), the score matrices in `label-eval-wide-2026-10-11/coco-bare/`, the export and ONNX pair in
`~/tenshu-models/`, and the query texts embedded with `retrieval-gate.py`'s `tokenise` and loaders.

⚠⚠ **Until 2026-10-04 the carried-over floor very likely passed nothing on the ONNX backend.** The band it was
taken from was measured on correctly padded text vectors, while `embedText` padded with the wrong
id (§21.1) and scored lower across the board: on a 36-query, 300-image host corpus, no query's best
match reached the floor (cosine 0.0915, then written `p ≥ 0.10`) before the fix and 19 of 36 do
after it (`models.md` §3.9.4).

### 35.2 ⚠ A backend that cannot calibrate does not filter

`scoreCalibration()` returns `null` for a backend whose graph nobody has read, and `null` is **not**
"use a sensible default" — there is no sensible default, which is the whole point. Such a backend
ranks exactly as it does today. Filtering on an invented number would be worse than the defect.

### 35.3 What the tests pin

⚠⚠ The fixture had to be able to produce the failing case, and the first attempt could not: a
calibration of `scale = 10, bias = 0` puts p = 0.5 at cos = 0, so two *orthogonal* vectors scored
0.5 and cleared the floor — the test failed while the code was right. The suite now uses a
sigmoid-shaped calibration (p = 0.5 at cos ≈ 0.11) that is deliberately **not** the pinned
checkpoint's own constants, so the cases keep their meaning when a different checkpoint ships;
that claim is pinned separately against `VisionModel.PinnedSigLip`.

Four mutations, each caught: removing the filter, comparing the raw cosine, filtering when there is
no calibration, and dropping the pinned constants.

Since 2026-10-11 `PinnedSigLipCalibrationPinTest` holds the constants beside the checkpoint
revision and weights digest they were read from. `SemanticFloorCarryOverTest`, which held the
carried-over cut, is **retired** with it (2026-11-09). In its place, through the op and under the
pinned constants: `theIphonesFiveCatsAreFoundAndNothingElse` (the device's 22 cosines: five back,
best first, the rest dropped; red under the old floor), `xylophoneOverAnUnrelatedLibraryReturnsNothing`
(the iPhone's and the camera roll's measured cosines; red at a floor of 0.0003) and
`theOpCutsAtTheMeasuredCosine` (0.06 in, 0.0599 out). `test_label_eval.py` holds the Kotlin
constant to the same cut.

### 35.4 Still not done

- ⚠ **`scorePerMille` still has no consumer.** It crosses the wire and `TenshuPhotosRepository`
  drops it. Carrying it to `PhotoHit` would let the screen present *how* like this a photograph is,
  rather than asserting likeness flatly — the honest surface for a floor that is deliberately low.
- ⚠ **Photos' own cap is computed from source 1 alone.** `PhotoSearchViewModel` derives `capped`
  from the name matches, so a semantic list truncated at 30 is silently cut — which the screen's own
  doctrine ("a screen quietly showing 60 of 900 is the dishonest version") forbids.
- ⚠ Mia's planner lowers to `photosLike` too and inherits the floor; nothing there was changed.

## 36. As built — P0f-b PR-1: the read filter and the reconcile, wired (2026-10-03)

`media-processing-pipeline-sweep.md` §6.4.1. P0f-a built both mechanisms and left them null
in production; this wires them in Control and changes nothing in this module.

- **One predicate, bound once.** `single<LivePaths> { fileLivePaths }` in the common `ProxyModule`
  (`control-shared-proxy/.../di/MediaLibrarySeams.kt`), handed to `FaceGroupingComponents`, so
  `faceGroups` and — through the factory's default — `photosLike` filter on Android, desktop and
  iOS alike. Every `PlatformProxyModule` passes the common singletons through (`faceGrouping =
  get()`, `mediaSource = get()`); `MediaLibraryWiringTest` fails on a platform-local copy.
- **The source vouches.** `controlMediaSource(files, metered, livePaths)` replaces the lambda that
  overrode only `media()`: `walk()` answers `MediaWalk.complete(media, livePaths)` only when
  `walkImageLibrary().complete`, else `partial`. Before this no production pass ever reconciled.
- ⚠⚠ **Realm (§6.4.10's open question), checked in code and pinned by a test.** Every session a
  proxy mints shares its one `Database` and base context (`ClientRegistry.mintSession`), so
  Photos' `faceGroups`, Mia's `photosLike` and Control's pass all read the same personal `File`
  rows. Shared-realm files are `SharedFile` rows under `"$realmId::"` keys; the walk never reaches
  them and the predicate never asks about them. `MediaLibrarySeamsTest` runs the pass in one session
  and reads People/search from another.
- ⚠⚠ **The failed-read rule: throw, never "all dead", never unfiltered.** `livePathsAmong` throws
  `LivenessUnanswered` when the realm root `/` (created by `initFileService`, never deleted) does
  not read. `faceGroups`/`photosLike` then fail as an ordinary failed op (Photos: `Failed`, not
  `Unavailable` — so no "nobody here", no "nothing matched", no "wait for indexing"), and the
  reconcile's `dropTheDead` catches it and drops nothing. A throw from the store itself takes the
  same path.
- ⚠ **iOS gets the wiring and runs none of it today**: no backend is bound (both reads answer
  `NO_BACKEND` before the store is read) and no `IndexingConditions` are contributed (the scheduled
  pass defers, *Index now* refuses), so no iOS process reconciles. When PIO-7 binds a backend in
  Photos, Photos' route-1 runtime already has the predicate, because it runs the same `proxyModule`.
  ⚠ B1 is a store **writer**: once PIO-7 lands, any route-1 process that can run a pass would
  reconcile the shared App-Group logs — PIO-D10's single-owner rule must keep backends and
  conditions out of every process but Photos.
- ⚠ Cost: `faceGroups` does one in-process point read per photograph with faces, 200 at a time;
  `photosLike` reads about `limit` paths best-first (plus a dead-store probe that stops at the first
  live chunk). Nothing crosses IPC.

---

## 37. Labels, measured offline: what "inert" consists of, and what the first labelled read found (2026-10-10)

**Scope:** `scripts/inference/label-eval.py` and its inputs; no Kotlin. The numbers, tables and
limits are in [`models.md`](../models.md) §3.10. This section is what they mean for this track.

### 37.1 ⚠⚠ Labels are inert at three layers, not one

The status line and §27.2 say labels are inert because the vocabulary is uncalibrated. That is one
of three, and each alone is sufficient:

1. `MediaIndexer`'s vocabulary is `LabelVocabulary.Empty` (§27.2).
2. **No production backend claims `CONTENT_TAGGING`.** `OnnxInferenceBackend.supports` and
   `CoreMlInferenceBackend.supports` answer `SEMANTIC_IMAGE_SEARCH` only, so the label half of the
   pass resolves no tagger (§32.1's structure) and never runs.
3. **`tagImage` has no body**: both backends return `emptyList()`. Nothing in production turns a
   vocabulary's terms into text vectors and scores an image against them.

So a calibrated vocabulary alone writes nothing. Leaving *inert* takes a `tagImage` that embeds the
vocabulary's prompts through the text tower and returns one score per term, a backend that claims
the capability, and then the vocabulary.

### 37.2 ⚠⚠ The rule's unit was never fixed

`selectLabels` compares `confidence >= floor` and `winner − runnerUp >= margin` on whatever
`ImageTag.confidence` holds. The KDoc and `confidencePerMille` say probability; the only numbers
the rule has been run on (`LabelSelectionTest`, `EmbeddingHarness.MARGIN`) are cosines. A floor
translates between the two and a margin does not. `models.md` §3.10.8 has the detail and the
argument for the cosine: calibrated floors for ordinary objects sit near **p = 0.002**, where a
per-mille confidence column stores 1. ⭐ **Decided 2026-10-11: the cosine**, in the rule and in
the row (§39.2).

⚠⚠ The same section records that `VisionModel.PinnedSigLip.calibration` carried the `-224`
checkpoint's constants, so `SEMANTIC_RELEVANCE_FLOOR` (§35) was p ≥ 0.061 on the pinned model, not
the 0.10 it stated. Corrected 2026-10-11: the constants are the pinned checkpoint's and the floor
reads `0.061245844`, the same cosine (0.0915) as before. ⭐ Re-measured 2026-11-09: cosine 0.06,
`0.00197` (§35.1.1).

### 37.3 What the first labelled read says about §19.3

- **IN-D9's two tiers behave as designed on what could be scored**: the floor does nearly all the
  work and the margin almost none (it decided 13 of 2 500 images in its busiest sibling set).
- ⚠ **One leaf per sibling set costs recall on photographs holding two siblings** (`dog` 0.72 →
  0.65). IN-D9 chose that for breeds, where siblings exclude each other; `dog` and `cat` under
  `animals`, or `car` and `bus` under `transport`, do not. Which sibling sets are exclusive is a
  per-branch question the label review owes.
- ⚠ **The candidate prompts lose to the bare label word** on 30 of 37 object labels. ⭐ Decided
  2026-10-11: the first vocabulary is prompted with the bare word (§39.3).
- ⚠⚠ **§28's firing-rate question is not answered.** `indoors` is not scorable against COCO.
- ⚠⚠ **A threshold table belongs to the artifact and the runtime version together** (`models.md`
  §3.10.6), and the Android/desktop and iOS artifacts do not write the same labels under their own
  tables: about one written label in five differs. That was
  [`ios-on-device-inference.md`](ios-on-device-inference.md) D13's open half. ⭐ **Decided
  2026-10-11: the first writer wins** (§39.1).

---

## 38. As built — Features become a device list, and move to the bottom (2026-10-11)

§32.4 put IN-D3's two answers on Settings → Models as two switches per capability, first on the
screen. Used on a phone, that was four switches before the first model, two of them under the same
icon, and nobody could say what the second of each pair was for without reading §9.2 — the
maintainer included, who had already deleted a device's face data through the wrong one
(2026-09-27). The store is right; the screen was showing the store.

**Nothing stored changed.** `realmConsent` and `deviceConsent` are the same rows, absence still
means no, and the proxy reads them exactly as before. No schema, no version bump.

### 38.1 The shape

- **Order: Models, Indexing, Features.** Downloading is what the screen is opened for. Which
  devices do the work is answered once and revisited rarely, so it goes last. ⚠ IN-D10 still holds:
  it is on this screen beside the models, not behind another settings entry.
- **Features are rows, not switches.** One row per offered capability, with its own icon, a line
  saying where it stands (*"On for this device and 1 other device"*, *"On for 2 other devices, not
  this one"*, *"Off"*), and a chevron.
- **Each row opens a feature screen** (`ModelFeatureScreen`, `Destination.ModelFeature`): what the
  feature does and costs, then **Devices** — *This device* first, then every other authorised
  device by name — each with one switch that is that device's own `deviceConsent`.
- ⚠ **The feature names are unchanged** (*Search photos by description*, *Group photos by people*).
  Photos and Mia send people to Control by those names; a rename is a change to three apps.

The realm answer has no control of its own. It is written with the first device switched on and
withdrawn by *Turn off everywhere*; in between it is simply yes.

### 38.2 ⚠⚠ What each control writes

| Control | Writes | Why |
|---|---|---|
| *This device* on | realm yes, then this device yes | §32.4's "on", unchanged — a bare device yes under a realm no moves a switch and indexes nothing. |
| *This device* off | this device no | The other devices keep theirs; the realm stays yes. |
| Another device off | that device no | `DeviceSetting` rows sync and every device sees every device's (§9.2), so the row reaches it. |
| Another device **on** | — not offered | A yes spends that device's battery and storage, so it is given on that device. The switch is disabled when off and the row says *"Turn it on from that device."* ⚠ `clearOtherDeviceConsent` takes no value: the interface cannot write a yes. |
| *Turn off everywhere* | this device no, each other device holding a yes no, **then** realm no | Realm last, for §32.4's reason: interrupted anywhere, the feature is on for fewer devices — never a realm no over answers a later "on" would resume. |

- ⚠⚠ **Switching faces off asks first, every way it can be done**, and says the face data is
  deleted and the names kept. An explicit no is what deletes (`inferenceConsentWithdrawn`), on this
  device or — once the row syncs — on another. *Turn off everywhere* asks for every feature.
- ⚠⚠ **Search's confirmation promises no deletion**, because there is none (§32.8).
- ⚠⚠ **A yes still stored under a realm no shows as on**, here and on other devices, and says it
  would start again. §32.4's rule, carried over: hidden, it resumes unseen. *Turn off everywhere*
  is offered whenever any yes is stored, which is the only way to clear another device's.
- ⚠⚠ **No other device is listed until this device knows its own id.** The roster includes this
  device; listed among the others, it would sit under a control that writes another device's row.
- ⚠⚠ **And *Turn off everywhere* waits for that list**. "Not loaded" and "no other
  device" are different answers (`null` and empty); run over the first it would clear no peer,
  write the realm no, and leave their answers to resume. The button is disabled and the view model
  refuses until the list has been read.
- ⚠ **Every other authorised device is listed, whether or not it can run the feature.** Nothing
  synced says which devices can; an iPhone's row reads *"Off. Turn it on from that device."* until
  something does.
- A failed write to another device is said (*"Couldn't turn that off for the other device"*), and
  the list — a live read — keeps showing what is stored.

### 38.3 ⚠⚠ Off by default, at the bottom of the screen — so the download asks

Moving Features last would have recreated §32's inert device: a model downloaded at the top, nothing
indexed, and the reason three sections down. Two things prevent it.

- **A first install asks.** When a model lands for an offered capability that is not on for this
  device, the screen asks *Turn on "…" on this device?* with the feature's whole description as the
  body — IN-D3's *say what it costs before the yes*. Yes is the same two writes as the switch. Not
  asked for an update, a failed download, a feature already on here, or a capability this build
  shows no row for.
- **The indexing line links.** *"Nothing is switched on for this device, so nothing is indexed.
  Pick a feature under Features."* scrolls to them when tapped.

⚠⚠ **This is not onboarding offering faces.** `offerableInOnboarding = false` (§9.2) exists because
a first-run flow that asks is one that pressures. This question is raised only by the user choosing
to download the face model, on the settings screen, and *Not now* writes nothing.

### 38.4 What the tests pin

- **Streams** (`ModelsLiveStreamsTest`): no other device before this one has an id; a row and a
  roster change both arrive on the one subscription; a realm of one says "none" rather than nothing.
- **View model** (`ModelsViewModelTest`): *Turn off everywhere* writes device, others, realm in
  that order and skips devices already off; one unwritable device is said and the rest still go;
  one other device is switched off alone; a first install offers, yes writes realm then device,
  *Not now* writes nothing; no offer for the four cases above, and one for realm-yes-device-no.
- **Screens** (`ModelFeatureScreenTest`, `ModelsScreenLayoutTest`, `ModelsScreenTextTest`): this
  device on calls the realm write and off the device write; an off device cannot be switched on;
  faces ask first; a lingering yes renders on; the Models screen carries no switch; the body
  scrolls from the first model to the last feature; the indexing line scrolls to Features.

### 38.5 What is still not true

- ⚠ **iOS has its own Models screen, built separately.** It reads the same shared view model; the
  three additions there (`otherDevices`, `featureOffer`, the two turn-off calls) are additive.
- ⚠ **Nobody has switched another device off from here across two real devices.** The write is a
  synced row and the reader is unchanged, so it should arrive; it is untested end to end.
- ⚠ **A device that leaves the realm keeps its rows until its tombstone lands**; it drops off the
  list at once, because the list is the authorised roster.

---

## 39. Label decisions (2026-10-11)

**Scope:** decisions only; no code. Three questions §37 left open were decided by the maintainer on
2026-10-11 (L1–L3); the two that L1–L3 left for the maintainer were decided on 2026-10-14 (L4, §39.6,
and L5, §39.7). Each is recorded with the measurement it rests on and with what it owes. **The numbers
are `models.md` §3.10's, §3.11's and §3.12's** and are not restated as tables
here; a figure below names its subsection. Three kinds of statement appear and are kept apart:
**Decided** (the maintainer's choice), **Measured** (host only: COCO val2017 in §3.10 and §3.11.4,
554 camera photographs with no truth in §3.11, Open Images V7 in §3.12) and **Code** (read from
the tree at `54b14e97f`, cited by file and line). Where a reading of a measurement is not itself
measured it is marked **Inferred**.

⚠⚠ **Sequencing.** Nothing for labels is built until PIO-7 PR-5 is complete
([`ios-on-device-inference.md`](ios-on-device-inference.md) §14). Labels are not on the path to iOS
search-by-description parity: search needs vectors, which are device-local, and no label. L2 is
recorded now, ahead of any build, because it is cheapest while no model has written a label row
(§39.2).

### 39.1 L1 — Across devices the first writer wins, each artifact under its own floor table

**Decided.** Label rows sync (IN-D5). Whichever device derives a photograph's labels first writes
them, using the floor table of the artifact it runs. Another device does not label a photograph
that has already been labelled for the same model id, model version and label-set version, whatever
artifact did it. The row records which artifact wrote it.

**Consequence accepted.** A borderline photograph is or is not tagged depending on which device
indexed it first. It does not flip afterwards.

**Measured** (`models.md` §3.10.4–§3.10.5; COCO, held-out half, target precision 0.90, the
candidate file's prompts, which is what the decision was taken on):

- Under their own floors the ONNX pair (Android, desktop) and the Core ML pair (iOS) agree with
  a Jaccard of **0.784** over the labels either writes; under one shared table, **0.693**.
- Core ML against fp32 is **0.988**. The distance is the ONNX pair's quantisation.
- **94 %** of the disagreements are within 0.01 cosine of a floor (74 % within 0.005). They are
  borderline photographs, and no choice of floor removes them: every floor has images beside it.

**Measured since, under the bare word L3 prompts with:**

- COCO, own floors: **0.842** (§3.11.4). The bare word halves the per-label offset between the two
  artifacts and leaves the per-image spread.
- Open Images, 156 labels, own floors: **0.535**; Core ML against fp32 0.960 (§3.12.7). Nineteen
  labels there get a floor on one artifact only, and are written by one platform and never by the
  other.
- On 554 camera photographs, with no truth: the two artifacts rank a different label first on
  about 24 % of them, and under COCO's floors write the identical label set for about 75 % of the
  photographs either labels (§3.11.3, §3.11.5; shares of counts rounded to the nearest ten).

⚠⚠ **A caveat on "its own floor table", not a reversal.** On Open Images one shared table agreed
*better* than two: **0.742** against 0.535, the opposite of COCO's 0.693 against 0.784 (§3.12.7).
A floor there was placed on some 50 positives, where COCO's common labels have hundreds.
**Inferred**, not measured: a per-artifact floor removes the offset between the artifacts and adds
its own sampling error, and on samples that small the second is the larger. L1 stands. What it
needs to hold is floors placed on enough images, and how many is enough has not been measured.

**Rejected, and why:**

| Alternative | Why not |
|---|---|
| Labels per device, not synced | It gives up what IN-D5 syncs labels for: a device with no model gets labels the moment another device derives them |
| Move Android and desktop to an fp16- or fp32-equivalent model | Roughly double the download and slower indexing, ⚠ **neither measured**, to change the outcome of borderline photographs |
| One threshold table shared by both artifacts | Measured worse on COCO: 0.693, and `people` on Core ML under ONNX's floors drops recall 0.57 → 0.28 (§3.10.4). ⚠ Measured better on Open Images' small samples; see the caveat above |

Of §3.10.5's three ways out this is the first (the stamp tells the artifacts apart), with a merge
rule of *the first writer's set stands*. The second (read the union) is rejected by it, and the
third is the middle row above.

#### 39.1.1 What the row holds today, and what recording the artifact costs

**Code.** A `MediaLabel` row has six fields: `labelKey` (`"$file/$capability/$label"`, the primary
key), `filePath`, `capability`, `label`, `confidencePerMille` and `producedBy`
(`MediaLabel.kt:81-88`, `:113-114`). `producedBy` is `ModelProvenance.asString()`,
`"$modelId@$modelVersion/$labelSetVersion"` (`ModelProvenance.kt:41`).

⚠⚠ **The type cannot tell the ONNX artifact from the Core ML one.** `ModelProvenance` has three
members and none is a runtime (`ModelProvenance.kt:19-32`). D13 gave both artifacts the same id and
version, and `VisionModel.PinnedSigLip.provenance` is `ModelProvenance(modelId, modelVersion)` for
both (`VisionModel.kt:40`, `:55-57`). The catalog entry knows its `runtime`
(`ModelCatalogData.kt:105`); the row does not. The KDoc says the omission is deliberate: two
devices running the same model *"must produce interchangeable rows"* (`ModelProvenance.kt:14-17`).
§3.10.5 measured that they do not, so that sentence is owed a correction with the code.

⚠⚠ **And the stamp a label pass would write today is not that one.** A label is stamped with
`currentProvenance(tagger)`, which is the backend's own `provenance` only when the backend is a
`ModelBackedBackend`, and otherwise `ModelProvenance(backend.id, 1)` (`MediaIndexer.kt:206-207`,
`:465`). Neither `OnnxInferenceBackend` nor `CoreMlInferenceBackend` is one
(`OnnxInferenceBackend.kt:59`, `CoreMlInferenceBackend.kt:79`; the only implementer outside the
face model is `MediaPipeFaceBackend`), and production passes no override (`ProxyModule.kt:375-390`).
Both stamp their **vectors** with `model.provenance`; a label would get `onnx-runtime@1/0` on
Android and desktop and `core-ml@1/0` on iOS (`OnnxInferenceBackend.kt:99`,
`CoreMlInferenceBackend.kt:81`). Two consequences, both the opposite of what D13 intends:

- The label stamp names the **runtime** in the `modelId` position and no model at all. It does not
  move when the weights do (`ModelBackedBackend.kt:8-10` says so of the fallback).
- `isStaleAgainst` calls a different `modelId` stale (`ModelProvenance.kt:57-64`). Each platform
  would read the other's marker as stale and label the photograph again, and the other would then
  do the same to that marker. That is the mutual staleness D13 chose one id to avoid, and under L1
  it is a second writer every time.

**Owed: the label stamp is the model's** (both backends answer as `ModelBackedBackend`, or the pass
is handed the model's provenance), before anything else in this section holds. It is inert today
for §37.1's reason: with no tagger there is no stamp.

**Owed: recording the artifact is a change to what a label row stores**, made when labels are
built. Either a seventh field on `MediaLabel` or a longer `producedBy` string; not decided here.
⚠⚠ **Neither is free, and both owe the `InferenceService` version bump** (it is at 2,
`InferenceService.kt:36`):

- **A field** is a positional reshape: House Rule 9 as written, the bump and its
  `SchemaMigration`, and `SchemaShapeBaselineEnforcementTest` fails until both exist.
- **A longer stamp string** leaves the positional shape alone, so the baseline test passes and
  nothing asks for a bump. It is owed all the same, because the string is a stored, synced value
  with a grammar and peers on different builds exchange it. `ModelProvenance.parse` returns `null`
  for anything after the label-set version that is not an integer (`ModelProvenance.kt:92-103`),
  and `isCurrent` reads `null` as *not indexed* (`MediaIndexer.kt:820`). A label-capable build
  with the old grammar, alongside one with the new, re-labels every photograph the new one
  marked: the second writer L1 forbids, for as long as the two coexist. So this option owes the
  bump (which is what fences the older build from pulling the rows, `core-schema-evolution.md`
  §7.2–§7.3), a migration or upcast for rows stamped in the old grammar, and a `parse` that still
  reads them. ⚠ `asString`/`parse` are also the stamp format of the two device-local stores
  (`DefaultEmbeddingStore.kt:359`, `DefaultFaceTemplateStore.kt:366`); a grammar change reaches
  them too.

§39.5 applies the same test to the other owed items.

⚠⚠ **The reshape is not free, and "no label rows exist" is not true of the table.** Two
statements, both **Code**:

- **No model writes a `MediaLabel` in production today.** The three production call sites pass
  `CONTENT_TAGGING` (`MediaIndexRunner.kt:54`, `MediaIndexPassImplementation.kt:117`,
  `StartMediaIndexPassImplementation.kt:110`); no production backend claims it
  (`OnnxInferenceBackend.kt:131`, `CoreMlInferenceBackend.kt:111`), so `tagger` is `null`
  (`MediaIndexer.kt:410`) and the pass leaves the loop before both the marker and the labels
  (`MediaIndexer.kt:690`, ahead of `:695` and `:701`). That is §37.1, and it covers the
  `__indexed` marker too. `FakeInferenceBackend` is never bound (`ProxyModule.kt:298`).
- ⚠⚠ **A person does.** `NameFaceGroupImplementation.kt:92-103` writes a `MediaLabel` for every
  photograph in a named face group: capability `FACE_CLUSTERING`, `confidencePerMille = 1_000`,
  `producedBy = ModelProvenance.User`. Those rows are synced, exist wherever People has been used,
  and are the rows `isStaleAgainst` exists to protect.

So a `MediaLabel` reshape owes a migration that carries the name rows, on every store that holds
one. It is small (one defaulted member), and it is not nothing. What *is* free today is the
**meaning** of every model-written row, because there are none.

#### 39.1.2 How the pass decides today, and what "do not re-label" asks of it

**Code.** A photograph needs labels when its **marker row** is missing or stale. The marker is the
`MediaLabel` keyed `(file, capability, "__indexed")`; `isCurrent` reads its `producedBy`, parses it
and answers `!parsed.isStaleAgainst(stamp)` (`MediaIndexer.kt:801-822`, called at `:521-522`).
Stale is a different `modelId`, a lower `modelVersion` or a lower `labelSetVersion`
(`ModelProvenance.kt:57-64`). The marker syncs, as every `MediaLabel` does.

**So the ordinary case of L1 is how the pass would behave once the stamp is the model's**
(§39.1.1), and not before. With one id and one version on both platforms, a marker that arrived
from the other platform reads as current and the photograph is skipped; nothing compares
artifacts, which is what "whatever artifact wrote them" asks. ⚠ The marker is what the rule reads,
not the presence of a label: a first writer that found nothing has also written, and a second
device adding labels to that photograph would be the flip L1 rules out.

What the rule does **not** get from today's code, each **owed as design and not decided here**:

1. **Owed: a label-set version bump.** ⚠ Today it would re-label nothing. The stamp comes from the
   backend (`MediaIndexer.kt:465`, `:207`) with `labelSetVersion = 0` either way (the fallback's
   default, and `PinnedSigLip`'s); `LabelVocabulary.version` (`LabelVocabulary.kt:79`) is read by
   nothing in the pass. Once it is in the stamp, every device holding the new vocabulary sees
   every marker as stale and re-labels. Which device does that, and whether a photograph first
   labelled by one artifact may be re-labelled by the other, is the question. "First writer wins"
   answers it per label-set version and says nothing about who may be first for the next one.
2. **Owed: two writers that raced.** Device B labels a photograph before device A's rows for it
   have arrived. `MediaLabel` is `Syncable(Eager, LastWriterWins)` (`MediaLabel.kt:79`) and is
   keyed per label, so today's merge is **per row**: the marker with the later HLC survives, and a
   label only one device wrote has no row to conflict with and survives too. ⚠⚠ The photograph
   ends with the **union** of both sets, which is §3.10.5's second way out, at a precision no floor
   was chosen for, under a marker naming one of the two writers. Which set stands, by what merge
   rule, and what removes the other set's rows is owed. Recording the artifact (§39.1.1) is what
   would let a rule tell the two sets apart at all.
3. **Owed: a threshold-table recalibration.** §3.10.6: a table belongs to the artifact **and** the
   runtime version, so an `onnxRuntime` bump invalidates one without changing a byte of the model
   or the vocabulary. Nothing in the stamp moves when a table does. Whether new floors apply only
   to photographs not yet labelled (old rows keep the old table's verdict, consistent with L1), or
   count as a version that re-labels, is owed. So is where a table's version is recorded.
4. **Owed: the stale sweep.** A pass writes rows and deletes none: a re-label that no longer
   selects a label leaves the old row in place. `ModelProvenance.kt:54-55` says so (*"Today nothing
   sweeps non-marker rows"*). Every case above that re-labels needs it, and it must never touch a
   user-authored row. Label rows of a deleted photograph are the same gap: `reconcile` drops
   vectors and templates only (`MediaIndexer.kt:334-347`).
5. **Owed: marker and labels are separate rows, saved one after another** (`MediaIndexer.kt:695`,
   then `:701-719`). Whether a second device can see a marker before the labels it vouches for,
   and whether that matters to any reader, has not been looked at.

### 39.2 L2 — The score is a cosine, in the rule and in the row

**Decided.** A floor, a margin and the stored confidence are cosines. The stored integer is the
cosine in thousandths.

**Why.** §37.2 and `models.md` §3.10.8: `selectLabels` never fixed its unit, the KDoc and the
column name say probability, and every number the rule has been run on is a cosine. A floor
translates between the two units and a margin does not. And the calibrated floors sit at a median
of **p ≈ 0.002** (§3.10.3), where a per-mille probability stores 1, and 0 for nine of ONNX's 32
labels.

**The range.** A cosine of two unit vectors is in [−1, 1], so the column holds **−1 000 to 1 000**
and ⚠ **can be negative**. That is not a corner case: Core ML's floor for `people` is −0.0128
(§3.10.3), so a correctly written `people` row can store a negative number. The floors measured run
from −0.013 to 0.116 (§3.10.3), −13 to 116 in the column.

**Code: can the field hold one?** As far as was read, yes, with one part unverified.
`int(...)` declares a `TInteger`, whose value is a `Long` (`Dsl.kt:944-950`, `TInteger.kt:15`);
that is §12.1's "Long on the wire". Nothing in the type restricts the sign, and the 8-byte encoding
is the two's-complement big-endian of the `Long` and reads back with the sign
(`LongUtil.kt:3-21`). ⚠ **Owed: a round-trip test of a negative value** through save, sync and
read on every store (JVM, Android, the iOS replica, IndexedDB), and a look at any ordered read over
the column: big-endian two's complement sorts negatives **after** positives byte-wise. Nothing
found sorts on it in the store today. Until that test exists this is read, not proven.

**Owed when labels are built** (none of it is done, and all of it is **Code** as it stands):

- **The write clamps negatives to 0** and truncates: `(tag.confidence * 1_000).toInt()
  .coerceIn(0, 1_000)` (`MediaIndexer.kt:713`). The clamp must go and the conversion must round
  (§28.4 is the same truncation, already paid for once).
- **Photos drops them**: `groupPhotosByLabel` skips a row below `minConfidencePerMille`, whose
  default is 0 (`PhotoLabel.kt:88`, `:120`), and seeds its per-photo best with 0 (`:95`). Correct
  for a probability, wrong for a cosine: a `people` row at −0.010 would vanish from Things.
- **The KDoc**: `selectLabels` (`LabelSelection.kt:8-36`), `ImageTag.confidence` (*"the confidence
  it believes it at"*, `InferenceBackend.kt:67-68`), `VocabularyLabel` (*"the scale is the model's
  own: SigLIP scores `sigmoid(117.33·cos − 12.93)`"*, `LabelVocabulary.kt:28-30`), and
  `MediaLabel`'s *"per-mille (0–1000)"* (`MediaLabel.kt:38-40`, `:61`, `:159`).
- **The column's name and meaning.** `confidencePerMille` is the field id's last segment
  (`MediaLabel.kt:122`) and the name every reader uses. Renaming it is a change to a stored type:
  treat it as a reshape (House Rule 9) unless `SchemaShapeBaseline` is shown not to pin field ids.
  ⚠ As in §39.1.1 it is free of *model* rows and not of name rows.
- ⚠ **Owed: what a user-authored row stores.** Name rows hold 1 000, written as *"certainty"*
  (`NameFaceGroupImplementation.kt:99-102`). In thousandths of a cosine 1 000 is still the top of
  the range, above anything a model writes, so the existing rows need no rewrite. Whether a
  person's row should carry a cosine at all is a question the rename should answer.

#### 39.2.1 ⚠⚠ L1 and L2 together: a stored cosine means something only beside its artifact

A consequence of the two decisions, left open on 2026-10-11 as ⚠⚠ **a question for the maintainer
before labels are built**: the column's meaning is fixed by the first row a model writes.
⭐ **Decided 2026-10-14: option i** (L4, §39.6). The analysis below is kept as the record it was
decided on.

**The property.** Under L1 a synced library holds label rows written by both artifacts. Under L2
each stores its own raw cosine. A raw cosine is comparable with another from the **same** artifact
for the same label, and with nothing else: two rows from different artifacts must not be ranked or
thresholded against each other as they stand.

**Measured** (`models.md` §3.10.4, §3.10.5):

- **A per-label offset.** ONNX's score minus Core ML's, over the same images, has a mean between
  −0.017 (`car`) and +0.023 (`people`, `skiing`): −17 to +23 in the column.
- **Per-image noise on top.** The standard deviation of that difference is about 0.0076 per label
  (0.007 to 0.012): about 8 in the column.
- **Against what.** The gap between a label's typical positive and typical negative is some 0.07
  (`dog`: medians 0.067 and −0.005). The offset is up to a third of that gap and the noise a tenth.
  ⚠ §3.10 does not publish the spread of scores **among a label's positives**, which is the number
  a ranking is skewed against. Rows that fire all lie above a floor, so that spread is likely
  narrower than the gap and the offset larger beside it than "a third" suggests (an inference, not
  a measurement). Owed: read it from the
  saved per-label distributions (§3.10.10) before judging how much this matters.
- For `people` the two floors differ by 0.0209 (0.0081 and −0.0128) against an offset of +0.023:
  the floor table already carries most of that label's offset. ⚠ One label; not checked for the
  rest.

**Code: who compares the stored value across rows today.** One reader, in two places:

- `groupPhotosByLabel` (`PhotoLabel.kt:67`; its only caller is `CollectionsViewModel.kt:748`,
  with the defaults) **thresholds** each row against `minConfidencePerMille` (`:88`), keeps the
  **maximum** per photograph across rows (`:95`), and **sorts a label's photographs by it,
  descending**, the first being the card's cover (`:101-104`). With rows from two artifacts the
  cover and the order favour whichever artifact scores that label higher: for `people`, by the
  offset above, the ONNX-written rows.
- **Name rows are not in that ordering.** A `FACE_CLUSTERING` row is skipped at `:87`, before the
  threshold and the sort, so its 1 000 competes with no model's score. ⚠ That is one `continue` in
  one reader. Whatever the column comes to hold, 1 000 must stay outside any ordering of model
  scores, or a typed name outranks or is outranked by a number in another unit.

Nothing else reads it: Photos' label search projects the path and the label only
(`TenshuPhotosRepository.kt:1499`), Mia filters on the label text and reads no score
(`QueryPlanLowering.kt:207`, `AskMiaImplementation.kt:919-920`), and no Swift or web code names
the field. `TenshuPhotosRepository.kt:1271`/`:1287` is the projection that feeds
`groupPhotosByLabel`.

**What cosine-derived quantity the column holds, and what a reader may do with it.** L2
settles the **unit**: a cosine, not a probability. It did not settle this, and the options were
put to the maintainer as they stand below. ⭐ **Decided 2026-10-14: i**, with iv's fallback for a
reader that has no table (L4, §39.6):

| | Option | Cost |
|---|---|---|
| i | Store the raw cosine; a reader normalises with the row's artifact and that artifact's table | Needs the artifact on the row (§39.4 item 2) and the table on **every** reader, including a device with no model, which is the device IN-D5 syncs labels for |
| ii | Store a value relative to the artifact, such as the cosine minus that artifact's floor for the label | Comparable across artifacts only as far as the floors track the offset (one label checked, above). ⚠ A recalibrated table changes what every stored row means (§39.4 item 5) |
| iii | Store both | A second column, and both costs above in part: the relative value still moves with the table |
| iv | Store the raw cosine and forbid any use across rows beyond "is labelled"; pick covers and order by something else (recency, or the device's own vectors) | The score stops being useful to a reader at all, and a device with no model has no vectors to order by |

⚠ **Search is a separate matter.** `SEMANTIC_RELEVANCE_FLOOR` (§35) stays a floor on the model's
probability. L2 is about labels: the selection rule and the label row.

### 39.3 L3 — The first vocabulary is what was measured and passed, prompted with the bare word

**Decided.** The first label release holds only the object and animal labels that were measured
and that get a floor at the target precision on **both** artifacts, and each is asked of the model
by its bare label word. Scenes, documents, screenshots, events, weather and art wait for labelled
data.

**Measured** (`models.md` §3.10.2, §3.10.7, §3.11.4; COCO, held-out half, target precision 0.90):

- **37 of the 207** candidate labels could be scored against COCO, the ones with a category
  behind them. Against Open Images, **156** (§3.12).
- The bare word beat the candidate file's prompt on **30 of 37** labels, mean AP **0.724 against
  0.697** (fp32, and the same two means on Core ML; on ONNX 29 of 37, 0.716 against 0.692). On
  Open Images it is ahead on 104 of 156 (ONNX), 0.781 against 0.771.
- **Under the bare word a floor was placed for 31 labels on ONNX and 29 on Core ML**: `bag` and
  `sandwich` on ONNX only; `food and drink`, `dessert`, `vegetables`, `houseplant`, `car` and
  `computer screen` ᵖ on neither. Under the candidate file's prompts it was 32 and 32.

**The set this defines** is the labels with a floor on both artifacts under the bare word, **29
labels** (§3.11.4):

- Top tier (3): `people`, `animals`, `transport`.
- Under `animals` (6): `dog`, `cat`, `bird`, `horse`, `cow`, `sheep`.
- Under `food and drink` (4): `pizza`, `cake`, `fruit`, `wine` ᵖ.
- Under `transport` (7): `bicycle`, `motorcycle`, `bus`, `train`, `boat`, `airplane`, `truck`.
- Under `objects` (4): `phone`, `laptop`, `furniture`, `book`.
- Under `activities` (5): `skiing` ᵖ, `snowboarding` ᵖ, `surfing` ᵖ, `tennis` ᵖ,
  `skateboarding` ᵖ.

ᵖ marks §3.10.2's proxies.

⚠⚠ **That list is the measurement's, and the decision's own wording does not yet select from it
cleanly.** Three things the measurement says that the list above does not settle. Each was **owed
to the maintainer** on 2026-10-11; ⭐ **items 1 and 2 were decided on 2026-10-14 (L5, §39.7)**, and
item 3 stays open:

1. ⭐ **Decided (L5): proxies are out, and the candidate set is 23.** As it was put: **six of the 29 are proxies**, and "object and animal labels" does not obviously
   include them. `skiing` was scored against the presence of skis, `wine` against a wine glass.
   §3.10.2: *"a proxy's figures describe the proxy."* Without them the set is 23.
2. ⭐ **Decided (L5): held-out precision whose Wilson lower bound is ≥ 0.90, on both artifacts,
   under the bare word**, which nothing passes today. As it was put: ⚠⚠ **the bar for "passed". A
   floor on the calibration half is not one.** What holds a
   precision of 0.90 on the *held-out* half, on both artifacts, bare word:

   | | labels with a floor on both | point estimate ≥ 0.90 | bootstrap lower bound ≥ 0.90 | Wilson lower bound ≥ 0.90 |
   |---|---|---|---|---|
   | COCO (§3.11.4, §3.12.6) | 29 | **9** | 3 | **0** |
   | Open Images (§3.12.6) | 100 | **25** | 4 | **1** |

   A bar must be a held-out one and must name its interval. ⚠ **A percentile bootstrap must not
   be it**: where no firing image is wrong every resample is 1.00, so the interval is [1.00, 1.00]
   for nine firing images as for nine hundred, and `truck` holds 0.90 on COCO that way with nine
   (Wilson lower bound 0.70). **Recommended to the maintainer, and not decided: the Wilson lower
   bound.** It implies a larger labelled sample before any label ships: a lower bound of 0.90
   takes 35 firing images with none wrong, or 53 with one, and Open Images' samples have about 50
   held-out positives a label. So that run can refute the bar for any label and can confirm it
   only for one that recalls seven tenths of its positives without a single mistake, which one
   label of the 100 did (`farm animal` ᵖ, 40 and 41 firing images). On COCO the nearest
   label is `people`, 0.909 and 0.907 over some 790 firing images, lower bounds 0.887 and 0.885.
   Whether a label must also reach some recall is not said either; the median label with a floor
   recalls 0.62 (ONNX) and 0.69 (Core ML) on COCO. ⚠ **Still open after L5.**
3. **Owed, still open: a leaf needs a parent that can be expressed.** `vocabularyOf` rejects a leaf without
   one (`LabelVocabulary.kt:115`) and every threshold is required (§27.2). Thirteen of the 29 sit
   under a parent that is not in the set: `food and drink` has no floor on either artifact, and
   `objects` and `activities` have no class behind them on COCO or on Open Images. §3.10.7 ran the
   rule with those parents at a floor of +∞. Whether the first vocabulary does the same, or
   reshapes the hierarchy, is owed. Under L5's 23 the count is seven: `activities` loses all five
   of its members, which were proxies, and `food and drink` (3) and `objects` (4) remain.

**Owed, and not decided** (the list the maintainer gave, with where each stands):

- **Owed in part: labelled data for everything else.** Open Images V7 scores 156 labels, 120 of
  them beyond COCO's (§3.12; images listed CC BY 2.0, annotations CC BY 4.0). Still owed: the 51
  it cannot score (39 with too few verified images, `receipt`, `qr code` and `landscape` among
  them, and 12 with no class); real screenshots and documents, which Flickr's are unlikely to be
  (§3.12.9); a sample large enough to confirm a bar (item 2); and seventeen mapping rows whose
  negatives are unsound, a union of alternatives with no broader class over them, `phone`,
  `football`, `urban` and `food and drink` among them (§3.12.3). §7.7's labelled sample of a
  *library* is the same debt and is untouched.
- ⚖️ **Owed: counsel on the data the floors came from.** 3 475 of the 5 000 COCO images are
  NonCommercial, and §3.10.1 puts a threshold table derived from them before counsel *"before any
  number below becomes a `calibration` file"*. L3 ships such numbers.
- **Owed: which sibling sets are exclusive**, per branch (§37.3). One leaf per set cost `dog`
  recall 0.72 → 0.65 on photographs holding two siblings.
- **Owed: languages other than English.** Every prompt measured was English (§3.10.9).
- **Owed: the firing rate on a real library** (§28). A first look only, under borrowed floors: on
  554 camera photographs COCO's floors fire `people` on about 22 to 24 % and nothing else above
  15 % (§3.11.5), and `indoors` has no floor to fire under on COCO or, by proxy, on Open Images.
  A rate under floors placed for another kind of image is not the rate a shipped table would
  give.
- ⚠⚠ **Owed: validation on a phone.** §3.10 to §3.12 ran on a host. §3.10.6: the same ONNX bytes
  under another onnxruntime differ by as much as the two platforms do, and `onnxruntime-android`
  1.20.0 is the same version with other kernels. Nothing ran on one, and not with the apps' own
  decoders (§3.10.7).
- ⚠⚠ **A floor belongs to the artifact, the runtime, the prompt and the population it was placed
  on.** The first is R4's, the second §3.10.6's, the third §3.11.4's (a floor moves a median of
  0.007 cosine with the prompt). The fourth is §3.12.8's: for the labels scored on both datasets
  Open Images' floor is a median of 0.011 above COCO's, and COCO's floors on Open Images give a
  precision of 0.77 where 0.90 was asked. **No floor has been placed on a personal library, so no
  floor measured so far is a number to ship.** L3 decides which labels are candidates and how
  they are prompted. It adopts no floor.

### 39.4 What is owed, in one list

Before any label is written by a model, in no order:

| # | Owed | From |
|---|---|---|
| 1 | The label stamp is the model's, not the backend's id | §39.1.1 |
| 2 | The row records the artifact, by a field or by a longer stamp: a version bump either way; the migration carries the name rows | §39.1.1 |
| 3 | The label-set version reaches the stamp; who re-labels on a bump | §39.1.2 (1) |
| 4 | The merge rule for two writers that raced, and what removes the losing set | §39.1.2 (2) |
| 5 | What a recalibrated table does to rows already written, and where its version lives | §39.1.2 (3) |
| 6 | The stale sweep, sparing user-authored rows | §39.1.2 (4) |
| 7 | Marker and labels arriving apart | §39.1.2 (5) |
| 8 | A negative stored value round-trips on every store | §39.2 |
| 9 | The write stops clamping and truncating; Photos stops dropping below 0; the KDoc; the column's name | §39.2 |
| 10 | ✅ **Decided 2026-10-14 (L4):** the column stores the raw cosine and the row records its artifact; a reader that compares rows normalises per artifact, and one without the table uses "is labelled" only. What it owes is items 20–22 | §39.2.1, §39.6 |
| 11 | ✅ Floors re-derived under the bare-word prompt; the both-artifacts set is 29 on COCO (`models.md` §3.11.4) | §39.3 |
| 12 | ✅ **Decided 2026-10-14 (L5):** Wilson lower bound ≥ 0.90 held out, both artifacts, bare word; proxies out, 23 candidates, none passing today. Floors placed for target **0.95** (2026-10-15). ⚠ Still open: a recall bar, and parents that cannot fire (§39.3 item 3) | §39.3 (1–3), §39.7 |
| 13 | Labelled data: 156 labels scored on Open Images; still owed are the 51 unscored, real screenshots and documents, a sample large enough to confirm a bar, and seventeen mapping rows with unsound negatives. ⚠⚠ **Under L5 the large-enough sample is the gate for the first vocabulary**: until it exists the vocabulary is empty (§39.7). ◐ That sample is measured for the 23 (`models.md` §3.13.4, 2026-10-15): six pass at the sample's mix, one (`fruit`) at 50/50. ⭐ Decided 2026-10-23: the six are item 19's candidates, and the library check is the gate (§39.7) | §39.3, §39.7 |
| 14 | ⚖️ Counsel on floors derived from NonCommercial images | §39.3 |
| 15 | Sibling-set exclusivity per branch; non-English prompts; the firing rate on a library under its own floors (a first look under borrowed ones: `models.md` §3.11.5) | §39.3 |
| 16 | The evaluation on a phone's vectors, both platforms | §39.3 |
| 17 | §37.1's three layers: a `tagImage` with a body, a backend that claims the capability, the vocabulary | §37.1 |
| 18 | ⚠⚠ The `InferenceService` version bump, and the migration or upcast, for every item above that changes what a synced row means without moving a shape: the baseline test will not ask for it | §39.5 |
| 19 | ⚠⚠ Floors placed on the population they will meet, a personal library, on enough images for L1's per-artifact tables to hold: no floor measured so far is one | §39.1, §39.3 |
| 20 | Every reader that thresholds, orders or picks a maximum across rows normalises per artifact or falls back to "is labelled" plus recency: `groupPhotosByLabel` (`PhotoLabel.kt:88`, `:95`, `:101-104`) first. Name rows stay outside any ordering of model scores | §39.6 |
| 21 | The floor tables reach the readers that compare scores, keyed by artifact, on a device that may hold no model: from where, and how a reader learns of a recalibration | §39.6 |
| 22 | The table a row was selected under: a reader normalising an old row with a later table moves it relative to the new floor, so either the row records its table's version (item 5) or readers accept that drift | §39.6 |

### 39.5 ⚠⚠ What changes on the wire without changing a shape, and what each owes

Several owed items change what a stored, synced value **means** while every field keeps its type
and position. The rule and its guard are both written for shape:

- **House Rule 9** opens *"Changed the stored-row or wire shape?"*, and `SchemaShapeBaseline` pins
  each composite's positional layout and each constant's existence. ⚠⚠ **It cannot see any item
  below**: the enforcement test stays green for all of them.
- **`core-schema-evolution.md`** grounds the rule in positional serialisation (§2, facts 1 and 5),
  and names the other class once, in §5.1: *"Removals, renames, semantic reshapes (the Fit case)
  still need upcasters and floors"*. So a semantic change is inside what the machinery is for, and
  outside what the test catches.
- **Why the bump is owed anyway:** devices on different builds exchange these rows. The service
  version is what the realm epoch fences on (§7.2: bumped by the first write at the new version;
  §7.3: a device below it stops pulling). Without a bump an older build pulls rows it misreads,
  silently. ⚠ §7.3's fence is pull-only: a fenced device may still **push** rows in the old
  meaning, so the newer build must read both, which is the migration or upcast.

⚠ One distinction runs through the table. **No shipped build can label** (§37.1), so no shipped
build *judges* a marker or *writes* a model row. Every shipped build can **read** label rows:
Photos does (§39.2.1). So a change to how rows are judged or stamped is free of old peers only if
it ships in or before the first label-capable build; a change to what a reader sees is not free
even then.

| Owed item (§39.4) | What changes, shape unchanged | What an older build does with it | Bump and lockstep |
|---|---|---|---|
| 1. The stamp is the model's id, not the backend's | The content of `producedBy` on model rows | A label-capable build stamping the backend's id reads the model-id marker as stale and re-labels (§39.1.1) | **Not needed if** it ships in or before the first label-capable build: no shipped build judges a marker. **Owed** if any label-capable build has shipped with the fallback stamp |
| 2. The artifact on the row, as a longer stamp string | The grammar of `producedBy` | Parses it as `null`, reads the photograph as not indexed, re-labels | **Owed** (§39.1.1), with a migration or upcast for old-grammar rows. The seventh-field form is a shape change and owes it by the rule as written |
| 3. The label-set version enters the stamp | The third number of `producedBy` starts to vary | A build on vocabulary *n* reads a marker at *n* + 1 as current and skips, which is correct; a build on *n* + 1 reads every *n* marker as stale and re-labels, which is the open question of §39.1.2 (1) | The stamp's grammar is unchanged, so no bump **for the stamp**. ⚠ Whether a vocabulary revision is itself a service version, so that an older build stops pulling labels it has no vocabulary for, is part of that owed design |
| 5. A table version, wherever it is recorded | In the stamp: its grammar, as item 2. In a new field: a shape change. Recorded nowhere on the row: nothing on the wire | As item 2, or as a reshape | **Owed** in either of the first two forms; follows from the design §39.1.2 (3) owes |
| 9, 10. The column holds a raw cosine in thousandths (L2, L4) | The meaning and range of `confidencePerMille`; same integer field | ⚠⚠ Every shipped Photos reads it as a probability per mille: drops a negative row at `minConfidencePerMille = 0`, seeds its maximum with 0, and sorts on it (`PhotoLabel.kt:88`, `:95`, `:101-104`) | **Owed even for the first label release**, because the misreading builds are the ones already shipped. Renaming the field id at the same time does not replace the bump |
| 10, 20 (L4). Readers compare scores only per artifact | Nothing on the row beyond item 2's artifact; what changes is what a correct reader may do with the integer | ⚠⚠ Every shipped Photos has no artifact and no table: it compares raw cosines across artifacts for its cover and order, and still drops a negative row and seeds its maximum with 0 (`PhotoLabel.kt:88`, `:95`, `:101-104`). The fallback L4 prescribes is not one it can take | **Owed, and the same bump as items 2 and 9**: it is what stops a shipped Photos pulling rows it would misorder. The new reader must still read rows from before the artifact was recorded; with no artifact, "is labelled" only |
| 4, 6. The merge rule for raced writers; the stale sweep | Which rows exist after a merge, and that rows are deleted | An older build keeps the per-row union and never sweeps, so the two builds disagree on a photograph's label set | Depends on the design owed in §39.1.2 (2) and (4). If the rule is a merger registered on the type, it changes how peers converge and is **owed**; that design must say so |

Nothing here is decided beyond L1–L5. It is the test each owed design must pass before it ships:
*would a device on the previous build misread, re-write or re-label a row this one wrote?* If yes,
the bump is owed whether or not a shape moved.

### 39.6 L4 — The column stores the raw cosine, and the row names the artifact that wrote it

**Decided** (2026-10-14; §39.2.1's option i, §39.4 item 10). The label column stores the **raw
cosine** in thousandths (L2), and the row records the **artifact** that wrote it, which §39.4 item 2
already owes. A reader that compares or orders scores across rows normalises each with that row's
artifact and that artifact's floor table. A reader without the table — a device with no model —
uses only *is labelled* and orders by something else, such as recency. Name rows
(`FACE_CLUSTERING`, 1 000) stay outside any ordering of model scores.

**What it rests on.**

- **Measured:** a raw cosine is comparable only within one artifact. The per-label offset between
  ONNX and Core ML runs from −17 to +23 in the column, with per-image noise of about 8 on top
  (§39.2.1; `models.md` §3.10.4–§3.10.5). L1 puts both artifacts' rows in one library.
- **The property chosen:** a raw value never changes meaning when a table is recalibrated. Option
  ii's floor-relative value would; that is §39.1.2 (3) turned into a rewrite of every stored row.
  The cost is moved to the reader instead of the row.
- **iv's fallback, kept for one reader only:** a device with no model has no table, and IN-D5
  syncs labels for exactly that device (§39.2.1, option i's cost). It reads presence, not score.
- **Code:** the only cross-row reader today is `groupPhotosByLabel`, which thresholds, keeps a
  per-photo maximum and sorts on the raw value (§39.2.1, `PhotoLabel.kt:88`, `:95`, `:101-104`).
  Name rows are kept out of that by one `continue` (`:87`); L4 makes that a rule, not an accident.

**What it owes** (§39.4 items 20–22, and item 2 is no longer optional for readers):

- **The artifact must be readable by every reader**, not only by the pass that judges markers.
  Whichever form item 2 takes — a field, or a longer `producedBy` — a reader must be able to
  project it. A row written before the artifact was recorded has none, and is read as *is
  labelled* only.
- **Readers normalise or fall back** (item 20). `groupPhotosByLabel`'s threshold, maximum and sort
  go through a per-artifact normalisation when the reader holds the tables, and otherwise become
  *is labelled* with recency as the order. §39.2's *"Photos drops them"* is subsumed: a reader with
  no table applies no threshold to the raw value at all.
- **The tables reach the readers that compare** (item 21). No floor table ships yet. If one arrives
  with the model it belongs to, a reader that compares rows from the **other** artifact still needs
  that artifact's table, which no device would download. Where they come from, keyed by what, is owed.
- ⚠ **The table a row was selected under** (item 22). The raw value does not move on a
  recalibration, but its distance from the *current* floor does: an old row normalised with a new
  table may land below the new floor while still marked labelled. Either the row records its
  table's version (item 5's open half) or readers accept that drift. **Inferred**, not measured:
  how large it is depends on how far a recalibration moves a floor; §3.11.4's prompt change moved
  them a median of 0.007.
- ⚠⚠ **The bump** (§39.5, the L4 row): a shipped Photos already reads the column and can neither
  normalise nor fall back, so the version bump items 2 and 9 owe is also what fences it.

### 39.7 L5 — "Passed" is a Wilson lower bound of 0.90, held out, on both artifacts; proxies are out

**Decided** (2026-10-14; §39.3 items 1–2, §39.4 item 12). A label **passes** when its held-out
precision has a **Wilson lower bound ≥ 0.90**, on **both** shipped artifacts, under the **bare-word**
prompt (L3). **Proxy labels are out**: the candidate set is **23**, not 29 — §39.3's list without
the six marked ᵖ (`models.md` §3.11.4).

⭐ **Floor target 0.95** (decided 2026-10-15). A label's floor is placed on the calibration half for
**target precision 0.95**, and the bar above is read against that floor; target 0.90 is reported
beside it, never as the verdict. ⚠⚠ **Why:** a floor placed for 0.90 holds about 0.90 held out, so
its lower bound converges on 0.90 *from below* however large the sample — at target 0.90 L5's bar
is unreachable by construction (`models.md` §3.13.2). At 0.95 a label needs some **139 firing
images** per artifact.

**Consequence accepted: the first vocabulary is empty** until a larger labelled sample exists.
Under this bar no COCO label passes and one Open Images label does, `farm animal` ᵖ, which is a
proxy and so out (`models.md` §3.12.6; §39.3 item 2's table).

**What it rests on.**

- **Measured:** a floor placed on the calibration half is not a precision held (§39.3 item 2), and
  a percentile bootstrap cannot be the interval: with no firing image wrong it reports [1.00, 1.00]
  on nine images as on nine hundred (`models.md` §3.12.4, §3.12.6). Wilson's bound does not.
- **Measured:** the nearest COCO label is `people`, lower bounds 0.887 and 0.885 over some 790
  firing images; `truck`, which held the bootstrap's bar on nine, has 0.70 (§3.12.6).
- **Proxies:** *"a proxy's figures describe the proxy"* (`models.md` §3.10.2). `skiing` scored
  against skis says nothing measured about skiing.

**What it owes.**

- ⚠⚠ **The larger labelled sample is the gate for the first vocabulary** (§39.4 item 13). A lower
  bound of 0.90 takes about **35 firing images with none wrong, or 53 with one**, per label and per
  artifact. Open Images' held-out samples hold about 50 positives a label, so a label would have
  to recall seven tenths of them without a mistake; §3.12.6 names `select --per-label 300` as the
  corpus that would do it for the rest, at about three times the images.
- **Still open, and not decided by L5:** whether a label must also reach some **recall** (§39.3
  item 2's last sentence), and **parents that cannot fire** (§39.3 item 3) — seven of the 23 now,
  under `food and drink` and `objects`; `activities` has no member left.
- ⚠ **The bar is on held-out data, not on a library.** L5 decides what *passed* means; it adopts no
  floor. §39.4 item 19 — floors placed on a personal library, on enough images — still stands, and
  a label that passes on a public dataset owes that before its floor ships.

**The larger sample, measured** (`models.md` §3.13.4, 2026-10-15). Open Images V7 for the 23,
up to 1 000 verified positives and 1 000 verified negatives a label (25 996 images), all three
artifacts, bare word, 0.95 floors. ⭐ **Six labels pass as the bar reads at the sample's own mix:
`dog`, `cat`, `bird`, `horse`, `cake`, `fruit`**; 14 are undecided, three refuted for want of a
floor (`furniture`, `truck`, `transport`).

⭐ **Decided 2026-10-23: the sample's own mix picks the candidates; a personal library is the gate.** The
samples run 50–83 % positive, and reweighted to 50/50 only `fruit` keeps a Wilson low above 0.90
(§3.13.4). Neither mix is a library's: there a label's positives are a few percent, which lowers
precision, while Open Images' negatives are hard ones, which lowers it more than ordinary photos
would. So the public-data bar does not decide what ships. **The six that pass at the sample's mix are
the candidates for §39.4 item 19** (floors placed and checked on a personal library, on enough
images), and **that library check is the pass/fail gate** for the first vocabulary. The 14
undecided and three refuted are not candidates until a larger or better sample says otherwise. ⚠
Item 19 has no method or library yet; until it does the vocabulary stays empty and labels stay
inert (§37).
