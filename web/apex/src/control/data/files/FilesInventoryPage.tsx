// /control/data/files — the realm's file inventory (control-ia.md §5.1, web-control-plane.md §4.3,
// web-app-suite.md §2 Control row). A meta-view, NOT a browser: counts, total size, the newest
// change and a breakdown by kind. Browsing is Drive's, so the one way into the files is "Open Drive".
//
// Native Control has no Files screen to mirror — its Data screen is type cards plus Shares, and
// `control-ia.md` §9 leaves "what exactly Data → Files surfaces beyond inventory" open. This is the
// inventory §5.1 pins; growth-over-time and source apps are not built (no read carries them).
//
// ⚠⚠ Read-only, deliberately. Native's only destructive verb on Data is "Delete all" per data
// type, and it is withheld from Files (`DataScreen.kt`: File rows live in the folder tree, so the
// generic reset would delete nothing and report success). Files are deleted in Drive.
//
// ⚠ The figures are what THIS browser's replica holds, which is a cache with an identity
// (web-app-suite.md §5), so the page says so. Your plan's usage is on Services.
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { useTranslation } from 'react-i18next'
import { FileSystem } from '@tenshu/sdk/files'
import { findHostedApp } from '../../../apps/registry'
import { formatStorageBytes } from '../../../lib/format'
import { totalsByKind } from './fileKinds'
import { readInventoryOnce, type InventoryState } from './fileInventoryReader'

const DRIVE_PATH = findHostedApp('drive')?.manifest.launchPath ?? '/drive/files'

export function FilesInventoryPage() {
  const { t } = useTranslation('dataFiles')
  const [state, setState] = useState<InventoryState>({ status: 'loading' })

  // ⚠ The read outlives an unmount (it cannot be cancelled — see `readInventoryOnce`), so its
  // answer is dropped once the page is gone rather than set on an unmounted component.
  const mounted = useRef(false)

  // One read on open and on "Try again" — never on a timer (no background work in a tab), and
  // never two at once: a second open joins the read already running.
  const load = useCallback(() => {
    void readInventoryOnce(() => FileSystem.inventory()).then(next => {
      if (mounted.current) setState(next)
    })
  }, [])

  useEffect(() => {
    mounted.current = true
    load()
    return () => {
      mounted.current = false
    }
  }, [load])

  const retry = useCallback(() => {
    setState({ status: 'loading' })
    load()
  }, [load])

  return (
    <div>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-2xl font-medium">{t('title')}</h1>
          <p className="mt-1 text-sm text-text-2">{t('subtitle')}</p>
        </div>
        <Link
          to={DRIVE_PATH}
          className="shrink-0 rounded-md border border-line px-3 py-1.5 text-sm font-medium text-text-2 transition-colors hover:bg-surface-3"
        >
          {t('openDrive')}
        </Link>
      </div>

      <InventoryBody state={state} onRetry={retry} />

      <p className="mt-8 text-xs text-text-2">
        {t('replicaNote')}{' '}
        <Link to="/control/services" className="font-medium text-text underline-offset-4 hover:underline">
          {t('servicesLink')}
        </Link>
      </p>
    </div>
  )
}

function InventoryBody({ state, onRetry }: { readonly state: InventoryState; readonly onRetry: () => void }) {
  const { t, i18n } = useTranslation('dataFiles')

  switch (state.status) {
    case 'loading':
      return (
        <div role="status" aria-label={t('loading')} className="mt-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {[0, 1, 2, 3].map(i => (
            <div key={i} className="rounded-md border border-line p-4">
              <div className="h-3 w-1/2 animate-pulse rounded bg-surface-3" />
              <div className="mt-3 h-5 w-2/3 animate-pulse rounded bg-surface-3" />
            </div>
          ))}
        </div>
      )
    case 'error':
      return (
        <div role="alert" className="mt-6 rounded-md border border-danger/40 bg-danger/5 p-6">
          <p className="text-sm font-medium text-danger">{t(`errors.${state.kind}.title`)}</p>
          <p className="mt-1 text-sm text-text-2">{t(`errors.${state.kind}.body`)}</p>
          {state.detail && <p className="mt-2 break-all font-mono text-[11px] text-text-3">{state.detail}</p>}
          <button
            type="button"
            onClick={onRetry}
            className="mt-4 rounded-md border border-line px-3 py-1.5 text-sm font-medium text-text-2 transition-colors hover:bg-surface-3"
          >
            {t('retry')}
          </button>
        </div>
      )
    case 'empty':
      return (
        <div className="mt-6 rounded-md border border-dashed border-line p-8 text-center text-sm text-text-2">
          <p className="font-medium text-text">{t('empty.title')}</p>
          <p className="mt-1">{t('empty.body')}</p>
        </div>
      )
    case 'ready': {
      const { inventory } = state
      const kinds = totalsByKind(inventory.byExtension)
      const newest =
        inventory.newestModifiedMillis > 0
          ? new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium' }).format(inventory.newestModifiedMillis)
          : t('summary.none')
      return (
        <>
          <dl className="mt-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Tile label={t('summary.files')} value={inventory.fileCount.toLocaleString(i18n.language)} />
            <Tile label={t('summary.folders')} value={inventory.folderCount.toLocaleString(i18n.language)} />
            <Tile label={t('summary.totalSize')} value={formatStorageBytes(inventory.totalBytes)} />
            <Tile label={t('summary.newest')} value={newest} />
          </dl>

          {kinds.length > 0 && (
            <>
              <h2 className="mt-8 text-sm font-medium uppercase tracking-wide text-text-2">{t('byKind.title')}</h2>
              <ul className="mt-3 divide-y divide-line rounded-md border border-line" aria-label={t('byKind.title')}>
                {kinds.map(k => {
                  const pct = inventory.totalBytes > 0 ? (k.bytes / inventory.totalBytes) * 100 : 0
                  return (
                    <li key={k.kind} className="px-4 py-3">
                      <div className="flex items-baseline justify-between gap-4 text-sm">
                        <span className="font-medium text-text">{t(`kinds.${k.kind}`)}</span>
                        <span className="text-text-2">
                          {t('byKind.count', { count: k.count })} · {formatStorageBytes(k.bytes)}
                        </span>
                      </div>
                      <div aria-hidden className="mt-2 h-1.5 overflow-hidden rounded-full bg-surface-3">
                        <div className="h-full rounded-full bg-primary" style={{ width: `${pct}%` }} />
                      </div>
                    </li>
                  )
                })}
              </ul>
            </>
          )}
        </>
      )
    }
  }
}

function Tile({ label, value }: { readonly label: string; readonly value: string }) {
  return (
    <div className="rounded-md border border-line p-4">
      <dt className="text-xs text-text-2">{label}</dt>
      <dd className="mt-1 text-lg font-medium text-text">{value}</dd>
    </div>
  )
}
