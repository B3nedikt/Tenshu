import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { SharedEntryItem } from '@tenshu/sdk/sharedFiles'
import { browseSharedFolder, readSharedFile } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { buildPreview, previewKind, type Preview } from '../lib/preview'
import { FilePreview } from './FilePreview'

// Browse one shared realm's tree (drive-web-mode-b-sharing.md MB-3). Folders
// drill in (a breadcrumb stack of fileIds); files open lazily — their bytes are
// fetched on demand via `readSharedFile` (the OnDemand byte fetch; chunks AEAD'd
// under the realm keys, GR-7 retained-key trial-decrypt automatic). Images, PDFs
// and text render inline in a lightbox; anything else downloads. The decrypted
// bytes never leave the page — the preview reads from an in-memory object URL.
type Crumb = { fileId: string; name: string }

export function SharedFolderBrowser({
  realmId,
  realmName,
  onBack,
}: {
  realmId: string
  realmName: string
  onBack: () => void
}) {
  // The path from the realm root to the current folder. Empty = root
  // (SharedFile.ROOT_PARENT === '').
  const [stack, setStack] = useState<Crumb[]>([])
  const [opening, setOpening] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [preview, setPreview] = useState<Preview | null>(null)

  // The open preview owns its object URL; free it whenever it closes or changes
  // (and on unmount), so the decrypted bytes don't linger in the URL store.
  useEffect(() => {
    if (!preview) return
    return () => URL.revokeObjectURL(preview.url)
  }, [preview])

  const parentFileId = stack.length === 0 ? '' : (stack[stack.length - 1]?.fileId ?? '')

  const { data, isPending, isError } = useQuery({
    queryKey: queryKeys.sharedBrowse(realmId, parentFileId),
    queryFn: () => browseSharedFolder(realmId, parentFileId),
  })
  const entries = data ?? []

  async function openFile(entry: SharedEntryItem) {
    setOpening(entry.fileId)
    setError(null)
    try {
      const read = await readSharedFile(realmId, entry.fileId)
      // ⚠ On a shared realm these are genuinely different: a failed read is usually the OTHER
      // side's permissions, which "it may not have synced yet" actively misdirects away from.
      if (!read.ok) {
        setError(`Couldn't open "${entry.name}" (${read.error.kind}).`)
        return
      }
      const bytes = read.bytes
      if (!bytes) {
        setError(`"${entry.name}" hasn't synced to this device yet.`)
        return
      }
      // Previewable kinds open in the lightbox; binaries download (→ null).
      const built = buildPreview(entry.name, bytes)
      if (built) setPreview(built)
    } finally {
      setOpening(null)
    }
  }

  return (
    <div className="space-y-4">
      <nav className="flex flex-wrap items-center gap-1 text-sm">
        <button type="button" onClick={onBack} className="text-text-2 hover:text-text">
          Shared with me
        </button>
        <span className="text-text-3">/</span>
        <button
          type="button"
          onClick={() => setStack([])}
          className={stack.length === 0 ? 'text-text' : 'text-text-2 hover:text-text'}
        >
          {realmName}
        </button>
        {stack.map((crumb, i) => (
          <span key={crumb.fileId} className="flex items-center gap-1">
            <span className="text-text-3">/</span>
            <button
              type="button"
              onClick={() => setStack((s) => s.slice(0, i + 1))}
              className={
                i === stack.length - 1
                  ? 'text-text'
                  : 'text-text-2 hover:text-text'
              }
            >
              {crumb.name}
            </button>
          </span>
        ))}
      </nav>

      {error && <p className="text-sm text-danger">{error}</p>}

      <div className="overflow-hidden rounded-xl border border-line">
        {isPending ? (
          <Message>Loading…</Message>
        ) : isError ? (
          <Message tone="error">Couldn&rsquo;t load this folder.</Message>
        ) : entries.length === 0 ? (
          <Message>This folder is empty.</Message>
        ) : (
          <ul className="divide-y divide-line">
            {entries.map((entry) => (
              <li key={entry.fileId}>
                <button
                  type="button"
                  disabled={opening !== null}
                  onClick={() =>
                    entry.isFolder
                      ? setStack((s) => [...s, { fileId: entry.fileId, name: entry.name }])
                      : openFile(entry)
                  }
                  className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-surface disabled:opacity-60"
                >
                  <span aria-hidden className="text-text-3">
                    {entry.isFolder ? '📁' : '📄'}
                  </span>
                  <span className="min-w-0 flex-1 truncate text-sm text-text">
                    {entry.name}
                  </span>
                  {opening === entry.fileId ? (
                    <span className="shrink-0 text-xs text-text-3">Opening…</span>
                  ) : !entry.isFolder ? (
                    <span className="shrink-0 text-xs text-text-3">
                      {previewKind(entry.name).kind === 'none' ? 'Download' : 'Open'}
                    </span>
                  ) : null}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {preview && <FilePreview preview={preview} onClose={() => setPreview(null)} />}
    </div>
  )
}

function Message({
  children,
  tone = 'muted',
}: {
  children: React.ReactNode
  tone?: 'muted' | 'error'
}) {
  return (
    <p
      className={`px-4 py-8 text-center text-sm ${
        tone === 'error' ? 'text-danger' : 'text-text-3'
      }`}
    >
      {children}
    </p>
  )
}
