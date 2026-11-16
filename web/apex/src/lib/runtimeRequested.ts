// "Has anything on this page asked for the runtime?" — without importing it.
//
// ⚠ Kotlin-free on purpose: the root reads this to decide whether to load the runtime lease notice,
// and the root must not reach the Kotlin bundle (web-app-suite.md F6). `ensureRuntime` sets it.
import { useSyncExternalStore } from 'react'

let requested = false
let pending = false
const listeners = new Set<() => void>()

// ⚠⚠ Deferred to a microtask, flag and notify both. The caller is `RequiresRuntime`, during RENDER;
// flipping the store there is a render-phase update to another component (the root's notice slot),
// and React answers it by throwing the in-progress render away and starting over. Any render-time
// work under the gate then ran twice — that is how every invite link read as "incomplete"
// (`useSecretFragment`). Settling after the render keeps it an ordinary update.
export function markRuntimeRequested(): void {
  if (requested || pending) return
  pending = true
  queueMicrotask(() => {
    requested = true
    for (const listener of listeners) listener()
  })
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function useRuntimeRequested(): boolean {
  return useSyncExternalStore(subscribe, () => requested, () => false)
}
