# Cross-app open — turning a cited row into the app that owns it

**Status:** v2.4 — **§6b Q2 answered for Drive** (§5m): a target it cannot show says so in catalogue copy. ⚠⚠ It rendered `RepositoryError.toString()`, and CO-D11 had just made that string the *whole app* — a decision that removes a fallback promotes every latent defect underneath it (§5m.1). ⚠ Still open for every other receiver.
**v2.3 — CO-D11: back returns the user to the app that sent them** (§5l), ⭐⭐ device-verified 2026-09-01, which also closes §5f.3's eleven-day-old Timeline→Drive gap. A cross-app open now *replaces* the receiving app's back stack instead of pushing onto it, so back leaves rather than descending into a Home screen nobody asked for. ⚠⚠ The same pass found that a *feed row's* photo must not be a cross-app target at all — one card was opening two different apps depending on which pixel was pressed (§5l.1). ⚠⚠ And that Timeline's receiving half was never wired into either shell: it delivered perfectly and navigated nowhere (§5l.3). ⚠⚠ The stack rule makes the bottom entry poppable, which `NavDisplay` treats as a crash — it ships with `popOrLeaveApp` (§5l.2b).
**v2.2 — TT-4b: the swap is a setting the user can make** (§5k). Control's Apps screen renders an *"opens by default"* picker for every type two installed apps can show. ⚠⚠ A picker that disagrees with the resolver is worse than none — four named guards, each verified by deletion (§5k.1). ⚠⚠ `HandlerTable` is public now: one table, read by both the resolver and the settings screen (§5k.3). ⚠⚠ Nobody has flipped it on a device and watched a place open elsewhere (§5k.6).
**v2.1 — TT-4a: the user's primary-handler choice, mechanism shipped** (§5j). ⭐⭐ A choice is a *re-ordering* of `handlersFor`'s candidate list, so it degrades on a device without the chosen app instead of breaking. ⭐ No new wire surface — it is an ordinary `RealmSetting`, so `AppService` stays at v2. ⚠⚠ TT-4's other two thirds (`handles`, the evaluator check) are **Rei-blocked**: `:rei-service` does not exist (§5j.1). ⚠⚠ Nothing in the product sets the preference yet — TT-4b owes the Apps-screen swap (§5j.5).
**v2.0 — TT-3 COMPLETE on all three platforms.** iOS has both halves: Drive receives and navigates (§5i.10–§5i.11, UI-test-verified), Timeline calls (§5i.12). ⚠⚠ The Timeline→Drive hop on iOS is **not** exercised end to end — it needs a realm with backed-up photos, the same `mia-query-layer.md` §14.7 gap Android has.
**v1.8 — TT-3 iOS: the carrier, VERIFIED** (§5i.10). ⭐⭐ `OpenTargetUITests` drives the full round trip — production encoder → URL → iOS → decode — and passes repeatably. ⚠⚠⚠ The two-session blocker was a modal: iOS confirms custom-scheme opens from *another* app, and every headless attempt sat behind it (§5i.7).
**v1.5 — TT-3 iOS: the carrier, not the navigation** (§5i). The scheme is derived, the URL round-trips, `openTarget` is registered on iOS *at all* for the first time, and Drive receives. ⚠⚠ CO-D1's *enforcement* is architecturally unavailable on iOS and degrades to a convention (§5i.2). ⚠⚠ The in-app decode is **not** verified end to end (§5i.5).
**v1.4 — TT-3 desktop shipped** (§5h). ⭐⭐ The Android constraint is *Android's*: macOS has no background-activity-launch rule, so Control launches **and raises** and the caller does nothing. ⚠ Unverifiable in a dev tree — `open -b` needs a packaged bundle — and §6b's timeout question is still a copied number. iOS remains.
**v1.3 — TT-2 COMPLETE.** Five types routed across five apps; `HandlerTable` values became **ordered candidate lists**, so a place prefers Timeline and falls back to Maps (§5g). ⚠⚠ `device` is deliberately NOT routed — Home has no screen for one device, and a candidate that cannot show the row is worse than none (§5g.2).
**v1.2 — TT-2a: a second caller.** Timeline's photo tap opens Drive, `tenshu/file` joins the handler table, and `timeline-photos.md` §10.5 is closed — what it priced as *"a new cross-app navigation contract"* is one `openTarget` call now that the contract is generic (§5f). ⚠ Timeline→Drive is **not device-verified** — an empty realm has no photos (§5f.3).
**v1.1 — TT-1 COMPLETE: a Mia citation opens Calendar on the right occurrence, device-verified 2026-08-21** (§5d, §5e). §5c settled on Option B by measurement. ⚠⚠ Six defects fell out along the way, two of them load-bearing: `openTarget` was **never callable over IPC** (§5d.3), and CO-D3's *"the OS is only for cold start"* is **wrong on Android** — a warm handler navigates invisibly (§5e.1). Opened 2026-08-16 from two tracks that hit the same wall
independently: Mia's citations are inert (`mia-query-layer.md` §14.7, §15.6) and Timeline's photo
tap-through has nowhere to go (`timeline-photos.md` §10.5).
⭐ **v0.2 (same day) closes all five of v0.1's open questions** — see §6. Two of them changed the
design: **nothing is ever held** (CO-D8) and the handler key **stays per type** (CO-D10).
⭐ **v0.3 adds the wire surface** (§2b): four types, and the finding that the delivery leg needs
**no new AIDL** — `IClientInterface.callback(in byte[] request)` already carries an opaque payload
proxy→client.
⭐ **v0.5 phases the implementation** — §5b is the canonical handoff: the slices, their file maps,
what each proves, and the traps already known.
⚠ **v0.6: TT-1a is built and CO-D9 gained a fifth reason**, `NO_DELIVERY` — the honest name for a
resolved handler this build cannot reach.
⚠⚠ **v0.7: binding does NOT cold-start a client** — the check §5b opened TT-1b with, answered no.
The `Intent` payload stays; the running-app push turns out to need no new transport at all.
⚠⚠⚠ **v0.8: Control cannot perform the launch on Android** (measured — CO-D3). `startActivity`
from a background proxy is aborted *silently*, and the one run that worked was inside a
`BAL_ALLOW_FOREGROUND` grace window. The launch moves to the caller; the decision stays in Control.
⭐ **v0.9 sketches the two shapes that leaves** (§5c): a launch *instruction* the caller builds an
`Intent` from, or a `PendingIntent` it merely fires. ⚖️ Recommendation: probe the second, ship the
first if the probe fails.
⭐⭐ **v1.0: the probe passed — Option B ships** (§5d, measured 2026-08-21). The framework prints its
own warrant, **`BAL_ALLOW_VISIBLE_WINDOW [realCaller]`**, which is a *structural* property of the
caller and not the timing-based `BAL_ALLOW_FOREGROUND` that made v0.8's measurement lie.
⚠⚠⚠ **v1.0 also measures that `PendingIntent.send()` is silent in exactly the way `startActivity`
was**: with the caller not visible the start is blocked and the send still returns normally, so
"opened" is a claim no caller may make (§5d.2).
⚠⚠ **v0.4 deletes the `locator` parameter** (CO-D5, rewritten). `DataId` is a *family* —
`CompositeEntityDataId` names a multi-part key and `EntityFieldDataId` a part of a row — so the one
case that motivated a free-text field is expressible without one, and **`OpenTarget` takes exactly
one parameter**.

