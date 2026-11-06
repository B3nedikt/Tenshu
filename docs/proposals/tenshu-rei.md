# Tenshu Rei — Bootstrap Scope, Permission Model, Shared Realms, and Public-vs-Private Records

**Status:** Sketch. Four design decisions locked. First-phase (Phase 0 + Phase 1)
open questions resolved 2026-05-29 — see the **Decided (2026-05-29)** notes on Q1, Q2,
Q3, Q4, Q5, Q43, Q45 below. The shared-realm cluster (§5.C, Q22–Q32) was also worked
through 2026-05-29 — all twelve now carry Decided notes, and two new questions surfaced
during that pass: Q52 (verifiable users / trust badges on sharing) and Q53 (invite
mechanisms, access-management roles, Contacts association), both deferred. Remaining
items (the §5.B enforcement details beyond what's decided, §5.D public-records, §5.F
desktop) still open.

**Scope:** New module `:rei-service` (+ `:rei-service-common`, `:rei-service-client`,
`:rei-service-proxy`, `:rei-service-test-utils`) following the established feature-service
split. Touches `:control-shared` (consumer for permission UI + bundled-Rei reader), Drive
manifests (first-party publication artifacts), `:identity-service` (encryption sub-key on
realm identity, needed for shared realms), `:sync-service` (shared realm wire shape), the
schema DSL (new `requiresUserGesture` / capability-class tags on Operations), and
`docs/sync/threat-model.md` (new assets: shared-realm master key, Rei trust anchor).

This doc captures the four design decisions we've taken on Tenshu Rei so far, the
refinements that fell out of them, and the longer list of open questions and traps that
need to be resolved before we can land anything. It is **not** an implementation plan —
the implementation plan comes after the open questions are answered.

The whitepaper introduces Rei as a six-layer distributed-ledger protocol family that
provides a verifiable chain of trust for app publications and a substrate for cross-store
interoperability. The four decisions below are about how we *start* implementing Rei in a
way that ships value before any actual ledger exists, and that doesn't paint us into a
corner once the ledger does.

---

## 1. Motivation

### 1.1 Rei needs to be useful before the ledger exists

The whitepaper describes Rei as a distributed ledger, but the first Tenshu apps — Drive,
Control, eventually Mia — need their chain-of-trust verification to work *before* any
ledger is operational. If Rei verification is gated on the ledger, Tenshu can't ship a
v1; if it's skipped for v1, the security model has a hole that's hard to close later.

We need a bootstrap shape where the first apps are verifiable through Rei's *artifact*
machinery (publications, issues, evaluations, signature chains, trust anchors) without
depending on a deployed ledger to fetch those artifacts.

### 1.2 "Semantic permissions" needs a concrete encoding

The whitepaper introduces semantic permissions as the user-visible permission model — "read
the user's file system and then return the file matching a given name." This is the
shape, not the encoding. Today there's no schema-level mechanism that lets Control display
an app's permission set, no enforcement boundary that ties a user's grant to a specific
proxy call, and no story for how an app's manifest expresses its permissions.

The platform's IDL already has the raw material — `Service` × `Operation` pairs, typed
params, `@RealmData`-tagged entities. The question is whether the (Service, Operation)
tuple is *itself* the permission unit, or whether we need a separate permission ontology
sitting on top.

### 1.3 File sharing requires multi-realm decryption

The Drive roadmap includes shared folders — files visible and editable across realms
owned by different users. The current realm model assumes one master identity decrypts
all the data the realm holds. Sharing needs a second realm to decrypt the same file
without giving it the host realm's master key.

This is solvable, but the failure modes (revocation, forward secrecy, membership churn,
attribution) are well-documented in the cryptographic literature and we should pick our
trade-offs deliberately.

### 1.4 The ledger surface should be the minimum that requires it

Once a ledger is operational, the temptation is to put everything on it — every
publication, every grant, every entitlement, every share. This is wrong: each public
record is permanent, unencryptable surface that everyone in the system sees. The
whitepaper's Minimal Information principle points the other way. We need an explicit
criterion for what *requires* publicness, and a default of "private unless proven
otherwise."

---

## 2. Design Decisions

These four are locked. Everything in §3 follows from them.

### 2.1 Rei is a read interface with multiple backends; the first backend is bundled

Control treats Rei as a read-only data source behind a `ReiSource` interface. The first
implementation, `BundledReiSource`, reads Rei artifacts from resources baked into the
Control binary. The eventual ledger-backed implementation, `OnlineReiSource`, reads from
the deployed ledger (or a shard / external service per whitepaper §Tenshu Rei). Both
implementations return identical artifact shapes — publications, issues, evaluations,
signatures — so the verifier code runs over either source without branching.

Bundled artifacts are byte-identical to what `OnlineReiSource` would return. This means
"bundled" is a *delivery channel*, not a *format*: the same chain-of-trust math runs end
to end whether the artifact was loaded from disk or fetched from a node.

The Tenshu root developer identity's public key is compiled into Control's binary. This
is the only key whose authenticity is bootstrapped by the app-distribution channel (Play
Store signing, Apple notarization, F-Droid reproducible build, our own signed release
binaries). Everything else verifies against it.

`BundledReiSource` stays authoritative for first-party apps even after the ledger is
operational. We don't migrate Drive, Control, or Mia onto the ledger — we keep their
verification self-contained so Tenshu remains usable if the ledger is unreachable.

### 2.2 Permissions are derived from the IDL but layered with capability classes and gesture requirements

The (Service, Operation) tuple an app invokes is the *baseline* permission unit. This
populates the manifest visible to the user in Control. "Read files" = permission to
invoke `FileSystem.list`; "delete files" = permission to invoke `FileSystem.delete`.

The permission set is derived from a static scan of the app, with the scan input
depending on the publisher's trust relationship:

- **First-party apps** scan source-time via a KSP processor / Kotlin compiler plugin in
  the trusted Tenshu build pipeline. The resulting manifest is signed under the Tenshu
  root developer identity — the same identity §2.1's bundled artifacts trust-chain back
  to — so no independent evaluator is in the loop and source-time is appropriate
  (faster, richer type info than scanning the built artifact).
- **Third-party developers** scan their built artifact (KMP bytecode, JavaScript, or
  WebAssembly). The App Console runs the scan on the developer's uploaded build so the
  developer doesn't have to ship source; the developer signs the resulting manifest
  under their own identity.
- **REL evaluators** independently re-run the artifact-side scan against the *published*
  binary to confirm the signed manifest matches what the code actually calls. Evaluators
  never see source — only the publication channel's artifact — so the evaluator tool
  must operate end-to-end on that.

The artifact-side scan anchors on **stable Tenshu platform symbols** (or, on web,
string-keyed op identifiers at the SDK call boundary), not on the app's own identifiers.
Standard obfuscation (R8, ProGuard, JS minification) renames symbols inside the app's
compilation unit but does not rename external platform references or string literals, so
call-site detection survives obfuscation in the common case. The scan additionally reads
the **platform manifest** (`AndroidManifest.xml`, iOS `Info.plist`, web app manifest),
which is unobfuscated by definition and independent of the Tenshu API surface. A Tenshu
app is supposed to reach user data only through the proxy; any platform-level capability
that lets it bypass the proxy (INTERNET, raw filesystem, keychain, app groups) is
therefore either a misconfiguration or hostile, and is surfaced as a red flag the
developer must justify before the manifest is acceptable.

Two manifest subcommands fall out of this, dispatched by a `tenshu` umbrella CLI
following the git-plugin pattern: each subcommand is a separately-installable binary on
`PATH` (`tenshu-manifest-get`, `tenshu-manifest-verify`) that the `tenshu` shim finds and
invokes. Evaluators don't pay disk-space or supply-chain cost for the developer-side
toolchain, developers don't ship the evaluator harness to end users, and either
subcommand can rev independently:

- `tenshu manifest get` — developer-side. KSP / compiler plugin for first-party builds,
  an artifact-scan service in the App Console for third-party. Produces the unsigned
  manifest the developer signs.
- `tenshu manifest verify` — evaluator-side. Takes `(published_artifact,
  signed_manifest)`, re-runs the artifact + platform-manifest scan, diffs against the
  signed manifest, and reports discrepancies plus platform red flags. Output is
  structured input for a human REL evaluator, not a pass/fail verdict: behavioural
  judgement (does the app's purpose justify its permissions, are there exfiltration
  patterns inside the authorised surface, is the developer identity credible) is human
  work.

Both subcommands link against a shared scanner library — per-platform manifest readers
plus per-platform call-site scanners for KMP bytecode, JS, and WASM — so the actual
scanning logic isn't duplicated even though the binaries are separate. The web scans are
easier than they sound: JS minifiers don't rename string literals, so SDK call sites
keyed on string op-names survive minification, and WASM modules expose their imports
table as strings in the module header so the host-API surface is visible without
disassembling internals.

Static scanning has known blind spots that apply uniformly across the three runtimes:
reflection, JNI / native code, dynamically loaded code, runtime-constructed op names,
and the Chain DSL (where ops are encoded as data, not method calls). The policy for an
op invoked through a path the static scan cannot see is uniform — the proxy rejects it,
or the app must hold an ambient grant covering the full possible surface. The app cannot
quietly invoke ops the manifest doesn't cover. See §5.B.14 for the unresolved
discussion.

Each `Operation` carries two new schema-level tags:

- **Capability class:** `Ambient` (read-broad, fine to grant once), `Picker` (write or
  destructive, default-grant per-invocation through a system-mediated picker in Control's
  UI), `Scoped` (ambient but constrained by an entity filter — "only files in /Photos").
- **Gesture requirement:** `none` / `requires_user_gesture` / `requires_explicit_confirmation`.
  Enforced at runtime independent of capability class.

These are orthogonal. A destructive op like `FileSystem.delete` is `Picker` *and*
`requires_user_gesture` by default. The first controls how the manifest renders; the
second controls runtime enforcement.

**Encoding (decided 2026-05-29): traits, not enum fields.** Both tags are modelled as
the platform's existing presence/absence traits (`IoOperation` / `TerminalOperation`
style), read by absence-defaulting accessors — `hasTrait` already walks the parent chain
and accessors like `isIo()` return the default when the trait is absent. Consequences:

- The safe defaults (`Picker`, `requires_user_gesture`) are the **absence** behaviour in
  the accessor; they are *not* stored on any schema. Only non-default values carry a
  trait: `AmbientCapability` / `ScopedCapability` for capability class, `GestureExempt`
  (none) / `RequiresExplicitConfirmation` for gesture. Ops opt *down* per-op; existing
  ops declare nothing and read back as `Picker` + `requires_user_gesture`. No
  service-wide migration (this supersedes Q43's field-with-default framing).
- The default must stay in the accessor, **not** as a trait on `Operation.Schema` or any
  shared intermediate op schema. Trait inheritance is monotonic — presence flows down and
  cannot be removed by a child — so a Picker trait on the root would make opt-down to
  Ambient impossible.
- Traits don't enforce mutual exclusivity the way an enum field would, so capability
  class needs an invariant test (extend `OperationSchemaInvariantTest`) asserting at most
  one capability-class trait per op.

> **Status (read/grant path landed — `control-app-registration.md`, Track A A0).** The
> capability-class + gesture-requirement *model* now exists as a descriptor Control consumes:
> `CapabilityClass {Ambient, Picker, Scoped}` and
> `GestureRequirement {None, RequiresUserGesture, RequiresExplicitConfirmation}` are concrete
> types in `:core` (`org.tenshu.core.app.AppManifest`), with the **safe defaults** baked in as
> the descriptor's field defaults (`Picker` + `RequiresUserGesture` when a permission declares
> neither). What is *not* yet built is the **source-time scan** (KSP / compiler plugin) that
> would populate these from the real IDL traits described above — v1 reads them from the
> bundled `AppManifest` (`AppManifestSource`), the `ReiSource`-shaped seam the ledger-backed
> scanner will later replace. The trait encoding + the `OperationSchemaInvariantTest` remain
> the target end-state for the scan phase.

