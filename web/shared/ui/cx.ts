// Tiny class-name joiner — drops falsy parts and joins with spaces. The shadcn
// `cn` (clsx + tailwind-merge) is deliberately NOT pulled in here: callers pass
// only *additive* layout utilities via `className` (e.g. `w-full`, `shrink-0`),
// never conflicting color/spacing that would need merge-resolution. Keeps the
// shared layer dependency-free. Promote to clsx+tailwind-merge if real conflicts
// appear (see docs/proposals/web-design-system.md Phase 5).
export function cx(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ')
}
