// Drive's single connection to the apex proxy. The subdomain has no direct
// backend access (docs/web/architecture.md §5.2) — every read/write goes
// through this bridge to `tenshu.app`, which holds the keys and runs the
// in-process proxy. This is the web analogue of Drive's IPC channel to
// Control on Android/desktop. The connection itself is the app-kit's
// (`createApexBridge`); this file is Drive's wire names and typed calls.
//
// ⚠⚠ No `@tenshu/sdk` value import here: types only (the bridge is the
// app-kit's), or the Kotlin runtime lands in Drive's bundle.
import { createApexBridge } from '@tenshu/app-kit/bridge'
import type { File, FileActivity, FileDetails, LargePreview } from '@tenshu/sdk/files'
import type { ShareLink, ShareListItem } from '@tenshu/sdk/share'
import type {
  CompleteResult,
  ContactInviteItem,
  ContactItem,
  InviteAck,
  PendingRedemptionItem,
  RedeemResult,
} from '@tenshu/sdk/contact'
import type { MemberItem, PendingGrantItem } from '@tenshu/sdk/membership'
import type { InboundRealmItem, SharedEntryItem, SharedFolderItem } from '@tenshu/sdk/sharedFiles'
import type { ContentStreamOpen } from '@tenshu/sdk/content'
import type { FileBytes } from '@tenshu/sdk/files'

// ⚠ Declared here rather than inlined seven times. `client.call<T>` is a CAST, not a check — a
// wrapper that keeps claiming the old type typechecks perfectly while receiving the new shape,
// so these annotations are the only thing making the change visible to consumers at all.
type ProxyFailure = { ok: false; error: { kind: string; message?: string } }
import type { StorageStatus } from '@tenshu/sdk/storage'
import type { RuntimeLeaseState } from '@tenshu/sdk/core'
import type { SyncHealthStatus } from '@tenshu/sdk/syncHealth'
import type { ContentTransfer, SchemaFence } from '@tenshu/sdk/syncStatus'

// The methods the apex gateway answers. Kept as string constants so the
// drive side and the apex `proxyGateway` agree on the wire names; as the
// Phase B SDK boundaries land, each new op adds one method here + one
// handler on the apex side.
export const ProxyMethod = {
  Ping: 'bridge.ping',
  RuntimeLease: 'runtime.lease',
  RuntimeUseHere: 'runtime.useHere',
  ListFiles: 'fs.list',
  RecentFiles: 'fs.recentFiles',
  Search: 'fs.search',
  HasLocalData: 'fs.hasLocalData',
  UploadFile: 'fs.uploadFile',
  CreateFolder: 'fs.createFolder',
  Rename: 'fs.rename',
  Move: 'fs.move',
  Delete: 'fs.delete',
  Details: 'fs.details',
  Activity: 'fs.activity',
  GetData: 'fs.getData',
  GetPreview: 'fs.getPreview',
  GetLargePreview: 'fs.getLargePreview',
  OpenContentStream: 'fs.openContentStream',
  ReadContentRange: 'fs.readContentRange',
  CloseContentStream: 'fs.closeContentStream',
  ShareCreate: 'shares.create',
  ShareList: 'shares.list',
  ShareRevoke: 'shares.revoke',
  StorageStatus: 'storage.status',
  SyncHealthObserve: 'sync.health.observe',
  SyncHealth: 'sync.health',
  ContentTransfer: 'sync.contentTransfer',
  SchemaFence: 'sync.schemaFence',
  SyncNow: 'sync.now',
  ContactCreateInvite: 'contact.createInvite',
  ContactRevokeInvite: 'contact.revokeInvite',
  ContactRedeem: 'contact.redeem',
  ContactAwaitAck: 'contact.awaitAck',
  ContactComplete: 'contact.complete',
  ContactListInvites: 'contact.listInvites',
  ContactConnected: 'contact.connected',
  ContactPending: 'contact.pending',
  MembershipGrant: 'membership.grant',
  MembershipAccept: 'membership.accept',
  MembershipMembers: 'membership.members',
  MembershipRemove: 'membership.remove',
  MembershipPollPending: 'membership.pollPending',
  MembershipDecline: 'membership.decline',
  SharedWithMe: 'sharedFile.sharedWithMe',
  SharedBrowse: 'sharedFile.browse',
  SharedReadFile: 'sharedFile.readFile',
  ShareFolder: 'sharedFile.shareFolder',
  SharedFolders: 'sharedFile.sharedFolders',
} as const

