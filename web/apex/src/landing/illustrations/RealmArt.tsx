// RealmArt — Block 4 "Your Realm" visual, the "realm ring".
// Copy + visual brief: docs/proposals/web-about-tenshu-public.md §6.
//
// The message here is deliberately narrow: *your stuff is the same on
// every device, kept in sync*. The relay / "encrypted on the server"
// beat is already carried by Block 3 (AnswerArt), so it is intentionally
// NOT repeated here — no relay node, no wire, no lock. Just the realm:
// four kinds of device (laptop, phone, desktop, tablet) in a ring, each
// *inhabited* with the same photo, with the Tenshu cat mark at the
// centre — the cat *is* the realm, the thing that ties the devices
// together (this is what ties the marketing to the brand; hence the
// real codified MarkSolid, not a redrawn cat).
//
// Built on the shared-marks Canvas so it lives in the same coordinate
// + stroke system as M1/Answer/Problem. The cat is the codified brand
// mark embedded verbatim (no geometry fork — see docs/design/brand/
// brand-agent.md): MarkSolid reads best at this small centre size, the
// same small-size call the favicon makes.
//
// The ring is not drawn as a solid circle (that read as a hard static
// line cutting through the devices). Instead it is *implied by motion*:
// a set of points orbits the ring centre and twinkles out of phase, so
// the ring is conveyed by travelling, blinking light. Animation (~6s
// family per §11) is transform/opacity only and gated behind
// prefers-reduced-motion in index.css — with reduced motion the points
// hold their positions (a faint dotted ring) and the frame is complete
// and legible on its own.
import { MarkSolid } from '../../components/marks/Solid'
import { Canvas, Image, Laptop, Monitor, Phone, Tablet } from './marks'

// Ring geometry — the single source of truth for device + dot
// placement. Devices sit on the ring at the four diagonals; the cat
// fills the centre.
const C = { x: 172, y: 135 }
const R = 84
const D = 0.58 // device mark scale — keeps the ring clear of the cat
const G = 0.5 // default photo-glyph scale

// Optical centring nudge for the cat only. The bottom devices (the
// monitor + its stand, and the tablet) are the largest, so the true
// geometric centre reads a touch low. Shift the cat up by this much —
// X and every bit of ring geometry are unchanged; this is purely the
// cat's vertical placement.
const CAT_DY = -12

// Device top-left corners: each mark is centred on its ring node
// (node = C ± R·sin45° on each axis), so x = node − scale·nominal/2.
const LAPTOP = { x: 77, y: 52 } // NW — nominal 124×84
const PHONE = { x: 219, y: 53 } // NE — nominal 40×78
const MONITOR = { x: 76, y: 163 } // SW — nominal 128×108
const TABLET = { x: 208, y: 164 } // SE — nominal 78×102

// Six points evenly around the ring (start at top). Evenly spaced, so
// their bounding box is centred on C — which lets the CSS rotation use
// `transform-box: fill-box; transform-origin: center` (no magic numbers
// in the stylesheet) to orbit exactly about the ring centre. Six (vs.
// the four device nodes) so the points sweep past the devices at
// varying phase rather than looking pinned to them.
const ORBIT_DOTS = Array.from({ length: 6 }, (_, k) => {
  const a = (-90 + k * 60) * (Math.PI / 180)
  return { x: C.x + R * Math.cos(a), y: C.y + R * Math.sin(a), k }
})

// A photo glyph (nominal 40×32) centred at (cx, cy) at scale `s`: the
// same readable item shown on every device. As a device child,
// (cx, cy) and `s` are in that device's local screen coords (the
// device's own scale then applies on top), so each screen gets a photo
// sized to it rather than three faint UI lines.
function Item({ cx, cy, s = G }: { readonly cx: number; readonly cy: number; readonly s?: number }) {
  return <Image x={cx - 20 * s} y={cy - 16 * s} scale={s} />
}

export function RealmArt() {
  return (
    <Canvas
      label="Four of your devices — laptop, phone, desktop, tablet — arranged in a ring around the Tenshu cat, each showing the same photo, kept in sync"
      // The composition is square (ring + devices); a square frame
      // tight to the content makes it render as tall as the copy
      // beside it on desktop, rather than a small left-shifted band in
      // a 16:9 box now that the relay is gone. Box ≈ content bbox + a
      // ~22u even margin all round.
      viewBox="54 26 222 222"
      aspectClassName="aspect-square"
    >
      {/* The ring, implied by motion: the whole group rotates about
          its own centre (= the ring centre, since the dots are evenly
          spaced) while each dot twinkles out of phase. Drawn before
          the devices so a dot reads as a sync pulse passing each one.
          Under reduced motion this is just a faint dotted ring. */}
      <g className="realm-orbit">
        {ORBIT_DOTS.map((d) => (
          <circle
            key={d.k}
            className="realm-pulse-dot"
            cx={d.x}
            cy={d.y}
            r="2.5"
            fill="currentColor"
            stroke="none"
            // Negative stagger so the dots twinkle out of phase around
            // the ring instead of all blinking together.
            style={{ animationDelay: `${-(d.k / ORBIT_DOTS.length) * 2.6}s` }}
          />
        ))}
      </g>

      {/* Four kinds of device on the ring, each *inhabited* with the
          same photo (replaces the faint screen lines, so the item
          isn't drawn over UI lines). Local screen-centre + per-device
          scale keeps the photo proportional to each screen. */}
      <Laptop x={LAPTOP.x} y={LAPTOP.y} scale={D}>
        <Item cx={62} cy={26} s={1.1} />
      </Laptop>
      <Phone x={PHONE.x} y={PHONE.y} scale={D}>
        <Item cx={20} cy={34} s={0.52} />
      </Phone>
      <Monitor x={MONITOR.x} y={MONITOR.y} scale={D}>
        <Item cx={64} cy={42} s={1.6} />
      </Monitor>
      <Tablet x={TABLET.x} y={TABLET.y} scale={D}>
        <Item cx={39} cy={40} s={0.96} />
      </Tablet>

      {/* The cat is the realm — centre of the ring. The codified
          brand mark embedded verbatim; decorative (the Canvas already
          carries the accessible label), so the nested role="img" is
          hidden from AT. */}
      <g className="realm-cat" aria-hidden="true">
        <g transform={`translate(${C.x - 30} ${C.y - 30 + CAT_DY})`}>
          <MarkSolid size={60} />
        </g>
      </g>
    </Canvas>
  )
}
