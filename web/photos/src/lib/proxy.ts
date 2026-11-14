// Photos's single connection to the apex proxy (docs/web/architecture.md §5.2): the app has no
// backend of its own, and every read and write goes through the apex, which holds the keys and
// runs the runtime. The connection is the app-kit's (`createApexBridge`); this file is the wire
// names and the typed calls.
//
// ⚠⚠ No value import from `@tenshu/sdk` here — `import type` only. A value import drags the Kotlin
// runtime into this bundle (web-app-suite.md §7; `sdkBoundary.test.ts` fails on it).
//
// ⚠⚠ What this origin may call is decided apex-side (F5): `methods` in
// `web/apex/src/apps/registry/apps/photos.ts`. A name added to `ProxyMethod` below must be added
// there too — the apex's `gatewayPermissions.test.ts` reads this table and fails until it is.
//
// ⚠ Still bytes and video are Drive's surface, reused as is (photos-app.md §43): `fs.getPreview`
// for tiles, `fs.getLargePreview` for the viewer, the three `fs.*ContentStream` ops for playback
// and "Download original". Nothing here projects `File.Preview` (`drive-web-parity.md` §1.2i), and
// nothing reads a whole original into memory to render it.
import { createApexBridge } from '@tenshu/app-kit/bridge'
import type { RuntimeLeaseState } from '@tenshu/sdk/core'
import type { ContentStreamOpen } from '@tenshu/sdk/content'
import type { FileBytes, LargePreview } from '@tenshu/sdk/files'
import type {
  AlbumsResult,
  CaptureTimesResult,
  FeedPageResult,
  MotionClipResult,
  Photo,
  PhotoAlbum,
  PhotoDetails,
  PhotoDetailsResult,
  PhotosFailure,
  PhotosListResult,
  PhotosWriteResult,
  TrashedPhoto,
  TrashResult,
} from '@tenshu/sdk/photos'
import type { SyncHealthStatus } from '@tenshu/sdk/syncHealth'

export type {
  AlbumsResult,
  FeedPageResult,
  LargePreview,
  Photo,
  PhotoAlbum,
  PhotoDetails,
  PhotosFailure,
  PhotosListResult,
  TrashedPhoto,
  TrashResult,
}

// The wire names, shared with the apex gateway (`web/apex/src/lib/gateway/{photo,fs}.ts`).
export const ProxyMethod = {
  Ping: 'bridge.ping',
  RuntimeLease: 'runtime.lease',
  RuntimeUseHere: 'runtime.useHere',
  SyncHealthObserve: 'sync.health.observe',
  FeedPage: 'photo.feedPage',
  CaptureTimes: 'photo.captureTimes',
  Details: 'photo.details',
  MotionClip: 'photo.motionClip',
  Albums: 'photo.albums',
  AlbumsObserve: 'photo.albums.observe',
  AlbumPhotos: 'photo.albumPhotos',
  AlbumsFor: 'photo.albumsFor',
  CreateAlbum: 'photo.createAlbum',
  RenameAlbum: 'photo.renameAlbum',
  DeleteAlbum: 'photo.deleteAlbum',
  AddToAlbum: 'photo.addToAlbum',
  RemoveFromAlbum: 'photo.removeFromAlbum',
  Favourites: 'photo.favourites',
  FavouritesObserve: 'photo.favourites.observe',
  IsFavourite: 'photo.isFavourite',
  SetFavourite: 'photo.setFavourite',
  Trashed: 'photo.trashed',
  TrashObserve: 'photo.trash.observe',
  Trash: 'photo.trash',
  Restore: 'photo.restore',
  DeleteForever: 'photo.deleteForever',
  GetPreview: 'fs.getPreview',
  GetLargePreview: 'fs.getLargePreview',
  OpenContentStream: 'fs.openContentStream',
  ReadContentRange: 'fs.readContentRange',
  CloseContentStream: 'fs.closeContentStream',
} as const

type Ok<T> = { readonly ok: true } & T
type Result<T> = Ok<T> | PhotosFailure

// The one shared connection to `VITE_APEX_ORIGIN` (else the apex dev server), mounted on first use.
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

// ── the feed ────────────────────────────────────────────────────────────────

// One keyset page, newest first. `cursor` null = the head; pass back exactly the `nextCursor` the
// previous page returned (opaque, `v2.<ms>.<lastPath>`). `pageSize` 1..500.
// `kind`: the library (photos and videos) or the Videos collection (web-apps-layout.md L5) — the
// same walk, order and cursor. Sent only when it is not the library, so a library call is unchanged.
export type FeedKind = 'all' | 'videos'

export function feedPage(cursor: string | null, pageSize: number, kind: FeedKind = 'all'): Promise<FeedPageResult> {
  return call<FeedPageResult>(ProxyMethod.FeedPage, kind === 'all' ? { cursor, pageSize } : { cursor, pageSize, kind })
}

