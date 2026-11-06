// `Contacts` — the invitation handshake and the address book.
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).

// Contacts: the link-only contact-invitation handshake (drive-web-mode-b-
// sharing.md MB-1). Backed by `:contact-service` ops dispatched through the
// apex in-process runtime. All fields mirror the `@JsExport` result classes
// in `Contacts.kt`; nullable Kotlin `String?` surfaces as `string | null`.
interface NativeRedeemResult {
  // 'redeemed' | 'alreadyUsed' | 'gone' | 'failed'. The 'alreadyUsed' / 'gone'
  // cases are the T-CI-01 loud-copy signals — never collapse them into 'failed'.
  readonly outcome: string
  readonly inviteId: string | null
  readonly message: string | null
}

// `Contacts.completeHandshake(...)` result. 'responseChanged' (threat model T-CI-10): the reply
// this invite holds is not the one the prompt showed, so nothing was saved and the only way on is
// to decline and send a new invite — a retry refuses identically. 'codeNotShown': a "codes match"
// confirmation for an invite no code was shown for yet. `message` is the catalogue key.
interface NativeCompleteResult {
  // 'completed' | 'responseChanged' | 'codeNotShown' | 'failed'
  readonly outcome: string
  readonly message: string | null
}

interface NativeInviteAck {
  // One of AwaitInviteAck.Result: ACCEPTED | REJECTED | PENDING | GONE | INVALID.
  readonly status: string
  readonly peerName: string
  readonly peerFingerprint: string
  readonly intentKind: string
  readonly intentLabel: string
  readonly sas: string
}

interface NativeContactInviteItem {
  readonly inviteId: string
  // PENDING | REDEEMED | COMPLETED | REJECTED | REVOKED | EXPIRED | COMPROMISED.
  readonly status: string
  readonly intentKind: string
  readonly intentLabel: string
  readonly createdAtMillis: number
  readonly expiresAtMillis: number
  // The responder's self-asserted name — render as a *claim*, quoted.
  readonly claimedName: string
  readonly claimedFingerprint: string
  readonly claimedSas: string
}

interface NativeContactItem {
  readonly personId: string
  readonly displayName: string
  readonly fingerprint: string
  readonly grantable: boolean
  // The Q2b rule: true iff the keyring's trust is 'verified' (mixed = false, ATTESTED-only = true).
  readonly verified: boolean
}

interface NativePendingRedemptionItem {
  readonly inviteId: string
  readonly redeemedAtMillis: number
  readonly peerFingerprint: string
  readonly sas: string
}

// The Contacts address book (web-app-suite.md Wave 1, run A) — mirrors `ContactsRepository`.
// Rows mirror the `@JsExport` classes in `ContactsBook.kt`.
interface NativeContactField {
  readonly label: string
  readonly value: string
}

interface NativeContactDetails {
  readonly phones: NativeContactField[]
  readonly emails: NativeContactField[]
  readonly addresses: NativeContactField[]
  readonly organisation: string
  readonly note: string
}

interface NativeContactIdentity {
  readonly fingerprint: string
  // What the key claims to be called — a claim, never the petname.
  readonly selfAssertedName: string
  // 'UNVERIFIED' | 'VERIFIED' | 'ATTESTED'; an unknown stored rung arrives as 'UNVERIFIED'.
  readonly verification: string
  readonly verifiedAtMillis: number
  readonly source: string
}

interface NativeContact {
  readonly personId: string
  readonly displayName: string
  // 'notConnected' | 'unverified' | 'verified' | 'needsReverification'
  readonly trust: string
  readonly identities: NativeContactIdentity[]
  readonly details: NativeContactDetails
  // 'none' | 'present' | 'tooLarge' | 'unknownFormat' — why `avatarDataUrl` is or is not set.
  readonly avatar: string
  // `data:image/…;base64,…`, only when `avatar` is 'present' (≤ 256 KiB of image).
  readonly avatarDataUrl: string | null
}

interface NativeOwnProfile {
  readonly displayName: string
  // Always '' today — there is no client-readable own-identity op.
  readonly fingerprint: string
}

interface NativeOutstandingInvite {
  readonly inviteId: string
  readonly createdAtMillis: number
  readonly expiresAtMillis: number
}

interface NativeInviteConfirmation {
  readonly inviteId: string
  readonly claimedName: string
  // RAW — pass it back unchanged as `completeHandshake`'s `shownFingerprint`.
  readonly claimedFingerprint: string
  readonly claimedSas: string
}

// `kind` is a catalogue key (`error_…`) — branch and localise on it; `message` is diagnostic.
type NativeContactsWrite = { readonly ok: true; readonly changed: boolean } | NativeFailure
type NativeContactsResult = { readonly ok: true; readonly contacts: NativeContact[] } | NativeFailure
// ⚠ `contact: null` = no such person; `ok: false` = the read failed.
type NativeContactResult = { readonly ok: true; readonly contact: NativeContact | null } | NativeFailure

