import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ErrorBoundary } from './components/ErrorBoundary'
import { RuntimeBanner } from './components/RuntimeBanner'
import { SyncBanner } from './components/SyncBanner'
import { ContactList } from './components/ContactList'
import { ContactDetail } from './components/ContactDetail'
import { DuplicatesView } from './components/DuplicatesView'
import { InvitesView } from './components/InvitesView'
import { ProfileView } from './components/ProfileView'
import { LiveProvider } from './lib/live'

type Tab = 'contacts' | 'invites' | 'duplicates' | 'profile'

const TABS: readonly Tab[] = ['contacts', 'invites', 'duplicates', 'profile']

// Contacts on the web (web-app-suite.md Wave 1, run B1). No backend of its own: every view reads
// and writes through the apex bridge (`lib/proxy.ts`), and the two live streams are subscribed
// once, here, by `LiveProvider`.
export default function App() {
  const { t } = useTranslation()
  const [tab, setTab] = useState<Tab>('contacts')
  const [openPerson, setOpenPerson] = useState<string | null>(null)

  return (
    <LiveProvider>
      <div className="min-h-screen bg-surface">
        <main className="mx-auto max-w-3xl px-6 py-10">
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
                  setOpenPerson(null)
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
          <SyncBanner />

          {/* Keyed by tab (and contact), so a crashed view recovers on navigation. */}
          <ErrorBoundary key={`${tab}:${openPerson ?? ''}`}>
            {tab === 'contacts' &&
              (openPerson ? (
                <ContactDetail personId={openPerson} onBack={() => setOpenPerson(null)} />
              ) : (
                <ContactList onOpen={setOpenPerson} />
              ))}
            {tab === 'invites' && <InvitesView />}
            {tab === 'duplicates' && <DuplicatesView />}
            {tab === 'profile' && <ProfileView />}
          </ErrorBoundary>
        </main>
      </div>
    </LiveProvider>
  )
}
