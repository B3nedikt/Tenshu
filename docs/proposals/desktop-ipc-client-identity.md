# Desktop IPC client identity — every UDS peer is currently "Drive"

**Status:** **Draft v0.3 — design settled + implementation phased; D2 spike-proven end-to-end, no
production code (2026-07-28).** Opened while wiring Fit into Control's app registry: the Android
half is two table edits, the desktop half is blocked on this. §1–§4 and §6.2 are verified fact; §5
is now a settled design rather than a direction.

**2026-11-05 — Apple-re-signed store copies (§10):** the team-OU requirement rejected every
Mac App Store / TestFlight install of our own apps, because Apple re-signs them with its own leaf
certificate. The gate now has a second route: Apple's store marker **plus** the signed team
identifier. Threat model A14 amended.

**2026-11-06 — the client direction (§11):** every desktop app now attests the process serving
`ipc.sock` as Tenshu Control before writing to it; before this, only Control checked its clients.
Threat model A14 amended.

**v0.3 deltas** (all from spiking the D2 chain in a real JVM against real signed peers — §6.2):
- ⚠ **Team ID corrected to `FAYT82GWD7`.** v0.2's D5/P0 named `<identity id>`, which is the identity's
  *CN suffix*, not the team. The requirement pins `subject.OU`, and the cert's OU is `FAYT82GWD7`.
  A requirement built on the v0.2 value would have rejected every peer including our own.
- **D2 is proven, in both directions** (§6.2): audit token → `SecCodeCopyGuestWithAttributes` →
  `SecCodeCheckValidity` runs from the JVM, and the Team-ID pin **admits a genuinely-signed peer
  while rejecting an ad-hoc imposter claiming the same identifier**. §4.1's bypass was re-verified
  *through the gate itself*, not just at the command line.
- **Q2 answered — FFM, housed in `:macos-helpers`, not in `:core-common`** (§7 Q2). This costs
  `:core-common` nothing: no toolchain bump, no Android/iOS blast radius, no per-connection spawn.
- ⚠ **New hard prerequisite: `--add-opens java.base/sun.nio.ch=ALL-UNNAMED`.** Recovering the peer
  fd is a hard `InaccessibleObjectException` without it, and it must land in **both** jvmArgs sites
  (`run` *and* `nativeDistributions`) or the verifier works in dev and dies when packaged.
- ⚠⚠ **§2 gains a new item 1: the hardcode is also a live *routing* bug**, not only a naming one —
  two desktop apps running together cross-wire their proxy→client push streams.
- **P0 hazard sharpened:** the existing signing tasks pass `--options runtime`, which *breaks
  launch* for a jpackage app. P0 has to drop it, not inherit it.
- **Q1, Q4, Q5 answered.**

---

## 1. The gap in one sentence

`UnixSocketServer` assigns every accepted connection the **same hardcoded client id**, so Control
cannot tell one desktop app from another — and treats them all as Drive.

```kotlin
// core-common/src/desktopMain/.../transport/UnixSocketServer.kt:64
val clientId = "drive-desktop-client" // For now, we only support one client.
```

## 2. What this actually causes today (verified)

App registration is **proxy-side and handshake-triggered** — no app calls `registerApp`.
`ClientRegistry.onSessionEstablished` → `AppRegistrationTrigger` → `RegisterAppImplementation`
fires when a client binds, and `AppIdentityResolver.resolveAppId(clientId)` decides whether that
client has a name (null ⇒ silent no-op; the allowlist is the gate).

Consequences, in severity order:

1. ⚠⚠ **Two desktop apps running at once cross-wire their push streams.** `remoteConnections` is
   keyed by `clientId` (`UnixSocketServer:32`), so the second app to connect **replaces** the
   first's entry. Every proxy→client push resolves through
   `getRemoteConnection(clientId)?.send(data)`
   (`control-shared-proxy/.../PlatformProxyModule.kt:786`), so once Fit-desktop connects, Drive's
   reactive results are delivered to *Fit's* socket. This is a correctness bug in the live build,
   independent of grants — and it presents as "Drive's UI went dead", which is
   indistinguishable from the resubscription-gap symptom already catalogued for Android
   (`core-client-resubscription.md`). Disconnect is safe (`remove(clientId, connection)` is the
   value-matching two-arg form); only connect clobbers.
2. ⚠⚠ **Every desktop app inherits Drive's identity — and therefore Drive's grants.** Fit-desktop
   already connects over the UDS with the full generic client stack
   (`UnixSocketTaskTransport` → `RemoteBinaryTaskExecutor` → `DefaultTenshuClient`). It is already
   triggering registration; it registers *as Drive*, hits `RegisterAppImplementation`'s idempotency
   check, and no-ops. Once per-app grant enforcement is real, this is a privilege bug, not a
   cosmetic one: the Apps screen's permission toggles are keyed by `appId`.
3. **No desktop app except Drive can ever appear** in Control's Apps list or the tray's Apps
   section (`listApps` → `AppRegistry.installedApps()` → the synced `AppRegistration` rows). Adding
   a `fit-desktop-client` row to `BundledAppIdentityResolver` would be dead code — the string is
   never produced.
4. **Observed 2026-07-27:** the personal realm's store held exactly two `tenshu/app/registration`
   rows, both `app.tenshu.drive` (one per device), despite Fit, Timeline, Maps and Home desktop all
   existing and Fit-desktop actively connecting.

## 3. ⚠ Why the obvious fix is the wrong one

The tempting fix is "let the client send its id in the handshake." **Reject that.**

Android's client id is **OS-attested and unforgeable by the caller** —
`ProxyService.getCallingPackageName()` reads `Binder.getCallingUid()` and asks the
`PackageManager` which package owns that uid. The app never states its own name.

A self-asserted id over a Unix socket is the opposite: any process that can open
`~/.tenshu/ipc.sock` could claim `clientId = "app.tenshu.drive"` and inherit Drive's standing
grants. That would make the desktop story *weaker* than today's hardcode, which at least fails
closed into a single known identity. It also silently converts `AppIdentityResolver` from an
allowlist into a self-service registration.

**Requirement: desktop identity must be attested by the OS or by code signing, never by the peer.**

## 4. The seam already exists — and already has the right shape

`PeerCredentialGate.kt` (threat-model A14/A20, `desktop-ipc.md` §3) was built for exactly this and
is already wired into both `UnixSocketServer` (`:63`) and `NativeBridgeServer` (`:159`):

