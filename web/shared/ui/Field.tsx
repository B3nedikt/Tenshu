import { forwardRef, type InputHTMLAttributes, type ReactNode } from 'react'
import { cx } from './cx'

// Token-wrapped text input. Standalone `Input` for bespoke layouts; `Field`
// pairs it with a label and an optional error line.
export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className, ...rest }, ref) {
    return (
      <input
        ref={ref}
        className={cx(
          'w-full rounded-lg border border-line px-3 py-1.5 text-sm text-text',
          'outline-none focus:border-line-strong',
          className,
        )}
        {...rest}
      />
    )
  },
)

export interface FieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: ReactNode
  error?: string | null
}

export function Field({ label, error, className, ...inputProps }: FieldProps) {
  return (
    <label className="block text-sm">
      <span className="text-text-2">{label}</span>
      <Input className={cx('mt-1', className)} {...inputProps} />
      {error && <span className="mt-1 block text-sm text-danger">{error}</span>}
    </label>
  )
}
