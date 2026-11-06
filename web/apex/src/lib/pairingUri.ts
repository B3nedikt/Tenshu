// Client-side validation for the pairing QR / paste payload an existing
// realm device emits — the canonical role-host form Control emits via
// `SyncManagerImpl.getPairingString()`:
//
//   tenshu://join?o=<ownerId>&d=<base32(deviceId)>&k=<base32(pubkey)>&s=<base32(secret)>
//
// An unrecognised `tenshu://` role host — a QR minted by a
// newer app than this build — gets 'unknownRole' ("update your app"), and a
// `tenshu://offer?…` code (shown by a *new* device; this screen needs the
// code from an *existing* one) gets 'offerRole'. Never a partial parse.
//
// This is purely a fast UX feedback gate — the SDK re-parses with the shared
// `:core-common` `PairingUri` helper, which is the source of truth.
export const PAIRING_URI_SCHEME = 'tenshu://'
export const JOIN_URI_PREFIX = 'tenshu://join'

export type PairingUriValidation =
  | { readonly ok: true }
  | {
      readonly ok: false
      readonly reason: 'wrongScheme' | 'unknownRole' | 'offerRole' | 'missingParams'
    }

export function validatePairingUri(input: string): PairingUriValidation {
  const trimmed = input.trim()
  if (!trimmed.startsWith(PAIRING_URI_SCHEME)) {
    return { ok: false, reason: 'wrongScheme' }
  }
  const afterScheme = trimmed.slice(PAIRING_URI_SCHEME.length)
  const hostEnd = afterScheme.search(/[?/#]/)
  const host = hostEnd >= 0 ? afterScheme.slice(0, hostEnd) : afterScheme
  if (host === 'offer') return { ok: false, reason: 'offerRole' }
  if (host !== 'join') return { ok: false, reason: 'unknownRole' }
  const query =
    hostEnd >= 0 && afterScheme[hostEnd] === '?' ? afterScheme.slice(hostEnd + 1) : ''
  const params = new URLSearchParams(query)
  const required = ['o', 'd', 'k', 's']
  for (const key of required) {
    if (!params.has(key) || params.get(key) === '') {
      return { ok: false, reason: 'missingParams' }
    }
  }
  return { ok: true }
}
