// Light / Dark / Auto, as a native radio group styled as a segmented control: arrow keys move
// the choice, the legend names the group and a screen reader announces the checked option.
// Auto (follow the OS) is the default. Behaviour: ./theme.ts.
//
// Two wirings, one markup (ThemeSwitchView):
// - `ThemeSwitch` — the SPA's live React control.
// - `StaticThemeSwitch` — the static support pages' copy, rendered to HTML at build time
//   (support-pages/generate.ts) and never hydrated: `hidden` until the inline
//   THEME_SWITCH_SCRIPT (support-pages/themeSwitch.ts) wires it by its `id` and input `name`,
//   so with JavaScript off it is absent rather than inert and the page follows the OS.
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { THEME_CHOICES, type ThemeChoice } from '@tenshu/web-tokens/theme'
import { applyThemeChoice, readThemeChoice } from './theme'

export function ThemeSwitch() {
  const name = useId()
  const [choice, setChoice] = useState<ThemeChoice>(() => readThemeChoice())
  return (
    <ThemeSwitchView
      name={name}
      choice={choice}
      onChoose={(value) => {
        setChoice(value)
        applyThemeChoice(value)
      }}
    />
  )
}

/** The id and input name THEME_SWITCH_SCRIPT looks the static switch up by. */
export const STATIC_THEME_SWITCH_ID = 'theme-switch'
export const STATIC_THEME_SWITCH_NAME = 'theme'

export function StaticThemeSwitch() {
  return <ThemeSwitchView id={STATIC_THEME_SWITCH_ID} name={STATIC_THEME_SWITCH_NAME} choice="auto" hidden />
}

function ThemeSwitchView({
  id,
  name,
  choice,
  onChoose,
  hidden,
}: {
  readonly id?: string
  readonly name: string
  readonly choice: ThemeChoice
  /** Absent: uncontrolled inputs (`defaultChecked`) for the static, script-wired copy. */
  readonly onChoose?: (choice: ThemeChoice) => void
  readonly hidden?: boolean
}) {
  const { t } = useTranslation('landing')
  return (
    <fieldset id={id} hidden={hidden} className="m-0 flex items-center gap-2 border-0 p-0">
      <legend className="float-left mr-2 p-0 text-text-2">{t('footer.theme.label')}</legend>
      <div className="inline-flex rounded-pill border border-line bg-surface p-0.5">
        {THEME_CHOICES.map((value) => (
          <label key={value} className="relative">
            <input
              type="radio"
              name={name}
              value={value}
              {...(onChoose
                ? { checked: choice === value, onChange: () => onChoose(value) }
                : { defaultChecked: choice === value })}
              className="peer sr-only"
            />
            <span className="block cursor-pointer rounded-pill px-3 py-1 text-text-2 peer-checked:bg-primary peer-checked:text-on-primary peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-primary hover:text-text peer-checked:hover:text-on-primary">
              {t(`footer.theme.${value}`)}
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  )
}
