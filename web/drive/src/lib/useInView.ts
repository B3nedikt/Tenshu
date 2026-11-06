import { useEffect, useRef, useState } from 'react'

// Returns a ref to attach to an element + whether it has scrolled into view.
// Sticky: once seen it stays true, so a loaded thumbnail isn't dropped when the
// row scrolls back off-screen. Degrades to always-visible where
// `IntersectionObserver` is unavailable (jsdom tests, very old browsers) so the
// gated work still runs.
export function useInView<T extends Element>(): [React.RefObject<T | null>, boolean] {
  const ref = useRef<T>(null)
  const [inView, setInView] = useState(typeof IntersectionObserver === 'undefined')

  useEffect(() => {
    if (inView) return
    const el = ref.current
    if (!el) return
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          setInView(true)
          observer.disconnect()
        }
      },
      // Preload a little before the row is actually on screen.
      { rootMargin: '100px' },
    )
    observer.observe(el)
    return () => observer.disconnect()
  }, [inView])

  return [ref, inView]
}
