// Provider half of the app-registry context; the context object and the hooks
// live in `appRegistryContext.ts` (a module mixing components with plain
// exports breaks fast refresh — react-refresh/only-export-components).
import type { ReactNode } from 'react'
import { AppRegistryContext, defaultAppRegistry } from './appRegistryContext'
import type { AppRegistry } from './appRegistry'

export function AppRegistryProvider({
  registry = defaultAppRegistry,
  children,
}: {
  readonly registry?: AppRegistry
  readonly children: ReactNode
}) {
  return <AppRegistryContext.Provider value={registry}>{children}</AppRegistryContext.Provider>
}
