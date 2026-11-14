// Gateway coverage for the Photos surface (web-app-suite Wave 2, Photos run A).
//
// Same harness as `proxyGateway.contacts.test.ts`: every `@tenshu/sdk/*` module is mocked (the real
// ones value-import the Kotlin/JS bundle) and `serveProxyGateway` is captured so the handler and
// subscription maps can be driven directly. What this guards is the gateway's wiring and its param
// validation. The ops themselves are pinned by `PhotosLibraryContractTest` in jsTest.
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

const { ensureRuntime, photos } = vi.hoisted(() => {
  const ok = { ok: true }
  const observer = (_cb: (v: unknown) => void, _onError: (m: string) => void) => () => {}
  return {
    ensureRuntime: vi.fn(),
    photos: {
      feedPage: vi.fn(async (_c: string | null, _n: number) => ({ ok: true, photos: [], nextCursor: null })),
      captureTimes: vi.fn(async () => ({ ok: true, times: [] })),
      photo: vi.fn(async (_p: string) => ({ ok: true, photo: null })),
      details: vi.fn(async (_p: string) => ({ ok: true, details: null })),
      motionClip: vi.fn(async (_p: string) => ({ ok: true, bytes: null })),
      albums: vi.fn(async () => ({ ok: true, albums: [] })),
      observeAlbums: vi.fn(observer),
      albumPhotos: vi.fn(async (_id: string) => ({ ok: true, photos: [] })),
      albumsFor: vi.fn(async (_c: string) => ({ ok: true, albums: [] })),
      createAlbum: vi.fn(async (_n: string) => ({ ok: true, albumId: 'album-1' })),
      renameAlbum: vi.fn(async (_id: string, _n: string) => ok),
      deleteAlbum: vi.fn(async (_id: string) => ok),
      addToAlbum: vi.fn(async (_id: string, _p: string) => ok),
      removeFromAlbum: vi.fn(async (_id: string, _c: string) => ({ ok: true, changed: true })),
      favourites: vi.fn(async () => ({ ok: true, photos: [] })),
      observeFavourites: vi.fn(observer),
      isFavourite: vi.fn(async (_c: string) => ({ ok: true, favourite: false })),
      setFavourite: vi.fn(async (_c: string, _f: boolean) => ok),
      trashed: vi.fn(async () => ({ ok: true, items: [] })),
      observeTrash: vi.fn(observer),
      trash: vi.fn(async (_p: string) => ok),
      restore: vi.fn(async (_c: string) => ok),
      deleteForever: vi.fn(async (_c: string) => ok),
    },
  }
})

vi.mock('./runtime', () => ({ ensureRuntime: () => ensureRuntime() }))
vi.mock('@tenshu/sdk/photos', () => ({ Photos: photos, MAX_FEED_PAGE_SIZE: 500 }))
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

vi.mock('@tenshu/sdk/core', () => ({ TenshuSdk: {} }))
vi.mock('@tenshu/sdk/files', () => ({ FileSystem: {} }))
vi.mock('@tenshu/sdk/content', () => ({ ContentStream: {} }))
vi.mock('@tenshu/sdk/share', () => ({ Shares: {} }))
vi.mock('@tenshu/sdk/contact', () => ({ Contacts: {} }))
vi.mock('@tenshu/sdk/membership', () => ({ Membership: {} }))
vi.mock('@tenshu/sdk/sharedFiles', () => ({ SharedFiles: {} }))
vi.mock('@tenshu/sdk/storage', () => ({ Storage: {} }))
vi.mock('@tenshu/sdk/syncHealth', () => ({ SyncHealth: {} }))
vi.mock('@tenshu/sdk/syncStatus', () => ({ SyncStatus: {} }))
vi.mock('@tenshu/sdk/timeline', () => ({ Timeline: {} }))

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

