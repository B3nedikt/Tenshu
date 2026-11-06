// Context + hooks for the apex `AppRegistry`, mirroring `publisherContext`.
// `useSyncExternalStore` re-renders consumers on every registry write — the
// canonical React 18+ pattern for an observable outside the React state tree.
// Provider isolation lets tests inject a fresh `MockAppRegistry` per render;
// production uses a single module-level singleton.
//
// The provider component lives in `AppRegistryProvider.tsx`: a module that
// exports both a component and plain functions breaks Vite's fast refresh
// (react-refresh/only-export-components), so the hooks stay component-free here.
import { createContext, useContext, useSyncExternalStore } from 'react'
import { MockAppRegistry, type AppRegistry } from './appRegistry'
import type { AppManifest } from './manifest'

export const defaultAppRegistry: AppRegistry = new MockAppRegistry()

export const AppRegistryContext = createContext<AppRegistry>(defaultAppRegistry)

export function useAppRegistry(): AppRegistry {
  return useContext(AppRegistryContext)
}

export function useInstalledApps(): readonly AppManifest[] {
  const registry = useAppRegistry()
  return useSyncExternalStore(
    listener => registry.subscribe(listener),
    () => registry.listInstalled(),
    () => registry.listInstalled(),
  )
}

export function useAppDetail(appId: string): AppManifest | null {
  const registry = useAppRegistry()
  return useSyncExternalStore(
    listener => registry.subscribe(listener),
    () => registry.getDetail(appId),
    () => registry.getDetail(appId),
  )
}
