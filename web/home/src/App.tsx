import { useTranslation } from 'react-i18next'
import { AppLayout, type AppNavItem } from '@tenshu/app-kit/ui'
import { useAppRoute } from '@tenshu/app-kit/route'
import { ActivityView } from './components/ActivityView'
import { AutomationsView } from './components/AutomationsView'
import { DevicesView } from './components/DevicesView'
import { ErrorBoundary } from './components/ErrorBoundary'
import { RuntimeBanner } from './components/RuntimeBanner'
import { LiveProvider } from './lib/live'
import { TABS, type Tab } from './lib/nav'

// Home on the web (web-app-suite.md Wave 4, Home run B). No backend of its own: every view reads
// through the apex bridge (`lib/proxy.ts`), and the pushed devices / hub / automations streams are
// subscribed once, here, by `LiveProvider`.
//
// ⚠⚠ READ-ONLY, decided (web-app-suite.md §8 items 2 and 9): the spec is the iOS route-1 read-only Home
// (home-and-mia-apps.md §8.1). No device control, no automation editor, no pairing, no routine
// evaluation — and no button, switch or menu that suggests any of them. The sections are iOS's, less
// Settings (a placeholder on every platform: rooms, notifications and hub management are a later
// milestone, and the apex already carries the privacy policy) — so the sidebar has no footer.
//
// The shared layout (web-apps-layout.md §3.1): no headline (the apex top bar names the app), the
// sections in the sidebar, the content at full width.
export default function App() {
  const { t } = useTranslation()
  // The section is the URL path (web-apps-layout.md §3.4): `devices` is `/`, the rest `/<id>`.
  const { section: tab, navigate } = useAppRoute(TABS, 'devices')
  const nav: AppNavItem<Tab>[] = TABS.map((id) => ({ id, label: t(`nav.${id}`) }))

  return (
    <LiveProvider>
      <AppLayout
        navLabel={t('nav.label')}
        nav={nav}
        active={tab}
        onNavigate={navigate}
        // First: in a tab without the runtime, every other read fails too.
        banners={<RuntimeBanner />}
      >
        {/* Keyed by tab, so a crashed view recovers on navigation. */}
        <ErrorBoundary key={tab}>
          {tab === 'devices' ? <DevicesView /> : tab === 'automations' ? <AutomationsView /> : <ActivityView />}
        </ErrorBoundary>
      </AppLayout>
    </LiveProvider>
  )
}
