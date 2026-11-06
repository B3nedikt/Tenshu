// The W-MAP dev harness (NOT deployed, NOT an app: its config lives here so `deploy/lib/web-apps.sh`
// never finds it). Renders the Firenze demo extract through the SAME `RegionArchiveSource` an app
// uses, behind a fixture `RegionAccess` instead of the bridge — so the renderer works before any
// tile transport is decided (maps-region-delivery.md §Web scope W.1).
//   pnpm -F @tenshu/map-kit harness   → http://localhost:5190
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { tenshuMapGlyphs } from '../src/vite'

const here = dirname(fileURLToPath(import.meta.url))
const DEMO = resolve(here, '../../../ui-map/src/androidMain/assets/tenshu_basemap.pmtiles')

export default defineConfig({
  root: here,
  plugins: [
    react(),
    tenshuMapGlyphs(),
    {
      name: 'tenshu-map-harness-fixture',
      configureServer(server) {
        server.middlewares.use('/fixture/tenshu_basemap.pmtiles', (_req, res) => {
          res.setHeader('Content-Type', 'application/octet-stream')
          res.end(readFileSync(DEMO))
        })
      },
    },
  ],
  // MapLibre and pmtiles as chunks of their own, so the size report names them separately.
  build: {
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            { name: 'maplibre-gl', test: /node_modules[\\/].*maplibre-gl/ },
            { name: 'pmtiles', test: /node_modules[\\/].*(pmtiles|fflate)/ },
          ],
        },
      },
    },
  },
  server: { port: 5190, strictPort: true, fs: { allow: [resolve(here, '..'), resolve(here, '../..')] } },
})
