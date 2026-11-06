# Drive — content preview beyond images (PDF, video, documents)

**Status:** Phases 1 (routing) + 2 (PDF viewer) + 2b (PDF upload thumbnail) + 3 (video
posters) + 4 (video playback) + D3 (open externally) + D4 (desktop HEIC viewer, §3.1)
+ D5 (HEIC thumbnail generation, §3.2) SHIPPED. Opened from a maintainer
discussion (2026-06-28) on closing the gap toward Dropbox/Drive-style in-app preview. Drive
originally rendered **images only**; everything else fell back to a category icon. This doc
introduced the type-routing seam that was missing, a lib-light **PDF** viewer, the (larger)
**video** track, and an **open-in-another-app** fallback, and explicitly scopes **office
documents out** of *in-app* rendering. Any threat-model deltas (§8) are proposal-level and
land with the implementing phase per House Rule #2.

Companion reading: [`sync/threat-model.md`](../sync/threat-model.md) (House Rule #1 — the
relay sees ciphertext only; this rules out server-side document conversion, §6),
[`drive-shared-realm-file-model.md`](drive-shared-realm-file-model.md) (the lazy
bytes-on-open delivery model that video collides with, §5), and `file-service`'s
`FileCategory` / `File.Preview` / `File.Thumbnail` surface (the existing preview pipeline
this reuses, §2 + §9).

---

## 1. Where we are today

The image path is clean and is the template for everything below:

- `TenshuFilesRepository.getRemoteFile(path): RepositoryResult<ByteArray>` delivers
  decrypted bytes (via `File.Data`; may trigger an OnDemand relay round-trip).
- `expect/actual fun decodeToImageBitmap(ByteArray): ImageBitmap?`
  (`drive-shared-ui/.../common/ImageDecode.kt`) is the **platform rendering seam** — Android
  `BitmapFactory` (two-pass downsample), Desktop Skia (`Image.makeFromEncoded`).
- `ImagePreviewScreen` renders a zoomable `ImageBitmap`; `ImageRepository` caches decoded
  bitmaps.
- Upload generates `File.Thumbnail` (100px PNG) + `File.ThumbnailLarge` (~256px JPEG) via the
  `GetPreview` op (`AndroidGetPreviewImplementation`), eagerly synced for list/detail tiers.

**The missing piece:** `FileCategory { Image, Audio, Video, Document, Archive, Other }`
exists in the `file-service` schema **but is never set at upload and never used to choose a
viewer**. Every file currently opens through `ImagePreviewScreen`. Type-aware routing (§3) is
the prerequisite for all of this work.

---

## 2. The unifying insight — most previews reduce to an `ImageBitmap`

A still image, **a PDF page**, and **a video poster frame** are all just bitmaps. That means:

- The existing zoomable viewer and the `GetPreview`/`File.Thumbnail` pipeline generalise with
  almost no new surface — a PDF's first page and a video's poster frame become thumbnails the
  same way an image does.
- The platform seam stays the same shape: add `renderPdfPage(...)` and
  `extractVideoPoster(...)` next to `decodeToImageBitmap(...)`.

**Video playback** is the one thing that does *not* reduce to a bitmap — it needs a real
player and, more importantly, a different **byte-delivery** model (§5). That's where the
actual cost is, not in the player UI.

---

## 3. Phase 1 — type routing (prerequisite, small) — **SHIPPED**

Two pieces:

1. **Derive `FileCategory` from the filename** — a pure
   `fileCategoryForName(name): FileCategory` helper in `:file-service` (extension → category:
   `pdf → Document`, `mp4/mov/webm → Video`, `jpg/png/… → Image`, …). Used for the list-row
   glyph (`FileMapper`) and for routing (§3.2).
2. **A viewer router.** At open time (`Destination.FilePreview`), switch on the derived
   `FileCategory` to pick `ImagePreviewScreen` vs `PdfPreviewScreen` (§4) vs
   `VideoPreviewScreen` (§5) vs the `UnsupportedPreviewScreen` fallback (type glyph +
   "no preview yet", still hosting the actions sheet so rename/move/details stay reachable).

**Why derive, not persist (Q1 resolved → derive).** The schema already carries a
`File.Category` property, but it's registered as a **constant relation** with *no value
factory* (`registerPropertyRelation`), so it structurally cannot hold a per-file value — it
has always read back null, which is why every file previously fell back to the `Other` glyph.
Making it op-written would mean a schema + wire change (a constant-valued value property with
an id→singleton decoder) plus a backfill for existing files. The extension mapping is free,
needs no backfill, adds nothing to the wire, and is the same key both the icon and the router
need. So Phase 1 derives; `File.Category` is left in place but unused. If content-sniffed
categories (extensionless files) or server-side category queries are ever needed, persisting
becomes its own slice.

This is intentionally small and unblocks both PDF and video. No protocol change, no
threat-model delta.

### 3.1 D4 — codec capability ≠ file category (the HEIC case) — **SHIPPED**