**Scope:** how one app asks for a row to be *shown* by whichever app owns it, on Android, desktop
and iOS, **and how the user gets back** (CO-D11). **Not** in scope: which screen the receiving app
picks for the row (that is each app's own navigation), and the third-party handler ecosystem
(`tenshu-rei.md`, §5 TT-4 here).

⚠ The back half was outside this scope until 2026-09-01, and that omission is what shipped the
defect in §5l: each receiving app pushed the target onto its own stack, which is the correct move
for a screen the *user* navigated to and the wrong one for an entry point that came from outside.
Nobody owned the difference because the doc had said it was somebody else's.

---

## 1. The shape in one paragraph

An app holding a row asks Control to **open** it: `OpenTarget(target: DataId)` — one parameter,
because `DataId` is already a family rich enough to name a row, a multi-part key or a part of a row
(CO-D5).
Control resolves the row's **type** to a handling app, checks that app is installed on this device,
and delivers — over the IPC channel it already has when the app is running, or by launching it and
delivering on connect when it is not. ⭐ **The address is the data, never the app**: which app opens
a `tenshu/calendar/event` is Control's decision today and the *user's* decision once Rei's
primary-handler concept lands, so an address that named Calendar would be a lie the moment someone
swapped their calendar app.

---

## 2. Decisions

### CO-D1 — The address is a `DataId`: not an app, and not a call

Three shapes were considered, and the differences matter more than they look.

**Rejected: `OpenTarget(OpenApp.Calendar.Event, eventId)`.** A typed enum of `(app, type)` buys
call-site safety and a closed set, both of which are real. It also **puts the app in the address**,
which is precisely the decision this whole mechanism exists to take *out* of the caller's hands.
`tenshu-rei.md` §2.2 already states that the realm has one primary handler per data type and that
*"the user can swap it for a competing file manager in Control"* — so a caller naming Calendar is
wrong as soon as that happens, and wrong in a way that compiles.

**Rejected: passing a service *call* (a chain / callstack) and letting Control route on it.** This is
the more interesting near-miss, because the instinct behind it is right — *we already have services;
the row's provenance should name the target*. What breaks is that **a call is a computation, not an
address**:

- it can return a **list**, and "which app opens these fourteen rows" has no answer;
- it can span **two services** — Mia's own place join reads `Place` rows to find `File` rows
  (`mia-query-layer.md` §12.1), so the chain's *subject* and the thing the user tapped are different
  services;
- MQ-D10 already established that a callstack is **inspectable but not legible**. Routing on one
  means Control parsing a program to guess intent, which is a strictly worse version of the problem
  the intent layer exists to avoid.

**Chosen: the row's own identity.** What the rejected idea was reaching for already exists as a
first-class platform type — `DataId` (`core/.../entity/DataId.kt`), a **wire composite carrying a
`type`**, which is how every stored row in Tenshu is already named. That gives:

- **the closed set the enum wanted**, sourced from the schema registry rather than a hand-maintained
  list — the type is a schema constant (`Event.ID = "tenshu/calendar/event"`);
- **no app in the address**, so the handler stays a resolution rather than a fact;
- **nothing new to mint.** `mia-query-layer.md` §8.1 already ruled that *"Mia never mints a routing
  vocabulary of its own"* and made `MiaHit.reference` carry the owning service's identifier. This is
  that rule one level up: the platform does not mint a second naming system for rows either.

⚠ Consequence worth stating: `MiaHit.kind` ("file", "place", "moment", …) becomes partly redundant —
it exists to pick a glyph (§15.4), and a `DataId`'s type could drive that too. Collapsing them is a
follow-on simplification, not part of this design, and it is only safe once every citation carries a
real `DataId` (see CO-D5).

### CO-D2 — Control resolves type → app, from three sources over time

| When | Source of the mapping |
|------|-----------------------|
| TT-1…TT-3 | a **compiled-in default table** in Control: type → first-party `appId` |
| always | `AppRegistration`, keyed `(appId, deviceId)`, for *"is it installed **here**"* |
| TT-4 | the app's **declared** handler set (`tenshu-rei.md`), with the user's primary-handler choice on top |

⭐ **The compiled-in table is the right starting point for the same reason MQ-D10's validator was.**
There are six types and six first-party apps, all shipped by us; building the declaration machinery
before one pair works would be inventing a protocol against a hypothesis. When Rei lands, the table
becomes the fallback and callers do not change — *the generator changes, the boundary does not*.

⚠⚠ **"Installed here" is a store read, not an OS probe.** `AppRegistration` is already keyed per
device (`control-app-registration.md`), so Control can answer without asking the platform, and gets
the same answer on all three. An OS-level "can anything handle this URL" check would be a second
source of truth that disagrees with the Apps screen.

### CO-D3 — Delivery rides the IPC channel that already exists; the OS is only for cold start

The payload does **not** need an OS carrier. A running Tenshu app is already connected to Control —
`ClientCallbackService` over Binder on Android, the UDS transport on desktop — and that channel
already carries proxy-initiated traffic (`core-client-resubscription.md`). So "show this row" is an
ordinary Tenshu message. The platform primitive is needed for exactly one case: **the app is not
running**.

| Platform | Launch (cold start) | Deliver the target |
|----------|---------------------|--------------------|
| **Android** | explicit `Intent` to the package, **carrying the target in its extras** | the launch itself. ⚠ needs `<queries>` — the trap `mia-android` shipped with once |
| **Desktop** | `NSWorkspace.openApplication(withBundleIdentifier:)` — already used by Control's tray agent, no packaging change | ⚠ **not the launch**: `fun main()` takes no arguments in either desktop app, so the payload follows over IPC (CO-D8) |
| **iOS** | ⚠⚠ **the exception** — there is no public API to launch another app by bundle id, so `openURL` with a declared scheme is the only route | the URL itself |

⚠ **An explicit Android `Intent` is safer than a URL scheme**, not merely equivalent: any app may
claim a scheme, while an explicit component cannot be intercepted.

#### ⚠⚠⚠ Measured 2026-08-16/17: **Control cannot perform the launch**

The table above says Control launches. On Android it may not, and the probe that established this
is worth reading before anyone tries again — because **the failure is silent and the first run
looked like a success**.

A probe wired a real `TargetDelivery` that called `context.startActivity(...)` from Control, and
drove it through the real chain: Mia in front, Calendar force-stopped, an utterance into Mia →
`OpenTarget` → Control.

| Run | What the log said | What happened |
|-----|-------------------|---------------|
| first | `START … from uid 10240 (app.tenshu.control.android) **(BAL_ALLOW_FOREGROUND)** result code=0` | Calendar cold-started and appeared |
| next day | `E ActivityTaskManager: **Abort background activity starts from 10240**` | nothing — and `startActivity` **returned without throwing** |

⭐ The framework names the reason itself: the first run was allowed under **`BAL_ALLOW_FOREGROUND`**,
because Control had been launched seconds earlier and was still inside its background-activity-start
grace window. Nothing about the design earned that; the test setup did.

⚠⚠ **`startActivity` does not throw when the start is refused.** A delivery that trusted its return
value would report success on every blocked launch, and the user would tap a citation and watch
nothing happen — the exact failure CO-D7 exists to prevent, arriving through the one path that
looks like it cannot fail.

**So the launch belongs to the caller.** The app the user tapped in is foreground and holds the
gesture; Control is a background proxy and holds the decision. Splitting them that way keeps CO-D1
intact — the caller still never chooses *which* app, it is told — while putting the act where
Android permits it. ⚠ Control's own shipped way of foregrounding anything is the same admission: a
notification `PendingIntent`, which works precisely because the **user** taps it.

⚠ This does not weaken CO-D2 or CO-D5: resolution, the installed-here check and the address are
unchanged. It changes *who calls `startActivity`*, and therefore what `OpenTarget` returns to a
caller that must then act — which is TT-1b's design question rather than a detail.

⭐ **Two of the three platforms let the payload ride the launch**, which is what makes CO-D8's
"hold nothing" possible without a queue.

### CO-D4 — The app identity is the `appId`; a URL scheme is an iOS transport detail

`app.tenshu.drive` is already the platform-independent identity (realm data in `AppRegistration` and
`IngestSource`), distinct from the Android package `app.tenshu.drive.android` and the macOS bundle
id. It is what the table in CO-D2 maps *to*.

⚠ iOS forces a URI, and it should be **derived mechanically** from the appId (`app.tenshu.drive` →
`tenshu-drive`) rather than authored per app, so the scheme stays a rendering of the identity rather
than a second identity. The shipped precedent is `tenshu-control://setup` (ADR 0022), which also
solved the *"nothing handles this scheme"* case by falling back to the App Store — that fallback is
reusable here.

⚠⚠ **iOS is last, and not only for the scheme.** Under route 1 every first-party iOS app runs its
**own in-process proxy** (`ios-first-party-route-1-migration.md`), so "the app is already connected
to Control" is not true in the same way, and CO-D3's cheap path does not exist there.

### CO-D5 — There is no locator: `DataId` is already a family, and one of its members fits

**Revised in v0.4 — the earlier draft was wrong.** It added a second parameter,
`locator: String?`, "opaque to Control, meaningful only to the handling app", for the one case that
does not fit a plain row id: a calendar **occurrence** is computed, not stored
(`mia-query-layer.md` §15.1), so a weekly stand-up has one row dated years ago and none at all for
tomorrow.

⚠⚠ **A protocol-level free-text field was the wrong answer, and it is worth naming why rather than
just replacing it.** It is untyped and unvalidatable; its format is a convention between a sender and
a receiver that the design otherwise insists do not know each other; and it is the second routing
vocabulary CO-D1 spends its whole argument refusing. One field's convenience against every
principle the rest of the doc holds.

**What the codebase already has.** `DataId` is not one shape — it is a family, and its members are
exactly the addressing cases:

| Variant | Addresses | Uses today |
|---------|-----------|------------|
| `StringDataId` / `LongDataId` | a row by a single-value key | 397 / 5 |
| `CompositeEntityDataId` | a row by a **multi-part** key | ⚠ 2 — registered, wire-ready, **no domain user yet** |
| `EntityFieldDataId` | a **part of** a row (`parentEntity` + `fieldRef`) | 37 |

So "which occurrence" needs no new parameter. The four candidates, compared:

| Option | Shape | Verdict |
|--------|-------|---------|
| **A. free-text `locator`** | `DataId` + `String?` | ⚠⚠ **rejected** — see above |
| **B. no sub-row address** | open the series, drop the occurrence | ⚠ rejected: the user tapped *one* Thursday. Landing them on a series that began in 2024 answers a question they did not ask |
| **C. `CompositeEntityDataId`** | `type = …/eventOccurrence`, `keyValues = [eventId, recurrenceId]` | ⭐ **chosen** |
| **D. `EntityFieldDataId`** | `parentEntity = the event`, `fieldRef = the recurrenceId` | ⚠⚠ rejected: `fieldRef` names a **field of the schema**, not an instance selector. It would be A's free text again, hidden in a slot that means something else |

⭐ **C, because the two parts stay two parts.** The alternative worth taking seriously was a plain
`StringDataId` over a composed key (`"$eventId|$recurrenceId"`), which would match the calendar's own
style — `Event`, `Calendar` and `EventOverride` are all keyed by a single string field. It was
rejected on one concrete risk: a composed key needs a separator that no `eventId` may contain, and
that is a silent-collision bug of exactly the kind positional composites already make this codebase
careful (HR #9). `CompositeEntityDataId` has no separator.

⚠ **Two honest costs.** We would be `CompositeEntityDataId`'s **first domain user** — it is
registered and has a DTO, but nothing exercises it, so TT-1 owes it a wire round-trip test. And the
occurrence type names something **no store row answers to**: `getData()` on it returns nothing, by
construction. That is not a contradiction — §15.1 already established the occurrence is computed —
but it is the first `DataId` in the system that is an *identity without a row*, and the type constant
should say so where it is declared.

⭐ **The payoff is that `OpenTarget` takes exactly one parameter.** Every future "part of a thing"
case — a PDF page, a photo inside a moment — is a `DataId` variant question rather than a new
protocol field, which is the same move CO-D1 makes one level up: do not mint a vocabulary the
platform already has.

### CO-D6 — Opening is a capability, not a courtesy

⚠⚠ An op that opens an arbitrary `DataId` is an **existence oracle**: a caller could probe which rows
exist by watching whether the open succeeds. Three controls, none exotic:

- the caller must be able to **read** the type it is asking to open — Mia already only cites rows it
  retrieved under MQ-D6 grants, so for the first caller this is free;
- `requires_user_gesture`, in the sense `tenshu-rei.md` §2.2 already defines — an app must not open
  windows in the background;
- the failure answer is **uniform** (CO-D7), so "no such row" and "no handler" are not
  distinguishable by a caller.

### CO-D7 — A refusal is visible, never a silent no-op

`timeline-photos.md` §10.5 ends on a genuinely good line: *"Tapping a photo does nothing today, which
is at least not a promise the app fails to keep."* Once a row is tappable, that protection is gone,
so every failure needs a sentence:

- the handler is not installed on this device → say so, and offer the install route (ADR 0022's App
  Store hand-off is the shipped shape on iOS);
- no app handles this type → say so; ⚠ this is a *product* gap, and it should be visible rather than
  swallowed;
- the app launched but never connected → say so rather than leaving a dead tap.

⚠⚠ **What must never happen is a tap that produces nothing and no explanation**, which is what an
`OpenTarget` that returns `Unit` and logs a warning would produce on every one of these paths.

### CO-D8 — Nothing is ever held: the payload rides the launch, or the caller retries briefly

**Decided 2026-08-16.** An earlier draft had Control keeping a *pending target* per
`(appId, deviceId)` until the launched app connected. That is not needed, and it is the kind of
state that rots:

- **Android and iOS carry the payload in the launch itself** — `Intent` extras, or the URL. The
  target is in the app's own hands before it has spoken to anyone, so there is nothing to remember.
- **Desktop cannot** (`fun main()` takes no arguments in `control-desktop` and `drive-desktop`
  alike), so the launcher **polls briefly and then gives up** — which is not a new pattern to invent:
  Control's tray agent already ships it as `raiseWindowWhenReady`, *"poll the bridge until the
  freshly-launched daemon is listening… bounded (~10s) so a failed boot can't spin forever"*, and
  then calls `openControlWindow`. Cross-app open is the same shape with a different payload.

⚠⚠ **The retry lives in the caller, never in a store.** If the bound elapses the answer is a visible
failure (CO-D7) — not a queued intent that fires later. Opening an app tomorrow to a row it was asked
about today is a bug that looks like a haunting, and the only reliable way to not have it is to keep
no state that could.

⚠ Not Android's `PendingIntent`, which is a different mechanism for a different problem — granting
another process the right to act *as you*, e.g. a notification firing later. Nothing here is
deferred and nothing acts on the user's behalf after the fact, so a plain explicit `Intent` with
extras is both sufficient and narrower.

### CO-D9 — Failure is a typed error on the existing channel

`OpenTarget` throws, using `core-error-channel.md`'s typed `TError` — no new mechanism. The reasons
are a small closed set, in the shape `SemanticSearchUnavailable` established for MQ-4 (§13.3), so a
caller can say four different sentences instead of one shrug:

| Reason | What the user is told |
|--------|-----------------------|
| `NO_HANDLER` | nothing on this platform opens this kind of thing |
| `NOT_INSTALLED_HERE` | the app that would is not on this device — with the install route |
| `LAUNCH_FAILED` | it did not come up |
| `NOT_PERMITTED` | the caller may not open this |
| `NO_DELIVERY` | ⭐ **added in TT-1a** — a handler resolved and this build has no way to reach it |

⚠⚠ **The reasons describe the handler, never the row.** `NO_HANDLER` and `NOT_INSTALLED_HERE` are
facts about apps; none of them reveals whether the `DataId` names a row that exists, which is what
keeps CO-D6's oracle shut while still being useful. A caller that is not entitled to the type gets
`NOT_PERMITTED` whether or not the row is real.

⚠⚠ **`NO_DELIVERY` was added while building TT-1a, and it is not a transitional hack.** That slice
ships the wire and the resolution and binds no launcher, so a real call resolves a handler and then
cannot reach it — and desktop and iOS legitimately sit there until TT-3. Reporting `LAUNCH_FAILED`
instead would say something *was* attempted and send a reader to debug the wrong half; returning an
`OpenOutcome` naming an app that was never told anything would be a success claim for something that
did not happen. Five reasons, and the fifth is the honest name for "not built here yet".

⚠ This is the same rule MQ-5a §16.4 drew, applied in the other direction: *throw when the user loses
a capability, return empty when only the implementation changes.* A tap that cannot be honoured is
the user losing something, so it throws.

### CO-D10 — One handler per **type**, and the key does not grow yet

⚖️ Weighed, because the alternative is what every desktop OS eventually does.

| Concern | One handler per type | Per `(type, intent)` |
|---------|----------------------|----------------------|
| **Matches** | `tenshu-rei.md` §2.2 exactly — *"exactly one primary handler per data type"* | Android `ACTION_VIEW`/`ACTION_EDIT`, macOS *Open With*, web MIME + `rel` |
| **Control UI** | one row per type in the Apps screen | a matrix; "which app opens PDFs" needs a second question |
| **Rei** | the primary-handler grant is already per type — nothing to extend | the **ambient `Picker`-class grant** would have to be reasoned about per intent, which is a security semantic, not a routing one |
| **Cost if wrong** | adding `intent` later is an **operation-schema reshape** — a service version bump and a migration (§11.7: even an optional field add is positional) | a dimension carried, declared and rendered from day one for a case we do not have |

**Decision: type only.** Every citation we have is *show me this*; there is no second verb to name,
and inventing one now means guessing which. ⚠ The deferral is **not free** and should not be sold as
free — HR #9 makes a later parameter a versioned change — but a version bump is a known, cheap,
well-trodden move on this codebase (`MiaService` has done it twice), while a wrong guess about the
second verb is carried in the manifest, the UI and the grant semantics indefinitely.

### CO-D11 — A cross-app open is an entry point, so it **replaces** the receiving app's back stack

⚠⚠ **The user's way back is part of this contract, not the receiving app's private business.** An
`openTarget` moves a person between two apps; if back does not bring them home the hop is one-way,
and no amount of correct routing makes up for it.

Every receiving app pushed the target onto its existing stack, which left its own root underneath:

| | pushed (before) | replaced (CO-D11) |
|---|---|---|
| **Drive's stack after a Timeline photo tap** | `[Home, FilePreview]` | `[FilePreview]` |
| **first back** | Drive's **Home tab** — a screen nobody asked for | finishes the activity → **Timeline, where they were** |
| **second back** | Timeline | — |

**Decision: the target is the only entry.** With one entry the nav host's back handler is disabled,
so the platform's own back takes over — on Android that finishes the activity and the system returns
the user to the calling task, which is exactly the contract Android already defines for an entry
point that comes from outside the app. No intent flags change, no task is created, and nothing about
the launch moves: it is the *receiver* that was wrong.

⚠ **The cost, stated rather than hidden:** an app that was already open loses its place in its own
history. That is a navigation position and not data, and the user had already left that app for the
one that sent them here. The alternative — push when the app is warm, replace when it is cold —
makes back mean two different things on the same screen, which is worse than either rule alone.

⚠⚠ **It ships with a second helper, and that pairing is not optional.** A single-entry stack makes
the *bottom* of the stack reachable by a back arrow for the first time, and `NavDisplay` requires a
non-empty back stack — `require(backStack.isNotEmpty())` — so an ordinary `removeAt(size - 1)` there
is a **crash**, not a no-op. `popOrLeaveApp(exitApp)` is what every back arrow and nav-host back
press now goes through: pop while there is something to pop, otherwise leave.

⚠ Spelled **once** — `enterFromAnotherApp`, `popOrLeaveApp` and `rememberAppExit` all live in
`:ui-core`, and both receivers call them. Two copies of a two-line rule is how one of them quietly
stops obeying it.

⚠ Android only, in effect: `rememberAppExit` is `Activity.finish()` there and **`null` on desktop
and iOS**, where an app cannot honestly leave itself. Those platforms keep a back arrow that does
nothing on a cross-app entry, which is the same thing they did before and is not a crash.

---

## 2b. The wire surface

Four new types, all in `:core` beside `AppService` — which is the right receiver because it already
owns app identity, `AppRegistration` and `AppGrant` (`control-app-registration.md`), and resolving
*which app shows this* is the same question one layer on.

⚠ Sketches at proposal fidelity: the KDoc is the part that matters, and the exact field order is
settled when it is written (HR #9 — a positional composite's layout is its wire contract).

### The operation

```kotlin
/**
 * Show [target] in whichever app owns its type (CO-D1).
 *
 * ⚠⚠ The address is a **`DataId`** — the row's own platform-wide identity — never an app and never
 * a call. Which app opens a `tenshu/calendar/event` is Control's resolution today and the user's
 * choice once `tenshu-rei.md` §2.2's primary handler lands, so a caller that named Calendar would
 * be wrong the moment someone swapped their calendar app.
 *
 * ⚠ Throws [OpenTargetUnavailable] rather than returning quietly: once a row is tappable, a tap
 * that produces nothing and says nothing is a broken promise (CO-D7).
 */
interface OpenTarget : Operation {

    override val schema: OperationSchema get() = Schema

    fun openTarget(target: DataId): RequestObject

    companion object {
        const val ID = "tenshu/app/openTarget"

        val Schema = operation(
            id = ID,
            receiver = AppService.Schema.toType(),
            params = {
                // ⚠⚠ One parameter, and `DataId.Schema` is the FAMILY's parent — a
                // `CompositeEntityDataId` (a calendar occurrence: `[eventId, recurrenceId]`) or an
                // `EntityFieldDataId` travels here unchanged. CO-D5 is the argument for why there
                // is no second, free-text parameter beside it.
                composite(id = Params.TARGET, type = DataId.Schema)
            },
            returnType = OpenOutcome.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val TARGET = "$ID/target"
    }
}
```

⚠ **`requires_user_gesture` is not a schema field today** — `tenshu-rei.md` §2.2 defines it as part
of the derived permission set, and CO-D6 needs it here. Whether that rides the operation schema or
the manifest is a TT-1 question, and it is the one place this design touches Rei before TT-4.

### What it returns

```kotlin
/**
 * What happened, for a caller that must be able to say something (CO-D9, Q3).
 *
 * ⚠⚠ **"Delivered" is the strongest honest claim.** Whether the user then *saw* the row is not
 * knowable from here — the app may open behind another window, or the user may switch away — so the
 * contract deliberately promises the weaker thing rather than a "shown" nobody can verify.
 *
 * ⚠ [coldStarted] is diagnostic, not decorative: a delivery that always cold-starts means the
 * handler is being killed between taps, which is a platform problem the caller cannot see otherwise.
 */
interface OpenOutcome : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The `appId` that received it — `app.tenshu.calendar`, not a package or bundle id (CO-D4). */
    val appId: TString get() = getField(Fields.APP_ID)

    val coldStarted: TBoolean get() = getField(Fields.COLD_STARTED)

    companion object {
        const val ID = "tenshu/app/openOutcome"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.APP_ID)
                boolean(Fields.COLD_STARTED)
            },
        )

        // `create(context, appId, coldStarted)` follows the house pattern — `context.create(type,
        // fields)` with the fields in schema order. Omitted here; it is not where the design lives.
    }

    object Fields {
        const val APP_ID = "$ID/appId"
        const val COLD_STARTED = "$ID/coldStarted"
    }
}
```

⚠ A composite rather than a bare `TString` for the same reason `IntentChoice` is one: a return shape
with one field today is the cheapest thing to grow, and growing a *bare value* into a composite is a
reshape at every call site.

### How it fails

```kotlin
/**
 * The open could not happen — thrown, on the existing typed-error channel
 * (`core-error-channel.md`), in the shape `SemanticSearchUnavailable` established for MQ-4.
 *
 * ⚠⚠ **Every reason describes the HANDLER, never the row.** None of them reveals whether the
 * `DataId` names something that exists, which is what keeps CO-D6's existence oracle shut while
 * still giving a caller four different sentences to say. A caller not entitled to the type gets
 * [Reasons.NOT_PERMITTED] whether or not the row is real.
 */
interface OpenTargetUnavailable : TError {

    override val schema: CompositeSchema get() = Schema

    /** One of [Reasons] — a stable token, never a sentence (the caller localises). */
    val reason: TString get() = getField(Fields.REASON)

    val message: TString get() = getField(Fields.MESSAGE)

    companion object {
        const val ID = "tenshu/result/error/openTargetUnavailable"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {
                string(Fields.REASON)
                string(Fields.MESSAGE)
            },
        )
    }

    object Reasons {
        /** Nothing on this platform opens this type at all — a product gap, and it should show. */
        const val NO_HANDLER = "noHandler"

        /** A handler exists but is not installed on THIS device (`AppRegistration`, CO-D2). */
        const val NOT_INSTALLED_HERE = "notInstalledHere"

        /** It was launched and never became reachable inside the bound (CO-D8). */
        const val LAUNCH_FAILED = "launchFailed"

        /** The caller may not open this type (CO-D6). ⚠ Says nothing about the row. */
        const val NOT_PERMITTED = "notPermitted"
    }

    object Fields {
        const val REASON = "$ID/reason"
        const val MESSAGE = "$ID/message"
    }
}
```

### What the handling app receives

```kotlin
/**
 * The payload the *receiving* app is handed — **one shape, three carriers** (CO-D3, CO-D8).
 *
 * ⭐ It rides whichever transport the situation allows, and the app parses the same composite each
 * time: the existing proxy→client callback when it is running, an `Intent` extra when Android
 * cold-starts it, the URL when iOS does.
 *
 * ⚠⚠ **No new AIDL.** `IClientInterface.callback(in byte[] request)` already carries an opaque
 * serialised payload proxy→client, so the Android delivery leg is a message inside a channel that
 * exists — the reason CO-D3's "the OS is only for cold start" holds at the transport level and not
 * merely in principle.
 *
 * ⚠ It carries an identity, never content. A handler that is not entitled to the row still cannot
 * read it; this says which row to try, not what is in it.
 */
interface OpenTargetRequest : Composite {

    override val schema: CompositeSchema get() = Schema

    val target: DataId get() = getField(Fields.TARGET)

    companion object {
        const val ID = "tenshu/app/openTargetRequest"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                composite(Fields.TARGET, type = DataId.Schema)
            },
        )
    }

    object Fields {
        const val TARGET = "$ID/target"
    }
}
```

### Deliberately absent

- **No `handles` declaration type.** TT-4's manifest section is Rei's to design, and it needs a
  concept Rei does not have yet (§5) — inventing the wire shape here would be guessing at another
  doc's decision.
- **No handler-resolution type.** The type → `appId` mapping is a compiled-in table in Control
  (CO-D2), so it is code, not wire. It becomes wire only at TT-4.
- **No pending-target row.** CO-D8: nothing is held, so there is nothing to persist.

---

## 3. Why this is not a Mia feature

Mia is the first caller, not the reason. `timeline-photos.md` §10.5 wants exactly this and deferred
it for exactly this reason (*"a new cross-app navigation contract … which is its own slice"*). Once
`OpenTarget` exists, Timeline's photo tap-through is a call site, and so is any future "open the file
this notification is about".

⚠ The alternative §10.5 weighed — *a second viewer inside the calling app* — is the thing this design
exists to avoid. It scales as `callers × types` and puts the same file behind two viewers that will
diverge.

---

## 4. Threat-model duties

Per House Rule #2, when the slice lands:

- **A new cross-app control channel.** Control gains the ability to launch and steer other apps on
  the user's behalf. The amendment should state the gesture requirement, the read-capability
  precondition (CO-D6), and that the payload is an **identity, not content** — no row data crosses,
  so a handler that is not entitled to the row still cannot read it.
- ⚠ **The existence-oracle surface** and the uniform-failure mitigation.
- ⚠ **Cold-start state**: a pending target held by Control until the app connects is a small piece of
  device-local state naming a row the user asked about — the same sensitivity class as MQ-D8's
  conversation history, and it should expire rather than persist.

---

## 5. Phasing

- **TT-1 — one pair, end to end: Mia → Calendar on Android.** `OpenTarget(DataId)` in
  Control, the type→app table with one entry, delivery over Binder, cold-start launch by package.
  Calendar because the citation already renders and an event-detail screen already exists.
  ⚠ It also forces CO-D5 immediately: the calendar citation is an occurrence.
- **TT-2 — the remaining first-party types**, and **Timeline → Drive** (`timeline-photos.md` §10.5)
  as the second caller, which is what proves the contract is not Mia-shaped.
- **TT-3 — desktop, then iOS.** Desktop is mostly free (CO-D3); iOS needs per-app schemes and does
  not get the cheap delivery path (CO-D4).
- **TT-4 — declared handlers.** `handles` joins the Rei manifest, the compiled-in table becomes the
  fallback, and Control's Apps screen grows the primary-handler swap `tenshu-rei.md` §2.2 describes.
  ⚠⚠ **This owes Rei a genuinely new concept**: §2.2's manifest is derived from a static scan of what
  an app *calls*, and a handler claim is not inferable from call sites — it is a positive declaration,
  which means an evaluator check of the form *"does the app actually route what it claims to
  handle?"*.

---

## 5b. Implementation notes

The canonical handoff section: what each slice contains, what it proves, and the traps that are
already known. ⚠ Every slice below is *inside* TT-1 except where marked — the strategic phases in §5
are the shape, these are the commits.

### TT-1a — the wire and the resolution, with no user-visible change

| Where | What |
|-------|------|
| `core/.../app/OpenTarget.kt` | the op on `AppService` (§2b) |
| `core/.../app/OpenOutcome.kt`, `OpenTargetRequest.kt` | the two composites |
| `core/.../app/OpenTargetUnavailable.kt` | the typed error, `parent = TError.Schema` |
| `core-common/.../common/module/CoreCommonModule.kt` | ⚠⚠ register **all four** — see the traps |
| `core-common/.../app/proxy/OpenTargetImplementation.kt` | resolve → check → deliver; sits beside `RegisterAppImplementation`, which is why `AppService` is the receiver |
| `core-common/.../app/proxy/HandlerTable.kt` | the compiled-in type → `appId` map (CO-D2) |
| `calendar-service-common/.../recurrence/OccurrenceId.kt` | the occurrence type constant + the `DataId` builder, next to `formatRecurrenceId` for the reason its KDoc already gives |
| `system-test-utils/.../schema-shape-baseline.txt` | regenerate — four new types, nothing reshaped, **no version bump** (§12.7's call for `PhotosNear`) |

**Proves:** the op resolves a type to an app, refuses with the right reason when there is no handler
or the app is not registered on this device, and round-trips a `CompositeEntityDataId` over the wire.
⚠ *No app is launched and nothing is delivered* — this slice is deliberately inert, which is what
makes it reviewable.

### TT-1b — Android delivery, both paths

⚠⚠ **Checked 2026-08-16, and the answer is NO — binding does not cold-start a client.** The hope
was that `AndroidClientConnection`'s `messageQueue` (buffers a `callback(...)` when the binder is
absent, drains it in `onServiceConnected`) plus `bind()`'s `Context.BIND_AUTO_CREATE` would make
cold start *launch-by-binding*, deleting the `Intent`-extra half of this slice. It does not, for a
reason that is structural rather than incidental:

> `bind()` targets the client's `ClientCallbackService`, and `BIND_AUTO_CREATE` does start the
> **process**. But `ClientCallbackService.onBind` returns `bridge?.invoke()`, and `bridge` is
> assigned in **`BinderTaskTransport`'s `init`** — which runs when the app builds its own Tenshu
> client stack, through its own entry point. A process started *by the bind* has no transport, so
> the bind returns `null` and nothing is ever delivered.

⭐ **So CO-D3's table was right and §5b's optimism was wrong**: cold start needs the app's real entry
point, and the payload rides the `Intent`. Recorded rather than quietly corrected, because "the
queue will cover it" is an attractive wrong answer that the next reader would re-derive.

⭐ **What the check did buy: the running-app path is already free.** `ClientConnection.send(data:
Data)` pushes an arbitrary composite proxy→client — no new AIDL, no new envelope, no new transport.
⚠ What it does not settle is the *client* side: dispatching an **unsolicited** composite (one that
answers no pending task and belongs to no stream) is the piece TT-1b actually has to build.

⚠ None of this contradicts CO-D8: the queue is a transport buffer tied to one connection attempt,
in memory, dying with the process — not the durable pending-target CO-D8 rejects.

| Where | What |
|-------|------|
| `core-common/.../app/proxy/` | the launcher seam — `expect`/`actual`, Android first |
| `core-common/src/androidMain/.../AppLauncher.android.kt` | explicit `Intent` to the resolved package |
| `control/src/main/AndroidManifest.xml` | ⚠⚠ `<queries>` for the target packages — Android 11+ package visibility, the trap `mia-android` shipped with once |
| `calendar-android` + `calendar-shared-ui` | receive an `OpenTargetRequest` and navigate to the event/occurrence |

**Proves:** a running Calendar navigates; a cold Calendar launches and then navigates.
⚠ Owes the **Android smoke gate** (HR #8) — it touches `androidMain` and `:control`.

### TT-1c — Mia taps, and the citation stops being inert

⚠⚠ **Revises Q5's sequencing.** Q5 said dropping `MiaHit.kind` was TT-2 cleanup. Doing it *here* is
better: `MiaHit` must reshape anyway to carry a `DataId`, and `kind` is subsumed by that id's type —
so one wire reshape, one `MiaService` bump, one migration, instead of two of each. HR #9 makes the
second bump the expensive part, not the field.

| Where | What |
|-------|------|
| `mia-service/.../MiaHit.kt` | `target: DataId` replaces `kind` + `reference`; **`MiaService` → v4** with its `SchemaMigration` step |
| `mia-service-proxy/.../AskMiaImplementation.kt` | every `hitsOf` branch builds a real `DataId` |
| `mia-shared-logic/.../TenshuConversationRepository.kt` | `Hit` carries the id it has been dropping since MQ-1 (`:122`) |
| `mia-shared-ui/.../chat/ChatScreen.kt` | rows become `clickable`; `glyphFor` switches on the **type** (§15.4's six glyphs, re-keyed) |
| `mia-shared-logic/.../ChatViewModel` | call `openTarget`; map the four `OpenTargetUnavailable` reasons to four sentences (CO-D9) |

**Proves:** the first tap-through in the platform. ⚠ **Device-verified before the PR**, which is this
track's standard since §14 — and the accessibility tree is the check that means something:
`clickable="true"` on the citation rows, where §14's pass measured `false`.

### After TT-1

- **TT-2** — the remaining first-party types, then **Timeline → Drive** (`timeline-photos.md` §10.5)
  as the second caller. ⭐ That one matters more than its size: it is what proves the contract is not
  Mia-shaped.
- **TT-3** — desktop (`NSWorkspace` + the tray's `raiseWindowWhenReady` polling shape), then iOS
  (per-app schemes; ⚠ owes `iosKotlinCheck` and `scripts/ios-smoke.sh`, and route-1 apps do not get
  CO-D3's cheap path).
- **TT-4a** ✅ — the primary-handler mechanism (§5j). A realm `RealmSetting` re-orders
  `handlersFor`'s candidates; no new op, no schema bump.
- **TT-4b** ✅ — the Apps-screen swap (§5k). ⚠⚠ Not device-verified: it needs a realm with both
  Timeline and Maps and a cited place (§5k.6).
- **TT-4** — Rei's `handles` declaration and the evaluator check. ⚠⚠ **Blocked**: `:rei-service` does
  not exist, so there is no manifest to declare into and no evaluator to check in (§5j.1).

### Traps already known

- ⚠⚠ **Register the parameter types, not just the return types.** MQ-5a lost 14 tests to
  `Schema not found` because `IntentCandidate` — a type that only ever travels *into* an op — was
  missing from its module. `OpenTargetRequest` and the `DataId` variants are the same shape of risk.
- ⚠⚠ **We are `CompositeEntityDataId`'s first domain user.** It is registered and has a DTO, and
  nothing exercises it — so TT-1a owes it an explicit wire round-trip test rather than assuming.
- ⚠ **`requires_user_gesture` is not a schema field**, and CO-D6 wants it. Decide in TT-1a whether it
  rides the operation schema or waits for Rei; it is the one place this design touches Rei early.
- ⚠ **A bumped service with no registered migration wipes the store** (HR #9) — TT-1c's `MiaService`
  bump ships its `SchemaMigration` in the same commit.
- ⚠ The gate is `scripts/run-gate.sh`, not a bare re-run: a repeated `desktopTest jvmTest test`
  executes zero test tasks and still reports success.

---

## 5c. TT-1b: two shapes for a launch the caller performs

CO-D3's measured amendment leaves one question: Control decides *who* opens the row, the caller has
to *do* it — so **what does `OpenTarget` hand back?** Both shapes below keep CO-D1 (the caller never
chooses the app) and CO-D2 (resolution and the installed-here check stay in Control). They differ in
what crosses the boundary, and the difference is not cosmetic.

⭐ **Common to both: the return becomes "delivered" *or* "you must launch this".** Control still
delivers by itself whenever the handler is running and connected (`ClientConnection.send`, no new
transport — §5b). The caller only acts when a cold start is needed, which is the one case Android
reserves for a foregrounded process.

```kotlin
interface OpenOutcome : Composite {
    val appId: TString          // who took it — unchanged
    val delivered: TBoolean     // ⭐ true = Control already handed it over; nothing for you to do
    val launch: LaunchTicket?   // present only when the caller must finish the job
}
```

### Option A — a launch *instruction* the caller builds an `Intent` from

`LaunchTicket` is ordinary data: enough for the caller to construct the launch itself.

```kotlin
interface LaunchTicket : Composite {
    /** ⚠ Platform-specific by necessity: an Android package, a macOS bundle id. */
    val launchId: TString
    /** The serialised `OpenTargetRequest`, for the caller to attach as an extra. */
    val payload: TString
}
```

- ✅ **Certain to work.** A foreground app starting another app's Activity is ordinary Android; no
  exemption is being relied on, which after CO-D3's probe is worth something.
- ✅ No new AIDL, no parcelables — it is a composite like everything else.
- ⚠⚠ **Package visibility spreads to every caller.** Android 11+ requires `<queries>` to launch
  another package, so *each app that can cite* needs an entry for *each app that can handle* — N×M
  manifest lines, maintained by hand, and `getLaunchIntentForPackage` returns **null** rather than
  failing loudly when one is missed. Today Control alone declares `<queries>`; this would put the
  list in Mia, Timeline, Drive and everything after them.
- ⚠⚠ **Control's decision becomes advisory.** The caller holds a package name and a payload, so
  nothing stops it launching something else with the same payload. ⚠ Bounded — the payload is an
  *identity*, not content (CO-D3), so the blast radius is "opened the wrong app at a row it may not
  be able to read" rather than a leak. But *"the address is the data, never the app"* stops being
  enforced and becomes a convention.
- ⚠ A platform identifier (`app.tenshu.calendar.android`) now crosses a cross-platform wire, which
  CO-D4 spent its argument keeping off it.

### Option B — a `PendingIntent` the caller fires

Control builds the `Intent`, wraps it immutable, and the caller only sends it.

```kotlin
// The composite carries a handle; the parcelable travels the Binder side-channel.
interface LaunchTicket : Composite {
    val ticketId: TString       // correlates the composite with the parcelable
}

