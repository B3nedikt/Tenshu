// Contact entry point: the link-only contact-invitation handshake
// (`docs/proposals/drive-web-mode-b-sharing.md` MB-1). Backed by `Contacts`
// on the Kotlin side (`:tenshu-sdk-js`) — the same `:contact-service` ops the
// native apps run, dispatched through the apex in-process runtime.
//
// Form factor (doc §1): web v1 is a deep-link invite → `UNVERIFIED` (TOFU)
// contact → explicit grant. The invite link carries its `K_link` in the URL
// fragment and never reaches the relay. Camera-QR + the 6-digit SAS verify
// upgrade is MB-5 — additive, not a prerequisite for sharing.

import * as bundle from './_bundle'

// `Contacts.redeemInvite(...)` outcome. The 'alreadyUsed' (the single response
// slot was taken) and 'gone' (expired / revoked) cases are the T-CI-01
// loud-copy signals the UI must surface distinctly — never as 'failed'.
export type RedeemOutcome = 'redeemed' | 'alreadyUsed' | 'gone' | 'failed'

export interface RedeemResult {
  readonly outcome: RedeemOutcome
  readonly inviteId: string | null
  readonly message: string | null
}

// `Contacts.completeHandshake(...)` outcome (threat model T-CI-10). 'responseChanged' means the
// reply on the relay is not the one the prompt showed: nothing was saved, and the user must
// decline and send a new invite — never tell them to retry, which refuses the same way.
// 'codeNotShown' is a confirmation for an invite no code has been shown for yet.
export type CompleteOutcome = 'completed' | 'responseChanged' | 'codeNotShown' | 'failed'

export interface CompleteResult {
  readonly outcome: CompleteOutcome
  // The catalogue key (`error_…`) on failure, `null` on success.
  readonly message: string | null
}

// `Contacts.awaitAck(...)` result — the invitee's view once the inviter
// responds. `status` is one of ACCEPTED | REJECTED | PENDING | GONE | INVALID;
// re-invoke while PENDING. `sas` is `""` on the v1 link path (no SAS).
export interface InviteAck {
  readonly status: string
  readonly peerName: string
  readonly peerFingerprint: string
  readonly intentKind: string
  readonly intentLabel: string
  readonly sas: string
}

// A row from `Contacts.listInvites()` — the inviter's pending/closed invites.
// A fresh `REDEEMED` status is the cue to show the completion prompt; render
// `claimedName` as a *claim* (quoted), and `claimedFingerprint` for eyeballing.
export interface ContactInviteItem {
  readonly inviteId: string
  readonly status: string
  readonly intentKind: string
  readonly intentLabel: string
  readonly createdAtMillis: number
  readonly expiresAtMillis: number
  readonly claimedName: string
  readonly claimedFingerprint: string
  readonly claimedSas: string
}

// A connected contact (`Contacts.connectedContacts()`) — a Person with ≥1
// linked identity. `grantable` gates MB-2's grant action on a grant seed.
export interface ContactItem {
  readonly personId: string
  readonly displayName: string
  readonly fingerprint: string
  readonly grantable: boolean
  // The Q2b rule (`ContactTrust`): true iff the keyring's trust is 'verified' — every key VERIFIED
  // or ATTESTED. A mixed keyring is NOT verified (it needs re-verification).
  readonly verified: boolean
}

// A resumable invitee-side redemption awaiting an ack (`pendingRedemptions()`).
export interface PendingRedemptionItem {
  readonly inviteId: string
  readonly redeemedAtMillis: number
  readonly peerFingerprint: string
  readonly sas: string
}

// ── The address book (web-app-suite.md Wave 1, run A) ──────────────────────
// Mirrors the native `ContactsRepository` op for op (`ContactsBook.kt` on the Kotlin side).
// Every write resolves `{ ok: true, … }` or a `ContactsFailure`, whose `kind` is a catalogue key
// (`error_…`) — the same key the native app localises. Branch on `kind`, never on `message`.

export interface ContactsFailure {
  readonly ok: false
  readonly error: { readonly kind: string; readonly message?: string }
}

// The catalogue keys this surface mints itself. Any other failure carries the key derived from
// the service's typed error (e.g. `error_generic`).
export const ContactsErrorKind = {
  noContactsInFile: 'error_contacts_none_in_file',
  nothingToExport: 'error_contacts_nothing_to_export',
  // The card's big photo could not be fetched; the export fails rather than drop it.
  photoUnavailable: 'error_contacts_photo_unavailable',
  invalidDetails: 'error_contacts_invalid_details',
  unknownKey: 'error_contacts_unknown_key',
} as const

// The Q2b trust rule over a contact's keyring.
export type ContactTrust = 'notConnected' | 'unverified' | 'verified' | 'needsReverification'
// An unknown stored rung arrives as 'UNVERIFIED' — the bridge fails closed.
export type ContactVerification = 'UNVERIFIED' | 'VERIFIED' | 'ATTESTED'
// Why `avatarDataUrl` is or is not set. 'tooLarge' / 'unknownFormat' are withheld, not absent.
export type ContactAvatarState = 'none' | 'present' | 'tooLarge' | 'unknownFormat'

export interface ContactField {
  readonly label: string
  readonly value: string
}

// The editable field set (Q6). As an argument to `saveContactDetails` every key is required:
// a missing `phones` would otherwise read as "no phones" and delete them on save.
export interface ContactDetails {
  readonly phones: ContactField[]
  readonly emails: ContactField[]
  readonly addresses: ContactField[]
  readonly organisation: string
  readonly note: string
}

export interface ContactIdentity {
  readonly fingerprint: string
  // A claim — render quoted, never as the petname.
  readonly selfAssertedName: string
  readonly verification: string // ContactVerification
  readonly verifiedAtMillis: number
  readonly source: string
}

export interface Contact {
  readonly personId: string
  // The petname; '' is legitimate and the UI substitutes its own "unnamed".
  readonly displayName: string
  readonly trust: string // ContactTrust
  readonly identities: ContactIdentity[]
  // The union of every source record's fields.
  readonly details: ContactDetails
  readonly avatar: string // ContactAvatarState
  // A `data:image/…;base64,…` URL (≤ 256 KiB of image), only when `avatar` is 'present'.
  readonly avatarDataUrl: string | null
}

export interface OwnProfile {
  readonly displayName: string
  // Always '' today: there is no client-readable own-identity op.
  readonly fingerprint: string
}

export interface OutstandingInvite {
  readonly inviteId: string
  readonly createdAtMillis: number
  readonly expiresAtMillis: number
}

export interface InviteConfirmation {
  readonly inviteId: string
  readonly claimedName: string
  // RAW — pass back unchanged as `completeHandshake`'s `shownFingerprint` (T-CI-10).
  readonly claimedFingerprint: string
  readonly claimedSas: string
}

export type ContactsResult = { readonly ok: true; readonly contacts: Contact[] } | ContactsFailure
// ⚠ `contact: null` = no such person (an answer); `ok: false` = the read failed.
export type ContactResult = { readonly ok: true; readonly contact: Contact | null } | ContactsFailure
// `changed` is the service's own answer — `false` is a no-op, visible rather than dropped.
export type ContactsWriteResult = { readonly ok: true; readonly changed: boolean } | ContactsFailure

// Operation surface: every method is Promise-returning (suspend lives on the
// Kotlin side) and safe to use directly as a TanStack Query `queryFn`.
export const Contacts = bundle.Contacts.getInstance()
