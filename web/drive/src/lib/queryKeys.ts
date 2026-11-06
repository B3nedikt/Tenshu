// Central registry of TanStack Query keys (the app-kit's `defineQueryKeys`, which also merges in
// its session keys — `runtimeLease`).
//
// Every query and every invalidation references a key through this factory
// rather than spelling out the array inline. Keys are shared across files —
// e.g. `contacts` is read in three views and `sharedFolders` is queried in one
// view but invalidated from the file browser — so a hand-typed literal that
// drifts by a character silently breaks cache invalidation. Routing both ends
// through one function makes that impossible.
import { defineQueryKeys } from '@tenshu/app-kit/query'

export const queryKeys = defineQueryKeys({
  files: (path: string) => ['files', path] as const,
  search: (query: string) => ['search', query] as const,
  hasLocalData: (path: string) => ['hasLocalData', path] as const,
  recent: () => ['recent'] as const,
  details: (path: string) => ['details', path] as const,
  activity: (path: string) => ['activity', path] as const,
  // ⚠ The SIZE is part of the key. It was `['preview', path]`, which is fine while only the 40 px
  // row icon is cached — and a trap the moment anything caches a second size for the same file
  // (X-5's lightbox asks for 2048). One key for two sizes serves whichever landed first.
  preview: (path: string, px: number) => ['preview', path, px] as const,
  storageStatus: () => ['storageStatus'] as const,
  syncHealth: () => ['syncHealth'] as const,
  contentTransfer: () => ['contentTransfer'] as const,
  schemaFence: () => ['schemaFence'] as const,
  shares: () => ['shares'] as const,
  shareMint: (path: string) => ['share-mint', path] as const,
  sharedFolders: () => ['shared-folders'] as const,
  sharedBrowse: (realmId: string, parentFileId: string | null) =>
    ['shared-browse', realmId, parentFileId] as const,
  sharedRealms: () => ['shared-realms'] as const,
  realmMembers: (realmId: string) => ['realm-members', realmId] as const,
  pendingGrants: () => ['pending-grants'] as const,
  contacts: () => ['contacts'] as const,
  contactInvites: () => ['contact-invites'] as const,
  contactInviteMint: () => ['contact-invite-mint'] as const,
})