// Caller side, Android:
pendingIntent.send()            // fired from a foreground process, with the user's gesture
```

- ⭐⭐ **The routing stays authoritative.** A `FLAG_IMMUTABLE` `PendingIntent` is opaque and
  unalterable: what the caller fires is exactly what Control built. Option A's "advisory" problem
  disappears — CO-D1 is *enforced* rather than agreed.
- ⭐⭐ **Package visibility stays in Control.** The caller never names a package, so no `<queries>`
  in any caller and nothing to maintain as handlers are added. This is the difference that scales.
- ⚠⚠ **It needs a new AIDL member.** A `PendingIntent` is a parcelable, not a composite, so it
  cannot ride `byte[]`. TT-1a deliberately avoided touching `IClientInterface`; this does not get to.
- ⚠⚠ **Unverified.** Whether a foreground process firing a background-authored `PendingIntent`
  satisfies the BAL rules is *exactly* the class of assumption CO-D3's probe just falsified. It owes
  its own probe before it is chosen — and the probe must be run twice, on different days, because a
  `BAL_ALLOW_FOREGROUND` grace window made the last one lie.
- ⚠ Android-shaped by construction. Desktop and iOS need a different ticket anyway (`NSWorkspace`;
  `openURL`), so `LaunchTicket` becomes a per-platform materialisation of one wire handle — arguably
  honest, arguably a leak of Android's model into the contract.

### Recommendation

**Probe B, ship A if the probe fails.** B is the better design on both axes that matter — the
decision stays enforced, and the visibility list stays in one place — and its two costs (an AIDL
member, an Android-shaped ticket) are the kind that stay contained. A is the safe fallback and needs
no probe at all, which is precisely why it should not be chosen *first*: it is the option whose
weaknesses (N×M manifests, advisory routing) get worse with every handler added, and they are
cheapest to avoid now.

⚠ **Whichever wins, the caller's `startActivity`/`send` result is not evidence.** CO-D3 measured
that the refusal is silent; the honest signal is the handler reporting back that it received the
request, which is the running-app path Control already has.

---

## 5d. Measured 2026-08-21: Option B works, and three things it taught

The §5c recommendation was *"probe B, ship A if the probe fails."* **B passed.** It was driven end
to end on an emulator — a realm on Control, Calendar registered and then force-stopped, Mia
foreground, `OpenTarget` over Binder — with the launch performed by Mia and authored by Control.

### 5d.1 ⭐⭐ The warrant is `BAL_ALLOW_VISIBLE_WINDOW`, which is why one run is enough

```
START u0 {act=MAIN cat=[LAUNCHER] pkg=app.tenshu.calendar.android (has extras)}
  from uid 10243 (app.tenshu.control.android)      ← the PendingIntent's CREATOR
  (realCallingUid=10249)                            ← the SENDER: Mia
  (BAL_ALLOW_VISIBLE_WINDOW [realCaller]) result code=0
