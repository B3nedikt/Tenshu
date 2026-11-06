// `storage.*` — the storage-provision snapshot.
import { Storage } from '@tenshu/sdk/storage'
import type { GatewayModule } from './module'

export const storageGateway: GatewayModule = {
  handlers: {
    // web-storage-status.md Bucket 1 — the storage-provision snapshot the
    // upload/share entry points gate on. Read-only; returns null until the
    // runtime has derived a status. Mirrors `StorageService.status()`.
    'storage.status': async () => {
      return Storage.status()
    },
  },
}
