import type { File as FsEntry } from '@tenshu/sdk/files'

// Pure path + formatting helpers for the Drive browser. Kept framework-free
// so they're trivially unit-testable. Paths are the Tenshu FS convention:
// absolute, '/'-separated, '/' is the root.

// Folder/file discriminator — the schema walker emits `files` for Folder
// composites and omits it for plain Files. Defined locally (not imported from
// `@tenshu/sdk/files`) on purpose: that module's value exports pull the whole
// Kotlin/JS runtime, which must NOT ship in the thin subdomain bundle — the
// runtime lives in the apex, reached over the postMessage bridge. Type-only
// imports (like `FsEntry` above) are erased and safe.
export function isFolder(entry: FsEntry): boolean {
  return entry.isFolder
}

// The last segment of a path — the display name. Root renders as "Drive".
export function basename(path: string): string {
  const trimmed = path.replace(/\/+$/, '')
  if (trimmed === '') return 'Drive'
  return trimmed.slice(trimmed.lastIndexOf('/') + 1)
}

// Join a folder path and a child name into an absolute path. Root ("/") stays
// a single leading slash; deeper folders get one separator.
export function joinPath(parent: string, name: string): string {
  const base = parent.replace(/\/+$/, '')
  return base === '' ? `/${name}` : `${base}/${name}`
}

// The folder that contains `path` — "/" for root-level entries. Used to skip
// no-op moves (moving an item into the folder it already lives in).
export function parentDir(path: string): string {
  const trimmed = path.replace(/\/+$/, '')
  const idx = trimmed.lastIndexOf('/')
  return idx <= 0 ? '/' : trimmed.slice(0, idx)
}

// One breadcrumb per ancestor, root first, current folder last. Each carries
// the absolute `path` to navigate to on click.
export interface Crumb {
  readonly label: string
  readonly path: string
}

export function breadcrumbs(path: string): Crumb[] {
  const crumbs: Crumb[] = [{ label: 'Drive', path: '/' }]
  const segments = path.split('/').filter((s) => s.length > 0)
  let acc = ''
  for (const segment of segments) {
    acc += `/${segment}`
    crumbs.push({ label: segment, path: acc })
  }
  return crumbs
}

// Human-readable byte size — "0 B", "12 KB", "3.4 MB", binary (1024) units to
// match how the native Drive renders sizes.
const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'] as const

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), UNITS.length - 1)
  const value = bytes / 1024 ** exponent
  // No decimals for whole bytes; one decimal otherwise.
  const rounded = exponent === 0 ? value : Math.round(value * 10) / 10
  return `${rounded} ${UNITS[exponent]}`
}

// Localised short date for a millisecond epoch; empty for the 0 sentinel.
export function formatDate(epochMillis: number): string {
  if (!epochMillis) return ''
  return new Date(epochMillis).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

// Localised date + time for a millisecond epoch — the Activity timestamps;
// empty for the 0 sentinel.
export function formatDateTime(epochMillis: number): string {
  if (!epochMillis) return ''
  return new Date(epochMillis).toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  })
}
