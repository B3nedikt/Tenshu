// Files entry point: file-system types + the FileSystem operation
// surface. Backed by `:file-service` on the Kotlin side.

import * as bundle from './_bundle'

// What the byte-returning reads (`getData`, `getPreview`) answer.
//
// ⚠⚠ Three answers, not two. `bytes: null` under `ok: true` is a legitimate absence — no such
// file, content not fetched on this device, or nothing previewable — which is an *answer*.
// `ok: false` is a failed read. The old `Int8Array | null` meant both, so every caller assumed
// absence and failures went unseen for phases at a time (`tech-debt.md`, the `T?` rule).
export type FileBytes =
  | { readonly ok: true; readonly bytes: Int8Array | null }
  | { readonly ok: false; readonly error: { readonly kind: string; readonly message?: string } }

// X-5: what `FileSystem.getLargePreview` answers (`drive-xxlarge-thumbnail-rung.md` §4.8).
//
// ⚠⚠ Bytes alone cannot drive a lightbox. It must choose between drawing what it got and
// downloading the whole original, and that needs *which class answered* and *whether anything
// better exists*. `coveringRung` is tri-state for the reason `FileBytes` is result-shaped: read
// "we could not tell" as "nothing is there" and a network blip fetches 6-18 MB on every open.
export type LargePreview =
  | {
      readonly ok: true
      readonly bytes: Int8Array | null
      readonly thumbnailClass: ThumbnailClass | null
      readonly coveringRung: CoveringRung
    }
  | { readonly ok: false; readonly error: { readonly kind: string; readonly message?: string } }

export type ThumbnailClass = 'xxlarge' | 'xlarge' | 'large' | 'list'
export type CoveringRung = 'present' | 'absent' | 'unknown'

export interface File {
  readonly path: string
  readonly size: number
  readonly lastModified: number
  readonly createdAt: number
  // W11: was `files?: File[]`, relied on as `'files' in entry`. That worked only
  // because the schema walker emitted the FILES field for Folder Composites and
  // omitted it for plain Files — incidental behaviour of a mechanism these rows
  // no longer go through (drive-web-parity.md §1.2e/§1.2f).
  readonly isFolder: boolean
  // Child count for a folder, 0 for a file. Was derived from `files.length`, which
  // the walker always emitted as an empty array — so every folder read "0 items".
  readonly itemCount: number
}

// ⚠ The `Folders.demo()` dev surface ONLY — a nested literal, not what
// `FileSystem.list` returns. Real listing rows are flat `File` values with
// `isFolder` / `itemCount`; nothing in the app tree nests children.
export interface Folder {
  readonly path: string
  readonly size: number
  readonly lastModified: number
  readonly files: ReadonlyArray<{
    readonly path: string
    readonly size: number
    readonly lastModified: number
  }>
}

// `FileSystem.details(path)` result — a File plus `createdAt`. Raw values
// (bytes, epoch-millis); the UI formats. `null` when the path doesn't exist.
export type FileDetails = File

// One `FileSystem.activity(path)` row — a single modification, newest-first.
// `detail` carries the rename ("old → new") / move ("to /newParent") text;
// `deviceName` is the resolved authoring device (null when unknown locally).
export interface FileActivity {
  readonly kind: 'Created' | 'Modified' | 'Renamed' | 'Moved'
  readonly detail: string | null
  readonly timestampMillis: number
  readonly deviceId: string
  readonly deviceName: string | null
}

// What `FileSystem.inventory()` answers — Control → Data → Files (`control-ia.md` §5.1).
//
// ⚠⚠ Three answers again: an inventory (possibly all zeros — an empty drive), or a failure with a
// kind. The listing reads fold a failed read into `[]`; an inventory doing that would tell the user
// they own nothing. `'read'` is the one kind the Kotlin side emits today (`INVENTORY_READ_FAILED`);
// the type stays `string` so a new kind arrives as a generic failure rather than a type error.
export interface FileInventory {
  readonly fileCount: number
  readonly folderCount: number
  readonly totalBytes: number
  // The newest file's "Modified" date, epoch-millis; 0 when there are no files.
  readonly newestModifiedMillis: number
  // Largest first. `extension` is lower-cased, '' for a file without one.
  readonly byExtension: ReadonlyArray<ExtensionTotal>
}

export interface ExtensionTotal {
  readonly extension: string
  readonly count: number
  readonly bytes: number
}

export type FileInventoryResult =
  | { readonly ok: true; readonly inventory: FileInventory }
  | { readonly ok: false; readonly error: { readonly kind: string; readonly message?: string } }

export const INVENTORY_READ_FAILED = 'read'

export function isFolder(entry: File): boolean {
  return entry.isFolder
}

export const Files = bundle.Files.getInstance()
export const Folders = bundle.Folders.getInstance()

// Operations: Promise-returning callable surface. Suspend lives on the
// Kotlin side; JS sees a vanilla `Promise<T>` that's safe to use as a
// TanStack Query `queryFn`.
export const FileSystem = bundle.FileSystem.getInstance()
