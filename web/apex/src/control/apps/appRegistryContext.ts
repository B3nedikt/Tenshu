// Context + hooks for the apex `AppRegistry`, mirroring `publisherContext`.
// `useSyncExternalStore` re-renders consumers on every registry change — the
// canonical React 18+ pattern for an observable outside the React state tree.
// Provider isolation lets tests inject a fresh `MockAppRegistry` per render;
// production injects `realAppRegistry` at the runtime root.
//
// ⚠ This module deliberately does NOT import `@tenshu/sdk/apps` (it drags the Kotlin bundle into
// every test that renders a consumer). ⚠⚠ The default is UNCONFIGURED, never the mock: a missing
// provider shows "can't tell which devices", not invented ones.
//
// The provider component lives in `AppRegistryProvider.tsx`: a module that
// exports both a component and plain functions breaks Vite's fast refresh
// (react-refresh/only-export-components), so the hooks stay component-free here.
import { createContext, useCallback, useContext, useSyncExternalStore } from 'react'
import { UnconfiguredAppRegistry, type AppRegistry, type RegistrationsState } from './appRegistry'
import type { AppManifest } from './manifest'

export const defaultAppRegistry: AppRegistry = new UnconfiguredAppRegistry()

export const AppRegistryContext = createContext<AppRegistry>(defaultAppRegistry)

export function useAppRegistry(): AppRegistry {
  return useContext(AppRegistryContext)
}

/**
 * ⚠ A stable subscribe per registry: an inline lambda is a new function every render, and
 * `useSyncExternalStore` resubscribes whenever it changes — for the SDK registry that would stop and
 * restart the realm read on every render.
 */
function useSubscribe(registry: AppRegistry): (listener: () => void) => () => void {
  return useCallback(listener => registry.subscribe(listener), [registry])
}

export function useInstalledApps(): readonly AppManifest[] {
  const registry = useAppRegistry()
  return useSyncExternalStore(
    useSubscribe(registry),
    () => registry.listInstalled(),
    () => registry.listInstalled(),
  )
}

export function useAppDetail(appId: string): AppManifest | null {
  const registry = useAppRegistry()
  return useSyncExternalStore(
    useSubscribe(registry),
    () => registry.getDetail(appId),
    () => registry.getDetail(appId),
  )
}

/** The realm's registrations, live. Subscribing is what starts the read in production. */
export function useAppRegistrations(): RegistrationsState {
  const registry = useAppRegistry()
  return useSyncExternalStore(
    useSubscribe(registry),
    () => registry.registrations(),
    () => registry.registrations(),
  )
}