Displayed app.tenshu.calendar.android/.MainActivity for user 0: +462ms
```

⭐⭐ **§5c asked for the probe to be run twice on different days, and that is no longer the right
instrument.** The reason it asked is that v0.8's success turned out to be `BAL_ALLOW_FOREGROUND` —
a *grace window*, which is exactly the kind of thing that decays between runs. Here the framework
names a different reason: **`BAL_ALLOW_VISIBLE_WINDOW [realCaller]`** — the real caller has a
visible window. That is a property of *what the caller is*, not of *when it last ran*, and it is
true by construction whenever a user taps a citation. ⚠ The general lesson is worth more than this
slice: **when a platform prints why it allowed something, read the reason rather than the outcome**
— the outcome is the same either way and only the reason distinguishes a right for a good cause
from a right about to expire.

⭐ `(has extras)` in the same line is the `OpenTargetRequest` riding the launch, so CO-D8's
"nothing is ever held" holds on Android as designed.

### 5d.2 ⚠⚠⚠ `PendingIntent.send()` is silent too — the CO-D3 trap, one layer up

The negative control fired the same ticket with the caller sent to the background first:

```
Background activity launch blocked! … realCallingUidHasVisibleActivity: false …
  balAllowedByPiSender: BSP.ALLOW_BAL; realCallerStartMode: MODE_BACKGROUND_ACTIVITY_START_ALLOWED