// The apex origin to dial (`VITE_APEX_ORIGIN`, else the apex Vite dev server) and the one shared
// connection — mounting twice would create two hidden iframes, so the bridge memoises the mount.
const bridge = createApexBridge(ProxyMethod)
const apexProxy = bridge.client

// Transport liveness probe — proves the cross-origin channel is up without
// depending on an authenticated session. Returns the apex's echo.
export async function ping(): Promise<{ pong: true; apexOrigin: string }> {
  const client = await apexProxy()
  return client.call<{ pong: true; apexOrigin: string }>(ProxyMethod.Ping)
}

// List the files under `path` via the apex's `FileSystem.list`. Returns an
// empty array when there is no session yet — Phase A proves the transport;
// real content arrives once the apex tab is signed in.
export async function listFiles(path: string): Promise<readonly File[]> {
  const client = await apexProxy()
  return client.call<readonly File[]>(ProxyMethod.ListFiles, { path })
}

// The `limit` most-recently-modified entries across the whole drive, newest
// first — the Home surface. Mirrors the native `FilesRepository.recentFiles`.
export async function recentFiles(limit = 20): Promise<readonly File[]> {
  const client = await apexProxy()
  return client.call<readonly File[]>(ProxyMethod.RecentFiles, { limit })
}

// Upload `data` as a new file at `parentPath/fileName`. Resolves `true` on
// success, `false` on any error (incl. a name collision). Get the bytes from a
// browser `File` via `new Int8Array(await file.arrayBuffer())`. Mirrors the
// native `FilesRepository.uploadFile`.
export async function uploadFile(
  parentPath: string,
  fileName: string,
  data: Int8Array,
): Promise<boolean> {
  const client = await apexProxy()
  return client.call<boolean>(ProxyMethod.UploadFile, { parentPath, fileName, data })
}

// Create a folder at `path`. Resolves `true` on success, `false` on any error
// (including an existing folder — idempotent). Mirrors `createFolder`.
export async function createFolder(path: string): Promise<boolean> {
  const client = await apexProxy()
  return client.call<boolean>(ProxyMethod.CreateFolder, { path })
}

// Rename the entry at `path` to `newName` (same parent). Resolves `true` on
// success, `false` on any error (incl. a name collision). Mirrors the
// native `FilesRepository.renameFile`.
export async function rename(path: string, newName: string): Promise<boolean> {
  const client = await apexProxy()
  return client.call<boolean>(ProxyMethod.Rename, { path, newName })
}

// Move the entry at `sourcePath` into `destinationFolderPath` (a folder
// path; `"/"` for the root), keeping its name. Mirrors `moveFile`.
export async function move(
  sourcePath: string,
  destinationFolderPath: string,
): Promise<boolean> {
  const client = await apexProxy()
  return client.call<boolean>(ProxyMethod.Move, { sourcePath, destinationFolderPath })
}

// Delete the entry at `path` (recursive for folders). Mirrors `deleteFile`.
export async function deleteEntry(path: string): Promise<boolean> {
  const client = await apexProxy()
  return client.call<boolean>(ProxyMethod.Delete, { path })
}

// Metadata snapshot for the entry at `path` (size + created/modified, raw),
// or `null` when it doesn't exist. Mirrors `FilesRepository.fileDetails`.
export async function details(
  path: string,
): Promise<{ ok: true; row: FileDetails | null } | ProxyFailure> {
  const client = await apexProxy()
  return client.call<{ ok: true; row: FileDetails | null } | ProxyFailure>(
    ProxyMethod.Details,
    { path },
  )
}

// Modification history for the file at `path`, newest first (empty on error /
// no history). Mirrors `FilesRepository.fileActivity`.
export async function activity(path: string): Promise<readonly FileActivity[]> {
  const client = await apexProxy()
  return client.call<readonly FileActivity[]>(ProxyMethod.Activity, { path })
}

// The raw bytes of the file at `path`, or `null` when it doesn't exist / can't
// be read. Fetched lazily on open (the Details drawer's Open action), mirroring
// the shared-file read path. Bytes ride the bridge as an `Int8Array`.
export async function getData(path: string): Promise<FileBytes> {
  const client = await apexProxy()
  return client.call<FileBytes>(ProxyMethod.GetData, { path })
}

