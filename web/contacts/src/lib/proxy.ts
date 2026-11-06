// Contacts' single connection to the apex proxy — the same bridge Drive dials
// (`web/drive/src/lib/proxy.ts`). The subdomain has no backend of its own
// (docs/web/architecture.md §5.2): every read and write goes through the hidden
// apex iframe (or the embedding apex), which holds the keys and runs the runtime.
//
// ⚠⚠ No value import from `@tenshu/sdk` at all: the connection is the app-kit's
// (`createApexBridge`, over `@tenshu/sdk/bridge`), and everything here is `import type` — a value
// import drags the Kotlin runtime into this bundle (web-app-suite.md §7;
// `pnpm -F contacts build` printing a >500 kB chunk is the tell).
//
// ⚠ What this origin may call is decided apex-side, per origin (F5,
// `web/apex/src/lib/gatewayPermissions.ts`): `contact.*` plus the session and
// sync-health methods. A method added to `ProxyMethod` below outside that set is
// refused with `not permitted …` — and `gatewayPermissions.test.ts` reads this
// table to say so before a browser does.
import { createApexBridge } from '@tenshu/app-kit/bridge'
import type {
  CompleteResult,
  Contact,
  ContactDetails,
  ContactResult,
  ContactsFailure,
  ContactsResult,
  ContactsWriteResult,
  InviteConfirmation,
  OutstandingInvite,
  OwnProfile,
} from '@tenshu/sdk/contact'
import type { RuntimeLeaseState } from '@tenshu/sdk/core'
import type { SyncHealthStatus } from '@tenshu/sdk/syncHealth'

export type { Contact, ContactDetails, ContactsFailure, OwnProfile }

// The wire names, shared with the apex `proxyGateway`'s method map.
export const ProxyMethod = {
  Ping: 'bridge.ping',
  RuntimeLease: 'runtime.lease',
  RuntimeUseHere: 'runtime.useHere',
  SyncHealthObserve: 'sync.health.observe',
  List: 'contact.list',
  ListObserve: 'contact.list.observe',
  Get: 'contact.get',
  GetObserve: 'contact.get.observe',
  OwnProfile: 'contact.ownProfile',
  OwnProfileObserve: 'contact.ownProfile.observe',
  SetOwnDisplayName: 'contact.setOwnDisplayName',
  Create: 'contact.create',
  Rename: 'contact.rename',
  Delete: 'contact.delete',
  UnlinkIdentity: 'contact.unlinkIdentity',
  Merge: 'contact.merge',
  RelinkIdentity: 'contact.relinkIdentity',
  ClearAvatar: 'contact.clearAvatar',
  SaveDetails: 'contact.saveDetails',
  ImportVcards: 'contact.importVcards',
  ExportVcard: 'contact.exportVcard',
  Sas: 'contact.sas',
  SetVerified: 'contact.setVerified',
  CreateInvite: 'contact.createInvite',
  RevokeInvite: 'contact.revokeInvite',
  Complete: 'contact.complete',
  OutstandingInvites: 'contact.outstandingInvites',
  PendingConfirmations: 'contact.pendingConfirmations',
} as const

type Ok<T> = { readonly ok: true } & T
type Result<T> = Ok<T> | ContactsFailure

// The one shared connection to `VITE_APEX_ORIGIN` (else the apex dev server), mounted on first use.
// A pushed stream is async setup, synchronous teardown — the component may unmount before the
// bridge has even mounted. `onError` is terminal: the producer ended the stream (its collector
// died), the gateway refused it, or the bridge never mounted.
const bridge = createApexBridge(ProxyMethod)

function call<T>(method: (typeof ProxyMethod)[keyof typeof ProxyMethod], params?: unknown): Promise<T> {
  return bridge.call<T>(method, params)
}

const subscribe = bridge.subscribe

// ── session ─────────────────────────────────────────────────────────────────

export function runtimeLease(): Promise<RuntimeLeaseState> {
  return call<RuntimeLeaseState>(ProxyMethod.RuntimeLease)
}

export async function runtimeUseHere(): Promise<void> {
  await call<void>(ProxyMethod.RuntimeUseHere)
}

export function subscribeSyncHealth(
  onValue: (status: SyncHealthStatus | null) => void,
  onError: (error: Error) => void,
): () => void {
  return subscribe(ProxyMethod.SyncHealthObserve, undefined, onValue, onError)
}

// ── the address book ────────────────────────────────────────────────────────

export function listContacts(): Promise<ContactsResult> {
  return call<ContactsResult>(ProxyMethod.List)
}

export function subscribeContacts(
  onValue: (result: ContactsResult) => void,
  onError: (error: Error) => void,
): () => void {
  return subscribe(ProxyMethod.ListObserve, undefined, onValue, onError)
}

