// Onboarding entry point: types and singletons for the four web onboarding
// flows specified in `docs/proposals/web-onboarding-ux.md`. This file is the
// **contract** between Track A (Kotlin/JS implementation) and Track B (apex
// UI). Track B builds against these types using a mock implementation in
// `web/apex/src/lib/`; Track A's `@JsExport` Kotlin objects satisfy the
// same types and swap in transparently.
//
// Status: wired. The runtime singletons exported from this file all delegate
// to Track A's `@JsExport` Kotlin objects (see the bottom of this file); the
// mock lives at the application boundary in `web/apex/src/lib/`, not here.
// Open questions are flagged inline.

import * as bundle from './_bundle'

// ---------------------------------------------------------------------------
// Session: what state is the apex in on `tenshu.app` load?
// ---------------------------------------------------------------------------

// Per `web-onboarding-ux.md` §2 (Three Landing States). `Session.detect()` is
// synchronous from the apex's POV — no network round-trip — and inspects
// IndexedDB for the apex origin.
export type SessionState =
  // Has IDB state and a valid session token; route to control plane.
  | { readonly kind: 'active'; readonly deviceName: string }
  // Has IDB state but the session token is missing or expired; show §3 unlock.
  | { readonly kind: 'locked'; readonly deviceName: string }
  // No IDB state at all; show §4 first-time chooser.
  | { readonly kind: 'firstTime' }

export interface SessionApi {
  // Synchronous landing-state detection per §2. Reads IDB metadata only —
  // does not attempt unlock or contact the relay.
  detect(): Promise<SessionState>

  // Persists a freshly-derived session token in IndexedDB with the
  // configured lifetime (default 24h per UX §5). Called after `unlock()`
  // succeeds or after a fresh onboarding completes.
  persist(token: SessionToken): Promise<void>

  // Wipes session token + (optionally) device-local realm state. Used for
  // explicit log-out, and as part of revocation cleanup. The `purgeRealm`
  // flag controls whether IDB realm data is destroyed too — log-out
  // typically keeps it (so unlock works next time), revocation wipes it.
  clear(options?: { readonly purgeRealm?: boolean }): Promise<void>

  // Stores a freshly-registered passkey for this browser. The apex runs
  // `navigator.credentials.create()` (see web `webauthn.ts`) and hands the
  // resulting registration to the SDK so subsequent `unlock(assertion)`
  // calls have a stored credential to verify against. Single-passkey model
  // in v1: re-registering replaces any previously-stored passkey.
  registerPasskey(registration: PasskeyRegistration): Promise<TResult<void>>

  // Verifies a WebAuthn assertion against the passkey registered via
  // `registerPasskey` and mints an opaque 24h session token. The actual
  // `navigator.credentials.get()` ceremony lives on the web side (see
  // UX §3.2); this call takes the assertion result and verifies it via
  // WebCrypto against the stored SPKI public key. v1 returns a random
  // token; integration with master-key wrapping is gated on Phase 4
  // crypto (`docs/web/roadmap.md`).
  unlock(assertion: WebAuthnAssertion): Promise<TResult<SessionToken, UnlockError>>

  // Phase 5.3 recovery-phrase fallback for the locked-flow unlock path.
  // Used when the user's passkey doesn't work (lost, never set up, PRF
  // unavailable, masterKeyUnwrap) but the device still has its realm IDB
  // state (`Session.detect() === 'locked'`). Distinct from
  // `Recovery.claim` — that's the firstTime-flow path that does a relay
  // round-trip to fetch the encrypted backup blob; this method does no
  // relay calls. The master keypair and shared derived keys are
  // re-derived locally from the mnemonic. Slow operation (~1–2 s on web)
  // because the Argon2id stretch runs to populate the shared derived
  // keys; the apex's screen mirrors the existing RecoveryFlow's
  // "5–10 seconds" copy.
  unlockWithPhrase(
    mnemonic: string,
  ): Promise<TResult<SessionToken, PhraseUnlockError>>
}

export interface SessionToken {
  // Opaque to JS. Stored in IDB; not displayed.
  readonly bytes: Int8Array
  // Epoch ms when this token expires. Apex compares against `Date.now()`
  // in `detect()` to decide active vs. locked.
  readonly expiresAtMs: number
}

