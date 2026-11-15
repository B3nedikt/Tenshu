// Whether the details pane is open (web-apps-layout.md D2: it stays as you left it). Per browser,
// in `localStorage`, until the app's synced preferences slice lands (L6) — read defensively, like
// listingPrefs: disabled storage or a foreign value means "closed", never an error.
const STORAGE_KEY = 'tenshu-drive-pane'

export function loadPaneOpen(): boolean {
  try {
    return window.localStorage.getItem(STORAGE_KEY) === 'open'
  } catch {
    return false
  }
}

export function savePaneOpen(open: boolean): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, open ? 'open' : 'closed')
  } catch {
    // A preference that cannot be saved is not worth failing anything for.
  }
}
