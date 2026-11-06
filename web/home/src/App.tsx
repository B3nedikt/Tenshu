import { useState } from 'react'
import { useTranslation } from 'react-i18next'
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
// evaluation — and no button, switch or menu that suggests any of them. The tabs are iOS's, less
// Settings (a placeholder on every platform: rooms, notifications and hub management are a later
// milestone, and the apex already carries the privacy policy).
export default function App() {
  const { t } = useTranslation()
  const [tab, setTab] = useState<Tab>('devices')

  return (
    <LiveProvider>
      <div className="min-h-screen bg-surface">
        <main className="mx-auto max-w-3xl px-4 py-10 sm:px-6">
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
                {t(`nav.${id}`)}
              </button>
            ))}
          </nav>

          {/* First: in a tab without the runtime, every other read fails too. */}
          <RuntimeBanner />

          {/* Keyed by tab, so a crashed view recovers on navigation. */}
          <ErrorBoundary key={tab}>
            {tab === 'devices' ? <DevicesView /> : tab === 'automations' ? <AutomationsView /> : <ActivityView />}
          </ErrorBoundary>
        </main>
      </div>
    </LiveProvider>
  )
}
