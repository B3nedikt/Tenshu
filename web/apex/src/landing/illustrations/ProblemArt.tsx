// M2 — Problem. "How big tech works today." Your photos and
// documents sit readable inside a company's cloud; a screen on the far
// side pulls them down. Flows are dashed and open — nothing protects
// them. The readable File/Image in the cloud is the deliberate
// contrast with AnswerArt, where the same data is only ever locked.
//
// Subtle motion (CSS, reduced-motion-gated — see index.css `.hero-flow`):
// a faint pulse travels each leak line so the "exfiltration" reads as
// live, not static. The pulse runs phone→cloud (uploading) and
// cloud→monitor (the far side pulling data down) — opposite directions
// of travel, matching the message that *your* data ends up somewhere
// you don't see.
//
// Style rules: docs/design/landing-illustrations.md.
import { Canvas, Cloud, Connector, File, Image, Monitor, Phone } from './marks'
import { connectorPath, port } from './marks/geometry'

// Element boxes (viewBox units). Cloud is the one element where the
// bounding box is bigger than the visible blob — the bottom-centre
// anchor sits a touch below the drawn shape; LINE_GAP covers it.
// Nominal mark sizes: Phone 40×78, Monitor screen 128×84, Cloud
// 92×54 (here scaled 1.8).
const PHONE = { x: 44, y: 150, w: 40, h: 78 }
const MONITOR = { x: 330, y: 120, w: 128, h: 84 }
const CLOUD = { x: 124, y: 16, w: 92 * 1.8, h: 54 * 1.8 }

// Animated overlay paths. Static base lines (Connector below) stay
// dashed; these flow paths use the geometry but pick the direction so
// the pulse reads as "phone uploads" and "PC pulls down" respectively.
const PHONE_TO_CLOUD = connectorPath(
  port(PHONE, 'right'),
  'right',
  port(CLOUD, 'bottom', 0, 2),
  'bottom',
)
const CLOUD_TO_MONITOR = connectorPath(
  port(CLOUD, 'bottom', 1, 2),
  'bottom',
  port(MONITOR, 'left'),
  'left',
)

export function ProblemArt() {
  return (
    <Canvas label="Your photos and documents sit readable in a company's cloud, pulled down by a server">
      <Phone x={PHONE.x} y={PHONE.y} />

      {/* The company cloud — big enough to hold your data, in plain
          sight. Centred between the phone and the monitor and lifted
          so it reads as a row above both devices. */}
      <Cloud x={CLOUD.x} y={CLOUD.y} scale={1.8} />
      <g opacity="0.85">
        <Image x={162} y={68} scale={0.6} />
        <File x={224} y={62} scale={0.6} />
      </g>

      <Monitor x={MONITOR.x} y={MONITOR.y} />

      {/* Open, unprotected flows — exposed (dashed): data into the
          company cloud and on to the server. Both leave their device
          at the side-midpoint facing the cloud and arrive at the
          cloud's bottom edge, fanned by one PORT_SPREAD so they don't
          overlap. Perpendicular ports (device side → cloud bottom) →
          rounded L. */}
      <Connector from={{ box: PHONE, side: 'right' }} to={{ box: CLOUD, side: 'bottom', index: 0, count: 2 }} exposed />
      <Connector from={{ box: MONITOR, side: 'left' }} to={{ box: CLOUD, side: 'bottom', index: 1, count: 2 }} exposed />

      {/* Live leak pulse on each line, staggered half a cycle apart. */}
      <path className="hero-flow" d={PHONE_TO_CLOUD} />
      <path className="hero-flow" d={CLOUD_TO_MONITOR} style={{ animationDelay: '-3.25s' }} />
    </Canvas>
  )
}
