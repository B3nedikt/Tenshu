// The device name onboarding registers this browser under — "Chrome on macOS".
// See `lib/deviceName.ts` for why there is no step asking for one.
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { defaultDeviceName } from '../lib/deviceName'

export function useDefaultDeviceName(): string {
  const { t } = useTranslation('onboarding')
  return useMemo(
    () =>
      defaultDeviceName({
        format: (browser, os) => t('deviceName.browserOnOs', { browser, os }),
        fallback: t('deviceName.default'),
      }),
    [t],
  )
}
