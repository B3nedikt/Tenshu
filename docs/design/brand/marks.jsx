// marks.jsx — Tenshu cat-mark set v6.
//
// **Mirror of `marks/Outline.tsx` + `marks/Solid.tsx`**. This file is the
// runtime sidecar `foundations.html` loads via <script>; the typed React
// modules under `marks/` are the codify-target source-of-truth that
// `web/apex/src/components/marks/` is generated from. The shapes (paths,
// eye positions, whisker/mouth proportions) MUST match across all four
// files. When you change geometry here, change it in the typed modules
// too — and regenerate `marks/mark.svg` from `marks/Outline.tsx`.
//
// Two marks now (Hanko retired for v0.1; triangle ears reinstated in
// v7 — see ADR 0004, which supersedes ADR 0003):
//   A · Outline — primary. Used wherever possible.
//   B · Solid   — same cat, body filled. Whiskers are outlined (drawn in
//                 currentColor on top of the filled head, so they extend
//                 outward from the cheek and read like the Outline mark).

const Mark = {};

// Shared geometry ──────────────────────────────────────────────────────
// Tall half-ellipse head (taller than wide — reads as a high rounded
// shape rather than a squat half-circle). Dome top at y=28.
const HEAD_PATH = "M 14 50 A 18 22 0 0 1 50 50 Z";
// Triangle "pagoda-eave" ears, reinstated in v7 (ADR 0004)
const EAR_L = "M 28.8 24.2 L 18 14.1 L 15.1 31.7 C 15.1 33.9 18.4 25.9 28.8 24.2 Z";
const EAR_R = "M 35.1 23.9 C 38.9 20.3 42.6 17.1 45.8 13.8 L 48.7 31.4 C 48.7 33.6 45.4 25.5 35.1 23.9 Z";
// Mouth lowered into the cheek band
const MOUTH_PATH = "M 27 43 Q 29 46 31 44 Q 32 43 33 44 Q 35 46 37 43";
// Whiskers — root at outer cheek (well clear of the eyes) and curve outward
const WHISK_L = [
  "M 20 39 Q 13 37 4 35",
  "M 20 41 Q 12 41 3 41",
  "M 20 43 Q 13 45 4 48",
];
const WHISK_R = [
  "M 44 39 Q 51 37 60 35",
  "M 44 41 Q 52 41 61 41",
  "M 44 43 Q 51 45 60 48",
];
const EYE_L = { cx: 26.5, cy: 36 }, EYE_R = { cx: 37.5, cy: 36 };

// ── Mark A — Outline (primary) ────────────────────────────────────────
Mark.Outline = function MarkOutline({ size = 64, stroke = 2.5 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 64 64" role="img"
         aria-label="Tenshu mark — sketch cat">
      <g fill="none" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round"
         vectorEffect="non-scaling-stroke">
        {/* Head as a single fill-surface background so whiskers visually
            tuck under at the rim */}
        <path d={HEAD_PATH} strokeWidth={stroke} fill="var(--surface, #fff)" />
        <path d={EAR_L} strokeWidth={stroke} fill="var(--surface, #fff)" />
        <path d={EAR_R} strokeWidth={stroke} fill="var(--surface, #fff)" />
        {/* Whiskers — drawn AFTER head so the inner ends sit on top, clearly
            rooted at the cheek */}
        {WHISK_L.map((d, i) => <path key={`l${i}`} d={d} strokeWidth={stroke * 0.55} />)}
        {WHISK_R.map((d, i) => <path key={`r${i}`} d={d} strokeWidth={stroke * 0.55} />)}
        {/* Mouth */}
        <path d={MOUTH_PATH} strokeWidth={stroke * 0.85} />
      </g>
      <g fill="currentColor">
        <circle {...EYE_L} r="2.1" />
        <circle {...EYE_R} r="2.1" />
        <circle cx={EYE_L.cx + 0.7} cy={EYE_L.cy - 0.7} r="0.55" fill="var(--surface,#fff)" />
        <circle cx={EYE_R.cx + 0.7} cy={EYE_R.cy - 0.7} r="0.55" fill="var(--surface,#fff)" />
      </g>
    </svg>
  );
};

// ── Mark B — Same cat, body filled. Whiskers are outlined: drawn in
// currentColor so they read like the Outline mark, extending outward from
// the cheek (the inner span sits on top of the filled head and is invisible
// against the same fill, the outer span emerges into surface). ─────────
Mark.Solid = function MarkSolid({ size = 64, stroke = 2.5 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 64 64" role="img"
         aria-label="Tenshu mark — filled cat">
      {/* Filled body */}
      <g fill="currentColor">
        <path d={HEAD_PATH} />
        <path d={EAR_L} />
        <path d={EAR_R} />
      </g>
      {/* Whiskers in currentColor — extend outward from cheek; inner span
          is invisible inside the filled head, outer span reads as outline. */}
      <g fill="none" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round">
        {WHISK_L.map((d, i) => <path key={`l${i}`} d={d} strokeWidth={stroke * 0.55} />)}
        {WHISK_R.map((d, i) => <path key={`r${i}`} d={d} strokeWidth={stroke * 0.55} />)}
      </g>
      {/* Mouth in surface color so it's visible inside the filled head */}
      <g fill="none" stroke="var(--surface, #fff)" strokeLinecap="round" strokeLinejoin="round">
        <path d={MOUTH_PATH} strokeWidth={stroke * 0.65} />
      </g>
      <g fill="var(--surface, #fff)">
        <circle {...EYE_L} r="2.1" />
        <circle {...EYE_R} r="2.1" />
      </g>
    </svg>
  );
};

window.TenshuMarks = Mark;
