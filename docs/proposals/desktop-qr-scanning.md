# Desktop QR scanning (macOS-native)

**Status:** P1 (Kotlin seam) + P2a (Swift helper, frame-streaming scanner,
in-window preview, protocol tests) + **P2b build wiring** (in-bundle helper embed +
`NSCameraUsageDescription` + bundle-path-first resolution) landed. Remaining:
actual code-signing/notarization + on-device camera verification (environmental).
**`drive-desktop` wiring landed 2026-07-21** (§8.6) — Drive spawns the scanner
from `RedeemInviteScreen`, so it now carries the same in-bundle helper +
`NSCameraUsageDescription` Control has. **The seamless-grant route is decided:
option 3 (in-process Panama/FFM) was spiked and validated 2026-06-27 — §9 "Spike
result" — and scoped for productionisation into `:ui-core` in §13.**

## 1. Problem

Control's pairing wizard offers "Scan a QR code", but on desktop the `QrScanner`
actual is a placeholder: *"Camera scanner not available on Desktop."* Users fall
back to manual code entry. Android (CameraX + ZXing; ML Kit until 2026-10) and iOS (AVFoundation,
`control-ios/Sources/QRKit.swift`) both have real scanners; desktop has none.

## 2. Why it's stubbed, and what's reachable

Two facts constrain the design:

1. **Desktop Control is a JVM / Compose Multiplatform process**, not a native
   app. The JVM exposes no camera API.
2. **iOS Control is a separate *native* SwiftUI app.** Its AVFoundation scanner
   runs in an iOS process and cannot be linked from the JVM — "share the iOS
   code" is not reachable by code-sharing.

So a desktop scanner needs a bridge from the JVM to *some* camera stack.

### Options considered

| Approach | New 3rd-party dep | Platforms | Notes |
|---|---|---|---|
| **A. macOS-native helper** | **none** | macOS only | Bundled Swift binary (AVFoundation); Win/Linux keep manual entry |
| B. JavaCV / OpenCV + ZXing | JavaCV (tens of MB of natives) | all desktops | One code path, but heavy for "just scanning"; flaky camera/TCC on macOS |
| C. Hybrid (A on Mac, B on Win/Linux) | JavaCV | all desktops | **Does not** save the dependency — JavaCV is still pulled for Win/Linux; pays both native-code *and* heavy-dep cost |

**Decision: A.** It adds zero third-party libraries (the project's stated
priority), reuses the existing `ProcessBuilder` + runtime-OS-branch pattern, and
covers the realistic desktop-Control audience (Mac-heavy, given the existing
macOS-integration work). Windows/Linux retain the manual-code fallback that the
pairing wizard already renders. C only makes sense if Win/Linux scanning becomes
a hard requirement *and* JavaCV proves too unreliable on macOS — a narrow window
— and even then it saves nothing on dependency weight.

JavaCV reliability on macOS: it routes through OpenCV's AVFoundation backend, so
it's the same underlying API several layers removed — "works in dev, flaky in the
field" (camera-index quirks, session-start hangs, and silent TCC denial for an
unsigned JVM). Native AVFoundation is what Apple tests.

## 3. Existing patterns reused

- **Subprocess bridge:** `DesktopMasterKeyStore` (`:control-shared-proxy`,
  `desktopMain`) wraps `/usr/bin/security` via `ProcessBuilder` and reads the
  result from stdout.
- **Compiled-Swift helper on desktop:** `control-desktop` already `swiftc`-compiles
  the macOS menu-bar tray agent (`macos/TrayAgent/*.swift`) and embeds it in the
  `.app` at `Contents/Helpers/tray-agent` (`desktop-macos-integration.md` §6a).
  `qr-scan-helper` reuses exactly this pattern; the only new build work is one
  more `swiftc` task in `:ui-core`.
- **Runtime OS branch:** `PlatformProxyModule` already does
  `os.contains("mac", ignoreCase = true)` because *desktop is a single Kotlin
  target* — Mac/Windows/Linux all compile to `desktopMain`, so `expect`/`actual`
  cannot distinguish them; the Mac-vs-rest split must be a runtime decision.

## 4. Why not Koin / why the composable stays `expect`/`actual`

`QrScanner` is a `@Composable`; injecting a composable through Koin reads worse
at every call site and loses `@Composable` ergonomics. More decisively,
**`:ui-core` does not use Koin at all** — DI lives at the app level
(`:control-shared-proxy`). Adding Koin to `:ui-core` solely for this would be the
bigger, worse change. The scanner's only dependency is the path to its bundled
helper binary, resolved from `:ui-core`'s own resources at runtime, so it's
instantiated directly in the desktop actual (`QrCameraScanner.forCurrentOs()`).
If a future need to swap implementations from the app arises, lift it to a
`CompositionLocal` then — not now.

## 5. Design

```
QrScanner (expect/actual @Composable, call sites unchanged)
  └─ desktop actual: QrCameraScanner.forCurrentOs()
        ├─ macOS  → CameraScannerSurface(scanner) → collects MacQrCameraScanner.scan()
        └─ else   → "not available" placeholder (+ caller's manual fallback)
```

Non-UI capture is a plain interface streaming preview frames + a terminal result:

```kotlin
internal interface QrCameraScanner {
    fun scan(): Flow<ScanEvent>           // preview frames until a terminal result
    companion object { fun forCurrentOs(): QrCameraScanner? }
}

internal sealed interface ScanEvent {
    data class Frame(val jpeg: ByteArray) : ScanEvent   // live preview frame
    data class Result(val outcome: ScanOutcome) : ScanEvent
}

internal sealed interface ScanOutcome {
    data class Decoded(val payload: String) : ScanOutcome
    data object Cancelled : ScanOutcome
    data object PermissionDenied : ScanOutcome
    data object NoCamera : ScanOutcome
    data class Failed(val reason: String) : ScanOutcome
}
```

`CameraScannerSurface` collects `scan()` in a `LaunchedEffect` (cancellation
tears down the helper), decodes each `Frame` JPEG to an `ImageBitmap` via Skia
(`org.jetbrains.skia.Image.makeFromEncoded(...).toComposeImageBitmap()` — no new
dependency) and draws it **inside the pairing screen**, always alongside the
wizard's "Enter code instead" affordance.

## 6. Why frame-streaming (the "same window" decision)

The preview must visually be the *same* window as the pairing screen. macOS
`AVCaptureVideoPreviewLayer` is a native `NSView`/`CALayer` and **cannot** be
composited into the Compose/Skia surface, and KMP has no window-in-window mode.
The realistic paths:

