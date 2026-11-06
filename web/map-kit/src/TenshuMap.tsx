// `<TenshuMap>`: the shared web map (web-app-suite.md §4, W-MAP) for Maps' places, Timeline's trips
// and Fit's routes.
//
// ⚠⚠ MapLibre is LAZY: this file — the one an app imports — reaches neither `maplibre-gl` nor
// `pmtiles` statically (`lazySplit.test.ts`). They load with `MapView` the first time a map renders,
// so an app's other routes, and the apex, never download them.
// ⚠ Never a blank box: no WebGL, a failed chunk, a refused region lookup — each is an explicit
// "Map unavailable" with a reason.
import { Component, lazy, Suspense, useState, type ReactNode } from 'react'
import { MapUnavailable } from './chrome'
import { DEFAULT_MAP_LABELS, type TenshuMapProps } from './props'
import type { MapTheme } from './style/basemapStyle'
import { supportsWebGL } from './webgl'

const MapView = lazy(() => import('./MapView'))

class ChunkBoundary extends Component<{ fallback: ReactNode; children: ReactNode }, { failed: boolean }> {
  state = { failed: false }
  static getDerivedStateFromError() {
    return { failed: true }
  }
  render() {
    return this.state.failed ? this.props.fallback : this.props.children
  }
}

function systemTheme(): MapTheme {
  return typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export function TenshuMap(props: TenshuMapProps) {
  const labels = { ...DEFAULT_MAP_LABELS, ...props.labels }
  // Probed once per mount: a browser does not gain WebGL mid-session.
  const [webgl] = useState(() => supportsWebGL())
  if (!webgl) return <MapUnavailable title={labels.unavailable} reason={labels.noWebGL} className={props.className} />
  const unavailable = <MapUnavailable title={labels.unavailable} reason={labels.tilesFailed} className={props.className} />
  return (
    <ChunkBoundary fallback={unavailable}>
      <Suspense fallback={<div role="status" className={props.className}>{labels.loading}</div>}>
        <MapView
          {...props}
          theme={props.theme ?? systemTheme()}
          language={props.language ?? (typeof navigator === 'undefined' ? undefined : navigator.language)}
          labels={labels}
        />
      </Suspense>
    </ChunkBoundary>
  )
}
