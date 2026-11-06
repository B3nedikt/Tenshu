# Logging Facade — a level-gated `Log` seam in front of `:logging-service`

**Status:** Draft v0.2 (2026-06-11). **⚠ Superseded-in-part 2026-07-21 by a shipped device-local variant (§0); go-forward convergence plan in §0.1 (one `Diagnostics` façade, many trees).** Motivating consumer (the SyncScheduler
drain-failure spam) fixed in the same PR as an interim `println` collapse; the
facade itself is not built yet. **Phase −1 (retire `tenshu/core/logging`) done
2026-06-11** — there is now exactly one `LoggingService`
(`tenshu/logging/service`); see §8. Scope decision recorded in §2.5: the facade
is proxy-/platform-internal, app code goes through the `LoggingService` client
surface.

## 0. As-built (2026-07-21) — a device-local single-stream `LogItem`, not the synced two-stream facade

The motivating need turned out to be **on-device debugging of a background feature** (Timeline
capture dying on ColorOS): "what did capture do overnight, and can I read it without a cable?". That
reframed two of this proposal's decisions, and a `:diagnostics` module shipped that diverges from the
draft on purpose:

- **One stream, not two.** The draft split `DiagnosticLog` (freetext) from `ErrorEvent` (typed) so the
  *typed* stream could be uploaded anonymously **by construction**. But that split is only worth its
  complexity if you want **silent** crash telemetry. The product wants *user-reviewed* upload ("send
  my logs"), where the user sees the content before it leaves — so anonymity-by-construction buys
  nothing at upload time. The trust distinction (anonymous-safe vs review-first) is therefore a
  **per-item property** (`LogItem.code` present + no freetext ⇒ structured/anonymous) applied at a
  future *upload seam*, not a reason to physically separate streams. Result: a single `LogItem`
  (`level`, `tag`, `message`, `timestampMs`, optional `code`), where a crash is just an `ERROR` item
  with a code — the normal logging-API shape, and better for debugging (breadcrumbs sit next to the
  crash they preceded).
- **Device-local, not synced.** `ErrorEvent` is `Syncable(Eager)`. Diagnostics are high-volume,
  device-specific, PII-ish (coordinates/paths), and read either on the same device or by *upload* —
  and the developer who wants them has **no paired device**, so sync buys nothing for retrieval. So
  `:diagnostics` is a plain **bounded local file** (`FileDiagnosticSink`, a 500-item ring), zero
  schema/op/wire/format cost — the opposite corner from `ErrorEvent`, and the right one for this data
  shape. It sidesteps the whole "wire the logging-proxy into per-app processes" problem the synced
  path has (Timeline never wired it).

**Shipped:** `:diagnostics` (pure `LogItem`/`LogLevel`/`DiagnosticRing`/`DiagnosticSink` + a `Diag`
global façade; `FileDiagnosticSink` + `DiagnosticCrashHandler` in the shared jvm+android source set).
Timeline installs the sink + crash handler at process start (before Koin, so a startup crash is
caught), emits breadcrumbs at the capture transitions (FGS lifecycle, watchdog re-arm, dwell/move,
rail sweep outcome), and renders a **Settings → Diagnostics** section (expand → `Diagnostics.recent()`, with
copy/clear). Upload stays unbuilt — a future opt-in toggle (beta users) owns it, filtering to `code`-
bearing items for any silent path, everything for a reviewed "send my logs".

**`DiagnosticCrashHandler`** is the "app just crashed" capture that did not exist before (the error
channel only sees exceptions thrown *out of operations*, never a main-thread NPE / OOM / UI crash): a
`Thread.setDefaultUncaughtExceptionHandler` that synchronously appends an `ERROR` item then chains to
the previous handler. It **earned its keep on the first run** — it recorded an uncaught
`SecurityException` from the M9 resilience slice starting a *location* FGS from the background without
permission/eligibility (thrown in the service's `onCreate`, *after* `CaptureRearm`'s own try/catch, so
otherwise a silent field crash). Fixed in the same PR: `CaptureRearm` now gates on location
permission, and `TimelineCaptureForegroundService.onCreate` wraps `startForeground` and stops
gracefully on refusal rather than crashing. The two-stream synced facade below would **not** have
caught that — it needs a thrown *operation* error, and Timeline never wired it.

The rest of this proposal (the proxy-internal `Log` facade fanning into a synced `DiagnosticLog`)
remains a valid, *separate* track for **proxy-side** freetext breadcrumbs that should ride the sync
plane; it is not superseded, just no longer the path for *app-side, device-local* diagnostics.

## 0.1 Convergence plan — one façade, many trees (the go-forward)

§0 shipped a device-local `Diagnostics` façade over a single `DiagnosticSink`. The intended
end-state — reconciling it with the `Log`/`LogSink` facade below and with the shipped `ErrorEvent`
channel — is **Timber's model: one call surface, a *list* of sinks ("trees"), routed per item.** The
three things that read as "separate streams" (crashes, diagnostics, failed operations) are **not
separate APIs and not separate stores** — they are the *same* `LogItem` timeline, with each tree
choosing which items it handles and where they go. This section pins that shape so it isn't
re-litigated when the pieces are wired.

### The model

- **`Diagnostics` is the one façade** — call sites (`Diagnostics.i("capture", …)`) never know or care
  where an item lands. This *is* the `object Log` §4 proposes; the two names converge on `Diagnostics`
  (§0's naming rationale: `Log` collides with `android.util.Log` and is overloaded; `Diagnostics`
  matches the `:diagnostics` module + its `Diagnostic*` type family). `DiagnosticSink` is this
  proposal's `LogSink`.
- **A sink is a tree.** `Diagnostics.install(...)` takes a **list** of sinks. Each filters by the
  item's `level` / `tag` / `code` and decides what to do with it. "Crash stream", "diagnostics
  stream", "failed-operations stream" are *routing outcomes over one stream*, not three schemas:
  - **local file tree** (shipped `FileDiagnosticSink`) — takes everything, device-local, bounded.
  - **crash tree** (future) — takes `code == "uncaught_exception"`, persists it stickily for
    next-launch surfacing/upload (a crash can't be uploaded in the dying process).
  - **upload tree** (future, opt-in) — ships items to the Console; see the trust rule below.
- **Configured once in the `Application` class.** `TimelineApplication.onCreate` installs the tree
  list — exactly where `DiagnosticCrashHandler` + the file sink are wired today. Adding a tree is a
  one-line change there, no call-site churn.

### Failed operations: mirror, don't merge

Failed operations already have a home — the shipped, synced, anonymous `ErrorEvent` stream
(error-channel Phase 5). The convergence is **not** "move them into `LogItem`". It is: give
`LocalTaskExecutor`'s error recorder a **tree**, so a failed operation *also* drops an `ERROR`
`LogItem` into the local diagnostic timeline. Same event, two trees:

- into `ErrorEvent` (synced, aggregatable, anonymous-uploadable — unchanged), **and**
- into the local `LogItem` timeline, so when you read Diagnostics a failed op sits **inline next to
  the breadcrumbs that led to it** — which is exactly what a person debugging wants.

This is additive and touches no existing behaviour: the executor already calls
`errorEventRecorder.record(...)`; a second call to `Diagnostics.e(tag, message, code = errorTypeId)`
is the whole change. (Whether that lands in per-app processes depends on the executor running there —
today Timeline's in-process executor would, Control's would; the recorder-wiring gap noted in §0
applies equally.)

### Two things the convergence must not break

1. **The trust boundary is per-item, not per-stream.** The instant a tree *uploads*, only items that
   are anonymous *by content* may travel a **silent** path — `code`-bearing, free-text-free (§2.1's
   whole argument, A19). Free-text items upload only behind **explicit review** ("send my logs" over a
   visible list, A22/A23). A tree that forwarded *everything* silently would re-open the exact PII
   hole the original two-stream design spent its threat-model budget closing. Collapsing to one
   *stream* is safe **only because** the anonymous-vs-review decision moved onto the *item* (its
   `code`/free-text shape) and is enforced at the *upload tree*, not by having two schemas. Keep it
   there.
2. **Trees encode the sync-vs-local decision, not just "where".** A file tree and a synced tree want
   *different* items: high-volume device-local breadcrumbs must **not** sync (that is the
   `Workout.Streams`/bounded-store volume trap on the sync plane); low-volume aggregatable failed-ops
   *should*. So a tree's filter is also a volume/plane policy — the reason `LogItem` is device-local
   (§0) and `ErrorEvent` is synced is preserved by *which tree takes what*, not lost in a merge.

### Concrete steps (each independently shippable, none blocking)

1. **Generalise `Diagnostics` to a tree list** — `install(vararg sinks)` / an ordered `List<DiagnosticSink>`;
   the façade fans each call to every tree. Pure, unit-testable. (Small.)
2. **Failed-ops mirror tree** — the executor emits an `ERROR` `LogItem` alongside its `ErrorEvent`, so
   operation failures appear inline in the local timeline. (Additive; wire the recorder in per-app
   processes as part of it.)
3. **Crash tree** — split crash persistence out of `DiagnosticCrashHandler` into a sticky tree, so the
   next launch can surface / (opt-in) upload the last crash.
4. **Upload tree, behind a beta toggle** — the piece §6 / error-channel Phase 6 already built the
   device half of; needs the App Console server (unbuilt) + the per-item filter above. This is the
   only step gated on external infrastructure.
5. **Fold naming** — retire the parallel `Log`/`LogSink` names in this proposal in favour of the
   shipped `Diagnostics`/`DiagnosticSink`; the proxy-internal `DiagnosticLog` synced stream (§1.3,
   §3) remains a *separate, still-valid* track for **proxy-side** freetext that genuinely wants the
   sync plane — it is a tree too, just one living in `:logging-service`, not the app.

Net: nothing about §0's device-local choice is walked back; the convergence *generalises* it —
`Diagnostics` becomes the single `Log`, sinks become trees, the three "streams" become three trees
plus per-item routing, all configured in the `Application` class. The remaining draft (§1–§9) is the
detailed design for the sink implementations and the threat-model deltas those trees carry.


**Owner:** Benedikt
**Depends on / extends:**
[`docs/proposals/core-error-channel.md`](core-error-channel.md) sub-track L —
the `:logging-service` module set this proposal builds the general-logging
half of;
[ADR 0016](../adr/0016-localisation.md) anchor #4 (typed codes, not free-text,
cross the proxy ↔ client boundary — load-bearing for *why the two log streams
must stay separate*);
[`docs/sync/threat-model.md`](../sync/threat-model.md) House Rule #1 (the relay
sees ciphertext only) + assets A18/A19 (the error-event log + its anonymous
Console upload).
**Touches the threat model:** new assets A22 (synced freetext diagnostic log)
+ A23 (per-app diagnostic export payload). Amendment sketch in §7.

