# Tenshu Control — App Registration & Removal

**Status:** Schema + operations landed (Track A A0, 2026-05-31). Two product decisions
locked 2026-05-31 (silent first-run registration; per-device removal default). The
`AppRegistration` / `AppGrant` schemas, the `AppService` surface, and the `RegisterApp` /
`RemoveApp` / `SetAppGrant` operations now exist in `:core` (`org.tenshu.core.app`), with the
proxy-side relation maintainer (`initAppService`, wired into Control's `PlatformProxyModule`)
and the threat-model amendment (asset A17 + threat T-C-14). Module-home decision resolved to
`:core` (§7). Verified `:core-common:desktopTest` (`AppServiceLocalTest` — register
idempotency, `Removed` blocks re-register, removal purges grants, grant tri-state round-trip,
compositeKey DataId) + `:control-desktop:compileKotlinJvm`. The `AppRegistry` data client
(Track A A1) and the Apps list + detail + permission widget UI (A2 native / A3 web) **landed
2026-05-31**. **Outstanding:** the per-binding handshake trigger that actually *calls*
`RegisterApp` — **scoped as Track A Phase A4** (`docs/tracks/track-a-control-apps.md`), gated
by a static `clientId → appId` allowlist (`AppIdentityResolver`) rather than the full
code-signing check; `verified=false` until the Rei verifier lands. Until A4 ships, no
`AppRegistration` row is ever written, so no app (incl. Drive) appears in Control's Apps screen.

⚠⚠ **The status line above is stale on A4, and 2026-08-14 found what shipping it left behind.**
The handshake trigger *did* ship (`appRegistrationTrigger`, wired in `control-shared-proxy`), so rows
are written — but **registration fires on an app's first announced session, which for a lazily-wired
client means its first *use*, not its launch.** A desktop Mia that is installed, signed, resolved by
the catalogue and connected over UDS held **no** registration row until a message was typed into it;
the row appeared the moment one was. Two consequences are recorded in
[`tech-debt.md`](../tech-debt.md) — an untouched app is invisible to Control, and a row written while
Control is open does not reach the menu-bar tray until Control restarts. ⚠ The registration record is
per **device** (`<deviceId>/<appId>`), so a row synced from a phone does *not* make the app appear on
a Mac — which is exactly how this hid: Control's store listed Mia, and this device's tray did not.

⚠⚠ **The rule this implies, and the gate that holds it:** registration rides the announce, and the
announce rides the session's *first call* — so an app whose opening screen issues no operation is
never registered. That is a property of each app's own wiring, which no Gradle module can see, so it
is enforced by **`scripts/check-launch-registration.py`**: every app either passes `launchScope` to
`wireSchemaAnnounce` or announces from its app root. The authoring rule is restated in
[`agent.md`](../agent.md) §7, where someone building a new app will meet it; the ⚠ PENDING list in
the script is the seven apps that predate the rule and register only because their first screen
happens to load something.

**Scope:** Defines the durable, synced record of *"this app is installed in this realm"* —
how it is created on an app's first verified connection, how Control's **Apps** section
([`control-ia.md`](control-ia.md) §4) reads and removes it, and how removal behaves across
devices. New schema in `:rei-service` (or `:core` — see §7), a proxy-side write-path on the
existing IPC handshake, and a small amendment to [`sync/threat-model.md`](../sync/threat-model.md).
Touches `:control-shared` (the Apps UI consumer) and the desktop/Android/iOS IPC bindings
(the handshake is where registration is triggered).

This doc fills a gap the other docs leave open. [`tenshu-rei.md`](tenshu-rei.md) §3.6 and
[`desktop-ipc.md`](../protocol/desktop-ipc.md) §3 define how a connection is *authenticated*;
[`control-ia.md`](control-ia.md) §4 defines the *Apps UI* and lists an `install date` and an
*Uninstall* action — but nothing defines the record those read from, who writes it, or when.
This is that mechanism.

---

## 1. Motivation

### 1.1 The Apps tab has no writer

`control-ia.md` §4.1 says each app card shows *"icon, name, install date, version."* §4.2's
App Detail surfaces an `Install date` (*"when the app was added to the realm"*) and a
per-app *Uninstall* action. But there is no durable record of *"the app was added to the
realm,"* nothing that produces `install date`, and no defined moment at which Drive becomes a
listed, removable app. The Apps tab is currently a view over a record set that has no writer.

### 1.2 Connection authentication is not registration

The IPC handshake ([`desktop-ipc.md`](../protocol/desktop-ipc.md) §3) authenticates *each
connection*: peer-credential check → binary path → code-signing identity → `AppId` matched
against a Rei publication held by the local source. This is per-connection and ephemeral. It
proves *"the process connecting right now is a signed build of Drive,"* but it persists
nothing and says nothing about *"Drive is installed in this realm, the user has seen it, and
these are its grants."* We need a durable record layered on top of the per-connection check.

### 1.3 This is registration, not pairing

It is tempting to model app onboarding on device pairing
([`sync/pairing-flow.md`](../sync/pairing-flow.md)). That would be a category error. Device
pairing is a cryptographic ceremony (handshake + SAS verification + master-key handoff)
because a new device *receives the realm master key* — there is a secret to exchange. **An
app never holds key material.** It gets scoped, proxy-mediated operation access; the proxy
holds the keys and does the crypto on the app's behalf (House Rule #1 — the relay, and by
extension everything below the proxy, sees ciphertext only). There is no key exchange, so
there is no pairing.

