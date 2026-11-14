// Drive — the first app on its own origin (ADR 0020; web-app-suite.md F2). Everything the apex knows
// about it: origin, gateway allowlist, route, Apps-section entry.
import type { ProxyMethodAllowlist } from '@tenshu/sdk/bridge'
import { appOrigin, SESSION_METHODS, type AppGlance, type HostedApp } from '../types'

declare global {
  interface ImportMetaEnv {
    /** The Drive app's origin (see `apps/registry/apps/drive.ts`). */
    readonly VITE_DRIVE_ORIGIN?: string
  }
}

const RECENT_USE_MS = Date.UTC(2026, 4, 8, 14, 30, 0)
const OLDER_USE_MS = Date.UTC(2026, 4, 5, 11, 12, 0)

// Exactly the methods `web/drive` calls today (its `ProxyMethod` table). Its handful of `contact.*`
// names are the invitation HANDSHAKE the sharing flow runs — not the address book: Drive may not
// rename, merge, delete or `setVerified` anybody.
// ⚠⚠ It CAN still mint a VERIFIED key: `contact.complete` / `contact.awaitAck` take `sasConfirmed`,
// and `true` creates the identity at VERIFIED / QR_SAS (CompleteContactHandshakeImplementation,
// AwaitInviteAckImplementation). Drive's QR+SAS flow needs exactly that, so XSS on Drive can still
// attach a key it controls to a contact as verified — the residual recorded in the threat model
// (T-CI-09b, web amendment). Do not describe this list as "Drive cannot verify".
const DRIVE_METHODS: ProxyMethodAllowlist = [
  ...SESSION_METHODS,
  'fs.list',
  'fs.recentFiles',
  'fs.search',
  'fs.hasLocalData',
  'fs.uploadFile',
  'fs.createFolder',
  'fs.rename',
  'fs.move',
  'fs.delete',
  'fs.details',
  'fs.activity',
  'fs.getData',
  'fs.getPreview',
  'fs.getLargePreview',
  'fs.openContentStream',
  'fs.readContentRange',
  'fs.closeContentStream',
  'shares.create',
  'shares.list',
  'shares.revoke',
  'storage.status',
  'sync.health.observe',
  'sync.health',
  'sync.contentTransfer',
  'sync.schemaFence',
  'sync.now',
  'contact.createInvite',
  'contact.revokeInvite',
  'contact.redeem',
  'contact.awaitAck',
  'contact.complete',
  'contact.listInvites',
  'contact.connected',
  'contact.pending',
  'membership.grant',
  'membership.accept',
  'membership.members',
  'membership.remove',
  'membership.pollPending',
  'membership.decline',
  'sharedFile.sharedWithMe',
  'sharedFile.browse',
  'sharedFile.readFile',
  'sharedFile.shareFolder',
  'sharedFile.sharedFolders',
]

const PROD_ORIGIN = 'https://drive.tenshu.app'

const RECENT_ROWS = 6

// The overview card (web-apex-home.md §3.6): recent files + the storage bar. Both methods are on
// DRIVE_METHODS above — the card's `call` refuses anything that is not.
const glance: AppGlance = {
  size: 'wide',
  subtitle: 'recents',
  async load(call) {
    // ⚠ The storage bar is garnish: its failure drops the bar, it does not fail the card.
    const [files, storage] = await Promise.all([
      call('fs.recentFiles', { limit: RECENT_ROWS + 4 }),
      call('storage.status').catch(() => null),
    ])
    const rows = (files as readonly RecentFile[])
      .filter(f => !f.isFolder)
      .slice(0, RECENT_ROWS)
      .map(f => {
        const name = f.path.slice(f.path.lastIndexOf('/') + 1)
        const dot = name.lastIndexOf('.')
        const ext = dot > 0 ? name.slice(dot + 1).toUpperCase() : ''
        return { title: name, badge: ext.length <= 4 ? ext : '', timeMs: f.lastModified, sizeBytes: f.size }
      })
    const s = storage as StorageStatus | null
    return {
      layout: 'list',
      rows,
      usage: s !== null && s.hasStorage ? { usedBytes: s.usedBytes, limitBytes: s.limitBytes } : null,
    }
  },
}

// The two answers the card reads — the bridge's `NativeFile` / `NativeStorageStatus`, narrowed.
interface RecentFile {
  readonly path: string
  readonly size: number
  readonly lastModified: number
  readonly isFolder: boolean
}
interface StorageStatus {
  readonly hasStorage: boolean
  readonly usedBytes: number
  readonly limitBytes: number
}

export const drive: HostedApp = {
  id: 'drive',
  order: 10,
  title: 'Drive',
  prodOrigin: PROD_ORIGIN,
  envName: 'VITE_DRIVE_ORIGIN',
  devPort: 5174,
  origin: () => appOrigin(import.meta.env.VITE_DRIVE_ORIGIN, PROD_ORIGIN, 5174),
  methods: DRIVE_METHODS,
  // The embedded app owns its own in-app navigation.
  routePath: 'drive/*',
  sections: ['', 'files', 'search', 'shared', 'incoming', 'people', 'settings'],
  glance,
  manifest: {
    id: 'drive',
    label: 'Drive',
    description: 'Your end-to-end encrypted file system.',
    version: '0.1.0',
    launchPath: '/drive',
    permissions: [
      {
        id: 'files.read',
        label: 'Read your files',
        description: 'Browse and open files synced to your realm.',
        state: 'granted',
        lastUsedAtMs: RECENT_USE_MS,
      },
      {
        id: 'files.write',
        label: 'Save and modify files',
        description: 'Create, rename, and edit files in your Drive volume.',
        state: 'granted',
        lastUsedAtMs: RECENT_USE_MS,
      },
      {
        id: 'notifications.show',
        label: 'Send notifications',
        description: 'Surface file activity and sync events on your devices.',
        state: 'askEachTime',
        lastUsedAtMs: OLDER_USE_MS,
      },
    ],
    services: [
      {
        id: 'storage',
        label: 'StorageService',
        reason: 'Stores file content and metadata, encrypted client-side.',
      },
      {
        id: 'notifications',
        label: 'NotificationService',
        reason: 'Delivers file-activity notifications to your other devices.',
      },
      {
        id: 'identity',
        label: 'IdentityService',
        reason: 'Reads which devices in your realm are authorised to sync Drive content.',
      },
    ],
  },
}

export default drive