---

## 1. Motivation

### 1.1 The immediate problem

`:control` is spammy. A stuck outbound sync queue logs one line **per pending
block, per hot tick** — every 1.5 s for the whole outage:

```
SyncScheduler -> Failed to push block dcd3895f-…. Will retry later.
SyncScheduler -> Failed to push block 21b277b9-…. Will retry later.
SyncScheduler -> Failed to push block 1954f536-…. Will retry later.
…
```

Two faults compound: the log fires per-block-per-tick (N × ticks of identical
lines), and the push exception was *swallowed* by the drain's try/catch — so the
flood reported the symptom ("a block failed") and never the cause (a 401, a
timeout, offline). The same shape repeats at the tombstone drain.

The interim fix (shipped in this PR) collapses both drains to **transition-only**
logging via a small `DrainFailureLog`: one line when a queue first stalls — with
the captured cause — one line when it drains, silence in between. That stops the
bleeding. It does not address the underlying gap.

### 1.2 The underlying gap

There is **no logging facility** in the codebase. ~30 non-test sites call bare
`println` (`SyncScheduler`, `SyncProcessor`, `PairingWizardViewModel`,
`ProxyGateway`, the relay server, …). There is:

- **no level** — `debug` breadcrumbs and genuine `warn`s are indistinguishable;
- **no production guard** — and neither Android nor R8 strips this for us.
  `println` lands on `System.out`, which Android redirects to Logcat verbatim in
  *every* build type; R8's `-assumenosideeffects` only touches `android.util.Log`,
  and only if you opt in. The "don't log in prod by mistake" check is entirely
  on us to build, and today it has nowhere to live;
