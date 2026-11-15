// App launcher — top-bar popover (per `web-control-plane.md` §3.1, as amended by
// `web-apex-home.md` §3.7). Web-only chrome piece; native uses the OS app drawer for the
// same affordance. The grid lists the overview + the user's SHOWN apps (web-apex-home.md §3.5;
// every registered app while that list is unknown), then "Your apps…" and a link into Control.
// Keyboard-navigable per spec (arrow keys + Enter, Escape closes), trigger has
// aria-haspopup="dialog".
import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type KeyboardEvent,
} from 'react'
import { useNavigate } from 'react-router'
import { useTranslation } from 'react-i18next'
import { AppIcon } from '../apps/AppIcon'
import { CONTROL_PATH, OVERVIEW_PATH, YOUR_APPS_PATH } from '../lib/overviewPath'
import { useVisibleApps } from '../overview/useVisibleApps'
import { GearIcon } from '../shell/icons'

interface LauncherEntry {
  readonly id: string
  readonly to: string
  // English fallback copy for an app whose `appLauncher.entries.<id>` keys the catalogue does not
  // have yet — so registering an app never requires editing `control.json` to show up.
  readonly label?: string
  readonly description?: string
}

// `tenshu` is the apex overview (labelled with the brand — "Home" is the Home app's name).
const OVERVIEW_ENTRY: LauncherEntry = { id: 'tenshu', to: OVERVIEW_PATH }

// The two footer links, after the tiles on the same arrow-key cycle.
const FOOTER: readonly LauncherEntry[] = [
  { id: 'yourApps', to: YOUR_APPS_PATH },
  { id: 'control', to: CONTROL_PATH },
]

export function AppLauncher() {
  const { t } = useTranslation('control')
  const { apps } = useVisibleApps()
  const ENTRIES: readonly LauncherEntry[] = [
    OVERVIEW_ENTRY,
    ...apps.map(app => ({
      id: app.id,
      to: app.manifest.launchPath,
      label: app.manifest.label,
      description: app.manifest.description,
    })),
  ]
  const FOCUSABLE_COUNT = ENTRIES.length + FOOTER.length
  const [open, setOpen] = useState(false)
  const [focusIndex, setFocusIndex] = useState(0)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const popoverRef = useRef<HTMLDivElement>(null)
  const itemRefs = useRef<Array<HTMLButtonElement | null>>([])
  const navigate = useNavigate()
  const dialogId = useId()

  const close = useCallback((returnFocus: boolean = true) => {
    setOpen(false)
    if (returnFocus) triggerRef.current?.focus()
  }, [])

  useEffect(() => {
    if (!open) return
    function onPointerDown(e: PointerEvent) {
      const target = e.target as Node | null
      if (
        popoverRef.current?.contains(target ?? null) ||
        triggerRef.current?.contains(target ?? null)
      ) {
        return
      }
      close(false)
    }
    function onKey(e: globalThis.KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault()
        close(true)
      }
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open, close])

  // Clamped: the list can shrink while open (a sync from another browser), and an index past the
  // end would leave focus on nothing.
  useEffect(() => {
    if (open) itemRefs.current[Math.min(focusIndex, FOCUSABLE_COUNT - 1)]?.focus()
  }, [open, focusIndex, FOCUSABLE_COUNT])

  function onTriggerClick() {
    setFocusIndex(0)
    setOpen(o => !o)
  }

  function onListKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setFocusIndex(i => (i + 1) % FOCUSABLE_COUNT)
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setFocusIndex(i => (i - 1 + FOCUSABLE_COUNT) % FOCUSABLE_COUNT)
    } else if (e.key === 'Home') {
      e.preventDefault()
      setFocusIndex(0)
    } else if (e.key === 'End') {
      e.preventDefault()
      setFocusIndex(FOCUSABLE_COUNT - 1)
    }
  }

  function onSelect(entry: LauncherEntry) {
    close(false)
    navigate(entry.to)
  }

  return (
    <div className="relative">
      <button
        ref={triggerRef}
        type="button"
        onClick={onTriggerClick}
        aria-label={t('appLauncher.triggerAriaLabel')}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? dialogId : undefined}
        title={t('appLauncher.triggerTitle')}
        className="rounded-m p-2 text-text-2 hover:bg-well hover:text-text focus:outline-none focus-visible:ring-2 focus-visible:ring-line-strong"
      >
        <NineDotIcon />
      </button>
      {open && (
        <div
          ref={popoverRef}
          id={dialogId}
          role="dialog"
          aria-label={t('appLauncher.dialogAriaLabel')}
          onKeyDown={onListKeyDown}
          className="absolute right-0 top-full z-30 mt-2 max-h-[calc(100vh-5rem)] w-80 max-w-[calc(100vw-2rem)] overflow-y-auto rounded-lg border border-line bg-surface p-3 shadow-lg"
        >
          {/* Icon + name only, three across: with every registered app listed, a description per
              tile pushed the Control link below a laptop screen. The description is the tooltip. */}
          <div className="grid grid-cols-3 gap-1">
            {ENTRIES.map((entry, i) => {
              const label = t(`appLauncher.entries.${entry.id}.label`, { defaultValue: entry.label })
              const description = t(`appLauncher.entries.${entry.id}.description`, {
                defaultValue: entry.description,
              })
              return (
                <button
                  key={entry.id}
                  ref={el => {
                    itemRefs.current[i] = el
                  }}
                  type="button"
                  onClick={() => onSelect(entry)}
                  title={description}
                  className="flex flex-col items-center gap-1.5 rounded-md border border-transparent px-1 py-2.5 text-center hover:bg-surface-2 focus:outline-none focus-visible:border-line focus-visible:bg-surface-2"
                >
                  <AppIcon appId={entry.id} label={label} size="md" />
                  <span className="max-w-full truncate text-xs font-medium text-text">
                    {label}
                  </span>
                </button>
              )
            })}
          </div>
          <div className="mt-3 flex flex-col border-t border-line pt-3">
            {FOOTER.map((entry, i) => (
              <button
                key={entry.id}
                ref={el => {
                  itemRefs.current[ENTRIES.length + i] = el
                }}
                type="button"
                onClick={() => onSelect(entry)}
                className="flex w-full items-center gap-3 rounded-md px-3 py-2 text-left text-sm text-text hover:bg-surface-2 focus:outline-none focus-visible:bg-surface-2"
              >
                <span className="text-text-2">{entry.id === 'control' ? <GearIcon /> : <PlusIcon />}</span>
                {t(entry.id === 'control' ? 'appLauncher.control' : 'appLauncher.yourApps')}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

function NineDotIcon() {
  return (
    <svg
      aria-hidden
      viewBox="0 0 20 20"
      width={20}
      height={20}
      fill="currentColor"
      className="block"
    >
      <circle cx={4} cy={4} r={1.5} />
      <circle cx={10} cy={4} r={1.5} />
      <circle cx={16} cy={4} r={1.5} />
      <circle cx={4} cy={10} r={1.5} />
      <circle cx={10} cy={10} r={1.5} />
      <circle cx={16} cy={10} r={1.5} />
      <circle cx={4} cy={16} r={1.5} />
      <circle cx={10} cy={16} r={1.5} />
      <circle cx={16} cy={16} r={1.5} />
    </svg>
  )
}

function PlusIcon() {
  return (
    <svg aria-hidden viewBox="0 0 20 20" width={18} height={18} fill="none" stroke="currentColor" strokeWidth={1.8}>
      <path d="M10 4v12M4 10h12" />
    </svg>
  )
}
