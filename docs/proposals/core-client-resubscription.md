# Client-side resubscription after a proxy restart

- **Status:** **R1 + R2 + R1b + R3 + R4 + R5 + R6 SHIPPED** (Android hook +
  `DefaultInteractorProvider.resubscribeAll()` with retained start tasks; desktop reconnect loop;
  **R1b** = `changes()` *and* `flow()` retry a *failed* subscribe, §4.1; **R3** = the replay buffer,
  §4.2; **R4** = the staleness signal, §4.3; **R5** = convergence onto that signal, §4.4; **R6** =
  R3's residual bound, §4.5; **R9** = a too-large Binder transaction is not a dead connection, and an oversize stream message travels in `SharedMemory`, §4.8; **R10** = concurrent subscribes no longer lose a stream's registration, §4.9). **Phase map R1–R6 COMPLETE** —
  see the §4 phase table, which is canonical. The §5 `am force-stop` reproduction **was run
  2026-08-03 and R1 passed it** — which is what proved the remaining symptom was R1b's separate
  path, not a broken R1. Found on-device 2026-07-22.
- **Date:** 2026-07-22
- **Owner:** Benedikt
- **Related:** [`drive-connectivity-service.md`](drive-connectivity-service.md) (the banner whose
  wrongness surfaced this) · [`protocol/desktop-ipc.md`](../protocol/desktop-ipc.md) ·
  [`protocol/ios-ipc.md`](../protocol/ios-ipc.md) · `core/core-agent.md` §streams ·
  [`timeline-movement-capture.md`](timeline-movement-capture.md) §11 (why Control dies so often —
  ColorOS force-kills foreground services routinely)

---

## 1. The bug

**Every reactive read a client holds over IPC silently dies when the proxy process restarts, and
never recovers until the *client* is restarted.**

The transport self-heals. The subscriptions do not. The client keeps rendering the last values it
received, indefinitely, with no error and no staleness signal.

### 1.1 How it was found

Chasing a Drive banner that read *"Local only — files on this device aren't backed up or synced"*.
Three real bugs were fixed first (see [`drive-connectivity-service.md`](drive-connectivity-service.md));
the banner still came back. Then this evidence, captured live on a ColorOS device:

| | value |
|---|---|
| Control's `StorageStatus` row | `hasStorage = true`, 23.7 MB / 5 GiB |
| Control's `SyncHealthStatus` row | `connectivity = online`, not locked |
| Control's `Entitlement` | present, valid |
| **Drive's banner** | **"Local only — Set up storage"** |
| Control uptime | 10 min (recently restarted) |
| Drive uptime | 71 min |

Control's state was entirely correct. Restarting **only Drive** — leaving Control's process
untouched, same PID, still alive — cleared the banner instantly.

So: not an entitlement bug, not a lock bug, not banner logic. Drive was rendering values from
before Control's restart and had stopped receiving updates.

### 1.2 Why it looks random

The trigger is "the proxy died while the client stayed alive". On a desktop that is rare. On this
ColorOS phone Control is force-killed routinely
([`timeline-movement-capture.md`](timeline-movement-capture.md) §11.4), so it happens
constantly — and each occurrence silently freezes *every* reactive read in Drive until the user
happens to swipe Drive away. That intermittency is what made three previous fixes look like they
had not landed.

### 1.3 Scope — this is not a banner bug

The banner is one consumer. The same freeze applies to every reactive read Drive holds: file
listings, storage figures, sync health, sync status, shared-realm membership. A stale file list
after a Control kill is this same bug wearing different clothes. It is plausible that some
previously-reported "stale listing" and "sync issue" symptoms share this root cause.

---

## 2. Mechanism

Three facts, each verified in the source:

**(a) The client holds a live registry of streams.** `DefaultInteractorProvider`
(`:core-common`) keeps `interactors: MutableMap<Long, StreamDispatcher<*>>`, keyed by `streamId`.
Inbound elements route through `dispatchElement(element)` → `interactors[element.streamId]`.

**(b) The proxy's registry dies with its process.** `StreamRegistry` (`:core-common`, proxy side)
is in-memory and per-session. When Control's process is killed, every registration is gone —
nothing to clean up, nothing to restore. Only the client knows what it still wanted.

⚠ Note this is specifically *proxy process death*, not a general "teardown on disconnect" policy.
The **reverse** direction behaves differently and is worth stating so it is not conflated: when a
*client* dies while the proxy lives, `AndroidClientConnection`'s death recipient clears
`clientService` and **queues** outbound callbacks (`messageQueue`) while retrying the bind — the
session and its `StreamRegistry` are deliberately *not* torn down. The stale session is reaped
**lazily**, on the next `ClientRegistry.getExecutionContext(clientId, sessionIdentity)` whose
identity differs (*"Identity changed (e.g. new PID on Android), unregister old client"*). That
bounds the leak to one stale session per client rather than unbounded growth, but between the
client's death and its relaunch the proxy holds live listeners and queues callbacks for a process
that no longer exists.

**(c) Nothing re-registers.** `grep -rn "resubscribe|onReconnect" core-client core-common core-proxy`
returns **nothing**. On Android, `BinderTaskTransport.onServiceDisconnected` sets `proxyService =
null`, `isConnected = false`, resets `connectionSignal` — and notifies no one. The framework
re-fires `onServiceConnected` when Control comes back (the binding survives), so the transport
recovers and *new* calls work. But the client's existing `StreamInteractorImpl`s are still sitting
in `interactors`, and their start callstacks were never re-sent. They are subscribed to a
`StreamRegistry` that no longer exists.

Result: a live channel with zero live subscriptions.

### 2.1 Is connection loss ever *not* proxy process death?

Mostly no — but there is one materially different path, and it is worse.

`onServiceDisconnected` is documented as firing when the hosting process has crashed or been
killed, so on Android "lost connection" ≈ "the proxy died". The client surviving its proxy is
exactly the observed bug.

**But `onBindingDied` is a separate callback, and it is unhandled — `grep -rn "onBindingDied|onNullBinding"`
returns nothing anywhere in the repo.** It fires when the binding itself is dead rather than merely
disconnected, most notably when the target app is **updated or reinstalled**. After it, the
framework does **not** re-deliver `onServiceConnected` on that binding: the client must
`unbindService` + `bindService` again to recover.

So a Control *update* can leave a running client permanently disconnected — not just
unsubscribed. That is a strictly worse failure than the one this note is about, it is trivially
reproducible (`installDebug`, or any Play Store update while Drive is open), and it must be fixed
in **R1** or the resubscribe hook will simply never fire in that case.

### 2.2 The property that makes the fix cheap

`streamId` is **deterministic**:

```kotlin
// LocalRequestChain
override fun createStreamId(): RequestChain = copy(streamId = getCallstack().hashCode().toLong())
```

Same request chain → same id. So re-sending a retained start callstack to the *new* proxy produces
the *same* `streamId`, which means the client's existing `StreamInteractorImpl` — already
registered under that id, still holding all its callbacks — receives the new elements with **no
change to any consumer**. Flows stay alive; ViewModels never learn a reconnect happened.

That is the whole reason this is a contained fix rather than a rewrite.

---

## 3. Design

### 3.1 Shape

1. **Retain the start task per stream.** `DefaultInteractorProvider.buildStreamInteractor` already
   builds `startTask`; today only `endTask` is captured (in the cleanup closure). Retain the start
   callstack alongside the interactor so it can be replayed.
2. **Add `resubscribeAll()` to `DefaultInteractorProvider`.** For every registered, non-closed
   interactor: re-send its start task and re-seed the result. Idempotent, and a no-op when the map
   is empty.
