// /control/home — the IA's notification-feed surface (per
// `control-ia.md` §3 + `web-control-plane.md` §4.1). Recovery banner
// renders above the feed when the phrase is unverified or stale.
import { useTranslation } from 'react-i18next'
import { NotificationFeed } from './NotificationFeed'
import { RecoveryBanner } from './RecoveryBanner'

export function HomePage() {
  const { t } = useTranslation('control')
  return (
    <div>
      <h1 className="text-2xl font-medium">{t('home.title')}</h1>
      <p className="mt-1 text-sm text-text-2">{t('home.subtitle')}</p>
      <div className="mt-6">
        <RecoveryBanner />
        <NotificationFeed />
      </div>
    </div>
  )
}