- **no capture** — a user reporting "Drive misbehaved" gives us nothing but a
  screenshot. The error-channel work (`:logging-service`) captures *typed errors*
  but not the freetext breadcrumb trail that explains how the app got there.

### 1.3 The ambition

`:logging-service` should become the place that collects **all** diagnostic
logs — from Control's own proxy *and* from every client app that talks to it
(Drive today, third-party apps later) — so a user can export the logs belonging
to a specific app and send them with a bug report. Control already mediates
every client over IPC, so it is the natural collection point.

The question this proposal answers: **should `:logging-service` be the facade
every call site talks to, or should a thin facade sit in front and treat the
service as one sink behind it?**

---

## 2. Decision: facade in front, service as a sink

**A thin `Log` facade in `:core`, with `:logging-service` as one pluggable sink
behind it.** Call sites depend only on the facade; the service is wired in as a
sink at the DI layer. Four reasons, in priority order.

### 2.1 The PII-by-construction collision (load-bearing)

`:logging-service` as shipped is **not** a general log store. It is a *typed
`TError` event* store whose entire rationale (error-channel §4.1, §5, threat
model A18/A19) is that it carries **no freetext** — no messages, no stack
traces, no paths, no ids — which is *why* its Console upload can be anonymous
**by construction** (error-channel §5 explicitly rejects regex scrubbing).

