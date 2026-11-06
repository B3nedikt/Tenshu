// Central registry of TanStack Query keys — the app-kit's `defineQueryKeys`, which also merges in
// its session keys (`runtimeLease`): every query, every `setQueryData` from a push stream and every
// invalidation names its key through this factory, so a key typed slightly differently at one end
// cannot silently detach the cache from its updates.
import { defineQueryKeys } from '@tenshu/app-kit/query'

export const queryKeys = defineQueryKeys({
  // The two pushed streams (`lib/live.tsx`).
  history: () => ['history'] as const,
  grants: () => ['grants'] as const,
  // A conversation's turns, keyset-paged newest first; refetched when its `turnCount` moves.
  turns: (conversationId: string) => ['turns', conversationId] as const,
})
