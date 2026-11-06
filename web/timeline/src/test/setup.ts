// Vitest setup (`tenshuAppConfig` wires `src/test/setup.ts` as `test.setupFiles`): the app-kit's
// shared one — jest-dom matchers + RTL cleanup. Importing it here also puts the matcher types in
// this package's program.
import '@tenshu/app-kit/testing/setup'

// ⚠ Every test renders the stand-in map (`mapKitMock.ts`), never MapLibre: jsdom has no WebGL.
import { vi } from 'vitest'

vi.mock('@tenshu/map-kit', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tenshu/map-kit')>()),
  TenshuMap: (await import('./mapKitMock')).FakeTenshuMap,
}))
