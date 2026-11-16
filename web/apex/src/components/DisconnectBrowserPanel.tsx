// The confirm → disconnecting → outcome sequence for "Disconnect this browser", shared by the lock
// screen (`LockedFlow`) and the signed-in device menu (`DeviceMenu`). Each host supplies its own
// frame (an onboarding card, a modal) and title element; the behaviour lives here once.
//
// ⚠⚠ Why each part exists:
//  - **The acknowledgement checkbox.** The disconnect destroys this browser's copy of the realm.
//    For a realm made here ("Just try it locally", or the first device) that may be the only copy,
//    and from the lock screen the phrase cannot be shown first. One accidental click must not be
//    enough.
//  - **The busy state.** The removal waits up to 8 s for the relay. With no feedback, people reload —
//    and a reload used to leave the device key and the data behind a UI that read signed out
//    (reproduced 2026-11-16). The SDK now finishes an interrupted purge on the next load, but the
//    honest answer is still to say it is working.
//  - **The unconfirmed outcome.** The wipe always happens; the removal from the realm is best-effort.
//    When it was not confirmed the user is told before the reload, because afterwards nothing can.
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import type { SessionApi } from '@tenshu/sdk/onboarding'
import { disconnectBrowser } from '../lib/disconnectBrowser'
import { reloadAfterPurge } from '../lib/reloadAfterPurge'

type PanelState =
  | { readonly kind: 'confirming' }
  | { readonly kind: 'disconnecting' }
  | { readonly kind: 'unconfirmed' }
  | { readonly kind: 'failed' }

export function DisconnectBrowserPanel({
  deviceName,
  session,
  onCancel,
  renderTitle,
  onBusyChange,
}: {
  readonly deviceName: string
  readonly session: Pick<SessionApi, 'clear'>
  readonly onCancel: () => void
  readonly renderTitle: (text: string) => ReactNode
  /** Lets a modal host refuse backdrop / Escape dismissal while the purge runs or once it ran. */
  readonly onBusyChange?: (busy: boolean) => void
}) {
  const { t } = useTranslation('control')
  const [state, setState] = useState<PanelState>({ kind: 'confirming' })
  const [acknowledged, setAcknowledged] = useState(false)

  function move(next: PanelState) {
    setState(next)
    onBusyChange?.(next.kind !== 'confirming')
  }

  async function confirm() {
    move({ kind: 'disconnecting' })
    try {
      const outcome = await disconnectBrowser(session)
      if (outcome === 'confirmed') {
        reloadAfterPurge('/')
        return
      }
      move({ kind: 'unconfirmed' })
    } catch {
      move({ kind: 'failed' })
    }
  }

  if (state.kind === 'unconfirmed') {
    return (
      <>
        {renderTitle(t('disconnectBrowser.unconfirmed.title'))}
        <div className="mt-3 space-y-3 text-sm text-text-2">
          <p>{t('disconnectBrowser.unconfirmed.body')}</p>
        </div>
        <div className="mt-6 flex flex-wrap justify-end gap-3">
          <PlainPrimaryButton onClick={() => reloadAfterPurge('/')}>
            {t('disconnectBrowser.unconfirmed.continue')}
          </PlainPrimaryButton>
        </div>
      </>
    )
  }

  if (state.kind === 'failed') {
    return (
      <>
        {renderTitle(t('disconnectBrowser.failed.title'))}
        <div className="mt-3 space-y-3 text-sm text-text-2">
          <p>{t('disconnectBrowser.failed.body')}</p>
        </div>
        <div className="mt-6 flex flex-wrap justify-end gap-3">
          <DangerButton onClick={() => void confirm()}>{t('disconnectBrowser.failed.retry')}</DangerButton>
        </div>
      </>
    )
  }

  const busy = state.kind === 'disconnecting'
  return (
    <>
      {renderTitle(t('disconnectBrowser.title'))}
      <div className="mt-3 space-y-3 text-sm text-text-2">
        <p>{t('disconnectBrowser.body1', { deviceName })}</p>
        <p>{t('disconnectBrowser.body2')}</p>
        <p>{t('disconnectBrowser.body3')}</p>
      </div>
      <label className="mt-5 flex items-start gap-3 rounded-md border border-line p-3 text-sm text-text">
        <input
          type="checkbox"
          checked={acknowledged}
          disabled={busy}
          onChange={e => setAcknowledged(e.target.checked)}
          className="mt-0.5 h-4 w-4 shrink-0 accent-danger"
        />
        <span>{t('disconnectBrowser.ack')}</span>
      </label>
      {busy && (
        <p role="status" className="mt-4 text-sm text-text-2">
          {t('disconnectBrowser.inFlightNote')}
        </p>
      )}
      <div className="mt-6 flex flex-wrap justify-end gap-3">
        <button
          type="button"
          disabled={busy}
          onClick={onCancel}
          className="rounded-md border border-line bg-surface px-4 py-2 text-sm font-medium text-text-2 hover:bg-surface-2 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {t('disconnectBrowser.cancel')}
        </button>
        <DangerButton disabled={!acknowledged || busy} onClick={() => void confirm()}>
          {busy ? t('disconnectBrowser.inFlight') : t('disconnectBrowser.confirm')}
        </DangerButton>
      </div>
    </>
  )
}

function DangerButton({
  children,
  onClick,
  disabled,
}: {
  readonly children: ReactNode
  readonly onClick: () => void
  readonly disabled?: boolean
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className="rounded-md bg-danger px-4 py-2 text-sm font-medium text-white hover:bg-danger/90 disabled:cursor-not-allowed disabled:opacity-60"
    >
      {children}
    </button>
  )
}

function PlainPrimaryButton({ children, onClick }: { readonly children: ReactNode; readonly onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary hover:bg-primary/90"
    >
      {children}
    </button>
  )
}