// Every capture time in the feed, newest first: the scrubber's map and the feed's length.
// ⚠ The same `kind` as the pages it maps — index i of these times is index i of that feed.
export function captureTimes(kind: FeedKind = 'all'): Promise<CaptureTimesResult> {
  return call<CaptureTimesResult>(ProxyMethod.CaptureTimes, kind === 'all' ? undefined : { kind })
}

export function photoDetails(path: string): Promise<PhotoDetailsResult> {
  return call<PhotoDetailsResult>(ProxyMethod.Details, { path })
}

// ⚠⚠ `bytes: null` is a wait, not an answer: never cache it. One clip in flight, never per tile.
export function motionClip(path: string): Promise<MotionClipResult> {
  return call<MotionClipResult>(ProxyMethod.MotionClip, { path })
}

// ── albums ──────────────────────────────────────────────────────────────────

export function listAlbums(): Promise<AlbumsResult> {
  return call<AlbumsResult>(ProxyMethod.Albums)
}

export function subscribeAlbums(
  onValue: (result: AlbumsResult) => void,
  onError: (error: Error) => void,
): () => void {
  return subscribe(ProxyMethod.AlbumsObserve, undefined, onValue, onError)
}

export function albumPhotos(albumId: string): Promise<PhotosListResult> {
  return call<PhotosListResult>(ProxyMethod.AlbumPhotos, { albumId })
}

export function albumsFor(contentId: string): Promise<AlbumsResult> {
  return call<AlbumsResult>(ProxyMethod.AlbumsFor, { contentId })
}

export function createAlbum(name: string): Promise<Result<{ albumId: string }>> {
  return call(ProxyMethod.CreateAlbum, { name })
}

export function renameAlbum(albumId: string, name: string): Promise<PhotosWriteResult> {
  return call(ProxyMethod.RenameAlbum, { albumId, name })
}

export function deleteAlbum(albumId: string): Promise<PhotosWriteResult> {
  return call(ProxyMethod.DeleteAlbum, { albumId })
}

export function addToAlbum(albumId: string, path: string): Promise<PhotosWriteResult> {
  return call(ProxyMethod.AddToAlbum, { albumId, path })
}

export function removeFromAlbum(albumId: string, contentId: string): Promise<Result<{ changed: boolean }>> {
  return call(ProxyMethod.RemoveFromAlbum, { albumId, contentId })
}

// ── favourites ──────────────────────────────────────────────────────────────

export function listFavourites(): Promise<PhotosListResult> {
  return call<PhotosListResult>(ProxyMethod.Favourites)
}

export function subscribeFavourites(
  onValue: (result: PhotosListResult) => void,
  onError: (error: Error) => void,
): () => void {
  return subscribe(ProxyMethod.FavouritesObserve, undefined, onValue, onError)
}

export function isFavourite(contentId: string): Promise<Result<{ favourite: boolean }>> {
  return call(ProxyMethod.IsFavourite, { contentId })
}

export function setFavourite(contentId: string, favourite: boolean): Promise<PhotosWriteResult> {
  return call(ProxyMethod.SetFavourite, { contentId, favourite })
}

// ── trash ───────────────────────────────────────────────────────────────────

export function listTrash(): Promise<TrashResult> {
  return call<TrashResult>(ProxyMethod.Trashed)
}

export function subscribeTrash(
  onValue: (result: TrashResult) => void,
  onError: (error: Error) => void,
): () => void {
  return subscribe(ProxyMethod.TrashObserve, undefined, onValue, onError)
}

export function trashPhoto(path: string): Promise<PhotosWriteResult> {
  return call(ProxyMethod.Trash, { path })
}

export function restorePhoto(contentId: string): Promise<PhotosWriteResult> {
  return call(ProxyMethod.Restore, { contentId })
}

// ⚠⚠ Irreversible: only after the user confirmed (`TrashView`'s dialog).
export function deleteForever(contentId: string): Promise<PhotosWriteResult> {
  return call(ProxyMethod.DeleteForever, { contentId })
}

// ── bytes: Drive's surface ──────────────────────────────────────────────────

// A tile: LIST or LARGE (this path never reaches XLARGE), fit within width × height.
export function getPreview(path: string, width: number, height: number): Promise<FileBytes> {
  return call<FileBytes>(ProxyMethod.GetPreview, { path, width, height })
}

// The viewer: the largest stored rung for `px`, which class answered, and whether the covering
// rung is `present`, `absent` or `unknown`.
export function getLargePreview(path: string, px: number): Promise<LargePreview> {
  return call<LargePreview>(ProxyMethod.GetLargePreview, { path, px })
}

export function openContentStream(path: string): Promise<ContentStreamOpen> {
  return call<ContentStreamOpen>(ProxyMethod.OpenContentStream, { path })
}

export function readContentRange(handle: string, offset: number, length: number): Promise<FileBytes> {
  return call<FileBytes>(ProxyMethod.ReadContentRange, { handle, offset, length })
}

export async function closeContentStream(handle: string): Promise<void> {
  await call<null>(ProxyMethod.CloseContentStream, { handle })
}

export const APEX = bridge.apexOrigin
