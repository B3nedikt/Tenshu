// `contact.*` — the invitation handshake (Drive's sharing flow) and the Contacts address book.
import { Contacts } from '@tenshu/sdk/contact'
import type { GatewayModule } from './module'
import { asRecord, readId, readString } from './params'

export const contactGateway: GatewayModule = {
  handlers: {
    // MB-1 — the link-only contact-invitation handshake (drive-web-mode-b-
    // sharing.md §10). The same `:contact-service` ops the natives run; the
    // apex holds the keys, the subdomain only renders. `redeem` parses the
    // invite link the joiner opened (its `K_link` rode the URL fragment and
    // never reached the relay). `connectedContacts` is the read MB-2's grant
    // picker will consume.
    'contact.createInvite': async (params) => {
      const { intentKind, intentLabel } = readContactInvite(params)
      return Contacts.createInvite(intentKind, intentLabel)
    },
    'contact.revokeInvite': async (params) => {
      return Contacts.revokeInvite(readInviteId(params, 'contact.revokeInvite'))
    },
    'contact.redeem': async (params) => {
      const { link, selfAssertedName } = readRedeem(params)
      return Contacts.redeemInvite(link, selfAssertedName)
    },
    'contact.awaitAck': async (params) => {
      const { inviteId, sasConfirmed } = readAwaitAck(params)
      return Contacts.awaitAck(inviteId, sasConfirmed)
    },
    'contact.complete': async (params) => {
      const c = readComplete(params)
      return Contacts.completeHandshake(
        c.inviteId,
        c.accept,
        c.shownFingerprint,
        c.personId,
        c.newPersonName,
        c.selfAssertedName,
        c.sasConfirmed,
      )
    },
    'contact.listInvites': async () => {
      return Contacts.listInvites()
    },
    'contact.connected': async () => {
      return Contacts.connectedContacts()
    },
    'contact.pending': async () => {
      return Contacts.pendingRedemptions()
    },

    // web-app-suite.md Wave 1, run A — the Contacts address book, mirroring the native
    // `ContactsRepository` op for op. Every write resolves `{ ok, … }` with a catalogue-key
    // `error.kind`; the reads that are Flows natively also have a push form under
    // `subscriptions` below. ⚠ Bare invites reuse `contact.createInvite` (empty intent),
    // `contact.revokeInvite` and `contact.complete` above — no second copy.
    'contact.list': async () => Contacts.contacts(),
    'contact.get': async (params) => Contacts.contact(readId(params, 'personId', 'contact.get')),
    'contact.ownProfile': async () => Contacts.ownProfile(),
    'contact.setOwnDisplayName': async (params) =>
      Contacts.setOwnDisplayName(readString(params, 'name', 'contact.setOwnDisplayName')),
    'contact.create': async (params) =>
      Contacts.createContact(readString(params, 'displayName', 'contact.create')),
    'contact.rename': async (params) =>
      Contacts.renameContact(
        readId(params, 'personId', 'contact.rename'),
        readString(params, 'displayName', 'contact.rename'),
      ),
    // ⚠⚠ Revokes nothing — the calling UI must say so first (Q7b).
    'contact.delete': async (params) => Contacts.deleteContact(readId(params, 'personId', 'contact.delete')),
    'contact.unlinkIdentity': async (params) =>
      Contacts.unlinkIdentity(readId(params, 'fingerprint', 'contact.unlinkIdentity')),
    // ⚠⚠ Explicit user confirmation only; there is no un-merge (D7).
    'contact.merge': async (params) =>
      Contacts.mergeContacts(
        readId(params, 'sourcePersonId', 'contact.merge'),
        readId(params, 'targetPersonId', 'contact.merge'),
      ),
    'contact.relinkIdentity': async (params) =>
      Contacts.relinkIdentity(
        readId(params, 'fingerprint', 'contact.relinkIdentity'),
        readId(params, 'personId', 'contact.relinkIdentity'),
      ),
    'contact.clearAvatar': async (params) =>
      Contacts.clearAvatar(readId(params, 'personId', 'contact.clearAvatar')),
    'contact.saveDetails': async (params) =>
      Contacts.saveContactDetails(readId(params, 'personId', 'contact.saveDetails'), readDetails(params)),
    'contact.importVcards': async (params) =>
      Contacts.importVcards(readString(params, 'text', 'contact.importVcards')),
    'contact.exportVcard': async (params) =>
      Contacts.exportVcard(readId(params, 'personId', 'contact.exportVcard')),
    'contact.sas': async (params) => Contacts.contactSas(readId(params, 'fingerprint', 'contact.sas')),
    // ⚠⚠ `verified: true` only after the user confirmed the digits (threat model A24) — so it is
    // required and must be a real boolean; nothing here defaults it.
    'contact.setVerified': async (params) => {
      const r = asRecord(params)
      if (typeof r.verified !== 'boolean') throw new Error('contact.setVerified requires { verified: boolean }')
      return Contacts.setVerified(readId(params, 'fingerprint', 'contact.setVerified'), r.verified)
    },
    'contact.outstandingInvites': async () => Contacts.outstandingInvites(),
    'contact.pendingConfirmations': async () => Contacts.pendingConfirmations(),
  },
  subscriptions: {
    // Contacts address book — genuine push: each is a collector over the relations' Flows (and
    // the owner-name preference), cancelled by the returned lambda. ⚠ `fail` is wired through:
    // a collector that dies (its flow threw, or `emit`'s postMessage refused a value) ends the
    // subscriber's stream with a terminal error instead of freezing its screen.
    'contact.list.observe': (_params, emit, fail) =>
      Contacts.observeContacts((result) => emit(result), fail),
    'contact.get.observe': (params, emit, fail) =>
      Contacts.observeContact(
        readId(params, 'personId', 'contact.get.observe'),
        (result) => emit(result),
        fail,
      ),
    'contact.ownProfile.observe': (_params, emit, fail) =>
      Contacts.observeOwnProfile((profile) => emit(profile), fail),
  },
}

