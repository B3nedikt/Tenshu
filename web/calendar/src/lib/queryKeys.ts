// Central registry of TanStack Query keys — the app-kit's `defineQueryKeys`: every query, every
// `setQueryData` from a push stream and every invalidation names its key through this factory.
import { defineQueryKeys } from '@tenshu/app-kit/query'

export const queryKeys = defineQueryKeys({
  calendars: () => ['calendars'] as const,
  hidden: () => ['hiddenCalendars'] as const,
  defaultCalendar: () => ['defaultCalendar'] as const,
  subscriptions: () => ['subscriptions'] as const,
  occurrences: (from: string, to: string) => ['occurrences', from, to] as const,
  reminders: (eventId: string) => ['reminders', eventId] as const,
})