```kotlin
data class PeerCredential(
    val verified: Boolean,
    /** Resolved app identity (e.g. the signed bundle id), or null when unresolved. */
    val appId: String? = null,
    val reason: String,
)

fun interface PeerCredentialVerifier { fun verify(channel: SocketChannel): PeerCredential }
```

`PeerCredential.appId` is **already the field this proposal needs**. The gate's own KDoc names the
missing piece: *"the real check needs native peer-credential access (`LOCAL_PEERPID` → binary path
→ code-signing identity) that the JVM does not expose on macOS"*, with
`UnverifiedPeerCredentialVerifier` as the placeholder and `PeerCredentialPolicy(strict = false)`
admitting everyone.

So this is **not a new mechanism**. It is: implement the real verifier, then feed its `appId` into
the session instead of the hardcoded string. `BundledAppIdentityResolver`'s KDoc already predicts
the endpoint — *"the future verifier-backed resolver … will derive identity from the peer's
code-signing publication rather than a static table."*

### 4.1 ⚠⚠ "macOS enforces signing anyway" — true, and not the property we need

Worth stating plainly because it is the natural assumption and it is half right in the dangerous
direction.

**What macOS does enforce (on Apple Silicon):** the kernel refuses to execute an unsigned binary,
so every running process carries *a* signature. `cc` ad-hoc signs its output automatically.

**What it does not enforce:** who that signature says you are. Ad-hoc signing is self-service and
the identifier is a free-text argument. Demonstrated locally 2026-07-27 with a throwaway binary:

```
$ cc -o imposter imposter.c
$ codesign -dv imposter          → Identifier=imposter  Signature=adhoc
$ codesign --force -s - -i "app.tenshu.drive" imposter
$ codesign -dv imposter          → Identifier=app.tenshu.drive  Signature=adhoc
$ codesign --verify imposter     → valid on disk
                                 → satisfies its Designated Requirement
```

A three-line binary now claims Drive's identifier, is "valid on disk", and **satisfies its
designated requirement**. Gatekeeper does not help: it gates *quarantined* downloads on first
launch, not a locally built binary opening a socket.

Two consequences that shape the whole design:
1. **Reading the identifier is not authentication.** Any check that ends in a string comparison
   against `app.tenshu.drive` is bypassed by the four commands above.
2. **The check must pin the anchor + Team ID via a requirement** (D2). That is the only part an
   attacker cannot self-assert without our signing key.

## 5. Design (settled v0.3)

**D1. Identity comes from `PeerCredential.appId`, not the handshake.** `handleClient` replaces the
hardcoded literal with the verifier's resolved identity. One line at the call site; the work is the
verifier.

**D2. The verifier checks the peer against a code *requirement*, keyed on the audit token.**

⚠ **Corrected in v0.2** — the first draft said `LOCAL_PEERPID` → `proc_pidpath` →
`SecStaticCodeCreateWithPath`. That shape has two defects and should not be built:
- **PID reuse.** A pid is recycled; by the time it is resolved it may name a different process.
- **TOCTOU.** `SecStaticCode*` inspects the **file on disk**, not the running image. The binary can
  be replaced after exec, so a passing check says nothing about the process on the socket.

Use the **audit token** and check the **running code**. The exact chain, proven in §6.2:
`getsockopt(SOL_LOCAL, LOCAL_PEERTOKEN)` → `SecCodeCopyGuestWithAttributes(NULL, {
kSecGuestAttributeAudit: CFData(token) }, …)` → `SecCodeCheckValidity(code, flags, requirement)`.

⚠ Use `SecCodeCopyGuestWithAttributes`, **not** `SecCodeCreateWithAuditToken` as v0.2 wrote — the
latter is not public API. The guest-attribute route is public, and it returns a `SecCodeRef` for the
**running process**, which is the property that defeats the TOCTOU above.

**And check a requirement — do not read an identifier and compare strings** (see §4.1 for why).
The requirement pins the anchor and the team:

```
anchor apple generic and certificate leaf[subject.OU] = "FAYT82GWD7"
    and identifier "app.tenshu.fit"
```

**Implementation shape — hint, then verify.** The identifier is needed *before* the requirement can
be built (the requirement names it), so the order is: read the identifier via
`SecCodeCopySigningInformation` as an **untrusted hint** → build the requirement pinning
`anchor` + `subject.OU` + that hint → `SecCodeCheckValidity`. Only after validity passes is the
identifier attested. This is O(1) rather than looping every allowlisted requirement, and it is safe
precisely because the hint is never itself the decision — the team pin is. ⚠ The variable named
`identifier` is untrusted for the length of exactly one function; name it `hint` so no later reader
mistakes it for an authenticated value.

The JVM exposes none of this, so it needs a native shim. ⚠ **There is already a precedent for the
shape**: `:macos-helpers` compiles Swift helpers (`MacImageDecoder`, `HelperBinary`) invoked from
JVM, and `:ui-core` does the same for `qr-scan-helper`. Helper-process vs in-process FFM is still
open (§7 Q2) — note `control-desktop` already pins `jvmToolchain(FFM_JDK = 25)` for
`java.lang.foreign`.

**D3. Unresolved ⇒ no identity, not a fallback identity.** A peer whose signature cannot be
resolved gets `appId = null`, and `AppRegistrationTrigger` already no-ops on null. Failing closed
into *anonymous* is correct; failing closed into *Drive* is what we have now and is the bug.

**D4. Keep the allowlist.** `BundledAppIdentityResolver` stays the gate — the verifier supplies an
attested bundle id, the resolver decides whether that id is a known first-party app. Two
independent checks, matching the Android shape (OS says which package; the table says whether we
know it).

**D5. ⚠⚠ Adhoc dev builds are the blocker, and this is sharper than "needs an escape hatch".**

Measured on the maintainer's machine, re-confirmed 2026-07-28:
- `/Applications/TenshuControl.app`, `TenshuDrive.app` and `TenshuFit.app` are all
  `Signature=adhoc`, `TeamIdentifier=not set`, `flags=0x10002(adhoc,runtime)`.
- `tenshu.teamId` and `tenshu.signingIdentity` are **unset** in `local.properties`, so the signing
  tasks self-skip (`signControlAppForAppGroup: SKIPPED`).
