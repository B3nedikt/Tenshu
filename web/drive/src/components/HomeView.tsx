import { useQuery } from '@tanstack/react-query'
import { recentFiles } from '../lib/proxy'
import { basename, formatBytes, formatDate, isFolder } from '../lib/paths'
import { queryKeys } from '../lib/queryKeys'
import { FileThumbnail } from './FileThumbnail'
import { useFileOpener } from '../lib/useFileOpener'

// Home — the most-recently-modified files across the whole drive. Tapping one
// opens it straight into the preview (image / pdf / text) or downloads it, the
// same primary action as the Files view. Folders are filtered out: "recent
// files" is the documents-you-touched surface.
export function HomeView() {
  const opener = useFileOpener()
  const { data, isPending, isError, error } = useQuery({
    queryKey: queryKeys.recent(),
    queryFn: () => recentFiles(20),
  })

  const files = (data ?? []).filter((entry) => !isFolder(entry))

  return (
    <div>
      <h2 className="mb-3 text-xs font-semibold uppercase tracking-wide text-text-3">
        Recent files
      </h2>
      <div className="overflow-hidden rounded-xl border border-line">
        {isPending ? (
          <Message>Loading…</Message>
        ) : isError ? (
          <Message tone="error">{String(error)}</Message>
        ) : files.length === 0 ? (
          <Message>Nothing here yet — upload a file to get started.</Message>
        ) : (
          <ul className="divide-y divide-line">
            {files.map((file) => (
              <li key={file.path}>
                <button
                  type="button"
                  onClick={() => opener.open(file.path)}
                  className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-surface"
                >
                  <FileThumbnail path={file.path} name={basename(file.path)} />
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm text-text">{basename(file.path)}</p>
                    <p className="truncate text-xs text-text-3">{file.path}</p>
                  </div>
                  <span className="shrink-0 text-xs text-text-3">
                    {opener.opening === file.path
                      ? 'Opening…'
                      : [formatBytes(file.size), formatDate(file.lastModified)]
                          .filter(Boolean)
                          .join(' · ')}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {opener.overlay}
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
    <p className={`px-4 py-8 text-center text-sm ${tone === 'error' ? 'text-danger' : 'text-text-3'}`}>
      {children}
    </p>
  )
}

