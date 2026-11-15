import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'

// How long the bubble stays after the last scroll, as macOS's overlay scrollbar fades.
const LINGER_MS = 1000
const BUBBLE_PX = 28
// Clear of the browser's scrollbar on the right (an overlay one is ~10 px, a classic one ~17).
const SCROLLBAR_CLEARANCE = 20

/**
 * The date at the top of the grid, beside the browser's own scrollbar while it moves
 * (web-apps-layout.md §4.9). ⚠⚠ The scrollbar IS the position indicator — it looks native on every
 * system, and keyboard and accessibility come with it. This bubble only says WHEN you are, so it is
 * decorative (`aria-hidden`): the section headers already say it to a screen reader.
 *
 * ⚠ It follows the scroll FRACTION, not the scrollbar thumb: a page cannot read where the browser
 * drew the thumb, so the bubble rides at the matching height and never covers the bar itself.
 */
export function DateBubble({
  when,
  scrollTop,
  scrollHeight,
  viewport,
  yearOnly,
}: {
  when: number | undefined
  scrollTop: number
  scrollHeight: number
  viewport: number
  yearOnly: boolean
}) {
  const { i18n } = useTranslation()
  const [visible, setVisible] = useState(false)
  const [seen, setSeen] = useState(scrollTop)

  // Shown by a scroll, not by the first render: opening the Library is not scrolling it.
  if (scrollTop !== seen) {
    setSeen(scrollTop)
    if (!visible) setVisible(true)
  }
  useEffect(() => {
    if (!visible) return
    const timer = setTimeout(() => setVisible(false), LINGER_MS)
    return () => clearTimeout(timer)
  }, [visible, seen])

  const format = useMemo(
    () => new Intl.DateTimeFormat(i18n.language, yearOnly ? { year: 'numeric' } : { month: 'long', year: 'numeric' }),
    [i18n.language, yearOnly],
  )
  const range = scrollHeight - viewport
  if (when === undefined || range <= 0) return null
  const fraction = Math.min(1, Math.max(0, scrollTop / range))
  return (
    <span
      aria-hidden
      data-testid="date-bubble"
      data-visible={visible || undefined}
      className={`pointer-events-none absolute z-20 whitespace-nowrap rounded-full bg-surface-2/95 px-3 py-1 text-sm font-medium text-text shadow-lg ring-1 ring-line transition-opacity duration-300 ${
        visible ? 'opacity-100' : 'opacity-0'
      }`}
      style={{ right: SCROLLBAR_CLEARANCE, top: fraction * (viewport - BUBBLE_PX), height: BUBBLE_PX }}
    >
      {format.format(when)}
    </span>
  )
}
