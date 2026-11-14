// A top-bar popover's open state: closes on a click outside and on Escape (focus back on the
// trigger), the behaviour the app launcher already has. Shared by the bell and the sync pill.
import { useCallback, useEffect, useRef, useState } from 'react'

export function usePopover() {
  const [open, setOpen] = useState(false)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)

  const close = useCallback((returnFocus: boolean) => {
    setOpen(false)
    if (returnFocus) triggerRef.current?.focus()
  }, [])

  // A popover is a dialog: focus moves into it on open (its first link or button).
  useEffect(() => {
    if (open) panelRef.current?.querySelector<HTMLElement>('a[href], button:not([disabled])')?.focus()
  }, [open])

  useEffect(() => {
    if (!open) return
    function onPointerDown(e: PointerEvent) {
      const target = e.target as Node | null
      if (panelRef.current?.contains(target) || triggerRef.current?.contains(target)) return
      close(false)
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault()
        close(true)
      }
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open, close])

  return { open, toggle: () => setOpen(o => !o), close, triggerRef, panelRef }
}
