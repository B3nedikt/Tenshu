# Drive Upload — Where the Source Read Happens

**Status:** v0.1 — design settled, Ph1a in progress.
**Prompted by:** a Keynote package crashing Drive-on-iOS (`docs/tech-debt.md`, "An iWork document
cannot be uploaded"), which exposed a layering problem the crash only made visible.

---

## 1. The problem is not the crash

A `.key` is a *directory*, so `NSData.dataWithContentsOfFile` returned nil and the iOS delegate
threw. But the reason that **aborted the app** rather than showing an error is structural, and it
outlives the package question:

`IosFilesRepositoryPlatformDelegate.readFileBytes(uriString)` is evaluated to produce the `data =`
argument of the upload op. It therefore runs **before the op is dispatched** — outside
`LocalTaskExecutor`, whose `catch (e: Throwable) / if (e is CancellationException) throw e`
(`LocalTaskExecutor.kt:34-36`) is the platform's own conversion of a throw into a
`TResult.Failure`. There is no `TResult` yet for it to fail into.

⭐ **Android is immune, and not by luck.** Its read happens *inside*
`AndroidFileUploadImplementation`, so the platform catch covers it for free. The asymmetry is the
defect; the crash was the symptom.

The P0 stopgap (`TenshuFilesRepository.guardingSourceRead`) re-implements the platform's
catch in app code. It stops the abort and should not be mistaken for the design.

## 2. Decisions

- **D1 — One op, not per-platform twins.** `AndroidFileUpload`'s schema is *already* platform
  neutral: `parentPath`, `fileName`, `uri: TString`, `sourceModifiedAt: Int?`. Nothing in it is
  Android. What differs per platform is only how `uri` is resolved to bytes — a descriptor from the
  `ParcelFileDescriptorCache` (Android), a `phasset://` or `file://` source (iOS), a filesystem path
  (desktop). ⚠ That is what per-platform `OperationImplementation` is for, so there is no
  `IosFileUpload` and no `MacFileUpload`.
- **D2 — Follow the `GetPreview` precedent exactly.** Schema in `file-service/commonMain`;
  each platform overlays its implementation through its own factory
  (`iosFileServiceProxyImplementationFactory` is consulted *before* the common one, and already
  exists for `GetPreview`). No new mechanism is invented here.
- **D3 — Rename rather than ship a lying name.** The op moves to a neutral id. ⚠ The `Params` keys
  are derived (`"${ID}/path"`), so the id change moves every param key with it — see §4.
- **D4 — Desktop's op parameter is NOT decided yet**, because it depends on an unanswered
  platform question (§5). iOS does not wait for it.
- **D5 — Package flattening is a separate track** and stays in the *picker*, not the op. On iOS
  `stageForUpload` must copy while the security scope is live (`FilesView.swift`), which is before
  any op runs, so the op will always receive an already-flat source. Tracked in `tech-debt.md`.

## 3. Target shape

```
UploadSourceFile(parentPath, fileName, uri, sourceModifiedAt?)   ← one schema, commonMain
  ├── Android impl   resolve fd from ParcelFileDescriptorCache   (exists, renamed)
  ├── iOS impl       resolve phasset:// | file://                (Ph1b)
  └── Desktop impl   resolve a path                              (Ph2, gated)
```

`UploadFile(data: ByteArray)` stays — it is the right op when the caller genuinely *has* bytes.
What goes away is clients reading a file only to hand the bytes straight back.

## 4. Phases

### Ph1a — generalise the op (pure refactor, no behaviour change)

Move the four ops from `androidMain` to `commonMain`, renamed neutrally:
`AndroidFileUpload` → `UploadSourceFile`, `AndroidFileUploadBg` → `UploadSourceFileBg`, and the two
`…Replace…` twins. Schemas, client ROs and the registration module all move; the Android
*implementations* keep their platform names, because they genuinely are Android-specific.

⚠ **Owes a `file-service` version bump + a regenerated `SchemaShapeBaseline`, but NO store
migration.** The baseline fingerprints `(field id, base type id, isOptional)`, so renamed param
keys trip it — and `SchemaShapeBaseline`'s own header settles the rest: *"Op parameter objects are
client-serialized composites too … reshaping them breaks old callers mid-parse and owes a bump and
lockstep, but owes no store migration because nothing persisted them."*

### Ph1b — the iOS implementation *(as-built)*

`IosUploadSourceFileImplementation` / `IosReplaceSourceFileImplementation` sit behind
`iosFileServiceProxyImplementationFactory`, and `IosFilesRepositoryPlatformDelegate` no longer
reads bytes — it passes the source reference. iOS reads inside the op from here on, so
`LocalTaskExecutor` owns the failure; `guardingSourceRead` stops covering anything on iOS, though
it stays for desktop until Ph2.

**The seam.** `IosUploadSourceReader` is declared in `:file-service-proxy` and implemented by
`IosUploadSource` in `:tenshu-sdk-ios`, because PhotoKit knowledge lives in the SDK and the
dependency runs *that* way (`:tenshu-sdk-ios` → `:control-shared-proxy` → `api(:file-service-proxy)`).
Injecting also means one reader serves both callers — the op, and the client-side `contentHashOf`
duplicate check, which still hashes bytes before an upload it may avoid. Bound in
`FirstPartyLocalRuntime.assembleBase`, not Drive's overrides: any first-party runtime that serves
an upload op needs it.

⚠ **Absence is loud.** The factory `error(...)`s if an upload op arrives with no reader wired,
rather than returning null and letting the id fall through to the common factory — which would
surface as a generic "no implementation" three layers from the cause.

⚠ **iOS needs no registration of its own.** Ph1b briefly added a `platformSchemaConfigs` binding
in `control-shared-proxy/iosMain`, back when registration was per-platform; once §5 Q2 closed and
the ops moved into `fileServiceCommonModule`, that binding became redundant and was removed. iOS
supplies an *implementation*, not a registration.

⭐ **The error message changed on purpose.** `NSData.dataWithContentsOfFile` returns nil for a
directory exactly as for a missing file, and the old `"File not found: $path"` for a path that
plainly exists is what sent the original investigation into disassembling the installed binary.
`IosUploadSourceTest` pins that: reverting the message to the old wording turns exactly that test
red and leaves the other three green.

### Ph2 — desktop, gated on an experiment, not on a design

⚠⚠ **Do not implement this phase before running the probe below, and do not assume it ends in
code.**

The probe: from a **packaged, signed** Control build (not `./gradlew run` — the packaged path
behaves differently and signing has stripped entitlements here before), attempt to open paths in
`~/Documents`, `~/Downloads` and iCloud Drive that Drive picked. Three outcomes:

| Outcome | Action |
|---|---|
| Opens silently | Implement Ph2 with a path parameter. Done. |
| Prompts, naming Control | It works, but the prompt is attributed to the wrong app while the user is in Drive. Product call, not a technical one. |
| Denied | ⚠⚠ **Stop. Do not build fd passing.** See below. |

Background: both desktop JVM processes are **deliberately unsandboxed** — `control.entitlements`
says so outright (*"the JVM daemon needs broad host access"*) and the plist `signDesktopApp`
signs Drive with has no `app-sandbox` key either — so this is a **TCC** question, not a sandbox one. TCC is per-binary:
Drive's `NSOpenPanel` selection satisfies TCC for *Drive's* binary, and Control is a different
subject. The sandboxed peer in the tree is the File Provider `.appex`, which is not on this path.

⚠⚠ **Why "denied" must not become an fd-passing project.** It is technically reachable —
`macos-helpers` already has a working FFM `Linker` and `PeerAuditToken` already extracts the raw fd
from a `SocketChannel` via `--add-opens java.base/sun.nio.ch` — but `SCM_RIGHTS` over FFM lands in
`desktopMain`, which the milestones.md JVM-less fallback (*"make macOS a second Apple-native target
alongside iOS"*) replaces with `macosMain` wholesale. A native in-process app is **its own
responsible TCC subject**, so the cross-process question dissolves there exactly as the camera-TCC
SIGKILL does (`desktop-qr-scanning.md` §9; milestones.md:267). Leaving desktop on `UploadFile(data)`
with P0's guard is the cheaper answer and the right one.

⚠ That native move is itself gated behind two cheap probes and milestones.md says outright that if
both come back favourable the re-platform is not justified — so this is a reason **not to
over-invest in desktop now**, never a reason to plan around a native macOS app arriving.

## 5. Open questions

- **Q1** — the Ph2 probe outcome (§4). Blocks desktop only.
- ~~**Q2** — announce surface~~ **CLOSED 2026-08-28.** The four ops are registered by
  `fileServiceCommonModule` / `fileServiceClientModule`; the standalone `sourceFile*` modules are
  gone. Every platform announces them whether or not it implements one — harmless, since a platform
  with no implementation never calls them, and it is what Ph1b/Ph2 fill in.

  This closes two silent problems that per-platform registration left open: the ops were outside
  `SchemaShapeBaselineEnforcementTest`'s graph (so the Ph1a rename came back **green** from the test
  that exists to object to it), and the registry attributed them to **no service**, which would have
  made the next reshape of an upload param demand a store-wiping `TENSHU_STORAGE_FORMAT_VERSION`
  bump. They now pin as `tenshu/file/service`.

  ⚠ **The history is worth keeping, because the conclusion was wrong for a day.** Folding these in
  reddened the full gate 3/3 against 0/4 on two control trees, and the honest reading at the time
  was "gated on explaining the race". The race turned out to be an unrelated latent test bug this
  change merely perturbed into firing: `RelayAmnesiaRepairSystemTest` asserted its precondition on a
  stamp one tick earlier than the one the guard reads, because `SyncObserver.emit` mints the
  tombstone's HLC itself (`tech-debt.md`). ⭐ The generalisable lesson: *"my change reddened
  an unrelated test"* is often **exposure**, not causation — and the way to tell them apart is a
  single-variable A/B under the same load, never isolated reruns.

- **Q3** — whether `UploadFile(data)` should eventually be reserved for genuinely in-memory
  callers, or kept as the universal fallback. Not urgent; nothing forces it now.

## 6. Naming history

Ph1a renamed the ops in place. Older docs and commit messages use the previous names, and these are
the pairs to grep for:

| Before | After |
|---|---|
| `AndroidFileUpload` / `AndroidFileUploadBg` | `UploadSourceFile` / `UploadSourceFileBg` |
| `AndroidFileReplace` / `AndroidFileReplaceBg` | `ReplaceSourceFile` / `ReplaceSourceFileBg` |
| `uploadAndroidFile(Background)` | `uploadSourceFile(Background)` |
| `replaceAndroidFile(Background)` | `replaceSourceFile(Background)` |
| `FileService.androidCommonModule()` / `.androidClientModule` | `.sourceFileCommonModule()` / `.sourceFileClientModule` |
| id `tenshu/file/system/android/file{Upload,Replace}` | `tenshu/file/system/sourceFile{Upload,Replace}` |

⚠ The **implementations** keep their platform names on purpose — `AndroidFileUploadImplementation`
and `AndroidFileReplaceImplementation` genuinely are Android-specific (they resolve a descriptor
from the `ParcelFileDescriptorCache`), and that is the one thing about this op that *should* say
Android.
