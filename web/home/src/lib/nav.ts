// The app's tabs (iOS's, less the Settings placeholder — `App.tsx`). Also its URL sections
// (web-apps-layout.md §3.4): `devices` is `/`, the rest must equal the apex registry's `sections`.
export const TABS = ['devices', 'automations', 'activity'] as const
export type Tab = (typeof TABS)[number]