"Collect all the logs" means freetext breadcrumbs — `"Failed to push block
dcd3895f-…"` — and that block id is exactly the realm-identifying payload the
error-event design spent its whole threat-model budget keeping out. General logs
**cannot** pour into the `ErrorEvent` stream without voiding A18/A19. So the
service grows a *second, separate* stream — and the only thing that can fan one
call into "typed-error path here, freetext-diagnostic path there, console in
dev, nothing in release" is a facade. Make the service the facade and you have
no seam at which to keep the two apart.

### 2.2 Hot-path / IPC cost

Log calls fire on hot paths — the SyncScheduler tick is the proof. A general
`Log.debug` must **not** synchronously cross Binder/IPC into Control's
proxy-hosted service. The facade decouples call site from sink: buffer locally,
batch, drop-below-level, no-op in release. The service is a heavy sink (synced
realm data over IPC); binding every println-replacement straight to it would be
a performance regression dressed as a logging cleanup.

### 2.3 The production guard has a home only if there's a facade

The "don't log in production" check (§1.2) lives in the facade's installed sink:
the release build installs a sink with `minLevel = WARN` (or no console sink at
all), so `debug`/`info` are dropped *before* reaching stdout or the service. If
call sites talked to the service directly, the cost is already paid and the
decision is made in the wrong place. The guard is configured once, at app init,
where `isDebug`/`BuildConfig` is already in scope (e.g. `PlatformProxyModule`).

### 2.4 Dependency graph

If every module called the service directly, every module — including
`commonMain` in `:core` — would gain a dep on `:logging-service-client`. A facade
interface in `:core` keeps that dep at one point (the DI wiring that installs the
service sink). This is the same separation as House Rule #6 (`LazyComposite`
doesn't reach into `SchemaRegistry`).

### 2.5 Scope decision: facade is proxy-internal, apps go through the service (2026-06-11)

Maintainer decision, refining the §2 opening line. "Call sites depend only on
the facade" holds for **proxy-/platform-side** code — the SyncScheduler,
`:core-common` machinery, feature-service `-proxy` modules. It is *not* the
surface for app/client modules: at the end state there is exactly **one**
`LoggingService` (`tenshu/logging/service`) and clients talk to *it*, via the
`org.tenshu.logging.client.loggingService` extension — the same
`<service>-client` pattern as every other service. The `Log` facade stays
internal to the proxy machinery; it must not become a second client-facing
logging API.

Consequences:

