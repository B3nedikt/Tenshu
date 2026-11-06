// App launcher — top-bar center popover (per `web-control-plane.md`
// §3.1). Web-only chrome piece; native uses the OS app drawer for the
// same affordance. The grid lists Tenshu + every hosted app (the registry,
// `apps/registry/apps/<id>.ts`) plus an "Install another app" placeholder.
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
import { HOSTED_APPS } from '../apps/registry'

interface LauncherEntry {
  readonly id: string
  readonly to: string
  // English fallback copy for an app whose `appLauncher.entries.<id>` keys the catalogue does not
  // have yet — so registering an app never requires editing `control.json` to show up.
  readonly label?: string
  readonly description?: string
}

const ENTRIES: readonly LauncherEntry[] = [
  { id: 'tenshu', to: '/control/home' },
  ...HOSTED_APPS.map(app => ({
    id: app.id,
    to: app.manifest.launchPath,
    label: app.manifest.label,
    description: app.manifest.description,
  })),
]

export function AppLauncher() {
  const { t } = useTranslation('control')
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

  useEffect(() => {
    if (open) itemRefs.current[focusIndex]?.focus()
  }, [open, focusIndex])

  function onTriggerClick() {
    setFocusIndex(0)
    setOpen(o => !o)
  }

  function onListKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setFocusIndex(i => (i + 1) % ENTRIES.length)
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setFocusIndex(i => (i - 1 + ENTRIES.length) % ENTRIES.length)
    } else if (e.key === 'Home') {
      e.preventDefault()
      setFocusIndex(0)
    } else if (e.key === 'End') {
      e.preventDefault()
      setFocusIndex(ENTRIES.length - 1)
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
        className="rounded-md p-2 text-text-2 hover:bg-surface-2 focus:outline-none focus-visible:ring-2 focus-visible:ring-line-strong"
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
          className="absolute left-1/2 top-full z-30 mt-2 w-80 -translate-x-1/2 rounded-lg border border-line bg-surface p-3 shadow-lg"
        >
          <div className="grid grid-cols-2 gap-2">
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
                  className="flex flex-col items-start gap-1 rounded-md border border-transparent p-3 text-left hover:border-line hover:bg-surface focus:outline-none focus-visible:border-line focus-visible:bg-surface"
                >
                  <span className="grid h-10 w-10 place-items-center rounded-md bg-primary text-sm font-semibold text-on-primary">
                    {label[0]}
                  </span>
                  <span className="text-sm font-medium text-text">
                    {label}
                  </span>
                  <span className="text-xs text-text-2 line-clamp-2">{description}</span>
                </button>
              )
            })}
          </div>
          <div className="mt-3 border-t border-line pt-3">
            <button
              type="button"
              disabled
              title={t('appLauncher.installAnotherTitle')}
              className="w-full rounded-md px-3 py-2 text-left text-sm text-text-3"
            >
              {t('appLauncher.installAnother')}
            </button>
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
