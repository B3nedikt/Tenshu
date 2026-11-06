import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ErrorBoundary } from './components/ErrorBoundary'
import { RuntimeBanner } from './components/RuntimeBanner'
import { Dashboard } from './components/Dashboard'
import { HistoryView } from './components/HistoryView'
import { ProfileView } from './components/ProfileView'
import { WorkoutDetail } from './components/WorkoutDetail'
import { LiveProvider } from './lib/live'
import { TABS, type Tab } from './lib/nav'

// Fit on the web (web-app-suite.md Wave 3, run B). No backend of its own: every view reads and writes
// through the apex bridge (`lib/proxy.ts`), and the pushed goals / profile / data-source streams are
// subscribed once, here, by `LiveProvider` (which also provides the profile's units). Scope is
// `fit-app.md` §Web scope → Decided: no recording, no "Log a workout" form, no workout edit, no
// onboarding or wizard, no auto-detect promotion, no Browse categories, no `.FIT` or Takeout import.
export default function App() {
  const { t } = useTranslation()
  const [tab, setTab] = useState<Tab>('dashboard')
  // The workout History opened, if any; Back returns to History where it was.
  const [open, setOpen] = useState<string | null>(null)

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
                onClick={() => {
                  setTab(id)
                  setOpen(null)
                }}
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

          {/* Keyed by tab and screen, so a crashed view recovers on navigation. */}
          <ErrorBoundary key={`${tab}:${open ?? ''}`}>
            {open !== null ? (
              <WorkoutDetail workoutId={open} onBack={() => setOpen(null)} />
            ) : tab === 'dashboard' ? (
              <Dashboard />
            ) : tab === 'history' ? (
              <HistoryView onOpen={setOpen} />
            ) : (
              <ProfileView />
            )}
          </ErrorBoundary>
        </main>
      </div>
    </LiveProvider>
  )
}
