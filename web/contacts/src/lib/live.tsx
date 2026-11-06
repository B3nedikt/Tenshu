// The push half of the address book. `contact.list.observe` and `contact.ownProfile.observe` are
// genuine streams (run A); each is subscribed ONCE, at the app root, and written into the query
// cache, so every screen reads one copy and no view refetches after a write — the write's own
// emission updates it. The mechanism is the app-kit's (`useLive`); this file is which streams.
//
// ⚠⚠ A terminal stream error (the producer's collector died, or the gateway refused) is surfaced
// as `streamError`, never swallowed: before run B1 a dead collector froze the screen on its last
// value with nothing to say so.
import type { ReactNode } from 'react'
import type { ContactsResult, OwnProfile } from '@tenshu/sdk/contact'
import { createLiveContext, useLive, type Live } from '@tenshu/app-kit/live'
import { listContacts, ownProfile, subscribeContacts, subscribeOwnProfile } from './proxy'
import { queryKeys } from './queryKeys'

interface LiveState {
  readonly contacts: Live<ContactsResult>
  readonly profile: Live<OwnProfile>
}

const [LiveContext, useLiveState] = createLiveContext<LiveState>('LiveProvider')

export function LiveProvider({ children }: { children: ReactNode }) {
  const contacts = useLive(queryKeys.contacts(), listContacts, subscribeContacts)
  const profile = useLive(queryKeys.ownProfile(), ownProfile, subscribeOwnProfile)
  return <LiveContext.Provider value={{ contacts, profile }}>{children}</LiveContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useContacts(): Live<ContactsResult> {
  return useLiveState().contacts
}

// eslint-disable-next-line react-refresh/only-export-components
export function useOwnProfile(): Live<OwnProfile> {
  return useLiveState().profile
}
