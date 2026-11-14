import * as RContextMenu from '@radix-ui/react-context-menu'
import { type ReactNode } from 'react'
import { cx } from './cx'

// A right-click menu on Radix ContextMenu (focus management, keyboard nav, Escape, collision-aware
// placement at the pointer). `children` is the area that opens it, rendered via `asChild`, so pass
// one element. The same `MenuEntry[]` can feed the `⋯` button's dropdown (`Menu`), so a row's two
// menus can never disagree.
export interface MenuEntry {
  readonly label: string
  readonly onSelect: () => void
  readonly danger?: boolean
  // Shown right-aligned, e.g. "⌘I". Display only — the keyboard handling lives with the list.
  readonly shortcut?: string
  // Draw a separator above this entry (groups, as in the native sheet).
  readonly groupStart?: boolean
}

export function ContextMenu({ items, children }: { items: readonly MenuEntry[]; children: ReactNode }) {
  return (
    <RContextMenu.Root>
      <RContextMenu.Trigger asChild>{children}</RContextMenu.Trigger>
      <RContextMenu.Portal>
        <RContextMenu.Content
          className={cx(
            'z-50 min-w-52 overflow-hidden rounded-xl border border-line bg-surface-2 p-1 shadow-xl',
            'focus:outline-none',
          )}
        >
          {items.map((item) => (
            <div key={item.label}>
              {item.groupStart && <RContextMenu.Separator className="my-1 h-px bg-line" />}
              <RContextMenu.Item
                onSelect={item.onSelect}
                className={cx(
                  'flex cursor-default items-center justify-between gap-6 rounded-lg px-3 py-1.5 text-sm outline-none select-none',
                  'data-[highlighted]:bg-surface-3',
                  item.danger ? 'text-danger' : 'text-text',
                )}
              >
                <span>{item.label}</span>
                {item.shortcut && <span className="text-xs text-text-3">{item.shortcut}</span>}
              </RContextMenu.Item>
            </div>
          ))}
        </RContextMenu.Content>
      </RContextMenu.Portal>
    </RContextMenu.Root>
  )
}
