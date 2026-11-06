// The push half of Home. `home.devices.observe`, `home.hubStorage.observe` and
// `home.automations.observe` are genuine streams (run A); each is subscribed ONCE, at the app root,
// and written into the query cache, so every tab reads one copy (the Automations summary resolves
// device names from the same devices read the Home tab shows). The mechanism is the app-kit's
// (`useLive`); this file is which streams.
//
// ⚠⚠ A terminal stream error is surfaced as `streamError` (rendered by kind, `Stopped`), and a typed
// failure arrives as `data` with `ok: false` — neither is ever an empty list.
import type { ReactNode } from 'react'
import type { HomeAutomationsResult, HomeDevicesResult, HomeHubStorageResult } from '@tenshu/sdk/home'
import { createLiveContext, useLive, type Live } from '@tenshu/app-kit/live'
import { automations, devices, hubStorage, subscribeAutomations, subscribeDevices, subscribeHubStorage } from './proxy'
import { queryKeys } from './queryKeys'

interface LiveState {
  readonly devices: Live<HomeDevicesResult>
  readonly hubStorage: Live<HomeHubStorageResult>
  readonly automations: Live<HomeAutomationsResult>
}

const [LiveContext, useLiveState] = createLiveContext<LiveState>('LiveProvider')

export function LiveProvider({ children }: { children: ReactNode }) {
  const devicesLive = useLive(queryKeys.devices(), devices, subscribeDevices)
  const hubLive = useLive(queryKeys.hubStorage(), hubStorage, subscribeHubStorage)
  const automationsLive = useLive(queryKeys.automations(), automations, subscribeAutomations)
  return (
    <LiveContext.Provider value={{ devices: devicesLive, hubStorage: hubLive, automations: automationsLive }}>
      {children}
    </LiveContext.Provider>
  )
}

// eslint-disable-next-line react-refresh/only-export-components
export function useDevices(): Live<HomeDevicesResult> {
  return useLiveState().devices
}

// eslint-disable-next-line react-refresh/only-export-components
export function useHubStorage(): Live<HomeHubStorageResult> {
  return useLiveState().hubStorage
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAutomations(): Live<HomeAutomationsResult> {
  return useLiveState().automations
}
