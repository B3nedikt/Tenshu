# Error copy catalogue — the proxy emits codes, the client renders them

**Status:** v1.0 (2026-08-29). **EC-0 … EC-5 all shipped**; as-built notes under each phase in §6.

v0.2 re-counted §4's site list against the tree and rewrote §5.1 around a constraint that makes
v0.1's shape uncompilable. **v0.3 adds the invariant the whole track is measured against (§5.0 —
no proxy code creates a localised string) and re-cuts §6 around it: the phases are now vertical
slices, one user-facing flow from throw site to rendered pixel, rather than horizontal passes over
all render sites and then all throw sites.** §6.0 says why. v0.1's lint phase is dropped (§8).
⚠ The EC-numbers were renumbered in v0.3 and do not mean what v0.2's did.

**Owner:** Benedikt

**Relationship to other docs.** [ADR 0016](../adr/0016-localisation.md) **already decided this**
at anchor #4 — this doc does not reopen it, it builds the half that was never built.
[`core-error-channel.md`](core-error-channel.md) shipped all six phases on 2026-06-02 and built
the **throw** side: ops return `T` and throw typed `TError`s, with the catch-all confined to
`LocalTaskExecutor`. The **render** side — mapping a typed error to localised user copy — was
left to the i18n track, which recorded it as *"implement the error-channel proposal first, then
the residual VM literals become a small mop-up PR."* The mop-up never happened, and without a
place to put copy the proxy kept writing English.

---

## 1. The symptom

A user approving a device on **Add This Device** sees:

> This device is no longer recognised by the relay, so it can't add another device. Pair this
> device again, then retry.

That sentence is a string literal in `ApprovePairingOfferImplementation` (`:sync-service-proxy`),
wrapped in a `GenericError`, carried across the proxy boundary as freetext, and rendered verbatim
by `PairingWizardViewModel`:

```kotlin
val msg = if (error is org.tenshu.core.GenericError) error.message.get() else …
```

It is decent copy. That is not the problem. The problem is where it lives: it cannot be
translated, cannot be matched on by a caller, and — because only `InternalError.internalDetail`
survives into a recorded `ErrorEvent` — it is **also discarded from the logs**, so the same
sentence is simultaneously untranslatable to the user and invisible to us.

## 2. This is a decided policy the code violates

ADR 0016 anchor #4, in full:

> **The proxy emits codes, not localised strings.** Any user-facing error or status that crosses
> the proxy boundary travels as an enum-ish schema (sealed `Error` subtype, named operation
> result, typed status composite) — *never* as a free-text `String` the client is expected to
> render. The client module that catches the typed value is responsible for mapping it to a
> localised string via its own `strings.xml` / `i18next` catalogue.

and its Consequences section names the audit and the test:

> **Cross-service error types must be enum-ish.** … The path is mechanical: turn the free-text
> into a sealed code, localise at render. Some sites will be fine as English-only forever
> (developer-facing log strings, internal diagnostics) — the test is *"will a user see this?"*

⚠ So the framing is **not** "we should consider codes". It is: the decision is four months old,
the throw side shipped, and the render side is missing.

## 3. What is actually missing

| piece | state |
|---|---|
| typed `TError` taxonomy + throw contract | ✅ `core-error-channel.md`, all six phases |
| `errorTypeId` recorded on `ErrorEvent` | ✅ `ErrorEventRecorderImpl` |
| id → **label** mapping | ⚠ exists as a **hardcoded English `when`** — `ErrorsScreen.kt:288` |
| id → **localised** copy | ❌ does not exist |
| feature screens rendering by id | ❌ they read `error.message` instead |

`labelFor`'s own KDoc says the catalogue *"will reuse the same id keys"* — i.e. it was written as
a placeholder for this doc's subject, and has been the placeholder ever since. It is also wired
only into **Settings → Errors**; no feature screen consults it.

⭐ **The English strings in the proxy are a symptom, not the disease.** There is nowhere else to
put user copy today, so an implementation author facing a real failure writes the sentence where
they are standing. Adding a catalogue removes the reason; adding a lint without one just moves
the pain.

## 4. Scope: count render sites, not throw sites

There are **125** `GenericError.create` call sites in non-test code, most of them
developer-facing (`"Content PUT error: ${response.status}"`) and blessed as English forever by
the ADR's own test. That number is the wrong scoping handle.

The tractable set is the expressions that pull a sentence **out** of a `TError` in an app module.
Measured 2026-08-28: **nine expressions, seven files, three modules** — v0.1 said six in two, and
missed `:contacts-shared-data` entirely as well as counting `PairingWizardViewModel`'s three
occurrences as one.

| file | line | kind |
|---|---|---|
| `control-shared-logic/.../recovery/RecoveryViewModel.kt` | 83 | render (bespoke) |
| `control-shared-logic/.../sync/PairingWizardViewModel.kt` | 567 | **match**, not render |
| `control-shared-logic/.../sync/PairingWizardViewModel.kt` | 608 | render (bespoke) |
| `control-shared-logic/.../sync/PairingWizardViewModel.kt` | 700 | render (bespoke) |
| `control-shared-logic/.../sync/HostPairingMonitor.kt` | 239 | render (bespoke) |
| `control-shared-logic/.../notifications/PendingRequestsRepository.kt` | 251 | render (`text()` clone) |
| `drive-shared-logic/.../data/MembershipRepository.kt` | 171 | render (`text()` clone) |
| `drive-shared-logic/.../data/ContactsRepository.kt` | 420 | render (`text()` clone) |
| `contacts-shared-data/.../data/TenshuContactsRepository.kt` | 385 | render (`text()` clone) |

Out of scope but worth naming so the lint does not trip on them: `hub/HubPairingCli.kt:82` is a
developer CLI, and `tenshu-sdk-js/.../TErrorJs.kt:13` + `Contacts.kt:272` belong to the web
question (§7.3). `tenshu-sdk-ios` renders the same errors by a different route entirely — §5.5.

⭐ **The nine split into three shapes, and the split *is* the phase boundary:**

