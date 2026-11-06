// M3 — Answer. "How it should be." The same rule on every device:
// your app (Drive) only ever talks to Control — the proxy app — on
// that device; Control is the only thing that reaches the network.
// Composition follows M2: the two devices sit in a lower row and the
// relay sits centred (by the device frames, not the apps) in a row
// above them.
//
// The phone is dressed as an Android-style launcher to make it obvious
// that Drive and Control are *apps*: a status bar up top, a blurred-out
// day/time/date widget in the upper-left, two rows of four app tiles
// and a four-icon dock along the bottom. Every tile is a dimmed
// placeholder except two — Control and Drive, drawn with their real
// app icons so they read as the real apps among many. The
// structural hops still hold: Drive → Control (on-device) and
// Control → relay (the only app that reaches the network).
//
// Every sync hop is drawn in the hero's line style (see Hop): a faint
// static base with an animated flow pulse on top, so all four lines
// match instead of mixing a solid stub with the gray connectors.
//
// The desktop gets the same treatment dressed as a macOS desktop: a menu
// bar up top, Control as the foreground app, and a dock at the bottom
// holding Drive among dimmed placeholders — the same Drive → Control →
// relay structure in its native form. Drive and Control are the one
// documented break from the one-colour rule: their real launcher icons
// (light + dark copies, switched by theme in index.css) so the reader
// recognises the apps. Style rules in docs/design/landing-illustrations.md.
import { useId } from 'react'
import { Canvas, Relay, type PortSpec } from './marks'
import { connectorPath, port, type Box } from './marks/geometry'

// Element boxes (viewBox units) — single source of truth for both
// placement and connector anchoring. The phone/desktop frames are
// drawn inline (not the Phone/Monitor marks) so their larger size
// keeps a uniform stroke weight; the boxes still describe them.
const PHONE = { x: 44, y: 112, w: 80, h: 144 }
const DESKTOP = { x: 296, y: 122, w: 140, h: 104 }
const RELAY = { x: 174, y: 12, w: 72, h: 108 }

// Launcher grid: four columns, two icon rows + a bottom dock. Control
// sits in the rightmost cell of row 1 and Drive in the dock below it;
// the cell between them (row 2, column d) is left open so their connector
// has room for the same padding as every other line. Every other cell is
// a dimmed placeholder tile.
const COL = { a: 60, b: 76, c: 92, d: 108 } // four columns; d faces the relay
const ROW1 = 168
const ROW2 = 190 // tight, natural row spacing (small gap to ROW1)
const DOCK = 224 // separated from the rows by spacing alone — no pill
const TILE = 13 // placeholder tile size
const ICON = 15 // real app icon size

// Dimmed placeholder app tiles — every cell except Control (row 1, col d),
// Drive (dock, col d), and the open cell between them (row 2, col d).
const TILES: readonly { readonly cx: number; readonly cy: number }[] = [
  { cx: COL.a, cy: ROW1 }, { cx: COL.b, cy: ROW1 }, { cx: COL.c, cy: ROW1 },
  { cx: COL.a, cy: ROW2 }, { cx: COL.b, cy: ROW2 }, { cx: COL.c, cy: ROW2 },
  { cx: COL.a, cy: DOCK }, { cx: COL.b, cy: DOCK }, { cx: COL.c, cy: DOCK },
]

// Control (row 1) + Drive (dock), rightmost column. Boxes describe the
// 15×15 footprint for connector anchoring.
const CTRL_P = { x: COL.d - ICON / 2, y: ROW1 - ICON / 2, w: ICON, h: ICON }
const DRIVE_P = { x: COL.d - ICON / 2, y: DOCK - ICON / 2, w: ICON, h: ICON }

// The desktop is dressed as a macOS desktop with the same means as the
// phone: a menu bar up top, Control as the foreground app, and a dock at
// the bottom holding Drive among dimmed placeholders. Control and Drive
// share a column (MAC_COL) so their hop runs straight up, and icons are
// 15px to match the phone.
const MAC_COL = 340
const CTRL_D = { x: MAC_COL - ICON / 2, y: 156 - ICON / 2, w: ICON, h: ICON }
const DRIVE_D = { x: MAC_COL - ICON / 2, y: 209 - ICON / 2, w: ICON, h: ICON }

// Dock placeholder apps, to the right of Drive.
const DOCK_D_TILES: readonly { readonly cx: number; readonly cy: number }[] = [
  { cx: 358, cy: 209 }, { cx: 376, cy: 209 }, { cx: 394, cy: 209 },
]

// A real app icon (public/icons/apps) filling `box`, clipped to the
// launcher squircle the website uses elsewhere (.hub-icon, 22.5% radius).
// Both theme copies are drawn; CSS shows the one for the active theme.
function AppIcon({ app, box }: { readonly app: 'drive' | 'control'; readonly box: Box }) {
  const clip = useId()
  const { x, y, w, h } = box
  return (
    <g stroke="none">
      <clipPath id={clip}>
        <rect x={x} y={y} width={w} height={h} rx={w * 0.225} />
      </clipPath>
      {(['light', 'dark'] as const).map(theme => (
        <image
          key={theme}
          className={`art-app-icon--${theme}`}
          href={`/icons/apps/${app}${theme === 'dark' ? '-dark' : ''}.webp`}
          x={x}
          y={y}
          width={w}
          height={h}
          clipPath={`url(#${clip})`}
        />
      ))}
    </g>
  )
}

