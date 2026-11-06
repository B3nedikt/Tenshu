# Web Track — Roadmap

This is the working roadmap for the web track of Tenshu — the apex
(`tenshu.app`), per-app subdomains (eventually `drive.tenshu.app`, etc.), and
the `@tenshu/sdk` npm package shared between them.

It complements [`docs/web/architecture.md`](architecture.md) (which covers the
*shape* of the architecture) with concrete, sized phases of implementation
work. When a phase is finished, the corresponding tech-debt entries in
[`docs/tech-debt.md`](../tech-debt.md) and the §15 "What's Next" list in the
architecture doc should be updated to reflect that.

**Status legend**: ✅ done · 🟡 in progress · ⬜ pending · 🔒 gated by another
phase · ⚪ optional / nice-to-have

---

## Phase 1 — Foundation **(✅ done)**

Reference of what's already shipped:

- ✅ `js(IR)` on `:core` + four feature-service umbrella modules
  (`:identity-service`, `:file-service`, `:notification-service`,
  `:sync-service`).
- ✅ `:tenshu-sdk-js` module + npm-publishable webpack bundle.
- ✅ `web/apex` Vite + React 19 + TS strict (with
  `noUncheckedIndexedAccess`).
- ✅ Tailwind 4, React Router 7, TanStack Query 5, Zustand 5.
- ✅ M3 design tokens ported from `:ui-core/theme/` to apex's `index.css`.
- ✅ Layout + `/`, `/about`, `/auth/sign-in` routes with placeholder content.
- ✅ End-to-end SDK link working: apex → `@tenshu/sdk` →
  `Drive-tenshu-sdk-js` → `:tenshu-sdk-js` Kotlin source.

---

## Phase 2 — Expand KMP→JS coverage **(✅ done)**

**Goal**: get `:core-common`, `:core-client`, and the feature-service
`*-common` / `*-client` modules compiling to JS so the SDK can expose real
protocol/schema/domain types.

- ✅ 2.1 `ProxyGateway.call` made `suspend`; the `runBlocking` bridge moved
  into the synchronous transports that wrap it (Android Binder
  `ProxyService`, desktop `UnixSocketServer`, `TestProxy.handleRequest`).
  `ProxyGateway` itself stays in `commonMain` and is now MP-clean.
- ✅ 2.2 Crypto / storage `expect`s in `:core-common/util/`:
  - `nextSecureRandomBytes` — JS actual via `crypto.getRandomValues`.
  - `TimestampUtil.formatTimestamp` — JS actual via `Date.toLocaleString`
    with `dateStyle: 'medium'` + `timeStyle: 'short'`.
  - `sha256` + `pbkdf2Sha512` — JS actuals via the **`@noble/hashes`** npm
    dep (small, sync, vetted; matches the sync API on JVM/Android/iOS).
  - `KmpByteArrayOutputStream` — deleted (zero callers anywhere).
- ✅ 2.3–2.7 `js(IR) { browser() }` added to `:core-common`,
  `:core-client`, `:identity-service-common`, `:identity-service-client`,
  `:file-service-common`, `:file-service-client`,
  `:notification-service-common`, `:notification-service-client`,
  `:sync-service-common`, `:sync-service-client`.
- ✅ 2.8 `:core-test-utils`, `:file-service-test-utils`,
  `:notification-service-test-utils` got JS targets too, with `nonJsMain`
  source sets holding the proxy-coupled bits (`TestProxy`, `FileTestProxy`,
  `NotificationTestProxy`) and their `:*-proxy` `api` deps. JS klibs hold
  the portable test fixtures; proxy bridges stay JVM/Native.
- ✅ 2.9 `:tenshu-sdk-js` `api`-depends on all 15 in-scope modules now
  (`:core` + `:core-common` + `:core-client` + four feature service
  umbrellas + their `*-common` and `*-client` variants).
- ✅ 2.10 Verified: `:tenshu-sdk-js:assemble` produces the webpack bundle;
  `gradle check` passes for every touched module + their proxy/desktop
  consumers (iOS test linking excluded — environmental Gradle daemon OOM
  unrelated to this work).

**Carry-over**: JS test compilation is currently disabled in `:core-common`,
`:file-service-common`, `:file-service-client`, and
`:notification-service-common` because their `commonTest` source files
import `TestProxy` / `FileTestProxy` / `NotificationTestProxy` from
`nonJsMain`. The disable is targeted (`compileTestKotlinJs` only); main JS
compilation is unaffected. The proper fix — moving those test source files
to a `nonJsTest` intermediate source set — folds into cross-cutting
**Phase T** (testing infrastructure).

---

## Phase 3 — Expose meaningful SDK surface **(🔒 gated on Phase 2)**

**Goal**: replace the `version`-only placeholder with real Kotlin-defined
types and operations exposed as ES module exports.

