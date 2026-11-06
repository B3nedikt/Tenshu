// marks/Solid.tsx — Tenshu cat mark, secondary (filled body, outlined whiskers).
// Reserved for app icons, favicons, and reversed chrome where the outline
// loses presence at small sizes. Outline is primary; reach for this only when
// it reads better. See ../brand-agent.md for usage rules.
import * as React from 'react';

const HEAD_PATH = "M 14 50 A 18 22 0 0 1 50 50 Z";
// Triangle "pagoda-eave" ears, reinstated in v7 (see ADR 0004).
const EAR_L = "M 28.8 24.2 L 18 14.1 L 15.1 31.7 C 15.1 33.9 18.4 25.9 28.8 24.2 Z";
const EAR_R = "M 35.1 23.9 C 38.9 20.3 42.6 17.1 45.8 13.8 L 48.7 31.4 C 48.7 33.6 45.4 25.5 35.1 23.9 Z";
const MOUTH_PATH = "M 27 43 Q 29 46 31 44 Q 32 43 33 44 Q 35 46 37 43";
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
const EYE_L = { cx: 26.5, cy: 36 };
const EYE_R = { cx: 37.5, cy: 36 };

export interface MarkProps {
  size?: number;
  /**
   * Stroke width for whisker + mouth. The head fill ignores this. Mirrors
   * Outline.tsx's `stroke` prop so consumers can scale both marks the same way;
   * whiskers default to `stroke * 0.55` (matches Outline's whisker ratio) and
   * mouth to `stroke * 0.65` (a touch heavier than Outline's because it sits
   * over the filled head).
   */
  stroke?: number;
  className?: string;
  'aria-label'?: string;
}

export function MarkSolid({
  size = 64,
  stroke = 2.5,
  className,
  ...rest
}: MarkProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 64 64"
      role="img"
      aria-label={rest['aria-label'] ?? 'Tenshu'}
      className={className}
    >
      <g fill="currentColor">
        <path d={HEAD_PATH} />
        <path d={EAR_L} />
        <path d={EAR_R} />
      </g>
      {/* Whiskers in currentColor — extend outward from cheek. */}
      <g fill="none" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round">
        {WHISK_L.map((d, i) => (
          <path key={`l${i}`} d={d} strokeWidth={stroke * 0.55} />
        ))}
        {WHISK_R.map((d, i) => (
          <path key={`r${i}`} d={d} strokeWidth={stroke * 0.55} />
        ))}
      </g>
      {/* Mouth in surface so it's visible inside the filled head. */}
      <g fill="none" stroke="var(--surface, #fff)" strokeLinecap="round" strokeLinejoin="round">
        <path d={MOUTH_PATH} strokeWidth={stroke * 0.65} />
      </g>
      <g fill="var(--surface, #fff)">
        <circle cx={EYE_L.cx} cy={EYE_L.cy} r="2.1" />
        <circle cx={EYE_R.cx} cy={EYE_R.cy} r="2.1" />
      </g>
    </svg>
  );
}