Reported 2026-07-21: every photo taken on an iPhone failed to open on the Mac app with
*"Could not decode image"*. The file is fine and macOS reads it perfectly — **Compose Desktop
does not**. Its decode path is Skia (`org.jetbrains.skia.Image.makeFromEncoded`), and
JetBrains' Skiko builds ship **without** the HEIF/HEVC codec: HEVC is patent-encumbered and
Skia's `SkHeifDecoder` is effectively Android-platform-only. Android was never affected
because its `decodeToImageBitmap` actual is `BitmapFactory` — the *platform* decoder, HEIF
since API 28 — and iOS goes through ImageIO. Desktop is the odd one out precisely because its
renderer is deliberately platform-independent. (The list-row thumbnail still appeared, which
made the failure look stranger than it was: `IosGetPreviewImplementation` transcodes previews
to JPEG/PNG before they sync, so only the untouched original was ever HEIC.)

This splits §3's single question into two. `fileCategoryForName` answers *"is this an
image?"*; the new `isImagePreviewSupported(fileName)` expect/actual answers *"can this
platform draw it?"*, and the router requires both before choosing `ImagePreviewScreen`.
Where the answer is no, the file takes the `UnsupportedPreviewScreen` fallback and its D3
**Open in another app** hand-off — which on a Mac means Preview.app, i.e. the user still sees
their photo. Android's actual returns false for HEIC below API 28 (`minSdk` is 24), closing a
latent gap on old devices by the same rule.

**The decode fix (macOS).** A bundled `image-decode-helper` (swiftc; it lives in
`:macos-helpers` — see §3.2 for why) bridges to ImageIO: encoded bytes in on **stdin**, PNG (alpha) or JPEG (no
alpha) out on **stdout**, EXIF orientation applied, capped at 4096 px on the long edge to
match the Android decoder's ceiling. The desktop `decodeToImageBitmap` tries Skia first and
falls back to the helper *on Skia failing* rather than on a format list — ImageIO also reads
exotic TIFFs, camera raw and (on recent macOS) AVIF, so all of them come along without anyone
enumerating them, and JPEG/PNG never pay for the fallback.

Two constraints shaped this and should survive any rewrite — and one exit condition:

- **The bridge has a documented removal trigger, and a test that guards it.**
  `HeicDecodeDesktopTest.skiaAloneCannotDecodeHeic` pins the *premise* (Skiko ships no HEIF/HEVC
  codec) against a real 16×16 HEIC fixture. If Skiko ever gains the codec that test fails, and
  that failure is the signal to **delete `:macos-helpers`' image bridge** rather than to update
  the assertion. ⚠ Without this note the bridge looks permanent and nobody would ever remove it.
  On the generate side the pair is `heicFileWithLocalBytes_rendersThumbnailThroughTheImageIoBridge`
  (proves the bridge renders) and `undecodableImageBytesStillResolveToNull` (proves it degrades) —
  ⚠ only the negative one was previously written down, and a detector tested on one side only can
  pass while doing nothing.


- **Pipes, not files.** The bytes are decrypted user plaintext. `sips`-style file-in/file-out
  conversion would put a plaintext copy on disk and drag in a threat-model A5 amendment;
  stdin/stdout keeps the exposure exactly where it already was (in memory). **No threat-model
  delta** — that is the point.
- **Availability is a runtime property.** The helper is swiftc-built on a Mac host and simply
  absent on Windows/Linux, where `MacImageDecoder.isAvailable()` is false and the routing gate
  above takes over. Packaged `.app`s embed the signed copy under `Contents/Helpers` (an
  extracted unsigned binary is killed under the hardened runtime); dev runs extract it from
  the `:macos-helpers` jar.

### 3.2 D5 — the same gap on the *generate* side — **SHIPPED**

D4 fixed the viewer; the proxy-side **thumbnail generator** had the identical blind spot from a
different cause. `JvmGetPreviewImplementation` decodes with `javax.imageio.ImageIO.read`, which
has no HEIF plugin either — so a HEIC uploaded **from a Mac** produced no thumbnail at all, on
any device. (Uploads from an iPhone were never affected: `IosGetPreviewImplementation`
transcodes its preview to JPEG/PNG before it syncs. That asymmetry is why the bug hid — the
common case looked fine.)

Same bridge, same shape: `readImage(bytes)` tries `ImageIO` first and falls back to
`MacImageDecoder` on failure, then re-reads the transcoded PNG/JPEG. Off macOS the fallback is
a no-op and the answer stays `null`, exactly as before.

**What this cost structurally.** The decoder had to leave `:ui-core` — a service module must
not depend on a Compose UI module — so it now lives in a new Compose-free JVM module,
**`:macos-helpers`**, which owns `HelperBinary` (bundle-first, classpath-resource-fallback
resolution), `MacImageDecoder` and the swiftc build of `image-decode-helper`. `:ui-core` keeps
building and shipping `qr-scan-helper` (it belongs with the camera code) and just borrows
`HelperBinary`; resolution is by *name*, so neither helper cares which module built it.

Packaging follows the consumers: `:drive-desktop` embeds the helper because it renders the
viewer, and **`:control-desktop` because Control is the proxy on desktop** and therefore the
one that generates thumbnails.

