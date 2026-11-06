import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { SharedFolderItem } from '@tenshu/sdk/sharedFiles'
import { sharedFolders } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { ManageAccessView } from './ManageAccessView'
import { SharesView } from './SharesView'

// The "Shared" tab — everything you share *out*. Mode-B folder shares (MB-4)
// over `sharedFolders`, each with Manage access (grant/remove members), above
// the Mode-A link shares (`SharesView`). Selecting a folder's Manage takes over
// the tab with `ManageAccessView`.
export function SharedFoldersView() {
  const [managing, setManaging] = useState<SharedFolderItem | null>(null)

  const { data, isPending, isError, error } = useQuery({
    queryKey: queryKeys.sharedFolders(),
    queryFn: () => sharedFolders(),
  })
  const folders = data ?? []

  if (managing) {
    return (
      <ManageAccessView
        realmId={managing.sharedRealmId}
        folderName={managing.name}
        onBack={() => setManaging(null)}
      />
    )
  }

  return (
    <div className="space-y-6">
      <section>
        <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-text-3">
          Shared folders
        </h2>
        <div className="overflow-hidden rounded-xl border border-line">
          {isPending ? (
            <Message>Loading…</Message>
          ) : isError ? (
            <Message tone="error">{String(error)}</Message>
          ) : folders.length === 0 ? (
            <Message>
              You haven&rsquo;t shared any folders. Use a folder&rsquo;s Share action in Files.
            </Message>
          ) : (
            <ul className="divide-y divide-line">
              {folders.map((folder) => (
                <li
                  key={folder.sharedRealmId}
                  className="flex items-center gap-3 px-4 py-3"
                >
                  <span aria-hidden className="text-text-3">
                    📁
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm text-text">{folder.name}</p>
                    <p className="truncate text-xs text-text-3">{folder.parentPath}</p>
                  </div>
                  <button
                    type="button"
                    onClick={() => setManaging(folder)}
                    className="shrink-0 rounded-lg px-2.5 py-1 text-sm font-medium text-text hover:bg-surface-3"
                  >
                    Manage access
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </section>

      <section>
        <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-text-3">
          Shared links
        </h2>
        <SharesView />
      </section>
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
