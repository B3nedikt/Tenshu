// `photo.*`: the Photos web surface (web-app-suite Wave 2, Photos run A). These are thin
// pass-throughs to `Photos` (`@tenshu/sdk/photos`). The rules (keyset paging, not-found shapes,
// typed failures) live in the Kotlin SDK and are pinned by `PhotosLibraryContractTest`.
//
// ⚠ Still bytes are NOT served here. The Photos app calls Drive's existing `fs.getPreview`,
// `fs.getLargePreview` and `fs.*ContentStream` methods, so its allowlist names those too (run B).
// ⚠ No app's allowlist names a `photo.*` method yet, so these are unreachable until run B adds
// `web/photos` and its registry entry (F5).
import { Photos, MAX_FEED_PAGE_SIZE } from '@tenshu/sdk/photos'
import type { GatewayModule } from './module'
import { asRecord, readId, readString } from './params'

export const photoGateway: GatewayModule = {
  handlers: {
    // The feed: a keyset page in, a page and the next cursor out. Never the whole library.
    'photo.feedPage': async (params) => {
      const { cursor, pageSize, kind } = readFeedPage(params)
      return Photos.feedPage(cursor, pageSize, kind)
    },
    // Optional `{ kind }` — the Videos collection's times; absent = the library's.
    'photo.captureTimes': async (params) => Photos.captureTimes(readKind(params, 'photo.captureTimes')),

    // One photograph. `null` = nothing at that path; `ok: false` = the read failed.
    'photo.get': async (params) => Photos.photo(readId(params, 'path', 'photo.get')),
    'photo.details': async (params) => Photos.details(readId(params, 'path', 'photo.details')),
    // ⚠⚠ The MOTION clip, outside GetPreview's ladder. `bytes: null` is a wait, never cache it.
    'photo.motionClip': async (params) => Photos.motionClip(readId(params, 'path', 'photo.motionClip')),

    'photo.albums': async () => Photos.albums(),
    'photo.albumPhotos': async (params) => Photos.albumPhotos(readId(params, 'albumId', 'photo.albumPhotos')),
    // An empty contentId is a value (a file predating content addressing), not an omission.
    'photo.albumsFor': async (params) => Photos.albumsFor(readString(params, 'contentId', 'photo.albumsFor')),
    'photo.createAlbum': async (params) => Photos.createAlbum(readString(params, 'name', 'photo.createAlbum')),
    'photo.renameAlbum': async (params) =>
      Photos.renameAlbum(
        readId(params, 'albumId', 'photo.renameAlbum'),
        readString(params, 'name', 'photo.renameAlbum'),
      ),
    'photo.deleteAlbum': async (params) => Photos.deleteAlbum(readId(params, 'albumId', 'photo.deleteAlbum')),
    'photo.addToAlbum': async (params) =>
      Photos.addToAlbum(
        readId(params, 'albumId', 'photo.addToAlbum'),
        readId(params, 'path', 'photo.addToAlbum'),
      ),
    'photo.removeFromAlbum': async (params) =>
      Photos.removeFromAlbum(
        readId(params, 'albumId', 'photo.removeFromAlbum'),
        readId(params, 'contentId', 'photo.removeFromAlbum'),
      ),

    'photo.favourites': async () => Photos.favourites(),
    'photo.isFavourite': async (params) => Photos.isFavourite(readId(params, 'contentId', 'photo.isFavourite')),
    // `favourite` is required and must be a real boolean: nothing here defaults a write.
    'photo.setFavourite': async (params) => {
      const r = asRecord(params)
      if (typeof r.favourite !== 'boolean') throw new Error('photo.setFavourite requires { favourite: boolean }')
      return Photos.setFavourite(readId(params, 'contentId', 'photo.setFavourite'), r.favourite)
    },

    'photo.trashed': async () => Photos.trashed(),
    'photo.trash': async (params) => Photos.trash(readId(params, 'path', 'photo.trash')),
    'photo.restore': async (params) => Photos.restore(readId(params, 'contentId', 'photo.restore')),
    // ⚠⚠ Irreversible: the calling UI confirms first.
    'photo.deleteForever': async (params) =>
      Photos.deleteForever(readId(params, 'contentId', 'photo.deleteForever')),
  },
  subscriptions: {
    // Genuine push over the photo-service relations' Flows. ⚠ `fail` is wired through: a collector
    // that dies ends the subscriber's stream with a terminal error instead of freezing its screen.
    // ⚠ The feed has no push form. Its only Flow is a whole-realm stream, so it is snapshot + refetch.
    'photo.albums.observe': (_params, emit, fail) => Photos.observeAlbums((result) => emit(result), fail),
    'photo.favourites.observe': (_params, emit, fail) =>
      Photos.observeFavourites((result) => emit(result), fail),
    'photo.trash.observe': (_params, emit, fail) => Photos.observeTrash((result) => emit(result), fail),
  },
}

// `cursor` is optional (absent or null = the head) and opaque. A non-string is a malformed call.
// `pageSize` is a required integer in 1..MAX_FEED_PAGE_SIZE: a missing one must not quietly become
// "the whole library".
// `kind` is optional: absent = the library ('all'), 'videos' = the Videos collection (L5). Anything
// else is a malformed call — never quietly "everything".
function readKind(params: unknown, method: string): 'all' | 'videos' {
  const kind = params === undefined || params === null ? undefined : asRecord(params).kind
  if (kind === undefined || kind === 'all') return 'all'
  if (kind === 'videos') return 'videos'
  throw new Error(`${method} requires { kind?: 'all' | 'videos' }`)
}

function readFeedPage(params: unknown): { cursor: string | null; pageSize: number; kind: 'all' | 'videos' } {
  const r = asRecord(params)
  const cursor = r.cursor ?? null
  if (cursor !== null && typeof cursor !== 'string') throw new Error('photo.feedPage requires { cursor: string | null }')
  const pageSize = r.pageSize
  if (typeof pageSize !== 'number' || !Number.isInteger(pageSize) || pageSize < 1 || pageSize > MAX_FEED_PAGE_SIZE) {
    throw new Error(`photo.feedPage requires { pageSize: integer 1..${MAX_FEED_PAGE_SIZE} }`)
  }
  return { cursor, pageSize, kind: readKind(params, 'photo.feedPage') }
}