3. **Add a connection-state signal to `TaskTransport`.** It is the one seam every platform shares
   (Android Binder, desktop UDS, iOS EPC), and it currently exposes `isConnected` / `bind()` /
   `unbind()` / `awaitConnected()` / `onCallback` but **no change notification**. Proposed:
   ```kotlin
   /** Fired after the transport re-establishes a connection to a *restarted* proxy. */
   var onReconnected: (() -> Unit)?
   ```
   Default `null` keeps every existing transport and test double compiling.
4. **Wire it.** Android: fire from `onServiceConnected` when it follows a disconnect (not on the
   first connect). Desktop/iOS: the equivalent point in their reconnect paths. Web / in-process:
   not applicable — no separate proxy process, so the hook is never fired.

### 3.2 Platform exposure — who actually has this bug

| platform | proxy | exposed? | notes |
|---|---|---|---|
| **Android** | Control, separate process, Binder | **yes — this is the reported bug** | plus the unhandled `onBindingDied` path (§2.1) |
| **Desktop** | Control, separate process, UDS | **yes, and worse** | see below |
| **iOS first-party** (Drive/Fit/Timeline, route-1) | **in-process** | **no — structurally immune** | see below |
| **iOS third-party / remote proxy** (EPC) | separate process | **yes, latent** | no first-party app uses it post-migration |
| **Web** | in-process `JsRuntime` | no | same reasoning as iOS route-1 |

**iOS first-party is immune, not merely unaffected.** Route-1 apps assemble
`FirstPartyLocalRuntime`, which builds an in-process graph over `LocalClientConnection` and no
`TaskTransport` at all. The proxy *is* the app: there is no connection to lose, and if the proxy
dies the client died with it, so everything is rebuilt on next launch. This is a real architectural
benefit of the route-1 migration, worth recording — the same class of bug simply cannot occur
there. EPC (route-2) is the remaining iOS IPC transport; it implements `TaskTransport`, has no
reconnect handling, and would need the same treatment if a first-party app ever rides it again.

**Desktop needs a reconnect loop.** `UnixSocketTaskTransport.bind()` connects once and the reader
thread exits on the first failure; there is **no background retry** and no equivalent of Binder's
automatic re-delivery.

⚠ Correction to the first draft, found while implementing R2: it is not true that *nothing* retries.
`call()` and `callCancellable()` both `bind()` on demand when disconnected, so a client that keeps
**calling** limps along. The gap is narrower but, for this bug, total: a client that only
**observes** — every reactive screen, including the sync-status banner that started this
investigation — issues no calls at all, so nothing ever prompts the re-bind. It stays dark
indefinitely. (This resolves the former Q5.)

### 3.3 The initial-snapshot problem (the hard part)

`StreamInteractorImpl.setInitialResult` is deliberately one-shot:

```kotlin
if (isClosed || initialResult != null) return@withLock …
```

But the fresh snapshot after a reconnect is exactly what carries the state the client missed while
disconnected. Re-registering without delivering it leaves the client correct only from the *next*
change onwards — which for an idle row (the storage status!) may be never. So the reconnect path
must be able to re-seed.

This needs a decision (**Q1** below): either a `reseedInitialResult` that overwrites and re-notifies
callbacks, or treating the reconnect snapshot as an ordinary element dispatch.

Related wrinkle: `StreamInteractorImpl` accumulates `elements` and replays them to
newly-registered callbacks. After a reconnect those buffered elements describe the *old* proxy's
history. For single-row relations (storage, health) that is harmless-to-wrong; for append-style
streams it risks duplicates. **Q2**.

### 3.4 What this deliberately does not do

- **No proxy-side change.** The teardown-on-disconnect behaviour is correct and stays — the proxy
  cannot distinguish an OEM kill from a voluntary quit, so only the client knows what it still
  wants. That asymmetry is the whole reason the fix belongs on the client side.
- **No change to consumers.** No ViewModel, repository, or Flow re-collection. If a consumer needs
  to know, that is a separate feature.
- **No client-side cache/replay of missed mutations.** The snapshot on resubscribe is the recovery
  mechanism; we are restoring a subscription, not building offline reconciliation.

---

## 4. Phases

| phase | content | ships value alone |
|---|---|---|
| ~~**R1**~~ **SHIPPED** | `TaskTransport.setOnReconnected` + Android wiring + `resubscribeAll()` + retained start tasks + **handle `onBindingDied` (unbind → rebind)**, without which the hook never fires after a Control update (§2.1). Resolve Q1 minimally. | ✅ fixes the reported bug on Android |
| ~~**R2**~~ **SHIPPED** | Desktop background reconnect loop (250 ms → 5 s backoff, single daemon thread) + the hook; deliberate `unbind()` is never fought. Also closed a latent race the reconnect work exposed: `call()`/`callCancellable()` both bind on demand, so two concurrent calls after a drop could each open a channel and spawn a reader — now behind a `connectLock`. Reader threads are bound to *their* channel rather than the shared field a reconnect may have swapped. iOS EPC deferred: no first-party app rides route-2 post-migration. | ✅ |
| ~~**R1b**~~ **SHIPPED** | Retry a *failed* subscribe — the half R1 structurally cannot reach (§4.1). `changes()` first, then `flow()` once its termination contract had been audited. | ✅ fixes a listing that stays empty after the session is re-admitted |
| ~~**R3**~~ **SHIPPED** | Q2 element-buffer semantics — the replay buffer was **missing every removal** (§4.2), so a late subscriber resurrected deleted rows; also bounded it, and closed the duplicate audit. | ✅ |
| ~~**R4**~~ **SHIPPED** | The staleness signal (§4.3) — an app could not tell "nothing to show" from "cannot ask", because every signal it would judge that by is read over the channel that is down. | ✅ |
| ~~**R5**~~ **SHIPPED** | Convergence (§4.4): the retry loops and the apps' reachability engine both **poll** for a fact the transport already knows. Both now take the connection signal, so a retry waits on the reconnect edge and an outage is reported without a probe. | ✅ |
| ~~**R6**~~ **SHIPPED** | R3's residual (§4.5): the replay buffer was bounded by rows *ever seen* because a removal could not be dropped. It can be, when the row it cancels is in no replayed payload. | ✅ |
| ~~**R9**~~ **SHIPPED** | §4.8: a Binder transaction that failed with the binding alive (too large) was read as a dead connection, and every later send and file token went with it; and a stream message over one transaction now travels in `SharedMemory`. | ✅ |
| ~~**R10**~~ **SHIPPED** | §4.9: concurrent subscribes on one session raced `StreamRegistry`'s unguarded maps — a stream that lost its registration token sent no snapshot (a read that never emits), one that lost its callback or ref set never heard a change. Not a restart path at all; found as an iOS "subscribe-window race". | ✅ |

R1 is the whole user-visible fix on the platform where it actually bites.

### 4.1 R1b — a failed subscribe was a one-way door

R1 recovers streams that were **healthy when the proxy died**. It cannot recover a stream whose
subscribe **failed**, and the skip is deliberate — by then there is nothing left to replay:

`setInitialResult(Failure)` delivers `start(error)` *and* `end(error)`. The consumer's `finally`
therefore calls `endStream`, which drops the refcount to zero, marks the interactor closed and runs
`onCleanup` — so `DefaultInteractorProvider` removes the entry entirely. Both ends of the
subscription are gone: the flow has completed and the interactor is unreachable. No later reconnect
can bring the read back; only restarting the **client process** rebuilt it.

