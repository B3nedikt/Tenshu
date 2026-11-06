import { useTranslation } from 'react-i18next'
import type { Connectivity } from '@tenshu/sdk/syncHealth'
import { SyncHealthNotice } from '@tenshu/app-kit/ui'
import { subscribeSyncHealth } from '../lib/proxy'

// Whether this device can sync — the app-kit's small banner over `sync.health.observe`. Calendar
// has no content plane, so only the states that mean "your edits stay here for now" get a banner;
// nothing for 'online' or the benign 'deferred'.
const NOTICE: Partial<Record<Connectivity, string>> = {
  offline: 'sync.offline',
  needs_attention: 'sync.needsAttention',
  locked: 'sync.locked',
  device_unrecognized: 'sync.deviceUnrecognized',
}

export function SyncBanner() {
  const { t } = useTranslation()
  return (
    <SyncHealthNotice
      subscribe={subscribeSyncHealth}
      message={(connectivity) => {
        const key = NOTICE[connectivity]
        return key ? t(key) : null
      }}
    />
  )
}