- 🟡 3.1 Decide and `@JsExport` the public surface: schemas, domain models
  (File, Folder, Device, Notification), operation interfaces, request DSL
  helpers, BIP-39 helpers.
  - ✅ **Bip39 helpers**: `app.tenshu.sdk.Bip39` in
    `tenshu-sdk-js/src/jsMain/.../Bip39.kt` — pure-value wrapper around
    `org.tenshu.core.common.util.Bip39`, exposes `generateMnemonic(entropySize?)`
    and `encode(entropy)`. Validated end-to-end: apex calls produce real 24-word
    English mnemonics with valid checksums. Bundle grew from 6.6 KB to 48 KB
    once `core-common` + `@noble/hashes` were pulled in for SHA-256.
  - ✅ **Device** (prototype): `app.tenshu.sdk.Devices.demo()` returns a
    flattened `Device` snapshot. Resolved the Composite-transitive-closure
    question via a **schema-walker pattern** instead of `@JsExport`-ing
    `Composite` directly: an internal Kotlin extension
    `Composite.toJs(): dynamic` walks `composite.fields`, un-namespaces each
    field id to its last path segment (`tenshu/identity/device/deviceId` →
    `deviceId`), and unwraps leaf `Data` values to JS-native primitives
    (`TString` → `string`, `TBoolean` → `boolean`, `TByteArray` → `Int8Array`,
    nested `Composite` → recurse). The walker stays Kotlin-side and emits a
    plain JS object; only the resulting flat shape is `@JsExport`-ed via the
    `Devices` object. The TS `Device` interface in `web/sdk` matches
    one-for-one. Demo Device built via `SimpleComposite(Device.Schema, ...)`
    with `null`-context `toTString` / `toTBoolean` / `toTByteArray` factories,
    so no `TContext` dependency is needed for the prototype. See
    `tenshu-sdk-js/src/jsMain/.../internal/CompositeSnapshot.kt` and
    `Devices.kt`. Bundle grew from 48 KB to 99 KB once `:core` and
    `:identity-service` were pulled in.
  - ✅ **Notification** (prototype): `app.tenshu.sdk.Notifications.demo()`
    returns `{uuid: string, timestamp: number}`. Added the `UnixInstant`
    branch to `unwrap` (`toEpochMilliseconds().toDouble()`); JS gets epoch ms
    and uses `new Date(notification.timestamp)` for display. Demo timestamp
    stamped with `kotlin.js.Date.now()` at build-time-of-call. See
    `tenshu-sdk-js/.../Notifications.kt`. Bundle grew from 99 KB to 112 KB
    once `:notification-service` joined.
  - ✅ **File** (prototype): `app.tenshu.sdk.Files.demo()` → `{path, size,
    lastModified}`. Added the `TInteger` branch to the walker (Long → JS
    `number`, lossy beyond 2^53). File's dynamic relations
    (`File.Relation.Data`/`Preview` at `File.kt:62-78`) live outside the
    schema-fields list, so the walker doesn't see them — they need a
    separate exposure mechanism when proxy wiring lands.
  - ✅ **Folder** (prototype): `app.tenshu.sdk.Folders.demo()` → `{path,
    size, lastModified, files: File[]}`. Added the `TList<*>` branch (must
    sit before `is Composite` since `TList : Composite`). Verified that
    schema inheritance from `File` propagates through the walker — the JS
    object includes `path`/`size`/`lastModified` inherited via
    `composite(parent = File.Schema, ...)` plus the locally-added `files`.
    Bundle: 112 KB → 117 KB.
  - 🟡 **Operation interfaces** — first stubs landed:
    - One-shot: `app.tenshu.sdk.FileSystem.list(path) → Promise<Array<File>>`
      via `GlobalScope.promise { ... }`. Suitable for TanStack Query `queryFn`.
    - Stream: `app.tenshu.sdk.Notifications.observe(callback) → unsubscribe`.
      Each emission delivers the full snapshot (matches `Flow<TResult<TList<T>>>`).
      Stub appends a notification every 2 s; the returned function cancels
      the coroutine, mirroring `StreamInteractor.endStream(callback)`.
    Both stubs return canned data; the real `LocalTaskExecutor` is now
    reachable from JS at the *compilation* layer (Phase P below) but the
    *runtime* wiring (constructing an in-process `ExecutionContext` /
    `SchemaRegistry` from JS, plus a `JsFileServiceProxyImplementationFactory`
    for canvas-backed previews etc.) is the next slice. API surface stays
    the contract; executor swaps later. See `docs/web/architecture.md`
    §12.10.
  - ⬜ Request DSL helpers, schemas.
  - 🟡 **Real domain-model + operation wiring**:
    - ✅ **Phase P — `js(IR)` on the proxy modules** (`:core-proxy`,
      `:notification-service-proxy`, `:identity-service-proxy`,
      `:file-service-proxy`, `:sync-service-proxy`). Zero source changes
      required; the proxy logic was already JS-portable. See
      `docs/web/architecture.md` §12.6.1 Phase P.
    - 🟡 **JS in-process runtime, slice 1**: `JsRuntime` in
      `tenshu-sdk-js/jsMain/internal/` constructs `SchemaRegistry` +
      `TContext` (Clock-backed via the new `SystemClock` `TComponent`) on
      first access. Mirrors the production Koin module
      (`:core-common/.../di/CoreCommonModule.kt`) one-for-one, using
      `createDefaultSchemaRegistry`, `DefaultSyncCryptoManager`, etc. —
      same component graph, no Koin runtime, no test-module dep.
      `TenshuSdk.runtimeStatus()` exposes `{ ready, nowMs }` — proof-of-life
      for the wiring. Apex renders runtime status inline alongside the SDK
      version. Bundle impact: SDK 117 → 268 KB, apex 440 → 533 KB (the
      proxy infra is the cost). See `docs/web/architecture.md` §12.12.
    - ⬜ Slice 2 — per-service schema registration (file/notification/
      identity/sync), `Database`, `ClientRegistry`, `ExecutionContext`,
      per-service `OperationImplementationFactory` wiring.
    - ⬜ JS-platform-specific operation factories: `JsFileServiceProxyImplementationFactory`
      (canvas-backed previews, Blob-backed uploads), JS storage backend
      (IndexedDB-backed Database), JS sync HTTP transport.
    - ⬜ Replace stubs in `FileSystem.list` / `Notifications.observe` with
      real `findOperation<...>().asTaskInteractor().execute()` /
      `asStreamInteractor().registerCallback()` calls.
- 🟡 3.2 `@OptIn(ExperimentalJsExport)` annotations where needed; isolate
  Kotlin-internal types to keep the JS surface minimal. Pattern established by
  Bip39 wrapper: `@file:OptIn(ExperimentalJsExport::class)` at the top of each
  `tenshu-sdk-js/jsMain` wrapper file; the underlying `core-common`/feature
  module stays free of JS-specific annotations.
- 🟡 3.3 Per-service entry points in the npm package (`@tenshu/sdk/files`,
  `/identity`, `/notifications`, `/core`) per [tech-debt: Bundle-Size
  Strategy](../tech-debt.md).
  - ✅ TS-side `exports` map landed: `web/sdk/package.json` declares
    `./core`/`./identity`/`./notifications`/`./files`. Each per-service
    file re-exports its slice from a shared `_bundle.ts` import. Apex
    consumes via the per-service paths. See `docs/web/architecture.md`
    §12.11.
  - ⬜ **Kotlin-side bundle splitting**: the Kotlin webpack output is
    still a single UMD blob, so apex's bundle stays ~505 KB regardless of
    which per-service path it imports. Real savings need either multiple
    webpack outputs (one per service module) or ES-module bundle output
    that Vite can tree-shake. Sized as a separate piece of build-pipeline
    work — see `docs/tech-debt.md` "Kotlin-side bundle splitting."
  - ⬜ A `/sync` entry point lands when sync-service has user-facing JS
    surface (currently nothing for it to expose).
- 🟡 3.4 Hand-written TS adapter at `web/sdk/`: per-service adapters that
  translate Kotlin shapes (`KotlinTenshuSdk.getInstance()`, `TString.get()`,
  sealed classes) into JS-idiomatic shapes (`{ version }`, `string`,
  discriminated unions). Tracks [tech-debt: Dukat Output
  Quality](../tech-debt.md).
  - ✅ Bridge infrastructure: `web/sdk/src/index.ts` imports the Kotlin/JS
    webpack bundle (`tenshu-sdk-js/build/dist/js/productionExecutable/tenshu-sdk-js.js`)
    via a relative path; `native.d.ts` is the hand-written ambient declaration
    of the bundle shape. Apex's `vite.config.ts` whitelists `'..', '../..'`
    in `server.fs.allow` so dev can reach the bundle from outside the pnpm
    workspace root. Currently re-exports the placeholder `TenshuSdk.version`.
  - ⬜ Real per-service adapters land alongside 3.1–3.3.
- ⬜ 3.5 Decide on Dukat (autogen `.d.ts`) vs. hand-written types. Lean:
  hand-written for the public surface; auto-gen for the internal/raw types.
- ⬜ 3.6 Bundle-size budget assertion in CI: importing `@tenshu/sdk/files`
  must not pull in notification/sync schemas.

**Estimated effort**: 2–3 days for the initial pass; ongoing maintenance
as the SDK grows.

---

## Phase 4 — Crypto + storage backends **(✅ slices A + B + C + D landed)**

**Goal**: provide real (not stub) implementations of the platform-
abstracted crypto and storage primitives across all targets — JVM,
Android, JS, eventually iOS.

**Slice A — Ed25519 + BIP-39 master-key derivation (LANDED)**:

- ✅ 4.1a Crypto-backend dispatch via Koin-injected interfaces, not
  expect/actual primitives. New `Ed25519Backend` interface in
  `:core-common/security/`; per-platform impls (BouncyCastle on JVM,
  `@noble/curves` on JS) wired through an `expect val
  cryptoBackendsModule: Module` seam included by `coreCommonModule`.
  Adding a new primitive group in slice B/C = one new interface + one
  new `single<...>` binding per platform inside `cryptoBackendsModule`,
  no new expect/actual files per primitive.
- ✅ 4.4a Real Ed25519 sign/verify/derivePublicKey on JVM (BouncyCastle
  shared between Android + Desktop via the new `jvmMain` source set)
  and JS (`@noble/curves`). RFC 8032 §7.1 test vectors verify both
  backends produce the canonical signatures.
- ✅ 4.4b `DefaultSyncCryptoManager.deriveMasterKeyPair` is real:
  decodes the BIP-39 mnemonic to its 256-bit entropy via `Bip39.decode`
  (skipping BIP-39's PBKDF2-2048 seed step on purpose per
  `docs/sync/threat-model.md` T-RC-02 + the `Bip39.kt` invariant),
  domain-separates via HKDF-SHA-256 with a v1 info tag, and uses the
  32-byte output as the Ed25519 seed. Production callers
  (`CreateRealmImplementation`, `RecoveryClaimImplementation`,
  `ApprovePairingRequestImplementation`) immediately stop being stubs.
