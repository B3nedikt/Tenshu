import { useTranslation } from 'react-i18next'
import { StreamNotice as KitStreamNotice } from '@tenshu/app-kit/ui'

// Shown when a push stream ended (run-A review fix): what is on screen may be stale, and saying so
// with a way to reconnect beats a list that silently stopped updating. The app-kit's notice with
// Contacts' copy.
export function StreamNotice({ live }: { live: { streamError: Error | null; retry: () => void } }) {
  const { t } = useTranslation()
  return <KitStreamNotice live={live} copy={{ stopped: t('stream.stopped'), reconnect: t('stream.reconnect') }} />
}
