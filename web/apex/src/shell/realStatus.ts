// The production status source for the bell and the sync pill (web-apex-home.md §3.4, H5b).
// ⚠ Imports the Kotlin bundle, so only the runtime root imports this (`statusContext.ts`).
import { Notifications } from '@tenshu/sdk/notifications'
import { SyncHealth } from '@tenshu/sdk/syncHealth'
import { SyncStatus } from '@tenshu/sdk/syncStatus'
import type { StatusSource } from './statusContext'

export const realStatus: StatusSource = {
  observeRecent(limit, onEvents) {
    return Notifications.observeRecent(
      limit,
      result => onEvents(result.ok ? result.events : null),
      // The stream ended: say "could not load" rather than freeze on the last list.
      () => onEvents(null),
    )
  },
  observeSync(onRow, onLost) {
    return SyncHealth.observeUntilEnd(onRow, () => onLost())
  },
  syncNow() {
    return SyncStatus.syncNow()
  },
  observePaused(onPaused) {
    return SyncStatus.observePaused(onPaused)
  },
  setPaused(paused) {
    SyncStatus.setPaused(paused)
  },
}