Deliberately unchanged: an image whose bytes no decoder can read still resolves to `null`
rather than falling back to a stored thumbnail. Only the PDF branch has that fallback (PDF
rendering is the fragile one), and widening it risks re-storing a stale thumbnail class.
`undecodableImageBytesStillResolveToNull` pins that.

---

## 4. Phase 2 — PDF (recommended first real feature) — **SHIPPED (viewer + upload thumbnail)**

**Model: PDF = paginated bitmap.** As built, the seam is a small stateful handle rather than a
stateless per-page function (the platform renderers want the document open once, not re-parsed
per page):

```
expect class PdfDocument { val pageCount: Int; fun renderPage(pageIndex, targetWidth): ImageBitmap?; fun close() }
expect fun openPdfDocument(bytes): PdfDocument?
```

`PdfPreviewScreen` is a `HorizontalPager` (one page per screen), each page rendered **lazily**
and pinch-`zoomable`, with a "page / total" indicator. `PdfPreviewViewModel` does the single
bounded `getRemoteFile` fetch (same as the image path), opens the document once, and serialises
every render behind a `Mutex` (neither renderer is thread-safe). This reuses the existing
zoomable-bitmap surface. **Note:** drive-shared-ui is android+desktop only, so this seam needs
no iOS actual — the iOS path is native PDFKit (§4.1).

Per-platform feasibility — the "no external libraries" assumption holds on mobile, **not on
desktop**:

| Platform | Native PDF render? | Mechanism |
|---|---|---|
| **Android** ✅ | yes, lib-free | `android.graphics.pdf.PdfRenderer` (API 21+) → temp-file `ParcelFileDescriptor` → `Bitmap` |
| **iOS** | yes, lib-free (not in this module) | native PDFKit / CoreGraphics — SwiftUI side, not Compose |
| **Desktop / JVM** ❌ | no native option | **PDFBox 3.0.4** (Apache 2.0), desktop source set only. JDK ships nothing |

### 4.1 The PDFKit question (native viewer vs paginated bitmap)

Why we standardise on render-to-bitmap rather than embedding a native PDF view everywhere:

- **Native macOS Swift app:** `PDFView` is an `NSView` → drop into SwiftUI via
  `NSViewRepresentable`. Full reader (search/selection/annotations) for free. Not our world
  today, but the cleanest if a native shell ever lands.
- **Compose iOS:** PDFKit *is* reachable — Compose MP iOS renders into a real `UIView`
  hierarchy and exposes `UIKitView { PDFView() }` interop. So a native PDFKit reader is an
  **optional upgrade** on iOS even inside Compose.
- **Compose Desktop (JVM):** embedding PDFKit is effectively impossible — and Skiko is only
  half the reason. The deeper problem is **JVM process + Cocoa framework**: PDFKit is
  Objective-C/AppKit, reaching it needs JNI bridging, *and* Compose Desktop draws into a
  single Skiko surface with no native view host to slot an `NSView` into. `SwingPanel` interop
  only bridges AWT/Swing, not Cocoa. No realistic seam.

**Decision D1: render-to-bitmap is the baseline on all platforms** (works everywhere:
PdfRenderer / CoreGraphics / PDFBox). A native PDFKit reader is an *optional, later* per-platform
upgrade where a native view host exists (native macOS shell, or Compose-iOS `UIKitView`) — not
a dependency of shipping PDF preview.

Whole-`ByteArray` delivery is fine for PDFs (typically small); no content-model change needed.

### 4.2 Phase 2b — first-page thumbnail at upload — **SHIPPED**

The viewer shipped with list rows still showing the `FileIcon.Pdf` glyph. Phase 2b closes
that: a PDF's **first page** becomes a `File.Thumbnail`/`ThumbnailLarge` at upload, the same
way an image does, so list/detail rows show the rendered page and it eager-syncs to every
device (including byte-less ones that never fetch the OnDemand bytes).

**Decision (Q2 resolved → reuse `GetPreview`, no new op).** The thumbnail is generated by
making the existing platform `GetPreview` resolvers PDF-aware (proxy-side), exactly as Phase 3
made them video-aware — `UploadFileImplementation.renderThumbnail` already calls `GetPreview`
for every upload regardless of category, so no upload-path or schema change was needed. Unlike
video, **both** platforms can rasterise a PDF, so both *generate* (no serve-the-synced-poster
asymmetry):

| Platform | PDF → bitmap at upload | Mechanism |
|---|---|---|
| **Android** ✅ | yes, lib-free | `android.graphics.pdf.PdfRenderer` (temp-file PFD), first page rendered onto a white bitmap |
| **Desktop / JVM** ✅ | yes (PDFBox) | `Loader.loadPDF` + `PDFRenderer.renderImage(scale, ImageType.RGB)` → `BufferedImage` |

Both render the first page at a width of *at least* the requested thumbnail size (so the
downstream downscale stays sharp) and at most a memory cap (2048 px), then run it through the
**same** scale + encode + cache path as an image. A render failure (corrupt / encrypted PDF)
falls back to any stored thumbnail, exactly like a byte-less file — it never fails the
best-effort thumbnail render or the upload. The `GetPreview` content cache keys on
`sha256(bytes)`, so it covers PDFs unchanged. No new op, no schema/wire change, no
threat-model delta (same on-device decrypted-`ByteArray`-on-render as an image). The viewer's
own `PdfDocument` seam in `:drive-shared-ui` is **not** reused — `GetPreview` is proxy-side
(`:file-service-proxy`), so the thumbnail path has its own platform PDF→bitmap (the Q2
expectation).

