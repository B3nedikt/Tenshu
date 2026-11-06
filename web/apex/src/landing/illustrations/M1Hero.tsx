// M1 — Hero. "Your apps. Your data. Your control." Four of your
// devices around a central relay that only ever holds your data
// locked; clean sync arcs connect them. Rebuilt on the shared-marks
// Canvas so the hero shares one coordinate + stroke system with the
// rest of the landing illustrations. The rules every illustration
// follows: docs/design/landing-illustrations.md.
//
// Two layouts, one composition: the MOBILE frame (16:9, the original)
// and a wider/shorter DESKTOP frame (5:2) with the devices pushed out
// for longer sync lines. Block1Hero swaps them at `md:` so phones keep
// the portrait-friendly proportion and desktop gets a flatter banner
// that lifts the headline + CTA toward the centre of the screen. The
// laptop/monitor pair align on their right edge in both frames so their
// connector bends land on the same x (parallel lines). On the right,
// mobile left-aligns the phone with the tablet (also parallel), while
// desktop centres the phone over the tablet for balance.
//
// Subtle motion (the only animated landing illustration besides
// RealmArt): the concentric rings breathe and a faint pulse travels
// each connector — all CSS, gated under prefers-reduced-motion, defined
// in index.css (`.hero-ring` / `.hero-flow`). The static frame is
// complete on its own; the classes only add life when motion is allowed.
import { Canvas, Connector, Laptop, Monitor, Phone, Relay, Tablet, type PortSpec } from './marks'
import { connectorPath, port, type Box } from './marks/geometry'

interface HeroLayout {
  readonly viewBox: string
  readonly aspectClassName: string
  readonly cx: number
  readonly cy: number
  readonly rings: readonly number[]
  readonly relay: Box
  readonly laptop: Box
  readonly monitor: Box
  readonly phone: Box
  readonly tablet: Box
}

// MOBILE — the original 16:9 frame. Nominal mark sizes: Relay 72×108,
// Laptop 124×84, Phone 40×78, Monitor 128×84, Tablet 78×102. Phone is
// left-aligned with the tablet (x:372) so their connector bends align.
const MOBILE: HeroLayout = {
  viewBox: '0 0 480 270',
  aspectClassName: 'aspect-video',
  cx: 240,
  cy: 135,
  rings: [92, 124],
  relay: { x: 204, y: 81, w: 72, h: 108 },
  laptop: { x: 26, y: 20, w: 124, h: 84 },
  monitor: { x: 24, y: 150, w: 128, h: 84 },
  phone: { x: 372, y: 26, w: 40, h: 78 },
  tablet: { x: 372, y: 150, w: 78, h: 102 },
}

// DESKTOP — a wider, flatter 5:2 banner. Same marks, but the relay is
// centred and the devices sit near the corners so the sync lines are
// longer; the shorter height pulls the headline + CTA up the page.
//
// IMPORTANT: a box's `h` here is the *screen* height used for connector
// anchoring, NOT the mark's full drawn height. The Monitor's stand
// reaches y+106 (screen 84 + neck + foot) and the Tablet y+102, so the
// bottom row is placed to keep those feet above the 216 floor with a
// margin — don't lower them past ~y:104/108 or the stand clips again.
const DESKTOP: HeroLayout = {
  viewBox: '0 0 540 216',
  aspectClassName: 'aspect-[5/2]',
  cx: 270,
  cy: 108,
  rings: [76, 100],
  relay: { x: 234, y: 54, w: 72, h: 108 },
  laptop: { x: 8, y: 12, w: 124, h: 84 },
  monitor: { x: 6, y: 104, w: 128, h: 84 },
  // Phone centred over the tablet (centre x 449) rather than sharing its
  // left edge — the narrow phone looks lopsided when left-aligned. The
  // trade-off is its connector bend no longer lines up with the tablet's;
  // centring pushes the phone out, so its sync line runs a touch longer.
  phone: { x: 429, y: 12, w: 40, h: 78 },
  tablet: { x: 410, y: 108, w: 78, h: 102 },
}

function Constellation({ layout }: { readonly layout: HeroLayout }) {
  const { relay, laptop, monitor, phone, tablet } = layout
  // The four relay↔device hops, one source for both the static base
  // Connector and the animated flow overlay. The relay's two left and
  // two right ports fan out by one PORT_SPREAD so they don't stack;
  // parallel ports (relay left/right ↔ device left/right) give a Z.
  const hops: readonly { readonly from: PortSpec; readonly to: PortSpec }[] = [
    { from: { box: relay, side: 'left', index: 0, count: 2 }, to: { box: laptop, side: 'right' } },
    { from: { box: relay, side: 'left', index: 1, count: 2 }, to: { box: monitor, side: 'right' } },
    { from: { box: relay, side: 'right', index: 0, count: 2 }, to: { box: phone, side: 'left' } },
    { from: { box: relay, side: 'right', index: 1, count: 2 }, to: { box: tablet, side: 'left' } },
  ]
  return (
    <Canvas
      label="Four of your devices syncing through a relay that only ever holds your data encrypted"
      viewBox={layout.viewBox}
      aspectClassName={layout.aspectClassName}
    >
      {/* Faint orbit rings — the constellation read; they breathe via
          `.hero-ring` (see index.css). */}
      <g>
        {layout.rings.map((r, i) => (
          <circle
            key={r}
            className={i === 0 ? 'hero-ring' : 'hero-ring hero-ring--delayed'}
            cx={layout.cx}
            cy={layout.cy}
            r={r}
          />
        ))}
      </g>

      {/* Sync hops — protected (solid). Static base line + an animated
          flow pulse on top, both from the same hop geometry. The pulse
          is staggered per line so they don't fire in unison. */}
      {hops.map((hop, i) => (
        <Connector key={`base-${i}`} from={hop.from} to={hop.to} />
      ))}
      {hops.map((hop, i) => {
        const fp = port(hop.from.box, hop.from.side, hop.from.index ?? 0, hop.from.count ?? 1)
        const tp = port(hop.to.box, hop.to.side, hop.to.index ?? 0, hop.to.count ?? 1)
        return (
          <path
            key={`flow-${i}`}
            className="hero-flow"
            d={connectorPath(fp, hop.from.side, tp, hop.to.side)}
            style={{ animationDelay: `${-i * 1.1}s` }}
          />
        )
      })}

      <Relay x={relay.x} y={relay.y} />
      <Laptop x={laptop.x} y={laptop.y} />
      <Phone x={phone.x} y={phone.y} />
      <Monitor x={monitor.x} y={monitor.y} />
      <Tablet x={tablet.x} y={tablet.y} />
    </Canvas>
  )
}

export function M1Hero() {
  return (
    <>
      <div className="md:hidden">
        <Constellation layout={MOBILE} />
      </div>
      <div className="hidden md:block">
        <Constellation layout={DESKTOP} />
      </div>
    </>
  )
}