The **primary handler** concept resolves the "file managers can't prompt on every delete"
problem without privileging first-party apps. The realm has exactly one primary handler
per data type (one file manager, one photos app, one contacts app). The primary handler
gets ambient access to `Picker`-class operations on its data type. Drive ships as the
default primary handler for files, but the user can swap it for a competing file manager
in Control; the new primary inherits the grant. Even the primary handler is bound by
`requires_user_gesture` — a compromised primary can't delete in the background.

Scoped access (per-File, per-Folder, per-Device, per-relation) composes via the existing
Chain DSL: a scope grant is a `.search { ... }` clause the proxy injects before the
operation runs. This is enforced server-side at the proxy; client-side filters don't
count.

### 2.3 Shared realms are modelled as their own realms, identity gains an encryption sub-key

A shared folder is a *shared realm* with its own master key. Member realms hold the shared
realm's master key wrapped under their own realm identity. Wrapping happens once at
realm-master-key level — never per file. The existing realm machinery (sync, recovery
manifest, key rotation) operates uniformly on shared realms; they're not a special case.

The current realm identity is a signing key pair (Ed25519). For wrapping, the identity
gains an encryption sub-key (X25519). Both keys are members of the same identity;
recovery, pairing, and revocation cover them together.

Writes to a shared realm are signed under the **member's own realm identity**, not the
shared realm's. The shared realm key provides confidentiality between the group and the
relay; the member identity provides attribution among the group. This avoids a
confused-deputy problem where any member could fake writes from another.

For low-churn shares (the 2–10 person folder case), simple "wrapped master key per member,
rotate on remove" is sufficient. TreeKEM / MLS is deferred until we actually have shares
with high enough churn to need it (likely never for v1).

### 2.4 Public records require cross-party verification without prior trust; everything else is realm-private

The criterion for "must be public" is: *two parties without a prior trust relationship
need to verify the same fact*. That is the only thing the public ledger uniquely solves.
Anything that can flow through an existing trust path — a pairing, a shared realm, a
direct identity-to-identity channel — goes private.

The taxonomy this lands:

- **Public required:** third-party app publications and their evaluation chains (RPL/RIL/REL
  for unknown developers); resellable digital items where the resale market needs
  verifiability between strangers; on-chain payment proofs and entitlement-redemption
  records for realm-storage purchases.
