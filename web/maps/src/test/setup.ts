// Vitest setup (`tenshuAppConfig` wires `src/test/setup.ts` as `test.setupFiles`): the app-kit's
// shared one — jest-dom matchers + RTL cleanup. Importing it here also puts the matcher types in
// this package's program.
import '@tenshu/app-kit/testing/setup'

// jsdom has no canvas: map-kit's WebGL probe would log "Not implemented: getContext()" once per map.
// `null` is what jsdom answers anyway (no WebGL), so this changes no outcome, only the noise.
HTMLCanvasElement.prototype.getContext = (() => null) as typeof HTMLCanvasElement.prototype.getContext
