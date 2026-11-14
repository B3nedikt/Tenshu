// The layout every app renders inside the apex frame (web-apps-layout.md §3.1): a section sidebar,
// content that fills the width, and an optional details pane. No headline — the apex top bar
// already names the app.
//
// Narrow windows (below 900 px: phone browsers, a half-screen window) get the native shapes back
// from the same markup, by CSS alone: the sidebar becomes a scrolling row of sections above the
// content, and the pane a bottom sheet. ⚠ CSS only, so jsdom cannot see the breakpoint — the tests
// pin the structure, not the narrow look.
import type { ReactNode } from 'react'

export interface AppNavItem<T extends string> {
  readonly id: T
  readonly label: string
  readonly icon?: ReactNode
  // A group label drawn above this item in the wide sidebar ("Collections"); narrow, the sections
  // are one row and the label is dropped.
  readonly heading?: string
}

export interface AppLayoutProps<T extends string> {
  // Names the sidebar's <nav> for assistive tech ("Drive sections").
  readonly navLabel: string
  readonly nav: readonly AppNavItem<T>[]
  // Sections pinned to the sidebar's foot (Settings).
  readonly navFooter?: readonly AppNavItem<T>[]
  readonly active: T
  readonly onNavigate: (id: T) => void
  // Above the sections: the app's primary action (Drive's "+ New").
  readonly primaryAction?: ReactNode
  // Below the sections, above the footer items: app status (Drive's storage meter).
  readonly sidebarExtra?: ReactNode
  // Banners shown above the content, whatever section is open (runtime, sync).
  readonly banners?: ReactNode
  // The details pane; omitted or null = no pane, and the content takes its width.
  readonly pane?: ReactNode
  readonly paneLabel?: string
  // Closes the pane: Escape inside it, and (narrow) a tap on the scrim behind the sheet.
  readonly onPaneClose?: () => void
  readonly children: ReactNode
}

export function AppLayout<T extends string>({
  navLabel,
  nav,
  navFooter = [],
  active,
  onNavigate,
  primaryAction,
  sidebarExtra,
  banners,
  pane,
  paneLabel = 'Details',
  onPaneClose,
  children,
}: AppLayoutProps<T>) {
  const item = (entry: AppNavItem<T>) => (
    <li key={entry.id} className="flex-none">
      {entry.heading && (
        <p className="mt-4 mb-1 hidden px-3 text-[11px] font-semibold uppercase tracking-wider text-text-3 min-[900px]:block">
          {entry.heading}
        </p>
      )}
      <button
        type="button"
        onClick={() => onNavigate(entry.id)}
        aria-current={entry.id === active ? 'page' : undefined}
        className={`flex w-full items-center gap-3 whitespace-nowrap rounded-lg px-3 py-2 text-left text-sm ${
          entry.id === active
            ? 'bg-primary-soft font-medium text-text'
            : 'text-text-2 hover:bg-surface-2 hover:text-text'
        }`}
      >
        {entry.icon}
        {entry.label}
      </button>
    </li>
  )

  return (
    <div className="flex h-dvh flex-col bg-surface text-text min-[900px]:flex-row">
      <aside className="flex flex-none items-center gap-2 overflow-x-auto border-b border-line px-3 py-2 min-[900px]:w-60 min-[900px]:flex-col min-[900px]:items-stretch min-[900px]:gap-1 min-[900px]:overflow-x-visible min-[900px]:overflow-y-auto min-[900px]:border-r min-[900px]:border-b-0 min-[900px]:px-3 min-[900px]:py-4">
        {primaryAction && <div className="flex-none min-[900px]:mb-3">{primaryAction}</div>}
        {/* One landmark for every section, footer ones included. Narrow, the aside is a row, so
            the footer sections simply follow; wide, the first list's flex-1 pushes them down. */}
        <nav
          aria-label={navLabel}
          className="flex flex-none items-center gap-1 min-[900px]:flex-1 min-[900px]:flex-col min-[900px]:items-stretch"
        >
          <ul className="flex gap-1 min-[900px]:flex-1 min-[900px]:flex-col">{nav.map(item)}</ul>
          {sidebarExtra && <div className="hidden min-[900px]:block">{sidebarExtra}</div>}
          {navFooter.length > 0 && (
            <ul className="flex gap-1 min-[900px]:mt-2 min-[900px]:flex-col">{navFooter.map(item)}</ul>
          )}
        </nav>
      </aside>

      <div className="flex min-h-0 min-w-0 flex-1">
        <main className="flex min-h-0 min-w-0 flex-1 flex-col overflow-y-auto px-4 py-4 min-[900px]:px-7 min-[900px]:py-5">
          {banners}
          {children}
        </main>
        {pane && onPaneClose && (
          // Narrow only: the sheet's scrim — a tap outside closes it. Pointer-only and hidden from
          // assistive tech; the keyboard closes the pane with Escape (below) or its own back button.
          <div aria-hidden className="fixed inset-0 z-30 bg-black/40 min-[900px]:hidden" onClick={onPaneClose} />
        )}
        {pane && (
          <aside
            aria-label={paneLabel}
            // Escape inside the pane closes it. On the pane, not the window: a dialog opened from the
            // pane is portalled outside it, so its own Escape never reaches here.
            onKeyDown={
              onPaneClose
                ? (e) => {
                    if (e.key === 'Escape') onPaneClose()
                  }
                : undefined
            }
            className="fixed inset-x-0 bottom-0 z-40 max-h-[70vh] overflow-y-auto rounded-t-2xl border-t border-line bg-surface-2 p-5 shadow-2xl min-[900px]:static min-[900px]:z-auto min-[900px]:max-h-none min-[900px]:w-80 min-[900px]:flex-none min-[900px]:rounded-none min-[900px]:border-t-0 min-[900px]:border-l min-[900px]:bg-surface min-[900px]:shadow-none"
          >
            {pane}
          </aside>
        )}
      </div>
    </div>
  )
}