What we need is *enrollment*: a lightweight, durable record created once trust is already
established by code-signing. The consent surface is **permissions** (`tenshu-rei.md` §2.2,
§3.3), not the connection. This framing is load-bearing for the rest of the doc — it is why
first-run can be silent (§3) and why removal is *deregistration*, not key revocation (§5).

---

## 2. The three-layer model

App state in Control decomposes into three layers, each with a different owner, scope, and
lifetime. Keeping them distinct is what makes registration and removal clean.

| Layer | Scope | Source / owner | Lifetime |
|-------|-------|----------------|----------|
| **App identity** | Realm-wide (same on every device) | The manifest / Rei publication (`tenshu-rei.md` §3.2) | Ships with the app binary |
| **`AppRegistration`** | Per `(appId, deviceId)` | Written by the **proxy** on first verified connect | Created on first run; tombstoned on removal |
| **`AppGrant`** | Realm-wide | The user, via Control's permission UI | Independent; purged on removal |

- **App identity** is everything the manifest already carries: name, icon, version, the
  derived `(serviceId, operationId, capabilityClass, gestureRequirement)` permission set.
  It is identical on every device because it comes from the signed publication, not from any
  per-device state. The Apps list renders an app's identity; it does not store it.
- **`AppRegistration`** is *the new thing this doc introduces*. It is the durable
  *"installed here"* record, keyed per app **and device** because installation is inherently
  a per-device fact — Drive may be on the laptop and not the phone. Its `createdAt` is the
  missing `install date`.
- **`AppGrant`** is the realm-level permission state (granted / denied / ask-each-time)
  already implied by `control-ia.md` §4.2 and `tenshu-rei.md` §3.3. *"Grant follows the app,
  capped by role"* was decided in `tenshu-rei.md` Q29. This doc does not redefine grants; it
  only specifies how removal interacts with them (§5).

The Apps list groups `AppRegistration` rows by `appId` and renders one card per app, showing
per-device install status underneath (*"Drive — installed on Laptop, Phone"*). Grants are
shown once, at the app level.

---

## 3. First-run registration (the flow)

### 3.1 Proxy-mediated, not app-driven

Registration is written by **the proxy**, never by the connecting app. The app cannot be
trusted to self-register — a forged or hostile client would simply assert itself into the
list. The proxy is the component that just performed the peer-credential / code-signing check
(`desktop-ipc.md` §3 step 2–4), so it is the only party that knows the connection is a
genuine signed build. Registration therefore hangs off the **successful completion of the
existing handshake**, on the proxy side.

The flow, layered onto the handshake:

1. Handshake completes as today: peer-cred verified, `AppId` established and confirmed
   against a Rei publication (`desktop-ipc.md` §3).
2. The proxy looks up `AppRegistration` for `(appId, deviceId)`.
3. **If no record exists** (neither active nor `Removed` — see §5), the proxy writes
   `AppRegistration{appId, deviceId, createdAt = now, state = Active}` via a
   `RegisterApp` operation. This write is the *"it shows up in Apps"* moment, and `createdAt`
   is the `install date`.
