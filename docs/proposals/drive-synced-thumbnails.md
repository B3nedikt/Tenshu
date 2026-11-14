# Drive — synced thumbnails

**Status:** **Phases 1 + 1b implemented — ⚠⚠ D1 + D2 REVERSED by C7 (2026-08-06), see §11.**
Thumbnails are no longer eagerly-synced stored values: both classes are
`dynamicProperty(Syncable(OnDemand))` on the content plane, because the C6 census measured
them at **97.26 % of every byte a `File` row occupies**. §10.1's "`File.Preview` is the single
preview API" invariant survives; the cold-read contract changed (§11.2).

**Original status:** **Phases 1 + 1b implemented.** Phase 1: `File.Thumbnail` stored value +
generate-on-upload + projection + threat-model amendment. **Phase 1b (§10.1):**
the consumer-side `thumbnail ?: preview` fallback moved into the `GetPreview`
resolver, so `File.Preview` is now the single "best available preview" API and
stored thumbnails are pure background machinery; no wire/protocol/threat-model
change. Verified cross-device by `SyncedThumbnailSystemTest` (a byte-less joiner now
resolves the synced thumbnail *through `File.Preview`*). This generalises to a
**thumbnail-class** model (§10.2–10.4) — two classes (`LIST` + evictable `LARGE`,
Phase 3), then **dynamic, demand-driven** classes with per-class deletion in Control
(Phase 4). **Phase 2 (backfill) is deferred — migration-only (§7); Phase 3 (two
classes) is the active next phase; Q1 is locked — JPEG, ~256 px `LARGE` under a
dedicated cap, format selected via an optional `GetPreview.Params.FORMAT` op param
(default `png`).** Phase 4 outstanding (§7).

**⚠ Latest (2026-09-16):** §17 — measured on a retina Mac, most blurry tiles were not an unpulled rung
but a **declined** one: a detailed frame's `XLARGE` is ~500 KB at q80 and the 256 KB cap refused it for
good. The mint now steps JPEG quality down (D-9) and the cap is 512 KB (D-10), which admits even pure
noise; `MINT_FORMAT` → `v2` re-asks every recorded decline. §17.4 records the direction for a rung
above `XLARGE`.

**⚠ (2026-09-09):** §15 re-motivates Phase 4 as **wanted classes** — a per-device, per-class
declaration the byte holder reads — and folds §12.6's T-2 and T-3 into one design (D-5…D-8). It is
what makes a new rung reach the *existing* library, which M5 of `photos-video-support.md` made
visible: only videos uploaded after M4 carry a clip. **W-1 + W-2 shipped 2026-09-09 (as-built §15.9; device pass §15.10; the two defects it found fixed in §15.11)**; W-3/W-4 open. ⚠ The per-run budget is a guess until a real library has been through it.

**⚠ (2026-08-11):** §12 adds the amendment that matters most for reading anything above it —
the class ladder has a **220× gap** between `LARGE` (256 px, 15 KB) and the original (3.3 MB), which
is why a hero on a byte-less device is a 3–5× upscale. §12 proposes the missing `XLARGE` rung,
corrects §10.3.2, and phases the work T-1…T-4.

**Owner:** Benedikt

**Supersedes / amends:** closes the gap opened (knowingly) by
"reactive list thumbnails" —
see `docs/tech-debt.md` §"File.Preview is now projected". Named as the **enabler**
by [`drive-offline-online.md`](drive-offline-online.md) §4.7. Touches a synced data
shape ⇒ ships with a `docs/sync/threat-model.md` amendment (House Rule #2).

---

## 1. The symptom

Upload a file on device **A** (desktop). It appears in the Files list on device
**B** (phone) almost instantly — the file metadata syncs eagerly. But its
**thumbnail never appears on B**: the row shows the generic category icon, while
on A the same row shows the real image preview. Switching screens on B does not
fix it (that was the *separate*, already-fixed reactivity bug that "reactive list thumbnails" fixed).

## 2. Why — the thumbnail is computed, not stored

`File.Preview` is a **`Property.Dynamic`** (`file-service/.../File.kt:179`): a
chain that, on read, calls `getPreview(width, height)`, which renders a thumbnail
**from the file's local bytes** (`JvmGetPreviewImplementation` /
`AndroidGetPreviewImplementation` → `GetData` → decode → scale → encode).

Device B does not hold the bytes. `File.Data` is `Syncable(SyncStrategy.OnDemand)`
(`File.kt:156`) — the metadata syncs, the bytes are fetched lazily only when the
file is opened. So on B the projected `getPreview` finds no local bytes and
returns `null` → the row falls back to its category icon. "Reactive list thumbnails" was explicit
about this:

> *the projected preview is computed from local bytes, so it appears on devices
> that hold them (e.g. desktop) and resolves to null (icon) on devices that don't.
> Delivering thumbnails to byte-less devices is the follow-up synced-thumbnail
> work this sets up.*

### 2.1. The `Syncable(Eager)` trait on `File.Preview` is inert — don't be fooled

`File.Preview` *carries* `traits = listOf(Syncable(SyncStrategy.Eager))`
(`File.kt:183`), which looks like it should already ship the preview to B. It does
not, and cannot. `SyncObserver.processSavedData`
(`sync-service-proxy/.../SyncObserver.kt:163`) only emits sync blocks for the
**entity** and for **stored values present in `data.properties`**. A dynamic
property is recomputed on every read and is *never written into the entity*, so
nothing ever saves a preview value, so no block is ever generated. The trait is
decorative there. Any future "let's just make the preview sync" instinct that
reaches for the trait is a dead end — **the value has to actually be stored.**

> Guard test worth adding regardless of this proposal: assert that a
> `Property.Dynamic` tagged `Syncable(Eager)` produces **no** block from
> `SyncObserver`, so the inertness is locked in and documented in code.

## 3. The design — a stored, eagerly-synced thumbnail field

Model the thumbnail as a **stored value property** on `File`, generated once on
the device that holds the bytes, and let it ride the machinery that already syncs
`File.Size`.

```kotlin
// file-service/.../File.kt
private val fileThumbnail: Property.Value<TByteArray> =
    valueProperty(id = "${File.ID}/thumbnail", schema = TByteArray.Schema)

/** A small, eagerly-synced preview thumbnail — present on every device once the
 *  file's metadata has synced, even on a device that holds no bytes. Distinct
 *  from the compute-on-read [Preview] (full-res-ish, local-bytes only). */
val File.Companion.Thumbnail: Property.Value<TByteArray> get() = fileThumbnail
```

```kotlin
// file-service-common/.../FileServiceCommonModule.kt
valueProperty(fromId = File.ID, property = File.Thumbnail) { TByteArrayDto(it as ByteArray) }
```

**Why this needs no new transport.** `File.Schema` is **already
`Syncable(SyncStrategy.Eager)`** (`File.kt:48`). Stored value properties such as
`File.Size` / `File.LastModifiedAt` reach peer B today purely by being `set()` on
the entity and riding the entity's eager block — there is no special emitter for
them (unlike the large, OnDemand `File.Data`, which `UploadFileImplementation`
emits explicitly through the two-plane content channel). A small thumbnail is just
another such scalar.

### 3.1. Generate once, on the device that has the bytes

In `UploadFileImplementation.uploadFile` (and `ReplaceFileImplementation`), after
the bytes are persisted and before `saveData(fileEntity)`, render the canonical
thumbnail and set it on the entity:

```kotlin
val thumb = executionContext.execute<TByteArray?>(
    operationId = GetPreview.ID,
    caller = fileEntityOrComposite,
    params = mapOf(
        GetPreview.Params.WIDTH  to CANONICAL_THUMB_PX.toTInteger(),
        GetPreview.Params.HEIGHT to CANONICAL_THUMB_PX.toTInteger(),
    ),
)?.get()

val fileEntity = Entity.create<File>(...) {
    set(File.Size, fileSize)
    set(File.LastModifiedAt, lastModified)
    set(File.CreatedAt, createdAt)
    if (thumb != null && thumb.size <= MAX_THUMB_BYTES) set(File.Thumbnail, thumb)
}
```

- Reuses the existing `GetPreview` render path and its `PreviewCache` — no new
  image code.
- Non-image / un-renderable files (`GetPreview` returns `null`) and over-cap
  thumbnails simply **don't set** the property → B shows the category icon, exactly
  as today. No regression for those files.
- The thumbnail is generated **at one canonical size** (§4 D4), independent of the
  per-row requested dimensions, so only one small payload ever rides the wire.

### 3.2. Project the stored thumbnail; fall back to the live preview

`getFilesPaged` currently projects `relations(File.Preview(100, 100))`. Project
`relations(File.Thumbnail)` as well, and in `FileMapper` (`drive-shared/.../
FileMapper.kt:71`) prefer the stored thumbnail, falling back to the live preview:

```kotlin
val previewBytes =
    file.get(File.Thumbnail)?.getOrNull()      // synced — present on every device
        ?: file.get(File.Preview)?.getOrNull()  // live — only where bytes are local
previewImage = previewBytes?.let(::decodeToImageBitmap),
```

- On B: `File.Thumbnail` resolves (it synced with the entity) → real preview, no
  bytes required. **Bug closed.**
- On A and any byte-holding device: same as today; the fallback also covers files
  uploaded *before* this feature whose thumbnail was never generated (§4 D5).

## 4. Design decisions

- **D1 — Stored value, not a dynamic property.** The entire fix is that the
  thumbnail must be a materialised value the sync observer actually sees. A
  dynamic property cannot sync (§2.1).

- **D2 — Metadata plane, not the content plane.** A canonical thumbnail is a few
  KB, so it rides the ordinary eager entity/metadata block (encrypted under
  `sharedIdentityKey`, like `File.Size`). It must **not** use the two-plane chunk
  channel (`emitFileContent`) — that exists for large, OnDemand `File.Data`. The
  **size cap (D4) is the invariant** that keeps the thumbnail metadata-plane-sized;
  enforce it at generation (don't `set` an over-cap thumbnail).

- **D3 — Generated only on a byte-holding device.** Thumbnailing requires the
  bytes; only the uploading/owning device has them. Byte-less devices *consume*
  the synced thumbnail, never produce it.

- **D4 — One canonical size, decoupled from the requested row size.** Generate a
  single fixed-longest-edge thumbnail regardless of the per-row `(width, height)`,
  so exactly one thumbnail per file rides the wire and sits in every device's
  store, rather than an open-ended set of (w, h) variants. **Phase 1 ships this at
  100 px** (`UploadFileImplementation.THUMBNAIL_PX`) — the list row size — encoded
  as PNG via the existing `GetPreview`, capped at 64 KB (`MAX_THUMBNAIL_BYTES`).
  This is the smallest, lowest-risk cut: zero platform image-code changes and tiny
  payloads. Bumping to a larger canonical size (~256 px) for higher-fidelity
  surfaces (file details) **with** a compact encode (JPEG/WebP, since a 256 px PNG
  photo would blow the cap) is **Phase 3 / Q1** — it mainly benefits surfaces
  Phase 1 doesn't feed, and shrinks payloads.

- **D5 — Lazy backfill for pre-existing files.** Files uploaded before this lands
  have no `File.Thumbnail`. They are handled by the §3.2 fallback wherever bytes
  are local; to reach byte-less devices, a byte-holding device backfills on access
  — when it renders such a file it `set`s `File.Thumbnail` and re-saves the entity,
  which eager-syncs the thumbnail out. No bulk migration job required (Phase 2).

- **D6 — Keep `File.Preview` for the on-open / details surface.** The live,
  compute-from-bytes `Preview` stays for higher-fidelity uses where bytes are (or
  become) local. The synced `Thumbnail` is specifically the small, always-present
  list-tier derivative. Drop the inert `Syncable(Eager)` trait from `File.Preview`
  (or comment it as intentionally inert) so the next reader isn't misled (§2.1).

  > **Revised 2026-06-14 — see §10.** D6 originally kept `Preview` and `Thumbnail`
  > as two properties the *consumer* selects between (the as-built `FileMapper`
  > does `thumbnail ?: preview`). The amendment in §10 reverses that: the
  > fallback policy moves *into* the `GetPreview` resolver, `File.Preview` becomes
  > the single "best available preview" surface, and the stored thumbnail(s) become
  > background machinery the UI never names. D6's intent (preserve high-fidelity
  > where bytes are local) survives — it just lives in the resolver's tier order,
  > not in the caller.

## 5. Interplay with offline/online v3

This is the **enabler** `drive-offline-online.md` §4.7 calls out. Once thumbnails
sync:

