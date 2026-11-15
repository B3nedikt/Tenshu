// "You use it on Pixel 9" — the devices an app is registered on, for the "Your apps" dialog's
// *More apps* list (docs/proposals/web-apex-home.md §3.5, H6).
//
// ⚠⚠ Never a guessed line: loading, a failed read, an app no device registered, and a device the
// roster cannot name (revoked, not synced yet) all yield NOTHING — not "on no devices", not an id.
// ⚠ Every registration is another device's: the browser writes none of its own (`appRegistry.ts`).
import type { RegistrationsState } from '../control/apps/appRegistry'

/** The named devices [appId] is registered on, sorted and de-duplicated; empty for "say nothing". */
export function usedOnDevices(registrations: RegistrationsState, appId: string): readonly string[] {
  if (registrations.kind !== 'ready') return []
  const info = registrations.byApp.get(appId)
  if (info === undefined) return []
  const names = info.devices.flatMap(d => (d.name === null ? [] : [d.name]))
  return [...new Set(names)].sort((a, b) => a.localeCompare(b))
}
