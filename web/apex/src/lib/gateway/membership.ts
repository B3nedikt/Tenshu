// `membership.*` — the cross-user shared-realm grant.
import { Membership } from '@tenshu/sdk/membership'
import type { GatewayModule } from './module'
import { asRecord } from './params'

export const membershipGateway: GatewayModule = {
  handlers: {
    // MB-2 — cross-user shared-realm membership grant (GR-1..GR-7). The
    // inbound side (`pollPendingGrants`/`accept`/`decline`) + the `members`
    // read back the Drive "Shared with me" + Manage-access surfaces; `grant`/
    // `remove` are wired for MB-4 (they target a realm a folder-share creates).
    'membership.grant': async (params) => {
      const g = readGrant(params)
      return Membership.grant(g.realmId, g.personId, g.label)
    },
    'membership.accept': async (params) => {
      return Membership.accept(readFingerprint(params, 'membership.accept'))
    },
    'membership.members': async (params) => {
      return Membership.members(readRealmId(params, 'membership.members'))
    },
    'membership.remove': async (params) => {
      const r = readRemove(params)
      return Membership.remove(r.realmId, r.memberId)
    },
    'membership.pollPending': async () => {
      return Membership.pollPendingGrants()
    },
    'membership.decline': async (params) => {
      return Membership.declinePendingGrant(readFingerprint(params, 'membership.decline'))
    },
  },
}

// MB-2 membership validators.
function readGrant(params: unknown): { realmId: string; personId: string; label: string } {
  const r = asRecord(params)
  if (typeof r.realmId !== 'string' || typeof r.personId !== 'string') {
    throw new Error('membership.grant requires { realmId, personId }')
  }
  return {
    realmId: r.realmId,
    personId: r.personId,
    label: typeof r.label === 'string' ? r.label : '',
  }
}

function readRealmId(params: unknown, op: string): string {
  const v = asRecord(params).realmId
  if (typeof v !== 'string') throw new Error(`${op} requires { realmId }`)
  return v
}

function readFingerprint(params: unknown, op: string): string {
  const v = asRecord(params).grantorFingerprint
  if (typeof v !== 'string') throw new Error(`${op} requires { grantorFingerprint }`)
  return v
}

function readRemove(params: unknown): { realmId: string; memberId: string } {
  const r = asRecord(params)
  if (typeof r.realmId !== 'string' || typeof r.memberId !== 'string') {
    throw new Error('membership.remove requires { realmId, memberId }')
  }
  return { realmId: r.realmId, memberId: r.memberId }
}
