// "Add widget" (docs/proposals/web-apex-home.md §4.12): the widgets not on the overview now —
// "Your apps" and a card per installed app that has one. Adding puts it at the end.
// ⚠ Only INSTALLED apps' cards: installing is the "Your apps" dialog's job (two lists, `widgets.ts`).
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Dialog } from '@tenshu/web-tokens/ui'
import { AppIcon } from '../apps/AppIcon'
import { useWidgets } from './useWidgets'
import { addableWidgets, widgetLabel, withWidgetAdded } from './widgets'

export function AddWidgetDialog({ onClose }: { readonly onClose: () => void }) {
  const { t } = useTranslation('control')
  const { installed, stored, pending, apply } = useWidgets()
  const [error, setError] = useState<string | null>(null)
  const addable = stored === null ? [] : addableWidgets(stored, installed)

  async function add(key: string) {
    if (pending || stored === null) return
    setError(null)
    if (await apply(withWidgetAdded(stored, installed, key))) onClose()
    else setError(t('customize.writeFailed'))
  }

  return (
    <Dialog title={t('customize.addWidget')} onClose={onClose}>
      {addable.length === 0 ? (
        <p className="text-sm text-text-2">{t('customize.allAdded')}</p>
      ) : (
        <ul className="-mt-1 divide-y divide-line">
          {addable.map(widget => {
            const label = widgetLabel(t, widget)
            return (
              <li key={widget.key} className="flex items-center gap-3 py-2.5">
                {widget.kind === 'apps' ? (
                  <span aria-hidden className="grid h-10 w-10 place-items-center rounded-[12px] bg-well text-text-2">
                    <svg viewBox="0 0 24 24" width={22} height={22} fill="currentColor">
                      <rect x={4} y={4} width={7} height={7} rx={2} />
                      <rect x={13} y={4} width={7} height={7} rx={2} />
                      <rect x={4} y={13} width={7} height={7} rx={2} />
                      <rect x={13} y={13} width={7} height={7} rx={2} />
                    </svg>
                  </span>
                ) : (
                  <AppIcon appId={widget.app.id} label={label} size="md" />
                )}
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-medium text-text">{label}</span>
                  <span className="block truncate text-xs text-text-2">
                    {widget.kind === 'apps'
                      ? t('customize.appsWidgetDetail')
                      : t(`glance.subtitle.${widget.app.glance!.subtitle}`)}
                  </span>
                </span>
                <button
                  type="button"
                  aria-disabled={pending || undefined}
                  onClick={() => void add(widget.key)}
                  aria-label={t('customize.addAriaLabel', { widget: label })}
                  className="shrink-0 rounded-pill bg-primary px-3 py-1 text-xs font-semibold text-on-primary hover:opacity-90 aria-disabled:opacity-50"
                >
                  {t('yourApps.add')}
                </button>
              </li>
            )
          })}
        </ul>
      )}
      {error !== null && (
        <p role="alert" className="mt-3 text-sm text-danger">
          {error}
        </p>
      )}
      <div className="mt-4 flex justify-end">
        <button
          type="button"
          onClick={onClose}
          className="rounded-pill border border-line px-4 py-1.5 text-sm font-medium text-text hover:bg-surface-3"
        >
          {t('customize.close')}
        </button>
      </div>
    </Dialog>
  )
}
