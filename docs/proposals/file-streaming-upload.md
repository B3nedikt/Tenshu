# File Upload Without Holding the File — Bounded-Memory Ingest

**Status:** Design, nothing built. Open questions in §8 need a maintainer decision before P1.
**Prompted by:** the Finder integration's 2 GiB upload cap
([`desktop-macos-integration.md`](desktop-macos-integration.md) §5b) — which turned out not to be
Finder's limit at all.

---

## 1. The problem

Every way a file enters a realm ends in `UploadFileImplementation.uploadFile(…, data: ByteArray, …)`
(`file-service-proxy/…/impl/UploadFileImplementation.kt`). The whole file is one array in the
proxy's heap, on every platform:

| Entry | Where the whole-file read happens |
|---|---|
| `UploadFile` / `ReplaceFile` (+ `Bg`) | the `TByteArray` op parameter |
| Android `UploadSourceFile` / `ReplaceSourceFile` | `FileInputStream(pfd).readBytes()` (`AndroidFileUploadImplementation.kt`) |
| iOS source-file ops | `IosUploadSourceReader.readBytes` (`IosSourceFileImplementations.kt`) |
| Drive desktop app | `readWithBudget`, which returns the whole file as a `ByteArray` sent inline (`DesktopFilesRepositoryPlatformDelegate.kt`) |
| Control `FolderWatcher` (synced folders on desktop) | `File(…).readBytes()` (`FolderWatcher.kt`) |
| Finder bridge | `createFileFrom` / `modifyContentsFrom` read the staged file whole through `FileProviderStaging.openUpload` (`ControlFileBridge.kt`) |
| Takeout photo import | one archive member per `uploadFile` call |

Consequences: a file above 2 GiB cannot be uploaded at all (`ByteArray` is `Int`-sized); a large
video costs its full size in heap, several times over on desktop (client read, wire frame, proxy
copy); and the desktop UDS path caps one request at 256 MiB (`StreamFramer.MAX_FRAME_BYTES`).
[`media-processing-pipeline-sweep.md`](media-processing-pipeline-sweep.md) F2 already names the
phone half of this ("a large video is read whole … likely OOM"; verification owed).

**What is already fine.** The content *emit* reads the stored file 2 MiB at a time with bounded
concurrency and never sees the upload array (`SyncObserver`; residency
`O(4 × 2 MiB)`). The download side reassembles into a `.tenshu-partial` by ranged writes and moves it
into place (`FileContentManifestApplier`). So the store and the wire are streaming
already; the gap is the **ingest** in between.

## 2. What the bytes are used for today

`uploadFile` uses `data` for six things. Each needs a different answer.

| Use | Today | Needs |
|---|---|---|
| Size for the quota gate | `data.size` | the size, **before** any write |
| Whole-file hash | `wholeFileHashOf(data)` — a chain over fixed 2 MiB pieces | a sequential fold at exactly that stride; three incremental forms already exist (§4 D2) |
| "Do we already hold exactly these bytes" (`heldContentId`) | stored `ContentHash` and `ContentId` match, no unsent-content marker for that id, and a windowed compare of the stored file against the array | source-vs-store compare by ranges, **before** the stored file is overwritten |
| Local write | `fileDatabase.writeByteArray(filePath, bytes)`, straight onto the final path | ranged writes into a per-operation temp in `incoming/`, then `moveFile` |
| Thumbnails and motion thumbnail | decoders take a `ByteArray` on every platform | see D4 — this is the part that is not uniformly solvable |
| Media metadata | `readMediaMetadata(bytes)`, indexes the array | a positional reader; MP4's `moov` may sit at the end of the file |

## 3. Goals and non-goals

**Goals.** (1) Peak proxy memory for an upload independent of file size, for the file's bytes
themselves. (2) No size cap below what the store and quota allow. (3) No schema change. (4) Every
existing behaviour pinned by the tests in §7 unchanged.

**Non-goals.** Resumable uploads; changing the emit, the manifest or the hash; streaming *into*
image decoders (an image is decoded whole by every platform codec — D4 bounds it instead).

## 4. Design

