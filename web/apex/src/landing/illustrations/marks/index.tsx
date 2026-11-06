// Minimal single-stroke marks shared across the landing illustrations.
//
// Conventions:
// - Every mark draws in its own local coordinate box anchored at
//   (0,0) top-left; the caller positions it with `x` / `y` and can
//   `scale`. Nominal box sizes are noted per mark.
// - No stroke attributes here: the parent <svg> sets
//   stroke="currentColor", fill="none", strokeWidth, and round caps,
//   and SVG presentation attributes inherit. So a mark is just
//   geometry, and theming is "set the text color on the block".
// - Muted/secondary detail goes in <g opacity={…}> so it dims with
//   the same color in light and dark.
import type { ReactNode } from 'react'
import { connectorEnd, connectorPath, port, type Box, type Side } from './geometry'

export interface MarkProps {
  readonly x?: number
  readonly y?: number
  readonly scale?: number
}

function At({
  x = 0,
  y = 0,
  scale = 1,
  children,
}: MarkProps & { readonly children: ReactNode }) {
  const t = scale === 1 ? `translate(${x} ${y})` : `translate(${x} ${y}) scale(${scale})`
  return <g transform={t}>{children}</g>
}

// Phone — nominal 40 × 78. The standard portrait device frame,
// sibling to Monitor: a rounded frame plus one faint bottom
// home-indicator line (no top line, no home-button dot). Two modes:
// *abstract* (the default — no children) draws three faint screen
// lines, the generic "this is a phone" read (M2/ProblemArt,
// M1); *inhabited* (given children) puts real content inside
// instead — e.g. stacked app icons (M3/AnswerArt draws its larger
// phone inline for the same stroke-weight reason as its desktop —
// this marks system scales stroke with the transform — but follows
// this exact look). See docs/design/landing-illustrations.md
// "Style contract".
export function Phone({ children, ...p }: MarkProps & { readonly children?: ReactNode }) {
  return (
    <At {...p}>
      <rect x="0" y="0" width="40" height="78" rx="8" />
      {children ?? (
        <g opacity="0.4">
          <path d="M10 22H30" />
          <path d="M10 34H30" />
          <path d="M10 46H24" />
        </g>
      )}
      <g opacity="0.4">
        <path d="M14 68H26" />
      </g>
    </At>
  )
}

// Laptop — nominal 124 × 84. A screen panel with rounded top corners
// but a square bottom (it meets the base at a 90° angle, no curve) on
// a small thin rounded keyboard base joined flush to the screen's
// bottom edge (no gap, no notch). Same two modes as Phone/Monitor:
// *abstract* (default — no children) draws three faint screen lines;
// *inhabited* (given children) puts real content in the screen panel
// instead (children are in the mark's local coords and scale with it).
export function Laptop({ children, ...p }: MarkProps & { readonly children?: ReactNode }) {
  return (
    <At {...p}>
      <path d="M16 54 L16 7 Q16 0 23 0 L101 0 Q108 0 108 7 L108 54 Z" />
      {children ?? (
        <g opacity="0.4">
          <path d="M28 15H68" />
          <path d="M28 29H94" />
          <path d="M28 43H80" />
        </g>
      )}
      <rect x="12" y="54" width="100" height="8" rx="3" />
    </At>
  )
}

// Monitor — nominal 128 × 108 (screen + stand). The standard
// desktop device frame, sibling to Phone. The stand is deliberately
// "floating": the neck stops short of the foot so there is a small
// gap between the vertical neck and the horizontal base — the
// sleeker, modern read. Like Phone it has two modes: *abstract*
// (the default — no children) draws three faint screen lines, the
// generic "this is a desktop" read (M2/ProblemArt, M1);
// *inhabited* (given children) puts real content inside the screen
// instead — e.g. stacked app icons (M3/AnswerArt draws its larger
// desktop inline for the same stroke-weight reason as its phone,
// but follows this exact look). See
// docs/design/landing-illustrations.md "Style contract".
export function Monitor({ children, ...p }: MarkProps & { readonly children?: ReactNode }) {
  return (
    <At {...p}>
      <rect x="0" y="0" width="128" height="84" rx="6" />
      {children ?? (
        <g opacity="0.4">
          <path d="M16 18H78" />
          <path d="M16 34H112" />
          <path d="M16 50H92" />
        </g>
      )}
      <path d="M64 84V98" />
      <path d="M40 106H88" />
    </At>
  )
}

