import { useTranslation } from 'react-i18next'
import { AppLayout } from '@tenshu/app-kit/ui'
import { useAppRoute } from '@tenshu/app-kit/route'
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

const SECTIONS: readonly Tab[] = ['contacts', 'invites', 'duplicates']
// "You" — the user's own profile — is the settings-like section: it sits at the sidebar's foot.
const FOOTER: readonly Tab[] = ['profile']
// Every section, for the route (web-apps-layout.md §3.4). `contacts` is home (`/`); the others are
// `/<id>` and must equal the apex registry's `sections` for Contacts.
const ROUTE_SECTIONS: readonly Tab[] = [...SECTIONS, ...FOOTER]

// The open person lives in the fragment as `p=<personId>` — never in the path, which hosts log.
const PERSON_PREFIX = 'p='
const personOf = (fragment: string): string | null =>
  fragment.startsWith(PERSON_PREFIX) && fragment.length > PERSON_PREFIX.length
    ? fragment.slice(PERSON_PREFIX.length)
    : null

// Contacts on the web (web-app-suite.md Wave 1, run B1). No backend of its own: every view reads
// and writes through the apex bridge (`lib/proxy.ts`), and the two live streams are subscribed
// once, here, by `LiveProvider`.
//
// The shell is the shared `AppLayout` (web-apps-layout.md §3.1): no headline — the apex top bar
// names the app. An open person shows in the layout's pane beside the list (a bottom sheet when
// narrow), so the list stays in view.
export default function App() {
  const { t } = useTranslation()
  // The URL is the single source of truth for the section and the open person, so a reload, Back
  // or a shared link keeps both.
  const { section: tab, fragment, navigate, setFragment } = useAppRoute(ROUTE_SECTIONS, 'contacts')
  const openPerson = tab === 'contacts' ? personOf(fragment) : null
  const setOpenPerson = (personId: string | null) => setFragment(personId ? PERSON_PREFIX + personId : '')
  const item = (id: Tab) => ({ id, label: t(`nav.${id}`) })

  const pane =
    openPerson ? (
      // Keyed by contact, so a crashed detail recovers when another person is opened.
      <ErrorBoundary key={openPerson}>
        <ContactDetail personId={openPerson} onBack={() => setOpenPerson(null)} />
      </ErrorBoundary>
    ) : null

  return (
    <LiveProvider>
      <AppLayout
        navLabel={t('app.sections')}
        nav={SECTIONS.map(item)}
        navFooter={FOOTER.map(item)}
        active={tab}
        // A section change starts with no fragment, so it also closes the open person.
        onNavigate={(id) => navigate(id)}
        banners={
          <>
            {/* First: in a tab without the runtime, every other read fails too. */}
            <RuntimeBanner />
            <SyncBanner />
          </>
        }
        pane={pane}
        onPaneClose={() => setOpenPerson(null)}
        paneLabel={t('detail.pane')}
      >
        {/* Keyed by tab, so a crashed view recovers on navigation. A reading width: these are
            lists and forms, not grids. */}
        <ErrorBoundary key={tab}>
          <div className="max-w-3xl">
            {tab === 'contacts' && <ContactList onOpen={setOpenPerson} />}
            {tab === 'invites' && <InvitesView />}
            {tab === 'duplicates' && <DuplicatesView />}
            {tab === 'profile' && <ProfileView />}
          </div>
        </ErrorBoundary>
      </AppLayout>
    </LiveProvider>
  )
}