4. **If an active record exists**, registration is a no-op; the connection proceeds.
5. **If a `Removed` record exists**, auto-registration is *suppressed* — the connection is
   refused (or downgraded to a re-consent prompt). See §5.

Because step 3 is gated behind the handshake's signature check, a forged client (threat
[`threat-model.md`](../sync/threat-model.md) T-C-11) cannot create a registration: it never
reaches step 3. This is worth a short threat-model sub-amendment stating exactly that (§6).

### 3.2 First-run is silent — Decision (locked 2026-05-31)

**First run registers the app silently — no modal, no "Allow Drive to connect?" gate.**
The connection is already gated on peer-credential + code-signing verification against a Rei
publication; an explicit connect-consent dialog on top of that is mostly theater, and it adds
friction to the overwhelmingly common case (a user who just installed a first-party app and
expects it to work). The app appears in the Apps list immediately, with all permissions at
their safe defaults (`Picker` + `requires_user_gesture` per `tenshu-rei.md` §2.2, i.e.
nothing is silently granted). Consent happens where it belongs — the first time the app
actually invokes a permission-bearing operation.

The rejected alternative — an explicit per-app connect prompt on first run — is more legible
but redundant given the signature check, and it conditions users to click through a dialog
that never carries a real decision.

**In practice, silent ≈ first-party.** Registration is *always* silent, but the user
experiences it as silent mainly for the default apps. A third-party or non-default app
surfaces consent almost immediately anyway, because the things it needs are themselves
prompts:

- To become the **primary handler** for a data type (file manager, photos app), it must be
  explicitly granted in Control — installing never silently reassigns the slot
  (`tenshu-rei.md` Q11, *explicit user grant*).
- To read anything, it needs at least one `Ambient`/`Scoped` grant, which is a consent
  surface on first use (`tenshu-rei.md` §3.3).

So registration stays uniform and frictionless, while a non-default app still hits a clear
consent moment the first time it tries to *do* anything — without registration itself having
to carry a gate.

### 3.3 Developer-mode apps are badged

When developer mode is active (`tenshu-rei.md` §3.6, threat-model asset A16), the handshake
downgrades an unknown-signature outcome from a hard fail to a warning, so an unsigned
development build can connect. Such an app **registers with an "unverified / developer build"
badge** carried on the `AppRegistration` (a `verified: Boolean` field, false when the
connection was admitted under the dev-mode downgrade). The Apps tab must never show an
unsigned app as indistinguishable from a signed one. This composes with the existing
persistent dev-mode status indicator (`tenshu-rei.md` §3.6) and threat T-C-12.

### 3.4 Transport-agnostic

The handshake differs per platform — Unix-socket peer-cred on desktop
(`desktop-ipc.md` §3), Binder caller-UID + package-signature on Android, App Group +
entitlement on iOS (`ios-ipc.md`). The registration flow sits **above** that seam: whatever
mechanism the platform uses to establish a verified `AppId`, the steps in §3.1 are identical
once it has one. The `AppRegistration` schema and the `RegisterApp`/`RemoveApp` operations
are platform-neutral; only the *trigger point* (which handshake fires them) is per-binding.

---

## 4. Schema shape

`AppRegistration` is a `Syncable` composite. It reuses the **exact pattern the preference
service already established** for per-device synced rows (`core-preference-service.md`,
`DeviceSetting`): a row keyed by a derived `compositeKey` so the platform's existing
single-field `stringDataId` provider works unchanged, with no new sync infrastructure.

```
AppRegistration : Syncable
  compositeKey : String   // derived: "$deviceId/$appId" — the stringDataId
  appId        : String   // stable app identifier from the manifest (tenshu-rei.md §3.2)
  deviceId     : String   // the realm device this registration is on
  createdAt    : Long      // install date (control-ia.md §4.2)
  state        : Enum      // Active | Removed   (§5)
  verified     : Boolean   // false if admitted under the dev-mode downgrade (§3.3)
```

Notes:

- **`compositeKey = "$deviceId/$appId"`** mirrors `DeviceSetting`'s
  `"$deviceId/$preferenceId"` derivation exactly, so the single-field provider applies
  with no new machinery. (The same `collectFields` / typeVar-deserializer caveats noted in
  `core-preference-service.md` do not bite here — every field is a concrete scalar.)
