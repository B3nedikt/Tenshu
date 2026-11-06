// tokens/motion.ts — Duration + easing.
//
// Quiet vocabulary: no bouncing, no spring overshoot. Always respect
// prefers-reduced-motion at the consumer side.
export const duration = {
  xs: 80,
  s:  140,
  m:  220,
  l:  360,
  xl: 600,
} as const;

// CSS cubic-bezier strings. M3-style four-token set.
//   standard   — gentle balanced ease for state changes (hover, focus).
//   enter      — fast in, slow settle. Use for surfaces appearing (toast, modal in).
//   exit       — slow start, fast accelerate. Use for surfaces leaving.
//   emphasised — pronounced decelerate (M3 emphasised-decelerate). Use for
//                page transitions and any motion that should *feel* deliberate
//                rather than utility.
export const easing = {
  standard:    'cubic-bezier(0.2, 0, 0, 1)',
  enter:       'cubic-bezier(0, 0, 0, 1)',
  exit:        'cubic-bezier(0.3, 0, 1, 1)',
  emphasised:  'cubic-bezier(0.05, 0.7, 0.1, 1)',
} as const;

export const guidance = {
  stateChange: { duration: 'xs-s', easing: 'standard' },  // hover, focus
  surface:     { duration: 'm',    easing: 'enter|exit' }, // toast, modal
  page:        { duration: 'l',    easing: 'emphasised' }, // route transitions
} as const;
