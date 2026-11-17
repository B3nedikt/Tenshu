// The overview's widgets (docs/proposals/web-apex-home.md §4.12): pure helpers over the stored list.
//
// The stored list holds tokens, in order:
//   `apps`              the "Your apps" widget (the dock)
//   `app:<id>`          an app's preview card (its `glance`)
//   `hidden:<widget>`   a default widget the user removed — so it is not derived back
// ⚠⚠ Two lists, never one: installing an app is the shown-apps list (`shownApps.ts`); a widget is
// only where it is drawn. Removing a widget never uninstalls, and uninstalling an app hides its
// widget without forgetting its place.
//
// The default (D-W1): `[]` = never edited → "Your apps" first, then one card per installed app that
// has a glance, in the dock's order. An edited list keeps that rule for anything it does not
// MENTION — so an app installed later still gets its card at the end, while a removed widget (a
// `hidden:` entry) stays removed. ⚠ Tokens the apex does not know (a newer client's clock widget)
// are kept in place on every write, never dropped.
import type { TFunction } from 'i18next'
import { appLabel } from '../apps/appLabel'
import type { HostedApp } from '../apps/registry'

export const APPS_WIDGET = 'apps'
const APP_PREFIX = 'app:'
const HIDDEN_PREFIX = 'hidden:'

export type Widget =
  | { readonly kind: 'apps'; readonly key: string }
  | { readonly kind: 'app'; readonly key: string; readonly app: HostedApp }

/** The edit bar's Add widget button — where focus goes when the last widget is removed. */
export const ADD_WIDGET_ID = 'overview-add-widget'

export function widgetLabel(t: TFunction, widget: Widget): string {
  return widget.kind === 'apps' ? t('overview.appsHeading') : appLabel(t, widget.app)
}

export const appWidgetKey = (appId: string) => `${APP_PREFIX}${appId}`

const isHidden = (token: string) => token.startsWith(HIDDEN_PREFIX)
const hiddenKey = (token: string) => token.slice(HIDDEN_PREFIX.length)

/** Every widget key the stored list names, whether shown or removed. */
function mentioned(stored: readonly string[]): Set<string> {
  return new Set(stored.map(t => (isHidden(t) ? hiddenKey(t) : t)))
}

/** The token list with the default's unmentioned widgets filled in — what every edit starts from. */
function materialized(stored: readonly string[], installed: readonly HostedApp[]): string[] {
  const named = mentioned(stored)
  const tokens = [...stored]
  if (!named.has(APPS_WIDGET)) tokens.unshift(APPS_WIDGET)
  for (const app of installed) {
    const key = appWidgetKey(app.id)
    if (app.glance !== undefined && !named.has(key)) tokens.push(key)
  }
  return tokens
}

/** The widget [token] draws as, or null: removed, unknown, or an app not installed (or cardless). */
function resolve(token: string, installed: readonly HostedApp[]): Widget | null {
  if (token === APPS_WIDGET) return { kind: 'apps', key: token }
  if (!token.startsWith(APP_PREFIX)) return null
  const app = installed.find(a => a.id === token.slice(APP_PREFIX.length))
  return app?.glance !== undefined ? { kind: 'app', key: token, app } : null
}

/** The widgets to draw, in order. [installed] is the shown apps, in the dock's order. */
export function effectiveWidgets(stored: readonly string[], installed: readonly HostedApp[]): Widget[] {
  return materialized(stored, installed).flatMap(token => resolve(token, installed) ?? [])
}

/** Widgets that could be added: "Your apps" and installed apps' cards not drawn now. */
export function addableWidgets(stored: readonly string[], installed: readonly HostedApp[]): Widget[] {
  const drawn = new Set(effectiveWidgets(stored, installed).map(w => w.key))
  const all = [APPS_WIDGET, ...installed.map(app => appWidgetKey(app.id))]
  return all.flatMap(key => (drawn.has(key) ? [] : (resolve(key, installed) ?? [])))
}

/** [key] removed; recorded as `hidden:` so the default does not bring it back. */
export function withWidgetRemoved(stored: readonly string[], installed: readonly HostedApp[], key: string): string[] {
  const tokens = materialized(stored, installed).filter(t => t !== key && t !== `${HIDDEN_PREFIX}${key}`)
  return [...tokens, `${HIDDEN_PREFIX}${key}`]
}

/** [key] drawn again — at [index] among the drawn widgets (an undo), else at the end. */
export function withWidgetAdded(
  stored: readonly string[],
  installed: readonly HostedApp[],
  key: string,
  index?: number,
): string[] {
  const tokens = materialized(stored, installed).filter(t => t !== key && t !== `${HIDDEN_PREFIX}${key}`)
  const drawn = tokens.filter(t => resolve(t, installed) !== null)
  const before = index === undefined ? undefined : drawn[index]
  const at = before === undefined ? tokens.length : tokens.indexOf(before)
  tokens.splice(at, 0, key)
  return tokens
}

/** [key] swapped with its drawn neighbour [delta] (±1) away; tokens in between keep their place. */
export function withWidgetMovedBy(
  stored: readonly string[],
  installed: readonly HostedApp[],
  key: string,
  delta: number,
): string[] {
  const tokens = materialized(stored, installed)
  const drawn = tokens.filter(t => resolve(t, installed) !== null)
  const from = drawn.indexOf(key)
  const other = drawn[from + delta]
  if (from < 0 || other === undefined) return tokens
  const a = tokens.indexOf(key)
  const b = tokens.indexOf(other)
  tokens[a] = other
  tokens[b] = key
  return tokens
}

/** [key] dropped onto [target]: it takes the target's place, the rest shift towards where it was. */
export function withWidgetMovedTo(
  stored: readonly string[],
  installed: readonly HostedApp[],
  key: string,
  target: string,
): string[] {
  const tokens = materialized(stored, installed)
  const from = tokens.indexOf(key)
  const to = tokens.indexOf(target)
  if (from < 0 || to < 0 || from === to) return tokens
  tokens.splice(from, 1)
  tokens.splice(tokens.indexOf(target) + (from < to ? 1 : 0), 0, key)
  return tokens
}
