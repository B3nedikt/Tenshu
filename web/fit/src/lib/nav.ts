// The app's tabs, the metric page Home's cards open, and the one detail screen a History row opens.
export type Tab = 'dashboard' | 'history' | 'profile'

export const TABS: readonly Tab[] = ['dashboard', 'history', 'profile']

// Every path the app routes to: the tabs, and `metric` (fit-web-parity.md §2.3) — a page under Home,
// not a sidebar entry. ⚠ Must equal the apex registry's `sections` for Fit.
export type Route = Tab | 'metric'
export const ROUTES: readonly Route[] = [...TABS, 'metric']

export type Open = (workoutId: string) => void
