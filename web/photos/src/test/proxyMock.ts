// One mock of `lib/proxy` for every component test. Each test file does
//   vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
// and drives the pushed streams through `streams`.
import { vi } from 'vitest'
import type { AlbumsResult, Photo, PhotoAlbum, PhotosListResult, TrashResult } from '@tenshu/sdk/photos'
import type { LargePreview } from '@tenshu/sdk/files'

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
    reset: () => subs.splice(0),
  }
}

export const streams = {
  albums: stream<AlbumsResult>(),
  favourites: stream<PhotosListResult>(),
  trash: stream<TrashResult>(),
}

const ok = { ok: true as const }
type Write = Promise<{ ok: true } | { ok: false; error: { kind: string } }>

export const proxy = {
  runtimeLease: vi.fn(async () => 'held' as unknown),
  runtimeUseHere: vi.fn(async () => {}),
  subscribeSyncHealth: vi.fn((_v: unknown, _e: unknown) => () => {}),
  feedPage: vi.fn(async (_cursor: string | null, _size: number) => ({ ok: true as const, photos: [] as Photo[], nextCursor: null as string | null }) as unknown),
  captureTimes: vi.fn(async () => ({ ok: true as const, times: [] as number[] }) as unknown),
  photoDetails: vi.fn(async (_path: string) => ({ ok: true as const, details: null }) as unknown),
  motionClip: vi.fn(async (_path: string) => ({ ok: true as const, bytes: null }) as unknown),
  listAlbums: vi.fn(async (): Promise<AlbumsResult> => ({ ok: true, albums: [] })),
  subscribeAlbums: vi.fn(streams.albums.subscribe),
  albumPhotos: vi.fn(async (_id: string): Promise<PhotosListResult> => ({ ok: true, photos: [] })),
  albumsFor: vi.fn(async (_c: string): Promise<AlbumsResult> => ({ ok: true, albums: [] })),
  createAlbum: vi.fn(async (_name: string) => ({ ok: true as const, albumId: 'a-new' }) as unknown),
  renameAlbum: vi.fn(async (_id: string, _name: string): Write => ok),
  deleteAlbum: vi.fn(async (_id: string): Write => ok),
  addToAlbum: vi.fn(async (_id: string, _path: string): Write => ok),
  removeFromAlbum: vi.fn(async (_id: string, _c: string) => ({ ok: true as const, changed: true }) as unknown),
  listFavourites: vi.fn(async (): Promise<PhotosListResult> => ({ ok: true, photos: [] })),
  subscribeFavourites: vi.fn(streams.favourites.subscribe),
  isFavourite: vi.fn(async (_c: string) => ({ ok: true as const, favourite: false }) as unknown),
  setFavourite: vi.fn(async (_c: string, _f: boolean): Write => ok),
  listTrash: vi.fn(async (): Promise<TrashResult> => ({ ok: true, items: [] })),
  subscribeTrash: vi.fn(streams.trash.subscribe),
  trashPhoto: vi.fn(async (_path: string): Write => ok),
  restorePhoto: vi.fn(async (_c: string): Write => ok),
  deleteForever: vi.fn(async (_c: string): Write => ok),
  getPreview: vi.fn(async (_p: string, _w: number, _h: number) => ({ ok: true as const, bytes: null }) as unknown),
  getLargePreview: vi.fn(async (_p: string, _px: number): Promise<LargePreview> => ({
    ok: true,
    bytes: null,
    thumbnailClass: null,
    coveringRung: 'unknown',
  })),
  openContentStream: vi.fn(async (_p: string) => ({ ok: false as const, error: { kind: 'error_generic' } }) as unknown),
  readContentRange: vi.fn(async (_h: string, _o: number, _l: number) => ({ ok: true as const, bytes: new Int8Array(0) }) as unknown),
  closeContentStream: vi.fn(async (_h: string) => {}),
}

export const proxyModule = { ...proxy, ProxyMethod: {}, APEX: 'http://localhost:5173' }

export function resetProxy() {
  // ⚠ `mockReset`, not `restoreMocks`: `mockReset` puts each `vi.fn(impl)` back to its `impl`.
  for (const fn of Object.values(proxy)) fn.mockReset()
  streams.albums.reset()
  streams.favourites.reset()
  streams.trash.reset()
}

let n = 0
const DAY = 86_400_000
// 2026-10-01T12:00Z, newest first from there.
export const T0 = Date.UTC(2026, 9, 1, 12)

export function photo(overrides: Partial<Photo> = {}): Photo {
  n += 1
  return {
    path: `/Photos/IMG_${String(n).padStart(5, '0')}.jpg`,
    name: `IMG_${String(n).padStart(5, '0')}.jpg`,
    capturedAtMs: T0 - n * DAY,
    isVideo: false,
    durationMs: null,
    contentId: `c${n}`,
    latE7: null,
    lngE7: null,
    pixelWidth: 4032,
    pixelHeight: 3024,
    ...overrides,
  }
}

export function album(overrides: Partial<PhotoAlbum> & { name: string }): PhotoAlbum {
  n += 1
  return { albumId: `a${n}`, createdAtMs: T0, photoCount: 0, coverPath: null, ...overrides }
}

/** A library of [count] photos, newest first, one an hour, with a feedPage over it. */
export function library(count: number): { photos: Photo[]; times: number[] } {
  const photos = Array.from({ length: count }, (_, i) =>
    photo({ path: `/Photos/p${i}.jpg`, name: `p${i}.jpg`, capturedAtMs: T0 - i * 3_600_000, contentId: `lc${i}` }),
  )
  return { photos, times: photos.map((p) => p.capturedAtMs) }
}

/** A keyset `feedPage` over [photos]: the cursor names the last row served, like `v2.<ms>.<path>`. */
export function keysetFeed(photos: readonly Photo[]) {
  return async (cursor: string | null, pageSize: number) => {
    const from = cursor === null ? 0 : photos.findIndex((p) => `v2.${p.capturedAtMs}.${p.path}` === cursor) + 1
    const page = photos.slice(from, from + pageSize)
    const last = page[page.length - 1]
    const nextCursor = page.length < pageSize || !last ? null : `v2.${last.capturedAtMs}.${last.path}`
    return { ok: true as const, photos: page, nextCursor }
  }
}
