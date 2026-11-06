// TanStack Query plumbing every app shares: the client defaults and the query-key factory pattern
// (Drive's `lib/queryKeys.ts`, which Contacts copied).
import { QueryClient } from '@tanstack/react-query'

/**
 * Queries don't retry: the apex bridge fails transiently (no session yet, apex not ready) and a
 * retry only delays the inline error the views already render.
 */
export function createAppQueryClient(): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

// The keys of the app-kit's own queries (the lease banner).
export const sessionQueryKeys = {
  runtimeLease: () => ['runtimeLease'] as const,
}

type KeyFactory = (...args: never[]) => readonly unknown[]

/**
 * An app's central registry of query keys, with the app-kit's session keys merged in.
 *
 * Every query, every `setQueryData` from a push stream and every invalidation names its key
 * through this factory rather than spelling the array inline: keys are shared across files (a
 * query in one view, its invalidation in another), so a literal that drifts by one character
 * silently detaches the cache from its updates. Routing both ends through one function makes that
 * impossible.
 */
export function defineQueryKeys<K extends Record<string, KeyFactory>>(
  keys: K & { readonly [P in keyof typeof sessionQueryKeys]?: never },
): typeof sessionQueryKeys & K {
  return { ...sessionQueryKeys, ...keys }
}
