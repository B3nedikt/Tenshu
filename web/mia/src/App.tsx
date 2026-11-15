import { useTranslation } from 'react-i18next'
import { AppLayout, type AppNavItem } from '@tenshu/app-kit/ui'
import { useAppRoute } from '@tenshu/app-kit/route'
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
//
// The shared layout (web-apps-layout.md §3.1): no headline (the apex top bar names the app), the
// sections in the sidebar — the grants, being settings, at its foot — and the content at full width,
// inside which every Mia view is text, so it keeps a reading column.
type Tab = 'chat' | 'history' | 'grants'
const SECTIONS: readonly Tab[] = ['chat', 'history']
const FOOTER: readonly Tab[] = ['grants']
// Every section, for the route (web-apps-layout.md §3.4): `chat` is home (`/`), the others `/<id>` —
// they must equal the apex registry's `sections` for Mia. Sections only: a question or an answer is
// the user's, and never goes in the URL.
const ROUTE_SECTIONS: readonly Tab[] = [...SECTIONS, ...FOOTER]

export default function App() {
  const { t } = useTranslation()
  const { section: tab, navigate } = useAppRoute(ROUTE_SECTIONS, 'chat')
  const item = (id: Tab): AppNavItem<Tab> => ({ id, label: t(`tabs.${id}`) })

  return (
    <LiveProvider>
      <AppLayout
        navLabel={t('tabs.label')}
        nav={SECTIONS.map(item)}
        navFooter={FOOTER.map(item)}
        active={tab}
        onNavigate={(id) => navigate(id)}
        // First: in a tab without the runtime, every other read fails too.
        banners={
          <div className="mx-auto w-full max-w-3xl">
            <RuntimeBanner />
          </div>
        }
      >
        <div className="mx-auto w-full max-w-3xl">
          {/* Keyed by tab, so a crashed view recovers on navigation and the shell stays usable. */}
          <ErrorBoundary key={tab}>
            {tab === 'chat' && <ChatView />}
            {tab === 'history' && <HistoryView />}
            {tab === 'grants' && <GrantsView />}
          </ErrorBoundary>
        </div>
      </AppLayout>
    </LiveProvider>
  )
}