---

## 5. Phase 3+ — video (native players, but a real delivery cost)

**Phase 3 (poster-frame thumbnails) — SHIPPED.** Video files now show a poster frame in
listings/detail instead of just the glyph. It rides the existing `GetPreview`/`File.Thumbnail`
pipeline (no new op, no schema change): `AndroidGetPreviewImplementation` detects a video by
filename and extracts a frame (~1 s in, nearest keyframe) via `MediaMetadataRetriever` over an
in-memory `MediaDataSource`, then runs it through the same scale/encode path as an image — so
the poster eager-syncs to every device as `File.Thumbnail`/`ThumbnailLarge`. Desktop/JVM has
no built-in video decoder, so `JvmGetPreviewImplementation` can't *generate* a poster; it
*serves* any poster synced from Android (`storedThumbnail`) and otherwise shows the glyph. iOS
poster generation (`AVAssetImageGenerator`) landed later in `IosGetPreviewImplementation` — ⚠ this
line claimed it was *deferred* until 2026-09-08, which `photos-video-support.md` §2 caught; the
poster offset is now one shared rule on Android (`MotionClipPlan.posterOffsetUs`, its §14.1).

**Phase 4 (playback) — SHIPPED (Android).** Tapping a video now opens the
`VideoPreviewScreen`. The platform seam is `isVideoPlaybackSupported()` + a `VideoPlayer`
composable: **Android** plays via the lib-free `VideoView` + `MediaController` (chosen over
Media3 to avoid the dependency for v1); **desktop** reports unsupported (no bundled codec) and
the screen shows a message without fetching; **iOS** isn't a target of this Compose module (its
player is SwiftUI `AVPlayer`). Byte delivery is **D2a** as planned: `VideoPreviewViewModel`
fetches via the same `getRemoteFile` path, writes the decrypted bytes to an app-private temp
file (`writeVideoTempFile`, `jvmMain`), hands the player the path, and **deletes it in
`onCleared`**. Threat-model **A5 amended** (§8). ⚠ Ph4 reuses the whole-`ByteArray` fetch, so it
inherits that path's memory ceiling for very large videos. **Update:** true streaming (D2b) is no
longer deferred — it is built through **S4** (Android) in
[`drive-streaming-preview.md`](drive-streaming-preview.md); `VideoPreviewViewModel` now *prefers*
the bounded-memory streaming path (`VideoSource.Stream`, no whole-file in memory or on disk) and
falls back to this D2a temp-file path only when streaming is unsupported (API < 26 / desktop) or the
stream can't be opened. On-device verify pending.

The players are the easy half:

| Platform | Native player |
|---|---|
| **Android** | `MediaPlayer` + `SurfaceView` is lib-free; **Media3/ExoPlayer** (external, Google) recommended for codec/format coverage |
| **iOS** | `AVPlayer` / `AVKit` via `UIKitView` interop — native, excellent |
| **Desktop / JVM** | ~~genuinely hard — VLCJ or GStreamer; candidate to defer or skip~~ **Superseded for macOS (§5.1): `AVPlayer` in-process over Panama/FFM, no library.** Windows/Linux keep the OS hand-off |

**The hard half is byte delivery.** Today `getRemoteFile` returns one decrypted `ByteArray`
from `File.Data`. That's fine for a few-MB image/PDF but a non-starter for a 500 MB video:
OOM, and players want to **seek** against a URI / file path / streamable source, not a memory
blob. Combined with E2E-at-rest (bytes are ciphertext on the relay and at rest; Control
decrypts on read), video needs one of:

- **D2a — decrypt-to-temp-file (pragmatic v1):** materialise decrypted bytes to a local temp
  file, hand the player a `file://` URI, secure-delete on close. Works immediately with
  AVPlayer / Media3. Cost: transient plaintext on disk (§8) and no partial-open (whole file
  must land first).
- **D2b — seekable streaming decrypt source (the "right" answer):** a range-capable content
  source that decrypts on demand and ties into the OnDemand relay fetch. Much more work; can
  follow D2a. **Designed in [`drive-streaming-preview.md`](drive-streaming-preview.md)** —
  feasible *as plumbing* because two substrates already exist: content is stored as
  independently-AEAD-sealed 2 MiB chunks (ADR 0017, no crypto/format change) *and* the IPC
  `Stream` subsystem already pushes typed elements over the boundary. The work is a content-stream
  producer + flow-control/seek "control blocks" on that channel + a Drive-side seekable fd
  (video-first).

**Decision D2: start with decrypt-to-temp-file (D2a); ship Android + iOS first; defer desktop
video.** A poster-frame thumbnail (Android `MediaMetadataRetriever`, iOS
`AVAssetImageGenerator`) is cheap and improves listings *before* any player exists — do it as
a standalone slice.


