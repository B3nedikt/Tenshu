import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { TenshuSdk, isRuntimeElsewhere, type RuntimeLeaseState } from '@tenshu/sdk/core'

// One runtime per origin (docs/web/architecture.md "Cross-tab"). Another tab — a second apex tab,
// or the apex iframe inside a Drive tab — holds the runtime, so every SDK call in this tab is
// refused. Say so, and offer to move it here.
//
// ⚠ Only listens: subscribing acquires nothing, so the notice appears exactly when something on
// this page asked for the runtime and was refused (or handed it over).
// ⚠ "Use here" ends in a reload — this page never opened its stores, so only a fresh runtime can
// take the lock (`TenshuSdk.takeOverRuntime`).
export function RuntimeLeaseNotice({ sdk = TenshuSdk }: { readonly sdk?: Pick<typeof TenshuSdk, 'onRuntimeLeaseChange' | 'takeOverRuntime'> }) {
  const { t } = useTranslation()
  const [state, setState] = useState<RuntimeLeaseState | null>(null)
  const [moving, setMoving] = useState(false)

  useEffect(() => sdk.onRuntimeLeaseChange(setState), [sdk])

  if (!isRuntimeElsewhere(state)) return null

  const useHere = async () => {
    setMoving(true)
    try {
      await sdk.takeOverRuntime()
    } finally {
      window.location.reload()
    }
  }

  // Same banner as `control/TryModeBanner.tsx` — an existing pattern, not a new one.
  return (
    <div role="status" className="border-b border-warning/30 bg-warning/10 px-4 py-3">
      <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-medium text-warning">{t('runtime.elsewhere.title')}</p>
          <p className="text-xs text-warning">
            {/* The hand-over can wait up to 2 min for the other tab's flush — say so, not just "Moving…". */}
            {moving ? t('runtime.elsewhere.waiting') : t('runtime.elsewhere.body')}
          </p>
        </div>
        <button
          type="button"
          disabled={moving}
          onClick={useHere}
          className="rounded-md bg-warning px-3 py-1.5 text-xs font-medium text-white shadow-sm hover:bg-warning/90 disabled:opacity-50"
        >
          {moving ? t('runtime.elsewhere.moving') : t('runtime.elsewhere.useHere')}
        </button>
      </div>
    </div>
  )
}
