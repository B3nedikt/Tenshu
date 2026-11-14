// The overview's shown set (docs/proposals/web-apex-home.md §3.5): which apps the dock, the cards
// and the launcher list, in the user's order. Pure functions — the store and React live elsewhere.
//
// ⚠⚠ PRESENTATION ONLY. Hiding an app never uninstalls, deregisters or deletes anything, and never
// touches its permissions: nothing here may call `AppRegistry.removeFromAllDevices` or the gateway.
import type { HostedApp } from '../apps/registry'

/** Always shown, enforced where the list is READ — one bad stored row must not hide it. */
export const ALWAYS_SHOWN = 'drive'

/** D1: a realm that has never customised shows these, in this order. Nothing is written for it. */
export const DEFAULT_SHOWN: readonly string[] = [ALWAYS_SHOWN, 'photos']

/**
 * The list the user is effectively looking at, as ids: the stored one, or the default when nothing
 * is stored (`[]`); Drive put first when missing; repeats dropped.
 * ⚠ Unknown ids (an app a newer client added) are KEPT, so a write built from this never drops them.
 */
export function effectiveIds(stored: readonly string[]): string[] {
  const base = stored.length === 0 ? DEFAULT_SHOWN : stored
  const ids = [...new Set(base)]
  return ids.includes(ALWAYS_SHOWN) ? ids : [ALWAYS_SHOWN, ...ids]
}

/** The shown apps this build knows, in the user's order (unknown ids skipped, not dropped). */
export function shownApps(stored: readonly string[], registry: readonly HostedApp[]): HostedApp[] {
  const byId = new Map(registry.map(app => [app.id, app]))
  return effectiveIds(stored).flatMap(id => {
    const app = byId.get(id)
    return app ? [app] : []
  })
}

/** The registered apps not shown, in registry order — the dialog's "More apps". */
export function hiddenApps(stored: readonly string[], registry: readonly HostedApp[]): HostedApp[] {
  const shown = new Set(effectiveIds(stored))
  return registry.filter(app => !shown.has(app.id))
}

/** The list to write after adding [appId] (appended at the end). */
export function withAdded(stored: readonly string[], appId: string): string[] {
  const ids = effectiveIds(stored)
  return ids.includes(appId) ? ids : [...ids, appId]
}

/** The list to write after hiding [appId]. Drive cannot be hidden: the list comes back unchanged. */
export function withRemoved(stored: readonly string[], appId: string): string[] {
  const ids = effectiveIds(stored)
  return appId === ALWAYS_SHOWN ? ids : ids.filter(id => id !== appId)
}

/**
 * Whether [app] keeps its files in Drive — shown as "Uses Drive". Derived from what the app may
 * actually call (any `fs.*` method on its allowlist), so it cannot drift from the registry.
 */
export function usesDrive(app: HostedApp): boolean {
  return app.id !== ALWAYS_SHOWN && app.methods.some(method => method.startsWith('fs.'))
}

/**
 * The list to write after reordering the VISIBLE apps to [visibleOrder] (Customize mode).
 * ⚠ Ids this build does not know keep their slots: only the known apps' slots are refilled, in the
 * new order, so a reorder made here never drops or shuffles an app a newer client added.
 */
export function withVisibleOrder(
  stored: readonly string[],
  registry: readonly HostedApp[],
  visibleOrder: readonly string[],
): string[] {
  const known = new Set(registry.map(app => app.id))
  const queue = [...visibleOrder]
  return effectiveIds(stored).map(id => (known.has(id) ? (queue.shift() ?? id) : id))
}

/** The list after moving [appId] [delta] places among the visible apps (−1 earlier, +1 later). */
export function withMovedBy(
  stored: readonly string[],
  registry: readonly HostedApp[],
  appId: string,
  delta: number,
): string[] {
  const visible = shownApps(stored, registry).map(app => app.id)
  const from = visible.indexOf(appId)
  const to = from + delta
  if (from < 0 || to < 0 || to >= visible.length) return effectiveIds(stored)
  visible.splice(from, 1)
  visible.splice(to, 0, appId)
  return withVisibleOrder(stored, registry, visible)
}

/** The list after dropping [appId] onto [targetId]'s place among the visible apps (drag and drop). */
export function withMovedTo(
  stored: readonly string[],
  registry: readonly HostedApp[],
  appId: string,
  targetId: string,
): string[] {
  const visible = shownApps(stored, registry).map(app => app.id)
  const from = visible.indexOf(appId)
  const to = visible.indexOf(targetId)
  if (from < 0 || to < 0 || from === to) return effectiveIds(stored)
  return withMovedBy(stored, registry, appId, to - from)
}

/**
 * Undo of a hide: [appId] back at [index] of the list as it is NOW — not the whole old list, which
 * would also revert anything changed since (another browser, another edit in between).
 */
export function withRestored(stored: readonly string[], appId: string, index: number): string[] {
  const ids = effectiveIds(stored).filter(id => id !== appId)
  ids.splice(Math.min(Math.max(index, 0), ids.length), 0, appId)
  return ids
}
