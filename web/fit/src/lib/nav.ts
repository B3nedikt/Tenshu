// The app's three tabs and the one detail screen a History row opens.
export type Tab = 'dashboard' | 'history' | 'profile'

export const TABS: readonly Tab[] = ['dashboard', 'history', 'profile']

export type Open = (workoutId: string) => void
