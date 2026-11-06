import { useTranslation } from 'react-i18next'
import { RuntimeLeaseBanner } from '@tenshu/app-kit/ui'
import { runtimeLease, runtimeUseHere } from '../lib/proxy'

export { STILL_CONNECTING_AFTER_MS } from '@tenshu/app-kit/ui'

// One runtime per origin (docs/web/architecture.md "Cross-tab") — the shared banner with Contacts'
// copy. When another tab runs Tenshu every call this tab makes is refused; this says why, and
// offers the way out.
export function RuntimeBanner() {
  const { t } = useTranslation()
  return (
    <RuntimeLeaseBanner
      lease={runtimeLease}
      useHere={runtimeUseHere}
      copy={{
        connectingTitle: t('runtime.connectingTitle'),
        connectingBody: t('runtime.connectingBody'),
        elsewhereTitle: t('runtime.elsewhereTitle'),
        elsewhereBody: t('runtime.elsewhereBody'),
        moving: t('runtime.moving'),
        movingShort: t('runtime.movingShort'),
        useHere: t('runtime.useHere'),
      }}
    />
  )
}