Abort background activity starts from 10243
START … (BAL_BLOCK) result code=102
```

…and the caller reported **success**. `send()` throws only `CanceledException`; a start the
framework aborts comes back as an ordinary return, so the *exact* failure CO-D3 measured for
`startActivity` survives into the mechanism chosen to replace it.

⚠ Consequence, and it is a contract change rather than a bug fix: **a caller may not say "opened".**
`OpenTargetResult` therefore has two successes, not one — `Delivered` (Control handed it to a
connected handler, observed) and `LaunchRequested` (a ticket was fired and nothing visibly refused
it). CO-D9 already said the honest signal is the handler reporting in; this is what makes that
sentence load-bearing instead of decorative.

⭐ Note what the log also proves: `balAllowedByPiSender: BSP.ALLOW_BAL` and
`realCallerStartMode: MODE_BACKGROUND_ACTIVITY_START_ALLOWED` — the sender's explicit opt-in *did*
apply. It failed because a backgrounded sender has no privilege to grant, not because the opt-in
was missing. The code is right; the situation was not.

### 5d.3 ⚠⚠ TT-1a's `openTarget` was never callable over IPC

`OpenTargetRo` built its request object naming the concrete return type:

```
No request object factory registered for type: tenshu/app/openOutcome
```

⚠⚠ **A `dtoFactory` is not an `roFactory`.** TT-1a registered all three composites' DTOs — carefully,
with a note about how easy the parameter type is to forget — and a DTO builds a row when one is
*read*. Naming a concrete type in `createRequestObject` demands an **RO** factory as well, which
`OpenOutcome` has not got. `AskMiaRo` carries a comment saying exactly this and passing
`Data.Schema.toType()` instead; TT-1a did the other thing.

⚠⚠⚠ **Nothing caught it, and the reason generalises.** `OpenTargetTest` drives an in-process
`TestProxy`, which never builds a request object at all — so eight green tests certified an op that
could not be called from any app. This is `inference-service.md` §17's rule (*a service ships when
it is reachable, not when its tests are green*) reaching a layer below the schema list: **an op is
reachable when a real client can compose the call**, and only a cross-process test says so.

### 5d.4 ⚠⚠ The typed refusal crosses as a value, never as a throw

`TargetOpener`'s first version caught `TenshuException`, which is what an in-process caller sees. Over
IPC a proxy-side typed throw fails the request scope and arrives as an ordinary `TResult.Failure`
carrying the error composite — so every one of CO-D9's five reasons was being collapsed into
"Control is unreachable", the one sentence that is certainly false when the proxy has just answered.

### 5d.5 ⚠ Two smaller traps, recorded because both cost time

- **A live process is not a launch.** Control's `AndroidClientConnection` rebinds with
  `BIND_AUTO_CREATE`, which **restarts the handler's process** after an `am force-stop` without any
  activity being started. `pidof` therefore reports the handler as "running" in the middle of a run
  that launched nothing — check `ActivityTaskManager`'s `Displayed` line instead. ⚠ It also means
  "cold" is harder to arrange than it looks, and that the running-vs-connected distinction the
  delivery switches on is doing real work.
- **`NOT_INSTALLED_HERE` can mean "no realm".** `OpenTargetImplementation` reads
  `syncManager.currentDeviceId`, but registration only happens when `currentOwnerId != null`
  (`appRegistrationTrigger`). On a realm-less device the id exists and the registration does not, so
  the op blames the *app* for something that is true of the *device*. ⚠ Open — it wants its own
  reason, since "Calendar is not installed" sends the user to reinstall a working app.

---

## 5e. As built — TT-1c: the citation opens the row (2026-08-21)

A Mia citation is tappable, and tapping it opens Calendar on **the occurrence the user was shown**.
Verified end to end on a device, both arrival paths, with Calendar force-stopped for the cold one.

### 5e.1 ⚠⚠⚠ CO-D3 is wrong on Android: delivering is not opening

CO-D3 says the payload rides the existing IPC channel and *"the OS is only for cold start"*. The
first half is true. The second is not, and the measurement is unambiguous:

| Calendar's state | What happened | What the user saw |
|---|---|---|
| cold (force-stopped) | ticket fired, `BAL_ALLOW_VISIBLE_WINDOW`, `Displayed … +1s549ms` | the event |
| **warm + connected** | push arrived, Calendar **navigated to the right occurrence** | ⚠⚠ **nothing — Mia stayed on top** |

⭐ **Delivering bytes and changing which app is in front are different powers, and a background
proxy has neither.** The IPC path solves the first; only the OS can do the second. So the "cheap
path" was the one that failed the user — a tap that silently succeeded, which is CO-D7's forbidden
outcome arriving through the arm nobody suspected because it *worked*.

**The fix: mint the ticket either way.** A warm handler still gets the push — so it has navigated
before it is ever shown, and the payload never depends on the OS parsing an extra — and the caller
still fires a ticket to raise it. ⚠ The same `ACTION_MAIN` intent **resumes** the existing task
rather than starting a second one (measured: one `START`, no new task), and the extra arrives at
`onNewIntent`, which is why the receiving side reads it in both places. What the warm path saves is
the **cold start**, not the launch.

### 5e.2 The receiving half, and what it reused

⭐ **Calendar grew almost no resolution logic.** `OpenTarget` addresses an occurrence as
`(eventId, recurrenceId)` — the same pair a *reminder tap* already carries — so the whole route is
`OccurrenceId.parse` → the shipped `ReminderDeepLink` → `resolveReminderLink` → the editor. Two
features needed "find the occurrence these two ids name" and CO-D5 is why they could share it.

⚠ Both carriers converge on one `OpenTargetInbox` (replay = 1, `IcsOpenRelay`'s shape and for its
reason): the cold-start target is read before `setContent` runs, so a nullable parameter would drop
exactly the case the mechanism exists for.

⚠⚠ **The client-side dispatch of an *unsolicited* composite had to be built.** Nine apps each
carried `if (element is StreamElement<*>) dispatch(it)` inline, which silently drops anything else —
a cross-app open answers no pending task and belongs to no stream, so it fell through all nine
without a trace. `wireClientPushDispatch` is now the one place that decides.

### 5e.3 ⚠⚠ Both of its parameters are providers, and a DI cycle is why

`wireClientPushDispatch(streams = get(), …)` crashed Calendar at launch with a `StackOverflowError`
thousands of frames deep inside Koin: `StreamElementDispatcher` is built **from** the executor being
constructed. The nine inline copies were safe only because their `get()` sat *inside* the handler
lambda, where it runs long after the graph is built — so taking instances quietly moved that call to
construction time for every adopter. ⚠ Unit tests were green throughout; the **Android smoke gate**
caught it, which is precisely the class of regression HR #8 exists for.

### 5e.4 `MiaHit.target` replaces `kind` + `reference`

⭐ A `DataId` carries a type *and* a key, so it does both fields' jobs in the vocabulary the platform
already uses for every stored row. MQ-2's rule was that *"Mia never mints a routing vocabulary of its
own"*; a bare `reference` string was that rule half-kept, since only its pairing with `kind` made it
resolvable and nothing enforced the pairing. `MiaService` → **v4**, one bump for both changes.

⭐ The calendar citation now carries the **occurrence**, closing `mia-query-layer.md` §15.6: it used
to carry the bare `eventId`, so a tap on "dentist tomorrow" would have opened the series, whose
stored row is dated whenever it was first created.

⚠ The chat cell's glyph switches on `target.type` — as **literals** in `mia-shared-ui`, not the
owning services' `ID` constants. MQ-D2 keeps Mia's client free of feature-service deps (they resolve
today only by transitive leak, which is worse than a literal: an undeclared dependency). Duplicating
a type id for *presentation* is `core-realm-data.md`'s sanctioned shape, and `HandlerTable` is the
same duplication one layer down.

⚠⚠ **Only the occurrence type is routed.** Tapping a file, place, moment, workout or device citation
gets `NO_HANDLER` and says so — five of the six glyphs lead somewhere honest but closed until TT-2.

### 5e.5 ⚠⚠ The typed refusal is a value, and the sentence is the caller's

`TargetOpener` first caught `TenshuException` — what an *in-process* caller sees. Over IPC a
proxy-side typed throw arrives as an ordinary `TResult.Failure`, so all five CO-D9 reasons were
collapsing into "Control is unreachable", the one sentence that is certainly false when the proxy
has just answered. ⚠ And the sentence comes from the **reason token**, never from
`OpenTargetUnavailable.message` — that is a developer string naming an `appId` the user has never
seen.

⚠ `ChatOpenHitTest` pins that the five reasons produce five *distinct* sentences, that a success
says nothing, and that an unknown reason still says something. Each guard was run against broken
code and watched to fail first — §14.9's standing rule.

### 5e.6 What is proven, and what is not

✅ Cold: citation tapped → `BAL_ALLOW_VISIBLE_WINDOW [realCaller]` → Calendar displayed on the right
occurrence. ✅ Warm: pushed over IPC, raised by ticket, one task. ✅ `clickable="true"` in the
accessibility tree, where §14.7 measured `false`. ✅ Full gate, `iosKotlinCheck`, Android smoke all
green.

⚠ **Not** verified: any citation other than a calendar occurrence (nothing else is routed yet), and
desktop/iOS (TT-3). ⚠ The realm was seeded through Mia's own `CreateEvent`, so this pass still says
nothing about a realm with real Drive/Timeline content — `mia-query-layer.md` §14.7's gap is
narrowed, not closed.

### 5e.7 ⚠ Two environment traps, recorded because both cost a run

- **`run-gate.sh` printed "all green" while a module failed to COMPILE.** Its tests simply never
  ran, and "no red results" reads as success — only the **exit code** carried the failure. Same
  family as the known "a re-run executes zero tests and reports BUILD SUCCESSFUL".
  ✅ **Already fixed on master the same day** (`tooling(gate)`): the gate keeps a ledger of
  which modules produced results and fails when one that had them produces none. ⚠ Worth knowing
  what that does *not* cover — a module whose tests have **never** run in this checkout is not in
  the ledger, so a first run after a fresh clone still cannot detect the loss. Read the exit code
  regardless.
- **The smoke gate's `FAIL: … is not running` can be `LOW_MEMORY`.** A 2 GB AVD running nine Tenshu
  apps kills one, and the gate cannot tell that from a crash. ⚠ Discriminator:
  `adb shell dumpsys activity exit-info <pkg>` — `reason=3 (LOW_MEMORY)` is not your change. It
  passed on a freshly booted emulator with the same build.

---

## 5f. As built — TT-2a: the second caller (2026-08-21)

TT-2's stated point is that *"Timeline → Drive proves the contract is not Mia-shaped."* This is that
half. `tenshu/file` joins `HandlerTable`, Drive receives, and Timeline calls.

### 5f.1 ⭐⭐ `timeline-photos.md` §10.5 costed four things; three already existed

§10.5 deferred the photo tap-through because it *"means a new cross-app navigation contract — an
intent filter in Drive, a launcher in Timeline, a path→viewer route, and a decision about what
happens when Drive is not installed — which is its own slice, not a line of this one."*

| §10.5 said it needed | What TT-1 left behind |
|---|---|
| an intent filter in Drive | **none needed** — the component is explicit (CO-D3) |
| a launcher in Timeline | Control mints it; Timeline fires an opaque ticket (§5c) |
| a path→viewer route | `Destination.FilePreview(path)` was already there |
| a not-installed decision | `NOT_INSTALLED_HERE`, with a sentence (CO-D9) |

⭐ So §10.5's two options — *a real deep link into Drive* versus *a second viewer inside Timeline* —
stop being a trade-off. The first one costs one call **and Timeline gains no viewer, names no app,
and declares no `<queries>`**. That is the payoff CO-D1 was arguing for, collected by a caller that
did not exist when the argument was made.

### 5f.2 ⚠⚠ A line in the table is a claim about two codebases

Adding a type to `HandlerTable` without a route in the receiving app is **worse than leaving it
out**: an absent type refuses with `NO_HANDLER`, which every caller turns into a sentence; a present
one launches an app that then shows the user nothing at all — a tap that "worked" and did nothing,
which is §5e.1's failure in a new place.

`HandlerTableTest` therefore pins **both halves**: the two routed types, *and* the four that are
deliberately absent (place, moment, workout, device — all citable and tappable today, all correctly
refusing until TT-2b builds their routes). ⚠ Its second test is a list that must shrink as the table
grows; a stale entry there is the only way this guard can rot.

### 5f.3 What is proven, and the one thing that is not

✅ Full gate, `iosKotlinCheck`, **Android smoke PASSED** — the last of those is not a formality: it
is what caught TT-1c's DI cycle, and Drive now carries the same `wireClientPushDispatch` wiring
(providers, not instances). ✅ `OpenTargetRoutingTest` pins that Drive ignores a target of another
type rather than opening its viewer on someone else's key — run against broken code and watched to
fail.

⚠⚠ **Timeline → Drive has NOT been exercised on a device.** A Timeline photo is a *backed-up Drive
file*, so the tap needs a realm with content flowing through the backup pipeline, and an emulator
realm has none — `mia-query-layer.md` §14.7's standing gap, reached from a new direction. What is
verified is that the apps launch, the wiring does not cycle, and the routing decision is correct;
what is not is the tap itself. ⚠ Mia's **file** citations became tappable as a side effect of the
table line, with no caller-side change at all — also unverified, and for the same reason.

### 5f.4 Still open — TT-2b

The remaining four types need a receiving route each: Timeline (moment), Fit (workout), Home
(device), and **⚖️ place, which has no obvious owner** — Timeline shows places and so does Maps, and
CO-D2's table takes one app per type. That is a product decision, not a routing one, and it is the
first time this design has hit a type two first-party apps both claim.

---

## 5g. As built — TT-2b: the remaining types, and a table that got a shape (2026-08-21)

### 5g.1 ⭐⭐ CO-D2's table now holds an ordered list, because two apps can be right

A `Place` is the first type two first-party apps both claim: **Timeline** answers *"what happened
here"* — visits, photos, the Place spine — and **Maps** answers *"where is it"*. Both are correct,
and CO-D2's one-app-per-type table had no way to say so.

⭐ The fix is smaller than choosing a winner: `handlersFor(type)` returns a **preference-ordered
list**, and the op takes the first candidate actually registered on this device. A user with Maps
and no Timeline still gets their place opened, instead of being told nothing here can show it.

⭐⭐ **It is also the shape TT-4 needs anyway.** CO-D2 already says this table becomes the *fallback*
under an app's declared `handles` set and the user's primary-handler choice — and a "choice" is
precisely a re-ordering of candidates. One id would have had to become a list eventually; the place
question just arrived first.

⚠ **The order is the table's, never the store's.** Iterating registrations instead would make the
handler depend on which app happened to register first, which is not a preference anyone expressed.
⚠ `Removed` is skipped, not chosen: a tombstone is not an absence (§5.1), so a fallback that only
asked *"is there a row"* would open an app the user deliberately removed in preference to one they
kept.

### 5g.2 ⚠⚠ `device` is not routed, and that is the rule being obeyed rather than an omission

§5f.2 says a line in the table without a receiving route is worse than no line. Home has no screen
that shows **one device** — its routes are Home, Automations, Activity, Settings and the automation
editor — so `tenshu/home/device/homeDevice` stays out, and a device citation refuses honestly with
`NO_HANDLER`. ⚠ `HandlerTableTest` asserts the *absence* by name, with instructions to delete that
test when Home grows the route. The rule cost a type on its first application, which is roughly the
evidence that it is a real rule.

**Routed after TT-2:** occurrence → Calendar, file → Drive, place → **Timeline, then Maps**,
moment → Timeline, workout → Fit.

### 5g.3 ⭐ Timeline is now on both sides of the contract

It opens Drive for a photo (§5f) and is opened for a place or a moment. That is the clearest
evidence available that the design is not caller-shaped — the same app, with no special casing,
plays both roles through the same two seams.

⚠ Timeline is also the first receiver handling **two** types, which makes the type-checks
load-bearing: a `placeId` and a `momentId` are both opaque strings, so a router reading the key
without checking the type would open the wrong screen on a plausible id. Its test asserts the same
key resolving to two different screens under two types.

### 5g.4 ⚠⚠⚠ The receiving hook must not resolve the client graph on an ordinary launch

The smoke gate caught a **new typed IPC failure** the moment Fit gained a receiving route:

```
tenshu/health/service/workouts → … → tenshu/result/error/unannouncedSession
  ("session has no admitted schema announce — call tenshu/app/service/announceSession first")