// D2b streaming (drive-streaming-preview.md §3.5, S6a): the pull range-source over
// the bridge. `openContentStream` opens a seekable session for a personal file
// (null if not streamable yet); `readContentRange` reads a byte range on demand
// (only the covering chunks decrypt — empty Int8Array at EOF, null on error);
// `closeContentStream` releases it. The Service Worker (S6b) drives these to
// answer HTTP Range requests for a <video src> without loading the whole file.
export async function openContentStream(path: string): Promise<ContentStreamOpen> {
  const client = await apexProxy()
  return client.call<ContentStreamOpen>(ProxyMethod.OpenContentStream, { path })
}

export async function readContentRange(
  handle: string,
  offset: number,
  length: number,
): Promise<FileBytes> {
  const client = await apexProxy()
  return client.call<FileBytes>(ProxyMethod.ReadContentRange, { handle, offset, length })
}

export async function closeContentStream(handle: string): Promise<void> {
  const client = await apexProxy()
  await client.call<null>(ProxyMethod.CloseContentStream, { handle })
}

// A thumbnail for the image at `path`, fit within `width × height`, or `null` if
// there's no file there or its bytes aren't a decodable image. Drives the lazy
// row thumbnails (`FileThumbnail`). Bytes ride the bridge as Int8Array.
//
// ⚠ The encoding is whatever the SDK hands back, not a fixed one: since W2b the
// normal answer is a STORED thumbnail class synced from whichever device minted
// it, and every rung is minted as JPEG (LIST moved off PNG on 2026-08-31 —
// lossless is ~9× larger on photographic content). WebP only appears on the
// local-bytes fallback, which renders in-browser and never leaves it. Treat the
// bytes as an opaque image blob — don't assume a type.
//
// ⚠ This path only ever reaches LIST and LARGE: `readPreviewJs` projects
// `File.Thumbnail` and `File.ThumbnailLarge`, never XLARGE.
export async function getPreview(
  path: string,
  width: number,
  height: number,
): Promise<FileBytes> {
  const client = await apexProxy()
  return client.call<FileBytes>(ProxyMethod.GetPreview, { path, width, height })
}

// X-5: the lightbox's read (`drive-xxlarge-thumbnail-rung.md` §4.8). Names the XXLARGE/XLARGE
// stored classes by relation, and — unlike `getPreview` above — reports WHICH class answered and
// whether the covering one is absent or merely unknown.
//
// ⚠⚠ Those two extra fields are the whole point: the lightbox must choose between drawing this and
// downloading the entire original, and bytes alone cannot make that choice. See `needsOriginal`.
export async function getLargePreview(path: string, px: number): Promise<LargePreview> {
  const client = await apexProxy()
  return client.call<LargePreview>(ProxyMethod.GetLargePreview, { path, px })
}

// Mode-A link sharing (drive-file-sharing.md §3.3/§6.5). `createShareLink`
// mints a fresh link for `path` (0 = no expiry / no cap); resolves
// `{ shareId, urlFragment }` or null. `listShares`/`revokeShare` are the *My
// shares* management surface.
export async function createShareLink(
  path: string,
  expiresAtMillis = 0,
  maxOpens = 0,
): Promise<ShareLink | null> {
  const client = await apexProxy()
  return client.call<ShareLink | null>(ProxyMethod.ShareCreate, {
    path,
    expiresAtMillis,
    maxOpens,
  })
}

export async function listShares(): Promise<readonly ShareListItem[]> {
  const client = await apexProxy()
  return client.call<readonly ShareListItem[]>(ProxyMethod.ShareList)
}

export async function revokeShare(shareId: string): Promise<boolean> {
  const client = await apexProxy()
  return client.call<boolean>(ProxyMethod.ShareRevoke, { shareId })
}

// The storage-provision snapshot (web-storage-status.md) — `hasStorage` plus the
// raw usage/limit byte counts, or `null` until the apex runtime has derived a
// status. Mirrors the native `StorageService.status()`. The Settings view reads
// it; upload/share gating layers on once a real entitlement-backed status lands.
export async function storageStatus(): Promise<StorageStatus | null> {
  const client = await apexProxy()
  return client.call<StorageStatus | null>(ProxyMethod.StorageStatus)
}

// One runtime per origin (docs/web/architecture.md "Cross-tab"). The apex iframe this tab dials
// shares its lock with every apex tab, so this Drive tab may be the one that does NOT run Tenshu —
// then every other call here fails, and the banner says why.
export async function runtimeLease(): Promise<RuntimeLeaseState> {
  const client = await apexProxy()
  return client.call<RuntimeLeaseState>(ProxyMethod.RuntimeLease)
}

// "Use here": the holder hands over, then the caller reloads this page (and so the iframe).
export async function runtimeUseHere(): Promise<void> {
  const client = await apexProxy()
  await client.call<void>(ProxyMethod.RuntimeUseHere)
}