- **Realm-private:** first-party app manifests (delivered via §2.1's bundled source);
  permission grants (always local to the user's realm); inter-realm shares (the two
  realms are already paired or share-related); non-resellable per-user entitlements.

Resellability is **opt-in at purchase time**. Buying an app produces a private receipt by
default; only "publish for resale" creates the public record. This minimises the public
surface for the common case while keeping higher-layer functionality available.

The public/private boundary is enforced at the **App Console** layer (where developers
declare publish-or-don't), not at the proxy layer. Once a record is public it's public
forever, so the enforcement point has to be where records are created.

**How this surfaces in Control.** The `RealmData` metamodel marker
([`core-realm-data.md`](core-realm-data.md)) is the mechanism by which both first-party
(realm-private) and eventual third-party (public, ledger-verified) *data types* surface
uniformly in Control's Data section: a type declares the marker, `SchemaProvider.realmDataTypeIds()`
enumerates it, and the section renders a card with zero per-type code. The verification
boundary above changes only the **source** of a type's declaration (bundled/local for
private; ledger-published for public) — not the Data UI, which is a generic walker either
way. v1 enumerates realm-private types only (`File`); a future ledger-published type would
join the same list once Rei is operational and the manifest path exists.

---

## 3. What Falls Out

### 3.1 The `:rei-service` module shape

`:rei-service` joins `:identity-service` / `:file-service` / `:notification-service` /
`:sync-service` as the fifth feature service. Same five-way split:

- `:rei-service` — public composite schemas (`Publication`, `Issue`, `Evaluation`,
  `TrustAnchor`, the artifact shapes from whitepaper §Rei).
- `:rei-service-common` — KMP-portable verifier (signature chain walking, trust-anchor
  pinning), `ReiSource` interface, `BundledReiSource` implementation.
- `:rei-service-client` — typed operation surface for consumers (Control, future App
  Console).
- `:rei-service-proxy` — proxy-side execution, where the verifier actually runs and
  where the `OnlineReiSource` (when it exists) is wired.
- `:rei-service-test-utils` — fixture publications signed under a test trust anchor.

The `Operation` capability-class and gesture-requirement tags from §2.2 are additions to
`:core`'s schema DSL — not Rei-specific, but described here because Rei is what surfaces
them to the user.

> **Apps UI status (Track A A0, `control-app-registration.md`).** Ahead of the full
> `:rei-service` + verifier, the synced app data and its operations landed in `:core`
> (`org.tenshu.core.app`): `AppRegistration` (per-`(appId, deviceId)`, `Syncable`),
> `AppGrant` (realm-wide permission rows, `Syncable`), the `AppService` surface with
> `appRegistrations` / `appGrants` relations, and the `RegisterApp` / `RemoveApp` /
> `SetAppGrant` operations. The permission descriptor is read through `AppManifestSource` —
> the `ReiSource`-shaped seam the ledger implementation will drop into. So Control's Apps +
> permissions surface can ship on real synced data without `:rei-service` existing yet; when
> it does, `AppRegistration` / `AppGrant` can move there (`control-app-registration.md` §7).

### 3.2 Manifest format

A first-party app's manifest is the same artifact a third-party app would publish to the
RPL. It is built from:

- The app's identifier and version.
- The derived semantic-permission set: a flat list of `(serviceId, operationId,
  capabilityClass, gestureRequirement)` tuples, produced by the scan described in §2.2
  (source-time for first-party, artifact-time for third-party — KMP bytecode, JS, or
  WASM per the app's runtime).
- Any developer-declared scope hints (default scope, default primary-handler claim if
  the app is offering itself as a file manager / photos app / etc.).
- Code-signing data (the app's signing certificate or equivalent platform-specific
  attestation material).
- The developer identity's signature over all of the above.

For first-party apps, the manifest is generated by the Drive/Control/Mia build pipeline,
signed under the Tenshu root developer identity, and dropped into Control's
`resources/rei/bundled/` directory along with the matching RIL issues and REL evaluations.
For third-party apps (post-ledger), the manifest is generated by the App Console and
published to the RPL.

### 3.3 The permission widget in Control

Control's permission UI groups by capability class:

- *Background access* — `Ambient` operations. Default-grant on install (the user is
  shown what's being granted but isn't asked per-grant).
- *On-demand actions* — `Picker` operations. The user sees them in the manifest but they
  don't take effect until the app actually requests an operation; Control then shows the
  system picker.
- *Scoped data* — `Scoped` operations. The user sees a "limit to" affordance — pick a
  folder, a device, a relation — and the proxy injects that filter.

Gesture-requirement is rendered as a small chip on the operation row (`needs your tap`,
`needs explicit confirmation`) so the user understands the runtime check.

> **Status (grant storage + write path landed — Track A A0).** Each permission row carries a
> **tri-state grant control** — Granted / Denied / Ask each time — alongside its human label,
> scope (for `Scoped` ops), and last-used timestamp. A **Revoke all permissions** action
> resets every grant for the app, and each row cross-links to the consuming service
> (`/control/services/:serviceId`). Grants are stored as realm-wide `Syncable` `AppGrant`
> rows (one per `(appId, serviceId, operationId)`); the user's decision is written by
> `SetAppGrant` and replicates across the realm like any other entity. The Control + web
> rendering of this widget is Track A A2 / A3; the schema + write path is A0.

### 3.4 Shared realm wire shape

A shared realm has the same shape as a personal realm at the sync layer — it has its own
realm id, its own master key, its own change stream, its own recovery manifest entries.
Differences are at the membership layer:

- Each member's `IdentityService` holds a `SharedRealmMembership` record:
  `(sharedRealmId, sharedRealmMasterKeyWrappedToMyIdentity, role)`.
- Membership-change operations (add, remove, transfer ownership) produce a
  `MembershipChange` sync block on the shared realm's stream, plus a key-rotation block
  in the same logical transaction.
- A member's recovery manifest (per whitepaper §Recovery) opt-includes the wrapped key
  for each shared realm they're in.

The content-blob channel ([ADR 0017](../adr/0017-file-content-two-plane-channel.md)) is
already realm-scoped; shared realms reuse it unchanged. The relay sees ciphertext-only either way.

### 3.5 Public-vs-private at the artifact level

`Publication`, `Issue`, and `Evaluation` schemas don't have a "public flag" — their
location is the distinction. The artifacts in `resources/rei/bundled/` are private (they
ship in the app binary, not on the ledger); identical artifacts created by the App Console
for ledger submission go public. This is a deployment-time decision, not a schema-time
one, so the verifier is uniform.

For the resellability case: a private receipt is a `Publication` of kind `EntitlementReceipt`
that flows from the seller's realm to the buyer's realm through a paired-realm channel.
Choosing to publish for resale converts it into a public RPL publication; the conversion
is one-way (you can't un-publish).

### 3.6 Desktop deployment shape

Control on desktop runs as a user-session background service, autostarted at OS login
(LaunchAgent on macOS, XDG autostart on Linux, Run-key on Windows), present in the
system tray / menu bar as a persistent icon. Clicking the icon opens a small status
popup — current sync state, up to 4 most recent notifications from
`:notification-service`, an "Open Control" button that brings up the full Control UI,
and an "App Launcher" button that lists installed Tenshu apps. The popup is
deliberately narrow: it surfaces the newest notifications without filtering, and the
full notification history lives in Control rather than in a richer tray UI. Routing
on a clicked notification depends on the notification kind — `FileCategory.Image`
notifications open the image in Drive as the first wired routing target, with other
notification kinds (file-shared, sync warning, pairing prompt, low-storage) gaining
routing handlers later as observed-use patterns justify them rather than landing
speculatively. Main-window close bounces back to the tray rather than quitting the
daemon (the OneDrive / Dropbox pattern, configurable from Control's settings if a
user wants different behaviour). Compose Multiplatform's built-in `Tray` is
AWT-`SystemTray`-backed — menu-only and unable to host the status popup described
above — so the tray binding is platform-specific rather than a single
cross-platform dependency.

**Decided (2026-06-02): three code paths, no single cross-platform tray library.**
Ordered by effort: macOS first via `NSStatusItem` / `NSPopover`, then Windows via
`Shell_NotifyIcon` — both clean, stable, decades-old APIs we bind directly — and
finally Linux via DorkBox SystemTray. The two primary platforms are bound directly
precisely so their tray is never coupled to a third-party library's release cadence
when the OS API shifts. Linux is the exception: it has no clean, stable tray API
(GTK `StatusIcon` is deprecated; the modern path is StatusNotifierItem over D-Bus
with per-desktop fragmentation — GNOME needs an extension to show tray icons at
all), which is exactly the mess DorkBox abstracts, so the library dependency earns
its place on the least-used desktop platform. On macOS and Windows the status item
is preferably a small native agent that talks to the daemon over the Unix-socket /
named-pipe IPC channel (below) rather than in-process JNA, since `NSStatusItem`
must run on the AppKit main thread the JVM/Skiko process already owns — a separate
agent gets its own clean run loop and is structurally just another IPC client.
Open: the macOS agent's language (Swift, consistent with other native macOS
surfaces, vs. Kotlin/Native, which can share the KMP IPC client and notification
types).

Distribution is sandbox-preferred. First-party apps (Drive, Control, Mia) ship as
Flatpak on Linux, MSIX on Windows, and sandboxed Developer ID or App Store on macOS;
the platform-native sandbox manifest (Flatpak manifest, `AppxManifest.xml`, sandboxed
`Info.plist` + entitlements) is what §2.2's scanner reads as the platform-manifest
backstop. Third-party apps are sandbox-required by default with case-by-case manual
exceptions during REL evaluation for specific technical needs that genuinely cannot
fit the sandbox model — kernel-level anti-cheat, low-latency audio with custom
drivers, raw USB / MIDI / capture-device interaction, legacy graphics-driver
requirements, or a "custom" category with a developer-supplied justification for
app-specific needs outside that list. The exception is on the technical need, not on
the app category (modern games typically *can* sandbox via XDG portals; Steam itself
ships as Flatpak). Review is fully manual either way — automation cannot validate a
claim like "BattlEye kernel anti-cheat required for multiplayer integrity," so the
evaluator reads the justification, cross-references vendor docs or the binary as
appropriate, and signs based on judgment. The exception category and justification
string are baked into the publication so the user sees them on install. Apps that
hold an exception are surfaced as "running without OS sandboxing" in Control's
permission UI, both on install and as a status indicator while they run, and their
REL evaluation requires the additional behavioural-review tier described in §5.F.

The IPC channel between client apps and the Control daemon uses the Unix-socket /
named-pipe transport from [`docs/protocol/desktop-ipc.md`](../protocol/desktop-ipc.md),
extended with peer-credential checks (`SO_PEERCRED` on Linux, `LOCAL_PEERPID` on
macOS, `GetNamedPipeClientProcessId` on Windows) so the `AppId` in the hello
handshake is verified against the connecting process's signed binary identity rather
than trusted as self-declared. A developer-mode toggle in Control's settings,
hidden in production builds behind an Android-style seven-tap reveal on the
build-number row, downgrades the unknown-signature outcome from a hard fail to a
warning so unsigned development builds can connect; while developer mode is active,
Control shows a persistent status indicator so the elevated trust posture is
visible. Debug builds of Control may surface the toggle directly without the reveal.

The handshake authenticates each *connection*; the durable, synced record of *"this
app is installed in this realm"* that the Control Apps tab lists and removes is layered
on top of it in [`control-app-registration.md`](control-app-registration.md) — the
proxy writes an `AppRegistration` on an app's first verified handshake (silent for
default apps; a dev-mode app registers with an "unverified" badge per its A16
downgrade).

The Control master key is persisted via the OS keychain — macOS Keychain and Windows
DPAPI are universally available and required (Control refuses to start if missing);
on Linux, Secret Service (gnome-keyring / KWallet via dbus) is preferred but not
universal, so Control falls back to an Argon2id-derived encrypted file on disk
(OWASP-recommended parameters, no per-device tuning) when no Secret Service is
found. The fallback defaults to a user-chosen passphrase prompted on each Control
start; users who prefer zero-prompt convenience can opt into a machine-bound mode
which derives the file-encryption key from a per-install identifier, trading
offline-disk-read resistance for not having to type a passphrase. A prominent
warning on first run explains the trade-off.

---

## 4. Out of Scope (for this proposal)

- **The ledger itself.** What chain, what consensus, what gas model, what node software —
  all deferred. §2.1 makes the system independent of the answer; §6 collects the relevant
  open questions.
- **Higher Rei layers (4–6).** The whitepaper introduces six layers; this proposal covers
  the bottom three (RPL/RIL/REL) plus the public-vs-private criterion. Cross-store
  interoperability and the digital-item exchange layers are out of scope until the
  first-three story is implemented.
- **App Console.** The console is where developers create publications, issues, and
  publish-for-resale records. It's mentioned here because §2.4 puts enforcement there,
  but its implementation is its own track.
- **iOS External Proxy Connection pattern.** The whitepaper covers it; Rei verification
  in that pattern follows the same artifact shapes but the static-analysis path differs.
  Out of scope until iOS work starts.
- **Mia / Voice App Service.** Voice apps are a real consumer of semantic permissions
  but introduce a delegation problem (Mia invokes other apps on the user's behalf) that
  deserves its own proposal.

---

## 5. Open Questions and Traps

This section is the substance — the user is going to walk through these one at a time.
Each item is a decision point that needs resolution before the corresponding piece of §3
can be implemented. Items are grouped by area, not by priority.

### 5.A — Bundled Rei

1. **Marker for "this is bundled, not on-ledger"?** Should the Control UI distinguish
   apps verified through `BundledReiSource` from apps verified through `OnlineReiSource`?
   The trust math is the same, but a privacy-conscious user might want to know which
   artifacts they could in principle re-verify against a public source. Default
   recommendation: no marker (the chain of trust is what matters), but worth a deliberate
   call.

   **Decided (2026-05-29): no marker.** Trust math is identical regardless of source, and
   in a first-party-only v1 (see Q45) every app is bundled, so a marker carries no
   information until the ledger exists. Revisit if/when `OnlineReiSource` ships.

2. **Bundled-record update channel.** Bundled records can only change via app updates. If
   we discover a vulnerability in a first-party app after release, the artifact updating
   to a "deprecated" state requires shipping a new Control build. Is that acceptable, or
   do we want a "bundled-source can be supplemented at runtime by signed updates from a
   Tenshu update endpoint"? The latter blurs the bundled-vs-online distinction; the
   former leaves us slow to react.

   **Decided (2026-05-29): update-only, fully offline (with Q3).** Bundled records change
   only via Control updates; the v1 verifier needs no network. Preserves the clean
   bundled-vs-online line at the cost of revocation being bounded by release cadence —
   acceptable for a first-party-only v1.

3. **Cross-source revocation.** What happens when a bundled app is later revoked on the
   ledger? §2.1 says bundled stays authoritative for first-party apps, but if the ledger
   says "this version is revoked," do we honour it? Default recommendation: yes, the
   "revoked" status flows from any source to all sources — but this means the bundled
   source has to be supplemented by an online revocation feed even before the rest of the
   ledger ships.

   **Decided (2026-05-29): no online revocation feed in v1 (follows Q2).** Cross-source
   revocation is deferred with the rest of `OnlineReiSource`; for v1 a revoked first-party
   app is handled by shipping a Control update. The "revoked status flows from any source
   to all sources" rule still holds once the online source exists.

4. **Trust anchor rotation.** The Tenshu root developer identity's public key is compiled
   into Control. Rotating it requires shipping a new Control build. Is that acceptable
   long-term? Most root-key systems plan for a rotation path; we should at least know what
   ours would look like.

   **Decided (2026-05-29): single key, rotate via build.** One root public key compiled
   into Control; rotation ships as a new build, consistent with the update-only posture
   (Q2). Accepted residual: emergency root-key compromise is bounded by release cadence,
   and old builds keep trusting the old key. A key-set-with-validity-windows or
   root-signs-intermediate scheme can supersede this later without invalidating Phase 1.

5. **Bundling third-party apps as a transitional measure?** Before the ledger ships, can
   a trusted third-party app (e.g. a community-blessed file manager) be bundled into
   Control's `BundledReiSource`? This trades extensibility for centralisation in the
   transitional period. Default recommendation: no (it makes Anthropic Tenshu Inc. the
   gatekeeper), but worth being explicit.

   **Decided (2026-05-29): no.** `BundledReiSource` stays first-party-only. Third-party
   apps wait for the Console/ledger path; we don't take on the curator role or blur the
   bundled-vs-ledger split during the transition.

### 5.B — Permission model

6. **Detecting "user gesture" reliably across platforms.** Android has foreground app +
   activity-lifecycle signals; desktop has window focus + recent input events; web has
   user-activation tokens. None are perfect. We need a concrete decision tree per
   platform, and a clear behaviour spec for the edge cases (background services that the
   user triggered five seconds ago, system-driven foreground transitions, etc.).

   **Decided (2026-05-29): freshness-checked platform token (Option A), with Q7.** Framing
   that scopes this: `Picker`-class ops are Control-mediated regardless, so the strong
   protection always covers the destructive default path. This decision governs only the
   narrower case of a `requires_user_gesture` op a **primary handler** holds as `Ambient`
   (the in-app delete the primary-handler concept exists to keep prompt-free). For that
   case the app threads the OS user-activation signal through the proxy call and the proxy
   validates **freshness + caller-is-foreground only**: Android — caller UID is top/focused
   app + activation timestamp; desktop — window focus + recent system input + timestamp;
   web — `navigator.userActivation`. This stops fully-background silent abuse but does not
   stop gesture *redirection* (see Q7). Low build cost (per-platform foreground/focus
   adapters + a timestamp field, no new wire shape); defers the capability-token
   machinery (Q12) out of Phase 2.

7. **Gesture-to-context binding.** A malicious app could ask the user to "click anywhere
   to continue" and then use the resulting gesture to authorise a destructive operation
   the user never intended. The gesture needs to be bound to a specific UI context — the
   delete button the user actually pressed, not just "any tap in the last 500ms." How do
   we surface this to apps without giving them the ability to spoof it?

   **Decided (2026-05-29): accepted as residual for the primary-handler case (follows
   Q6/Option A).** Full gesture→intent binding requires either an op+entity capability
   token (Option B) or a Control-rendered trusted overlay (Option C); both were deferred
   in favour of Option A's freshness+foreground gate. The redirection risk ("click
   anywhere to continue" reused for an unintended destructive op) is therefore an accepted
   residual — bounded by the fact that it applies only to a user-chosen, foreground-gated
   **primary handler**, and that all `Picker`-class ops remain Control-mediated. Promote
   to the op+entity token (Option B) when the capability primitive is built for Q12/Q16;
   amend the threat model then.

8. **Conflict between developer-declared and IDL-derived permissions.** A developer's
   manifest declares permissions; the IDL-derived scan also produces permissions. If they
   disagree (developer omitted an op the code calls, or declared one the code never
   touches), what happens? Default recommendation: the scan is authoritative for what the
   user sees, with a discrepancy warning surfaced to the App Console. But: how do we
   handle the case where an evaluator on the REL can't reproduce the same scan result
   (different toolchain, different optimisation level)? This is a real interop problem.

9. **Primary handler — slot definition authority.** Who defines the slots ("file
   manager," "photos app," "contacts app")? The platform owns them (limited extensibility
   but stable), or each data type carries its own slot declaration via `RealmData`
   (extensible but harder to reason about)? Lean: the latter, slots are properties of
   `RealmData` annotations.

   **Decided (2026-05-29): standalone `PrimaryHandlerSlot` annotation — NOT a field on
   `RealmData`** (overrides the original lean; diverges from Q44 — see Q44). A
   realm-resident composite has (at least) three orthogonal facets, each its own small
   composable annotation/trait per the codebase convention (`IoOperation`, `Syncable`,
   …): **sync** (`Syncable` — strategy + conflict, deliberately narrow), **ontology/
   presentation** (`RealmData` — user-visible type, name/icon, inventory + list ops), and
   **handler-ownership** (`PrimaryHandlerSlot` — which app is default, confers the ambient
   grant). Member-sets nest: `{has slot} ⊆ {RealmData} ⊆ {Syncable}` (`FileModification`
   is Syncable but not RealmData; `Device` is RealmData but has no handler slot; `File`
   has all three). Two reasons for a sibling rather than a `RealmData` field: (1)
   `core-realm-data.md` §6 deliberately keeps permissions/enforcement *out* of `RealmData`,
   and a handler slot confers a grant (enforcement-adjacent); (2) `core-realm-data.md` is
   spec'd-but-not-started, so a `RealmData` field would block primary-handler support on
   that proposal landing, whereas a sibling annotation (rei/permission layer, keyed by
   `schemaId`) ships independently.

10. **Primary handler — strict singular, or shared?** Strictly one file manager, or can
    multiple coexist (each with ambient grant)? The strict version is simpler to reason
    about; the shared version supports "two apps that both want to manage files for
    different purposes." Default recommendation: strict singular for v1, with a "claim"
    operation that produces a Control prompt when contested.

    **Decided (2026-05-29): strict singular for v1.** Exactly one primary handler per
    slot. A "claim" operation produces a Control prompt when contested (see Q11 for how
    the switch happens). Revisit shared handlers only if a concrete two-managers use case
    materialises.

11. **Primary handler — install-time claim vs explicit user grant.** When a competing file
    manager is installed, does it become primary automatically, prompt the user, or only
    become primary if the user explicitly grants it? Android's pattern is "prompt on first
    relevant action." iOS's is "only changeable in Settings." Tenshu's is up for grabs.

    **Decided (2026-05-29): explicit user grant (iOS-like).** Installing a competing
    handler never silently or contextually reassigns the slot; the user must explicitly
    switch in Control. Most conservative posture — an install never moves an ambient grant
    without a deliberate user action. (Consistent with the strict-singular claim op in
    Q10: the claim surfaces the option in Control, the user decides.)

12. **Delegated capability tokens.** Drive lets a viewer app open a specific file. Rather
    than giving the viewer `FileSystem.read` ambient, Drive could hand it a one-shot
    capability token for that file. This is a clean cap-system pattern but adds wire shape
    we don't have today. Is this v1 or v2?

    **Decided (2026-05-29): v2 (settled by Q6).** Choosing Option A for Q6/Q7 explicitly
    deferred the capability-token machinery out of Phase 2. Delegated capability tokens
    land with Q16's app-to-app handoff, at which point the same primitive also upgrades
    Q7's residual to full op+entity binding.

13. **Scope grants and the Chain DSL — server-side enforcement.** §2.2 says scoping is
    injected as a `.search { ... }` clause before the operation runs. The trap is that
    the *app* must not be able to circumvent this by routing through a different operation
    that returns the same data unfiltered. We need a per-`@RealmData` scope-completeness
    check: every operation returning a given data type must be enforceable by the same
    scope clause, or be tagged as scope-incompatible (and then require ambient or picker
    grants only).

    **Decided (2026-05-29): enforce the scope-completeness check as an invariant.** Every
    operation returning a given `@RealmData` type must be enforceable by the same injected
    `.search { }` scope clause, or be statically tagged scope-incompatible — in which case
    it is grantable only as `Ambient`/`Picker`, never `Scoped`. A registration/build-time
    invariant test guarantees no unfiltered back-door op exists for a scoped type, so a
    `Scoped` grant is actually sound rather than best-effort. Pairs with Q14: the proxy is
    the single complete chokepoint, and this check is the static guarantee that makes
    dispatch-time enforcement watertight.

14. **Dynamic property chains and runtime operations.** The current schema system allows
    dynamic property chains that resolve operations at runtime. The build-time scan can't
    see these. What permission does an app need to invoke a dynamically-resolved
    operation? Default lean: the permission of the *resolved* operation, checked at proxy
    dispatch — but this means the user can't see all an app's potential capabilities at
    install time. May be acceptable; may not.

    **Decided (2026-05-29): check the resolved op's permission at proxy dispatch.** The
    proxy is the complete chokepoint — whatever op a dynamic chain resolves to is
    permission-checked at dispatch, so an app can never reach an op its grants don't cover,
    regardless of static-scan blind spots. Accepted residual: the install-time manifest
    can't enumerate every *potential* capability of a dynamic chain. Mild in a
    first-party-only v1 (first-party apps are source-scanned and signed). Q13's
    scope-completeness invariant is what keeps this dispatch-time enforcement watertight
    for scoped types.

15. **App permission upgrades on update.** When an app's update declares new permissions
    its previous version didn't, the user is re-prompted. What if the new permission is
    `Ambient` and was previously `Picker`? Always treat upgrades as "ask again," even if
    that's annoying? Reasonable default; worth confirming.

    **Decided (2026-05-29): always re-prompt.** Any new or friction-reducing permission
    change on update (including a `Picker`→`Ambient` relaxation) re-prompts the user.
    Fail-closed and predictable; avoids the subtle, error-prone job of defining "strictly
    broader" across the capability-class × gesture matrix.

16. **Multi-app workflows.** A user dragging a file from Drive to a viewer app crosses
    a permission boundary. The OS-mediated drag/drop produces a capability automatically;
    a Tenshu-mediated invocation (Drive calling the viewer's `OpenFile` operation) needs
    an equivalent. How does this compose with both apps' permission sets?

    **Decided (2026-05-29): v2, with Q12.** A Tenshu-mediated app-to-app invocation is the
    primary *consumer* of the delegated capability-token primitive deferred in Q12 (and
    Q6/Option A). It lands when that primitive does; not in Phase 2.

17. **What about reads of derived data?** "Read the user's storage usage" might be
    `Identity.getQuota`, which is `Ambient`. But the same number can be derived from
    walking `FileSystem.list`. Should the derived path require an additional grant? The
    pure answer is no (the data is the same), but it makes the manifest less honest.

    **Decided (2026-05-29): no extra grant.** A grant covers the op invoked; deriving the
    same fact from data the app can already read requires nothing extra. Trying to flag
    "derivable" access is intractable (any aggregate is derivable from enough reads) and
    noisy. The minor manifest-honesty cost is accepted.

18. **Scan reproducibility across toolchains.** The developer-side `tenshu-manifest-gen`
    and the evaluator-side `tenshu-manifest-verify` must produce the *same* permission
    set on the same code, or the developer's signed manifest won't match the evaluator's
    recompute and REL fails. R8 mode, Kotlin compiler version, JS minifier settings, WASM
    toolchain version, etc. all influence the artifact in ways that can affect what a
    scanner extracts. The platform must either pin the scan toolchain (developer and
    evaluator run byte-identical scanners with a versioned algorithm) or define a
    normalisation step that makes the permission set deterministic regardless of upstream
    toolchain. Treat this as an explicit reproducibility contract, not an emergent
    property. Closely related to §5.B.8 (developer-vs-derived discrepancy) — the
    "evaluator can't reproduce the same scan result" failure mode named there is the same
    problem at the protocol level.

19. **Platform-manifest red-flag policy.** §2.2 says platform-level capabilities that
    bypass the proxy (INTERNET, raw filesystem, keychain, app groups, etc.) are red flags.
    We need a concrete policy: an allow-list of platform permissions clearly compatible
    with proxy-only data access, a deny-list that fails evaluation outright, and a gray
    zone requiring developer justification + evaluator sign-off. The policy differs per
    platform (`AndroidManifest.xml`, iOS `Info.plist`, web app manifest, Flatpak manifest
    on Linux, MSIX `AppxManifest.xml` on Windows, sandboxed-macOS `Info.plist` +
    entitlements, future iOS External Proxy Connection) and per app class (a
    media-decoder app legitimately needs more native access than a pure data app). For
    classically-distributed desktop apps where the OS does not enforce a manifest at
    all, the policy degrades to "manifest-as-documentation only" — see §5.F for the
    sandbox-tier decision. Open: does the gray-zone justification string live in the
    Tenshu manifest itself (so users can see why a flagged permission was approved), or
    only in the evaluator's REL evaluation artifact?

20. **Scanner tool delivery and versioning.** `tenshu manifest get` is plausibly a
    Gradle plugin for first-party plus a hosted service inside the App Console for
    third-party; `tenshu manifest verify` is plausibly a CLI for evaluators plus a
    hosted service for batch evaluation. The git-plugin dispatcher pattern (`tenshu`
    finds `tenshu-manifest-*` binaries on `PATH`) means the two subcommands can rev
    independently and each user installs only what they need, but the versioning story
    still interacts with item 18 — the scanner algorithm inside each binary must be
    compatible across the developer-side and evaluator-side installs that are looking
    at the same publication. Open: does the manifest carry a `scanner_version` field
    so verifiers know which algorithm to apply, or does the platform mandate a single
    canonical version per protocol epoch (and force a coordinated rev whenever the
    algorithm changes)? And: does the `tenshu` dispatcher itself need any versioning
    beyond "find a binary on PATH", or is it deliberately a dumb shim?

21. **WASM / JS scan completeness and indirect dispatch.** §2.2 claims JS minifiers don't
    rename string literals and WASM imports tables are exposed. Both hold for the
    standard cases, but a JS app can obfuscate by base64-encoding op names and decoding
    at runtime, and a WASM app can import a single generic dispatch function and select
    the op via a runtime argument. Both collapse onto the dynamic-op-name problem in
    §5.B.14 and resolve the same way (fail evaluation or require ambient over the full
    surface), but the evaluator tool needs to *detect* the indirect-dispatch pattern
    explicitly rather than silently producing an under-counted permission set. Concretely:
    any indirect dispatch through a string-arg parameter on the SDK boundary should
    itself be a flag.

> **Deferred (2026-05-29): Q18, Q19, Q20, Q21 ride with the App Console.** All four are
> third-party / evaluator-tooling concerns — scan reproducibility across toolchains (Q18),
> platform-manifest red-flag policy (Q19), `tenshu-manifest-*` delivery + versioning (Q20),
> and WASM/JS indirect-dispatch detection (Q21). The first-party-only v1 (Q45) has no
> independent evaluator and no third-party artifact scan, so none of these gate Phase 0–2.
> They become live decisions when the Console / `OnlineReiSource` track starts (Phase 5+).

### 5.C — Shared realms

22. **Who owns a shared realm — single owner, multi-owner, ownerless?** The "owner" has
    rotation authority. Single-owner is simplest; multi-owner needs MLS-like consensus on
    membership changes; ownerless needs every change unanimously signed. Default lean:
    single-owner with transferable ownership.

    **Decided (2026-05-29): single-owner, transferable ownership.** Exactly one member
    holds rotation + membership authority, matching the existing one-master-identity realm
    model and the §2.3 wrapped-key-per-member + rotate-on-remove scheme (which assumes a
    single unilateral rotation authority — multi-owner/ownerless would drag the deferred
    MLS/consensus machinery back into v1). This is the "Shared Drives" container model
    (the realm is one storage pool with one host), not the per-file-ownership model, which
    tees up Q24 toward owner-pays. Owner-loss recovery semantics are punted to Q28.

23. **Shared realm creation flow.** A user wants to share a folder with another user.
    Options: (a) the host carves out a new shared realm and migrates the folder into it,
    (b) the folder stays in the host's personal realm and individual files get
    multi-wrapped, (c) the user pre-creates "share groups" and assigns folders to them.
    §2.3 commits to "shared realm as its own realm," which means (a) — but the migration
    UX (what happens to existing sync state, devices, app permissions referencing the
    folder) needs design.

    **Decided (2026-05-29): option (a), via a single resumable migration operation.** §2.3
    already forces (a) — (b) is ruled out ("wrapping happens once at master-key level —
    never per file") and (c) "share groups" is a UX layer on top of (a), not an
    alternative. The decision is therefore the migration mechanics. Core fact: migration =
    **client-side re-encryption of the subtree under the shared realm's master key** (the
    host's personal key can't decrypt the shared realm; the relay sees ciphertext only).
    Locked mechanics:
    - **Sync state** moves streams as tombstone-in-personal + create-in-shared, carried by
      one resumable/idempotent migration operation (composes with ADR 0006 tombstones and
      the §3.4 "membership-change + key-rotation in one logical transaction" rule; a fit
      for the background-processing / ScheduleService infra). An interrupted migration
      resumes rather than leaving the folder half in each realm.
    - **Host devices auto-join** — as owner, the host identity wraps the shared-realm key
      automatically; existing devices pull the new stream with no new pairing.
    - **v1 constraint: self-contained subtree only** (folder + descendants). Inbound
      references from outside the subtree are blocked-or-warned at share time; cross-realm
      references defer (collides with Q27 causality + the reference-property work).
    - **App-permission carry-over** is punted to Q29; migration must not silently widen
      grants (new realm gets grants re-evaluated, not blindly inherited).
    - Creation has two halves: host carve+migrate (above), then the **invite** — a separate
      `MembershipChange` wrapping the shared key to the invitee's identity.
    - **Implementation gate (found 2026-05-31):** the carve+migrate flow is gated on a
      **multi-realm-per-device sync runtime** — today `SyncManager` / the local `ObjectStore` /
      the encrypt-decrypt path are single-realm-per-device, so a host cannot sync both its
      personal realm and a shared realm at once. The crypto + schema primitives are done (SR-1
      A13 sub-key + A12 wrap, SR-2 `SharedRealmMembership`); the runtime prerequisite
      ("SR-M") and the migration op design are in
      [`sync-multi-realm-runtime.md`](sync-multi-realm-runtime.md). The relay is already
      per-owner-scoped and needs no change.

24. **Storage cost attribution.** A shared realm's storage usage counts against whose
    quota? Whoever wrote the data? The owner? Split among members? This intersects with
    the storage-key entitlement model in §5.D.

    **Decided (2026-05-29): owner-pays, via a billing-entitlement reference.** The single
    owner (Q22) is charged for the whole shared realm's storage — the Shared Drive model,
    consistent with single-owner. Charge-the-writer / split-among-members are rejected for
    v1 as too complex for the user with few real use cases. **The owner is not hardcoded as
    the payer:** attribution is modelled as the realm carrying a **billing-entitlement
    reference** that in v1 resolves to the owner's storage entitlement (this is the
    realm→storage-key indirection §5.D / Q34 / Q37 already imply). Consequence for future
    flexibility: a pooled/split model later is a *rebinding* of that pointer to a
    multi-party-funded entitlement, not a rewire of attribution, and consensus over the
    pool rides on the same multi-owner/MLS machinery deferred in Q22 — i.e. split billing
    is free on top of multi-owner once that exists, though it does not reduce multi-owner's
    own cost.

25. **Discovery / membership listing.** "Who's in this share?" requires every member to
    know the others' realm identities. Putting member identities on the shared realm's
    state is fine for the members but leaks pseudonyms to anyone who sees the shared
    realm's ciphertext. Pseudonymous-only-by-default, with opt-in real identity reveal?

    **Decided (2026-05-29): encrypted member list + pseudonymous-by-default; relay
    metadata residual punted to Q30.** Clarification on what actually leaks: the membership
    list lives as a record on the shared-realm state **encrypted under the shared-realm
    master key**, and member attribution signatures (§2.3) live **inside** the encrypted
    envelope — so the relay sees ciphertext only (House Rule #1 holds) and never needs
    member identity keys (attribution is a member-side check, not relay business logic).
    Content therefore does not leak. The real leak is **relay-side metadata**: per-device-key
    auth (Q30) lets the relay correlate which device keys touch which shared realm → a
    co-membership graph, without decrypting. The mitigation lever — same device-key
    credential across all of a device's realms vs a **per-realm credential** (relay can't
    link a device's personal realm to its shares) — is decided in Q30; the residual is
    documented in the threat model (attack surface #10, per Q42). User-facing identity:
    **pseudonymous-by-default** (realm pubkey + self-chosen display handle, no email/legal
    name auto-attached), **opt-in real-identity/attestation reveal** modelled as the Q52
    identity reference — so the membership entry is an identity *reference* that defaults to
    pseudonym-only but can carry a verified badge later. Q25 and Q52 share the identity-
    reference shape.

26. **Revocation as removal vs revocation as rotation.** §2.3 acknowledges revocation
    can't reach past content. The UX should make this clear, but there's also a question
    of *what* gets rotated on remove. Just the shared-realm master key, or also the per-file
    content keys (forward secrecy on existing files)? The latter is expensive but
    cryptographically stronger.

    **Decided (2026-05-29): rotate the master key only; do NOT bulk-rotate per-file content
    keys of unchanged files.** The item's "expensive but stronger" framing was misleading —
    it conflated removal-rotation with the forward-secrecy axis that actually belongs to
    Q31 (MLS/TreeKEM key schedule: a *current* member's key compromise not exposing *past*
    epochs). Three data buckets on removing member M: (1) what M already downloaded /
    saw in plaintext — unrecoverable, nothing claws it back; (2) brand-new files written
    after removal; (3) new versions of files that existed while M was a member. In the
    chunked-AEAD design ([ADR 0017](../adr/0017-file-content-two-plane-channel.md)) each write carries a freshly-generated
    content key wrapped under the **current** master key, so rotating just the master key
    on removal already covers buckets (2) *and* (3): every future write wraps under the new
    master key M lacks, even if M can still fetch ciphertext from a lagging relay.
    Bulk-rotating *unchanged* existing files buys essentially nothing — it re-encrypts
    ciphertext that decrypts to bytes M already holds in plaintext; pure cost, no benefit.

    - **Load-bearing invariant:** every new write (new file *and* new version) generates a
      fresh content key wrapped/derived under the *current* master key — a new version is
      never re-encrypted in place under a pre-rotation content key. The whole argument rests
      on this; state it explicitly wherever the content-key wrap is implemented.
    - **Defense in depth:** pair master-key rotation with relay-side access revocation (Q30)
      so M's device key stops being served immediately; the rotation is the cryptographic
      backstop for when the relay lags or is compromised.
    - **UX:** make clear that removal protects future content only — already-accessed data
      cannot be recalled.
    - Genuine forward secrecy (past-epoch protection) stays with Q31's MLS deferral.

27. **Inter-realm conflict resolution.** Two members in the same shared realm edit the
    same file at the same time. Standard CRDT / OT merge — but the existing merge layer
    assumes one realm, with HLCs in a single logical clock space. Shared realms need a
    causality story across realm boundaries.

    **Decided (2026-05-29): HLC stays the substrate; split policy by data kind.** Reframe:
    a shared realm is its own single change stream, so HLCs already apply (they exist
    precisely for multiple actors each advancing a logical clock on what they observe). The
    new wrinkles are narrower than "no causality story": (a) clock-domain isolation, (b)
    cross-actor tiebreak determinism, (c) adversarial co-member clocks, (d) merge policy for
    opaque content vs structure. Comparable systems: Google-Docs OT needs a plaintext-reading
    central serializer — ruled out by House Rule #1 (the relay sees ciphertext only and
    cannot be the semantic ordering authority); CRDTs (Yjs/Automerge) are E2E-friendly and
    right for *structured* data but do nothing for opaque blobs; Dropbox-style LWW +
    conflict-copy is the standard answer for opaque file content; Matrix's causal-DAG +
    deterministic state-resolution is the no-trusted-orderer analogue, of which HLC is the
    lightweight version the codebase already uses. Decisions:

    - **File content (opaque blobs): LWW by HLC + conflict-copy.** Two binary versions can't
      be merged and the relay can't help; newest HLC wins, the loser is preserved as a
      conflict copy so nothing is silently lost. Reuses the two-plane chunked-manifest
      model unchanged (ADR 0017).
    - **Structure (folder membership, metadata): the existing semantic merger** (`FolderMerger`),
      extended so set-like fields merge add-wins/observed-remove CRDT-style. Kept over a
      full CRDT rewrite.
    - **Tiebreak by member-identity public key** (lexicographic) — the cross-actor replacement
      for device-id tiebreak; every member converges to the same resolution independently.
    - **Per-realm HLC domain**, not a global device clock — cleaner isolation, and it closes
      a metadata leak (a shared global clock lets co-members correlate a device's shared-realm
      cadence with its personal-realm activity).
    - **Bound adversarial HLCs** — reject/clamp a co-member timestamp implausibly far ahead of
      the relay's receive order or of others observed; use the relay's coarse receive-sequence
      over opaque blocks as a backstop. This is the shared-realm face of the existing
      HLC-consistency carve-out and needs a threat-model amendment (per Q42).

28. **Recovery of shared content when the host realm is lost.** A member loses their
    personal realm and recovers from the recovery manifest. The wrapped shared-realm
    key was in the manifest — good. But if the *owner* loses their realm and didn't have
    the shared realm in their manifest, can the share survive? Recovery semantics for
    shared realms is its own design call.

    **Decided (2026-05-29): two orphan states + deterministic succession; member voting
    deferred to Q31.** Key fact: Kr is held by *every* member wrapped to their identity
    (§3.4), so "owner lost" ≠ "key lost". Two distinct states:
    - **Governance-orphaned (data survives):** owner gone but other members still hold Kr —
      everyone can still read/download; only *authority* (add/remove/rotate) is lost.
    - **Data-orphaned (truly dead):** no surviving member holds Kr — the relay holds
      ciphertext nobody can decrypt. The rarer case, and the one matching the "orphaned,
      unrecoverable" intuition.

    Load-bearing dependency: member recovery only works if recovery is **identity-preserving**
    (BIP39 seed → same X25519 sub-key, so the manifest-wrapped Kr still unwraps). If recovery
    ever rotated identity, a recovered member would need re-admission by the owner — and a
    member recovering with a rotated identity while the owner is also gone would be locked
    out. State identity-preserving recovery as a requirement, not an incidental property.

    Succession (this is where Q22's owner-loss punt lands): single-owner has no consensus to
    elect a new owner. **A free "vote till decided" is deceptively hard** — it's distributed
    consensus: the relay can't tally (ciphertext-only), members tally independently and must
    agree, and a partition lets two subsets elect two owners → permanent fork. That's the
    MLS/consensus machinery deferred in Q22/Q31, plus the impossibility of proving the owner
    is *permanently* gone vs offline. v1 therefore uses **deterministic succession**, which is
    fork-free by construction:
    - **Pre-designated successor** named at creation, or — if none — the **lowest-lexicographic
      member key** auto-becomes candidate. Determinism means concurrent attempts converge to
      the same successor, so no consensus round is needed.
    - Gated by a lightweight "majority of currently-reachable members confirm" + a **long
      owner-absence timeout** (owner silent N days) so it can't fire while the owner is merely
      away.
    - **Full member voting is parked with the Q31 MLS work** — it layers on cheaply once that
      consensus primitive exists.

    UX:
    - **Governance-orphaned, no successor:** freeze **read-only** — banner "The owner of this
      shared folder can no longer manage it. It's now read-only: you can still view and
      download, but members can't be added or removed." Plus a **"Copy to my Drive"** escape
      hatch (re-encrypt contents into the member's own realm) so members aren't trapped.
    - **Data-orphaned:** "This shared folder can't be opened — everyone who held its keys has
      lost access. Files you already downloaded remain on your device." Offer relay-side
      ciphertext purge as cleanup.

    Default: the owner's recovery manifest **defaults to including** each share's wrapped key
    (opt-out), to keep the data-orphan case rare.

29. **Per-app permissions on a shared realm.** Drive in user A's realm has `FileSystem.list`
    permission. When Drive lists files in a shared realm A is a member of, does it use A's
    grant, or does the shared realm have its own grant set? Default lean: A's grant
    applies to all realms A can read, including shared ones — but this opens an
    impersonation surface (an app misbehaving in a shared realm reflects on the host
    realm).

    **Decided (2026-05-29): grant follows the app, capped by role — effective permission =
    role ∩ local grant.** The "A's grant applies everywhere A can read" lean is almost right
    but needs one correction: an app acting for member A in shared realm S is bounded by
    **(A's role rights in S, per Q53) ∩ (A's local grant to that app)**. The role is the cap,
    the local grant is the floor within it — a viewer-A's apps can't write to S whatever
    their local grants; an editor-A's read-only app still can't write. Rejected: a separate
    per-share grant set (heavy friction, solves the wrong problem — grant administration
    stays local to A, no per-share re-granting).

    Why this contains the impersonation surface the item flags: writes to S are signed under
    **A's member identity** (§2.3), so a misbehaving app is attributable to A — A is the
    accountable principal and the app can never exceed what A could do. The blast radius is
    "what A could already do in S", which the other members **already accepted when they
    admitted A at that role** (same inherent trust as sharing a Google Doc with someone whose
    device/extensions you then also trust; A could exfiltrate manually regardless). Accepted
    residual: A's *choice of apps* reaches S up to A's access — equivalent to trusting A, not
    a new surface. Also retroactively satisfies Q23's "migration must not silently widen
    grants": effective rights in S are recomputed as role ∩ grant, not inherited from the
    personal realm.

    **Non-exclusive — the model is a bound, not a lock.** It says nothing about *how many*
    apps/members touch a file; there is no single-writer / one-app-per-file constraint. So
    "Drive shares files → members open them in a collaborative editor app and edit in
    parallel" is fully supported: any number of apps/members with sufficient role + grant can
    edit the same files. Mechanically, apps don't join the shared realm — *members* do; an
    editor app acts through member A's proxy with A's keys, bounded by role ∩ grant, writes
    signed under A's member identity. Different editor apps used by different members all
    compose.

    Concurrency is a **separate** axis (Q27), and the only real limit lives there, by file
    kind: an **opaque file blob** gets save-level **LWW + conflict-copy** (Dropbox-style, not
    live co-editing); **true real-time co-editing** (Google-Docs-style live merge) requires
    the editing app to bring its own CRDT/OT document model — Tenshu provides the encrypted
    shared realm as transport plus the structured-merge primitives, but can't make an opaque
    binary live-mergeable on its own. Live co-edit is an app-layer capability, never
    forbidden by the share model.

    Mechanics: enforced server-side at the proxy (consistent with §2.2 scope enforcement) —
    the proxy knows the caller's realm + role and the app's grant and intersects them.
    Primary-handler status (Drive for files) carries into S but stays capped by role +
    gesture requirements. Cross-links: Q23, Q27, Q53.

30. **Sync relay cost of cross-realm channels.** The relay sees per-realm change streams.
    Shared realms add streams that multiple users are reading and writing. Authentication
    (JWT-per-device-key) needs to allow a device key to access multiple realms; the relay
    Phase 4 (Postgres + S3) deployment plan should anticipate this.

    **Decided (2026-05-29): owner-signed per-realm capability tokens + per-realm device
    credentials.** Resolves the Q25 credential lever and the Q26 relay-side revocation
    together. Crux: the relay must gate shared-realm stream access **without** being the
    membership authority and without plaintext (House Rule #1). Rejected alternatives: a
    relay-held ACL roster (hands the relay the explicit co-membership graph — the Q25 leak,
    centralised and durable) and bare bearer-proof-of-Kr (relay can't distinguish/revoke
    individual members). Chosen model:
    - **Capability tokens:** when A joins S the owner issues A a signed capability for S; A's
      device presents it; the relay verifies against a `realm → authority-pubkey` binding
      registered at realm creation. Relay stays dumb — checks "valid unrevoked cap signed by
      this realm's registered authority?", no membership semantics, no roster.
    - **Per-realm device credentials (resolves Q25):** each device uses a distinct auth key
      per realm; the capability binds *that per-realm pubkey* to S. The relay therefore can't
      link a device's personal realm to its shares or build a co-membership graph — the
      cross-realm correlation leak is minimised by construction, not merely accepted. Cost:
      more keys to manage + an owner action per add (Q53's invite flow already has it).
    - **Revocation (resolves Q26 relay side):** caps are bounded-validity + revocable; on
      member removal the owner **drops/revokes the cap at the relay** *and* rotates Kr —
      relay access removal for immediacy, Kr rotation as the cryptographic backstop for a
      lagging/compromised relay (defense in depth).
    - **Ownership transfer** (Q22/Q28) updates the `realm → authority-pubkey` binding via a
      signed transfer the relay accepts. One authority pubkey per realm is a pointer, not a
      roster.

    **Phase 4 (Postgres + S3):** shared realms are **ordinary realms** in the schema (§3.4 —
    no special-casing; per-realm streams keyed by realm id, content blobs already realm-scoped
    in S3). Add a small **auth table** (`realm → authority pubkey` + capability revocation
    list) and a per-realm **billing-entitlement pointer** (Q24). Plan capacity for
    **multi-reader/writer fan-out** (N members × M devices on one stream) — where the deferred
    long-poll on `/sync/changes` (`sync-relay-api.md` Phase 5) starts to matter for shares.
    Threat-model residual: the relay learns `realm → authority pubkey` + per-realm access
    patterns (per-realm creds minimise linkability) — amend per Q42 (attack surface #10).

31. **TreeKEM trigger.** §2.3 commits to deferring MLS. But we need a stated threshold:
    at what membership count or churn rate do we revisit? Otherwise we'll either bolt
    MLS on under pressure (badly) or never (and ship insecure for large shares).

    **Decided (2026-05-29): membership size is mostly a red herring; trigger on requirement
    or feature, not scale — plus a v1 hard cap.** Cost reality of the simple scheme ("wrap
    Kr per member, rotate on remove"): add = O(1) one wrap (new member reads existing content,
    usually desired for a folder); remove = rotate Kr + re-wrap to N−1 survivors, O(N); no
    per-message re-keying. A wrap is one public-key op, so O(N) re-wrap is trivial at N=10,
    fine at N=100, user-visible only in the low thousands — the O(N)→O(log N) MLS win does
    **not** bite on performance for realistic folder shares. What MLS actually buys, hence the
    real triggers:
    - **Forward secrecy / PCS.** The simple scheme gives only *rotation-boundary* forward
      secrecy and **no post-compromise security** — a compromised member key or Kr exposes
      everything under the current Kr until a manual rotation, with no automatic healing. If a
      share class *requires* that guarantee, the simple scheme can't provide it **at any
      size**. Requirement trigger, not scale.
    - **Concurrent membership authority.** **Multi-owner (Q22) and member-consensus
      promotion/voting (Q28) structurally need MLS-style epoch agreement** to order concurrent
      membership changes without forking — MLS is their explicit unblocking dependency, and
      this is the most likely *actual* trigger (a foreseeable feature decision, not a stumbled-
      into scale event).
    - **Raw size**, only at the far end — when O(N) re-wrap latency goes user-visible, in the
      low hundreds.

    Decision: keep the simple scheme for v1; revisit MLS on **whichever comes first of** —
    (a) a product requirement for forward secrecy / PCS on any share class; (b) shipping
    multi-owner (Q22) or member-consensus/voting (Q28); (c) shares routinely needing to exceed
    the v1 hard cap. **Enforce a ~150-member hard cap now** as the guardrail — it converts the
    item's "ship insecure for large shares" worry into "feature-gated until MLS lands" so we
    can't accidentally run the no-PCS, O(N) scheme into a regime it's unsuited for. Numbers
    are defensible-but-revisable.

32. **Notifications across realm boundaries.** A file change in a shared realm should
    notify other members. The notification service today is realm-scoped. Either it grows
    a cross-realm shape, or shared realms get their own notification service instance.
    Either way, threat-model assets need to be revisited (notification metadata is
    sensitive).

    **Decided (2026-05-29): notifications stay per-realm data; the *consumer* fans in.** The
    item's "grow a cross-realm shape vs separate instance" framing misleads — production is
    the easy half. **Production + storage is free:** §3.4's realm-machinery uniformity means
    a notification is just a synced data type *within* a realm; a shared realm syncs its
    notification records to all members on its own stream, encrypted under Kr, relay sees
    ciphertext only. No cross-realm machinery, no separate service instance. The real work is
    elsewhere:
    - **Consumption / aggregation:** a member's notification UI needs one unified feed across
      their personal realm **and** every shared realm they're in — a local fan-in observer
      that merges N realm streams. This is the only genuinely cross-realm piece, and it lives
      on the local proxy consumer, not in a deeper service change. This is what "the service
      is realm-scoped" actually pinches.
    - **Derive, don't transmit (trust):** if member B *writes* a notification record A
      consumes, B can forge spam ("your account is compromised, click here"). Fix: shared-realm
      activity notifications are **locally derived by each member's proxy from the observed,
      signed change block** (A's proxy sees B's signed file-modification in S and generates
      "B changed X" locally), not attacker-controllable payloads. Removes the forged-notification
      surface; inherits attribution from the §2.3 member signature.
    - **Push wakeups (the hard, privacy residual):** local/foreground observation leaks
      nothing, but *waking an offline device* means telling a wakeup path (relay / push
      provider) "realm S changed, wake these devices" — leaking activity timing + recipient
      set (the co-membership/activity metadata again). Composes with Q30's per-realm
      credentials; accept a coarse leak for v1 or gate push-for-shares behind the per-realm-
      credential delivery. Document in the threat model (Q42).

53. **Invite mechanisms, access-management roles, and Contacts association.** Q23/§3.4
    cover only the *result* of an add — a `MembershipChange` that wraps the shared-realm
    master key to the invitee's identity, plus a key-rotation block — and `SharedRealmMembership`
    already carries a `role` field. Unspecified: how the owner obtains the invitee's
    identity key, and the access-management model on top. Deferred (Phase 3/4; Contacts is a
    later milestone app), with leans:

    - **Crux:** adding a member requires wrapping the realm key to the invitee's realm-identity
      X25519 sub-key, so the owner must know that public key *before* the add. Invite
      mechanisms differ only in how that key reaches the owner.
    - **Invite-by-link = invitation link, NOT bearer-key link.** Reject the bearer-key style
      (link embeds/derives the realm key): it breaks §2.3's per-member wrapping, gives no
      per-member attribution, and can't be cleanly revoked (revoke = rotate + re-wrap for
      everyone). Use an **invitation link**: a one-time/expiring capability that *initiates*
      a cross-user handshake (the invitee's client presents its realm identity pubkey; owner-
      side policy then wraps the key and emits the `MembershipChange`). The cross-user sibling
      of the device-pairing flow ([`sync/pairing-flow.md`](../sync/pairing-flow.md)); revocable before redemption; the
      link authorises *becoming a member candidate*, access policy decides acceptance.
    - **Access management = roles** built on §3.4's `role` field: at least owner / editor /
      viewer, a mapping of which capability classes each role confers *inside* the shared
      realm, and which roles may invite. Intersects Q29 (per-app permissions on a shared
      realm).
    - **Contacts association:** the Contacts service (milestones §Contacts, vCard-shaped)
      becomes the realm-resident store of known **identity references** — same shape as Q25
      membership entries and Q52 verified-badge hooks. "Invite from contacts" reuses a stored
      identity instead of re-exchanging keys; a Q52 verified checkmark surfaces in the picker.
      vCard carries the human-facing fields; a Tenshu realm-identity reference carries the
      cryptographic half.

    Cross-links: Q23 (invite half of creation), Q25 (membership listing / identity refs),
    Q29 (per-app permissions on shared realms), Q52 (verifiable users / badges), milestones
    §Contacts.

### 5.D — Public vs private records

33. **Identity-revealing aspects of public records.** A publication is signed by a
    developer identity. That identity may be a known company or a pseudonymous individual.
    The whitepaper supports both; we need to make sure pseudonymous publication doesn't
    leak identifying metadata (timing patterns, signature linkability, etc.).

34. **Anonymous redemption of entitlements.** A storage key purchase produces a public
    on-chain record. When the user redeems it, the realm proves entitlement to the
    storage provider without (ideally) linking the redemption to the user's identity.
    This is a zero-knowledge proof problem; we should know whether we're solving it for
    v1 or accepting linkability initially.

35. **Lazy minting for collectibles.** Game collectibles need eventual transferability
    but most are never transferred. Mint-on-first-transfer is much cheaper than mint-on-earn,
    but it requires the game to keep authoritative pre-mint state. Acceptable trade?

36. **Revocation of public records.** A publication can't be deleted from the ledger.
    Revocation is achieved by a separate revocation record. Proxies need a clear policy
    for handling apps whose publication is revoked after install — refuse to run them?
    Warn the user? Quarantine?

37. **Smart contract for storage-key consumption.** §2.4 mentions that storage purchases
    need a redemption record to prevent double-spend. The contract shape is non-trivial
    (append-only spent-set, reorganisation safety, gas cost). Design call deferred until
    the ledger choice is made, but the redemption interface visible to Control should be
    designed now so we don't have to refactor later.

38. **Console-side enforcement of minimisation.** §2.4 puts the public/private boundary
    at the App Console. But the console will be operated by Tenshu (initially) and
    eventually by third-party console providers. How do we ensure third-party consoles
    respect the minimisation defaults? Auditing? A "what would be published" preview
    the developer must approve? Both?

39. **Pseudonymous developer identity in chains of trust.** REL evaluators confirm that
    a developer identity is trustworthy. For pseudonymous developers, the evaluator
    necessarily ties the pseudonym to *something* (a Sybil-resistance signal, a
    web-of-trust endorsement, a stake). Each of these has different privacy trade-offs.
    Worth choosing deliberately.

40. **Ledger choice and what we lock in by not making it.** We defer the ledger choice
    in §4, but each plausible choice (Monero-with-smart-contracts, Cosmos, custom) has
    different artifact-size limits and gas models. We should at least know what artifact
    shapes are infeasible on each, so §3.5's wire format doesn't paint us into a corner.

41. **What the proxy does when no Rei source is available.** Offline Control with no
    `BundledReiSource` artifact for a third-party app and `OnlineReiSource` unreachable.
    Block the app? Run it with a "unverified" warning? Allow on first-party only? This
    is a real degraded-mode policy.

52. **Verifiable users (trust badges on sharing).** When sharing, a user should be able to
    see a trust signal on the recipient — a "verified" checkmark — from either of two
    sources: **web-of-trust** ("trusted by people you know" — endorsements signed by
    identities the user already trusts) and **public Rei verification** (a stranger-checkable
    public attestation that an identity is who it claims). Deferred (the public half needs
    the ledger — Phase 5+; the web-of-trust half needs an endorsement artifact type that
    doesn't exist yet), but the architecture is already shaped for it and a few constraints
    should be honoured now so we don't paint ourselves into a corner:

    - **The two sources map onto §2.4's public/private split exactly.** Public checkmark =
      strangers verifying an identity → public record. Web-of-trust endorsement = flows
      through an existing trust path → realm-private. No new taxonomy needed.
    - **Rei attestation subjects must generalise from app-publications to identities.** A
      verified-user badge is structurally a Rei evaluation/attestation whose *subject is an
      identity* rather than an app `Publication`. The `Publication`/`Issue`/`Evaluation`/
      `TrustAnchor` shapes shouldn't need to change — only their subject should generalise.
      This is the one load-bearing constraint to keep in mind when §3.1's schemas are
      designed.
    - **Model invitees as identity references, not bare key blobs.** Q23's invite flow and
      Q25's membership listing should reference an identity that can later carry attached
      attestations, so a badge drops in without a wire-shape change.
    - **Verification is never a precondition to share.** The default — sharing with a friend
      — stays private and unverified-friendly; the badge is an opt-in trust signal layered
      on top, not a gate. Premature public identity records would also collide with the
      pseudonym-leakage concerns in Q25 and Q33.

    Cross-links: Q25 (membership listing / pseudonyms), Q33 (identity-revealing public
    records), Q39 (pseudonymous identity in chains of trust — the developer-side analogue of
    the same web-of-trust / Sybil-resistance question).

### 5.E — Cross-cutting

42. **Threat-model asset list — partially landed.**
    [`docs/sync/threat-model.md`](../sync/threat-model.md) v0.3 amendment adds six new
    assets covering Rei and desktop deployment: **A11** (Tenshu root developer identity),
    **A12** (shared-realm master key), **A13** (realm identity encryption sub-key),
    **A14** (desktop IPC peer-credential binding), **A15** (desktop master-key at-rest
    storage including the Linux SS-absent fallback), **A16** (developer-mode toggle
    state). Anchor threats land alongside: T-REI-01 / T-REI-02 in a new §8.8 (Rei trust
    chain), T-SR-01–04 in a new §8.9 (shared realms), T-C-11 / T-C-12 / T-C-13 in §8.4
    (desktop client device). §7 attack surfaces grew #9 (Rei artifact distribution) and
    #10 (cross-realm channels). The remaining ratchets — particularly the relay-side
    wire shape of shared realms once that slice ships, and tighter T-C-1x detail when
    the desktop IPC code lands — follow House Rule #2 and co-land with their
    implementation.

43. **Schema DSL changes — backward compatibility.** Adding `capabilityClass` and
    `gestureRequirement` to `Operation` means every existing operation declaration needs
    a default. Defaults should be the safest (highest-friction) choice for the user:
    `Picker` + `requires_user_gesture`. Then each op explicitly opts down to `Ambient` /
    `none` where appropriate. Migration touches every existing service.

    **Decided (2026-05-29): traits with absence-defaults — no per-op migration.** The
    field-with-default framing in this item is superseded: `capabilityClass` and
    `gestureRequirement` are modelled as presence/absence traits (`IoOperation` style),
    with the safe defaults (`Picker` + `requires_user_gesture`) returned by the accessor
    when no trait is attached. Existing ops declare nothing and read back as the safe
    default; ops opt *down* via `AmbientCapability` / `ScopedCapability` / `GestureExempt`
    / `RequiresExplicitConfirmation` traits. So migration does **not** touch every
    service — Phase 0 is purely additive (define traits + absence-defaulting accessors +
    a capability-class exclusivity invariant test). Two trait-specific traps: keep the
    default out of `Operation.Schema` / shared parent op schemas (monotonic trait
    inheritance can't be removed downstream, which would block opt-down), and add the
    exclusivity invariant since traits don't enforce it. See the §2.2 "Encoding" note.

44. **`@RealmData` interaction with permissions and primary-handler slots.** `core-realm-data.md`
    already lands `RealmData` annotations on `File` and `Device`. Primary-handler slots
    are a property of those annotations. We need to extend `RealmData` (probably with
    optional `primaryHandlerSlot: String?` and the slot's display metadata) without
    breaking the landed v1.

    **Superseded (2026-05-29) by Q9.** The premise here is now reversed: the primary-handler
    slot is **not** a `RealmData` field. It lands as a standalone `PrimaryHandlerSlot`
    annotation keyed by `schemaId`, a sibling facet alongside `RealmData` (presentation)
    and `Syncable` (sync) — see Q9 for the full reasoning (enforcement-adjacent concern
    kept out of `RealmData` per `core-realm-data.md` §6; sibling ships without waiting on
    `core-realm-data.md`, which is still not started). Two corrections to this item's
    premise: `core-realm-data.md` has *not* landed (it's spec'd/not-started), and the slot
    therefore does not extend `RealmData` at all. What remains true: whatever lands must
    compose with `RealmData` cleanly once that proposal does ship.

45. **App Console as a precondition.** A surprising amount of this proposal assumes the
    App Console exists. For first-party apps we can hand-roll the manifest-generation
    step; for any third-party flow we can't. The Console is its own significant project.
    We should decide whether v1 of `:rei-service` is "first-party only, no Console" or
    "Console-required."

    **Decided (2026-05-29): first-party-only, no Console.** v1 ships `BundledReiSource`
    over hand-rolled Drive/Control/Mia manifests signed under the root identity, with the
    verifier and Control permission UI running on top. No Console, no third-party
    scan/verify toolchain, no REL flow in the first phase. Consequence: Q18 (scan
    reproducibility) and the `tenshu manifest get/verify` tooling drop out of Phase 1 —
    there's no independent evaluator in the loop for source-time first-party scans. The
    Console and everything third-party becomes Phase 5+.

46. **Documentation amendments.** This proposal touches the whitepaper's Rei section
    (which is currently aspirational and would benefit from a "Implementation Notes"
    appendix linking back to this doc and the eventual ADRs). Worth landing as a separate
    documentation pass, not in the implementation PR.

### 5.F — Desktop deployment

47. **Sandbox-exception criteria for third-party apps.** §3.6 commits to
    sandbox-required by default, with manual exceptions during REL evaluation. The
    exception is claimed against either a documented common category (kernel-level
    anti-cheat, low-latency audio with custom drivers, raw USB / MIDI / capture-device
    access, legacy graphics-driver requirements) or a "custom" category with a
    developer-supplied free-text justification. Review is fully manual either way —
    automation cannot validate claims like "BattlEye kernel anti-cheat required for
    multiplayer integrity" or "sub-1ms audio driver latency that pipewire can't
    deliver inside Flatpak"; the evaluator reads the justification, cross-references
    vendor documentation or the binary as appropriate, and signs based on judgment.
    The exception category and justification string are baked into the publication so
    the user sees them on install.

    The custom category is necessary (the documented list will never be exhaustive)
    but creates a rubber-stamping risk: any evaluator could approve any justification.
    Open sub-questions: should custom-category exceptions require a stricter evaluator
    signature than common-category ones (multiple evaluator signatures, or a
    Tenshu-blessed evaluator); should Control's install UI visually distinguish
    custom-category from documented-category exceptions to flag the weaker review
    process; does the documented-category list have a defined process to grow over
    time (a "graduate from custom" path when a justification recurs across
    publications)?

    Resolved leans: separate `RelBehaviouralReview` evaluation type required for any
    sandbox-exception app regardless of category; persistent status-bar indicator
    while such an app is running; install-time refusal-by-default that the user must
    explicitly override.

48. **Tray popup contents and lifecycle.** §3.6 commits to up to 4 most recent
    notifications without filtering, "Open Control" + "App Launcher" buttons, and
    click-routing for `FileCategory.Image` notifications into Drive as the first
    concrete routing target. Additional routing handlers (file-shared → Drive at
    that file, low-storage warnings → Control's storage settings, pairing prompts →
    Control's pairing flow, sync-status warnings → Control's sync settings) wait on
    observed-use signal rather than landing speculatively — each routing handler
    adds maintenance surface and risks confusing users who don't expect specific
    click behaviour. Open: what happens to the popup when the user clicks outside
    it (auto-close vs sticky); whether long-press / right-click on the tray icon
    shows a different (admin-style) menu with Quit Control / Sign Out / Pause Sync;
    how the App Launcher distinguishes installed apps from "available to install"
    (relevant once the apex publication-discovery story exists).

49. **Peer-credential check edge cases.** §3.6 commits to PID → binary →
    code-signing-identity → known-publication match on every IPC connection, with a
    developer-mode toggle (hidden in production behind a seven-tap reveal on
    Control's build-number row, optionally always-visible in debug builds) that
    downgrades unknown-signature to a warning so unsigned development builds can
    connect. While active the toggle ships behind an explicit warning ("Allowing
    unsigned apps lets any process on your computer connect to Control as if it were
    a Tenshu app — only enable this for development") and a persistent status
    indicator.

    Open sub-questions on developer mode: is it a blanket "accept unsigned" bypass,
    or must each dev build be signed under a user-installed dev-only trust anchor
    (slightly more friction but tighter control — the user designates "this is my
    dev key" once rather than trusting every unsigned process); are permission
    grants to dev-mode apps session-scoped (cleared on Control restart) or
    persistent, and do they survive disabling developer mode.

    Other open sub-questions independent of developer mode: how first-connection
    bootstrap works for a *signed* app whose publication has just been installed
    but the in-memory cache hasn't picked up yet; how the cache invalidates when
    an app updates its signature on update; how Windows-named-pipe peer-PID
    resolution handles the documented race where `GetNamedPipeClientProcessId`
    returns a PID whose process has already exited (use the impersonation API
    alternative? require the client to send a one-shot token within N ms of
    connect?).

50. **Linux master-key fallback when Secret Service is absent.** §3.6 commits to
    Argon2id-derived encrypted-file fallback when no Secret Service implementation
    is available. Resolved choices:

    - Argon2id parameters pinned to the OWASP-recommended set (no per-device
      tuning — convenience gain doesn't justify per-install variance for a path
      this niche).
    - Default mode: passphrase prompt on each Control start. The user's passphrase
      derives the file-encryption key via Argon2id.
    - Opt-in machine-bound mode: key derives from a per-install machine identifier,
      no prompt on start, but anyone who reads the disk offline can decrypt the
      master key. The user makes this choice explicitly after the warning, and
      Control's Settings UI persistently shows a "running without a secret store"
      indicator while the fallback is active.
    - Warning text (v1, pre-localisation): "No system secret store detected. Choose
      a passphrase to protect your master key (you'll be prompted each Control
      start), or use a machine-bound key (no prompt, but anyone who reads this
      disk offline can recover your data). You can change this in Settings."
    - Headless / server-style Control deployments are out of scope for v1.
    - Biometric unlock of the stored passphrase (libfprint / PAM on Linux) is
      future-scope, not blocking v1 — users with a fingerprint reader who want
      biometric should install gnome-keyring, which is the right path for that
      population.

    Open: precise derivation source for the machine-bound identifier
    (`/etc/machine-id` is convenient but readable by every process on the system;
    a generated per-install UUID under `0600` is the more deliberate path; TPM-bound
    derivation where the hardware is available is the strongest); UX flow for
    rotating a forgotten passphrase (recovery code re-bootstrap?).

51. **Dangerous-action categories for the desktop scanner.** §3.6 acknowledges
    classic-tier apps have scanner-best-effort verifiability and we're explicitly
    not trying to detect arbitrary syscalls or JNI calls — perfect coverage is
    impossible. The scanner targets the categories that materially affect the
    threat model.

    Suggested v1 list (subject to refinement once we have classic-tier apps to
    test against):

    *Hard-fail-unless-declared* — the publication must list the category explicitly
    and the evaluator must approve:
    - Outbound network egress (sockets, HTTP libraries, `dlopen` of network libs
      like libcurl).
    - Raw filesystem access outside the app's allocated data directory.
    - Writeable-and-executable memory pages (JIT, code-from-data execution).
    - Direct hardware device access (`/dev/*` on Linux, IOKit / `IOServiceCall` on
      macOS, kernel-mode driver calls on Windows).

    *Red-flag-requires-justification* — the evaluator's writeup explains why the
    category is acceptable; surfaced to the user in Control's install UI:
    - IPC to non-Tenshu peers (Unix sockets, named pipes, dbus calls beyond a
      whitelist of standard system services).
    - Subprocess spawn (`exec*` family, `posix_spawn`, `CreateProcess`).
    - Dynamic code loading (`dlopen` / `LoadLibrary` of non-standard / non-system
      libraries).
    - Reflection or runtime metaprogramming — cross-references §5.B.14 (same
      static-analysis blind spot at the SDK boundary).
    - Indirect dispatch through a string-arg parameter on the Tenshu SDK call
      boundary — cross-references §5.B.21.

    Open: precise category boundaries (what counts as "outside the app's data
    directory" — symlinks, `/tmp`, user-supplied paths via OS open dialogs?);
    how the scanner reports its known-incompleteness in the REL evaluation
    artifact so evaluators don't accidentally overclaim coverage in their
    sign-off; whether the category list is versioned per protocol epoch
    (compatible with §5.B.18 reproducibility) or per-evaluator extensible
    (probably not — extensibility makes reproducibility impossible).

---

## 6. Implementation Plan (placeholder)

Intentionally not written yet. The plan crystallises after §5 is worked through, since
several open questions (5.B.6, 5.B.13, 5.B.14, 5.B.18, 5.C.22, 5.C.23, 5.D.40, 5.E.45,
5.F.47, 5.F.49) materially change what Phase 1 looks like.

**Update (2026-05-29):** the Phase 0 + Phase 1 gating questions are now resolved — Q45
(first-party-only, no Console), Q2+Q3 (update-only, fully offline verifier), Q4 (single
compiled-in root key, rotate via build), Q1 (no bundled-vs-online marker), Q5 (no
transitional third-party bundling), Q43 (capability/gesture modelled as traits with
absence-defaults `Picker` + `requires_user_gesture` — no per-op migration). Q18
and the manifest-scan toolchain drop out of the first phase as a consequence of Q45. The
remaining listed questions (5.B.6/13/14 enforcement, 5.C.22/23 shared realms, 5.D.40
ledger, 5.F.47/49 desktop) gate Phases 2–5, not the first phase, and are still open.
Phase 0 and Phase 1 are now unblocked for an implementation plan.

Once decisions are made, the plan likely splits into:

- **Phase 0** — schema DSL additions for `capabilityClass` + `gestureRequirement`,
  modelled as presence/absence traits (per the §2.2 Encoding note and Q43): define the
  `AmbientCapability` / `ScopedCapability` / `GestureExempt` / `RequiresExplicitConfirmation`
  traits, two absence-defaulting accessors (default `Picker` + `requires_user_gesture`),
  and a capability-class exclusivity invariant test. Purely additive — no behaviour
  change and no service-wide migration.
- **Phase 1** — `:rei-service` module scaffolding, `BundledReiSource` reading hand-rolled
  artifacts for Drive + Control + Mia, verifier running, threat-model amendment.
- **Phase 2** — Control permission UI consuming the manifest; runtime enforcement of
  capability class + gesture requirement.
- **Phase 3** — `IdentityService` encryption sub-key; shared-realm wire shape;
  membership / rotation operations.
- **Phase 4** — first end-to-end shared folder in Drive.
- **Phase 5+** — App Console, Online Rei source, public/private boundary enforcement,
  resale-publication path.

Phases 1 and 3 are independent and can run in parallel.

---

## 7. References

- [`docs/whitepaper.adoc`](../whitepaper.adoc) §Tenshu Rei — the vision this proposal
  implements.
- [`docs/whitepaper.adoc`](../whitepaper.adoc) §Realm Device Pairing, §Recovery — the
  realm-machinery that shared realms reuse.
- [`docs/sync/threat-model.md`](../sync/threat-model.md) — the security contract this
  proposal needs to amend (see open question 42).
- [`docs/proposals/core-realm-data.md`](core-realm-data.md) — the `RealmData` annotation
  this proposal extends with primary-handler-slot metadata.
- [ADR 0017](../adr/0017-file-content-two-plane-channel.md) — the content-blob
  channel that shared realms reuse unchanged (protocol semantics in
  [`sync/design.md` §2.7](../sync/design.md#27-file-content-two-plane-channel)).
- [`docs/proposals/sync-backend-relay-plan.md`](sync-backend-relay-plan.md) — the relay
  hardening plan that shared realms intersect with (Phase 4 deployment).
- [`docs/web/architecture.md`](../web/architecture.md) §6, §13 — semantic-permission
  declarations in the manifest format on web; phishing on look-alike subdomains as a
  related Rei use case.
- [`docs/agent.md`](../agent.md) — Service / Operation pattern, schema DSL conventions,
  module-config registration.
