// `Membership` — the cross-user shared-realm grant.
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).

// Membership: the cross-user shared-realm grant (drive-web-mode-b-sharing.md
// MB-2 / GR-1..GR-7). Backed by SyncService + IdentityService ops dispatched
// through the apex in-process runtime.
interface NativeMemberItem {
  readonly memberId: string
  readonly displayName: string
  readonly contactFingerprint: string
  // 'owner' | 'member'.
  readonly role: string
}

interface NativePendingGrantItem {
  readonly grantorFingerprint: string
  readonly discoveredAtMillis: number
}

interface MembershipInstance {
  grant(realmId: string, personId: string, label: string): Promise<boolean>
  // Picks up + accepts a waiting grant. Resolves `{ ok: true, realmId }` with the joined realm
  // id, or `{ ok: false, error }`.
  // ⚠ Same: success always yields a realm id; the reason is what a refused grant needs to say.
  accept(grantorFingerprint: string): Promise<{ readonly ok: true; readonly realmId: string } | NativeFailure>
  members(realmId: string): Promise<NativeMemberItem[]>
  remove(realmId: string, memberId: string): Promise<boolean>
  pollPendingGrants(): Promise<NativePendingGrantItem[]>
  declinePendingGrant(grantorFingerprint: string): Promise<boolean>
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const Membership: { getInstance(): MembershipInstance }
}
