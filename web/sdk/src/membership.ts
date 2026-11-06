// Membership entry point: the cross-user shared-realm grant
// (`docs/proposals/drive-web-mode-b-sharing.md` MB-2 / GR-1..GR-7). Backed by
// `Membership` on the Kotlin side (`:tenshu-sdk-js`) — the same SyncService /
// IdentityService ops the native apps run, dispatched through the apex runtime.

import * as bundle from './_bundle'

// A row from `Membership.members(realmId)` — the in-realm member roster.
export interface MemberItem {
  readonly memberId: string
  readonly displayName: string
  readonly contactFingerprint: string
  // 'owner' | 'member'.
  readonly role: string
}

// A quarantined inbound grant the poll discovered (`pollPendingGrants()`). The
// grantor's petname is resolved from contacts; only the fingerprint is needed.
export interface PendingGrantItem {
  readonly grantorFingerprint: string
  readonly discoveredAtMillis: number
}

// Operation surface: Promise-returning, safe as a TanStack Query `queryFn`.
export const Membership = bundle.Membership.getInstance()
