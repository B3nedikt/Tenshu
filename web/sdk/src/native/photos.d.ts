// `Photos` — the Photos web surface (web-app-suite Wave 2, Photos run A).
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).
// Rows mirror the `@JsExport` classes in `PhotosLibrary.kt`; calls mirror `Photos.kt`.
//
// ⚠ Still bytes are NOT here: tiles use `FileSystem.getPreview`, the viewer `getLargePreview`, a
// video's original `ContentStream`. The one byte read is `motionClip` (`MOTION` is outside
// `GetPreview`'s ladder).

interface NativePhoto {
  readonly path: string
  readonly name: string
  readonly capturedAtMs: number
  readonly isVideo: boolean
  readonly durationMs: number | null
  // '' for a file predating content addressing: it can be shown, not filed (albums, favourites, trash).
  readonly contentId: string
  readonly latE7: number | null
  readonly lngE7: number | null
  readonly pixelWidth: number | null
  readonly pixelHeight: number | null
}

interface NativePhotoAlbum {
  readonly albumId: string
  readonly name: string
  readonly createdAtMs: number
  readonly photoCount: number
  // The chosen cover, else the first entry; null for an empty album.
  readonly coverPath: string | null
}

interface NativeTrashedPhoto {
  readonly contentId: string
  // `photo.path` is its `/Trash/…` path.
  readonly photo: NativePhoto
  readonly originalPath: string
  readonly trashedAtMs: number
}

// The stored fields only — no EXIF parse of the original (photos-app.md §42.5).
interface NativePhotoDetails {
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

type NativePhotosOk<T> = ({ readonly ok: true } & T) | NativeFailure

interface PhotosInstance {
  // Keyset page, newest first. `cursor` null/'' = head; pass back `nextCursor` (opaque) for the next;
  // `nextCursor: null` after the last. `pageSize` 1..500, else `error_photos_bad_page_size`.
  // `kind`: 'all' (default — photos and videos) or 'videos' (the Videos collection); anything else
  // is `error_photos_bad_kind`. Same order and cursor either way.
  feedPage(
    cursor: string | null,
    pageSize: number,
    kind?: 'all' | 'videos',
  ): Promise<NativePhotosOk<{ readonly photos: NativePhoto[]; readonly nextCursor: string | null }>>
  // Every capture time in the feed (epoch ms), newest first — the scrubber's map and the feed length.
  // ⚠ Pass the same `kind` as the pages it maps.
  captureTimes(kind?: 'all' | 'videos'): Promise<NativePhotosOk<{ readonly times: number[] }>>
  // ⚠ `photo: null` / `details: null` / `bytes: null` = nothing at that path (an answer);
  // `ok: false` = the read failed.
  photo(path: string): Promise<NativePhotosOk<{ readonly photo: NativePhoto | null }>>
  details(path: string): Promise<NativePhotosOk<{ readonly details: NativePhotoDetails | null }>>
  // ⚠⚠ `bytes: null` is a WAIT, not an answer: never cache it. One clip in flight, never per tile.
  motionClip(path: string): Promise<NativePhotosOk<{ readonly bytes: Int8Array | null }>>

  albums(): Promise<NativePhotosOk<{ readonly albums: NativePhotoAlbum[] }>>
  // ⚠ `onError` is TERMINAL: it fires once when the stream can no longer deliver.
  observeAlbums(
    callback: (result: NativePhotosOk<{ readonly albums: NativePhotoAlbum[] }>) => void,
    onError: (message: string) => void,
  ): () => void
  // Unknown album = `error_photos_unknown_album`.
  albumPhotos(albumId: string): Promise<NativePhotosOk<{ readonly photos: NativePhoto[] }>>
  albumsFor(contentId: string): Promise<NativePhotosOk<{ readonly albums: NativePhotoAlbum[] }>>
  createAlbum(name: string): Promise<NativePhotosOk<{ readonly albumId: string }>>
  renameAlbum(albumId: string, name: string): Promise<NativePhotosOk<object>>
  deleteAlbum(albumId: string): Promise<NativePhotosOk<object>>
  // Missing photo = `error_photos_not_found`; no content id = `error_photos_no_content_id`;
  // not an image/video, or in the trash = `error_photos_not_a_photo` (same for `trash`).
  addToAlbum(albumId: string, path: string): Promise<NativePhotosOk<object>>
  // `changed: false` = it was not in the album.
  removeFromAlbum(albumId: string, contentId: string): Promise<NativePhotosOk<{ readonly changed: boolean }>>

  favourites(): Promise<NativePhotosOk<{ readonly photos: NativePhoto[] }>>
  observeFavourites(
    callback: (result: NativePhotosOk<{ readonly photos: NativePhoto[] }>) => void,
    onError: (message: string) => void,
  ): () => void
  isFavourite(contentId: string): Promise<NativePhotosOk<{ readonly favourite: boolean }>>
  setFavourite(contentId: string, favourite: boolean): Promise<NativePhotosOk<object>>

  trashed(): Promise<NativePhotosOk<{ readonly items: NativeTrashedPhoto[] }>>
  observeTrash(
    callback: (result: NativePhotosOk<{ readonly items: NativeTrashedPhoto[] }>) => void,
    onError: (message: string) => void,
  ): () => void
  trash(path: string): Promise<NativePhotosOk<object>>
  // Unknown id = `error_photos_not_in_trash`.
  restore(contentId: string): Promise<NativePhotosOk<object>>
  // ⚠⚠ Irreversible.
  deleteForever(contentId: string): Promise<NativePhotosOk<object>>
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const Photos: { getInstance(): PhotosInstance }
}