---

### 5.1 Desktop playback on macOS — SHIPPED, and why the original answer was wrong

The table above concluded desktop was "genuinely hard … defer or skip". That was right about
the **JVM** and wrong as a conclusion about the **platform**, and the distinction is the whole
finding:

- **KMP was never the constraint.** iOS Drive drives `AVPlayer` from Kotlin/Native today
  (`drive-ios/Sources/StreamingVideoLoader.swift`). What has no video decoder is the JVM — and
  Java Sound is PCM-only, so the same gap covers audio.
- **On macOS the JVM reaches AVFoundation anyway.** `:macos-helpers` carries a Panama/FFM
  Objective-C bridge (`ffi/ObjC.kt`) that `ui-core`'s `FfmMacQrCameraScanner` already uses to run
  an `AVCaptureSession` **in-process** and pump `CVPixelBuffer`s into Compose. Video playback is
  that same pipeline with `AVPlayerItemVideoOutput` as the source instead of a capture output.

**As built.** `MacVideoPlayer` / `MacVideoSession` (`:macos-helpers`) own an `AVPlayer` +
`AVPlayerItem` + `AVPlayerItemVideoOutput`; `VideoPlayback.desktop.kt` pulls one frame per Compose
frame (`withFrameNanos`), converts BGRA → `ImageBitmap` through the camera's own
`CoreVideo.readBgra`, and draws its own transport row. **AVPlayer keeps decode, the clock and
audio** — we only take pictures off it, which is why this is small.

**The one real cost, and it is in the bridge, not the player.** `ObjC.kt` deliberately bound only
pointer/integer/void signatures. Every time-bearing AVFoundation call passes a `CMTime` **by
value**, and at 24 bytes it is over the 16-byte threshold that makes it an indirect return — so
`ObjC.msgSendReturningStruct` has to pick the symbol **by architecture**: plain `objc_msgSend` on
arm64 (hidden pointer in `x8`, outside the argument registers), `objc_msgSend_stret` on x86_64
(hidden pointer in `rdi`, where `self` would otherwise be). ⚠⚠ Getting that wrong does not fail —
it reads the receiver as the result pointer and corrupts memory. `CoreMediaTest` pins the round
trip against `NSValue`'s CMTime category, needing no media file, and a one-field offset error
turns it red.

**Scope.** macOS only — Windows would need a Media Foundation bridge, Linux GStreamer, and both
keep the `UnsupportedPreviewScreen` OS hand-off that the router now sends them to. Byte delivery
is **D2a** (the temp file the view model already writes): the D2b streaming twin needs an
`AVAssetResourceLoaderDelegate` as a runtime-registered class, which the bridge can express
(`registerClass`) but which is its own slice.

**Verified:** `CoreMediaTest` (5, mutation-checked) plus a real 4.4 MB H.264 asset opened through
`MacVideoPlayer` — 35.07 s duration read back through the struct return, 288 frames at 1600×756,
position advancing with the clock, clean close. **And in the packaged app** (2026-09-02): a synced
video opened in `drive-desktop` against a live Control reached
`FIRST FRAME 1920x1080 stride=7680 duration=5.998` off the D2a temp file, so the router gate, the
session, the frame pump and the `Rendering` hand-off all hold end to end.

⚠⚠ **Two Drive instances on one socket look exactly like a broken build.** The first attempt failed
every read with `unannouncedSession` — because `/Applications/TenshuDrive.app` had relaunched itself
beside the build under test, and two clients claiming `app.tenshu.drive` mean the later announce
takes the admission while the other's ops fail the §6.5 gate. Control was never restarted and the
socket was intact; `ps -ax | grep TenshuDrive` is the check, and it must show exactly one.

⚠ Still unverified in the app: seeking (the transport slider) and the decode-failure path.

---

## 6. Documents (Office / docx / pptx) — out of in-app scope

Two reasons, the second load-bearing:

1. No native rendering on any of our platforms; in-app rendering needs heavy libraries.
2. The usual shortcut — **server-side conversion to PDF/image — violates House Rule #1**
   (the relay sees ciphertext only). Conversion can only happen on-device, which is most of
   the cost of #1 again.

**Decision D3: documents get "open in external app"** (Android `ACTION_VIEW` / share sheet,
iOS `UIDocumentInteractionController` / share, desktop `Desktop.open`), **not** in-app
rendering. This is also the right fallback for any unhandled category.

**D3 — SHIPPED (Android + desktop).** The `UnsupportedPreviewScreen` (the router's residual
fallback) now offers an **Open in another app** action. It reuses the video viewer's
decrypt-to-temp-file step (the helper generalised from `VideoTempFile` →
`PreviewTempFile`): a new `OpenExternallyViewModel` fetches the decrypted bytes via the same
`getRemoteFile` path, writes them to an app-private temp file, and surfaces a one-shot signal
the screen hands to a platform `OpenFileLauncher` seam (mirrors the `ShareText` launcher
pattern). **Android** shares the temp file through a newly-declared, **non-exported**
`FileProvider` (`<applicationId>.fileprovider`, `<cache-path>` root) as a `content://` URI on
an `ACTION_VIEW` chooser with a per-launch read grant (a raw `file://` URI throws on API 24+);
**desktop** uses `java.awt.Desktop.open`. The temp file is deleted in `onCleared`.
**Threat-model A5 amended** (the same transient-plaintext entry as video, plus the
deliberate-disclosure-to-another-app delta). iOS isn't a target of this Compose module
(`drive-shared-ui` is android+desktop only) — its `UIDocumentInteractionController` path is
deferred with the rest of the iOS UI.

