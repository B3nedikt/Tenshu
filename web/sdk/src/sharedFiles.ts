// SharedFiles entry point: the Mode-B file read path
// (`docs/proposals/drive-web-mode-b-sharing.md` MB-3). Backed by `SharedFiles`
// on the Kotlin side (`:tenshu-sdk-js`) — the same IdentityService /
// FileSystem read ops the native apps run, dispatched through the apex runtime.

import * as bundle from './_bundle'

// A realm shared *with* this member (`sharedWithMe()`). `name` is the wrapper-
// root folder's name, or null until it syncs in (show a fallback label).
export interface InboundRealmItem {
  readonly sharedRealmId: string
  readonly name: string | null
}

// An entry in a shared folder listing (`browseSharedFolder(...)`).
export interface SharedEntryItem {
  readonly fileId: string
  readonly name: string
  readonly isFolder: boolean
}

// A folder this owner shares *out* (`shareFolder(...)` result / `sharedFolders()`).
// `sharedRealmId` is the `Membership.grant` target + the browse key.
export interface SharedFolderItem {
  readonly sharedRealmId: string
  readonly name: string
  readonly parentPath: string
}

// Operation surface: Promise-returning, safe as a TanStack Query `queryFn`.
// `readSharedFile` fetches the decrypted bytes lazily on open and resolves
// result-shaped: `{ ok: true, bytes }` — `bytes` null when there is nothing to
// hand back yet — or `{ ok: false, error }` when the read failed.
export const SharedFiles = bundle.SharedFiles.getInstance()
