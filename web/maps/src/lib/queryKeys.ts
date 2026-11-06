// Central registry of TanStack Query keys — the app-kit's `defineQueryKeys`, which also merges in
// its session keys (`runtimeLease`): every query, every `setQueryData` from a push stream and every
// invalidation names its key through this factory, so a key typed slightly differently at one end
// cannot silently detach the cache from its updates.
import { defineQueryKeys } from '@tenshu/app-kit/query'

export const queryKeys = defineQueryKeys({
  // The pushed list (`lib/live.tsx`).
  places: () => ['places'] as const,
  // Timeline's last visits, for the list map's tie-break only (`lib/anchor.ts`).
  recency: () => ['recency'] as const,
  // Detail reads: snapshot + refetch (the SDK has no push form for them).
  detail: () => ['detail'] as const,
  place: (placeId: string) => ['detail', 'place', placeId] as const,
  visits: (placeId: string) => ['detail', 'visits', placeId] as const,
})