---

## 7. Phasing (each independently shippable)

| Phase | Scope | Notes |
|---|---|---|
| **1** ✅ | Type routing: derive `FileCategory` from name + viewer router + `UnsupportedPreviewScreen` | **SHIPPED.** Small; no protocol/threat-model change |
| **2** ✅ | PDF viewer (paginated bitmap) — Android `PdfRenderer` + desktop PDFBox | **SHIPPED (viewer).** First-page thumbnail at upload split out → Phase 2b (Q2) |
| **2b** ✅ | PDF first-page thumbnail at upload (`GetPreview`/`File.Thumbnail`) | **SHIPPED.** Both Android (`PdfRenderer`) + JVM (PDFBox) generate; Q2 resolved → reuse `GetPreview`, no new op |
| **3** ✅ | Video poster-frame thumbnails — Android `MediaMetadataRetriever` | **SHIPPED.** Android generates; desktop serves the synced poster; iOS deferred |
| **4** ✅ | Video playback (decrypt-to-temp-file) — Android `VideoView` | **SHIPPED (Android).** Threat-model A5 amended; desktop unsupported; iOS deferred (SwiftUI `AVPlayer`) |
| **5** (opt) | Native PDFKit reader on iOS / native macOS shell | Optional upgrade over Phase 2 |
| **6** (opt) | Seekable streaming decrypt source (D2b) | **S1–S4 built → [`drive-streaming-preview.md`](drive-streaming-preview.md)** (Android video, on-device pending): `VideoPreviewViewModel` prefers streaming, D2a temp-file is now the fallback. Web/iOS/desktop + PDF reuse remain (S5–S9) |
| **D3** ✅ | "Open externally" for documents/unhandled | **SHIPPED (Android + desktop).** `OpenFileLauncher` seam + `OpenExternallyViewModel`; Android `FileProvider`+`ACTION_VIEW`, desktop `Desktop.open`; threat-model A5 amended; iOS deferred |

---

## 8. Threat-model notes (proposal-level)

- **PDF (Phase 2):** no delta — same decrypted-`ByteArray`-on-open as images; rendered fully
  on-device.
- **Video temp file (Phase 4, D2a) — LANDED:** introduces **transient decrypted plaintext on
  local disk** for the duration of playback (app-private temp dir; deleted in
  `VideoPreviewViewModel.onCleared`). **Threat-model A5 amended** (`sync/threat-model.md`) — a
  within-user device-exposure delta only, **no relay / HR#1 change**. ⚠ Still-open hardening
  noted in the amendment: explicit backup/indexer exclusion + best-effort deletion of files
  orphaned by an OS process kill that pre-empts `onCleared`.
- **Documents (D3) — LANDED:** "open externally" hands decrypted bytes to a third-party app
  via the OS (Android `FileProvider` `content://` URI on an `ACTION_VIEW` chooser with a
  per-launch read grant; desktop `Desktop.open`) — a deliberate, user-initiated disclosure of
  the same transient-plaintext temp file the video path uses. **Threat-model A5 amended**
  (`sync/threat-model.md`) with the disclosure-to-another-app delta; **no relay / HR#1 change**
  (bytes already decrypted client-side; nothing reaches the relay). The `FileProvider` is
  non-exported and scoped to the app cache dir.

---

## 9. Open questions

- **Q1 — category source of truth:** ~~persist at upload vs compute from extension?~~
  **RESOLVED → compute** (Phase 1, §3). `File.Category` is a non-functional constant relation;
  the extension mapping is free and needs no backfill. Revisit only if content-sniffing /
  server-side category queries become a requirement.
- **Q2 — PDF thumbnail generation site:** ~~the existing `GetPreview` op vs a sibling
  `GetPdfPreview`?~~ **RESOLVED → reuse `GetPreview`** (Phase 2b, §4.2). The platform
  `GetPreview` resolvers were made PDF-aware (exactly as Phase 3 made them video-aware), so the
  thumbnail rides `renderThumbnail()` at upload with no new op and no schema change. As Q2
  anticipated, the proxy-side path has its **own** platform PDF→bitmap (Android `PdfRenderer`,
  JVM PDFBox) rather than reusing the `:drive-shared-ui` `PdfDocument` viewer seam.
- **Q3 — desktop video:** ship VLCJ, or accept "open externally" on desktop indefinitely?
- **Q4 — large-file gate:** above what size do we refuse in-app preview (esp. PDF page-count /
  video length) and fall back to external open?

---

## 10. Implementation Notes for Claude Code

Canonical handoff — file-level insertion points (paths are current as of this draft):

