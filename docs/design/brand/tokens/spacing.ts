// tokens/spacing.ts — Classical 4-baseline spacing scale.
// Multiplied by --space-scale (1 today; reserved for a future density toggle).
//
// Step pattern: paired equal-add steps then doubling.
//   s1→s2 +4   ┐
//   s2→s3 +4   ┘ baseline doubling pair
//   s3→s4 +4
//   s4→s5 +8   ┐
//   s5→s6 +8   ┘ small doubling pair
//   s6→s7 +16  ┐
//   s7→s8 +16  ┘ medium doubling pair
//   s8→s9 +32      large jump for section/page-level rhythm
//
// The Fibonacci-ish curve gives designers reliably-distinguishable gaps at
// every scale: small siblings (4/8) read as adjacent, medium siblings (24/32)
// as related-but-distinct, and large siblings (48/64, 64/96) as section
// breaks. Grid gutters use s5; component padding clusters around s3-s5;
// section spacing sits at s7-s9.
export const spacing = {
  s1:  4,
  s2:  8,
  s3:  12,
  s4:  16,
  s5:  24,
  s6:  32,
  s7:  48,
  s8:  64,
  s9:  96,
} as const;