- **Phase 1/2 sweeps target proxy-side call sites.** App-side "swallowed
  error" markers (Drive's `LogUnexpectedErrorUseCase`, see §8 Phase −1) are
  *not* migrated to `Log.*`; if app-side breadcrumbs turn out to be needed,
  the `DiagnosticLog` stream grows a client-callable write op on
  `LoggingService` instead (Phase 3 design item).
- The dependency-graph argument (§2.4) is unchanged — it just applies to a
  smaller set of modules.

The facade fans into **two** distinct streams inside `:logging-service`. They are
different assets with different consent surfaces; keeping them separate is the
whole point of §2.1.

| | `ErrorEvent` (today, error-channel §4.1) | `DiagnosticLog` (new, this proposal) |
|---|---|---|
| Content | typed `TError` code + non-PII metadata | freetext message + level + tag |
| Ingress | **automatic** at the consolidated catch-all (`LocalTaskExecutor`) | **voluntary** breadcrumbs via the `Log` facade |
| PII | none, by construction | **may** carry payload — treated as sensitive |
| Keying | realm-scoped | realm-scoped **+ `appId`** (which app emitted it) |
| Export | **anonymous** one-shot upload to the App Console | **per-app, user-reviewed** export attached to a bug report |
| Synced | yes (`Syncable`) | yes, but see §7 — the *export* is the sensitive boundary, not the sync |

The two ingresses are complementary, not competing: typed-error capture answers
*what failed*, the diagnostic breadcrumbs answer *how it got there*. A bug report
ideally carries both — but the diagnostic stream is freetext and therefore
travels under the stricter, content-reviewed consent of §7, never the anonymous
Console pipe.

---

## 4. The facade

### 4.1 Surface

A global object in **`:core`** (the lowest common dependency — everything depends
on it, including top-level utility functions that can't receive an injected
logger):

```kotlin
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun debug(tag: String, message: () -> String)
    fun info(tag: String, message: () -> String)
    fun warn(tag: String, message: () -> String, cause: Throwable? = null)
    fun error(tag: String, message: () -> String, cause: Throwable? = null)

    /** Installed once at app init; replaces the default. Idempotent-safe. */
    fun install(sink: LogSink)
}

interface LogSink {
    val minLevel: Log.Level
    fun emit(level: Log.Level, tag: String, message: String, cause: Throwable?)
}
```

- **`message` is a lambda** so a dropped-by-level call never builds the string —
  the hot-path concern from §2.2. `Log.debug` below `minLevel` is a single enum
  compare.
- **`tag`** is a short component label (`"SyncScheduler"`) used for filtering and,
  later, for routing diagnostic rows. Not the `appId` — that's attached by the
  service sink (§6), which knows which app's facade it is.
- The **default sink** (before `install`) is a no-op `minLevel = WARN` writer, so
  a misconfigured wiring fails *quiet*, not spammy.

### 4.2 Why a global object, not an injected `Logger`

Logging must be callable from anywhere — top-level functions, `init` blocks,
deep `commonMain` utilities that have no DI graph. Threading a `Logger` through
every signature is the noise this proposal exists to remove. This is the
Napier/Kermit pattern: a global facade with installable sinks, configured once.
The cost is one piece of init-time mutable global state; the mitigation is that
`install` is called exactly once per process at app start (asserted in the init
wiring). Where a class *already* has DI (like `SyncScheduler`), it may still take
an injected sink for testability — the global is the floor, not a mandate.

### 4.3 Sinks

- **`ConsoleLogSink`** (in `:core`) — writes to stdout/Logcat. Installed in
  **debug** builds with `minLevel = DEBUG`.
- **Release default** — `ConsoleLogSink(minLevel = WARN)` or none, decided in the
  platform init module where `isDebug` is in scope. This *is* the production
  guard (§2.3).
- **`LoggingServiceSink`** (in `:logging-service-client`, Phase 3) — forwards
  records to Control's proxy-hosted store over IPC. Composes with the console
  sink via a trivial `CompositeLogSink`.

Dependency direction stays correct: `:logging-service-client` already depends on
`:core`, so it can implement `LogSink`; `:core` never learns about the service.

---

