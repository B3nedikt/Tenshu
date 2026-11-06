// Gateway coverage for the Contacts address book (web-app-suite.md Wave 1, run A).
//
// Same harness as `proxyGateway.test.ts`: every `@tenshu/sdk/*` module is mocked (the real ones
// value-import the Kotlin/JS bundle) and `serveProxyGateway` is captured so the handler and
// subscription maps can be driven directly. What this guards is the gateway's wiring and its
// param validation — the ops themselves are pinned by `ContactsAddressBookTest` in jsTest.
import { describe, expect, it, vi, beforeEach } from 'vitest'

type Handler = (params: unknown) => unknown
type Subscription = (
  params: unknown,
  emit: (value: unknown) => void,
  fail: (message: string) => void,
) => () => void
let captured: Record<string, Handler> = {}
let subscriptions: Record<string, Subscription> = {}

vi.mock('@tenshu/sdk/bridge', () => ({
  serveProxyGateway: (options: {
    handlers: Record<string, Handler>
    subscriptions?: Record<string, Subscription>
  }) => {
    captured = options.handlers
    subscriptions = options.subscriptions ?? {}
    return { dispose: () => {} }
  },
}))

const { ensureRuntime, contacts } = vi.hoisted(() => {
  const ok = { ok: true, changed: true }
  return {
    ensureRuntime: vi.fn(),
    contacts: {
      contacts: vi.fn(async () => ({ ok: true, contacts: [] })),
      observeContacts: vi.fn((_cb: (v: unknown) => void, _onError: (m: string) => void) => () => {}),
      contact: vi.fn(async (_id: string) => ({ ok: true, contact: null })),
      observeContact: vi.fn(
        (_id: string, _cb: (v: unknown) => void, _onError: (m: string) => void) => () => {},
      ),
      ownProfile: vi.fn(async () => ({ displayName: 'Me', fingerprint: '' })),
      observeOwnProfile: vi.fn((_cb: (v: unknown) => void, _onError: (m: string) => void) => () => {}),
      setOwnDisplayName: vi.fn(async (_n: string) => ({ ok: true })),
      createContact: vi.fn(async (_n: string) => ({ ok: true, personId: 'p-new' })),
      renameContact: vi.fn(async (_id: string, _n: string) => ok),
      deleteContact: vi.fn(async (_id: string) => ok),
      unlinkIdentity: vi.fn(async (_fp: string) => ok),
      mergeContacts: vi.fn(async (_s: string, _t: string) => ok),
      relinkIdentity: vi.fn(async (_fp: string, _id: string) => ok),
      clearAvatar: vi.fn(async (_id: string) => ok),
      saveContactDetails: vi.fn(async (_id: string, _d: unknown) => ({ ok: true })),
      importVcards: vi.fn(async (_t: string) => ({ ok: true, imported: 2, cards: 2 })),
      exportVcard: vi.fn(async (_id: string) => ({ ok: true, vcard: 'BEGIN:VCARD' })),
      contactSas: vi.fn(async (_fp: string) => ({ ok: true, sas: '123456' })),
      setVerified: vi.fn(async (_fp: string, _v: boolean) => ok),
      outstandingInvites: vi.fn(async () => ({ ok: true, invites: [] })),
      pendingConfirmations: vi.fn(async () => ({ ok: true, confirmations: [] })),
    },
  }
})

vi.mock('./runtime', () => ({ ensureRuntime: () => ensureRuntime() }))
vi.mock('@tenshu/sdk/contact', () => ({ Contacts: contacts }))

vi.mock('@tenshu/sdk/core', () => ({ TenshuSdk: {} }))
vi.mock('@tenshu/sdk/files', () => ({ FileSystem: {} }))
vi.mock('@tenshu/sdk/content', () => ({ ContentStream: {} }))
vi.mock('@tenshu/sdk/share', () => ({ Shares: {} }))
vi.mock('@tenshu/sdk/membership', () => ({ Membership: {} }))
vi.mock('@tenshu/sdk/sharedFiles', () => ({ SharedFiles: {} }))
vi.mock('@tenshu/sdk/storage', () => ({ Storage: {} }))
vi.mock('@tenshu/sdk/syncHealth', () => ({ SyncHealth: {} }))
vi.mock('@tenshu/sdk/syncStatus', () => ({ SyncStatus: {} }))
vi.mock('@tenshu/sdk/timeline', () => ({ Timeline: {} }))
vi.mock('@tenshu/sdk/calendar', () => ({ Calendar: {} }))
vi.mock('@tenshu/sdk/photos', () => ({ Photos: {}, MAX_FEED_PAGE_SIZE: 500 }))
vi.mock('@tenshu/sdk/fit', () => ({ Fit: {}, MAX_WORKOUT_PAGE_SIZE: 200, MAX_ACTIVITY_FILE_BYTES: 33554432 }))
vi.mock('@tenshu/sdk/home', () => ({ Home: {}, MAX_ACTIVITY_PAGE_SIZE: 200 }))
vi.mock('@tenshu/sdk/mia', () => ({
  Mia: {},
  MAX_UTTERANCE_CHARS: 500,
  MAX_TURN_PAGE_SIZE: 100,
  MiaMethod: {
    Ask: 'mia.ask',
    History: 'mia.history',
    HistoryObserve: 'mia.history.observe',
    TurnsPage: 'mia.turnsPage',
    Grants: 'mia.grants',
    GrantsObserve: 'mia.grants.observe',
    SetGranted: 'mia.grants.setGranted',
    SetWritable: 'mia.grants.setWritable',
  },
}))

