# Landing Illustrations

**Status:** Live — four code-drawn illustrations render on the apex landing (`/`).
**Scope:** The single-stroke line-art illustrations in
`web/apex/src/landing/illustrations/`, all composed from the shared marks in
`illustrations/marks/` (`index.tsx` for the device/object marks, `geometry.ts` for
connector anchoring). This doc is the rule set they share and the one thing each must
communicate; each component's header comment carries its composition, and the block copy
lives in [`web-about-tenshu-public.md`](../proposals/web-about-tenshu-public.md).

The components are the final art. This doc was once a brief for generated (Recraft)
replacements and for illustrations M4–M7; the replacements were never made, and the other
slots went to the app carousel's real screenshots (Block 7) or to text-only blocks, so
the brief and the parked components (`EncryptionArt`, `DevicesArt`, `TodayArt`) were removed.

---

## What renders

| ID | Component | Block | Must communicate |
|----|-----------|-------|------------------|
| **M1** | `M1Hero` | Block 1 (hero) | *Calm control*: your devices around a relay that only ever holds your data locked. |
| **M2** | `ProblemArt` | Block 2 ("How big tech works today.") | Your photo and document sit **readable** in someone else's cloud. The readability is the threat — no eye or watcher glyph. |
| **M3** | `AnswerArt` | Block 3 ("How it should be.") | The **device boundary**, and that Control *is* the proxy: Drive → Control on-device, and only Control reaches the locked relay. Same world as M2, different rules. |
| — | `RealmArt` | Block 4 ("Your Realm") | The realm ring: the same item on every kind of device, the cat at the centre. Deliberately no relay (M3 carries that beat) — see [`web-about-tenshu-public.md`](../proposals/web-about-tenshu-public.md) §6. |

Blocks 5, 6 and the rest are text-only or use the carousel. Block 8 shows `ScreenshotPlaceholder`
tiles (`Placeholders.tsx`) where hardware photos are still owed. Any new visual for them (e.g.
the "relay's-eye view" parked in `web-about-tenshu-public.md` §7) follows the contract below.

---

## Style contract

Every landing illustration obeys the same vocabulary so the page reads as one system:

- **Single-weight line art.** All strokes the same weight, rounded caps and joins. No
  fills (except tiny solid arrowheads and the embedded brand mark), no shading, no
  gradients, no glow, no shadows, no 3D, no perspective. Everything on one plane.
- **One color.** Stroke is `currentColor`, set once by `Canvas` — it themes with the
  block. Secondary detail dims via opacity groups, never a second hue.
  One documented exception: M3 (`AnswerArt`) draws **Drive and Control with their real
  launcher icons** (`public/icons/apps`, light + dark copies switched in `index.css`), so the
  reader recognises them as the apps — legibility over a single hue.
- **Connectors are standardized.** Every line between elements goes through one
  `Connector` component, `currentColor` at **0.4 opacity**. Three rules:
  - **Port anchoring, not coordinates.** A connector end is `{ box, side, index?, count? }`
    — never a hand-picked point. The anchor is the **midpoint of that side** of the
    element's bounding box. Each illustration declares its element **boxes once** as the
    single source of truth for both placement *and* anchoring, so anchors move with the
    element. Several connectors on one side **fan out** by `PORT_SPREAD` instead of
    stacking. The shared **`LINE_GAP` inset**, along the side's outward normal, trims both
    ends so a line never touches the element.
  - **Straight segments, rounded turns — no bezier bow.** Axis-aligned legs that meet both
    elements **perpendicular to the edge**: a rounded **L** for perpendicular ports, a
    symmetric rounded **Z** for parallel ones, a straight line when aligned. Corners use
    `CONNECTOR_RADIUS`, in the device-frame `rx` family.
  - **Line style is the only meaning-carrier.** **Solid = protected** (M1 sync, M3 hops);
    **dashed (`exposed`) = unprotected** (M2's leaks). Any other concept is carried by a
    glyph, never by overloading the line. Arrowheads only when direction must be shown.

  Caveat: the **cloud** is a blob, so its bounding box is larger than the drawn shape;
  `LINE_GAP` covers it.
- **Device frames are standardized** (`Phone`, `Monitor`, `Laptop`, `Tablet`). Two modes:
  **abstract** (no children — three faint screen lines, the generic "this is a device"
  read) and **inhabited** (given children — real content inside the same frame). Frame
  chrome is fixed per device and never varies per illustration. Marks scale stroke with
  their transform, so an illustration that needs an oversized frame (M3) draws it inline
  at full scale in the identical look rather than scaling the mark.
- **16:9 against a 480×270 viewBox** by default; `Canvas` takes `viewBox` /
  `aspectClassName` for the exceptions (M1's wider desktop frame, `RealmArt`'s square).
- **No text, no logos, no faces, no people** inside an illustration (M3's real app icons
  above aside). Two documented
  exceptions: the `Relay` mark's faint hex-like *ciphertext* texture (it reads as "the relay
  only ever holds encrypted data"), and `RealmArt`'s centre, which embeds the codified cat
  (`MarkSolid`) verbatim — never a redrawn cat ([`brand/brand-agent.md`](brand/brand-agent.md)).
- **Static first.** Each composition is complete and legible with no motion; any animation
  is CSS (`index.css`), transform/opacity only, and opt-in under
  `prefers-reduced-motion: no-preference`.