// Tablet — nominal 78 × 102. Faint bottom indicator line instead of
// a home-button dot, matching the Phone. Same two modes as
// Phone/Monitor: *abstract* (default — no children) draws three faint
// screen lines; *inhabited* (given children) puts real content on the
// screen instead (children are in the mark's local coords).
export function Tablet({ children, ...p }: MarkProps & { readonly children?: ReactNode }) {
  return (
    <At {...p}>
      <rect x="0" y="0" width="78" height="102" rx="9" />
      {children ?? (
        <g opacity="0.4">
          <path d="M14 16H64" />
          <path d="M14 32H64" />
          <path d="M14 48H50" />
        </g>
      )}
      <g opacity="0.4">
        <path d="M28 90H50" />
      </g>
    </At>
  )
}

// Cloud — nominal 92 × 54 (single outline).
export function Cloud(p: MarkProps) {
  return (
    <At {...p}>
      <path d="M22 52C10 52 2 44 2 33C2 23 10 16 19 16C22 7 31 1 41 1C54 1 64 11 65 23C78 23 90 31 90 41C90 48 84 52 76 52H22Z" />
    </At>
  )
}

// Server / relay node — nominal 72 × 108. A minimalist server rack:
// three stacked units, each with a "screw" dot on the left and two
// rows of faint hex-like ciphertext (grey, like the other devices'
// detail) — the relay only ever holds encrypted data.
export function Relay(p: MarkProps) {
  const units = [
    { y: 10, a: 'A7F2C9E4', b: '3D8B016A' },
    { y: 41, a: 'E5109C7F', b: 'B2A6D34E' },
    { y: 72, a: '07C3FA9D', b: '9E14B8C2' },
  ]
  return (
    <At {...p}>
      <rect x="0" y="0" width="72" height="108" rx="8" />
      {units.map((u) => (
        <g key={u.y}>
          <rect x="8" y={u.y} width="56" height="26" rx="3" />
          <circle cx="15" cy={u.y + 13} r="1.7" fill="currentColor" stroke="none" />
          <g
            opacity="0.4"
            fill="currentColor"
            stroke="none"
            fontFamily="monospace"
            fontSize="6.5"
          >
            <text x="22" y={u.y + 11}>{u.a}</text>
            <text x="22" y={u.y + 21}>{u.b}</text>
          </g>
        </g>
      ))}
    </At>
  )
}

// Document — nominal 38 × 50, folded corner.
export function File(p: MarkProps) {
  return (
    <At {...p}>
      <path d="M2 2H24L36 14V48H2Z" />
      <path d="M24 2V14H36" />
      <g opacity="0.4">
        <path d="M9 24H29" />
        <path d="M9 32H29" />
        <path d="M9 40H22" />
      </g>
    </At>
  )
}

// Image — nominal 40 × 32, framed picture with sun + mountain ridge.
// Used as readable "your data" inside the company cloud in ProblemArt.
export function Image(p: MarkProps) {
  return (
    <At {...p}>
      <rect x="0" y="0" width="40" height="32" rx="4" />
      <circle cx="11" cy="10" r="3.5" />
      <path d="M3 27L14 15L21 22L29 13L37 23" />
    </At>
  )
}

// Disc — nominal 30 × 30. Temporary placeholder icon for Drive: a
// CD-like disc with a centre hole and a faint sheen arc. Swap for the
// real Drive mark when it exists.
export function Disc(p: MarkProps) {
  return (
    <At {...p}>
      <circle cx="15" cy="15" r="14" />
      <circle cx="15" cy="15" r="4" />
      <path d="M15 3A12 12 0 0 1 27 15" opacity="0.4" />
    </At>
  )
}

