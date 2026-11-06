# Telling the user their device is out of step

**Status:** v0.7 — **SK-1…SK-4 all shipped** (§8–§8.7; the identity row is per-writer, §8.7; SK-2 was rebuilt as `RuntimeIdentity`
in §8.4, SK-4 landed 2026-08-25 and is wired in all three `PlatformProxyModule`s). Control
surfaces its own fence (§8.8).
⚠⚠ **§1 and §3.2 describe the pre-build state in the present tense and are superseded by
§8–§8.6** — read those before touching the fence surface. Remaining: the device pass (§8.6).
**Owns:** the user-facing surface for schema skew, on every platform.
**Inherits from:** [`core-schema-evolution.md`](core-schema-evolution.md) §6 (session
announce) and §7.3 (the pull fence), which built the detection and explicitly deferred
the UX — *"no UI in this slice"*.

---

## 0. The question this doc answers

> *"Where do we need to show this banner? For the user the issue will be an individual
> app not syncing, even when only an update of Control would be necessary to fix
> things."*

That instinct is right, and it is the whole problem. The app the user is looking at is
often **not** the app that needs updating, and on one of our three platforms it *is*.
A banner that guesses gets it wrong half the time.

## 1. The finding: detection is done twice, presentation is done zero times

Before designing anything, the audit result — because it changes what this work is.

We do not have a detection gap. We have **two independent, working, per-service skew
detectors**, both of which already produce a named, directional, actionable verdict:

| | **Skew A — device ↔ realm** | **Skew B — app ↔ proxy** |
|---|---|---|
| Mechanism | `SchemaPullFence` → `SchemaFenceStatus` | `AnnounceSession` → `SessionAdmission` |
| Question | "is this *device* behind the realm?" | "do this *app* and its proxy agree?" |
| Granularity | per service | per service, **plus direction** |
| Blast radius | this device stops pulling — every app, all data | this session is refused — one app |
| Symptom today | silent staleness; data just stops arriving | a generic failure on whatever screen called first |
| Surface today | **none** | **none** |

Neither is rendered anywhere. Verified: no `.kt` or `.swift` outside `core-common`
references `SessionAdmission` or `ServiceVersionMismatch` — the sole mention is a KDoc
in `UnannouncedSessionError.kt`. No UI reads `SchemaFenceStatus`; the client-side
accessor in `sync-service-client/.../SyncServiceExtensions.kt` has no consumer.

So **this track is not "detect skew". It is exclusively presentation.** That is good
news for cost and bad news for our estimate of how well the system currently behaves —
we have been computing the right answer and throwing it away.

### 1.1 Skew B already knows which binary to blame

`AnnounceSessionImplementation`'s own KDoc:

> - `announced > current` → the proxy is behind (**"update Control"**);
> - `announced < floor` → the client is cut off (**"this app needs updating"**).

The direction is the answer. The proxy compares two vectors it holds simultaneously, so
it never has to know its own deployment topology to decide who is stale — a fact that
matters in §3.

⚠⚠ **And the verdict is retained, then dropped.** `SchemaAnnouncer.lastAdmission` holds
*"The last `SessionAdmission` received, refused ones included."* On refusal, the typed
per-service mismatches are flattened into a **string** inside an
`UnannouncedSessionErrorDto` message. Everything the banner needs is in memory, one
field away, already correct.

⚠⚠ **Worse, the launch-time announce swallows it.** `wireSchemaAnnounce`'s optional
`launchScope` announce is `runCatching`-wrapped and deliberately fire-and-forget. So a
device that is *definitely* skewed at launch says nothing at launch; the user finds out
later, from whichever screen happened to issue the first operation, as a generic error.
This is the mechanism behind the *"Can't reach Control"* and empty-screen reports.

## 2. Why the user's framing is the correct one

The two skews have different owners and different fixes, and the *symptom is identical*:
an app that will not show fresh data. The user cannot be asked to tell them apart, so
the surface must.

- **Skew A is device-scoped.** One stale service fences the whole device's pull. Drive,
  Fit, Timeline and Maps all go quiet because `identity` was behind. Nothing is broken
  locally — reads keep working — so the failure presents as *"my stuff isn't arriving"*,
  not as an error.
- **Skew B is app-scoped and bidirectional.** It can mean "update Drive" or "update
  Control", and only the proxy knows which.

⚠ These can also **co-occur**, and then they disagree about the fix: a freshly-updated
Drive on a device whose Control is old is skew B ("update Control"); the same device
after Control updates may still be skew A if a *peer* moved the realm epoch further.
The surface must therefore be able to show B first and A after, without either claiming
to be the whole story.

## 3. The platform asymmetry — the part that is genuinely hard

`SyncProxyInitialization.kt:770` — *"the sync runtime elected by this session (one
scheduler per process)"*. One scheduler per **process** is the load-bearing clause.

**Android and desktop.** Control is the only proxy. Drive, Fit, Timeline and Maps are
separate processes that reach it over Binder / UDS. There is exactly **one** runtime,
one version vector, one fence verdict — and it belongs to Control. If it is fenced,
*every* app is stale, and the fix is *update Control*. The app the user is staring at is
innocent, and telling them to update it is a dead end.

