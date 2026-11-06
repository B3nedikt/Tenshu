// Browser-side WebAuthn ceremony helpers for §3.2 (unlock), §4a.4 / §4b.5
// (passkey registration). The Track B handover memo locks these to the
// web side: `navigator.credentials.create/get` are browser APIs, not SDK
// calls. The PublicKeyCredential is converted into the SDK's
// `WebAuthnAssertion` shape (Int8Arrays) and handed off to
// `Session.unlock` / a future passkey-registration SDK call.
//
// Phase 5 PRF extension (`docs/web/architecture.md` §4.4): both ceremonies
// request the WebAuthn `prf` extension with a fixed evaluation salt. When
// the authenticator + browser support it, the resulting per-credential PRF
// output is surfaced on `PasskeyRegistration.prfOutput` /
// `WebAuthnAssertion.prfOutput` and used by the SDK as the wrap key for
// the master keypair (asset A1). Chrome ≥132 returns PRF on `create()`;
// older builds and some authenticators only deliver it on `get()`, so
// `registerPasskey` runs an immediate follow-up assertion to extract the
// output when `create()` reports `enabled: true` but no `results.first`.
// Browsers without PRF support omit the field; the SDK falls back to
// today's opaque-session-token path silently.
import type { WebAuthnAssertion } from '@tenshu/sdk/onboarding'

const RP_ID = typeof window === 'undefined' ? 'tenshu.app' : window.location.hostname
const RP_NAME = 'Tenshu'

// PRF evaluation salt for the master-key wrap. Fixed per-app value so the
// authenticator returns the same PRF output across all calls — the
// `create()` ceremony uses it to wrap the master keypair, the matching
// `get()` ceremony unwraps it. Treated as a domain-separation tag, not a
// secret: rotating it would invalidate every existing wrapped blob, so
// the constant is pinned at v1.
const PRF_SALT_LABEL = 'tenshu/master-key-wrap/v1'
const PRF_SALT: Uint8Array<ArrayBuffer> = (() => {
  const encoded = new TextEncoder().encode(PRF_SALT_LABEL)
  const out = new Uint8Array(new ArrayBuffer(encoded.byteLength))
  out.set(encoded)
  return out
})()

export type WebAuthnFailureKind = 'cancelled' | 'wrongCredential' | 'hardware' | 'unsupported' | 'unknown'

export interface WebAuthnFailure {
  readonly kind: WebAuthnFailureKind
  readonly message: string
}

export function isWebAuthnSupported(): boolean {
  return typeof window !== 'undefined' && typeof window.PublicKeyCredential === 'function'
}

function bufferToInt8(buffer: ArrayBuffer | Uint8Array | null): Int8Array {
  if (buffer === null) return new Int8Array(0)
  return buffer instanceof Uint8Array ? new Int8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength) : new Int8Array(buffer)
}

function randomChallenge(): Uint8Array<ArrayBuffer> {
  const bytes = new Uint8Array(new ArrayBuffer(32))
  crypto.getRandomValues(bytes)
  return bytes
}

function int8ToFreshUint8(source: Int8Array): Uint8Array<ArrayBuffer> {
  // Copy through a fresh ArrayBuffer so the resulting Uint8Array satisfies
  // WebAuthn's `BufferSource` (ArrayBuffer-backed) requirement, even when the
  // source is backed by a SharedArrayBuffer.
  const out = new Uint8Array(new ArrayBuffer(source.byteLength))
  for (let i = 0; i < source.byteLength; i++) out[i] = source[i]! & 0xff
  return out
}

function classifyError(err: unknown): WebAuthnFailure {
  if (err instanceof DOMException) {
    switch (err.name) {
      case 'NotAllowedError':
      case 'AbortError':
        return { kind: 'cancelled', message: err.message }
      case 'InvalidStateError':
        return { kind: 'wrongCredential', message: err.message }
      case 'SecurityError':
      case 'NotSupportedError':
        return { kind: 'unsupported', message: err.message }
      case 'NetworkError':
      case 'UnknownError':
        return { kind: 'hardware', message: err.message }
    }
  }
  return { kind: 'unknown', message: err instanceof Error ? err.message : String(err) }
}

export type WebAuthnResult<T> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly error: WebAuthnFailure }

export interface PasskeyRegistration {
  readonly credentialId: Int8Array
  // SubjectPublicKeyInfo (SPKI) DER. `getPublicKey()` returns this directly
  // for COSE algorithms WebCrypto knows how to import (-7 ES256, -257 RS256).
  readonly publicKey: Int8Array
  // COSE algorithm identifier (-7 = ES256, -257 = RS256). The SDK uses this
  // to pick the WebCrypto algorithm parameters for assertion verification.
  readonly algorithm: number
  readonly clientDataJson: Int8Array
  readonly attestationObject: Int8Array
  // Phase 5 PRF output (per-credential, derived from PRF_SALT). Present
  // when the authenticator supports the WebAuthn `prf` extension. The SDK
  // wraps the master keypair under this value so the next page load can
  // unwrap on `Session.unlock`. Undefined when PRF is unsupported — in
  // that case the SDK falls back to today's opaque-session-token path.
  readonly prfOutput?: Int8Array
}

