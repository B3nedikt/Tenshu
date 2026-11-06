# Drive — streaming (seekable) preview decrypt source (D2b)

**Status:** v1.2 — **S1–S4 shipped; S6a shipped; S6b built; S7
built** (iOS streaming, [`ios-drive-parity.md`](ios-drive-parity.md) §17, 2026-07-17).
(2026-06-30 → 07-01). Design locked, cross-platform contract = the pull range-source (§3.5).
**S1:** `FileChunkDecryptor`.
**S2a:** `StreamingContentSession` + `StreamingContentRegistry` (bounded-memory + seek engine).
**S2b:** the pull op IPC surface on `SyncService`
(`OpenContentStream`/`ReadContentRange`/`CloseContentStream`, generic-triple addressing per SQ8).
**S3:** client `FilesRepository.openStreamingContent(path)` → `StreamingContentHandle` + Android
`openProxyFileDescriptor` fd + `isStreamingPreviewSupported()`; A5 amendment landed. **S4 (this
PR):** `VideoPreviewViewModel` branches on `isStreamingPreviewSupported()` → streams via a
`VideoSource.Stream` (D2b) else falls back to the D2a temp file; the `VideoView`-vs-fd question is
resolved by a **`content://` provider** (`StreamingVideoProvider`, app-internal, `exported=false`)
that serves the seekable proxy fd to `MediaPlayer` unchanged. Path-selection unit-tested
(`VideoPreviewViewModelTest`); the provider/fd/`VideoView` are framework paths → on-device verify.
**S6a (this PR):** expose the pull ops to JS end-to-end — a `@JsExport ContentStream` bridge
(`:tenshu-sdk-js`) over `client.syncService.openContentStream/readContentRange/closeContentStream`
(decrypt runs in the in-process JsRuntime, Noble AEAD), `@tenshu/sdk` TS surface (`native.d.ts` +
`content.ts`), apex gateway handlers (`fs.openContentStream`/`readContentRange`/`closeContentStream`),
drive proxy wrappers. **S6b (this PR):** the web consumer — a **logic-free Service Worker**
(`public/tenshu-stream-sw.js`) intercepts a virtual `/__tenshu_stream__/<token>` URL and forwards
each `Range` request (via a per-read `MessageChannel`) to its window client, which reads the
covering range through the S6a bridge and returns a `206` plan; a `'video'` preview kind renders
`<video src>`; `openVideoStream(path)` opens/registers/tears-down the session. The SW→window→apex→
runtime channel keeps the SW keyless (House Rule #1). Range parsing + response planning are pure +
unit-tested (`streamProtocol` / `videoStream` tests); SW registration + `<video>` playback are
browser-E2E (pending). **S7 (built):** iOS video — a Swift `AVAssetResourceLoaderDelegate` over a
custom-scheme `AVURLAsset` fulfils each `dataRequest` by pulling ≤256 KiB ranges through
`ReadContentRange` (the commonMain `openStreamingContent` handle, wrapped for Swift as
`IosContentStreamSession`, `ByteArray`→`NSData` in Kotlin); replaces P4's D2a whole-file temp for
video, D2a stays the offline fallback. ⚠ Under route-1 iOS Drive holds bytes in-process, so — unlike
the §3.5 "first-party uses the App-Group file, no streaming" framing (route-2 era) — Drive IS the
streaming consumer, the Android-S4 twin (delegate ⇄ fd/provider). `IosContentStreamSessionTest`
(4, range/EOF/close/gate-null) + both Drive schemes build; playback framework-level → on-device
(P7). **Next: S5** (PDF reuse) — optional. Fleshes out the deferred
**D2b** from
[`drive-content-preview.md`](drive-content-preview.md) §5: replace the whole-file
decrypt-to-temp-file (D2a, shipped for video + open-externally) with a **seekable source that
decrypts on demand**, so a large file previews without ever holding the whole plaintext in
memory or on disk. Opened because the alternative — a large-file *gate* (Q4) that refuses
in-app preview above a size — is a band-aid; streaming is the right answer. The crypto/storage
substrate is the chunked content store (ADR 0017) — independently AEAD-sealed 2 MiB chunks make
range-decrypt already possible (§1); D2b is the **read side** finally using it, with no crypto /
format / migration change.

**v1.1 reframe (this revision).** Viewed across all four targets (Android / iOS / desktop / web),
the *portable* contract is a **pull range-source** — `read(offset, length)` over a warm session —
**not** the `Stream` push channel the v0.2 draft adopted. Every platform's native player hook is
already pull/range (Android `ProxyFileDescriptorCallback.onRead`, iOS
`AVAssetResourceLoaderDelegate`, web Service-Worker `Range`, desktop socket read), and the v0.2
push design *re-inverts back to pull* at every consumer (the §3.3 ring buffer **is** that
re-inversion). So v1.1 **settles the v0.2 push-vs-pull steer** (new **§3.5**): **the contract is
the pull range-source** — the **abandoned v0.1 `OpenContentStream` / `ReadContentRange` /
`CloseContentStream`** shape, revived through the multiplatform lens — and the `Stream`-reuse is
dropped (kept only as a documented Android fallback, §3.5). **Decided 2026-06-30** (supersedes the
v0.2 steer). (History: v0.2 reworked §3 onto `Stream` rather than range-RPC ops; v1.0 locked the
format question §3.4 — both maintainer steer. §3.0, §3.4, and §4 are transport-independent and
stand unchanged under either contract.)

Companion reading: [`drive-content-preview.md`](drive-content-preview.md) §5 (the D2a/D2b
split), [ADR 0017](../adr/0017-file-content-two-plane-channel.md) (the chunked content
channel this rides), [`sync/threat-model.md`](../sync/threat-model.md) A5 (the plaintext-on-
device contract), and [`core-chain-entity-typing.md`](core-chain-entity-typing.md) §2.4 (the
`ClientConnection.tokenize` OOB byte seam).

---

## 1. The substrate already exists (this is why D2b is plumbing, not a migration)

Per **ADR 0017**, a file's content is **not** one sealed blob. It is stored as the
content-plane half of a two-plane channel:

- **Chunked.** Content is split into fixed **2 MiB** chunks (`SyncObserver.chunkSizeBytes =
  2 * 1024 * 1024`; `chunkCount = ceil(totalSize / 2 MiB)`, ≥1).
- **Independently AEAD-sealed.** Each chunk is its own XChaCha20-Poly1305 ciphertext under the
  realm `sharedVolumeKey`, with AAD bound to `(fileId, chunkIndex, chunkCount)`
  (`fileChunkAad`, `SyncObserver.kt`). Content-addressed as `blob-<sha256(cipher)>` in the
  blob store; chunks are immutable.
- **Manifested.** A small `FileContentManifestPayload` (on the encrypted metadata plane)
  carries `(fileId, propertyId, chunkCount, totalSize, wholeFileHash, chunks=[FileChunkRef(ref,
  cipherHash)…])`.

Because **any single chunk fetches and decrypts in isolation**, range-decrypt — the thing a
seekable source needs — is already possible at the crypto + storage + relay layers. ADR 0017
explicitly rejected the whole-file-single-ciphertext alternative precisely because it had "no
selective fetch on the read side". D2b is the read side finally using that selectivity.

**What is *not* streaming-ready today** (the three things this proposal builds):

1. The read path **reassembles the whole file to local disk and verifies it before surfacing a
   single byte.** `FileContentManifestApplier.apply` loops every chunk → `writeBytesRange` into
   a `.tenshu-partial` temp → checks `totalSize` + `wholeFileHash` → `moveFile` onto the real
   path. The "no partial surface" gate is load-bearing for *integrity* (a tampered/truncated
   set must never appear) — §4 keeps per-chunk integrity while relaxing the whole-file gate for
   the streaming consumer.
2. **The delivery channel.** Drive's `getRemoteFile` returns a whole `FileByteArray`
   (`FileByteArrayDto.get() = file.readBytes()`) — a *pull*, but whole-file. Two candidate shapes
   for a *ranged* delivery: (a) a **ranged pull** — `getRemoteFile` with `(offset, length)` over
   the ordinary op request/response channel (v1.1's recommended contract, §3.5); or (b) the
   general **`Stream` subsystem** (`core` + `core-common`), which pushes typed `StreamElement`s
   incrementally (the v0.2 draft, §3.1–3.3). The `Stream` route's gap for *content*: it is
   **server-push with unbounded client retention** (`StreamInteractorImpl` keeps every `Value`
   element for replay; `flow()` uses an `UNLIMITED` channel) and has **no client→proxy demand/seek
   back-channel** — fine for small reactive *lists*, but for ~250 content chunks it would buffer
   the whole 500 MB file on the client, so push needs "new control blocks" (§3.2) *and* a
   re-inversion back to pull at the consumer (§3.3). The ranged-pull route needs neither — see
   §3.5 for why it is the portable choice.
3. The **viewers consume whole bytes** — `VideoPreviewViewModel` writes the whole blob to a
   temp file (`writePreviewTempFile`) and hands `VideoView` a path.

The relay GET (`GET /sync/content/{ref}`, `RestContentBlobStore.getChunk`) is whole-**blob**,
not HTTP-Range — but a blob *is* one 2 MiB chunk, already fetched selectively per ref, so
byte-range on the relay is unnecessary for chunk-granular streaming (noted as an optional
later refinement in §7).

---

## 2. Scope — video-first; what does and doesn't stream

"Stream a preview" is not uniform. The honest per-consumer picture:

| Consumer | Streams? | Why |
|---|---|---|
| **Video** ✅ (the target) | yes | The real OOM case (100s of MB). A player issues seeks + forward reads — a perfect fit for range-decrypt; bounded memory, instant start. |
| **PDF** ◑ (follow-up) | yes, benefits | `PdfRenderer`/PDFBox want a *seekable fd*; a lazy chunk source provides one. But PDFs are usually small, so v1 leaves PDF on the temp-file path. |
| **Image** ✗ | no | You don't stream a still — you decode it. The fix for a huge image is bounded-memory **downsample-on-decode**, a separate concern (not D2b). |
| **Open externally (D3)** ✗ | no | The third-party app reads the *whole* file; the hand-off is inherently whole-file. The D3 temp file stays. |

**Decision S1: D2b targets video on Android first.** PDF reuse, desktop, and iOS are
follow-ups (desktop video is itself unsupported today — Q3; the desktop UDS tokenizer is
unbuilt). Images and open-externally are out of scope by nature.

---

## 3. Design — content delivery over the IPC boundary

> **⚠ v1.1 — superseded:** §3.1–§3.3 below describe the **v0.2 push-stream** design (content as
> `Stream` chunk elements). v1.1 **decided against it** — see **§3.5**, which sets the contract to a
> *pull range-source*. §3.1–§3.3 are retained as the documented Android fallback and for the
> rationale they capture; the live design is §3.5. §3.0 (the two-modes analysis) and §3.4
> (format-agnostic) are transport-independent and stand.

**Decision S2 (v0.2 — SUPERSEDED by §3.5, pull): ride the existing `Stream` IPC subsystem; do NOT build parallel range-RPC ops.**
The channel already exists end to end — a `StreamOperation`-trait op opens a `streamId`; the
proxy pushes `StreamElement.Value`/`Removed`/`End` via `clientConnection.send(...)` framed as
`StreamFramer.TYPE_CALLBACK`; the client dispatches through `StreamInteractor`/`StreamCallback`
and the `flow()` collector. `ListStreamImplementation` + `StreamRegistry` are merely the
*list/DB-backed* producer. Content streaming reuses the framing, `streamId` multiplexing,
callback dispatch, and lifecycle wholesale. Three pieces (§3.1–3.3) — but first, *where* it sits
relative to `Stream.kt`:

### 3.0 Two modes over one transport — extend the wire, layer the semantics

The existing `Stream` is one streaming *archetype*; content needs the other. They share a wire,
not a flow-control contract:

| | **Reactive/observable** (today's `.flow()`) | **Backpressured transfer** (content) |
|---|---|---|
| Driver | server-pushed (DB deltas) | consumer-pulled (demand) |
| Retention | **retain all** `Value`s, replay to late subscribers | **don't retain** — drop once consumed |
| Cardinality | small, sparse, live-updating *set* | large, ordered *sequence* (seekable) |
| Flow control | none needed (sparse) | **required** (else the 500 MB file buffers) |

**Decision (modes): a second *mode beside* `Stream`, not a generalization *of* it.** Concretely:

- **Extend (shared, ~free) — the transport/element/routing layer.** It is *already* mode-agnostic:
  `DefaultInteractorProvider.dispatchElement` routes any `StreamElement<*>` by `streamId`;
  `RemoteConsumeStack` feeds every element in; `StreamFramer.TYPE_CALLBACK` + the
  `clientConnection.send(StreamElement)` producer seam + the `StreamOperation` trait are all
  payload-agnostic. A content chunk rides this with **at most one new `StreamElement` subtype**.
- **Layer above (content-only) — the interactor/collector semantics.** `StreamInteractorImpl`
  (retain-all replay) and `flow()` encode the *reactive* contract; content gets its **own**
  `StreamDispatcher`/collector (non-retaining, demand-driven). The list path is **untouched**.
- **The one shared seam to add:** `DefaultInteractorProvider.buildStreamInteractor` currently
  *hardcodes* `StreamInteractorImpl` for every `StreamOperation`. Add a marker (e.g. a
  `TransferStreamOperation` trait) so it selects the content collector for a content stream —
  a small, clean extension point, not a rewrite of the list interactor.

**Would backpressure help the existing `.flow()` case? No — it's a net downside there.** The
list case wants deltas pushed *immediately* (live UI) and *retained* for replay to late/multiple
subscribers; demand handshakes add round-trips + latency to sparse updates for zero benefit,
non-retention breaks replay, and a "window" is meaningless for a live-updating set. (Large lists
are already served by `core-paging`/`TenshuPagingSource`, a separate mechanism — there is no
latent huge-`.flow()` case that backpressure would rescue.) So we deliberately keep two modes;
unifying the *contract* would make both worse.

Three pieces:

### 3.1 Proxy — a content-stream producer (sibling to the list producer)

Factor the per-chunk fetch + cipherHash-gate + AEAD-decrypt (the body of
`FileContentManifestApplier`'s loop, incl. the GR-7 trial-decrypt across retired keys) into a
shared **`FileChunkDecryptor.decryptChunk(manifest, index): ByteArray`**, used by both the
existing reassemble path and the new producer (no logic duplicated).

Add a **content-stream op** `StreamContent(path, startChunkIndex)` (`StreamOperation` trait,
sibling to `Stream`) + a **`ContentStreamImplementation`** (sibling to `ListStreamImplementation`)
whose producer is the chunk-decrypt source — *not* a `StreamRegistry` DB listener. It pushes each
decrypted chunk forward as a stream element from `startChunkIndex`, then `End`. Delivery is the
same `executionContext.clientConnection.send(StreamElement…)` the list path uses.

### 3.2 New control blocks — flow-control + seek (the load-bearing addition)

This is the "new control blocks." The list path gets away with push-only + unbounded retention
because list deltas are small and sparse; a 500 MB content stream cannot. Two additions:

- **A content-chunk element** — either reuse `StreamElement.Value<TByteArray>` carrying the chunk
  bytes, or a thin `StreamElement.ContentChunk(chunkIndex, bytes)` subtype (cleaner: it self-
  describes position for the client's ring buffer). One `StreamElement` schema addition.
- **A client→proxy demand/seek back-channel** — today streams are one-directional. Add small
  control ops on the open `streamId`:
  - `ContentDemand(streamId, ackedThroughChunk)` — flow control. The producer keeps only a
    bounded **window** (e.g. W = 4 chunks) ahead of the client's ack and *waits* rather than
    draining the whole file. This is what makes memory bounded — without it the push stream
    buffers everything (§1.2).
  - `ContentSeek(streamId, chunkIndex)` — repositions the producer + flushes the window for an
    in-stream seek. (`EndStream` + a fresh `StreamContent(path, k)` is the cruder fallback and
    needs no new op, but costs a stream restart per seek.)

Client retention must also change for content: a content-specific stream collector (or a
"consumed" flag) that does **not** retain `Value` elements after the consumer reads them
(`StreamInteractorImpl` replays all elements to late subscribers — correct for a list snapshot,
fatal for 250 chunks).

### 3.3 Client — bridge the chunk stream to a seekable fd

The viewers want a *pull* seekable fd; the stream is *push*. Bridge them with a bounded ring
buffer: a Drive-side **`StorageManager.openProxyFileDescriptor`** (API 26+) whose
`ProxyFileDescriptorCallback.onRead(offset, size, data)` blocks until the chunk stream has
delivered the chunk(s) covering `offset` — issuing `ContentDemand` as the player consumes and
`ContentSeek` when `offset` falls outside the window — then copies out. The resulting
`ParcelFileDescriptor` is a *normal* seekable fd Drive hands to **`VideoView` unchanged** (and
later `PdfRenderer`); the consumer never knows it's lazy.

- **API 24–25 fallback:** `openProxyFileDescriptor` is API 26+; below it, fall back to the
  existing D2a temp-file. Small device slice; no behaviour regression.
- Rejected alternative: a `MediaDataSource`(API 23+)→`MediaPlayer` bridge — video-only and forces
  `VideoView`→`MediaPlayer`+`Surface`. The proxy-fd is consumer-agnostic (kept as the fallback if
  proxy-fd proves flaky on some OEMs).

Client surface: `FilesRepository.openStreamingContent(path): StreamingContentHandle` (wraps the
`StreamContent` op + the ring buffer + demand/seek). `VideoPreviewViewModel` uses it instead of
`writePreviewTempFile` when supported; `onCleared` ends the stream + closes the PFD.

### 3.4 Format-agnostic — opaque bytes; the player owns the container

**Decision S-fmt: the layer-2 protocol streams opaque plaintext byte ranges; it is NOT
container-aware. No MP4/MKV/WebM parsing anywhere in the stream layer.** This is forced by the
storage layer and matched by the player contract:

- **Storage is already opaque.** A file's chunks are arbitrary **2 MiB cuts of the plaintext
  byte stream** (`SyncObserver`), content-addressed by *ciphertext* hash — boundaries are not
  aligned to container atoms/clusters/frames. Decrypt + concatenate = the original file,
  byte-identical. The only natural unit is "plaintext bytes at offset X"; re-deriving container
  structure the store deliberately doesn't track would be pure cost.
- **The player IS the demuxer.** ExoPlayer/`MediaPlayer`/AVPlayer/`PdfRenderer` parse the
  container + seek to the moov/cues/xref themselves; they want only a *seekable byte source*.
  Our job is exactly "given offset+length, return those plaintext bytes" — one codepath for
  **every** file type (video, PDF, anything), which is the whole point.
- **No client-side transcode/remux.** "Get it in the right format for ExoPlayer" means *wrap the
  byte source in the player's API shape* (an fd / `DataSource` / `AVAssetResourceLoaderDelegate`)
  — **not** transform the bytes. We hand over the original file bytes; the player does the rest.

Where the container *does* leak in — at the **edges (client glue/UX), not the wire**:

1. **Seek must be random-access, not forward-only.** A progressive MP4 with `moov` at the end
   (any non-"faststart" file) makes the player's *first* read jump to the **tail**, then back to
   byte 0. So `ContentSeek` + the ring buffer must support **arbitrary** offsets from the start —
   this is precisely why the seekable-fd / ResourceLoaderDelegate design beats a pure forward
   pipe. (A behavioural requirement on the control blocks, not container parsing.)
2. **A MIME/extension hint** is passed to the player to pick the demuxer (e.g. ExoPlayer with a
   custom `DataSource`, or AVPlayer's resource loader) — derived from the filename
   (`fileCategoryForName`/extension), **client-side player config, not on the wire.**
3. **Codec coverage is a player choice**, not a protocol concern — streaming + broader format
   coverage may be what finally motivates **Media3/ExoPlayer over the Phase-4 `VideoView`**
   (tracked as Q3 / SQ6); the byte protocol is identical either way.

**Rejected — aligning chunk boundaries to the container (keyframe/GOP/fragment).** It would buy
nothing and cost plenty:
- *No benefit:* the manifest is **already a seek index**. Because chunks are *fixed* 2 MiB,
  any byte offset maps by arithmetic — `chunkIndex = offset / 2 MiB`, `manifest.chunks[i].ref`,
  `intra = offset % 2 MiB` — O(1), and the offsets are *derivable* (no offset table stored). The
  player seeks by **byte offset** (read from the container's own moov/cues/xref) and demuxes
  straight across our boundaries; it never sees chunks. Alignment would only matter for
  format-level segment operations (HLS/DASH packaging, partial decode) that we don't do — and
  can't, server-side, under HR#1.
- *Real cost:* a **format-aware chunker in the encrypt/upload path** (per-container parsing,
  video-only — PDF/zip/arbitrary files have no alignment); **variable chunk sizes** that forfeit
  the derivable fixed-stride math (forcing an explicit per-chunk offset table in the manifest)
  and can run tiny→huge (breaking the bounded decrypt-buffer / bounded-fetch property); and
  **coupling at-rest encrypted storage to media semantics** at the most foundational, currently
  format-blind layer. Fixed-size opaque chunking + manifest-as-index is strictly better.

### 3.5 The cross-platform contract: a pull range-source (v1.1)

§3.1–§3.3 ride the push `Stream` channel because it already exists end-to-end *on Android*. The
moment you look past Android, that choice inverts the natural data flow twice. The portable shape
is a **pull range-source**:

```
interface ContentSource {                            // backed by FileChunkDecryptor (§3.1, S1)
    fun read(offset: Long, length: Int): ByteArray   // ≤256 KiB, AEAD-verified per covered chunk
}
```

opened once per playback (a warm session holding the manifest + a small decrypted-chunk LRU),
read by range, closed on teardown — i.e. the **abandoned v0.1** `OpenContentStream` /
`ReadContentRange` / `CloseContentStream` op trio. `ReadContentRange` returns ≤256 KiB inline,
under the Binder cap; it is just `getRemoteFile` with `(offset, length)` and a session handle —
the op request/response channel that already exists end to end (and is already bridged to web as
`fs.getData`).

**Why pull, not push.** Every platform's native seekable-source hook is *already* a pull/range
callback:

| Platform | Native player hook | Shape | Binds to `ReadContentRange` by |
|---|---|---|---|
| **Android** | `ProxyFileDescriptorCallback.onRead(offset, size, data)` | pull | calling it directly in `onRead`; the resulting `ParcelFileDescriptor` feeds `VideoView` unchanged |
| **iOS** (3rd-party) | `AVAssetResourceLoaderDelegate` | pull (range) | fulfilling each `dataRequest`'s `requestedOffset`/`requestedLength` from it |
| **Web** | Service Worker `fetch` + HTTP `Range` | pull (range) | a SW that decrypts the covering chunks in JS and answers `206 Partial Content` |
| **Desktop** | socket read at offset | pull | a plain ranged read over UDS |

The v0.2 push design (§3.2/§3.3) must therefore **re-invert** push→pull at *every* consumer: the
§3.3 Android ring buffer exists solely to turn the pushed stream back into the pull `onRead`
shape, and iOS/web/desktop would each need their own re-inversion. A pull contract deletes the
`ContentDemand` / `ContentSeek` control-block vocabulary *and* the per-platform re-inversion —
request/response is inherently backpressured (the consumer asks for exactly the bytes it will
play) and inherently seekable (any `offset`, no in-stream-seek sub-protocol).

**Decision S2′ (v1.1, DECIDED 2026-06-30): the contract is the pull `ReadContentRange` session;
each platform binds its native pull hook to it.** The `Stream`-channel reuse (§3.2) is dropped from
the live design — retained only as a documented Android fallback should the push form ever prove to
feed `openProxyFileDescriptor` more smoothly than a direct blocking `onRead`→op call (the §3.3 ring
buffer already argues against it). This supersedes the v0.2 maintainer steer.

> **What push buys, and why pull keeps it.** Push lets the *producer* prefetch/decrypt ahead (SQ1
> read-ahead) and hold warm decrypt state. Pull keeps both: the warm state lives in the
> `OpenContentStream` session's LRU, and read-ahead becomes a *client-side* speculative
> `ReadContentRange(K+1)` issued while the player consumes K — a few lines in the fd bridge, not a
> wire-level flow-control protocol. Net: pull is strictly less machinery for the same behaviour.

**Per-platform reality — all simpler than Android, in priority order:**

- **Android** (the motivating case). Cross-process Binder + the OOM that opened D2b. `onRead` →
  `ReadContentRange` over a session; API < 26 → D2a temp-file fallback. This is where S2–S4 effort
  belongs.
- **iOS.** First-party already shares plaintext via the **App Group** container (UDS + shared
  files, `protocol/ios-ipc.md`) — within the Tenshu Team ID there is *no* streaming problem to
  solve. Streaming is needed **only for third-party apps** (App Groups are team-scoped), where
  `AVAssetResourceLoaderDelegate` is Apple's first-class on-demand range loader — a near-perfect
  fit for `ContentSource`, and the **same consumer a macOS-native shell would use** (SQ6 option c).
  Lowest urgency, cleanest binding.
- **Web.** Best fit, and *already the architecture*: the web proxy is "split between a browser-side
  **service worker** and a server-side ciphertext relay" (`web/architecture.md` §1). A SW
  intercepts `Range` requests for a virtual content URL, fetches the covering encrypted chunks
  from the relay, **decrypts them in JS** (the Phase-4 crypto port), and answers `206` — then
  `<video src=…>` is the demuxer (exactly §3.4). Two traps: **(a)** `URL.createObjectURL(wholeBlob)`
  is the web D2a — it pulls the whole file into the tab → OOM; avoid for large media. **(b)** MSE /
  `SourceBuffer.appendBuffer` is the *wrong* tool here — it needs container-aware fragmented
  MP4/WebM, which conflicts with opaque 2 MiB cuts (§3.4); the SW-`Range` path keeps the player as
  demuxer and the wire format-agnostic.
- **Desktop.** Transport is trivial (UDS is a plain byte stream, no Binder cap; the filesystem is
  free, so even D2a works), but **there is no embeddable video player on Compose Desktop** (SQ6) —
  so desktop streaming is *moot until a player lands*, not a transport problem. Don't design for it
  now.

Only Android needs the heavy lifting; iOS / web / desktop are each a thin native binding onto the
same `ContentSource` (or, for first-party iOS, no binding at all).

---

## 4. Integrity — what streaming gives up, and what it keeps

The reassemble path verifies the **whole-file hash + total size** before *any* byte surfaces.
A seekable consumer reads chunk K (a seek) without having read 0…K-1, so that up-front
whole-file gate cannot hold. What **streaming keeps**, per chunk, at read time:

- **Authenticity + integrity of each chunk** — the cipherHash gate + the XChaCha20-Poly1305
  AEAD tag. A relay serving wrong/flipped bytes for a ref is caught.
- **Position binding** — the AAD `(fileId, chunkIndex, chunkCount)` catches a reordered,
  cross-file-spliced, or count-mismatched chunk.
- **Manifest authenticity** — the manifest rode the encrypted, authenticated metadata block.

What it **defers / relaxes**: the whole-file `wholeFileHash` chain and truncation (a missing
*trailing* chunk) aren't confirmed up front — they're only observed if/when the consumer reads
that far. For **preview/playback this is acceptable** (we render bytes, we don't trust-then-
execute them), and every byte shown is still individually authenticated to the right position
of the right file. **Decision S3: the streaming source verifies per-chunk on read; whole-file
verification stays the contract only on the reassemble path** (open-externally, download-for-
offline, anything that produces a durable whole-file artifact keeps using the gated applier).

---

## 5. Threat-model (proposal-level; A5 touch only, no relay/HR#1 change)

- **Disk exposure — strictly *better* than D2a.** Streaming holds only a few decrypted chunks
  in **memory** (the LRU) on Control and a single read's bytes transiently on Drive; the whole-
  file plaintext **never lands on disk** for a streamed video. This removes the D2a temp-file
  (the current A5 amendment's "transient plaintext on disk") for the streaming path.
- **IPC.** Chunk elements ride the **existing `Stream` channel** (the same `TYPE_CALLBACK`
  framing the list streams already use) — no new transport, no relay traffic, no key material
  crosses, no protocol/data-shape change. **House Rule #1 intact.**
- **Integrity delta** (§4) — worth a one-line A5 note: streamed bytes are authenticated
  per-chunk + position-bound, but the whole-file hash/truncation gate is relaxed for the
  render-only consumer.

No relay or sync-protocol change ⇒ no relay-side threat-model amendment; an **A5 amendment**
(device-local, and a net *reduction* in disk exposure) lands with the implementing phase per
House Rule #2.

---

## 6. Phasing (each independently shippable)

S1 is the portable core and is transport-independent. The §3.5 push-vs-pull question is
**settled: pull** (decided 2026-06-30); the rows below build the pull `ReadContentRange` contract.

| Phase | Scope | Notes |
|---|---|---|
| **S1** ✅ | Proxy: `FileChunkDecryptor` extraction (out of `FileContentManifestApplier`) | **Shipped.** Internal, byte-identical reassemble path; `decryptChunk(index)` is the shared unit. Tests: in-order round-trip, out-of-order (seekable) decrypt, cipherHash-mismatch + missing-blob gate fails |
| **S2a** ✅ | Proxy core: `StreamingContentSession` (range assembly across chunks + bounded decrypted-chunk LRU over a `FileChunkDecryptor`) + `StreamingContentRegistry` (handle→session) + the shared `FileContentChunkSizeBytes` stride constant | **Shipped.** The bounded-memory + seek engine, transport/op-independent. Tests: boundary-spanning + intra-chunk ranges, EOF clamp, backwards-seek, LRU bound, tamper-fails-closed, registry lifecycle |
| **S2b** ✅ | The pull **op surface** (IPC) on `SyncService`: `OpenContentStream(entityType,entityKey,propertyId)`→`ContentStreamHandle` / `ReadContentRange(handle,offset,length)`→`TByteArray` / `CloseContentStream(handle)`, all `IoOperation` (House Rule #3). `OnDemandResolutionImpl` gains the open/read/close methods over a `StreamingContentRegistry`, reusing its realm-routed fetch + `SyncProcessor.openContentStream` (manifest→session, no reassemble) | **Shipped.** Generic-triple addressing (SQ8 — covers personal + shared in one op). Tests: `ContentStreamOpsTest` round-trip + `ContentStreamImplementationsTest` per-impl |
| **S3** ✅ | Client `FilesRepository.openStreamingContent(path)` → `StreamingContentHandle` (fills the personal `(File.ID, path, File.Data.id)` triple, calls the S2b ops) + Android `openProxyFileDescriptor` fd (`onRead`→`readContentRange`, `onRelease`→`closeContentStream`) + `isStreamingPreviewSupported()` (API 26+, desktop false) | **Shipped.** A5 amendment landed. `StreamingContentHandleTest` (delegation); the fd is framework code (`StorageManager`/`ProxyFileDescriptorCallback`) → on-device pending. The API<26 fallback *selection* + fd→player consumption are S4 |
| **S4** ✅ | Wire `VideoPreviewViewModel` (Android): branch on `isStreamingPreviewSupported()` → stream via the fd vs the D2a temp-file; feed the fd to the player; `onCleared` closes the stream | **Shipped.** ⚠ resolved: `VideoView` consumes a URI, not a raw fd → a small app-internal `content://` provider (`StreamingVideoProvider`, `exported=false`/no grants) serves the `openProxyFileDescriptor` PFD to `MediaPlayer` so `VideoView` + `MediaController` stay **unchanged** (chosen over `MediaPlayer.setDataSource(fd)` + a hand-rolled `SurfaceView`, which would rewrite the proven player; the provider is the blessed use of `openProxyFileDescriptor`). New `VideoSource` (File\|Stream) replaces the bare path; `VideoPreviewUiState.videoSource`. The proxy fd's `onRelease` owns the session close; `onCleared` closes as a backstop. Tests: `VideoPreviewViewModelTest` (path selection — streaming success / open-fail fallback / unsupported). Provider + fd + `VideoView` = framework → on-device verify |
| **S5** (opt) | PDF reuse — hand the Android fd to `PdfRenderer` | Reuses S1–S3 wholesale |
| **S6a** ✅ | **Web ops surface**: expose the pull ops to JS — `@JsExport ContentStream` (`:tenshu-sdk-js`) over `client.syncService.{open,read,close}ContentStream` (decrypt in the in-process JsRuntime, Noble AEAD) + `@tenshu/sdk` TS (`native.d.ts`/`content.ts`) + apex gateway handlers + drive proxy wrappers | **Built (this PR).** Personal-file triple filled Kotlin-side (`openStream(path)`); shared rides the `SharedFile` triple later. Verified: Kotlin/JS compiles, apex+drive `tsc`, `proxy.test.ts` routing (3 new). No SW/UI → S6b |
| **S6b** ✅ | **Web SW consumer**: a Service-Worker `Range` shim on `drive.tenshu.app` intercepting `<video src>` requests → `readContentRange` (via the S6a bridge) → `206`; add a `'video'` preview kind + `<video>` element | **Built (this PR).** ⚠ resolved SW-vs-window-context: the SW (`public/tenshu-stream-sw.js`) is **logic-free** — it forwards each `Range` request over a per-read `MessageChannel` to its window client (`event.clientId`), which owns the apex bridge + the token→handle map and returns a `206` plan; the SW just builds the `Response`. So all parse/plan/read logic is window-side + unit-tested (`streamProtocol` range parsing, `videoStream.buildStreamResponse`), and the SW stays keyless (House Rule #1). `openVideoStream(path)` opens the session, registers the SW (skipWaiting + `clients.claim`), and returns the virtual URL + a `close`; `useFileOpener` branches video → stream (skipping the whole-file `getData`), other kinds unchanged; shared videos stay on the download fallback (Mode-B streaming later). SW registration + `<video>` playback = browser paths → E2E verify |
| **S7** (opt) | **iOS third-party**: `AVAssetResourceLoaderDelegate` → pull contract | §3.5; first-party uses the App-Group shared file (no streaming). Shares the consumer with a macOS-native shell (SQ6c) |
| **S8** (opt) | Relay HTTP `Range` on `GET /sync/content/{ref}` for sub-chunk seeks | Only if 2 MiB fetch granularity proves too coarse in practice |
| **S9** (opt) | Desktop video | Blocked on a *player* (§7 SQ6), not transport — UDS already carries the contract |

---

## 7. Open questions

- **SQ1 — chunk LRU size + read-ahead.** Start with 2–4 chunks, no prefetch; measure on-device
  whether a forward read-ahead (decrypt chunk K+1 while serving K) is needed for smooth
  playback before adding it.
- **SQ2 — session lifecycle on backgrounding.** Control may be suspended mid-playback (the
  same wake-window issue route-1/iOS hit). For Android Drive↔Control (Binder, both foreground
  during playback) this is fine; revisit for cross-process suspension.
- **SQ3 — proxy-fd OEM reliability.** `openProxyFileDescriptor` has had OEM-specific quirks;
  S3 keeps the `MediaDataSource` path as a documented fallback if the on-device sweep finds
  problems.
- **SQ4 — relay Range (S6).** Worth it only if a future smaller chunk size or sub-chunk seek
  latency demands it; deferred until measured.
- **SQ5 — element type for chunks (push variant only).** Reuse `StreamElement.Value<TByteArray>`
  (zero schema change, but the client must infer position from arrival order) vs a
  `ContentChunk(chunkIndex, bytes)` subtype (one schema addition, self-describing — safer with
  seek/reorder). Leaning `ContentChunk`. **Moot if the pull contract (§3.5, recommended) is
  chosen** — pull carries `(offset, length)` in the request, no stream element at all.
- **SQ7 — push vs pull (§3.5). RESOLVED 2026-06-30: pull.** The pull `ReadContentRange` session
  contract is the cross-platform spine; `Stream`-reuse is dropped (kept only as a documented
  Android fallback). The one thing push buys — producer-driven read-ahead — pull keeps via a
  client-side speculative `ReadContentRange(K+1)` over a warm session. Every platform binding
  (S3/S6/S7) inherits this contract.
- **SQ6 — desktop player strategy.** The desktop blocker is *not* transport (the `Stream`
  channel + the chunk-decrypt producer are platform-agnostic and run over UDS today) — it is
  that **Compose Desktop has no native video host**: Skiko draws into one surface, `SwingPanel`
  bridges only AWT/Swing not Cocoa, so `AVPlayer`/`AVKit` can't be embedded (the same wall §4.1
  of `drive-content-preview.md` hit for PDFKit). Options: (a) bundle **VLCJ/GStreamer** (native
  libs, the lib-heavy route — `drive-content-preview.md` Q3); (b) **accept open-externally** on
  desktop (D3 already ships this — macOS hands the file to QuickTime/`AVPlayer`, native, just not
  embedded); (c) **a native macOS (AppKit/SwiftUI) shell**, where `AVPlayer` is native *and*
  `AVAssetResourceLoaderDelegate` is Apple's first-class **range/on-demand byte-loading** hook —
  a near-perfect fit for the chunk-decrypt source, and the **same path iOS uses** (so macOS-native
  + iOS would share the streaming consumer). A native shell makes desktop video + streaming nearly
  free — but it's a large investment that **video alone doesn't justify** (desktop video is the
  lowest-value consumer); it pays off only if a native shell lands on its own merits (native
  `PDFView`, `AVPlayer`, system polish), with video/streaming as a side benefit. Until then (b)
  is the pragmatic desktop answer.
- **SQ8 — the S2b op surface. RESOLVED + BUILT 2026-06-30.** Ops on **`SyncService`**, addressed
  **generically by the OnDemand resolve triple `(entityType, entityKey, propertyId)`** — *not* a
  path and *not* `(realmId, fileId)`. The deciding constraint is **layering**: `:sync-service`
  cannot see the `:file-service` `File` type, so a `SyncService` op can't construct
  `DataId(File.ID, path)` itself — the file-aware caller passes the triple. This turned out
  *better* than the path/`(realmId,fileId)` options it supersedes: **one op covers personal
  `(File.ID, path, File.Data.id)` AND shared `(SharedFile.ID, compositeKey, SharedFile.DATA_PROPERTY_ID)`**
  — no separate `OpenSharedContentStream` sibling needed. A friendly `openStreamingContent(path)`
  wrapper that fills the personal triple lives client-side (Drive, S3/S4). `ReadContentRange(handle,
  offset, length)` + `CloseContentStream(handle)` are handle-based. `OpenContentStream` reuses
  `OnDemandResolutionImpl`'s realm-routed fetch (shared with `resolve`) but captures the manifest
  into a `StreamingContentSession` via `SyncProcessor.openContentStream` (no reassemble-to-disk).
  Rejected: optional-`realmId` (overloads a param's meaning path↔fileId) and a `tenshu://` URI
  locator (a new user-facing abstraction the codebase's explicit-param convention doesn't use).
  The S2a core
  (`StreamingContentSession`/`StreamingContentRegistry`) is addressed by *manifest + decryptor*;
  exposing it as ops raises three coupled choices:
  - **(a) Op receiver — `FileSystem` vs `SyncService`.** Every existing *file* read op hangs off
    `FileSystem` (`ReadSharedFile`, `getRemoteFile`'s chain), and Drive's `TenshuFilesRepository`
    talks to `fileService` — argues `FileSystem`. But the session machinery (manifest fetch +
    `FileChunkDecryptor` + content blob store + realm keys) lives entirely in `:sync-service-proxy`
    — argues `SyncService` (like `GetRecoveryPhrase`) to avoid a cross-module component hop. A
    `FileSystem` op would have to reach the sync-side registry via a `TComponent`/holder (the way
    `ReadSharedFileImplementation` reaches `onDemandResolution`).
  - **(b) Addressing — path vs `(realmId, fileId)`.** The consumer (`VideoPreviewViewModel`) holds
    a *path* and today calls `getRemoteFile(path)`; `ReadSharedFile` uses `(realmId, fileId)`. The
    handle returned by `OpenContentStream` then carries the resolved manifest, so `ReadContentRange`
    needs no addressing beyond the handle.
  - **(c) path→manifest resolution.** The manifest is **not** a queryable local row — it is fetched
    per-entity from the relay and parsed on the OnDemand resolve path
    (`OnDemandResolutionImpl.resolve` → `SyncProcessor.processSyncBlockAsync`, the *one* place a
    `FileContentManifestPayload` is decrypted). So `OpenContentStream` must run that same
    fetch-entity-blocks + `decryptAndParse` step but, instead of `applier.apply` (reassemble to
    disk), capture the manifest into a `StreamingContentSession`. The cleanest shape is a small
    sync-side seam (e.g. `SyncProcessor.openContentStream(entityId): handle`) that reuses
    `fetchEntityBlocks` + `decryptAndParse`, building the session next to where the decryptor +
    keys already live. **Recommendation:** ops on `SyncService` (or a thin `FileSystem` facade
    delegating to a sync `TComponent`), addressed by the same `(realmId, fileId)` `ReadSharedFile`
    uses, with `OpenContentStream` driving the existing OnDemand fetch/parse seam. Confirm before S2b.

---

## 8. Implementation Notes for Claude Code

Canonical handoff — insertion points (paths current as of this draft).

> **⚠ v1.1 — two delivery shapes.** The notes below detail the **v0.2 push** path (`StreamContent`
> + control ops + `Stream` collector). If the **pull contract** (§3.5, recommended) is taken at
> the Gate, S1 (`FileChunkDecryptor` extraction) and the *Study-first* / *Decryptor-extraction* /
> *Tests* bullets are **unchanged**, but the *Content producer + control ops*, *Chunk element*,
> and *Client collector + provider seam* bullets are **replaced** by:
> - **Pull session ops** in `:file-service` / `:sync-service-common`: `OpenContentStream(path) →
>   handle`, `ReadContentRange(handle, offset, length) → TByteArray` (≤256 KiB inline; an
>   `IoOperation` — House Rule #3), `CloseContentStream(handle)`. The proxy holds a
>   `StreamingContentSession` (manifest + decrypted-chunk LRU over `FileChunkDecryptor`) keyed by
>   `handle`. **No `StreamElement` subtype, no `ContentDemand`/`ContentSeek`, no non-retaining
>   collector, no `DefaultInteractorProvider` seam** — it rides the ordinary op request/response
>   channel (the same one `getRemoteFile` uses).
> - **Client:** `TenshuFilesRepository.openStreamingContent(path)` wraps the session ops; the
>   Android `openProxyFileDescriptor` helper's `ProxyFileDescriptorCallback.onRead` calls
>   `ReadContentRange` directly (optionally issuing a speculative `ReadContentRange(K+1)` for
>   read-ahead). No ring buffer needed beyond the session LRU.

**Push-path insertion points (v0.2):**

- **Study first:** the `Stream` subsystem is the template — `core/.../collection/list/Stream.kt`
  (op + trait + `flow()` collector), `core/.../StreamElement.kt` (Value/Removed/End schema),
  `core-common/.../proxy/ListStreamImplementation.kt` (the list producer) + `StreamRegistry.kt`,
  `core-common/.../StreamInteractorImpl.kt` (client interactor — note its replay-all `elements`
  buffer, which content must NOT inherit), `core-common/.../transport/StreamFramer.kt`
  (`TYPE_CALLBACK`). The content path mirrors these.
- **Decryptor extraction:** pull the per-chunk fetch+gate+decrypt loop body out of
  `sync-service-proxy/.../impl/FileContentManifestApplier.kt` (`apply`, the `for (index …)`
  block + `decryptChunkTrying`) into a `FileChunkDecryptor`; have `apply` call it so the
  reassemble path is byte-identical.
- **Content producer + control ops:** new in `:sync-service-proxy` — `ContentStreamImplementation`
  (sibling to `ListStreamImplementation`) pushing chunk elements via `clientConnection.send`; op
  ids `StreamContent` / `ContentDemand` / `ContentSeek` in the `:file-service` /
  `:sync-service-common` schema (`StreamContent` carries the `StreamOperation` trait). A
  bounded-window producer that waits on `ContentDemand` (House Rule #3: lifecycle via the stream
  machinery, no `suspend` on op interfaces).
- **Chunk element (SQ5):** add `StreamElement.ContentChunk(chunkIndex, bytes)` to
  `core/.../StreamElement.kt` + a DTO under `core-common/.../impl/` (sibling to
  `StreamElementValueDto`), or reuse `Value<TByteArray>`.
- **Client collector + provider seam (§3.0):** add a non-retaining `StreamDispatcher` impl
  (sibling to `StreamInteractorImpl`, no replay buffer) and a content collector (sibling to
  `TList.flow()`); have `core-common/.../DefaultInteractorProvider.buildStreamInteractor` select
  it for a content stream via a `TransferStreamOperation` trait marker (today it hardcodes
  `StreamInteractorImpl`). The list path stays on `StreamInteractorImpl` untouched.
  `drive-shared-logic/.../TenshuFilesRepository.kt` gets `openStreamingContent(path)` + a ring
  buffer feeding the fd.
- **Android proxy-fd:** new `androidMain` helper in `:drive-shared-ui` (sibling to
  `PreviewTempFile`/`VideoPlayback`) wrapping `StorageManager.openProxyFileDescriptor` +
  `ProxyFileDescriptorCallback` → ring-buffer read (issues demand/seek).
  `isStreamingPreviewSupported()` expect/actual (`true` on Android API 26+, else → D2a fallback).
- **VM:** `VideoPreviewViewModel` branches on `isStreamingPreviewSupported()` — stream (open →
  fd → `VideoView`) vs the existing temp-file path; `onCleared` ends the stream + closes the fd.
- **Tests:** `FileChunkDecryptor` unit test (multi-chunk manifest, a tampered chunk → per-chunk
  gate fail) + a content-stream lifecycle test (demand-windowed delivery, seek repositions,
  collector doesn't retain). ⚠ `openProxyFileDescriptor` + `VideoView` are framework paths →
  on-device verification.

The mirror of this status block lives in this doc's header; update both when phases land. S4
amended `drive-content-preview.md` §5/Phase 6 (D2b is now built through S4; D2a is the fallback) +
its header. The remaining phases (S5 PDF, S6 web, S7 iOS, S9 desktop) each bind a platform's native
pull hook to the same `OpenContentStream`/`ReadContentRange`/`CloseContentStream` contract.
