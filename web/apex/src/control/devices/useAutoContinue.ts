// The countdown behind `AutoContinueButton` — `docs/proposals/control-pairing-ux.md` §9.
// The web twin of `:ui-core`'s `AppAutoContinueButton` (Compose) and control-ios's
// `AutoContinueButton.swift`. Same rules, same timing:
//   - counts down only while `armed`, not cancelled, and the page is visible;
//   - "Stay", or any pointer-down on the surrounding screen (`auto.cancel`), stops it for good;
//   - a hidden tab pauses it, and returning restarts the full countdown;
//   - reduced motion drops the ring (the component's concern), never the timer.
// ⚠ The web has no reliable screen-reader signal, so — as on desktop — none is consulted.
import { useCallback, useEffect, useRef, useState } from 'react'

/** ⚠ Mirrors `AUTO_CONTINUE_DURATION_MS` (Kotlin) and `autoContinueDurationMs` (Swift). Change all three. */
export const AUTO_CONTINUE_DURATION_MS = 3000

const REDUCED_MOTION = '(prefers-reduced-motion: reduce)'

function usePageVisible(): boolean {
  const [visible, setVisible] = useState(
    () => typeof document === 'undefined' || document.visibilityState !== 'hidden',
  )
  useEffect(() => {
    const onChange = () => setVisible(document.visibilityState !== 'hidden')
    document.addEventListener('visibilitychange', onChange)
    return () => document.removeEventListener('visibilitychange', onChange)
  }, [])
  return visible
}

/** `prefers-reduced-motion: reduce`. False where there is no matchMedia (tests, SSR). */
function usePrefersReducedMotion(): boolean {
  const [reduced] = useState(
    () =>
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia(REDUCED_MOTION).matches,
  )
  return reduced
}

export interface AutoContinue {
  /** The countdown is running (and "Stay" is offered). */
  readonly counting: boolean
  readonly secondsLeft: number
  readonly reducedMotion: boolean
  readonly durationMs: number
  /** Stop the countdown for good; the button stays, as a plain Continue. */
  readonly cancel: () => void
  /** Continue now (the button's own click). */
  readonly continueNow: () => void
}

/**
 * Hoisted so the screen can cancel on any pointer-down (`onPointerDownCapture={auto.cancel}`).
 * `onContinue` runs at most once from the timer.
 */
export function useAutoContinue({
  armed,
  onContinue,
  durationMs = AUTO_CONTINUE_DURATION_MS,
}: {
  readonly armed: boolean
  readonly onContinue: () => void
  readonly durationMs?: number
}): AutoContinue {
  const [cancelled, setCancelled] = useState(false)
  const [fired, setFired] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const visible = usePageVisible()
  const reducedMotion = usePrefersReducedMotion()
  const onContinueRef = useRef(onContinue)
  useEffect(() => {
    onContinueRef.current = onContinue
  }, [onContinue])

  const counting = armed && !cancelled && !fired && visible

  useEffect(() => {
    if (!counting) return
    // Whole-second ticks for the spoken label; the timer itself ends exactly at `durationMs`.
    let done = 0
    let timer = 0
    const schedule = () => {
      const left = durationMs - done
      const next = left % 1000 === 0 ? 1000 : left % 1000
      timer = window.setTimeout(() => {
        done += next
        if (done >= durationMs) {
          setFired(true)
          onContinueRef.current()
          return
        }
        setElapsed(done)
        schedule()
      }, next)
    }
    schedule()
    // Paused, cancelled or unmounted: the next arm restarts from the full duration.
    return () => {
      window.clearTimeout(timer)
      setElapsed(0)
    }
  }, [counting, durationMs])

  const cancel = useCallback(() => setCancelled(true), [])
  const continueNow = useCallback(() => {
    setFired(true)
    onContinueRef.current()
  }, [])

  return {
    counting,
    secondsLeft: Math.ceil((durationMs - elapsed) / 1000),
    reducedMotion,
    durationMs,
    cancel,
    continueNow,
  }
}
