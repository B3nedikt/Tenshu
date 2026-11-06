// `home.*`: the Home web surface (web-app-suite Wave 4, Home run A). Thin, validated pass-throughs to
// `Home` (`@tenshu/sdk/home`). The rules (state as stored, the hub tile's choice, the program shape,
// keyset paging, failure-never-empty) live in the Kotlin SDK and are pinned by `HomeWebContractTest`.
//
// ⚠⚠ READ-ONLY, decided (web-app-suite.md §8 items 2 and 9): every method below is a read or a push of
// one. There is no device command, rename, pairing, automation toggle / save / delete / run-now or hub
// command, and the runtime maps none (`HomePlugin.kt`). `proxyGateway.home.test.ts` pins this module
// to exactly `HOME_METHODS`.
// ⚠ Only `web/home` may call these, each by its exact name (`apps/registry/apps/home.ts`, run B; F5).
// `gatewayPermissions.test.ts` pins that no other origin reaches `home.*`.
import { Home, MAX_ACTIVITY_PAGE_SIZE, type HomeError } from '@tenshu/sdk/home'
import type { GatewayModule } from './module'
import { asRecord } from './params'

// The wire names — what `web/home/src/lib/proxy.ts`'s `ProxyMethod` table and its registry allowlist
// spell out. Reads and their push forms only.
export const HOME_METHODS = [
  'home.devices',
  'home.devices.observe',
  'home.hubStorage',
  'home.hubStorage.observe',
  'home.automations',
  'home.automations.observe',
  'home.activityPage',
  'home.activity.changes',
] as const

export const homeGateway: GatewayModule = {
  handlers: {
    // Every bridged device with its state as stored, and the rooms they name.
    'home.devices': async () => Home.devices(),
    // `hub: null` = no hub reports disk figures (no tile); `ok: false` = the read failed.
    'home.hubStorage': async () => Home.hubStorage(),
    'home.automations': async () => Home.automations(),
    // The feed: a keyset page in, a page and the next cursor out. Never the whole history.
    'home.activityPage': async (params) => {
      const { cursor, pageSize } = readPage(params)
      return Home.activityPage(cursor, pageSize)
    },
  },
  subscriptions: {
    // Genuine push over the relations' Flows. ⚠ `fail` is wired through: a collector that dies ends
    // the subscriber's stream with a terminal error instead of freezing its screen. The feed has no
    // push form; `home.activity.changes` says when to refetch its head.
    'home.devices.observe': (_params, emit, fail) => Home.observeDevices((result) => emit(result), failWith(fail)),
    'home.hubStorage.observe': (_params, emit, fail) => Home.observeHubStorage((result) => emit(result), failWith(fail)),
    'home.automations.observe': (_params, emit, fail) =>
      Home.observeAutomations((result) => emit(result), failWith(fail)),
    'home.activity.changes': (_params, emit, fail) =>
      Home.observeActivityChanges((version) => emit(version), failWith(fail)),
  },
}

// The SDK's terminal error is typed (`HomeError`); the bridge's `fail` crosses the origin boundary as a
// message string. ⚠ `kind` leads it, so a subdomain can still branch on the catalogue key.
function homeFailMessage(error: HomeError): string {
  return error.message ? `${error.kind}: ${error.message}` : error.kind
}

function failWith(fail: (message: string) => void): (error: HomeError) => void {
  return (error) => fail(homeFailMessage(error))
}

// `cursor` is optional (absent or null = the head) and opaque. `pageSize` is a required integer in
// 1..MAX_ACTIVITY_PAGE_SIZE: a missing one must not quietly become "the whole feed".
function readPage(params: unknown): { cursor: string | null; pageSize: number } {
  const r = asRecord(params)
  const cursor = r.cursor ?? null
  if (cursor !== null && typeof cursor !== 'string') throw new Error('home.activityPage requires { cursor: string | null }')
  const pageSize = r.pageSize
  if (
    typeof pageSize !== 'number' ||
    !Number.isInteger(pageSize) ||
    pageSize < 1 ||
    pageSize > MAX_ACTIVITY_PAGE_SIZE
  ) {
    throw new Error(`home.activityPage requires { pageSize: integer 1..${MAX_ACTIVITY_PAGE_SIZE} }`)
  }
  return { cursor, pageSize }
}
