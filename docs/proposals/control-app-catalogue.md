# One app catalogue — converging identity, manifest and attestation

**Status:** **Draft v0.1 — design, no code (2026-07-28).** Opened after a live incident on the
maintainer's Mac, and after the desktop IPC identity work
([`desktop-ipc-client-identity.md`](desktop-ipc-client-identity.md) P0–P3) added a
*fourth* table keyed on `appId`. §1–§3 are verified fact; §4 onward is a direction.

Sibling docs: [`control-app-registration.md`](control-app-registration.md) (the synced record),
[`tenshu-rei.md`](tenshu-rei.md) (the eventual publication source).

---

## 1. The incident, and why it is not just a one-off

Control's tray listed Home, Maps and Timeline as `app.tenshu.home`, `app.tenshu.maps`,
`app.tenshu.timeline` — raw ids — while Drive and Fit showed correctly.

Root cause, verified by extracting `BundledAppManifestSource.class` from the installed bundle:
the desktop Control in `/Applications` was built **before** Maps, Timeline and Home were registered as first-party apps, so its compiled-in
manifest table held only `drive` and `fit`. The *registration rows* for the other three had
synced in from the Android device, whose Control does have them. `TenshuAppRegistry` then does:

```kotlin
name = manifest?.name ?: appId      // TenshuAppRegistry.toInstalledApp
```

Rebuilding Control fixed it. But three properties make this a class, not an incident:

1. **It is silent.** The fallback renders a plausible-looking string, so it reads as a *naming*
   bug rather than "this Control does not know that app".
2. **It recurs by construction.** Desktop Control lags Android Control every time a
   first-party app is added, because manifests are compiled into the *reader*.
3. **It is the visible face of a structural weakness** — several tables keyed on `appId` by
   string equality, joined at runtime, degrading quietly when a join misses.

## 2. Ground facts (verified 2026-07-28)

- **The manifest table is platform-neutral.** `BundledAppManifestSource` lives in
  `control-shared-logic/src/commonMain`, so Android Control and desktop Control compile in the
  *same* set. ⚠ **This kills a plausible worry:** "the app has no Mac version" or "the user chose
  not to install it on the Mac" does **not** produce a missing manifest. Every Control knows
  every first-party app's manifest regardless of where it is installed.