- Exactly one identity is available: `Apple Development: <Apple ID e-mail>
  (<identity id>)` — whose certificate subject is
  `UID=E8MUF6ZBJ2, CN=Apple Development: … (<identity id>), OU=FAYT82GWD7`.
  ⚠ **The Team ID is the OU, `FAYT82GWD7`.** `<identity id>` is part of the common name and is *not*
  what `certificate leaf[subject.OU]` matches. v0.2 conflated the two.

So a Team-ID-pinned requirement (D2) rejects **every locally built Tenshu app today**, including
Control's own peers. That means P3 cannot simply be "flip strict": the local install path has to
start signing with a real identity first, or strict mode is unusable for development.

Sequencing consequence: **make the packaged build sign with the available Apple Development
identity before P3**, and keep the dev bypass explicit, non-default, and impossible to enable in a
release build. A bypass that is on by default is indistinguishable from not having the gate.

## 6. Phasing

Each phase is independently shippable and leaves the system working. **P0 is a hard prerequisite**
(D5): until local builds carry a real Team ID, the verifier has nothing to resolve and P3 is
unreachable, so doing P1 first would just produce a resolver that returns "adhoc, no team" for
every peer including Control's own.

### P0 — sign the local install path

*Build scripts only. No Kotlin, no `:core-common`, no behaviour change.*

Until every locally installed bundle carries `TeamIdentifier=FAYT82GWD7`, a Team-ID-pinned
requirement rejects our own peers, so P1 would resolve nothing and P3 would be unreachable.

1. **P0.1 — credentials.** Add `tenshu.teamId=FAYT82GWD7` and
   `tenshu.signingIdentity=Apple Development: <Apple ID e-mail> (<identity id>)` to the
   gitignored `local.properties`. Both existing resolvers already read it (`-P` → env →
   `local.properties`), so no build-script change is needed for this step alone.
2. **P0.2 — pin every desktop `bundleID` explicitly.** Only `control-desktop` (`app.tenshu.control`)
   and `drive-desktop` (`app.tenshu.drive`) set one. `fit-`/`maps-`/`timeline-desktop` have a
   `macOS { }` block with no `bundleID`; `home-`/`mia-desktop` have no block at all. Fit's installed
   bundle *happens* to report `app.tenshu.fit` via the plugin's derivation — but the identifier is
   about to become security-relevant, and an identifier nobody declared is one a plugin upgrade can
   silently change. Declare all seven.
3. **P0.3 — a signing step for every desktop app.** Today only two apps have one, and both are
   entitlement-specific (`signControlAppForAppGroup`, `signDriveAppForFileProvider`). Fit, Timeline,
   Maps, Home and Mia have none. Factor the common shape once and apply it to all seven rather than
   copying a `doLast` block seven times.
4. **P0.4 — sign inside-out.** Nested code signs first: `Contents/Helpers/tray-agent`,
   `qr-scan-helper`, `image-decode-helper`, then the bundle.

⚠⚠ **Do not inherit `--options runtime`.** Both existing tasks pass it, and the installed bundles
are already `flags=0x10002(adhoc,runtime)`. On a jpackage app, hardened runtime + **Library
Validation** rejects the bundled JDK's `libjli.dylib` (different Team ID) and **the app stops
launching**. For the local install path, sign *without* `--options runtime`. If hardened runtime is
ever needed (notarisation), it requires a `com.apple.security.cs.disable-library-validation`
entitlement. Decide this explicitly in P0 — it is not a detail that can be deferred, because the
first thing P0 changes is the flag set every bundle is signed with.

**Done when** `codesign -dv` reports `TeamIdentifier=FAYT82GWD7` for all seven installed bundles
**and each one still launches**. The second half is the real gate.

⚠ **The two things most likely to look like a regression** (both are code-identity side effects,
not bugs you introduced):
- **Login-Keychain ACL re-key.** At-rest secrets are bound to the old code identity;
  `DesktopMasterKeyStore` silently falls back to in-memory when the prompt is not answered
  *Always Allow*, which presents as "Control asks me to re-pair on every restart". Verify Control
  unlocks and *stays* unlocked across a restart before moving on.
- **TCC re-prompt.** Camera/other grants are recorded against code identity; a recorded deny under
  the old signature sticks and macOS never re-prompts. `tccutil reset Camera app.tenshu.control`.

#### P0 as-built (2026-07-28)

Shipped as `gradle/desktop-app-signing.gradle.kts` (a script plugin, applied from all seven
`*-desktop` modules) registering `signDesktopApp`. Deltas from the plan above:

- ⚠ **`tenshu.teamId` was already set** to `FAYT82GWD7` in `local.properties` (from the iOS
  device-signing work) — D5's "both unset" was stale, and it independently corroborates the OU
  correction. Only `tenshu.signingIdentity` was missing, which is why the tasks self-skipped: they
  require *both*.
- **No `buildSrc`, and configuration cache is on** (Gradle 9.4.1). A script plugin is the shape that
  adds no build-graph infrastructure; it stores *and reuses* the config-cache entry, provided every
  value the `doLast` touches is declared inside the `register` block — the rule `control-desktop`
  already documents.
- **The task verifies its own output** and fails the build when `TeamIdentifier` is not the
  configured team. Silently emitting an ad-hoc bundle is precisely the state P0 exists to end, so it
  must not degrade to a warning like the pre-existing tasks do.
- ⚠⚠ **The two pre-existing entitlement-signing tasks were an armed footgun**, not merely a bad
  example: they pass `--options runtime`, and were harmless only because they self-skip without
  `tenshu.signingIdentity` — which P0 asks people to configure. Both entitlements templates gained
  `com.apple.security.cs.disable-library-validation`, so configuring signing no longer arms a launch
  break. Safe because both cover the non-sandboxed JVM host, not the sandboxed `.appex`.
- **Scoped the `.app` search** to `build/compose/binaries/main/app`, fixing the nondeterminism noted
  in P0.3 (the old tasks depend on `createReleaseDistributable` but walk the shared root and take the
  first match).

**Verified:** `TenshuControl.app` + `TenshuFit.app` → `TeamIdentifier=FAYT82GWD7`, `flags=0x0`
(hardened runtime correctly absent); umbrella path seals 3 nested helpers; Fit's newly-pinned
`bundleID` takes effect (`Identifier=app.tenshu.fit`); the bundle **satisfies the exact P1
requirement** and **fails it under a wrong team**.

⚠ **Outstanding — the second half of P0's gate is maintainer-side.** "Each app still launches" was
deliberately not executed: launching a freshly-signed Control triggers the login-Keychain ACL
re-key, and `UnixSocketServer.start()` does `deleteIfExists(path)` before binding, so a second
Control **steals the UDS socket** from a running one. That is a call for whoever owns the machine,
not something to do unilaterally mid-phase.