export async function registerPasskey(args: {
  readonly userId: Int8Array
  readonly userName: string
  readonly displayName: string
}): Promise<WebAuthnResult<PasskeyRegistration>> {
  if (!isWebAuthnSupported()) {
    return { ok: false, error: { kind: 'unsupported', message: 'WebAuthn unavailable' } }
  }
  try {
    const credential = (await navigator.credentials.create({
      publicKey: {
        challenge: randomChallenge(),
        rp: { name: RP_NAME, id: RP_ID },
        user: {
          id: int8ToFreshUint8(args.userId),
          name: args.userName,
          displayName: args.displayName,
        },
        pubKeyCredParams: [
          { type: 'public-key', alg: -7 }, // ES256
          { type: 'public-key', alg: -257 }, // RS256
        ],
        authenticatorSelection: {
          residentKey: 'preferred',
          userVerification: 'preferred',
        },
        extensions: { prf: { eval: { first: PRF_SALT } } } as AuthenticationExtensionsClientInputs,
        timeout: 60_000,
      },
    })) as PublicKeyCredential | null
    if (credential === null) {
      return { ok: false, error: { kind: 'cancelled', message: 'No credential returned' } }
    }
    const response = credential.response as AuthenticatorAttestationResponse
    const algorithm = response.getPublicKeyAlgorithm?.() ?? 0
    const credentialId = bufferToInt8(credential.rawId)
    const prfOutput = await extractRegistrationPrfOutput(credential, credentialId)
    return {
      ok: true,
      value: {
        credentialId,
        publicKey: bufferToInt8(response.getPublicKey?.() ?? null),
        algorithm,
        clientDataJson: bufferToInt8(response.clientDataJSON),
        attestationObject: bufferToInt8(response.attestationObject),
        ...(prfOutput !== null ? { prfOutput } : {}),
      },
    }
  } catch (err) {
    return { ok: false, error: classifyError(err) }
  }
}

// Pull the PRF output from the create() ceremony when the browser
// included it (Chrome ≥132). When PRF is `enabled: true` but the
// authenticator hasn't returned `results.first` yet, run an immediate
// follow-up assertion bound to the same credential to extract it —
// some authenticators only compute the PRF during get(), not create().
// Both the user-gesture window and the credential are still warm here,
// so the assertion reuses them without a second prompt on cooperative
// platforms (passkey + biometric authenticators typically auto-confirm).
//
// Returns null when PRF isn't supported or the follow-up assertion
// fails — both are graceful-degradation paths; the caller stores the
// passkey without a wrapped master key.
async function extractRegistrationPrfOutput(
  credential: PublicKeyCredential,
  credentialId: Int8Array,
): Promise<Int8Array | null> {
  const results = credential.getClientExtensionResults()
  const inlinePrf = readPrfFirst(results)
  if (inlinePrf !== null) return inlinePrf
  if (results.prf?.enabled !== true) return null
  // Follow-up assertion to extract the PRF output. Same RP / same salt.
  try {
    const assertion = (await navigator.credentials.get({
      publicKey: {
        challenge: randomChallenge(),
        rpId: RP_ID,
        allowCredentials: [
          {
            type: 'public-key',
            id: int8ToFreshUint8(credentialId),
          },
        ],
        userVerification: 'preferred',
        extensions: { prf: { eval: { first: PRF_SALT } } } as AuthenticationExtensionsClientInputs,
        timeout: 60_000,
      },
    })) as PublicKeyCredential | null
    if (assertion === null) return null
    return readPrfFirst(assertion.getClientExtensionResults())
  } catch {
    // The follow-up may be cancelled by the user or rejected by the
    // platform when the create() ceremony's UV credit has lapsed.
    // Treat as graceful degradation rather than failing registration.
    return null
  }
}

function readPrfFirst(results: AuthenticationExtensionsClientOutputs): Int8Array | null {
  const first = results.prf?.results?.first
  if (first === undefined || first === null) return null
  // `first` is `BufferSource` per lib.dom.d.ts — could be `ArrayBuffer` or
  // any TypedArray view. `bufferToInt8` already handles both shapes.
  return bufferToInt8(first as ArrayBuffer | Uint8Array)
}