// W3 — whether this device can sync, and why not. `null` until the first tick has produced a
// row. ⚠ A snapshot: the bridge has no push channel, so the banner refetches rather than
// subscribing.
export async function syncHealth(): Promise<SyncHealthStatus | null> {
  const client = await apexProxy()
  return client.call<SyncHealthStatus | null>(ProxyMethod.SyncHealth)
}

// W12's first consumer. Pushes every change to the sync-health row until the returned lambda is
// called; the apex side is a real Flow collector, not a poll.
//
// ⚠ Async setup, synchronous teardown. `apexProxy()` may still be mounting when a component
// subscribes, so unsubscribing has to work before the client exists — hence the flag rather
// than returning a Promise a `useEffect` cleanup could not await.
export function subscribeSyncHealth(
  onValue: (status: SyncHealthStatus | null) => void,
): () => void {
  return bridge.subscribe<SyncHealthStatus | null>(ProxyMethod.SyncHealthObserve, undefined, onValue)
}

// W6 — whether this file's bytes are already on this device. A file without them still opens;
// the bytes fetch from the content plane on demand. ⚠ Call per VISIBLE row only — never across a
// listing (the op's contract).
export async function hasLocalData(path: string): Promise<boolean> {
  const client = await apexProxy()
  return client.call<boolean>(ProxyMethod.HasLocalData, { path })
}

// W4 — files whose NAME contains `query`, case-insensitively, anywhere in the drive. An empty
// query resolves an empty array. The predicate runs proxy-side; there is no client-side
// re-filter to drift from it.
export async function searchFiles(query: string): Promise<readonly File[]> {
  const client = await apexProxy()
  return client.call<readonly File[]>(ProxyMethod.Search, { query })
}

// W3 — bytes still waiting for the content plane. `null` until the first row exists.
export async function contentTransfer(): Promise<ContentTransfer | null> {
  const client = await apexProxy()
  return client.call<ContentTransfer | null>(ProxyMethod.ContentTransfer)
}

// W3 — whether this build is too old to read what the realm now writes.
export async function schemaFence(): Promise<SchemaFence | null> {
  const client = await apexProxy()
  return client.call<SchemaFence | null>(ProxyMethod.SchemaFence)
}

// W3 — ask the scheduler to drain now. Resolves whether the KICK was signalled, not whether the
// drain succeeded; the drain runs on the next tick.
export async function syncNow(): Promise<boolean> {
  const client = await apexProxy()
  return client.call<boolean>(ProxyMethod.SyncNow)
}

// MB-1 — the link-only contact-invitation handshake (drive-web-mode-b-
// sharing.md §10). `createInvite` mints the one-session messaging link the
// inviter shares; `redeemInvite` runs redeem-on-open for a joiner; the
// inviter watches `listInvites` for a fresh REDEEMED row then
// `completeHandshake`s. `connectedContacts` is the read MB-2's grant picker
// consumes.
export async function createContactInvite(
  intentKind = '',
  intentLabel = '',
): Promise<string | null> {
  const client = await apexProxy()
  return client.call<string | null>(ProxyMethod.ContactCreateInvite, {
    intentKind,
    intentLabel,
  })
}

export async function revokeContactInvite(inviteId: string): Promise<boolean> {
  const client = await apexProxy()
  return client.call<boolean>(ProxyMethod.ContactRevokeInvite, { inviteId })
}

export async function redeemContactInvite(
  link: string,
  selfAssertedName: string,
): Promise<RedeemResult> {
  const client = await apexProxy()
  return client.call<RedeemResult>(ProxyMethod.ContactRedeem, { link, selfAssertedName })
}

export async function awaitInviteAck(
  inviteId: string,
  sasConfirmed = false,
): Promise<InviteAck> {
  const client = await apexProxy()
  return client.call<InviteAck>(ProxyMethod.ContactAwaitAck, { inviteId, sasConfirmed })
}

export async function completeHandshake(args: {
  inviteId: string
  accept: boolean
  // The RAW claimed fingerprint the prompt displayed (contact-service v2, threat model T-CI-10):
  // the proxy stores the response only if it is this key. Required — '' when nothing was shown.
  shownFingerprint: string
  personId?: string
  newPersonName?: string
  selfAssertedName?: string
  sasConfirmed?: boolean
}): Promise<CompleteResult> {
  const client = await apexProxy()
  return client.call<CompleteResult>(ProxyMethod.ContactComplete, {
    inviteId: args.inviteId,
    accept: args.accept,
    shownFingerprint: args.shownFingerprint,
    personId: args.personId ?? '',
    newPersonName: args.newPersonName ?? '',
    selfAssertedName: args.selfAssertedName ?? '',
    sasConfirmed: args.sasConfirmed ?? false,
  })
}