**iOS route-1.** Drive, Fit, Timeline and Maps each link their **own** in-process proxy
over the shared replica; first-party apps are realm co-owners
([`ios-first-party-route-1-migration.md`](ios-first-party-route-1-migration.md) Decision
C, threat model A4). Each declares its own vector and evaluates its own fence. Drive-iOS
can be fenced while Control-iOS is fine. The fix is *update the app you are in* — and
the same realm can hold some fenced apps and some healthy ones simultaneously.

So the naive copy is wrong on exactly one platform each way:

| Banner says | Android / desktop | iOS route-1 |
|---|---|---|
| "Update this app" | ❌ wrong — Control is the stale one | ✅ right |
| "Update Control" | ✅ right | ❌ wrong — Control may be current |

⚠ Skew B degenerates correctly on iOS and needs no special case: the app *is* the proxy,
so both vectors come from one binary and can never disagree. **Skew B is inert on iOS
route-1 and load-bearing on Android/desktop.** Skew A is live on both.

### 3.1 The rule that resolves it

> **A fence belongs to a runtime. A runtime is owned by exactly one installable binary.
> Name that binary.**

- Android / desktop → the runtime is Control's → *"Update Control"*
- iOS route-1 → the runtime is the app's own → *"Update Drive"*
- Control-on-iOS → the runtime is Control's own → *"Update Control"*

There is no third case, and the rule needs no platform branch at the call site.

### 3.2 How the client learns it — and why it must not compute it

The obvious implementation is for the client to check whether its transport is
in-process. ⚠⚠ **Do not.** Verified: no such discriminator exists on the client today
(`core-client` and `core-common` have no `isInProcess` / route-1 flag), so it would have
to be invented — and it would encode the topology in the layer least able to keep it
true, then need revisiting for every future deployment shape (remote proxy, EPC
third-party, web apex).

**Instead, the proxy stamps its own identity onto the fence status when it writes the
row.** The proxy trivially knows which binary it is. The client renders what it is told
and the topology question never reaches it.

Concretely, one field on `SchemaFenceStatus` — today `key` / `fenced` /
`fencedServiceIds` — plus its migration:

```
+  /** The app that owns this runtime, and therefore the one to update.
+   *  Control on Android/desktop; the app itself under iOS route-1. */
+  val owningAppId: TString
```

⚠ House Rule #9: this reshapes a `sync` composite, so it owes a service version bump and
a `SchemaMigration` in the same PR. `SchemaShapeBaseline` will fail loudly if we forget.
⚠ Pre-release, a bump plus migration is close to free — cheaper than the alternative of
deriving the field client-side forever.

⭐ **This also removes the need for a service-id → app-name map**, which does not exist
and which I had assumed we would need. Verified: no such mapping is present anywhere;
`AppGrant` carries `(appId, serviceId, operationId)` but is a *permission* record, is
realm-level, and lists what an app may call — not which app is responsible for a
service's schema. Using it would give wrong answers for any service two apps both touch.
`fencedServiceIds` stays what it is: diagnostic detail, not the headline.

## 4. The surface

**One component, rendered in every app**, because the user is wherever they are. Both
signals are already client-readable, so this is presentation only.

**Placement.** A persistent inline banner at the top of the app's primary surface — not
a modal, not a toast. The condition is durable (it lasts until the user updates
something), and a dismissible toast for a durable condition is how you get a user who
believes sync is simply broken. It must survive navigation within the app.

**Priority.** Skew B before skew A. B is the one that produces a hard failure right now;
A degrades to staleness. Show one banner, never two.

**Copy**, derived from `owningAppId` and the announce direction — never guessed:

| Case | Headline | Body |
|---|---|---|
| A, owner is this app | Update Drive to keep syncing | This version of Drive is older than your other devices, so new changes aren't arriving. Your files here are safe. |
| A, owner is Control | Update Control to keep syncing | Tenshu Control on this device is older than your other devices, so new changes aren't arriving for any app. Your files here are safe. |
| B, `announced > current` | Update Control to use Drive | Drive is newer than Tenshu Control on this device. |
| B, `announced < floor` | Update Drive | Drive is older than Tenshu Control on this device and can no longer talk to it. |

Two rules the copy must keep: **say what is still true** ("your files here are safe" —
under §7.3 the device is read-only-stale, not bricked), and **name the app, never the
service** — `fencedServiceIds` belongs in a diagnostics expander, not the headline.

**The action.** A single primary button routing to wherever that binary updates: Play
Store, App Store, Mac App Store.

⚠⚠ **Desktop has no target.** Those are self-signed bundles with no store and no
in-app updater. The desktop copy must degrade honestly to "Update Tenshu on this device"
with no button, or link a download page if one exists by then. This is a real gap and it
is the strongest argument in §5.

## 5. Why not the hard app gate

The suggestion on the table was a blocking *"You need to update X to continue"* screen
with a store link, i.e. the classic mobile pattern. I recommend **against** it, on four
grounds:

1. **It is strictly heavier than today's behaviour and buys nothing on the main case.**
   Skew A leaves the device fully usable for local reads. Blocking a user out of files
   that are sitting on their disk, because a *peer* moved a schema epoch, converts a
   degradation into an outage.
2. **It has no target on desktop.** A blocking screen whose only action is unavailable
   is a trap. We would be shipping a state a user cannot leave.
3. **It aims at the wrong app.** Under skew A on Android, the gate would fire in Drive
   while the fix is in Control — so a user who does exactly what the blocking screen says
   is still blocked afterwards.
