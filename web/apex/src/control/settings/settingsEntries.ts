// The Settings index's rows (SettingsPage.tsx), apart from the component so fast refresh keeps
// working (react-refresh/only-export-components).

export interface SettingsEntry {
  readonly id: 'devices' | 'backgroundIngest' | 'recovery'
  readonly to: string
  readonly glyph: string
}

// The native destinations that have a web page (RuntimeApp.tsx's routes).
// ⚠ Native's "Sync" opens its sync dashboard (devices + sync state); the web's nearest page is the
// device list under Data, which is where it lives in the web IA (`web-control-plane.md` §4.3).
// So the row is labelled "Devices", never native's "Sync & devices": it must not promise a sync
// dashboard the page does not have.
export const SETTINGS_ENTRIES: readonly SettingsEntry[] = [
  { id: 'devices', to: '/control/data/devices', glyph: '⟳' },
  { id: 'backgroundIngest', to: '/control/data/background-ingest', glyph: '☁' },
  { id: 'recovery', to: '/control/settings/recovery', glyph: '⚿' },
]

/**
 * Native Settings entries deliberately absent here. Not rendered — this is the record a reviewer
 * checks against (web-app-suite.md §2: native-only screens stay out).
 */
export const OMITTED_ON_WEB = {
  nativeOnly: ['backgroundSync', 'offlineMaps', 'desktopMenuBar'],
  excludedThisWave: ['models'],
  noWebPageYet: ['unlock', 'forgetDevice', 'errors', 'advancedSecurity', 'openSourceLicences'],
} as const