// Subset of `PublicKeyCredential` plus the assertion response, shaped for
// the JS/Kotlin boundary. Track B is responsible for the
// `navigator.credentials.get()` call and producing this struct.
export interface WebAuthnAssertion {
  readonly credentialId: Int8Array
  readonly clientDataJson: Int8Array
  readonly authenticatorData: Int8Array
  readonly signature: Int8Array
  readonly userHandle: Int8Array | null
  // Phase 5 PRF-extension output, used by `Session.unlock` to unwrap the
  // realm's master keypair (asset A1) on this device. Optional because
  // browsers without PRF support omit it; in that case unlock still
  // mints an opaque session token (today's behaviour) but the master
  // keypair stays unloaded — callers that need the full unlock should
  // route to the recovery-phrase fallback. See `docs/web/architecture.md`
  // §4.4.
  readonly prfOutput?: Int8Array
}

// Subset of `AuthenticatorAttestationResponse` shaped for the JS/Kotlin
// boundary. The apex's `webauthn.ts` produces this struct; the SDK
// stores it in IndexedDB so subsequent `unlock(assertion)` calls have a
// public key to verify against.
export interface PasskeyRegistration {
  readonly credentialId: Int8Array
  // SubjectPublicKeyInfo (SPKI) DER. The SDK imports this directly via
  // `crypto.subtle.importKey('spki', ...)` for assertion verification.
  readonly publicKey: Int8Array
  // COSE algorithm identifier (-7 = ES256, -257 = RS256). Picks the
  // WebCrypto algorithm parameters used by `unlock()`.
  readonly algorithm: number
  readonly clientDataJson: Int8Array
  readonly attestationObject: Int8Array
  // Phase 5 PRF-extension output captured during the registration
  // ceremony (or the immediate follow-up assertion when the browser
  // doesn't deliver PRF on `create()`). When present, the SDK uses it
  // as the wrap key for the realm's master keypair so the next page
  // load can unwrap on `Session.unlock`. Browsers without PRF support
  // omit it; the SDK falls back to today's opaque-session-token path
  // silently.
  readonly prfOutput?: Int8Array
}

export type UnlockError =
  // No passkey has been registered for this browser. The apex should
  // route the user back to recovery (the §3 fallback in UX).
  | { readonly kind: 'noPasskey' }
  // The assertion's credential ID doesn't match the registered passkey,
  // or the signature failed verification. Apex shows the §3 "couldn't
  // verify" prompt with a try-again CTA.
  | { readonly kind: 'wrongCredential' }
  // The stored passkey uses an algorithm WebCrypto can't import on this
  // browser. Should be impossible in practice (the apex only requests
  // ES256/RS256 at registration); included for completeness.
  | { readonly kind: 'unsupportedAlgorithm'; readonly algorithm: number }
  // Phase 5 unwrap path — the WebAuthn assertion verified, but the
  // wrapped master keypair couldn't be unwrapped under the PRF output
  // (auth-tag mismatch / corrupted blob). The credential is still
  // valid; the master keypair is missing or the wrap key is wrong.
  // Apex routes to recovery-phrase fallback.
  | { readonly kind: 'masterKeyUnwrap' }
  | { readonly kind: 'other'; readonly message: string }

// Phase 5.3 — typed errors for `Session.unlockWithPhrase`. The apex's
// phrase-unlock screen handles each branch with a distinct error copy,
// mirroring `RecoveryError` for the firstTime-flow recovery path.
export type PhraseUnlockError =
  // Bip39.decode failed — surfaced before any expensive crypto, same
  // pattern as `Recovery.claim`.
  | { readonly kind: 'invalidMnemonic'; readonly reason: Bip39DecodeError }
  // The phrase derives a master keypair whose ownerId doesn't match the
  // one already stored locally (in `tenshu-sdk-master-keys`). Indicates
  // the user typed a phrase for a different realm — likely a typo or
  // wrong-account paste; either way, we won't overwrite local realm
  // state. Apex copy: "this phrase doesn't match this browser's realm".
  | { readonly kind: 'wrongRealm' }
  | { readonly kind: 'other'; readonly message: string }