// §4.6.1 dedicated WebAuthn re-challenge for high-stakes actions
// (currently: Settings → Recovery → Re-display recovery phrase). Distinct
// from `getPasskeyAssertion()`, which mints a session token — this
// ceremony's only purpose is to attest "the human at this device approved
// this specific action just now." Returned `assertedAtMs` is the wall
// clock at the moment the ceremony resolved; callers enforce a freshness
// window (~60s) so an assertion can't be stockpiled.
//
// Action-binding: each call generates a fresh random challenge — the
// browser will not return a stale assertion from a different ceremony.
// The `action` label is folded into the challenge bytes as a domain
// separator so two concurrent ceremonies for different actions can't be
// crossed; the apex-side caller still checks `assertedAtMs` and consumes
// the assertion immediately.
export interface DedicatedAssertion {
  readonly assertion: WebAuthnAssertion
  readonly assertedAtMs: number
  readonly action: string
}

const DEDICATED_FRESHNESS_MS = 60_000

export function isDedicatedAssertionFresh(
  assertion: DedicatedAssertion,
  now: number = Date.now(),
): boolean {
  const age = now - assertion.assertedAtMs
  return age >= 0 && age <= DEDICATED_FRESHNESS_MS
}

function buildActionBoundChallenge(action: string): Uint8Array<ArrayBuffer> {
  // 32 bytes: 16 random + 16 from a domain-separated hash of the action
  // label. Random half guarantees uniqueness; action half lets a future
  // server-side verifier (or this side, on a follow-up audit) confirm the
  // ceremony bound to the right action without needing to remember the
  // exact challenge. Apex consumes and verifies in-process today, so the
  // binding is belt-and-braces; it costs ~no extra latency.
  const random = new Uint8Array(16)
  crypto.getRandomValues(random)
  const labelBytes = new TextEncoder().encode('tenshu/dedicated-assertion/v1:' + action)
  const out = new Uint8Array(new ArrayBuffer(32))
  out.set(random, 0)
  // Cheap mix: XOR-fold the label bytes into the second 16 bytes. We're
  // not relying on cryptographic collision-resistance of this fold —
  // freshness + action-string check at the call site does the real work.
  for (let i = 0; i < labelBytes.length; i++) {
    out[16 + (i % 16)] = (out[16 + (i % 16)] ?? 0) ^ (labelBytes[i] ?? 0)
  }
  return out
}

export async function getDedicatedAssertion(
  action: string,
): Promise<WebAuthnResult<DedicatedAssertion>> {
  if (!isWebAuthnSupported()) {
    return { ok: false, error: { kind: 'unsupported', message: 'WebAuthn unavailable' } }
  }
  try {
    const credential = (await navigator.credentials.get({
      publicKey: {
        challenge: buildActionBoundChallenge(action),
        rpId: RP_ID,
        userVerification: 'required',
        timeout: 60_000,
      },
    })) as PublicKeyCredential | null
    if (credential === null) {
      return { ok: false, error: { kind: 'cancelled', message: 'No credential returned' } }
    }
    const response = credential.response as AuthenticatorAssertionResponse
    return {
      ok: true,
      value: {
        assertion: {
          credentialId: bufferToInt8(credential.rawId),
          clientDataJson: bufferToInt8(response.clientDataJSON),
          authenticatorData: bufferToInt8(response.authenticatorData),
          signature: bufferToInt8(response.signature),
          userHandle: response.userHandle === null ? null : bufferToInt8(response.userHandle),
        },
        assertedAtMs: Date.now(),
        action,
      },
    }
  } catch (err) {
    return { ok: false, error: classifyError(err) }
  }
}

export async function getPasskeyAssertion(): Promise<WebAuthnResult<WebAuthnAssertion>> {
  if (!isWebAuthnSupported()) {
    return { ok: false, error: { kind: 'unsupported', message: 'WebAuthn unavailable' } }
  }
  try {
    const credential = (await navigator.credentials.get({
      publicKey: {
        challenge: randomChallenge(),
        rpId: RP_ID,
        userVerification: 'preferred',
        extensions: { prf: { eval: { first: PRF_SALT } } } as AuthenticationExtensionsClientInputs,
        timeout: 60_000,
      },
    })) as PublicKeyCredential | null
    if (credential === null) {
      return { ok: false, error: { kind: 'cancelled', message: 'No credential returned' } }
    }
    const response = credential.response as AuthenticatorAssertionResponse
    const prfOutput = readPrfFirst(credential.getClientExtensionResults())
    return {
      ok: true,
      value: {
        credentialId: bufferToInt8(credential.rawId),
        clientDataJson: bufferToInt8(response.clientDataJSON),
        authenticatorData: bufferToInt8(response.authenticatorData),
        signature: bufferToInt8(response.signature),
        userHandle: response.userHandle === null ? null : bufferToInt8(response.userHandle),
        ...(prfOutput !== null ? { prfOutput } : {}),
      },
    }
  } catch (err) {
    return { ok: false, error: classifyError(err) }
  }
}
