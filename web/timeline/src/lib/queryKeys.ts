// Central registry of TanStack Query keys — the app-kit's `defineQueryKeys`, which also merges in
// its session keys (`runtimeLease`): every query, every `setQueryData` from a push stream and every
// invalidation names its key through this factory, so a key typed slightly differently at one end
// cannot silently detach the cache from its updates.
import { defineQueryKeys } from '@tenshu/app-kit/query'

export const queryKeys = defineQueryKeys({
  // The two pushed streams (`lib/live.tsx`).
  feed: () => ['feed'] as const,
  places: () => ['places'] as const,
  // Detail reads: snapshot + refetch (the SDK has no push form for them).
  detail: () => ['detail'] as const,
  moment: (momentId: string) => ['detail', 'moment', momentId] as const,
  trip: (tripId: string) => ['detail', 'trip', tripId] as const,
  place: (placeId: string) => ['detail', 'place', placeId] as const,
  // A map's route POINTS: one entry per leg (that leg's traces, `+`-joined), in drawing order.
  // ⚠ Points only — never the legs' modes, which this key does not name (`RouteMap`).
  route: (legs: readonly string[]) => ['detail', 'route', ...legs] as const,
})
