// The apps the apex hosts, in launcher / Apps-list order. ⚠ Registering an app is ADDING ONE FILE,
// `apps/<id>.ts`, whose default export is its `HostedApp` — no shared list to edit, so parallel app
// sessions never collide here (web-app-suite.md F4; docs/how-to/new-web-app.md).
// `registry.test.ts` fails when a `web/<id>` app has no entry, or an entry has no app.
import type { HostedApp } from './types'

const modules = import.meta.glob<{ default: HostedApp }>('./apps/*.ts', { eager: true })

export const HOSTED_APPS: readonly HostedApp[] = Object.values(modules)
  .map((module) => module.default)
  .sort((a, b) => a.order - b.order || a.id.localeCompare(b.id))

export function findHostedApp(id: string): HostedApp | null {
  return HOSTED_APPS.find((app) => app.id === id) ?? null
}

export { appOrigin, SESSION_METHODS, type HostedApp } from './types'
