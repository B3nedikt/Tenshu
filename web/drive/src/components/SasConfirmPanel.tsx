import { displayFingerprint, formatSas } from '../lib/contacts'

// The §5.3 SAS compare panel (drive-web-mode-b-sharing.md §5, MB-5). Both sides
// show the *same* 6-digit code (HKDF over the two sorted A13 pubkeys, pinned
// cross-platform) + the peer fingerprint; the humans compare out-of-band (in
// person / over a call) and each confirms. The contact is born VERIFIED only
// when both confirm. The SDK derives the code — this only renders + relays the
// yes/no.
export function SasConfirmPanel({
  sas,
  peerFingerprint,
  peerName,
  busy = false,
  onConfirm,
  onReject,
}: {
  sas: string
  peerFingerprint: string
  peerName?: string
  busy?: boolean
  onConfirm: () => void
  onReject: () => void
}) {
  return (
    <div className="space-y-4">
      <p className="text-sm text-text-2">
        Check that {peerName ? <strong>{peerName}</strong> : 'the other person'} sees the same
        code. Only confirm if they match.
      </p>

      <div className="rounded-xl border border-line bg-surface px-4 py-5 text-center">
        <p className="font-mono text-3xl tracking-[0.3em] text-text">
          {formatSas(sas)}
        </p>
        {peerFingerprint && (
          <p className="mt-2 font-mono text-xs text-text-3">
            {displayFingerprint(peerFingerprint)}
          </p>
        )}
      </div>

      <div className="flex justify-between gap-2">
        <button
          type="button"
          disabled={busy}
          onClick={onReject}
          className="rounded-lg px-3 py-1.5 text-sm font-medium text-danger hover:bg-danger/10 disabled:opacity-50"
        >
          They don&rsquo;t match
        </button>
        <button
          type="button"
          disabled={busy || !sas}
          onClick={onConfirm}
          className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-on-primary hover:bg-primary/90 disabled:opacity-50"
        >
          {busy ? 'Confirming…' : 'They match'}
        </button>
      </div>
    </div>
  )
}
