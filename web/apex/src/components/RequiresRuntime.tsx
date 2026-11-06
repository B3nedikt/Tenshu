import { useState, type ReactNode } from 'react'
import { ensureRuntime } from '../lib/runtime'

// Route gate: everything under it may use the Kotlin runtime, so start it (configure + take the
// `tenshu-runtime` lock) before any child renders — a child's first SDK call must find the relay
// already configured. Public pages are simply not wrapped (docs/web/architecture.md "Cross-tab").
//
// ⚠ In a useState initialiser, not an effect: effects run child-first, so a child's effect would
// reach the SDK before this one configured it.
export function RequiresRuntime({
  children,
  start = ensureRuntime,
}: {
  readonly children: ReactNode
  readonly start?: () => void
}) {
  useState(() => {
    start()
    return true
  })
  return <>{children}</>
}