// ---------------------------------------------------------------------------
// Realm: first-device bootstrap (§4b — new user)
// ---------------------------------------------------------------------------
//
// The new-user flow holds the mnemonic in apex React state from §4b.2
// (display) through §4b.4 (confirm) to here. There is no separate
// `MasterIdentity.generate/commit` step in the SDK — the apex calls the
// existing `Bip39.generateMnemonic()` from `@tenshu/sdk` for §4b.2 and
// passes the same string to `Realm.create()` once the user confirms.
// Per UX §7.1 the mnemonic leaves no persistent trace until `Realm.create`
// resolves successfully (the realm's IDB state is the commit point).
//
// Wraps `:sync-service` `CreateRealm` per `docs/sync/pairing-flow.md` §13
// (first-device bootstrap). On success the relay has stored
// `(ownerId → masterPublicKey)` and registered this device's public key
// — subsequent JWT-authenticated relay calls verify.

export interface RealmApi {
  create(args: {
    // The 24-word mnemonic the apex generated via `Bip39.generateMnemonic()`
    // and the user has confirmed in §4b.4.
    readonly mnemonic: string
    readonly deviceName: string
  }): Promise<TResult<void>>
  // This realm's `ownerId` — the keyspace name every commerce endpoint is keyed
  // by — or `null` before one exists (no realm yet, or locked and the master key
  // not derived). Deliberately not a `TResult`: absence is an ordinary state
  // here, not a failure to report.
  ownerId(): Promise<string | null>
}

// ---------------------------------------------------------------------------
// Pairing: §4a (existing-realm-device) — web is the requester
// ---------------------------------------------------------------------------
//
// **Protocol direction (decided 2026-05-08):** the web runs the existing
// `pairing-flow.md` requester role — Device A (existing) hosts the QR,
// the web (Device B) scans/pastes it. UX `web-onboarding-ux.md` §4a.1's
// "browser shows QR" layout is rewritten in this contract: the apex
// instead shows a scan-or-paste affordance from the start. The "browser
// shows QR, phone scans" variant is the offer flow
// (`docs/proposals/sync-pairing-inverted-direction.md`); its
// `OfferPairingSession` joins this contract with P3/P5 of that proposal.
//
// `submit({pairingUri, deviceName})` parses the canonical `tenshu://join?...` URI
// per `sync-pairing-flow-improvements.md` §8, calls `:sync-service`
// `InitiatePairing(ownerId, pairingSecret, deviceName)` to register on
// the relay + generate this device's ephemeral keys, then polls
// `GetPairingStatus(pairingSecret, otherDevicePublicKey)` until terminal.
// Returns a session whose `observe()` emits status transitions and whose
// `cancel()` burns the local context (per pairing-flow.md §5).

export interface PairingApi {
  // Submit pairing material the user obtained from their existing device
  // (scanned via the apex's webcam helper or pasted manually as the
  // `tenshu://join?...` URI). Resolves with a session whose `observe()`
  // emits status transitions; the initial state is `awaiting`.
  // `submit()` itself returns once `InitiatePairing` has registered on
  // the relay — i.e. after the web has its own ephemeral keys and is
  // waiting for the host to approve.
  submit(args: {
    // The full canonical `tenshu://join?o=...&d=...&k=...&s=...` URI.
    // Track B owns parsing the QR / manual base32 entry; the SDK only
    // takes the canonical URI and re-parses it server-side.
    readonly pairingUri: string
    readonly deviceName: string
  }): Promise<TResult<PairingSession, PairingSubmitError>>

  // Phase B host-side pairing (per `web-control-plane.md` §4.3 — the
  // Devices section's "Pair new device" CTA). Defaults to the host
  // role: this device shows a pairing code, the user scans it from
  // their other device. Wraps `:sync-service`'s
  // `StartPairing` + `GetPendingPairingRequests` + `ApprovePairingRequest`
  // + `StopPairing` — same operation set `:control-shared`'s
  // `SyncDashboardViewModel` consumes on Android + desktop.
  show(): Promise<TResult<HostPairingSession, PairingShowError>>

  // Offer-flow onboarding (`sync-pairing-inverted-direction.md` §5.1): THIS
  // browser is the *new* device — it displays the QR and an existing realm
  // device scans it. The inverse of `submit()` and the natural web ergonomics
  // (laptop shows, phone scans). Wraps `:sync-service`'s `OfferPairing` +
  // `GetOfferStatus` + `CancelPairingOffer`. Resolves immediately with a
  // session carrying the `tenshu://offer?…` URI to render; `observe()` then
  // emits `showing` -> `paired`/`denied`/`expired`/`error` as the responder acts.
  offer(deviceName: string): Promise<TResult<OfferPairingSession, PairingSubmitError>>
}

