// Browser-local helpers for the Contacts UI. Pure, so they are unit-tested directly; the rules
// they copy from the native app are named where they live.
import type { Contact, ContactDetails } from '@tenshu/sdk/contact'

// Display form of a full-hex identity fingerprint: the leading 15 hex chars as
// `XXXXX-YYYYY-ZZZZZ`. Verbatim the native `displayFingerprint` (and Drive's copy), so a
// web↔native pair reads the same string.
export function displayFingerprint(fingerprintHex: string): string {
  const head = fingerprintHex.slice(0, 15).toUpperCase()
  const groups: string[] = []
  for (let i = 0; i < head.length; i += 5) groups.push(head.slice(i, i + 5))
  return groups.join('-')
}

// The six digits as two groups of three; anything else passes through.
export function formatSas(sas: string): string {
  if (sas.length !== 6) return sas
  return `${sas.slice(0, 3)} ${sas.slice(3)}`
}

// Case- and accent-insensitive search over what the list row shows and the details hold.
export function matchesQuery(contact: Contact, query: string): boolean {
  const q = fold(query.trim())
  if (!q) return true
  const haystack = [
    contact.displayName,
    contact.details.organisation,
    ...contact.details.emails.map((f) => f.value),
    ...contact.details.phones.map((f) => f.value),
    ...contact.identities.map((i) => i.selfAssertedName),
  ]
  if (haystack.some((v) => fold(v).includes(q))) return true
  // A phone typed with or without spaces / dashes still finds the number.
  const digits = q.replace(/\D/g, '')
  return digits.length >= 3 && contact.details.phones.some((f) => f.value.replace(/\D/g, '').includes(digits))
}

function fold(value: string): string {
  return value.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase()
}

// ── duplicate suggestions (native `MergeCandidate.kt`, D7) ────────────────────
// ⚠⚠ Suggests, never acts: nothing here writes. Everything a handshake delivers is self-asserted,
// so merging on a resemblance would let an attacker's key inherit a known person's card.

export type MergeReason = 'phone' | 'email' | 'name'

export interface MergeCandidate {
  readonly first: Contact
  readonly second: Contact
  // Strongest first; never empty.
  readonly reasons: readonly MergeReason[]
}

const REASON_ORDER: Record<MergeReason, number> = { phone: 0, email: 1, name: 2 }

export function findMergeCandidates(contacts: readonly Contact[]): MergeCandidate[] {
  const out: MergeCandidate[] = []
  for (let i = 0; i < contacts.length; i++) {
    for (let j = i + 1; j < contacts.length; j++) {
      const a = contacts[i]!
      const b = contacts[j]!
      const reasons = reasonsFor(a, b)
      if (reasons.length > 0) out.push({ first: a, second: b, reasons })
    }
  }
  return out.sort((x, y) => REASON_ORDER[x.reasons[0]!] - REASON_ORDER[y.reasons[0]!])
}

function reasonsFor(a: Contact, b: Contact): MergeReason[] {
  const reasons: MergeReason[] = []
  if (sharesAny(a.details.phones.map((f) => normalisePhone(f.value)), b.details.phones.map((f) => normalisePhone(f.value)))) {
    reasons.push('phone')
  }
  if (sharesAny(a.details.emails.map((f) => normaliseEmail(f.value)), b.details.emails.map((f) => normaliseEmail(f.value)))) {
    reasons.push('email')
  }
  const sameName = a.displayName.trim() !== '' && normaliseName(a.displayName) === normaliseName(b.displayName)
  // ⚠ Two CONNECTED contacts are never suggested on name alone: two completed handshakes are two
  // people the user separately confirmed exist (native rule, same reason).
  if (sameName && !(isConnected(a) && isConnected(b))) reasons.push('name')
  return reasons
}

export function isConnected(contact: Contact): boolean {
  return contact.identities.length > 0
}

function sharesAny(a: string[], b: string[]): boolean {
  const left = new Set(a.filter((v) => v !== ''))
  return left.size > 0 && b.some((v) => v !== '' && left.has(v))
}

// Digits only, compared on the last nine (the native rule: `+44 7700 900123`, `07700 900123` and
// `00447700900123` are one number).
export function normalisePhone(value: string): string {
  const digits = value.replace(/\D/g, '')
  return digits.length > 9 ? digits.slice(-9) : digits
}

const normaliseEmail = (value: string) => value.trim().toLowerCase()
const normaliseName = (value: string) => value.trim().toLowerCase().split(/\s+/).filter(Boolean).join(' ')

// Which side survives by default: the one a user would miss more (native `preferredTarget`). Only
// a default — the confirmation puts both names in front of the user.
export function preferredTarget(candidate: MergeCandidate): string {
  const score = (c: Contact) =>
    (isConnected(c) ? 100 : 0) +
    c.identities.length * 10 +
    c.details.phones.length +
    c.details.emails.length +
    (c.avatar === 'present' ? 1 : 0)
  return score(candidate.second) > score(candidate.first) ? candidate.second.personId : candidate.first.personId
}

// ── details editing ──────────────────────────────────────────────────────────

export const EMPTY_DETAILS: ContactDetails = { phones: [], emails: [], addresses: [], organisation: '', note: '' }

// What the editor saves: blank rows dropped, values trimmed. ⚠ Every key present — the save MERGES
// the set into the record, so a missing `phones` would delete every number (run A's contract).
export function cleanDetails(details: ContactDetails): ContactDetails {
  const rows = (fields: ContactDetails['phones']) =>
    fields.map((f) => ({ label: f.label.trim(), value: f.value.trim() })).filter((f) => f.value !== '')
  return {
    phones: rows(details.phones),
    emails: rows(details.emails),
    addresses: rows(details.addresses),
    organisation: details.organisation.trim(),
    note: details.note.trim(),
  }
}

// ── vCard file I/O (browser-only halves; parsing/serialising stays in the SDK) ──

export function vcardFileName(displayName: string): string {
  const safe = displayName.trim().replace(/[\\/:*?"<>|]+/g, '_').slice(0, 80)
  return `${safe || 'contact'}.vcf`
}

// Hands the text to the browser as a download. No network: a Blob URL of local bytes.
export function downloadVcard(text: string, fileName: string, doc: Document = document): void {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/vcard;charset=utf-8' }))
  const a = doc.createElement('a')
  a.href = url
  a.download = fileName
  doc.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}
