// Apex-boundary mock for the four onboarding singletons declared in
// `@tenshu/sdk/onboarding`. Track A delivers the real Kotlin/JS bindings;
// until they land, this mock satisfies the same interfaces with canned
// responses + artificial delays so Track B's flows are clickable
// end-to-end. Per the Track B handover memo the substitution happens at
// the application boundary (provider in `OnboardingSdkContext`); the SDK
// re-export is left untouched.
//
// Mock behaviours can be steered from the URL or session storage so the
// dev surface (`MockDevtools` overlay) can jump between states. Every
// async call has a short artificial delay so spinners and intermediate
// screens are observable.
import type {
  HostPairingSession,
  HostPairingStatus,
  OfferPairingSession,
  OfferPairingStatus,
  PairingApi,
  PairingSession,
  PairingShowError,
  PairingStatus,
  PairingSubmitError,
  PasskeyRegistration,
  PhraseUnlockError,
  RealmApi,
  RecoveryApi,
  RecoveryError,
  RevealPhraseError,
  SessionApi,
  SessionState,
  SessionToken,
  TResult,
  UnlockError,
  WebAuthnAssertion,
} from '@tenshu/sdk/onboarding'
import { Bip39 } from '@tenshu/sdk/core'

const MOCK_STORAGE_PREFIX = 'tenshu-mock:'

function readScenario(key: string): string | null {
  if (typeof window === 'undefined') return null
  const fromUrl = new URLSearchParams(window.location.search).get(key)
  if (fromUrl !== null) return fromUrl
  return window.sessionStorage.getItem(MOCK_STORAGE_PREFIX + key)
}

function setStored(key: string, value: string | null): void {
  if (typeof window === 'undefined') return
  if (value === null) window.sessionStorage.removeItem(MOCK_STORAGE_PREFIX + key)
  else window.sessionStorage.setItem(MOCK_STORAGE_PREFIX + key, value)
}

function readJson<T>(key: string): T | null {
  if (typeof window === 'undefined') return null
  const raw = window.sessionStorage.getItem(MOCK_STORAGE_PREFIX + key)
  if (raw === null) return null
  try {
    return JSON.parse(raw) as T
  } catch {
    return null
  }
}

function writeJson(key: string, value: unknown): void {
  if (typeof window === 'undefined') return
  window.sessionStorage.setItem(MOCK_STORAGE_PREFIX + key, JSON.stringify(value))
}

function clearAllMockStorage(): void {
  if (typeof window === 'undefined') return
  const keys: string[] = []
  for (let i = 0; i < window.sessionStorage.length; i++) {
    const k = window.sessionStorage.key(i)
    if (k !== null && k.startsWith(MOCK_STORAGE_PREFIX)) keys.push(k)
  }
  keys.forEach(k => window.sessionStorage.removeItem(k))
}

function delay(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms))
}

// -- Session ---------------------------------------------------------------

interface PersistedRealm {
  readonly deviceName: string
  readonly hasPasskey: boolean
  // The 24-word phrase, retained so the §7.4 "Set up account recovery" flow
  // (`Recovery.revealPhrase`) can re-display it after onboarding. Present
  // only on master devices created via `Realm.create`; a joiner paired via
  // `Pairing.submit` never receives the phrase (see the real
  // `RevealPhraseError.joinerDevice` case), so this stays undefined there.
  readonly mnemonic?: string
}

interface PersistedToken {
  readonly hex: string
  readonly expiresAtMs: number
}

interface PersistedPasskey {
  readonly credentialIdHex: string
  readonly publicKeyHex: string
  readonly algorithm: number
}

const REALM_KEY = 'realm'
const TOKEN_KEY = 'token'
const PASSKEY_KEY = 'passkey'
const SCENARIO_DETECT = 'scenario'

function bytesToHex(bytes: Int8Array): string {
  let out = ''
  for (let i = 0; i < bytes.length; i++) {
    const b = bytes[i]! & 0xff
    out += b.toString(16).padStart(2, '0')
  }
  return out
}

function hexToBytes(hex: string): Int8Array {
  const out = new Int8Array(hex.length / 2)
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16)
  }
  return out
}

function randomBytes(length: number): Int8Array {
  const u8 = new Uint8Array(length)
  crypto.getRandomValues(u8)
  const i8 = new Int8Array(length)
  for (let i = 0; i < length; i++) i8[i] = u8[i]!
  return i8
}