```

⚠⚠ The cause was an argument evaluated too eagerly: `targetFrom(intent, getKoin().get())` resolves
`BinaryParser` — and with it the client graph — on **every** launch, including the overwhelmingly
common one carrying no extra at all. In Fit that pulled the graph up ahead of `wireSchemaAnnounce`'s
preflight, and the Home screen's first workouts read went out unannounced.

⭐ `parser` is now a **provider**, so an ordinary launch resolves nothing. ⚠ The general form is
worth keeping: **a hook that runs on every launch must cost nothing on the path where it does
nothing** — and "nothing" includes touching DI. Only Fit surfaced it, because Fit's first screen
issues a read immediately; the same eagerness was sitting in four other apps.

⚠ Worth noting how it was found: the gate's own line is *"WARN: typed IPC failure(s) — a legitimate
channel, but confirm they are expected"*, and it does **not** fail the run. Diffing it against the
previous run's log is what turned a warning into a defect.

### 5g.5 What is proven, and what is not

✅ Full gate (91 tasks, 1154 classes), `iosKotlinCheck`, **Android smoke PASSED with zero typed IPC
failures**. ✅ Routers pinned per app, including the two-type discrimination and the
fallback order — the order guard run against an inverted preference and watched to fail.

⚠⚠ **No tap-through beyond TT-1's calendar pair has been exercised on a device.** Every new route
needs a realm with content — places, moments, workouts — which an emulator realm does not have
(`mia-query-layer.md` §14.7). ⚠⚠ **Fit is a smoke-gate canary** (HR #8): the gate installs it but
never rebuilds it, so its route is not exercised even structurally. Its assertions and a
maintainer's own device are the only things standing behind it.

---

## 5h. As built — TT-3 desktop: the platform where CO-D3 was right all along (2026-08-21)

### 5h.1 ⭐⭐ "Control may neither launch nor raise" is an ANDROID sentence

§5e.1 is the most quoted finding in this doc, and it is worth being precise about its scope. On
macOS there is **no background-activity-launch rule**, the Control daemon is unsandboxed, and
LaunchServices' `open -b` both launches an app and *activates* it. So desktop keeps CO-D3's original
shape exactly: Control does everything, the caller does nothing, and `launchTicketId` is always
empty.

⭐ Consequence worth stating plainly: **`LaunchPerformer` is unreachable on desktop by
construction.** Its `{ false }` binding is not a stub waiting for TT-3 to fill in — TT-3 is what
established it will never be called. It stays as a defensive no-op so a future ticket-bearing
outcome cannot become a silent tap.

⚠⚠ **What does NOT change is that raising is a separate act from delivering.** A connected desktop
handler is pushed the request *and* asked to come forward; the push alone would leave the user
looking at the app they tapped in — §5e.1's defect, on the platform where avoiding it is trivial.
The platforms differ in **who may raise**, never in whether raising is needed.

### 5h.2 The cold path is the one place Control waits

CO-D3 already said the payload cannot ride a desktop launch — `fun main()` takes no arguments in
either desktop app — so a cold open is *launch, wait for the app to connect over the socket, then
push*. That wait is bounded and giving up is a real outcome (`LAUNCH_FAILED` and a sentence).

⚠ **This is not CO-D8's rejected pending-target.** Nothing is stored, nothing outlives the call, and
if the caller goes away the wait goes with it — a bounded wait *inside one request*, not a queue
that survives it.

### 5h.3 ⚠⚠ §6b's first open question is still open, and the number is still a copy

*"How long may a launch take before the caller gives up?"* — 10 s, taken from the tray's
`raiseWindowWhenReady`, and it is **a precedent rather than a measurement**, exactly as §6b warns. A
cold JVM app with a Compose window to build is not a daemon; 10 s could be generous or short.

⚠ Measuring it needs a **packaged** bundle: `open -b` finds nothing in a dev tree, so every desktop
path here is unexercised on this machine. The number ships with its provenance stated rather than
dressed up as evidence.

### 5h.4 What is proven

✅ `DesktopTargetDeliveryTest` pins the four paths — connected ⇒ pushed **and raised** (run against
a delivery with the raise removed and watched to fail), cold ⇒ launched then pushed on connect, a
failed launch reported rather than waited out, and an app that never connects giving up. ✅ All five
desktop shells compile and collect. ✅ Full gate green.

⚠⚠ **Nothing desktop-side has been run.** No packaged bundle, therefore no launch, therefore no
end-to-end path. This slice is code and unit-level evidence only.

---

## 5i. As built — TT-3 iOS: the carrier (2026-08-22)

### 5i.1 ⚠⚠ The URL is not one carrier among several — it is the only one

Android has two (an existing IPC channel plus an `Intent` extra) and desktop has one plus a launch.
On iOS, route 1 means **every first-party app runs its own in-process proxy**, so the proxy that
resolves the handler is the *caller's own* and it has no channel to any other app. CO-D3's cheap
path does not exist; there is a URL, and the app must open it.

⭐ The scheme is **derived, never authored** (CO-D4): `app.tenshu.drive` → `tenshu-drive`, in
`OpenTargetUrl.schemeFor`. ⚠ The payload is the **same serialised `OpenTargetRequest`** the Android
extra carries, base64url encoded — reusing the wire shape rather than inventing a query encoding is
what keeps a `CompositeEntityDataId` (an occurrence's `(eventId, recurrenceId)`) surviving the trip.
Its round-trip is pinned, because on iOS a variant that does not survive the encoding is simply a
type that cannot be opened.

### 5i.2 ⚠⚠⚠ CO-D1's *enforcement* is unavailable on iOS, and saying so beats pretending

On Android the ticket is a `FLAG_IMMUTABLE` `PendingIntent`: opaque, so what the caller fires is
exactly what Control built, and *"the address is the data, never the app"* is an enforced property.
On iOS the "ticket" is a URL string handed back **inside the caller's own process** — it can read
it, and therefore learns which app was resolved. Wrapping it in a handle would be theatre: the map
would live in the same process as the code it was hiding from.

**So on iOS CO-D1 survives as a convention, not as an enforced property.** Everything else does
survive: resolution, the installed-here check, the five typed refusals, and the `DataId` as the only
address any caller names.

### 5i.3 ⚠⚠ `openTarget` was not registered on iOS at all

`control-shared-proxy`'s iOS `PlatformProxyModule` never called
`appOpenTargetImplementationFactory`, so the op resolved to no implementation and failed as
`Could not handle protocol` — a long way from the cause. Registering it is `inference-service.md`
§17's rule again: **a service ships when it is reachable**, not when its tests are green.

### 5i.4 ⚠⚠⚠ The launch URL arrives before the runtime exists — the common case was the broken one

Measured on a simulator, not reasoned about. A cross-app open arrives as a **launch** URL, which is
exactly when the route-1 runtime is *least* likely to be installed: Drive's shipping shell renders
`ConnectToControlView` and installs nothing until it finds a Control session. The first version
returned null whenever the graph was absent — so it failed on every cold open and would have worked
only on the warm one nobody was testing.

⭐ `PendingOpenUrl` holds the raw URL and `install()` drains it: the iOS shape of `OpenTargetInbox`'s
`replay = 1`, which the other platforms already needed for the identical ordering problem. ⚠ One
slot, last-wins, never durable — CO-D8 holding.

⚠⚠ **A second lesson from the same debugging session**: the nil return made *"not one of ours"* and
*"ours, but we could not take it"* the same observable event. A receiving hook whose failure is
silent is the shape this track keeps rediscovering, so URLs that *look* like ours now log which nil
they are — while an unrelated app's `openURL` still costs nothing.

### 5i.5 What is proven, and what is not

✅ The derived scheme reaches the **built `Info.plist`** (read out of the app bundle, not assumed) —
and `DeclaredIosSchemesTest` pins every routed app's declaration against `HandlerTable`.
✅ `OpenTargetUrlTest` round-trips a simple id, a multi-part occurrence, and rejects five shapes of
junk. ✅ `iosKotlinCheck` green; **`ios-smoke.sh --build-only` PASSES**.

⭐ `.onOpenURL` is a **View** modifier, not a Scene one. Attaching it to `WindowGroup` type-checks
nowhere in the Kotlin gate and fails only in the Swift build — why `ios-smoke.sh` is the only real
iOS check.

⭐⭐ **The decode is VERIFIED** — see §5i.10. §5i.7 is why it took two sessions.

### 5i.7 ⚠⚠⚠ iOS asks the user first — every verification attempt sat behind an unconfirmed modal

**Root cause, found 2026-08-22 by screenshotting the simulator instead of reading logs.**
`xcrun simctl openurl <sim> tenshu-drive://open?…` raises a SpringBoard alert —

> **Open in "Drive"?**    Cancel   Open

— and **waits**. It appears from the home screen with nothing else frontmost, so it is not a
Safari-referral artefact: iOS confirms custom-scheme opens, and `simctl openurl` does not bypass
that. Nothing is delivered until somebody taps **Open**.

⚠⚠ **This explains every symptom, and retracts an earlier conclusion in this section.** A previous
revision listed seven eliminated causes and concluded *"the handler is never invoked"*. The handler
was never invoked because **the open was never confirmed** — not because the wiring was wrong. In
particular:

| Symptom | Actually |
|---|---|
| no receipt, no log, ever | the open never happened |
| a URL launch "dies" | it never launched; the alert was dismissed by the next command |
| `willConnectTo urlContexts=0` | the app was *activated*, without a URL payload |
| Safari control "worked" | `https` needs no confirmation — the control did not test the same thing |

⭐ **The lesson is about the instrument, not the feature.** Two sessions of log-reading could not see
a modal dialog, because a dialog is not an event. One screenshot showed it immediately. ⚠ When a
mechanism produces *no* signal at all — not a wrong signal, none — look at the screen before adding
another log line.

### 5i.8 How to verify it (the harness, as it now stands)

Everything below works; only the final tap is not automated here.

```bash
# 1. build + install (DEV_HARNESS gives you the receipt)
cd drive-ios && xcodegen generate
xcodebuild -project DriveIos.xcodeproj -scheme DriveIos-Dev -configuration DebugHarness \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath ../build/ios-sim/H ENABLE_DEBUG_DYLIB=NO build
xcrun simctl install <sim> <path>/DriveIos.app

# 2. prove the instrument before trusting its silence
xcrun simctl launch <sim> app.tenshu.drive.ios.smoke -openTargetSelfTest
cat "$(xcrun simctl get_app_container <sim> app.tenshu.drive.ios.smoke data)/Documents/tenshu-open-target.txt"
#   → selftest ok

# 3. fire, then TAP "Open" in the simulator window
xcrun simctl openurl <sim> "tenshu-drive://open?t=<base64url>"
#   → a receipt line `saw decoded=true …` proves the handler ran and the payload decoded
```

⚠ **Generating the URL**: `OpenTargetUrl.encode(appId, request, serializer)` — the same call the
delivery makes. A throwaway `desktopTest` that writes it to a file is the cheapest way.