- **In-process JNI** (AWT `SwingPanel` + JAWT-attached `AVCaptureVideoPreviewLayer`):
  true native preview in-window, but the camera then runs *in the JVM* (a JNI
  dylib, not a subprocess) and hits the AWT "airspace" overlap/clip problem.
  Abandons the clean subprocess design.
- **Separate helper window:** simplest, but a second OS window is always a
  separate window — it can be positioned over Control to *look* attached, but
  never genuinely the same window (cross-process view embedding isn't available).
- **Frame-streaming (chosen):** the subprocess stays headless and streams
  JPEG preview frames back; Compose renders them as real in-window content. No
  JNI, no airspace, no second window, still zero new dependencies. Cost: ~+60–100
  lines vs. headless. Latency is a non-issue for a QR.

## 7. Swift helper (`qr-scan-helper`)

A ~130-line standalone executable lifted from `QRKit.swift`'s
`ScannerViewController`:

- `AVCaptureSession` + `AVCaptureMetadataOutput` (`[.qr]`) for native decode +
  `AVCaptureVideoDataOutput` (BGRA, `vga640x480`, late-frames discarded) for
  preview, throttled to ~12 fps and JPEG-encoded via `CIContext`.
- Pre-flight `AVCaptureDevice.authorizationStatus` / `requestAccess` →
  `permission_denied`; `default(for: .video)` missing → `no_camera`.
- Streams a **framed binary protocol** to stdout, then exits on decode.

```
[1-byte type][4-byte big-endian length][payload]
  type 0x01 = FRAME  : JPEG preview bytes
  type 0x02 = RESULT : UTF-8 "decoded\t<payload>" | "permission_denied"
                       | "no_camera" | "failed\t<reason>"
```

`MacQrCameraScanner.scan()` resolves the binary, `ProcessBuilder(bin).start()`,
runs `parseFramedStream(proc.inputStream)` (pure over an `InputStream`, so unit
tested without a camera — a clean EOF / partial frame → `Cancelled`), and
`destroyForcibly()` in `finally`.

**stdin is the liveness line.** The parent never writes to the helper; it only
holds the pipe open, and the helper exits on EOF. `destroyForcibly()` covers a
cancelled scan but not a parent that is itself SIGKILLed, and nothing covered a
helper started by hand: one run detached on `/dev/null` held the camera for
three hours, because the only other exit is a decoded QR code. Consequence for
the Kotlin side: never close or redirect the helper's stdin, or it exits before
the first frame. `probe` mode does not watch stdin — it exits on its own.

## 8. Build & bundling

**P2a (landed):**
1. A `compileQrScanHelper` `Exec` task runs `swiftc -O` into a build dir wired as
   a `desktopMain` resource, so the binary rides in the `:ui-core` desktop jar at
   `/qr-scan-helper`. Registered only on a macOS build host (config-time `if`,
   not `onlyIf`, to stay configuration-cache compatible); absent elsewhere.
2. `HelperBinary.resolve()` extracts the resource to a temp file, `setExecutable`,
   caches it. Works for unsigned dev runs.

**P2b build wiring (landed):**
3. `embedQrScanHelper` (in `control-desktop`, mirrors `embedTrayAgent`) drops the
   `:ui-core`-compiled binary into `TenshuControl.app/Contents/Helpers/qr-scan-helper`;
   wired into the `packageControlApp` umbrella (+ a presence check) so it always
   ships, like the tray. `HelperBinary.resolve()` prefers this in-bundle copy
   (derived from `ProcessHandle.current()` → `Contents/Helpers/`) and falls back
   to the temp-extract for dev. The in-bundle copy is covered by the app's
   signature / `--deep` re-sign, unlike a temp-extracted unsigned binary (liable
   to be killed under hardened runtime).
4. **`NSCameraUsageDescription`** added to the macOS `infoPlist {}` in
   `control-desktop` (verified present in the packaged `Info.plist`, alongside
   the existing `LSUIElement`).

**P2b remaining (environmental — can't run here):**
5. **Signing / TCC:** camera consent is attributed to the responsible `.app`
   bundle. A signed, jpackaged app prompts once and works (the `embed` runs
   before `signControlAppForAppGroup`'s `--deep`). An **unsigned dev
   `:control-desktop:run` may be silently denied** — so on-device verification
   needs a signed/packaged build (free Team `FAYT82GWD7`).
**8.6 `drive-desktop` wiring (landed 2026-07-21):**
6. Drive *does* have the scanner — `RedeemInviteScreen` (contact invitation
   redemption, CI-4b) calls `QrScanner`, so it needed both halves Control has:
   an `embedQrScanHelper` task copying the `:ui-core`-compiled binary into
   `TenshuDrive.app/Contents/Helpers/`, and **`NSCameraUsageDescription`** in
   its macOS `infoPlist {}`. The plist key is not optional garnish: a spawned
   helper's TCC identity is its *responsible process*, i.e. the host app, so
   without the key macOS kills Drive on camera access no matter where the binary
   lives. Both `packageDmg`/`packagePkg` depend on the embed (alongside
   `embedImageDecodeHelper`); Drive has no `packageDriveApp` umbrella to hang it
   on. ⚠ Drive now also inherits §9's first-grant hazard.

## 9. First-grant handling (the macOS host-crash workaround)

Granting camera access for the **very first time** can make macOS SIGKILL the
host app (Control/Drive). The host is the responsible TCC subject for the
helper's `requestAccess`, and the kill arrives with no JVM crash report — no
`hs_err`, no `.ips`, no unified-log entry — the signature of a TCC-driven
termination, not a Compose/render fault. This was confirmed by a **no-frames
probe**: the host still died with zero preview frames streamed, so it is not the
live-preview rendering; substituting the skiko render API (`SOFTWARE_FAST` /
`OPENGL`) was inconclusive because only Metal launches on the test host at all.

Two mitigations ship:

1. **No live preview while the permission dialog is up.** On `.notDetermined`
   the helper calls `requestAccess` and, on grant, emits `retry` and exits
   *without* starting a session. `MacQrCameraScanner.scan()` re-spawns a fresh
   helper that finds `.authorized` and streams the preview with no dialog on
   screen (the two-pass loop). This makes the *survive-the-grant* path seamless.

2. **A one-time pre-grant explainer** (the shipped "option 1"). Because the host
   can still be killed *before* the re-spawn, the desktop surface no longer
   springs the camera mid-pairing. It first runs a **pure status probe**
   (`qr-scan-helper probe` → `authorized | not_determined | denied | no_camera`;
   never opens a session, so it can't crash the host — `QrCameraScanner.probePermission()`)
   and, when undecided, shows an *"Enable camera scanning"* card explaining
   *"approve the prompt; the app may close once — just reopen it; this only
   happens the first time."* After that single grant every later scan probes
   `.authorized` and goes straight to the live preview. Both Control's pairing
   wizard and Drive's redeem-invite screen get this for free — it lives in the
   `ui-core` desktop actual (`QrScanner.desktop.kt`).

**Status (shipped):** the explainer + probe make the first grant a *one-time,
explained* event — the app may close once on the very first Allow, then never
again. This is the accepted behaviour (it matches what Teams/Electron apps do —
see "How other apps handle this" below). The reopen step is the only residual
rough edge; removing it is **option 2**, deferred.

### Option 2 (deferred): make the helper its own TCC subject

The crash exists because **the JVM host (Control/Drive) is the responsible TCC
subject** for the helper's `requestAccess`: the helper is a bare executable we
`posix_spawn` from the JVM, so it inherits the host's TCC identity, and macOS
terminates that host on the first grant. The fix is to give the camera work an
identity of its own so the grant is attributed to *it*, never to the host.

Shape (for whoever picks this up cold):

1. **Helper becomes a tiny signed `.app`**, not a bare Mach-O.
   `qr-scan-helper.app/Contents/` with its own `Info.plist`
   (`CFBundleIdentifier = app.tenshu.control.qrhelper`, its own
   `NSCameraUsageDescription`). It must be **code-signed** so the bundle ID is
   stable across launches — an unsigned/ad-hoc bundle gets a fresh TCC identity
   each run and would re-prompt forever. Embed it under `Contents/Helpers/`
   beside the tray agent; it's covered by the app's `--deep` re-sign.
   ⚠ Earlier we found that *embedding an `Info.plist` with a bundle id made
   `AVCaptureDevice.authorizationStatus` mis-report `.authorized`* — that was for
   a **bare** executable carrying a phantom plist. A real `.app` bundle with a
   real (signed) identity is the supported case and does not have that bug; the
   lesson is "bundle id without a bundle is the trap," not "no bundle id."

2. **Launch it as its own process group**, detached from the JVM, so it is the
   responsible process. `NSWorkspace.openApplication` / LaunchServices `open`,
   or `posix_spawn` with `responsibility_spawnattrs_setdisclaim(attr, 1)` (the
   private SPI we already prototyped) so the child *disclaims* the parent and
   becomes its own responsible subject. The disclaim path is lighter than a full
   LaunchServices launch and keeps a normal parent/child relationship — prefer it
   if it proves stable (our earlier disclaim attempt crashed, but that was while
   also re-exec-ing on the authorized path; a clean disclaim-once-at-spawn has
   not been tried).

3. **Rework IPC off the stdout pipe.** A LaunchServices/`open` launch is detached,
   so the child does *not* inherit our stdout pipe — the framed
   `[type][len][payload]` stream needs a real channel. Use a **Unix-domain
   socket**: the JVM binds `~/Library/Application Support/Tenshu/qr-<nonce>.sock`,
   passes the path as an arg, the helper connects and writes the same framed
   protocol over it. `MacQrCameraScanner` swaps `process.inputStream` for the
   socket's `InputStream`; `parseFramedStream` / `readFirstResult` are unchanged
   (they're already pure over an `InputStream`). If we keep the `disclaim` +
   `posix_spawn` path instead, the inherited pipe still works and this step is
   unnecessary — which is the main reason to try disclaim first.

Why it's deferred: it's a real rewrite (new signed sub-bundle + signing wiring +
socket transport) for a payoff that only removes a one-time reopen the user has
accepted. Ship the explainer; revisit if the reopen proves annoying in practice
or if `drive-desktop` adoption raises the stakes.

### Option 3 (SPIKED — validated 2026-06-27): in-process camera access via Panama/FFM

The root of the crash is that we **spawn a bare subprocess** that touches the
camera, so macOS attributes the access to the parent JVM and kills it. Option 2
fixes that by giving the helper its own identity. Option 3 fixes it from the
other end: **don't spawn anything — open the camera inside the JVM process
itself**, so the app is both the accessing process *and* the responsible TCC
subject. That's the ordinary native-app situation (Zoom, FaceTime, Photo Booth)
which doesn't crash, so it plausibly removes the SIGKILL *and* the one-time
reopen with no second bundle and no socket transport.

Shape:

1. **Bridge to AVFoundation from the JVM** via the **Foreign Function & Memory
   API** (`java.lang.foreign`, aka Project Panama — stable since JDK 22; we'd
   need to confirm the bundled runtime's version or bump it). No JNI shim, no
   subprocess. Call `AVCaptureDevice.requestAccess` / build an
   `AVCaptureSession` + `AVCaptureVideoDataOutput` and run `VNDetectBarcodesRequest`
   directly — the same calls the Swift helper makes, expressed as C-ABI
   downcalls. (A thin JNI/JNA shim is the fallback if hand-writing the
   Objective-C message sends through FFM proves too painful — `objc_msgSend`
   variadics are the awkward part.)
2. **Frame delivery stays the same shape.** The sample-buffer callback hands us
   pixel buffers in-process; JPEG-encode (or hand the bytes straight to Skia) and
   feed the existing in-window `Image` path. No framed stdout/socket protocol at
   all — `parseFramedStream` / `readFirstResult` would no longer be needed for the
   macOS path (keep them only if option-2-style out-of-process is also retained).
3. **Run-loop caveat (the real risk).** AVFoundation's async callbacks need a
   live `CFRunLoop` on the main thread. On Compose Desktop the main thread is the
   AWT event loop; AVFoundation generally cooperates with the main `CFRunLoop`
   that AWT already pumps, but this is the part to spike first — a wrong-thread
   session is the most likely failure. Still needs the app signed with
   `NSCameraUsageDescription` (already present).

Trade vs option 2: option 3 is *less* packaging/IPC work (no sub-bundle, no
socket) but *more* native-bridge plumbing (hand-rolled C-ABI calls + the
main-thread run-loop spike), and it keeps the camera code inside the JVM rather
than isolated in a crash-only-itself subprocess. Pick option 2 if process
isolation is valued (a camera crash can't take Control down); pick option 3 if
removing the subprocess + IPC entirely is worth the FFM effort. **Spike first:**
a ~50-line FFM proof that opens the session in-process and survives the first
grant decides it before committing to either rewrite.

#### Spike result (2026-06-27) — the bet pays off

A self-contained ~260-line single-file Java FFM proof (JDK 21 + `--enable-preview`,
arm64, **zero third-party deps**, pure `java.lang.foreign` over `libobjc` +
AVFoundation) exercised the whole path in-process. Every piece the option depended
on works:

- **The FFM → Objective-C bridge works.** `objc_getClass` / `sel_registerName` /
  `objc_msgSend` as C-ABI downcalls; read the `AVMediaTypeVideo` framework constant;
  built `AVCaptureSession` + `AVCaptureDeviceInput` + `AVCaptureVideoDataOutput`
  entirely from Java. The `objc_msgSend` "awkward part" resolves cleanly: bind a
  *separate* downcall handle per call signature and **do not** mark it variadic
  (no `firstVariadicArg`) on arm64 — the dispatched method expects args in registers
  per AAPCS. No JNI/JNA shim was needed.
- **Objective-C blocks are constructible from Java.** `requestAccessForMediaType:`
  `completionHandler:` was driven by a hand-built global block literal in native
  memory (`isa = _NSConcreteGlobalBlock`, `flags = BLOCK_IS_GLOBAL`, an upcall stub
  as `invoke`, a 32-byte descriptor). Its completion fired with `granted=true`.
- **Runtime-registered delegate classes work.** `objc_allocateClassPair` +
  `class_addMethod` (type `v@:@@@`) + an upcall `IMP` produced a working
  `AVCaptureVideoDataOutput` sample-buffer delegate. It received **68 real frames in
  3 s (~22 fps)** in-process — proving live capture, not just session setup.
- **First-grant survival — CONFIRMED (witnessed, not inferred).** Tested cleanly:
  `tccutil reset Camera` → status `notDetermined`, then a single JVM called
  `requestAccessForMediaType:completionHandler:` and **blocked on the completion
  latch while alive** (pid logged before the call). A **real TCC dialog** appeared,
  was **Allowed**, and the completion fired with the **same pid still running**
  (`granted=true`) — the process was demonstrably alive *through* the grant moment,
  then went on to stream 76 frames and exit cleanly (status → `authorized`). This is
  the §9 crash's mirror image: in-process the host is both accessor *and* responsible
  subject (the Zoom/FaceTime situation), so the bare-subprocess SIGKILL does not occur.
  (An earlier sloppier reading inferred this from a cross-run `notDetermined →
  authorized` transition where no process was provably alive at the grant; the reset
  re-test above is the rigorous version.)
- **The run-loop "real risk" mostly dissolves for the QR path.** Frames arrive on a
  `libdispatch` queue passed to `setSampleBufferDelegate:queue:`, **not** the main
  thread — so the `AVCaptureVideoDataOutput` path (the one we need) does not depend on
  pumping the AWT/`CFRunLoop`. The `requestAccess` completion is likewise delivered on
  an internal queue (a Java `CountDownLatch` sufficed; no pump). The main-thread
  `CFRunLoop` only matters for `AVCaptureVideoPreviewLayer` / main-queue delegates,
  which this design doesn't use.

Residual caveats (none blocking):

1. **JDK version.** FFM is *preview* in JDK 21 (`--enable-preview`), stable in 22+.
   The desktop app bundles its own JRE, so this is a packaging choice (bump the
   bundled runtime to 22+, or ship `--enable-preview`), not a portability risk.
2. **TCC subject in the spike was the terminal**, since a bare `java` launch has no
   bundle identity; the grant was attributed to the launching terminal. Production is
   *more* favourable, not less — a signed `Control`/`Drive.app` is its own stable
   subject (exactly the crash-free native-app case). Final on-the-real-app
   confirmation rides the normal signed-build path, same as every other TCC item here.
3. **Decode still needs Vision.** The spike proved the frame pipeline via
   `AVCaptureVideoDataOutput`; QR *decode* must run `VNDetectBarcodesRequest` over the
   sample buffers (macOS has no `AVCaptureMetadataOutput` QR path — the same lesson the
   shipped Swift helper already encodes). That port is well-understood C-ABI calls on
   the frame buffers, not a new unknown.
4. **Cost is FFM boilerplate.** ~260 lines for the full mechanism (per-signature
   `objc_msgSend` handles, the block literal, runtime class registration). A small
   reusable `ObjC` helper would shrink call sites; it's verbose, not hard.

**Verdict:** option 3 is now the validated front-runner over option 2 for the macOS
path. It removes the subprocess, the framed IPC, *and* the one-time reopen with no
second signed bundle and no socket transport — at the cost of FFM plumbing that the
spike showed to be tractable (the `objc_msgSend`/block/run-loop unknowns are all
resolved). The throwaway proof lives in the session scratchpad (`AvfSpike.java` /
`AvfSpike2.java`); productionising it means porting it into `:ui-core`'s desktop
actual behind the existing `QrCameraScanner` seam and adding the Vision decode call.

### How other apps handle this

- **Native AppKit apps that access the camera in-process** (Zoom, FaceTime,
  Photo Booth) don't hit this: the process touching `AVCaptureDevice` *is* the
  stable, signed app with the usage description, so the grant is attributed to
  itself and there's nothing to kill. Many still request authorization at a
  *quiet* moment (onboarding/settings), not the instant the camera is needed —
  which is the spirit of our explainer.
- **Electron / Chromium apps** (Teams, Slack, VS Code) push camera access into a
  bundled GPU/renderer **helper** that is a properly signed sub-app with its own
  bundle id (`*.app/Contents/Frameworks/… Helper (GPU).app`) — i.e. exactly
  option 2's structure, done by the framework. Even so, several (Teams notably)
  **require a restart after the first grant**, which is the same one-time reopen
  we ship and the reason it's an acceptable v1.
- **The thing to avoid** is what we started with: a long-lived host process
  spawning a *bare* camera executable that inherits the host's TCC identity. The
  general fixes are (a) access the camera from the signed app itself, or (b) give
  the helper its own signed bundle identity (option 2).

## 10. Phasing

- **P1 (landed):** `QrCameraScanner` seam + desktop actual OS-branch; macOS
  stubbed to `NoCamera` (behaviour unchanged). Pure Kotlin, no new deps.
- **P2a (landed):** `qr-scan-helper.swift` + `swiftc` task + jar-resource
  embedding + frame-streaming `scan()` + in-window preview + `parseFramedStream`/
  `parseResult` tests. Builds + runs on a Mac dev host; camera subject to TCC.
- **P2b build wiring (landed):** `embedQrScanHelper` → `Contents/Helpers/` (wired
  into `packageControlApp`) + bundle-path-first `HelperBinary.resolve()` +
  `NSCameraUsageDescription`. Verified by packaging `TenshuControl.app` and
  asserting the embedded Mach-O + the plist key.
- **P2b remaining (environmental):** real code-signing/notarization + manual
  on-device camera verify on a signed build; `drive-desktop` wiring if it exposes
  the scanner.
- **First-grant explainer (landed — "option 1", §9):** pure-status `probe` mode
  on the helper + `probePermission()` + a one-time "Enable camera scanning" card
  shown before the (host-crashing) first grant. Covered by `readFirstResult` /
  `parsePermissionResult` unit tests.

## 11. Open questions

1. OK that on-device verification is gated on a signed package (unsigned `run`
   likely TCC-denied)?
2. Should `drive-desktop` also get the scanner in P2b, or Control-only first?
3. The one-time reopen is **accepted for v1** (it matches Teams/Electron). *If*
   we later want the grant fully seamless: **option 3 (in-process Panama/FFM) is
   now spiked and validated** (§9 "Spike result") — it survived a real first-grant
   in-process, delivered live frames off a `libdispatch` queue (no main-thread
   run-loop dependency), and needs no second bundle or socket. It is the front-runner
   over option 2. The productionisation into `:ui-core` behind the `QrCameraScanner`
   seam is scoped in **§13** (phases P0–P6).

## 12. Out of scope

Windows (Media Foundation/DirectShow) and Linux (V4L2) native scanners. They keep
the manual-code fallback. Revisit only if Win/Linux scanning becomes a hard
requirement — at which point option B/C and their dependency cost get re-weighed.

## 13. Productionising option 3 into `:ui-core` (scope)

Turning the validated §9 spike into the shipping macOS scanner, behind the
**unchanged** `QrCameraScanner` seam. The seam, the `ScanEvent`/`ScanOutcome`/
`CameraPermission` contract, the pure mapping helpers, and the Compose preview UI
all stay; the **subprocess + Swift helper + framed-stdout protocol** get swapped for
an in-process FFM implementation. Net code is expected to *shrink* (a Swift file, two
Gradle tasks, `HelperBinary`, `spawnHelper`, and the framed-protocol parsers all go).

### 13.1 What stays vs. what swaps

| Component (file) | Fate |
|---|---|
| `QrScanner.kt` expect / `QrScanner.desktop.kt` Compose preview | **stays** (one decode-path tweak, D2) |
| `QrCameraScanner` interface + `ScanEvent`/`ScanOutcome`/`CameraPermission` | **stays** (drop `NeedsRetry`, D3) |
| `forCurrentOs()` | **stays** — selects the FFM impl on macOS |
| `MacQrCameraScanner` (subprocess) + `spawnHelper` + `HelperBinary` | **replaced** by `FfmMacQrCameraScanner` |
| `parseFramedStream`/`readFirstResult`/`parseResult`/`parsePermissionResult` + `TYPE_*` | **removed** (no stdout protocol) — keep `parsePermissionResult` only if convenient |
| `qr-scan-helper.swift`, `compileQrScanHelper` (`:ui-core`), `embedQrScanHelper` (`:control-desktop`) | **removed** |
| `NSCameraUsageDescription` infoPlist | **stays** (still required for in-process access) |

### 13.2 Decisions

- **D1 — JDK ≥ 22 (FFM final), not 21+`--enable-preview`.** `java.lang.foreign` is
  *final* in 22. Prefer bumping the toolchain over shipping preview: Kotlin does not
  emit javac's "preview-enabled" classfile flag, so FFM-preview on 21 throws
  `Preview features not enabled` at runtime — a sharp edge we avoid by targeting a
  final-FFM JDK. The Compose Desktop / jpackage bundled runtime must match.
  **As-built (P0, 2026-07-22): JDK 25, and the bump is contained to three modules,
  not the ~100 on `jvmToolchain(17)`.** See §13.5.
- **D2 — raw BGRA → Skia, drop the JPEG hop.** The Swift helper JPEG-encoded each
  preview frame (`CIContext`); in-process we already hold the `CVPixelBuffer`, so copy
  the locked BGRA bytes straight into `ScanEvent.Frame` and render via
  `Image.makeRaster(ImageInfo …, bytes, stride)` instead of `makeFromEncoded`. Less
  ObjC surface, lower latency. Touches only the **desktop-internal** `Frame` type and
  the `LivePreview` decode line — the Android path (CameraX) is unaffected.
- **D3 — drop the first-grant explainer + `NeedsRetry` two-pass for the FFM path.**
  Both exist only because the *subprocess* crashed the host (§9). In-process survives
  the grant (spike-proven), so `scan()` becomes: `requestAccess` → await the block →
  `startRunning`, no card, no reopen, no re-spawn. **Gated**: keep the explainer behind
  the bring-up flag (D5) until the signed-build on-device pass confirms survival on a
  real app identity (the spike's TCC subject was the terminal, not a signed `.app`).
- **D4 — class-registration is process-global; the session is per-scan.** The runtime
  ObjC delegate class (`objc_allocateClassPair`) can only be registered once per name,
  so register-and-cache it under a process-global `Arena` (its IMP upcall stub must
  outlive every scan). The `AVCaptureSession` + delegate instance + `dispatch_queue` +
  their `Arena` are per-`scan()`, torn down on Flow cancellation: `stopRunning`, drop
  the delegate, `release` the owned objects (`alloc/init` objects are +1; autoreleased
  returns like `defaultDeviceWithMediaType:` are wrapped in an autorelease pool), close
  the Arena.
- **D5 — keep the Swift path selectable during bring-up.** A temporary
  `-Dtenshu.qr.impl=swift|ffm` switch in `forCurrentOs()` lets us A/B the two on the
  same build through P2–P4; the Swift path and its build tasks are deleted only in P5
  after the FFM path verifies on-device.

### 13.3 Phases

- **P0 — toolchain bump (build-only, no behaviour). — SHIPPED 2026-07-22, §13.5.**
  Landed as `:macos-helpers` (compiles on 25, emits JVM-17) + `:control-desktop` /
  `:drive-desktop` (toolchain **and** jpackage runtime on 25). `:ui-core` did **not**
  move — see §13.5 for why that turned out to be both unnecessary and undesirable.
  (The "CMP 1.7.3 / skiko sanity" risk this phase was written against is stale: CMP
  went to 1.11.1 on 2026-07-08, and nothing in the desktop suites regressed.)
- **P1 — reusable ObjC/FFM bridge primitive (no QR yet). — SHIPPED 2026-07-22, §13.6.** A new internal desktopMain
  helper (e.g. `components/macffi/ObjC.kt`) distilled from the spike: linker + `libobjc`
  / framework lookups, `cls()`/`sel()`, the per-signature `objc_msgSend` handles (with
  the arm64 non-variadic rule documented; plain `objc_msgSend` covers all our
  pointer/int/void returns on both arm64 **and** x86_64 — no `_stret`/`_fpret` needed
  since we return no structs/floats), `alloc`/`init`/`release`/autorelease-pool,
  NSString↔Java, framework-constant reader, register-once class creation, upcall-stub
  helpers, plus plain-C downcalls for CoreVideo/CoreMedia (`CVPixelBuffer*`,
  `CMSampleBufferGetImageBuffer`) and libdispatch (`dispatch_queue_create`). Lands the
  primitive first (house style). Camera-free `desktopTest` smoke: class/selector
  resolution + NSString round-trip + allocate/register a throwaway class.
- **P2 — `FfmMacQrCameraScanner`: probe + capture + preview (no decode). — SHIPPED 2026-07-22 (§13.7 engine, §13.8 adapter).**
  `probePermission()` via `authorizationStatusForMediaType:` (no session → can't crash);
  `scan()` on `Dispatchers.IO` building session + BGRA `AVCaptureVideoDataOutput` +
  registered delegate on a dispatch queue, `requestAccess` on `.notDetermined`, emitting
  raw-BGRA `Frame`s (D2). Wire `forCurrentOs()` + the D5 flag. Verifies same-window
  preview renders from the in-process path.
- **P3 — Vision QR decode. — SHIPPED 2026-07-22, §13.9.** Bridge `VNImageRequestHandler(initWithCVPixelBuffer:)` +
  `VNDetectBarcodesRequest` (`symbologies=[.qr]`) per (throttled) frame, read
  `VNBarcodeObservation.payloadStringValue`, emit `ScanOutcome.Decoded`. Reaches parity
  with the Swift helper. Camera-free `desktopTest`: `CIQRCodeGenerator` image →
  `VNImageRequestHandler` → assert payload (mirrors the existing Swift-side Vision
  probe). ⚠ macOS QR decode **must** use Vision, never `AVCaptureMetadataOutput` (the
  shipped helper's hard-won lesson).
- **P4 — lifecycle hardening + first-grant simplification.** Tie teardown to Flow
  cancellation (D4); prove repeated open/close of the pairing screen neither leaks nor
  double-registers. Remove the explainer + `NeedsRetry` (D3) once the signed-build pass
  confirms grant survival on a real app identity. Confirm no main-thread `CFRunLoop`
  dependency for the video-data path (spike-indicated).
- **P5 — delete the Swift helper + build tasks (gated on P2–P4 on-device verify).**
  Remove `qr-scan-helper.swift`, `compileQrScanHelper`, `embedQrScanHelper` (now in
  **both** `:control-desktop` and `:drive-desktop`), the `desktopProcessResources`
  dependsOn, `spawnHelper`, the subprocess scanner, and the now-dead framed-protocol
  parsers + their tests. Keep the seam.
  ⚠ **Correction (2026-07-21): `HelperBinary` must NOT be deleted.** It moved to
  `:macos-helpers` and is now shared with `image-decode-helper`, the ImageIO bridge
  behind HEIC decode (`drive-content-preview.md` §3.1–§3.2) — which has nothing to do
  with the camera and isn't going away. Only the QR *binary* and its spawn path die.
- **P6 — `drive-desktop` adoption (now trivial). — HALF-DONE 2026-07-21.** Drive's
  redeem-invite already uses the same `:ui-core` `QrScanner` composable; post-FFM there
  is **no binary to embed**, so it just needs `NSCameraUsageDescription` in
  `drive-desktop`'s infoPlist — **which has landed** (§8.6), along with an
  `embedQrScanHelper` that P5 will delete. The plist key is the permanent half. Resolves
  the old §11 Q2 ("Control-only first?") — both apps get the scanner for free.

### 13.4 Testing & the one environmental gate

Camera-free and CI-runnable: the pure mappers (existing), the P1 ObjC-bridge smoke
test, and the P3 `CIQRCodeGenerator → Vision` decode round-trip. Only **live capture +
the first-grant prompt** remain manual-on-a-signed-build — the same gate as the current
Swift helper and every other TCC item here, not a regression. Primary residual risk is
the P0 JDK bump rippling into CMP 1.7.3 / skiko, contained by keeping P0 an isolated,
launch-and-test-verified PR.

### 13.5 P0 as-built — the toolchain bump is three modules, not the graph

**Shipped 2026-07-22.** The phase was written as "`:ui-core` + the desktop app modules",
which under-reads the repo: **101 modules** sit on `jvmToolchain(17)`, and `:ui-core`
itself has an **Android** target, so moving its toolchain would have dragged the Android
build along for a JDK bump it has no use for. The bump that actually shipped touches
three build files and leaves the other ~100 modules alone.

**The trick that contains it: compile on 25, emit 17.** `:macos-helpers` — the
Compose-free JVM module that already hosts the ImageIO bridge — sets
`jvmToolchain(25)` with `compilerOptions.jvmTarget = JVM_17`. Kotlin, unlike javac's
`--release`, compiles against the *toolchain's full API* unless `-Xjdk-release` says
otherwise, so `java.lang.foreign` is visible while the emitted class files stay at
**major 61**, readable by every 17-target consumer. Verified both ways: a real `strlen`
downcall runs (`ForeignSupportTest`), and `javap -v` reports major 61 on the same class.
`java.{source,target}Compatibility` are pinned to 17 alongside, or Gradle's Java/Kotlin
JVM-target consistency check fails the build.

So the FFM camera code (P1–P3) should live in `:macos-helpers`, called from `:ui-core`'s
desktop actual behind the existing seam — the same shape `MacImageDecoder` already uses.
`:ui-core` never needs to see a 22+ JDK.

**⚠ The trap: jpackage does not follow `jvmToolchain`.** Setting the toolchain on
`:control-desktop` / `:drive-desktop` left the bundled runtime at **21** — jpackage embeds
whatever `compose.desktop.application.javaHome` points at, which defaults to the Gradle
*daemon* JVM. Nothing warns you: the build is green, every test passes, and the shipped
app fails at the first FFM call. Both app modules now set `javaHome` explicitly from the
same toolchain (`javaToolchains.launcherFor { … }`), and the bundled `release` files read
`JAVA_VERSION="25.0.3"`.

**No system JDK install needed** — `settings.gradle.kts` already applies the
`foojay-resolver-convention`, so Gradle auto-provisions Temurin 25 into `~/.gradle/jdks`.

**Verified:** `:macos-helpers` 2/2 (incl. the live downcall) · `:ui-core` 9/9 ·
`:drive-shared-ui` 274/274 · `:file-service-proxy` jvmTest 207/207 · both `.app`s built
and launched from the JDK-25 bundles with no crash. Not verified: FFM executing *inside*
a packaged bundle — jpackage strips `bin/java` from the jlink image, so there is no way
to run a probe in it; the runtime's `MODULES` does include `java.base` (where
`java.lang.foreign` lives), and P2 exercises the real thing.

**Not done, deliberately:** no `--enable-native-access=ALL-UNNAMED` JVM arg. JDK 25 issued
no restricted-method warning in this configuration, so adding the flag now would be
speculative; revisit at P2 when FFM actually runs inside the app (and before any JDK that
flips restricted native access to deny-by-default).

### 13.6 P1 as-built — the ObjC bridge, and the trap in it

**Shipped 2026-07-22.** `ObjC` + `Dispatch` in **`:macos-helpers`** (`app.tenshu.macos.ffi`),
not `:ui-core` — P0 (§13.5) established that the FFM-capable module is the Compose-free one.
14 camera-free tests, all running in the ordinary suite.

**What the tests actually prove**, because each is a place this can be silently wrong rather
than loudly broken:

- **Dispatch works and returns the right thing.** `NSString.length` returns 6 for `"tenshu"`.
  A *variadic* `objc_msgSend` binding tends to return a plausible-but-wrong number here on
  arm64 rather than failing — so the per-signature non-variadic rule is asserted, not assumed.
- **Strings round-trip**, including non-ASCII, through `stringWithUTF8String:`/`UTF8String`.
- **Framework constants read by identity.** `AVMediaTypeVideo` resolves and reads as `"vide"`.
  Loading AVFoundation prompts for nothing — only *using* the camera would.
- **A runtime-registered class calls back into Kotlin.** `objc_allocateClassPair` +
  `class_addMethod` with an FFM upcall stub as the IMP; messaging the instance runs the Kotlin
  lambda. That is exactly the mechanism the P2 frame delegate needs.
- **Registration is idempotent** — the runtime refuses a second `allocateClassPair` for a live
  name, so repeat calls return the cached class instead of nil.

**⚠ The trap, and it is not a compile error: Kotlin's `invokeExact` is signature-polymorphic.**
Its call-site type is taken from the *static shape of the call*, so the result must be cast to
the descriptor's return type — and must **not** sit behind a `?.`, which boxes it into
`Object`. Get it wrong and the build is green while the first invocation throws
`WrongMethodTypeException: handle's method type (…)byte but found (…)Object`. This cost one
red test in `class_addMethod` and is the single most likely way P2/P3 will break; every
`invokeExact` in `ObjC.kt` now binds the handle to a local and casts explicitly. (The
`vararg` message-send bridge sidesteps it entirely by using `invokeWithArguments`, at the
price of a boxing hop — fine for session setup, but the **per-frame** path in P2 should bind
its own exact handle rather than route through it.)

**Scope deviations from P1 as written:**

- **CoreVideo/CoreMedia downcalls deferred to P2.** P1 listed them, but with no consumer they
  could only be smoke-tested for "the symbol resolves" — the interesting part (reading pixels
  out of a `CVPixelBuffer`) needs a real sample buffer, which only P2 produces. Adding them
  now would be untested surface.
- **libdispatch included**, because it is both needed by P2 and genuinely testable now:
  `Dispatch.serialQueue(label)` creates and releases. Serial is load-bearing — frames must not
  decode out of order, and the queue gives that without a lock.

**⚠ Shape note for P2, which §13.1 doesn't spell out:** `FfmMacQrCameraScanner` **cannot** live
in `:macos-helpers`. It implements `QrCameraScanner` and emits `ScanEvent`, both of which live
in `:ui-core` — a Compose module on JDK 17. So P2 splits: a Compose-free **capture engine** in
`:macos-helpers` (FFM, JDK 25, emitting plain frames/payloads) plus a thin adapter in
`:ui-core`'s desktop actual that maps engine output to `ScanEvent`/`ScanOutcome`. That is the
same shape `MacImageDecoder` already uses, and it keeps `forCurrentOs()` unchanged.

### 13.7 P2 as-built (engine half) — in-process capture, and where the test boundary goes

**Shipped 2026-07-22.** `MacCamera` / `MacCameraSession` / `CoreVideo` in `:macos-helpers`,
19 tests green. This is the **engine** half of P2, per the §13.6 split: Compose-free, speaking
only in `CameraFrame`s. The `:ui-core` adapter, the `forCurrentOs()` wiring, the D5
`-Dtenshu.qr.impl` flag and the in-window preview render are **not** in this slice.

**Blocks had to be built first.** P1 didn't cover them, and
`requestAccessForMediaType:completionHandler:` is block-based — there is no block-free way to
ask for camera access. `ObjC.globalBlock` lays out the 32-byte literal by hand (`isa` →
`_NSConcreteGlobalBlock`, `BLOCK_IS_GLOBAL`, an upcall stub as `invoke`, a 16-byte
descriptor). **Global** is the right kind: no captured state, no lifetime, nothing for the
runtime to refcount — state the body needs is captured on the Kotlin side instead. Proven
camera-free against `NSArray.enumerateObjectsUsingBlock:`.

**Where the test boundary sits, and why: everything except `startRunning`.** `createSession`
returns a graph that is *wired but not running* — device, input, session, BGRA output,
registered delegate, serial queue — and `start()` is a separate call. That split is what keeps
the suite unattended: building the graph turns the camera on for nobody, while `startRunning`
lights the indicator and makes results machine-dependent. Live capture stays the §13.4 human
gate. Verified the authorization-gated tests genuinely run here rather than silently skipping
(status reads `Authorized`, so the session tests really did build against a real device).

**Two decisions worth keeping:**

- **Frames are copied, not aliased.** `CVPixelBuffer`'s base address is valid only between
  lock and unlock, and CoreVideo recycles the buffer immediately after — so anything outliving
  the callback (a Compose bitmap, a decode queue) must be our own memory. The lock is
  read-only; this path never writes back.
- **`bytesPerRow`, never `width * 4`.** CoreVideo pads rows for alignment. Any consumer that
  walks the buffer must step by the stride, which is why `CameraFrame` carries it.

**⚠ ABI note (a P1 shortcut corrected):** passing a `BOOL` or an `int` as a pointer-sized
argument happens to work on arm64/x86_64 because the callee reads the low bits — coincidence,
not contract. `ObjC.sendVoidWithBool` / `sendWithInt` give those calls correctly-typed
descriptors instead; the frame path should keep that habit.

**Delegate state is keyed by instance, not by class.** The ObjC class registers once per
process, so a per-session callback can't live on it. `FrameDelegate` keys callbacks by the
delegate *instance* address — which also means concurrent sessions coexist instead of the last
one silently winning. `close()` unregisters the callback **before** tearing the graph down, so
a frame delivered concurrently with teardown becomes a no-op rather than a use-after-free.

**Still unproven, and only a human can prove it:** that frames actually flow, and that the
first-grant no longer kills the app. Both need `start()` against a live camera on a signed
build (§13.4).

### 13.8 P2 as-built (adapter half) — backend selection and the raw-frame path

**Shipped 2026-07-22**, completing P2. `FfmMacQrCameraScanner` + `decodeBgraFrame` in
`:ui-core` desktopMain, 6 new tests (15 in the module).

**The adapter is deliberately dull**, and that is the §13.6 split paying off: it maps
`CameraAuthorization` → `CameraPermission` and `CameraFrame` → `ScanEvent.RawFrame`, and
contains no FFM at all. `:ui-core` stays on JDK 17 and never imports `java.lang.foreign`.

**`ScanEvent` gained `RawFrame` rather than changing `Frame`.** The two backends genuinely
deliver different things: the Swift helper *must* JPEG-encode because frames cross a pipe,
while in-process frames have no boundary to cross and are rastered straight from the
`CVPixelBuffer` copy (D2). Both shapes coexist for the D5 A/B window; the sealed `when` in
`LivePreview` makes the compiler enforce that neither is dropped.

**⚠ The Swift helper stays the `forCurrentOs()` default, and that is load-bearing.** The FFM
path previews but cannot yet *decode* — Vision is P3 — so defaulting to it would ship a camera
that never resolves a code. `-Dtenshu.qr.impl=ffm` opts in (D5). Flipping the default belongs
with P3 plus the on-device pass, and `defaultsToTheSwiftHelperEvenOnMac` guards it in the
meantime.

**The stride is the thing most likely to be got wrong**, so it is tested directly:
`rastersABgraFrameWhoseStrideExceedsItsWidth` feeds a 3-px-wide frame with a 16-byte stride
(real padding, 12 → 16). A renderer that assumed `width * 4` shears the image on any device
whose width isn't already aligned — a bug that looks like a camera fault, not a code fault.
Malformed geometry returns `null` instead of reading past the buffer, which on a live camera
is the difference between a blank preview and a crash in the render loop.

**Frames conflate rather than queue.** `trySend` into a capacity-1 `DROP_OLDEST` buffer means
a slow collector drops stale frames instead of back-pressuring the capture callback: for a
live preview only the newest frame is worth drawing, and blocking AVFoundation's delivery
queue would stall capture itself.

**`NeedsRetry` is dead weight on this path** and the adapter never emits it. It exists because
the *subprocess* had to exit and respawn around the first grant; in-process there is no second
process, so the flow asks and continues. The type stays for the Swift path until P5 deletes it.

**Unchanged, still the gate:** nothing here proves frames flow or that the first grant is
survivable — `start()` against a live camera on a signed build remains §13.4's human step.
What P2 now guarantees is that everything *around* that call is wired and covered.

### 13.9 P3 as-built — Vision decode, and what it does *not* settle

**Shipped 2026-07-22.** `Vision` in `:macos-helpers` + detection wired into the frame
delegate + `ScanOutcome.Decoded` emitted by the adapter. 23 tests in `:macos-helpers`
(was 19), 15 in `:ui-core`. **The FFM path is now functionally complete: it previews and it
decodes.**

**Tested without a camera, exactly as §13.3 prescribed:** render a QR with
`CIQRCodeGenerator`, hand the `CIImage` to Vision, assert the payload returns. That round trip
exercises everything new — the symbology constant read by identity, request configuration,
`performRequests:`, walking `results` — with no hardware and no permission. A Unicode payload
is included, because a contact invite can carry a petname and the NSString boundary is where
that would quietly mangle. `Vision.ciImageHandler` exists **for** that test and says so;
production uses `pixelBufferHandler`.

**Detection is throttled to ~10/s; preview never is.** `VNDetectBarcodesRequest` is real image
analysis and the camera runs at ~30 fps, but a code missed this frame is read on the next —
so detection costs a third of the work at no perceptible latency, while throttling the
*preview* would be visible immediately.

**One pixel buffer, two consumers.** `CoreVideo.imageBufferOf` was split out of
`readBgraFrame` so a frame is unwrapped once and serves both the preview copy and Vision,
rather than each fetching its own. Detection is wrapped in `runCatching`: it runs on
AVFoundation's delivery queue, where an escaping exception would stop *capture*, not merely
skip a frame.

**⚠ The default does not flip here.** `forCurrentOs()` still returns the Swift helper. The
reason has changed rather than gone away: P3 removes "it cannot decode", leaving the claim
this whole track rests on — *that the first grant no longer kills the host* — which is
precisely the claim no test can make (§13.4). That belongs to the on-device pass on a signed
build; `defaultsToTheSwiftHelperEvenOnMac` guards against the flip drifting in early.

**So P4/P5 are now gated on one human step**, not on more code: run
`-Dtenshu.qr.impl=ffm` on a signed build, scan a real code, and confirm the first grant
survives. If it does, D3's explainer and `NeedsRetry` come out (P4), the default flips, and
P5 deletes the Swift helper, its two Gradle tasks, both embed tasks and the framed-protocol
parsers — while `HelperBinary` stays, per §13.6's correction.
