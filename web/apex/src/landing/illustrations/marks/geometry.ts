// Geometry helpers for the landing illustrations. Kept out of
// `index.tsx` so that file can stay component-only (react-refresh).

export interface Point {
  readonly x: number
  readonly y: number
}

// An element's bounding box in viewBox units. Connectors anchor to a
// box + a side, never to hand-picked coordinates — so anchors stay
// consistent, move with the element, and mirror automatically.
export interface Box {
  readonly x: number
  readonly y: number
  readonly w: number
  readonly h: number
}

export type Side = 'top' | 'bottom' | 'left' | 'right'

// Standard inset, in viewBox units, trimmed off *both* ends of every
// connector — applied along the side's outward normal so the line
// always leaves/enters the element straight-on, never grazing it, and
// stays flexible when real icons replace the placeholder marks.
export const LINE_GAP = 8

// Corner radius where a connector turns. In the same family as the
// device-frame corner radii (Phone/Monitor `rx`), so a turning line
// reads with the rounded vocabulary of the frames. Clamped per-leg.
export const CONNECTOR_RADIUS = 12

// Fixed separation between connectors that share one side of an
// element. Co-located lines fan out symmetrically by this amount
// instead of stacking on top of each other.
export const PORT_SPREAD = 14

function round(n: number): number {
  return Math.round(n * 100) / 100
}

function normal(side: Side): Point {
  switch (side) {
    case 'top':
      return { x: 0, y: -1 }
    case 'bottom':
      return { x: 0, y: 1 }
    case 'left':
      return { x: -1, y: 0 }
    case 'right':
      return { x: 1, y: 0 }
  }
}

// The anchor point: the midpoint of `side` of `box`, fanned along
// that edge when several connectors share it. `count` connectors are
// spread `PORT_SPREAD` apart, centred on the midpoint; `index`
// (0-based, ordered along the +axis) selects which.
export function port(box: Box, side: Side, index = 0, count = 1): Point {
  const cx = box.x + box.w / 2
  const cy = box.y + box.h / 2
  const off = (index - (count - 1) / 2) * PORT_SPREAD
  switch (side) {
    case 'top':
      return { x: cx + off, y: box.y }
    case 'bottom':
      return { x: cx + off, y: box.y + box.h }
    case 'left':
      return { x: box.x, y: cy + off }
    case 'right':
      return { x: box.x + box.w, y: cy + off }
  }
}

// A symmetric rounded "Z": straight leg out of `a`, one perpendicular
// middle leg split at the midpoint, straight leg into `b`. Both end
// legs share an axis (so the line meets both elements at 90°), and
// the midpoint split with equal radii makes the route its own mirror.
function zPath(a: Point, b: Point, vertical: boolean, radius: number): string {
  if (vertical) {
    const midY = (a.y + b.y) / 2
    const dA = Math.sign(midY - a.y) || 1
    const dH = Math.sign(b.x - a.x) || 1
    const dB = Math.sign(b.y - midY) || 1
    const r = Math.min(
      radius,
      Math.abs(midY - a.y),
      Math.abs(b.y - midY),
      Math.abs(b.x - a.x) / 2,
    )
    return [
      `M ${round(a.x)} ${round(a.y)}`,
      `L ${round(a.x)} ${round(midY - dA * r)}`,
      `Q ${round(a.x)} ${round(midY)} ${round(a.x + dH * r)} ${round(midY)}`,
      `L ${round(b.x - dH * r)} ${round(midY)}`,
      `Q ${round(b.x)} ${round(midY)} ${round(b.x)} ${round(midY + dB * r)}`,
      `L ${round(b.x)} ${round(b.y)}`,
    ].join(' ')
  }
  const midX = (a.x + b.x) / 2
  const dA = Math.sign(midX - a.x) || 1
  const dV = Math.sign(b.y - a.y) || 1
  const dB = Math.sign(b.x - midX) || 1
  const r = Math.min(
    radius,
    Math.abs(midX - a.x),
    Math.abs(b.x - midX),
    Math.abs(b.y - a.y) / 2,
  )
  return [
    `M ${round(a.x)} ${round(a.y)}`,
    `L ${round(midX - dA * r)} ${round(a.y)}`,
    `Q ${round(midX)} ${round(a.y)} ${round(midX)} ${round(a.y + dV * r)}`,
    `L ${round(midX)} ${round(b.y - dV * r)}`,
    `Q ${round(midX)} ${round(b.y)} ${round(midX + dB * r)} ${round(b.y)}`,
    `L ${round(b.x)} ${round(b.y)}`,
  ].join(' ')
}