interface ContactsInstance {
  // ⚠ Success always yields a link, so this carries the failure REASON rather than resolving
  // an ambiguity — pairing is where the reason matters most.
  createInvite(intentKind: string, intentLabel: string): Promise<{ readonly ok: true; readonly link: string } | NativeFailure>
  revokeInvite(inviteId: string): Promise<boolean>
  redeemInvite(link: string, selfAssertedName: string): Promise<NativeRedeemResult>
  awaitAck(inviteId: string, sasConfirmed: boolean): Promise<NativeInviteAck>
  completeHandshake(
    inviteId: string,
    accept: boolean,
    // The RAW `claimedFingerprint` the prompt displayed, or '' when it displayed none (T-CI-10).
    shownFingerprint: string,
    personId: string,
    newPersonName: string,
    selfAssertedName: string,
    sasConfirmed: boolean,
  ): Promise<NativeCompleteResult>
  listInvites(): Promise<NativeContactInviteItem[]>
  connectedContacts(): Promise<NativeContactItem[]>
  pendingRedemptions(): Promise<NativePendingRedemptionItem[]>

  // ── the address book ──
  contacts(): Promise<NativeContactsResult>
  // Push form of `contacts()`; returns the unsubscribe.
  // ⚠ `onError` is TERMINAL: it fires once when the stream can no longer deliver (the flow or the
  // callback threw, or the flow ended) and nothing is pushed after it. Without it a dead collector
  // froze the UI on its last value.
  observeContacts(
    callback: (result: NativeContactsResult) => void,
    onError: (message: string) => void,
  ): () => void
  contact(personId: string): Promise<NativeContactResult>
  observeContact(
    personId: string,
    callback: (result: NativeContactResult) => void,
    onError: (message: string) => void,
  ): () => void
  // Cannot fail: an absent row is the default ''.
  ownProfile(): Promise<NativeOwnProfile>
  // Emits the current value at once, then on every change. `onError` as above.
  observeOwnProfile(
    callback: (profile: NativeOwnProfile) => void,
    onError: (message: string) => void,
  ): () => void
  setOwnDisplayName(name: string): Promise<{ readonly ok: true } | NativeFailure>
  createContact(displayName: string): Promise<{ readonly ok: true; readonly personId: string } | NativeFailure>
  renameContact(personId: string, displayName: string): Promise<NativeContactsWrite>
  // ⚠⚠ Revokes nothing — shared-realm access survives. Say so before calling (Q7b).
  deleteContact(personId: string): Promise<NativeContactsWrite>
  unlinkIdentity(fingerprint: string): Promise<NativeContactsWrite>
  // ⚠⚠ Explicit user confirmation only; there is no un-merge.
  mergeContacts(sourcePersonId: string, targetPersonId: string): Promise<NativeContactsWrite>
  // An unknown key is `error_contacts_unknown_key`.
  relinkIdentity(fingerprint: string, personId: string): Promise<NativeContactsWrite>
  clearAvatar(personId: string): Promise<NativeContactsWrite>
  // Every key of `details` is required — a missing one is `error_contacts_invalid_details`.
  saveContactDetails(personId: string, details: NativeContactDetails): Promise<{ readonly ok: true } | NativeFailure>
  // `imported < cards` = some cards were refused. No card at all = `error_contacts_none_in_file`.
  importVcards(text: string): Promise<{ readonly ok: true; readonly imported: number; readonly cards: number } | NativeFailure>
  // No record = `error_contacts_nothing_to_export`.
  exportVcard(personId: string): Promise<{ readonly ok: true; readonly vcard: string } | NativeFailure>
  // `sas === ''` = unknown key or locked realm: render the absence, never a placeholder.
  contactSas(fingerprint: string): Promise<{ readonly ok: true; readonly sas: string } | NativeFailure>
  // ⚠⚠ `verified: true` ONLY after the user confirmed the digits match (threat model A24).
  // `changed: false` = already at that rung, or ATTESTED (which a local verdict cannot move).
  setVerified(fingerprint: string, verified: boolean): Promise<NativeContactsWrite>
  // Bare invites only (`intentKind === ''`) — Drive owns SHARE_FOLDER invites.
  outstandingInvites(): Promise<{ readonly ok: true; readonly invites: NativeOutstandingInvite[] } | NativeFailure>
  pendingConfirmations(): Promise<{ readonly ok: true; readonly confirmations: NativeInviteConfirmation[] } | NativeFailure>
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const Contacts: { getInstance(): ContactsInstance }
}
