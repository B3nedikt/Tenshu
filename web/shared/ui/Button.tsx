import { forwardRef, type ButtonHTMLAttributes } from 'react'
import { cx } from './cx'

// Token-wrapped button. Variants/sizes are distilled from the patterns Drive
// already used (see docs/proposals/web-design-system.md Phase 3); every colour
// is a semantic token so it is dark-mode correct with no `dark:` variant.
// `className` is for additive layout only (e.g. `w-full`, `shrink-0`).
export type ButtonVariant = 'primary' | 'outline' | 'ghost' | 'danger' | 'danger-ghost'
export type ButtonSize = 'sm' | 'md' | 'lg'

const VARIANTS: Record<ButtonVariant, string> = {
  primary: 'bg-primary text-on-primary hover:bg-primary/90',
  outline: 'border border-line text-text hover:bg-surface-3',
  ghost: 'text-text-2 hover:bg-surface-3',
  danger: 'bg-danger text-white hover:bg-danger/90',
  'danger-ghost': 'text-danger hover:bg-danger/10',
}

const SIZES: Record<ButtonSize, string> = {
  sm: 'px-2.5 py-1 text-sm',
  md: 'px-3 py-1.5 text-sm',
  lg: 'px-4 py-2 text-sm',
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: ButtonSize
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'primary', size = 'md', type = 'button', className, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      className={cx(
        'rounded-lg font-medium transition-colors disabled:pointer-events-none disabled:opacity-50',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-line-strong',
        VARIANTS[variant],
        SIZES[size],
        className,
      )}
      {...rest}
    />
  )
})