⚠⚠ **Automating the tap** needs one of: Accessibility permission for `osascript` (a machine-level
grant, so a maintainer's decision), `idb`, or — the sanctioned route and the one worth building —
an **XCUITest** in the existing `DriveIosUITests` target, which can tap
`XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Open"]` with no extra
permissions. ⚠ Whether a signed device build prompts at all is **untested**, and if it does not, the
device pass is simpler than the simulator one.

### 5i.9 What the scene delegate is for

`OpenUrlSceneDelegate` handles **both** `scene(_:willConnectTo:options:)` and
`scene(_:openURLContexts:)`, and both are needed regardless of the alert: a URL that *launches* the
app arrives once, in `connectionOptions`, and `openURLContexts` is never called for it; a URL to an
already-running app arrives only in `openURLContexts`. ⚠ It was added while chasing the alert, so
whether SwiftUI's `.onOpenURL` alone would have sufficed is **not established** — the scene
callbacks are the documented path and are kept for that reason, not because `.onOpenURL` was proven
inadequate.

### 5i.10 ⭐⭐ Verified — `OpenTargetUITests` (2026-08-22)

The round trip runs end to end and passes repeatably (3 consecutive green runs): the app builds a
URL with the **production encoder**, opens it, iOS delivers it, and the app decodes it back to the
row it named — asserted through the accessibility tree, which is the only surface a UI test can read.

⭐ **Two tests, one fact each.** `test_aCrossAppOpenReachesTheAppAndDecodes` proves the decode;
`test_withNoOpenTheReadoutSaysNone` proves the readout is mounted and says `none` when nothing
opened — without which the first could pass vacuously against a stale value. ⚠ They cannot be one
test: the probe fires seconds after launch and races a same-run sentinel read, which is exactly how
the first version of this test failed.

⭐ **The probe self-opens**, and that is a finding rather than a shortcut: **an app opening its own
scheme is not prompted**, while an external opener is. So the automated path needs no tap.
`confirmOpenAlertIfPresent` remains for the externally-opened case and taps only if an alert
appears — a test that *required* one would be asserting a fact about the opener, not the feature.

⚠⚠ **A stale pending open survives across runs.** An unconfirmed `simctl openurl` stays queued and
is delivered on a *later* launch, so the readout shows a target nobody in that run asked for and the
failure reads like a wrong decode. It survived uninstall/reinstall; `simctl shutdown` + `boot`
cleared it. ⚠ This is the simulator-hygiene hazard `tech-debt.md` already records for Drive's
XCUITests, in a new form.

⚠ §5i.11 adds the navigation, and §5i.12 the caller half, so iOS now has both.

### 5i.11 ⭐ The navigation — a cross-app open opens the file (2026-08-22)

`OpenTargetRouter` carries the decoded target from the app root to `PairedAppView`, which pushes a
`FileRoute` onto the Files stack.

⚠⚠ **A holder rather than a direct call, because the two ends are not both present.** The URL
arrives on the app — possibly at launch — while the navigation path lives inside `PairedAppView`,
which does not exist until a Control session is found. An open to an unprovisioned Drive waits
rather than being dropped. ⚠ Not durable and must not become so: one slot, last-wins, process
lifetime, exactly like the request it carries (CO-D8).

⚠⚠ **Both `task` and `onChange`, and neither is redundant**: `task` catches an open that was already
waiting when the view appeared (the cold-launch case, where the URL beat the Control session),
`onChange` catches one arriving while Drive is on screen. Wiring only one loses a whole class of
open, silently.

⚠ It **pushes** rather than resetting the stack — a user deep in a folder keeps their place and Back
returns them there; an *open* that destroyed the current context would be a worse answer than the
one it replaced. It switches to the Files tab first, because a push onto a stack the user cannot see
is the same silent no-op this whole half exists to avoid.

⚠ Drive drops a target of any other type rather than guessing. The type literal duplicates `File.ID`
for the reason `HandlerTable` documents — iOS builds against `drive-shared-logic` and cannot reach
`drive-shared-ui`'s Kotlin copy of the rule.

⭐⭐ **Three UI tests, three failure modes.** `…NavigatesToTheFile` asserts the *navigation title*,
not file contents — the probe names a path with no bytes behind it, and where the user landed is the
fact under test. Proven independent by suppressing the push: the navigation test went red while the
decode and sentinel tests stayed green.

### 5i.12 ⭐ The caller half — Timeline opens a photo in Drive (2026-08-23)

⚠⚠ **Mia was the obvious candidate and is the wrong one.** `MiaSurface` binds
`MockConversationRepository` and there is **no `MiaLocalRuntime`** — Mia-on-iOS has no client stack
at all, and the mock produces no citations. Wiring an opener there would have been a caller with
nothing to call with and nothing to cite. ⭐ Timeline is the right one on both counts: it has a
route-1 runtime, and Timeline→Drive is already TT-2a's established second caller — *"a contract
exercised only by Mia is a contract shaped like Mia"*, on the platform where that risk is highest.

⚠⚠ **The launch is Swift's, because only Swift can do it.** An open is
`UIApplication.shared.open(url)`; there is no Kotlin/Native equivalent and — under route 1 — no
Control process to hand it to. `IosOpenTargetCaller.installLauncher` takes a plain
`(String) -> Boolean` rather than a Swift-authored `LaunchPerformer`: that interface's member is
`suspend`, and asking the bridge for a Swift suspend implementation buys nothing when the work is
one non-suspending UIKit call.

⚠ Installed at launch **unconditionally**, because a tap can precede provisioning; a missing
launcher would make that tap a silent no-op instead of an honest refusal. An unprovisioned Timeline
answers `"Timeline isn't connected to Control yet."`

⭐ Timeline still names **no app**: it hands Control a `DataId` and Control resolves. It declares no
`LSApplicationQueriesSchemes`, links no viewer, and knows nothing about Drive — the same property
TT-2a established on Android, now on the platform where CO-D1 is only a convention (§5i.2).

⚠ Every refusal gets a sentence from the reason **token**, never the proxy's developer message —
that names an `appId` the user has never seen — and no sentence names an app.

⚠⚠ **What is NOT verified: the hop itself.** A Timeline photo is a backed-up Drive file, so tapping
one needs a realm with content flowing through the backup pipeline; an emulator or simulator realm
has none. That is `mia-query-layer.md` §14.7 reached from the iOS side, and it is the same gap that
leaves Android's Timeline→Drive unexercised. What *is* verified: both apps compile, Drive's
receiving side passes its UI tests, and the caller refuses honestly with no runtime installed.

---

## 5j. As built — TT-4a: the user's primary handler, the mechanism (2026-08-23)

TT-4 is three things (§5b): Rei's `handles` declaration, the **user-swappable primary handler**, and
the evaluator check that an app routes what it claims. Two of the three are Rei-blocked; this slice
ships the third, which was never blocked on anything.

### 5j.1 ⚠⚠ Rei does not exist, so two thirds of TT-4 cannot be built honestly

There is no `:rei-service` in `settings.gradle.kts` — `tenshu-rei.md`'s status is *Sketch*. So
`handles` has no manifest to join, and *"does the app actually route what it claims to handle?"* has
no evaluator to check it in. Building either now would be inventing a protocol against a hypothesis,
which is the exact warning CO-D2 opens with and the reason the table was compiled in to begin with.

⚠ **This is not a deferral of the hard part.** The declaration and the check are one feature — a
claim and its verification — and shipping the claim without the check would put a line in the table
that no test can hold to §5f.2's standard. They land together, with Rei.

### 5j.2 ⭐⭐ The choice is a re-ordering, and that is why it degrades instead of breaking

`handlersFor(type)` has returned an **ordered candidate list** since TT-2b, for the place question
(§5g.1) — and §5g.1 already recorded that this is *"the shape TT-4 needs anyway"*, because a choice
is precisely a re-ordering of candidates. That prediction held exactly: the resolver change is one
private function that moves the chosen `appId` to the front of the list it already had.

The consequence is the property worth having. The **installed-here check still runs afterwards**, so
a user who picked Maps for places on their laptop still gets places opened on a phone that only has
Timeline. An override would have turned one realm-scoped setting into a broken feature on every
device the chosen app is missing from — and the setting is realm-scoped deliberately, because
*"I prefer Timeline for places"* is a statement about the user, not about a device.

⚠⚠ **A preference naming a non-candidate is ignored.** The table is a claim that an app has a real
receiving route (§5f.2); honouring an arbitrary `appId` would let a stale or hand-edited row send a
place to Calendar, which would launch and then show nothing — trading an honest refusal for a silent
dead end. `aPreferenceNamingAnAppThatCannotShowTheTypeIsIgnored` pins it.

### 5j.3 ⭐ No new wire surface — the choice is an ordinary preference row

`PrimaryHandler.preferenceId(type)` = `tenshu/app/primaryHandler/{type}`, stored as a `RealmSetting`
whose value is the chosen `appId`. `WriteSetting` is a **shipped generic op**, so the choice is
writable today by any client that can reach `:preference-service` — TT-4a needed a *reader* and an id
scheme, not an operation, and `AppService` does not move off version 2.

⚠⚠ **The reader lives in `:control-shared-proxy`, not `:core-common`.** `:core-common` does not
depend on `:preference-service` and must not start; `appOpenTargetImplementationFactory` therefore
takes the lookup as a lambda, the same shape `deviceId` already used. It is resolved from the
*executing* `ExecutionContext` at call time rather than captured at DI time, because the choice is a
realm row that can change while the proxy runs.

⚠ The id is **derived from the type** so no second table of ids has to be maintained beside the
table of handlers — two tables would drift, and the drift would be silent. `theIdIsNamespacedAndDerivedFromTheType`
pins the string because it is a *stored key*: changing it orphans every choice a user has already
made, and nothing else in the system would notice.

### 5j.4 ⚠⚠ The two test files are testing different things, and neither alone is enough

`OpenTargetTest` proves the resolver honours a preference it is **handed** — the lookup is a fake, so
nothing there can fail if the id scheme and the stored row disagree. `PrimaryHandlerPreferenceTest`
writes an ordinary `RealmSetting` the way a settings screen would and reads it back through the
production lookup. Without the second, `preferenceId` could name a row nobody ever writes and every
other test would still be green — the `one-row-test-pass-without` failure shape.

⚠ Its fixture needs `preferenceServiceCommonModule` registered explicitly: a bare `TestProxy` carries
the core registry only, and without it the test fails on `Schema not found` — on the fixture rather
than on the behaviour.

### 5j.5 What is proven, and what is not

Proven: the resolver honours a choice, falls through when the chosen app is not installed here,
ignores a choice for an app that cannot show the type, and does not leak a choice across types; the
id scheme and the stored row agree end to end; all three platforms supply the reader.

⚠⚠ **Not proven — and the honest gap: nothing in the product sets it yet.** The mechanism is
reachable (`WriteSetting` is generic and shipped), so this is *undiscoverable*, not *inert* — but the
user-visible half of TT-4, the primary-handler swap on Control's Apps screen, is still owed. That is
TT-4b. ⚠ It is a different kind of gap from §5f.2's *"a line with no receiving route"*: a preference
nobody sets changes nothing, whereas a route that does not exist misroutes an open.

---

## 5k. As built — TT-4b: the swap the user can actually make (2026-08-24)

TT-4a shipped a preference nothing set. This is the other half: Control's Apps screen grows the
primary-handler swap `tenshu-rei.md` §2.2 describes, so the mechanism becomes a setting.

### 5k.1 ⚠⚠ A settings screen that disagrees with the resolver is worse than no settings screen

Everything in this slice follows from that. The picker is separate code from
`OpenTargetImplementation`, so there are four ways it could claim something an open would not do,
and each has a named test:

| The lie | Where it would come from | The guard |
|---|---|---|
| offering a choice that changes nothing | a type with one candidate installed here | `oneCandidateInstalledHereIsNotAChoice` |
| naming a default that is not what opens | rendering the table's order while the resolver used the choice | `theDefaultOptionNamesTheAppThatWouldActuallyOpen` |
| showing a selection the resolver ignores | honouring a choice for a non-candidate | `aChoiceForAnAppThatIsNotACandidateHereReadsAsNoChoice` |
| a choosable type with no name | a new second candidate lands in the table | `everyChoosableTypeHasALabel` |

⭐ All four were verified by **deleting the guard and re-running** — each fails on exactly its own
property and nothing else. The fourth is the one worth keeping: adding a second candidate for any
type in `HandlerTable` makes it choosable, and without that test the first sign would be a user
seeing `tenshu/timeline/moment` in their settings.

### 5k.2 ⭐⭐ One derivation, shared by the real registry and the mock

`openHandlerChoices(apps, chosen)` is a pure function in `:control-shared-logic`, and **both**
`TenshuAppRegistry` and `MockAppRegistry` call it. That is not tidiness — a second copy in the mock
is what every screen test would then be built on, and a fake that agrees with itself is the shape
that hides a defect (`fake-models-only-happy`). It also makes the picker unit-testable without a
proxy: the production code left untested is the flow plumbing, not the logic.

⚠ It mirrors the resolver rather than reimplementing it: chosen app to the front of the table's
order, non-candidate choices ignored, nothing offered that is not installed on this device.

### 5k.3 ⚠⚠ `HandlerTable` is public now, and not in the `.proxy` package

A settings screen cannot show a routing it cannot read. The alternative — a client-side list of
"which apps can show a place" — would be a **second source of truth able to disagree with the
resolver**, which is exactly the drift `PrimaryHandler` avoids for ids by deriving them.

⚠ This is not CO-D1 leaking. A *caller* still addresses a `DataId` and never names an app; what
reads the table here is the management surface whose whole job is to show the routing. ⚠⚠ When Rei
lands the table stops being the source and this read becomes an op — CO-D2 already says the
generator changes and the boundary does not.

`choosableTypes()` is **derived** from the handler map (types with >1 candidate), so a hand-kept
second list cannot drift out of step. Today it is `place` alone.

### 5k.4 ⚠ "Recommended (Timeline)", not "Automatic"

The default option names the app it would pick. A user choosing between *"Recommended"* and
*"Timeline"* cannot tell whether those are the same thing; naming it answers that without a second
screen — and it is only answerable because `candidates` arrives in the resolver's effective order.
`theDefaultLabelFollowsTheOrderRatherThanNamingAFixedApp` is the guard against that becoming a
hard-coded string.

⚠ **The section is absent, not empty-stated, when there is nothing to choose.** Explaining a setting
the user cannot make is noise on a screen whose job is the app list — and most realms cannot make
this one, since it needs two place apps on the same device. It renders *below* the app list for the
same reason.

⚠ No optimistic update on tap: the write goes to a synced realm row and the read flow brings the
result back, so echoing the tap locally would race the store and could briefly show a selection the
resolver would not honour.

### 5k.5 ⚠⚠ `iosKotlinCheck` caught what the full gate could not — again

`AppRegistry` gained two members, and `NotConnectedAppRegistry` in `:tenshu-sdk-ios` implements it.
The full gate — 105 test tasks, 1385 classes, **all green** — compiles no `iosMain` at all, and
`iosKotlinCheck` failed on the exact class. This is the third time in this track's history; the
standing rule in `CLAUDE.md` earned its ⚠⚠ again.

⚠ Its answers follow the seam's own rule ([`control-onboarding-ux.md`](control-onboarding-ux.md)
— *not-connected is a failure, never an empty success*): `openHandlerChoices` is empty
because with no Control there are genuinely no installed apps to choose between — the same answer a
connected Control gives a realm with one place app — while `setPrimaryHandler` **throws**, because a
swallowed choice would tell the user their default had changed while nothing recorded it.