// Offerer-side session (`sync-pairing-inverted-direction.md` §5.1). `pairingUri`
// is the `tenshu://offer?…` code the apex renders as a QR (the offerer's device
// displays it). Terminal on `paired` | `denied` | `expired` | `error`.
export interface OfferPairingSession {
  readonly pairingUri: string
  observe(callback: (status: OfferPairingStatus) => void): () => void
  cancel(): Promise<void>
}

export type OfferPairingStatus =
  // The QR is being shown; waiting for an existing device to scan + approve.
  | { readonly kind: 'showing'; readonly pairingUri: string }
  // The responder approved; keys are installed proxy-side. `sas` is shown for
  // the user's post-hoc comparison against the responder's approval prompt.
  | { readonly kind: 'paired'; readonly sas: string }
  // The responder declined.
  | { readonly kind: 'denied' }
  // The 5-minute pairing window elapsed with no response.
  | { readonly kind: 'expired' }
  | { readonly kind: 'error'; readonly message: string }

// Host-side counterpart to `PairingSession`. Apex consumers pass a
// callback to `observe()` to react to status transitions; the session
// terminates on `paired`, `cancelled`, or `error`. `approve(deviceId)`
// is intentionally explicit: the request status carries the deviceId
// the user is approving, but UI surfaces both deviceName + SAS so the
// user can confirm they're approving the right peer.
export interface HostPairingSession {
  observe(callback: (status: HostPairingStatus) => void): () => void
  approve(deviceId: string): Promise<TResult<void, ApprovePairingError>>
  cancel(): Promise<void>
}

export type HostPairingStatus =
  // Pairing code is being shown; waiting for a peer device to scan it.
  // The `pairingUri` is the canonical `tenshu://join?...` URI.
  // Apex renders it as text + QR.
  | { readonly kind: 'showing'; readonly pairingUri: string }
  // A peer has submitted a request; waiting for the user to approve
  // or cancel. `sas` is the proxy-derived 6-digit SAS for OOB
  // verification per `sync-pairing-flow-improvements.md` §3.2 — null
  // when the proxy hasn't computed it yet (timing race; the apex
  // falls through to deviceName-only confirmation, matching v1 native
  // UX).
  | {
      readonly kind: 'request'
      readonly deviceId: string
      readonly deviceName: string
      readonly sas: string | null
    }
  // Approve succeeded; peer is now an authorised device in the realm.
  | { readonly kind: 'paired' }
  // User cancelled the host session. The proxy's
  // `activePairingContext` is cleared.
  | { readonly kind: 'cancelled' }
  | { readonly kind: 'error'; readonly message: string }

export type PairingShowError =
  | { readonly kind: 'network' }
  | { readonly kind: 'other'; readonly message: string }

export type ApprovePairingError =
  | { readonly kind: 'network' }
  | { readonly kind: 'other'; readonly message: string }

export interface PairingSession {
  // Subscribe to status transitions. The callback is invoked with the
  // current state on subscribe and on every change until terminal
  // (`paired` | `denied` | `expired` | `error`). Returns an unsubscribe
  // function; the SDK also cleans up internally on terminal.
  observe(callback: (status: PairingStatus) => void): () => void

  // Cancel/burn the local pairing context. Idempotent. After a terminal
  // status the SDK has already cleaned up; calling this is a no-op.
  cancel(): Promise<void>
}

export type PairingSubmitError =
  // The submitted URI didn't parse as the canonical
  // `tenshu://join?o=...&d=...&k=...&s=...` form an existing realm
  // device emits. Covers the retired legacy `tenshu://pair?...` shape
  // and unknown role hosts too — the apex's pre-submit gate
  // (`web/apex/src/lib/pairingUri.ts`) owns the per-reason copy.
  | { readonly kind: 'invalidUri' }
  // Couldn't reach the relay to register this device's ephemeral key.
  | { readonly kind: 'network' }
  | { readonly kind: 'other'; readonly message: string }

export type PairingStatus =
  // Waiting for the host to approve the request (UX §4a.2 — "Approve on
  // your existing device"). The SAS per pairing-flow-improvements §3.2
  // is shown here so the user can verify it matches what the host
  // displays.
  | { readonly kind: 'awaiting'; readonly sas: string }
  // Pairing succeeded; master keys received and SyncManager initialized.
  | { readonly kind: 'paired' }
  // The host denied (pairing-flow-improvements §10).
  | { readonly kind: 'denied' }
  // 5-minute pairing window elapsed.
  | { readonly kind: 'expired' }
  | { readonly kind: 'error'; readonly message: string }

