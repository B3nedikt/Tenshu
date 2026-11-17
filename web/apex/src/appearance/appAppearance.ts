// What a hosted app is told about the apex's appearance (docs/proposals/web-apex-home.md §4.15):
// mode, the language the apex SHOWS, and the tint. Sent as the frame's `name` at open and as a
// `tenshu.appearance` message on every change (and on the app's `tenshu.ready`).
//
// ⚠ The shape is duplicated in the app-kit (`web/app-kit/src/appearance.ts`) — the apex does not
// depend on it. Change both together.
import { useTranslation } from 'react-i18next'
import { tintMirrorValue } from '@tenshu/web-tokens/tint'
import { useAppearance } from './appearance'
import { useCurrentTint } from './background'

export const APPEARANCE_MESSAGE = 'tenshu.appearance'
export const FRAME_NAME_PREFIX = 'tenshu-appearance:'

export interface AppAppearance {
  readonly mode: 'light' | 'dark' | 'auto'
  readonly language: string
  readonly tint: string | null
}

export function useAppAppearance(): AppAppearance {
  const { mode } = useAppearance()
  const tint = useCurrentTint()
  // Re-renders on a language change; resolved, so "Automatic" sends what is actually shown.
  const { i18n } = useTranslation()
  return { mode, language: i18n.resolvedLanguage ?? i18n.language ?? 'en', tint: tintMirrorValue(tint) }
}

export function frameName(appearance: AppAppearance): string {
  return FRAME_NAME_PREFIX + JSON.stringify(appearance)
}

export function appearanceMessage(appearance: AppAppearance) {
  return { type: APPEARANCE_MESSAGE, ...appearance }
}
