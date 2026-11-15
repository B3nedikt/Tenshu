// The production shown-apps source: the SDK's `Overview` surface over a REALM preference, so the
// list is shared by every browser of the realm (web-apex-home.md D2).
//
// ⚠ Isolated because importing `@tenshu/sdk/overview` drags the Kotlin bundle into whatever imports
// it. Only the runtime root does — see `shownAppsContext.ts`.
import { Overview } from '@tenshu/sdk/overview'
import type { ShownAppsSource } from './shownAppsContext'

export const realShownApps: ShownAppsSource = {
  observe(onList, onError) {
    return Overview.observeShownApps(
      result => (result.ok ? onList(result.appIds) : onError(result.error.message ?? result.error.kind)),
      onError,
    )
  },
  async write(appIds) {
    const result = await Overview.setShownApps(appIds)
    return result.ok ? { ok: true } : { ok: false, message: result.error.message ?? result.error.kind }
  },
}