### P1 — the real verifier, observe-only

*The build. Nothing consumes the result and nothing is rejected.*

Housing (Q2): the FFM implementation goes in **`:macos-helpers`** — Compose-free, JVM-only, already
compiles against JDK 25 while emitting JVM-17 bytecode. `:core-common` keeps the
`PeerCredentialVerifier` seam and gains **no** dependency, no toolchain bump, and no
Android/iOS blast radius. `:macos-helpers` depends on nothing but the stdlib and must stay that way,
so it exposes a plain JDK-typed API and the ~15-line adapter to `PeerCredential` lives on the
`control-desktop` side.

1. **P1.1 — JVM flags, both sites.** Add `--add-opens java.base/sun.nio.ch=ALL-UNNAMED`,
   `--add-opens java.base/java.io=ALL-UNNAMED` and `--enable-native-access=ALL-UNNAMED` to
   **both** the `run` jvmArgs *and* the `nativeDistributions` jvmArgs in `control-desktop`
   (`build.gradle.kts:89` and `:108`). ⚠⚠ Miss the second and the verifier works under
   `./gradlew run` and throws in the packaged app — the exact shape of bug the tray-packaging
   entry already burned a session on.
2. **P1.2 — `PeerAuditToken`** (`:macos-helpers`): `SocketChannel` → fd (reflection, walk the
   hierarchy for the `FileDescriptor` field) → `getsockopt(SOL_LOCAL=0, LOCAL_PEERTOKEN=6)` → the
   32-byte `audit_token_t`. Returns null on any failure — never throws into the accept path.
3. **P1.3 — `MacCodeSignatureVerifier`** (`:macos-helpers`): the §5 D2 hint-then-verify chain over
   FFM. Returns the attested identifier **only** when `SecCodeCheckValidity` passes; otherwise null.
   The Team ID is a constructor parameter, not a constant — the test needs to vary it.
4. **P1.4 — adapter + wiring.** An `AttestedPeerCredentialVerifier : PeerCredentialVerifier`
   mapping the result to `PeerCredential(verified, appId, reason)`, passed to the single production
   construction site `control-desktop/.../Main.kt:58`. **Policy stays non-strict.**
5. **P1.5 — log per connection** (Q5: yes). This is the entire point of the phase.

**Testing — must discriminate in both directions.** Sign one fixture ad-hoc claiming a real Tenshu
identifier and one with the genuine identity claiming the *same* identifier, connect each over a
real socket, and assert imposter-rejected **and** genuine-accepted. A test that only asserts the
rejection passes against an always-reject implementation, which is the failure mode already
recorded for the detector work.

**Done when** the log shows Drive-desktop, Fit-desktop and Control's own peers each resolving to
their own attested bundle id, and an ad-hoc build resolving to nothing.

⚠ **Do not skip ahead to P2 here.** P2 is the visible win and the temptation is real, but consuming
an identity you have not yet watched resolve correctly across every app is how a silent
misattribution ships. P1's log is the evidence that P2 is safe.

#### P1 as-built (2026-07-28)

`:macos-helpers` gains `PeerAuditToken` (fd → `getsockopt(LOCAL_PEERTOKEN)`) and
`MacPeerAttestation` (guest-from-token → identifier hint → team-pinned
`SecCodeCheckValidity`), with the adapter `AttestedPeerCredentialVerifier` +
`FirstPartyTeamPolicy` in `control-desktop` and the verifier passed to `UnixSocketServer` at
`Main.kt`. Policy stays non-strict; nothing consumes `appId`.

- **Trust anchor is compiled in, and is a `Set`.** `generatePeerTrustAnchor` bakes
  `tenshu.teamId` (+ optional `tenshu.additionalTeamIds`) into a generated
  `PeerTrustAnchor.kt`. ⚠ Runtime trust deliberately does **not** read a property or env var
  the way *signing* config does — the gate exists to stop a hostile local process, which
  could then add its own team, and a shipped Control has no `local.properties` anyway. The
  set exists so a future team migration is additive rather than a flag day.