export async function listContactInvites(): Promise<readonly ContactInviteItem[]> {
  const client = await apexProxy()
  return client.call<readonly ContactInviteItem[]>(ProxyMethod.ContactListInvites)
}

export async function connectedContacts(): Promise<readonly ContactItem[]> {
  const client = await apexProxy()
  return client.call<readonly ContactItem[]>(ProxyMethod.ContactConnected)
}

export async function pendingRedemptions(): Promise<readonly PendingRedemptionItem[]> {
  const client = await apexProxy()
  return client.call<readonly PendingRedemptionItem[]>(ProxyMethod.ContactPending)
}

// MB-2 — cross-user shared-realm membership grant (GR-1..GR-7). The inbound
// trio (`pollPendingGrants` / `acceptGrant` / `declineGrant`) + `realmMembers`
// back the Drive "Shared with me" + Manage-access surfaces; `grantMembership` /
// `removeMember` target a realm a folder-share (MB-4) creates.
export async function grantMembership(
  realmId: string,
  personId: string,
  label = '',
): Promise<boolean> {
  const client = await apexProxy()
  return client.call<boolean>(ProxyMethod.MembershipGrant, { realmId, personId, label })
}

export async function acceptGrant(grantorFingerprint: string): Promise<string | null> {
  const client = await apexProxy()
  return client.call<string | null>(ProxyMethod.MembershipAccept, { grantorFingerprint })
}

export async function realmMembers(realmId: string): Promise<readonly MemberItem[]> {
  const client = await apexProxy()
  return client.call<readonly MemberItem[]>(ProxyMethod.MembershipMembers, { realmId })
}

export async function removeMember(realmId: string, memberId: string): Promise<boolean> {
  const client = await apexProxy()
  return client.call<boolean>(ProxyMethod.MembershipRemove, { realmId, memberId })
}

export async function pollPendingGrants(): Promise<readonly PendingGrantItem[]> {
  const client = await apexProxy()
  return client.call<readonly PendingGrantItem[]>(ProxyMethod.MembershipPollPending)
}

export async function declineGrant(grantorFingerprint: string): Promise<boolean> {
  const client = await apexProxy()
  return client.call<boolean>(ProxyMethod.MembershipDecline, { grantorFingerprint })
}

// MB-3 — Mode-B file read (drive-web-mode-b-sharing.md §7, membership-driven).
// `sharedWithMe` lists the realms you're a member of; `browseSharedFolder`
// walks a realm tree (`''` = root); `readSharedFile` fetches a file's bytes
// lazily on open (null on error / not-yet-available).
export async function sharedWithMe(): Promise<readonly InboundRealmItem[]> {
  const client = await apexProxy()
  return client.call<readonly InboundRealmItem[]>(ProxyMethod.SharedWithMe)
}

export async function browseSharedFolder(
  realmId: string,
  parentFileId = '',
): Promise<readonly SharedEntryItem[]> {
  const client = await apexProxy()
  return client.call<readonly SharedEntryItem[]>(ProxyMethod.SharedBrowse, {
    realmId,
    parentFileId,
  })
}

export async function readSharedFile(realmId: string, fileId: string): Promise<FileBytes> {
  const client = await apexProxy()
  return client.call<FileBytes>(ProxyMethod.SharedReadFile, { realmId, fileId })
}

// MB-4 outbound — share a folder out (creates + populates + self-mounts a realm); list the
// folders this owner shares out.
//
// ⚠⚠ `error.kind` names which step failed — 'realm' | 'share' | 'mount' — because the steps are
// not undone. A failure after the first leaves a shared realm on the relay, and the old bare
// `null` made that partial state invisible to the user AND to us.
export async function shareFolder(
  folderPath: string,
): Promise<{ ok: true; item: SharedFolderItem } | ProxyFailure> {
  const client = await apexProxy()
  return client.call<{ ok: true; item: SharedFolderItem } | ProxyFailure>(
    ProxyMethod.ShareFolder,
    { folderPath },
  )
}

export async function sharedFolders(): Promise<readonly SharedFolderItem[]> {
  const client = await apexProxy()
  return client.call<readonly SharedFolderItem[]>(ProxyMethod.SharedFolders)
}

export const APEX = bridge.apexOrigin