export function getContact(personId: string): Promise<ContactResult> {
  return call<ContactResult>(ProxyMethod.Get, { personId })
}

export function subscribeContact(
  personId: string,
  onValue: (result: ContactResult) => void,
  onError: (error: Error) => void,
): () => void {
  return subscribe(ProxyMethod.GetObserve, { personId }, onValue, onError)
}

export function ownProfile(): Promise<OwnProfile> {
  return call<OwnProfile>(ProxyMethod.OwnProfile)
}

export function subscribeOwnProfile(
  onValue: (profile: OwnProfile) => void,
  onError: (error: Error) => void,
): () => void {
  return subscribe(ProxyMethod.OwnProfileObserve, undefined, onValue, onError)
}

export function setOwnDisplayName(name: string): Promise<Result<object>> {
  return call(ProxyMethod.SetOwnDisplayName, { name })
}

export function createContact(displayName: string): Promise<Result<{ personId: string }>> {
  return call(ProxyMethod.Create, { displayName })
}

export function renameContact(personId: string, displayName: string): Promise<ContactsWriteResult> {
  return call(ProxyMethod.Rename, { personId, displayName })
}

// ⚠⚠ Revokes nothing: shared-folder access survives (Q7b). The delete dialog says so.
export function deleteContact(personId: string): Promise<ContactsWriteResult> {
  return call(ProxyMethod.Delete, { personId })
}

export function unlinkIdentity(fingerprint: string): Promise<ContactsWriteResult> {
  return call(ProxyMethod.UnlinkIdentity, { fingerprint })
}

// ⚠⚠ Only after the user confirmed this exact pair; there is no un-merge (D7).
export function mergeContacts(
  sourcePersonId: string,
  targetPersonId: string,
): Promise<ContactsWriteResult> {
  return call(ProxyMethod.Merge, { sourcePersonId, targetPersonId })
}

export function relinkIdentity(fingerprint: string, personId: string): Promise<ContactsWriteResult> {
  return call(ProxyMethod.RelinkIdentity, { fingerprint, personId })
}

export function clearAvatar(personId: string): Promise<ContactsWriteResult> {
  return call(ProxyMethod.ClearAvatar, { personId })
}

export function saveContactDetails(personId: string, details: ContactDetails): Promise<Result<object>> {
  return call(ProxyMethod.SaveDetails, { personId, details })
}

export function importVcards(text: string): Promise<Result<{ imported: number; cards: number }>> {
  return call(ProxyMethod.ImportVcards, { text })
}

export function exportVcard(personId: string): Promise<Result<{ vcard: string }>> {
  return call(ProxyMethod.ExportVcard, { personId })
}

// `sas === ''` = unknown key or a locked realm: render the absence, never a placeholder.
export function contactSas(fingerprint: string): Promise<Result<{ sas: string }>> {
  return call(ProxyMethod.Sas, { fingerprint })
}

// ⚠⚠ `verified: true` ONLY from the user's explicit "they match" (threat model A24).
export function setVerified(fingerprint: string, verified: boolean): Promise<ContactsWriteResult> {
  return call(ProxyMethod.SetVerified, { fingerprint, verified })
}

// ── invitations (bare: `intentKind === ''` — Drive owns SHARE_FOLDER invites) ──

export function createInvite(): Promise<Result<{ link: string }>> {
  return call(ProxyMethod.CreateInvite, { intentKind: '', intentLabel: '' })
}

export function revokeInvite(inviteId: string): Promise<boolean> {
  return call<boolean>(ProxyMethod.RevokeInvite, { inviteId })
}

export function outstandingInvites(): Promise<Result<{ invites: OutstandingInvite[] }>> {
  return call(ProxyMethod.OutstandingInvites)
}

export function pendingConfirmations(): Promise<Result<{ confirmations: InviteConfirmation[] }>> {
  return call(ProxyMethod.PendingConfirmations)
}

export function completeHandshake(args: {
  inviteId: string
  accept: boolean
  // RAW — exactly the `claimedFingerprint` the prompt showed (T-CI-10).
  shownFingerprint: string
  personId?: string
  newPersonName?: string
  selfAssertedName?: string
  sasConfirmed?: boolean
}): Promise<CompleteResult> {
  return call<CompleteResult>(ProxyMethod.Complete, {
    inviteId: args.inviteId,
    accept: args.accept,
    shownFingerprint: args.shownFingerprint,
    personId: args.personId ?? '',
    newPersonName: args.newPersonName ?? '',
    selfAssertedName: args.selfAssertedName ?? '',
    sasConfirmed: args.sasConfirmed ?? false,
  })
}

export const APEX = bridge.apexOrigin
