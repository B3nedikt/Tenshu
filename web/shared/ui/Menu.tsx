import * as DropdownMenu from '@radix-ui/react-dropdown-menu'
import { type ReactNode } from 'react'
import { cx } from './cx'

// Headless dropdown menu on Radix. Replaces Drive's hand-rolled portal `RowMenu`
// — Radix gives focus management, full keyboard nav (arrows / Home / End /
// typeahead / Escape), outside-click dismissal and collision-aware positioning
// for free, none of which the manual version had. `trigger` is rendered via
// `asChild`, so pass a real focusable element (a `<button>`).
export function Menu({
  trigger,
  children,
  align = 'end',
}: {
  trigger: ReactNode
  children: ReactNode
  align?: 'start' | 'center' | 'end'
}) {
  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>{trigger}</DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align={align}
          sideOffset={4}
          className={cx(
            'z-50 min-w-40 overflow-hidden rounded-lg border border-line bg-surface-2 py-1 shadow-lg',
            'focus:outline-none',
          )}
        >
          {children}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  )
}

export function MenuItem({
  children,
  onSelect,
  danger = false,
}: {
  children: ReactNode
  onSelect: () => void
  danger?: boolean
}) {
  return (
    <DropdownMenu.Item
      onSelect={onSelect}
      className={cx(
        'block w-full cursor-default px-3 py-1.5 text-left text-sm outline-none select-none',
        'data-[highlighted]:bg-surface-3',
        danger ? 'text-danger' : 'text-text',
      )}
    >
      {children}
    </DropdownMenu.Item>
  )
}