- **Four are one byte-identical helper**, copy-pasted across three modules:
  `private fun TError.text(): String = (this as? GenericError)?.message?.get() ?: toString()`.
  It flattens into a `String` field on a carrier that is already sealed
  (`RepositoryError.Unknown(message)`, `RequestActionResult.Failure(text)`). Mechanical; one
  shape to fix four times.
- **Four are bespoke**, each with its own hand-written English fallback (*"Couldn't add the
  device. Please try again."*, *"Recovery failed."*, *"Registration failed"*). Each needs a
  copy decision, not a rename.
- **One is not a render site at all.** `PairingWizardViewModel:567` *matches* on
  `message.startsWith(RespondToHubPairing.ERROR_VERIFY_FAILED)` and maps to a typed
  `PairingScanError`. It is already ADR-0016-shaped in intent — the code just travels in the
  wrong field. §5.6.

⚠ v0.1 called the `(as? GenericError)?.message` expression "greppable, which makes it both the
migration checklist and the lint rule". Half true: two of the nine spell it
`if (error is org.tenshu.core.GenericError) error.message.get()`, fully qualified and with `is`
rather than `as?`. The literal regex in v0.1 finds seven of nine — worth knowing for anyone
working the list by grep, and for whoever eventually revisits the dropped lint (§8).

## 5. Design

### 5.0 ⭐⭐ The invariant: localised strings are created app-side, at the ViewModel level or above

Everything below is downstream of one rule, and it is stricter than "prefer codes":

> **No proxy code ever creates a localised string.** A user-facing string is created on the app
> side of the proxy boundary — at the layer where the ViewModels live, or above it. Below that
> line only typed values travel.

⚠ On Android this is not a layering preference, it is a **process and package** fact. Control is
the proxy; Drive talks to it over Binder (`AGENTS.md`, *Build & test*). `:sync-service-proxy`,
`:contact-service-proxy` and the rest are **loaded into Control's process**, while
`drive-shared-logic/MembershipRepository` renders in **Drive's**. So today's freetext path has
Control's APK authoring a sentence that Drive's UI displays — one app writing another app's copy,
across a package boundary, with two independent `strings.xml` catalogues that can never agree.
That is the concrete thing ADR 0016 anchor #4 forbids, and §1's symptom is what it looks like.

⭐ **The ViewModel layer is where a proxy value stops being the proxy's**, and the repo already
has the exemplar: `RecoveryError` is a sealed carrier in `:control-shared-logic` (VM level) and
`RecoveryScreen.errorCopy()` in `:control-shared` turns it into copy. The typed value reaches the
VM; the copy is created above it. Nothing about that shape is new — three of `RecoveryError`'s
four members already work this way, and `Generic(message: String)` is the hole (§6 EC-1).

**Two consequences that shape the phases:**

1. **The proxy's English is diagnostics, permanently.** It is not draft copy waiting to be
   translated. It goes to the `ErrorEvent` and the logs, and a render site must never reach it —
   §5.3 as a hard rule, not a preference.
2. **Nothing below the app layer holds copy, and ideally nothing below it holds a copy *key*
   either.** §5.4 already says keys are derived from ids by rule
   (`tenshu/result/error/notFound` → `error_not_found`), and if the derivation is a rule then
   there is no id→key table that needs a home — which dissolves most of what the seam phase was asked to
   decide. See §5.1.

⚠ **One carve-out, and it must be named or the rule reads as violated.** ADR 0016 anchor #5
blesses the proxy having its own catalogue *for UI the proxy itself renders*, and
`control-shared-proxy/androidMain/AndroidPendingRequestNotifier.kt:67` does exactly that —
`context.getString(R.string.actionable_request_grant_title)` in a proxy module. That is legal
because **no proxy boundary is crossed**: Control is rendering Control's own notification into
Control's own UI, and it resolves against Control's catalogue with Control's locale. The rule is
about strings that *travel*. Ditto `ProxyService`'s and `HostPairingForegroundService`'s FGS
notifications in `:control`.

The test, stated so a reviewer can apply it: **does this string cross a process/package boundary
before a user sees it?** If yes, it must be a typed value instead. If no, the proxy may localise
it against its own catalogue.

### 5.1 ⚠⚠ The renderer cannot live where v0.1 put it, and that reshapes the whole migration

v0.1 proposed a single artefact in `:ui-core`:

```kotlin
@Composable
fun TError.label(): String = when (schema.id.get()) { … stringResource(…) … }
```

**Every one of the nine sites in §4 is in a module that has no Compose dependency at all** —
`:control-shared-logic`, `:drive-shared-logic` and `:contacts-shared-data` each declare zero
compose artefacts, which is exactly the property `docs/agent.md` names as *"the Compose-free
view-model/repository layer, which is what makes an app iOS-capable"*. `stringResource` is a
`@Composable`. So the proposed `TError.label()` is **uncallable from all nine call sites**, and
`:ui-core` (a Compose module) cannot be depended on by any of them.

This is not a detail to fix inside one phase; it is the reason the migration is not a
find-and-replace. The
migration is inherently two-sided:

- the **shared-logic** side stops flattening a `TError` to a `String`, and carries something
  id-bearing in its `UiState` / result carrier instead;
- the **shared-ui** side (`:control-shared`, `:drive-shared-ui`, `:contacts-shared-ui`) resolves
  that value to copy with `stringResource`.

⭐ **The split has a precedent in this repo, and `:ui-core`'s own build file argues for it.**
`RealmStorageState` + `evaluateRealmStorage` live in `:storage-service-common`, not `:ui-core`,
with the comment: *"the iOS SDK needs the same decision and cannot link this module — `:ui-core`
is Compose, and dragging the Compose runtime into the XCFramework to share two branches would be
absurd. Duplicating it in Swift was the alternative, and that is how Android and iOS come to
disagree."* The error catalogue is the same shape of problem with more branches.

**So the artefact splits in two, and §5.0 puts both on the app side:**

1. **`TError.errorCopyKey(): ErrorCopyKey` — Compose-free.** Derives the key from the error's
   schema id by §5.4's rule. ⭐ Because the key is *derived* rather than tabulated, this is a
   pure string transform with no copy in it, so it can sit wherever is convenient without
   carrying catalogue knowledge below the app layer — `:core-common` is fine, and so is a
   `*-shared-logic` module. ⚠ Derivation has a real cost over an explicit table: an id with no
   catalogue entry derives a key that silently resolves to the fallback, where a `when` over
   known ids would have been exhaustive. EC-0 should pair the derivation with a test asserting
   every id in the taxonomy has an entry, which buys back the exhaustiveness without putting a
   table below the line.
2. **`@Composable fun ErrorCopyKey.label(): String` — in `:ui-core`.** The `stringResource`
   lookup plus the fallback, per ADR 0016 anchor #7. It never sees a `TError`, and it is
   app-side by construction.

⚠ The fallback renders a generic sentence, **never** the raw message. An unrecognised id is a
missing catalogue entry, and showing the developer's English is what this doc exists to stop.
The id itself still belongs in a detail row / the `ErrorEvent`, where a developer will look.

⚠ Note what the fallback costs. `ErrorsScreen.labelFor`'s `else` branch today derives a readable
label from the id (`…/notFound` → *"Not found"*), which is strictly better than a fixed
*"Unexpected error"* on the developer-facing Errors screen and strictly worse on a user-facing
one — it leaks the id vocabulary. The two surfaces want different fallbacks; EC-0 must keep both
rather than pick one.

### 5.2 Parameters force structured fields, and that is the point

*"No Device row for `$deviceId`"* is not a code — it is a code plus an argument. ICU
MessageFormat is already the committed content format (anchor #3), so the catalogue can take
`{deviceName}`; what it needs is for the typed error to **carry `deviceId` as a field** rather
than baked into a sentence. This is the concrete reason the migration is not a find-and-replace:
each converted error has to decide what its arguments are.

⚠ Arguments are also what stops `ErrorCopyKey` from being a bare enum: a key that takes
`{deviceName}` has to carry the value with it. `RecoveryError.UnknownWord(val word: String)` in
`:control-shared-logic` is the shape already in the tree.

### 5.3 `GenericError` becomes developer-only, by policy

It stays for diagnostics, and `labelFor` already renders it *"Operation failed"*. The rule to
write down: **a `GenericError` must never reach a user-visible surface** — the §5.0 test applied
to the one type that carries freetext by design. Enforced by review, not by tooling (§8).

### 5.4 Key naming is the n=2 obligation

Anchor #8 pre-committed a single ICU JSON catalogue with codegen at the second locale, and asked
that keys chosen now make that *"merge, not rewrite"*. Error keys should therefore be derived
from the id (`tenshu/result/error/notFound` → `error_not_found`) rather than invented per screen.

### 5.5 ⚠⚠ There are three renderers, not one — and the second one is already written

`RecoveryError` is the exemplar of the target shape and it is **already in the tree**: a sealed
carrier in `:control-shared-logic`, rendered by `RecoveryScreen.errorCopy()` in `:control-shared`
via `stringResource`. That is i18n Phase 2c, and it works.

It is also rendered a **second** time, in hardcoded English, by
`tenshu-sdk-ios/.../ControlSurface.kt:635`:

```kotlin
is RecoveryError.UnknownWord -> "'${error.word}' isn't a valid recovery word. Check the spelling."
```

`ControlSurface` carries four such error-copy entry points today — `genesisErrorMessage`
(`NewUserError`), `recoveryErrorMessage` (`RecoveryError`), `unlockErrorMessage` (`LockedError`)
and `pairingErrorMessage`. Route-1 iOS apps are SwiftUI over that bridge and never touch Compose,
so **a `:ui-core`-only catalogue does not localise iOS at all** — it guarantees a permanent
second English copy that drifts, which is precisely the failure `:storage-service-common` was
carved out to prevent.

⚠⚠ The fourth one is worse than a duplicate. `pairingErrorMessage` is
`fun pairingErrorMessage(state: PairingWizardUiState): String? = state.error`, a straight
passthrough whose KDoc reads *"already user-facing on the VM side"* — so §1's proxy-authored
sentence reaches a SwiftUI screen verbatim, having crossed the proxy boundary, a
`GenericError`, a `UiState` and the XCFramework without ever being copy anybody owns. EC-4 fixes
this site on both platforms at once or on neither.

The third renderer is the JS SDK (`TErrorJs.kt:13`), which is §7.3's question.

⭐ This is why §5.1 keeps the key *derivation* Compose-free and the key → copy *table* app-side,
once per renderer, in that renderer's own catalogue format. All three renderers are on the app
side of the proxy boundary — `:ui-core` for Compose, `tenshu-sdk-ios` for SwiftUI, the JS SDK for
web — so §5.0 is satisfied by all three, and the triplication is exactly what anchor #7 already
accepts and anchor #8's n=2 codegen is meant to merge.

### 5.6 The proxy already emits codes in two places — as freetext

Two operations pre-empted this doc by defining stable codes and shipping them **inside**
`GenericError.message`:

- `RespondToHubPairing.ERROR_VERIFY_FAILED` / `ERROR_OFFER_NOT_FOUND` /
  `ERROR_RELAY_UNSUPPORTED`, whose KDoc says *"so shared UI can map each failure to its own copy
  without a typed-error round"*;
- `RedeemContactInvite.ErrorCode.ALREADY_RESPONDED` / `GONE`, whose KDoc cites ADR 0016 by name.

⭐ These are the **cheapest** conversions in the whole migration and should go first: the code
vocabulary is already settled, the argument list is already decided (none), and a consumer
already exists. They also demonstrate that authors reach for a code the moment they need one —
§3's point that the English is a symptom of having nowhere to put copy, not of authors not
caring.

⚠ They are also the reason a naive check would misfire: anything flagging
`(as? GenericError)?.message` uniformly would flag `PairingWizardViewModel:567`, which is
*matching* a code, not rendering a sentence. Worth remembering if the lint is ever revisited
(§8) — but note that EC-4 deletes this site's pattern outright, so by then the exception is gone.
It is also why EC-4 lists that conversion as its cheapest, strictly subtractive part.

## 6. Phases

### 6.0 Why these phases and not the obvious ones

v0.2 sliced **horizontally** — build the catalogue, then migrate every render site, then every
throw site — because it was scoped from §4's call-site list. §5.0 makes that ordering wrong.

A horizontal slice moves the render sites first, which stops the *client* displaying the
proxy's sentence but does not stop the proxy *creating* it. Under §5.0 the string is still
authored in Control's process and still crosses the package boundary; only the last phase
(the throw sites) would have made the invariant true, and it would have made it true everywhere
at once, in the largest and least reviewable PR of the set.

⭐ So the slices below are **vertical: one user-facing flow at a time, throw site to rendered
pixel**. Each closes a real boundary crossing end to end, and the invariant is provably true for
that flow the day it lands. The phases are then the same shape repeated, which is also what makes
them reviewable — after EC-1 nobody has to re-argue the pattern.

⚠ **Renumbered, not extended.** These EC-numbers do not mean what v0.2's did. The mapping:
v0.2's EC-1 (the seam) survives as EC-0; v0.2's EC-2/EC-3 (render sites) and EC-4/EC-5 (throw
sites) are dissolved and re-cut by flow across EC-1…EC-4; EC-5 is new. v0.1's lint stays dropped
(§8).

| phase | flow | throw sites | render sites (§4) | obligations |
|---|---|---|---|---|
| EC-0 | *(none — the seam)* | — | — | gate |
| EC-1 | Recovery | `RecoveryClaimImplementation` ×1 | `RecoveryViewModel:83` | gate + iOS + HR#9 |
| EC-2 | Membership & pending requests | *(none — relay diagnostics)* | `MembershipRepository:171`, `PendingRequestsRepository:251` | gate + **iOS** |
| EC-3 | Contacts & invites | `RedeemContactInvite` ×6, `CompleteContactHandshake` ×1 | `ContactsRepository:420`, `TenshuContactsRepository:385` | gate + HR#9 + **iOS** |
| EC-4 | Pairing | ~26 across 10 impls | `PairingWizardViewModel:567/608/700`, `HostPairingMonitor:239` | gate + iOS + HR#9 |
| EC-5 | *(none — the sweep)* | — | — | gate |

⚠⚠ **Corrected 2026-08-29: no flow phase is iOS-free.** EC-2 and EC-3 were first tabled as
gate-only. They are not: `tenshu-sdk-ios` implements the very repository interfaces whose error
carriers this track reshapes — `NotConnectedControlRepositories.kt:142` constructs
`RequestActionResult.Failure("Tenshu Control is not running")` and `FakeFilesRepository.kt:271`
constructs `RepositoryError.Unknown(…)`, with `PairingWizardUiState` read there too. Changing a
carrier's payload changes `iosMain`. ⭐ The general fact worth carrying: **the iOS SDK's
not-connected and fake repositories are a second implementation of every client interface**, so
"does this touch iOS?" is answered by asking who implements the interface, not by whether the phase
sounds UI-shaped.

Order is by increasing risk, not by importance. Pairing is the motivating symptom (§1) and goes
**last**: it is the largest, it carries the heaviest iOS obligation, and it is the one flow where
you want the pattern already proven three times before you touch it.

---

### EC-0 — the seam, with exactly one consumer

Unchanged from v0.2's EC-1, and still first: no vertical slice can land until there is somewhere
to put copy.

1. `TError.errorCopyKey()` — the §5.4 derivation, Compose-free, plus a test asserting every id in
   today's taxonomy resolves to a real catalogue entry (§5.1 records why derivation needs that
   test).
2. `:ui-core`'s **first** `composeResources/values/strings.xml` + `@Composable fun
   ErrorCopyKey.label()`. The module already declares `compose.components.resources`; the recipe
   is `:ui-map`'s `compose.resources { packageOfResClass = …; generateResClass = always }` block.
   i18n Phase 2b skipped `:ui-core` deliberately, *"lands when the first cross-module shared
   user-facing string appears"* — this is that moment.
3. The `tenshu-sdk-ios` twin, beside `ControlSurface`'s existing `when` blocks. English-only, but
   derived from the same key rather than invented (§5.5).

Then delete `ErrorsScreen.labelFor` and make Settings → Errors the first consumer — ⚠ keeping its
id-derived fallback as a *developer-surface* fallback per §5.1, not collapsing both onto the
generic sentence.

⭐ **Deliberately proves all three renderers with zero flows migrated.** If the three-renderer
story (§5.5) is wrong, it is wrong here, in a PR with no ViewModel diff in it.

**Exit:** Errors screen renders identically from the new path; `:ui-core` has a `strings.xml`;
one key resolves in three catalogues.

#### EC-0 as built (2026-08-28)

Shipped as designed except in two places, both recorded here because the reasons outlive the PR.

- `ErrorCopyKey` + `errorCopyKeyForId` in **`:app-error-handling`**, a new member of the `app-*`
  family (`app-reachability`, `app-outbox`, `app-liveness`, …). ⭐ **Not `:core`** — it first
  shipped there and was moved: the key is *presentation vocabulary*, a string transform from an
  error's schema id to a catalogue entry name, and `:core` is the type foundation. The family
  exists for exactly this shape, and `:app-reachability`'s `UnreachableReason` is the same problem
  already solved once: a small vocabulary that the Compose UI, the Compose-free VM layer and the
  iOS/JS SDKs all need to name.
- **Why not `:ui-core`, with a localisation subpackage?** Three hard blockers, not taste. (1) Five
  Compose-free modules name `ErrorCopyKey` in their `UiState`s — the VM/repository layers, which
  are Compose-free precisely so an app can compile on iOS; `drive-shared-logic`'s own build file
  says it was *"extracted out of the old `:drive-shared` so it can compile on iOS. The Compose UI
  (screens, ui-core, …) stays in `:drive-shared-ui`."* (2) `:ui-core` has **no `js()` target**, and
  `tenshu-sdk-js` calls `errorCopyKey()`. (3) iOS would drag the Compose runtime into the
  XCFramework, which `:ui-core`'s own build file calls *"absurd"*. ⭐ The *renderer* does live
  there (`app.tenshu.ui.error.label()` + the `strings.xml`) — that half is right; it is the key
  that must sit below the UI layer.
- **Why not `:core-common`, or splitting the extension into it?** It is the wire/schema layer that
  every `*-service-proxy` links, so it does not reduce proxy reach at all; its `:core` dependency
  is `implementation`, so a public `TError` extension there would force an `api` widening of a very
  central module; and the analogous extension `TError.messageOrNull()` lives in `:core`, so
  `:core-common` is not the established home. Splitting one concept across two modules bought
  nothing the dedicated module does not.
- **Why not `:core-client`?** Semantically the aptest — client-only is right — but `:ui-core` would
  need its first `-client` dependency, against a policy its own build file states twice
  ("Declarations only … stays free of the preference *client*"), and the module has no production
  code.
- **Why not `:core-client`?** Asked and considered: the proxy genuinely never needs the key, and a
  `-client` home would put §5.0 into the module graph rather than leaving it a review rule.
  Rejected on two grounds. ⭐ First, **the key carries no copy**, so a proxy that could name one
  still could not create a localised string — the catalogues are all app-side, and that is the part
  §5.0 actually constrains; the move tightens naming, not the invariant. Second, `:ui-core` would
  need its **first `-client` dependency**, against a policy its own build file states twice
  ("Declarations only; this module stays free of the preference *client*" / "no storage *client*")
  — it links declaration modules and no client module today. `:core-client` is also empty of
  production code, so this would be its first tenant. ⚠ If the module-graph guarantee is later
  judged worth those costs, the piece to move is `TError.errorCopyKey()` alone — it is the only
  declaration that needs `TError`, and `:ui-core` never calls it.
- ⚠ **A `data class`, not a `@JvmInline value class`.** `:core` is exported to Swift, and
  Kotlin/Native mangles the names of declarations mentioning an inline class. `:basemap-core`'s
  `MapPalette` KDoc rejected the value class for the same reason; the trade is worth writing down
  twice.
- ⚠ **`Res.allStringResources` does not exist** on the generated accessor at CMP 1.12.0 — tried
  first, so `:ui-core` carries an explicit `CATALOGUE` map instead. That makes adding an error a
  two-place edit (`strings.xml` + the map) with nothing compile-checked, which is precisely what
  `ErrorCopyCatalogueTest` covers: forward (every id has an entry), reverse (no entry is
  unreachable), no key collisions, and the fallback key is not derivable. Mutation-checked —
  deleting one entry fails the test by name.
- **18 error types, not the nine** `labelFor` knew about (§3's table said nine because that is what
  the placeholder listed). ⭐ And `labelFor`'s ninth entry was **dead**: it matched
  `tenshu/file/error/already-exists` while the real id is `tenshu/file/system/error/alreadyExists`.
  A hand-written table had drifted from the ids without anyone noticing — the argument for
  derivation, found by doing it.
- **Two fallbacks, per §5.1 and Q4:** `label()` for user surfaces (generic sentence) and
  `developerLabel()` for the Errors screen (the key's own humanisation). Settings → Errors uses the
  latter and keeps its readable labels.

⚠⚠ **The iOS artefact is UNVERIFIED.** `ErrorCopySurface.kt` is `iosMain`, and Kotlin/Native does
not cross-compile Apple targets off macOS — `iosKotlinCheck` cannot run in a Linux container, so
the JVM/desktop green above says nothing about it. It owes a macOS pass before anyone treats EC-0
as done.

⭐⭐ **Both of EC-5's guards were pulled forward into EC-0** (2026-08-29), because the track is
being worked without any iOS verification at all — this container cannot compile Apple targets and
the repo's iOS CI lane is blocked, so `ErrorCopySurface.kt` is a file nothing else in the build
looks at. `ErrorCopyParityTest` in `:ui-core/desktopTest` closes both gaps on the JVM:

- **Three-way parity** — `strings.xml` ≡ `CATALOGUE` ≡ `ErrorCopySurface`'s `when`, by reading the
  iOS file's *text*. It cannot prove that file compiles; it does prove its copy matches what
  Android and desktop users see, which is the likelier mistake when adding an entry.
- **Completeness, automatically** — it scans every non-test `.kt` in the tree for
  `const val ID = "…error…"` and fails if any derives a key with no catalogue entry. This is what
  the hand-maintained list in `ErrorCopyCatalogueTest` could not do: a `TError` added tomorrow in
  any module now fails until somebody writes its sentence.

⚠ It reads sources by relative path, which is the fragile part; both paths are asserted to exist so
a moved file fails loudly rather than passing vacuously. `:system-test-utils` remains the tidier
home if this ever needs to reflect over classes instead of text.

### EC-1 — Recovery, end to end. **The pilot.**

The smallest flow that is nonetheless complete, and the one where most of the target shape is
already built: `RecoveryError` is a sealed carrier in `:control-shared-logic` whose
`UnknownWord(word)` / `InvalidLength(wordCount)` / `ChecksumMismatch` members already render
through `RecoveryScreen.errorCopy()`. Three quarters of §5.0 is satisfied here today.

The hole is the fourth member, and the throw side feeding it. `RecoveryClaimImplementation:64` is
a **single** `GenericError.create` that interpolates a nested exception's text:

```kotlin
throw TenshuException(GenericError.create(context, "Invalid recovery code: ${e.message}"))
```

⭐ Every distinct recovery failure a user can hit — bad word, wrong length, bad checksum — arrives
as that one sentence with a `Bip39InvalidMnemonicException`'s message glued on. It is §5.2's
argument in miniature: what the client needs is *which word*, not a sentence containing it. And
the client already has the vocabulary — the VM decodes BIP-39 locally first and raises the typed
members itself (`control-ios/Sources/RecoveryView.swift:9`), so the proxy is re-deriving as prose
a distinction the app can already express.

So EC-1 is mostly **deletion**: type the one throw site with structured fields, map it onto the
sealed members that exist, and `RecoveryError.Generic(String)` disappears.

⚠ First ICU parameter lands here (`{word}`, `{wordCount}`) — §7.2. ⚠ First HR#9 bump
(`:sync-service`) and first `ControlSurface` change (`recoveryErrorMessage`), so EC-1 is where
the per-phase obligations get exercised for real, on the smallest possible diff. That is the
point of picking it as the pilot.

**Exit:** `RecoveryError` has no freetext member; nothing in the recovery flow reads
`GenericError.message`; the same three keys render in Compose and on iOS.

#### EC-1 as built (2026-08-29)

⭐ **No House Rule 9 bump, and no new composite.** The plan assumed typing the throw site meant a
new error type. It did not: `InvalidParameter` is already the platform's shape for *"a value-level
invariant the schema can't encode, raised by an impl body"*, carrying a parameter `name` and a
wire-stable `reason` from a closed set. A malformed phrase is `Reasons.BAD_FORMAT`. So the wire
surface is unchanged and `:sync-service` stays at `version = 3`. ⚠ Worth remembering before EC-3
and EC-4 reach for a bump: **check `InvalidParameter` first** — a new composite is for a failure
that genuinely is not a bad parameter.

- **The three BIP-39 causes collapse to one reason on the proxy path, deliberately.** That path is
  defence in depth for callers that are not Control; Control's own ViewModel decodes BIP-39 first
  and raises `UnknownWord(word)` / `InvalidLength(n)` / `ChecksumMismatch` locally, which is why
  the parameterised copy survives untouched.
- `RecoveryError.Generic(String)` → **two** members: `Unexpected(ErrorCopyKey)` for anything from
  the proxy, and `PhraseRejected` for the proxy's own BIP-39 refusal. ⚠ `PhraseRejected` is
  deliberately not folded into `ChecksumMismatch`: reaching it means the local decode passed and
  the proxy's did not, so naming a cause would assert one we cannot know.
- ⭐ **What that `Generic` member actually carried was usually a relay reason phrase.** The VM
  validates BIP-39 first, so the proxy's sentence was near-unreachable; the messages arriving were
  `RestSyncRelay`'s `"Recovery claim failed: 401 Unauthorized …"` diagnostics — the class §8 says
  must never reach a user. That is the real EC-1 win, and it was not visible from §4's site list.
- ⚠ **One test asserted the leak.** `RecoveryClaimImplementationTest` required the error to
  *"name the offending word"* — putting a word the user typed into their recovery phrase into
  `GenericError.message`, which `TError.messageOrNull()` feeds to the IPC failure logs. The
  replacement asserts the opposite.
- ⚠ ICU parameters were already in place (`recovery_error_unknown_word` takes `%1$s`), so §7.2's
  "ICU lands in EC-1" was satisfied by copy that already existed rather than by new work.

### EC-2 — Membership & pending requests. **The free one.**

`MembershipRepository:171` and `PendingRequestsRepository:251` — two of the four `text()` clones.

⭐⭐ **This slice has no throw sites to type and no copy to write.** The grant/admission failures
originate in `RestSyncRelay` (*"Grant drop failed: ${response.statusWithReason()}"*), which are
relay reason phrases — diagnostics that §8 says must never reach a user under any phasing. And
on the render side, **all four** consumers of `RepositoryError.Unknown` already discard the
payload (`AddFileActionsViewModel:106` → `ERROR_UPLOAD_FAILED`; the rename, create-folder and
move-picker ViewModels → `ERROR_FAILED`).

So both `text()` calls are marshalling a relay diagnostic into a field nothing has ever rendered.
Deleting the payload is behaviour-preserving *today* and closes the crossing permanently.

⚠ `RepositoryError.Unknown(message)` is constructed **33** times across three files in
`:drive-shared-logic`, with 10 test fakes in `:drive-shared-ui/desktopTest`. The site count is
large and the semantic change is nil — this is a mechanical sweep, not a design phase. Do **not**
add `Unknown(key)` as a sibling beside `Unknown(String)`: a freetext constructor that still
compiles is one authors keep reaching for (§7.5).

**Exit:** `RepositoryError.Unknown` carries no proxy freetext; two `text()` helpers gone.

#### EC-2 + EC-3 as built (2026-08-29) — **they landed together, and had to**

⚠⚠ **The unit for Drive and Contacts is the carrier, not the flow.** `RepositoryError.Unknown` is
shared by `MembershipRepository` (EC-2) and `ContactsRepository` (EC-3), so changing its payload
made both compile-or-not together; `ContactsRepository`'s `text()` helper was the *only* reader of
`RedeemContactInvite.ErrorCode`, which is EC-3's throw side. Splitting them would have meant
shipping a knowingly-broken intermediate. ⭐ The vertical slicing of §6.0 is right for *flows*, but
where two flows share a carrier the carrier wins — worth knowing before EC-4.

- **`InvalidParameter` again, and again no HR#9 bump** (EC-1's lesson held). `ErrorCode
  .ALREADY_RESPONDED` / `.GONE` became `Reasons.ALREADY_CONSUMED` / `Reasons.GONE` — two new
  constants in an existing closed set, which its own KDoc invites. The `reason` field is already a
  string on the composite, so no shape moved and `:contact-service` did not bump.
- **The blast radius was the Contacts app's whole error surface**, not two call sites: five
  `*UiState`s carried `errorMessage: String?` fed from `RepositoryResult.Failure(String)`. All 26
  assignments were mechanical (`result.message` or `null`) — no app-authored literals — so the
  migration was safe, but `:contacts-shared-logic` had **no `:core` dependency at all** and needed
  one to name `ErrorCopyKey`.
- ⭐ **§5.1's key chaining is now real, because two failures forced it.** *"No contacts found in
  that file"* and *"This contact has nothing to export"* are the app's own validation, not
  `TError`s. They became `ContactsErrorKeys` in `:contacts-shared-logic`, rendered by
  `ErrorCopyKey.contactsLabel()` in `:contacts-shared-ui`, which chains to `:ui-core`'s `label()`.
  App copy stays in the app's `strings.xml` (anchor #7); the shared catalogue keeps only the core
  taxonomy.
- ⚠ Keys that no error id derives (`CONTROL_NOT_RUNNING` for the iOS not-connected repositories)
  are declared in `ErrorCopyKey.MINTED` so `ErrorCopyCatalogueTest` still tells a deliberate key
  from a typo.
- ⭐ **The web SDK came along for free.** `tenshu-sdk-js` matched the same codes by substring and
  stopped compiling, so it now matches the typed reason and returns the **key** — which is exactly
  §7.3's answer and anchor #8's "merge, not rewrite", arrived at by necessity rather than plan.

### EC-3 — Contacts & invites

`ContactsRepository:420` and `TenshuContactsRepository:385` — the other two `text()` clones — plus
the throw side that feeds them.

⭐ The vocabulary is already decided: `RedeemContactInvite.ErrorCode.ALREADY_RESPONDED` / `GONE`
are stable codes shipped **inside** `GenericError.message`, with a KDoc that cites ADR 0016 by
name (§5.6). This phase makes them real types. The copy already has an owner too —
`ALREADY_RESPONDED` is the T-CI-01 tell and its KDoc says the UI *"must be **loud** about it"*,
which is a copy requirement written down and never rendered.

⚠ HR#9: `:contact-service` needs a `service(id, version = N)` bump and a registered
`SchemaMigration` in the same PR. `SchemaShapeBaselineEnforcementTest` names any composite that
moved without one — do **not** touch `TENSHU_STORAGE_FORMAT_VERSION`.

**Exit:** `ErrorCode` constants are gone, replaced by types; no contacts code reads
`GenericError.message`.

### EC-4 — Pairing. **The symptom, last.**

Everything §1 is about, and the largest slice by an order of magnitude: four render sites, the
three `RespondToHubPairing.ERROR_*` codes, and ~26 throw sites across ten implementations
(`ApprovePairingOffer`, `ApprovePairingRequest`, `RespondToHubPairing`, `OfferHubPairing`,
`GetOfferStatus`, `PairingAdmission`, `ReadmitDevice`, `ReregisterDevice`, `RespondToPairingOffer`,
`DenyPairingOffer`).

Three distinct sub-problems, and they are why this is last:

1. **`PairingWizardViewModel:567` is a match, not a render** (§4). It maps
   `message.startsWith(ERROR_VERIFY_FAILED)` onto a typed `PairingScanError`. Converting the
   codes to types deletes the string-matching outright — the cheapest part of the phase.
2. **`ApprovePairingOfferImplementation`'s sentence is good copy in the wrong place** (§1). Give
   that error its own type and its own catalogue entry: **relocate the sentence, do not lose
   it.**
3. ⚠⚠ **`ControlSurface.pairingErrorMessage` is a passthrough** — `= state.error`, a raw `String?`
   straight from `PairingWizardUiState` to SwiftUI, with a KDoc reading *"already user-facing on
   the VM side"* (§5.5). Fixing the Compose side alone leaves iOS rendering the proxy's English
   forever. **Both platforms in this phase or neither.**

⚠⚠ **This phase cannot be verified from a Linux container.** It reshapes sealed members
`ControlSurface` matches exhaustively and changes `PairingWizardUiState`'s shape — per `AGENTS.md`
that exact class of change left `fit-ios` uncompilable behind a fully green JVM gate.
`iosKotlinCheck` **and** `scripts/ios-smoke.sh --build-only control` are required, both macOS-only.
A session that writes this on Linux must say so rather than cite the gate. HR#9 on
`:sync-service` as in EC-1.

⭐ Consider splitting on the sub-problems above if the diff gets unreviewable — (1) is
independently shippable and strictly subtractive.

**Exit:** §1's sentence renders from a catalogue key on both platforms; no app module reads
`GenericError.message` anywhere.

#### EC-4 as built (2026-08-29)

⭐ **Its reason set is a `TConstant`, not a `TString`.** Raised in review: the platform already has
an enum-ish schema shape (`SyncStrategy`, `ConflictStrategy`), and anchor #4 asks for exactly that.
It buys three things a constants-object cannot — exhaustive `when`s at every render site, a schema
id per member (so a copy key could derive *per reason* rather than per error type, removing the
hand-written reason `when`s), and no way to pass an arbitrary string. ⚠⚠ The cost is skew and it is
**unenforced**: `SchemaShapeBaseline` pins composites, so adding a member trips nothing, but an
un-rebuilt client throws *"No constant factory registered for type"* rather than degrading. Adding
a member is a wire change — bump with it. ⭐ That trade is affordable here (pairing is
device-to-device inside one fleet, and HR#9 moves clients in lockstep) and is **not** automatically
affordable for the four `TString`-reason types elsewhere; `docs/tech-debt.md` records why a
consistency sweep is the wrong response and service maturity is the criterion.

⭐ **The track's only new composite, and its only House Rule 9 bump.** EC-1's lesson ("check
`InvalidParameter` first") held three times and broke here: the caller's arguments are fine and
what failed is the state of *this device* or of the offer, which is not a bad parameter. So
`PairingFailedError` — a `reason` field over a closed set — with `:sync-service` 3 → 4, an empty
migration step (additive: one composite, nothing reshaped), and a regenerated shape baseline.
⚠ `TENSHU_STORAGE_FORMAT_VERSION` deliberately did not move.

- **§1's sentence was relocated, not rewritten.** It now lives in `:control-shared`'s
  `strings.xml` against `Reasons.ADMITTER_UNRECOGNISED`, and in the iOS twin. `PairingError` on the
  VM side keeps it a distinct member rather than folding it into `Unexpected` — the specific,
  actionable copy was the thing worth saving.
- ⭐ **The relay-unsupported code did not need a new reason at all**: `RelayRouteUnsupportedError`
  already existed for exactly that, so `ERROR_RELAY_UNSUPPORTED` became a type the client already
  knew how to match.
- ⚠ **Two guards caught the bump before review did.** `RealmSchemaEpochSerializationTest` failed on
  the declared version, and `SchemaShapeBaselineEnforcementTest` refused the unpinned composite and
  printed the regeneration command. The regenerated baseline is **1 new type + 111 version-column
  propagations + 0 reshapes** — checked, because a 223-line diff for one composite deserves it.
- ⚠ **A test asserted §1's symptom as a contract**:
  `OfferPairingRoundTripTest` required the message to *contain* "This device is no longer
  recognised" and *not* contain "401"/"responder". The typed replacement asserts the reason and
  that the error carries **no message field at all** — the same guarantee by construction rather
  than by substring.

#### EC-5 as built (2026-08-29)

- **The census is clean.** No app module reads a `TError`'s message. What remains is
  proxy-internal diagnostics (`RelayUnauthorized` / `EntitlementRejection` marker matching, log
  lines) and `tenshu-sdk-js`'s own helper — both blessed by §8.
- **§5.0 is now stated in [`docs/agent.md`](../agent.md)**, with the process/package reasoning, the
  reviewer's test, the anchor-#5 carve-out and the four practical rules (throw typed, carry a key,
  render app-side, `GenericError` is developer-only). ADR 0016's header records that anchor #4's
  render half is built. ⭐ A rule that lives only in a proposal binds nothing — this was the point
  of the phase.
- ⚠ The guards (parity, completeness) shipped in EC-0 instead, for the reason recorded there.

### EC-5 — the sweep, and writing the rule down

1. Re-run §4's census. Every remaining reader of a `TError`'s message is either a developer
   surface (`hub/HubPairingCli.kt:82`) or the web question (§7.3) — assert that and record the
   list.
2. ⚠ `TError.messageOrNull()` in `:core` is the sanctioned best-effort extractor and the obvious
   next thing an author reaches for once the direct read is gone. Its KDoc should say it is for
   logs and IPC diagnostics only.
3. **Write §5.0 where new code will meet it**: `docs/agent.md`, and ADR 0016's Consequences
   section, which currently says the audit is needed without saying what the rule is at the
   ViewModel layer. A rule that lives only in a proposal binds nothing.

⭐ This is also the point at which the dropped lint becomes cheap to reconsider (§8): a check
that starts green and stays green is a different proposition from one that lands on a dirty tree.

**Exit:** the census is empty of user-facing readers; §5.0 is stated in `docs/agent.md`.

## 7. Open questions

1. ~~**Does `TError.label()` belong in `:ui-core` or in each `*-shared`?**~~ **Closed in §5.0/§5.1.**
   Neither, as posed. Two things settle it: no render site can link a Compose module at all
   (§5.1), and §5.0 puts every localised string app-side at the VM layer or above. So `:ui-core`
   owns the Compose lookup, the key derivation is a Compose-free string transform, and — because
   the key is *derived* rather than tabulated — there is no catalogue table needing a home below
   the app layer at all. The service-specific tail (a pairing refusal has no `:core` id) is
   answered the same way: its id derives its key like any other, and whichever app renders it
   owns the entry. Revisit at EC-3, the first phase with a second contributor to prove it against.

2. ~~**Do we need the argument-carrying case in the first phase, or can it wait?**~~ **Answered,
   and the vertical re-cut moved it earlier.** EC-0 needs no parameters — Settings → Errors is a
   plain lookup. But EC-1 does: `RecoveryError.UnknownWord(word)` and `InvalidLength(wordCount)`
   already interpolate, and `ControlSurface` interpolates the same two values again. So **ICU
   formatting lands in EC-1**, the pilot. ⭐ That is an argument *for* Recovery as the pilot
   rather than against it: the parameter case is the part of §5.2 most likely to be got wrong,
   and it gets exercised on a one-throw-site flow instead of on pairing.

3. **What does the web side do?** Anchor #4 binds the proxy, and `web/` renders errors from the
   same typed values over the SDK (`TErrorJs.kt:13`, `Contacts.kt:272`). Out of scope here, but
   §5.1 makes it cheap: the JS SDK sees the same schema ids, so the same derivation gives it the
   same keys, and an i18next catalogue keyed identically is the n=2 "merge, not rewrite" that
   anchor #8 asked for. **The iOS half of this question is no longer deferred**: it
   is EC-0's third artefact (§5.5).

4. **Does `ErrorsScreen` keep its id-derived fallback?** New. §5.1 argues yes — a developer
   surface and a user surface want different fallbacks — but that means `label()` takes a
   fallback mode, or `:ui-core` exposes two functions. Decide in EC-0; it is a one-line shape
   choice, but making it silently is how the Errors screen loses its readable labels.

5. **Does `RepositoryError.Unknown` keep a payload at all?** New. It is constructed 33 times in
   `:drive-shared-logic` and every consumer discards the value (§6 EC-2), so the honest options
   are "no payload" and "an `ErrorCopyKey`", not "a better string". Worth deciding explicitly,
   because dropping it is the smaller diff *and* the one that removes a freetext channel — the
   two usually pull apart.

## 8. Non-goals

- **Not a second locale.** Everything here ships English-only; the value is the *seam*, so that
  n=2 is a catalogue swap rather than an archaeology exercise.
- **Not the ~70 developer-facing sites.** ADR 0016 explicitly blesses them.
- **Not a lint, for now.** v0.1's fourth phase proposed a Detekt rule (or a source-tree check) failing on
  `(as? GenericError)?.message`. Dropped from the plan: there is no Detekt in this repository at
  all, and the source-tree alternative would be a fourth checker in a family
  (`check-launch-registration.py`, `tresult-audit.py`, `check-agents-md-size.py`) where **none of
  the existing three runs in `scripts/run-gate.sh` or any workflow** — so it would enforce
  nothing while costing an allowlist to maintain. ⭐ v0.1's own reasoning still holds and is the
  reason this is "for now" rather than "no": a lint is unenforceable until EC-0 gives authors
  somewhere to put copy, and one that cannot be satisfied gets suppressed. Revisit after EC-5,
  when the tree is clean and the check would start green — enforcing a rule nobody is currently
  breaking is a different and much cheaper proposition. Until then §5.0's test is a review rule.
- **Not the proxy's own rendered UI.** Control's foreground-service notifications and
  `AndroidPendingRequestNotifier` localise inside proxy modules and stay that way — ADR 0016
  anchor #5, and no proxy boundary is crossed (§5.0's carve-out).
- **Not the relay's own strings.** The relay's HTTP reason phrases are diagnostic and reach the
  client as failure detail; the client must never render them either (a raw
  `401 Unauthorized — Unknown responder device` reaching the pairing screen is what
  `ApprovePairingOfferImplementation`'s wrapper was added to stop). They are covered by the same
  §5.3 rule without needing relay changes.
