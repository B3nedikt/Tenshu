// Recovery banner shown above the Home notification feed when the
// 24-word phrase is unverified or stale. Per `control-ia.md` §3:
// "Home does the alerting; Settings owns the steady-state surface."
//
// Phase A reads the IDB-stored verified-at timestamp; the write path
// (Settings → Recovery → Test recovery) lands with Phase B. Phase A
// therefore renders the banner unconditionally (every browser is
// "never verified" today) — Phase B's Test recovery flow flips it off.
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { useTranslation } from 'react-i18next'
import { getRecoveryStatus, type RecoveryStatus } from './recoveryStatus'

export function RecoveryBanner() {
  const { t } = useTranslation('control')
  const [status, setStatus] = useState<RecoveryStatus | null>(null)
  useEffect(() => {
    let cancelled = false
    void getRecoveryStatus().then(s => {
      if (!cancelled) setStatus(s)
    })
    return () => {
      cancelled = true
    }
  }, [])
  if (status === null || status.kind === 'verified') return null
  const isStale = status.kind === 'stale'
  return (
    <div className="mb-6 rounded-md border border-warning/30 bg-warning/10 p-4">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm font-medium text-warning">
            {isStale ? t('recoveryBanner.titleReverify') : t('recoveryBanner.titleVerify')}
          </p>
          <p className="mt-1 text-sm text-warning">
            {isStale
              ? t('recoveryBanner.bodyReverify', {
                  date: new Date(status.verifiedAtMs).toLocaleDateString(),
                })
              : t('recoveryBanner.bodyVerify')}
          </p>
        </div>
        <Link
          to="/control/settings/recovery"
          className="shrink-0 rounded-md bg-warning px-3 py-1.5 text-sm font-medium text-white hover:bg-warning/90"
        >
          {t('recoveryBanner.cta')}
        </Link>
      </div>
    </div>
  )
}
