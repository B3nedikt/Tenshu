/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** The apex origin this app dials for the bridge (see `lib/proxy.ts`). */
  readonly VITE_APEX_ORIGIN?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