**Phase 1 (routing) — as built:**
- Helper: `file-service/.../FileCategoryForName.kt` → `fileCategoryForName(name): FileCategory`
  (extension map; SVG excluded — would fail bitmap decode).
- Icon: `FileMapper` now derives the row glyph via `fileCategoryForName(name).toFileIcon()`
  (the old private `iconFromCategory` became a shared `FileCategory.toFileIcon()` extension in
  `drive-shared-logic/.../data/FileIcon.kt`); the dead `File.Category` read is gone.
- Router: `App.kt` `entry<Destination.FilePreview>` switches on `fileCategoryForName(name)` —
  `Image → ImagePreviewScreen`, else → `UnsupportedPreviewScreen`.
- Fallback: `drive-shared-ui/.../common/preview/UnsupportedPreviewScreen.kt` (type glyph +
  `preview_unavailable` string + shared actions sheet).

**Phase 2 (PDF) — as built:**
- Seam: `drive-shared-ui/src/commonMain/.../common/PdfRender.kt` → `expect class PdfDocument`
  (`pageCount` / `renderPage(pageIndex, targetWidth)` / `close()`) + `expect fun openPdfDocument(bytes)`.
  Stateful handle (open once), not a per-page stateless fn.
- Actuals: `…/androidMain/.../PdfRender.android.kt` (`PdfRenderer` over a temp-file PFD; white
  page erase) + `…/desktopMain/.../PdfRender.desktop.kt` (PDFBox `Loader.loadPDF` +
  `PDFRenderer.renderImage(scale, ImageType.RGB).toComposeImageBitmap()`). **No iOS actual** —
  module is android+desktop only.
- `PdfPreviewViewModel`/`PdfPreviewUiState` + DI (`viewModelsModule`) + `PdfPreviewScreen`
  (`HorizontalPager`, lazy per-page render guarded by a `Mutex`, page-cached, zoomable, "n / m"
  indicator) + `LocalPdfPreviewViewModelFactory` (keyed by path).
- Routing: `App.kt` `FilePreview` now `when { Image → image; isPdf → PdfPreviewScreen; else → fallback }`;
  `isPdf(name)` added to `FileCategoryForName.kt`; `FileMapper` shows `FileIcon.Pdf` for PDFs.
- Dep: `libs.pdfbox` (`org.apache.pdfbox:pdfbox:3.0.4`) in drive-shared-ui **desktopMain only**.
- Tests: `PdfRenderDesktopTest` (synthesise→open→render), `isPdf` cases in `FileCategoryForNameTest`.

**Phase 2b (PDF upload thumbnail) — as built:**
- No new seam or op — made the existing proxy-side `GetPreview` resolvers PDF-aware (the
  same pattern Phase 3 used for video). `UploadFileImplementation.renderThumbnail` already
  calls `GetPreview` for every upload, so nothing changed in the upload/schema/wire path.
- Android: `file-service-proxy/androidMain/.../AndroidGetPreviewImplementation.kt` — `isPdf(name)`
  branch in the `originalBitmap` selection renders the first page via a new
  `renderPdfFirstPage(bytes, maxEdge)` (`android.graphics.pdf.PdfRenderer` over a temp-file
  PFD, white-erased bitmap), then reuses the existing scale + encode + cache path. Render
  failure → stored-thumbnail fallback (same as a byte-less file).
- JVM: `file-service-proxy/jvmMain/.../JvmGetPreviewImplementation.kt` — symmetric `isPdf`
  branch + `renderPdfFirstPage(bytes, maxEdge)` via PDFBox (`Loader.loadPDF` +
  `PDFRenderer.renderImage(scale, ImageType.RGB)` → `BufferedImage`). Both render at a width of
  ≥ the requested edge and ≤ `MAX_PDF_RENDER_PX` (2048). **Also fixed a latent bug**: the JVM
  resolver read the path only from an `Entity<*>` caller and fell to `""` for a bare `File`
  composite (the upload shape) — so the video/PDF category branches were silently disabled at
  upload. Now reads the path from `is File` too (mirrors the Android resolver).
- Dep: `libs.pdfbox` added to `file-service-proxy` **jvmMain** (`build.gradle.kts`).
- Surfacing: no UI change — `FileMapper` already passes `File.Preview` bytes as `previewBytes`;
  once a PDF has a stored thumbnail, the row shows the rendered page and falls back to the
  `FileIcon.Pdf` glyph only when absent.
- Tests: `pdfFileWithLocalBytes_rendersFirstPageThumbnail` (PDFBox-synthesised PDF → real
  rendered, aspect-preserving image) + `pdfFileWithUnrenderableBytes_fallsBackToStoredThumbnail`
  in `JvmGetPreviewImplementationThumbnailFallbackTest`. ⚠ Android `PdfRenderer` is a framework
  class (not unit-testable) — needs on-device verification.

**Phase 3 (video poster) — as built:**
- No new seam — made the existing `GetPreview` resolver video-aware (proxy-side, not the UI
  module): `file-service-proxy/androidMain/.../AndroidGetPreviewImplementation.kt` detects a
  video by `fileCategoryForName(name)`, extracts a frame via `MediaMetadataRetriever` over an
  in-memory `MediaDataSource` (`extractVideoPoster` + `InMemoryVideoDataSource`), then reuses
  the existing scale/encode path → stored as `File.Thumbnail`/`ThumbnailLarge` at upload.