// ---------------------------------------------------------------------------
// Recovery: §6 (lost all devices, recovery-phrase entry)
// ---------------------------------------------------------------------------
//
// Wraps `:sync-service` `RecoveryClaim`. The Kotlin side already wires
// `Bip39.decode` so a mistyped phrase fails fast with a typed error
// before any relay round-trip — see `RecoveryClaimImplementation`.

export interface RecoveryApi {
  claim(args: {
    readonly mnemonic: string
    readonly deviceName: string
  }): Promise<TResult<void, RecoveryError>>

  // §7.4 "Set up account recovery" — re-reads the 24-word BIP-39 phrase for
  // the deferred-capture flow (`docs/proposals/onboarding-low-friction.md`
  // §7.1/§7.4). Distinct from `claim`, which is the lost-all-devices entry
  // path: `revealPhrase` reads the phrase this device already holds so the
  // user can write it down after they've decided Tenshu is worth keeping.
  //
  // The phrase is shown only inside an unlocked session; the Kotlin actual
  // is the authoritative gate (it owns the master-key-in-memory check and,
  // on web, the WebAuthn-PRF unwrap of the persisted entropy). The v2
  // `tenshu-sdk-master-keys` wrap blob carries the BIP-39 *entropy* alongside
  // the master keypair, so the real binding reconstructs the phrase. A legacy v1
  // (keypair-only) blob resolves `unavailable`, which apex flows render as
  // "coming soon".
  //
  // ⚠⚠ A realm whose phrase is shorter than 24 words is PATH-DEPENDENT, not
  // simply unavailable. The v2 blob carries exactly 32 entropy bytes and
  // 12/15/18/21-word phrases yield 16/20/24/28, so `Recovery.claim` declines to
  // hold them and reveal reports `unavailable` — but `Session.unlockWithPhrase`
  // and `Realm.create` have no such check, so there reveal SUCCEEDS and the next
  // passkey registration throws instead. See `tech-debt.md`.
  //
  // ⚠ It was also every browser restored through `Recovery.claim`, until
  // 2026-09-14: `claim` validated the mnemonic and discarded the entropy where
  // `Realm.create` and `Session.unlockWithPhrase` retain it, so a recovered
  // host had a master key it could use and a phrase it could not show. `claim`
  // now retains it on success.
  revealPhrase(): Promise<TResult<string, RevealPhraseError>>
}

export type RevealPhraseError =
  // The session has no master key in memory (locked / never initialised).
  | { readonly kind: 'locked' }
  // The SDK can't reconstruct the phrase: this host's wrap blob predates the
  // entropy-carrying v2 format, or the realm's phrase is shorter than 24 words
  // and this host reached it through `Recovery.claim`, which declines entropy
  // the blob cannot carry. Re-registering a passkey after a phrase unlock
  // upgrades the first case. Surfaced as "coming soon".
  | { readonly kind: 'unavailable' }
  // Joiner-only device: per §7.4 the phrase never left the originator, so
  // there's nothing to reveal here.
  | { readonly kind: 'joinerDevice' }
  | { readonly kind: 'other'; readonly message: string }

export type RecoveryError =
  // Bip39.decode failed — surfaced before any network call.
  | { readonly kind: 'invalidMnemonic'; readonly reason: Bip39DecodeError }
  // Relay has no backup blob for the derived ownerId. UX §6 distinguishes
  // this case so the message can suggest "try again in a few minutes".
  | { readonly kind: 'noBackupOnRelay' }
  // KDF parameters from the backup blob predate the current libsodium
  // build. Recovery still possible per UX §6, just slower.
  | { readonly kind: 'kdfParametersOutOfDate' }
  // This device already belongs to a DIFFERENT realm (T-RC-09) — refused before any
  // relay call. The user disconnects this browser first; retrying cannot help.
  | { readonly kind: 'deviceAlreadySetUp' }
  | { readonly kind: 'network' }
  | { readonly kind: 'other'; readonly message: string }

export type Bip39DecodeError =
  | { readonly kind: 'invalidLength'; readonly wordCount: number }
  | { readonly kind: 'unknownWord'; readonly word: string }
  | { readonly kind: 'checksumMismatch' }