That is a routine state, not an exotic one. A subscribe fails whenever the proxy is down at that
moment, or when the session is **refused** — which is exactly what the announce gate does on a
schema-version skew. Observed on-device: Drive was installed ahead of Control, its session was
refused, its file-listing subscribes failed; installing the matching Control got the session
re-admitted, and the listing **still stayed empty** until Drive itself was restarted.

⚠ **Why the emulator repro of §5 passes and this still bit.** Force-stopping Control with Drive
open exercises the *healthy-stream* path, which R1 handles correctly (verified 2026-08-03:
`live=9 replayed=9 skipped=0`, the reset tick fired, the listing survived). The failing path needs
a subscribe that never took — a different starting state, not a flakier version of the same one.

**Fix: retry in `changes()`, not in the provider.** Recovery has to build a *fresh* request object
and interactor, because the old pair is unreachable, so it belongs to whoever creates the
subscription. `changes()` now distinguishes a failed subscribe from a stream that ended cleanly and
re-subscribes on the former, backing off 1s → 2s → 4s → 8s → 15s and resetting once one lands. A
retry that succeeds emits a tick, because arbitrary changes were missed while the read was down —
the same reasoning as the reconnect `reset()`. A clean end is *not* retried: that is the observed
list genuinely being done.

**`flow()` followed in a second slice**, once the semantic change it implies had been audited: a
retrying state stream no longer *completes* on failure, so a consumer relying on termination would
hang instead. Across all 31 main-source consumers plus the test sources there is **no**
`Flow.toList()`/`single`/`last`/`count`/`reduce` on a `flow()` — every call site either takes
`.first()` (which completes on the first emission, and a `Failure` **is** an emission) or collects
indefinitely; the single `.first { predicate }` is a test whose predicate matches. So the retry
could be made unconditional rather than opt-in. ⚠ That grep is now a standing precondition for
adding a terminal operator to a `flow()`.

Two behaviours are specific to the state stream:

- ⚠ **The failure is emitted once per failure *run*, not per attempt.** It is a state signal
  ("this read is broken"), and repeating it every 15 s would wake every consumer with nothing new
  to say. A landed subscribe re-arms the report, so a later failure is surfaced again.
- ⚠ **Each attempt starts from an empty list.** A retry means arbitrary changes were missed while
  the read was down, so merging a fresh snapshot onto rows accumulated before the break would
  resurrect deleted ones — the same hazard `reset()` exists for, and the same reason nothing is
  emitted for the clear itself (a spurious "no rows" frame would flash on every recovery).

**Two collectors of the same callstack cannot poison each other**, which is worth stating because
`streamId` is deterministic and they therefore share one interactor.
`allocateOrReuseStreamInteractor` reuses an existing entry only while it is **not closed**, and a
failed subscribe delivers the terminal to *every* registered callback, so all of them unwind and
the entry is dropped once the last one calls `endStream`. If one collector retries before another
has finished unwinding it briefly re-attaches to the failed interactor and fails again — costing an
attempt, not correctness, because the backoff keeps trying and the entry cannot stay behind.

### 4.2 R3 — the replay buffer was missing every removal

`StreamInteractorImpl` buffers dispatched elements and replays them to any callback that registers
**after** the subscribe. That path is not exotic: `streamId` is deterministic (§2.2), so two
collectors of the same relation share one interactor, and the second one cannot be sent a snapshot
of its own — the buffer is the only thing that can catch it up.

⚠⚠ **The buffer recorded `Value` and `End`, and silently dropped every `Removed`.** So the history
it replayed was one in which nothing had ever been deleted, and a late subscriber rendered rows the
first collector had already seen removed. Proven by `StreamLateSubscriberTest`: two rows, one
deleted, second collector renders `[alpha, beta]` where the truth is `[beta]`.

This is the same *class* as the R1 `reset()` bug (§Q2) — buffered deltas describing a world that no
longer exists — reached through the late-register door rather than the reconnect door. R1 closed
one and left the other open, which is why Q2 was still listed as R3 work.

**Fix: buffer removals too, and key the buffer by row.** A new event for a row replaces that row's
previous one, because per-row last-wins is exactly what `merge`/`remove` apply on replay, so the
collapsed log converges to the same list. That also **bounds** what was an unbounded structure: the
buffer now grows with the number of distinct rows the stream has carried, not with the number of
changes. ~~⚠ It is bounded by *distinct rows ever seen*, not by live rows~~ — **closed by R6
(§4.5)**, which drops the removals that turn out to cancel nothing.

Identity is `getDataIdHash()`, the same hash `merge`/`remove` already key on, so no new assumption
is introduced (a collision would have mis-merged the list long before it could evict a buffer
entry). Rows with no resolvable identity — the initial snapshot, whose payload is the whole list,
and any element type without a `DataIdProvider` — are never compacted and simply append, which is
also what keeps the snapshot at the front where replay needs it. Resolving identity needs a
`TContext`, which `StreamInteractorImpl` did not have; the provider that builds it does, so it is
passed in (optional, so test doubles can omit it and lose only the compaction).

**The duplicate audit that R3 also called for came back empty.** No consumer accumulates across
`flow()` emissions — `flow()` re-emits the whole list each time, so consumers replace rather than
append — which leaves the interactor's replay as the only vector by which the same row could arrive
twice, and it is now keyed.

⚠⚠ **As built, 2026-11-05: a joiner's catch-up is ONE emission.** A faithful log is not enough if
the consumer *emits* it step by step: `TList.flow` emitted after every replayed element, so a late
subscriber's first emission was the buffer's head — the snapshot from when the stream first
subscribed. Photos' consent read showed `realm=false` over a stored yes this way (`tech-debt.md`, the
`PhotosModelDownloadTest` entry). `registerCallback` now hands a **joiner** (another callback already
holds the stream) its catch-up as one batch, `StreamCallback.catchUp`, and `TList.flow` folds it into
one emission — `StreamLateSubscriberTest.aLateSubscribersFirstEmissionIsTheBuffersState`. ⚠ Live
elements, and the first subscriber's whole stream, stay one emission each: `@defer`'s
snapshot-then-patch frames depend on it. ⚠ **Narrowed, not closed:** the emission is current as of
the buffer; a write still in the element queue arrives after it.
⚠⚠ **As built, 2026-11-06: the other door into the same class: a re-subscription under a reused id.**
A subscription that *ended* with elements still queued (in process or in the IPC push lane) left them
for the next subscriber of its id. Each element is now stamped on receipt, before the queue
(`StreamElementReceipts`), and `DefaultInteractorProvider.dispatchElement` drops one received at or
before the interactor it would reach was registered. That interactor's own elements can only follow
its start, which is sent after registration (`StreamResubscribeStaleBacklogTest`). ⚠⚠ On the proxy,
`StreamRegistry` gives each registration a token, and an update still evaluating when its stream ends
sends nothing: produced after the client's next registration, it would pass the stamp. ⚠ It is
process-local and adds no hop to subscribe; a barrier that did add one was withdrawn. ⚠⚠ **As built, 2026-11-06: a re-subscribe that *overlaps* the previous one's `EndStream`.** The
provider replaced a closed interactor before its end reached the proxy, which removes listeners **by
id**: an end processed after the new start silently froze the new read, and until it was processed
the old registration's sends passed the stamp and the token. A new subscription now waits for a
closing predecessor's end before sending its start, and drops every element until then
(`DefaultInteractorProvider.awaitPredecessorEnd`, `StreamResubscribeOverlapTest`). ⚠ Only then: a
subscribe with no end in flight neither suspends nor gains a hop. Bounded (5 s, on a real clock even
under `runTest`; a hung end is then forgotten), and released by a failed end. ⚠ A waiter ended by a
joiner while it waited subscribes afresh rather than starting the dead interactor. Process-local; a generation on `EndStream` would have been a wire reshape (`tech-debt.md`,
"A re-subscription is handed the ended subscription's still-queued elements").

