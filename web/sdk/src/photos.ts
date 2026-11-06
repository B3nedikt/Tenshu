// Photos entry point (web-app-suite Wave 2, Photos run A): the capture-date feed with keyset
// paging, the viewer's stored details, albums, favourites, the trash and `MOTION` clips. Backed by
// `Photos` on the Kotlin side (`:tenshu-sdk-js`): the same `:photo-service` ops and `:file-service`
// reads the native app runs, dispatched through the apex in-process runtime.
//
// ⚠ Still bytes are Drive's surface, reused as is: `FileSystem.getPreview` for tiles,
// `FileSystem.getLargePreview` for the viewer (its `coveringRung` decides whether an original may be
// fetched), and `ContentStream` for a video's original. Nothing on web projects `File.Preview`
// (`drive-web-parity.md` §1.2i).
//
// ⚠ Faces, labels, search, places, duplicates, upload and mint-on-view are open questions
// (`photos-app.md` §42) and are not on this surface.

import * as bundle from './_bundle'

export interface PhotosFailure {
  readonly ok: false
  readonly error: { readonly kind: string; readonly message?: string }
}

// The catalogue keys this surface mints itself. Any other failure carries the key derived from the
// service's typed error (e.g. `error_generic`). Branch on `kind`, never on `message`.
export const PhotosErrorKind = {
  badCursor: 'error_photos_bad_cursor',
  badPageSize: 'error_photos_bad_page_size',
  unknownAlbum: 'error_photos_unknown_album',
  notFound: 'error_photos_not_found',
  noContentId: 'error_photos_no_content_id',
  notInTrash: 'error_photos_not_in_trash',
  notAPhoto: 'error_photos_not_a_photo',
  fileOpFailed: 'error_photos_file_op_failed',
} as const

// Rows per feed page at most: a page is one postMessage, never the library.
export const MAX_FEED_PAGE_SIZE = 500

export interface Photo {
  readonly path: string
  readonly name: string
  readonly capturedAtMs: number
  readonly isVideo: boolean
  readonly durationMs: number | null
  // '' for a file predating content addressing: shown, but it cannot be filed.
  readonly contentId: string
  readonly latE7: number | null
  readonly lngE7: number | null
  readonly pixelWidth: number | null
  readonly pixelHeight: number | null
}

export interface PhotoAlbum {
  readonly albumId: string
  readonly name: string
  readonly createdAtMs: number
  readonly photoCount: number
  readonly coverPath: string | null
}

export interface TrashedPhoto {
  readonly contentId: string
  readonly photo: Photo
  readonly originalPath: string
  readonly trashedAtMs: number
}

export interface PhotoDetails {
  readonly path: string
  readonly capturedAtMs: number | null
  readonly sizeBytes: number | null
  readonly pixelWidth: number | null
  readonly pixelHeight: number | null
  readonly orientation: number | null
  readonly cameraMake: string | null
  readonly cameraModel: string | null
  readonly durationMs: number | null
  readonly latE7: number | null
  readonly lngE7: number | null
}

// An opaque keyset cursor: pass back exactly what `nextCursor` returned. `null` is the head.
export type FeedCursor = string

export type FeedPageResult =
  | { readonly ok: true; readonly photos: Photo[]; readonly nextCursor: FeedCursor | null }
  | PhotosFailure
export type CaptureTimesResult = { readonly ok: true; readonly times: number[] } | PhotosFailure
// ⚠ `null` = nothing at that path (an answer); `ok: false` = the read failed.
export type PhotoResult = { readonly ok: true; readonly photo: Photo | null } | PhotosFailure
export type PhotoDetailsResult = { readonly ok: true; readonly details: PhotoDetails | null } | PhotosFailure
// ⚠⚠ `bytes: null` is a wait, not an answer — never cache it.
export type MotionClipResult = { readonly ok: true; readonly bytes: Int8Array | null } | PhotosFailure
export type AlbumsResult = { readonly ok: true; readonly albums: PhotoAlbum[] } | PhotosFailure
export type PhotosListResult = { readonly ok: true; readonly photos: Photo[] } | PhotosFailure
export type TrashResult = { readonly ok: true; readonly items: TrashedPhoto[] } | PhotosFailure
export type PhotosWriteResult = { readonly ok: true } | PhotosFailure

// Operation surface: every method is Promise-returning and safe as a TanStack Query `queryFn`.
// `observe*` push on change and return the unsubscribe; their `onError` is terminal.
export const Photos = bundle.Photos.getInstance()
