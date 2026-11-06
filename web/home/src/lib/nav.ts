// The app's tabs (iOS's, less the Settings placeholder — `App.tsx`).
export const TABS = ['devices', 'automations', 'activity'] as const
export type Tab = (typeof TABS)[number]
