import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ErrorBoundary } from './components/ErrorBoundary'
import { RuntimeBanner } from './components/RuntimeBanner'
import { ChatView } from './components/ChatView'
import { HistoryView } from './components/HistoryView'
import { GrantsView } from './components/GrantsView'
import { LiveProvider } from './lib/live'

// Mia on the web (web-app-suite.md Wave 4, Mia run B). No backend of its own: every view reads and
// writes through the apex bridge (`lib/proxy.ts`), and the history and grants streams are subscribed
// once, here, by `LiveProvider`. The MVP (§2): text chat over the deterministic planner (no model,
// §8 item 8), the in-memory history, and the grants with their switches (§8, 2026-10-28).
// ⚠ Not here: voice, widget, assistant role, generative layer — and no action: a tab withholds every
// one, so "turn off the light" answers with why not.
type Tab = 'chat' | 'history' | 'grants'
const TABS: readonly Tab[] = ['chat', 'history', 'grants']

export default function App() {
  const { t } = useTranslation()
  const [tab, setTab] = useState<Tab>('chat')

  return (
    <LiveProvider>
      <div className="min-h-screen bg-surface">
        <main className="mx-auto max-w-3xl px-4 py-10 sm:px-6">
          <header className="mb-6">
            <h1 className="text-2xl font-semibold tracking-tight text-text">{t('app.title')}</h1>
            <p className="mt-1 text-sm text-text-2">{t('app.tagline')}</p>
          </header>

          {/* First: in a tab without the runtime, every other read fails too. */}
          <RuntimeBanner />

          <nav role="tablist" aria-label={t('tabs.label')} className="mb-6 flex gap-1 border-b border-line">
            {TABS.map((id) => (
              <button
                key={id}
                type="button"
                role="tab"
                aria-selected={tab === id}
                onClick={() => setTab(id)}
                className={
                  tab === id
                    ? '-mb-px border-b-2 border-primary px-3 py-2 text-sm font-medium text-text'
                    : 'px-3 py-2 text-sm text-text-2 hover:text-text'
                }
              >
                {t(`tabs.${id}`)}
              </button>
            ))}
          </nav>

          {/* Keyed by tab, so a crashed view recovers on navigation and the shell stays usable. */}
          <ErrorBoundary key={tab}>
            {tab === 'chat' && <ChatView />}
            {tab === 'history' && <HistoryView />}
            {tab === 'grants' && <GrantsView />}
          </ErrorBoundary>
        </main>
      </div>
    </LiveProvider>
  )
}