const mockSession: SessionApi = {
  async detect(): Promise<SessionState> {
    await delay(120)
    const override = readScenario(SCENARIO_DETECT)
    if (override === 'firstTime') return { kind: 'firstTime' }
    if (override === 'locked') {
      const realm = readJson<PersistedRealm>(REALM_KEY)
      return { kind: 'locked', deviceName: realm?.deviceName ?? 'This Browser' }
    }
    if (override === 'active') {
      const realm = readJson<PersistedRealm>(REALM_KEY)
      return { kind: 'active', deviceName: realm?.deviceName ?? 'This Browser' }
    }
    const token = readJson<PersistedToken>(TOKEN_KEY)
    const realm = readJson<PersistedRealm>(REALM_KEY)
    if (realm === null) return { kind: 'firstTime' }
    if (token === null || token.expiresAtMs <= Date.now()) {
      return { kind: 'locked', deviceName: realm.deviceName }
    }
    return { kind: 'active', deviceName: realm.deviceName }
  },

  async persist(token: SessionToken): Promise<void> {
    await delay(80)
    writeJson(TOKEN_KEY, {
      hex: bytesToHex(token.bytes),
      expiresAtMs: token.expiresAtMs,
    } satisfies PersistedToken)
  },

  async clear(options?: { readonly purgeRealm?: boolean }): Promise<void> {
    await delay(60)
    setStored(TOKEN_KEY, null)
    if (options?.purgeRealm === true) {
      setStored(REALM_KEY, null)
      setStored(PASSKEY_KEY, null)
      setStored(SCENARIO_DETECT, null)
    }
  },

  async registerPasskey(registration: PasskeyRegistration): Promise<TResult<void>> {
    await delay(80)
    const scenario = readScenario('registerPasskey')
    if (scenario === 'fail') {
      return { ok: false, error: 'mock: passkey registration rejected' }
    }
    writeJson(PASSKEY_KEY, {
      credentialIdHex: bytesToHex(registration.credentialId),
      publicKeyHex: bytesToHex(registration.publicKey),
      algorithm: registration.algorithm,
    } satisfies PersistedPasskey)
    const realm = readJson<PersistedRealm>(REALM_KEY)
    if (realm !== null) {
      writeJson(REALM_KEY, { ...realm, hasPasskey: true } satisfies PersistedRealm)
    }
    return { ok: true, value: undefined }
  },

  async unlock(assertion: WebAuthnAssertion): Promise<TResult<SessionToken, UnlockError>> {
    await delay(450)
    const scenario = readScenario('unlock')
    if (scenario === 'noPasskey') {
      return { ok: false, error: { kind: 'noPasskey' } }
    }
    if (scenario === 'fail' || scenario === 'wrongCredential') {
      return { ok: false, error: { kind: 'wrongCredential' } }
    }
    const stored = readJson<PersistedPasskey>(PASSKEY_KEY)
    if (stored === null) {
      return { ok: false, error: { kind: 'noPasskey' } }
    }
    // The mock doesn't run the WebCrypto verify (Track A's job); it only
    // checks the credentialId matches the registered passkey so dev flows
    // can surface the wrongCredential branch realistically.
    if (bytesToHex(assertion.credentialId) !== stored.credentialIdHex) {
      return { ok: false, error: { kind: 'wrongCredential' } }
    }
    const token: SessionToken = {
      bytes: randomBytes(32),
      expiresAtMs: Date.now() + 24 * 60 * 60 * 1000,
    }
    return { ok: true, value: token }
  },

  async unlockWithPhrase(
    mnemonic: string,
  ): Promise<TResult<SessionToken, PhraseUnlockError>> {
    // §6.2 spec timing for the firstTime recovery flow says 5–10 s for
    // the Argon2id stretch on real hardware. The unlock-with-phrase path
    // hits the same KDF, so the mock approximates the same UX delay.
    await delay(1500)
    const scenario = readScenario('unlockWithPhrase')
    if (scenario === 'wrongRealm') {
      return { ok: false, error: { kind: 'wrongRealm' } }
    }
    if (scenario === 'fail') {
      return {
        ok: false,
        error: { kind: 'other', message: 'mock: unlock-with-phrase rejected' },
      }
    }
    const decoded = Bip39.decode(mnemonic.trim())
    if (decoded.kind !== 'ok') {
      return {
        ok: false,
        error: {
          kind: 'invalidMnemonic',
          reason:
            decoded.kind === 'unknownWord'
              ? { kind: 'unknownWord', word: decoded.word ?? '' }
              : decoded.kind === 'invalidLength'
                ? { kind: 'invalidLength', wordCount: decoded.wordCount }
                : { kind: 'checksumMismatch' },
        },
      }
    }
    const token: SessionToken = {
      bytes: randomBytes(32),
      expiresAtMs: Date.now() + 24 * 60 * 60 * 1000,
    }
    return { ok: true, value: token }
  },
}