- **`state`, not deletion.** Removal sets `state = Removed` rather than deleting the row
  outright; §5.1 explains why a tombstone that *suppresses re-registration* is required for
  removal to mean anything against a still-installed binary.
- **No grants here.** Permission grants live in their own realm-level `AppGrant` records
  (`tenshu-rei.md` §3.3); `AppRegistration` carries no permission state. The two are joined
  by `appId` at the UI layer.
- **`RealmData` later.** `AppRegistration` is a natural future `@RealmData` target
  (`core-realm-data.md`) so *"Apps"* becomes a first-class data type alongside Files and
  Devices in `control-ia.md` §5 — but that is additive and not required for this doc to land.

The two operations:

- `RegisterApp(appId, deviceId, verified)` — proxy-only, idempotent on `(appId, deviceId)`.
  Sets `state = Active`, stamps `createdAt` on first creation. No-op if already active.
- `RemoveApp(appId, deviceId | allDevices)` — user-initiated from Control. Sets the targeted
  row(s) to `state = Removed` and purges the app's `AppGrant`s (§5).

---

## 5. Removal

### 5.1 "Uninstall" in Control means deregister

Control **cannot delete the binary**. On Android, Control and Drive are separate APKs and
Control has no right to remove Drive's package; on desktop, Control could in principle delete
files but should not own the user's filesystem. So the Apps-tab *Uninstall* action
(`control-ia.md` §4.2) is **deregistration**, not binary removal:

1. Set the targeted `AppRegistration` row(s) to `state = Removed`.
2. Purge the app's `AppGrant`s (so re-adding starts from default-deny, like a fresh install).
3. Every proxy refuses — or re-prompts for — that `AppId` until the user explicitly re-adds it.

**The `Removed` tombstone is what makes removal stick.** If removal merely deleted the record,
a still-installed binary that passes the peer-cred check would re-register itself silently on
its next launch (§3.1 step 3), and *Uninstall* would be a no-op the user has to repeat
forever. The `Removed` state suppresses auto-registration (§3.1 step 5): re-registration
only happens when there is *no* record at all, or when the user explicitly re-adds the app in
Control. Re-connection/refusal propagation across devices rides on the existing revocation
machinery and its HLC-consistency carve-out (threat-model §9, I4/I6); a `Removed` row syncing
to a second device must cause that device's proxy to start refusing the `AppId`.

### 5.2 Per-device removal by default — Decision (locked 2026-05-31)

**Removal defaults to this device only** — it tombstones the `(appId, deviceId)` row, leaving
the app's registrations on other devices untouched. This matches how install/uninstall works
on every platform the user already knows: uninstalling Drive on the laptop does not
uninstall it on the phone. Because `AppRegistration` is keyed per `(appId, deviceId)`, this
falls out of the schema for free.

Two affordances accompany the default:

- **"Remove from all devices"** — an explicit option that tombstones every `(appId, *)` row
  for that app, for the user who wants the app gone from the whole realm in one action.
- **"To fully remove, uninstall from your OS"** — an inline hint, because deregistration does
  not delete the binary. Control deregisters and forgets grants; the OS package manager (or
  app store) is where the bytes actually go.

This is the *"closest to how similar install systems work"* posture: per-device by default,
realm-wide on request, with an honest note that the binary itself lives outside Control's
reach.

---

## 6. Sync & threat-model hooks

- **Synced realm metadata.** `AppRegistration` is `Syncable`, so the list of installed apps
  replicates across the realm's devices like any other entity block — ciphertext to the relay
  (House Rule #1 holds; the relay learns nothing it does not already learn from the existence
  of blocks). A second device thus knows *"Drive is installed on the laptop"* and can render
  the aggregated Apps list.
- **New asset.** The `AppRegistration` set is realm metadata describing which apps are
  installed where. It should be added to the threat-model asset list (alongside A1–A16) when
  this lands, per House Rule #2.
- **Self-registration is prevented by proxy-mediation.** A forged local client (T-C-11)
  cannot create an `AppRegistration` because the `RegisterApp` write is reachable only after
  the peer-credential / code-signing check the forged client fails. Worth a short T-C
  sub-amendment that names this explicitly.
