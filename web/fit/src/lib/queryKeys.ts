// Central registry of TanStack Query keys — the app-kit's `defineQueryKeys`, which also merges in
// its session keys (`runtimeLease`): every query, every `setQueryData` from a push stream and every
// invalidation names its key through this factory, so a key typed slightly differently at one end
// cannot silently detach the cache from its updates.
import { defineQueryKeys } from '@tenshu/app-kit/query'

export const queryKeys = defineQueryKeys({
  // The three pushed streams (`lib/live.tsx`).
  goals: () => ['goals'] as const,
  profile: () => ['profile'] as const,
  dataSources: () => ['dataSources'] as const,
  // The dashboard's window, pushed per window (`Dashboard.tsx`).
  days: (from: string, to: string) => ['days', from, to] as const,
  // History's keyset pages (`lib/history.ts`). ⚠ One infinite query; a change re-reads its head.
  history: () => ['history'] as const,
  // Detail: a snapshot, invalidated when History's change signal moves.
  workout: (workoutId: string) => ['workout', workoutId] as const,
  workouts: () => ['workout'] as const,
})
