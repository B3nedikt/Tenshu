import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { APEX, createShareLink, storageStatus } from '../lib/proxy'
import { basename } from '../lib/paths'
import { shareUrl } from '../lib/share'
import { evaluateStorageGate } from '../lib/storageGate'
import { queryKeys } from '../lib/queryKeys'
import { Dialog } from './Dialog'

// Mints a Mode-A link for a file and shows the copy-able URL. The link is
// created once when the dialog opens (cached by path, so re-opening doesn't
// mint duplicates); the key fragment is in the URL hash and never hits the wire.
//
// Tier-1 gated (storage-capacity-gating.md §2): sharing needs the relay, so a
// realm with no storage plan — or one out of room — can't mint a link. We check
// the cached storage status first and show a hand-off to Tenshu instead of
// firing a mint the proxy's Tier-2 `ensureCapacity` would reject anyway.
export function ShareDialog({ path, onClose }: { path: string; onClose: () => void }) {
  const { data: status, isPending: statusPending } = useQuery({
    queryKey: queryKeys.storageStatus(),
    queryFn: () => storageStatus(),
  })
  const gate = evaluateStorageGate(status ?? null)

  const { data, isPending, isError } = useQuery({
    queryKey: queryKeys.shareMint(path),
    queryFn: () => createShareLink(path),
    // Only mint once the gate allows it — never before the status settles, and
    // never when blocked. Unknown status resolves to 'allow' (Tier-2 backstops).
    enabled: !statusPending && gate === 'allow',
    staleTime: Infinity,
  })
  const [copied, setCopied] = useState(false)

  const url = data ? shareUrl(data) : null

  async function copy() {
    if (!url) return
    try {
      await navigator.clipboard.writeText(url)
      setCopied(true)
    } catch {
      // Clipboard blocked (no permission / insecure context) — the field is
      // selectable, so the user can copy manually.
    }
  }

  return (
    <Dialog title={`Share “${basename(path)}”`} onClose={onClose}>
      {statusPending ? (
        <p className="text-sm text-text-3">Checking storage…</p>
      ) : gate !== 'allow' ? (
        <StorageGateBlock full={gate === 'full'} onClose={onClose} />
      ) : isPending ? (
        <p className="text-sm text-text-3">Creating link…</p>
      ) : isError || !url ? (
        <p className="text-sm text-danger">Couldn't create a share link.</p>
      ) : (
        <div className="space-y-3">
          <p className="text-sm text-text-2">Anyone with this link can view the file.</p>
          <div className="flex gap-2">
            <input
              readOnly
              aria-label="Share link"
              value={url}
              onFocus={(e) => e.target.select()}
              className="min-w-0 flex-1 rounded-lg border border-line px-3 py-1.5 font-mono text-xs text-text"
            />
            <button
              type="button"
              onClick={copy}
              className="shrink-0 rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-on-primary hover:bg-primary/90"
            >
              {copied ? 'Copied' : 'Copy'}
            </button>
          </div>
          <div className="flex justify-end">
            <button
              type="button"
              onClick={onClose}
              className="rounded-lg px-3 py-1.5 text-sm font-medium text-text-2 hover:bg-surface-3"
            >
              Done
            </button>
          </div>
        </div>
      )}
    </Dialog>
  )
}

// Shown when the Tier-1 gate blocks sharing: no storage plan, or out of room.
// Sharing can't proceed without the relay, so we route the user to Tenshu to
// manage storage rather than minting a link the relay would reject.
function StorageGateBlock({ full, onClose }: { full: boolean; onClose: () => void }) {
  return (
    <div className="space-y-3">
      <p className="text-sm text-text-2">
        {full
          ? "You're out of storage, so new share links can't be created. Free up space or upgrade your plan in Tenshu."
          : "This realm has no storage plan, so share links can't be created. Set up storage in Tenshu to share files."}
      </p>
      <div className="flex justify-end gap-2">
        <button
          type="button"
          onClick={onClose}
          className="rounded-lg px-3 py-1.5 text-sm font-medium text-text-2 hover:bg-surface-3"
        >
          Close
        </button>
        <a
          href={APEX}
          target="_blank"
          rel="noreferrer"
          className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-on-primary hover:bg-primary/90"
        >
          Open Tenshu
        </a>
      </div>
    </div>
  )
}
