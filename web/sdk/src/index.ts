// Aggregated entry point: re-exports everything from the per-service
// modules. New code should prefer the per-service paths
// (`@tenshu/sdk/files`, `/identity`, `/notifications`, `/core`) — they
// match the npm `exports` map declared in `package.json` and they're
// where real bundle splitting will land. This barrel stays for
// convenience and back-compat; see docs/web/architecture.md §12.11.

export * from './core'
export * from './identity'
export * from './notifications'
export * from './files'
export * from './bridge'
export * from './share'
export * from './contact'
export * from './membership'
export * from './sharedFiles'
export * from './content'
export * from './ingest'
export * from './onboarding'