- ✅ 4.6 `Bip39.decode()` already shipped pre-Phase-4 (`Bip39.kt`).

**Slice B — X25519 ECDH + AEAD (LANDED)**:

- ✅ 4.4c Real X25519 ephemeral keypair + ECDH via BouncyCastle (JVM)
  / `@noble/curves`'s `x25519` export (JS); replaces the dummy random +
  XOR shared-secret in `DefaultSyncCryptoManager.generateKeyPair` /
  `calculateSharedSecret`. Closes the pairing-handshake confidentiality
  gap. Cross-backend agreement test vector pinned in
  `BouncyCastleX25519BackendTest.crossBackend_sharedSecretAgreement` +
  `NobleX25519BackendTest`.
- ✅ 4.4d Real **XChaCha20-Poly1305** AEAD per threat-model TA3.
  Output blob is self-contained (`nonce(24) || ciphertext || tag(16)`)
  so the existing two-arg `encrypt(payload, key)` / `decrypt(payload,
  key)` API stays workable. JVM picks BouncyCastle's
  `ChaCha20Poly1305` plus a hand-rolled HChaCha20 sub-key derivation
  (BC doesn't ship XChaCha20 directly); JS uses `@noble/ciphers`'s
  `xchacha20poly1305`. Tampered-ciphertext + wrong-key tests confirm
  the auth tag is enforced.
- ✅ 4.4e Asset A4 (per-device signing key) split off from the X25519
  pairing-keypair API: new `SyncCryptoManager.generateDeviceSigningKeyPair()`
  returns an Ed25519 keypair, distinct curve from the X25519
  ephemeral keypair `generateKeyPair()` now produces. Default impl
  delegates to `generateKeyPair()` so existing test fakes that
  override `generateKeyPair()` keep round-tripping.
- ✅ 4.4f `DefaultSyncCryptoManager.encrypt` HKDF-stretches non-32-byte
  keys to the AEAD key length. Existing callers that pass
  shared-secret bytes (32 already) or PBKDF2 wrapper keys (32 already)
  pass through unchanged; legacy callers passing arbitrary-length
  material get domain-tagged HKDF rather than the silent XOR-modulus
  the slice A stub used.

**Slice C — Argon2id KDF (LANDED)**:

- ✅ 4.4g Real Argon2id (RFC 9106 v0x13) via the new `KdfBackend`
  interface in `:core-common/security/`. JVM uses BouncyCastle's
  `Argon2BytesGenerator` (no new dependency — `bcprov` already on
  classpath); JS uses `@noble/hashes`'s sync `argon2id` export
  (`@noble/hashes` 1.4+ already on the JS classpath, no new npm
  dependency). Both backends implement the same RFC 9106 v0x13
  algorithm, so the same parameters produce byte-identical output.
  Functional tests in `BouncyCastleKdfBackendTest` pin determinism +
  parameter-sensitivity; cross-backend agreement is the spec
  compliance of the two audited libraries.
- ✅ 4.4h `DefaultSyncCryptoManager.deriveKeys` now Argon2id-stretches
  the *decoded BIP-39 entropy* (not the mnemonic string — see
  `core-common/.../util/Bip39.kt` and threat-model T-RC-02) with
  production parameters m=64 MiB / t=3 / p=1, then HKDF-domain-
  separates the 32-byte output into the shared-identity + shared-
  volume halves with distinct info tags. Replaces the slice A
  PBKDF2-2048-over-mnemonic path; rejects malformed mnemonics with
  the typed `Bip39InvalidMnemonicException` (matches
  `deriveMasterKeyPair`'s contract).
- Production Argon2id parameters live as `KdfBackend.RECOVERY_KDF_*`
  constants. Bumping them is a protocol-version change (changes all
  derived shared keys), so coordinate with the slice D recovery-blob
  format before adjusting. Re-evaluate annually per threat-model §12.

**Slice D — IndexedDB master-key wrapper (LANDED)**:

- ✅ 4.5 New `MasterKeyWrapper` in `:core-common/security/` wraps the
  master identity private key (asset A1) under a caller-supplied wrap
  key, producing a self-authenticated `WrappedMasterKey` AEAD blob via
  the slice-B XChaCha20-Poly1305 path. Wrong wrap key or corrupted
  ciphertext surfaces as the typed `MasterKeyUnwrapException`. Wrap-key
  length is delegated to slice 4.4f's HKDF stretch, so callers can pass
  raw shared-secret bytes, Argon2id-derived material, or future
  WebAuthn-PRF outputs without per-call-site length handling.
- ✅ 4.5b New JS-side `IndexedDbMasterKeyStore` (`tenshu-sdk-master-keys`
  database) holds wrapped master-key blobs keyed by realm `ownerId`,
  cache-aside per the existing IDB pattern. Wired into `JsRuntime`
  alongside the protocol / files / session / secure-storage stores:
  hydrated by `awaitReady()`, wiped by `purgeRealmStorage()` so the
  revocation-cleanup path drops the wrapped key. Per arch §4.3:
  IndexedDB only, never localStorage.
- ✅ **Slot now filled — Phase 5.7 PRF-wrap path (LANDED 2026-05-09).**
  The WebAuthn-PRF ceremony is the producer: `Session.registerPasskey`
  wraps `SyncManagerImpl.currentMasterKeyPair` (asset A1, present in memory
  after `Realm.create` / `Recovery.claim` / `Pairing.submit`) under the
  authenticator's PRF output via `MasterKeyWrapper.wrap(KeyPair, wrapKey)`
  and persists the blob to `tenshu-sdk-master-keys` keyed by
  `currentOwnerId`; `Session.unlock` unwraps the store's single entry and
  `reseatMasterKeyPair`s the result. The recovery-phrase fallback (5.3)
  also reads the store for its wrong-realm guard. Browsers without PRF
  support degrade to the opaque-session-token-only path (the master key
  stays unloaded, unwrapped). See Phase 5 below.

---

## Phase 5 — WebAuthn integration **(🟡 in progress; verify + PRF-wrap + phrase-fallback landed)**

**Goal**: real passkey-based sign-in + recovery-phrase fallback per arch §4.4.

- ✅ 5.1 Thin wrapper over `navigator.credentials.create()` for new-device
  passkey enrollment (`web/apex/src/lib/webauthn.ts` `registerPasskey()`;
  captures the COSE algorithm via `getPublicKeyAlgorithm()` so the SDK can
  pick the right WebCrypto params at verify time).
- ✅ 5.2 Wrapper over `navigator.credentials.get()` for sign-in
  (`getPasskeyAssertion()`); SDK-side verification via
  `WebAuthnVerifier.verify` over `crypto.subtle` against the stored SPKI
  public key. ECDSA P-256 (ES256) and RSASSA-PKCS1-v1_5 SHA-256 (RS256)
  supported.
- ✅ 5.4 §3 Locked flow + §4a/§4b/§7 passkey setup wired to the real SDK.
  `Session.registerPasskey(reg)` stores the credential; `Session.unlock`
  returns a typed `UnlockError` (`noPasskey | wrongCredential |
  unsupportedAlgorithm | other`) so apex routing branches correctly.
- ✅ 5.5 Session persistence in IndexedDB (`tenshu-sdk-session` records
  `deviceName | sessionToken | passkey`). 24h opaque token minted on
  successful unlock; the wrapped master keypair lives alongside it in
  the separate `tenshu-sdk-master-keys` IDB (5.7 below).
- ✅ 5.7 **PRF-extension wrap-key ceremony** (LANDED 2026-05-09). The
  apex's `webauthn.ts` requests the WebAuthn `prf` extension on both
  `registerPasskey()` and `getPasskeyAssertion()` with a fixed
  evaluation salt (`tenshu/master-key-wrap/v1`). On registration, the
  PRF output (or a follow-up assertion when `create()` reports
  `enabled: true` but no `results.first`) is surfaced on
  `PasskeyRegistration.prfOutput`; on assertion it's on
  `WebAuthnAssertion.prfOutput`. `Session.registerPasskey` wraps
  `SyncManagerImpl.currentMasterKeyPair` (asset A1, present in memory
  after `Realm.create` / `Recovery.claim` / `Pairing.submit`) under
  the PRF output via the new `MasterKeyWrapper.wrap(KeyPair, wrapKey)`
  overload (versioned plaintext layout: `0x01 || privateKey(32) ||
  publicKey(32)`), and persists the blob to `tenshu-sdk-master-keys`
  keyed by `currentOwnerId`. `Session.unlock` mirrors: after WebAuthn
  verification succeeds, it `unwrapKeyPair`s the store's single entry
  under the assertion's PRF output and `SyncManager.reseatMasterKeyPair`s
  the result, deriving ownerId from the unwrapped public half via
  `ownerIdForMasterPublicKey`. Auth-tag mismatch surfaces as a typed
  `UnlockError.kind = "masterKeyUnwrap"`; apex `LockedFlow` routes that
  case to the recovery-phrase fallback. Browsers without PRF support
  silently degrade to the opaque-session-token-only path — unlock
  continues to succeed but the master keypair stays unloaded.
- ✅ 5.3 **Recovery-phrase fallback** (LANDED 2026-05-09). New
  `Session.unlockWithPhrase(mnemonic)` SDK method on the locked-flow
  unlock path, distinct from `Recovery.claim` (the firstTime-flow path
  for "lost all devices"). No relay round-trip: derives the master
  keypair locally from the phrase via `cryptoManager.deriveMasterKeyPair`
  (HKDF, fast), runs the wrong-realm guard against
  `IndexedDbMasterKeyStore` (rejects with typed `wrongRealm` if a
  wrapped blob exists for a different ownerId), then runs Argon2id via
  `cryptoManager.deriveKeys` to populate `derivedKeys` so
  `SyncManager.isEnabled()` is true and background sync can run.
  `SyncManager.initializeWithKeys` + `reseatMasterKeyPair` together
  bring SyncManager to the same state `initialize(phrase)` would
  produce, but **without persisting the plaintext phrase**.
  `LockedFlow`'s "Lost your passkey?" CTA switches to an inline
  phrase-entry step instead of routing to `/recovery`; `/recovery`
  remains the firstTime-flow "lost all devices" path. Typed
  `PhraseUnlockError` surface: `invalidMnemonic | wrongRealm | other`.
- ⬜ 5.6 Cross-browser testing (Safari/iOS WebAuthn quirks, Firefox passkey
  behaviour, PRF-extension parity across authenticators).

**Deferred / out-of-scope for v1**:
- **Multi-passkey-per-browser** — single-passkey model in IDB; latest
  registration wins. Multi-credential lookup is a follow-up.
- **Multi-realm-per-browser** — `Session.unlock`'s unwrap path uses
  `IndexedDbMasterKeyStore.singleEntryOrNull()`. Multi-realm needs
  ownerId disambiguation (e.g. carrying the candidate ownerId on the
  assertion, or trying each entry until one unwraps).
- **PRF-degradation surfacing** — when the authenticator omits a PRF
  output the SDK falls back to today's opaque-token path silently; the
  apex doesn't warn that the master key isn't wrapped on this browser.
  Tracked in tech-debt.
- **`unsupportedAlgorithm` UX** — apex `LockedFlow` now distinguishes
  `masterKeyUnwrap` (routes copy to recovery) but still collapses
  `unsupportedAlgorithm` and `other` into the generic failure state.

**Estimated effort remaining**: 5.6 cross-browser sweep (~1 day).

---

## Phase 6 — Onboarding flows **(🔒 gated on 4 + 5)**

**Goal**: implement arch §8 end-to-end. The four onboarding states from the
doc:

- ⬜ 6.1 §8.1 — **Active session, returning user** — straight to app
  routing with cached session.
- ⬜ 6.2 §8.2 — **Locked, returning user** — unlock with passkey or
  recovery phrase.
- 🟡 6.3 §8.3a — **First-time, existing realm device** — pairing flow:
  scan QR or enter code from Drive/Control on another device, receive
  master key. **~70% built; not greenfield.** Source-verified state
  (2026-06-23):
  - ✅ **UI + state machine**: `OnboardingFlow.tsx` has the joiner steps
    (`pairing.deviceName → submit → await → terminal`); all three
    screens in `onboarding/screens/PairingScreens.tsx`; `QrScanner.tsx`
    (BarcodeDetector + paste fallback).
  - ✅ **SDK**: `Pairing.submit()` (`tenshu-sdk-js/.../Pairing.kt:105`)
    is real — parses URI, calls `client.syncService.initiatePairing()`,
    persists device name (`JsRuntime.sessionStore.setDeviceName` +
    `flush`, line 128–129), polls `getPairingStatus` → `paired`.
    Master-key delivery rides the in-process `:sync-service` client
    (same path as native); mock implements `submit` too. The agent-audit
    claims that key acquisition / sessionStore / SyncManager init are
    "missing" are **wrong** — that plumbing exists.
  - ✅ **URI-format mismatch RECONCILED (2026-06-23).** The TS pre-submit
    gate `validatePairingUri` (`src/lib/pairingUri.ts`) used to accept
    **only** the then-planned canonical `tenshu-pair:v1?o=…&d=…&k=…&s=…`
    (since renamed `tenshu://join`, 2026-07-05, with the legacy form slated
    for flag-day removal — see `sync-pairing-inverted-direction.md`), while
    the Kotlin `parsePairingUri` (`internal/PairingUri.kt`, what `submit`
    actually uses) accepts **only** the legacy
    `tenshu://pair?v=1&d=<bech32>&ownerId=…&deviceId=…` — exactly what
    Control emits today via `SyncManagerImpl.getPairingString()`. A real
    Control QR was therefore rejected at the TS gate before reaching the
    working Kotlin parser. **Both sides are now reconciled on the legacy
    form** (`PAIRING_URI_PREFIX = 'tenshu://pair'`; required params
    `d`/`ownerId`/`deviceId`, `v` optional/ignored). Reconciled in
    lockstep: the apex validator + its tests, the `mockOnboarding`
    `isValidPairingUri`, the `PairNewDeviceDialog` host-display test
    fixture, the user-facing `onboarding.json` paste placeholder +
    `wrongScheme` warning, and the `PairingSubmitError` doc-comment in
    `web/sdk/src/onboarding.ts`. The canonical migration stays a separate,
    cross-platform, out-of-web-track item; when it lands, flip all of
    these back together.
  - 🟡 **Post-pairing tail**: confirm the joiner branch (`paired` →
    `tail.passkey` → passkey register + `Session.persist`) mints a
    session and lands `active` + syncing. The state-machine wiring is now
    test-covered (`OnboardingFlow.test.tsx` joiner walk reaches the shared
    passkey tail; `PairingScreens.test.tsx` covers `paired → onContinue`),
    so the joiner reuses the new-user tail by construction. The
    *behavioural* confirmation (real `Session.persist` + sync start) still
    rides the on-device gate below.
  - ✅ **Tests (T.2 gap closed, 2026-06-23)**: `PairingScreens.test.tsx`
    (12 tests) covers the submit-screen validation gate on the legacy
    form, the SDK submit hand-off, the await-screen SAS render + terminal
    forwarding + cancel, and each terminal CTA; `OnboardingFlow.test.tsx`
    gained a joiner happy-path walk (chooser → pair → SAS → paired →
    shared passkey tail). Full apex suite green (195 tests).
  - ✅ **On-device cross-device round-trip VERIFIED (2026-06-24).** Apex
    joiner on the real SDK (no `?mock=1`) ← **Control desktop** host over a
    live relay. Full handshake on the wire: joiner `POST /sync/pairing/request
    → 200`; host approves; joiner `GET /sync/pairing/response → 200` +
    `POST /sync/pairing/ack → 200` + `GET /sync/realm-epoch → 200`; terminal
    `paired` → shared tail → **landed signed-in at `/control/home`**. The
    web-displayed SAS matched Control's and was approved on the host. This was
    the last big web-track on-device gate. **Two real issues surfaced:** (1) the relay must be run
    from its `buildFatJar` artifact (`java -jar …-all.jar`), not Gradle
    `:run`, or a concurrent shared-module rebuild corrupts the running relay's
    open jar → `NoClassDefFoundError`/`500` on the (lazily-loaded) pairing-
    list serialization path; (2) Control's host-side pairing poll
    (`SyncDashboardViewModel.startPairingObserver`) runs in short bursts and
    stops, so it routinely misses a joiner request landing just after a burst
    — the relay mailbox retains the request, so re-opening "Add device"
    re-arms the poll and surfaces it. `HostPairingMonitor` now keeps the poll
    armed for the process lifetime. (1) is filed in [`tech-debt.md`](../tech-debt.md).
  - **Out of scope**: the "browser hosts QR, phone scans" inversion —
    already filed as tech-debt ("Inverted-direction pairing for web").
  - **Revised effort**: URI reconciliation + tail wiring + tests **DONE
    (2026-06-23)**; on-device cross-device round-trip **VERIFIED
    (2026-06-24)**. Web-track 6.3 is functionally complete; the residual is
    the Control-side host-poll robustness fix (tracked in tech-debt, not a
    web-track item).
- 🟡 6.4 §8.3b — **First-time, new user** — sign-up: passkey enrollment,
  recovery phrase generation, initial keys. **Web side complete + test-
  covered (2026-06-23).** The full §4b chain is built and exercised:
  `NewUserScreens.tsx` (intro → device-name → generate → show → confirm →
  commit → failure) over the real Kotlin `Bip39` bundle (mnemonic
  generation runs unmocked in jsdom), the shared tail (passkey → sync
  warning → explainer → storage), and `Realm.create` at the SDK boundary.
  - ✅ **Tests (2026-06-23)**: `NewUserScreens.test.tsx` (11) — device-name
    default/trim/back, generate auto-advance → 24-word mnemonic,
    show-screen saved-it gate, confirm happy-path + mismatch + three-miss
    re-display lockout, commit success/failure; `MnemonicGrid.test.tsx`
    (3) — grid render + clipboard copy + auto-clear countdown;
    `OnboardingFlow.test.tsx` gained a **full new-user happy-path walk**
    (chooser → generate → scrape grid → confirm asked words → commit →
    shared passkey tail), mock-free except the `Realm.create` SDK
    boundary. Full apex suite green (214).
  - ✅ **Live-relay round-trip VERIFIED (2026-06-24)**: apex on the real
    SDK (no `?mock=1`) against a fresh `:sync-relay-server` walked the full
    new-user signup headless (puppeteer-core / system Chrome) and landed
    signed-in at `/control/services/picker`. Observed: `POST /sync/realm →
    201` (genesis), `POST /sync/push → 200 [JWT]` (genesis pushed, **JWT
    auth verified**), `POST /sync/changes` + `GET /sync/tombstones → 200
    [JWT]` (**device registration confirmed** — the device-key JWT
    verifies), IDB stores created (`master-keys`/`session`/`secure`/
    `files`). **Bug found + fixed**: React StrictMode (dev) double-invoked
    `CommittingRealmScreen`'s effect, firing two concurrent `Realm.create`
    calls that both passed the proxy's non-atomic `realmRelayRegistered`
    latch → first `POST /sync/realm` 201, second raced to a 409 ("Realm
    already exists for this ownerId") surfaced as a spurious "Couldn't set
    up your realm". Fixed apex-side with a run-once `useRef` guard
    (`NewUserScreens.tsx`) — the idiomatic StrictMode-safe guard for a
    non-idempotent effect; relay 409 semantics (load-bearing for
    `ReregisterDevice`'s admit-fallback) untouched. Production never
    double-calls (no StrictMode), so this was a dev-only / retry-path break.
    A latent op-level race in `RealmRegistrar.ensureRegistered` (check-then-
    POST across a suspend) remains, only reachable under concurrent
    `createRealm` — noted as a low-priority follow-up. **Still on-device-
    pending**: the passkey/PRF tail (walk skipped it via "Skip") and a real
    cross-device round-trip.
  - ✅ **Actuals confirmed present (2026-06-23)**: the Kotlin `@JsExport
    Realm.create` actual already exists (`Realm.kt` → `createRealm` relay
    bootstrap) **and** persists the device name on success
    (`sessionStore.setDeviceName` + `flush`). The earlier "device-name
    persistence outstanding" note was stale. Only the live-relay round-trip
    remains (on-device gate above).
- 🟡 6.5 Per-flow visuals, error states, threat-model amendments where
  needed. Error-state coverage for the onboarding flows now exists (commit
  failure, pairing terminal states, unlock errors, confirm lockout);
  per-flow **visual** polish (illustrations, transitions) is the remaining
  bulk — overlaps Phase 7.
- 🟡 6.6 **Low-friction onboarding port — apex §8** (catch web up to the
  native redesign). **Web side shipped + test-covered (2026-06-23); on-device
  gate pending** (same live-relay round-trip as 6.3/6.4). As-built:
  - §8.1 chooser reframed to three CTAs — `[Sign up free]` (primary),
    `[I have Tenshu already]` (secondary), `[Just try it locally]` (tertiary
    quiet link). `ChooserScreen` props are now `onSignUp` / `onExisting` /
    `onTryLocally`.
  - **Reorder** — `ShowMnemonicScreen` + `ConfirmMnemonicScreen` removed from
    the new-user path (`OnboardingFlow` goes intro → device-name → generate →
    commit → shared passkey/PRF tail; the phrase is generated but never shown
    in onboarding). The two screens **moved** to
    `control/recovery/RecoveryCaptureScreens.tsx` and the 6.4 `NewUserScreens`
    coverage for them followed to `RecoveryCaptureScreens.test.tsx`.
  - §7.4 **Set up account recovery** — new `AccountRecoverySetupPage` (route
    `/control/settings/recovery/setup`, entry card on `RecoveryPage`): reveal
    → show → confirm → `setRecoveryVerified` (clears the Home RecoveryBanner).
    Sources the phrase via the **`Recovery.revealPhrase()`** SDK op. ✅
    **Kotlin actual LANDED (2026-06-23)**: the `MasterKeyWrapper` blob now
    carries the BIP-39 entropy (v2 payload — `0x02 || priv || pub ||
    entropy`); the entropy is held in memory (`JsRuntime.currentEntropy`)
    from `Realm.create` / `Session.unlockWithPhrase` / a v2-blob PRF unlock,
    and `revealPhrase` re-encodes it to the mnemonic. v1 (pre-entropy) blobs
    resolve `unavailable` until a passkey re-registration upgrades them;
    joiners resolve `joinerDevice`; no realm → `locked`. Threat-model A1
    web-at-rest paragraph amended (no new trust boundary — the entropy is
    A1's own seed). ✅ **Live-SDK reveal VERIFIED (2026-06-24)**: a fresh
    signup → `/control/settings/recovery/setup` → "Show my recovery phrase"
    returned the real 24-word phrase on the live SDK (same session, entropy
    in `JsRuntime.currentEntropy`) — not the stale "coming soon"
    `unavailable` notice. Corrected the now-stale "Track-A follow-up /
    coming soon is the steady state" comments in `AccountRecoverySetupPage`.
    (`RevealPhraseDialog` on `RecoveryPage` is a *separate* older Phase-B
    primitive still staged "Coming soon" behind its WebAuthn re-challenge
    gate — not yet wired to the working `revealPhrase`; a follow-up.)
  - **Intent routing** (§8.1/§8.2) — `[Sign up free]` completes into the §5.1
    account picker (`/control/services/picker`); `[Just try it locally]`
    lands on `/control/home`, where the existing non-dismissible
    `TryModeBanner` is the conversion surface. Both run the identical local
    realm-creation flow; only the terminal navigation differs.
  - **No protocol change.** The flow reuses I7 + T-OB-01 from proposal §9
    (landed with Control's Phase 2); the entropy-at-rest change extends the
    threat-model A1 web-at-rest paragraph (carried with the code per House
    Rule #2). On web the `revealPhrase` gate is the SDK-side reveal authority.
  - **Outstanding:** §7.2 Levels 1–3 escalation + §8.2's post-upload convert
    modal not built (Level-0 nag is the standing `TryModeBanner`); on-device
    round-trip.
  <details><summary>Original scoping note (pre-build context — kept for the
  rationale)</summary>

  **⚠ Native-vs-web divergence.** Control (Android +
  Desktop) shipped the
  [`onboarding-low-friction.md`](../proposals/onboarding-low-friction.md)
  redesign — recovery-phrase capture **deferred** behind biometric / TEE
  unlock — but that proposal's **Phase 2** ("defer phrase capture") listed
  *Modules touched: `:control-shared`* only. The apex was never in it, so
  6.1–6.5 above still implement the *older*
  [`web-onboarding-ux.md`](../proposals/web-onboarding-ux.md) flow: every
  new user walks the full 24-word ceremony up front (generate → show →
  confirm, the 6.4 path). This phase ports the deferral to web per
  proposal §8 (+ the apex parts of §7):
  - §8.1 Apex Welcome — reframe the chooser to `[Sign up free]` (primary),
    `[I have Tenshu already]`, `[Just try it locally]` (tertiary); framed
    as sync-enablement, not access-gating.
  - §8.2 Try-mode — generate identity + WebAuthn-PRF unlock immediately, a
    persistent non-dismissible "trying locally" banner, a post-upload
    convert modal, phrase capture reachable from Settings. (Partial
    scaffolding already on apex from the proposal's earlier phases —
    `TryModeBanner.tsx`, `AccountPickerPage`, the Services section — but
    the chooser reword + onboarding reorder did **not** survive the
    Phase-6 rebuild: the live chooser still reads "Start fresh on this
    device" and the full phrase ceremony is intact. Verify current state
    before building.)
  - **Reorder** — remove the `ShowMnemonicScreen` + `ConfirmMnemonicScreen`
    from the onboarding path; new users go straight to the passkey/PRF
    tail. The screens are **not deleted** — they move to a Settings "Set up
    account recovery" entry (§7.4), so the 6.4 `NewUserScreens` tests still
    cover that surface.
  - §7.2 escalation — Level 0 hint + the persistent recovery-phrase
    affordance on web.
  - Threat-model — reuses I7 + T-OB-01 from §9 (landed with Control's Phase
    2); confirm the apex port needs no *new* amendment beyond a surface
    note. ⚠ Deferring on web is riskier than on Android (a browser profile
    can be cleared at any time), which is why §8 keeps signup eagerly
    prompted — the deferral is the *third* CTA, not the default.

  </details>

**Estimated effort**: 6.4 web side done; remaining is the on-device relay
round-trip (~½ day) + 6.5 visual polish (folds into Phase 7) + 6.6
low-friction port (~2–3 days; a flow reorder + Try-mode, no protocol
change).

---

## Session & device exit — self-revoke **(✅ Phase B landed 2026-06-23; on-device verify pending)**

**Goal**: a web "sign out everywhere" / disconnect that actually removes
this browser from the realm, so it doesn't linger as a *ghost device* in
other devices' lists.

**Landed — Phase A, local session-exit affordances:**

- ✅ `SignedInBanner` **Lock** → `Session.clear({})` → `/lock`. Reversible;
  keeps the wrapped keys in IDB; does **not** touch realm membership, so no
  revocation needed.
- ✅ `LockedFlow` **"Disconnect this browser"** escape on the welcome +
  unlock-failed cards → confirm → `Session.clear({ purgeRealm: true })` →
  `/` (firstTime). Unblocks a wedged passkey ceremony (e.g. Chrome).
- ✅ `LandingCtaProvider` re-probes `Session.detect()` on `visibilitychange`
  so an exit performed elsewhere reflects without a hard reload.

**The gap — Phase B (now closed):** Phase A's exits were **local-only
purges**. `Session.clear({ purgeRealm: true })` wipes this browser's IDB
but never emitted a `RevokeDevice` tombstone, so other devices kept
listing this browser until it was manually revoked from one of them. On
web, `DeviceDetailPage` already revokes *other* devices through the
existing JS `RevokeDevice` bridge and deliberately disables Revoke for
"this device" — but the *self*-revoking variant belongs on the
active-session exit, not that detail page (B.3).

**Landed — Phase B (2026-06-23):**

- ✅ B.1 Active-state **"sign out everywhere"** (`DeviceMenu`) now calls
  the new SDK op `Devices.revokeSelf()` and **pushes the tombstone before
  wiping**. Ordering is load-bearing: the wipe destroys the signing key +
  device JWT, so a wipe-first path can never project the revocation
  (permanent ghost). `revokeSelf()` resolves the current device id
  SDK-side (the apex has no reliable handle — `Device.isCurrent` is empty
  in practice and name-matching is ambiguous) and resolves `ok` only once
  the tombstone is pushed; the apex then does the local wipe + redirect.
- ✅ B.2 **Offline** policy — **warn-and-proceed**. When `revokeSelf()`
  fails (network / relay error) the confirm modal switches to a warning
  ("we couldn't reach the relay to revoke this browser … it will keep
  showing on your other devices until you remove it there"), then the user
  can sign out locally anyway; the ghost is reaped by revoking from another
  device. (Rejected: block-until-online, defer-wipe-behind-retry — both
  contradict "disconnect now.")
- ✅ B.3 Surface placement — the self-revoking variant lives on the
  **active-session** `DeviceMenu` "Sign out everywhere"; the locked-flow
  disconnect stays a local-only purge (no master key in memory there to
  sign the tombstone). `SignedInBanner` keeps its Lock-only affordance.
- ✅ B.4 Threat-model — **no new amendment needed.** Self-revoke reuses the
  device-revocation broadcast already covered by
  [ADR 0007](../adr/0007-sync-device-management.md) + synced
  device-management (`RevokeDevice` + `DeleteBlock(revokedDeviceId)` +
  realm-epoch advance, threat-model I4 + §8.5 "Key management and
  revocation"). The
  revoking device is itself the target, so there is no new trust boundary
  or asset; the only new surface is a UI affordance over the existing op.
- 🟢 **Relay-side self-revoke VERIFIED (2026-06-24)**: apex on the real SDK
  against a live relay — signup → `DeviceMenu` → "Sign out everywhere" →
  confirm pushed the tombstone (`POST /sync/push-tombstone → 200 [JWT]`)
  **before** the wipe, and the relay then **rejected the device's own JWT**
  (`POST /sync/changes → 401 "Invalid token"`) — proving the revocation took
  effect server-side — followed by local wipe + redirect to landing. The
  revoke→wipe ordering (tombstone pushed before the wipe) held. (The trailing 401s are the background
  sync poller racing the wipe; benign teardown noise.)
- 🟡 **On-device (cross-device)**: the ghost-reaping half (tombstone reaches
  a *second* device → its row drops) still needs a second device — same gate
  as 6.3. Single-device relay propagation is now verified (above). Web side is
  fully test-covered (`DeviceMenu.test.tsx`: revoke-before-wipe ordering,
  offline warn-and-proceed, cancel, lock-now-local-only).

**As-built note**: implemented as a small SDK contract addition
(`Devices.revokeSelf()` in `web/sdk/src/identity.ts` + `native.d.ts`) plus
apex wiring. Composing the existing `Devices.revoke(deviceId)` was rejected:
the apex can't reliably resolve its own device id, and a dedicated
self-revoke keeps the revoke→wipe ordering enforceable.

✅ **Kotlin actual LANDED (2026-06-23)**: `@JsExport Devices.revokeSelf`
(`tenshu-sdk-js/.../Devices.kt`) resolves `currentDeviceId` from the
`SyncManager`, calls `RevokeDevice` (which *emits* a `DeleteBlock`
tombstone — it does **not** push synchronously), then forces
`SyncScheduler.drainNow()` to push the tombstone to the relay **before
resolving** — honouring the load-bearing push-before-wipe ordering. The
scheduler is reached via a new `syncSchedulerHolder` on the JS
`RuntimeContext` (mirrors `tombstoneEmitterHolder`), populated by the sync
plugin's `initialize` from `SyncProxyComponents.scheduler`. Cold-boot race
(scheduler unwired) → `network` error rather than a falsely-claimed push.
On-device round-trip still the done-gate.

---

## Phase 7 — Full apex content **(🟡 in progress; routing + 8-block scroll landed)**

**Goal**: build out arch §3-§9 and [`web-about-tenshu-public.md`](../proposals/web-about-tenshu-public.md) 8-block
narrative visually.

- 🟡 7.1 Full `/` 8-block scroll per
  [`about-tenshu-public.md`](../proposals/web-about-tenshu-public.md) +
  [`web-apex-landing.md`](../proposals/web-apex-landing.md). Routing
  (landing at `/`, OnboardingRouter moved to `/start`), smart CTA per
  §4 (firstTime → `/start`, active → `/control`, locked → `/lock`),
  marketing chrome (header + footer), persistent "you're signed in"
  banner, and copy for all eight blocks landed.
- ✅ 7.2 Hero illustration — the code-drawn line art (`M1Hero`) is the final
  art ([`landing-illustrations.md`](../design/landing-illustrations.md)).
- ✅ 7.3 Mockups — superseded: Blocks 2–4 carry line art (`ProblemArt`,
  `AnswerArt`, `RealmArt`), the apps block real screenshots (the carousel);
  the rest of the old M-catalog was dropped. Block 8's hardware photos are
  still placeholders.
- ⬜ 7.4 PWA manifest for the apex (per arch §6).
- 🟡 7.5 Brand polish. **Landed**: the brand codify step for apex (per
  `docs/design/brand/brand-agent.md` "How to codify" → Web) — amethyst
  palette → CSS custom properties in `web/apex/src/index.css`, and the
  Outline + Solid cat marks (v7, ADR 0004) copied to
  `web/apex/src/components/marks/`. The shared `BrandLockup` (wordmark +
  Outline mark) replaces the old "Tenshu apex" text in every navbar
  (marketing / onboarding / control); the favicon SVG is the Solid mark
  on an Amethyst badge, enlarged to read at 16px. Theming deviation
  documented inline in `index.css`: tokens applied by
  prefers-color-scheme media query (apex has no theme switcher) rather
  than the contract's literal `[data-theme]` selector; `[data-theme]`
  overrides kept for a future switcher. Only the canonical amethyst
  palette is wired (slate unused in apex). **Outstanding**: codify
  type / radii / spacing / motion tokens; Open Graph + Twitter-card
  art (still the placeholder cross on slate); favicon raster set
  (16/32/180/192/512). Favicon call made: the **simplified** variant
  (head + ears + whiskers silhouette, no eyes/mouth) for 16px
  legibility; the in-app `components/marks/` keep the full face.
- 🟢 7.6 FAQ section (block 8) — collapsible `<details>` per source doc.

**Estimated effort**: 3–5 days for content/layout; design assets are a
separate effort.

---

## Phase 8 — First-party Tenshu apps **(🔒 gated on 6 + functional SDK)**

**Goal**: drive.tenshu.app — the first per-app subdomain, demonstrating the
apex/subdomain split.

**Design**: [`docs/proposals/drive-web-app.md`](../proposals/drive-web-app.md) —
scope triage, the thin-SDK-vs-VM-reuse decision (locked: thin SDK + TS rebuild), the
per-feature SDK boundary inventory, and the A→B→C phasing the items below map onto.

- ✅ 8.1 Add `web/drive/` workspace member; same Vite + TS + Tailwind
  setup, consuming `@tenshu/sdk`. **(Phase A)** — third workspace package;
  hidden-iframe proxy client; Phase A hello-world page (bridge ping +
  `fs.list("/")` round-trip) lands the origin split end-to-end.
- ✅ 8.2 Implement postMessage IPC between subdomain (drive.tenshu.app) and
  apex (tenshu.app) per arch §3.2. **(Phase A)** — `@tenshu/sdk/bridge`
  (typed envelope + `mountApexProxy` client + `serveProxyGateway`, origin
  allowlist = the §5.2 admission boundary); apex mounts the gateway in
  `main.tsx` with `bridge.ping` + `fs.list` handlers. 8 bridge tests.
- 🟡 8.3 Manifest validation, app installation flow at apex side. **(Phase A
  partial)** — wire-level `tenshu.*` manifest **validator** landed
  (`web/apex/src/lib/tenshuManifest.ts`, 9 tests) + Drive's served
  `manifest.webmanifest`. The apex-side **install/grant flow** (§6 steps 3–5,
  Rei signature + bundle `versionHash` verify) remains.
- ⬜ 8.4 Drive-specific UI: file browser, upload, folder navigation. Reuses
  `:file-service` operations exposed via SDK. **(Phase C)** — gated on the
  Phase B SDK boundaries (`docs/proposals/drive-web-app.md` §4).

**Phasing**: see [`docs/proposals/drive-web-app.md`](../proposals/drive-web-app.md)
— **A** (subdomain shell + postMessage IPC) ✅ landed; **B** (per-feature SDK
boundaries) → **C** (React UI parity).

**Estimated effort**: 1–2 weeks once the SDK is functional.

### 8.5 Timeline — the **bridge only** (✅ landed; no app)

`:timeline-service` is now reachable from the web: `:tenshu-sdk-js` gained a
`TimelinePlugin` + an `@JsExport Timeline` object, `@tenshu/sdk/timeline`
exposes it, and `web/apex`'s `proxyGateway` serves `timeline.{moments,moment,
trips,traces}`. Read-only — mutations (rename / merge / setRole / recordTrip)
are deliberately absent — and shaped to mirror `TimelineProjectionRepository`'s
`MOMENT_FIELDS` / `TRIP_FIELDS` / `TRACE_FIELDS` projections rather than
inventing new ones.

There is **no `web/timeline/` app**, on purpose: [ADR 0020](../adr/0020-web-app-structure.md)
records that `@tenshu/app-kit` lands with app #2, so the first new app surface
is the slice that extracts it — a larger piece of work than this bridge.

Two carry-overs a Timeline UI will hit:

- ⚠ **Subjects arrive un-hydrated.** A moment names its subject as
  `{subjectKind, subjectId}`; resolving a `place` or a `workout` needs
  `:place-service` / `:health-service`, neither of which is bridged. Only
  `trace` resolves today (same service). The native projector does this join
  at read time.
- ⚠ **Reconciliation is not applied.** `moments()` returns the raw relation,
  superseded rows included. Half-applying the projector's `reconcile` here
  would give a JS consumer a subtly *different* feed from the native one;
  `supersededBy` is exposed so a consumer can at least filter the stored half.

⚠⚠ **Plugin ordering is load-bearing**: `TimelinePlugin` sits *before*
`SyncPlugin` in `JsRuntimeBuilder`, with no `dependsOn`, because
`initTimelineService` registers `MomentMerger`/`TripMerger` into the
`SemanticMergerRegistry` that `SyncPlugin.initialize` hands to
`initSyncService`. Copying `ContactPlugin`'s `dependsOn = {"sync"}` would
invert that. Pinned by `JsRuntimePluginTest`.

---

## Cross-cutting work *(parallel to phases above)*

### Testing infrastructure

- ✅ T.1 Vitest + jsdom landed in `web/apex` with first pure-logic tests
  (pairing-URI parsing, browser-family detection, confirm-position
  picker, `Bip39.decode` → `RecoveryError` mapping). React Testing
  Library + `jest-dom` matchers + `cleanup` afterEach added in the
  same PR as the first component tests below.
- 🟡 T.2 Component tests landed for: `LandingCta` (slot variants +
  loading-disabled), `OnboardingRouter` (three landing-state
  branches + BootSplash), `ControlShell` (loading state, five-section
  nav, NavLink active styling), `LockedFlow` (welcome card +
  pre-detect routing + happy-path passkey unlock + WebAuthn cancel +
  SDK wrongCredential + phrase-entry submit gate), `RecoveryFlow`
  (24-word entry gate + entry → deviceName → processing →
  tail.passkey on success + `noBackupOnRelay` failure card with
  retry + Cancel link), `OnboardingFlow` (chooser dispatches into
  both branches, intro → device-name forward, and the back-navigation
  invariant that distinguishes new-user back → intro from pairing
  back → chooser; deeper happy-path walks deferred to T.3 Playwright).
  Control-plane: `RecoveryBanner`, `NotificationFeed` (empty / list
  with sort + count + singular vs plural / mark-all-read / per-row
  clear / show-older pagination at >20 items), `AppsListPage`,
  `AppDetailPage` (unknown id, full Drive manifest render including
  permission badges, disabled per-app actions, back link),
  `DevicesListPage`, `DeviceDetailPage` (unknown id, "this device"
  disables Revoke, happy-path revoke routes back to list, network
  failure surfaces friendly retry copy in the dialog), the
  control-plane `RecoveryPage` (loading / verified / never-verified
  panels + static manifest + action buttons + lost-everything link).
  Dialogs: `TestRecoveryDialog` (24-word gate, happy path with IDB
  stamp + onVerified callback, retry-from-failed, error-copy
  variants), `RevealPhraseDialog` (gate disabled when WebAuthn
  unsupported, happy path → "coming soon" stage, cancelled WebAuthn
  copy, stale-assertion defence-in-depth fallback),
  `PairNewDeviceDialog` (starting spinner, network failure, showing
  with pairingUri, request → SAS render → approve drives the
  session, paired success + onPaired callback, approve failure
  surfaces inline without leaving the request step). Remaining:
  `HomePage` (thin wrapper around RecoveryBanner + NotificationFeed
  — deferred to T.3), and any new screens added during Phase 6+.
- ⬜ T.3 Playwright e2e: route navigation, dark-mode auto-switch, basic
  accessibility (axe).
- ⬜ T.4 SDK adapter unit tests in `web/sdk` once it has real content
  (Phase 3+).
- ⚪ T.5 Visual regression — Argos / Chromatic on critical routes.

### CI

- ✅ C.1 GitHub Actions workflow for `web/` landed
  (`.github/workflows/web.yml`): builds `:tenshu-sdk-js` (so the JS
  bundle is on disk), then `pnpm install` → lint (`eslint`) →
  typecheck + build (`tsc -b && vite build`) → test (`vitest`).
  Path-filtered to web + KMP modules surfaced through the bundle, with
  `concurrency: cancel-in-progress` so superseded runs are killed.
- ✅ C.1b `web/drive` joined the gate. C.1 ran the lint/build/test trio for
  **`apex` only** — `web/drive`, a full workspace app with 26 test files, was
  never linted, typechecked or run in CI at all. Three `pnpm -F drive` steps
  now mirror the apex ones.
  ⚠⚠ **Both lint steps were red on arrival, from pre-existing code**: `apex`
  10 errors, `drive` 7. Neither set was introduced by adding the steps —
  apex's step has existed all along and CI has been dark across every lane
  since 2026-06-30 — they were simply the first errors anyone had ever seen
  from these packages.
- ✅ C.1c Those 17 errors fixed (nothing suppressed; both packages now lint
  clean). Three classes, three shapes of fix:
  - **`no-unused-vars` ×3** — every site was already written `_scope` /
    `_deviceName` / `_id`, i.e. the "deliberately unused" convention, but the
    rule ran at its default settings. Both `eslint.config.js` files now set
    `argsIgnorePattern: '^_'` (plus the vars / caught-errors / destructured
    twins) so the convention is what it always read as.
  - **`react-hooks/set-state-in-effect` ×5** — all five were the same shape:
    a *derivable* fact written into state from an effect body. `QrDisplay` /
    `QrImage` reset `failed` synchronously on every input change → one state
    tagged with the input that produced it, compared at render.
    `FileThumbnail` minted an object URL into state (and cleared it for every
    non-image row — a cascading render per row on a list that can be
    thousands long) → `useMemo` for the URL, the effect only revokes it. The
    `/i` redeem + `/s/:shareId` viewer routes wrote a "link arrived without
    its fragment" error phase → that is a property of the URL, so it is now
    derived at render (`view`) and the effect just returns early.
  - **`react-refresh/only-export-components` ×9** — four modules mixing
    components with plain exports, which costs *both* halves their fast
    refresh. Split along the existing seam: `AppRegistryContext` /
    `PublisherContext` → a component-free `appRegistryContext.ts` /
    `publisherContext.ts` (context object + hooks) beside an
    `AppRegistryProvider.tsx` / `PublisherClientProvider.tsx`;
    `buildPreview` / `download` / the `Preview` type out of
    `FilePreview.tsx` and into `lib/preview.ts`, where `previewKind` already
    lived; `useFileOpener`'s two local chrome components out to
    `components/FileOpenerOverlays.tsx`. No behaviour change — call sites
    only re-point their imports.
- ⬜ C.2 Bundle-size budget enforcement (size-limit or
  `@vercel/style-guide` bundlewatcher).
- ⬜ C.3 Preview deploys per PR (Cloudflare Pages or Vercel).
- ⬜ C.4 Production deploy on tag.
- ⬜ C.5 Kotlin/JS Gradle build wired into the same workflow so SDK changes
  trigger a rebuild + test.

### Quality / DX

- ⬜ Q.1 axe-core in tests for accessibility regression.
- ⬜ Q.2 Color contrast validation per ADR 0001 (WCAG AA on body / primary
  / containers).
- ⬜ Q.3 Keyboard navigation audit on every interactive element.
- ⚪ Q.4 Token parity automation: generate Tailwind theme directly from
  Kotlin via a Gradle task, replacing the hand-mirror in `index.css`. Do
  this once token churn warrants the build complexity.

---

## Suggested execution order

Concrete first-week sequence for highest impact:

1. **Phase 2** (~1 day) — small, mechanical, unlocks meaningful SDK surface
   for Phase 3.
2. **T.1 + T.2** (~half day) — get tests running so subsequent work is
   verifiable.
3. **C.1** (~half day) — CI: typecheck/lint/test/build on every PR.
4. Then either **Phase 3** (real SDK surface) or **Phase 7** (apex content)
   depending on whether the next priority is technical or visual progress.

**Phase 4 + 5** are best done together when there's appetite for the auth
deep-end. **Phase 6** flows naturally from those.

---

## Updating this doc

When a phase ships:

- Tick the phase items here.
- Update the corresponding tech-debt entry in
  [`../tech-debt.md`](../tech-debt.md).
- Update [§15 "What's Next" in `architecture.md`](architecture.md) so the
  high-level list reflects current state.
- If decisions change (e.g. swapping libsodium for `@noble/curves`), amend
  this doc + the tech-debt entry in the same PR.
