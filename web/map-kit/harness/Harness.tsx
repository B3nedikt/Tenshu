import { useState } from 'react'
import { TenshuMap, type MapCamera, type MapTheme } from '../src'
import { fixtureRegionAccess } from './fixture'

const regions = fixtureRegionAccess(() => fetch('/fixture/tenshu_basemap.pmtiles').then((r) => r.arrayBuffer()))

const DUOMO = { lat: 43.7731, lng: 11.256 }
const PONTE_VECCHIO = { lat: 43.768, lng: 11.2531 }
const PITTI = { lat: 43.7652, lng: 11.25 }

const cameras: Record<string, MapCamera> = {
  centre: { kind: 'center', center: DUOMO, zoom: 14.5 },
  route: { kind: 'bounds', points: [DUOMO, PONTE_VECCHIO, PITTI] },
  // Past BASEMAP_MAX_ZOOM: the camera clamps to it.
  tooClose: { kind: 'center', center: PONTE_VECCHIO, zoom: 19 },
  outside: { kind: 'center', center: { lat: 48.137, lng: 11.575 }, zoom: 12 },
}

export function Harness() {
  const [theme, setTheme] = useState<MapTheme>('light')
  const [camera, setCamera] = useState<keyof typeof cameras>('centre')
  return (
    <div>
      <p>
        {Object.keys(cameras).map((name) => (
          <button key={name} onClick={() => setCamera(name)} disabled={name === camera}>{name}</button>
        ))}
        <button onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}>{theme}</button>
      </p>
      <TenshuMap
        className="map"
        regions={regions}
        theme={theme}
        camera={cameras[camera]!}
        pins={[{ id: 'duomo', position: DUOMO, label: 'Duomo' }]}
        polylines={[
          {
            id: 'walk',
            segments: [
              { points: [DUOMO, PONTE_VECCHIO], color: '#2563EB' },
              { points: [PONTE_VECCHIO, PITTI], color: '#DC2626' },
            ],
          },
        ]}
        onPinClick={(id) => console.info('pin', id)}
      />
    </div>
  )
}

