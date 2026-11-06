import { reloadAfterPurge } from '../lib/reloadAfterPurge'
// Device-name pill + lock-now icon + dropdown menu (per
// `web-control-plane.md` §3.2). High-frequency security actions live in
// chrome rather than buried under Data → Devices.
//
// Lock now: clears the apex's session token and routes to /lock. The
// realm IDB stays intact so the next unlock works. Phase 5 PRF wrap is
// already wired SDK-side, so the master keypair re-wraps on the next
// session-mint cycle.
//
// Sign out everywhere (self-revoke, roadmap Phase B): push a
// `RevokeDevice` tombstone for THIS browser, *then* wipe local state and
// redirect to landing — so the browser doesn't linger as a ghost device
// in other devices' lists. Ordering is load-bearing: the wipe destroys
// the device signing key + relay JWT, so we must project the revocation
// before clearing (see `Devices.revokeSelf` doc). Offline → warn-and-
// proceed: we couldn't push the tombstone, so we tell the user this
// browser will keep showing on their other devices until they remove it
// there, then wipe locally anyway (the ghost is reaped by revoking from
// another device). The locked-flow "Disconnect" stays a local-only purge
// — no master key in memory there to sign the tombstone (Phase A).
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { useTranslation } from 'react-i18next'
import { Session } from '@tenshu/sdk/onboarding'
import { Devices } from '@tenshu/sdk/identity'

// Sign-out state machine: idle → confirming → revoking → (offline
// warn-and-proceed). `revoking` covers both the tombstone push and the
// subsequent wipe; `offline` is the warn-and-proceed fork when the
// tombstone couldn't be pushed.
type SignOutState =
  | { readonly kind: 'idle' }
  | { readonly kind: 'confirming' }
  | { readonly kind: 'revoking' }
  | { readonly kind: 'offline' }

export function DeviceMenu({ deviceName }: { readonly deviceName: string }) {
  const { t } = useTranslation('control')
  const [open, setOpen] = useState(false)
  const [signOut, setSignOut] = useState<SignOutState>({ kind: 'idle' })
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

  // Local-only wipe + redirect. Used both as the success tail (after the
  // tombstone is pushed) and as the offline warn-and-proceed outcome.
  async function wipeAndLeave() {
    await Session.clear({ purgeRealm: true })
    reloadAfterPurge('/')
  }

  async function performSignOut() {
    setSignOut({ kind: 'revoking' })
    const result = await Devices.revokeSelf()
    if (!result.ok) {
      // Couldn't project the revocation (offline, or a relay error). Don't
      // wipe yet — warn first, then let the user proceed with a local-only
      // sign-out (B.2 warn-and-proceed).
      setSignOut({ kind: 'offline' })
      return
    }
    await wipeAndLeave()
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
          className="flex max-w-[12rem] items-center gap-1.5 rounded-full border border-line px-3 py-1 text-sm text-text-2 hover:bg-surface focus:outline-none focus-visible:ring-2 focus-visible:ring-line-strong"
        >
          <span aria-hidden className="h-2 w-2 rounded-full bg-success" />
          <span className="truncate font-mono text-xs">{deviceName}</span>
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
                  setSignOut({ kind: 'confirming' })
                }}
                className="block w-full rounded-md px-2 py-1.5 text-left text-sm text-danger hover:bg-danger/10"
              >
                {t('deviceMenu.signOutEverywhere')}
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
      {signOut.kind !== 'idle' && (
        <SignOutConfirmModal
          state={signOut}
          onCancel={() => setSignOut({ kind: 'idle' })}
          onConfirm={() => void performSignOut()}
          onProceedOffline={() => void wipeAndLeave()}
        />
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

function SignOutConfirmModal({
  state,
  onCancel,
  onConfirm,
  onProceedOffline,
}: {
  readonly state: SignOutState
  readonly onCancel: () => void
  readonly onConfirm: () => void
  readonly onProceedOffline: () => void
}) {
  const { t } = useTranslation('control')
  const offline = state.kind === 'offline'
  const inFlight = state.kind === 'revoking'
  // While the tombstone is being pushed, swallow backdrop/cancel so the
  // ordering-sensitive revoke→wipe sequence isn't interrupted mid-flight.
  const dismiss = inFlight ? () => {} : onCancel
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="signout-title"
      className="fixed inset-0 z-40 grid place-items-center bg-black/40 p-4"
      onClick={e => {
        if (e.target === e.currentTarget) dismiss()
      }}
    >
      <div className="w-full max-w-md rounded-lg border border-line bg-surface p-6 shadow-xl">
        <h2 id="signout-title" className="text-lg font-semibold">
          {offline ? t('deviceMenu.confirm.offline.title') : t('deviceMenu.confirm.title')}
        </h2>
        <div className="mt-3 space-y-3 text-sm text-text-2">
          {offline ? (
            <p>{t('deviceMenu.confirm.offline.body')}</p>
          ) : (
            <>
              <p>{t('deviceMenu.confirm.body1')}</p>
              <p>{t('deviceMenu.confirm.body2')}</p>
            </>
          )}
        </div>
        <div className="mt-6 flex flex-wrap justify-end gap-3">
          <button
            type="button"
            disabled={inFlight}
            onClick={onCancel}
            className="rounded-md border border-line bg-surface px-4 py-2 text-sm font-medium text-text-2 hover:bg-surface disabled:cursor-not-allowed disabled:opacity-60"
          >
            {offline ? t('deviceMenu.confirm.offline.cancel') : t('deviceMenu.confirm.cancel')}
          </button>
          <button
            type="button"
            disabled={inFlight}
            onClick={offline ? onProceedOffline : onConfirm}
            className="rounded-md bg-danger px-4 py-2 text-sm font-medium text-white hover:bg-danger/90 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {inFlight
              ? t('deviceMenu.confirm.revoking')
              : offline
                ? t('deviceMenu.confirm.offline.proceed')
                : t('deviceMenu.confirm.confirm')}
          </button>
        </div>
      </div>
    </div>
  )
}