// Cog — nominal 30 × 30. Temporary placeholder icon for Control: a
// simple gear (body + hole + eight short radial teeth). Swap for the
// real Control mark when it exists.
export function Cog(p: MarkProps) {
  return (
    <At {...p}>
      <circle cx="15" cy="15" r="9" />
      <circle cx="15" cy="15" r="3" />
      <path d="M15 2V6" />
      <path d="M15 24V28" />
      <path d="M2 15H6" />
      <path d="M24 15H28" />
      <path d="M5.8 5.8L8.6 8.6" />
      <path d="M21.4 21.4L24.2 24.2" />
      <path d="M24.2 5.8L21.4 8.6" />
      <path d="M5.8 24.2L8.6 21.4" />
    </At>
  )
}

// Shared illustration frame. Sets the single stroke color (themed:
// near-black in light, near-white in dark), round caps, and no fills —
// every mark inside inherits these. Muted marks dim via their own
// opacity groups, so the whole illustration is one color. Defaults to
// the 16:9 480×270 system every M1–M3 illustration uses; `viewBox` +
// `aspectClassName` let a single illustration (e.g. RealmArt, which is
// a square composition that should sit as tall as the copy beside it)
// override the frame without affecting the others.
export function Canvas({
  label,
  viewBox = '0 0 480 270',
  aspectClassName = 'aspect-video',
  children,
}: {
  readonly label: string
  readonly viewBox?: string
  readonly aspectClassName?: string
  readonly children: ReactNode
}) {
  return (
    <div
      role="img"
      aria-label={label}
      className={`${aspectClassName} w-full text-text`}
    >
      <svg
        viewBox={viewBox}
        className="h-full w-full"
        fill="none"
        stroke="currentColor"
        strokeWidth="2.25"
        strokeLinecap="round"
        strokeLinejoin="round"
        xmlns="http://www.w3.org/2000/svg"
        aria-hidden="true"
      >
        {children}
      </svg>
    </div>
  )
}

// Small filled arrowhead, pointing along `angle` (degrees, 0 = right).
// Marks set fill:none, so this one re-enables fill explicitly.
export function Arrowhead({
  x,
  y,
  angle = 0,
}: {
  readonly x: number
  readonly y: number
  readonly angle?: number
}) {
  return (
    <path
      d="M0 -4 L8 0 L0 4 Z"
      fill="currentColor"
      stroke="none"
      transform={`translate(${x} ${y}) rotate(${angle})`}
    />
  )
}

// A connector endpoint: which element box, which side of it, and —
// when several connectors share that side — `index`/`count` so they
// fan out by one fixed PORT_SPREAD instead of stacking.
export interface PortSpec {
  readonly box: Box
  readonly side: Side
  readonly index?: number
  readonly count?: number
}

// The one standardized connector for every illustration. Each end is
// a port: the midpoint of a side of an element's bounding box (fanned
// when shared). Single look: currentColor at 0.4 opacity (themes with
// the block — no fixed grey), the shared LINE_GAP inset, and straight
// segments meeting both elements perpendicular to their edge through
// rounded corners in the device-frame radius family (a rounded L for
// perpendicular ports, a symmetric rounded Z for parallel ones — no
// free bezier bow). Line *style* is the only thing that carries
// meaning:
//   - `exposed` → dashed: the data on this hop is unprotected (the M2
//     "open leak" beat).
//   - default   → solid: the data is protected (M1, M3).
// `arrow` draws an arrowhead at the trimmed end, facing into the
// element.
export function Connector({
  from,
  to,
  exposed = false,
  arrow = false,
}: {
  readonly from: PortSpec
  readonly to: PortSpec
  readonly exposed?: boolean
  readonly arrow?: boolean
}) {
  const fp = port(from.box, from.side, from.index ?? 0, from.count ?? 1)
  const tp = port(to.box, to.side, to.index ?? 0, to.count ?? 1)
  const end = connectorEnd(tp, to.side)
  return (
    <g opacity="0.4">
      <path
        d={connectorPath(fp, from.side, tp, to.side)}
        strokeDasharray={exposed ? '5 6' : undefined}
      />
      {arrow && <Arrowhead x={end.point.x} y={end.point.y} angle={end.angle} />}
    </g>
  )
}