- An `ONLINE_ONLY` file (bytes intentionally not kept locally) is still
  **browsable and previewable** — thumbnail + a cloud glyph; tapping fetches the
  bytes on demand. Without synced thumbnails, online-only files are blank rows
  (today's bug, generalised).
- **Eviction (`EvictFileBytes`) must not evict the thumbnail.** Evicting
  `File.Data` is the whole point of online-only; the thumbnail is what keeps the
  file visible afterwards. The thumbnail is a separate, small property and is *not*
  part of the content plane, so the existing byte-eviction path leaves it
  untouched by construction — but add a test pinning that an online-only /
  evicted file still surfaces its thumbnail.
- `drive-offline-online.md` §4 floats a future *large-thumbnail tier* that would
  itself be evictable like `File.Data`. Out of scope here; v1 is the single small
  canonical thumbnail only.

## 6. Threat-model note (House Rule #2)

Synced thumbnails introduce a **new synced data shape derived from file plaintext**
(A5-class): a low-resolution visual of the file content.

- **Relay still sees ciphertext only (House Rule #1).** The thumbnail rides the
  existing encrypted metadata envelope (`sharedIdentityKey` for the personal realm;
  the per-realm shared identity key for a shared realm, via `generateSyncBlock`'s
  existing realm routing). The relay learns nothing it didn't already learn about
  the entity block's size.
- **Privacy delta to call out in the amendment:** a low-res visual of *every*
  thumbnailable file now becomes resident on *every* paired device (encrypted),
  regardless of that device's sync policy — including `ONLINE_ONLY` files whose
  bytes the user chose not to keep there. This is the intended trade (browsability
  without bytes) but is a genuine change in what a compromised *device* (not relay)
  exposes: previously a byte-less device held no visual of an online-only file; now
  it holds a thumbnail. The amendment should state this under the relevant device
  asset and note it follows the same at-rest protection as other synced metadata.
- **Shipped** as an amendment under **A5** in `docs/sync/threat-model.md` (not a
  new asset — the thumbnail is a derivative of A5, protected identically). No
  relay-shape or protocol change, so House Rule #2's hard triggers aren't hit; the
  device-exposure delta is recorded there regardless.

## 7. Phased plan

1. **Phase 1 — close the bug for new uploads. ✅ Implemented.** `File.Thumbnail`
   stored value property + register it + generate-on-upload/replace (one change in
   `UploadFileImplementation.uploadFile`, which `ReplaceFile` delegates to) +
   `getFilesPaged` projection + `FileMapper` prefer-thumbnail-then-preview
   fallback. Removed the inert `File.Preview` `Syncable(Eager)` trait. Threat-model
   amendment (A5). `SyncedThumbnailSystemTest` is the acceptance test. **This alone
   fixes the reported symptom for every file uploaded after it ships.**
2. **Phase 1b — unify on `File.Preview`; move the fallback into the resolver.**
   Push the `thumbnail ?: preview` policy out of `FileMapper`/`getFilesPaged` and
   into the `GetPreview` resolver, so `drive-shared` projects and reads only
   `File.Preview`. Establishes the **thumbnail-class** seam (§10) with exactly one
   class. No new wire shape. See §10.1.
3. **Phase 2 — backfill pre-existing files. Deferred — migration-only.** There is
   no pre-existing file corpus in dev — every file goes through the post-Phase-1
   upload path, so it already carries a thumbnail — and the resolver's null→icon
   fallback already handles absent thumbnails gracefully. This phase is purely a
   migration concern (byte-holding device sets+re-saves `File.Thumbnail` on render
   per D5, eager-syncing it to byte-less devices; no bulk job) and is off the
   critical path. Revisit post-launch only if blank-until-reupload turns out to be
   unacceptable for some real corpus.
4. **Phase 3 — two thumbnail classes. ← active.** Add a second, **large** class (~256 px,
   **JPEG** per the locked Q1) alongside the list-tier class, generated on the
   byte-holding device. The resolver picks the smallest class that satisfies the
   requested `(w, h)`, then computed-from-bytes, then the next-smaller class. The
   large class is the **evictable tier** offline-v3 §4 calls out: it follows its
   own eviction rule, not the file's (cheaper to keep, so keep more). See §10.2.
5. **Phase 4 — dynamic thumbnail classes + Control management.** Stop generating
   the large class eagerly for every file; generate it **on demand**, only for
   files whose previews are *frequently requested large* (a device-local demand
   signal). Surface per-class storage in Control and let power users **delete a
   specific class's thumbnails — or all of a class — per realm / per device**.
   Thumbnail classes become a managed, evictable cache tier rather than
   always-resident derivatives. See §10.3.

Each phase is independently shippable; Phase 1 is the whole user-visible fix and
Phase 1b is the architectural cleanup that unlocks 3 and 4.

## 8. Open questions

- **Q1 — encode format + size cap. Decided (2026-06-14).** The `LARGE` class
  encodes **JPEG** at a canonical **~256 px** longest edge, under a **dedicated cap**
  (`MAX_LARGE_THUMBNAIL_BYTES`, ~48 KB — distinct from `LIST`'s 64 KB PNG cap; both
  ride the eager entity block). A 256 px PNG photo would blow the cap, so PNG is not
  an option for `LARGE`; JPEG with no alpha matches the existing `TYPE_INT_RGB`
  render path on both platforms. Format is chosen via a **new optional**
  `GetPreview.Params.FORMAT` op param (`"png"` / `"jpeg"`), **defaulting to `"png"`**
  so every existing `File.Preview(width, height)` call is byte-for-byte unchanged
  (the 2-param `Preview` arity is preserved — `FORMAT` is *not* a new `File.Preview`
  arity). Only the upload-time `LARGE` render passes `"jpeg"`. `LIST` stays 100 px
  PNG. WebP is noted as a future option only if a cheap cross-platform encoder
  appears (JVM `ImageIO` has no bundled WebP writer; Android has `Bitmap.CompressFormat.WEBP`).
- **Q2 — non-image previews.** v1 = images only (everything else → icon, unchanged).
  PDF-first-page / video-frame thumbnails are a later, per-type render and out of
  scope.
- **Q3 — entity-inline vs. separate property block.** Recommended: set the
  thumbnail on the entity so it rides the existing eager entity block (simplest,
  mirrors `File.Size`). Alternative: emit it as its own eager `emitPropertyBlock`
  (mirrors `File.Data`'s explicit emit) to keep the base entity block lean. Decide
  once the per-file entity-block size impact is measured (Q1 caps it either way).
- **Q4 — shared realms.** The thumbnail should sync within a shared realm under
  that realm's identity key via the same `generateSyncBlock` routing — expected to
  "just work," but add a shared-realm round-trip to the test matrix to confirm.
- **Q5 — regeneration on content change.** `ReplaceFileImplementation` (in-place
  overwrite) must re-generate and re-`set` the thumbnail so a replaced file's
  preview updates on B. Fold into Phase 1.

## 9. Implementation notes — as built (Phase 1)

- **Files touched:** `file-service/.../File.kt` (new `Thumbnail` handle; removed the
  inert `Preview` trait), `file-service-common/.../FileServiceCommonModule.kt`
  (registered `valueProperty` with a `TByteArrayDto` factory),
  `file-service-proxy/.../UploadFileImplementation.kt` (`renderThumbnail` + `set`;
  `ReplaceFile` delegates here, so Q5 is covered by the one change),
  `drive-shared/.../TenshuFilesRepository.kt` (projection) + `FileMapper.kt`
  (prefer-thumbnail fallback), `docs/sync/threat-model.md` (A5 amendment),
  `docs/tech-debt.md` (reconciled the projection note).
- **The load-bearing uncertainty is resolved.** `SyncedThumbnailSystemTest` proves
  a `set`-on-entity `File.Thumbnail` reaches a peer through `File`'s eager entity
  block and is projectable on the receiver — exactly as `File.Size` is, and without
  the explicit-`emitPropertyBlock` fallback (Q3) being needed. The receiver reads it
  through `select`/`relations`, **not** `entity.get` (value properties aren't inline
  on the stored entity — the test waits a poll cycle for the projection to settle).
- **`TByteArray` as a stored value property** (the first one — `File.Data` is
  `TByteArray` but *dynamic*) round-trips fine through `DbEntryMapper` and the wire,
  as the system test demonstrates end-to-end.
- **Best-effort generation is already guarded by the existing suite:** `commonTest`
  wires no `GetPreview` impl, so `UploadFileOperationTest` et al. already exercise
  "upload succeeds with no thumbnail generated" (the `try/catch`-to-null path) and
  stay green — no new test needed for that path.
- **Test coverage rationale.** The single new test is the cross-device acceptance
  test, because single-device unit tests structurally *cannot* catch this (they hold
  the bytes, so `getPreview` is non-null and they pass — which is why the existing
  `HorizontalFileItemPreviewReactivityTest` / `FilesPagingIntegrationTest` were green
  while the phone was blank). Deferred, low-ROI guards (not added, to avoid
  redundancy): a `SyncObserver` dynamic-Eager-emits-nothing guard (the File.kt
  comment documents §2.1 instead) and an offline-v3 evicted-file-still-shows-
  thumbnail test (lands with the Phase 3 offline-v3 integration that owns that path).

## 10. Amendment (2026-06-14) — `File.Preview` is the only preview API; the thumbnail-class model

Phase 1 shipped the fix but left the *consumer* choosing between two properties:
`FileMapper` reads `File.Thumbnail ?: File.Preview` and `getFilesPaged` projects
both. That leaks a background concern — "which derivative do we have, and which is
best?" — into the view layer, and it doesn't generalise: a third derivative (a
larger thumbnail) would mean a third property the UI has to know about and a longer
hand-coded fallback. This amendment moves the selection below the repository and
defines the model the later phases extend.

### 10.1. One surface: `File.Preview` (Phase 1b — ✅ implemented)

`File.Preview` is the single **"best available preview"** property. The tier
order — *computed from local bytes* → *the smallest stored thumbnail class that
covers the request* → *null (icon)* — lives **inside the `GetPreview` resolver**
(`JvmGetPreviewImplementation` / `AndroidGetPreviewImplementation`), not in the
caller.

- **The seam was already there.** `getPreview` returned `null` at the
  no-local-bytes branch (`JvmGetPreviewImplementation.kt:79`); that branch now
  returns the stored thumbnail. The byte-holding path (compute + `PreviewCache`) is
  unchanged, so D6's high-fidelity intent is preserved — the resolver simply
  *degrades* to a synced class when bytes are absent.
- **The resolver self-fetches the stored class — as built.** It cannot read
  `caller.get(File.Thumbnail)`: a stored value property isn't inline on the entity
  composite handed to `GetPreview` (§9 — value properties materialise via
  `select`/`relations`, not `entity.get`). The resolver fetches the value itself, by
  the file's id, through the **existing** `ExecutionContext.resolveProperty` seam —
  no new op, no new wire shape:

  ```kotlin
  val reference = (File.Thumbnail.selector() as StaticPropertyRelation).reference
  val resolved  = executionContext.resolveProperty(file, reference)   // → resolveStaticProperty
  val thumb     = File.Thumbnail.decode(resolved)                     // TByteArray?
  ```

  `resolveProperty` → `ReferenceResolver.resolveStaticProperty` re-fetches the
  entity by its `DataId` (`database.getData(dataId, asEntity = true)`) and reads
  `dataEntity.properties[reference]` — the same path `SelectImplementation` drives
  for a projected `relations(File.Thumbnail)`, identical in spirit to how
  `File.Size` resolves. This is the idiomatic single-stored-value read.
- **`drive-shared` no longer names the storage.** `FileMapper` collapsed to
  `file.get(File.Preview)?.getOrNull()`; `getFilesPaged` projects only
  `File.Preview(100, 100)`. `File.Thumbnail` still exists but is pure background
  machinery — written by generation, read only by the resolver. No `import
  org.tenshu.core.file.Thumbnail` survives in `drive-shared`.
- **No wire/protocol/threat-model change.** Generation/storage/sync of
  `File.Thumbnail` are untouched; only the read path is rerouted (House Rule #2's
  hard triggers aren't hit). `SyncedThumbnailSystemTest`'s joiner now asserts
  *through `File.Preview`* (resolved from the synced thumbnail on a byte-less
  device); `JvmGetPreviewImplementationThumbnailFallbackTest` is the new
  resolver-level guard (stored thumbnail → returned; none → `null`).

**As-built files (Phase 1b):** `JvmGetPreviewImplementation.kt` +
`AndroidGetPreviewImplementation.kt` (no-bytes branch → self-fetch),
`File.kt` (`Thumbnail` KDoc → "background machinery, read only by the resolver"),
`drive-shared/.../FileMapper.kt` + `TenshuFilesRepository.kt` (drop the
`Thumbnail` read/projection + import), `SyncedThumbnailSystemTest.kt` (assert via
`File.Preview`), `JvmGetPreviewImplementationThumbnailFallbackTest.kt` (new).
⚠ The Android impl change mirrors the JVM one over shared APIs but is **not yet
on-device compiled** (project convention — Android SDK absent in the build env).

### 10.2. The thumbnail-class model (Phase 3 — two classes)

A **thumbnail class** is a named preview derivative defined by `(canonical size,
encode format, sync policy, eviction policy)`. v1 has exactly one, implicitly:

| Class  | Size    | Encode | Sync   | Eviction                         |
|--------|---------|--------|--------|----------------------------------|
| `LIST` | 100 px  | PNG    | Eager  | Never (it's what keeps the file visible) |

Phase 3 adds a second:

| Class   | Size    | Encode | Sync   | Eviction                          |
|---------|---------|--------|--------|-----------------------------------|
| `LARGE` | ~256 px | JPEG   | Eager* | **Evictable — its own rule (offline-v3 §4)** |

`LARGE` encode is **JPEG** under a dedicated cap (`MAX_LARGE_THUMBNAIL_BYTES`,
~48 KB), per the locked Q1. (WebP is a future option only if a cheap cross-platform
encoder appears — see Q1.)

- Each class is its own stored value property (`File.Thumbnail` → `File.Thumbnail`
  for `LIST`, a new handle for `LARGE`), all background machinery behind §10.1.
- The resolver's tier order becomes: **smallest class that satisfies the requested
  `(w, h)` → computed-from-bytes → next-smaller class → null.** A 100×100 list row
  is served by `LIST`; the details screen's larger request prefers
  computed-from-bytes where local, else `LARGE`, else upscaled `LIST`.
- `LARGE` is the **evictable large-thumbnail tier** offline-v3 §4 floats: it
  follows its *own* eviction rule (cheaper than `File.Data`, so keep more),
  decoupled from the file's effective sync rule. `LIST` stays non-evictable —
  evicting it would re-blank the row, the bug this whole doc exists to fix.
- \*`LARGE`'s sync policy is itself a knob: eager like `LIST` keeps it always
  present (more wire/at-rest cost), or on-demand defers it until a device needs it.
  Phase 3 can ship `LARGE` eager-and-evictable and let Phase 4 make generation
  itself demand-driven.

### 10.3. Dynamic thumbnail classes + Control management (Phase 4)

Eagerly generating `LARGE` for *every* file is wasteful — most files are never
viewed large. Phase 4 makes classes **demand-driven**:

- **Generate on demand, not on upload.** A class beyond `LIST` is generated for a
  file only when that file's preview is *actually requested at that class's size*,
  and (to avoid one-off churn) frequently enough to be worth materialising — a
  **device-local demand signal**. The byte-holding device generates and stores it;
  it then eager-syncs like any class. Cold files cost nothing beyond `LIST`.
- **Privacy: keep the demand signal device-local.** "How often this file's large
  preview was requested" is an access-pattern signal. It must **not** sync — it
  stays a local heuristic input, never a synced property, so a compromised relay or
  peer device learns nothing about which files a user views. (Threat-model note to
  land with the phase, under the same A5 derivative framing as §6.)
- **Control management surface.** Expose per-class storage footprint (per realm /
  per device) and let power users **delete a specific class's thumbnails, or all of
  a class**, reclaiming space. Deleting `LIST` is allowed but warned (rows revert to
  icons on that device until regenerated/re-synced); deleting `LARGE` is cheap and
  safe (the resolver falls back to `LIST`/computed). This reframes thumbnail classes
  as a **managed, evictable cache tier** rather than permanent file metadata.

#### 10.3.1. Storage model — separate properties (now) vs. a `TMap` (if classes go dynamic)

Phase 3 stores each class as its **own** `Property.Value<TByteArray>`
(`File.Thumbnail` = `LIST`, `File.ThumbnailLarge` = `LARGE`). That is deliberate and
should stay for the fixed, small set of classes, because it is what gives each class
an **independent lifecycle**: a per-class `Syncable` policy (e.g. `LIST` eager,
`LARGE` later on-demand) and **per-class eviction** by simply dropping that property —
the whole point of the Phase 4 evictable tier. With only ~2–3 classes ever
(`LIST` / `LARGE` / a possible `XLARGE`), the "one schema property per class" cost is
negligible.

If Phase 4 ever makes classes **open-ended** (app-declared or dynamically sized
classes keyed by id, rather than a closed enum), a fixed property per class becomes
the wrong tool, and the right shape is a **single map-valued property**:

```kotlin
// ThumbnailClass is a TConstant (the FileCategory pattern), not a free string.
val File.Companion.Thumbnails: Property.Value<TMap<ThumbnailClass, TByteArray>>
```

**Feasibility (confirmed by code trace, 2026-06-16): supported end-to-end, but
unprecedented.** `TMap<K, V>` is already a first-class wire composite
(`core/.../collection/TMap.kt`, backed by `TList<TPair<K,V>>`; `binary-protocol.md`
§2.2). The schema DSL has a `map(...)` field kind and `valueProperty(id, schema)`
accepts any `DataSchema`, so the property is declarable; the local store
(`DbEntryMapper.writeValuesToDb`'s `is Composite` arm) persists composite-valued
properties; and `SyncObserver.processSavedData` emits any `Syncable` property value
through `BinarySerializer` with no scalar-only filter. The `genericData` "no inline
arrays" limit (preference-service) is specific to the self-describing path and does
**not** apply to a typed `map` field. **Caveat:** this would be the *first*
composite-valued **synced** value property in the codebase — `File.FileHistory:
Property.Value<TList<…>>` is no precedent (it is locally re-derived by a maintainer,
not synced). Per the wire-registration house rule, **de-risk with a
`serialize → parse → sync` round-trip test before adopting** (none exists today).

**Why it is not a win yet — the tradeoff.** A single `TMap` bundles every class into
**one property value on one eager block with one `Syncable` trait**, which forfeits
exactly the independence above: classes can no longer carry distinct sync policies,
and evicting one class becomes a **read-modify-write of the whole map** (re-emitting
the block, churning the other classes' bytes) instead of dropping a property. So the
map only pays off once the class set is genuinely dynamic/unbounded; adopt it **with**
a per-class eviction that does the map RMW, not before.

#### 10.3.2. ⚠⚠ Note before building Phase 4 (2026-08-10) — the demand signal cannot reach the generator

> ⚠⚠ **Superseded in part by §12 (2026-08-11).** The verdict on Phase 4 *as specced* stands, but
> two of the three objections below are objections to demand-driven **generation** and do not apply
> to a class generated eagerly at upload. Read §12.2 before citing this section.

Opened by Timeline-on-desktop rendering every photo blurred. Read this before scheduling any of
§10.3; two of its premises no longer hold, and one of them is structural.

**1. The generation half of Phase 4's motivation is already spent.** §10.3 opens with "eagerly
generating `LARGE` for *every* file is wasteful — most files are never viewed large", and makes
generation demand-driven to fix it. But **C7 (§11) already made *distribution* demand-driven**: a
device now fetches a thumbnail class when something asks, and receives none it never displays. What
is left of the original saving is the generating device's own CPU + local bytes at upload — real,
but far smaller than the join-cost argument that motivated the phase.

**2. ⚠⚠ The demanding device and the byte-holding device are usually not the same one, and §10.3's
privacy rule is what makes that unbridgeable.** §10.3 says "the byte-holding device generates and
stores it", which silently assumes one device both wants the preview and holds the file. With
`File.Data` as `Syncable(OnDemand)`, that is the *uncommon* case: the phone that took the photo
holds the bytes, and the desktop is the one asking for a hero. A device-local demand signal on the
desktop cannot cause the phone to generate anything — and making it cross-device means **syncing an
access-pattern signal**, which §10.3 rules out for a good reason ("a compromised relay or peer
device learns nothing about which files a user views"). So Phase 4 as written cannot serve the case
that actually motivates a new class.

**3. ⚠ There is no regeneration path at all.** `renderThumbnail` has exactly one call site,
`UploadFileImplementation` — both classes are generated at upload, on the uploading device, and
never again. Any new class would therefore apply to future uploads only, leaving every existing
photo at 256 px. A "generate class X for this file now" operation does not exist and would be a
prerequisite.

**What the observed defect actually is.** Both classes are list-and-card sized (`LIST` 100 px,
`LARGE` 256 px), so on a device without local bytes, `GetPreview` falls to `storedThumbnail(…)` and
**256 px is the ceiling for any request, at any size**. A desktop hero band is ~840–1440 px, i.e. a
3–5× upscale; a second phone would show the same thing. That is a missing *rung on the size ladder*,
not a missing demand heuristic — and it is invisible on the device that took the photos, which is
why it read as a desktop-rendering bug.

**Direction taken instead** (see `timeline-photos.md`): the consumer fetches the original for the
one surface that needs a large preview, via the two-step `relations(File.Data)` → `File.Preview`
that `TenshuFilesRepository.getRemoteFile` already uses, and computes a sharp preview locally. This
needs no schema change, no synced demand signal, works for photos that already exist, and leaves the
device holding bytes it can serve every later size from. ⚠ The two-step is **forced**, not a style
choice: §11.2 records that the dynamic-property path is synchronous, so `GetPreview` cannot await a
fetch and the class policy in the resolver cannot be extended to "fetch if no class satisfies".

If Phase 4 is revisited, it should be re-motivated from scratch — the strongest remaining argument
is the §10.3 **Control management surface** (per-class footprint + per-class deletion), which is
independent of both problems above and which C7 *sharpened* rather than weakened (§11.3).

### 10.4. Threat-model trajectory

Each new class is another plaintext-derived synced shape and rides the existing
A5-derivative treatment (§6): encrypted under the realm identity key, relay sees
only ciphertext size. The deltas to record as the phases land: (a) `LARGE` raises
the at-rest visual fidelity a compromised *device* exposes (a sharper low-res image
than `LIST`); (b) Phase 4's demand signal is a new local-only access-pattern store
that must stay unsynced (§10.3). No relay-shape or protocol change in any phase, so
House Rule #2's hard triggers stay unhit; the device-exposure deltas are recorded
under A5 regardless.

---

## 11. Amendment (2026-08-06) — ⚠⚠ D1 and D2 are REVERSED: thumbnails moved to the content plane

**C7 of [`sync-device-catch-up.md`](sync-device-catch-up.md) (§11.3) moves both thumbnail
classes from eagerly-synced stored values to `dynamicProperty(Syncable(OnDemand))` with
their bytes on the content plane.** That is the direct opposite of **D1** ("stored value,
not a dynamic property") and **D2** ("metadata plane, not the content plane"), so it is
recorded here rather than left as a silent reversal — §5.6 of the catch-up note required
this decision be re-opened explicitly.

### 11.1 What changed the answer: a measurement

D1/D2 were reasoned from *"a canonical thumbnail is a few KB"* against a size cap. The cap
held — but the conclusion did not survive contact with the aggregate. The **C6 census**
(`sync-device-catch-up.md` §11.2) decoded a real store per property:

| property | rows present | bytes | share of a `File` row |
|---|---:|---:|---:|
| `tenshu/file/thumbnailLarge` | 24 | 263,307 | **57.99 %** |
| `tenshu/file/thumbnail` | 24 | 178,313 | **39.27 %** |
| everything else + entry header | 37 | 12,443 | 2.74 % |

> **97.26 % of every byte a `File` row occupies is thumbnail.** ~18.4 KB against ~336 B of
> actual metadata.

Each one *is* only a few KB, exactly as D2 assumed. The flaw was reasoning per-file about a
cost paid per-file-per-device-per-join: a joining device must receive all of it before it is
caught up, ~12.9 GB at the 700 k-file scale §2.4 of the catch-up note works. D2's "it must
not use the two-plane chunk channel — that exists for large, OnDemand `File.Data`" was
right about the *channel's* purpose and wrong about which side of the line thumbnails fall.

⚠ **D1's premise was not wrong, it expired.** *"A dynamic property cannot sync (§2.1)"* was
true when written; `Syncable(OnDemand)` on a dynamic property is precisely the shape
`Workout.Streams` shipped later (`fit-workout-streams-content-plane.md`), and C7 is that
same move.

### 11.2 What this costs, stated plainly

**Join cost traded for first-view latency.** A device no longer receives thumbnails it may
never display; it fetches one when something asks. ⚠ On a byte-less device that means the
first look at a folder resolves previews to `null` and fills in as blobs arrive — accepted
deliberately (the metadata renders immediately, and a re-visit is warm).

⚠⚠ **A contract changed, and `SyncedThumbnailSystemTest` now pins the new one.** Projecting
`File.Preview` alone on a **cold** device resolves to `null` *forever*: `GetPreview` reads
the blob locally, and the dynamic-property path it is reached through is **synchronous**, so
it cannot await a fetch. The fetch happens only at a **suspend terminal** materialising the
markers a projection left behind. So a caller must project the OnDemand properties first
(`relations(File.Thumbnail, File.ThumbnailLarge)` through a `Maybe.get()`) and then read the
preview — two reads, because within one select `File.Preview` resolves before the terminal
materialises anything. `TenshuFilesRepository.getPreview` does exactly this on its cold path.

⚠ §10.1's invariant **survives**: `File.Preview` is still the single "best available preview"
API and the class policy still lives in `GetPreview`. The caching step only fills the local cache and
re-asks; it does not duplicate "which class satisfies this size".

### 11.3 What stays true

- **§3.1 generate-once-on-the-device-that-has-the-bytes** — unchanged.
- **The size caps (D4)** — unchanged, and still enforced at generation.
- **§10.2's two classes** — unchanged, and C7 *sharpens* §4/Phase 4's evictability goal: the
  classes now resolve to genuinely separate content-plane blobs under separate derived
  content ids, so dropping `LARGE` really does leave `LIST` untouched.
- **Threat model** — no new delta. Thumbnail bytes were already sealed under realm keys; they
  move from the metadata block to CEK-sealed content chunks, which is the same posture the
  file's own bytes already have. §10.4's recorded device-exposure deltas stand.

### 11.4 ⚠ As-built notes worth carrying

- **Per-property `contentId` is mandatory.** `manifestContentRef(contentId)` keys on the
  content id **alone** and a `File` has exactly one, so `Data`, `Thumbnail` and
  `ThumbnailLarge` would otherwise PUT their manifests to the same relay ref and overwrite
  each other. `FileBytesPropertyWriter.resolveContentId` derives a per-property id instead —
  no change to that shipped ref derivation (violating its `REF_PATTERN` is the content-addressing
  Phase 1 regression), and the chunk AAD is domain-separated for free.
- **`resolveContentHash` must return `null` for a thumbnail.** `File.ContentHash` anchors the
  *file's* bytes; stamping it on a thumbnail manifest would make the integrity gate reject
  every thumbnail fetched.
- **No `emitPropertyBlock` fallback** on the thumbnail emit path, unlike `File.Data`: that
  block is the eager entity payload C7 exists to remove.
- **HR #9 — `FileService` is v2 with an empty migration.** ⚠ `SchemaShapeBaselineEnforcementTest`
  reports **0 changed shapes**: a value property lives in the entry's string-keyed property
  map, not the composite's positional field list, so nothing moved positionally. The bump is
  owed for the *sync-semantics* change, which is what fences a stale peer that still expects
  eager thumbnails. ⚠ Rows written before the bump keep their now-unread thumbnail bytes —
  dead weight the migration DSL cannot address (it maps *fields*, and these never were).

---

## 12. Amendment (2026-08-11) — the missing rung: an `XLARGE` class, and who mints it

Opened by Timeline-on-desktop still rendering blurred photos *after* the hero fix
changed its hero to fetch the file's original bytes. The hero is sharp now. The feed is not — and
the reason that fix felt unsatisfying turns out to be structural rather than incidental.

### 12.1 The measurement that reframes it

Derivatives of one real photo already in a desktop store (2304 × 4096, phone camera):

| rung | size | vs original |
|---|---:|---:|
| stored `LIST` (100 px) | ~4 KB | 800× cheaper |
| stored `LARGE` (256 px) | **15 KB** | 220× cheaper |
| *— nothing exists between here —* | | |
| (1024 px JPEG q80) | 139 KB | 23× cheaper |
| (1600 px JPEG q80) | 295 KB | 11× cheaper |
| original | **3 344 KB** | — |

**The ladder has a 220× gap in it, and the hero fix made the hero jump the whole gap.** Serving a
840–1680 px hero from a 256 px class is a 3–5× upscale; the only alternative the resolver had was
the entire file.

⚠⚠ **The decisive property is that a derivative's weight is set by its own pixel dimensions, not by
the source's.** A 45 MP DSLR JPEG (~20 MB) and this 3 MB phone photo have the *same* ~139 KB
1024 px derivative. Every mechanism that scales with the **source** — fetching the original, and any
scheduler that merely reorders such fetches — degrades without bound as cameras get bigger. Every
mechanism that scales with the **derivative** does not. That, and not politeness about when to
download, is what makes a class the right answer.

### 12.2 Correcting §10.3.2 — it was two-thirds wrong

§10.3.2 (written the day before this) argued that the designed thumbnail-class work cannot serve
this case. Its verdict on **Phase 4 *as specced*** stands. Its implied conclusion — that classes
cannot help — does not, and the error is worth naming so it is not repeated:

- *"The demand signal cannot reach the byte-holding device"* and *"a cross-device signal would leak
  access patterns"* are both objections to **demand-driven generation**. They evaporate entirely if
  the class is generated **eagerly at upload**, exactly as `LIST` and `LARGE` already are. There is
  then no signal to route and none to leak.
- Only the third objection survives: **nothing regenerates thumbnails after upload**
  (`renderThumbnail` has one call site, in `UploadFileImplementation`), so a new class reaches only
  future uploads. §12.6's T-2 and T-3 close that, by two different routes.

### 12.3 The shape: the original-fetch is the ladder's floor, not a rival to it

`GetPreview` has always implemented a ladder — *smallest stored class that satisfies the request →
else compute from local bytes → else null*. The hero fix did not add a competing mechanism; it made **rung
3 reachable from a device that holds no bytes**. That was worth doing and should stay: it is what
makes the ladder total rather than lucky, and it is the only rung that can serve a request no class
anticipated.

What is missing is the **middle rung**. Adding it is not a retreat from the hero fix; it is what stops the
floor from being the common case.

### 12.4 ⚠ Who does the work — the **proxy**, not the app

Every part of this is proxy-side and always has been: generation at upload
(`UploadFileImplementation`), the OnDemand fetch, and `GetPreview` itself. An app never performs
image work and never needs to.

- On **Android** that is Control's `ProxyService`; on **desktop**, the packaged Control daemon
  behind the tray. A client app only ever asks.
- ⚠⚠ **iOS route-1 is the exception, and "Control does it" is false there.** There is no separate
  Control process: each first-party app hosts its own in-process proxy (`FirstPartyLocalRuntime`),
  so the work happens inside the asking app. The portable statement is *"the proxy does it"* — which
  on two platforms out of three is Control.

⚠⚠ **A defect in what the hero fix shipped, which this reframing exposes.** The client triggers the fetch
with `select { relations(File.Data) }`, so the materialised original is serialised **back across
IPC** to a caller that immediately discards it — on Android via the out-of-band byte carrier. That
is ~3.3 MB of Binder traffic to draw a 220.dp band, and it grows with the camera. Minting must be an
operation the client **requests** and the proxy performs end-to-end, with the original never
crossing the IPC boundary. T-2 fixes this as a side effect of doing the right thing.

### 12.5 Decisions

- **D-1 — eager at upload, not demand-driven.** No signal to route, no access-pattern exposure, and
  §10.3.2's blockers never arise. The cost is one extra encode per image upload, paid by the device
  that already has the bytes open.
- **D-2 — ⚠ a class *below* the largest hero requires an explicit resolver tolerance.** The rule is
  *smallest class that **satisfies** the request*, so a 1024 px `XLARGE` does **not** satisfy a
  1680 px retina hero and the resolver would fall straight through to the original — reintroducing
  precisely the cost the class exists to remove. Two ways out: size the class at 1600 px (295 KB) so
  it covers the largest hero outright, or state the trade as a rule — *never fetch a whole file to
  satisfy a request a stored class covers to within ~1.5×*. **Recommend the rule plus a 1024 px
  class**: it keeps the common case at 139 KB and makes the fidelity-vs-bandwidth trade explicit
  rather than an artefact of a number chosen to dodge a threshold.
- **D-3 — JPEG, under its own cap.** Same reasoning as Q1 for `LARGE`. Measured 139 KB at 1024 px
  q80, so a `MAX_XLARGE_THUMBNAIL_BYTES` around 256 KB leaves headroom without admitting a blob that
  defeats the point. (`LARGE`'s cap is 48 KB.)
- **D-4 — the original-fetch floor stays.** A request no class satisfies must still resolve, and
  after T-2 that path is also what *creates* the missing class.

### 12.6 Phases — each ships independently

- **T-1 — the class, generated at upload.** `File.ThumbnailXLarge` as
  `dynamicProperty(Syncable(OnDemand))` on the content plane (the C7 shape), rendered beside the
  existing two in `UploadFileImplementation`, plus D-2's resolver tolerance in all three
  `GetPreview` actuals. Fixes **every photo uploaded from now on**, on every device, at 139 KB.
- **T-2 — a proxy-side mint, and rung 3 becomes generative.** ⚠ Reshaped by §15 (D-7): the
  fallback half of one design, not a rival to T-3. An op the client can request
  ("ensure a preview class for this file") that fetches, renders, stores and publishes entirely
  proxy-side. Then a device that *does* fall through to the original mints `XLARGE` and publishes
  it, so **the realm pays the original-fetch cost once, ever, for a photo anyone actually looks
  at** — demand-driven generation with no demand signal travelling, because the demanding device
  makes itself the byte-holder. Also removes §12.4's IPC waste.
- **T-3 — backfill on the byte-holding device.** ⚠ Reshaped by §15 (D-6): keyed on the realm's
  *wanted* classes, which is what tells the byte holder there is someone to mint for. The phone that uploaded a photo still holds the
  original, so it can mint `XLARGE` for its own history as idle+charging `PlatformScheduler`
  maintenance. This is §7's deferred Phase 2 backfill, now with a reason to exist. ⚠ Must be
  maintenance, never correctness-bearing: nothing may depend on it having run.
- **T-4 — attention-based scheduling (optional, probably unnecessary).** Draw the cheap class
  immediately; upgrade a frame that survives a dwell. ⚠ Worth stating plainly that this is a
  *scheduling* policy and not a fidelity mechanism: it reorders spending without reducing it, and at
  DSLR sizes it merely staggers the same hundreds of megabytes. Consider it only if a 139 KB
  per-photo feed still misbehaves — i.e. fix the mechanism first, then measure.

### 12.7 Costs, risks and the rules this touches

- **House Rule #9.** A new synced property on `File` ⇒ bump `FileService` **v3 → v4** and register
  `schemaMigration(FileService.ID, fromVersion = 3) {}`. ⚠ Expect
  `SchemaShapeBaselineEnforcementTest` to report **0 changed shapes** — a value/dynamic property
  lives in the entry's string-keyed property map, not the composite's positional field list
  (§11.4). The bump is owed for the **sync-semantics** change, not a positional one.
- ⚠ **Per-property `contentId` is mandatory** (§11.4) or `Data`, `Thumbnail`, `ThumbnailLarge` and
  the new class all PUT their manifests to the same relay ref and overwrite each other.
- ⚠ `resolveContentHash` must return `null` for the new class, exactly as for the others — stamping
  the file's own `ContentHash` on a derivative's manifest makes the integrity gate reject it.
- **Upload cost.** A third encode per image upload on the uploading device.
- **Tier-3 cache budget.** Another OnDemand class competing for the local ceiling
  (`retention-quota-model.md`). Re-fetchable by construction, so this is bandwidth and cache
  pressure, never data loss.
- **Threat model.** Another plaintext-derived synced shape, riding the existing A5 derivative
  treatment (§6/§10.4). ⚠ A *sharper* derivative raises what a compromised device exposes at rest
  more than `LARGE` does; record that delta with T-1.


## 13. Pointer (2026-09-04) — a fourth rung, `MOTION`, is proposed elsewhere

[`photos-video-support.md`](photos-video-support.md) proposes **`MOTION`** — a ~3 s, 256 px, capped
H.264 clip — as a fourth rung on this ladder, to give the Photos grid the looping video tile Google
Photos has. It reuses `GetStoredThumbnail` (`CLASS_MOTION`) and the `OnDemand` content-plane
delivery every class above uses; §12.1's argument (*a derivative's weight is set by its own
dimensions, not the source's*) is what makes it the right answer instead of streaming the original.

⚠ Two things there that this doc's readers should know: the rung is deliberately **outside
`GetPreview`'s ladder** (a clip is not a bitmap, and `GetPreview` is on every list row's path), and
it inherits §12.2's backfill limitation — `renderThumbnail` has one call site, so it reaches only
future uploads.

---

## 14. Amendment (2026-09-06) — ⚠⚠ T-1 shipped the rung and nothing ever asked for it

T-1 (§12.6) claims it *"fixes every photo uploaded from now on, **on every device**"*. The first
half was true on 2026-08-11. The second half was never true, and stayed untrue for the better part
of a month: `XLARGE` was minted at upload, written to the content plane under its own content id,
and read back by all three `GetPreview` actuals — but **no client ever pulled it**, so no device
except the one that uploaded a photo could hold it.

### 14.1 Why the gap was invisible

Every rung is `Syncable(OnDemand)` since C7, so a rung reaches a device only when something names it
in a `relations(...)` projection. `GetPreview` cannot be that something: it resolves classes through
`resolveStoredClass`, which is *"whatever is local, never a fetch"* — a proxy-side resolver cannot
fetch an `OnDemand` property at all. The ask therefore has to come from the client, and at T-1 there
were five places in the repository that pull thumbnail blobs. All five named exactly two rungs:

| Site | Pulled |
|---|---|
| `TenshuPhotosRepository.cacheThumbnails` | `Thumbnail`, `ThumbnailLarge` |
| `TenshuFilesRepository.cacheThumbnails` | `Thumbnail`, `ThumbnailLarge` |
| `TimelineProjectionRepository.cachePhotoThumbnails` | `Thumbnail`, `ThumbnailLarge` |
| `tenshu-sdk-js` `cacheThumbnailsJs` | `Thumbnail`, `ThumbnailLarge` |
| `SyncedThumbnailSystemTest` | `Thumbnail`, `ThumbnailLarge` |

The last row is why it survived review. The acceptance test for this whole document pulled the same
two rungs as the code it was testing, and asserted at **100 px and 480 px** — both of which the
`LARGE` rung answers correctly. A test that never asks a hero-sized question cannot notice that
hero-sized questions are answered by the 256 px class.

⚠ **The user-visible symptom was Photos on desktop, and it read as a rendering fault rather than a
sync one.** Chrome sharp, photographs blurry, on both the grid and the viewer — a full-bleed
1024 px request served by a 256 px class is an ~8-11x upscale on a retina panel. Nothing about it
pointed at an unpulled property.

### 14.2 What changed

- **`TenshuPhotosRepository.cacheThumbnails` takes a `requestedMaxEdge`** and pulls `XLARGE` only
  above `GetStoredThumbnail.LARGE_PX`. ⚠⚠ The gate is not caution, it is the fix's other half: the
  hero rung is ~139 KB against `LARGE`'s ~15 KB, so a feed of 256 px tiles that pulled it would pay
  9x the bytes for a difference no tile can render, and would hold ~57 entries in `PreviewCache`'s
  8 MB budget where it holds hundreds. ⚠⚠ **Superseded by §16:** the pull only ran on a null read,
  which the grid made unreachable, and *"no tile can render"* was measured in dp — a phone tile is
  ~400 px.
- **D-2's tolerance is now bounded below as well as above** in all three `GetPreview` actuals.
  `coveringStoredThumbnail` answered *every* request above the `LIST` rung with `XLARGE`; it now
  applies the ordinary *smallest class that covers* rule first, and reaches for the tolerance only
  in the band `LARGE` genuinely cannot cover. ⚠ This was latent, not harmless — it could not bite
  while nothing pulled `XLARGE`, so fixing the pull without fixing this would have traded a blurry
  viewer for a 9x heavier feed.
- **The ladder's px values are one spelling now** — `GetStoredThumbnail.LIST_PX` / `LARGE_PX` /
  `XLARGE_PX`. They were private to `UploadFileImplementation` and re-declared in each actual, which
  was tolerable while only the proxy reasoned about size; a client that must pull a rung by name has
  to know what each rung covers.
- **`SyncedThumbnailSystemTest` now asks a hero-sized question** and asserts on **decoded
  dimensions**. ⚠ Presence was never enough: both candidate rungs are non-empty JPEGs, so non-null,
  non-empty and the `FF D8` magic all pass just as happily on the blurry answer.
- **Timeline's `fetchOriginal` became `heroFidelity`** and stopped fetching whole files — §14.4,
  which is where the interesting half of this amendment ended up.

### 14.3 What was deliberately left alone

- **Drive** (`TenshuFilesRepository`) — every one of its preview requests is 100x100 (`HomeScreen`,
  `FilesScreen`, `FolderScreen` list rows). It has no surface above the `LIST` rung, so pulling the
  hero rung there would be pure cost. Revisit if Drive grows a full-screen viewer.
- **`tenshu-sdk-js`** — the web path does not use `GetPreview` at all; it picks between `LIST` and
  `LARGE` client-side (§1.2i of `drive-web-parity.md` tracks unifying the four selectors). Pulling a
  rung its own selector cannot choose would be bytes with no consumer.

**Timeline was not left alone** — it turned out to be the sharpest consequence of the fix, and §14.4
is its own section.

### 14.4 Timeline: the workaround this rung was invented to remove

`TimelineProjectionRepository.photoBytes` carried a `fetchOriginal` flag whose entire purpose was to
put the **whole original** on the device so `GetPreview` would compute from bytes *"instead of
serving the 256 px stored class"*. Its own interface KDoc justified it from a premise this amendment
falsifies:

> the largest class is **256 px**… The class ladder has **no rung above 256 px**, nothing regenerates
> thumbnails after upload

That was true when written and is not any more. Worse, leaving it would have made Timeline the one
place the fix actively **hurt**: the hero asks for 1600 px (`MomentDetailViewModel.COVER_EDGE`),
which is inside D-2's 2× tolerance, so once Photos pulled `XLARGE` for a shared file the moment
detail would be served the 1024 px rung **after** paying the multi-megabyte fetch — worst of both.

**What shipped: the flag stayed and its meaning changed.** `fetchOriginal` → `heroFidelity`, which
now pulls the `XLARGE` rung instead of the file. ~139 KB where it was megabytes; the hero is a 1.56×
upscale of a rung rather than a true render, which is D-2's trade taken deliberately.

⚠⚠ **The flag could not be replaced by a size threshold, and the near-miss is worth recording.** The
obvious simplification — derive "is this a hero" from the requested size, as Photos does — is wrong
here: `previewRequestPx` buckets a frame's measured box to 256/512/768/…, so an ordinary Timeline
feed row on a desktop asks for **512**, comfortably above `LARGE_PX`. A size gate would therefore
have pulled ~139 KB for every row a scroll passes — the precise trap `timeline-photos.md` §5 warns
about, reintroduced while removing a different one. Photos could use a size gate because its grid asked
for exactly `TILE_PX`; Timeline cannot, because its frames measure themselves. (⚠ Photos' tiles
measure themselves too since §16, and a large one deliberately takes the hero rung.) **Only the caller
knows whether it is one deliberately-opened photo.**

The two-loader split (`PhotoByteLoaders.cached` / `.hero`) survives for the same reason, renamed
from `.original`. The gap it guards narrowed by an order of magnitude but did not close.

### 14.5 The rule worth carrying

**A rung that nothing pulls is a rung that does not exist.** Minting a class, storing it, and
teaching the resolver to read it are three quarters of a feature; on an `OnDemand` property the
fourth quarter is a client naming it, and it is the only quarter with no compiler or test pressure
behind it. When adding a rung, the acceptance test must ask a question **only that rung can answer**
— and assert on something that distinguishes the rungs, which for images means dimensions, never
presence.

---

## 15. Amendment (2026-09-09) — wanted classes: Phase 4 re-motivated, and T-2 + T-3 become one design

**Decided with the maintainer, 2026-09-09.** M5 of [`photos-video-support.md`](photos-video-support.md)
made §10.3.2's third objection visible in the most literal way: the grid's rotating player only ever
uses a tile whose `MOTION` clip is in hand, and on the maintainer's own library the only videos that
carry one are the seven uploaded during M4's device pass. A rung reaches future uploads only; the
feature is correct and almost invisible. The same is true of `XLARGE` for every photo uploaded
before T-1, which §14 reached by fetching originals instead.

The question was whether to backfill on the byte holder (T-3), mint on demand on the device that
wants the class (T-2), or — the maintainer's framing — **both, with a rule for which**: *if a device
in the realm renders the class, mint it on the byte holder, because someone will need it anyway;
if none does, the device that first needs it mints it itself.* That rule is what this section
records, because it also answers a question none of the earlier sections asked: how the **next**
rung — an `XXLARGE` for high-density displays, an `XSLARGE` for a watch — reaches a library that
already exists.

### 15.1 What changed since §10.3.2 — the objection was to the *signal*, not to demand

§10.3's Phase 4 died on its own privacy rule: the demand signal it wanted was **per file** ("this
file's large preview was requested N times"), which is an access pattern, and §10.3.2 showed the
demanding device is usually not the byte holder, so the signal would have had to sync. Both halves
of that verdict stand.

⭐ **A per-device, per-class declaration is not that signal.** *"This device renders `XLARGE`"* or
*"this device plays `MOTION`"* says nothing about any file. It is device-capability metadata of the
kind the realm already carries about every device (its name, its platform — ADRs 0006/0007), and it
is readable by the byte holder for free, as any `DeviceSetting` row is. So:

- objection 1 (the saving is mostly spent) — unchanged, and irrelevant here: this is about reach,
  not about saving the uploading device's CPU;
- objection 2 (the demand cannot reach the byte holder without leaking access patterns) — **does not
  apply** to a class-level declaration;
- objection 3 (nothing regenerates after upload) — is exactly what the pass below closes.

### 15.2 D-5 — the wanted set

**`thumbnails.wantedClasses`** — a **DEVICE**-scoped `Preference` (`StringListAdapter`) whose value
is the set of class ids from `GetStoredThumbnail.CLASS_*` this device's apps render. Written by the
consuming device's proxy, at class granularity, **never** a file id and **never** a count.

- ⚠ **Who writes it, and when.** The proxy learns a class is wanted the first time a client on this
  device names the rung — `cacheThumbnails` pulling `File.ThumbnailXLarge`, the rotation reading
  `File.ThumbnailMotion` — which is §14.5's own observation turned into a record: *a rung nothing
  pulls does not exist*, so the pull is the declaration. An app may also declare at install; the
  proxy unions the two. A device that never wrote the row wants nothing beyond the defaults.
- **`wanted(realm)`** = the union over every `DeviceSetting` row whose `preferenceId` is
  `thumbnails.wantedClasses`, read by a bounded `scanRowsOfType` (the CR-4e primitive) — ⚠ no device
  directory is consulted, and a device that left the realm simply stops being counted when its rows
  are tombstoned.
- **`LIST` and `LARGE` are wanted everywhere, implicitly.** Every list row renders them; they are
  minted at upload unconditionally, as today, and are never in the set.

### 15.3 D-6 — T-3, keyed on `wanted − present`, on the byte holder

The pass, per device, over the files it holds bytes for:

```
for each File row (schema-driven, bounded scan):
    if !HasLocalData(file)                          → skip (nothing to mint from)
    for each class in wanted(realm) − present(file):
        if category cannot carry the class          → skip (MOTION is Video-only)
        if this platform cannot mint the class      → skip (MOTION: Android only, §4.4 of the video doc)
        ensureWithinQuota                           → stop the run
        mint (the upload's own render) → write → emitFileContent (the upload's own emit)
```

- ⚠⚠ **The upload's emit path, never a bespoke one.** Since CR-4 every emit declares its content
  edge inline (`SyncObserver.declareContentEdges`), so a class minted through `emitFileContent` is
  declared by construction and the reclamation sweep can name it. A pass that wrote the blob and
  emitted its own way would mint exactly the undeclared population §9.7 of
  `content-plane-refcount.md` exists to unstrand.
- `present(file)` is a local fact: `thumbnailPath(contentId, class)` has bytes in the
  `FileDatabase`. `HasLocalData` is the existing op; the render is `GetPreview` with
  `GENERATE_FRESH` for a still and the `MotionThumbnailRenderer` seam for a clip — the mint code
  gains no second implementation.
- **Schedule:** a `HeadlessSafe` op on an **idle + charging + unmetered** `ScheduleItem`, registered
  beside `registerContentBackfillSchedule` and with the same in-process caveat (`ScheduleDrainLoop`
  does not enforce constraints on desktop). Unmetered because it uploads: ~139 KB per photo for
  `XLARGE`, ~90 KB per video for `MOTION`, fixed-size by §12.5 of the video doc.
- **Marker:** per device, through the same device-setting seam as `ProxyBackfillMarker` — but the
  value is the **hash of the wanted set** the last *clean* run completed for, not a boolean. A new
  device declaring a class, or a new class existing, changes the hash and re-arms the pass; a
  partial run (quota, budget, an unmintable file) never latches, so it resumes next tick.
- **Budget per run**, in files and in bytes, so a library of thousands is drained across ticks
  rather than in one; the numbers are W-2's to measure, not this section's to guess.
- ⚠⚠ **Never correctness-bearing.** §12.6's rule for T-3 stands word for word: nothing may depend
  on the pass having run. The resolver keeps every fall-through it has, the rotation keeps skipping
  a tile with no clip, and a device that never idles on a charger simply has a library that moves
  less.

### 15.4 D-7 — T-2 stays, as the fallback, and it is allowed a network policy

A file whose byte holder cannot mint — uploaded from a platform with no encoder, evicted from the
phone, or simply held by a device that is never on a charger — is still reachable by the device
that wants the class: it fetches the original (rung 3), computes the class from the bytes it now
holds, stores and publishes it, and the realm has paid the original-fetch **once** for that file.
That is §12.6's T-2 unchanged in mechanism and changed in *role*: it runs only for
`wanted − present` files that D-6 could not reach, decided by the pass on the wanting device, never
by a tile mid-scroll.

⚠ **For a clip this fetches a whole video, and that is why it may carry a policy.** D2 and D21 of
the video doc refuse a Wi-Fi or battery condition for *playback* because the rotation's cost is one
clip in flight regardless of library size. A backfill's cost scales with the library, so
idle + charging + unmetered is the honest shape here, and it is not a contradiction. ⚠ Not
scheduled until W-2 has run on a real library and shown what D-6 leaves behind; it may be nothing
worth a pass.

### 15.5 D-8 — upload-time gating on the wanted set (an option, not a decision)

`UploadFileImplementation` could consult `wanted(realm)` and skip `XLARGE` and `MOTION` for a realm
no device renders them on, saving the uploading device's encode and ~139 KB of relay bytes per
photo. **Recommendation: not yet.** Eager-at-upload is the cheapest mint there is (the bytes are
already open), the pass would only re-do it later, and a wanted set that changes after the fact
turns every gated upload into backfill work. Revisit when a storage census shows a realm carrying a
class nobody reads.

### 15.6 The next rung — the recipe this buys

Adding `XXLARGE` or `XSLARGE` becomes: one `CLASS_*` constant and its `_PX`, one
`File.ThumbnailXxx` `dynamicProperty(Syncable(OnDemand))` with the `FileService` version bump and
the *property-kind* baseline (§13.2 of the video doc — the shape baseline stays silent, and that
silence must be proved), one mint line at upload for the categories that can carry it, and **one
device declaring it**. History follows through §15.3 on its own. The class set stays closed and
small, so §10.3.1's `TMap` question stays closed too; and §14.5's rule is now enforced by the
mechanism rather than by an acceptance test — a rung nothing declares is a rung the pass never
mints.

### 15.7 House rules

- **HR #2.** W-1 adds a synced row per device: its wanted classes. Amend the threat model with the
  phase: this is A6 device metadata at class granularity, revealing a device's *kind* (a desktop
  with a hero surface, a watch) — which the realm's device rows already reveal — and no file, no
  count, no timing. §10.3's rule that a **per-file** demand signal never syncs is untouched and
  should be restated next to the new row so the two are not confused.
- **HR #9.** Not engaged by W-1/W-2: a preference row is data, not a type. Engaged by any new rung
  (§15.6), exactly as it was for `XLARGE` (v4) and `MOTION` (v10).
- **HR #8.** W-2 touches Control's DI on Android; the smoke gate runs.

### 15.8 Phases

| # | Slice | Notes |
|---|---|---|
| **W-1** ✅ | The declaration: `thumbnails.wantedClasses`, its writers on the pull path, `wanted(realm)` by row scan | shipped 2026-09-09 (§15.9); threat-model A6 amended |
| **W-2** ✅ | The pass (D-6) for `XLARGE` + `MOTION` on Android: schedule, marker by wanted-set hash, budget, quota stop | shipped 2026-09-09 (§15.9); ⭐ closes Q3 of the video doc for the maintainer's library; the M5 device pass is what shows it — ⚠ budget unmeasured |
| **W-3** | T-2 as the fallback (D-7), stills first; clips behind the policy | ⚠ after W-2 has measured what is left |
| **W-4** | Upload-time gating (D-8) | optional; see §15.5 |

**Open:** the per-run budget (files and bytes) — §15.9 ships a guess and §15.10 could not measure
it; ~~whether the marker should also carry a `BACKFILL_FORMAT`-style version~~ (it does:
`ThumbnailClassBackfill.MINT_FORMAT` is hashed in beside the set); and whether a device that *stops*
wanting a class should ever cause eviction — §10.3's management surface, still independent and still
unbuilt. The two defects the device pass read out of the code
(a declining file stalls the walk, and
`XLARGE` is never declared on a warm device, §15.10) are **fixed in §15.11**; what that fix has not
had is a device pass with real backfill work in it.

### 15.9 As built — W-1 + W-2 (2026-09-09)

Landed together, on the JVM gate; the Android module compiles and its DI is audited by source
text. ⭐ **Both have since run on a device — §15.10.**

**W-1 — the declaration.**

- `ThumbnailPreferences.WantedClasses` (`:file-service`, beside `FileSyncPreferences`): DEVICE-scoped,
  `StringListAdapter`, and `ThumbnailPreferences.DECLARABLE_CLASSES` = `{xlarge, motion}` is the only
  vocabulary a row may hold — `declarable()` filters every read and write, so `LIST`/`LARGE` and
  typos never reach a row. ⭐ **The next rung is added there as well as to `GetStoredThumbnail`.**
- ⭐ **The pull is the declaration, and it needed no app change.** `OnDemandResolutionImpl` gained
  an optional `OnDemandPullObserver` (`:sync-service-proxy`, told the property id and nothing of
  what it means); Control's Android and desktop DI hand it `WantedThumbnailClasses.notePull`
  (`:file-service-proxy`), which maps the id through `FileBytesPropertyWriter.thumbnailClassFor`
  and writes the device row on first sight. The hook fires on **every** projected OnDemand
  relation, present or not — `SelectImplementation` leaves a placeholder per relation and the
  materialiser resolves each — which is what makes it cover a byte holder reading its own rungs.
  Steady state is one uncontended mutex and a set lookup; the store is read once per class per
  process, written only on change, and a failed write never fails the pull.
- ⚠ **Why not `GetStoredThumbnailImplementation`.** It was the obvious hook and it is wrong:
  `GetPreview`'s ladder resolves *every* stored class through `resolveProperty` on every render,
  so hanging the declaration there would declare `XLARGE` on any device that ever drew a preview.
  The resolver seam sees only client-named rungs.
- `wanted(realm)` = `wantedThumbnailClassesInRealm(objectStore)`: a bounded scan of the
  `DeviceSetting` type (`ProxyTypeScan`, the CR-4e pager copied into `:file-service-proxy` because
  the original is private to the sync module), filtered by preference id, decoded, unioned. `null`
  when the store cannot enumerate, and `null` is never empty.
- The app-side declaration (§15.2 "may") was **not** built: Photos and Timeline both pull the rungs
  through `select { relations(File.ThumbnailXLarge …) }`, so the hook already sees them.

**W-2 — the pass.**

- `BackfillThumbnailClasses` (`HeadlessSafe`, like `OfflineKeeper`) → `ThumbnailClassBackfill`, a
  pure class with every collaborator injected in the `ContentDeclarationBackfill` shape, wired by
  `BackfillThumbnailClassesImplementation`. Enumeration is a **type scan of `File.ID`** (folders are
  their own type, so leaf files only), never the tree from `/`; `present(file)` and
  `HasLocalData` are stats.
- ⭐ **One mint.** `ThumbnailClassMinter` is the upload's render + write + `emitFileContent`,
  *extracted* from `UploadFileImplementation` (which now delegates) rather than copied — so a class
  minted a year late is byte-for-byte and edge-for-edge the class an upload mints, and the
  reclamation sweep can name it. The minter reports *why* nothing was stored, which the pass needs.
- ⚠ **Declined vs failed — a refinement of §15.3's "an unmintable file never latches".** A render
  that *declined* (`GetPreview` returned null, the clip encoder said no) is terminal for that file
  and class and does not block the marker: the upload path would have stored nothing either, and
  refusing to latch over it would re-decode every unrenderable file on every tick for a result that
  cannot change. A render that *threw* is a transient and blocks the marker. Quota and budget stops
  block it too.
- Gating: `XLARGE` for what `GetPreview` renders (image, video poster, PDF page); `MOTION` for a
  video where a `MotionThumbnailRenderer` is wired (Android). What this platform cannot mint is not
  this platform's incompleteness — the marker may latch over a skipped class.
- Marker: `ThumbnailPreferences.BackfillMarker` = `sha256("v1|" + sorted classes)`; bump
  `ThumbnailClassBackfill.MINT_FORMAT` to re-run every device. Written only after a run that walked
  every row, hit neither quota nor budget, and failed no mint; an empty wanted set latches without
  enumerating.
- Quota: `ensureWithinQuota` at the class's **cap** (256 KB / 96 KB) before each render —
  conservative, so a realm at the edge stops before the encode, not after. Same no-op as an upload
  where storage is unknown.
- Budget: ⚠ **a guess** — 250 files and 32 MiB per run (≈ 230 heroes), constructor parameters. The
  numbers exist so a library of thousands drains across nights rather than in one; W-2's device pass
  replaces them with measured ones.
- Schedule: `registerThumbnailBackfillSchedule()` — idle + charging + unmetered + battery-not-low,
  24 h period, 20 min initial delay, dedup key `tenshu/file/thumbnailBackfill` — registered beside
  the offline keeper on **Android and desktop**. ⚠ Desktop's in-process drain loop enforces no
  constraint (the same caveat as the content backfill); the budget bounds it. ⚠ **iOS is not
  registered**: route 1 runs N in-process proxies under one device id, so the marker row would be
  a read-modify-write race between sibling apps, and an `iosMain` change cannot be verified off a
  Mac — it waits for the next device pass on that platform.
- DI on both platforms passes `objectStore =` to the common file factory and `onPull =` to the
  resolver; `ThumbnailBackfillWiringTest` (source-text audit, the `MotionThumbnailRendererWiringTest`
  shape) fails if either, or the schedule registration, goes missing.

**Tests.** `ThumbnailClassBackfillTest` (the pure pass: wanted − present, gating, quota/budget stops
never latch and resume, declined vs failed, marker re-arms on a set change and on the mint format,
unreadable is never empty), `WantedThumbnailClassesTest` (write-once on pull, implicit classes
ignored, union with an existing row, no identity ⇒ retry, `wanted(realm)` over three device rows),
`BackfillThumbnailClassesOpTest` (the real op over the real store with `GetPreview` and the clip
encoder faked: blob path, property id, no self-declaration, latch, no re-emit), and
`OnDemandPullObserverTest` (every pull observed, a throwing observer never fails a pull).

### 15.10 ⭐ The device pass (2026-09-09) — it runs, and two defects are read out of it

Run on the OnePlus against the real library, together with the M5 pass (`photos-video-support.md`
§15.9 — the two are one pass: a rotation needs a clip, and the clip is what this mints).

**What worked, first time.** ⭐ **W-1 needs no app change**: opening the Photos feed wrote this
device's row as `motion` — the grid's `motionClip` select on a visible video tile *was* the
declaration, exactly as §15.2 designed it. The schedule item registered clean and sat `PENDING`
beside `offlineKeeper`. The pass fired ~24 min after Control started (20 min nominal plus the tick
accumulator's drift) and reported

    ThumbnailClassBackfill -> minted 1 classes (82566 B) over 1 files — recorded complete

with the marker at `sha256("v1|motion")`. ⭐ **"1 file" is correct**: exactly one video on this
device has local bytes, so `hasLocalBytes` gating is right and the other 12 belong to whichever
device holds theirs.

**The library, for calibration:** 1575 files with thumbnails (`list` 1575, `large` 1575, `xlarge`
**1463**, `motion` **7**); 20 videos, **1** with local bytes.

⚠⚠ **Two defects, both read out of the code rather than measured** — one file of work cannot
exercise either:

1. **A declined file consumes the run budget every tick.** `filesTouched++` precedes the mint and
   nothing persists a decline, so a permanently-declining file is re-rendered every run *and* costs
   a slot; once such files reach `maxFilesPerRun` the marker never latches and the walk never
   reaches the rest of the library. §15.3's stated reason for latching over a decline — not
   re-decoding an unrenderable file for a result that cannot change — is delivered for the *marker*
   but not for the *re-decode*. ⚠ The fix must **persist** the decline; merely not counting it
   trades a stall for an unbounded run.
2. **`XLARGE` is never declared on a warm device.** After a session of grid browsing *and* a
   full-screen viewer open, the row was still `motion` only. `cacheThumbnails` is the only caller
   naming `File.ThumbnailXLarge` and `preview()` reaches it only on a **cache miss** — with 1463 of
   1575 files already holding `xlarge`, the miss never happens. `motion` declares only because
   `motionClip` selects unconditionally. So *"a rung nothing pulls does not exist"* holds, and its
   corollary bites: **a class rendered constantly from cache is pulled never, and declared never.**
   This is the case for §15.2's app-side `declare()`, which was not built.

⚠ Also unmeasured, and now visible: **the schedule's idle + charging + unmetered constraints do not
hold on Android while Control runs** — `ScheduleDrainLoop` never reads `ConstraintSpec` (bgproc Ph6),
and WorkManager is only the driver once Control is closed. The pass fired on a phone that was
plainly not idle. Pre-existing and shared with the offline keeper and the slice-snapshot pass, but
this is the first pass expensive enough for it to matter.

### 15.11 As built — the §15.10 fixes (2026-09-10): declines persist, apps declare, the walk runs every tick

Three changes, one per finding of §15.10 plus its 2b, and one thing deliberately not done.

- **A decline is recorded, beside the blob.** `BackfillThumbnailClassesImplementation.thumbnailDeclinePath`
  = `thumbnailPath(contentId, class) + ".declined"`, a tiny local file whose content is the
  `MINT_FORMAT` it was recorded under. `declined(file)` is one stat per class, subtracted from
  `wanted − present` exactly like `present` is; keyed by `contentId` so a re-upload starts clean;
  local only, never synced, never on the content plane. ⚠ A sentinel under an *older* format is
  re-tried and overwritten — bumping `MINT_FORMAT` is how a new encoder gets to ask every old "no"
  again. ⚠ A decline still costs a budget slot **on the tick it happens**, deliberately: the issue's
  own warning was that not counting it swaps a stall for an unbounded run, and with persistence
  each file can decline once, ever — so a library of ten thousand never-seen declining files drains
  across ticks like any other work, and the tick after reaches what lies behind them
  (`decliningFilesCannotStallTheWalkBelowTheBudget`).
- **Apps declare at launch.** `declareWantedThumbnailClassesAtLaunch` (`:file-service-client`, over
  the preference client, the `FileSynchronisationSettings` shape) waits for the device id and
  unions the classes an app's screens draw into the row — a read-modify-write that writes nothing
  in steady state, so a launch puts no block on the wire. Wired beside the schema announce in
  `PhotosClientModule` (`xlarge`; `motion` when *Play videos in the grid* is on **and** the platform
  has a tile player — the rotation's own gate) and `TimelineClientModule` (`xlarge`, for
  `heroFidelity`). ⚠ **Drive does not declare.** Its `cacheThumbnails` pulls `LIST` + `LARGE` only;
  the desktop hero goes through `GetPreview` and, byte-less, fetches the original (§14) — a device
  that never pulls a rung must not say it renders it. If Drive's hero ever pulls `XLARGE`, that PR
  declares it.
- **The walk runs every tick; the marker is a report.** The skip-the-walk gate is gone from
  `ThumbnailClassBackfill`: every tick enumerates, and `hasLocalBytes` is re-read from the
  filesystem, so bytes that arrive after a complete run are minted on the next one (§15.10's 2b)
  with no second signal to re-arm anything. The cost is the bounded type scan plus one stat per
  file per declarable class (a sentinel is looked for only where a class is still absent) —
  milliseconds at this library's size, about a second at a hundred thousand rows. The marker is still written after a clean run, as *"this wanted set was fully
  served once"*, and nothing reads it but a person.

**Verified on the JVM gate**, not a device: the pass tests (declines recorded once, the stall
case, a failed mint retried, later-arriving bytes reached), the op test over the real store (the
sentinel's content, no second render, a stale-format sentinel retried once), the pure union. ⚠ The
next device pass is the one with real backfill work in it — §15.10's library has 112 `xlarge`-less
files and now a device that declares the class — and the budget is still a guess until then.

---

## 16. Amendment (2026-09-15) — ⚠⚠ the viewer never pulled the hero rung, and the grid asked for dp

Reported as *"Photos thumbnails got worse — now blurry on Android too, not just desktop"*. Two
defects, both in how Photos **asks**; the ladder, the resolver and the backfill were right.

### 16.1 The grid: a 256 px request against a ~400 px tile

The grid asked for a flat `TILE_PX = 256`, justified in its KDoc as *"a 3-across phone grid is
~130 px"*. ⚠⚠ **That is 130 dp.** On a 3x phone the tile is ~410 physical px, and the desktop grid
is the same fixed three columns (`FeedDensity.Day`) in a window several times wider. A tile is also
a centre crop, so a 4:3 photograph's 256×192 `LARGE` fills a square only up to **192 px**.

It looked fine on the uploading phone until 2026-09-06 by accident: §14.2's lower bound on
`coveringStoredThumbnail` is what made a 256 px request land on the 256 px class. Before it, a
device holding `XLARGE` answered *every* request above `LIST` with it, so that phone's grid was
decoding 1024 px heroes into 400 px tiles. §14.2 fixed a real cost, and in doing so exposed the
request size — which is why Android "got worse" rather than having been wrong all along. On desktop
(byte-less) the grid had always been `LARGE`.

⚠ **§14.2's rationale is wrong in one clause, and it is the clause that mattered:** *"a difference no
tile can render"*. A tile renders it at anything above 192 physical px — every 3-across and 5-across
phone grid, and every desktop grid.

**What changed:** a tile measures itself (`onSizeChanged`, in physical px) and asks for
`tilePreviewRequestPx(edge, pixelWidth, pixelHeight)` — `LARGE_PX` while `LARGE` fills the square,
`XLARGE_PX` once it cannot. ⚠ "Fills" is the class's **short** edge, so it is the photo's shape:
256 px for a square photo, 192 at 4:3, 144 at 16:9, ~118 for a phone screenshot. `File.PixelWidth` /
`PixelHeight` carry it (a rotation does not change the ratio; without them 4:3 is assumed), and ⚠⚠
**the grid is drawn from three reads, so all three project it**: the paged feed (and search/album
rows), the shared `ContentIndex` / `RealmContentIndex` (All photos, Favourites, Duplicates — two more
scalars per indexed row, the coordinates' precedent), and album covers, looked up in that index by
content id rather than read per album. ⚠ The About panel's `albumsFor` skips the lookup: it has no
index open, and a whole-realm walk to shape covers it lists by name is not worth it.
`RealRepositoryReadTest.aPhotographsShapeReachesEveryReadTheGridDrawsFrom` asks all three. ⚠ Two values, not the raw size: the tile keys its load on the request and `App`
keys `PreviewCache` on `(requestPx, path)`, so a window drag reloads once at the threshold rather
than once per pixel, and the year view's small tiles and the day view's large ones stay separate
entries. Nothing loads before layout. `AlbumCard`'s cover follows the same rule.

⚠ **The cost is §14.2's, now paid on purpose:** a tile above 192 px is a ~100–139 KB JPEG where it
was ~15 KB, and `PreviewCache`'s 8 MB holds ~60 of them (four-odd screens of a 3-across grid). On
Android each is also a 1024×768 decoded bitmap (~3 MB) — which is what the uploading phone already
decoded, until 2026-09-06, for every tile whose photograph had a hero rung, at every density; the
7-across year view on a 1080 px phone now gets `LARGE`, so this is less than that. ⚠ A decode that downsamples to the tile is the
follow-up if a device pass shows memory pressure; it is not built.

### 16.2 The viewer: the pull fired only on a null read, and the grid made it never null

§14's fix pulled `XLARGE` inside `cacheThumbnails`, which `preview` called **only when
`File.Preview` came back `null`**. On a byte-less device it is not null by the time anyone opens a
photograph: the grid tile has already pulled `LIST` + `LARGE`, so `GetPreview` answers a 1024 px
request with `storedThumbnail`'s best local class — the 256 px one. The hero rung stayed on the
content plane and every viewer page was a 4x upscale.

`SyncedThumbnailSystemTest` could not see it for §14.1's reason one level up: it pulled the hero rung
**by hand** and then asserted `File.Preview` served it, proving the rung *could* arrive while the
repository never asked. ⚠ Its §14 precondition even measured the broken state — *"with only LIST +
LARGE pulled, a 1024 px request can only be served by the 256 px class"* — and read it as setup.

**What changed:** `TenshuPhotosRepository.preview`, for a request above `LARGE_PX` on a device that
does **not** hold the original (`hasLocalData`, an O(1) stat), selects `File.ThumbnailXLarge`
directly — the `motionClip` shape: the relation answers from the local copy or pulls it — and falls
back to `File.Preview` only when no hero rung exists anywhere. `cacheThumbnails` pulls the two small
rungs only.

- ⚠ **Not on a byte holder — for a still.** `GetPreview` already answers it sharp (stored `XLARGE`,
  else a render from the original), and asking for a hero rung nobody minted — an upload before T-1
  the backfill has not reached — would cost a relay round trip per tile. The stat buys that off.
- ⚠⚠ **A video asks regardless** (`asksForHeroByName`). Rendering a poster from the original is a
  platform capability: the JVM proxy has no frame decoder and serves a downloaded video's *stored*
  classes only, so on desktop "holds the original" does not mean "sharp". The hero poster is
  local-first, so a device that already has it pays nothing; a byte holder whose video has no hero
  poster yet pays a relay miss per composition until the backfill mints one.
- ⚠⚠ **A fallback is marked, and never cached.** When the hero rung cannot be had — not minted
  yet, or the fetch failed — the answer is `File.Preview`'s best local class with
  `PhotoPreview.standIn = true`. `PreviewCache` serves it and does not keep it, the rule it already
  applied to `null`: kept under the `1024:path` key, it would hold a tile blurry for the session
  after the byte holder's backfill minted the real class. The cost is that such a tile re-asks
  (a stat and a relay miss) each time it is composed again, until the class exists.
- ⚠ **Known limitation, left deliberately: a viewer page does not re-ask while it stays composed.**
  `PhotoPage` loads its hero once per path, so a page that got a stand-in (opened offline, or before
  the backfill reached that photograph) keeps it until it leaves the pager window or the viewer is
  reopened. Re-asking needs a signal, and there is none: a thumbnail class landing on the content
  plane changes no `File` row, and polling a relay miss while someone looks at a photograph trades
  battery and data for the rare case where the class arrives during those seconds. Master drew the
  256 px class on every such page unconditionally, so this is a remaining gap, not a regression.
- ⚠ This reads a class by name in app code, which §10.1 said consumers never do. It is the same
  exception `motionClip` and §14's pull already made: the resolver cannot fetch, so the rung a
  request needs has to be named by the one layer that can. The resolver still owns every *local*
  choice.

**The test that pins it** is `SyncedThumbnailSystemTest.joinerOpensAPhotoAfterTheGrid_andIsServedTheHeroRung`,
over the real `TenshuPhotosRepository` (`:system-test-utils` now depends on `:photos-shared-logic`,
test scope): a tile read first, asserting the joiner holds `LARGE`, then a viewer read asserting a
decoded 1024 px edge — plus the byte holder's own read, which must not take the joiner's path. Its
sibling `joinerAsksForAHeroRungNobodyMinted_andIsServedAMarkedStandIn` uploads seeded noise, whose
`XLARGE` is far over the 256 KB cap and so is declined while `LARGE` fits, and asserts the stand-in
flag (it pins that premise first, so an encoder change cannot quietly make it a happy-path test).

### 16.3 The rule worth carrying

**A request size is a claim about the screen, and a constant in px written against a layout in dp
is off by the density.** Measure where the pixels are drawn. And §14.5 has a corollary: a rung
reaches a device only if the client names it *on the path users actually take* — a pull that runs
only when a read is empty is never reached once any cheaper rung has arrived.

---

## 17. Amendment (2026-09-16) — ⚠⚠ the hero rung was declined, not missing: a quality step-down and a 512 KB cap

**Decided with the maintainer, 2026-09-16.** Reported as *"Photos desktop still shows blurry images,
and they get blurrier when I resize the window"*, on a 3456 × 2234 retina MacBook, the day after §16
shipped.

### 17.1 The measurement

Instrumented `PhotoTile` on the packaged desktop app (a temporary log line: tile edge in physical px,
requested px, decoded bitmap), default window:

| tile | asked | got | upscale |
|---|---:|---:|---:|
| 426 px | 1024 | 1024 × 1024 | 0.41 |
| 860 px (a 2 × 2 mosaic feature) | 1024 | **256 × 256** | **3.35** |
| 426 px | 1024 | **256 × 256** | **1.66** |
| 426 px | 1024 | **256 × 256** | **1.66** |

Three of four were §16's marked stand-in: the hero rung was asked for by name and **did not exist**.
Only 6 of the desktop's 118 locally cached content ids held an `xlarge` blob.

⚠⚠ **Not §14's defect, and not §16's.** The traced file was uploaded 2026-09-11, a month after T-1, so
`XLARGE` was rendered at upload. Its `LARGE` is **35 KB** against the ~15 KB of an ordinary photograph
(cap 48 KB): a detailed frame. `ThumbnailClassMinter.renderStill` stored nothing over the cap and had
no second attempt, and the backfill (§15.11) records exactly that as a terminal decline. So a byte-less
device drew such a picture from the 256 px class **permanently**, and no pull, declaration or backfill
could change it. ⚠ Inferred from the file's `LARGE` weight plus the reproduction below; the relay was not
read.

**Reproduced on the JVM encoder** with a synthetic dense-text 1200 px frame, whose `LARGE` weighs 36 KB
(the real file: 35 KB):

| `XLARGE` of that frame | q80 | q65 | q60 | q50 | q40 |
|---|---:|---:|---:|---:|---:|
| bytes | 494 KB | 377 KB | 351 KB | 312 KB | 276 KB |

⚠ **A quality step-down alone does not rescue it**: every quality down to q40 is over 256 KB. Hence two
decisions, not one.

⚠ The *"blurrier when I resize"* half is §17.4's, not this section's: the grid is a fixed three columns
and the viewer asks a constant 1024 px, so a larger window is a larger upscale of the same rung. Computed
for this panel maximised, not observed (nothing here can drive or capture the window): tiles ~1070 px,
features ~2100 px, the viewer ~3000 px.

### 17.2 D-9 — a JPEG over its cap is re-rendered at lower quality before it is declined

`renderStill` tries `STEP_DOWN_QUALITIES` = **default, 65, 50** and stores the first render that fits.

- **Default first**, so a frame that already fits encodes byte-for-byte as before (80 on Android and
  iOS, 75 in ImageIO, 85 in a browser).
- **50 is the floor**: below it the saving flattens (q40 is 276 KB against q50's 312 KB above) while text
  visibly smears.
- ⚠ **Only an over-cap answer steps down.** An empty render is the file saying no (not an image, not
  decodable), and no quality changes that. `BackfillThumbnailClassesOpTest` pins both halves.
- ⚠ **Every step is a fresh render from the original**, never a re-encode of the rejected JPEG, which
  would walk the ladder downhill (the reason `GENERATE_FRESH` exists).
- It applies to **every JPEG still rung** (`LIST`, `LARGE`, `XLARGE`) because they share the one mint.
  The cost is paid only by a frame that was over its cap: up to two extra decodes, at upload or in the
  backfill.
- **Mechanism:** `GetPreview.Params.QUALITY`, an optional int. All four actuals (Android, JVM, iOS,
  browser) honour it for JPEG and ignore it for PNG. ⚠⚠ Each proxy-side preview cache **keys on it**
  (`contentHash:jpeg:q65`); a key without the quality would answer the step-down with the over-cap
  render it is replacing (`JvmGetPreviewImplementationCacheTest.jpegQuality_isHonoured_andKeysItsOwnCacheEntry`).

### 17.3 D-10 — `MAX_XLARGE_THUMBNAIL_BYTES` 256 → 512 KB

The dense-text frame fits at **q80** (494 KB), and an ordinary photograph (~139 KB, §12.1) is unaffected.
The maintainer's reasoning was that the bytes land on exactly the users who keep detailed pictures
here, who are the ones holding the storage subscription.

⚠⚠ **The cap no longer refuses any image, and that should be said plainly.** D-3 (§12.5) described it
as refusing *"anything pathological"*. With D-9's floor, even pure RGB noise fits: 1024 × 1024 at q50
measured **420 KB** (JPEG's own chroma subsampling and quantisation do the refusing). The cap is now a
**byte bound per blob**, not a filter, and an `XLARGE` decline in practice means *"could not render"*.
Two consequences:

- `SyncedThumbnailSystemTest.joinerAsksForAHeroRungNobodyMinted_andIsServedAMarkedStandIn` built its
  premise from a noise image over the old cap, and that premise became impossible. It now uses a host
  whose `GetPreview` will not render the hero size: the pre-T-1 upload it always stood for. That took an
  `operationDecorator` on `TestSystem.client`, which wraps one device's implementation of one
  operation. The premise assertion still guards it.
- ⚠ The proxy-side `PreviewCache` entry cap is also 256 KB (`DEFAULT_MAX_ENTRY_BYTES`). A heavy `XLARGE`
  is still minted and stored as a blob; only a *computed* preview above that size is not memoised by
  the proxy, which is the path a byte holder takes. Nothing depends on that memo.

**`ThumbnailClassBackfill.MINT_FORMAT` → `v2`**, so every decline a `v1` pass recorded is retried on
the device holding the original. The marker hash moves with it and re-arms the pass (§15.11).

**House rules.** ⚠ **HR #9: engaged.** An
optional parameter reads like an additive nothing, but an op's parameter object is a client-serialized
positional composite, and `SchemaShapeBaselineEnforcementTest` reported `tenshu/file/getPreview` as
reshaped. So `FileService` went **v10 → v11** with an empty `schemaMigration(fromVersion = 10)`. It is
empty for v9's reason: nothing ever persisted a parameter object, so the bump buys lockstep and moves
no cell. (The regenerated baseline also picks up two `:core` rows still pinned at format 21 after that
format's bump to 22; they are version-only.) HR #2: not engaged. No new plaintext-derived shape: the same 1024 px rung now exists for more
files, and its A5 treatment (§6, §10.4) already covers it. The relay sees a ciphertext length up to
512 KB where it saw up to 256 KB.

### 17.4 Direction for a rung above `XLARGE` — maintainer positions, not a design yet

> **Design:** [`drive-xxlarge-thumbnail-rung.md`](drive-xxlarge-thumbnail-rung.md) (2026-09-16) —
> the rung, the progressive large preview, mint-on-view and the eviction it needs, as D-12…D-20 and
> phases X-0…X-6.

Raised in the same conversation: a retina viewer (~3000 px) is served the 1024 px rung, a ~3× upscale,
and fixing the grid layout does not fix that. Two routes had been sketched: §15.6's `XXLARGE` recipe,
or the original as the top rung (§12.6 T-2 / §15.4 W-3). The maintainer's positions, recorded so the
design that follows starts from them:

- ⭐ **`XXLARGE` is wanted, not merely a fallback option.** The web client needs sharp large images and
  can never be asked to download an 18 MB DSLR original to draw one. That is §12.1's own argument: a
  derivative's weight is set by its pixels, the original's by the camera.
- **~480 KB per photograph (estimated for ~2048 px from §12.1's 295 KB at 1600 px) is acceptable.**
  Photo-heavy users are the subscription users.
- ⭐ **A progressive swap is worth designing:** show the best class already local, and when the higher
  class arrives or is minted, exchange it in place. ⚠ §16.2 names the gap this must close: a page that
  got a stand-in has **no signal** that the class later landed, because a class arriving on the content
  plane changes no `File` row. The swap needs that signal before it needs any UI.

Not decided yet: `XXLARGE`'s edge (2048 or larger), whether D-8 upload-time gating ships with it, and
how T-2 relates to it (the original may still be the only route to zoom and 5K/6K panels).

### 17.5 The rule worth carrying

**A cap that declines is a decision about the picture, and it is permanent on every device without
the original.** Before a byte bound refuses a render, ask whether a cheaper encoding of the same pixels
would do, and measure the class on the pictures people actually keep (screenshots and text, not only
camera frames).

### 17.6 D-11 — a preview above the hero rung's edge, asked for with no format, is JPEG

Added for item 3 of the same request: the Photos viewer asks for the size it actually draws instead
of a constant 1024 px (`photos-app.md` §34). Above 1024 px no stored class answers, except within D-2's
tolerance, so on a device holding the original the answer is a **render from it**. `File.Preview`
names no format, and the JVM and iOS actuals encoded that as PNG:

| JVM, a smooth 6016 px photograph | PNG | JPEG |
|---|---:|---:|
| 1024 px edge | 1093 KB, 72 ms | 42 KB, 30 ms |
| 2048 px edge | 3801 KB, 472 ms | 122 KB, 57 ms |
| 3072 px edge | **7588 KB, 528 ms** | **234 KB, 127 ms** |

⚠ A detailed photograph is larger still. So `GetPreview.Params.defaultFormatFor` makes an unformatted
request PNG up to 1024 px, exactly what every existing caller got, and **JPEG above it**. Android's
actual already defaults to lossy WebP and does not read it. The browser never projects `File.Preview`.
⚠ A transparent image's large preview gets a flattened background, as every stored rung already does.
⚠ Not a `File.Preview` arity change, so no schema bump of its own.


## 18. Amendment (2026-09-17) — ⚠⚠ a generation call served stored classes, and they were stored under the wrong name

Found while designing the `XXLARGE` rung (`drive-xxlarge-thumbnail-rung.md`), and confirmed by a JVM
probe before any fix: `GENERATE_FRESH` at 1024 px for a local `.mp4` with only a `LARGE` poster stored
returned the `LARGE` bytes verbatim.

### 18.1 What was wrong

`ThumbnailClassMinter.renderStill` sends `GENERATE_FRESH` and stores whatever comes back under the
class it asked for. The actuals honoured the flag in their two stored-class short-circuits, but not in
the fallbacks for a render that could not happen:
- JVM: a video (no frame decoder), no local bytes, a PDF page that failed to render;
- Android: no local bytes, a poster that failed to extract, a PDF page that failed to render;
- iOS: no local bytes, a poster or a PDF page that failed to render.

Each answered with `storedThumbnail(…)`, and two callers store that answer:
- ⚠⚠ **The backfill (§15.3).** On a desktop holding a video synced from a phone, the `XLARGE` render
  fell back to the pulled `LARGE` poster, and the pass stored and **published** the 256 px image as
  the 1024 px class. The class then reads as present, so it is never re-minted, and every device that
  pulls it gets the small picture for good.
- ⚠ **A replace.** The render runs before the row takes the new content id, so a fallback resolves the
  **previous** content's classes. An unrenderable replacement had the old picture stored as its own.

### 18.2 The fix

Every one of those fallbacks goes through `storedFallback`, which answers `null` on a generation call
and `storedThumbnail` on a read. A read is unchanged. The minter sees a decline, so the backfill
records it (§15.11) and a desktop costs each video one immediate decline, with no byte read, instead
of a mislabeled mint. JS was already right: its render failure is `null`.

Pinned by `GenerateFreshNeverServesStoredClassesTest`, which runs both routes through the real JVM
`GetPreview` and minter, and by three generation-call cases in
`JvmGetPreviewImplementationThumbnailFallbackTest`. Each JVM call site was mutated back and caught.
⚠ The Android and iOS sites have no runnable test surface for their actuals; they share the
one-line helper, and are covered by compile and the smoke gate only.

### 18.3 Not fixed: the blobs already published

A class stored under the wrong name before this fix stays present, so nothing re-mints it. It can only
be told apart by decoding its edge. Remediation is open (`drive-xxlarge-thumbnail-rung.md` §8 Q5).

### 18.4 The rule worth carrying

**A generation call either renders from this file's own bytes or says no.** A fallback that is right for
a read ("show the best we have") is wrong for anything that stores the answer, because the answer then
gets a name it did not earn.

## 19. Amendment (2026-10-02) — P1a: one decode renders an upload's whole still ladder

The sweep that found this is `media-processing-pipeline-sweep.md` (§4.1, phase P1a; decisions in §5).
The two of its decisions (maintainer, 2026-10-02) that bind this section, restated so a reader of
this doc does not have to reconstruct them:
- **D8** — all four still rungs stay inline in the upload op once single-decode lands. They are
  written and journalled before the op returns, because `LIST`/`LARGE` are never backfilled (§15.2):
  a rung lost in memory is lost to every peer for good. Only `MOTION` may move out of the op later.
- **D12** — a shared decode is an **explicit seam**, never a hidden cache keyed on array identity: an
  explicit seam can be faked and counted in a test, and a cache hides the contract.

Measured before the change (JVM, ImageIO, 12 MP JPEG): the whole ladder from one decode took 157 ms
against 393 ms rung by rung (≈ 2.5×); a 2× subsampled decode alone took 128 ms. As built, the opt-in
`ThumbnailLadderBenchmarkTest` measures the whole `UploadFile` op at 233 ms against 558 ms (median of 7, 4 cores).

### 19.1 What was wrong

The upload rendered each still rung with its own `GetPreview` call (§12, §15.3's "one mint"). Each
call read the file back, SHA-256'd it for the preview cache, decoded the original at full size and
scaled from there — four full decodes per photo. Each JPEG quality step (§17.2 D-9) re-rendered from
the original too, so a detailed frame cost up to twelve.

### 19.2 The seam

`ThumbnailLadderRenderer` (`file-service-proxy` commonMain, beside `MotionThumbnailRenderer`) renders
every rung from one decode of the bytes the upload already holds. `ThumbnailClassMinter.renderStills`
uses it where the platform wires one and falls back to today's per-rung `renderStill` otherwise, and
for any file the renderer answers `null` for.
- ⚠⚠ A platform seam, **not** a `GetPreview` param: a new op param is a wire reshape under House
  Rule 9. Nothing here touches the wire, a stored row or the `SchemaShapeBaseline`.
- ⚠ The output contract is the per-rung path's, rung for rung: the edge rule on the **original**
  dimensions (never upscaled), the format, the minter's `STEP_DOWN_QUALITIES` in order, the cap
  (re-checked by the minter), and the platform `GetPreview`'s pixel orientation — **upright** on
  every platform. ⚠⚠ *(2026-11-01)* This line used to say neither JVM nor Android applies EXIF to a
  rung, which was true and was the bug: no display turns a rung by `File.Orientation`, iOS had always
  minted upright, and the Android/JVM rungs showed iPhone selfies upside down on every device. See
  §20.
- ⚠⚠ **The JVM decodes at full resolution, not with `setSourceSubsampling`**, although the sweep's
  §4.1 names it. A subsampled read picks pixels without filtering them, so detail at the subsampling
  frequency aliases. 1 px stripes read at factor 2 keep one phase and the top rung comes out **solid
  black**, under every cap, so nothing downstream can tell; it would have been stored and synced to
  every peer (`ThumbnailLadderParityTest.finePeriodicDetailIsNeverAliasedAway`). It
  also bought nothing measured: a 12 MP photo needs factor 1, so P1a's whole gain is the one decode.
  Peak memory is one full decode, the same as each per-rung call before. ⚠ **P1b/P1c: a bounded
  decode is only acceptable if it filters** — check what `inSampleSize` and
  `CGImageSourceCreateThumbnailAtIndex` do for PNG and other non-JPEG sources before relying on them.
- ⚠ A decline stays distinguishable from a failure: undecodable is `Declined` at every rung, a throw
  is `Failed` at every rung (§15.11: the backfill records a decline for good and retries a failure).

### 19.3 Where it is wired

| Platform | Ladder | Notes |
|---|---|---|
| JVM / desktop | `JvmThumbnailLadderRenderer` — one full-resolution decode, cascade 2048 → 1024 → 256 → 100, step-down re-encodes the scaled bitmap, no hash | Plain images only; PDFs and videos fall back. Audited by `ThumbnailLadderRendererWiringTest`. |
| Android | none yet (P1b) | per-rung `GetPreview`, unchanged |
| iOS | none yet (P1c) | per-rung `GetPreview`, unchanged |
| JS | none | per-rung, unchanged |

Only the upload and replace ops use it. The backfill and mint-on-view mint one class per call and
still go through `renderStill`.

⚠ The ladder writes nothing to the `PreviewCache`. The per-rung path cached each rung under the
file's SHA-256, which a later `GetPreview` read could only hit at exactly a rung's size, format and
quality, and the stored classes answer those first (§12.5 D-2).

### 19.4 Pinned by

- `UploadDecodesOnceTest` (jvmTest): the real op and decoder, counted — 1 decode per upload with the
  ladder, 4 without it, more than 4 for a frame that walks the step-down.
- `ThumbnailLadderParityTest` (jvmTest): the same rungs stored and declined, JPEG, dimensions to the
  pixel, under the cap, mean pixel difference within tolerance, on a landscape past the top rung, a portrait,
  a small PNG and a noise frame that reaches both a decline and the step-down; and 1 px stripes, whose
  every rung must keep the stripes' mean (an unfiltered subsampled read made it 0.0).
- `ThumbnailLadderSeamTest` (commonTest, so it also runs on Kotlin/Native): the minter's half — order,
  cap, decline versus failure, and the fallback.

## 20. Amendment (2026-11-01) — ⚠⚠ a rung is stored upright, on every platform

### 20.1 What was wrong

Two contradictory rules, each with code behind it:

- **iOS minted upright.** `IosGetPreviewImplementation` draws a `UIImage`, which applies the
  orientation, so its rungs are the picture as shown (and carry EXIF orientation 1).
- **Android and the JVM minted in sensor order.** `BitmapFactory` ignores EXIF for every orientation,
  HEIC included; `ImageIO` ignores it for JPEG (the macOS bridge for HEIC applies it). §19's contract
  and the `ThumbnailLadderRenderer` KDoc said so on purpose: *"`File.Orientation` carries it to the
  display"*.
- **No display ever read `File.Orientation`** — not Photos or Drive on any platform, not the web. All
  of them draw a rung as-is. The only consumer of the sensor-order rule was the face indexer
  (`IndexPreview` → `detectFaces(bytes, orientation)`), which therefore turned every **iPhone-minted**
  rung a second time.

A rung is minted once per content and synced, so whichever device mints it first decides what every
device shows. Found on the maintainer's phone: front-camera iPhone 17e HEICs (EXIF 3, `irot` 2) showed
**upside down** in the grid and the viewer. The iPhone had minted `LIST`/`LARGE` upright; the phone,
holding the originals, backfilled `XLARGE`/`XXLARGE` overnight in sensor order (byte-identical to its
own preview cache). A portrait iPhone selfie (EXIF 6) came out on its side the same way. The desktop
looked right because it was drawing the iPhone's small rungs.

### 20.2 The fix

- **The rule: a rung, and any `GetPreview` render, is upright.** One table, `ExifUpright`
  (`file-service-proxy` commonMain), maps EXIF 1–8 to a turn (mirror, then 0/90/180/270° clockwise),
  and `ExifUprightTest` pins all eight against the spec's row/column wording. Each platform applies
  it with its own native transform — a pixel loop would drop Android's Display P3 colour space and
  cost the JVM three frame-sized buffers.
- **Android** reads the orientation with the common `readMediaMetadata`, fits the box to the picture
  *as shown*, scales in sensor order and turns the result with a `Matrix`, so a rung's turn costs the
  rung's size.
- **JVM**: `JvmImageDecoder.Default` turns what `ImageIO` decoded, with one `AffineTransform` draw;
  the macOS bridge's answer is left alone (it is already upright). Both JVM paths sit behind that
  decoder. `JvmUprightRenderTest` covers 1–8 and a minted rung, and fails with the turn removed.
- ⚠ **Not covered: a HEIF whose rotation is only in `irot`** (EXIF 1 or absent — some non-Apple
  encoders). `readMediaMetadata` reads the Exif item only, and `BitmapFactory` ignores `irot`, so such
  a file still mints in stored order on Android and the JVM. Apple's files carry both.
- ⚠ **Both preview caches key a turned render `…:upright`**, or the unturned renders cached before the
  fix would be served for good. An unturned picture keeps its key and its cache.
- **The indexer passes no orientation for a preview** (`PreviewBytes`/`IndexPreview` lost the field):
  `null` means "the bytes' own tag", which is 1 on an iPhone rung and absent on the rest.
- Related, same symptom: `ui-core`'s Android `decodeToImageBitmap` — what Drive's viewer uses on an
  **original** — now applies EXIF, as Skia already did on desktop and iOS.

### 20.3 ⚠⚠ Not fixed: the rungs already published

A sideways or upside-down rung minted before this fix stays present, so nothing re-mints it, and it
cannot be fixed in place: a rung's address is derived from `(contentId, class)`, so a forced re-emit
names a new chunk set under the **same** manifest (the old one stranded on the relay as duplicate chunks
no manifest describes) and a peer that already holds the old bytes never asks again. Nor can a reader correct for
it: a sensor-order rung carries no EXIF, but neither does an upright one the JVM minted from a HEIC
through the macOS bridge, so for a half turn (EXIF 3) the bytes alone cannot say which they are.
Only a device holding the original can tell, by comparing a small upright render of it with the rung.

Affected: `XLARGE`/`XXLARGE` (and on Android uploads, every class) minted on Android or a desktop
from an original whose EXIF orientation is not 1. Options, none built: a content-plane primitive to
**replace** a derived rung (new chunk set declared, old one released, peers told to drop their
copy); or accept them until the file changes. Same shape as §18.3 and
`drive-xxlarge-thumbnail-rung.md` §8 Q5, and whatever answers one answers all three.

**Decision (2026-11-01, maintainer): accepted, no repair.** The fix ships before the first store
release, so the only realm holding such rungs is the maintainer's test realm; every released build
mints upright. The face indexer now reads such a rung unturned too, so a portrait face in one is
mostly missed — the same realm, the same decision. ⚠ If a pre-fix build ever reaches users, this
decision has to be revisited.

### 20.4 The rule worth carrying

**A stored derivative carries everything needed to show it, or the reader must be made to need
nothing.** A rule that relies on every reader applying a side value is a rule some reader will not
know about — here, all of them.
