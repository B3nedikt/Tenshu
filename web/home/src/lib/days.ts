// The activity feed's filter and day grouping (`ActivityView.tsx`), native's (`home-ios` `ActivityView`).
import type { TFunction } from 'i18next'
import type { HomeActivity } from '@tenshu/sdk/home'

export const FILTERS = ['all', 'device', 'automation', 'voice'] as const
export type Filter = (typeof FILTERS)[number]

export type Item =
  | { readonly type: 'day'; readonly key: string; readonly label: string }
  | { readonly type: 'event'; readonly event: HomeActivity }

// The rows with a header before each local day (rows arrive newest first).
export function withDays(t: TFunction, locale: string, rows: readonly HomeActivity[], nowMs: number): Item[] {
  const out: Item[] = []
  let current: string | null = null
  const today = dayKey(nowMs)
  const yesterday = dayKey(new Date(nowMs).setDate(new Date(nowMs).getDate() - 1))
  for (const event of rows) {
    const key = dayKey(event.claimedAtMs)
    if (key !== current) {
      current = key
      const label =
        key === today
          ? t('activity.today')
          : key === yesterday
            ? t('activity.yesterday')
            : new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(event.claimedAtMs)
      out.push({ type: 'day', key, label })
    }
    out.push({ type: 'event', event })
  }
  return out
}

function dayKey(epochMs: number): string {
  const d = new Date(epochMs)
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`
}
