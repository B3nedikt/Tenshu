// An app's single connection to the apex proxy (docs/web/architecture.md §5.2). An app has no
// backend of its own: every read and write goes through the hidden apex iframe (or the embedding
// apex), which holds the keys and runs the runtime. Extracted from Drive's and Contacts'
// `lib/proxy.ts` (web-app-suite.md F3, ADR 0020).
//
// ⚠⚠ `@tenshu/sdk/bridge` is the ONLY `@tenshu/sdk` value import anywhere in the app-kit. Every
// other entry re-exports the Kotlin bundle, so one value import would drag the whole runtime into
// every app (`sdkBoundary.test.ts` walks this package's import graph and fails on it).
//
// ⚠ What an origin may call is decided apex-side, per origin (F5,
// `web/apex/src/apps/registry/apps/<id>.ts`). A method missing from the app's allowlist is refused
// with `not permitted …`; the apex's `gatewayPermissions.test.ts` reads each app's `ProxyMethod`
// table out of `web/<app>/src/lib/proxy.ts` and fails on drift before a browser does.
import { mountApexProxy, type ProxyClient } from '@tenshu/sdk/bridge'

// An app's wire names: `{ ListFiles: 'fs.list', … } as const`.
export type MethodTable = Readonly<Record<string, string>>
export type MethodOf<M extends MethodTable> = M[keyof M]

// The session methods every app calls before any of its own can work: the liveness probe, and the
// one-runtime-per-origin lease + "Use here" hand-over (docs/web/architecture.md "Cross-tab"). An
// app's `ProxyMethod` table spells them out literally (the apex test reads that table as text), and
// the allowlist's `SESSION_METHODS` is the same three names.
export const SessionMethod = {
  Ping: 'bridge.ping',
  RuntimeLease: 'runtime.lease',
  RuntimeUseHere: 'runtime.useHere',
} as const

/**
 * The apex origin to dial: `VITE_APEX_ORIGIN` at build time, else the apex Vite dev server in dev
 * and the production apex in a production build. ⚠ A production build never falls back to
 * localhost: every app would dial a dev server that is not there, and fail only in the browser.
 * (`deploy/build-apex-image.sh` sets it and checks the bundle; this guards every other build path.)
 */
export function configuredApexOrigin(): string {
  return (
    import.meta.env.VITE_APEX_ORIGIN ??
    (import.meta.env.PROD ? 'https://tenshu.app' : 'http://localhost:5173')
  )
}

export interface ApexBridge<M extends MethodTable> {
  readonly apexOrigin: string
  // The mounted client. Mounting twice would create two hidden iframes, so this is memoised.
  client(): Promise<ProxyClient>
  call<T>(method: MethodOf<M>, params?: unknown): Promise<T>
  // A pushed stream: async setup, synchronous teardown — a component may unmount before the
  // bridge has even mounted, so unsubscribing must work before the client exists. `onError` is
  // terminal: the producer ended the stream (its collector died), the gateway refused it, or the
  // bridge never mounted.
  subscribe<T>(
    method: MethodOf<M>,
    params: unknown,
    onValue: (value: T) => void,
    onError?: (error: Error) => void,
  ): () => void
}

/**
 * Creates the app's bridge over its method table. Create it ONCE, at module level, next to the
 * table — the memoised mount lives in the returned object.
 */
export function createApexBridge<M extends MethodTable>(
  _methods: M,
  options: { readonly apexOrigin?: string } = {},
): ApexBridge<M> {
  const apexOrigin = options.apexOrigin ?? configuredApexOrigin()
  let clientPromise: Promise<ProxyClient> | null = null

  function client(): Promise<ProxyClient> {
    if (!clientPromise) clientPromise = mountApexProxy({ apexOrigin })
    return clientPromise
  }

  return {
    apexOrigin,
    client,
    async call<T>(method: MethodOf<M>, params?: unknown): Promise<T> {
      const c = await client()
      return params === undefined ? c.call<T>(method) : c.call<T>(method, params)
    },
    subscribe<T>(
      method: MethodOf<M>,
      params: unknown,
      onValue: (value: T) => void,
      onError?: (error: Error) => void,
    ): () => void {
      let cancelled = false
      let unsubscribe: (() => void) | null = null
      client().then(
        (c) => {
          if (cancelled) return
          unsubscribe = onError
            ? c.subscribe<T>(method, params, onValue, onError)
            : c.subscribe<T>(method, params, onValue)
        },
        (error: unknown) => {
          if (!cancelled) onError?.(error instanceof Error ? error : new Error(String(error)))
        },
      )
      return () => {
        cancelled = true
        unsubscribe?.()
        unsubscribe = null
      }
    },
  }
}
