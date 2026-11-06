# iOS Proxy Architecture — External Proxy Connection, Control-on-iOS, and relay rendezvous

**Status: Draft v0.1 (2026-06-16). Phase 1 (build + CI) + Phase 2 (EPC channel —
handshake, session, transport, connector) + Phase 3 (relay rendezvous mailbox +
threat-model amendment A31/#14/§8.16, and the relay-backed transport proven
app ↔ relay ↔ proxy) + Phase 4a (measure-2 app-attestation seam + protocol +
software reference verifier) shipped; see Appendix. **Phase 6a (shared iOS crypto
foundation — libsodium-backed Ed25519/X25519/XChaCha20-Poly1305/Argon2id `actual`s
via cinterop, byte-compatible with the JVM/JS backends) shipped 2026-06-17.** ⚠ **Status
stale — see §0.5.** As of 2026-07, **Control-on-iOS (6e) is built and device-verified**
(genesis / lock-unlock / device-pairing / relay registration / EPC host / real Face ID unlock),
and the shipping **first-party data plane is route-1 in-process, not EPC** (§0.5).
Only **Phase 4b (real Apple App Attest verifier)** and **6f (`:rei-service`)** remain genuinely
unstarted. **Next slice = 6b.5
(Drive-client build-out): `:drive-shared` + `:core-paging` iOS-ization, SKIE bridge, first
SwiftUI screens against a desktop proxy + software attestor — see §9.5 (2026-06-19).**
Sequencing decision 2026-06-17 (§9): the first
iOS app is the Drive client (not Control, not parallel); Rei deferred — first-party
v1 reuses the existing bundled-manifest path.** Supersedes the generalisable reading of
[`docs/protocol/ios-ipc.md`](../protocol/ios-ipc.md) — that document is demoted to a
first-party-only special case (§6). Owned by the *iOS Drive Client* milestone
([`docs/milestones.md`](../milestones.md)); resolves the data-plane fork that milestone
left open. Companion to the whitepaper's *External Proxy Connection* section
([`docs/whitepaper.adoc`](../whitepaper.adoc) §External Proxy Connection).

---

## 0. TL;DR

1. **Cross-vendor on-device IPC is impossible on iOS.** App Groups are bound to a single
   Apple development team (Team ID), and re-signing third-party binaries under one umbrella
   team to share a group violates the Apple Developer Program License Agreement and the
   App Review Guidelines (apps must be "self-contained, single app installation bundles"
   that "cannot install code or resources in shared locations"). No sanctioned cross-vendor
   on-device IPC path exists; EU DMA marketplaces and Custom App Distribution do not change
   this (each app is still signed by its own developer). So the App-Group socket model in
   `ios-ipc.md` only ever works for apps Tenshu itself ships.

2. **Therefore EPC is mandatory on iOS.** ⚠ **Superseded for first-party — see §0.5.** True for
   *third-party* apps. First-party apps ship on **route-1 in-process** (own proxy over the shared
   App-Group replica, no live EPC channel). Original text: every iOS Tenshu app reaches its
   services through the whitepaper's **External Proxy Connection** — one proxy as a paired, trusted
   endpoint over a network channel — never on-device IPC.

3. **Control ships on iOS — but as an EPC endpoint, not an App-Group broker.** An
   iPhone-only user (no awake desktop/Android/Hub) has no proxy at all unless one is
   resident on the phone. So Control-on-iOS is required. It does **not** revive on-device
   IPC: a co-resident Control is reached over the *same* EPC channel as a remote one, and
   because iOS suspends it, it cannot be an always-on local socket server. Co-residence is a
   deployment location, not a different IPC mechanism.

4. **Data-plane default is proxy-mediated EPC, not in-process.** ⚠ **Inverted for first-party —
   see §0.5.** In-process (route-1) is the *shipped default* for first-party apps, not a reserved
   exception; proxy-mediated EPC is the third-party default. Original text: because third-party iOS
   apps are untrusted, the master key stays on the proxy and the app talks to it over the E2E
   channel; the web-style in-process model is reserved for first-party / trusted cases.

5. **The relay needs new surface — for rendezvous, not attestation.** Attestation
   *verification* is the proxy's job inside the E2E channel (whitepaper EPC measure 2);
   putting it on the relay would violate House Rule #1 (relay sees ciphertext only). What
   the relay genuinely needs is an app↔proxy **rendezvous** channel distinct from today's
   realm-device sync mailboxes, plus an admission/anti-abuse gate. A coarse edge-level App
   Attest *admission* check is a defensible option but a threat-model decision, not a given.

6. **Control-on-iOS is a full realm device, not an EPC client (§11).** When the iPhone is the
   first/only device, Control mints the realm and holds the master key (realm genesis); a later
   Control joins via the existing device-pairing ceremony. EPC app pairing (§10) is a separate,
   keyless flow. Consequence: Control appears under Control IA Data → Devices; EPC-paired *apps*
   live under Apps. Opens O6 (iOS key custody) + O7 (genesis UX). ⚠ **O6/O7 now BUILT — see §0.5.**

## 0.5 As-built correction (2026-07-18) — route-1 supersedes "EPC-mandatory" for first-party apps

⚠ **This section is authoritative over §2, §3, §4, §8, §11.2, §11.5, and TL;DR items 2/4, all of
which predate route-1 and were written when EPC was assumed to be the only iOS data plane.** The
original reasoning is kept below as decision history; where it says "EPC is mandatory / in-process
is a reserved exception / the answer is relay wake via push," read this section instead.

**The two-model split (the real picture).**
- **First-party apps → route-1 in-process.** A Tenshu-signed app runs its *own* in-process proxy
  graph over a **shared App-Group replica**, adopting Control's device identity — **no live EPC
  channel to Control**. Shipped for Drive (`DriveLocalRuntime`, gated by `isProvisioned()`; keys
  read from `RealmKeyMaterialBridge`; a same-device Drive↔Control EPC spike proved a co-resident EPC channel *stalls* the moment
  Control backgrounds, which is why in-process is the first-party answer). Full productization:
  [`ios-drive-parity.md`](ios-drive-parity.md) P1–P6. Fit/Timeline/Maps are **not yet migrated** —
  they run the *interim* route-2 EPC path (`EpcFitClient` etc.) and inherit its "Control must be
  hosting" limitation ([`fit-app.md`](fit-app.md) §11.8). The generalization plan is
  [`ios-drive-parity.md`](ios-drive-parity.md) §20 (build the first-party local runtime once).
- **Third-party apps → EPC** (the original §2 model). A foreign app reaching *someone's* Control
  over the rendezvous channel is where the EPC ceremony — SAS, App Attest, Rei — is load-bearing.
  No third-party consumer exists yet; only the software-attestor seam (4a) is built.

