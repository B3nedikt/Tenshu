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
  // The attention colour (rose, ADR 0027) marks it as needing action; the words stay in the body
  // colours, so it reads as a request rather than an alarm.
  return (
    <div className="mb-6 flex flex-wrap items-center gap-x-4 gap-y-3 rounded-[18px] border border-attention-line bg-glass p-4 shadow-glass">
      <span
        aria-hidden
        className="grid h-10 w-10 shrink-0 place-items-center rounded-[12px] bg-linear-135 from-attention to-attention-2 text-on-attention"
      >
        <svg viewBox="0 0 24 24" width={22} height={22} fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">
          <path d="M12 3l7 3v5c0 4.5-3 8.2-7 10-4-1.8-7-5.5-7-10V6z" />
          <path d="M9 12l2 2 4-4" />
        </svg>
      </span>
      <div className="min-w-0 flex-1 basis-56">
        <p className="text-sm font-semibold text-text">
          {isStale ? t('recoveryBanner.titleReverify') : t('recoveryBanner.titleVerify')}
        </p>
        <p className="mt-0.5 text-sm text-text-2">
            {isStale
              ? t('recoveryBanner.bodyReverify', {
                  date: new Date(status.verifiedAtMs).toLocaleDateString(),
                })
              : t('recoveryBanner.bodyVerify')}
        </p>
      </div>
      <Link
        to="/control/settings/recovery"
        className="ml-14 shrink-0 rounded-pill bg-primary px-4 py-1.5 text-sm font-semibold text-on-primary hover:opacity-90 focus:outline-none focus-visible:ring-2 focus-visible:ring-line-strong sm:ml-0"
      >
        {t('recoveryBanner.cta')}
      </Link>
    </div>
  )
}
