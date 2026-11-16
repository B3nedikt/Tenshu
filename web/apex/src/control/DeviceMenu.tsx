// Device-name pill + lock-now icon + dropdown menu (per
// `web-control-plane.md` §3.2). High-frequency security actions live in
// chrome rather than buried under Data → Devices.
//
// Lock now: drops the apex's session token and routes to /lock. The device
// name, passkey record and realm IDB stay, so the lock screen greets this
// browser by name and the next unlock works.
//
// Disconnect this browser: the same flow as the lock screen's
// (`components/DisconnectBrowserPanel.tsx`) — one implementation, one name. It
// removes this browser from the realm through the shared `ForgetThisDevice`
// op (which pushes the removal while the device key still exists, waiting a
// bounded 8 s), wipes local state, tells the passkey provider, and reloads. An
// unconfirmed removal (offline, relay refused) is shown before the reload.
//
// ⚠ This used to be "Sign out everywhere" — a name that promised more than it
// did (it only ever removed THIS browser) — and it removed the device twice:
// `Devices.revokeSelf()` first, then `Session.clear({purgeRealm})`, whose
// forget op revokes again and waits again.
import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useNavigate } from 'react-router'
import { useTranslation } from 'react-i18next'
import { Session } from '@tenshu/sdk/onboarding'
import { DisconnectBrowserPanel } from '../components/DisconnectBrowserPanel'

export function DeviceMenu({ deviceName }: { readonly deviceName: string }) {
  const { t } = useTranslation('control')
  const [open, setOpen] = useState(false)
  const [disconnecting, setDisconnecting] = useState(false)
  const navigate = useNavigate()
  const pillRef = useRef<HTMLButtonElement>(null)
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    function onPointerDown(e: PointerEvent) {
      const target = e.target as Node | null
      if (
        menuRef.current?.contains(target ?? null) ||
        pillRef.current?.contains(target ?? null)
      ) {
        return
      }
      setOpen(false)
    }
    function onKey(e: globalThis.KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault()
        setOpen(false)
        pillRef.current?.focus()
      }
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  async function lockNow() {
    setOpen(false)
    await Session.clear({})
    navigate('/lock', { replace: true })
  }

  return (
    <div className="flex items-center gap-1">
      <div className="relative">
        <button
          ref={pillRef}
          type="button"
          onClick={() => setOpen(o => !o)}
          aria-haspopup="menu"
          aria-expanded={open}
          className="flex h-8 max-w-[12rem] items-center gap-1.5 rounded-full border border-line px-3 text-sm text-text-2 hover:bg-well focus:outline-none focus-visible:ring-2 focus-visible:ring-line-strong max-sm:px-2.5"
        >
          <span aria-hidden className="h-2 w-2 rounded-full bg-success" />
          {/* On a phone the bar has no room for the name: it stays the button's accessible name. */}
          <span className="truncate font-mono text-xs max-sm:sr-only">{deviceName}</span>
        </button>
        {open && (
          <div
            ref={menuRef}
            role="menu"
            className="absolute right-0 top-full z-30 mt-2 w-72 rounded-md border border-line bg-surface p-3 text-sm shadow-lg"
          >
            <p className="px-2 pb-2 text-xs text-text-2">
              {t('deviceMenu.signedInOnPrefix')}
              <span className="font-mono text-text-2">{deviceName}</span>
              {t('deviceMenu.signedInOnSuffix')}
            </p>
            <div className="border-t border-line pt-2">
              <button
                type="button"
                role="menuitem"
                onClick={() => void lockNow()}
                className="block w-full rounded-md px-2 py-1.5 text-left text-sm text-text-2 hover:bg-surface-2"
              >
                {t('deviceMenu.lockNow')}
              </button>
              <button
                type="button"
                role="menuitem"
                onClick={() => {
                  setOpen(false)
                  setDisconnecting(true)
                }}
                className="block w-full rounded-md px-2 py-1.5 text-left text-sm text-danger hover:bg-danger/10"
              >
                {t('deviceMenu.disconnect')}
              </button>
            </div>
          </div>
        )}
      </div>
      <button
        type="button"
        onClick={() => void lockNow()}
        aria-label={t('deviceMenu.lockNowAriaLabel')}
        title={t('deviceMenu.lockNowAriaLabel')}
        className="rounded-md p-2 text-text-2 hover:bg-surface-2 focus:outline-none focus-visible:ring-2 focus-visible:ring-line-strong"
      >
        <LockIcon />
      </button>
      {disconnecting && (
        <DisconnectModal deviceName={deviceName} onClose={() => setDisconnecting(false)} />
      )}
    </div>
  )
}

function LockIcon() {
  return (
    <svg
      aria-hidden
      viewBox="0 0 20 20"
      width={18}
      height={18}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      className="block"
    >
      <rect x={4} y={9} width={12} height={8} rx={1.5} />
      <path d="M6.5 9V6.5a3.5 3.5 0 0 1 7 0V9" />
    </svg>
  )
}

function DisconnectModal({
  deviceName,
  onClose,
}: {
  readonly deviceName: string
  readonly onClose: () => void
}) {
  // Once the purge starts there is no going back to the app: the runtime holds a destroyed
  // identity until the reload, so backdrop and Escape stop dismissing.
  const [locked, setLocked] = useState(false)
  useEffect(() => {
    function onKey(e: globalThis.KeyboardEvent) {
      if (e.key === 'Escape' && !locked) onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [locked, onClose])
  // Portalled to <body>: rendered in place, an ancestor in the top bar acted as the containing
  // block for this `fixed` overlay, pinning it to the bar and leaving the page undimmed.
  return createPortal(
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="disconnect-title"
      className="fixed inset-0 z-40 grid place-items-center bg-black/40 p-4"
      onClick={e => {
        if (e.target === e.currentTarget && !locked) onClose()
      }}
    >
      <div className="w-full max-w-md rounded-lg border border-line bg-surface p-6 shadow-xl">
        <DisconnectBrowserPanel
          deviceName={deviceName}
          session={Session}
          onCancel={onClose}
          onBusyChange={setLocked}
          renderTitle={text => (
            <h2 id="disconnect-title" className="text-lg font-semibold">
              {text}
            </h2>
          )}
        />
      </div>
    </div>,
    document.body,
  )
}
