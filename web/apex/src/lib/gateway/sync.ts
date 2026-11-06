// `sync.*` — whether this device can sync, and the scheduler's one write.
import { SyncHealth } from '@tenshu/sdk/syncHealth'
import { SyncStatus } from '@tenshu/sdk/syncStatus'
import type { GatewayModule } from './module'

export const syncGateway: GatewayModule = {
  handlers: {
    // W3: whether this device can sync at all. The row has existed in the store since
    // Phase A (initSyncService calls initSyncHealth); nothing read it, so a browser could sit
    // in `device_unrecognized` for hours behind a normal-looking file list.
    'sync.health': async () => SyncHealth.status(),
    // W3 remainder — both are readers of rows the runtime already maintains.
    'sync.contentTransfer': async () => SyncStatus.contentTransfer(),
    'sync.schemaFence': async () => SyncStatus.schemaFence(),
    // The one write here: asks the scheduler to drain now. Resolves whether the KICK was
    // signalled, not whether the drain worked.
    'sync.now': async () => SyncStatus.syncNow(),
  },
  subscriptions: {
    // W12/W3 — the first consumer of the bridge's push channel.
    //
    // ⭐ Genuine push, not a callback-shaped poll: `SyncService.syncHealth()` has always been a
    // `Flow`, and `SyncHealth.status()` merely took its `first()`. The web snapshotted because
    // the BRIDGE could not carry a stream, not because the runtime could not produce one.
    //
    // ⚠ One collector per subscriber, by design. Two subdomain tabs get two flows; the runtime
    // dedupes downstream and the alternative — a shared collector with refcounting here — is
    // state this layer should not own.
    'sync.health.observe': (_params, emit) => SyncHealth.observe((row) => emit(row)),
  },
}