// MB-1 contact-handshake validators. Optional string params default to '' (the
// Kotlin sentinel); a missing required id throws so the gateway rejects loudly.
function readContactInvite(params: unknown): { intentKind: string; intentLabel: string } {
  const r = asRecord(params)
  return {
    intentKind: typeof r.intentKind === 'string' ? r.intentKind : '',
    intentLabel: typeof r.intentLabel === 'string' ? r.intentLabel : '',
  }
}

function readInviteId(params: unknown, op: string): string {
  const v = asRecord(params).inviteId
  if (typeof v !== 'string') throw new Error(`${op} requires { inviteId }`)
  return v
}

function readRedeem(params: unknown): { link: string; selfAssertedName: string } {
  const r = asRecord(params)
  if (typeof r.link !== 'string') {
    throw new Error('contact.redeem requires { link }')
  }
  return {
    link: r.link,
    selfAssertedName: typeof r.selfAssertedName === 'string' ? r.selfAssertedName : '',
  }
}

function readAwaitAck(params: unknown): { inviteId: string; sasConfirmed: boolean } {
  const r = asRecord(params)
  if (typeof r.inviteId !== 'string') {
    throw new Error('contact.awaitAck requires { inviteId }')
  }
  return { inviteId: r.inviteId, sasConfirmed: r.sasConfirmed === true }
}

type ContactFieldParam = { label: string; value: string }
type ContactDetailsParam = {
  phones: ContactFieldParam[]
  emails: ContactFieldParam[]
  addresses: ContactFieldParam[]
  organisation: string
  note: string
}

// ⚠ Every key required: the save MERGES the edited set into the record, so an absent `phones`
// read as [] would delete every number. Rebuilt field by field so nothing extra crosses.
function readDetails(params: unknown): ContactDetailsParam {
  const d = asRecord(asRecord(params).details)
  const fields = (key: string): ContactFieldParam[] => {
    const v = d[key]
    if (!Array.isArray(v)) throw new Error(`contact.saveDetails requires details.${key}: array`)
    return v.map((item: unknown) => {
      const f = asRecord(item)
      if (typeof f.label !== 'string' || typeof f.value !== 'string') {
        throw new Error(`contact.saveDetails requires details.${key}[]: { label, value }`)
      }
      return { label: f.label, value: f.value }
    })
  }
  if (typeof d.organisation !== 'string' || typeof d.note !== 'string') {
    throw new Error('contact.saveDetails requires details.organisation and details.note')
  }
  return {
    phones: fields('phones'),
    emails: fields('emails'),
    addresses: fields('addresses'),
    organisation: d.organisation,
    note: d.note,
  }
}

function readComplete(params: unknown): {
  inviteId: string
  accept: boolean
  shownFingerprint: string
  personId: string
  newPersonName: string
  selfAssertedName: string
  sasConfirmed: boolean
} {
  const r = asRecord(params)
  if (typeof r.inviteId !== 'string') {
    throw new Error('contact.complete requires { inviteId }')
  }
  // Required since contact-service v2 (threat model T-CI-10): a caller that does not say what its
  // prompt displayed must fail loudly, never default to "nothing shown".
  if (typeof r.shownFingerprint !== 'string') {
    throw new Error('contact.complete requires { shownFingerprint }')
  }
  return {
    inviteId: r.inviteId,
    accept: r.accept === true,
    shownFingerprint: r.shownFingerprint,
    personId: typeof r.personId === 'string' ? r.personId : '',
    newPersonName: typeof r.newPersonName === 'string' ? r.newPersonName : '',
    selfAssertedName: typeof r.selfAssertedName === 'string' ? r.selfAssertedName : '',
    sasConfirmed: r.sasConfirmed === true,
  }
}