// ---------------------------------------------------------------------------
// Shared result shape
// ---------------------------------------------------------------------------

export type TResult<T, E = string> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly error: E }

// ---------------------------------------------------------------------------
// Singletons
// ---------------------------------------------------------------------------
//
// Track A delivers Kotlin `@JsExport` objects in `:tenshu-sdk-js` that
// satisfy these interfaces; this file re-exports them via `_bundle`. All
// singletons below now bridge to Kotlin. Apex tests substitute a mock at
// the application boundary (see `web/apex/src/lib/onboardingSdk.tsx`)
// rather than rebinding this file.

// Delegates to the Kotlin `@JsExport object Session` in `:tenshu-sdk-js`.
// Every operation (`detect`, `persist`, `clear`, `registerPasskey`, `unlock`,
// `unlockWithPhrase`) is wired Kotlin-side; the apex's `webauthn.ts` runs the
// `navigator.credentials.{create,get}` ceremonies and hands the results here.
// Alongside the opaque session token, `registerPasskey` wraps the master key
// under the WebAuthn-PRF output and `unlock` unwraps it; browsers without PRF
// fall back to the token alone.
const sessionKotlin = bundle.Session.getInstance()

export const Session: SessionApi = {
  detect: () => sessionKotlin.detect(),
  persist: (token) => sessionKotlin.persist(token),
  clear: (options) => sessionKotlin.clear(options ?? null),
  registerPasskey: (registration) =>
    sessionKotlin.registerPasskey(registration) as ReturnType<SessionApi['registerPasskey']>,
  unlock: (assertion) =>
    sessionKotlin.unlock(assertion) as ReturnType<SessionApi['unlock']>,
  unlockWithPhrase: (mnemonic) =>
    sessionKotlin.unlockWithPhrase(mnemonic) as ReturnType<
      SessionApi['unlockWithPhrase']
    >,
}

// Delegates to the Kotlin `@JsExport object Realm` in `:tenshu-sdk-js`.
// The Kotlin side takes positional `(mnemonic, deviceName)`; this adapter
// destructures the contract args before forwarding.
const realmKotlin = bundle.Realm.getInstance()

export const Realm: RealmApi = {
  create: ({ mnemonic, deviceName }) => realmKotlin.create(mnemonic, deviceName),
  ownerId: () => realmKotlin.ownerId(),
}

// Delegates to the Kotlin `@JsExport object Pairing`. The session object
// the Kotlin side returns inside `value` is a plain JS object whose
// `observe`/`cancel` already match the `PairingSession` interface, so no
// per-method wrapping needed here — just pass the result through.
const pairingKotlin = bundle.Pairing.getInstance()

export const Pairing: PairingApi = {
  submit: ({ pairingUri, deviceName }) =>
    pairingKotlin.submit(pairingUri, deviceName),
  show: () =>
    pairingKotlin.show() as ReturnType<PairingApi['show']>,
  offer: (deviceName) =>
    pairingKotlin.offer(deviceName) as ReturnType<PairingApi['offer']>,
}

// Delegates to the Kotlin `@JsExport object Recovery`. Bip39 validation
// runs inside the Kotlin wrapper before the relay is touched so a typo
// resolves to a typed `invalidMnemonic` reason without a network round-trip.
const recoveryKotlin = bundle.Recovery.getInstance()

export const Recovery: RecoveryApi = {
  claim: ({ mnemonic, deviceName }) =>
    recoveryKotlin.claim(mnemonic, deviceName),
  // The wrap blob now carries the BIP-39 entropy (v2 `MasterKeyWrapper`
  // payload), so the Kotlin actual reconstructs the phrase from the entropy
  // held in memory since `Realm.create` / `unlockWithPhrase` / a v2-blob PRF
  // unlock. Older v1 blobs (pre-entropy) resolve `unavailable` until a passkey
  // re-registration upgrades them — the apex renders that as "coming soon". A
  // realm on a phrase shorter than 24 words resolves it only when this host came
  // through `Recovery.claim`; see the contract comment above for why the other
  // entry paths behave differently.
  revealPhrase: () =>
    recoveryKotlin.revealPhrase() as ReturnType<RecoveryApi['revealPhrase']>,
}

// Leftover from the contract-draft era: `bundle` is referenced directly by the
// four `getInstance()` calls above, so nothing here is unused any more and this
// statement is a no-op.
void bundle