// -- Realm -----------------------------------------------------------------

const mockRealm: RealmApi = {
  async create(args): Promise<TResult<void>> {
    // §4b.2 spec: 1–2s. Mock the Argon2id-derived key wrapper compute.
    await delay(1400)
    const scenario = readScenario('realm')
    if (scenario === 'fail') {
      return { ok: false, error: 'mock: realm create failed' }
    }
    const trimmed = args.deviceName.trim()
    const deviceName = trimmed === '' ? 'This Browser' : trimmed
    writeJson(REALM_KEY, {
      deviceName,
      hasPasskey: false,
      mnemonic: args.mnemonic,
    } satisfies PersistedRealm)
    // §4b/§4a both proceed to passkey setup before persisting a session;
    // we leave the session unset here so the apex routes through passkey
    // configuration before landing on the control plane.
    return { ok: true, value: undefined }
  },

  // A stable fake owner id once a realm exists. The commerce surface keys every
  // call by it, so mock mode has to answer or the whole account flow reads as
  // "set up this device first" — the one state the mock cannot get out of.
  //
  // ⚠ Shaped like a real one (hex) but obviously not one. It is only ever sent
  // to a commerce service the mock never talks to.
  async ownerId(): Promise<string | null> {
    const realm = readJson<PersistedRealm>(REALM_KEY)
    return realm === null ? null : MOCK_OWNER_ID
  },
}

/** Fixed so a reload keeps the same account binding in mock mode. */
export const MOCK_OWNER_ID = '0'.repeat(63) + '1'

// -- Pairing ---------------------------------------------------------------

// Reconciled on the canonical role-host form Control emits (see pairingUri.ts):
//   tenshu://join?o=<ownerId>&d=<base32(deviceId)>&k=<base32(pubkey)>&s=<base32(secret)>
const PAIRING_URI_PREFIX = 'tenshu://join'

function isValidPairingUri(uri: string): boolean {
  if (!uri.startsWith(PAIRING_URI_PREFIX + '?')) return false
  const params = new URLSearchParams(uri.slice(PAIRING_URI_PREFIX.length + 1))
  return ['o', 'd', 'k', 's'].every(k => params.has(k) && params.get(k) !== '')
}

function generateSas(): string {
  const value = Math.floor(Math.random() * 1_000_000)
  const padded = value.toString().padStart(6, '0')
  return padded.slice(0, 3) + ' ' + padded.slice(3, 6)
}

