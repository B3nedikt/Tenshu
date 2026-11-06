// Singleton + React context for the apex `PublisherClient`. The hook
// re-renders on every account-state change via `useSyncExternalStore` —
// the canonical React 18+ pattern for an "observable" outside the React
// state tree.
//
// Provider isolation lets tests inject a fresh `MockPublisherClient` per
// render. Production wires a single module-level singleton via
// `defaultPublisherClient`.
//
// The provider component lives in `PublisherClientProvider.tsx`: a module that
// exports both a component and plain functions breaks Vite's fast refresh
// (react-refresh/only-export-components), so the hooks stay component-free here.
import { createContext, useContext, useSyncExternalStore } from 'react'
import {
  MockPublisherClient,
  type PublisherAccount,
  type PublisherClient,
} from './publisherClient'

/**
 * ⚠ The mock is the CONTEXT default, not the production choice. Constructing the
 * real client here would mean importing the SDK (for `Realm.ownerId`) into every
 * module that reads the account, dragging the Gradle bundle into their tests.
 * `App.tsx` injects the real one — see `realPublisherClient.ts`.
 */
export const defaultPublisherClient: PublisherClient = new MockPublisherClient()

export const PublisherClientContext = createContext<PublisherClient>(defaultPublisherClient)

export function usePublisherClient(): PublisherClient {
  return useContext(PublisherClientContext)
}

export function usePublisherAccount(): PublisherAccount | null {
  const client = usePublisherClient()
  return useSyncExternalStore(
    listener => client.subscribe(listener),
    () => client.getAccount(),
    () => client.getAccount(),
  )
}
