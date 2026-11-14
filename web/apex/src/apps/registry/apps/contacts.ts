// Contacts — the second app on its own origin (web-app-suite.md Wave 1, run B1). Everything the
// apex knows about it: origin, gateway allowlist, route, Apps-section entry.
import type { ProxyMethodAllowlist } from '@tenshu/sdk/bridge'
import { appOrigin, SESSION_METHODS, type HostedApp } from '../types'

declare global {
  interface ImportMetaEnv {
    /** The Contacts app's origin (see `apps/registry/apps/contacts.ts`). */
    readonly VITE_CONTACTS_ORIGIN?: string
  }
}

// Exactly the methods `web/contacts` calls (its `ProxyMethod` table), plus the sync-health stream
// its offline banner reads. ⚠ Exact names, not `contact.*`: a prefix would also grant the
// handshake ops Contacts never runs (`redeem`, `awaitAck` — joiner-side verified minting —,
// `listInvites`, `connected`, `pending`) and every `contact.*` handler added later.
// ⚠ Nothing of Drive's: no `fs.*`, no `membership.*`, no `sharedFile.*`.
const CONTACTS_METHODS: ProxyMethodAllowlist = [
  ...SESSION_METHODS,
  'contact.list',
  'contact.list.observe',
  'contact.get',
  'contact.get.observe',
  'contact.ownProfile',
  'contact.ownProfile.observe',
  'contact.setOwnDisplayName',
  'contact.create',
  'contact.rename',
  'contact.delete',
  'contact.unlinkIdentity',
  'contact.merge',
  'contact.relinkIdentity',
  'contact.clearAvatar',
  'contact.saveDetails',
  'contact.importVcards',
  'contact.exportVcard',
  'contact.sas',
  'contact.setVerified',
  'contact.createInvite',
  'contact.revokeInvite',
  'contact.outstandingInvites',
  'contact.pendingConfirmations',
  'contact.complete',
  'sync.health.observe',
]

const PROD_ORIGIN = 'https://contacts.tenshu.app'

export const contacts: HostedApp = {
  id: 'contacts',
  order: 20,
  title: 'Contacts',
  prodOrigin: PROD_ORIGIN,
  envName: 'VITE_CONTACTS_ORIGIN',
  devPort: 5175,
  origin: () => appOrigin(import.meta.env.VITE_CONTACTS_ORIGIN, PROD_ORIGIN, 5175),
  methods: CONTACTS_METHODS,
  routePath: 'contacts/*',
  sections: ['', 'invites', 'duplicates', 'profile'],
  // ⚠ The permissions listed here are DISPLAY data, like Drive's — what the app can actually call
  // is `methods` above (F5).
  manifest: {
    id: 'contacts',
    label: 'Contacts',
    description: 'The people you know, end-to-end encrypted.',
    version: '0.1.0',
    launchPath: '/contacts',
    permissions: [
      {
        id: 'contacts.read',
        label: 'Read your contacts',
        description: 'See the people in your address book and how far you have verified them.',
        state: 'granted',
        lastUsedAtMs: null,
      },
      {
        id: 'contacts.write',
        label: 'Manage your contacts',
        description: 'Add, edit, combine, verify and remove contacts, and send invitations.',
        state: 'granted',
        lastUsedAtMs: null,
      },
    ],
    services: [
      {
        id: 'contact',
        label: 'ContactService',
        reason: 'Stores your contacts and their keys, encrypted client-side.',
      },
      {
        id: 'preference',
        label: 'PreferenceService',
        reason: 'Keeps the name people see when you invite them.',
      },
    ],
  },
}

export default contacts
