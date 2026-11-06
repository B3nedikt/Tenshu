import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { createContactInvite } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { Dialog } from './Dialog'
import { QrImage } from './QrImage'

// Mints a contact-invite link and offers the link surfaces (drive-web-mode-b-
// sharing.md §3). The link is created once when the dialog opens (cached, so
// re-opening doesn't mint a duplicate invite). Its `K_link` rides the URL
// fragment and never reaches the relay — the link is a handshake *initiator*,
// not a bearer capability.
//
// Surfaces shipped in MB-1: **Copy** (always) + **`navigator.share`** (the OS
// share sheet, feature-detected — a progressive enhancement). QR-display is
// deferred (it needs a QR-encoder dependency; doc §3 marks it optional in v1).
export function InviteDialog({ onClose }: { onClose: () => void }) {
  const { data, isPending, isError } = useQuery({
    queryKey: queryKeys.contactInviteMint(),
    queryFn: () => createContactInvite('', ''),
    staleTime: Infinity,
  })
  const [copied, setCopied] = useState(false)
  const [showQr, setShowQr] = useState(false)

  const link = data ?? null
  const canShare = typeof navigator !== 'undefined' && typeof navigator.share === 'function'

  async function copy() {
    if (!link) return
    try {
      await navigator.clipboard.writeText(link)
      setCopied(true)
    } catch {
      // Clipboard blocked — the field is selectable, so copy manually.
    }
  }

  async function share() {
    if (!link) return
    try {
      await navigator.share({ url: link })
    } catch {
      // User dismissed the share sheet, or it's unavailable — Copy remains.
    }
  }

  return (
    <Dialog title="Invite someone" onClose={onClose}>
      {isPending ? (
        <p className="text-sm text-text-3">Creating invite…</p>
      ) : isError || !link ? (
        <p className="text-sm text-danger">Couldn&rsquo;t create an invite link.</p>
      ) : (
        <div className="space-y-3">
          <p className="text-sm text-text-2">
            Send this one-time link to the person you want to connect with. When they open it,
            you&rsquo;ll be asked to confirm them.
          </p>
          <div className="flex gap-2">
            <input
              readOnly
              aria-label="Invite link"
              value={link}
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
          {showQr && (
            <div className="flex flex-col items-center gap-2 pt-1">
              <QrImage value={link} />
              <p className="text-xs text-text-3">Have them scan this to verify in person.</p>
            </div>
          )}
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-1">
              {canShare && (
                <button
                  type="button"
                  onClick={share}
                  className="rounded-lg px-3 py-1.5 text-sm font-medium text-text hover:bg-surface-3"
                >
                  Share…
                </button>
              )}
              <button
                type="button"
                onClick={() => setShowQr((v) => !v)}
                className="rounded-lg px-3 py-1.5 text-sm font-medium text-text hover:bg-surface-3"
              >
                {showQr ? 'Hide QR' : 'Show QR'}
              </button>
            </div>
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