## 5. The production guard, concretely

The thing asked for in the originating conversation — "checks to not log in
production by mistake":

1. **Level gate at the sink.** Release installs `minLevel = WARN`; `debug`/`info`
   are compiled-in but never emitted. Zero string construction (§4.1).
2. **Lint backstop (deferred).** A Detekt rule banning raw `println` /
   `print` / `System.out` outside `:core`'s `ConsoleLogSink` and the relay
   server's main. Rides the same deferred-Detekt slot as the i18n
   `stringResource` rule (CLAUDE.md i18n row). Convention now, enforcement when
   the Detekt module lands.
3. **No `printStackTrace()`.** The remaining `e.printStackTrace()` sites
   (`SyncScheduler.start`'s tick catch, ProxyGateway history) become
   `Log.error(tag, …, cause = e)` so the sink — not the call site — decides
   whether a stack trace reaches stdout.

---

## 6. Cross-app collection (the "send Drive's logs" ambition)

This is the established client/proxy split — `:logging-service-client` ↔
`:logging-service-proxy`, symmetric with `:notification-service` /
`:preference-service`:

```
call site → Log facade (:core, tiny, level-gated)
               │  sink installed at init
               ├── ConsoleLogSink      → stdout/Logcat (dev; WARN+ or none in release)
               └── LoggingServiceSink   → :logging-service-client
                        │  IPC  (Drive, third-party apps → Control)
                        ▼
                  :logging-service-proxy   ← Control hosts the store
                    ├── ErrorEvent stream   (typed, PII-free, anonymous Console upload)
                    └── DiagnosticLog stream (freetext, per-appId, user-reviewed export)
```

Because client apps already talk to Control over IPC, the `LoggingServiceSink`
*is* how "Control collects all the logs, from itself and from client apps." The
proxy stamps each inbound record with the calling `appId` (it already
authenticates the IPC peer), so the diagnostic stream is keyed by app. The export
UI (Phase 4) lists logs grouped by app — "Send Drive's logs", "Send Control's
logs" — and attaches the selected app's recent rows to a bug report.

`DiagnosticLog` reuses the error-event retention bounds (error-channel §4.4): a
500-event ring buffer + 30-day eviction, per app, so a chatty app can't blow the
realm.

---

## 7. Threat model amendment sketch

The diagnostic stream is freetext that **can** carry realm payload and **can**
leave the device — exactly the risk error-channel §5 designed *away* for the
typed stream. It therefore needs its own assets and a stricter consent surface.

- **Asset A22 — synced diagnostic log.** Realm-scoped `DiagnosticLog` rows
  (level + tag + freetext message + `appId`). Subject to House Rule #1 like any
  realm data: the sync relay sees ciphertext only. Distinct from A18 (the
  PII-free typed event log) precisely because it is *not* PII-free.
- **Asset A23 — per-app diagnostic export payload.** The bytes a user exports
  with a bug report. **Not anonymous** (unlike A19's Console upload): freetext
  can't be made anonymous by construction, so the consent model changes — see
  T-L-04.
- **Threat T-L-04 (user exports payload they didn't review).** Mitigation: the
  export is **content-reviewed** — the UI shows the actual rows being sent, and
  the consent string is "Send these logs" over a visible list, *not* an "include
  anonymous diagnostics" toggle. No auto-send, no background upload. Diverges
  deliberately from A19's anonymous one-shot: A19 can be silent because it's
  PII-free; A23 cannot, so it is never silent.
- **Threat T-L-05 (a `Log.debug` leaks a secret into the synced stream).**
  Mitigation: authoring convention — never log key material / tokens / full
  payloads; tags + identifiers + counts, not bodies. Release drops `debug`/`info`
  (§5) so the lowest-discipline logs never persist in production builds at all.
- **Threat T-L-06 (cross-app log read).** A client app's facade can only emit;
  the *read*/export surface is Control's, gated by the same peer-cred boundary as
  the rest of the IPC service. Drive cannot read Control's (or another app's)
  diagnostic rows.

---

## 8. Migration plan

**Phase −1 — Retire `tenshu/core/logging`** [DONE, 2026-06-11]. The legacy
core `LoggingService` + `LogOperation` (`:core`), their RO/wire registrations
(`:core-common` `CoreCommonModule`), the proxy-side `LogImplementation` —
which was a **no-op** that discarded the error — and the pattern-breaking
`TenshuClient.loggingService` member (plus the never-read
`TenshuClient.isConnected`) are deleted. There is now exactly one
`LoggingService`: `tenshu/logging/service`. Drive's
`LogUnexpectedErrorUseCase` is kept as a no-op seam — its ~10 call sites in
`TenshuFilesRepository` mark "swallowed error" spots, but the proxy-side
error-channel recorder already captures every typed error that escapes an
operation, so client-side re-logging would only duplicate events. Whether
those markers ever feed the `DiagnosticLog` stream is a Phase 3 design item
(per §2.5 they would go through a client-callable op on `LoggingService`,
not the facade).

**Phase 0 — Spam fix** [DONE, this PR]. `DrainFailureLog` collapses the
SyncScheduler drains to transition-only logging and surfaces the swallowed cause.
Still bare `println` — the motivating consumer the facade adopts in Phase 1.

**Phase 1 — `Log` facade + `LogSink` SPI** [SMALL]. `Log` + `LogSink` +
`ConsoleLogSink` in `:core`; `install` wired at app init in the platform modules
with the release level-guard. Migrate the SyncScheduler `println`s (Phase 0's
sites) to `Log.warn(...)`. No service dependency yet — this phase ships the
production guard on its own.

**Phase 2 — Sweep the bare `println` sites** [SMALL]. Mechanical: the ~30
non-test `println` / `printStackTrace` sites move to `Log.*`. Relay server
(`:sync-relay-server`) is a JVM main — it may keep its own console logging or
adopt the same facade; decide per §9.

**Phase 3 — `DiagnosticLog` stream + service sink** [MEDIUM]. Add the freetext
stream to `:logging-service` (`-common` schema, `-proxy` store + ring buffer +
`appId` stamping, `-client` `LoggingServiceSink`). Compose it with the console
sink at init. After this, Control collects logs from itself and from Drive.

**Phase 4 — Per-app export UI** [MEDIUM, gated on the diagnostics screen]. The
"Send <app>'s logs" surface in `:control-shared` Settings → Diagnostics, content
review per T-L-04, threat-model A22/A23 amendment landed in the same PR.

Phases 0–2 are independent of `:logging-service` entirely; Phase 1 alone closes
the production-logging gap. Phases 3–4 are the cross-app ambition and carry the
threat-model work.

---

## 9. Open questions

- **Facade home — `:core` vs `:core-common`.** `:core` is the lowest common dep
  (everything, including pure top-level functions, can reach it) and the facade
  is pure (no IPC), so `:core` is the lean. `:core-common` would also work and
  keeps `:core` schema-only — but forces transport-adjacent code to be the floor
  for a logging call, which it isn't. Lean: `:core`.
- **Does the relay server share the facade?** `:sync-relay-server` is a separate
  JVM deployable with no `:core` UI concerns; it may want structured server
  logging (SLF4J/Logback) rather than this facade. Lean: leave the relay on its
  own logging; the facade is for the KMP client/proxy surface.
- **`tag` typing.** Freetext `String` tag vs a typed component enum. Freetext is
  simpler and matches Logcat conventions; a typed tag would let the diagnostics
  UI group reliably. Lean: freetext v1, revisit if the UI needs stable grouping.
- **Structured fields on `DiagnosticLog`.** Should a row carry optional typed
  key/value pairs (op id, count) alongside the freetext, to make the stream
  partly queryable? Deferred — freetext message v1.
- **Sampling / rate-limit.** A tight loop emitting `Log.warn` could churn the
  ring buffer. The 500-row cap bounds storage; per-tag rate limiting (à la
  error-channel §8) is a Phase 3 review item.
- **Global mutable sink.** `Log.install` is process-global mutable state (§4.2).
  Accepted as the cost of "callable from anywhere", asserted single-call at init.
  Flag if a second runtime in one process (the JS `JsRuntime`) needs isolation.