---

### 4.3 R4 — an outage rendered as an ordinary empty screen

Recovery is now automatic (R1, R1b), and silent. The gap R4 closes is that during the outage the
app **claims things it cannot know**.

⚠ Drive's banner takes four inputs — storage status, sync health, the schema fence, content
transfer — and *every one of them is a row read from the proxy*. While Control is unreachable none
of them arrive, which the evaluator cannot distinguish from "not derived yet", so it returned
`null` and showed **no banner at all** while the file list rendered empty. Reproduced directly:
Drive launched against a disabled Control says **"No files here yet"** — an assertion about the
user's data made at a moment when it could not ask.

Any signal that fixes this has to be **client-local**, which is the whole design point.

- `TaskTransport.setOnConnectionChanged(cb)` reports **both** edges, where `setOnReconnected`
  reports only the recovery edge — the machinery needs the edge, a consumer needs the state. Same
  `fun`-with-no-op-default shape, so every implementation and test double keeps compiling.
- ⚠ On Binder the notification hangs off a **custom setter on `isConnected`**, because that field
  is assigned from five places (connect, disconnect, binding-died, null-binding, unbind) and a
  per-call-site notification would rot the first time a sixth appears. The desktop transport
  computes `isConnected` from its channel, so there it fires at the three transitions instead.
- Registering reports the **current** value immediately, not just the next change — otherwise a
  consumer that subscribes after the bind has landed waits for something to break.
- `TenshuClient.proxyConnected: StateFlow<Boolean>` is the consumer-facing seam. It is constant
  `true` wherever client and proxy share a process (iOS route-1, the web runtime, in-process
  tests) — accurate rather than a fallback, since there is no channel to lose.
- `SyncBannerState.Disconnected` outranks **everything, including `Locked`**: "locked" is a claim
  about the proxy's state, and making it requires having heard from the proxy. It carries no
  action button, because recovery needs nothing from the user.

⚠⚠ **A trap worth recording: `AndroidTenshuClient` overrode `init()`** while delegating everything
else to `DefaultTenshuClient`. That read as a harmless duplicate of the delegate's bind — until the
delegate's `init` became where `proxyConnected` is wired, at which point the override silently
skipped it and the signal would have sat at a constant `true` **on the one platform where the proxy
actually goes away**. The override now forwards. An override that skips its delegate also skips
whatever the delegate grows later.

### 4.4 R5 — two pollers for a fact the transport already knew

R1–R4 each added a piece; R5 is the pass that notices they now **overlap with older workarounds**,
and folds those onto the signal instead of leaving two mechanisms answering one question.

Two places polled:

- **`Stream.kt`'s retry backoff (R1b).** 1s → 2s → 4s → 8s → 15s, forever, for as long as the read
  is collected. Correct when the proxy is *there* and refusing. Pure waste when the **channel** is
  down: every attempt is a guaranteed-failing IPC round trip, and a proxy can stay unreachable
  indefinitely (uninstalled, disabled, Control-desktop not running).
- **`:app-reachability`'s `ControlReachability`** — Fit, Timeline and Maps. A probe every 5 s
  whenever the signal is not known-good, plus a `readEpoch` that force-re-subscribes read flows on
  recovery.

⚠⚠ **The reachability engine's stated premise had expired.** Its own KDoc explains why the probe
had to own detection: *"a reactive `.flow()` read against a dead transport does not reliably fail —
the collector just hangs, so waiting for a read to signal would wait forever."* R1b's subscribe
retry closed the hang and R4 supplies the transport edge. It was a workaround for holes that had
since been filled underneath it, and nothing had gone back to look.

**What R5 does not do, and why.** It does not delete the probe. `proxyConnected` is authoritative
in **one direction only**: `false` means unreachable now — no call crosses a dead channel — and
that inference is the only one drawn. `true` proves nothing, because a *connected* proxy still
refuses every read when the two sides disagree on a schema version (the announce gate). That is the
standing "Can't reach Control" case in this project's history and it is not a transport failure at
all. So a reconnect is treated as a **reason to re-check, never as an answer**, and the probe
remains the thing that detects a live-but-refusing proxy. Organic `markReachable`/`markUnreachable`
from read folds stay for the same reason.

> ⚠⚠ **Amended 2026-11-13 — `false` is not authoritative before the first connect.**
> `BinderTaskTransport` starts `isConnected = false` and reports it the moment the client
> registers, ~0.3 s before `onServiceConnected` (measured on a OnePlus CPH2581 with Control running).
> Read as an outage, it flashed *"Can't reach Tenshu Control"* on **every** cold start (14 of 14
> traced Photos launches composed `Transport`); in Photos, a nav entry built inside that window also
> kept the strip for good (`TabStripFollowsTheVerdictTest`). The engine now holds a `false` that
> arrives while it has **no verdict yet** for `FIRST_CONNECT_GRACE_MS` (2 s), and a connect
> cancels it. After the first verdict, a drop is still reported at once; a Control that never binds
> is still reported, 2 s late. Pinned by `aTransportThatConnectsWithinTheLaunchGraceIsNeverAnOutage`
> and `aTransportThatNeverConnectsIsReportedOnceTheGraceRunsOut`.

> ⚠⚠ **Amended 2026-09-03 — the last sentence was wrong about `markReachable`, and R5's own
> reasoning is what falsifies it.** *"A reason to re-check, never an answer"* is the right treatment
> for **every** positive signal, and an organic setter is by construction the opposite of it: it
> answers. Worse, it answers *and* cancels the check, because the probe loop collects `state` with
> `collectLatest` and runs only while that is not `true` — and it skips the recovery, since only the
> probe's own `false → true` flip bumps `readEpoch` and runs `onRecovered`. So a repository's
> success could stop a hung `resubscribing` stream from ever re-subscribing. Seventeen call sites
> across five apps did this. They are gone, `ControlReachability.markReachable` is `internal`, and
> `TenshuClient.proxyAnswered` — one event per call the proxy **served** — wakes the probe instead,
> which is what R5 said all along. §4.4b.
>
> The `markUnreachable` half of the sentence is still standing, and separately doubted:
> `ADR 0025` has Photos calling neither.

Shape:

- **`ProxyConnectivity`** (`:core`) — `isConnected` plus `awaitConnected()`. `TenshuClient.proxyConnected`
  is the same fact for a *consumer*; this is it for the machinery in `:core`, which sits below the
  transport and cannot see a `TenshuClient`. Optional like `BackgroundScopeProvider`: absent means
  no channel to lose, and callers read that as permanently connected.
- ⚠ **Carried by `DefaultInteractorProvider`, and that is not arbitrary.** R4's
  `setOnConnectionChanged` is a **single-occupancy slot** owned by `TenshuClient.proxyConnected`, so
  a second listener there would silently displace the first. The provider is already the one thing
  that self-wires to the executor's reconnect edge (R1), so it can answer the question without a
  second listener — and being resolved off `InteractorProvider` with `as?` means **no app's DI
  changes at all**.
- ⚠ `awaitConnected()` samples the reconnect **edge counter before** re-reading the level. The
  caller decides to wait on a stale read; a reconnect landing in that window must not be missed, or
  the caller sleeps until the *next* one — which, for a proxy that comes back and stays up, never
  arrives. The level re-read is the first defence and is pinned by test; the counter ordering guards
  a narrower window, asserted by construction.
