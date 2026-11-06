// The hub storage tile's staleness, on THIS app's clock (web-app-suite.md §4.H): `home.hubStorage`
// judges `stale` once, at read time, and nothing re-derives it while the rows stand still — a tab runs
// no ticker in the runtime. So the tile re-checks `now - freshAsOfMs > staleAfterMs` itself, on a
// render timer (`useNow`, `clock.ts`). ⚠ The timer only re-renders: it reads nothing, writes nothing, starts no
// work in the runtime.
import type { HomeHubStorage } from '@tenshu/sdk/home'

export function isStale(hub: HomeHubStorage, nowMs: number): boolean {
  // `hub.stale` too: a row the runtime already judged stale never reads fresh again.
  return hub.stale || nowMs - hub.freshAsOfMs > hub.staleAfterMs
}

// The moment the tile turns stale on its own: the first millisecond past `staleAfterMs`.
export function staleAtMs(hub: HomeHubStorage): number {
  return hub.freshAsOfMs + hub.staleAfterMs + 1
}
