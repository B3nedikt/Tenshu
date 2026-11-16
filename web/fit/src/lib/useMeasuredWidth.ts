// An element's content width, kept current (ResizeObserver) — the charts lay out in real pixels.
// ⚠ jsdom has no layout: a width of 0 falls back to [fallback], so tests render a real-sized chart.
import { useLayoutEffect, useRef, useState } from 'react'

export function useMeasuredWidth<T extends HTMLElement>(fallback = 640): [React.RefObject<T | null>, number] {
  const ref = useRef<T>(null)
  const [width, setWidth] = useState(fallback)
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const measure = () => setWidth(el.clientWidth || fallback)
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(measure)
    observer.observe(el)
    return () => observer.disconnect()
  }, [fallback])
  return [ref, width]
}