**D1 — an `UploadSource`, not a `ByteArray`.** `uploadFile` takes a small interface:
`size: Long` and `readRange(offset: Long, length: Int): ByteArray`. Implementations: an in-memory
one (every existing caller keeps working, bit for bit), a path/staged-file one (JVM), a descriptor
one (Android), a file-URL one (iOS). `UploadFile(data)` stays as an op — it is the right one when
the caller genuinely holds bytes.

⚠ **Not every source can seek or knows its size.** An Android provider may hand over a pipe, or a
cloud-backed descriptor whose length is unknown until it has been read. Such a source declares
itself *sequential* (`size == null`, no `readRange`). The proxy spools it once into the operation's
temp file (D2) while hashing. Every later step (the D3 compare, the quota gate, the D4 reads) then
reads that temp file, which is seekable. A sequential source therefore costs one temp copy on
disk, never a second read of the source, which it could not do.

⚠ **The spool is bounded by free disk, not by the quota.** The quota gate needs the D3 answer
first: an unchanged replacement must succeed on a full realm (`ReplaceQuotaGateTest`), and whether
it is unchanged is known only once the whole source has been hashed. So for a sequential source
the order is spool → D3 on the temp → quota gate on the spooled size → move. The temp is not
stored content, so it does not count against the quota; the spool aborts when it would leave less
than a fixed margin of free disk, which is what protects the device. A new upload (not a
replacement) can never be a no-op, so its spool may also stop as soon as its count exceeds what
the quota still allows.

**D2 — one sequential pass writes and hashes.** The source is read in 2 MiB strides; each stride is
written with `writeBytesRange` into a temp file and folded into `IncrementalSha256`. At the end
the hash is known and the temp is moved onto `filePath`. This is the download path's shape
reused. The fold moves to `core-common` beside `wholeFileHashOf` — today it exists three times,
privately (`SyncObserver.wholeFileHashAt`, `foldPlatformContentHash` in drive-shared-logic, the
manifest applier).

⚠⚠ **The temp is unique per operation, and it lives outside the user-file namespace.** It goes in a
reserved `incoming/` directory beside the content root, on the same volume so the final move stays
a rename, named by a random operation id: never a fixed name, and never a suffix on `filePath`.
- *Unique:* two overlapping uploads or replacements of the same destination would otherwise write
  ranges into one file and interleave while each hashed only its own strides, so the file moved
  into place would match neither hash. The move is the commit point: the last move wins, and the
  file it puts in place is the one its hash describes.
- *Outside the namespace:* a file name is whatever the user chose, so no suffix is reserved. A
  user can upload `notes.tenshu-incoming-1234`, and a sweep matching a pattern under the content
  root would delete its stored bytes. No `FileDatabase` path maps into `incoming/`, so everything
  in it belongs to an upload operation.

A failed operation deletes its own temp, and a sweep at proxy start empties `incoming/` of files
left by a crash.

**D3 — the unchanged-overwrite no-op needs its own pre-pass.** An overwrite with identical bytes
must succeed on a full realm (`ReplaceQuotaGateTest`) and must not rewrite the file. That decision
needs the hash *before* the quota gate, and the gate must run before any write. So:

1. Replacing, the stored size equals `source.size`, the row carries a `ContentHash` and a
   `ContentId`, **and** no unsent-content marker exists for that `ContentId` → read the source
   once to hash it (no write), compare the hash to the stored one and then the bytes by ranges
   against the stored file. If identical, call `ensureContentOnRelay` for the kept `ContentId` (it
   re-emits under the same id when the relay cannot confirm it holds the content, which
   `UploadOverwriteReclamationTest` pins) and stop. ⚠⚠ The marker condition is the easy one to
   lose: a write interrupted between saving the row and journalling its bytes leaves row and bytes
   matching with nothing uploaded, and retrying the same overwrite is what finishes it. A byte
   compare alone would turn that retry into a no-op. These are `heldContentId`'s conditions and the
   no-op's relay check today; the refactor changes only where the compared bytes come from.
2. Otherwise → quota gate on `source.size`, then the D2 pass.