const mockPairing: PairingApi = {
  async submit(args): Promise<TResult<PairingSession, PairingSubmitError>> {
    await delay(280)
    const scenario = readScenario('pairing')
    if (scenario === 'invalidUri') {
      return { ok: false, error: { kind: 'invalidUri' } }
    }
    if (scenario === 'network') {
      return { ok: false, error: { kind: 'network' } }
    }
    if (!isValidPairingUri(args.pairingUri)) {
      return { ok: false, error: { kind: 'invalidUri' } }
    }
    const sas = generateSas()
    let status: PairingStatus = { kind: 'awaiting', sas }
    const subscribers = new Set<(status: PairingStatus) => void>()
    let cancelled = false

    function emit(next: PairingStatus): void {
      if (cancelled) return
      status = next
      subscribers.forEach(cb => cb(next))
    }

    // Per dev scenario, decide outcome and a delay after submit.
    const outcome: PairingStatus['kind'] =
      scenario === 'denied'
        ? 'denied'
        : scenario === 'expired'
          ? 'expired'
          : scenario === 'error'
            ? 'error'
            : 'paired'
    const ms =
      scenario === 'expired'
        ? 5000
        : scenario === 'denied'
          ? 2400
          : scenario === 'error'
            ? 2400
            : 3000

    setTimeout(() => {
      if (cancelled) return
      if (outcome === 'paired') emit({ kind: 'paired' })
      else if (outcome === 'denied') emit({ kind: 'denied' })
      else if (outcome === 'expired') emit({ kind: 'expired' })
      else emit({ kind: 'error', message: 'mock pairing error' })
    }, ms)

    if (outcome === 'paired') {
      const trimmed = args.deviceName.trim()
      const deviceName = trimmed === '' ? 'This Browser' : trimmed
      // Persist on success so the eventual passkey screen can persist a session.
      setTimeout(() => {
        if (cancelled) return
        writeJson(REALM_KEY, { deviceName, hasPasskey: false } satisfies PersistedRealm)
      }, ms)
    }

    const session: PairingSession = {
      observe(callback: (status: PairingStatus) => void): () => void {
        subscribers.add(callback)
        callback(status)
        return () => {
          subscribers.delete(callback)
        }
      },
      async cancel(): Promise<void> {
        cancelled = true
        subscribers.clear()
      },
    }
    return { ok: true, value: session }
  },

  async show(): Promise<TResult<HostPairingSession, PairingShowError>> {
    // Minimal mock: returns a session that emits a fake `showing` URI,
    // then transitions to `request` after 1.5s, and resolves to `paired`
    // when the user calls approve(). Lets the dev surface (?mock=1)
    // exercise the pair-new-device flow end-to-end without a relay.
    await delay(150)
    const scenario = readScenario('pairingShow')
    if (scenario === 'network') return { ok: false, error: { kind: 'network' } }
    if (scenario === 'fail') return { ok: false, error: { kind: 'other', message: 'mock: show failed' } }

    const subscribers = new Set<(status: HostPairingStatus) => void>()
    let currentStatus: HostPairingStatus = {
      kind: 'showing',
      pairingUri: 'tenshu://join?o=mock-owner&d=MOCKDEVICE&k=MOCKPUBKEY&s=MOCKSECRET',
    }
    let terminal = false

    function emit(next: HostPairingStatus, isTerminal: boolean) {
      if (terminal) return
      currentStatus = next
      subscribers.forEach(cb => cb(next))
      if (isTerminal) {
        terminal = true
        subscribers.clear()
      }
    }

    const requestTimer = window.setTimeout(() => {
      if (terminal) return
      emit(
        {
          kind: 'request',
          deviceId: 'mock-incoming',
          deviceName: 'Mock Phone',
          sas: '482 173',
        },
        false,
      )
    }, 1500)

    const session: HostPairingSession = {
      observe(callback) {
        subscribers.add(callback)
        callback(currentStatus)
        return () => {
          subscribers.delete(callback)
        }
      },
      async approve() {
        await delay(300)
        emit({ kind: 'paired' }, true)
        return { ok: true, value: undefined }
      },
      async cancel() {
        window.clearTimeout(requestTimer)
        emit({ kind: 'cancelled' }, true)
      },
    }
    return { ok: true, value: session }
  },

  async offer(_deviceName): Promise<TResult<OfferPairingSession, PairingSubmitError>> {
    // Minimal mock: emits `showing` with a fake offer URI, then resolves to
    // `paired` after a short delay so the dev surface (?mock=1) can exercise the
    // "this browser shows the QR" flow without a relay.
    await delay(150)
    const scenario = readScenario('pairing')
    if (scenario === 'network') return { ok: false, error: { kind: 'network' } }
    if (scenario === 'invalidUri') return { ok: false, error: { kind: 'other', message: 'mock: offer failed' } }

    const pairingUri = 'tenshu://offer?k=MOCKPUB&g=MOCKSIGN&d=MOCKDEVICE&s=MOCKSECRET&n=This%20Browser'
    const subscribers = new Set<(status: OfferPairingStatus) => void>()
    let currentStatus: OfferPairingStatus = { kind: 'showing', pairingUri }
    let terminal = false

    function emit(next: OfferPairingStatus, isTerminal: boolean) {
      if (terminal) return
      currentStatus = next
      subscribers.forEach(cb => cb(next))
      if (isTerminal) {
        terminal = true
        subscribers.clear()
      }
    }

    const outcome: OfferPairingStatus =
      scenario === 'denied'
        ? { kind: 'denied' }
        : scenario === 'expired'
          ? { kind: 'expired' }
          : { kind: 'paired', sas: '482 913' }
    const pairTimer = window.setTimeout(() => emit(outcome, true), 3000)

    const session: OfferPairingSession = {
      pairingUri,
      observe(callback) {
        subscribers.add(callback)
        callback(currentStatus)
        return () => {
          subscribers.delete(callback)
        }
      },
      async cancel() {
        window.clearTimeout(pairTimer)
        terminal = true
        subscribers.clear()
      },
    }
    return { ok: true, value: session }
  },
}

// -- Recovery --------------------------------------------------------------

