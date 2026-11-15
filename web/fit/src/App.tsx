import { useTranslation } from 'react-i18next'
import { AppLayout } from '@tenshu/app-kit/ui'
import { useAppRoute } from '@tenshu/app-kit/route'
import { ErrorBoundary } from './components/ErrorBoundary'
import { RuntimeBanner } from './components/RuntimeBanner'
import { Dashboard } from './components/Dashboard'
import { HistoryView } from './components/HistoryView'
import { ProfileView } from './components/ProfileView'
import { WorkoutDetail } from './components/WorkoutDetail'
import { LiveProvider } from './lib/live'
import { TABS, type Tab } from './lib/nav'

// Profile holds goals, personal data and the sources — Fit's settings, so it sits at the sidebar's
// foot (web-apps-layout.md §3.1).
const FOOTER: readonly Tab[] = ['profile']
const SECTIONS = TABS.filter((id) => !FOOTER.includes(id))

// The open workout lives in History's fragment as `w=<workoutId>` (web-apps-layout.md §3.4) — never
// in the path, which hosts log, and never its title.
const WORKOUT_PREFIX = 'w='
const workoutOf = (fragment: string): string | null =>
  fragment.startsWith(WORKOUT_PREFIX) && fragment.length > WORKOUT_PREFIX.length
    ? fragment.slice(WORKOUT_PREFIX.length)
    : null

// Fit on the web (web-app-suite.md Wave 3, run B). No backend of its own: every view reads and writes
// through the apex bridge (`lib/proxy.ts`), and the pushed goals / profile / data-source streams are
// subscribed once, here, by `LiveProvider` (which also provides the profile's units). Scope is
// `fit-app.md` §Web scope → Decided: no recording, no "Log a workout" form, no workout edit, no
// onboarding or wizard, no auto-detect promotion, no Browse categories, no `.FIT` or Takeout import.
export default function App() {
  const { t } = useTranslation()
  // The URL is the single source of truth for the section and the open workout, so a reload, Back
  // or a shared link keeps both. `dashboard` is home (`/`); `TABS`' other ids must equal the apex
  // registry's `sections` for Fit. A section change starts with no fragment, so it closes the workout.
  const { section: tab, fragment, navigate, open: openDetail, back } = useAppRoute(TABS, 'dashboard')
  // The workout History opened, if any; Back returns to History where it was.
  const open = tab === 'history' ? workoutOf(fragment) : null
  // A workout detail takes over the History list, so opening it is a history entry: browser Back
  // closes it, and the detail's own Back steps the same history (web-apps-layout.md §4.2).
  const openWorkout = (workoutId: string) => openDetail(WORKOUT_PREFIX + workoutId)
  const item = (id: Tab) => ({ id, label: t(`nav.${id}`) })

  return (
    <LiveProvider>
      <AppLayout
        navLabel={t('nav.label')}
        nav={SECTIONS.map(item)}
        navFooter={FOOTER.map(item)}
        active={tab}
        onNavigate={(id) => navigate(id)}
        // First: in a tab without the runtime, every other read fails too.
        banners={<RuntimeBanner />}
      >
        {/* Keyed by tab and screen, so a crashed view recovers on navigation. */}
        <ErrorBoundary key={`${tab}:${open ?? ''}`}>
          {open !== null ? (
            <WorkoutDetail workoutId={open} onBack={() => back()} />
          ) : tab === 'dashboard' ? (
            <Dashboard />
          ) : tab === 'history' ? (
            <HistoryView onOpen={openWorkout} />
          ) : (
            <ProfileView />
          )}
        </ErrorBoundary>
      </AppLayout>
    </LiveProvider>
  )
}