Only a same-size overwrite pays for two reads. Everything else is one. A sequential source (D1)
has no size up front, so it takes the spool-first order described there, and its D3 compare
reads the temp instead of the source.

**D4 — derived data is read from the stored file, under a decode budget.**
- *Media metadata:* `readMediaMetadata` gains a positional-reader form over
  `FileDatabase.readBytesRange`. It needs box headers plus the `moov` / `meta` payloads, and never
  reads `mdat` whole. ⚠ A HEIC/HEIF's EXIF item can live *inside* `mdat`: `readHeifExif` follows
  `iloc` to an absolute extent, and `aHeicYieldsTheCoordinateThatUsedToBeLost` pins the GPS and
  camera fields it yields. So the reader also does a bounded read of each extent `iloc` names for
  the EXIF item, wherever it sits. JPEG and PNG need only a prefix.
- *Video posters and motion thumbnails:* the decoders already accept something file-shaped —
  Android via a `MediaDataSource`, iOS via a temp path — so they move to a file-backed source over
  the stored file. JVM has no video decoder and stays as it is.
- *Images and PDFs:* every platform codec decodes from the whole encoded file. They keep doing so,
  from the stored file, **only below a budget** (proposed: 256 MiB encoded); above it the upload
  succeeds without stills. This is the one place the result differs from today — today such a
  file fails or exhausts the heap instead.
- ⚠⚠ **Skipped stills need a repair path, which does not exist yet.** The thumbnail backfill
  (`ThumbnailClassBackfill`) deliberately leaves out `LIST` and `LARGE`, because upload is assumed
  to have minted them, and iOS registers no backfill schedule (only Android and desktop call
  `registerThumbnailBackfillSchedule`). A file over the budget would therefore never get a default
  thumbnail, not get one late. So P3 also teaches the backfill to mint a missing `LIST`/`LARGE`
  when no decline sentinel is recorded for it, under the same budget, and registers the schedule
  on iOS. A file over the budget on every device gets a decline sentinel and shows the generic
  icon.

**D5 — desktop callers hand over a file, by staging it.** There is no JVM implementation of
`UploadSourceFile` / `ReplaceSourceFile` (`JvmFileServiceProxyImplementationFactory` handles only
`GetPreview`). It gets one, taking a **staged file name**, not an arbitrary path:

- Control cannot simply open the path the user picked in Drive. macOS consent is per binary, so
  Drive's file-dialog grant does not extend to Control
  ([`drive-upload-source-read.md`](drive-upload-source-read.md), the TCC paragraph), which is why
  that doc left desktop on `UploadFile(data)`.
- The Finder work already built the alternative: the client clones the file into the App Group
  container's staging directory and sends its name (`FileProviderStaging`, threat model T-C-16).
  The Drive desktop app and `FolderWatcher` use the same hand-off; on APFS the clone costs no
  extra disk.

This amends that doc's "leave desktop on `UploadFile(data)`" — the reason for it (no way to hand
Control a file) no longer holds.

**D6 — phones.** Android reads from the descriptor it is already handed, in strides, instead of
`readBytes()`. iOS reads from a file URL; whether PhotoKit can hand over a URL rather than data is
not established (§8).

## 5. What it costs

- **Disk, briefly:** an overwrite holds the old and the new file until the move.
- **A second read** for same-size overwrites (D3).
- **Stills above the budget arrive late** (D4), by the backfill extended in P3 rather than at
  upload; a file over the budget on every device keeps the generic icon.
- **A temp copy for sequential sources** (D1), and a reserved `incoming/` directory, emptied at
  proxy start (D2).
- **Plaintext staging** on desktop for the Drive app and `FolderWatcher` too — same mitigations and
  the same residual as T-C-16, which needs a line saying the directory now serves three callers.

## 6. Phases

Each is shippable alone and leaves every caller working.

