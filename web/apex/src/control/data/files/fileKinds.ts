// Groups `FileSystem.inventory()`'s per-extension totals into the kinds Data → Files shows.
// Presentation only: the SDK reports extensions, never names a kind (core-realm-data.md's rule —
// presentation lives in the consumer, not on the data).
//
// ⚠ By extension, so it is a guess about content, not a fact: a `.bin` that is really a photo
// counts as Other. Good enough for an inventory; never use it to decide what a file IS.
import type { ExtensionTotal } from '@tenshu/sdk/files'

export const FILE_KINDS = ['image', 'video', 'audio', 'document', 'archive', 'other'] as const
export type FileKind = (typeof FILE_KINDS)[number]

const EXTENSIONS: Readonly<Record<Exclude<FileKind, 'other'>, readonly string[]>> = {
  image: ['jpg', 'jpeg', 'png', 'gif', 'webp', 'heic', 'heif', 'avif', 'bmp', 'tif', 'tiff', 'svg', 'dng', 'raw', 'cr2', 'cr3', 'nef', 'arw'],
  video: ['mp4', 'mov', 'm4v', 'webm', 'mkv', 'avi', '3gp'],
  audio: ['mp3', 'm4a', 'aac', 'wav', 'flac', 'ogg', 'oga', 'opus'],
  document: ['pdf', 'txt', 'md', 'rtf', 'doc', 'docx', 'odt', 'xls', 'xlsx', 'ods', 'csv', 'ppt', 'pptx', 'odp', 'pages', 'numbers', 'key', 'epub'],
  archive: ['zip', 'tar', 'gz', 'tgz', 'bz2', 'xz', '7z', 'rar'],
}

const KIND_OF = new Map<string, FileKind>(
  Object.entries(EXTENSIONS).flatMap(([kind, exts]) => exts.map(ext => [ext, kind as FileKind])),
)

export function kindOfExtension(extension: string): FileKind {
  return KIND_OF.get(extension.toLowerCase()) ?? 'other'
}

export interface KindTotal {
  readonly kind: FileKind
  readonly count: number
  readonly bytes: number
}

/** Folds extension totals into kind totals: empty kinds dropped, largest first, ties in FILE_KINDS order. */
export function totalsByKind(byExtension: readonly ExtensionTotal[]): readonly KindTotal[] {
  const sums = new Map<FileKind, { count: number; bytes: number }>()
  for (const { extension, count, bytes } of byExtension) {
    const kind = kindOfExtension(extension)
    const sum = sums.get(kind) ?? { count: 0, bytes: 0 }
    sum.count += count
    sum.bytes += bytes
    sums.set(kind, sum)
  }
  return FILE_KINDS.flatMap(kind => {
    const sum = sums.get(kind)
    return sum && sum.count > 0 ? [{ kind, ...sum }] : []
  }).sort((a, b) => b.bytes - a.bytes || FILE_KINDS.indexOf(a.kind) - FILE_KINDS.indexOf(b.kind))
}