4. **We already have a hard gate where a hard gate is right.** Skew B's
   reject-if-silent already refuses the *session* when the vectors are incompatible
   (`SchemaSessionPolicy.REJECT_IF_SILENT = true`). That is a correctness boundary —
   positional garbling — and it is enforced at the transport, which is the right place.
   The banner's job is to explain that refusal in words, not to add a second one.

⚠ The exception worth keeping open: if we ever ship a change where an old client would
*corrupt* rather than merely miss data, that is a hard-gate case. Nothing in E0–E4 is
one, because the asymmetric contract (§3) means new code reads old formats.

## 6. Phasing

- **SK-1 — surface skew B.** ✅ **Mechanism + Fit shipped** — as built in §8. Stop
  discarding `lastAdmission`; expose the verdict reactively; render it. No schema change.
- **SK-1b — the remaining shells.** ✅ **Shipped** — Timeline, Maps and Contacts, which
  with Fit is *every* app that renders this banner (§8.2). Smaller than §7 Q1 predicted.
- **SK-2 — name the fence's owner.** ✅ **Shipped, but not as designed** — §3.2's
  `owningAppId`-on-the-fence-row plan is unbuildable and would have been harmful. See
  §8.3; the answer is a `ProxyHost` declared by each wiring site.
- **SK-3 — surface skew A.** ✅ **Fit shipped** (§8.3). ⚠ The other three shells need a
  decision first (§8.3.1), not just wiring.
- **SK-4 — an honest update route.** ✅ **Resolved** (§8.5), and not as scoped: *no*
  platform has an update route, so this was never desktop-specific. No button ships; the
  actionable half was making the reported build version real. ⚠ Superseded 2026-11-04: a skew or
  fence now gets an **Update** button wherever the stale binary has a configured page (§8.5).

SK-1 stands alone and is worth shipping on its own.

## 7. Open questions

- **Q1 — where does the banner physically live in each app?** `ui-core` can hold the
  component, but each app must place it. Six apps × two platforms is the actual cost of
  this track, and it is larger than the logic.
- **Q2 — does iOS route-1 need a *cross-app* view?** If Drive-iOS is fenced and Fit-iOS
  is not, does Control-on-iOS show a roll-up? Deferred: it needs a per-app fence read
  across app-group boundaries, which we do not have.
- **Q3 — first-run false positive.** `schemaFenceStatus()` is *"`null` until the proxy
  has evaluated the fence (its first sync tick after unlock)"*. The banner must treat
  `null` as "unknown", never "fine" and never "fenced". ⚠ The inverse of the
  `divergence-empty-tip` false positive, and the same class of bug.
- **Q4 — does the Android smoke gate cover the banner?** It already asserts the
  `TenshuSchemaAnnounce` beacon, so a refused admission is observable in CI. Asserting
  the *banner* needs a deliberately-skewed app in the built set, which conflicts with the
  gate's lockstep design. Probably a unit-level test of the copy selector instead.

---

## 8. As built — SK-1 (2026-08-25)

The mechanism, plus Fit wired end to end as the proof. Six shells remain (SK-1b).

**The verdict became a value.** `SessionAdmission.toSchemaSkew(appId)` distils a refusal
into `SchemaSkew(appId, direction, serviceIds)` — plain Kotlin, not a composite, because
it never crosses the wire and a schema type would owe a bump and a migration to carry
what the client already holds. `SchemaAnnouncer` gained `skew: StateFlow<SchemaSkew?>`,
set on every announce and cleared by an admitted one.

⭐ **"Un-swallowing" the launch announce turned out to need no error-handling change.**
`announce()` already assigned `lastAdmission` *before* throwing on refusal, so the
`runCatching` in `wireSchemaAnnounce` was discarding the *throw*, never the *verdict*.
Making the verdict reactive was sufficient; the fire-and-forget launch announce now
reports skew at launch rather than on whichever screen issues the first operation.

