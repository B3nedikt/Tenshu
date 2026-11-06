// Provider half of the publisher-client context; the context object and the
// hooks live in `publisherContext.ts` (a module mixing components with plain
// exports breaks fast refresh — react-refresh/only-export-components).
import type { ReactNode } from 'react'
import { defaultPublisherClient, PublisherClientContext } from './publisherContext'
import type { PublisherClient } from './publisherClient'

export function PublisherClientProvider({
  client = defaultPublisherClient,
  children,
}: {
  readonly client?: PublisherClient
  readonly children: ReactNode
}) {
  return (
    <PublisherClientContext.Provider value={client}>{children}</PublisherClientContext.Provider>
  )
}