- **Registrations are realm-wide, not per-device.** Rows sync, so Control on any device lists
  apps installed on the others; `InstalledApp.deviceCount` already models that ("registered on
  N devices"). *Not installed here* is a device-count fact, never a manifest fact.
- **Four tables now key on `appId`:**

  | table | module | question |
  |---|---|---|
  | `BundledAppIdentityResolver` | `:control-shared-logic` | who is calling? |
  | `BundledAppManifestSource` | `:control-shared-logic` | what is it called, what may it do? |
  | `FirstPartyTeamPolicy` | `:control-desktop` | may this signature vouch for that id? |
  | `AppRegistration` rows | synced | is it installed, on which devices, attested? |

- **The resolver→manifest join is guarded by hand** (`BundledAppTablesTest`
  `everyResolvableAppId_hasABundledManifest`) — a hand-written test standing in for an
  invariant that wants to be structural.
- ⚠ **`AppsScreen` already renders `UnverifiedBadge()`** when `!app.verified`, and `verified`
  aggregates `rows.all { it.verified }`. Android does not yet make an attestation claim, so on
  any realm with an Android device **every app shows the badge** — it currently carries no
  signal. That is the strongest argument for the Android follow-up, and it constrains §4 D3.

## 3. What a missing manifest can actually mean

| cause | reachable today | right response |
|---|---|---|
| **(a) Reader skew** — this Control predates the app | **yes — the observed case** | render honestly; suggest updating Control |
| **(b) Third-party app** — not in the bundled table by construction | not yet (needs Rei) | render honestly; it is *unknown*, not broken |
| **(c) Allowlist entry with no manifest** — an internal mistake | yes | must be **impossible**, not handled |

⚠ Note what is **absent**: "the app has no build for this platform" (§2). Conflating it with the
above is what makes the raw-id fallback feel like a UX question when it is really a data question.

## 4. Direction

**D1 — one catalogue; derive the tables.** A single first-party declaration per app:

```kotlin
data class FirstPartyApp(
    val appId: String,
    val manifest: AppManifest,
    val clients: List<ClientBinding>,
)

sealed interface ClientBinding {
    data class AndroidPackage(val packageName: String) : ClientBinding   // uid-attested
    data class MacCodeSignature(val identifier: String) : ClientBinding  // requirement-attested
    // iOS route-1 contributes none: in-process, so there is no IPC peer to identify.
}
```

`AppIdentityResolver`, `AppManifestSource` and the attestation policy all become **derived
indexes** over one list. Consequences:
- cause **(c) becomes unrepresentable**, and `BundledAppTablesTest`'s hand-written join guard
  turns structural;
- adding an app is **one entry** instead of edits to two tables in two files (registering Maps, Timeline and Home paid that
  cost for three apps);
- it tightens a loose end from the identity work: `FirstPartyTeamPolicy` accepts *any*
  identifier signed by our team. ⚠ To be precise — this is **not** an impersonation hole. A
  binary's identifier is fixed by its signature, so claiming `app.tenshu.fit` requires a binary
  *signed by us* carrying that identifier, which nobody outside can produce. The narrowing
  guards against **our own** mistakes (a mis-set `bundleID`, a mis-signed build) and makes the
  allowlist and the attestation agree by construction. Defence in depth, not a closed breach.

**The mac-specific part is the mechanism, not the declaration.** FFM + `Security.framework` +
audit tokens stay in `:macos-helpers` behind `PeerCredentialVerifier` / `PeerAttestationProbe` —
that seam is already right. What converges is the *declaration*, which is platform-neutral.

### 4.1 ⚠⚠ What the signature gate does and does not buy

Worth stating plainly, because "an app could claim to be Fit and read its data" has two halves
and the gate only closes one.

**Closed — impersonation by an outsider.** A non-Tenshu process cannot present itself as Fit:
that needs a binary signed by our Apple team carrying Fit's identifier, and nobody outside can
produce one. This was a *live* hole until P3 — any same-user process could reach `bridge.sock`
and read Drive plaintext (T-C-15) — and it is now enforced on both desktop sockets.

**⚠⚠ NOT closed — one admitted app reading another's data.** Verified 2026-07-28:
`AppGrant` rows are written, synced and rendered, but **nothing consults them on the dispatch
path**. `ProxyGateway.call` resolves the session and executes; there is no authorization step.
So **any** admitted first-party app can call **any** operation: Drive-desktop can read Fit's
health data today. The gate authenticates *who is calling*; it does not constrain *what they
may do*.

Two further facts keep that from being a quick fix:
- **The permission list cannot express reads.** Apps read through service-data relations +
  `select`/`flow`, and relations are not operations — so an `operationId`-keyed list has nothing
  to name for the majority of what an app actually does. Drive only looks well-described because
  its reads happen to go through a real op (`tenshu/file/getData`).
- **On iOS route-1 this is an explicit decision, not an omission.** First-party apps are
  accepted as realm **co-owners** holding the whole content key
  ([`ios-first-party-route-1-migration.md`](ios-first-party-route-1-migration.md) P0-C); each app
  *is* its own in-process proxy, so proxy-mediated per-app permissions are not even expressible
  there.

⇒ **The platform's current boundary is Tenshu-vs-not-Tenshu, not app-vs-app.** That is
defensible while every app is first-party and we ship them all; it becomes non-negotiable for
third-party. Inter-app isolation is a separate, sizeable track — grant enforcement at dispatch
*plus* a permission model that can express reads — and should not be smuggled in under a
catalogue refactor. §6 Q4.

**Consequence for D2/D3:** rejecting a manifest-less app would buy **no** isolation, because
nothing downstream enforces the manifest it is missing. That is the substantive reason the
"reject in production" instinct does not pay off here — not merely the UX argument in D3.

### 4.2 Where enforcement is possible — and what we already promise

⚠ **Correction to a natural assumption: desktop is on the *enforceable* side.** Verified —
`drive-desktop` and `fit-desktop` depend on `:core-common` only, on **no** proxy module, and
every operation crosses Control's `ProxyGateway.call(clientId, …)` exactly as Android's Binder
path does. The dividing line is **in-process vs out-of-process proxy**, not Android vs the rest:

| surface | proxy lives | identity | enforceable? |
|---|---|---|---|
| Android | separate process (`ProxyService`) | Binder uid → package (OS-attested) | **yes** |
| Desktop (macOS) | separate daemon (Control) | code signature (attested, P3) | **yes** |
| iOS route-1 | **in-process, one per app** | none — the app *is* the proxy | **no** |
| Web (in-process `JsRuntime`) | in-process | — | **no** |
| iOS route-2 / EPC | Control hosts | EPC pairing | **yes** |

So the desktop identity work (P0–P3) is not merely a naming fix: it is the **prerequisite** that
makes desktop enforcement trustworthy, because enforcing against a self-declared `clientId`
would have enforced nothing.

#### ⭐ 4.2.1 The split is first-party vs third-party, not platform vs platform

Reading that table as "some platforms cannot enforce" leads to designing per-platform
permissions. It is the wrong cut, because **the two unenforceable rows are first-party-only**:

- *"EPC is the third-party connection protocol; every first-party iOS app belongs on route-1"*
  (settled 2026-07-18, `ios-first-party-route-1-migration.md`) and *"proxy-mediated EPC is the
  third-party default"* (`ios-proxy-architecture.md` §3). So a **third-party** iOS app is
  proxy-mediated — enforceable.
- Route-1's in-process design comes with first-party apps accepted as realm **co-owners**
  (P0-C). They were never going to be per-app permission-gated; that is the deal route-1 made.

⇒ **Third-party apps are proxy-mediated on every surface** (Binder / UDS / EPC / the web
bridge), and first-party apps are co-owners. So *"enforceable on some platforms only"* is an
artifact of applying a third-party model to first-party apps that were explicitly exempted from
it. ⚠ One leg unverified: the third-party **web** path (separate-origin page → apex over
postMessage) is *inferred* to be mediated, not confirmed.

**Consequence — D6:** permissions are a **third-party** feature. The model is **one decision per
`(app, capability)`, realm-wide, enforced at the proxy** — not per platform and not per device:

- **Per-platform** keys on a proxy for topology (iOS third-party is EPC, so the correlation
  breaks exactly where third-party arrives); cross-platform derivation between differing
  enforceable sets drops grants silently; and on an unenforceable surface it shows *nothing*,
  which reads as *"this app has no access"* — the gap becomes **invisible** rather than
  explicit. That is the same silent-failure class as §1's raw appId, the unread `not_found`
  (C6) and the unenforced grant itself.
- **Per-device** breaks the one thing per-platform was reaching for: a true uninstall/reinstall
  mints a new device id (only `install -r` preserves identity), so the grants would not come
  back.
- **Realm-wide** gives reinstall-restore for free, survives new devices, and keeps the user's
  mental model intact — a permission is about *their data*, not about a device.

⚠⚠ **The live problem is not a future platform split — it is a promise we already make and do
not keep.** `AppDetailScreen` wires `onSetGrant = viewModel::setGrant`, which writes a real,
synced `AppGrant` row through `SetAppGrant`. No dispatch path reads it (§4.1). **A user who
switches a permission off today receives no protection, on any platform.** That is worse than
an absent feature: an absent control makes no claim, whereas this one silently implies a
boundary that does not exist.

⇒ **Priority order this implies** (and it inverts the obvious one):
1. **Stop implying a guarantee we do not provide.** Cheapest, most urgent, and independent of
   every architectural question below. Either the permission surface states plainly that it is
   not yet enforced, or it does not present as a control.
2. **Enforce where the architecture allows** — Android and desktop, both out-of-process. Real,
   buildable, and now unblocked on desktop.
3. **Decide what iOS route-1 says.** It can never enforce without undoing first-party
   co-ownership (a settled decision). So either the UI is explicit about *where* a permission
   applies, or the model becomes per-surface. A permission that is honoured on two surfaces and
   silently ignored on a third is the worst of the three options.

⇒ "mostly a UX question" is right about **priority** and wrong about **substance**: on two of
four surfaces this is a real, buildable control; the UX question is how to be honest about the
other two.

**D2 — build-time *and* runtime, but the runtime half applies to a different population.**

The invariant has two sides, and an earlier draft of this doc had it as a flat "build-time, not
runtime", which was wrong:

- **Build time** — the catalogue guarantees every *declared* app has a manifest (D1).
- **Runtime** — the gate can guarantee only *declared* apps connect.

⭐ **These are the same fact, and with D1 the second falls out for free.** Once the attestation
policy is derived from the catalogue, the lookup that answers "may this signature vouch for this
identifier?" is the *same lookup* that yields the manifest. An identifier that resolves to
nothing has no manifest **and** no business connecting, so the gate rejects it — no new
mechanism, just a narrower policy on the check P3 already performs. One lookup, three answers:
who is calling, may they, and what are they.

⚠ **This closes a real gap that exists today.** Verified: `AppIdentityResolver.resolveAppId` is
consulted **only** by the registration trigger; the dispatch path (`proxyGateway.call(clientId,
…)`) never consults it. So the allowlist gates **registration, not access** — an identifier
signed by our team but absent from the allowlist is admitted, gets **full proxy access**, and
never registers, so it is simultaneously *unconstrained* and *invisible in the Apps list*. That
is strictly worse than rejecting it. Deriving the policy from the catalogue closes both halves at
once.

**But the runtime check applies to connecting peers only.** The two populations are different:

| population | is anything being asked of us? | right response |
|---|---|---|
| a **connecting peer** on this device | yes — it wants to call operations | **reject if unknown** |
| a **synced registration row** from another device | no — it is a record, not a request | render honestly (D3) |

A row written by Control-on-Android is not a peer here; there is no signature to check and
nothing to deny. Rejecting there is not enforcement, it is hiding — which is the §3(a) skew case
and D3's subject. So "reject the unknown" and "never hide the unrecognised" are not in tension:
they address different sides.

⚠ **Two implementation traps for the runtime half:**
- **Not every first-party peer is an app.** Control's own tray agent signs as `Identifier=tray-agent`
  (team `FAYT82GWD7`, verified) and connects to `bridge.sock`; the File Provider `.appex` is the
  same shape. A naive per-*app* catalogue policy would reject Control's own helpers. The catalogue
  needs a peer kind (app vs first-party helper), or the bridge keeps a broader policy than the
  binary transport. ⚠ Since 2026-11-05 it does both: `bridge.sock` alone also admits Control's own
  identifier (`FirstPartyDesktopPeers.BRIDGE_IDENTIFIERS`), so a second Control refused by the
  single-instance guard can raise the running one (`desktop-macos-distribution.md` §2.11).
- **Rei on the connect path needs an offline answer.** The same lookup generalises to third-party
  via a remote Rei — but a network round-trip at `accept()` means an unreachable Rei would reject
  legitimate apps. Needs a cache and a defined offline verdict before that path is load-bearing.

⚠ One enforcement point still does **not** work: **at registration**. The reader is not the
writer — desktop Control never writes the Maps row, Android Control does — so rejecting on the
write side cannot fix a read-side skew.

**D3 — never hide an unrecognised app.** ⚠ This is a deliberate push-back on "reject it in
production". Control's entire job is to show what can touch the user's data; silently omitting
an app that holds a realm registration (and possibly grants) is the **worst** failure direction
for a control plane. An app we cannot name is exactly the one the user most needs to see.

So: always render, and make the *state* legible rather than faking a name.
⚠ **Do not reuse "unverified" for this.** P3 gave that badge a precise meaning — *the peer's
code signature was not attested*. "We have no manifest for this id" is a different question
(*what do we know about it*, not *who is it*), and overloading one badge with both makes a
security signal ambiguous. A distinct state — `Unrecognised` — with the raw id shown
deliberately, plus a hint toward the likely cause (§3a: "this Control may be out of date").
§2's finding that the unverified badge is currently always-on is a live example of what a
signal-free badge costs.

**D4 — the deep fix for skew: let the manifest travel with the registration.** ⭐
Skew exists *because* the manifest is compiled into the reader rather than accompanying the
record. Three options:

| | shape | cost |
|---|---|---|
| **(i)** | status quo + D3's honest state | cheapest; skew permanent but legible |
| **(ii)** | manifest (or a ref) rides the synced registration | reader renders correctly at any version |
| **(iii)** | Rei-served publication | the end state, required for third-party anyway |

⚠ (ii) is not app-self-assertion: the row is written by the **proxy** from its own compiled-in
table, so it is first-party-vouched within the already-trusted device set — the same basis as
every other synced row. ⚠ But it reshapes a stored row ⇒ **House Rule #9 format bump**.
Recommendation: **(i) now**, design toward (ii)/(iii); do not bundle a format bump into what is
otherwise a UI fix.

**D5 — the tray lists what it can *launch*; Control lists what exists in the realm.**
(Answers §6 Q2.)

Observed: the tray lists apps registered only on the phone, and clicking them does nothing.

The principle that makes the split non-arbitrary: **the tray's only app action is
`launchApp`**, so it is a launcher, and a launcher should offer only what it can launch.
Control → Apps is the realm *inventory* — the place where "you have Timeline on your phone" is
a useful, actionable fact (grants, per-device removal). Two surfaces, two jobs.

**No schema change is needed** — the data already exists:
- `AppRegistration` is keyed per device (`compositeKey = "$deviceId/$appId"`, plus a
  `DEVICE_ID` field), so *which* devices is already recorded — only `deviceCount` is surfaced;
- `SyncManager.currentDeviceId` tells Control which device it is;
- the identity-service `Device` roster carries a `name` for attribution in Control's list.

So the tray filter is `rows.any { it.deviceId == currentDeviceId }`, and Control's list gains
device names where the roster resolves.

⚠ **Fix `fireAndForget` regardless of the filter.** `ControlDataBridge.launchApp` *already*
returns `not_found` when no bundle is installed (it is even unit-tested), but the tray calls it
through `fireAndForget`, discarding the reply — the daemon reports the error correctly and the
tray drops it. Filtering removes the common case; stale rows and races will still produce
unlaunchable entries, and a launcher that silently does nothing is the worst failure mode. The
filter hides the symptom; handling the reply fixes the defect.

⚠ **Three caveats on what "installed here" actually means:**
- **It means "has connected here at least once."** Registration fires on session establishment,
  so an app installed but never opened has no row. Defensible, but the copy should not say
  *installed*.
- **Uninstalling does not remove the row.** Nothing can detect an OS-level uninstall, so the
  inventory is really "ever connected, not explicitly removed". ⇒ hiding non-local apps from the
  tray makes a stale row invisible on the surface the user sees most often, which is precisely
  why Control → Apps must stay the place to see and clean up (per-device `RemoveApp` already
  exists for this).
- **The device-name join can miss.** The `Device` roster has no regeneration path after a store
  wipe, so attribution needs a fallback ("another device") rather than a blank or a crash.

**Rejected alternative:** showing non-local apps greyed-out in the tray. It preserves
discoverability, but a menu-bar dropdown is a compact action surface, and a permanently disabled
row is noise in it. The discoverability is not lost — it moves to Control → Apps, where it comes
with the device attribution that makes it meaningful.

## 5. Phasing

- **C3 — legible unknown state. SHIPPED 2026-07-29.** `InstalledApp.recognised` (= a manifest
  was found) drives a new `UnrecognisedBadge` on the list and an explanatory line on the detail
  screen; the `name = manifest?.name ?: appId` fallback stays, but the bare id is now *labelled*
  instead of passing as a name. ⚠ A separate badge from `Unverified`, and deliberately neutral
  rather than error-coloured: `verified` answers *who is this* (a security signal), `recognised`
  answers *what do we know about it* (usually just a stale Control) — rendering both in red
  would teach the user to read routine staleness as a threat. At most one badge shows, and
  unrecognised wins, because it explains the bare id the headline is displaying. The copy names
  the likely cause without asserting it, since §3(a) skew and §3(b) third-party are not
  distinguishable from the row. Mutation-checked; Android smoke gate passed.
- **C1 — the catalogue. SHIPPED 2026-07-29.** `BundledAppCatalogue` holds one `FirstPartyApp`
  per app (manifest + `ClientBinding`s); `BundledAppIdentityResolver` and
  `BundledAppManifestSource` are derived views. §3(c) is now unrepresentable, and
  `everyResolvableAppId_hasABundledManifest` was **deleted deliberately** — it stood in for an
  invariant the type now carries. ⚠ The resolver's `*_CLIENT_ID` constants went with it: keeping
  them would have reinstated the same two-places-joined-by-a-string, with the drift merely moved
  from the table to the constant. ⚠ The manifests stay in `BundledAppManifestSource`'s companion
  (only the *pairing* moved) — the dependency runs one way, catalogue → manifests, so
  initialisation is safe. **New failure modes get real guards:** duplicate appId and two apps
  claiming one client id both fail loudly, and the checks are extracted as `manifestIndexOf` /
  `clientIndexOf` so they are *exercised* by tests rather than merely present in an object
  initialiser. Android smoke gate passed.
- **C2 + C2b — derive attestation, and reject the unknown. SHIPPED 2026-07-29.** They landed as
  one edit because they *are* one: once `FirstPartyTeamPolicy` consults `FirstPartyDesktopPeers`
  instead of vouching for any identifier, an undeclared one gets no team and the already-strict
  gate (P3) closes the channel. Closes the §D2 gap where an identifier signed by our team but
  absent from the allowlist was admitted with **full proxy access and no registration** —
  unconstrained and invisible at once, since the allowlist gates *registration*, not *access*.
  ⚠⚠ The peer-kind distinction was **required, not deferrable**: Control's own tray agent signs
  as `tray-agent`, carries no manifest and never registers, yet drives `bridge.sock` on every
  poll — a policy derived from the app catalogue alone would have rejected Control's own helper
  on first launch. So the admissible set is catalogue Mac bindings **+** an explicit helper set;
  helpers are deliberately *not* catalogue entries, because an entry pairs an identifier with a
  manifest and a helper has nothing to describe. ⚠ Pipe-only subprocesses (`qr-scan-helper`,
  `image-decode-helper`) excluded — they never reach a socket. ⚠ The File Provider `.appex` is
  omitted rather than guessed: an absent entry fails loudly, a wrong one would look correct.
  ⚠ The derivation lives in `control-shared-logic` desktopMain rather than `control-desktop`
  precisely so it is testable — `control-desktop` has no test source set, and this set decides
  who may reach the proxy. **Verified live in both directions**: an identifier signed by our team
  but never shipped is now `CLOSED_BY_SERVER`, while Drive, Fit and the tray all still connect.
- **C2c — Maps/Timeline/Home gain their Mac bindings. SHIPPED 2026-08-10.** Their entries carried
  only an `AndroidPackage`, so on desktop they attested fine (once signed) and then resolved to no
  app — ⚠⚠ **admissible but unregistered**, which is the *quiet* half of the §D2 gap rather than
  the loud one: no Apps-screen presence, no manifest, no name. The blocker was never the catalogue
  edit but its precondition — a bundle signed with a real identity — and only `:control-desktop`
  had an umbrella that signed, so every other app shipped ad-hoc. A shared `packageDesktopApp`
  (`gradle/desktop-app-signing.gradle.kts`, `docs/tech-debt.md`) fixed that for all nine desktop
  modules, after which the bindings are one line each. ⚠ The pinned value is the **code-signing
  Identifier**, not the Android package (`app.tenshu.maps.ios` vs `app.tenshu.maps.android`; every macOS id is the iOS one since C4, `desktop-macos-distribution.md`, 2026-11-05).
  **Verified live**: this Mac's registrations went from `drive` alone to all five of
  `drive`/`fit`/`home`/`maps`/`timeline`. ⚠ Test note — `anAppWithNoMacBindingIsNotAdmissible`
  filtered the *real* catalogue, so it silently went vacuous the moment the last unbound app gained
  a binding (an empty `forEach` passes for a derivation that admits everything); the rule now runs
  against a synthetic catalogue via an extracted `macIdentifiersOf`, with the real catalogue's state
  asserted separately. ⚠ Still out: **Mia** (opens no proxy session — an entry would be
  unreachable) and **Contacts/Calendar** (no manifest, so not a one-line addition).
- **C4 — Android attestation claim. SHIPPED 2026-07-29.** `BinderAttestedClients`
  (`:control-shared-proxy` androidMain) implements `PeerAttestationProbe`; `ProxyService`
  records each uid-derived package as it resolves it. ⚠ **Recorded, not answered `true`
  unconditionally** — Control's own in-process client does not come through the Binder path, so
  a blanket `true` would be *accidentally* correct via a fact that lives in another file (it is
  simply not in the allowlist). ⚠ Soft edge documented: `getPackagesForUid` returns every
  package sharing a uid and the first is taken — the uid is attested, the name is arbitrary
  *within* a `sharedUserId` trust group (deprecated; no first-party app uses it). Android smoke
  gate passed.
- **C5 — manifest delivery (D4 ii/iii).** Only with a real driver: third-party apps, or skew
  proving costly in practice. Owes a format bump.

- **C6 — tray/Control surface split (D5). SHIPPED 2026-07-29.** `InstalledApp` gained
  `installedOnThisDevice`, computed in `TenshuAppRegistry` from the per-device rows against
  `currentDeviceId()` (the accessor the class already had for per-device uninstall — no new DI,
  no schema change); the bridge sends it; the tray filters on it and now **reads
  `launchApp`'s reply** instead of discarding it, showing "couldn't open" on the row rather than
  appearing inert. Device-name attribution in Control's list deliberately deferred: it needs the
  identity-service roster join plus D5's fallback, and the split works without it.
  ⚠ Absent field ⇒ treated as local Swift-side: an older daemon does not send it, and a tray
  listing *nothing* would be a far worse failure than one listing an app it cannot launch.
  **Verified live** over the bridge — home/maps/timeline `false` (Android only), drive/fit
  `true` — with both the registry and bridge tests mutation-checked.

- **C7 — the tray asks LaunchServices (amends D5/C6). 2026-10-11.** C6's filter read
  *"has connected here at least once"*, which is wrong in both directions: an app installed but
  never opened has no registration, and an uninstalled app keeps the one it wrote. The tray now
  asks LaunchServices where each bundle is on every refresh, so a new install appears the next
  time the menu opens — macOS has no public *app installed* notification, and asking at the
  moment of use needs none. `listApps` supplies the candidates: registered apps plus every
  catalogue app with a macOS bundle that never connected (`registered: false`).
  The rule is deliberately unequal: a copy in an Applications folder counts on its own; a copy
  anywhere else counts only for an app registered on this Mac, because LaunchServices also
  remembers every stray `build/` output. Trashed copies never count.
  ⚠ **Disk presence only decides what the menu offers.** It is never written back as an
  `AppRegistration`: a bundle id is self-asserted, and that row means an attested peer connected.
  ⚠ **LaunchServices can hold a broken record.** A `cp -R` install was recorded half-copied
  (`no-info.plist`) and stayed unknown by bundle id — TenshuPhotos, found while building this.
  `scripts/reinstall-all-apps.sh` now runs `lsregister -f` after each copy; the tray has no
  fallback, since `launchApp` opens by bundle id and would fail on the same record.
  The Swift half has no automated test (nothing in the repo tests the tray agent); the daemon
  half is covered by `ControlDataBridgeTest`.

⚠ **C0 — demote the permission surface to a declaration (§4.2, D6).** Ahead of all of the
above. Today's Apps screen presents a *control* that enforces nothing on any platform; under D6
a per-app permission control is a third-party feature, so for first-party apps a read-only
declaration is not a stopgap — it is the accurate surface. Drop the tri-state and *Revoke all*,
keep the declared list, say plainly that Tenshu does not restrict an installed app. The real
control gets built with the third-party track, where it is enforceable everywhere and needs no
per-platform hedging.
⚠ The `AppGrant` schema, `SetAppGrant` and `AppRegistry.setGrant` stay — they are what the
third-party track will use; only the UI affordance goes.

## 6. Open questions

- **Q1.** Can the UI distinguish §3(a) skew from §3(b) third-party? Partially: *unknown to the
  resolver* vs *known but no manifest* separates them, but only once third-party ids exist at
  all. Is the distinction worth showing, or is one honest "unrecognised" enough?
- ~~**Q2.**~~ **ANSWERED — D5.** The tray filters to locally-launchable apps; Control → Apps
  keeps the full realm inventory and gains per-device attribution. No schema change; the
  per-device rows already exist and only the count was being surfaced.
- **Q3.** Does the catalogue belong in `:core` (beside `AppIdentityResolver` / `AppManifest`) or
  stay in `:control-shared-logic` as a Control-owned table? Rei's arrival argues for a source
  interface either way.
- **Q4.** ⭐ **Inter-app isolation is unbuilt** (§4.1): grants are never checked at dispatch, and
  `AppPermission` cannot express reads. Does it get a track of its own, and when? It is the one
  question here with real security weight — the catalogue only makes the *declaration* coherent,
  and a coherent declaration that nothing enforces is still nothing enforced. Note the answer
  differs per platform: proxy-mediated enforcement is expressible on Android/desktop but not on
  iOS route-1, where each app is its own in-process proxy.
