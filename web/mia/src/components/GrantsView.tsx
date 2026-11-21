// What Mia can use (native Settings' `GrantsEntry`, MQ-D6): one switch per source, which covers
// what Mia may do there too. The separate calendar write switch was removed on 2026-11-19: every
// write already needs the confirmation tap, so it asked twice. Decided 2026-10-28 (web-app-suite.md
// §8): switchable on the web, as natively.
//
// ⚠⚠ Never optimistic. A switch draws the grant the runtime READ BACK: the write's own result (the
// whole set after the write) goes into the cache, and the push confirms it. A failed write changes
// nothing on screen but the error under that row, by kind.
// ⚠ Per service, because that is the only granularity there is — the copy says so, and implies
// nothing finer. Realm-scoped: a switch here changes Mia on every device.
import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import type { MiaGrant, MiaSource, SetGrantResult } from '@tenshu/sdk/mia'
import { useGrants } from '../lib/live'
import { setGranted } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import type { Failure } from '../lib/errors'
import { Card, ErrorText, Loading, ReadFailure, StreamEnded } from './ui'

type Pending = { source: MiaSource } | null
type Refusal = { source: MiaSource; failure?: Failure; error?: unknown } | null

export function GrantsView() {
  const { t } = useTranslation()
  const grants = useGrants()
  const queryClient = useQueryClient()
  const [pending, setPending] = useState<Pending>(null)
  const [refusal, setRefusal] = useState<Refusal>(null)

  async function flip(grant: MiaGrant, granted: boolean) {
    setPending({ source: grant.source })
    setRefusal(null)
    try {
      const result: SetGrantResult = await setGranted(grant.source, granted)
      if (result.ok && result.grants) queryClient.setQueryData(queryKeys.grants(), { ok: true, grants: result.grants })
      // Written, but not read back: re-read rather than draw the requested value or report a failure.
      else if (result.ok) await queryClient.invalidateQueries({ queryKey: queryKeys.grants() })
      else setRefusal({ source: grant.source, failure: result.error })
    } catch (error) {
      setRefusal({ source: grant.source, error })
    } finally {
      setPending(null)
    }
  }

  let list
  if (grants.isPending) list = <Loading />
  else if (grants.error) list = <ReadFailure error={grants.error} onRetry={grants.retry} />
  else if (grants.data && !grants.data.ok) list = <ReadFailure failure={grants.data.error} onRetry={grants.retry} />
  else {
    const rows = grants.data?.ok ? grants.data.grants : []
    list = (
      <ul className="divide-y divide-line">
        {rows.map((grant) => (
          <li key={grant.source} className="py-2" data-source={grant.source}>
            <Switch
              label={t(`grants.source.${grant.source}`)}
              checked={grant.read}
              busy={pending !== null}
              onChange={(on) => void flip(grant, on)}
            />
            {refusal?.source === grant.source && <ErrorText failure={refusal.failure} error={refusal.error} />}
          </li>
        ))}
      </ul>
    )
  }

  return (
    <section aria-label={t('tabs.grants')} className="space-y-4">
      <Card>
        <h2 className="text-base font-semibold text-text">{t('grants.title')}</h2>
        <p className="mt-1 text-sm text-text-2">{t('grants.body')}</p>
        <p className="mt-2 text-sm text-text-2">{t('grants.everywhere')}</p>
      </Card>
      <StreamEnded live={grants} />
      {list}
    </section>
  )
}

function Switch({
  label,
  checked,
  busy,
  onChange,
}: {
  label: string
  checked: boolean
  busy: boolean
  onChange: (checked: boolean) => void
}) {
  return (
    <label className="flex cursor-pointer items-center justify-between gap-3 py-1.5">
      <span className="text-sm text-text">{label}</span>
      <input
        type="checkbox"
        role="switch"
        aria-checked={checked}
        checked={checked}
        disabled={busy}
        onChange={(e) => onChange(e.target.checked)}
        className="h-5 w-9 cursor-pointer accent-[var(--color-primary)] disabled:cursor-not-allowed disabled:opacity-50"
      />
    </label>
  )
}
