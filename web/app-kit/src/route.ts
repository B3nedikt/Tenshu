// An app's place in its own URL, mirrored to the apex's (web-apps-layout.md §3.4, L2).
//
// The app's SECTION is a fixed vocabulary and lives in the path (`/files`); anything the user named
// or that identifies their data (a folder, a person, an album) lives in the FRAGMENT
// (`#/Documents/Taxes`).
// ⚠⚠ Never put user data in the path: a reload sends the path to the apex and app hosts, both of
// which log it. A fragment is never sent. The apex enforces this too — it drops a reported path
// that is not one of the app's registered sections.
//
// The apex owns the browser history. Inside the frame the app only ever REPLACES its own entry (a
// push inside an iframe would add an entry to the tab's joint history that the apex knows nothing
// about), and reports every change to the apex, which pushes or replaces its own URL. Back / forward
// / a launcher click arrive the other way, as a `tenshu.route` message.
//
// ⚠ The two message shapes are duplicated in the apex (`web/apex/src/apps/appNav.ts`) — the apex
// does not depend on the app-kit. Change both together.
import { useCallback, useEffect, useRef, useState } from 'react'
import { configuredApexOrigin } from './bridge'

export const NAV_MESSAGE = 'tenshu.nav'
export const ROUTE_MESSAGE = 'tenshu.route'
// App → apex, once the route listener is installed (the apex re-sends its place; anything it sent
// earlier was lost).
export const READY_MESSAGE = 'tenshu.ready'

/** App → apex: where the app now is. `push` = a new history entry (a section change). */
export interface NavMessage {
  readonly type: typeof NAV_MESSAGE
  readonly path: string
  readonly hash: string
  readonly push: boolean
}

/** Apex → app: go here (back/forward, a launcher or deep link). */
export interface RouteMessage {
  readonly type: typeof ROUTE_MESSAGE
  readonly path: string
  readonly hash: string
}

export interface AppRoute<S extends string> {
  readonly section: S
  // The decoded fragment without its `#` ('' when there is none). App-defined: Drive keeps a folder
  // path here, Contacts a person id.
  readonly fragment: string
}

export interface AppRouter<S extends string> extends AppRoute<S> {
  // Open a section (a new history entry), with an optional fragment.
  readonly navigate: (section: S, fragment?: string) => void
  // Change only the fragment, in place (no new history entry): a SELECTION, e.g. the person in a
  // side pane — Back should not walk through every row you clicked.
  readonly setFragment: (fragment: string) => void
  // Open a detail that TAKES OVER the content (a viewer, a full-screen detail): a new history
  // entry in the current section, so browser Back closes it (web-apps-layout.md §4.2).
  readonly open: (fragment: string) => void
  // The detail's own Back button: steps browser history back when this app pushed the entry being
  // left, so it and browser Back agree; otherwise (a reload or a deep link straight into the detail,
  // or standalone) replaces the fragment with [fallback] — never history.back() out of the app.
  readonly back: (fallback?: string) => void
}

export interface UseAppRouteOptions {
  // The apex origin messages go to and are accepted from. Defaults to the configured one.
  readonly apexOrigin?: string
}

/** `home` ↔ `/`; every other section ↔ `/<id>`. */
export function pathOf<S extends string>(section: S, home: S): string {
  return section === home ? '/' : `/${section}`
}

export function hashOf(fragment: string): string {
  if (!fragment) return ''
  try {
    return `#${encodeURI(fragment)}`
  } catch {
    // A lone surrogate cannot be encoded: drop the fragment rather than throw out of a click.
    return ''
  }
}

/** Where [path] + [hash] point. An unknown section lands on [home], never throws. */
export function parseRoute<S extends string>(
  path: string,
  hash: string,
  sections: readonly S[],
  home: S,
): AppRoute<S> {
  const segment = path.split('/')[1] ?? ''
  const section = (sections as readonly string[]).includes(segment) ? (segment as S) : home
  let fragment = hash.startsWith('#') ? hash.slice(1) : hash
  try {
    fragment = decodeURI(fragment)
  } catch {
    // A malformed escape: keep the raw text rather than losing the place.
  }
  return { section, fragment }
}

export function useAppRoute<S extends string>(
  sections: readonly S[],
  home: S,
  options: UseAppRouteOptions = {},
): AppRouter<S> {
  const apexOrigin = options.apexOrigin ?? configuredApexOrigin()
  const [route, setRouteState] = useState<AppRoute<S>>(() =>
    parseRoute(window.location.pathname, window.location.hash, sections, home),
  )
  // The latest route, for `setFragment` (it keeps the section it is called in).
  const current = useRef(route)
  const setRoute = useCallback((next: AppRoute<S>) => {
    current.current = next
    setRouteState(next)
  }, [])

  // Entries this app pushed that are still behind the current one, as far as it can tell. Only a
  // framed app pushes (the apex does it); standalone everything is a replace.
  const pushed = useRef(0)
  const framed = window.parent !== window

  const go = useCallback(
    (next: AppRoute<S>, push: boolean) => {
      const path = pathOf(next.section, home)
      const hash = hashOf(next.fragment)
      window.history.replaceState(window.history.state, '', path + hash)
      setRoute(next)
      if (push && window.parent !== window) pushed.current += 1
      // Standalone (not framed) there is no apex to tell.
      if (window.parent !== window) {
        const message: NavMessage = { type: NAV_MESSAGE, path, hash, push }
        window.parent.postMessage(message, apexOrigin)
      }
    },
    [apexOrigin, home, setRoute],
  )

  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      // Only the embedding apex may move us.
      if (event.source !== window.parent || event.origin !== apexOrigin) return
      const data = event.data as Partial<RouteMessage> | null
      if (!data || data.type !== ROUTE_MESSAGE || typeof data.path !== 'string' || typeof data.hash !== 'string') return
      const next = parseRoute(data.path, data.hash, sections, home)
      // The apex moved us (most often Back): one fewer of our pushes is behind us. A Forward
      // under-counts, which only makes `back` fall back to a replace — never leave the app.
      pushed.current = Math.max(0, pushed.current - 1)
      window.history.replaceState(window.history.state, '', pathOf(next.section, home) + hashOf(next.fragment))
      setRoute(next)
    }
    window.addEventListener('message', onMessage)
    if (window.parent !== window) window.parent.postMessage({ type: READY_MESSAGE }, apexOrigin)
    return () => window.removeEventListener('message', onMessage)
  }, [apexOrigin, sections, home, setRoute])

  const navigate = useCallback((section: S, fragment = '') => go({ section, fragment }, true), [go])
  const setFragment = useCallback(
    (fragment: string) => go({ section: current.current.section, fragment }, false),
    [go],
  )

  const open = useCallback(
    (fragment: string) => go({ section: current.current.section, fragment }, true),
    [go],
  )
  const back = useCallback(
    (fallback = '') => {
      if (framed && pushed.current > 0) window.history.back()
      else go({ section: current.current.section, fragment: fallback }, false)
    },
    [framed, go],
  )

  return { ...route, navigate, setFragment, open, back }
}
