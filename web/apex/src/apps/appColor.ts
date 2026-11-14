// An app's colour pair as the custom properties `--a` / `--b` (docs/design/brand/tokens/apps.ts,
// ADR 0027), for the app icon and for anything drawn in that app's colour (a glance card's meters).
// An app with no pair of its own gets the brand amethyst (`--app-default-*`).
import type { CSSProperties } from 'react'

export function appColorStyle(appId: string): CSSProperties {
  return {
    '--a': `var(--app-${appId}-a, var(--app-default-a))`,
    '--b': `var(--app-${appId}-b, var(--app-default-b))`,
  } as CSSProperties
}
