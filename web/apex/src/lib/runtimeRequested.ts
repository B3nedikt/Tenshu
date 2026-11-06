// "Has anything on this page asked for the runtime?" — without importing it.
//
// ⚠ Kotlin-free on purpose: the root reads this to decide whether to load the runtime lease notice,
// and the root must not reach the Kotlin bundle (web-app-suite.md F6). `ensureRuntime` sets it.
import { useSyncExternalStore } from 'react'

let requested = false
const listeners = new Set<() => void>()

export function markRuntimeRequested(): void {
  if (requested) return
  requested = true
  for (const listener of listeners) listener()
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function useRuntimeRequested(): boolean {
  return useSyncExternalStore(subscribe, () => requested, () => false)
}