- **Removal propagation.** Deregistration on one device must cause every other device's proxy
  to refuse the `AppId`; this is the same shape as device revocation (I4) and inherits the
  HLC-consistency carve-out in threat-model §9. Until the `Removed` tombstone has propagated,
  a lagging device may still admit the app — the cryptographic backstop is that the app holds
  no key material, so the residual exposure is *operation access on one lagging device*, not
  data at rest.
- **Dev-mode visibility.** The `verified = false` badge (§3.3) keeps A16's elevated trust
  posture visible at the per-app granularity, complementing the realm-wide dev-mode indicator
  and threat T-C-12.

Per House Rule #2, the actual `threat-model.md` edits (new asset + T-C sub-amendment) land in
the same PR as the implementing code, not in this proposal.

---

## 7. Open items

- **Module home for `AppRegistration`. — RESOLVED 2026-05-31: `:core`.** Of the two
  candidates — (a) `:rei-service`, conceptually adjacent to Rei; (b) `:core` /
  `:control-shared`, a Control-platform concern not gated on the ledger — option (b) was
  chosen, for the same reason `tenshu-rei.md` Q9 put `PrimaryHandlerSlot` in a sibling rather
  than blocking on `core-realm-data.md`: registration ships value before Rei is operational
  and shouldn't inherit its not-started status. Concretely, the schemas + operations live in
  `:core` under package `org.tenshu.core.app`; DTOs / ROs / proxy implementations + the
  relation maintainer in `:core-common` (`org.tenshu.core.common.app`); they register in
  `coreCommonModule` + the core `implementationFactory` (so every app picks them up with no
  per-app wiring). If `:rei-service` later materialises, the package can move there wholesale.
  Operations attach to a small marker `AppService` (sibling of `LoggingService`) since they
  need a service receiver and `:core` had no existing one.
- **Re-add UX.** When a `Removed` app is launched again, does Control (a) silently refuse and
  show nothing, (b) surface a passive *"Drive tried to connect — re-add it?"* entry, or (c)
  prompt actively on the next launch? Lean (b): a passive re-add affordance in the Apps tab,
  consistent with not interrupting the user with modals (§3.2). Needs a UI decision.
- **`uninstalled` vs `Removed` semantics on re-add.** On re-add, do we reuse the old row
  (preserving original `createdAt`) or stamp a fresh install date? Lean: fresh `createdAt` —
  re-adding is a new install event and the old date would mislead.
- **iOS / web triggers.** §3.4 asserts the flow is transport-agnostic, but the iOS
  (App Group + XPC) and web (in-process runtime) handshake bindings need their concrete
  trigger points specified when those tracks reach app-connection. Web is special: there is
  no separate app *process* to peer-cred against in the in-process runtime — registration on
  web likely keys off the per-app subdomain / SDK origin instead. Out of scope here; flagged
  for the web track.
- **Aggregated vs per-device list rendering.** §2 specifies grouping by `appId` with
  per-device status underneath. The exact UI (one card with a device sublist, vs a flat list)
  is a `control-ia.md` / per-platform rendering decision, not a schema one.

---

## 8. References

- [`control-ia.md`](control-ia.md) §4 — the Apps section this populates (list, detail,
  install date, uninstall action).
- [`tenshu-rei.md`](tenshu-rei.md) §2.2 (capability classes + primary handler), §3.2
  (manifest format = app identity), §3.3 (permission widget = grants), §3.6 (desktop
  handshake + dev mode), Q9/Q11 (primary-handler slot + explicit-grant), Q29 (grant follows
  the app).
- [`../protocol/desktop-ipc.md`](../protocol/desktop-ipc.md) §3 — the handshake that triggers
  registration on desktop.
- [`core-preference-service.md`](core-preference-service.md) — the `DeviceSetting`
  `compositeKey` pattern §4 reuses.
- [`../sync/threat-model.md`](../sync/threat-model.md) — assets A14 (IPC peer-cred binding),
  A16 (dev-mode toggle), threats T-C-11/T-C-12, §9 carve-outs (I4/I6) that removal
  propagation inherits.
- [`core-realm-data.md`](core-realm-data.md) — the future `@RealmData` annotation that would
  surface Apps as a first-class data type.
