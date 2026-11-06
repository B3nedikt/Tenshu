// Minimal modal dialog shell for the Recovery section's two flows
// (Test recovery, Re-display phrase). Renders a centred card over a
// dimming backdrop; closes on Esc and on backdrop click. Phase B keeps
// this local rather than promoting it to a shared apex component — once
// a second section needs a dialog we'll lift it.
import { useEffect, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

export function RecoveryDialog({
  title,
  onClose,
  children,
}: {
  readonly title: string
  readonly onClose: () => void
  readonly children: ReactNode
}) {
  const { t } = useTranslation('control')
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div
      className="fixed inset-0 z-30 flex items-center justify-center bg-black/40 px-4 py-8"
      onClick={onClose}
      role="presentation"
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={e => e.stopPropagation()}
        className="w-full max-w-lg rounded-xl border border-line bg-surface p-6 shadow-xl"
      >
        <div className="flex items-start justify-between gap-4">
          <h2 className="text-lg font-semibold tracking-tight">{title}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('recovery.dialog.closeAriaLabel')}
            className="-mt-1 -mr-1 rounded-md p-1 text-text-2 hover:bg-surface-2 hover:text-text"
          >
            <svg aria-hidden viewBox="0 0 20 20" width={18} height={18} fill="none" stroke="currentColor" strokeWidth={1.6}>
              <path d="M5 5l10 10M15 5L5 15" />
            </svg>
          </button>
        </div>
        <div className="mt-4">{children}</div>
      </div>
    </div>
  )
}
