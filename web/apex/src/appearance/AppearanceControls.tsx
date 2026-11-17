// Light / dark / auto and the language, for this browser (`appearance.ts`), then the background
// colour, for the realm (`BackgroundPicker`): the overview's edit bar
// and Settings draw this one component. The mode is a native radio group styled as a segmented
// control (arrow keys move it); the language a native <select>, each language in its own name.
import { useId, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { THEME_CHOICES } from '@tenshu/web-tokens/theme'
import { SITE_LANGUAGES } from '../landing/languages'
import { setLanguage, setMode, useAppearance, type LanguageChoice } from './appearance'
import { BackgroundPicker } from './BackgroundPicker'

export function AppearanceControls({ children }: { readonly children?: ReactNode }) {
  const { t } = useTranslation('control')
  const { mode, language } = useAppearance()
  const name = useId()
  const languageId = useId()
  return (
    <div className="flex flex-wrap items-center gap-x-5 gap-y-3">
      <fieldset className="m-0 flex items-center gap-2 border-0 p-0">
        <legend className="float-left mr-1 p-0 text-sm text-text-2">{t('appearance.mode')}</legend>
        <div className="inline-flex rounded-pill border border-glass-line bg-glass p-0.5 shadow-glass backdrop-blur-xl">
          {THEME_CHOICES.map(value => (
            <label key={value} className="relative">
              <input
                type="radio"
                name={name}
                value={value}
                checked={mode === value}
                onChange={() => setMode(value)}
                className="peer sr-only"
              />
              <span className="block cursor-pointer rounded-pill px-3 py-1 text-sm text-text-2 hover:text-text peer-checked:bg-primary peer-checked:text-on-primary peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-primary">
                {t(`appearance.${value}`)}
              </span>
            </label>
          ))}
        </div>
      </fieldset>
      <span className="flex items-center gap-2">
        <label htmlFor={languageId} className="text-sm text-text-2">
          {t('appearance.language')}
        </label>
        <select
          id={languageId}
          value={language}
          onChange={e => setLanguage(e.target.value as LanguageChoice)}
          className="h-8 rounded-pill border border-glass-line bg-glass px-3 text-sm text-text shadow-glass backdrop-blur-xl focus:outline-none focus-visible:ring-2 focus-visible:ring-line-strong"
        >
          <option value="auto">{t('appearance.languageAuto')}</option>
          {SITE_LANGUAGES.map(l => (
            <option key={l.lang} value={l.lang} lang={l.lang}>
              {l.name}
            </option>
          ))}
        </select>
      </span>
      <BackgroundPicker />
      {children}
    </div>
  )
}
