// A stand-in for `@tenshu/map-kit`'s `<TenshuMap>` in every component test (wired in `setup.ts`):
// jsdom has no WebGL, and what is under test here is what the app hands the map — overlays, camera,
// copy — and that the screen stays usable whatever the map does. map-kit's own states are pinned in
// `web/map-kit` (`TenshuMap.test.tsx`).
//
// Like the real map, it looks its region up through `regions.regionAt` once mounted, so an
// unconfigured transport reaches the app's wrapper (`AppMap`) exactly as in a browser.
// ⚠ `.ts` with `createElement`, not `.tsx`: a module exporting a component AND helpers trips
// `react-refresh/only-export-components`, which only checks JSX files.
import { createElement, useEffect } from 'react'
import type { TenshuMapProps } from '@tenshu/map-kit'

// Every render's props, newest last.
export const drawnMaps: TenshuMapProps[] = []

export function lastMap(): TenshuMapProps {
  const props = drawnMaps.at(-1)
  if (!props) throw new Error('no map was drawn')
  return props
}

// What map-kit does when the user pans: look the region up again for the new centre, through the
// same `regions` the app handed it. Resolves with the wrapper's answer (or its rejection).
export function panTo(lat: number, lng: number) {
  return lastMap().regions.regionAt(lat, lng)
}

export function FakeTenshuMap(props: TenshuMapProps) {
  drawnMaps.push(props)
  const { regions, camera } = props
  useEffect(() => {
    const at = camera.kind === 'center' ? camera.center : camera.points[0]
    if (at) regions.regionAt(at.lat, at.lng).catch(() => {})
    // Once per mount, as map-kit resolves once per camera centre.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  return createElement('div', { role: 'region', 'aria-label': props.labels?.map, 'data-testid': 'tenshu-map' })
}