// A single rounded "L": straight leg a→corner, one fillet, straight
// leg corner→b. Both legs are axis-aligned (corner shares a's axis
// with one and b's with the other), so each end meets its element at
// 90°.
function lPath(a: Point, corner: Point, b: Point, radius: number): string {
  const len1 = Math.hypot(corner.x - a.x, corner.y - a.y)
  const len2 = Math.hypot(b.x - corner.x, b.y - corner.y)
  const r = Math.min(radius, len1, len2)
  const u1 = { x: Math.sign(corner.x - a.x), y: Math.sign(corner.y - a.y) }
  const u2 = { x: Math.sign(b.x - corner.x), y: Math.sign(b.y - corner.y) }
  return [
    `M ${round(a.x)} ${round(a.y)}`,
    `L ${round(corner.x - u1.x * r)} ${round(corner.y - u1.y * r)}`,
    `Q ${round(corner.x)} ${round(corner.y)} ${round(corner.x + u2.x * r)} ${round(corner.y + u2.y * r)}`,
    `L ${round(b.x)} ${round(b.y)}`,
  ].join(' ')
}

// The standardized connector. Each end is a port (point + the side it
// leaves on). The path is straight segments only, perpendicular to
// both elements' edges (so it looks plugged-in, never grazing):
//   - perpendicular ports (e.g. a right edge → a bottom edge) → one
//     rounded L,
//   - parallel ports (left ↔ right, or top ↔ bottom) → a symmetric
//     rounded Z (collapses to a straight line when aligned).
// Corners use CONNECTOR_RADIUS (clamped per-leg). Length is whatever
// the box geometry implies — inherently dynamic.
export function connectorPath(
  from: Point,
  fromSide: Side,
  to: Point,
  toSide: Side,
  gap = LINE_GAP,
  radius = CONNECTOR_RADIUS,
): string {
  const nF = normal(fromSide)
  const nT = normal(toSide)
  const a = { x: from.x + nF.x * gap, y: from.y + nF.y * gap }
  const b = { x: to.x + nT.x * gap, y: to.y + nT.y * gap }
  const fromHoriz = nF.x !== 0
  const toHoriz = nT.x !== 0

  if (fromHoriz === toHoriz) {
    // Parallel ports → Z, or a straight line when already aligned.
    const vertical = !fromHoriz
    if (vertical && Math.abs(a.x - b.x) < 0.5) {
      return `M ${round(a.x)} ${round(a.y)} L ${round(b.x)} ${round(b.y)}`
    }
    if (!vertical && Math.abs(a.y - b.y) < 0.5) {
      return `M ${round(a.x)} ${round(a.y)} L ${round(b.x)} ${round(b.y)}`
    }
    return zPath(a, b, vertical, radius)
  }

  // Perpendicular ports → one rounded L. The corner sits on the
  // from-leg's axis and the to-leg's axis.
  const corner = fromHoriz ? { x: b.x, y: a.y } : { x: a.x, y: b.y }
  return lPath(a, corner, b, radius)
}

// Where the connector actually ends (inset off `to` along its side
// normal) and the direction it's travelling there — for an arrowhead.
export function connectorEnd(
  to: Point,
  toSide: Side,
  gap = LINE_GAP,
): { readonly point: Point; readonly angle: number } {
  const n = normal(toSide)
  return {
    point: { x: to.x + n.x * gap, y: to.y + n.y * gap },
    angle: (Math.atan2(-n.y, -n.x) * 180) / Math.PI,
  }
}
