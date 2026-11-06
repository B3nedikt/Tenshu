// `SharedFiles` — the Mode-B shared-folder read path.
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).

// SharedFiles: the Mode-B file read path (drive-web-mode-b-sharing.md MB-3).
// Backed by IdentityService.getSharedRealmMemberships + FileSystem.listSharedFolder
// / readSharedFile, dispatched through the apex in-process runtime.
interface NativeInboundRealmItem {
  readonly sharedRealmId: string
  // The wrapper-root folder's name, or null until it syncs in.
  readonly name: string | null
}

interface NativeSharedEntryItem {
  readonly fileId: string
  readonly name: string
  readonly isFolder: boolean
}

interface NativeSharedFolderItem {
  readonly sharedRealmId: string
  readonly name: string
  readonly parentPath: string
}

interface SharedFilesInstance {
  sharedWithMe(): Promise<NativeInboundRealmItem[]>
  browseSharedFolder(realmId: string, parentFileId: string): Promise<NativeSharedEntryItem[]>
  // Bytes fetched lazily on open (no proactive push).
  // ⚠ `bytes` null = nothing to hand back; `ok: false` = the read failed. On a shared realm the
  // other side's permissions are the likeliest cause, so the reason matters here most of all.
  readSharedFile(realmId: string, fileId: string): Promise<NativeFileBytes>
  // MB-4 outbound: share a folder out (creates + populates + self-mounts a realm), resolving
  // `{ ok: true, item }` or `{ ok: false, error }`; `sharedFolders` lists what this owner
  // shares out.
  // ⚠⚠ `error.kind` names WHICH STEP failed — 'realm' | 'share' | 'mount'. This is a multi-step
  // write whose steps are not undone, so a failure after the first leaves a shared realm on the
  // relay; naming the step is what stops that partial state being invisible.
  shareFolder(folderPath: string): Promise<{ readonly ok: true; readonly item: NativeSharedFolderItem } | NativeFailure>
  sharedFolders(): Promise<NativeSharedFolderItem[]>
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const SharedFiles: { getInstance(): SharedFilesInstance }
}