- **Policy is `identifier → teams`, injected.** `:macos-helpers` stays free of Tenshu types
  and owns no policy. v1 vouches for any identifier signed by our team — that answers "is
  this one of ours?", with "do we know this app?" left to `AppIdentityResolver`'s allowlist
  (D4's two independent checks, mirroring Android). Third-party support later narrows this
  one function; no new subsystem.
- ⚠ **`--add-opens java.base/sun.nio.ch=ALL-UNNAMED` landed at three sites**, not two: both
  `control-desktop` JVM args (hoisted into one `PEER_GATE_JVM_ARGS` so they cannot drift) and
  the `:macos-helpers` **test** JVM. Verified present in the packaged app's `.cfg`.

⚠⚠ **The first run of the test suite produced a false pass, and it is worth knowing why.**
The imposter-rejected test passed while the mechanism was completely non-functional — the
test JVM lacked the opens, so *every* attestation failed at the fd, which is indistinguishable
from a working gate rejecting a peer. Only the paired genuine-peer test exposed it. Two
consequences, both now in the suite: the rejection test asserts **what caused** the rejection
(`not signed by`), and failures carry a reason specific enough to tell a build-configuration
mistake from a hostile peer. A rejection-only suite here is worth approximately nothing.

**Verified:** four `MacPeerAttestationTest` cases green over a real socket against binaries
built and signed at test time — ad-hoc imposter claiming `app.tenshu.drive` **rejected by the
team pin**, genuinely-signed peer claiming the identical identifier **attested**; unknown
identifier rejected; requirement string pinned. `:core-common:desktopTest` unaffected.

**P1's gate is MET (2026-07-28).** Against the real signed apps on the maintainer's machine,
Control logged exactly:

```
[ipc] peer attested: app.tenshu.drive (team FAYT82GWD7)
[ipc] peer attested: app.tenshu.fit   (team FAYT82GWD7)
```

One line per app, no unattested peers, no exceptions. **P2 is therefore safe to build** — the
identity it will consume has been observed resolving correctly for every real desktop peer.

⚠ **Two things that make this test reproducible, both easy to get wrong:**
- **Every app must be P0-signed, not just Control.** Drive and Fit were still
  `TeamIdentifier=not set` on first attempt, so they correctly logged *unattested* — the gate
  working, looking like the gate broken. `signDesktopApp` per module, then reinstall.
- **Control must be launched from a terminal** (`…/TenshuControl.app/Contents/MacOS/
  TenshuControl`). The log is a `println`, and LaunchServices discards a bundle's stdout, so a
  Finder-launched Control shows nothing however well the gate works. (A durable log surface is
  `core-logging-facade.md`'s business, not P1's.)

⚠ **Do not read the Apps list as a P1 signal.** Fit still appears as *Drive* there, and that is
correct at this phase: `UnixSocketServer:64` still hardcodes the clientId, so registration is
untouched. P2 is what changes it.

### P2 — consume the identity

*The phase that fixes the live bugs (§2 items 1 and 2).*

1. **P2.1 — replace the hardcode.** `UnixSocketServer:64` takes the verifier's resolved id.
2. **P2.2 — ⚠ unresolved peers need a *unique* id, not a shared one.** `clientId` is a map key and
   a `proxyGateway.call` argument, so it cannot be null. Give an unattested peer a per-connection
   synthetic id (`"unattested-<uuid>"`). A shared constant would recreate §2 item 1 — every
   unattested peer colliding on one key — inside the fix for it. The synthetic id resolves to
   `null` through `AppIdentityResolver`, so `AppRegistrationTrigger` no-ops (D3).
3. **P2.3 — `BundledAppIdentityResolver` desktop entries.** Real attested bundle ids now, so the
   desktop `clientId` and the canonical `appId` coincide (`app.tenshu.drive`, `app.tenshu.fit`) —
   no collision with the Android ids, which carry the `.android` suffix. Delete the "⚠ No Fit
   *desktop* entry, deliberately" comment; it documents exactly this blocker.
   ⚠ Per `BundledAppTablesTest`, every resolver entry needs a matching `BundledAppManifestSource`
   manifest or it degrades silently to the bare-appId fallback.
4. **P2.4 — dev bypass** (Q4): the flag lives in `PeerCredentialPolicy` (testable) but is *sourced*
   from the existing `-Dtenshu.release` build-type jvmArg, so it cannot be enabled in a release
   build. A bypass that is on by default is indistinguishable from not having the gate.

**Done when** Fit-desktop appears in Control's Apps list and the tray as *Fit*; the store holds a
`tenshu/app/registration` row with `appId = app.tenshu.fit`; **and** Drive-desktop + Fit-desktop
running simultaneously keep their reactive streams separate (§2 item 1 — worth an explicit
two-app check, since nothing else in the suite would catch a regression here).

#### P2 as-built (2026-07-28)

`UnixSocketServer` now takes its session identity from the verifier's attested id; the
`"drive-desktop-client"` hardcode is gone. Both §2 bugs are closed.

- **`admit()` now returns `PeerCredential?` instead of `Boolean`.** The caller needs `appId`,
  and re-verifying to get it would mean checking twice — with a window in which the peer could
  exit and change the answer between the two checks. One verification, one answer, used for
  both the admit decision and the identity. `NativeBridgeServer` ignores the credential (it
  serves only Control's own tray).
- **Unattested ⇒ `unattested-<uuid>`, unique per connection.** A shared constant would have
  recreated the §2-item-1 collision among unattested peers, inside the fix for it. It resolves
  to no app, so registration stays a no-op (D3).
- **Desktop `clientId`s are now the bundle ids**, so they coincide with the canonical `appId`
  while Android's keep the `.android` suffix — no collision between the two columns.
  `FIT_DESKTOP_CLIENT_ID` added; the "no Fit desktop entry, deliberately" comment is gone.
- **Dev bypass wired but inert** (Q4): sourced from the `-Dtenshu.release=false` jvmArg the dev
  `run` task sets, which the packaged app never passes — so it cannot be enabled in a release
  build. Idle until P3 makes the policy strict.

**Verified live** on the maintainer's machine, all three apps rebuilt + signed + installed:
Control logged `app.tenshu.drive` and `app.tenshu.fit` attested, and the personal realm's
store went from **2 registration rows, both `app.tenshu.drive`**, to **4 — two Drive and two
`app.tenshu.fit`**. That store state is the §2 item-4 observation that opened this proposal,
now inverted.

**Regression test:** `UnixSocketPeerIdentityTest` (`:core-common` desktopTest) pins both
halves — two attested peers occupy two connection slots, and two *unattested* peers do too.
⚠ Both tests were confirmed to **fail** against the reinstated pre-P2 hardcode before being
accepted; after P1's false pass, a green suite is not evidence that a test guards anything.

⚠ `SqliteObjectStoreSoakTest` failed once under full-suite parallel load and passes in
isolation and on re-run — a timing-sensitive soak test, unrelated to this change.

### P3 — flip the gate strict

`PeerCredentialPolicy(strict = true)`: unresolved peers are rejected rather than admitted
anonymously. Threat-model **A14 / A20 / T-C-15** amendment lands in this PR (House Rule #2).

⚠ **`verified = true` is not the one-line change its KDoc promises.** `AppRegistrationTrigger:66`
hardcodes `false`, and the trigger's signature is `(clientId, context)` — it never sees a
`PeerCredential`. Either widen `ClientRegistry.onSessionEstablished` to carry the credential or
keep a per-connection credential map the trigger can consult. Size this as real work, not a flag
flip.

**Gate:** every first-party desktop app must be signed (P0 covers all seven) or it is rejected on
sight. This is also where **Q1** is settled for `NativeBridgeServer` — see §7.

#### P3 as-built (2026-07-28) — the gate is ENFORCING

`PeerCredentialPolicy(strict = true)` on **both** desktop servers. An unattested peer is
rejected and its channel closed before a frame is served.

- **Q1 resolved as planned: the bridge got the verifier too.** `NativeBridgeServer` now runs
  the same requirement check, which is what actually closes **T-C-15** — the bridge exposes
  File Provider read/write, so an ungated `bridge.sock` handed any same-user process the
  user's Drive plaintext. It gets its own *quiet* verifier instance (fresh connection per
  call ⇒ a per-connection log would be per-call spam).
- **`verified` is real now, and it was not the one-liner its KDoc promised.** The trigger only
  ever received `(clientId, context)` and never saw a `PeerCredential`. Rather than widen
  `ProxyGateway.call` across Android/iOS/JS, a new optional `PeerAttestationProbe` is resolved
  per session (`getOrNull`), bound on desktop to `UnixSocketServer.isAttested`. Absent ⇒
  `false`, so a platform that has not made the claim never over-states it.
  ⚠ Android has OS-attested identity but has **not** yet made the claim, and
  `TenshuAppRegistry` aggregates with `all { it.verified }` — so an app installed on both a Mac
  and an Android device still shows *unverified* overall. Deliberate conservatism, and the
  obvious next slice.
- **Attestation is tracked explicitly**, not inferred from whether the id starts with
  `unattested-` — that would be a second, silently-drifting encoding of the same fact.

**Verified live, in both directions** (the only way this claim means anything):

| peer | outcome |
|---|---|
| Drive-desktop, Fit-desktop (P0-signed) | attested + admitted |
| Control's own tray agent, over `bridge.sock` | admitted — **zero** rejections |
| ad-hoc binary claiming `app.tenshu.drive` → `ipc.sock` | **CLOSED_BY_SERVER** |
| ad-hoc binary claiming `app.tenshu.drive` → `bridge.sock` | **CLOSED_BY_SERVER** |

logged as `rejected — peer claimed 'app.tenshu.drive' but is not signed by FAYT82GWD7`.
⚠ "No rejections" alone would have been worthless evidence — it is equally consistent with a
gate that rejects nothing. The imposter probe is what makes the admit-side result meaningful.

⚠⚠ **Dev-workflow consequence, worth knowing before it surprises someone.** A desktop app run
from Gradle (`./gradlew :drive-desktop:run`) is a *toolchain JVM*, signed by Adoptium, not by
us — so against a **packaged** Control it is now **rejected**. The pairing that works is
dev-to-dev: `:control-desktop:run` sets `-Dtenshu.release=false`, which enables developer mode
(A16) and downgrades the rejection to admit-with-warning. Packaged-to-packaged also works. The
mixed case (gradle app → packaged Control) does not, by design: developer mode is sourced from
a build-type flag the packaged app never passes, because a bypass an attacker can turn on is
indistinguishable from having no gate. ⚠ Note a dev-mode peer is admitted *anonymously*
(`unattested-<uuid>`), so it connects but never registers.

**Threat model amended in this PR** (House Rule #2): **A14** rewritten — the binding is now
audit-token → `SecCodeCopyGuestWithAttributes` → requirement check, and both earlier framings
(PID→path→`SecStaticCode`; read-the-identifier-and-compare) are recorded as *superseded and
unsafe*, with the reasons. **T-C-15** implementation status → enforcing, residual **High → Low**.
**T-C-11** desktop self-registration → closed. Header sub-amendment updated.

### P4 — Linux

`SO_PEERCRED` gives uid/pid but has no code-signing analogue; identity would have to key on binary
path or a package manifest. Out of scope until Linux is a target.

### 6.1 Starting cold — what to read, in order

1. This doc's §2 (what breaks), §4.1 (why "macOS enforces signing" is not enough), §5 D2/D3/D5, and
   §6.2 (what is already proven, so you build rather than re-spike).
2. `core-common/src/desktopMain/.../transport/PeerCredentialGate.kt` — the seam being filled; note
   `PeerCredential.appId` already exists and is the field to populate.
3. `core-common/src/desktopMain/.../transport/UnixSocketServer.kt:32,63-64` — the gate call, the
   hardcode to replace, and the `remoteConnections` map the hardcode collides in (§2 item 1).
4. `core-common/src/commonMain/.../app/proxy/AppRegistrationTrigger.kt` — the consumer; confirms
   null-appId is already a safe no-op, and carries the `verified = false` hardcode P3 must widen.
5. `control/src/main/java/.../server/ProxyService.kt:212` — the Android equivalent worth matching.

**Verify the premise before building anything** (it takes a minute and is the whole argument):

```
cc -o /tmp/imposter -x c - <<< 'int main(){return 0;}'
codesign --force -s - -i "app.tenshu.drive" /tmp/imposter
codesign -dv /tmp/imposter          # → Identifier=app.tenshu.drive, adhoc
codesign --verify --verbose /tmp/imposter   # → satisfies its Designated Requirement
```

If that still passes, identifier-only checks are still insufficient and D2 stands.
*(Re-verified 2026-07-28 — still passes.)*

### 6.2 Spike results — the D2 chain, verified 2026-07-28

Run in a JDK 25 JVM against real peers connecting over a real Unix socket. Reference sources are in
this session's scratchpad (`PeerTokenSpike.java`, `GateSpike.java`, `client.c`); they are throwaway
proofs, not code to merge, but they pin every constant and signature P1 needs.

**Chain works.** fd via reflection on `sun.nio.ch.SocketChannelImpl.fd` →
`getsockopt(SOL_LOCAL=0, LOCAL_PEERTOKEN=0x006)` → `rc=0`, 32 bytes, and word 5 of the token equals
the peer's pid (cross-checked against `LOCAL_PEERPID`) → `SecCodeCopyGuestWithAttributes` with
`kSecGuestAttributeAudit` → `rc=0` → `SecCodeCopySigningInformation` → identifier →
`SecCodeCheckValidity`.

**And it discriminates.** Two C binaries, both `codesign -i "app.tenshu.drive"`, both reporting
`Identifier=app.tenshu.drive`; one ad-hoc, one signed with the real Apple Development identity:

| peer | `identifier "app.tenshu.drive"` | `anchor apple generic and certificate leaf[subject.OU] = "FAYT82GWD7" and identifier "app.tenshu.drive"` |
|---|---|---|
| ad-hoc imposter | **PASS** | **FAIL** (OSStatus −67050, `errSecCSReqFailed`) |
| genuinely signed | PASS | **PASS** |

The left column is §4.1's bypass reproduced *through the gate itself* — a naive identifier check
admits the imposter. The right column is D2, and it is the only column that separates the two.

**Constants pinned:** `SOL_LOCAL = 0`, `LOCAL_PEERTOKEN = 0x006`, `LOCAL_PEERPID = 0x002`,
`audit_token_t` = 8 × `uint32` = 32 bytes, `kCFStringEncodingUTF8 = 0x08000100`,
`kSecCodeInfoIdentifier` = the CFString `"identifier"`. `kSecGuestAttributeAudit` is a
`CFStringRef` **global** — dereference the symbol address; `kCFTypeDictionary{Key,Value}CallBacks`
are structs — pass the symbol address itself.

**Flags are mandatory, not cosmetic:** without `--add-opens java.base/sun.nio.ch=ALL-UNNAMED` the fd
extraction dies with `InaccessibleObjectException` (verified — it is a hard failure, not a warning).
`--enable-native-access` is only a warning on 25 but is documented to become an error.


## 7. Open questions

- ~~**Q1.**~~ **ANSWERED — same verifier, but land it in P3, not P1.** The tray agent ships *inside*
  `Control.app` (`Contents/Helpers/tray-agent`), so P0 gives it the same Team ID for free and the
  identical requirement admits it. It is not exempt: the bridge is a filesystem socket like any
  other, and "only the tray connects" is an assumption about who *does* connect, not a constraint
  on who *can*. But it carries no `AppIdentityResolver` path and no app registration, so it gains
  nothing from P1/P2's observe-and-consume steps — its only meaningful state is
  admitted-vs-rejected, which is P3.
- ~~**Q2.**~~ **ANSWERED — FFM, in `:macos-helpers`, and the dilemma dissolves.** The question
  assumed the only two homes were a helper process or `:core-common`. There is a third:
  `:macos-helpers` is JVM-only, Compose-free, depends on nothing but the stdlib, and already
  compiles against JDK 25 while emitting JVM-17 bytecode — precisely so FFM callers can live there
  without the 17-target world knowing. So we get in-process FFM (no per-connection spawn on the hot
  accept path) **and** `:core-common` keeps its toolchain, its Android target and its iOS target
  untouched. `:core-common` keeps only the `PeerCredentialVerifier` interface; the implementation is
  injected at the one production construction site (`control-desktop/.../Main.kt:58`), which is also
  what keeps `PhaseL2CancellationTest`'s in-process `UnixSocketServer` working unchanged.
- ~~**Q3.**~~ **ANSWERED (§4.1): both, checked as a requirement, never as a string compare.** Bundle
  id alone is self-asserted — proven locally by ad-hoc signing a throwaway binary as
  `app.tenshu.drive`. The requirement must pin `anchor apple generic` + `certificate
  leaf[subject.OU] = <TEAM_ID>` *and* the identifier; only the Team ID portion is unforgeable
  without our signing key.
- ~~**Q4.**~~ **ANSWERED — both, and they are not alternatives.** The *decision* lives in
  `PeerCredentialPolicy` (it is already the policy object, and that keeps it unit-testable); the
  *value* is sourced from the existing `-Dtenshu.release` build-type jvmArg rather than a runtime
  setting, so it cannot be enabled in a release build. Testability and hard-to-enable-by-accident
  were never in tension — they apply to different halves.
- ~~**Q5.**~~ **ANSWERED — yes, per connection, from P1.** It is the whole deliverable of an
  observe-only phase: P2 is only safe once the log shows every real app resolving correctly. ⚠ Note
  the gate is deliberately quiet today (`PeerCredentialPolicy` suppresses the non-strict path "to
  avoid per-call spam", and every bridge call is a fresh connection), so P1 must add logging that
  the *binary* UDS path reaches without making the JSON bridge chatty — they share the gate but not
  their connection rates.

## 8. Threat model

Touches **A14 / A20** (desktop IPC peer authentication) and closes **T-C-15**'s residual risk. Per
House Rule #2, the amendment lands with P3 — the phase where the gate actually rejects. P1 is pure
observability and owes no amendment beyond noting that `appId` is now populated.

⚠ **P2 is the nuance.** It changes no *stated* invariant — the gate still admits every peer, so the
A14/T-C-15 text stays accurate — but it does end the grant inheritance in §2 item 2, which is a real
posture improvement landing a phase before the amendment that describes it. Note it in the P2 PR
description rather than letting P3's amendment silently take credit for it.

## 9. Related

- `core-common/src/desktopMain/.../transport/PeerCredentialGate.kt` — the seam, already wired.
- `core-common/src/desktopMain/.../transport/UnixSocketServer.kt:64` — the hardcode.
- `control/src/main/java/.../server/ProxyService.kt:212` — Android's OS-attested equivalent.
- `control-shared-logic/src/commonMain/.../BundledAppIdentityResolver.kt` — the allowlist, and its
  KDoc predicting the verifier-backed replacement.
- `core-common/src/commonMain/.../app/proxy/AppRegistrationTrigger.kt` — the consumer.
- `docs/protocol/desktop-ipc.md` §3 — the transport contract.

## 10. Apple-re-signed store copies — the second admission route (2026-11-05)

**The defect.** Apple re-signs every Mac App Store and TestFlight build. Measured on a TestFlight
install of Control (`/Applications/TenshuControl 2.app`, `app.tenshu.control.ios`, build
202611041720): `Authority=TestFlight Beta Distribution`, leaf subject `UID=TESTFLIGHT,
CN=TestFlight Beta Distribution, OU=TESTFLIGHT, O=Apple Inc.`, `TeamIdentifier=752LWAZMKZ`. The leaf
belongs to Apple, so `certificate leaf[subject.OU] = "752LWAZMKZ"` fails, and Control would refuse
Drive, the tray agent and the File Provider in every store install. Mac App Store production builds
are signed by *Apple Mac OS Application Signing* and fail the same way.

**The fix (`MacPeerAttestation.decide`).** A peer is attested when either route passes:

1. **We signed it** (Developer ID / Apple Development), unchanged:
   `anchor apple generic and certificate leaf[subject.OU] = "<TEAM>" and identifier "<hint>"`.
2. **Apple re-signed it for the store**:
   `anchor apple generic and (certificate leaf[field.1.2.840.113635.100.6.1.9] exists or certificate
   leaf[field.1.2.840.113635.100.6.1.25.1] exists) and identifier "<hint>"` (the Mac App Store and
   TestFlight markers), **and** `kSecCodeInfoTeamIdentifier` from `SecCodeCopySigningInformation`
   (requested with `kSecCSSigningInformation`; with flags 0 the key is absent) is one of the teams
   the policy accepts for that identifier.

⚠⚠ **The team check is the whole of route 2's security.** The requirement language has no team
clause (`csreq` rejects `teamid = …`), and the marker clause alone is satisfied by every store app on
the machine. On the maintainer's Mac, 20 unrelated apps (WhatsApp, OneDrive, Pixelmator, Apple's own
iWork) pass it. Apple registers only the main bundle id, so the identifiers of nested code
(`tray-agent`) are free: another developer's store app could ship a helper signed as `tray-agent`
and would pass "marker + identifier". The team identifier sits in the CodeDirectory that Apple's
signature covers, so for code that passed route 2's Apple-anchored check it is Apple's statement,
not the peer's. It is read only *after* that check passes; an ad-hoc binary never gets that far.

The identifier allowlist (`FirstPartyDesktopPeers`, `BundledAppCatalogue`) is unchanged. Route 2
admits only identifiers the policy already vouches for, under the same team set.

**Residual: two reads, not one.** Route 1 is decided inside a single `SecCodeCheckValidity`. Route 2
is that check followed by a signing-information read on the same `SecCodeRef`. `SecCodeCheckValidity`
on a guest fails if the on-disk code no longer matches the running image, but the read comes after
it. In principle a peer that is itself an Apple-store-signed binary could swap its on-disk bundle
for a copy of one of ours between the two calls. To try this, an attacker would have to get a store
app through Apple review (signed under their own team, which identifies them) and win a race with
the attestation call. Accepted, and recorded at threat model A14.

**Verified 2026-11-05:**

| bundle | store requirement (route 2) | team-OU requirement (route 1) | signed team |
|---|---|---|---|
| TestFlight Control, main binary | pass | **fail** (the defect) | `752LWAZMKZ` → admitted |
| TestFlight Control, `Contents/Helpers/tray-agent` | pass | fail | `752LWAZMKZ` → admitted |
| `/System/Applications/Calculator.app` | fail | fail | rejected |
| 20 third-party store apps (marker only) | pass | fail | foreign team → rejected |

Checked by hand with `codesign -v -R=…` (every row). `MacPeerAttestationDecisionTest` pins the
decision table with fakes. It also runs the same strings through `codesign -R` against the
TestFlight Control, its `tray-agent` and Calculator. These are on-disk checks, not a running guest,
and they ⚠ **self-skip** on any Mac without that TestFlight install, CI included. There, only the
pinned-string test guards the OIDs. Another test,
`MacPeerAttestationTest.signedTeamIdentifierIsReadFromTheRunningPeer`, proves that the native team
read returns the team for a really signed running peer and nothing for an ad-hoc one, and fails if
the `kSecCSSigningInformation` flag is dropped. ⚠ **Not yet seen end to end:** a store-installed
Control admitting a store-installed Drive over the real socket. That needs a TestFlight build
containing this change.

## 11. The client direction: every app checks Control (2026-11-06)

**Why.** §1–§10 authenticate the *client*: Control knows which app is on the other end. Nothing
authenticated the *server*. A client connected to whatever held `ipc.sock` and sent it every
request, so a substituted Control was served by every genuine app on the Mac. This mattered once the
`.dmg` went public (threat model T-MD-01): a replaced `TenshuControl` download, signed and notarized
under someone else's team, passes Gatekeeper. Android always checked both directions (T-C-20).

**As built.**
- `UnixSocketTaskTransport` takes a `serverVerifier: PeerCredentialVerifier?`. After `connect()` and
  **before a byte is written**, it runs the verifier through the same `admit()` and
  `PeerCredentialPolicy(strict = true)` as the server. A refusal closes the socket and counts as a
  failed connect, so the reconnect loop keeps trying and the app shows "Control not running". One log
  line per distinct reason.
- `ControlServerCheck` (`:ui-core`, desktop) is the verifier every app's DI uses
  (`ControlServerCheck.transport(get())` in each `*PlatformClientModule`). It is
  `MacPeerAttestation.attest` with a policy that vouches for **one** identifier,
  `app.tenshu.control.ios`, by the teams compiled into `ClientPeerTrust` (`generateClientPeerTrust`,
  from `tenshu.teamId`, as `generatePeerTrustAnchor` does for Control). Both routes apply: our
  Developer ID or development signature (route 1) and a store copy re-signed by Apple (route 2, §10).
- `LOCAL_PEERTOKEN` on a connected **client** socket names the process that called `listen()`.
  `MacPeerAttestationTest.genuinelySignedServerIsAttestedFromTheClientSide` and
  `adHocServerClaimingControlIsRejectedFromTheClientSide` prove it with signed C servers.
- Developer mode (A16): each desktop app's dev `run` task now sets `-Dtenshu.release=false`, as
  Control's does. A dev run admits an unverified server with a warning, so it still reaches a
  dev-run Control, which is an unsigned `java` process. ⚠⚠ The flag counts only outside a packaged
  app: `JAVA_TOOL_OPTIONS` can inject any `-D` into a packaged JVM, but not remove the
  `jpackage.app-path` its launcher sets, so both sides (`ControlServerCheck.developerMode` and
  Control's `Main.kt`) enforce whenever that property is present.
- ⚠⚠ **Every desktop app passes `PEER_GATE_JVM_ARGS` at both JVM sites** (`run` and packaged): the two
  `--add-opens` `PeerAuditToken` needs, and `--enable-native-access`. Without the opens every
  attestation is `Unavailable` and a packaged app refuses the genuine Control, while dev runs
  (developer mode) and every unit test (whose task has the opens) stay green.
- ⚠⚠ **Every desktop app bundles JDK 25** (`FFM_JDK`, `javaHome`). Seven apps bundled the Gradle
  daemon's JDK (21), where `Arena.allocateFrom` does not exist: every attestation was a
  `NoSuchMethodError`. `ControlServerCheckTest.everyDesktopAppPassesThePeerGateJvmArgsAtBothSites`
  holds all ten build files to both rules.

**What changes for developers.** A *packaged* client no longer talks to a *dev-run* Control:
`./gradlew :control-desktop:run` is a `java` process, which fails the check. Run both from Gradle, or
both packaged (`scripts/reinstall-all-apps.sh`).

**Not covered.** The Swift peers of `bridge.sock` (the tray agent and the File Provider extension)
still trust the socket path. Both live inside Control's and Drive's own bundles, and replacing them
means replacing a signed bundle the user already trusts, but they are the remaining one-directional
links.

**Verified end to end (2026-11-06), packaged and signed builds from this branch, on scratch
`TENSHU_IPC_DIR`/`TENSHU_DATA_DIR`:** Control attested Photos (`peer attested: app.tenshu.photos.ios`)
and Photos, enforcing, connected to it; Photos pointed at a Python listener on the same path refused
it 40 times (`no team may vouch for claimed identifier 'python.exe'`) and the listener received **0
bytes**. The first packaged run found both ⚠⚠ items above; no unit test could have. ⚠ Not seen: a
**store** Control admitted by a client. The store build installed on the test Mac (202611051609)
predates C4 and refuses every `.ios` client itself, so the client check never ran against it.