**The reason joined the fact.** `ControlReachability` gained `reason:
Flow<UnreachableReason?>` — `Transport` or `Skew(update, serviceIds)`. The engine's own
class doc had already identified this exact conflation (*"a connected proxy still refuses
every read when the two sides disagree on a schema version … which is the 'Can't reach
Control' case that is not a transport failure at all"*) and then reported both as
`state = false`.

Three invariants, each pinned by a test that was mutation-checked to fail without it:

- ⚠⚠ **Skew outranks Transport.** A refused announce also fails every probe, so a skewed
  device satisfies both cases; resolving it the other way restores the exact mislabel
  this removes.
- ⚠⚠ **`null` means "not yet known", never "healthy".** §7 Q3.
- ⚠ **Recovery clears the reason** even while the skew flow still holds its last value.

**The direction→target mapping has exactly one definition**, in
`:app-reachability`'s `toUnreachableReason()`. It is a total function over three enum
values — the shape that gets copied into each app's DI and drifts, leaving two apps
naming different binaries for the same skew. This cost `:app-reachability` an
`implementation` dependency on `:core-common`; it does not reach consumers, so
`:app-notify` still has no core-common on its compile classpath.

⚠⚠ **`MutableSchemaSkewSource` exists because the wiring's failure mode is silence.** The
repository is constructed from a different Koin module than the client, in whichever
order Koin resolves them, and a wiring site that simply passed nothing would compile,
run, and report every skewed device as a transport outage — indistinguishable from the
bug being fixed. The source's flow is valid from construction and starts carrying
verdicts whenever `bindTo` is called. `bindTo` takes the *flow* rather than the
`SchemaAnnouncer` for one reason: an announcer needs a live client to construct, and a
seam only reachable in production is how wiring ships inert.

### 8.1 What is NOT wired

- **`EpcFitClient` / the other EPC clients.** EPC is a serialized transport, so skew B is
  live there; it is out of this slice, not inapplicable. ⚠ Route-1 iOS is different — the
  app *is* its own proxy, so the two vectors come from one binary and skew B is inert by
  construction. That asymmetry is §3, and it needs no per-platform code.
- **The store link.** The banner names the app to update but offers no button. §4's
  desktop problem (self-signed bundles, no store) is unresolved and is SK-4.

## 8.2 As built — SK-1b (2026-08-25)

Timeline, Maps and Contacts wired on the SK-1 recipe. ⚠ **Q1 was wrong about the size of
this**, and the correction is worth keeping: the doc estimated *"six apps × two platforms"*
of placement work. The real number is **four apps total**, because only four render a
reachability-driven shell banner at all — Fit, Timeline, Maps, Contacts (`grep
'controlReachable == false'`). Calendar and Photos own a reachability *engine* but render
no banner off it; Drive, Home and Mia have neither. So SK-1b completes the surface rather
than chipping at it, and *"which apps show this banner"* was a question worth asking
before estimating rather than after.

⚠ **The apps with an engine and no banner are a separate gap, not a leftover of this
track.** Calendar and Photos fold an unreachable Control to an empty screen with no
explanation — the exact failure (Fit, Timeline and Maps on an in-memory store, workouts vanishing) this banner exists to prevent, still live
there. That predates SK-1 and is not schema skew; it wants its own slice.

Per app the recipe was identical: `schemaSkew` onto the reachability factory or repository
constructor, `controlUnreachableReason()` on the repository interface with a `null`
default, an `api` dependency on `:app-reachability` from the `-logic` module (the interface
names the type, so `-data` and `-ui` both need it), a `MutableSchemaSkewSource` single, the
bind at the announce site, and a four-arm `when` at the banner.

⭐ **The copy stayed per-app and the mapping stayed shared**, which is the split that
matters. Direction → `UpdateTarget` has one definition; the strings are per-app because
they already were, and because *"your places can't load"* beats a generic line. The `when`
at each banner is exhaustive over an enum, so the compiler — not review — is what stops an
app from dropping a case.

⚠⚠ **Every app had the same mock-graph trap**, and each needed the same fix: the client
module resolves the skew source with `get`, not `getOrNull`, so a Koin graph missing it
fails loudly instead of silently degrading to the pre-SK-1 words. The price is that *both*
repository modules — real and mock — must bind it. `timelineRepositoryModule`,
`mockPlaceRepositoryModule` and `mockContactsRepositoryModule` are dead code today, which
is exactly why a startup crash hiding in one of them would have been found by a user rather
than by a gate.

## 8.3 As built — SK-2 + SK-3, and why they became one slice (2026-08-25)

⚠⚠ **§3.2's design was right about the mechanism and wrong about the location.** The
first SK-2 draft rejected it wholesale; that over-reach is corrected in §8.4. What
follows is the part that stands.

1. ~~**"The proxy trivially knows which binary it is."**~~ **This objection was wrong** —
   see §8.4. `loggingServiceProxyImplementationFactory` has taken `appId` + `appVersion`
   from every host all along, so the identity is an established pattern, not a new one.
2. **`SchemaFenceStatus` explicitly forbids the field**, and for *this track's own
   reason*. Its KDoc: *"This is a positional composite: appending a field shifts the
   layout and breaks a version-skewed app↔Control pair … a 'which side is behind'
   discriminator therefore goes in a NEW composite id, not as fields here."* Adding
   `owningAppId` there would have broken exactly the skewed pair the banner exists to
   serve — the readers who need it most.
3. **It owes no version bump either way.** §6 of this doc and the PR both said SK-2 was
   the phase that would owe an HR #9 bump. Wrong: `SchemaFenceStatus` is deliberately
   **not `Syncable`**, never enters a `SyncBlock`, and reshapes no stored row — its own
   KDoc says so.
4. **Route-1 has no announcer to hang it off as a fallback.** Despite
   `core-schema-evolution.md` §6.6's claim that route-1 announces too — *"one code path,
   and the frozen envelope gets exercised constantly"* — **none of the seven
   `*LocalRuntime`s call `wireSchemaAnnounce`**; only the three EPC clients do. Harmless
   for correctness (skew B is unreachable on route 1 anyway) but §6.6's stated rationale
   does not hold, and the announce beacon never fires there.

**What shipped instead:** a `ProxyHost` enum declared by each wiring site — the one place
that has always known the answer, because it chooses the transport in the same breath.
`CONTROL` for Android, desktop and iOS EPC; `THIS_APP` for route 1. No composite, no
migration, no proxy plumbing.

### 8.3.1 The finding that merged SK-2 into SK-3

⚠⚠ **A fenced device is not unreachable, so the banner could never have shown it.** The
fence gates `pullAllowed` alone (`SyncScheduler.kt`): local reads keep working, writes keep
pushing, and `ControlReachability.state` stays `true` throughout. Every shell gated its
banner on `controlReachable == false`. **That condition can never fire for skew A** — so
SK-2's signal had nowhere to land, and shipping it alone would have been inert wiring of
exactly the kind §8's `MutableSchemaSkewSource` note warns about.

Two consequences, both now built:

- `ControlReachability.reason` evaluates the fence on the **reachable** branch, not after
  the `reachable != false -> null` guard. Mutation-checking that ordering is the one test
  that matters here: restore SK-1's arm order and the fenced case silently returns `null`.
- Every shell now gates on `reason != null` rather than `state == false`. `reason` is a
  **superset** of `state`, not a view of it.

⚠ The type is still called `UnreachableReason` even though `Fenced` is not an unreachable
case. A rename would churn four apps that shipped it days earlier for no behavioural gain;
the KDoc carries the truth instead.

### 8.3.2 What is NOT wired, and the decision it needs

**Timeline, Maps and Contacts have no fence surface**, and this is not simply "more of
SK-1b". ⚠⚠ **Only Fit registers `syncServiceCommonModule` / `syncServiceClientModule`** —
the other three cannot read `schemaFence()` at all without adding the sync service to
their schema config, which **widens their announced version vector**, the very thing the
announce gate compares. That is a schema-surface change to three apps in order to read a
*device-level* signal, and it deserves a decision rather than a reflex:

- **Option A — register sync in all three.** Follows Fit's existing precedent, and Control
  hosts the service already, so there is no announce-gate skew. Cost: three more apps
  carrying a service they otherwise never call.
- **Option B — surface skew A in Control only.** Honest about it being a device fact, but
  leaves a user inside Drive with no explanation, which is the complaint that started this
  doc.
- **Option C — a narrower client read** that does not require the full sync service
  registration.

Route-1 Fit is wired (`FitLocalRuntime`), with `ProxyHost.THIS_APP` and its own fence
mirror — route 1 has its own realm epoch and can fall behind peers like any other device.

## 8.4 Correction — SK-2 rebuilt as `RuntimeIdentity` (2026-08-25)

⚠⚠ **§8.3's first objection was wrong, and the maintainer caught it.** It claimed *"the
proxy does not know which binary it is"*, generalised from grepping `core-common/proxy`
and `sync-service-proxy`. In fact `loggingServiceProxyImplementationFactory` takes
`appId` **and** `appVersion` from every host and has done all along — *"production passes
platform-resolved values (BuildConfig …)"*. Threading the same into the sync proxy follows
an existing pattern rather than inventing one, and §3.2's instinct — *the proxy stamps it,
the client reads it* — was correct.

**What actually stands from §8.3** is narrower: the identity must not be a **field on
`SchemaFenceStatus`**. That catch-22 is real and unchanged (no bump ⇒ positional garbling
on a skewed pair; bump ⇒ pull-fences the realm over a local row). ⭐ This codebase had
already reached the same conclusion twice — `SyncProxyInitialization` carries *"its own
row, because ContentTransferStatus is positional and cannot grow fields"* — so a **new
composite** is the house pattern, not a workaround.

**Built:** `RuntimeIdentity` — a single non-`Syncable` row (`appId`, `appVersion`)
published once at proxy init, since it describes the process and cannot change while the
process lives. No bump, no migration; both baselines took additions only.

Two things that would have shipped wrong:

- ⚠⚠ **"Compare the row's `appId` to my own" silently never matches.** Route 1 registers
  as `app.tenshu.fit.ios` (its `clientId`) while the same app announces as
  `app.tenshu.fit`. Consumers therefore ask `isControlHosted()`, anchored on the single
  canonical `CONTROL_APP_ID`; every other host keeps whatever id it already has.
- ⚠⚠ **Route 1 reuses Control's `buildPlatformProxyModule()` verbatim.** Hard-coding
  Control's id there would have told *every route-1 app* that Control owns its runtime —
  the exact inversion this row exists to prevent. Resolved through a `named("hostAppId")`
  override that `FirstPartyLocalRuntime.assembleBase` binds last.

`UnreachableReason.Fenced.update` is now **nullable**: no published row means the banner
reports the fence without naming an app, because guessing is wrong on one platform either
way.

### 8.4.1 Two defects the guards caught that compilation did not

⭐ Both are worth keeping, because a green compile and green unit tests covered both.

1. **The composite was unregistered.** `RuntimeIdentity` needed `composite(...)` +
   `roFactory` + trait + DTO in `syncServiceCommonModule`. Without them the proxy
   publishes the row, the relation exists, and **every client read dies as an opaque
   `error/internal`** — the §6.10 class (an unregistered client `roFactory`), which has now fired four times here.
   ⭐ **The tell was the schema-shape baseline regenerating to *no change*:** an
   unregistered composite is outside the walked service graph, so it is neither pinned
   nor serialisable. A baseline that does not move after you add a type is a *finding*.
2. **This change owed two baselines.** `SchemaShapeBaseline` went green while
   `PropertyKindBaselineEnforcementTest` was still red on an unpinned
   `tenshu/sync/service/runtimeIdentity|CHILD_ROW`. ⚠ Check the regenerated diff for
   **deletions**, not just additions — a partial regeneration silently drops entries.

## 8.5 As built — SK-4 (2026-08-25)

⚠⚠ **SK-4's premise was wrong in the same shape as SK-2's.** §4 framed the missing update
route as desktop's problem — *"Desktop has no target … Android/iOS get a button"*. Checked:

| Platform | Update route today |
|---|---|
| iOS | **None.** `TENSHU_CONTROL_APP_STORE_ID` is *"deliberately empty"* — Control is not on the App Store ([`milestones.md`](../milestones.md), ADR 0022) |
| Android | **None documented.** No Play presence; no store link anywhere in the tree |
| Desktop | **None.** Developer-ID/notarized `.app`; Homebrew is "near-term", MAS is future |

So the no-store case is **universal**, not an exception. ⚠ There is also **no
URL-opening seam at all** in common Compose code (`LocalUriHandler` appears nowhere), so a
store button would cost three platform actuals before it could point anywhere.

**Decision: no button ships.** The copy already states the fix (*"Update Control to keep
your … syncing"*) without promising a mechanism, which is the degraded form §4 asked for —
it was simply already true everywhere rather than only on desktop.

⚠ *2026-10-31:* Android now has a distribution outside Play (APKs from tenshu.app). Its update
route is "download the latest APK again" (D4 in `android-direct-apk-distribution.md`; no in-app
updater). That makes the download page a real route, so a downloaded copy's banner is the one place
this decision changes.

**Amended 2026-11-04 — the "no button ships" decision is replaced.** A skew or fence now carries an
**Update** action (`ControlBannerAction.Kind.UPDATE`, decided in `bannerActionKind`) wherever the
stale binary has a page for the copy installed here (the maintainer widened item 11 of
`android-direct-apk-distribution.md` from downloaded APKs to every store channel):

| Copy | Update opens | Configured by |
|---|---|---|
| Android, downloaded from tenshu.app | `https://tenshu.app/apps/<app>/download/android` | fixed address |
| Android, from Play | that app's Play listing | `tenshu.<app>.playUrl` (Control: `tenshu.control.playUrl`) → `ControlStoreConfig.APP_PLAY_URLS` |
| iOS | that app's App Store page | `TENSHU_APP_STORE_ID` → `TenshuAppStoreId`; Control: `TENSHU_CONTROL_APP_STORE_ID` → `TenshuControlAppStoreId` |
| Android store client, failed installer lookup, debug build; desktop | nothing, text only | — |

Empty config means no button, never a dead link. Today only Control's Play URL is set, and every
App Store id is empty. Rules, each pinned in `ui-core` `commonTest`:

- ⚠⚠ **The page is the stale binary's, not the banner's app's** (§3.1). `AppUpdatePages` carries
  this app's page and Control's, and `pageFor(UpdateTarget)` picks by the verdict. On Android most
  verdicts are `CONTROL`, so Maps' banner opens *Control's* page. On iOS route 1 a fence is usually
  `THIS_APP`, so it opens the app's own.
- ⚠⚠ **Each page follows the channel of the copy it updates.** On Android, Control's page comes from
  Control's own installer record, not this app's: a downloaded Maps beside a Play Control opens
  Control's Play listing for a Control verdict, never the website's Control (two channels).
  ⚠ That listing may have nothing newer yet if the website release is ahead of Play's rollout:
  a live page offering only *Open*, not a dead link.
- ⚠⚠ **A Play build never links the APK** (`android-direct-apk-distribution.md` item 10). The
  one exception to the rule above: a Play-installed app beside a downloaded Control gets **no**
  button for a Control verdict, only words (`androidAppUpdatePages`).
- **One channel decision.** `androidChannel` (`AndroidControlInstallRoute.kt`) is what both *Get
  Control* and *Update* read; a test pins that they agree on which copies are downloaded.
  `InstallerRecord.Unknown` is never read as downloaded: silence is not absence.
- **iOS ids use `ControlLink`'s guard** (`appStorePageFor`): digits only, greater than zero, so an
  empty or unsubstituted `$(…)` value is no button.
- **No page for `BOTH`, or for a fence no host claimed** (`Fenced(null)`). One button cannot carry
  two updates, and an unnamed binary has no page.
- **Gated on the reason, not `state`**: a fenced device is reachable and still gets the offer.
- The label is a plain *Update* in each app's catalogue (`<app>_update_action`; Drive's
  `sync_banner_needs_app_update_action`), never naming an app: the banner's words already do.
  Drive offers it on its Skew-B row (`NeedsAppUpdate`) only. Its fence row (`NeedsUpdate`) carries
  no target, so it has no page to open.

⚠ What is not wired or not seen:
- **No iOS screen renders it.** The iOS apps are SwiftUI and none draws a fence banner; the Kotlin
  iOS actual (`AppUpdatePages.ios.kt`) is a seam with no consumer yet.
- **Not device-verified.** A debug build is `DEBUG`, so neither the emulator smoke gate nor a dev
  install shows the button. It needs a release APK installed from a browser or from Play.
- Control's own home banner (§8.8) has no button, and neither do Home or Mia: they wire `Skew` but
  render no skew banner, only the `NotSetUp` / `NotInstalled` walls.

⭐ **When a route does exist, follow `ControlLink` rather than inventing a second
mechanism.** ADR 0022's pattern is exactly right for this: a build setting → Info.plist →
code, where **empty is the unconfigured signal**, an unsubstituted `$(VAR)` and a
placeholder id are both rejected, and an unset value renders an honest "not available"
state. Its KDoc has the principle worth copying: *"the only way to ship a wrong link is to
configure a wrong id."*

### 8.5.1 The half that was actionable — and a defect of my own

⚠⚠ **`RuntimeIdentity.appVersion` was publishing the literal `"dev"`.** §8.4 added that
field *"for telling a human which build they are running"* and then shipped a constant —
inert wiring of exactly the kind this doc keeps warning about, introduced by the slice that
warns about it.

It was inherited rather than invented: all three Control proxy modules passed
`appVersion = "dev"` to `loggingServiceProxyImplementationFactory`, whose own KDoc claims
*"production passes platform-resolved values (BuildConfig + os.name + os.version)"*. Desktop
resolved `osLabel` from system properties and left the version fake, which is how it
survived review. **Every error report ever filed names the same fictional build.**

Now resolved per platform: Android via `PackageManager.getPackageInfo(...).versionName`,
iOS via `CFBundleShortVersionString` (⚠ under route 1 that reads the *hosting app's*
bundle, which is correct — the app is its own runtime owner and its version is the one a
user would be asked to update), and desktop via `UNKNOWN_VERSION`, because nothing injects
a constant into the packaged bundle.

⚠ **`UNKNOWN_VERSION` over `"dev"` is the point, not a fallback.** A version nobody can act
on is worse than none: it looks like an answer. This also makes `ErrorEvent.appVersion`
real, which is the larger practical win — support threads can finally tell two builds apart.

## 8.6 The device pass, and the defect only a screen could show (2026-08-25)

Skew B verified end to end on an Android emulator: `HealthService` bumped in a **Fit-only**
build against an unmodified Control, producing `admitted=false [tenshu/health/service
announced=3 floor=1 current=2]` and the banner *"Fit is newer than Tenshu Control on this
device. Update Control to keep your health data syncing."*

⭐ It named **Control** — with Fit both on screen and the app that was bumped, so the
wrong answer was the plausible-looking one. The update glyph rendered rather than
cloud-off, and the strip sat below the status bar, so SK-3's `reason != null` condition
composed with the storage banner's status-bar inset fix instead of reproducing that collision.

`RuntimeIdentity` verified on both sides: published in Control's store
(`appId=app.tenshu.control`, `appVersion=1.0` — SK-4's real version, not `"dev"`), and read
back across IPC by Fit as `isControlHosted=true`. ⚠⚠ That read had to be probed explicitly,
because `runtimeIdentity()` folds a **failed** read and *"no row published"* to the same
`null` — an unregistered composite (the §6.10 class) would have been invisible from
the UI.

### 8.6.1 "Still trying…" — true of one cause, false of the other

⚠⚠ **The banner was right and the body directly beneath it was lying.** With the session
refused, Home rendered *"Your activity couldn't be loaded. Still trying…"* under a banner
saying *"Update Control…"*. Two contradictory claims on one screen.

`HomeUnavailableState`'s KDoc had justified having no retry button on the grounds that
*"the read is a reactive `flow()` subscription that retries itself"* — **true of a transport
outage, false of a schema refusal**, which rejects the session until the user updates and
which no amount of waiting resolves. A fenced device is not pulling at all, so it is not
"trying" either.

⭐ **No test could have caught this, and that is the point.** Each half was correct in
isolation; only the combination was a contradiction. It took rendering both on one screen.

Fixed via `waitingWillFix(reason)` — `Transport`/`null` keep the retry wording (the honest
default: absent a known schema cause the subscription really is retrying), `Skew`/`Fenced`
switch to *"Updating will fix it."* ⚠ The alternative copy deliberately does **not** name an
app or point at the banner: the banner already names it, *"above"* is fragile, and saying it
twice is how one of them goes stale.

⚠ Home and Browse were the only two instances in the tree — checked rather than assumed.

### 8.6.2 What is still unverified

**The fence evaluating on real data.** It needs a genuine peer at a higher version; the
single-device shortcut (bump → raise the epoch → downgrade) exercises the *downgrade* path
instead, which may wipe or refuse rather than fence — a different code path that would not
answer the question. Everything downstream of the fence is now proven: the target
derivation (`isControlHosted=true`), the banner, the gating condition and the insets.

⚠⚠ Reproducing it needs an **isolated relay**. The emulator's Control is baked against
`https://relay.tenshu.app` unless `local.properties` sets `tenshu.relayUrl`, so a
manufactured epoch bump on a production-registered realm would fence the maintainer's real
devices. ⚠ A locked Control does not sync, so "no relay traffic in logcat" is **not**
evidence that a device is unregistered — that inference was made and was wrong.

## 8.7 Correction — one `RuntimeIdentity` row per writer (2026-10-29)

⚠⚠ **§8.4's "a single row, published once" was wrong on iOS route 1.** The row was keyed on
the constant `"current"`, and every route-1 app runs its own proxy over **one** shared
App-Group replica, so the row named **whichever app launched last**. Open Fit, then Control:
Fit read `app.tenshu.control`, `isControlHosted()` said `true`, and the banner told the user to
update Control — the exact inversion §8.4 built the row to prevent. A single-runtime test
cannot see it: with one writer, the last writer is always the reader.

**Now:** the key is the writer's own host id (`named("hostAppId")` — Control's canonical id on
Android/desktop, the route-1 `clientId` on iOS), as `SyncCatchUpStatus` already did for the
same reason. Each runtime upserts its own row and leaves its siblings'.

- **Route-1 readers select their own row:** `syncService.runtimeIdentity(base.hostAppId)`
  (`FirstPartyLocalRuntime.Base.hostAppId`). ⚠ Never the unkeyed read there.
- **The unkeyed `runtimeIdentity()` returns the *only* row, else `null`.** Android and desktop
  have one writer (Control), so nothing changes for them. More than one row means a shared
  replica, where any one row is as likely a sibling's — so it reports the honest unknown.
- **Init removes the old single `"current"` row** (from before one row per writer, §8.7), or an upgraded Android store would hold two
  rows and the unkeyed read would turn to unknown.
- **No version bump, by HR #9's own test:** the composite's fields and their positions are
  unchanged — only the *value* of `key` moves — and the row is not `Syncable`.
  `SchemaShapeBaselineEnforcementTest` and `PropertyKindBaselineEnforcementTest` pass unregenerated.

⚠ **A side effect worth having:** the relation now enumerates the runtimes on a device, which
the singleton could not (the "barrier across all runtimes on this device" question in
`content-plane-refcount.md` §9.14). ⚠ But a row outlives its process, so presence is not
liveness — the same limit `SyncCatchUpStatus` states.

⚠ **Not closed: the shared-store stale-cache hazard.** The relation is one array cached per
process; a sibling writing from a cache that predates this runtime's row can hide it
(`SyncActivitySharedReplicaTest` records the same for catch-up). The row is written once at
init, so a hidden row stays hidden until that app relaunches, and the reader then gets `null`
— an unnamed banner, never a wrong one.

## 8.8 As built — Control surfaces its own fence (2026-10-29)

**§8.3.2's Option B, for Control itself.** On 2026-09-26 a desktop Control built 2026-09-21
ran for days fenced: relay cursor 2715 against a head of 10813, process alive, pushes going
out, zero `POST /sync/log`. Nothing said so — no Control surface read the fence, the tray read
*Synced*, and the one line naming the cause was a `println` a packaged `.app` sends nowhere.
Because reclamation waits on every device, it also stalled the realm's content reclamation.

**Built, with no schema change** (no composite touched, so no HR 9 bump):

| Surface | Reads | Behaviour |
|---|---|---|
| Control home banner (Android, desktop) | `SyncRepository.schemaFence()`, the `SchemaFenceStatus` row | *"Update Control to keep syncing"*; not dismissible; hidden while `null` |
| Desktop tray | `getSyncState` | new `SyncStateCode.NEEDS_UPDATE`, below `DIVERGENCE`, above `OFFLINE` / `SYNCING` / `SYNCED` |
| macOS notification | `getSyncState.schemaFenced` (tri-state) | the tray posts once per engagement, withdraws on clear |
| Android notification | `SyncHealthHolder.observeSchemaFence()` | once per engagement, own DEFAULT channel |
| Desktop log | the same holder flow | `FileDiagnosticSink` at `<data dir>/diagnostics/control.log` |

**One verdict, two reads.** `SchemaPullFence` now also hands each verdict *change* to
`SyncHealthHolder.onSchemaFence` — the in-process read for Control's own surfaces, which need it
synchronously (the bridge) or from a background wake (Android). Clients keep reading the
persisted row. ⚠ The holder's fence flow is deliberately **not** a field of
`SyncHealthFigures`: every figures change re-derives the `SyncHealthStatus` row and the device
status, and neither carries the fence.

Rules this keeps, each pinned by a test:

- ⚠⚠ **`null` is never a transition** (§7 Q3). A cold process reports `null` until its first
  tick; reading that as "cleared" would withdraw the notification and re-post it on every
  restart. That is why the bridge's `schemaFenced` is `Boolean?` rather than `Boolean`.
- ⚠⚠ **Once per engagement on Android needs a persisted memory.** Every `DrainSync` wake is a
  new process and a fenced device stays fenced across all of them; an in-memory "already
  alerted" would re-alert hourly. `SchemaFenceAlerts` takes the memory as a seam.
- ⚠ **`NEEDS_UPDATE` outranks `SYNCING`/`SYNCED` because a fenced device keeps pushing.** A
  backlog draining reads as Syncing and an idle device as Synced, which is exactly how the
  incident read for days.
- ⭐ **The app to name is always Control** inside Control (§3.1), so the banner needs no
  `RuntimeIdentity` read. Control-on-iOS owns its own runtime too, so the copy holds there.

### 8.8.1 What is NOT wired

- **Control-on-iOS.** Its home is SwiftUI (`HomeView.swift`) and renders none of the shared
  home banners; the fence there needs a `ControlSurface` accessor and a Swift banner.
- **Drive's banner is still Files-only** (`FilesScreen.kt`), hidden in multi-select. Desktop
  Drive users now hear of the fence from the tray and the notification, not from Drive.
- **M1's "Syncing…" state** (`content-plane-cr2-quorumless.md` B1) is separate and owes the
  HR 9 bump. When it lands, the fence must outrank it in both UIs.
- **No update button** inside Control's own home banner. §8.5's *Update* action is wired into the
  apps' banners only.