**What route-1 trades (a real cost, recorded consciously).** Route-1 makes a first-party app a
**key-holding realm device**: `RealmKeyMaterial` carries *realm-level* keys (`sharedVolumeKey`
decrypts all file content, `sharedIdentityKey`, A13) as one blob in the shared Keychain, **not**
app-scoped. So it collapses the app↔proxy enforcement boundary that EPC/Rei gave *even to
first-party apps*, and does so **asymmetrically**: on Android the proxy is a separate process that
mediates what each app sees; under iOS route-1 the "proxy" runs inside the app's own process, so
that mediation is gone. The **core guarantees still hold** — relay sees ciphertext only (HR#1),
keys live only on provisioned devices, master key A1 never leaves Control — so what is lost is
*inter-first-party-app least privilege* (defense-in-depth between our own signed apps), a tier
below the E2E guarantees. This was judged an acceptable, iOS-forced trade (no sanctioned local IPC
+ unreliable EPC channel), **not** a reason to keep EPC.

**⚠ OPEN QUESTION (decide before generalizing route-1 to Fit/Timeline/Maps).** The bridge is
all-or-nothing: a route-1 Fit would read the same `RealmKeyMaterial` and hold `sharedVolumeKey`, so
**Fit could decrypt Drive's file bytes.** Does each first-party app get the whole realm blob
(simple, but full cross-app read), or scoped material (per-service keys, or a metadata-only key for
apps that don't touch file content)? Tracked in [`ios-drive-parity.md`](ios-drive-parity.md) §20.

**App-Group paid gate.** Route-1's shared replica requires the App-Group entitlement → a **paid
Apple Developer account**; entitlement-less/free builds fall back to an **app-local** replica (no
cross-process sharing → route-1 unavailable). Moot for shipping (App Store needs a paid account
anyway). See [[project_ios_appgroup_fallback_free_signing]].

**Background: push-wake was never built.** §3/O4 cite "relay wakes the proxy via push /
`BGTaskScheduler`." As-built there is **no push/APNS code** anywhere; the background story is
`BGAppRefreshTask` where **the first-party app itself is the syncer** over the shared replica
(`drive-ios/.../BackgroundSync.swift`, `fit-ios/.../FitBackgroundRefresh.swift`), plus P3b
background `URLSession` for bulk upload (O3 Option B — shipped, no longer "deferred"). Silent-push
wake remains a legitimate *future* option (it would let a backgrounded first-party app be woken for
timely sync), just an **unbuilt** one — do not cite it as the shipped mechanism.

---

## 1. Why on-device IPC is off the table on iOS

The Android model has Control act as an on-device proxy that other apps reach over Binder.
The Desktop model uses a Unix Domain Socket in a private directory (or a macOS App Group
container when sandboxed) — see [`docs/protocol/desktop-ipc.md`](../protocol/desktop-ipc.md).
`ios-ipc.md` ports the socket idea to iOS via an App Group shared container
(`group.org.tenshu`).

That port is sound **only for apps under Tenshu's own Team ID**, because:

- **App Groups are single-team by construction.** Apple assigns each `group.*` identifier to
  one development team and enforces uniqueness at provisioning specifically to prevent a
  different team from joining it. Every IPC mechanism the shared container unlocks (UNIX
  domain sockets, Mach IPC, XPC, shared memory) is gated behind that single-team membership.
  A third-party developer signing with their own Team ID can never join `group.org.tenshu`
  and never sees the socket.

- **The re-sign workaround is prohibited.** Obtaining a third-party binary and re-signing it
  under Tenshu's certificate to fold it into one team is exactly what the ADP License
  Agreement and review guidelines forbid: third-party distribution must go through the App
  Store or Custom App Distribution, apps must be self-contained single-app bundles, and may
  not install code/resources in shared locations.

- **No carve-out helps.** Custom App Distribution / Apple Business Manager is
  business-internal, not a public open platform. EU DMA alternative marketplaces (iOS 17.4+)
  distribute apps but do not merge signing identities — each app is still signed by its own
  developer, so no shared App Group spans vendors.

**Conclusion:** there is no sanctioned cross-vendor on-device IPC on iOS. For a platform whose
whole pitch is third-party apps, on-device IPC is a dead end.

## 2. EPC is mandatory on iOS

The whitepaper already names this exact situation: iOS "doesn't allow apps to communicate
directly with each other on-device to the extent required by Tenshu," so EPC is used. EPC
pairs **one client app with one proxy** as a trusted endpoint over a network channel; the app
is *not* made a realm device and gets only what the proxy chooses to expose, mediated by
semantic permission grants. Its three security measures (whitepaper EPC §):

1. A pairing handshake establishes an E2E-encrypted app↔proxy channel via authenticated key
   exchange (QR/NFC + short-authentication-string confirmation; no third party trusted).
2. The proxy verifies **app attestation** (Apple App Attest on iOS) — the app is genuine and
   unmodified.
3. The proxy verifies **permissions** via Tenshu Rei (RPL publication → RIL issue → REL
   evaluation; for iOS the evaluator inspects the app's `Info.plist`).

All three are **proxy responsibilities**, performed end-to-end inside the channel. The relay
is not in the trust path for any of them (see §5).

## 3. Control-on-iOS — required, but as a co-resident EPC endpoint

The proxy endpoint an iOS app pairs with may live:

- **on another device** — desktop, Android phone, or a Tenshu Hub; or
- **on the same iPhone** — Control-on-iOS, for the user who carries only an iPhone.

The second case is why **Control must ship on iOS**: without it, an iPhone-only user has no
proxy anywhere, and the cross-app proxy/permission model — the whole point of Tenshu — is
unavailable on their primary device. But co-residence changes nothing about the mechanism:

- **Still EPC, not IPC.** A third-party app on the same phone reaches the local Control over
  the EPC channel (a paired, rendezvoused network session), because the App-Group socket is
  closed to it (§1). Control-on-iOS is "a proxy that happens to be local," not an on-device
  broker.

- **Background suspension breaks the always-on server assumption.** iOS suspends Control;
  it cannot hold a persistent local socket server the way the Android/Desktop proxy does.
  This is the lifecycle gap `ios-ipc.md` §3 hand-waves with "read keys from a shared file."
  ⚠ **As-built (§0.5): for the first-party case, "read keys from a shared file" is exactly what
  route-1 does — and it works** (`RealmKeyMaterialBridge` + shared App-Group replica); this §3
  dismissal of it as a hack was disproven. **Push-wake was never built** — the shipped background
  path is the first-party app itself syncing under `BGAppRefreshTask`, not a relay-woken Control.
  Original text follows: The answer here is the relay as a **rendezvous + wake** fabric: sessions
  are brokered through the relay so neither app needs the other's local address or foreground
  coupling, and the proxy is woken for sync windows via push / `BGTaskScheduler`. When no proxy is
  reachable and the local one is asleep, the app is degraded to cached / in-process-only
  behaviour (consistent with the app-side-capture decision in `milestones.md`). **§9.4 details
  the platform constraints this lives within** — continuous background sync is not achievable on
  iOS; Control-on-iOS gets best-effort, system-scheduled windows only.

This *confirms* rather than complicates the app-side-capture decision: the on-device iOS app
necessarily holds its own photo/location/etc. permission and enumerates locally, because there
is no local broker to delegate to.

## 4. Data plane — proxy-mediated by default

⚠ **Superseded for first-party by route-1 — see §0.5.** This section's "proxy-mediated EPC is the
default; in-process is a reserved optimisation" framing is inverted for first-party apps as-built.
Kept below as decision history; the trade-off route-1 makes (realm-scoped keys, collapsed app↔proxy
boundary) and the open key-scoping question are in §0.5.

The milestone left open whether the iOS data plane uses **remote/co-resident proxy EPC**
(master key stays on the proxy; costs a round-trip) or the **web-style in-process** model
(app embeds the K/N core, holds the key, talks straight to the dumb relay — proven by Web
Baseline).

**Decision (default): proxy-mediated EPC.** Third-party iOS apps are untrusted; handing each
of them the master key (in-process) means trusting every vendor's binary, which only holds up
if Rei attestation is doing real gating and even then weakens the posture. Keeping the key on
the proxy preserves the semantic-permission mediation that defines the platform.

**Exception: in-process for first-party / trusted surfaces.** Where Tenshu ships and signs the
app, the in-process path (mirroring Web Baseline's service-worker split: permission evaluation
+ master-key crypto in-process, ciphertext relay server-side) is available as an optimisation.
This is the same coexistence the web already has (extension = direct pattern; no extension =
EPC).

## 5. Relay extension — rendezvous, not attestation

Per the whitepaper, attestation and permission **verification belong to the proxy**, inside
the E2E channel. The relay only ever sees ciphertext (House Rule #1), so it *cannot* perform
those checks without breaking zero-trust, and it does not need to. What the relay does need:

- **An app↔proxy rendezvous channel**, distinct from the realm-device sync mailboxes that the
  current relay work targets (
  [`sync-backend-relay-plan.md`](sync-backend-relay-plan.md),
  [`sync-relay-api.md`](sync-relay-api.md) are all *realm-device↔realm-device* sync surfaces).
  EPC introduces a new connection class — a client app that is **not** a realm device pairing
  with a proxy — so it needs its own addressed, store-and-forward channel that lets an app
  reach a remote proxy or a suspended local Control without either holding the other's address.

- **An admission / anti-abuse gate.** EPC opens the relay to connections from apps that are
  not realm devices; the relay needs to bound who can open rendezvous sessions to resist
  flooding.

**Open (threat-model) decision:** whether that admission gate does a *coarse* Apple App Attest
check at the edge — purely as DoS resistance, never as the security-bearing attestation. It is
zero-trust-compatible (attestation concerns the connecting client's integrity, orthogonal to
the E2E payload the relay can't read), but it adds an Apple dependency to the relay and is not
in the whitepaper. Either way it is **in addition to**, never **instead of**, the proxy's
measure-2 verification. Because this touches the relay's data shape and the trust model, it
requires a threat-model amendment in the implementing PR (House Rule #2).

## 6. Disposition of `ios-ipc.md`

Rewrite it as an explicit **first-party-only** note: it describes how Tenshu's own
same-Team-ID apps (e.g. Control + Drive, if both ever ship on iOS as first-party) *could*
share an App Group container, and it states loudly at the top that this does **not** generalise
to third-party apps and is **not** the platform's iOS connection model — EPC (this doc) is.
Its §3 "passive sync via shared file" lifecycle hack is superseded by §3 here (relay
rendezvous + background wake). If first-party co-residence never ships, retire the doc.

## 7. Open questions

- **O1 — Co-resident transport.** Is there a foreground loopback fast-path (lower latency when
  both app and local Control are foregrounded) on top of the relay-rendezvous baseline, or is
  rendezvous always relay-brokered for uniformity? (Loopback is cross-team-capable and avoids
  the App-Group barrier, but adds a second code path and only works foreground-coupled.)
- **O2 — Edge App Attest admission gate.** Yes/no per §5; threat-model amendment either way.
- **O3 — Data-plane exceptions.** Confirm proxy-mediated default; enumerate which first-party
  iOS surfaces (if any) are allowed the in-process path at v1. **Sharpened by §9.4:** the
  load-bearing exception is *content-plane*, not whole-master-key — bulk file upload on iOS must
  ride a background `URLSession` (the only Apple-sanctioned background transfer), which cannot run
  the per-frame EPC crypto, so the iOS client needs **local content-key capability** to upload
  ciphertext straight to the relay content store and register the manifest via a small deferrable
  EPC op. Decide whether v1 grants that content-key exception (it touches the data-plane trust
  model → threat-model note in the implementing PR). **v1 line decided (§9.5): Option A
  foreground-only — no content key, no threat-model change; the content-key exception (Option B)
  is deferred to when survives-backgrounding upload is built.** **§11.5 refines this:** when the
  in-process path is taken (first-party), the app rides the device's single Control-anchored sync
  identity (one HLC authority + signing key + shared replica), not an independent per-app device.
  ⚠ **RESOLVED — Option B (background `URLSession` content upload) shipped** as route-1 P3b
  ([`ios-drive-parity.md`](ios-drive-parity.md) §16); no longer deferred. §0.5.
- **O4 — Background liveness budget.** Map `BGTaskScheduler` / push wake windows to EPC
  session liveness expectations; define the degraded (no reachable proxy) UX. **See §9.4** for the
  platform constraints this must live within (no persistent proxy process on iOS; best-effort,
  system-scheduled windows only). ⚠ **PARTIALLY BUILT — `BGAppRefresh` (app-as-syncer) shipped;
  push-wake never built** (§0.5). The "push" mechanism is an unbuilt future option, not shipped.
- **O5 — Rei dependency ordering. RESOLVED (2026-06-17, §9).** Measure 2 (attestation) is the
  security-bearing genuineness check and is already done; it does **not** depend on Rei.
  Measure 3 (permission verification) for first-party v1 reuses the **existing** bundled-manifest
  path already in code (`AppManifest` / `BundledAppManifestSource` / `BundledAppIdentityResolver`,
  the `BundledReiSource` bootstrap from [`tenshu-rei.md`](tenshu-rei.md)). The full ledger /
  `:rei-service` / `OnlineReiSource` is needed only for **third-party** iOS apps and is deferred
  to its own track — not a prerequisite for the first iOS apps.
- **O6 — iOS master-key custody.** Control-on-iOS as a key-holding realm device (§11) needs a
  Secure Enclave-backed wrap key + Keychain persistence (Face ID / passcode gated) — the iOS
  analog of the desktop `MasterKeyStore` macOS path ([`desktop-masterkeystore.md`](desktop-masterkeystore.md)).
  ~~Not specced.~~ ⚠ **BUILT** — Keychain-ACL wrap key + real Face ID / Touch ID unlock
  (`IosBiometricUnlockDelegate`, on-device verified). §0.5.
- **O7 — First-device genesis UX on iOS.** Realm creation + recovery-phrase capture in
  Control-on-iOS onboarding (§11.3). Existing onboarding docs assume genesis on desktop/Android;
  the iPhone-first path is unwritten. ⚠ **BUILT** — Control-on-iOS genesis → lock/unlock lifecycle
  ([`ios-control-handover.md`](ios-control-handover.md)). §0.5.

## 8. Phasing (each independently shippable)

1. **Prove the K/N core. ✅ SHIPPED.** Build the first `XCFramework` from shared Kotlin and the
   first Apple-native platform layer; re-enable iOS tests (lifts the iOS test-linking disable). This is the forcing
   function the milestone already identifies, and it is shared with the JVM-less macOS path. See
   the Appendix "Phase 1 — as-built" for the umbrella export set + test-gate scope decisions.
2. **EPC channel (transport). ✅ SHIPPED.** app↔proxy pairing + E2E channel (measure 1),
   proven over loopback first. See Appendix "Phase 2 — as-built".
3. **Relay rendezvous surface. ✅ SHIPPED.** `/sync/epc` rendezvous mailbox + relay-backed
   transport (app ↔ relay ↔ proxy) in `:sync-relay-server`, alongside (not entangled with) the
   realm-device sync mailboxes. Threat-model amendment A31/#14/§8.16.
4. **Attestation (measure 2). ✅ SHIPPED (4a — seam + software verifier).** O2 decided: *no*
   edge admission gate (relay admission stays JWT + caps). Real Apple App Attest is 4b, folded
   into Phase 6 (6c/6d) since it needs Apple-side prerequisites.
5. **Rei permission verification (measure 3).** Wire proxy-side REL/Info.plist evaluation;
   sequence per O5. **For first-party v1, reuse the existing bundled-manifest path — do not
   build `:rei-service` first (see §9).**
6. **Control-on-iOS as co-resident proxy.** Background-wake story (O4) so the iPhone-only user
   gets a self-sufficient device. **Sequenced *after* the iOS Drive client — see §9.** Phase **6a**
   (shared crypto foundation: libsodium `actual`s + cinterop + `ktor-client-darwin` in the catalog)
   ✅ **shipped 2026-06-17** — see the Phase 6a as-built section. The **6a app-module half**
   (first iOS app target + crypto smoke harness, simulator-verified) ✅ **shipped 2026-06-19** —
   see "Phase 6a — as-built (app-module half)". **6b** (EPC rendezvous client packaging —
   `:epc-rendezvous-client` extracted + exported, Darwin engine simulator-verified)
   ✅ **shipped 2026-06-19** — see "Phase 6b — as-built". **6b.5 (Drive-client build-out:
  `:drive-shared` + `:core-paging` iOS-ization + SKIE + first SwiftUI screens) is the next slice
  — see §9.5.** ~~6c–6f outstanding.~~ ⚠ **As-built (§0.5): 6e (Control-on-iOS) is BUILT +
  device-verified** (genesis, lock/unlock, device-pairing, relay registration, EPC host, Face ID);
  the shipping first-party data plane is **route-1 in-process**, not EPC. Only **4b (real
  App Attest)** and **6f (`:rei-service`)** remain unstarted. **Control-on-iOS owns the *pairing
  surfaces* a proxy needs
  (it does not lose them when Drive's first-party onboarding drops QR/SAS, §11.6):** the
  **proxy-side EPC pairing UI** (display QR + show/confirm SAS so apps can connect *to* it — the
  §10.6 "proxy side" half, here on iOS), and the **device-pairing / realm-join** flow (where
  Control-on-iOS *scans* to join an existing realm as a second Control, the `sync/pairing-flow.md`
  ceremony, §11.1). See §11.6 "Deferred" for the same-device OOB nuance.
7. **Rewrite/retire `ios-ipc.md`** per §6. ✅ SHIPPED (this doc's first session).

---

## 9. Sequencing decision — Drive client first, defer Rei (2026-06-17)

With Phases 1–4a shipped, the EPC channel exists as a fully-tested protocol/transport library
with software stand-ins and **no iOS app yet**. Two questions about what to build next were
settled:

### 9.1 The first iOS app is the **Drive client**, not Control — and not both in parallel

On iOS, Drive and Control are **not** symmetric peers the way they are on Android. Drive-on-iOS
is a pure EPC **client** that pairs with an *existing* proxy (the user's desktop or Android
Control); it needs no local Control. Control-on-iOS (Phase 6e) is the **proxy** side and the
single hardest iOS-specific piece — a proxy that survives background suspension
(`BGTaskScheduler`/push, O4), long-poll on `/sync/epc`, and the unresolved O1.

The dependency story is therefore asymmetric: a Drive client forces exactly the shared
foundation everything needs — CryptoKit/libsodium crypto `actual`s (replacing the throwing
`CryptoBackendsModule.ios.kt` stubs), `ktor-client-darwin`, real Apple App Attest (6c/6d) — and
proves it on a device against a known-good proxy. **Control-on-iOS then reuses that proven
foundation.** Starting with Control means building the foundation *and* the hardest platform
risk at once, with nothing simpler to validate against; running both in parallel means
coordinating two app tracks over a foundation not yet proven even once.

The iPhone-only user (no other reachable proxy) is the one motivation for Control-on-iOS early.
That is a real but **later** story — a Drive client paired to the user's desktop Control is a
valid v1 and de-risks the whole stack first.

**Decided order:** 6a (iOS Drive client: app module + crypto actuals + darwin engine) → 6b
(client packaging) → **6b.5 (Drive-client build-out: `:drive-shared` + `:core-paging`
iOS-ization, SKIE, first SwiftUI screens against the Phase-4a software attestor — §9.5)** →
6c/6d (real App Attest + live gating, swapped in *under* the now-working client) →
*(pin Info.plist→`AppManifest` mapping)* → 6e (Control-on-iOS) → 6f (Rei ledger, third-party
only).

### 9.2 Do not build Rei out before the first iOS apps

Measure 2 (attestation) — the security-bearing "genuine, unmodified app" check — is already done
and is independent of Rei. For **first-party** v1 iOS apps (Tenshu signs them), trust is
bootstrapped by the distribution channel through the bundled-manifest path, which **already
exists in code** (`AppManifest`, `BundledAppManifestSource`, `BundledAppIdentityResolver`,
`PublisherClient`). The full Rei ledger (`:rei-service`, RPL/RIL/REL, `OnlineReiSource`) matters
only when third-party iOS apps want to use someone's proxy — not v1 — and remains its own
unsized track. Building it first is a detour the first iOS apps do not need.

The one cheap, worthwhile step before hardcoding anything: a short paper pass pinning how an iOS
`Info.plist` maps into the existing `AppManifest` shape (possibly a minor `AppManifest`
extension), so the first-party bundled artifact format is settled. That is a design task, not the
ledger.

### 9.3 iOS app code-sharing boundary — share the SDK + view models + repos; UI is SwiftUI (2026-06-19)

The 6a smoke harness (`drive-ios/`) reuses only the umbrella SDK. The real Drive iOS client needs a
decision one layer up: where the shared/native line falls between the exported `TenshuSdk.xcframework`
and the on-screen UI. Decided after auditing what `:drive-shared` actually is today.

**Decision: share the protocol/client SDK *and* the view-model + repository layer; write the UI in
SwiftUI.** Concretely — three bands:

1. **Shared, already exported (the SDK).** `:core`/`:core-common`/`:core-client` + every service
   `-common`/`-client` (schema, chain DSL, crypto, the EPC channel). The real client builds on the
   same `TenshuSdk.xcframework` the smoke harness links. Not in question.
2. **Shared, to be made iOS-capable (Drive app logic).** Drive's **view models and repositories**,
   today in `:drive-shared`. The audit makes this cheap rather than a rewrite: the ~21 VMs already
   extend the **multiplatform** `androidx.lifecycle.ViewModel` (+ `viewModelScope`, both real on iOS
   since lifecycle 2.8) and expose state as **`StateFlow`** (≈25 files); only **5** spots touch
   Compose's `mutableStateOf`. That is exactly the UI-framework-neutral shape KMP shares to native.
   SwiftUI consumes a VM by resolving it through plain Koin `get()` (not the Compose
   `koinComposeViewmodel` helper) and observing its `StateFlow` via a **`Flow`→Swift bridge (SKIE**,
   or KMP-NativeCoroutines), driving an `@Observable`/`ObservableObject`; events are plain VM calls.
3. **Not shared — rewritten in SwiftUI (the UI).** The `@Composable` screens in `:drive` / `:ui-core`
   are **not** reused. **Compose Multiplatform UI is rejected on iOS for the same reason House Rule #5
   rejected CMP-web:** CMP-iOS renders through Skia/Skiko (it draws its own Material widgets, not
   UIKit/SwiftUI controls), so scroll physics, text selection, system fonts, Dynamic Type, context
   menus and accessibility diverge from native — "good, not indistinguishable." For a privacy/trust
   product the native polish is load-bearing, and the repo is pinned to **CMP 1.7.3** (binary-compat,
   `tech-debt.md`), where iOS was less mature still.

**Why B and not "SDK-only, re-implement logic in Swift" (the literal web analogy).** Web went full
React because CMP-web was rejected *and* the protocol bridge was the only KMP-reachable layer. iOS is
different: the VM/repo layer is plain Kotlin that compiles to native, and it is *already* in the
StateFlow-on-`ViewModel` shape — so iOS is the canonical "share business logic, native UI" case.
Re-implementing repos + VMs in Swift would mint a **third** copy of Drive's business rules (KMP for
Android/desktop, TS for web, Swift for iOS) for no structural gain. House Rule #5 governs the *UI*
boundary (honoured here — SwiftUI), not the logic layer.

**Prerequisite refactor (the actual work, not a rewrite):** `:drive-shared` is `androidTarget` +
`jvm("desktop")` only and mixes its VM/repo logic with Compose-UI deps (`:ui-core`, `paging.compose`,
the `@Composable` screens) and the full base service modules. To reuse it on iOS: add iOS targets to
the logic slice, **separate the VM/repo layer from the Compose-UI deps** (so the iOS-exported piece
pulls only the narrow `-client`/`-common` surface the umbrella already exposes), convert the 5
`mutableStateOf` sites to `StateFlow`, and add the SKIE plugin to the umbrella. Watch **paging**:
`androidx.paging` core is KMP but `collectAsLazyPagingItems()` is Compose, so iOS needs a
non-Compose collection path (the custom `:core-paging` is the lever). This is a packaging/extraction
job, sequenced with the real client (after 6b); it does not block 6b (EPC transport packaging), which
is below this layer.

### 9.4 iOS background-execution constraints — what the data/background features can do (2026-06-19)

iOS has no persistent background process: an app gets ~30 s (`beginBackgroundTask`) after
backgrounding, then suspension. The only sanctioned background work is **opportunistic, system-
scheduled, and best-effort** — `BGAppRefreshTask` (~30 s, system-timed), `BGProcessingTask` (minutes,
only when charging/idle, can request `requiresNetworkConnectivity` / `requiresExternalPower`),
throttled silent push, and **background `URLSession`** (out-of-process `nsurlsessiond` transfers that
survive suspension/termination and relaunch the app on completion). There is **no** general background
filesystem watch (the Photos library is observable only while running). Low Power Mode / adaptive
throttling can starve all of it. This subsection records how Drive's background features fare, because
the answer differs sharply by iOS role and the synced-folders + Control-on-iOS slices both depend on it.

**The content path, confirmed in code.** `FileUploader` / `TransportFileUploader` (client) stream file
bytes *over the transport to the proxy*; `UploadFileImplementation` (`:file-service-proxy`) is what
encrypts and PUTs to the relay `ContentBlobStore`. On iOS the transport is EPC-over-relay, so a naive
upload is `app → (per-frame-sealed EPC) → proxy → relay content store`. EPC needs the app's code
*running* to seal each frame, so that path **cannot proceed while the app is suspended.**

- **Drive-on-iOS as a pure EPC client (v1, paired to a desktop/Android proxy) — mostly fine, one real
  problem.** The durable work (storage, relay sync, content upload) runs on the *remote* proxy, and the
  EPC mailbox is store-and-forward, so a suspended iOS app does not stall a live desktop Control. Small
  metadata ops seal in the foreground or the 30 s grace. The one hostile case is **bulk file/photo
  upload from the device:** those bytes exist only on iOS and currently ride interactive EPC. The fix is
  the **O3 content-key exception** — encrypt locally and hand the ciphertext to a background
  `URLSession` bound for the relay content store, registering the manifest via a small deferrable EPC
  op. This exposes a *content* key, not the master key, and needs a threat-model note.
- **Control-on-iOS as a co-resident proxy (6e) — the genuinely hard one (O4).** A proxy is normally a
  long-lived process (desktop tray / Android foreground service); iOS has no equivalent. Control-on-iOS
  can only run foregrounded or in `BGProcessing`/push windows, so **continuous background sync is not
  achievable** — it is best-effort, and an iPhone-only user's sole proxy is unreachable whenever it is
  asleep (the relay buffers requests; the proxy must wake to service them). This is why §9 sequenced
  Control-on-iOS last.
- **Synced-Folders auto-backup — the most iOS-hostile feature; reframe, do not port.** The Android model
  (WorkManager + foreground service watching a folder) has no iOS analogue. The achievable shape is
  PhotoKit change-history fetched on launch/refresh + background `URLSession` + opportunistic
  `BGProcessingTask` + an explicit "open the app to finish backing up" nudge — best-effort, not
  set-and-forget. Correctly gated as iOS Ph4 today.

**What ports cleanly.** The `HeadlessSafe` / `BackgroundOperation` / `ScheduleService` abstractions are
portable — they need an iOS `PlatformScheduler` backed by `BGTaskScheduler` (the seam the
preference-service track already anticipated). `ConstraintSyncGate`'s WifiOnly / charging predicates map
onto `BGProcessingTask`'s `requiresNetworkConnectivity` / `requiresExternalPower`; the difference is
*cadence* — iOS owns the schedule, the app only expresses requirements. Receiving content to iOS can use
a background `URLSession` download symmetrically.

**Bottom line:** nothing blocks v1 (a client paired to a desktop/Android proxy that does the durable
work). The iOS-specific design work is exactly the three open questions: **O3** (content-key exception
to enable background-`URLSession` uploads — the only item that touches the data-plane trust model),
**O4** (accept best-effort push-assisted windows for Control-on-iOS), and reframing **Synced-Folders**
to the iOS-achievable UX before building it.

### 9.5 Session resolutions — SKIE, paging, O3 v1 line, 6b.5 named (2026-06-19)

A working session settled the four questions §9.3/§9.4 left open and named the next slice
**6b.5 — the Drive-client build-out**. It sequences *after* 6b (client packaging) and *before* 6c
(real Apple App Attest): the client UI is brought up against the Phase-4a **software** attestor
first, and 6c/6d swap the real Apple verifier in underneath the now-working client (the Phase-4a
seam means that swap is no protocol change). It needs **no Apple Team ID**, so it is the longest
unblocked runway on the track. Detailed slice plan + kickoff prompt:
[`ios-proxy-phase6b5-handover.md`](ios-proxy-phase6b5-handover.md).

**StateFlow→Swift bridge = SKIE** (not KMP-NativeCoroutines). SKIE renders the exported surface as
idiomatic Swift — `Flow`→`AsyncSequence`, `suspend`→`async`/`await`, sealed classes→exhaustive
enums — which matters because the umbrella `TenshuSdk.xcframework` is also the public SDK that
third-party (incl. Swift-only) apps will consume; KMP-NativeCoroutines instead pushes a Swift
runtime dependency + annotations onto the *consumer*, leaking the KMP-ness. **One coupling to
hold:** SKIE is a compiler plugin pinned to Kotlin compiler versions — the same version-coupling
fragility the libsodium cinterop was hand-rolled to avoid — so verify SKIE supports the pinned
Kotlin *before* adopting it, and gate Kotlin bumps on SKIE support (treat it like the CMP 1.7.3 pin
in `tech-debt.md`). Lock-in is low: SKIE applies only at `:tenshu-sdk-ios` and touches no shared
logic, so it is droppable if it ever blocks a Kotlin upgrade. **Verified (2026-06-19, slice 1
gate):** repo Kotlin is **2.3.21** and SKIE **0.10.12** (2026-05-18) adds *"Support for Kotlin
2.3.21"* — adoptable, but at the bleeding edge (newest SKIE for newest Kotlin). The `tech-debt.md`
entry lands with the SKIE adoption (slice 3), not before.

**Paging = `androidx.paging`'s `PagingDataPresenter`**, not a hand-rolled `AsyncStream`. Drive's
VMs already expose `Flow<PagingData<FileUiModel>>` and the Compose screens call
`.collectAsLazyPagingItems()` (`FilesScreen` / `FolderScreen` / `MovePickerScreen` in
`:drive-shared`). `PagingData` carries load states, retry, placeholders and the load-next-page
trigger; a raw `AsyncStream` would drop all of it. `PagingDataPresenter` is the **non-Compose base
that `LazyPagingItems` itself is built on** and ships in the KMP `paging-common` artifact. Plan: add
iOS targets to `:core-paging` (today `jvm("desktop")`-only — `PagingUtil.kt`), wrap
`PagingDataPresenter` in a small presenter exposing snapshot + `loadStateFlow` + `get(index)`,
surface it via SKIE; SwiftUI observes the snapshot and calls `get(index)` from the last cell's
`onAppear`. **v1 shortcut allowed:** for small/bounded lists a screen may expose a plain
`StateFlow<List<FileUiModel>>` and add the presenter only when a folder is actually large.

**O3 (background upload) — v1 ships foreground-only; the content-key exception is deferred,
deliberately.** The clarification that matters: encryption *can* run in iOS background CPU windows
(`beginBackgroundTask` ~30 s grace, `BGAppRefreshTask`, `BGProcessingTask` minutes-when-charging/
idle, throttled silent push) — what *cannot* run there is per-frame EPC sealing across a streaming
upload, and the only transfer that survives a locked/terminated phone (**background `URLSession`**
via out-of-process `nsurlsessiond`) runs with the app suspended, so it can only ship
**pre-encrypted** bytes. iOS also has no interval-precise wake — `BGProcessingTask` is opportunistic
(`earliestBeginDate` + system choice), not a cron. Hence two shapes:

- **v1 (decided): Option A — foreground-only.** Upload while the app is open (+ the 30 s grace to
  finish the current chunk), normal EPC streaming, backgrounding cancels in-flight. **No content
  key, no threat-model change.** Sufficient for the first working client; it is a deliberate scope
  line, not a platform limit.
- **Later: Option B — batch-encrypt + background `URLSession` (the Photos/OneDrive model).** Encrypt
  the batch locally with a **content** key → ciphertext to disk → background `URLSession` to the
  relay content store → app relaunched on completion to register the manifest via one small
  deferrable EPC op. This is the survives-a-locked-phone path and *is* O3 — it needs the content-key
  exception + a threat-model note. The load-bearing trust question is content-key **custody** on the
  device: cheap for *outbound* (the photos originate on-device, so the key only encrypts bytes the
  device already holds in plaintext), but the threat-model must rule on whether the same key
  decrypts other realm members' content and on Keychain persistence once the device is
  offline/untrusted. Decide when upload-that-survives-backgrounding is built, not before.

**O1 / O4 — confirmed deferred, no v1 impact.** Both belong to Control-on-iOS (6e), sequenced last
(§9.1). v1 is Drive-on-iOS paired to an awake **desktop/Android Control** that does the durable
work — no co-residence (O1) and no background-wake (O4) on the critical path. The only assumption is
a reachable proxy at pairing time, which matches the v1 deployment.

---

## 10. EPC pairing — the real flow (design, 2026-06-19)

Slice 4a built an `EpcPairingView` that drives `EpcLoopback.connect()` — an **in-app loopback**
whose "proxy" is a co-located `EpcResponder`. It proves the handshake/SAS/sealing crypto runs on
device, but it is **not a pairing flow**: there is no out-of-band exchange, so the app has no way to
learn *which* proxy to reach. This section designs the real flow. It is a prerequisite for a usable
iOS client and is **cross-cutting** — it spans the proxy (Control) and the client (iOS), so it is
really the first concrete piece of **6d/6e**, not a client-only UI task. (App Attest, 6c, gates a
channel that can't yet be established — so it was mis-sequenced as "next"; this comes first.)

### 10.1 What does the targeting — and what does not

EPC is a **device-level app↔proxy channel**, *not* a realm connection. Realm membership is a separate
layer (contacts/grants, the `shared-realm-*` proposals). The relay is only a **store-and-forward
mailbox keyed by a rendezvous session id**, and that id is `deriveEpcRendezvousSessionId(pairingSecret)`
(HKDF of an OOB secret — `EpcRendezvousAddress.kt`, 16 bytes). So "which proxy" is answered entirely by
a **shared out-of-band secret**: the proxy that `provision()`s session-id *X* is the peer the app
reaches by deriving the same *X*. Per the Phase-3 design + `EpcRendezvousOverRelayTest`, the **app side
is anonymous** (`authTokenProvider = { null }`); only the **proxy** authenticates (it provisions under
its existing device-key JWT, with the relay's per-owner session cap). The handshake is **ephemeral
X25519** (`EpcHandshake`) — no static proxy public key is needed in the payload; the **SAS**
authenticates the exchange.

### 10.2 The pairing payload (proposed)

The OOB channel therefore carries only:

```
EpcPairingPayload {
  relayBaseUrl: String        // where the rendezvous mailbox lives
  pairingSecret: ByteArray    // fresh, high-entropy (≥16 bytes); HKDF'd to the session id
  // (optional) expiresAtMillis — advisory display TTL; the relay's cap/TTL is authoritative
}
```

Encoded as a compact base64url blob inside a `tenshu://epc/pair#<payload>` URI (mirrors the
contact-invite link convention — `K_link` in the fragment), rendered as a **QR code** plus a copyable
**manual string** for the camera-less path. No proxy identity/pubkey in the payload (ephemeral
handshake). The secret is **per-pairing** and short-lived.

### 10.3 Direction + step-by-step

**Control displays the QR, iOS scans it** (Control desktop has no camera; iOS is the new client — the
"new device scans the existing one" shape, as in device pairing). The proxy must be *serving* before
the app's `ClientHello` lands, so **"show QR" provisions + serves**:

1. **Control (proxy):** generate `pairingSecret` → `sessionId = derive(secret)` →
   `EpcRendezvousAcceptor(crypto, authedRendezvousClient, sessionId).provision()` then launch
   `serve(handler)` (the handler is Control's existing op gateway) → render the QR `{relayBaseUrl,
   secret}` + show its own `establishedSas` once the handshake completes.
2. **iOS (app):** scan the QR → parse payload → `sessionId = derive(secret)` →
   `EpcConnector(crypto).connect(RelayRendezvousTaskTransport(RendezvousRelayClient(relayBaseUrl,
   darwinEngine, authTokenProvider = { null }), sessionId))` → display `connection.sas`.
3. **Both** show the 6-digit SAS; the **user confirms they match** (EPC *measure 1*). Only then is the
   channel trusted; ops flow over `connection.transport`.

`EpcLoopback` is replaced on the real screen by `RelayRendezvousTaskTransport`; the connector/SAS code
is unchanged (the Phase-2 "real transport swaps in behind the protocol" property).

### 10.4 Persistence + re-connection (the 6e seam)

Pairing should yield a **remembered proxy**: the app persists `{relayBaseUrl, pairingSecret}` (Keychain)
and re-derives the session to reconnect later — no re-scan per launch. **But** reconnection needs the
proxy to be *provisioned + serving* at that moment. For a desktop/Android Control that's the always-on
tray / foreground-service case (fine); for an iPhone-only Control it's the wake-window problem (§9.4 /
O4). So **v1 pairing assumes a live, serving Control**; durable reconnection + on-demand serve is
**6e**. (Open: does the proxy keep one long-lived session per paired app, or re-provision on demand?
Resolve with 6e's lifecycle.)

> **Downstream surface is already built (don't re-derive it).** Control's
> central-notifications surface ([`control-central-notifications.md`](control-central-notifications.md))
> is the home for the user-facing side of this wake: when a remembered app reconnects, the
> "an app wants to reconnect" alert rides that surface's `PendingRequest` model + center / pinned
> cards / OS elevation, which already render new variants generically. So 6e here only needs to add
> the **producer** (remembered-app persistence + on-demand provision-and-serve + the wake) and then
> the `PendingRequest.AppReconnect` variant + an "approve reconnect" action over it; that proposal's
> §8 Phase 5 was deliberately left BLOCKED-on-this rather than scaffolded ahead of the producer.

### 10.5 Threat-model

The OOB secret is the shared secret; its confidentiality reduces to the **QR's** OOB confidentiality
(shoulder-surf / a photographed QR ⇒ a relay-level attacker who *also* has the secret could attempt a
MITM). The **SAS confirmation is load-bearing** against that — it is the residual defence the existing
A31 / §8.16 T-RDV analysis already names. This flow distributes the OOB pairing secret A31 anticipates,
so the expected delta is a **§8.16 note** (QR/manual-string as the OOB carrier; SAS mandatory;
per-pairing fresh secret; relay cap/TTL bounds session abuse — T-RDV-03 DoS unchanged), **not** a new
asset. Confirm against A31 when the code lands (HR#2). One sharp edge to call out: **skipping the SAS
check defeats the channel** — the UI must make confirmation mandatory, not dismissible.

### 10.6 What to build (and the open scoping question)

⚠ **Scope (per §11.6, 2026-06-21):** this QR + SAS flow is **not** the first-party Drive onboarding.
A first-party app co-resident with Control rides Control's identity over the App Group + shared
Keychain, so it does no QR/SAS (§11.6). This flow is for (a) **third-party** apps (which cannot share
the App Group) and (b) a **dev/test harness** that exercises the real relay path on the simulator
(desktop Control shows QR → iOS scans). The iOS client side below therefore lives in the **debug
scheme**, not the shipping first-party UI.

- **Proxy side (Control):** wire `EpcRendezvousAcceptor` into Control's accept path (it exists in
  `:sync-service-proxy`, unwired today); generate the secret + provision-on-show + serve over Control's
  op gateway; render the QR + SAS (Compose / `:ui-core` — there's a QR+SAS precedent in the contact
  handshake CI-4b and device pairing).
- **Client side (iOS) — debug-scheme harness + future third-party path, not shipping first-party
  onboarding:** a SwiftUI QR **scanner** (AVFoundation) + manual-string entry → parse the payload →
  drive the **real** `RelayRendezvousTaskTransport` + `EpcConnector` → SAS-confirm screen (replaces
  the 4a loopback view). Lands in the debug scheme alongside `Smokes`.

  ✅ **As-built (client scanner slice, 2026-06-23).** Landed in the `DriveIos-Dev` (`DEV_HARNESS`)
  scheme. Kotlin `EpcRelayPairing` (umbrella `iosMain`) parses the URI → `deriveEpcRendezvousSessionId`
  → builds a Darwin-backed `RendezvousRelayClient` (anonymous `authTokenProvider = null`, §10
  asymmetric admission) → `RelayRendezvousTaskTransport` → `EpcConnector(crypto).connect()`. **Crypto
  is the real libsodium `DefaultSyncCryptoManager`, NOT the bootstrap stub** — Control's
  `EpcResponder` uses the real `SyncCryptoManager`, and bootstrap's fake X25519/HKDF/AEAD does not
  interoperate, so a live round-trip requires real crypto both ends (the EPC handshake is ephemeral —
  X25519 keypair + ECDH only — so the four `cryptoBackendsModule()` backends are all it needs; no
  master/identity keys, no storage). Swift `EpcPairingView` rewritten to scan-or-paste → SAS card;
  `QrScannerView` is an `AVFoundation` representable with a graceful no-camera fallback (the
  **simulator has no camera, so manual paste is the on-sim path**; the camera is real on device —
  `NSCameraUsageDescription` added). The in-app `EpcLoopback` stays as the on-sim SAS proof in
  `Smokes`. Verified on the iPhone-16 simulator headless (`RESULT: ALL PASSED`) via a new
  `EpcRelayPairingSmoke` (URI round-trip + malformed-reject + real `EpcConnector`-over-Darwin against
  an unreachable relay → `RendezvousRelayException`). **Outstanding:** the live SAS round-trip needs a
  Control serving EPC (slice 2b) over a reachable relay → on-device manual gate; `onCallback`/server-
  push over rendezvous is still unbuilt (3b gap). No threat-model change (existing surface #14 / A31).
- **Shared:** the `EpcPairingPayload` codec (encode/decode the `tenshu://epc/pair#…` URI) in
  `:core-common` so both ends share it.

⚠ **Open scoping question (deferred per the 2026-06-19 session):** which Control hosts the proxy side
of the *first* real-pairing slice — **desktop Control only** (easiest to run + show a QR for the iOS
sim/device; recommended), or desktop **+ Android**? Decide when this slice is picked up. The
desktop-only path is end-to-end testable (desktop Control shows QR → iOS scans → pair over the real
relay) without the iPhone-only wake problem.

### 10.7 As-built — slice 2b (Control proxy side, 2026-06-23)

The Compose QR + SAS screen and the platform host landed. **Scoping question resolved: both desktop
*and* Android.** The orchestration (`DefaultEpcPairingHost`) and seam (`EpcRendezvousServer`) live in
`control-shared` *commonMain* — provisioning, the serve→gateway dispatch loop, and the SAS await are
all platform-agnostic — so wiring `single<EpcPairingHost> { … }` into both `PlatformProxyModule`s
(desktop + android) is the same five-line DI block twice; gating it to desktop-only would have meant
*more* work (a dead "Connect an app" action on Android). Desktop stays the testable path (Control
shows QR → iOS sim scans).

As-built shape:

- **`EpcRendezvousServer`** (commonMain seam) — the `provision()` / `serve(handler)` /
  `awaitEstablishedSas()` subset of `EpcRendezvousAcceptor` (`:sync-service-proxy`, android/desktop-
  only), lifted to commonMain so the host orchestration is unit-testable without a live relay. SAS is
  a **suspend point**, not the acceptor's polled `@Volatile` — the host doesn't busy-loop; the
  production adapter bridges the volatile with a coarse poll, the test backs it with a `Deferred`.
- **`DefaultEpcPairingHost`** (commonMain `EpcPairingHost`) — mints the secret, `buildEpcPairingInvite`
  → URI + sessionId, provisions, surfaces the URI, then `coroutineScope { launch serve; onSas(await) }`
  so cancelling `run()` (screen dismissed) tears the relay session down. Op dispatch is injected as
  `suspend (clientId, ByteArray) -> ByteArray` = `ProxyGateway.call`, under a stable session-scoped
  `clientId = "epc:<sessionId>"`.
- **DI** (each `PlatformProxyModule`) builds the acceptor over `RendezvousRelayClient` under the same
  device-key-JWT seam as the relay/content clients, wraps it in an anonymous `EpcRendezvousServer`.
- **UI**: `EpcPairingScreen` (commonMain) renders `QrCode` (`:ui-core`) + a copyable manual link, then
  the SAS as `XXX YYY` monospace (matching the device-pairing wizard) with a confirm/Done. Reached
  from **Apps → "Connect an app on another device"** (`Destination.EpcPairing`), per `control-ia.md`
  §4.3 — the IA "open call" the slice-2a handoff flagged was already settled there.
- **No threat-model change**: this only wires the *existing* EPC rendezvous (relay surface #14 / A31,
  threat-model.md §8.16, landed Phase 3a) into Control's UI — no new protocol, no relay-shape change.

Outstanding: the iOS client **scanner** side (AVFoundation + manual entry → `EpcConnector` over
`RelayRendezvousTaskTransport`) is the next slice (debug-scheme harness per §11.6); on-device
desktop-Control-QR → iOS round-trip is then the manual gate. `onCallback`-over-rendezvous (server
push) is still unbuilt (3b gap) — the dispatch seam is request/response only.

## 11. Control-on-iOS realm membership — decision (2026-06-21)

A session question surfaced the case §3 already anticipates but never resolved at the
membership level: **iOS Control as the *first* device of a realm** — the iPhone-only user who
owns no Mac, desktop, or Android Control. This is expected to be a *common* profile, not an
edge case.

### 11.1 Decision: Control-on-iOS is a full realm device (not an EPC client)

Control-on-iOS **joins the realm as a full, key-holding realm device**, exactly like a desktop
or Android Control. It is not, and must not be, modeled as a keyless EPC client. The reasoning
is forced by §3 + §4: an iPhone-only user has no other device to hold the master key, and
*someone* must hold it and do the crypto. Control is the Tenshu-signed, first-party trusted
surface (§4's in-process exception), so it is the component that holds keys on that phone.
There is no "way around" keeping the master key on the only device the user owns — and we
would not want one (a realm with no key-holder is no realm).

This means **EPC and realm membership are two distinct subsystems with two distinct flows**;
collapsing them is the category error §1.3 (app-registration) warned about, in the other
direction. The three flows:

| Flow | What it is | Key material | Where it surfaces |
|------|------------|--------------|-------------------|
| **Realm genesis** | iOS Control is the *first* device: create realm, generate master key, capture recovery phrase | Mints the master key | Control-on-iOS first-run onboarding (no pairing — there is no peer) |
| **Control device-pairing** | A second Control (later desktop, second phone) joins an existing realm | Receives the master key (handoff + SAS) | The existing cross-Control pairing ceremony ([`sync/pairing-flow.md`](../sync/pairing-flow.md)); realm-device op → Control IA Data → Devices |
| **EPC app pairing** | Drive (or any app) ↔ a Control proxy | None — app is keyless, proxy holds keys (§10.1) | §10 flow; Control IA **Apps** (see `control-ia.md` §4) |

The practical IA consequence: **Control-on-iOS is the one case where "Control" legitimately
appears under Data → Devices** (it is a key-holding realm member). EPC-paired *apps* never do —
they live under Apps. This resolves the earlier ambiguity about whether EPC pairing belonged
under Devices: it does not; only realm membership does.

### 11.2 The partial "way around it" — in-process collapse for the solo first-party phone

The hard cost of an iPhone-only Control is **not** holding the key; it is the wake-window
problem (O4 / §9.4): iOS suspends Control, so a co-resident app reaching it over EPC may find
it asleep. §4's **in-process exception** is the lever that sidesteps this for the common solo
case: a phone running only **first-party, Tenshu-signed apps** can run Drive **in-process**
(the web extension/in-process split) rather than EPC-to-local-Control, so no separate local
proxy needs to be woken. The suspended-local-proxy tax only bites when a **third-party** app on
the phone must reach Control over EPC.

So the platform degrades gracefully: *solo iPhone + first-party apps* → in-process, no wake
problem; *add third-party apps* → EPC to local Control, subject to §9.4 wake windows. **Note:**
§9.5 set the v1 line at proxy-mediated / foreground-only, so in-process-for-Drive is **not** the
default today — adopting it for the solo-iPhone profile is a deliberate decision (it touches the
data-plane trust model → threat-model note in the implementing PR, per O3/§4). ⚠ **Superseded
(§0.5): route-1 in-process IS the shipped first-party default now** — the deliberate decision was
taken and productized ([`ios-drive-parity.md`](ios-drive-parity.md) P1–P6).

### 11.3 Prerequisites this opens (not yet specced)

Two gaps must close before iOS-Control-as-first-device is buildable:

- **O6 — iOS master-key custody.** Control-on-iOS holding the master key needs a Secure
  Enclave-backed wrap key + Keychain persistence, Face ID / passcode gated — the iOS analog of
  the desktop `MasterKeyStore` macOS Touch ID / login-password path
  ([`desktop-masterkeystore.md`](desktop-masterkeystore.md)). No iOS backend exists yet; see that
  doc's open questions.
- **O7 — First-device genesis UX on iOS.** Realm creation + recovery-phrase capture inside
  Control-on-iOS onboarding. The existing onboarding docs assume genesis happened on a
  desktop/Android Control; the iPhone-first path is unwritten. Sequence with Phase 6
  (Control-on-iOS).

### 11.4 Sequencing note

This decision does not change the Drive-client-first sequencing (§9). It constrains Phase 6
(Control-on-iOS): Phase 6 must deliver genesis + device-pairing + key custody, not merely a
co-resident EPC endpoint. The EPC app-pairing flow (§10) remains independent and ships first.

### 11.5 In-process first-party apps ride the device's sync identity — decision (2026-06-21)

When a first-party app takes the in-process path (§11.2 / §4 exception), the open question was
whether it is **its own sync device** (own device key, HLC node, quorum slot, revocation unit,
replica) or whether it **rides the device's existing identity**. **Decision: it rides the
device's single, Control-anchored sync identity** — a physical device is *one* sync device
regardless of how many first-party lenses are installed.

**Why.** This keeps iOS consistent with the model the platform already uses everywhere else: on
Android, Drive holds no key and no identity — it talks to Control over Binder, and **Control is
the single sync authority** for that device; apps are clients/lenses (the Control IA thesis,
`control-ia.md` §5 — "apps are temporary lenses; data is the realm's"). Riding the device
identity moves only the *crypto* in-process (for the §9.4 liveness win), not the device model.
The alternative (app-as-its-own-device) contradicts the lens model, **bloats quorum** (every
rarely-opened app must ack before `SyncMailbox.gcSweep` can proceed — `sync/design.md` §2.6),
and clutters the Data → Devices list with one row per lens.

**The load-bearing constraint — "ride the identity" must mean a shared sync *authority*, not a
shared label.** A sync device is not just a name: it is (1) a device key that signs relay JWTs
**and signs HLC tips**, (2) a per-device HLC clock, (3) a quorum slot, (4) a revocation unit
(`sync-relay-persistence.md` §2; `HlcTipStore` + `highestSeenByDevice` regression detection).
If Control and the in-process app are two processes both writing under the *same* `deviceId`
with *separate* HLC counters, they produce two differently-signed tips for one device — an
**HLC fork**, which the regression detector is built to reject. So the decision requires a
**single HLC authority + single signing key + single shared replica per device**: first-party
writes serialise through one on-device sync core, rather than two processes hitting the relay
independently. This narrows what "in-process" means here — in-process *crypto*, **not** an
in-process *independent direct-to-relay writer*.

⚠ **As-built (§0.5): route-1 IS an in-process independent direct-to-relay writer** — Drive does its
own in-process crypto → wire-valid `SyncBlock` → `POST /sync/push`, and Drive (not Control) is the
background syncer. The HLC-fork hazard this section raised was **solved as the mechanism, not
avoided**: a cross-process shared HLC counter + `SharedReplicaDatabaseLock` (flock) + Drive riding
Control's `deviceId`/A4 over the *same* shared replica. The "one sync core, in-process crypto only"
prescription is the road not taken; the residual cost is multi-writer-to-one-SQLite concurrency
complexity (mitigated by the flock + shared counter + writer-crash recovery).

**App Attest justifies the trust, not the mechanism.** Attestation proves a genuine build on a
genuine device, which is why first-party apps are *allowed* to share the device identity — but
it does nothing for HLC/key/replica consistency. Don't let "we can attest it" stand in for "the
coordination is handled."

**Downsides to design around (priority order):**

1. **Cross-process HLC + key coordination (the sharp one).** One signing key + one monotonic
   clock shared across the Control and app processes, on iOS, where the App Group is the only
   shared channel (legitimate for same-team first-party apps, §1) and Control may be suspended.
   Buildable via single-writer discipline + advisory file locks in the App Group + the device
   key in the shared Keychain — but concurrency-fragile and the real engineering cost. Skipping
   it corrupts the merge (see HLC fork above). **Prerequisite for the in-process path.**
2. **Replica divergence vs. quorum/GC.** If "shared identity" degrades into "shared label,
   separate replicas," the relay sees one ack at the device's HLC tip and may GC bodies only one
   replica pulled → silent data loss. Fix: one shared device replica. This also reframes the
   §11.3 "first device retains a replica" point — it is *the device's* one replica that both
   Control and the app are views over, not the app's own copy alongside Control's.
3. **Coarse revocation.** Riding the device identity means a single first-party app cannot be
   revoked independently of the device. Acceptable for trusted first-party; a real loss of
   granularity if an app ships a bad build.
4. **Control becomes a hard anchor dependency.** Shared identity assumes Control exists on the
   device as the key custodian / sync authority. Holds on iOS (Control is required and is the
   genesis/key-holder for the solo phone, §11.1), but it couples every first-party app's sync
   identity to Control being installed — state it as a dependency, don't rediscover it.

This refines **O3** (the in-process data-plane exception): the exception is in-process crypto
over a shared device sync authority, not an independent per-app device. Third-party apps are
unaffected — they remain proxy-mediated EPC (§4) and never share identity.

### 11.6 Minimal Drive-on-iOS UI — the next slice (handoff, 2026-06-21)

The slice-4a/4b app is a three-tab harness (`Pair` loopback / `Files` canned / `Smokes`). This
section specs the **minimal** rework that makes the onboarding decisions real. The guiding fact:
almost every screen is *bind an existing `:drive-shared-logic` view model to SwiftUI* (§9.3 — the
VMs are already SKIE-exported), so there is **very little new UI to write** once onboarding is
settled.

#### Onboarding is a forward-to-Control handoff, not an in-Drive pairing ceremony (decision, 2026-06-21)

A first-party app **must not** run its own QR + SAS pairing flow. That ceremony (§10) exists only
because the general EPC channel runs over the relay (an untrusted path), so it needs an OOB secret
("which proxy") and a SAS (anti-MITM). A first-party app co-resident with Control has neither
problem: the **App Group + a shared Keychain access group** is a channel Apple gates to the *same
Team ID*, i.e. an already-authenticated *local* channel (§1 — App Groups work for Tenshu's own
apps; §11.5 — the app rides Control's device identity through it). So there is no "which proxy"
question and no network MITM to defend against — **the QR and the SAS both fall away.** Drive's
onboarding collapses to a presence check on the shared container:

- **Control set up on this device →** ride its identity **silently** (this is the already-locked
  "first-run is silent" registration decision, `control-app-registration.md` §3.2) → straight to
  Files.
- **Control absent →** a single screen that **deep-links the user to Control** to install / set up
  (genesis or pair-to-realm), then returns.

In the shipping product this is the **only** first-party onboarding, because Control is required on
every iOS device (§3) — a real iPhone always has a local Control to forward to. There is no shipping
first-party "reach a Control over the relay" case. The §10 QR + SAS EPC flow is therefore **not**
Drive's onboarding; it is retained for (a) **third-party** apps (which cannot share the App Group)
and (b) a **dev/test harness** (desktop Control shows QR → iOS scans → exercises the real relay
path on the simulator), and it lives in the **debug scheme**, not the shipping UI.

#### Near-term build decision (2026-06-21): end-state shape, stubbed Control

Forwarding needs a local Control-on-iOS to forward *to*, which §9 sequences last. **Decision: build
Drive's onboarding in the end-state forward-to-Control shape now**, with the "is Control set up?"
check reading the shared Keychain access group, exercised against a **stub/dev tool** that
provisions that item until Control-on-iOS lands. Keep the QR+SAS loopback in the **debug scheme** as
the dev harness. Consequence accepted: **no throwaway first-party QR/SAS UI is built**, and Drive is
not end-to-end-real against a real Control until Control-on-iOS ships — the stub stands in for the
provisioning step meanwhile.

#### App shell

Replace the `TabView` with a `RootView` that reads the **shared Keychain access group** for a
Control-provisioned session handle: present → main; absent → the forward-to-Control screen. Minimal
main is Files + Add + Settings, so **drop the tab bar** and use one `NavigationStack` rooted at
Files with toolbar buttons. (The tab bar returns when the Shared surface lands.)

#### Onboarding (the only genuinely new screen)

| Screen | What it does | Reuses / needs |
|--------|--------------|----------------|
| **Connect to Control** | shown only when no Control session is present; explains Drive needs Tenshu Control on this device; button deep-links to Control (install / open), returns on completion | shared-Keychain read; URL-scheme deep link to Control; **no QR, no SAS** in the first-party path |

#### Main app (mostly wiring)

| Screen | View model (`:drive-shared-logic`) | Notes |
|--------|------------------------------------|-------|
| **Files (home)** | `FilesScreenViewModel` via `TenshuPagingPresenter` | already half-done in `FolderListView`; add folder push-nav, pull-refresh, tap-to-open; row actions via `FileActionsViewModel` |
| **+ Add** | `AddFileActionsViewModel` | iOS `UIDocumentPickerViewController` / `PHPickerViewController` → existing `FileUploader`; **foreground-only** per §9.4 / O3 |
| **Settings** | (connection state; optional `SyncPreferencesViewModel`) | shows *"Connected through Control on this device"* + an **Open Control** link; **removal/unpair lives in Control** (App Detail → Connections, `control-ia.md` §4.3), not in Drive — Drive rides Control's identity (§11.5) and does not own the pairing |
| _cheap extras_ | `CreateFolderDialogViewModel`, `RenameFileDialogViewModel`, minimal `FileDetailsViewModel` | include if low-cost; VMs already exist |

#### Deferred / out of this slice

Shared surface as a second tab (`SharedScreenViewModel` / `ManageAccessViewModel` / invites /
contacts); background `URLSession` upload (O3-B); synced-folder auto-backup (PhotoKit + "open app to
finish" nudge — reframe, do not port, §9.4); Control-on-iOS genesis onboarding (O7); the real
shared-Keychain provisioning (lands with Control-on-iOS, replacing the stub). **Smokes** → debug-only
scheme (keep `SmokeReport.logToConsole()` for headless CI), alongside the QR+SAS dev harness; neither
is a shipping tab.

**Pairing surfaces are not lost — they belong to other roles/phases (clarification).** Dropping
QR/SAS from *Drive's first-party onboarding* removes it only there. The capability is still needed,
in the right place and direction:

- **iOS EPC *scanner* (client side).** Built **now in the debug scheme** (the §10.6 dev harness);
  graduates to the shipping **third-party** app path (third-party apps can't share the App Group, so
  they scan a Control's QR / enter a manual string). Not lost — just not in Drive's shipping UI.
- **Control-on-iOS *proxy-side* pairing UI — Phase 6.** For apps to connect *to* a Control-on-iOS,
  Control must **display** the QR + show/confirm SAS (the §10.6 "proxy side", here on iOS). Control
  is the QR *shower*, not the scanner, in EPC.
- **Control-on-iOS *device-pairing* (realm join) — Phase 6.** The one case Control-on-iOS actually
  **scans**: joining an existing realm as a second Control via `sync/pairing-flow.md` (§11.1) — a
  different ceremony from EPC app pairing.
- **Same-device OOB nuance.** A camera can't scan the phone's own screen, so a *co-resident*
  third-party app ↔ Control-on-iOS cannot use camera-QR; that OOB carrier must be the **manual
  string / deep link / pasteboard** (the §10.2 manual-string fallback), with SAS still mandatory.
  Resolve the exact carrier when the third-party-on-iOS / Control-on-iOS pairing slice is picked up.

#### Net new code

The forward-to-Control screen + shared-Keychain presence check (+ dev-stub provisioner), and the iOS
Document/Photo pickers. **No QR scanner and no SAS screen in the shipping first-party app.**
Everything else is SwiftUI over already-exported view models.

## 12. Timeline & Fit iOS apps — as-built (2026-07-06)

The second and third first-party iOS apps after Drive/Control. Both are pure applications of the
§9.3 boundary (share the Compose-free view-model/repository layer, write the UI in SwiftUI) and
started **mock-backed**, exactly as `drive-ios`/`control-ios` did over their fakes. Shipped as two
PRs — `timeline-ios` first (the pattern-setter), then `fit-ios` stacked on it.

**Why this was cheap.** Unlike Drive (which needed `:drive-shared` split into a Compose-free
`-logic` slice), Timeline and Fit were *already* factored: `:timeline-shared-logic` and
`:fit-shared-logic` already declared `iosArm64()`+`iosSimulatorArm64()`, all their view models live
there (Compose-free `ViewModel` + `StateFlow`), and each carries its `*Repository` seam **and** its
`Mock*Repository` (Fit also `MockRecordingEngine`). So the mock-backed app needs **only**
`-shared-logic` exported — nothing from `-shared-data`, keeping the `:health-service-proxy` /
`:place-service-proxy` / `:timeline-service-proxy` deps off the iOS surface.

**The recipe (per app), mirroring `drive-ios`/`control-ios`:**
1. `export(:{app}-shared-logic)` + matching `api` on the umbrella (lockstep).
2. A `{App}Surface` object in `:tenshu-sdk-ios` `iosMain` — an install-slot `{App}Repository`
   (default `Mock{App}Repository`) + one factory per view model — the iOS analog of `DriveReadSurface`,
   since Koin-Compose DI (`:{app}-shared-ui`) isn't on iOS. The live-EPC tier later calls
   `installRepository(...)` with a `{App}ProjectionRepository(client)` — no SwiftUI change (the Drive
   Tier-1/Tier-2 seam). A `{App}SurfaceTest` covers the factories on the `iosSimulatorArm64Test` gate.
3. A `{app}-ios/` XcodeGen app (copy `control-ios/`; mock-only ⇒ no Keychain/camera/entitlements) +
   a `generate{App}IosProject` Gradle task + a `.githooks` entry.
4. Native SwiftUI screens binding VMs via `@StateObject` + SKIE `AsyncSequence`; sealed UI states via
   `onEnum(of:)`. No paging (these lists are plain `StateFlow<List<…>>`, so no `TenshuPagingPresenter`).

**Latent fix surfaced.** The iOS umbrella didn't compile on `master`: `FakeFilesRepository` was
missing `openStreamingContent` (added to `FilesRepository` by the streaming-preview work — iOS isn't
CI-gated, CI being billing-blocked). Fixed in the `timeline-ios` PR. **Takeaway: run
`:tenshu-sdk-ios:iosSimulatorArm64Test` + `xcodebuild` locally when touching the umbrella — nothing
else guards the iOS build.**

**Live-EPC Kotlin seam — SHIPPED for both apps (Fit: §9.9 of `fit-app.md`; Timeline: this
section).** The client tier turned out cheaper than feared — the `-shared-data` "pulls `-proxy` via
`InProcessProxy`" worry was unfounded: `:{fit,timeline}-shared-data` already compile for iOS and are
added to the umbrella's iosMain deps **without** exporting them (Swift drives them through the
`{App}Surface` Kotlin methods, mirroring `:control-shared-proxy`); the `-proxy` transitive dep rides
along exactly as it does for Control, master key / op-impls staying off the client (HR#1). The seam
per app:

- **`Epc{App}Client.assembleClient(channel): {App}Client`** (umbrella iosMain) — mirrors
  [`EpcDriveClient`]: `coreCommonModule()` + every service `-common`/`-client` schema config **plus the
  app's stores** (Fit → `health`; Timeline → `timeline` + `place` + `health`), a
  `RemoteBinaryTaskExecutor` over the channel, a remote `TenshuClient`, and a real
  `{App}ProjectionRepository(client)`. Because the `-common`/`-client` configs are `implementation`
  deps of `-shared-data`, they are added explicitly to iosMain so the assembler can name the modules.
- **`{App}Surface.installRealClient(channel)`** — assembles + swaps `installed` onto the projector;
  `installMock()` restores. Zero SwiftUI change.
- **`{App}LiveClientSession`** — channel-lifecycle owner (`activate(pairing)` / `teardown`),
  session-scoped, nothing persisted (ephemeral X25519 session keys + one-shot rendezvous).

Verified on `iosSimulatorArm64Test` (`Epc{App}ClientTest`): the full graph assembles on iOS,
`installRealClient` swaps off the mock, and a real op marshals down to `TaskTransport.call`; the
umbrella framework links + the ObjC header exports the additive API. No SwiftUI change → no
`xcodebuild` app run needed at the seam stage (a seam, not a UI slice).

**Pairing dev-harness — SHIPPED for both apps (this PR).** The Swift half that drives the seam: a
`DEV_HARNESS`-gated scheme (`{App}Ios-Dev` over a new `DebugHarness` config that defines the
`DEV_HARNESS` compilation condition) whose app root is a two-tab `TabView` — the normal app on one tab
plus a **Pair** tab (`EpcPairingView`, a verbatim adaptation of drive-ios's) that runs
`EpcRelayPairing.connect(pairingUri:)` over the relay, shows the 6-digit SAS, and on confirm calls
`{App}LiveClientSession.activate(pairing:)` — installing the real `{App}ProjectionRepository`. A
`.liveSessionChanged` notification bumps an `.id()` on the App tab so its view models **remount and
rebuild over the now-live repository** (the surface swap happens at VM-construction time, so a fresh
mount is what picks it up). `QrScannerView` is copied per app (self-contained AVFoundation, gated to
`DEV_HARNESS`); `NSCameraUsageDescription` is added for the on-device QR path. The **shipping** scheme
stays mock-only (the `#else` branch; the harness files are fully `#if DEV_HARNESS`-gated), so no
entitlements / no App-Group / no Keychain — simpler than drive-ios (which also carries the route-1
probes). Verified: `xcodegen generate` + `xcodebuild` **green for all four schemes** (`{Fit,Timeline}Ios`
shipping + `{Fit,Timeline}Ios-Dev` harness) on the simulator; `FitIos-Dev` boots clean and renders the
two-tab shell. **Device gate CLOSED 2026-07-17:** the live SAS round-trip against the shipping
Control-on-iOS **passed on hardware** (iPhone 17e, free-tier-signed builds — see
`ios-signing.md` + `fit-app.md` §9.12 P-iOS-1): Control's *Settings → Connect an app* relay
invite → Fit `FitIos-Dev` Pair tab → SAS → `FitLiveClientSession.activate` swapped the harness
onto the live client. Timeline's twin seam rides the identical recipe (not yet run).
**Keychain-invite auto-connect BUILT (2026-07-17):** the shipping Fit now rides the
Control-provided local channel — Control's "Connect an app" host publishes its live invite into
the shared Keychain (`publishEpcInvite`, now a shipping call) and Fit's `ControlAutoConnect`
reads it on launch/foreground and silently connects + `FitLiveClientSession.activate`s: no QR,
no SAS (Keychain custody stands in — threat-model "Co-resident EPC auto-connect" amendment).
Fit gained the shared-Keychain entitlements (sim bare / device Team-prefixed,
`Fit.entitlements` + `Fit.device.entitlements`); the `-Dev` Pair tab remains as the manual
fallback. Free-tier compatible (Keychain, not App Group). Constraint unchanged: the rendezvous
completes only while Control's host session is alive (iOS suspends background apps) — the
zero-interaction *replica* read is the paid-tier route-1 App-Group shape.
> ⚠ **Deleted 2026-11-05.** `ControlAutoConnect` is gone and the threat-model sub-amendment is
> retired: route 1 covers the co-resident case
> ([`ios-first-party-route-1-migration.md`](ios-first-party-route-1-migration.md) §8). Control's
> *Connect an app* host and `publishEpcInvite` stay. No shipping app reads that invite now; the
> `-Dev` Pair tab (SAS) does.

## Appendix — Implementation Notes for Claude Code

This is the canonical handoff section. Read it with the phasing in §8; each phase is one PR (or
a small stack). House rules #1, #2, #3, #4, #7 all bear on this work — re-read them before
touching the relay or adding a `suspend`.

### Repo state today (verified 2026-06-16)

- iOS K/N targets (`iosX64`, `iosArm64`, `iosSimulatorArm64`) are **declared** in shared modules
  (`:core`, `:core-common`, and the service `-common`/`-client` modules) and `src/iosMain` source
  sets exist (e.g. `core-common/src/iosMain`). But **no `.framework`/`XCFramework` is produced** —
  the build files say so explicitly: *"When an iOS app appears, expose a proper XCFramework"*
  (`core/build.gradle.kts`).
- **No iOS CI lane.** `.github/workflows/` has `android.yml`, `relay-image.yml`, `web.yml` only.
  So the declared targets compile-rot invisibly — this *is* the declared-targets hazard called out in
  `docs/tech-debt.md` ("declared-but-unbuilt platform rots invisibly").
- **No iOS app module** in `settings.gradle.kts` yet (apps are `:drive`/`:drive-desktop`,
  `:control`/`:control-desktop`). The JS analog `:tenshu-sdk-js` exists and is the pattern to
  mirror for the iOS umbrella.

### Phase 1 — first PR (do this one first; everything else depends on it)

**Goal:** prove the K/N core actually builds and tests on iOS, and stop it rotting. Pure build /
CI work — no product code, low blast radius, high de-risking value.

1. **Stand up one umbrella XCFramework**, mirroring `:tenshu-sdk-js` — a new `:tenshu-sdk-ios`
   (or `:shared-ios`) module that depends on + exports the client-facing shared surface an iOS
   app needs (`:core-client` and the service `-client`/`-common` modules). Configure
   `XCFramework`/`binaries.framework` there. **Do not** add per-module frameworks — the
   `core/build.gradle.kts` comment records that each module declaring its own `shared` framework
   was "pure build-time cost"; one umbrella is the decided shape.
2. **Add an iOS CI lane** `.github/workflows/ios.yml` on a `macos-*` runner that runs the iOS
   compile + test + `assembleXCFramework` tasks, and make it a required check. This is the
   actual fix for that hazard.
3. **Re-enable the disabled iOS tests** and get `iosSimulatorArm64Test` green for the shared
   modules.

**Acceptance (House Rule #7 — tests, not assertions):**
- `./gradlew :core:iosSimulatorArm64Test :core-common:iosSimulatorArm64Test` (+ the other shared
  modules) pass.
- The umbrella `assembleXCFramework` task produces a framework artifact.
- The new CI lane runs on PRs and is green.

**Also lift:** update the iOS-targets entry in `docs/tech-debt.md` to reflect the now-gated target.

### Later phases — module targets (lighter; specify fully when the phase is picked up)

- **EPC channel, measure 1 (Phase 2):** app↔proxy pairing + authenticated key exchange + E2E
  symmetric channel. New shared protocol code in `:sync-service-common` / `:core-common`; reuse
  the existing `[length][type][payload]` framing (`StreamFramer`, see `desktop-ipc.md` §2) for
  wire consistency. Prove against a foregrounded proxy first.

  **Phase 2 plan — protocol-first over loopback (decided 2026-06-16).** Sequencing knot: the
  bullet above says "on top of the relay rendezvous channel (§5)" but rendezvous is *Phase 3*.
  Resolution: Phase 2 builds and proves the **channel protocol over a direct in-process /
  loopback transport, no relay** — matching "foregrounded proxy first" — and Phase 3 later swaps
  the loopback for relay rendezvous underneath the *same* channel. Open question O1 (foreground
  loopback fast-path vs always-relay) is thereby deferred to Phase 3, not Phase 2. The substrate
  is all present and reused, not reinvented: the device-pairing X25519-ECDH-+-SAS pattern
  (`InitiatePairingImplementation` / `PairingSas.derivePairingSas`), the `SyncCryptoManager`
  crypto surface (`generateKeyPair` / `calculateSharedSecret` / `encryptWithAad` + `hkdfSha256`),
  and the transport seam (`TaskTransport` client-side, `ClientConnection` proxy-side,
  `StreamFramer` framing, with `InProcessTaskTransport` as the loopback). Slices:

  - **2a — EPC handshake + session, pure protocol (`:core-common`, package
    `org.tenshu.core.common.epc`). ✅ first PR.** Authenticated key exchange mirroring device
    pairing: both ends mint an ephemeral X25519 keypair, exchange public keys, ECDH → a session
    secret via `hkdfSha256(shared, salt = initiatorPub‖responderPub, info = "tenshu/epc/session/v1")`
    and a 6-digit **SAS** via the same construction with `info = "tenshu/epc/sas/v1"` (the SAS is
    the MITM authenticator — no third party trusted, whitepaper measure 1). The resulting
    `EpcSession` derives **two directional AEAD keys** (`…/key/c2p/v1`, `…/key/p2c/v1`) and
    `seal`/`open`s frames with a per-direction monotonic sequence counter bound as AAD
    (`encryptWithAad`) → confidentiality + integrity + replay/reorder rejection. No transport, no
    relay, no `suspend`. Tested in `commonTest` with `BootstrapSyncCryptoManager` so the suite
    runs on the **iOS simulator gate too** (the iOS crypto backends are still throwing stubs, but
    the bootstrap manager is pure-Kotlin and cross-platform).
  - **2b — loopback `EpcTaskTransport`. ✅ shipped.** `EpcTaskTransport` (`:core-common`) wraps
    any `TaskTransport` with an `EpcSession` — seals outbound `call`/`callCancellable` requests,
    opens responses + pushed `onCallback`s — proving an end-to-end encrypted client→proxy
    round-trip with **no proxy-core change** (the proxy decrypts with the peer session and the
    gateway still sees plaintext `ByteArray`). As-built decisions: (i) EPC is an *encryption
    layer over* an existing transport, not a transport of its own — `inner` keeps connect/bind/
    framing/delivery; (ii) responses and callbacks share the proxy→client sequence counter, so
    EPC **assumes the underlying transport preserves frame order** across both (true for a single
    ordered stream — UDS / Binder / a relay mailbox / the loopback — and that order is the
    transport's responsibility, not EPC's); (iii) the `callCancellable`/`cancel` `taskId` stays
    in the clear (a routing tag, not user data; the bodies it tags are sealed). Tested in
    `commonTest` (loopback round-trip + callback + cancellable + counter advance, runs on the iOS
    gate) and `desktopTest` (wire-is-authenticated-ciphertext + tamper-fails, real backends).
    Real iOS crypto (CryptoKit) is the gating item for running 2b on a device, not for landing it.
  - **2c — handshake driver + SAS-confirm seam. ✅ shipped (Phase 2 complete).** `EpcConnector`
    (client) + `EpcResponder` (proxy) in `:core-common`. One round-trip over the *plain*
    transport — `ClientHello`/`ServerHello` (`[version][ephemeral pubkey]`, length-agnostic so
    the stub + real backends share framing; unknown version is a hard error, the
    "update-the-app" rule) — completes the 2a handshake and returns an `EpcConnection(sas,
    transport)` whose transport is the 2b `EpcTaskTransport` ready for sealed operation traffic.
    **Design correction to the original bullet:** this is *not* a schema `IoOperation`. Schema
    operations are dispatched *through* the channel, so the step that establishes the channel
    cannot be one (circular); it is plain client-side orchestration (`suspend`, like
    `TaskTransport.call` itself — HR #3 governs the schema dispatch layer, not transport
    bring-up). `EpcResponder.accept` is non-`suspend` pure compute so it drops into a foreground
    loopback now and the Phase-3 relay accept loop later unchanged. The SAS-confirm *UX* is the
    caller's (an iOS ViewModel surfaces `EpcConnection.sas`); the connector's job is to surface
    it. Tested end-to-end (connector ↔ loopback ↔ responder: SAS agreement + encrypted op
    round-trip) in `commonTest` (iOS gate) + `desktopTest` (real crypto, fresh-key SAS
    uniqueness). **Not yet wired into any app** — that happens with the iOS app module (Phase 6);
    Phase 2 is a self-contained, fully-tested EPC protocol/transport library in `:core-common`.
- **Relay rendezvous (Phase 3):** new surface in `:sync-relay-server`, in its **own route group +
  store**, separate from the realm-device mailbox code that `sync-backend-relay-plan.md` /
  `sync-relay-api.md` target. It carries opaque ciphertext frames and must not parse or decrypt
  payloads (House Rule #1). Touches the relay's data shape → threat-model amendment in the same
  PR (House Rule #2).

  **Phase 3 plan — relay rendezvous (drafted 2026-06-16).** Phase 2 proved the channel over a
  loopback; Phase 3 swaps the loopback for a relay-brokered store-and-forward mailbox **without
  changing the Phase-2 protocol** — `EpcConnector`/`EpcResponder`/`EpcTaskTransport` are reused
  verbatim; only the underlying `TaskTransport` changes. Design, mirroring the landed
  `/sync/invite/*` (contact) and `/sync/grant/*` (membership) sealed mailboxes:

  - **Out-of-band pairing seed → mailbox address (no directory).** EPC pairing starts from a
    pairing code the proxy shows and the app scans (QR/link — same trust model as device
    pairing's QR; never relay-visible). It seeds a high-entropy rendezvous address via
    `deriveEpcRendezvousAddress(pairingSecret, direction)` (HKDF, mirroring `deriveGrantAddress`),
    so app and proxy compute the *same* address without a directory or either holding the other's
    network location. Knowledge of the address is the capability to use the mailbox (exactly the
    `/sync/invite` `/sync/grant` anonymous-pickup posture). The pairing seed does **not**
    authenticate the channel — the 2a SAS does (a malicious relay that swaps handshake frames
    produces a divergent SAS, caught by the user). Two layers, same as device pairing.
  - **Duplex store-and-forward.** Two directional addresses (client→proxy, proxy→client) each a
    short FIFO of opaque sealed frames (the Phase-2 `EpcSession` frames). The relay stores
    `(address, seq, sealed-blob, expiry)`, serves in order, and never inspects the bytes. The
    Phase-2 `EpcSession` sequence + AEAD already give end-to-end replay/reorder/tamper rejection,
    so the relay queue is pure transport.
  - **Admission gate — asymmetric, reusing existing auth.** The **proxy is a realm device**, so it
    provisions/owns a rendezvous session under its **device-key JWT** (binds the session to its
    `ownerId`; bounds how many a given owner may open — the `maxOpenPerOwner` pattern). The **app
    is not a realm device** and posts/polls **anonymously** with the pairing-derived address as a
    bearer capability (mirroring invite/grant). Anti-abuse: per-owner session caps, per-address
    frame caps, short TTL, and rate limiting. **O2 resolved (2026-06-16): no edge App Attest — the
    JWT-gating + caps above are the whole admission story, keeping the relay vendor-neutral with no
    Apple dependency; revisit only if abuse is observed.**
  - **Poll first, long-poll later.** MVP uses simple poll with backoff (matches today's relay);
    an optional `?wait=<ms>` long-poll and the push/`BGTaskScheduler` wake fabric (§3) are the
    Phase-6 background-liveness concern (O4), not Phase 3.

  Slices:
  - **3a — rendezvous mailbox surface + store + admission gate (`:sync-relay-server`). ✅ shipped.**
    `/sync/epc/{sessionId}` route group (`configureEpcRendezvousRouting`, mirroring
    `InviteRouting`/`MembershipGrantRouting`): JWT-gated `POST`/`DELETE` session provisioning +
    teardown (the admission gate) and anonymous capability-addressed `POST`/`GET` frame traffic on
    two directional FIFOs. `RendezvousRegistry` store (in-memory `RendezvousStore` default +
    `SqliteRendezvousStore`, migration **v10**: `epc_session` + `epc_frame`), per-owner session cap
    + per-direction frame cap + 15-min TTL + hourly GC. `deriveEpcRendezvousSessionId` in
    `:core-common` (HKDF of the pairing seed only — directions are URL path segments, a small
    simplification of the "two HKDF addresses" sketch). **The threat-model amendment landed in this
    slice (HR #2): A31, surface #14, §8.16 T-RDV-01–06.** Relay-only; no client wiring yet (3b).
  - **3b — relay-backed transport. ✅ shipped (Phase 3 complete).** `RendezvousRelayClient`
    (HTTP wrapper over `/sync/epc`, mirroring `RestInviteRelayClient`) +
    `RelayRendezvousTaskTransport` (client `TaskTransport`: append c2p, poll p2c — strict
    lock-step request/response) + `EpcRendezvousAcceptor` (proxy: JWT-`provision` then `serve` the
    `EpcResponder` over the mailbox), all in `:sync-service-proxy` commonMain. The Phase-2
    `EpcConnector`/`EpcResponder`/`EpcTaskTransport` are reused **verbatim** — only the inner
    transport changed. As-built notes: async proxy-pushed `onCallback`s are **not** carried over
    rendezvous yet (they need the `StreamFramer` demux + the §3 wake fabric — deferred); poll is
    simple-poll-with-backoff (no long-poll until O4). End-to-end proof lives in
    `:system-test-utils` (JVM 21, the only module that can host the relay's `module()` via
    `testApplication` *and* see the `:sync-service-proxy` client): app ↔ real relay ↔ proxy
    completes the handshake (SAS agreement) + round-trips two encrypted operations; provisioning is
    a genuine device-key JWT (real `BouncyCastleEd25519Backend`), EPC crypto is the bootstrap stub
    (the bridge, not crypto strength — that's the 2a/2c real-crypto tests).
  - **3c — server push over rendezvous. ✅ shipped (2026-06-24).** Closes the 3b `onCallback` gap:
    a paired app now receives proxy-pushed callbacks (observers/streams), not just request/response.
    The lock-step `RelayRendezvousTaskTransport` is replaced by **`EpcRendezvousChannel`** (the
    relay-specific client channel; `EpcConnector`/`EpcTaskTransport` stay the loopback path). It runs
    **one ordered background reader** over the proxy→client direction that `EpcSession.open`s each
    frame in arrival order — so replies and callbacks, which share the one proxy→client key+counter,
    stay sequence-monotonic — and demuxes on a **1-byte `EpcFrameType` tag carried inside the seal**:
    `DATA` completes the head of the FIFO of waiting `call`s, `CALLBACK` goes to `onCallback`. The
    acceptor gained `pushCallback` + a seal-mutex (replies and pushes serialise their seal+append).
    **No relay / wire / threat-model change:** the tag lives inside the sealed payload the relay
    already stores opaquely, so the rendezvous data shape is unchanged (still two FIFOs of opaque
    blobs). Enabling tweaks: `EpcHandshakeMessage` made public + `:epc-rendezvous-client` gained
    `implementation(:core)` (for `SyncCryptoManager`, mirroring `:core-common`). iOS `EpcRelayPairing`
    + the system-test migrated to the channel; the over-relay system-test extended to prove a
    proxy `pushCallback` reaches the client's `onCallback`, interleaved with the reply stream.
    **Outstanding:** wiring the proxy's gateway `ClientConnection.send` (real ops) into `pushCallback`
    so live observers push automatically — the transport capability is proven, the ops hookup is the
    next slice; simple-poll still (no long-poll until O4).
  - **3c-ops — gateway → push wiring. ✅ shipped (2026-06-24).** Closes the ops half of §3c: an op on
    an EPC-paired session that registers an observer/stream now pushes automatically. The proxy mints
    an **`EpcClientConnection`** (`control-shared`) for the `epc:<sessionId>` clientId (via the
    connection factory) whose `send(Data)` serialises with the same `BinarySerializer` as the other
    transports (inline bytes, no OOB tokeniser — the EPC seal carries everything) and routes the bytes
    — in emission order, via a single ordered consumer — through an **`EpcCallbackRouter`** into the
    session's `EpcRendezvousAcceptor.pushCallback`. `DefaultEpcPairingHost` registers/unregisters the
    route over the session's lifetime; `StreamRegistry` is unchanged (it already drives every push
    through `executionContext.clientConnection.send`). No relay/wire/threat-model change. Tests:
    `EpcCallbackRouterTest`, `EpcClientConnectionTest` (real serialise→route→`BinaryParser` round-trip,
    order-preserving), `DefaultEpcPairingHostTest` (register-on-serve / unregister-on-teardown); full
    `:control-shared` desktop green.
  - **3c-consume — iOS client consume wiring. ✅ shipped (2026-06-24).** Closes the client half: a
    proxy-pushed stream element now reaches the iOS app. `assembleRemoteConsumeStack(transport,
    serializer, parser, context, scope)` (`:core-common`, Koin-free) wires a `RemoteBinaryTaskExecutor`
    over a `TaskTransport` (the `EpcRendezvousChannel`) with its `streamElementHandler` bound to a
    `DefaultInteractorProvider` — the same wiring `coreCommonModule` does for Android/desktop, factored
    out for the iOS umbrella (no app DI graph) and tests. `EpcDriveClient.assembleConsume(channel,
    scope)` (umbrella iosMain) stands it up from the production `coreCommonModule` (its core-base schema
    parses the wire `StreamElement` DTOs — all the consume path needs). Verified: `RemoteConsumeStackTest`
    (real serialize → push → parse → correct `StreamElement`); the over-relay system-test extended to
    assemble the stack over the live channel and prove a proxy `pushCallback(serialize(StreamElementValueDto))`
    reaches the client's parsed handler; **iOS sim smoke `EpcConsumeSmoke` ALL PASSED** (consume stack
    parses + routes a pushed element in the app binary).
  - **3c-client — Drive-on-iOS client scaffold. ✅ shipped (2026-06-24).** The full client assembly
    over the EPC channel, as far as it goes without a live Control. `EpcDriveClient.assembleClient(channel)`
    (umbrella iosMain) hand-wires what Android/desktop get from `:drive-shared-ui`'s Koin graph (the iOS
    umbrella has none): `coreCommonModule` + **every service `-common`/`-client` schema config** + the
    channel as `TaskTransport` + a `RemoteBinaryTaskExecutor` (its `streamElementHandler` auto-wired by
    `coreCommonModule`'s `DefaultInteractorProvider`) + `createRemoteTenshuEnvironment`/`DefaultTenshuClient`
    (`init()` binds the executor) + a real `TenshuFilesRepository` (with a minimal
    `IosFilesRepositoryPlatformDelegate` — read path needs no delegate; iOS ingest via the Document/Photo
    picker is a follow-up). Verified by **iOS sim smoke `EpcDriveClientSmoke` (ALL PASSED)**: (1) the
    whole graph — every service schema + environment + client + repo — **assembles in the app binary**
    (a missing/broken service config would throw); (2) `repository.createFolder` **marshals a real op to
    `TaskTransport.call`** (46 bytes), i.e. the op path is wired end-to-end through the full schema to the
    channel. **Outstanding (on-device):** the live **op → reply** and **op → push → Flow** round-trips
    against a Control serving EPC over a reachable relay (Team-ID/signing + live relay), and the final
    swap of `DriveReadSurface`'s `CannedFilesRepository` → this real repo (gated on an established channel,
    which needs a paired Control). The scaffold is ready for that swap; the data path is the device gate.
  - **3c-ui-seam — injectable repository. ✅ shipped (2026-06-24).** The Drive-on-iOS UI is now driven
    by an **injected `FilesRepository`** instead of a hardcoded canned one — the seam that makes the
    SwiftUI layer testable and that the real client plugs into. `DriveReadSurface` gained a canonical
    `create(repository, …)` (tests/previews inject a configured fake to drive any UI state),
    `installRepository(repo)`, and `installRealClient(channel)` (production: assemble + install the real
    `TenshuFilesRepository` over the channel — one call once a Control serves EPC). The old private
    `CannedFilesRepository` became a public, complete, **configurable + recording `FakeFilesRepository`**
    (reactive mutable listing; create/delete/rename/move/upload recorded; share/details/settings return
    benign defaults that full-parity slices enrich as they bind each screen). Verified by the iOS sim
    smoke `DriveReadSurfaceSmoke` (ALL PASSED): the default surface shows the fake's listing, and an
    **injected, differently-configured fake drives a different listing** through the real
    `FilesScreenViewModel` + presenter. This is the foundation for binding the rest of the shared VMs
    (file detail/actions, create-folder/rename/move/search, settings, sharing) toward full UI parity.
  - **3c-ui-detail — file detail + actions. ✅ shipped (2026-06-24).** First full-parity screen (step B).
    `DriveDetailSurface.create(path, isFolder)` hand-wires `FileDetailsViewModel` (header + metadata +
    activity) + `FileActionsViewModel` (offline pin, delete) over the same installed `FilesRepository`
    as the list; SwiftUI `FileDetailView` binds both `uiState`s (SKIE), pushed from the Files list when a
    file row is tapped (`FileRoute` nav). `FakeFilesRepository` enriched: `fileDetails` synthesises a
    basic detail for any listed path + configurable `detailsFor`/`activityFor`; download/evict recorded.
    Verified by the iOS sim smoke `DriveDetailSurfaceSmoke` (ALL PASSED) — the surface assembles and both
    VMs **seed from the injected repository** (synchronous `uiState.value`); the reactive metadata/history
    streams and the action handlers run on `viewModelScope` (the iOS main loop), so they come alive in
    the running app (the init-time smoke can't pump Main — the standing constraint for `viewModelScope`
    VMs). Next B screens: create-folder/rename/move/search, then settings, then the sharing cluster.
  - **3c-ui-tests — reactive iOS test harness. ✅ shipped (2026-06-24).** Lifts the `viewModelScope`
    constraint above: the umbrella gained an **`iosTest` source set** (`kotlin-test` +
    `kotlinx-coroutines-test`) running on the already-enabled `iosSimulatorArm64Test` gate (so it joins
    the `ios.yml` CI lane). Unlike the in-app `*Smoke` objects (production code, run inside a real app
    binary, can only check synchronous seed state because they block Main), these tests install
    `Dispatchers.setMain(StandardTestDispatcher())` **before** building a VM, so each `viewModelScope`
    captures the test scheduler and `advanceUntilIdle()` pumps the reactive streams + action handlers.
    `DriveDetailSurfaceTest` proves the detail VMs end-to-end over `FakeFilesRepository`: `uiState`
    reflects the repo's metadata + activity, `onDeleteClicked` reaches the repo, the offline toggle
    flips state + records the download. `FakeFilesRepositoryTest` covers the test double itself
    (reactive listing, recorded create/delete/rename/move). 7 tests green. **This is the pattern every
    subsequent B screen's VMs use for real coverage** (smokes remain the app-binary linkage proof).
  - **3c-ui-mutations — create folder / rename / move. ✅ shipped (2026-06-24).** Three file-mutation
    screens (step B). `DriveMutationSurface` hand-wires `CreateFolderDialogViewModel` /
    `RenameFileDialogViewModel` / `MovePickerViewModel` over the installed repo (move also collects its
    folders-only `pagedFolders` through a `TenshuPagingPresenter`, like the list). SwiftUI: `CreateFolderSheet`
    (Files toolbar → "+" → New Folder), `RenameSheet` + `MovePickerView` (file detail → Rename / Move; the
    picker is a push-nav folder browser with "Move Here" gated on `canMoveHere`). Verified by the reactive
    `iosTest` harness — `DriveMutationTest` (5): create-folder path-building + record, rename → repo,
    move → repo + `moved` flag, move-into-source-parent disabled; app builds. Next B: search, then settings,
    then the sharing cluster.
  - **3c-ui-settings — Sync Preferences (search skipped). ✅ shipped (2026-06-24).** **Search was skipped
    on purpose**: it is a stub across the *entire* Drive app — `FilesSearchBarViewModel` has empty
    `onSearchClicked`/`onItemClicked`, `FilesRepository` has no search op, and Android/desktop render a
    search bar wired to `onSearchClicked = {}`. Nothing to bind; a real search is a separate cross-platform
    (and proxy-backed) feature.
    ⚠ **SUPERSEDED 2026-07-20** — accurate on 2026-06-24, obsolete since Drive search (the `contains` predicate + result-tap navigation) built exactly that
    "separate cross-platform, proxy-backed feature": the handlers drive a debounced pipeline, the op
    exists (`Contains`), and Android + desktop ship it. The skip decision was right at the time; iOS
    search is now a real parity gap, tracked in `ios-drive-parity.md` §24. Settings instead: `DriveSettingsSurface.syncPreferences()` binds
    `SyncPreferencesViewModel` over the iOS Noop preference seams; SwiftUI `SyncPreferencesView` (a Form:
    keep-offline-by-default + Wi-Fi-only toggles, data-limit stepper, swipe-to-remove offline files),
    reached from `SettingsView` → "Sync Preferences". Toggles are optimistic (persist once a Control serves
    `:preference-service`). Verified by `SyncPreferencesTest` (3, over recording preference fakes): uiState
    reflects the offline list + default policy, the keep-offline toggle writes `KEEP_OFFLINE`, removing an
    offline file updates state + persists. **Synced Folders settings deferred** — `SyncedFoldersViewModel`
    needs the auto-backup infra (`SyncedFolderController`/`MediaSourceProvider`/`BackupFolderGrants`), the
    iOS-hostile Phase-4-gated feature (reframe-not-port, §9.4) with no iOS impl. Next B: the sharing cluster.
  - **3c-ui-share-link — create share link. ✅ shipped (2026-06-24).** First of the sharing cluster.
    `DriveShareSurface.createShareLink(filePath)` binds `CreateShareLinkViewModel`; SwiftUI
    `CreateShareLinkSheet` (file detail → **Share Link**): Generate Link → shows the canonical URL +
    Copy (writes `UIPasteboard`). `FakeFilesRepository.shareLink` enriched — was `notConfigured` (error);
    now records the path + returns a configurable `shareLinkResult` (default `Success(ShareLinkPayload(
    "demo-share-id","demo-fragment"))`). Verified by `CreateShareLinkTest` (2): generate → repo called +
    `link`/copyable URL (`https://…/s/{id}#k={fragment}`), failure → `errorMessage` + no link. app builds.
    Note the copyable string goes through `onCopyLinkRequested()` (the VM method) rather than
    `ShareLinkUrl.toCopyableString()` directly — SKIE doesn't surface Kotlin default args, so the
    default-`host` overload isn't callable from Swift. Next sharing screens: Manage access + Shared-with-me.
  - **3c-ui-manage-access — Manage Access. ✅ shipped (2026-06-24).** The folder access screen
    (`ManageAccessViewModel` — its heaviest, 3 repos). `DriveShareSurface.manageAccess(folderPath)` over the
    installed `FilesRepository` + new `FakeContactsRepository` / `FakeMembershipRepository` (the real impls
    wrap `:contact-service` + the GR shared-realm ops over a `TenshuClient` — the on-device data path).
    SwiftUI `ManageAccessView` (folder toolbar → people icon when inside a folder): "You — Owner" + cross-
    user members (swipe-Remove, GR-6) + invite rows (swipe-Revoke PENDING) + a `GrantPickerSheet`
    (grant a connected contact, GR-4). `FakeFilesRepository.sharedFolders` made configurable (drives
    `backsSharedRealm`). The **QR/SAS *invite someone* + completion-prompt sub-flows are deferred** — the
    contacts fake throws on those handshake methods (`createInvite`/`redeemInvite`/`awaitInviteAck`/
    `completeHandshake`), which the bound screen never calls. Verified by `ManageAccessTest` (2): folder
    invites show + revoke; a shared-realm folder loads members + grants a contact + removes a member.
    app builds. Next sharing: Shared-with-me, then the invite/completion sub-flows.
  - **3c-ui-shared-with-me — Shared destination. ✅ shipped (2026-06-24).** The two-tab Shared
    screen (`SharedScreenViewModel`). `DriveShareSurface.sharedWithMe()` over the installed
    `FilesRepository` + the contacts/membership fakes. SwiftUI `SharedView` — *My Shares* (outbound
    Mode-A link shares with swipe-Revoke + Mode-B shared folders) + *Shared with Me* (inbound realms +
    quarantined GR-6 pending grants to accept/decline) + the GR-4 `AcceptFromContactSheet`. `PairedAppView`
    becomes a two-tab shell (Files + Shared) now that the surface has landed. `FakeFilesRepository` /
    `FakeMembershipRepository` enriched with reactive outbound shares, inbound realms, and pending grants.
    Verified by `SharedWithMeTest` (4): my-shares show + revoke-drops-row, inbound realm listing,
    pending-grant accept, pending-grant decline. The REDEEMED-invite banner + resumed-redemption rows
    (contact-handshake completion) are deferred with the rest of the handshake sub-flow.
  - **3c-ui-invite-link — *Invite someone* link/QR. ✅ shipped (2026-06-24).** First of the
    contact-handshake sub-flow. `DriveShareSurface.inviteLink(folderName)` binds `InviteLinkViewModel`
    (the `CreateShareLinkViewModel` pattern over `createInvite`); SwiftUI `InviteSomeoneSheet`
    (Manage Access → **Invite someone new**): Generate → one-session invite link rendered as a
    scannable **QR** (new `CIQRCodeGenerator` helper — iOS had QR *scanning* only) + Copy. The
    advisory intent is `SHARE_FOLDER`/folder-name inside Manage Access, plain otherwise.
    `FakeContactsRepository.createInvite` enriched — was `deferred` (threw); now records
    `(intentKind, intentLabel)` + returns a configurable link. Verified by `InviteLinkTest` (4):
    share-folder invite + link, empty-folder plain invite, generate idempotent once a link exists,
    failure → error + no link. app builds. **Still deferred** (the contacts fake still throws on these):
    the SAS-confirm + `awaitInviteAck`, the REDEEMED banner → `ContactPromptViewModel` completion
    prompt (`completeHandshake`), and the invitee-side `RedeemInviteViewModel` redemption. Next: the
    completion prompt, then redeem.
  - **3c-ui-completion-prompt — inviter-side handshake completion. ✅ shipped (2026-06-24).** The §2.3
    completion prompt (`ContactPromptViewModel` — 3 repos). `DriveShareSurface.contactPrompt(inviteId)`
    over the installed `FilesRepository` + the contacts/membership fakes. SwiftUI `ContactPromptSheet`:
    claim header (quoted self-asserted name + fingerprint) → SAS "this matches their screen" toggle →
    petname (or link-to-an-existing-contact disclosure) → your-name → Connect/Decline; a `SHARE_FOLDER`
    accept flips in-place to the §2 grant offer ("Add {petname} to '{label}'?" → Add to folder / Not now).
    Entry point: a **REDEEMED-invite banner** in `SharedView` (over `hasRedeemedInvites` →
    `onContactPromptBannerClicked` → `promptInviteId` sheet). `FakeContactsRepository` enriched —
    `completeHandshake` was `deferred` (threw); now records the call (`completedHandshakes` +
    `ownNames`) + returns a configurable result, and `persons()`/`ownDisplayName()` are configurable.
    Verified by `ContactPromptTest` (4): REDEEMED row seeds claim + SAS, accept → handshake with
    confirmed SAS + petname/own-name + done, decline → handshake rejected, SHARE_FOLDER accept → grant
    offer → `onGrantConfirmed` → membership grant. app builds. **Still deferred** (fake throws): the
    invitee-side `RedeemInviteViewModel` redemption (`redeemInvite`/`awaitInviteAck`) + the
    resumed-redemption rows. Next: invitee-side redeem — the last handshake piece.
  - **3c-ui-redeem — invitee-side redeem an invite. ✅ shipped (2026-06-24).** Completes the sharing
    cluster (the §3 invitee side). `DriveShareSurface.redeemInvite(initialLink)` binds
    `RedeemInviteViewModel`; SwiftUI `RedeemInviteSheet` (Shared toolbar → **Redeem invite**): a stage
    machine over the VM's `RedeemStage` sealed type — EnterLink (paste + typed parse errors) →
    ConfirmName (inviter fingerprint + your-name) → Waiting (spinner + SAS "matches" toggle) →
    Accepted / Rejected / Gone / Invalid terminal cards. Stages bridged to Swift via SKIE
    `switch onEnum(of: stage)`. `FakeContactsRepository` enriched — `redeemInvite`/`awaitInviteAck`
    were `deferred` (threw); now record (`redeemedLinks`/`ackWaits`) + return configurable
    outcome/ack, and `pendingRedemptions()` is configurable. Verified by `RedeemInviteTest` (5): valid
    link → ConfirmName + fingerprint, malformed → MALFORMED, v2 → UNSUPPORTED_VERSION, confirm →
    redeem + own-name + ACCEPTED, already-used → Gone. **First slice verified with a full
    `xcodebuild`** (`** BUILD SUCCEEDED **`) — which caught a latent `Section("title"){} footer:{}`
    Swift error in the merged `ContactPromptSheet` (Kotlin `iosTest` doesn't compile SwiftUI);
    fixed here. From now iOS UI slices build the app, not just the VM test. QR-scan entry (feeding
    `initialLink`) is a follow-up. **Sharing cluster + contact-handshake COMPLETE** (share-link,
    manage-access, shared-with-me, invite-link, completion-prompt, redeem).
  - **3c-ui-redeem-qr — scan-to-redeem follow-up. ✅ shipped (2026-06-24).** Wires camera QR scanning
    into the redeem flow. `QrScannerView` (the AVFoundation scanner) is **promoted out of `#if
    DEV_HARNESS`** to the shipping target — it's now a generic scanner shared by the shipping redeem
    flow + the harness EPC pair surface (`NSCameraUsageDescription` already in Info.plist; the
    simulator's no-camera fallback message stands). `RedeemInviteSheet`'s EnterLink stage gains a
    **Scan invite QR code** button → presents `QrScannerView`; a scanned `tenshu-invite:` URI feeds
    `RedeemInviteModel.enterScanned` → `onLinkEntered` (same parse path as paste). `RedeemInviteTest`
    +1 (6 total): a `toQrUri()` scan also parses to ConfirmName. **Both schemes xcodebuild green**
    (shipping `DriveIos` + harness `DriveIos-Dev` — the promotion touches both). The remaining redeem
    follow-up is on-device camera (simulator has none).
  - **3c-ui-uitest — sharing-cluster XCUITest harness. ✅ shipped (2026-06-24).** Closes the
    runtime-render gap the Kotlin `iosTest` (VM-only) and `xcodebuild` (compile-only) leave open: a
    new `DriveIosUITests` bundle (XcodeGen `bundle.ui-testing` target + `DriveIos-UITests` scheme,
    built in `DebugHarness`) drives the real SwiftUI over the fakes — no taps scripted by hand. The
    app is provisioned into `PairedAppView` via the existing `DRIVE_DEV_PROVISION_CONTROL=1`
    launch-env hook. `SharingClusterUITests` (3): the Shared destination renders (both segments +
    Redeem entry); the Redeem sheet's EnterLink → malformed link → typed parse error (view↔VM wiring
    end to end); Files → folder → Manage Access → Invite someone → Generate renders the QR + Copy
    (the `CIQRCodeGenerator` headline). `.accessibilityIdentifier`s added to the driven controls
    (`shared-redeem`/`redeem-scan`/`redeem-link-field`/`redeem-continue`/`invite-generate`/
    `manage-invite-someone`; `manage-access` already existed). Screenshots are `.deleteOnSuccess`
    `XCTAttachment`s in the `.xcresult` (a gitignored build artifact) — **never committed**; they
    survive only a failing run. All 3 green on the iPhone-16
    sim. ⚠ SwiftUI List rows surface as **static text, not buttons** — query `app.staticTexts[name]`
    to tap a folder row. Caveat: still fake-backed (no real Control/relay/camera) and CI is
    billing-blocked, so this is a local check + latent regression net, not a CI gate yet.
- **Attestation, measure 2 (Phase 4):** App Attest verification is **proxy** code, not relay code
  — most naturally in the proxy layer that already issues a per-client `ExecutionContext`
  (`:core-proxy` / `:control-shared`). iOS-specific bits go behind `expect/actual`. The optional
  edge admission gate (O2) is the only relay-side attestation touch, and only if O2 resolves yes
  — also a threat-model amendment.

  **Phase 4a — as-built (shipped). ✅ seam + protocol + software reference verifier.** The real
  Apple App Attest cert-chain verifier is deferred to Phase 6 (it needs a live `DCAppAttestService`
  + Apple sample vectors to test against — neither exists pre-app), so 4a built the verification
  *seam* + protocol + a cross-platform reference, the way Phase 2 used `BootstrapSyncCryptoManager`.
  In `:core-common` `org.tenshu.core.common.epc`:
  - `AppAttestor` (app) / `AppAttestVerifier` (proxy) — `fun interface`s over **opaque** attestation
    bytes + `AppAttestResult.Attested(keyId)`/`Rejected(reason)`. The Apple impl drops in as one
    pair without touching the protocol.
  - `SoftwareAppAttestor`/`SoftwareAppAttestVerifier` — the reference: an Ed25519 signature over the
    challenge (`[len][pubkey][sig]`, length-prefixed so stub + real keys both work) + an optional
    `isTrusted(pubkey)` gate; returns a key id for cross-reconnection pinning. **Not** a hardware
    root of trust — stands in for genuineness; real attestation is the Apple verifier (Phase 6).
  - `EpcAttestationClient`/`EpcAttestationServer` — measure 2 runs as a fixed **positional prelude**
    of two exchanges over the established channel (init → challenge; attestation → verdict), fitting
    the lock-step transports; operations are gated until `isAttested`. **The channel itself binds
    the challenge** — it rides the sealed E2E session, so an attestation can't be read or replayed
    across channels (no extra binding needed).
  - Tested cross-platform (`commonTest`, iOS gate: flow + prelude-gates-operations over the loopback)
    + real-crypto (`desktopTest`: tampered attestation + untrusted key rejected).
  - **Not wired into the live acceptor/gateway yet** — the gated-handler composition is shown in the
    4a tests; the `ExecutionContext`-gating wiring lands with the iOS app (Phase 6). Measure 2's
    verification is **independent of Rei** (Rei is measure 3); O5 only sequences measure 3.
- **Rei permission verification, measure 3 (Phase 5):** proxy-side REL / `Info.plist` evaluation
  via the `BundledReiSource` / `OnlineReiSource` shapes in `tenshu-rei.md` (first-party verifies
  against bundled artifacts; third-party EPC apps need the online source). Sequence per O5.
- **Control-on-iOS co-resident proxy (Phase 6):** requires a new iOS app module (not yet in
  `settings.gradle.kts`) + the background-wake story (`BGTaskScheduler` / push, O4). Operations
  stay non-suspend at the interface level — use the `IoOperation` + `TaskInteractor` pattern
  (House Rule #3, ADR 0012). **Phase 6a (shared crypto foundation) shipped — see the Phase 6a
  as-built section below.**

### Phase 6a — as-built (shipped). ✅ iOS crypto actuals over libsodium.

The first slice of Phase 6 (per the §9 *Drive-client-first* sequencing) is the **shared crypto
foundation** every iOS app — Drive client now, Control-on-iOS later — needs before the EPC channel
can run on a device. It is the Kotlin/Native foundation only; the Xcode app target, signing, App
Attest (6c/6d) and on-device runs are deliberately *not* in it (they cannot be exercised in a
headless session). Decisions recorded per the "decisions land in docs" house rule:

- **Crypto binding = libsodium via hand-rolled cinterop, not CryptoKit or a precompiled klib.**
  CryptoKit is Swift-only and unreachable from Kotlin/Native's Objective-C interop. A precompiled
  third-party KMP libsodium binding (e.g. Ion Spin) would have to match our exact compiler ABI,
  which is fragile on a bleeding-edge Kotlin (2.3.21). A cinterop klib *we* generate depends only
  on the C library's headers + symbols (compiler-version-independent), so it is the robust choice.
  The throwing `CryptoBackendsModule.ios.kt` stubs are replaced by `Libsodium{Ed25519,X25519,Aead,
  Kdf}Backend` (`core-common/src/iosMain`).
- **All four backends are byte-for-byte interoperable with the JVM (BouncyCastle) and JS (@noble)
  backends** — load-bearing because an iOS client and a desktop/Android proxy share the EPC and
  synced wire formats. Ed25519 (`crypto_sign_ed25519_*`, RFC 8032 detached), X25519
  (`crypto_scalarmult[_base]`, RFC 7748, clamps on use exactly as BouncyCastle), XChaCha20-Poly1305
  (`crypto_aead_xchacha20poly1305_ietf_*`, IETF construction = the same HChaCha20 + inner nonce the
  JVM builds by hand; output keeps the `nonce(24) ‖ ct ‖ tag(16)` self-contained layout). Proven by
  `LibsodiumCryptoBackendsTest` (iosTest gate, 14 tests) against fixed vectors generated from the
  BouncyCastle backends.
- **Argon2id binds the reference `argon2id_hash_raw`, not libsodium's `crypto_pwhash`.** The
  high-level `crypto_pwhash` is salt-locked to 16 bytes, but the production recovery KDF uses a
  22-byte domain-tagged salt (`"tenshu/recovery-kdf/v1"`, `DefaultSyncCryptoManager`). libsodium
  vendors the phc-argon2 `argon2id_hash_raw` (arbitrary salt + parallelism, RFC 9106 v0x13,
  byte-identical to BouncyCastle/@noble); it is compiled into the static lib but not in the public
  `sodium.h`, so its prototype is declared in the cinterop `.def` C section and resolves from the
  archive at link time. The test asserts the 22-byte-salt output matches BouncyCastle's.

  **Why a 22-byte salt / would 16 bytes (the public `crypto_pwhash`) be better?** *Not* for security
  — switching to 16 bytes would be **neither more nor less secure** here. The salt in this design is a
  *fixed constant* used only for **domain separation**, not a per-user random value: the input to
  Argon2id is the 256-bit BIP-39 entropy, which is already globally unique, so the salt's usual
  anti-rainbow-table / anti-precomputation role is moot, and security rests entirely on that input
  entropy plus Argon2id's memory-hardness. 16 bytes (the RFC 9106 recommended size) separates domains
  exactly as well as 22. So the only *advantage* of moving to 16 bytes is being able to use libsodium's
  **public, ABI-stable `crypto_pwhash`** instead of the internal `argon2id_hash_raw` symbol (removing
  the version-coupling risk). The *costs*: (i) it is a **protocol-breaking change** — the salt feeds
  every key derived from the recovery phrase, so changing it invalidates existing derived keys /
  recovery blobs and needs a recovery-format version bump + migration; (ii) it must change on **all
  three backends at once** (JVM/JS/iOS) to preserve interop; (iii) `crypto_pwhash` is hardcoded to
  parallelism=1, re-introducing the p>1 divergence from the `KdfBackend` contract that
  `argon2id_hash_raw` avoids. **Decision:** keep the 22-byte salt + `argon2id_hash_raw`; the
  internal-symbol risk is cheap to hold (source pinned to libsodium 1.0.20 + the vector test guards
  it). The natural moment to switch is **if/when the Argon2id cost parameters are bumped** (RFC 9106
  recommends an annual review — itself a key-invalidating protocol-version change): the salt can drop
  to 16 bytes and move to `crypto_pwhash` then, at no extra migration cost.
- **libsodium is built from pinned source, not vendored as a binary or pulled from Maven.**
  `scripts/build-libsodium-apple.sh` cross-compiles a static `libsodium.a` + headers per
  Kotlin/Native Apple target (`iosSimulatorArm64` / `iosX64` / `iosArm64`) from the checksummed
  `libsodium-1.0.20.tar.gz` (sha256 `ebb65ef6…`), into the module build dir (gitignored — no binary
  blobs in git). `core-common/build.gradle.kts` registers a `buildLibsodium<Target>` task that the
  matching `cinteropLibsodium<Target>` depends on, with per-target `includeDirs` + `-libraryPath`.
  Reproducible on any macOS host with Xcode + `make` (no autotools — the release tarball ships
  `configure`). First cinterop establishes the repo's first cinterop precedent.
- **`ktor-client-darwin` added to the version catalog only.** The rendezvous relay client
  (`RelayRendezvousTaskTransport`, `:sync-service-proxy`) is the engine's consumer, but that module
  is `-proxy` and excluded from the iOS umbrella, so no iOS-exported module wires an `HttpClient`
  engine yet. Wiring it is the **6b** packaging decision (export a client-side rendezvous module vs
  split `:sync-service-proxy`); the catalog entry pre-positions it.
- **Verified:** `:core-common:iosSimulatorArm64Test` green (incl. the 14 new tests);
  `:tenshu-sdk-ios:assembleTenshuSdkXCFramework` links libsodium into the static umbrella framework
  for all three iOS slices (the framework binary carries `T _argon2id_hash_raw` et al., so a
  consuming app resolves the symbols). **No threat-model change** — this swaps a software stand-in
  for a real implementation behind the unchanged backend interfaces; the EPC/relay trust model is
  untouched (House Rule #2 not triggered). CI cannot be observed green while Actions billing is
  blocked; verified locally.

### Phase 6a — as-built (app-module half, shipped 2026-06-19). ✅ First iOS app + crypto smoke.

The second half of 6a stands up the **first iOS app** and proves the libsodium crypto foundation
runs inside a *real app binary*, not just on the `iosSimulatorArm64Test` gate. Decisions:

- **The app is the Drive client (per §9), but at 6a it is a crypto _smoke harness_, not the client
  proper.** It links the umbrella `TenshuSdk.xcframework` and runs Ed25519 / X25519 /
  XChaCha20-Poly1305 / Argon2id round-trips against the same BouncyCastle-produced vectors as
  `LibsodiumCryptoBackendsTest`, through the production `cryptoBackendsModule()` Koin wiring. This
  closes the one gap the headless crypto phase (6a) could not: that a consuming Swift app actually
  links the umbrella framework and resolves its libsodium symbols (incl. the internal
  `argon2id_hash_raw`). EPC pairing / relay networking is **not** here — that is 6b.
- **The iOS app is an Xcode project, not a Gradle module.** It is a pure consumer of
  `:tenshu-sdk-ios`; the KMP shared code is the only Gradle side. (The handover's "iOS app target in
  `settings.gradle.kts`" expectation does not apply to a pure-consumer Xcode app — `settings.gradle.kts`
  is unchanged.) It lives in `drive-ios/` (SwiftUI, `@main DriveIosApp` + `CryptoSmoke` bridge/UI).
- **Project generation = XcodeGen.** `drive-ios/project.yml` is the committed single source of truth;
  `DriveIos.xcodeproj` + `Info.plist` are generated and **gitignored**, mirroring the repo's
  "build from source, don't commit generated artifacts" convention (libsodium, the JS SDK bundle).
  Regenerate with `(cd drive-ios && xcodegen generate)`. A pre-build script phase runs
  `:tenshu-sdk-ios:assembleTenshuSdkDebugXCFramework` so the linked static framework stays fresh.
- **The smoke harness `CryptoSmoke` lives in the umbrella's `iosMain`** (`org.tenshu.sdk.ios`), not
  in `:core-common` — it is iOS-app foundation code, kept out of the shared crypto module. It
  resolves the `internal` libsodium backends only through the public `cryptoBackendsModule()` factory
  (Koin is `api`-exposed by `:core-common`), so it adds no new public crypto API.
- **Verified on the iOS Simulator (headless).** `xcodebuild … -sdk iphonesimulator` builds the app
  (BUILD SUCCEEDED; one benign `libicu … built for newer iOS 17.2 than 16.0` link warning from the
  K/N runtime); `simctl launch --console` prints `RESULT: ALL PASSED` (all four checks green) and the
  same verdict renders on screen. **Not exercisable headlessly / deferred:** a real-device build, a
  Developer Team ID, code signing, and App Attest (App Attest is unavailable on the simulator anyway)
  — those ride **6c/6d**. **No threat-model change** (no new asset/surface; the crypto seam is
  unchanged). CI billing-blocked; verified locally.

### Phase 6b — as-built (shipped 2026-06-19). ✅ EPC rendezvous client packaging.

6b answers "how does the iOS app reach the relay?" The rendezvous client lived in
`:sync-service-proxy`, which the umbrella deliberately **excludes** (`api`-depends on `:core-proxy` /
`:sync-service` — the master-key + operation-impl code §§2,4 keep off an untrusted client). Exporting
that whole module would have dragged the proxy into the iOS framework, so the client half was
**extracted**, not exported. Decisions:

- **New module `:epc-rendezvous-client`** (EPC-family name, per the maintainer call). KMP, targets
  android + jvm + js + the three iOS slices, no per-module framework (Phase-1 umbrella rule). It holds
  the client/shared rendezvous code moved out of `org.tenshu.sync.proxy.epc` → `org.tenshu.epc.rendezvous`:
  `RendezvousRelayClient` (+ `RendezvousDirection`/`RendezvousFetch`/`RendezvousFrame`/`RendezvousRelayException`
  + wire DTOs) and `RelayRendezvousTaskTransport`. Its only non-stdlib deps are `:core-common` (for
  `TaskTransport`) + ktor-client-core + serialization — **zero proxy types**, which is what makes the
  HR#1 boundary clean. The proxy-side `EpcRendezvousAcceptor` **stays** in `:sync-service-proxy`, which
  now `api`-depends on the new module (its constructor exposes `RendezvousRelayClient`).
- **HTTP engine is injected by the consumer.** `HttpClient` is a constructor arg, so the module pins no
  engine; commonMain exposes `rendezvousHttpClient(engine)` (installs the JSON content-negotiation the
  frame envelopes need) and `iosMain` adds `darwinRendezvousHttpClient()` (the `ktor-client-darwin`
  engine the 6a crypto half pre-positioned in the catalog) so the Swift app gets a ready transport
  without constructing Ktor from Swift.
- **Umbrella exports `:epc-rendezvous-client`.** It is the first relay-facing HTTP client on the iOS
  side (a pure client never syncs to the relay directly — the proxy does; this is the EPC channel).
- **Incidental fix:** `EpcRendezvousAcceptor` used a bare `@Volatile`, which only auto-resolves on JVM;
  the proxy's `js` main compilation had rotted since EPC Phase 3b (CI billing-blocked). Switched to the
  multiplatform `kotlin.concurrent.Volatile` import — `:sync-service-proxy` now compiles for js again.
- **Verified.** The JVM `EpcRendezvousOverRelayTest` (app↔real-relay↔proxy handshake + 2 encrypted op
  round-trips) stays green after the move — the behavioural proof the extracted client still works.
  `:tenshu-sdk-ios:assembleTenshuSdkXCFramework` (the CI-gate task) links it. The `drive-ios` smoke
  gains an **EPC rendezvous** section that builds the Darwin client and makes a real request to a closed
  port → `RendezvousRelayException`, proving the Darwin engine **links and runs** in a real app binary
  (`RESULT: ALL PASSED` on the iPhone-16 simulator). A live SAS round-trip on device is 6c+.
- **No threat-model change.** Pure packaging — the `/sync/epc` relay surface, the EPC protocol, and the
  trust model are untouched; the move actively *strengthens* HR#1 (proxy code no longer reachable from
  the client framework). The `onCallback`-over-rendezvous gap (3b) is still open, unchanged here.

### Phase 6b.5 — as-built (Slice 1, shipped 2026-06-19). ✅ `:core-paging` iOS-ization.

First slice of the Drive-client build-out (§9.5): make the paging layer iOS-capable so a SwiftUI
list can drive `Flow<PagingData<…>>` without the Compose-only `collectAsLazyPagingItems()`. Code
only; no umbrella/SKIE/`:drive-shared` change yet (those are later slices). Decisions:

- **`:core-paging` gained iOS targets and `PagingUtil.kt` moved `jvmMain` → `commonMain`.** The
  `TList → androidx.paging` bridge (`TenshuPagingSource` + `pagingSource`) is pure `paging-common`
  (the multiplatform core) + `:core` + coroutines — all iOS-capable — so a single `commonMain` copy
  now serves Android, desktop **and** iOS. The old manually-materialised `jvmMain` intermediate is
  gone (commonMain is the shared parent for all targets).
- **New `TenshuPagingPresenter<T>` (commonMain).** A non-Compose driver wrapping
  `androidx.paging.PagingDataPresenter` — the same base `LazyPagingItems` is built on — exposing
  `snapshot: StateFlow<List<T>>` + `loadState: StateFlow<CombinedLoadStates?>` + `get(index)` +
  `size`, and `collect(flow)` to start collection in a caller-supplied scope. Load states / retry /
  placeholders / the load-next trigger survive (a hand-rolled `AsyncStream` would drop them — §9.5).
  SwiftUI (via SKIE, slice 3) observes `snapshot`/`loadState` and calls `get(index)` from the last
  cell's `onAppear`. The presenter's diff dispatcher is a ctor arg defaulting to `Dispatchers.Main`
  (real on iOS via the run loop); tests inject a dispatcher.
- **⚠ No `iosX64()` target — paging-common forced it.** `androidx.paging:paging-common` 3.4.2
  publishes only `iosArm64` (device) + `iosSimulatorArm64` (Apple-silicon sim); there is **no
  `iosX64`** (Intel-mac sim) klib, so a shared `nativeMain` that `api`-exposes paging-common can't
  resolve for iosX64. `:core-paging` therefore declares only the two paging-common supports. The CI
  gate runs `iosSimulatorArm64Test`, and Apple-silicon is the only simulator arch that matters, so
  the gate is unaffected. **Open decision for slice 3 (umbrella export):** the umbrella
  (`:tenshu-sdk-ios`) still declares `iosX64`, so when it exports the paging-consuming logic
  (`:core-paging` / `:drive-shared-logic`), iosX64 can no longer be satisfied — either **drop
  iosX64 umbrella-wide** (it's the legacy Intel-sim arch; androidx already dropped it for paging) or
  keep the paging surface off the exported framework. Recommend dropping iosX64 umbrella-wide; flag
  to the maintainer at slice 3.
- **SKIE compatibility checked up front (§9.5 gate).** Repo Kotlin is pinned **2.3.21**; SKIE
  **0.10.12** (2026-05-18, current latest) adds *"Support for Kotlin 2.3.21"*, so SKIE is adoptable
  at the umbrella in slice 3 — at the bleeding edge (newest SKIE for newest Kotlin, zero headroom),
  so a `tech-debt.md` entry gating Kotlin bumps on SKIE support lands **with** the SKIE adoption
  (slice 3), per §9.5. Not adopted in this slice.
- **Tests.** New `TenshuPagingPresenterTest` (3 tests: first-page snapshot + `get`, out-of-range
  `get` is null, end-access pages forward) lives in **`commonTest`** so it also runs on the
  `iosSimulatorArm64` gate (proving the non-Compose path links + works on K/N). It uses only
  paging-common + coroutines-test (no proxy/test-utils), so it stays iOS-capable. The Pager fetcher
  loads on a real dispatcher the `runTest` virtual clock can't drive, so the tests bridge to
  `Dispatchers.Default` and await the `snapshot` flow under a real timeout. The pre-existing
  `TenshuPagingSourceTest` stays in `desktopTest` (it builds a `tContext` from `:core-common` /
  `:core-test-utils`, not iOS-capable). **Verified locally** (CI billing-blocked, HR#7):
  `:core-paging:iosSimulatorArm64Test` green, `:core-paging:desktopTest` green,
  `:core-paging:compileKotlinIosArm64` (device) green, `:drive-shared:compileKotlinDesktop` (the
  jvm consumer) still green. On-device/signing out of scope (none needed for this slice).
- **No threat-model change.** Presentation-layer plumbing only.

### Phase 6b.5 — as-built (Slice 2, shipped 2026-06-19). ✅ Drive logic layer is iOS-capable.

Slice 2 made Drive's view-model/repository layer compile on iOS, in two PRs. **2a (de-Compose,
in-place)** removed three Compose/JVM types that had leaked into the public model API so the logic
could be platform-neutral: `previewIcon: ImageVector` → a neutral `FileIcon` enum (UI maps via
`FileIcon.asImageVector()` / SF Symbols), `previewImage: ImageBitmap` → `previewBytes: ByteArray?`
(VMs return bytes; the screen `previewLoader` decodes off-thread via `ByteArray.decodePreviewBitmap()`;
`decodeToImageBitmap` moved `data/` → `ui/common/`), and `java.text.DecimalFormat` → a small KMP
formatter. After 2a the 4 list/detail VMs + `ui/model/` + `data/` carried zero Compose imports.

**2b (module split)** is the structural extraction, decided with the maintainer (proposal §9.3 + a
working session):

- **`:drive-shared` split into `:drive-shared-ui` + `:drive-shared-logic`** (symmetric naming chosen
  over a one-sided `:drive-shared` + `:drive-shared-logic`, for clarity). `:drive-shared-logic`
  (android + desktop + **iosArm64 + iosSimulatorArm64**) holds the 66 Compose-free commonMain logic
  files (VMs, repos, mappers, models, domain, selection, preferences) + the `ShareHost` platform
  `expect` (new iOS actual reads `NSProcessInfo` env). `:drive-shared-ui` keeps the 35 `@Composable`
  screens + the 6 UI-helper files (`FileIconVector`, `ImageDecode`, the preview subsystem,
  `navigation/Destination`) + `ImagePreviewViewModel` (caches `ImageBitmap`, UI-coupled) + the Koin DI
  assembly, and `api`-depends on `:drive-shared-logic`.
- **Packages unchanged (`app.tenshu.drive.*`).** A screen in `-ui` and its view model in `-logic`
  share a leaf package (e.g. `ui.screens.files`) across the two modules; Kotlin resolves same-package
  symbols over the module dependency with **no import**, so the 35 screens needed **zero** import
  edits — only file moves + the dependency edge. (Split-package-across-modules is a mild
  JetBrains-discouraged smell but compiles cleanly and raised no IDE warnings; revisited later whether
  to rename logic packages.)
- **No `iosX64`** on `-logic` either — it `api`-depends on `:core-paging` (no iosX64 klib). Reinforces
  the slice-3 "drop iosX64 umbrella-wide" recommendation.
- **`toPagingData` dropped its `expect`/`actual`** — now that `:core-paging.pagingSource` is in
  commonMain, it's a plain commonMain function in `-logic`; the JVM-only actual is gone.
- **New `lifecycle-viewmodel` catalog entry** — the base Compose-free multiplatform
  `androidx.lifecycle:lifecycle-viewmodel` (the ~21 VMs extend `ViewModel` + `viewModelScope`);
  previously pulled transitively via the Compose viewmodel artifacts.
- **Two `internal`-using tests** (`AwaitOrWireCancelTest`, `FolderUploadLivenessTest`) moved to
  `-logic` (they reach logic internals; `internal` doesn't cross the new module boundary). Fixed one
  iOS portability nit: bare `@Volatile` → `kotlin.concurrent.Volatile` in `SyncedFolderController`.
- **`:drive` → `:drive-android` rename deferred** to its own follow-up PR (out of this diff).
- **Verified locally** (CI billing-blocked, HR#7): `:drive-shared-logic` compiles for
  `iosSimulatorArm64` + `iosArm64` (device) + desktop + android; its `iosSimulatorArm64Test` task links
  and runs; `:drive-shared-logic:desktopTest` (4) + `:drive-shared-ui:desktopTest` (208) green; both
  apps (`:drive`, `:drive-desktop`) compile. **No threat-model change** — packaging/refactor only.

### Phase 6b.5 — as-built (Slice 3, shipped 2026-06-19). ✅ SKIE at the umbrella + logic exported.

Slice 3 makes the iOS-ized Drive logic reachable from Swift as an idiomatic SDK. Build-config only
(`:tenshu-sdk-ios` + catalog + tech-debt); no app code yet (that's slice 4).

- **SKIE adopted at `:tenshu-sdk-ios`** (`co.touchlab.skie` **0.10.12**, catalog `skie`). The §9.5
  pre-adoption gate held: 0.10.12 is the first SKIE supporting the pinned Kotlin **2.3.21**, so it
  links — at zero version headroom. The coupling is now a `tech-debt.md` entry ("SKIE ⨯ Kotlin
  version pin"): **gate every Kotlin bump on SKIE support**, exactly like the CMP 1.7.3 pin.
- **SKIE analytics upload disabled** (`skie { analytics { enabled.set(false) } }`) — SKIE phones home
  build analytics by default; a hard reject for the zero-trust posture (HR#1). Verified the
  `skieUploadAnalytics…` task is `SKIPPED`.
- **Exported `:core-paging` + `:drive-shared-logic`** from the umbrella (framework `export(...)` +
  `iosMain` `api(...)`). `export` is not transitive, hence both (the logic module `api`-depends on
  `:core-paging`, surfacing `TenshuPagingPresenter` / `Flow<PagingData>`).
- **Dropped `iosX64` from the umbrella** — the two new exports transitively need
  `androidx.paging:paging-common`, which ships no iosX64 klib, so an iosX64 framework slice can't
  link once they're on the surface. This is the slice-1 "open decision" resolved the recommended way:
  **iosX64 is gone from the shipping framework** (Apple-silicon is the only simulator arch that
  matters; the gate runs `iosSimulatorArm64`). Only the umbrella's target list changed — the
  individual modules that still declare iosX64 are simply not aggregated for it.
- **Bridge verified** (the §9.5 / slice-plan-step-3 ask): SKIE generated
  `DriveSharedLogic.FilesScreenViewModel.swift` — the VM's `suspend` methods surface as Swift
  `async throws` (`loadPreviewBytes(...) async throws -> KotlinByteArray?`), the sealed
  `RepositoryResult` renders as a Swift enum with `.error`/`.success` + `onEnum(of:)` for exhaustive
  `switch`, and the full `SkieSwiftStateFlow` family (Flow→`AsyncSequence`) is generated.
- **Verified locally** (CI billing-blocked, HR#7): `:tenshu-sdk-ios:linkDebugFrameworkIosArm64`
  (device) + `…IosSimulatorArm64` (sim) link green with SKIE + the new exports;
  `assembleTenshuSdkXCFramework` produces the `ios-arm64` + `ios-arm64-simulator` slices.
- ⚠ **SKIE name renames to expect Swift-side:** the framework-name collision renames the Kotlin
  `object TenshuSdk` (the 6a crypto smoke entry, `org.tenshu.sdk.ios`) to **`TenshuSdk_`** in Swift,
  and the schema `Type` composite to `Type_`. New slice-4 SwiftUI uses the SKIE names directly; the
  existing `drive-ios` smoke harness Swift may need a one-line `TenshuSdk` → `TenshuSdk_` touch-up
  (an Xcode-side follow-up — not verifiable headlessly). Renaming the Kotlin object (or `@ObjCName`)
  would remove the rename if desired.
- **No threat-model change.** Packaging only (plus the privacy-positive analytics-off).

### Phase 6b.5 — as-built (Slice 4a, shipped 2026-06-19). ✅ Real EPC channel + SAS screen in-app.

First SwiftUI slice: the real EPC channel running inside the iOS app binary, surfaced as a pairing
screen. Scoped to a **loopback** by necessity — a real proxy can't run on iOS (the `-proxy`
master-key/op code stays off the client, HR#1), and Control doesn't yet *serve* EPC over the relay
(6d/6e). So the handshake/SAS/sealed transport are real; only the peer is co-located (the Phase-2
"protocol-first over loopback" property — swapping in `RelayRendezvousTaskTransport` against a live
Control is a transport swap, no protocol change).

- **`EpcLoopback` (umbrella `iosMain`, `org.tenshu.sdk.ios`).** A `LoopbackProxyTransport` terminates
  the EPC handshake + sealed traffic with a co-located `EpcResponder`: the first `call` is the
  `ClientHello` → `ServerHello` (records the proxy `EpcSession` + SAS); later `call`s are sealed
  client→proxy frames, opened with the proxy session, run through a handler, sealed back. Mirrors
  what `EpcRendezvousAcceptor.serve` does, minus the relay (that acceptor is `:sync-service-proxy`,
  off the iOS surface). `connect()` runs `EpcConnector(crypto).connect(...)` and returns a
  `Channel(connection, proxySas)` with `sasMatches`. *As built later:* `crypto` was the
  `BootstrapSyncCryptoManager` stub at slice 4a; since the stub moved to `:core-test-utils` it is
  `EpcRelayPairing.realCrypto()` (libsodium), so the loopback exercises the real X25519 + AEAD path.
- **SwiftUI `EpcPairingView`** drives `EpcLoopback.shared.connect()` (SKIE renders the Kotlin
  `suspend` as Swift `async`) and shows the 6-digit SAS in a confirm card (EPC *measure 1*). The app
  is now a `TabView`: **Pair** (the SAS screen) + **Smokes** (the headless verdict).
- **`EpcLoopbackSmoke`** asserts the handshake yields a matching 6-digit SAS on both ends + a sealed
  op round-trips, wired into the `RESULT: ALL PASSED` console verdict.
- **Two build-integration fixes the iosX64 drop (slice 3) forced on the consuming app:**
  (i) a no-arg `connect()` overload — SKIE does not surface Kotlin default values for *function-typed*
  params to Swift, so `connect(handler = …)` alone left no zero-arg Swift method;
  (ii) `EXCLUDED_ARCHS[sdk=iphonesimulator*] = x86_64` in `project.yml` — the umbrella has no x86_64
  slice now, and without excluding it the app tried to build x86_64, failed to load the framework's
  Swift module for that arch, and SKIE's async extensions silently vanished behind the Obj-C fallback
  ("value of type 'EpcLoopback' has no member 'connect'"). The README's build command also moved to a
  generic simulator destination (device-name resolution drifts with installed runtimes).
- **Verified locally** (CI billing-blocked, HR#7): `assembleTenshuSdkDebugXCFramework` +
  `xcodebuild -sdk iphonesimulator` build green; `simctl install`+`launch` on the iPhone-16 sim →
  **RESULT: ALL PASSED**, incl. `[EPC channel (6b.5)] EPC handshake yields a matching SAS` (e.g. SAS
  790215) + `Sealed op round-trips … ('echo:ping')`. The SwiftUI pairing screen renders the SAS.
- **No threat-model change.** Loopback only — no key leaves the device, no relay/proxy contact.
- **Next — slice 4b:** the read surface — `FilesScreenViewModel` (resolved via Koin `get()` from a
  minimal iOS wiring module) bound to a SwiftUI list through `TenshuPagingPresenter`, its data from a
  small in-app `FilesRepository` (the 49-member interface) since no proxy serves real listings yet.

### Phase 6b.5 — as-built (Slice 4b, shipped 2026-06-19). ✅ Real Drive VM → SwiftUI list.

Closes the §9.3 loop in an app binary: the **real `FilesScreenViewModel`** (`:drive-shared-logic`)
constructs on iOS, and its paged `StateFlow<PagingData<FileUiModel>>` drives a SwiftUI list through
`TenshuPagingPresenter` — "share the view models / repos, write the UI native" proven end-to-end.

- **`DriveReadSurface` (umbrella `iosMain`)** hand-wires the VM stack the SwiftUI client needs —
  `SelectionViewModel` + `FilesScreenViewModel` + a `TenshuPagingPresenter<FileUiModel>` already
  collecting `viewModel.pagedFiles`. Drive's Koin DI lives in `:drive-shared-ui` (Compose, off iOS),
  so this minimal factory stands in for `viewModelsModule()`; the VM is plain Kotlin resolved without
  Koin here.
- **`CannedFilesRepository`** implements the 27-member `FilesRepository` with a fixed in-app listing
  (`getFilesPaged` → `PagingData.from(listing)`; `storageStatus`/`getPreview`/`hasLocalData` benign;
  the rest error if called). **The boundary is deliberate:** the VM, the `FileModel`→`FileUiModel`
  paging map and the presenter are *real*; only the repository's *data source* is local, because no
  proxy serves Drive ops on iOS yet (HR#1; Control serves EPC at 6d/6e). Swapping in a
  `TenshuFilesRepository` over the EPC `TaskTransport` is then a repository swap, no VM/UI change.
- **SwiftUI `FolderListView`** (a `Files` tab) observes `presenter.snapshot` — SKIE renders the
  `StateFlow<List<FileUiModel>>` as an `AsyncSequence`, consumed via `for await` into an
  `@Published [FileUiModel]`. `FileIcon` bridges as a Swift enum (`switch` → SF Symbols).
- **`DriveReadSurfaceSmoke`** drives the VM→presenter on `Dispatchers.Default` (the Pager fetcher is
  off the main run loop) and asserts the 4 canned files surface.
- **Verified locally** (CI billing-blocked, HR#7): `xcodebuild -sdk iphonesimulator` green;
  `simctl launch` on the iPhone-16 sim → **RESULT: ALL PASSED**, incl. `[Drive read surface (6b.5)]
  FilesScreenViewModel paged listing drives the presenter — presenter surfaced 4 files … (Photos,
  sunset.jpg, notes.txt, budget.pdf)`. The SwiftUI Files tab renders the listing.
- **No threat-model change.** Canned local data; no key/proxy/relay contact.

**Phase 6b.5 is COMPLETE** (slices 1 → 4b): `:core-paging` + the Drive logic layer are iOS-capable,
SKIE bridges them to Swift, and the iOS app runs the real EPC channel (SAS) + the real Drive VM →
SwiftUI list — all over the Phase-4a software attestor, no Apple Team ID. The follow-up
`:drive`→`:drive-android` rename also landed (the Android app module; a pure Gradle-path rename —
namespace/applicationId stay `app.tenshu.drive.android`). Remaining on the §9 track:
**6c/6d** (real Apple App Attest + live attestation gating) and **6e** (Control-on-iOS co-resident
proxy — the thing that lets the read surface show *real* listings over EPC).

### Phase 3 — threat-model amendment (LANDED in `threat-model.md` with 3a)

Per House Rule #2 the amendment landed *in* [`docs/sync/threat-model.md`](../sync/threat-model.md)
in the same change as the 3a relay code: asset **A31** (EPC rendezvous state + pairing capability),
attack surface **#14** (the `/sync/epc` mailbox), and threat group **§8.16 (T-RDV-01–06)** + the
House-Rule-#1 audit, plus the §0 header sub-amendment bullet. **O2 resolved: no edge App Attest** —
JWT-gating + per-owner/per-direction caps + TTL are the whole admission story (T-RDV-03 residual
Medium, DoS-only); the relay stays vendor-neutral. See §8.16 for the canonical text.

### Phase 1 — as-built (build-graph decisions to persist)

Phase 1 shipped as build + CI only (no product code beyond two platform-glue/test fixes noted
below). The non-trivial build-graph decisions, recorded here per the "decisions land in docs"
house rule:

- **One umbrella, `:tenshu-sdk-ios`** (mirrors `:tenshu-sdk-js`), producing
  `TenshuSdk.xcframework` via `XCFramework("TenshuSdk")`. No per-module frameworks (the
  `core/build.gradle.kts` comment's decided shape).
- **What the umbrella exports = the client-facing shared surface**, and *only* that:
  `:core` + `:core-common` + `:core-client`, plus every service `-common` and `-client` module
  that declares iOS targets (identity / file / notification / ingest / preference / contact /
  storage / logging / sync). It deliberately **excludes** the `-proxy` modules — under
  proxy-mediated EPC (§4) the master key and operation implementations stay on the proxy, so an
  iOS *client* never links proxy code — and the `-test-utils` modules. (The JS SDK exports
  `-proxy` modules because the web Baseline runs them in-process; iOS does not.)
- **Static framework** (`isStatic = true`): simplest to embed in an Xcode app target; the first
  iOS app can flip to dynamic if it ever needs to.
- **A trivial `TenshuSdk` Kotlin object exists in the umbrella's `iosMain`** purely so the
  framework has a source to link — an export-only module links as `NO-SOURCE` and produces no
  artifact. It is not API; the real client entry points (pairing, channel) arrive in Phase 2.
- **iOS test gate scope.** `iosSimulatorArm64Test` is enabled for the client-facing surface (the
  modules the umbrella ships) and gated *out* for `-proxy` / `-test-utils` modules in the root
  `build.gradle.kts` (via `afterEvaluate { …enabled = false }` — the lazy `configureEach` form
  did not stick for an explicitly-invoked test task). Proxy-side iOS test-green is deferred to
  Phase 6 (Control-on-iOS) when that code actually targets iOS; some proxy modules carry genuine
  K/N-vs-JVM behavioural gaps (e.g. a `TraverseOperation` NPE) out of Phase 1's build-only scope.
  `iosX64Test` / `iosArm64Test` stay disabled everywhere (device arch, unrunnable on the macOS
  simulator host).
- **CI lane** `.github/workflows/ios.yml` on `macos-latest` runs `iosSimulatorArm64Test` +
  `:tenshu-sdk-ios:assembleTenshuSdkXCFramework`. The previous no-op `test-ios` job in
  `android.yml` (it ran the then-disabled task) was removed to avoid duplication. ⚠ It cannot be
  observed green on GitHub while the Actions **billing block** is in effect (runners fail fast);
  Phase 1 was verified locally instead, and the lane should be marked a required check once
  billing is restored.
- **Two non-build fixes were required to get the shipped surface green** (both genuine
  cross-platform correctness, not feature code): `core-common`'s iOS `nextSecureRandomBytes`
  guarded against `size == 0` (`addressOf(0)` throws on an empty K/N array — caught by the gate),
  and one `:file-service-proxy` test function renamed to drop a comma (K/N rejects commas in
  backtick test names; the proxy module is out of the test gate but its test source still
  compiles).

### Done in the framing session (pre–Phase 1)

- §6 `ios-ipc.md` rewritten to first-party-only with a top warning and the superseded
  lifecycle note.
- `milestones.md` iOS Drive Client + External Proxy Connection mentions now point here, and the
  data-plane fork is recorded as decided (§4).
