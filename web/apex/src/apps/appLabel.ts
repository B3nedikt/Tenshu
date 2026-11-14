// An app's display name and one-line description, from the `control` catalogue when it has them
// and the registry manifest's English otherwise — so registering an app never requires editing
// `control.json` to show up (the launcher's rule, shared with the Home dock and the in-app bar).
import type { TFunction } from 'i18next'
import type { HostedApp } from './registry'

export function appLabel(t: TFunction, app: HostedApp): string {
  return t(`appLauncher.entries.${app.id}.label`, { defaultValue: app.manifest.label })
}

export function appDescription(t: TFunction, app: HostedApp): string {
  return t(`appLauncher.entries.${app.id}.description`, { defaultValue: app.manifest.description })
}

/** The hosted app whose route [pathname] is inside, or null (Home, Control, anything else). */
export function hostedAppAt(apps: readonly HostedApp[], pathname: string): HostedApp | null {
  return (
    apps.find(app => {
      const base = '/' + app.routePath.replace(/\/\*$/, '')
      return pathname === base || pathname.startsWith(base + '/')
    }) ?? null
  )
}