describe('proxyGateway — photos', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    captured = {}
    subscriptions = {}
    mountProxyGateway()
  })

  it('registers exactly the run-A surface: no upload, search, faces, places or mint-on-view', () => {
    expect(Object.keys(captured).filter((m) => m.startsWith('photo.')).sort()).toEqual(
      [
        'photo.addToAlbum',
        'photo.albumPhotos',
        'photo.albums',
        'photo.albumsFor',
        'photo.captureTimes',
        'photo.createAlbum',
        'photo.deleteAlbum',
        'photo.deleteForever',
        'photo.details',
        'photo.favourites',
        'photo.feedPage',
        'photo.get',
        'photo.isFavourite',
        'photo.motionClip',
        'photo.removeFromAlbum',
        'photo.renameAlbum',
        'photo.restore',
        'photo.setFavourite',
        'photo.trash',
        'photo.trashed',
      ].sort(),
    )
    expect(Object.keys(subscriptions).filter((m) => m.startsWith('photo.')).sort()).toEqual([
      'photo.albums.observe',
      'photo.favourites.observe',
      'photo.trash.observe',
    ])
  })

  it.each<[string, unknown, keyof typeof photos, unknown[]]>([
    ['photo.feedPage', { pageSize: 60 }, 'feedPage', [null, 60, 'all']],
    ['photo.feedPage', { cursor: null, pageSize: 1 }, 'feedPage', [null, 1, 'all']],
    ['photo.feedPage', { cursor: 'v1.5.2', pageSize: 500 }, 'feedPage', ['v1.5.2', 500, 'all']],
    // L5: the Videos collection is the same feed with `kind`.
    ['photo.feedPage', { pageSize: 60, kind: 'videos' }, 'feedPage', [null, 60, 'videos']],
    ['photo.captureTimes', undefined, 'captureTimes', ['all']],
    ['photo.captureTimes', { kind: 'videos' }, 'captureTimes', ['videos']],
    ['photo.get', { path: '/a.jpg' }, 'photo', ['/a.jpg']],
    ['photo.details', { path: '/a.jpg' }, 'details', ['/a.jpg']],
    ['photo.motionClip', { path: '/v.mp4' }, 'motionClip', ['/v.mp4']],
    ['photo.albums', undefined, 'albums', []],
    ['photo.albumPhotos', { albumId: 'al' }, 'albumPhotos', ['al']],
    ['photo.albumsFor', { contentId: '' }, 'albumsFor', ['']],
    ['photo.createAlbum', { name: '' }, 'createAlbum', ['']],
    ['photo.renameAlbum', { albumId: 'al', name: 'Trip' }, 'renameAlbum', ['al', 'Trip']],
    ['photo.deleteAlbum', { albumId: 'al' }, 'deleteAlbum', ['al']],
    ['photo.addToAlbum', { albumId: 'al', path: '/a.jpg' }, 'addToAlbum', ['al', '/a.jpg']],
    ['photo.removeFromAlbum', { albumId: 'al', contentId: 'c' }, 'removeFromAlbum', ['al', 'c']],
    ['photo.favourites', undefined, 'favourites', []],
    ['photo.isFavourite', { contentId: 'c' }, 'isFavourite', ['c']],
    ['photo.setFavourite', { contentId: 'c', favourite: false }, 'setFavourite', ['c', false]],
    ['photo.trashed', undefined, 'trashed', []],
    ['photo.trash', { path: '/a.jpg' }, 'trash', ['/a.jpg']],
    ['photo.restore', { contentId: 'c' }, 'restore', ['c']],
    ['photo.deleteForever', { contentId: 'c' }, 'deleteForever', ['c']],
  ])('%s forwards %j and passes the result through', async (method, params, op, args) => {
    const result = await handler(method)(params)
    expect(photos[op]).toHaveBeenCalledWith(...args)
    expect(result).toEqual(await (photos[op] as unknown as (...a: unknown[]) => unknown)(...args))
    expect(ensureRuntime).toHaveBeenCalled()
  })

  it('passes a failure envelope through untouched — never as an empty success', async () => {
    const failure = { ok: false, error: { kind: 'error_photos_unknown_album', message: 'al' } }
    photos.albumPhotos.mockResolvedValueOnce(failure as never)
    await expect(handler('photo.albumPhotos')({ albumId: 'al' })).resolves.toEqual(failure)
  })

  it('passes a not-found photograph through as null', async () => {
    await expect(handler('photo.get')({ path: '/gone.jpg' })).resolves.toEqual({ ok: true, photo: null })
  })

  it.each<[string, unknown]>([
    // A missing pageSize must not become "the whole library".
    ['photo.feedPage', {}],
    ['photo.feedPage', { pageSize: 0 }],
    ['photo.feedPage', { pageSize: 501 }],
    ['photo.feedPage', { pageSize: 2.5 }],
    ['photo.feedPage', { pageSize: '60' }],
    ['photo.feedPage', { cursor: 7, pageSize: 60 }],
    // An unknown kind is a malformed call, never quietly "everything".
    ['photo.feedPage', { pageSize: 60, kind: 'images' }],
    ['photo.captureTimes', { kind: 'images' }],
    ['photo.get', {}],
    ['photo.get', { path: '' }],
    ['photo.details', { path: 3 }],
    ['photo.motionClip', undefined],
    ['photo.albumPhotos', { albumId: '' }],
    ['photo.albumsFor', {}],
    ['photo.createAlbum', {}],
    ['photo.renameAlbum', { albumId: 'al' }],
    ['photo.renameAlbum', { name: 'x' }],
    ['photo.deleteAlbum', {}],
    ['photo.addToAlbum', { albumId: 'al' }],
    ['photo.addToAlbum', { path: '/a.jpg' }],
    ['photo.removeFromAlbum', { albumId: 'al', contentId: '' }],
    ['photo.isFavourite', {}],
    ['photo.trash', { path: '' }],
    ['photo.restore', {}],
    ['photo.deleteForever', { contentId: '' }],
  ])('%s rejects malformed params %j without dispatching', async (method, params) => {
    await expect(handler(method)(params)).rejects.toThrow(/requires/)
    for (const op of Object.values(photos)) expect(op).not.toHaveBeenCalled()
  })

  // A write is never defaulted: an omitted or truthy non-boolean flag must not reach the op.
  it.each([[{ contentId: 'c' }], [{ contentId: 'c', favourite: 'true' }], [{ contentId: 'c', favourite: 1 }]])(
    'photo.setFavourite refuses a non-boolean flag %j',
    async (params) => {
      await expect(handler('photo.setFavourite')(params)).rejects.toThrow(/favourite: boolean/)
      expect(photos.setFavourite).not.toHaveBeenCalled()
    },
  )

  it('the observe subscriptions emit what the SDK pushes, wire `fail` through and return its unsubscribe', () => {
    const stop = vi.fn()
    photos.observeAlbums.mockImplementationOnce((cb) => {
      cb({ ok: true, albums: [] })
      return stop
    })
    const emit = vi.fn()
    const fail = vi.fn()
    const unsubscribe = subscription('photo.albums.observe')(undefined, emit, fail)
    expect(emit).toHaveBeenCalledWith({ ok: true, albums: [] })
    unsubscribe()
    expect(stop).toHaveBeenCalledTimes(1)

    photos.observeTrash.mockImplementationOnce((_cb, onError) => {
      onError('photos stream ended')
      return () => {}
    })
    subscription('photo.trash.observe')(undefined, emit, fail)
    expect(fail).toHaveBeenCalledWith('photos stream ended')

    subscription('photo.favourites.observe')(undefined, emit, fail)
    expect(photos.observeFavourites).toHaveBeenCalledWith(expect.any(Function), fail)
  })
})
