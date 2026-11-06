import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ErrorBoundary } from './components/ErrorBoundary'
import { RuntimeBanner } from './components/RuntimeBanner'
import { SyncBanner } from './components/SyncBanner'
import { CalendarView } from './components/CalendarView'
import { CalendarsView } from './components/CalendarsView'
import { SubscriptionsView } from './components/SubscriptionsView'
import { TransferView } from './components/TransferView'
import { LiveProvider } from './lib/live'

type Tab = 'calendar' | 'calendars' | 'subscriptions' | 'transfer'

const TABS: readonly Tab[] = ['calendar', 'calendars', 'subscriptions', 'transfer']

// Calendar on the web (web-app-suite.md Wave 2, run B). No backend of its own: every view reads and
// writes through the apex bridge (`lib/proxy.ts`), and the book-wide streams are subscribed once,
// here, by `LiveProvider`. ⚠⚠ Not on the web, by decision (calendar-app.md §Web scope): reminder
// firing, feed fetching, a first-run "Personal" seed, device calendars and widgets.
export default function App() {
  const { t } = useTranslation()
  const [tab, setTab] = useState<Tab>('calendar')

  return (
    <LiveProvider>
      <div className="min-h-screen bg-surface">
        <main className="mx-auto max-w-6xl px-6 py-10">
          <header className="mb-6">
            <h1 className="text-2xl font-semibold tracking-tight text-text">{t('app.title')}</h1>
            <p className="mt-1 text-sm text-text-2">{t('app.tagline')}</p>
          </header>

          <nav className="mb-5 flex gap-1 border-b border-line">
            {TABS.map((id) => (
              <button
                key={id}
                type="button"
                onClick={() => setTab(id)}
                aria-current={tab === id ? 'page' : undefined}
                className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium ${
                  tab === id ? 'border-primary text-primary' : 'border-transparent text-text-2 hover:text-text'
                }`}
              >
                {t(`tabs.${id}`)}
              </button>
            ))}
          </nav>

          {/* First: in a tab without the runtime, every other read fails too. */}
          <RuntimeBanner />
          <SyncBanner />

          {/* Keyed by tab, so a crashed view recovers on navigation and the shell stays usable. */}
          <ErrorBoundary key={tab}>
            {tab === 'calendar' && <CalendarView />}
            {tab === 'calendars' && <CalendarsView />}
            {tab === 'subscriptions' && <SubscriptionsView />}
            {tab === 'transfer' && <TransferView />}
          </ErrorBoundary>
        </main>
      </div>
    </LiveProvider>
  )
}
