// Central registry of TanStack Query keys — the app-kit's `defineQueryKeys`, which also merges in
// its session keys (`runtimeLease`): every query, every `setQueryData` from a push stream and every
// invalidation names its key through this factory, so a key typed slightly differently at one end
// cannot silently detach the cache from its updates.
import { defineQueryKeys } from '@tenshu/app-kit/query'

export const queryKeys = defineQueryKeys({
  // Fed by the `contact.list.observe` push stream (`useLiveContacts`), seeded by `contact.list`.
  contacts: () => ['contacts'] as const,
  ownProfile: () => ['ownProfile'] as const,
  sas: (fingerprint: string) => ['sas', fingerprint] as const,
  outstandingInvites: () => ['outstandingInvites'] as const,
  pendingConfirmations: () => ['pendingConfirmations'] as const,
})
