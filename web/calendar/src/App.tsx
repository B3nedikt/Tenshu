import { useTranslation } from 'react-i18next'
import { AppLayout } from '@tenshu/app-kit/ui'
import { useAppRoute } from '@tenshu/app-kit/route'
import { ErrorBoundary } from './components/ErrorBoundary'
import { RuntimeBanner } from './components/RuntimeBanner'
import { SyncBanner } from './components/SyncBanner'
import { CalendarView } from './components/CalendarView'
import { CalendarsView } from './components/CalendarsView'
import { SubscriptionsView } from './components/SubscriptionsView'
import { TransferView } from './components/TransferView'
import { LiveProvider } from './lib/live'

type Tab = 'calendar' | 'calendars' | 'subscriptions' | 'transfer'

const SECTIONS: readonly Tab[] = ['calendar', 'calendars', 'subscriptions']
// Import & export is a utility, not a place to read the book: it sits at the sidebar's foot.
const FOOTER: readonly Tab[] = ['transfer']
// Every section is also a URL path (web-apps-layout.md §3.4): `calendar` is `/`, the rest `/<id>` —
// they must equal the apex registry's `sections`. Module-level: its identity is an effect dependency.
const ROUTES: readonly Tab[] = [...SECTIONS, ...FOOTER]

// Calendar on the web (web-app-suite.md Wave 2, run B). No backend of its own: every view reads and
// writes through the apex bridge (`lib/proxy.ts`), and the book-wide streams are subscribed once,
// here, by `LiveProvider`. ⚠⚠ Not on the web, by decision (calendar-app.md §Web scope): reminder
// firing, feed fetching, a first-run "Personal" seed, device calendars and widgets.
//
// The shell is the shared `AppLayout` (web-apps-layout.md §3.1): no headline — the apex top bar
// names the app — and the month/week grid takes the full width; the list screens keep a reading
// width.
export default function App() {
  const { t } = useTranslation()
  const { section: tab, navigate } = useAppRoute(ROUTES, 'calendar')
  const item = (id: Tab) => ({ id, label: t(`tabs.${id}`) })

  return (
    <LiveProvider>
      <AppLayout
        navLabel={t('app.sections')}
        nav={SECTIONS.map(item)}
        navFooter={FOOTER.map(item)}
        active={tab}
        onNavigate={navigate}
        banners={
          <>
            {/* First: in a tab without the runtime, every other read fails too. */}
            <RuntimeBanner />
            <SyncBanner />
          </>
        }
      >
        {/* Keyed by tab, so a crashed view recovers on navigation and the shell stays usable. */}
        <ErrorBoundary key={tab}>
          {tab === 'calendar' && <CalendarView />}
          {tab !== 'calendar' && (
            <div className="max-w-3xl">
              {tab === 'calendars' && <CalendarsView />}
              {tab === 'subscriptions' && <SubscriptionsView />}
              {tab === 'transfer' && <TransferView />}
            </div>
          )}
        </ErrorBoundary>
      </AppLayout>
    </LiveProvider>
  )
}
