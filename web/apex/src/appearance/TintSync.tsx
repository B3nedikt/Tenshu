// Draws the stored background colour (`background.ts`): every ready read applies it and refreshes
// the boot mirror. Mounted once, under the session's background provider.
// ⚠ Leaving the session (sign-out, lock) takes the colour away with it: it is the realm's, and the
// landing page or another account's onboarding in this browser must not wear it.
import { useEffect } from 'react'
import { parseTint } from '@tenshu/web-tokens/tint'
import { useBackgroundState } from '../overview/shownAppsContext'
import { applyTint } from './background'

export function TintSync() {
  const { state } = useBackgroundState()
  useEffect(() => {
    if (state.kind === 'ready') applyTint(parseTint(state.stored[0]))
  }, [state])
  useEffect(() => () => applyTint(null), [])
  return null
}