- ⚠ In `changes()` the retry counter had to **split in two**. `attempt` gates the recovery tick a
  landed subscribe owes its consumer (changes were missed while the read was down); `backoff` is
  just how long to wait. Resetting the wrong one on a reconnect swallows the tick — caught in
  review of this change, not by a test.
- `ControlReachability` gains `transportConnected`. A drop sets the state `false` immediately
  instead of after a probe interval; a probe is **skipped entirely** while the channel is known
  down; a reconnect wakes the loop early rather than waiting out the interval.

⚠ One behaviour genuinely new rather than merely cheaper: a transport drop **while the signal is
known-good** now reopens the loop. Before, the probe only ran while the state was not `true`, so a
healthy app whose Control was killed sat at `true` until some read happened to fold a failure into
`markUnreachable` — and on an idle screen there may be no such read.


### 4.4b The positive signal moves to the executor too (2026-09-03)

R5 left the *negative* direction to the transport and the *positive* direction to seventeen
hand-written `markReachable` calls across five apps. This finishes the symmetry, and it is a
correctness change rather than a tidying one.

**What the calls actually claimed, and why they could not.** A repository marks reachable from a
fold's success arm. But a fold does not know how the round trip ended — a value can come back from a
warm cache (Photos' `duplicates()` read `RealmContentIndex.current()`, which is `observe().first()`)
— and the *executor* does know, because it owns the connection check, the round trip, the parse and
the result. `CallOutcome` already carried that classification and `DefaultTenshuClient` was throwing
the successful case away.

**⚠⚠ Why the naive wiring is worse than what it replaces, and this is the trap.** A proxy refusing
the session on a schema mismatch **still answers**: `ensureSessionPreflight` throws a typed
`TenshuException` so ordinary reads return before `transport.call` and emit nothing at all — but the
announce's *own* call carries `PreflightMarker`, skips the preflight, completes the round trip and
parses its refusal perfectly. Every first-party app announces at launch, so a
`CallOutcome.Answered` mapped to `markReachable` would clear the update banner on a device that can
read nothing, before any screen has read anything. That is SK-1, re-opened.

Two things keep it out:

- **`Answered` now requires the result to be a success.** A readable typed failure — the refusal, and
  an ordinary `notFound` — is `CallOutcome.AnsweredWithError`, which clears the skew latch (the
  answer *was* readable) and asserts nothing about reachability.
- **The signal wakes the probe; it never sets the state.** `TenshuClient.proxyAnswered` is an event
  stream, handed to `ControlReachability(answered = …)` and treated exactly as the reconnect edge
  is. The probe decides, so a refusing proxy still fails it — and because the flip goes through the
  probe, the recovery (`readEpoch` + `onRecovered`) actually runs, which the organic setter skipped.

⚠ `ControlReachability.markReachable` is now `internal`: the visibility is the enforcement, and the
compiler found every call site. `markUnreachable` stays public until the remaining apps adopt
Photos' classify-from-`proxyConnected` shape (`ADR 0025`).

⚠ Contacts was wired to `transportConnected` in the same change — it had been missed in R5 and was
leaning on its read folds for both directions, so removing the positive half would have left it on
the probe alone.

⚠ Silent on an in-process runtime (iOS route-1, the web runtime, every in-process test): `CallOutcome`
is the *remote* executor's classification. That is accurate rather than a gap — there is no channel
to lose there either.

**⚠⚠ And the contract all of this rests on was not true.** `RemoteBinaryTaskExecutor` states it in
its own comments — *callers get a `TResult`, never a throw* — and classifying the call's outcome at the executor made it true for the
**response parse**. The **request serialize** was left outside the try, so a request this build
cannot encode (`LinearSerializer`'s one `error(...)`, a composite missing a non-optional field)
threw straight past every guard and landed in whichever repository `catch` was nearest. Folded
here, with no `CallOutcome`: nothing was sent, so there is no round trip to classify — and
emphatically not `Unintelligible`, which means *the two builds disagree*, whereas failing to encode
our own request is this build's own registry and "update Control" is the wrong instruction for it.

⭐ **The asymmetry is what makes it worth writing down.** `LocalTaskExecutor` is
`core-error-channel.md` Phase 3's designated **single catch-all** and folds everything, including a
failed request *parse*. So an in-process runtime was protected from its own encoding bugs and a
Binder/UDS one was not — the reverse of where the wire boundary is, and the reason "the executor
never throws" read as true from every direction anyone had looked from.


### 4.5 R6 — the replay buffer counted rows ever seen

R3 left one bound unmet and said so: the buffer grew with *distinct rows ever seen*, because a
removal could not be dropped — it may be the only thing cancelling a row inside the initial
snapshot, and the snapshot is replayed whole and cannot be rewritten.

The missing distinction turns out to be small: **it only matters whether the snapshot contains that
row.** It does not, for anything added after it, and such a removal cancels nothing on replay —
its own `Value` was already displaced from the buffer by the same last-wins rule that keeps one
entry per row. So a tombstone for a row that appears in no uncompacted payload is garbage, and
dropping it leaves the replayed list identical.

The steady-state bound becomes **live rows + snapshot rows since removed**, both bounded by the
data rather than by time. A row that is added and deleted after the snapshot now leaves nothing
behind, which is the case that actually grew.

- ⚠ **Deferred behind a compaction floor (64 buffered entries), not run per removal.** Resolving
  the snapshot's row ids costs one `getDataIdHash` pass over its rows. That is the same work a
  replaying subscriber's `merge` does anyway — but it runs under the interactor's mutex, so the
  common small stream must not pay it to fix a problem it does not have. The floor doubles after
  each pass, making compaction amortised O(1) per dispatch instead of an O(buffer) scan per
  removal once a stream is legitimately large.
- ⚠ **Without a `TContext`, nothing is dropped.** No identity can be resolved, and the asymmetry is
  not close: over-retention costs bounded memory, while dropping a tombstone that was still needed
  resurrects a deleted row — R3's original bug, re-reachable through a new path. That is why the
  regression test asserts it at a buffer size that actually triggers compaction, and is
  mutation-checked in both directions.

---

### 4.6 R7 — a client launched before the proxy was never "re"-connected (2026-10-13)

R5 parked `Stream.changes`' retry on `ProxyConnectivity.awaitConnected`, which sleeps on the
**reconnect edge**. On the desktop socket that edge was defined as *a connection after an earlier
one*, so a client that started while Control was not running had no edge to wait for:

- **No announcement.** `openConnection` reported `hasConnectedBefore`, which is false on the first
  success however many attempts failed before it. The retry stayed parked for the life of the process.
- **No background retry either.** `scheduleReconnect` was started from a reader's death alone, and a
  client that never connected has no reader. It connected only when some call happened to bind.

Seen in Photos: launched before Control, the feed stayed on *Proxy not connected* after Control came
up; a manual retry loaded it, and from then on it never heard a change — the listing was a one-shot
read with a dead observer behind it.

**The rule now** (`UnixSocketTaskTransport`): a first connect that follows a failed attempt is
announced like a reconnect, and a `bind()` that could not connect starts the background loop.
`resubscribeAll` finds nothing to replay in that case, which is right — the edge is for the waiters.

- Test: `UnixSocketReconnectTest.aClientStartedBeforeTheProxy_connectsOnItsOwn_andAnnouncesIt`; each
  half removed turns it red on its own assertion.
- Verified on the packaged apps: Photos started first, Control 25 s later — the feed loaded with no
  retry and followed an add and a delete.
- **Binder had the same hole** (found by reading, the same day). `BinderTaskTransport` announced
  only from a flag set on disconnect, and two ordinary launches reach a first connect with a caller
  already turned away: Control absent or disabled (`bindService` returns false), and — the likelier
  one — Control cold-starting slower than `awaitConnected`'s 5 s, so the call fails while the bind is
  still in flight and lands on its own a moment later. Both now mark the debt
  (`ReconnectEdge.turnedAway`), and the next `onServiceConnected` announces.
  `ReconnectEdgeTest` pins the rule. ⚠⚠ **It does not pin the wiring** — the transport cannot be
  built on the host — and **neither path has been reproduced on a device**: the emulator's Control
  holds no realm, so there is no listing to watch recover.

### 4.7 R8 — an explicit unbind ended the session and the rebind replayed nothing (2026-10-16)

The third way to reach a connect that owes a replay, and the first one **reproduced on a device**.
Drive's `MainActivity.onDestroy` calls `client.destroy()` (the only app that does), so every Activity
recreation — a dark-mode switch, a rotation — unbinds and the new Activity's `client.init()` binds
again. Control's `ProxyService.onUnbind` unregisters every client it served once the last binding
goes, **streams and all**. The client's interactors stay open, a new subscription to the same chain
reuses one (stream id from the callstack), and `BinderTaskTransport.unbind()` set no debt, so the
rebind announced nothing:

- the Files root listing kept its retained view model and never refreshed again (a folder upload left
  *No files here yet* until a tab switch built a new view model);
- the list/grid toggle wrote its row and never saw it: every `readForDevice` shares the one
  `deviceSettings.flow()` stream, which the Activity-scoped selection view model held open, so even a
  fresh screen joined the dead subscription. Only process death cleared it.

This is what the store-screenshot walk hit on 2026-10-16: it switched the emulator to dark mode and
back with Drive on screen before seeding.

**The rule now:** `unbind()` of a live connection marks the edge `lost`, as the socket transport's
`hasConnectedBefore` already did. Test: `BinderTaskTransportRebindTest` (the real transport against a
fake `Context`, host-built with `isReturnDefaultValues`) — red without the line. Verified on the
emulator: after a dark-mode round trip, a folder upload appears in place and the toggle switches.

**Later phase (agreed with the maintainer 2026-10-17): don't unbind on a configuration change at
all.** The fix above makes a recreation *correct*, at the price of a full replay of every live stream
on each rotation or theme switch. The planned rework keeps the binding across configuration changes
(e.g. skip `client.destroy()` when `isChangingConfigurations`, or hold the client above the Activity)
so a recreation costs nothing. The replay stays as the safety net for real disconnects.

### 4.8 R9 — a too-large transaction was read as a dead connection (2026-10-23)

Not a reconnect that owes a replay — the reverse: a connection marked lost **that never was**, so no
reconnect ever came. Found by the Android store-screenshot walk (`store-screenshots.md` §8.1): after
Fit's full-history Health Connect import (~3,800 rows), the initial emission of one of Fit's list
streams was one 1.35 MB `callback` transaction, over the ~1 MB buffer Binder gives each receiving
process (`TransactionTooLargeException`). `AndroidClientConnection.safeTransact` treated **every**
`RemoteException` as a dead binder: it nulled `clientService`, marked the connection down and queued
the message. The binding was alive, so no `onServiceConnected` followed; every later `send` queued,
and `tokenize`'s `clientService?.receiveFile` silently did nothing while still writing the token —
every workout detail then failed with *ParcelFileDescriptor … not found in cache*, far from the
cause. On a genuine reconnect the queue drain re-sent the oversize message and failed again.

**The rules now** (`AndroidClientConnection`, Control → app):

1. **Only a dead binder is a dead connection**: a `DeadObjectException` from a binder whose
   `isBinderAlive` is false. ⚠ The binder decides, not the exception type — Android also throws
   `DeadObjectException` for a transaction that fails "on a small parcel" (the receiver's buffer
   full) while the process lives. Anything else fails **that message** and leaves the connection,
   the service ref and the queue untouched.
2. **A message that cannot go out ends its own stream with a typed error.** A stream element is
   replaced by a small `StreamElement.End(streamId, InternalError)`, so that one subscription gets a
   `TResult.Failure` and `Stream.flow()`'s retry (R1b) re-subscribes; every other stream keeps
   delivering. ⚠⚠ For a stream refused **every** time (API 24–26, or an app on revision 0), the
   backoff is what keeps that from being a 1 s loop: `flow()` used to reset it, and re-arm the
   failure report, on every landed subscribe, so subscribe → refused → retry after 1 s, with a fresh
   Failure each second. It now resets only on a delivered element — one report, backing off to 15 s.
   ⚠ By design that also means a failure that follows a long failing streak (with no element in
   between) waits up to 15 s before its retry and is not reported again.
   And an end that does not go out either is **held** per stream id (`HeldEnds`): resent before the
   next message and by a timer (1 s backing off to 30 s, eight times, ~2 min — after that it waits
   for the next send), single-flight, with the stream's later elements dropped meanwhile; lost, it
   would leave the subscription hanging with no signal, or missing a `Removed` and showing a ghost
   row. ⚠⚠ Stream ids are callstack hashes, so the same query subscribed again **reuses the id**: a
   hold is dropped whenever Control registers or unregisters that id (`ClientConnection.forgetStream`
   from `StreamRegistry`) and on a fresh connection, or it would end the new subscription with the
   old error or hold back its snapshot. For the same reason an end for a binder that is gone is
   dropped, never queued — the queue drains into the next app process — and a hold is **bound to
   the binder it failed against**: delivered to that binder only, dropped when a retry finds it dead
   or replaced, and all holds are cleared *before* a new binder is published on reconnect (and on
   `destroy`). Residuals, both named: (i) an end that still has not gone out after the timed
   retries waits for the next send to that app; (ii) ⚠ **an end already in its transaction cannot
   be recalled**: if the app ends stream S and re-subscribes the same query while a held end of the
   old S is being delivered on another thread, the old end can land on the new S. Narrow (a held
   end, a retry, and a same-id re-subscribe within one transaction's time) and self-healing (the new
   S fails once and `flow()` retries it). No cheap client-side guard exists — an end legitimately
   arrives before its subscribe is answered (a refused snapshot is ended inside the subscribe) —
   and a per-subscription generation on the wire would be a positional-shape change under House
   Rule 9, so it is accepted rather than built.
   `InternalError` (not a new code) on purpose: a new `TError` composite would be a
   wire-shape change an older app cannot parse, which is the last thing an error path may need.
   Nothing is queued for a replay that would fail the same way, and a message carrying out-of-band
   tokens is never queued at all — its descriptors went to the process that died.
3. **A token is written only once its delivery went out.** An in-memory payload that cannot go
   through `SharedMemory` is written inline instead; a file (no inline fallback — it can be
   gigabytes) throws a typed `TenshuException`, which fails the stream element as in (2), and on the
   sync-reply path (`serializeReply` in `ProxyGateway.kt`) becomes the call's typed `TResult.Failure`.
4. **A message over `BinderIpc.CALLBACK_INLINE_LIMIT_BYTES` (128 KiB) travels out of band**: the same
   serialized bytes in a read-only `SharedMemory` region through `IClientInterface.callbackShared`,
   only the descriptor in the transaction; the app copies them out and hands them to the same
   `onCallback` as `callback`. ⚠ The limit is far under 1 MB because that ceiling is the receiving
   process's whole buffer, shared by every transaction in flight to it.

> ⚠ *1.0.1 re-cut (2026-11):* no app below revision 1 ever reached a user, so Control no longer asks
> — `AndroidClientConnection` sends `callbackShared` to every app over the inline limit (still
> inline below API 27). The negotiation below is history; a later revision's method must be gated
> on the query again.

**The wire decision.** `IClientInterface` is widened append-only (two methods, after an *APPEND
ONLY* marker — a method's transaction code is its position) and **negotiated** the way
`IProxyInterface` already is: the app reports `ipcRevision()` (`BinderIpc.CLIENT_REVISION`, 1). An
older app has no such method; its stub answers the unknown code with an empty reply, which the
generated proxy reads as 0 (only an *answer* is cached per binder; a failed query is asked again).
⚠⚠ That same empty reply is why the revision must be asked rather than
the new method tried: sent `callbackShared`, an older app would "succeed" and drop the message
without a trace. Below revision 1 — and on a device below API 27, which has no `SharedMemory` —
Control sends the inline `callback` the app always got, and (1)–(2) handle it if it does not fit:
a loud, per-stream typed failure, never a misread. No House Rule 9 bump: the payload bytes are
byte-for-byte those `callback` carries, so no schema, store or format version is involved; the
envelope's skew is the transport's own revision, as for cancellable reads. An older **Control**
never calls the new methods, so a newer app talking to it is exactly as before.

**Not covered — the other direction.** The sync reply of `IProxyInterface.call` has no out-of-band
carrier (`tech-debt.md`, *A sync-call reply has no out-of-band path*, which also records how an
oversize reply is mislabelled on the client). App → Control sends were checked for the same defect
and do not have it: `asNotConnected` maps a failed transaction to an `IllegalStateException` and
leaves connection state to the `ServiceConnection` callbacks.

Tests: `AndroidClientConnectionTest` (host; 15) — a TTLE keeps the binding and later sends; the
failed element ends only its stream with the typed error while another stream keeps delivering; a
`DeadObjectException` from a live binder is not death; a file token still travels after a TTLE and is
never written when its descriptor did not go out; an oversize message arrives whole out of band, an
older app is never sent the new method, the revision is asked once per binder, and a device without
`SharedMemory` goes inline. Seven of the local-fix cases fail against the pre-fix class.
`SerializeReplyTest` pins the reply path's typed failure. After review (19 cases): a failed
revision query is asked again; a held end is resent first and its stream's later elements dropped;
a carrier failing locally fails only its message. After the second review (24 cases): a held end
never reaches a re-subscription under the same id, nor holds back its snapshot; the timer retries
it without a send, with a backoff, a bounded number of times; it is in flight at most once under a
re-entrant send; an end for a gone binder is not queued for the next process.
`StreamRegistryTest` pins that registering and unregistering an id calls `forgetStream`. `StreamSubscribeRetryTest` (a subscribe ended
before its first element backs off, 4 subscribes in 7.5 s where the old loop made 8, and reports
once) and `ClientCallbackGuardTest` (Control's UID only, a reinstalled Control's new UID, the size
cap, a stranger cannot make every call a package-manager lookup, a reinstalled Control is admitted
on its first call). ⚠ The guard first resolved Control's UID up front and rate-limited re-checks;
the smoke gate then showed Home refusing Control (`callback: uid 10230 is not Control`) for a
re-check interval — so it asks whether the **caller's** UID belongs to Control instead, the way
Control checks its own callers. ⚠ Not covered by a test: the real-Binder claim that an unknown transaction code reads as 0, and
`SharedMemoryCarrier` / `readSharedMessage` themselves — the module's host tests run against the stub
`android.jar` (no Robolectric), where `Binder` and `SharedMemory` do nothing; the device runs below
cover them.

Verified on the emulator (2026-10-23, `scripts/store-screenshots-android.sh fit` with the full-history
step restored): Control logged `callback(app.tenshu.fit.android): 1350918 bytes over the 131072-byte
inline limit — stream -659827057, 3651 × tenshu/core/entity` — the very message that failed before,
same size — and no transaction failure; the workout detail (route and heart rate) loaded and Profile
showed *Height 178 cm*. Which of Fit's list streams it is the log does not name (the rows arrive as
`tenshu/core/entity`); 3,651 of the import's 3,659 items, so a list over the imported samples.

After the review fixes (Control-UID guard, size cap, held ends, backoff), again on the emulator:
the same `fit` run exited 0 with the 1,350,918-byte message delivered out of band twice, the workout
detail loaded, and Control's log held no `is not Control` rejection. The Android smoke gate
(`scripts/android-smoke.sh`, emulator-5554) **PASSED** — launch, no crash/ANR, schema lockstep — with one
WARN: `unannouncedSession` on a few Contacts and Fit reads, the two apps the gate deliberately does
not rebuild (older installs), the startup race that WARN line exists to surface.

Second review (held ends keyed against id reuse, single-flight + timer, no queued ends, the guard
asking about the caller): the smoke gate **PASSED** again (same WARN class, `unannouncedSession` on a
few Fit/preference reads of the un-rebuilt apps) with **no** `is not Control` refusal in logcat —
the run before the guard rewrite had shown Home refusing Control for a re-check interval — and the
`fit` run exited 0 with the 1,350,918-byte message out of band and the workout detail loading.


### 4.9 R10 — concurrent subscribes lost a stream's registration (2026-11-11)

Not a restart either: a read that **never emitted** on a healthy, in-process proxy. Seen as
`ContactsLocalRuntimeTest.aWrittenContactIsReadBack` timing out in 2 of 15 iOS simulator runs, with
the test's diagnostic saying a *fresh* subscription saw the row — read at the time as "the write
committed between the initial read and the listener's registration". ⚠ **That was not the
mechanism**, and nothing in this path reads before it registers: `StreamRegistry.setDatabaseListener`
installs the array listener before its initial evaluation, and the write had returned before the
read began.

**What a proxy-side trace of a stalled read showed** (simulator, 12 stalls in 150 fresh assemblies,
each one carrying it): Contacts' `contacts()` is a `combine` of four relation reads, so four
`setDatabaseListener`s run **at once** on `Dispatchers.Default` workers, and nothing above the
registry serialises one session's requests. Its maps were plain hash maps. One of the four
registrations' `streamIdToRegistration[id] = token` was lost to a concurrent write, so that stream's
own initial evaluation found itself not live, sent **no snapshot**, and `combine` never emitted. A
fresh subscription seldom races the same way, which is what made it look like a missed invalidation.

**The other face of the same race is a missed invalidation**: a lost `streamIdToUpdateCallback` or
`refToStreamListeners` entry is a stream that subscribed fine and never hears its array change — on
the JVM that is the shape that reproduces, in the first of 400 rounds of 16 concurrent registrations.

**The rules now** (`StreamRegistry`):

1. **Every map is under one lock** (`state`, an atomicfu reentrant lock), held only around map reads
   and writes and the database's listener (un)registration — never across a suspension or a send.
2. **The order is registry → database.** The database fires array listeners under its own lock, so
   `triggerUpdate` never takes the registry lock: it reads an immutable copy of the array → streams
   map republished under the lock (`listenersByArray`), and each stream's update looks up its
   callback under the lock from its own coroutine — still one launch per stream, so a change costs
   the same dispatch hops as before (`DatabaseStreamTest.testNestedFolderStreaming` counts them).
   Taking the registry lock in the listener would invert the order `registerRefForStream` holds (the
   reason `docs/tech-debt.md` recorded this as "not a one-line lock").
3. **The liveness check and the ref bookkeeping are one step.** A stream ended between its
   evaluation and its ref update used to re-register refs nobody would remove; the check is repeated
   inside the guarded step.
4. **A registration installs only while it is still the live one.** `setDatabaseListener` stamps its
   token, then installs the callback, the root ref's listener and the active-stream count in a
   second guarded step that re-checks the token — an `EndStream` landing in between used to leave a
   listener re-evaluating a dead stream, and `activeStreamCount` one too high (holding
   `SyncScheduler` on its fast tick).
5. ⚠⚠ **The session scope must dispatch.** Under an unconfined scope `triggerUpdate`'s launched
   update runs inline inside the database's lock and its first guarded step inverts the order. The
   production scopes are `Dispatchers.Default`-based; the unit tests that use unconfined scopes run
   over lock-free fake databases, so they could not catch a scope that stopped dispatching.

The snapshot is republished once per outermost hold that changed it, not per ref, and deferred
patch jobs are cancelled after the lock is released (cancellation runs completion handlers that take
it again).

Guards: `StreamRegistryConcurrentRegistrationTest` (JVM) — a second registration must not progress
inside the first (deterministic); a stream ended between stamp and install leaves no listener, count
or snapshot (deterministic, via the `afterRegistrationStamped` seam); and 400 rounds of 16 concurrent registrations each send a snapshot
and hear their change; both red with the lock deleted. `ContactsConcurrentSubscribeTest` (iOS
simulator) — 100 fresh assemblies, each must list the contact it wrote. Measured on the simulator:
20 runs of the whole `:tenshu-sdk-ios-contacts`
iOS suite each side. **Before:** `ContactsConcurrentSubscribeTest` red in 18 of 20 (1–14 of its 100
reads lost per run), and `ContactsLocalRuntimeTest` failed in 3 — `aWrittenContactIsReadBack` once,
and two **first emissions that never came** (`contacts()` on an empty graph, `ownProfile()`), which
the now-bounded `firstBounded()` reports as a 30 s timeout instead of hanging the binary as a bare
`first()` had. **After:** 0 of 20, i.e. 0 lost reads in 2 000 fresh assemblies, no hangs. Earlier
probes without the fix: 6 of 60 and 4 of 80 reads lost; reachability ruled out (the bare repository
lost reads as often as the wrapped one).

⚠ **Every platform had it**, not only iOS: the registry is `commonMain` and every proxy — Control
on Android and desktop, route 1 on iOS, the web runtime — subscribes concurrently. It is likeliest
wherever a screen combines several reads; the timing only made it common on the simulator.

## 5. Testing

The bug is invisible to every existing test because they all use an in-process proxy that never
dies. That gap is itself worth closing.

- **Unit (`:core-common`)**: a fake `TaskTransport` that can simulate death + reconnect; assert
  that a registered stream re-sends its start callstack on `onReconnected` and that the client's
  interactor identity (hence its callbacks) is preserved.
- **Unit**: assert the re-seeded snapshot reaches callbacks (the Q1 decision), since that is the
  half that actually fixes an idle row.
- **On-device (the real gate)**: with Drive open, `am force-stop app.tenshu.control.android`, wait
  for rebind, change state in Control, and assert Drive updates **without being restarted**. This
  is the exact reproduction above and cannot be faked in-process. **Run 2026-08-03 (emulator):
  passed** — the listing survived and instrumentation showed `live=9 replayed=9 skipped=0` with the
  reset tick delivered.
- **R3 (`StreamLateSubscriberTest`)**: two collectors of the same relation (same callstack ⇒ same
  interactor). One asserts the late one does not see a row that was already deleted; the other
  measures the buffer's size indirectly — a signal-only subscriber emits one tick per replayed
  element, so N edits of one row collapsing to one entry is observable without reaching inside.
  Both fail against the pre-fix file. ⚠ The write burst must be **spaced**: the store's write
  mailbox dedupes queued writes by hash, so back-to-back edits of one row collapse before they ever
  become stream elements — which would make the bound test pass against any implementation. ⚠ And a
  signal-only stream has no initial emission, so there is nothing to await to know it is live; a
  write that lands before the subscribe is missed and never resent.
- **R1b (`StreamSubscribeRetryTest`)**: an `InProcessProxy` whose `Stream.ID` implementation is
  withheld, so the subscribe *fails* rather than returning an empty stream; assert no tick is
  fabricated while it fails, that the read recovers on its own once the implementation returns,
  and that the recovered subscription is live rather than a one-shot. ⚠ It is a real-clock test
  (the retry backoff runs on the collector's dispatcher, not `runTest`'s virtual time) and it was
  confirmed to **time out against the pre-fix `Stream.kt`** — a retry test that passes both ways
  proves nothing.

---

## 6. Threat model

No delta. This is client-local subscription bookkeeping over the already-authenticated
Drive↔Control IPC channel. No relay surface, no new asset, no protocol change — House Rule #1 and
#2 untouched. Worth stating explicitly in the implementing PR so the reviewer does not have to
re-derive it.

R9 (§4.8) **does** touch the threat model, and this section said otherwise until review: each app's
`ClientCallbackService` is exported with no permission, so *any* installed app could call
`IClientInterface` — forging stream elements before R9, and with `callbackShared` also making the app
allocate a buffer of a size the caller chose. Now every delivering method admits Control's UID
alone and a region is capped at 64 MiB before anything is allocated (`ClientCallbackGuard`). The
amendment is `threat-model.md` **T-C-19**.

---

## 7. Open questions

- ~~**Q1 — reconnect snapshot delivery.**~~ **DISSOLVED in R1, not decided.** The initial payload
  reaches consumers as stream *elements*, not through `initialResult` — `ListStreamImplementation`
  returns it but the `flow()` consumer never reads it, and `dispatchElement` only requires it to be
  a non-null `Success`, which it already is from the original subscribe. So there is no re-seed and
  the write-once contract is untouched.
- ~~**Q2 — buffered elements across a reconnect.**~~ **Answered: clear** — and **R3 later found the
  same class of bug through the late-register door**, where clearing is not an option because the
  buffer is the late subscriber's only source of state: it recorded additions but not removals, so
  it replayed a history in which nothing was ever deleted (§4.2). R1 found this matters
  more than the draft realised. The consumer keeps a *local* list mutated by `merge`/`remove` and
  only drops rows on an explicit `Removed`; every `Removed` from the disconnect window died with the
  old registry, so merging the new snapshot over the old state would **resurrect deleted rows**.
  Closed by a client-local `StreamCallback.reset()` (synthesised, never on the wire ⇒ no format
  bump): `flow()` clears its list, `changes()` emits a tick.
- **Q3 — thundering herd.** A client with many live streams re-sends all start callstacks at once
  against a proxy that is still warming up. Does this need staggering or a bounded concurrency, or
  is the existing request pipeline sufficient? *Probably fine at current stream counts; measure
  before adding machinery.*
- **Q4 — first-connect vs reconnect.** `onServiceConnected` fires for both. Resubscribing on first
  connect is harmless (the map is empty) but the distinction should be explicit rather than
  incidental.
- ~~**Q5 — does the desktop transport reconnect at all?**~~ **Answered: no.**
  `UnixSocketTaskTransport.bind()` connects once and the reader thread exits on the first
  exception, with nothing retrying — so on desktop a dead Control kills the transport outright,
  not just the subscriptions. R2 owns the reconnect loop (§3.2).
- **Q6 — should a stale proxy-side session be reaped eagerly?** Today a dead client's session
  survives until that clientId reconnects with a new identity (§2 (b)), queueing callbacks for a
  process that no longer exists. Bounded, so not urgent — but if R4 ever adds a staleness signal
  the two lifetimes should be reasoned about together.
