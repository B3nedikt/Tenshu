// The shown-apps seam (docs/proposals/web-apex-home.md §3.5): one live subscription shared by the
// overview's dock, its dialog, Customize mode and the launcher in both shells.
//
// ⚠ This module deliberately does NOT import `@tenshu/sdk/overview` — that drags the Gradle-built
// Kotlin bundle into every test that renders the launcher. The production source is
// `realShownApps.ts`, wired in at the runtime root (the `storageStatusContext.ts` pattern).
import { createContext, useContext } from 'react'

export type ShownAppsWrite = { readonly ok: true } | { readonly ok: false; readonly message: string }

export interface ShownAppsSource {
  /** Seeds, then re-emits the stored list (`[]` = never customised). Returns the unsubscribe. */
  observe(onList: (stored: readonly string[]) => void, onError: (message: string) => void): () => void
  /** Replaces the whole stored list. */
  write(appIds: readonly string[]): Promise<ShownAppsWrite>
}

export type ShownAppsState =
  /** No answer yet: render nothing app-specific rather than a default that may be wrong. */
  | { readonly kind: 'loading' }
  /**
   * `stored` is the EFFECTIVE list: the store's, or — between a write resolving and the store
   * re-emitting it — the list just written (`ShownAppsProvider`). Every consumer reads this one.
   */
  | { readonly kind: 'ready'; readonly stored: readonly string[] }
  /**
   * ⚠⚠ The list could not be read (or no source is configured). A failure, never an empty
   * success: consumers show EVERY app and say so — hiding apps on a guess would look like data loss.
   */
  | { readonly kind: 'unavailable'; readonly message: string }

export interface ShownAppsValue {
  readonly state: ShownAppsState
  /** A write is in flight. Editors refuse new changes until it settles (they build from `stored`). */
  readonly pending: boolean
  /** Writes [next] as the whole list. False when it failed (nothing moved) or nothing is loaded. */
  readonly apply: (next: readonly string[]) => Promise<boolean>
}

const NOT_CONFIGURED: ShownAppsValue = {
  state: { kind: 'unavailable', message: 'no shown-apps source' },
  pending: false,
  apply: async () => false,
}

export const ShownAppsContext = createContext<ShownAppsValue>(NOT_CONFIGURED)

export function useShownAppsState(): ShownAppsValue {
  return useContext(ShownAppsContext)
}