// One sync hop, drawn in the hero's two-layer line style: a faint static
// base (the legible frame) plus a `.hero-flow` pulse that travels the
// same path (animated, reduced-motion-gated — see index.css). `gap` is
// the padding trimmed off both ends so the line clears the icons; the
// staggered `delay` keeps the pulses from firing in unison.
function Hop({
  from,
  to,
  gap = 12,
  delay = 0,
}: {
  readonly from: PortSpec
  readonly to: PortSpec
  readonly gap?: number
  readonly delay?: number
}) {
  const fp = port(from.box, from.side, from.index ?? 0, from.count ?? 1)
  const tp = port(to.box, to.side, to.index ?? 0, to.count ?? 1)
  const d = connectorPath(fp, from.side, tp, to.side, gap)
  return (
    <>
      <path d={d} opacity="0.4" />
      <path d={d} className="hero-flow" style={{ animationDelay: `${delay}s` }} />
    </>
  )
}

export function AnswerArt() {
  return (
    <Canvas label="On both your phone and your desktop, Drive talks only to Control — the proxy app on that device; Control is the only app that reaches the relay, which only ever holds your data locked">
      {/* The phone (lower-left) as a launcher home screen. The frame is
          drawn inline (not the Phone mark) so the larger size keeps a
          uniform stroke weight. */}
      <rect x={PHONE.x} y={PHONE.y} width={PHONE.w} height={PHONE.h} rx="16" />

      {/* Status bar — a few faint filled indicators top-right. */}
      <g fill="currentColor" stroke="none" opacity="0.4">
        <rect x="103" y="115" width="9" height="4.5" rx="1.5" />
        <circle cx="96" cy="117" r="1.4" />
        <circle cx="99.5" cy="117" r="1.4" />
      </g>

      {/* Blurred-out clock widget, upper-left: day of week, big time,
          date — soft filled rounded bars, not real glyphs. */}
      <g fill="currentColor" stroke="none" opacity="0.3">
        <rect x="52" y="124" width="20" height="3.5" rx="1.75" />
        <rect x="52" y="131" width="34" height="7" rx="3.5" />
        <rect x="52" y="143" width="16" height="3" rx="1.5" />
      </g>

      {/* Dimmed placeholder app tiles — filled rounded squares (the
          modern "solid icon" read), not outlines. The dock is the bottom
          row; it's set apart by spacing alone, no background pill. */}
      <g fill="currentColor" stroke="none" opacity="0.2">
        {TILES.map(t => (
          <rect
            key={`${t.cx}-${t.cy}`}
            x={t.cx - TILE / 2}
            y={t.cy - TILE / 2}
            width={TILE}
            height={TILE}
            rx="3.5"
          />
        ))}
      </g>

      {/* The two real apps among the launcher, with their real icons:
          Control (the proxy) in row 1, Drive in the dock below it. */}
      <AppIcon app="control" box={CTRL_P} />
      <AppIcon app="drive" box={DRIVE_P} />

      {/* Home-gesture indicator. */}
      <g opacity="0.4">
        <path d="M69 248H99" />
      </g>

      {/* The relay — only ever holds the data locked. Centred between
          the two device frames and lifted into a row above them. */}
      <Relay x={RELAY.x} y={RELAY.y} />

      {/* The desktop (lower-right) — a monitor with a floating stand,
          its screen dressed as a macOS desktop. Frame drawn inline so the
          larger size keeps a uniform stroke weight. */}
      <g>
        <rect x={DESKTOP.x} y={DESKTOP.y} width={DESKTOP.w} height={DESKTOP.h} rx="8" />
        <path d="M366 226V240" />
        <path d="M342 248H390" />
      </g>

      {/* macOS menu bar — Apple mark + menu items left, status + clock
          right — over a hairline. Placeholder glyphs, like the phone. */}
      <g fill="currentColor" stroke="none" opacity="0.45">
        <circle cx="306" cy="126" r="2" />
        <rect x="314" y="124.5" width="9" height="2.5" rx="1.25" />
        <rect x="327" y="124.5" width="6" height="2.5" rx="1.25" />
        <circle cx="404" cy="126" r="1.4" />
        <circle cx="410" cy="126" r="1.4" />
        <rect x="415" y="124.5" width="14" height="3" rx="1.5" />
      </g>
      <path d="M296 131H436" opacity="0.2" />

      {/* Dock — a faint rounded shelf set apart at the bottom, holding
          Drive (full strength) among dimmed placeholder apps. */}
      <rect x="329" y="199" width="76" height="20" rx="7" fill="currentColor" stroke="none" opacity="0.12" />
      <g fill="currentColor" stroke="none" opacity="0.2">
        {DOCK_D_TILES.map(t => (
          <rect key={`d-${t.cx}`} x={t.cx - TILE / 2} y={t.cy - TILE / 2} width={TILE} height={TILE} rx="3" />
        ))}
      </g>

      {/* Desktop apps, real icons: Control as the foreground app,
          Drive in the dock below it. */}
      <AppIcon app="control" box={CTRL_D} />
      <AppIcon app="drive" box={DRIVE_D} />

      {/* Sync hops — all in the hero's animated line style, all with the
          same padding from the icons. Drive → Control on each device
          (the phone's runs up the open right column from the dock), then
          Control → relay (the relay's two connectors fan out on its
          bottom edge by one PORT_SPREAD). Staggered so they don't pulse
          in unison. */}
      <Hop from={{ box: DRIVE_P, side: 'top' }} to={{ box: CTRL_P, side: 'bottom' }} delay={0} />
      <Hop from={{ box: CTRL_P, side: 'right' }} to={{ box: RELAY, side: 'bottom', index: 0, count: 2 }} delay={-1.6} />
      <Hop from={{ box: DRIVE_D, side: 'top' }} to={{ box: CTRL_D, side: 'bottom' }} delay={-3.2} />
      <Hop from={{ box: CTRL_D, side: 'left' }} to={{ box: RELAY, side: 'bottom', index: 1, count: 2 }} delay={-4.8} />
    </Canvas>
  )
}
