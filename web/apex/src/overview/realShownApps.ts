// The production shown-apps source: the SDK's `Overview` surface over a REALM preference, so the
// list is shared by every browser of the realm (web-apex-home.md D2).
//
// ⚠ Isolated because importing `@tenshu/sdk/overview` drags the Kotlin bundle into whatever imports
// it. Only the runtime root does — see `shownAppsContext.ts`.
import { Overview } from '@tenshu/sdk/overview'
import type { ShownAppsSource } from './shownAppsContext'

function storedList(key: 'shownApps' | 'widgets' | 'background'): ShownAppsSource {
  return {
    observe(onList, onError) {
      return Overview.observeList(
        key,
        result => (result.ok ? onList(result.appIds) : onError(result.error.message ?? result.error.kind)),
        onError,
      )
    },
    async write(values) {
      const result = await Overview.setList(key, values)
      return result.ok ? { ok: true } : { ok: false, message: result.error.message ?? result.error.kind }
    },
  }
}

export const realShownApps = storedList('shownApps')
/** The widget list (`widgets.ts`), its own REALM row. */
export const realWidgets = storedList('widgets')
/** The background colour (`theme/background.ts`), its own REALM row. */
export const realBackground = storedList('background')
