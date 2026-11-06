import { useTranslation } from 'react-i18next'

// Where native links to *Offline maps* (§Web scope 3): the web manages no regions — no list, no
// sizes, no delete — so it says where that lives instead.
export function OfflineMapsNote() {
  const { t } = useTranslation()
  return <p className="text-xs text-text-2">{t('offline.note')}</p>
}
