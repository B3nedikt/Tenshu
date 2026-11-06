// Auto-continue button — `docs/proposals/control-pairing-ux.md` §9. The countdown itself lives in
// `useAutoContinue.ts` (hoisted, so the screen can cancel on any pointer-down).
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import type { AutoContinue } from './useAutoContinue'

const RING_RADIUS = 7
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS

/**
 * A Continue button that shows a countdown ring while [auto] is counting, with a "Stay" button
 * beside it. A plain button otherwise. The spoken "Continuing in N seconds" rides
 * `aria-describedby`, so the button's accessible name stays its label.
 */
export function AutoContinueButton({ auto, label }: { readonly auto: AutoContinue; readonly label: string }) {
  const { t } = useTranslation('control')
  const countdownId = useId()
  return (
    <div className="flex flex-wrap items-center gap-3">
      {auto.counting && (
        <span id={countdownId} className="sr-only">
          {t('devices.pair.autoContinue.countdown', { seconds: auto.secondsLeft })}
        </span>
      )}
      <button
        type="button"
        onClick={auto.continueNow}
        aria-describedby={auto.counting ? countdownId : undefined}
        className="inline-flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90"
      >
        {auto.counting && !auto.reducedMotion && (
          <svg
            data-testid="auto-continue-ring"
            aria-hidden
            width="16"
            height="16"
            viewBox="0 0 16 16"
            className="-rotate-90"
          >
            <circle cx="8" cy="8" r={RING_RADIUS} fill="none" stroke="currentColor" strokeOpacity="0.3" strokeWidth="2" />
            <circle
              cx="8"
              cy="8"
              r={RING_RADIUS}
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeDasharray={RING_CIRCUMFERENCE}
              strokeDashoffset={RING_CIRCUMFERENCE}
            >
              <animate
                attributeName="stroke-dashoffset"
                from={RING_CIRCUMFERENCE}
                to="0"
                dur={`${auto.durationMs}ms`}
                fill="freeze"
              />
            </circle>
          </svg>
        )}
        {label}
      </button>
      {auto.counting && (
        <button
          type="button"
          onClick={auto.cancel}
          className="text-sm font-medium text-text-2 hover:text-text"
        >
          {t('devices.pair.autoContinue.stay')}
        </button>
      )}
    </div>
  )
}
