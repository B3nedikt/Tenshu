// The apex half of an app's URL (web-apps-layout.md §3.4, L2). The app half is the app-kit's
// `route.ts`; ⚠ the two message shapes are duplicated there (the apex does not depend on the
// app-kit) — change both together.
//
// ⚠⚠ The apex accepts a reported PATH only if it is one of the app's registered `sections`, so
// a folder or file name can never reach the apex URL's path (a reload would send it to the apex host,
// which logs it). User data rides in the fragment, which is never sent.
import type { HostedApp } from './registry'

export const NAV_MESSAGE = 'tenshu.nav'
export const ROUTE_MESSAGE = 'tenshu.route'
// App → apex, once its route listener is installed: anything sent before that was lost.
export const READY_MESSAGE = 'tenshu.ready'

// A fragment is the app's business, but bounded: a URL is not storage.
const MAX_HASH = 8192

/** `/drive` for `drive/*`. */
export function appBase(app: HostedApp): string {
  return '/' + app.routePath.replace(/\/\*$/, '')
}

/** The in-app path a section names: `''` → `/`, `files` → `/files`. */
export function sectionPath(section: string): string {
  return section === '' ? '/' : `/${section}`
}

/** [path] if it is one of the app's sections, else null. */
export function allowedPath(app: HostedApp, path: string): string | null {
  return app.sections.some((section) => sectionPath(section) === path) ? path : null
}

/** The in-app path for an apex [pathname] under the app, or `/` when it names no section. */
export function appPathFor(app: HostedApp, pathname: string): string {
  const rest = pathname.slice(appBase(app).length) || '/'
  // A trailing slash (`/drive/files/`) is the same section.
  const normalised = rest.length > 1 ? rest.replace(/\/+$/, '') : rest
  return allowedPath(app, normalised) ?? '/'
}

export function validHash(hash: unknown): hash is string {
  return typeof hash === 'string' && hash.length <= MAX_HASH && (hash === '' || hash.startsWith('#'))
}

export interface NavReport {
  readonly path: string
  readonly hash: string
  readonly push: boolean
}

/** A `tenshu.nav` message's payload if it is well-formed and names a section, else null. */
export function readNavMessage(app: HostedApp, data: unknown): NavReport | null {
  if (!data || typeof data !== 'object') return null
  const message = data as { type?: unknown; path?: unknown; hash?: unknown; push?: unknown }
  if (message.type !== NAV_MESSAGE || typeof message.path !== 'string') return null
  const path = allowedPath(app, message.path)
  if (path === null) return null
  // A fragment we will not carry (too long, malformed) costs only the fragment — the section move
  // and its history entry still happen.
  return { path, hash: validHash(message.hash) ? message.hash : '', push: message.push === true }
}

/** The apex URL for an in-app [path] + [hash]. */
export function apexUrl(app: HostedApp, path: string, hash: string): string {
  return appBase(app) + (path === '/' ? '' : path) + hash
}