- `file-service-proxy/jvmMain/.../JvmGetPreviewImplementation.kt`: video guard returns
  `storedThumbnail` (serves an Android-synced poster; the JVM can't decode video — FFmpeg out
  of scope). iOS GetPreview impl still doesn't exist → deferred.
- Tests: `videoFileWithLocalBytes*` cases in `JvmGetPreviewImplementationThumbnailFallbackTest`.
  ⚠ Android `MediaMetadataRetriever` extraction isn't unit-testable (framework class) — needs
  on-device verification.

**Phase 4 (video playback) — as built:**
- Seam (`drive-shared-ui/commonMain/.../common/`): `VideoPlayback.kt`
  (`isVideoPlaybackSupported()` + `@Composable VideoPlayer(filePath, modifier)`) +
  `PreviewTempFile.kt` (`writePreviewTempFile(bytes, ext)` / `deletePreviewTempFile` — the
  generic decrypt-to-temp-file helper, **renamed from `VideoTempFile` in the D3 slice** and now
  shared with open-externally).
- Actuals: `androidMain/VideoPlayback.android.kt` (`true` + `AndroidView { VideoView +
  MediaController }`, autostart, `stopPlayback` on dispose) + `desktopMain/VideoPlayback.desktop.kt`
  (`false` + empty box) + **`jvmMain/PreviewTempFile.jvm.kt`** (shared java.io temp write/delete —
  android cache dir via `java.io.tmpdir`).
- `VideoPreviewViewModel`/`VideoPreviewUiState` + DI + `VideoPreviewScreen` (player / spinner /
  error / unsupported) + `LocalVideoPreviewViewModelFactory`. Routing: `App.kt` `FilePreview`
  adds `fileCategoryForName == Video → VideoPreviewScreen`.
- Decisions vs the original sketch: **`VideoView` (lib-free), not Media3** — no new dep for v1;
  the temp-file helper lives in the **UI module's `jvmMain`** (not a `TenshuFilesRepository`
  method) since the write is platform code and the repo is Compose-free logic.
- Tests: `PreviewTempFileTest` (write/suffix/delete round-trip). ⚠ `VideoView` playback is a
  framework path — needs on-device verification.

**D3 (open externally) — as built:**
- Seam (`drive-shared-ui/commonMain/.../common/`): `OpenFile.kt` — `class OpenFileLauncher(
  canOpenExternally, open(filePath, fileName))` + `@Composable expect rememberOpenFileLauncher()`
  (mirrors the existing `ShareText` launcher pattern). Reuses the generalised
  `PreviewTempFile` helper (renamed from `VideoTempFile`; video VM + `PreviewTempFileTest`
  updated to match).
- Actuals: `androidMain/OpenFile.android.kt` — `canOpenExternally = true`;
  `FileProvider.getUriForFile("<pkg>.fileprovider", tempFile)` → `ACTION_VIEW` + `setDataAndType`
  (MIME from `MimeTypeMap` by extension) + `FLAG_GRANT_READ_URI_PERMISSION`, wrapped in
  `Intent.createChooser`. `desktopMain/OpenFile.desktop.kt` — `canOpenExternally =
  Desktop.isSupported(OPEN)`; `Desktop.getDesktop().open(File(path))`.
- `OpenExternallyViewModel`/`OpenExternallyUiState` + DI + `LocalOpenExternallyViewModelFactory`
  (keyed by path) in `UnsupportedPreviewScreen.kt`. The VM does **no** up-front fetch (most
  files here are never opened externally); `prepareOpen()` fetches via `getRemoteFile` on tap,
  writes the temp file, and surfaces a one-shot `readyToOpen` path the screen hands to the
  launcher via `LaunchedEffect`; `onCleared` deletes the temp file. A second tap reuses the
  already-written file.
- UI: `UnsupportedPreviewScreen` gains a `viewModel` param + an **Open in another app** button
  (shown only when `canOpenExternally`), a spinner while preparing, and an error line. Router
  `App.kt` passes `LocalOpenExternallyViewModelFactory.current(path)`. Strings
  `preview_open_externally` / `preview_open_externally_failed`.
- Android plumbing: a **non-exported** `<provider android:name="androidx.core.content.FileProvider"
  authorities="${applicationId}.fileprovider">` in `drive-android/AndroidManifest.xml` +
  `res/xml/file_provider_paths.xml` (`<cache-path path="."/>` — `java.io.tmpdir` == the app cache
  dir, so the temp file is exposable, nothing else is) + `libs.core.ktx` on `drive-shared-ui`
  androidMain.
- Threat-model **A5 amended** (transient plaintext + deliberate disclosure to a third-party
  app; non-exported provider, per-launch grant; no relay / HR#1 change). ⚠ Android
  `ACTION_VIEW` + `FileProvider` is a framework path — needs on-device verification; desktop
  `Desktop.open` works from `drive-desktop`.

The mirror of this status block lives in this doc's header; update both when phases land.