import { mountProxyGateway } from './proxyGateway'

function handler(method: string): Handler {
  const h = captured[method]
  if (!h) throw new Error(`gateway registered no handler for '${method}'`)
  return h
}

function subscription(method: string): Subscription {
  const s = subscriptions[method]
  if (!s) throw new Error(`gateway registered no subscription for '${method}'`)
  return s
}

const details = {
  phones: [{ label: 'mobile', value: '+1 555' }],
  emails: [],
  addresses: [],
  organisation: 'Org',
  note: '',
}

describe('proxyGateway — contacts address book', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    captured = {}
    subscriptions = {}
    mountProxyGateway()
  })

  it('registers every address-book method beside the existing handshake ones', () => {
    const methods = Object.keys(captured).filter((m) => m.startsWith('contact.')).sort()
    expect(methods).toEqual([
      'contact.awaitAck',
      'contact.clearAvatar',
      'contact.complete',
      'contact.connected',
      'contact.create',
      'contact.createInvite',
      'contact.delete',
      'contact.exportVcard',
      'contact.get',
      'contact.importVcards',
      'contact.listInvites',
      'contact.list',
      'contact.merge',
      'contact.outstandingInvites',
      'contact.ownProfile',
      'contact.pending',
      'contact.pendingConfirmations',
      'contact.redeem',
      'contact.relinkIdentity',
      'contact.rename',
      'contact.revokeInvite',
      'contact.sas',
      'contact.saveDetails',
      'contact.setOwnDisplayName',
      'contact.setVerified',
      'contact.unlinkIdentity',
    ].sort())
    expect(Object.keys(subscriptions).filter((m) => m.startsWith('contact.')).sort()).toEqual([
      'contact.get.observe',
      'contact.list.observe',
      'contact.ownProfile.observe',
    ])
  })

  it.each<[string, unknown, keyof typeof contacts, unknown[]]>([
    ['contact.list', undefined, 'contacts', []],
    ['contact.get', { personId: 'p1' }, 'contact', ['p1']],
    ['contact.ownProfile', undefined, 'ownProfile', []],
    ['contact.setOwnDisplayName', { name: '' }, 'setOwnDisplayName', ['']],
    ['contact.create', { displayName: 'Ada' }, 'createContact', ['Ada']],
    ['contact.rename', { personId: 'p1', displayName: 'Grace' }, 'renameContact', ['p1', 'Grace']],
    ['contact.delete', { personId: 'p1' }, 'deleteContact', ['p1']],
    ['contact.unlinkIdentity', { fingerprint: 'fp' }, 'unlinkIdentity', ['fp']],
    ['contact.merge', { sourcePersonId: 'a', targetPersonId: 'b' }, 'mergeContacts', ['a', 'b']],
    ['contact.relinkIdentity', { fingerprint: 'fp', personId: 'p2' }, 'relinkIdentity', ['fp', 'p2']],
    ['contact.clearAvatar', { personId: 'p1' }, 'clearAvatar', ['p1']],
    ['contact.saveDetails', { personId: 'p1', details }, 'saveContactDetails', ['p1', details]],
    ['contact.importVcards', { text: 'BEGIN:VCARD' }, 'importVcards', ['BEGIN:VCARD']],
    ['contact.exportVcard', { personId: 'p1' }, 'exportVcard', ['p1']],
    ['contact.sas', { fingerprint: 'fp' }, 'contactSas', ['fp']],
    ['contact.setVerified', { fingerprint: 'fp', verified: false }, 'setVerified', ['fp', false]],
    ['contact.outstandingInvites', undefined, 'outstandingInvites', []],
    ['contact.pendingConfirmations', undefined, 'pendingConfirmations', []],
  ])('%s forwards its params and passes the result through', async (method, params, op, args) => {
    const result = await handler(method)(params)
    expect(contacts[op]).toHaveBeenCalledWith(...args)
    expect(result).toEqual(await (contacts[op] as unknown as (...a: unknown[]) => unknown)(...args))
    expect(ensureRuntime).toHaveBeenCalled()
  })

  it('passes a failure envelope through untouched — never as an empty success', async () => {
    const failure = { ok: false, error: { kind: 'error_contacts_unknown_key' } }
    contacts.relinkIdentity.mockResolvedValueOnce(failure as never)
    await expect(handler('contact.relinkIdentity')({ fingerprint: 'fp', personId: 'p' })).resolves.toEqual(failure)
  })

  it('passes a not-found contact through as null', async () => {
    await expect(handler('contact.get')({ personId: 'gone' })).resolves.toEqual({ ok: true, contact: null })
  })

  it.each<[string, unknown]>([
    ['contact.get', {}],
    ['contact.get', { personId: '' }],
    ['contact.rename', { personId: 'p1' }],
    ['contact.rename', { displayName: 'x' }],
    ['contact.delete', { personId: 7 }],
    ['contact.merge', { sourcePersonId: 'a' }],
    ['contact.merge', { sourcePersonId: '', targetPersonId: 'b' }],
    ['contact.relinkIdentity', { fingerprint: 'fp' }],
    ['contact.create', {}],
    ['contact.setOwnDisplayName', { name: 3 }],
    ['contact.importVcards', {}],
    ['contact.exportVcard', 'p1'],
    ['contact.sas', {}],
    ['contact.unlinkIdentity', { fingerprint: '' }],
    ['contact.clearAvatar', {}],
  ])('%s rejects malformed params %j without dispatching', async (method, params) => {
    await expect(handler(method)(params)).rejects.toThrow(/requires/)
    for (const op of Object.values(contacts)) expect(op).not.toHaveBeenCalled()
  })

  // ⚠⚠ A24: elevation needs an explicit human "the digits match". A truthy non-boolean, or an
  // omitted flag, must never reach the op as `true` — nor be defaulted to `false` silently.
  it.each([[{ fingerprint: 'fp' }], [{ fingerprint: 'fp', verified: 'true' }], [{ fingerprint: 'fp', verified: 1 }]])(
    'contact.setVerified refuses a non-boolean verdict %j',
    async (params) => {
      await expect(handler('contact.setVerified')(params)).rejects.toThrow(/verified: boolean/)
      expect(contacts.setVerified).not.toHaveBeenCalled()
    },
  )

  // ⚠ The save merges into the record, so a missing key read as empty would delete data.
  it.each<[string, unknown]>([
    ['no details', { personId: 'p1' }],
    ['missing phones', { personId: 'p1', details: { ...details, phones: undefined } }],
    ['a field without a value', { personId: 'p1', details: { ...details, emails: [{ label: 'home' }] } }],
    ['missing note', { personId: 'p1', details: { ...details, note: undefined } }],
  ])('contact.saveDetails rejects %s', async (_label, params) => {
    await expect(handler('contact.saveDetails')(params)).rejects.toThrow(/contact\.saveDetails requires/)
    expect(contacts.saveContactDetails).not.toHaveBeenCalled()
  })

  it('contact.saveDetails forwards only the published keys', async () => {
    await handler('contact.saveDetails')({
      personId: 'p1',
      details: { ...details, phones: [{ label: 'm', value: '1', extra: 'x' }], smuggled: true },
    })
    expect(contacts.saveContactDetails).toHaveBeenCalledWith('p1', {
      ...details,
      phones: [{ label: 'm', value: '1' }],
    })
  })

  it('the observe subscriptions emit what the SDK pushes and return its unsubscribe', () => {
    const stop = vi.fn()
    contacts.observeContacts.mockImplementationOnce((cb) => {
      cb({ ok: true, contacts: [] })
      return stop
    })
    const emit = vi.fn()
    const unsubscribe = subscription('contact.list.observe')(undefined, emit, vi.fn())
    expect(emit).toHaveBeenCalledWith({ ok: true, contacts: [] })
    unsubscribe()
    expect(stop).toHaveBeenCalledTimes(1)

    subscription('contact.get.observe')({ personId: 'p1' }, emit, vi.fn())
    expect(contacts.observeContact).toHaveBeenCalledWith('p1', expect.any(Function), expect.any(Function))
    subscription('contact.ownProfile.observe')(undefined, emit, vi.fn())
    expect(contacts.observeOwnProfile).toHaveBeenCalledTimes(1)
  })

  // Run-A review: a collector that died used to leave the subscriber frozen on its last value.
  it.each([
    ['contact.list.observe', undefined, () => contacts.observeContacts.mock.calls[0]?.[1]],
    ['contact.get.observe', { personId: 'p1' }, () => contacts.observeContact.mock.calls[0]?.[2]],
    ['contact.ownProfile.observe', undefined, () => contacts.observeOwnProfile.mock.calls[0]?.[1]],
  ] as const)('%s wires the SDK onError through to the gateway fail', (method, params, onError) => {
    const fail = vi.fn()
    subscription(method)(params, vi.fn(), fail)
    onError()?.('relation read failed')
    expect(fail).toHaveBeenCalledWith('relation read failed')
  })

  it('contact.get.observe rejects a missing personId before subscribing', () => {
    expect(() => subscription('contact.get.observe')({}, vi.fn(), vi.fn())).toThrow(/requires \{ personId \}/)
    expect(contacts.observeContact).not.toHaveBeenCalled()
  })
})