// Exported for unit tests; consumers should still go through
// `Recovery.claim`, which composes this with the artificial relay
// delay + scenario overrides.
export function classifyMnemonic(mnemonic: string): RecoveryError | null {
  // Real BIP-39 validation via the Kotlin SDK — same code path the
  // production `Recovery.claim` will use before any relay round-trip.
  const result = Bip39.decode(mnemonic)
  switch (result.kind) {
    case 'ok':
      return null
    case 'invalidLength':
      return {
        kind: 'invalidMnemonic',
        reason: { kind: 'invalidLength', wordCount: result.wordCount },
      }
    case 'unknownWord':
      return {
        kind: 'invalidMnemonic',
        reason: { kind: 'unknownWord', word: result.word ?? '' },
      }
    case 'checksumMismatch':
      return { kind: 'invalidMnemonic', reason: { kind: 'checksumMismatch' } }
  }
}

const mockRecovery: RecoveryApi = {
  async claim(args): Promise<TResult<void, RecoveryError>> {
    // Up-front BIP-39 validation, mirroring the real impl which calls
    // `Bip39.decode` before any relay round-trip.
    const decodeError = classifyMnemonic(args.mnemonic)
    if (decodeError !== null) return { ok: false, error: decodeError }

    // §6.2 spec: 5–10s for Argon2id derive + relay fetch.
    await delay(5200)

    const scenario = readScenario('recovery')
    if (scenario === 'noBackup') return { ok: false, error: { kind: 'noBackupOnRelay' } }
    if (scenario === 'kdfStale') return { ok: false, error: { kind: 'kdfParametersOutOfDate' } }
    if (scenario === 'network') return { ok: false, error: { kind: 'network' } }
    if (scenario === 'checksum') {
      return {
        ok: false,
        error: { kind: 'invalidMnemonic', reason: { kind: 'checksumMismatch' } },
      }
    }

    const trimmed = args.deviceName.trim()
    const deviceName = trimmed === '' ? 'This Browser' : trimmed
    writeJson(REALM_KEY, { deviceName, hasPasskey: false } satisfies PersistedRealm)
    return { ok: true, value: undefined }
  },

  async revealPhrase(): Promise<TResult<string, RevealPhraseError>> {
    // §7.4 deferred capture. The real SDK gates on a master-key-in-memory
    // check + WebAuthn-PRF unwrap; the mock approximates the unwrap cost so
    // the dev surface shows a spinner, then returns the phrase persisted at
    // `Realm.create`. A scenario override drives the error branches.
    await delay(400)
    const scenario = readScenario('revealPhrase')
    if (scenario === 'unavailable') return { ok: false, error: { kind: 'unavailable' } }
    if (scenario === 'locked') return { ok: false, error: { kind: 'locked' } }
    if (scenario === 'joiner') return { ok: false, error: { kind: 'joinerDevice' } }
    const realm = readJson<PersistedRealm>(REALM_KEY)
    if (realm === null) return { ok: false, error: { kind: 'locked' } }
    // A joiner (paired, no phrase) has no phrase to reveal.
    if (realm.mnemonic === undefined) return { ok: false, error: { kind: 'joinerDevice' } }
    return { ok: true, value: realm.mnemonic }
  },
}

// -- Composite mock + utilities -------------------------------------------

export interface OnboardingMockState {
  readonly hasRealm: boolean
  readonly hasToken: boolean
  readonly deviceName: string | null
  readonly tokenExpiresAtMs: number | null
}

export function readMockState(): OnboardingMockState {
  const realm = readJson<PersistedRealm>(REALM_KEY)
  const token = readJson<PersistedToken>(TOKEN_KEY)
  return {
    hasRealm: realm !== null,
    hasToken: token !== null,
    deviceName: realm?.deviceName ?? null,
    tokenExpiresAtMs: token?.expiresAtMs ?? null,
  }
}

export function resetMockState(): void {
  clearAllMockStorage()
  // Strip query overrides so reset doesn't immediately re-apply them.
  if (typeof window !== 'undefined' && window.location.search !== '') {
    const url = new URL(window.location.href)
    url.search = ''
    window.history.replaceState({}, '', url.toString())
  }
}

export const MockOnboardingSdk = {
  Session: mockSession,
  Realm: mockRealm,
  Pairing: mockPairing,
  Recovery: mockRecovery,
}

// `Session.peek` — a runtime-free landing probe. Only the real SDK has one (the mock IS runtime-free,
// so its `detect` serves); `LandingCtaProvider` prefers it when present.
export type OnboardingSdk = Omit<typeof MockOnboardingSdk, 'Session'> & {
  readonly Session: (typeof MockOnboardingSdk)['Session'] & {
    readonly peek?: () => Promise<SessionState>
  }
}

export { hexToBytes }