### 5k.6 What is proven, and what is not

Proven: the derivation (6 tests), the rendering and its four lies (6 tests), the resolver↔preference
join from TT-4a (7 tests), and that all four guards fail when deleted. The full gate, `iosKotlinCheck`,
`scripts/ios-smoke.sh --build-only control` and the Android smoke gate are green.

⚠⚠ **Not proven: nobody has changed the setting on a device and watched a place open in the other
app.** That needs a realm with both Timeline and Maps installed and a cited place — the same
device-verification gap `mia-query-layer.md` §14.7 names, reached from a third side. What the tests
establish is that the picker and the resolver agree about what *would* happen; the round trip itself
is still owed.

⚠ TT-4's remaining third — Rei's `handles` declaration and the evaluator check — is unchanged and
still blocked (§5j.1).

---

## 5l. As built — CO-D11 and the two defects beside it (2026-09-01)

Opened from a user report of one screen: Timeline's Home feed, a moment card for a visit to
the office. Tapping the **title** opened the moment's detail screen; tapping the **photo** on the same
card left Timeline for Drive. Two of the three findings below were never TT-2's design going wrong —
they are the design applied one surface too widely, and a wiring line that was written in a comment
and never in code.

### 5l.1 ⚠⚠ A feed row's photo is not a cross-app target — the card was two targets wearing one skin

`PhotoCluster` made every frame open its file in the owning app, and it is reused on four surfaces.
On the two **detail** surfaces (moment detail, a place's visit rows) that is right: the photo is the
subject, and TT-2a's deep link is the whole point. Inside a **feed row** it is not — the picture
there is a thumbnail *of* the moment, drawn under the moment's own title, inside the row's own
`clickable`. So the card had two destinations, in two different apps, with nothing on screen to
distinguish them, and which one you got depended on which pixel you pressed.

⭐ **What the guard test found, before the fix:** with the frame's own `clickable` in place, a tap on
the photo did not fall through to the row either — the inner click consumed it. So the row was not
merely ambiguous, it was unreachable through half its own surface.

The fix is a parameter, not a deletion: `PhotoCluster(onPhotoTap = …)`. `NamedMoment` passes its own
`onClick`, so the picture goes where the title goes; every other caller passes nothing and keeps the
cross-app open. ⚠ A row with **no** destination of its own still falls back to the opener — an inert
photo would be a loss rather than a fix.

⚠ iOS needed no change and that is evidence, not luck: `TimelineHomeView`'s `MomentRow` renders
*"· 3 photos"* as text and no frame at all, while `MomentDetailView` is the only surface carrying
`openInOwningApp`. The SwiftUI shell had drawn the line in the right place from the start.

### 5l.2 ⭐⭐ CO-D11 — back went into the app instead of out of it

See CO-D11 for the decision and its cost. What is worth recording here is *why it was invisible*:
each receiving app's route was one line (`backStack.add(…)`), it was correct as a piece of that
app's own navigation, and the doc's scope line had explicitly disowned it. The bug lived in the gap
between "Control's contract" and "Drive's navigation", which is where nobody was looking.

### 5l.2b ⚠⚠ The stack rule uncovered a crash, one screen away

Making the target the only entry put a back arrow on the bottom of the stack, which nothing could
reach before. Drive's file preview pops with `backStack.removeAt(backStack.size - 1)` — correct at
every depth the app could previously be at, and an `IllegalArgumentException` from inside
`NavDisplay` at depth one. Ten call sites in Drive and one in Timeline were the same unguarded pop.

⚠ The fix is not a size check bolted onto the risky one: all eleven now go through
`popOrLeaveApp(exitApp)`, so the guard cannot be present at the site someone happened to think of
and absent at the next one added. ⚠⚠ Worth stating plainly — **this crash was created by CO-D11**,
not found beside it. A decision that makes a previously-unreachable state reachable owes the audit
of what now reaches it, and one grep for `removeAt(` was that audit.

### 5l.3 ⚠⚠ Timeline's receiving half was resolved, documented, and never handed over

`timeline-android`'s `MainActivity` injected `OpenTargetInbox`, read the launch extra in both
`onCreate` and `onNewIntent`, delivered it — and then called `App()` with no `openTargets`. The
desktop shell did the same. Both files carry a header comment naming the exact call they do not
make (*"the UI shell is just `TenshuTheme { App(openTargets = …) }`"*), so the intent was recorded
and the code was not.

⚠⚠ **The failure mode is the silent one this track keeps rediscovering.** Delivery succeeds, the
inbox holds the target on its `replay = 1` buffer, `openTarget` returns `Delivered`, the launch
ticket fires and Timeline comes to the front — showing Home. Every observable signal says the open
worked. §5g.3 called Timeline "the first app on both sides of the contract"; it was on both sides in
`:timeline-shared-ui` and on neither in the shells that run it.

⚠ It also means §5g.5's proof was narrower than it read: `destinationForTarget` had unit tests and
the route was rendered by the shared `App`, but no shipped Timeline binary could reach it.

### 5l.4 What is proven, and what is not

Proven by test: the feed-row routing and its fallback (4 Compose tests, `PhotoTapTest`, and the
first fails when the fix is reverted), and CO-D11's stack shape plus the pop guard on all three of
its platforms (6 tests, `CrossAppEntryTest`). The full gate, `iosKotlinCheck` and the
`:timeline-android` / `:drive-android` / desktop compiles are green.

⭐⭐ **Device-verified 2026-09-01** (Android phone, maintainer's own realm — the first end-to-end
Timeline→Drive run there has ever been). The feed-row tap lands on the moment, and back out of
Drive's file preview returns to Timeline's detail screen. **CO-D11's central claim is no longer an
inference from the framework's source**: Android really does bring the calling task forward when a
single-activity app finishes its only entry.

⭐ **§5f.3's gap is closed with it.** TT-2a shipped 2026-08-21 with *"Timeline→Drive is not
device-verified — an empty realm has no photos"*, and it stayed open for eleven days. The realm that
finally had photos in it is the same one that found the defect this section is about, which is the
argument for device passes in one line.

⚠ **Still not watched: the warm case.** CO-D11 throws away a receiving app's own navigation position,
and every run here started with Drive cold or on Home — where there is nothing to lose. Whether
losing a folder the user was three levels into *feels* like the right trade is a judgement no test
makes, and it has not been put in front of one.

⚠ Android only. Desktop and iOS ship the same stack rule with `rememberAppExit` = `null`, so a
cross-app entry there has a back arrow that does nothing; neither has been looked at.

---

## 5m. As built — §6b Q2, and the screen CO-D11 left as the whole app (2026-09-02)

Reported from a device the day CO-D11 shipped: a cross-app open naming a file Drive could not read
put **`Unknown(copyKey=ErrorCopyKey(value=error_not_found))`** on screen, centred, in red.

### 5m.1 ⚠⚠ Two independent defects, and the second made the first fatal

**The copy** was already wrong before any of this: all three preview view models set
`error = result.error.toString()`. `core-error-copy-catalogue.md` §5.0 names that exact failure —
*"a catalogue miss renders the generic sentence, **never** the error's own message"* — and
`toString()` on a sealed class is worse than the message alone, because it also prints the class
name and the key vocabulary.

**CO-D11 made it the entire app.** Before, this screen sat on top of `Home`: ugly, but the user was
one back-press from a working Drive. After, the target is the only back-stack entry, so a raw
developer string *was the whole window*. ⚠⚠ The lesson generalises past this bug: **a decision that
removes a fallback promotes every latent defect underneath it.** Nothing about the preview changed
on 2026-09-01; what changed is that there was no longer anything behind it.

### 5m.2 The fix follows the catalogue, not a local sentence

`RepositoryError.copyKey()` maps the four arms to catalogue keys, and the three preview UI states
carry an `ErrorCopyKey?` where they carried a `String?`. Screens resolve it through a new
`ErrorCopyKey.driveLabel()` — Drive's own keys chained in front of `:ui-core`'s, exactly the shape
`ContactsErrorCopy.contactsLabel()` already had (§5.1's chaining).

⚠ `RepositoryError.UnexpectedError.message` is **dropped**, not shown. It is a diagnostic authored
below the proxy boundary — on Android, inside Control's process — and §5.0 exists to keep one app
from writing another's copy. `RepositoryErrorCopyKeyTest` pins that, and fails when the leak is
reintroduced.

⭐ The three hardcoded English strings beside it (*"Could not decode image"*, and the PDF and video
equivalents) became Drive-owned catalogue keys in the same pass, because migrating the field forced
them to be keys anyway — so they are localisable now rather than baked into a `when`.

### 5m.3 ⚠ What was deliberately NOT built

**A way onward from the failed screen.** Back from a cross-app entry returns the user to the app
that sent them, which is arguably the right exit — they came for that photo, and Drive's Home is
not a consolation prize. The actions sheet is still reachable from the top bar. An explicit
"Open Drive" button is a design addition rather than a defect fix, so it is a question, not a
commit.

### 5m.4 What is proven, and what is not

Proven: the mapping and the §5.0 rule (4 tests, `RepositoryErrorCopyKeyTest`; two of them fail when
`UnexpectedError` is mapped back to its message). The three modules compile and their suites pass.

⚠⚠ **Not proven: nobody has watched the new sentence on a device.** It needs a realm holding a
`DataId` for a row that is gone — the same setup that produced the report, which was found by
accident rather than constructed.

---

## 6. Questions, and what they resolved to

All five of v0.1's open questions were answered on 2026-08-16. Recorded here with their reasoning
rather than deleted, because two of them changed the design.

- **Q1 — Where does a pending target live?** ✅ **Nowhere → CO-D8.** The payload rides the launch on
  Android and iOS; desktop polls briefly and gives up. ⚠ The question named Android's `PendingIntent`
  as a possible reading — it is not that, and `PendingIntent` would be the wrong tool: it exists to
  let another process act *as you* later, and nothing here is deferred.
- **Q2 — Is the desktop connection durable enough to deliver into?** ✅ **Answered by reading, not by
  an exploration.** `raiseWindowWhenReady` in the tray agent already does exactly this — bounded
  polling until the daemon is reachable, then a call — so the pattern is shipped Swift rather than an
  unknown. ⚠ What a spike *would* still be worth: measuring how long a cold desktop app takes to
  become steerable, so TT-3's bound is chosen from data instead of copied (the tray's is ~10 s).
- **Q3 — Does the caller learn the outcome?** ✅ **Yes, on the existing error channel → CO-D9.** ⚠ The
  half that stays true from the original question: *"delivered"* is knowable and *"the user saw it"*
  is not, so the contract promises only the first.
- **Q4 — One handler per type, or per `(type, intent)`?** ✅ **Per type → CO-D10**, with the deferral
  cost stated rather than waved away.
- **Q5 — Does `MiaHit.kind` survive?** ✅ **No — it is redundant once a citation carries a `DataId`**,
  since the type drives both the routing and the glyph §15.4 added it for. ⚠⚠ **But it is a
  consequence of this landing, not a precondition**: removing it before every citation carries a real
  id would leave the glyph with nothing to switch on. It is TT-2 cleanup, and it wants its own note
  in `mia-query-layer.md` when it happens.

## 6b. Still genuinely open

- **How long may a launch take before the caller gives up?** The tray's ~10 s is a precedent, not a
  measurement, and a cold JVM desktop app is not a daemon.
- ~~**What does an app do with a target it cannot show?**~~ ✅ **Answered for Drive, 2026-09-02
  (§5m)** — it says so in catalogue copy, and back returns the user to the app that sent them. ⚠ The
  question stays open for *every other* receiver: Timeline's route can be handed a `placeId` or
  `momentId` whose row is gone, and nothing there has been looked at.

---

## 7. Pointers

- [`mia-query-layer.md`](mia-query-layer.md) — §8.1 (`reference` is the owning service's id, and the
  no-new-vocabulary rule), §14.7 + §15.6 (the inert citations), §15.1 (why an occurrence has no id).
- [`timeline-photos.md`](timeline-photos.md) §10.5 — the same problem, deferred, with the two options
  this design chooses between.
- [`tenshu-rei.md`](tenshu-rei.md) §2.2 — the manifest scan (what an app *calls*) and the
  **primary handler per data type** (what an app *shows*), which TT-4 joins up.
- [`control-app-registration.md`](control-app-registration.md) — `AppRegistration` keyed
  `(appId, deviceId)`: the "installed here" answer, and the three-layer identity/registration/grant
  split this design sits on top of.
- [ADR 0022](../adr/0022-ios-forward-to-control-surface.md) — `tenshu-control://setup`, the shipped
  iOS deep link, its `openURL`-completion fallback, and the route-1 constraint.
- [`core-client-resubscription.md`](core-client-resubscription.md) — the connection semantics Q2 turns
  on.
- [`cross-app-file-access.md`](cross-app-file-access.md) — the *data*-sharing counterpart; this doc is
  navigation only and deliberately moves no bytes.
