// One mock of `lib/proxy` for every component test. Each test file does
//   vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
// and drives the pushed streams through `streams`.
import { vi } from 'vitest'
import type { Contact, ContactsResult, OwnProfile } from '@tenshu/sdk/contact'

type Sub<T> = { onValue: (v: T) => void; onError: (e: Error) => void; closed: boolean }

function stream<T>() {
  const subs: Sub<T>[] = []
  return {
    subs,
    subscribe: (onValue: (v: T) => void, onError: (e: Error) => void) => {
      const sub: Sub<T> = { onValue, onError, closed: false }
      subs.push(sub)
      return () => {
        sub.closed = true
      }
    },
    push(value: T) {
      for (const s of subs) if (!s.closed) s.onValue(value)
    },
    fail(error: Error) {
      for (const s of subs) if (!s.closed) s.onError(error)
    },
    open: () => subs.filter((s) => !s.closed).length,
    reset: () => subs.splice(0),
  }
}

export const streams = {
  contacts: stream<ContactsResult>(),
  profile: stream<OwnProfile>(),
}

const ok = { ok: true as const, changed: true }

export const proxy = {
  runtimeLease: vi.fn(async () => 'held' as unknown),
  runtimeUseHere: vi.fn(async () => {}),
  subscribeSyncHealth: vi.fn((_v: unknown, _e: unknown) => () => {}),
  listContacts: vi.fn(async (): Promise<ContactsResult> => ({ ok: true, contacts: [] })),
  subscribeContacts: vi.fn(streams.contacts.subscribe),
  getContact: vi.fn(),
  subscribeContact: vi.fn(() => () => {}),
  ownProfile: vi.fn(async (): Promise<OwnProfile> => ({ displayName: '', fingerprint: '' })),
  subscribeOwnProfile: vi.fn(streams.profile.subscribe),
  setOwnDisplayName: vi.fn(async (_n: string) => ({ ok: true as const })),
  createContact: vi.fn(async (_n: string) => ({ ok: true as const, personId: 'p-new' })),
  renameContact: vi.fn(async (_id: string, _n: string) => ok),
  deleteContact: vi.fn(async (_id: string) => ok),
  unlinkIdentity: vi.fn(async (_fp: string) => ok),
  mergeContacts: vi.fn(async (_s: string, _t: string) => ok),
  relinkIdentity: vi.fn(async (_fp: string, _id: string) => ok),
  clearAvatar: vi.fn(async (_id: string) => ok),
  saveContactDetails: vi.fn(async (_id: string, _d: unknown) => ({ ok: true as const })),
  importVcards: vi.fn(async (_t: string) => ({ ok: true as const, imported: 1, cards: 1 })),
  exportVcard: vi.fn(async (_id: string) => ({ ok: true as const, vcard: 'BEGIN:VCARD\r\nEND:VCARD\r\n' })),
  contactSas: vi.fn(async (_fp: string) => ({ ok: true as const, sas: '123456' })),
  setVerified: vi.fn(async (_fp: string, _v: boolean) => ok),
  createInvite: vi.fn(async () => ({ ok: true as const, link: 'https://tenshu.app/i#v1.abc' })),
  revokeInvite: vi.fn(async (_id: string) => true),
  outstandingInvites: vi.fn(async () => ({ ok: true as const, invites: [] as unknown[] })),
  pendingConfirmations: vi.fn(async () => ({ ok: true as const, confirmations: [] as unknown[] })),
  completeHandshake: vi.fn(async (_a: unknown) => ({ outcome: 'completed', message: null }) as { outcome: string; message: string | null }),
}

export const proxyModule = { ...proxy, ProxyMethod: {}, APEX: 'http://localhost:5173' }

export function resetProxy() {
  // ⚠ `mockReset`, not `restoreMocks`: since Vitest 3 `restoreMocks` leaves `vi.fn(impl)` alone, so
  // a `mockResolvedValue` would leak into the next test. `mockReset` puts each back to its `impl`.
  for (const fn of Object.values(proxy)) fn.mockReset()
  streams.contacts.reset()
  streams.profile.reset()
}

let n = 0
export function contact(overrides: Partial<Contact> & { displayName: string }): Contact {
  n += 1
  return {
    personId: `p${n}`,
    trust: 'notConnected',
    identities: [],
    details: { phones: [], emails: [], addresses: [], organisation: '', note: '' },
    avatar: 'none',
    avatarDataUrl: null,
    ...overrides,
  }
}

export function identity(fingerprint: string, verification: string, selfAssertedName = '') {
  return { fingerprint, selfAssertedName, verification, verifiedAtMillis: 0, source: 'INVITE_LINK' }
}