| | What | Lifts |
|---|---|---|
| P0 | `UploadSource` seam with only the in-memory implementation; the hash fold in `core-common`. No behaviour change. | nothing — it is the refactor the tests in §7 must survive |
| P1 | D2 + D3 in `uploadFile`; JVM source-file ops over staging (D5); the Finder bridge uses them. | the ingest's in-memory copy for Finder uploads (⚠ **not** the 2 GiB cap, see below) |
| P2 | Drive desktop app and `FolderWatcher` stage instead of sending bytes. | the 256 MiB UDS frame cap; the client-side copy |
| P3 | D4: positional metadata reader, file-backed poster/motion sources, the decode budget, the backfill minting a missing `LIST`/`LARGE`, and its schedule on iOS. | the 2 GiB cap; the last whole-file reads on desktop |
| P4 | Android descriptor streaming, including sequential sources (D1), + file-backed `MediaDataSource`. | F2 on Android |
| P5 | iOS. | F2 on iOS |

⚠ **The 2 GiB cap lifts in P3, not P1.** After P1, `uploadFile` still hands the whole file to
`renderThumbnails` and `readMediaMetadata(data)` before it saves the row, and a file above 2 GiB
cannot be one `ByteArray`. So P1 keeps the bridge's `too_large` refusal
([`desktop-macos-integration.md`](desktop-macos-integration.md) §5b): it removes the ingest copy,
not the cap. P3 replaces both readers (D4), and only then is the refusal
dropped. P3 is also what makes memory flat.

## 7. The safety net

A refactor of `uploadFile` is guarded by, in `file-service-proxy` unless noted:
`UploadOverwriteReclamationTest` (held-bytes no-op, unsent marker, the multi-window compare),
`ReplaceQuotaGateTest` (gate before write; unchanged replace on a full realm),
`UploadFileOperationTest`, `ReplaceFileOperationTest`, `UploadStampsMediaMetadataTest`,
`UploadMintsMotionThumbnailTest`, `FileCreatedAtTest`, `FileModificationEmitTest`,
`UploadMintsTheFullStillLadderTest` and `ThumbnailLadderParityTest` (jvmTest),
`PlatformContentHashTest` (drive-shared-logic: the streaming fold equals `wholeFileHashOf`), and
the bridge round trips in drive-shared-ui.

⚠ **Gap:** nothing exercises a large or streamed source on the Android or iOS source-file ops.
P4 and P5 need that test written first. P1 needs two new ones: two overlapping replacements of one
destination each leave a file matching its own hash (D2), and an interrupted-then-retried overwrite
with an unsent marker still emits (D3). P3 needs one for the backfill minting a skipped `LIST`.

## 8. Open questions

1. **The decode budget (D4).** Is "no stills at upload above 256 MiB encoded, backfill later"
   acceptable, and is that the right number? It is the only user-visible change, and it needs the
   backfill extension in P3 to hold.
2. **Staging for the Drive desktop app (D5).** It widens what T-C-16's directory is used for.
   Alternative: keep the Drive app on `UploadFile(data)` and accept the 256 MiB cap there.
3. **iOS PhotoKit.** Whether an original can be had as a file URL without materialising it as data
   was not read (`IosPhotoLibrary.readOriginalBytes`).
4. **Android store write.** `AndroidFileDatabaseImpl.writeByteArray` silently writes nothing for a
   value that is not a `TByteArrayDto`. What `toTByteArray` yields on the Android proxy
   was not traced; P4 uses ranged writes and sidesteps it, but the silent no-op deserves its own
   look.
5. **MP4 above 4 GiB.** The box walker gives up on a 64-bit `largesize` with a non-zero high half
   (`MediaMetadata.kt`), so a `moov` after such an `mdat` is not found. Out of scope here;
   it becomes reachable once files that large can be uploaded.
6. **The no-emitter fallback** (`emitPropertyBlock(value = bytes)` in `uploadFile`) takes the whole
   value. Proposed: keep it for the in-memory source only and fail a streamed source there.

## 9. Docs this would amend when built

`drive-upload-source-read.md` (desktop decision, Q3), `file-content-addressing.md` (the "hash
computed once over the in-memory upload bytes" note), `media-processing-pipeline-sweep.md` (F2 and
its P2), `desktop-macos-integration.md` §5b (the upload limit), `takeout-import-and-recall.md`
(the per-member `ByteArray`), `retention-quota-model.md` (the `FileDatabase` SPI list, if it gains
an API), and threat model T-C-16.
